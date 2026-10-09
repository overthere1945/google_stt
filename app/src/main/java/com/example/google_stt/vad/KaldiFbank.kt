/*
 * 파일명: vad/KaldiFbank.kt
 * 목적 및 기능:
 * - Kaldi(kaldi-native-fbank) 규격의 80차 log-mel filterbank 특징을 계산한다.
 *   FunASR FSMN-VAD(창: hamming) 와 FireRedVAD(창: povey, knf 기본값)가 공통으로 쓴다.
 * - 규격(knf FbankOptions 기본값 + 두 모델 설정):
 *     16 kHz, frame_length 25 ms(400 샘플), frame_shift 10 ms(160 샘플), snip_edges = true,
 *     dither 0, remove_dc_offset = true, preemph 0.97, FFT 512, mel 80, low 20 Hz, high = Nyquist,
 *     use_power = true, use_log_fbank = true, log 하한 = float epsilon(1.1920929e-07)
 *     입력 스케일 = int16 값 그대로(FunASR 는 float×32768, FireRed 는 int16 → 같은 스케일)
 * - 근거:
 *     FunASR runtime/llama.cpp/funasr-common/funasr_vad.h  fbank80() — "PyTorch fsmn-vad 와 bit-exact" 로 검증된 구현
 *     FireRedVAD fireredvad/core/audio_feat.py  KaldifeatFbank(knf.OnlineFbank, 기본 창 povey)
 *     kaldi-native-fbank feature-window.cc / mel-computations.cc (povey = (0.5-0.5cos)^0.85)
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import com.example.google_stt.denoise.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

/**
 * 목적: Kaldi 규격 fbank 계산기.
 * 입력(생성자): window — 창 함수 종류(HAMMING = FSMN, POVEY = FireRed)
 * 비고: 내부 버퍼를 재사용하므로 thread-safe 하지 않다(VAD 실행기는 @Synchronized 로 부른다).
 */
class KaldiFbank(window: Window) {

    /** 창 함수 종류 */
    enum class Window { HAMMING, POVEY }

    companion object {
        const val SAMPLE_RATE = 16_000                    // 입력 샘플레이트
        const val FRAME_LENGTH = 400                      // 25 ms
        const val FRAME_SHIFT = 160                       // 10 ms
        const val NUM_MEL = 80                            // mel 빈 수
        private const val NFFT = 512                      // 400 → 2의 거듭제곱으로 올림
        private const val PREEMPH = 0.97                  // 프리엠퍼시스 계수
        private const val LOW_FREQ = 20.0                 // mel 하한(Hz)
        private const val HIGH_FREQ = 8000.0              // mel 상한 = Nyquist
        private const val LOG_FLOOR = 1.1920929e-07       // float epsilon(Kaldi 기본 log 하한)

        /** Kaldi MelScale: 1127 * ln(1 + f / 700) */
        private fun mel(f: Double): Double = 1127.0 * ln(1.0 + f / 700.0)
    }

    private val fft = Fft(NFFT)                                            // 512점 FFT(트위들 재사용)
    private val win = DoubleArray(FRAME_LENGTH) { i ->                     // 창 함수 계수
        val base = 0.5 - 0.5 * cos(2.0 * PI * i / (FRAME_LENGTH - 1))     // hanning 꼴 공통 부분
        when (window) {
            Window.HAMMING -> 0.54 - 0.46 * cos(2.0 * PI * i / (FRAME_LENGTH - 1))
            Window.POVEY -> base.pow(0.85)                                 // Kaldi povey 창
        }
    }
    private val numBins = NFFT / 2 + 1                                     // 0 ~ Nyquist (257)
    private val melBank: Array<DoubleArray> = Array(NUM_MEL) { m ->        // 삼각 mel 필터 가중치
        val binWidth = SAMPLE_RATE.toDouble() / NFFT                       // FFT 빈 간격(Hz)
        val ml = mel(LOW_FREQ)
        val mh = mel(HIGH_FREQ)
        val dm = (mh - ml) / (NUM_MEL + 1)                                 // mel 간격
        val left = ml + m * dm
        val center = ml + (m + 1) * dm
        val right = ml + (m + 2) * dm
        DoubleArray(numBins) { k ->
            val mf = mel(binWidth * k)                                     // 이 빈의 mel 값
            if (mf > left && mf < right) {                                 // 삼각형 안쪽만 가중치
                if (mf <= center) (mf - left) / (center - left) else (right - mf) / (right - center)
            } else 0.0
        }
    }
    private val re = DoubleArray(NFFT)                                     // FFT 실수부 버퍼
    private val im = DoubleArray(NFFT)                                     // FFT 허수부 버퍼
    private val frame = DoubleArray(FRAME_LENGTH)                          // 프레임 버퍼

    /**
     * 목적: 전체 파형의 fbank 를 계산한다.
     * 입력: wav — int16 스케일 실수 파형(값 범위 -32768 ~ 32767)
     * 리턴: [T][80] 특징(T = 1 + (N - 400) / 160, N < 400 이면 0 프레임)
     */
    fun compute(wav: FloatArray): Array<FloatArray> {
        val n = wav.size
        val t = if (n < FRAME_LENGTH) 0 else (n - FRAME_LENGTH) / FRAME_SHIFT + 1  // snip_edges = true
        val out = Array(t) { FloatArray(NUM_MEL) }
        for (ti in 0 until t) {
            val off = ti * FRAME_SHIFT                                     // 프레임 시작 샘플
            var mean = 0.0
            for (i in 0 until FRAME_LENGTH) mean += wav[off + i]
            mean /= FRAME_LENGTH                                           // remove_dc_offset
            for (i in 0 until FRAME_LENGTH) frame[i] = wav[off + i] - mean
            for (i in FRAME_LENGTH - 1 downTo 1) frame[i] -= PREEMPH * frame[i - 1]  // 프리엠퍼시스(뒤에서부터)
            frame[0] -= PREEMPH * frame[0]                                 // Kaldi: 첫 샘플은 자기 자신 기준
            for (i in 0 until NFFT) {                                      // 창 곱하고 0 채우기
                re[i] = if (i < FRAME_LENGTH) frame[i] * win[i] else 0.0
                im[i] = 0.0
            }
            fft.transform(re, im, inverse = false)                         // 512점 FFT
            for (m in 0 until NUM_MEL) {
                val w = melBank[m]
                var e = 0.0
                for (k in 0 until numBins) {
                    val wk = w[k]
                    if (wk > 0.0) e += wk * (re[k] * re[k] + im[k] * im[k])   // power spectrum × mel 가중치
                }
                out[ti][m] = ln(if (e > LOG_FLOOR) e else LOG_FLOOR).toFloat()  // log mel energy
            }
        }
        return out
    }
}
