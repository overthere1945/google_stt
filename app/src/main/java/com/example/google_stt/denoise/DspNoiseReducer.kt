/*
 * 파일명: denoise/DspNoiseReducer.kt
 * 목적 및 기능:
 * - 모델 파일이 전혀 필요 없는 전통 DSP 노이즈 저감 3종을 순수 Kotlin 으로 구현한다.
 *     (1) Spectral Subtraction — Berouti 1979 (과감산 + 스펙트럼 바닥)
 *     (2) Wiener decision-directed — Scalart & Vieira Filho 1996
 *     (3) MMSE-STSA — Ephraim & Malah 1984
 * - 사용자가 첨부한 조사 문서의 10~12번 항목에 해당한다.
 * - ONNX 모델을 못 받은 상태에서도 "오늘 바로" A/B 비교를 시작할 수 있게 하는 것이 목적이다.
 *
 * 공통 STFT 규격: n_fft 512 (32 ms), hop 256 (16 ms), sqrt-Hann, center 없음.
 * 잡음 추정: 파일 전체를 한 번 훑어 "가장 조용한 하위 N% 프레임"의 평균 PSD 를 잡음으로 본다.
 *   오프라인 파일 처리이므로 앞부분 몇 ms 만 보는 고전 방식보다 정확하고,
 *   말로 바로 시작하는 파일에서도 음성을 잡음으로 오인하지 않는다.
 *
 * 근거 문헌:
 * - Y. Ephraim, D. Malah, "Speech Enhancement Using a MMSE Short-Time Spectral Amplitude
 *   Estimator", IEEE Trans. ASSP-32(6):1109-1121, 1984.  (식 7, 8, 51)
 * - M. Berouti, R. Schwartz, J. Makhoul, "Enhancement of speech corrupted by acoustic noise",
 *   ICASSP 1979, pp.208-211.  (과감산 계수 alpha, 스펙트럼 바닥 beta)
 * - P. Scalart, J. Vieira Filho, "Speech enhancement based on a priori signal to noise
 *   estimation", ICASSP 1996.  (decision-directed Wiener)
 * - 참조 구현: VOICEBOX v_ssubmmse.m (Mike Brookes, Imperial College)
 *
 * add-hyungchul-20260826-1100
 */
package com.example.google_stt.denoise

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 목적: 지수 스케일 변형 Bessel 함수 I0/I1.
 * 비고: exp(-u)*I0(u) 를 직접 계산한다. exp(-u) 와 I0(u) 를 따로 구해 곱하면
 *       u 가 커질 때 I0(u) 가 먼저 overflow 해서 NaN 이 된다.
 * 근거: Abramowitz & Stegun, Handbook of Mathematical Functions, 9.8.1~9.8.4 다항 근사.
 */
internal object Bessel {

    /** exp(-x) * I0(x), x >= 0 */
    fun i0e(x: Double): Double {
        val ax = abs(x)
        return if (ax < 3.75) {
            val t = (ax / 3.75).let { it * it }
            val i0 = 1.0 + t * (3.5156229 + t * (3.0899424 + t * (1.2067492 +
                t * (0.2659732 + t * (0.0360768 + t * 0.0045813)))))
            i0 * exp(-ax)
        } else {
            val t = 3.75 / ax
            val p = 0.39894228 + t * (0.01328592 + t * (0.00225319 + t * (-0.00157565 +
                t * (0.00916281 + t * (-0.02057706 + t * (0.02635537 +
                    t * (-0.01647633 + t * 0.00392377)))))))
            p / sqrt(ax)          // 이미 exp(-x) 가 반영된 근사식이다
        }
    }

    /** exp(-x) * I1(x), x >= 0 */
    fun i1e(x: Double): Double {
        val ax = abs(x)
        return if (ax < 3.75) {
            val t = (ax / 3.75).let { it * it }
            val i1 = ax * (0.5 + t * (0.87890594 + t * (0.51498869 + t * (0.15084934 +
                t * (0.02658733 + t * (0.00301532 + t * 0.00032411))))))
            i1 * exp(-ax)
        } else {
            val t = 3.75 / ax
            val p = 0.39894228 + t * (-0.03988024 + t * (-0.00362018 + t * (0.00163801 +
                t * (-0.01031555 + t * (0.02282967 + t * (-0.02895312 +
                    t * (0.01787654 + t * (-0.00420059))))))))
            p / sqrt(ax)
        }
    }
}

/**
 * 목적: "잡음 PSD 를 추정하고 bin 마다 이득(gain)을 곱한다" 는 구조를 공유하는 DSP 3종의 공통 부모.
 * 비고:
 * - 잡음 추정: 파일 앞부분 noiseInitMs 를 잡음 구간으로 보고 평균을 낸 뒤,
 *   이후에도 "말이 없어 보이는 프레임"에서만 천천히 갱신한다(비정상 소음 대응).
 * - 초기 추정 구간에서는 이득을 1 로 두어 신호를 건드리지 않는다.
 */
abstract class GainNoiseReducer : SpectralNoiseReducer(
    // ★ STFT 규격을 512 / 256 (32 ms / 16 ms) 으로 잡은 이유
    //   - 32 ms / 16 ms 는 음성 향상에서 표준적인 구성이다(NSNet2 논문도 이 값을 쓴다고 기술).
    //   - 512 는 2의 거듭제곱이라 AudioDsp 의 radix-2 경로를 탄다.
    //     320 을 쓰면 Bluestein 경로가 되어 FFT 비용이 약 3배가 되는데,
    //     DSP 알고리즘은 추론이 없어서 FFT 가 전체 시간을 지배한다. 그래서 512 를 골랐다.
    nFft = 512,
    hop = 256,
    windowType = WindowType.HANN_SQRT,
    center = false,
) {
    /** bin 별 잡음 PSD 추정치 */
    protected val noisePsd = DoubleArray(bins)

    /** 이번 파일에서 잡음 추정에 실제로 쓰인 프레임 수 */
    private var noiseFrames = 0

    /** 이번 파일에서 잡음 갱신이 몇 번 일어났는지(note 용) */
    private var noiseUpdates = 0

    /** 최소 이득(선형). config.minGainDb 로부터 계산한다. */
    protected var minGain = 0.0
        private set

    override fun beginFile(frameCount: Int, config: NoiseReduceConfig) {
        java.util.Arrays.fill(noisePsd, 0.0)
        noiseFrames = 0
        noiseUpdates = 0
        minGain = 10.0.pow(config.minGainDb / 20.0)
        onBeginFile(frameCount, config)
    }

    /**
     * 목적: 본 루프 전에 파일 전체를 한 번 훑어 잡음 스펙트럼을 추정한다.
     *       "가장 조용한 하위 N% 프레임"의 평균 PSD 를 잡음으로 본다.
     * 입력: padded, frameCount, config
     * 리턴: 단계별 소요 나노초(전부 STFT 비용으로 계상한다)
     * 비고: 프레임별 PSD 를 FloatArray 로 들고 있는다.
     *       90초 파일이면 5625 프레임 x 257 bin x 4 byte = 약 5.8 MB 로 감당 가능하다.
     */
    override fun prePass(
        padded: DoubleArray,
        frameCount: Int,
        config: NoiseReduceConfig,
    ): PrePassCost {
        val t0 = System.nanoTime()
        if (frameCount <= 0) return PrePassCost(stftNs = System.nanoTime() - t0)

        val re = DoubleArray(bins)
        val im = DoubleArray(bins)
        val psd = FloatArray(frameCount * bins)      // 프레임별 파워 스펙트럼
        val energy = DoubleArray(frameCount)         // 프레임별 총 파워(조용한 프레임 고르기용)

        for (f in 0 until frameCount) {
            stft.analyze(padded, f, re, im)
            var sum = 0.0
            val base = f * bins
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                psd[base + k] = p.toFloat()
                sum += p
            }
            energy[f] = sum
        }

        // 하위 N% 지점의 에너지를 문턱값으로 삼는다(정렬본을 따로 만들어 원본 순서를 유지).
        val sorted = energy.copyOf()
        java.util.Arrays.sort(sorted)
        val take = ((frameCount * config.noiseQuantilePct) / 100).coerceIn(1, frameCount)
        val threshold = sorted[take - 1]

        var used = 0
        for (f in 0 until frameCount) {
            if (energy[f] > threshold) continue      // 조용한 프레임만 사용
            val base = f * bins
            for (k in 0 until bins) noisePsd[k] += psd[base + k].toDouble()
            used++
            if (used >= take) break
        }
        if (used > 0) {
            val inv = 1.0 / used
            for (k in 0 until bins) noisePsd[k] *= inv
        } else {
            // 이론상 도달하지 않지만, 전부 같은 값이면 전체 평균으로 대체한다.
            for (f in 0 until frameCount) {
                val base = f * bins
                for (k in 0 until bins) noisePsd[k] += psd[base + k].toDouble()
            }
            val inv = 1.0 / frameCount
            for (k in 0 until bins) noisePsd[k] *= inv
            used = frameCount
        }
        noiseFrames = used
        // 이 패스는 전부 STFT 비용이다(추론이 없다). CSV 의 ns_stft_ms 에 합산된다.
        return PrePassCost(stftNs = System.nanoTime() - t0)
    }

    /** 하위 클래스가 추가로 초기화할 것이 있으면 여기서 한다. */
    protected open fun onBeginFile(frameCount: Int, config: NoiseReduceConfig) = Unit

    /**
     * 목적: bin 하나의 이득을 계산한다.
     * 입력: k(bin 번호), power(|Y|^2), noise(잡음 PSD), config
     * 리턴: 0~1 사이 이득
     */
    protected abstract fun gainFor(k: Int, power: Double, noise: Double, config: NoiseReduceConfig): Double

    final override fun processFrame(
        re: DoubleArray,
        im: DoubleArray,
        frameIndex: Int,
        config: NoiseReduceConfig,
    ) {
        // ── 1) 이번 프레임 전체 파워와 현재 잡음 추정 총량 ──
        var framePower = 0.0
        var frameNoise = 0.0
        for (k in 0 until bins) {
            framePower += re[k] * re[k] + im[k] * im[k]
            frameNoise += noisePsd[k]
        }

        // ── 2) 말이 없어 보이는 프레임이면 잡음 추정을 천천히 갱신한다 ──
        //   기준: 프레임 파워가 잡음 추정치의 2배(=3 dB)를 넘지 않으면 잡음으로 본다.
        //   카페/도로처럼 잡음이 서서히 변하는 상황을 따라가기 위한 최소 장치다.
        if (frameNoise > 0.0 && framePower < 2.0 * frameNoise) {
            for (k in 0 until bins) {
                val p = re[k] * re[k] + im[k] * im[k]
                noisePsd[k] = NOISE_SMOOTH * noisePsd[k] + (1.0 - NOISE_SMOOTH) * p
            }
            noiseUpdates++
        }

        // ── 3) bin 마다 이득을 계산해 곱한다(위상은 그대로 둔다) ──
        for (k in 0 until bins) {
            val power = re[k] * re[k] + im[k] * im[k]
            val noise = max(noisePsd[k], NOISE_FLOOR)
            var g = gainFor(k, power, noise, config)
            if (g < minGain) g = minGain            // 과도한 제거를 막는 하한
            if (g > 1.0) g = 1.0                    // 증폭은 하지 않는다
            re[k] *= g
            im[k] *= g
        }
    }

    override fun fileNote(): String = "noiseFrames=$noiseFrames, noiseUpdates=$noiseUpdates"

    override fun close() = Unit

    companion object {
        /** 잡음 갱신 평활 계수. 1 에 가까울수록 천천히 따라간다. */
        private const val NOISE_SMOOTH = 0.95

        /** 0 으로 나누기 방지용 하한. */
        internal const val NOISE_FLOOR = 1e-12
    }
}

/**
 * Spectral Subtraction (Berouti 1979).
 * 이득: G = sqrt( max( 1 - alpha/gamma , beta/gamma ) )
 *   alpha 는 프레임 SNR 에 따라 자동 조절한다(SNR 이 낮을수록 세게 뺀다).
 * 장점: 매우 빠르다. 단점: musical noise(삐걱대는 잔향) 아티팩트가 생기기 쉽다.
 */
class SpectralSubtractionNoiseReducer : GainNoiseReducer() {
    override val algorithm: NoiseAlgorithm = NoiseAlgorithm.SPECTRAL_SUB
    override val label: String = "spectral-subtraction(berouti1979)"

    /** 이번 프레임의 과감산 계수. processFrame 이전에 갱신되지 않으므로 bin 별로 계산한다. */
    override fun gainFor(k: Int, power: Double, noise: Double, config: NoiseReduceConfig): Double {
        val gamma = power / noise                                   // a posteriori SNR
        if (gamma <= 0.0) return config.spectralFloor
        // Berouti 규칙: alpha = alpha0 - (3/20) * SNR_dB, -5dB ~ 20dB 구간에서 선형.
        val snrDb = 10.0 * ln(gamma) / LN10
        val alpha = when {
            snrDb < -5.0 -> config.overSubtraction + 0.75          // 매우 시끄러우면 조금 더 세게
            snrDb > 20.0 -> 1.0                                     // 충분히 깨끗하면 거의 빼지 않는다
            else -> config.overSubtraction - 0.15 * snrDb
        }.coerceAtLeast(1.0)
        val sub = 1.0 - alpha / gamma
        val floor = config.spectralFloor / gamma
        return sqrt(max(sub, floor).coerceAtLeast(0.0))
    }

    private companion object {
        val LN10 = ln(10.0)
    }
}

/**
 * Wiener decision-directed (Scalart & Vieira Filho 1996).
 * 이득: G = xi / (1 + xi),  xi 는 decision-directed 로 추정한 a priori SNR.
 * MMSE-STSA 와 앞단이 완전히 같고 이득식만 다르다 — A/B 비교에 딱 좋은 짝이다.
 */
class WienerNoiseReducer : GainNoiseReducer() {
    override val algorithm: NoiseAlgorithm = NoiseAlgorithm.WIENER
    override val label: String = "wiener-decision-directed(scalart1996)"

    /** 직전 프레임의 추정 음성 파워 / 잡음 PSD (decision-directed 재귀에 쓴다) */
    private var prevSnr = DoubleArray(0)

    override fun onBeginFile(frameCount: Int, config: NoiseReduceConfig) {
        if (prevSnr.size != bins) prevSnr = DoubleArray(bins)
        java.util.Arrays.fill(prevSnr, 1.0)                          // 첫 프레임은 0 dB 로 시작
    }

    override fun gainFor(k: Int, power: Double, noise: Double, config: NoiseReduceConfig): Double {
        val gamma = min(power / noise, GAMMA_MAX)                    // 무음 bin 에서 NaN 나는 것 방지
        // decision-directed (Ephraim-Malah 식 51)
        val xi = (config.ddAlpha * prevSnr[k] + (1.0 - config.ddAlpha) * max(gamma - 1.0, 0.0))
            .coerceAtLeast(XI_MIN)
        val g = xi / (1.0 + xi)
        prevSnr[k] = g * g * gamma                                   // A^2/lambda_d = G^2 * gamma
        return g
    }

    internal companion object {
        /** gamma 상한. VOICEBOX qq.gx=1000 과 같은 값. */
        const val GAMMA_MAX = 1000.0

        /** a priori SNR 하한 (-25 dB). musical noise 억제용. */
        const val XI_MIN = 0.0031622776601683794
    }
}

/**
 * MMSE-STSA (Ephraim & Malah 1984).
 * 이득: G = (sqrt(pi)/2) * (sqrt(v)/gamma) * exp(-v/2) * [ (1+v) I0(v/2) + v I1(v/2) ]
 *   v = xi/(1+xi) * gamma
 * v 가 클 때는 VOICEBOX 가 쓰는 근사 G ~= (0.277 + v)/gamma 를 쓴다(오차 0.02 dB 이내).
 * 전통 DSP 중 musical noise 가 가장 적어 ASR 앞단에 쓰기 무난하다.
 */
class MmseStsaNoiseReducer : GainNoiseReducer() {
    override val algorithm: NoiseAlgorithm = NoiseAlgorithm.MMSE_STSA
    override val label: String = "mmse-stsa(ephraim-malah1984)"

    private var prevSnr = DoubleArray(0)

    override fun onBeginFile(frameCount: Int, config: NoiseReduceConfig) {
        if (prevSnr.size != bins) prevSnr = DoubleArray(bins)
        java.util.Arrays.fill(prevSnr, 1.0)
    }

    override fun gainFor(k: Int, power: Double, noise: Double, config: NoiseReduceConfig): Double {
        val gamma = min(power / noise, WienerNoiseReducer.GAMMA_MAX)
        val xi = (config.ddAlpha * prevSnr[k] + (1.0 - config.ddAlpha) * max(gamma - 1.0, 0.0))
            .coerceAtLeast(WienerNoiseReducer.XI_MIN)
        val v = xi / (1.0 + xi) * gamma
        val g = if (v > V_APPROX_THRESHOLD) {
            // 큰 v 근사식 (VOICEBOX v_ssubmmse.m 과 동일)
            (0.277 + v) / gamma
        } else {
            // 정확식. exp(-v/2)*I0(v/2) 를 i0e 로 한 번에 구해 overflow 를 피한다.
            val u = v / 2.0
            SQRT_PI_2 * sqrt(v) / gamma * ((1.0 + v) * Bessel.i0e(u) + v * Bessel.i1e(u))
        }
        val clamped = g.coerceIn(0.0, 1.0)
        prevSnr[k] = clamped * clamped * gamma
        return clamped
    }

    private companion object {
        /** Gamma(1.5) = sqrt(pi)/2 */
        val SQRT_PI_2 = sqrt(PI) / 2.0

        /** 이 값을 넘으면 근사식을 쓴다(VOICEBOX 기준 v/2 > 0.5 → v > 1). */
        const val V_APPROX_THRESHOLD = 1.0
    }
}
