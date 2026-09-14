/*
 * 파일명: denoise/AudioDsp.kt
 * 목적 및 기능:
 * - 노이즈 저감 알고리즘이 공통으로 쓰는 신호처리 기본기를 모아 둔다.
 *     (1) Fft      : 임의 길이 복소 FFT. 2의 거듭제곱은 radix-2, 그 외는 Bluestein 으로 처리한다.
 *     (2) WindowType: 노이즈 저감 모델마다 요구하는 분석창(hann_sqrt / vorbis / hann)
 *     (3) Stft     : frame 단위 STFT / iSTFT(overlap-add). 프레임 하나씩 흘려보내는 구조라
 *                    80~90초 음원도 메모리를 n_fft 만큼만 쓴다.
 *
 * ★ 왜 임의 길이 FFT 가 필요한가
 *   - GTCRN 은 n_fft = 512 (2의 거듭제곱)
 *   - DPDFNet / NSNet2 는 n_fft = 320 (= 2^6 x 5, 2의 거듭제곱이 아니다)
 *   320 을 512 로 zero-pad 하면 주파수 bin 수가 161 → 257 로 바뀌어 모델 입력 규격이 깨진다.
 *   그래서 320-point FFT 를 그대로 계산해야 하고, Bluestein(chirp-z) 알고리즘을 쓴다.
 *
 * add-hyungchul-20260826-1100
 */
package com.example.google_stt.denoise

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 목적: 임의 길이 복소 FFT. 한 번 만들어 두고 같은 크기를 반복 계산하는 용도(트위들 재사용).
 * 입력: n — 변환 길이
 * 비고: process() 는 in-place 로 동작하며 thread-safe 하지 않다(내부 버퍼를 재사용한다).
 */
class Fft(val n: Int) {

    private val isPowerOfTwo: Boolean = n > 0 && (n and (n - 1)) == 0

    // ── radix-2 용 트위들(회전인자) 테이블 ──
    private val cosTable = DoubleArray(n / 2)
    private val sinTable = DoubleArray(n / 2)

    // ── Bluestein 용 사전 계산 ──
    private var bluesteinSize = 0                    // 2의 거듭제곱으로 올린 보조 길이 m
    private var chirpCos = DoubleArray(0)            // cos(pi * k^2 / n)
    private var chirpSin = DoubleArray(0)            // sin(pi * k^2 / n)
    private var kernelRe = DoubleArray(0)            // 보조 커널의 FFT (실수부)
    private var kernelIm = DoubleArray(0)            // 보조 커널의 FFT (허수부)
    private var innerFft: Fft? = null                // 보조 길이용 radix-2 FFT
    private val workRe: DoubleArray
    private val workIm: DoubleArray

    init {
        require(n > 0) { "FFT 길이는 1 이상이어야 합니다: $n" }
        if (isPowerOfTwo) {
            // exp(-2*pi*i*k/n) 을 미리 계산해 둔다.
            for (k in 0 until n / 2) {
                val a = -2.0 * PI * k / n
                cosTable[k] = cos(a)
                sinTable[k] = sin(a)
            }
            workRe = DoubleArray(0)
            workIm = DoubleArray(0)
        } else {
            // Bluestein: 길이 n 의 DFT 를 길이 m(2의 거듭제곱, m >= 2n-1)의 순환 convolution 으로 바꾼다.
            var m = 1
            while (m < 2 * n - 1) m = m shl 1
            bluesteinSize = m
            chirpCos = DoubleArray(n)
            chirpSin = DoubleArray(n)
            for (k in 0 until n) {
                // k^2 을 2n 으로 나눈 나머지를 쓰면 큰 k 에서도 각도 정밀도가 유지된다.
                val j = (k.toLong() * k) % (2L * n)
                val a = PI * j / n
                chirpCos[k] = cos(a)
                chirpSin[k] = sin(a)
            }
            // 커널 b[k] = conj(chirp) 를 원형으로 배치한 뒤 FFT 해 둔다.
            val bRe = DoubleArray(m)
            val bIm = DoubleArray(m)
            bRe[0] = chirpCos[0]
            bIm[0] = chirpSin[0]
            for (k in 1 until n) {
                bRe[k] = chirpCos[k]; bIm[k] = chirpSin[k]
                bRe[m - k] = chirpCos[k]; bIm[m - k] = chirpSin[k]
            }
            val inner = Fft(m)
            inner.transform(bRe, bIm, inverse = false)
            innerFft = inner
            kernelRe = bRe
            kernelIm = bIm
            workRe = DoubleArray(m)
            workIm = DoubleArray(m)
        }
    }

    /**
     * 목적: 복소 배열을 in-place 로 FFT / IFFT 한다.
     * 입력: re/im — 길이 n 의 실수부/허수부, inverse — true 면 역변환(1/n 정규화 포함)
     * 출력: 없음 (re/im 을 직접 수정)
     * 리턴: 없음
     */
    fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        require(re.size == n && im.size == n) { "FFT 입력 길이가 $n 이어야 합니다." }
        if (inverse) {
            // IFFT(x) = conj(FFT(conj(x))) / n — 켤레를 취해 정변환을 재사용한다.
            for (i in 0 until n) im[i] = -im[i]
            forward(re, im)
            val inv = 1.0 / n
            for (i in 0 until n) {
                re[i] *= inv
                im[i] = -im[i] * inv
            }
        } else {
            forward(re, im)
        }
    }

    /** 정변환 진입점. 길이에 따라 radix-2 / Bluestein 으로 갈린다. */
    private fun forward(re: DoubleArray, im: DoubleArray) {
        if (isPowerOfTwo) radix2(re, im) else bluestein(re, im)
    }

    /** 반복형 radix-2 Cooley-Tukey (재귀·할당 없음). */
    private fun radix2(re: DoubleArray, im: DoubleArray) {
        // 1) 비트 역순 재배열
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        // 2) 버터플라이
        var len = 2
        while (len <= n) {
            val step = n / len
            val half = len / 2
            var i = 0
            while (i < n) {
                var k = 0
                for (p in i until i + half) {
                    val q = p + half
                    val wr = cosTable[k]
                    val wi = sinTable[k]
                    val tr = re[q] * wr - im[q] * wi
                    val ti = re[q] * wi + im[q] * wr
                    re[q] = re[p] - tr
                    im[q] = im[p] - ti
                    re[p] += tr
                    im[p] += ti
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Bluestein(chirp-z): 2의 거듭제곱이 아닌 길이를 순환 convolution 으로 계산한다. */
    private fun bluestein(re: DoubleArray, im: DoubleArray) {
        val m = bluesteinSize
        val inner = innerFft!!
        java.util.Arrays.fill(workRe, 0.0)
        java.util.Arrays.fill(workIm, 0.0)
        // a[k] = x[k] * conj(chirp[k])
        for (k in 0 until n) {
            val c = chirpCos[k]
            val s = -chirpSin[k]
            workRe[k] = re[k] * c - im[k] * s
            workIm[k] = re[k] * s + im[k] * c
        }
        inner.transform(workRe, workIm, inverse = false)
        // 주파수 영역에서 커널과 곱한다.
        for (i in 0 until m) {
            val tr = workRe[i] * kernelRe[i] - workIm[i] * kernelIm[i]
            val ti = workRe[i] * kernelIm[i] + workIm[i] * kernelRe[i]
            workRe[i] = tr
            workIm[i] = ti
        }
        inner.transform(workRe, workIm, inverse = true)
        // 다시 conj(chirp) 를 곱해 마무리한다.
        for (k in 0 until n) {
            val c = chirpCos[k]
            val s = -chirpSin[k]
            re[k] = workRe[k] * c - workIm[k] * s
            im[k] = workRe[k] * s + workIm[k] * c
        }
    }
}

/** 노이즈 저감 모델이 요구하는 분석창 종류. 모델마다 다르므로 반드시 맞춰야 한다. */
enum class WindowType {
    /** 주기형 Hann. */
    HANN,

    /** sqrt(주기형 Hann). GTCRN(hann_sqrt) / NSNet2 계열이 사용한다. */
    HANN_SQRT,

    /**
     * sqrt(대칭형 Hann). 분모가 n-1 이다.
     * NSNet2 공식 스크립트(featurelib.py)가 np.sqrt(np.hanning(N)) 을 쓰므로 그대로 맞춘다.
     * 대칭형은 hop=n/2 에서 창^2 합이 정확히 1 이 아니지만,
     * Stft.normalize() 가 창^2 누적으로 나눠 주므로 복원은 여전히 정확하다.
     */
    HANN_SQRT_SYMMETRIC,

    /** Vorbis 창 sin(pi/2 * sin^2(pi*(n+0.5)/N)). DPDFNet 이 사용한다. */
    VORBIS,
    ;

    /**
     * 목적: 길이 n 의 창 계수를 만든다.
     * 입력: n — 창 길이
     * 리턴: DoubleArray(n)
     */
    fun create(n: Int): DoubleArray {
        val w = DoubleArray(n)
        for (i in 0 until n) {
            when (this) {
                // 주기형(periodic) Hann: 분모가 n 이다(대칭형은 n-1). hop = n/2 에서 합이 정확히 1 이 된다.
                HANN -> w[i] = 0.5 - 0.5 * cos(2.0 * PI * i / n)
                HANN_SQRT -> w[i] = sqrt(0.5 - 0.5 * cos(2.0 * PI * i / n))
                // 대칭형: 분모가 n-1 (numpy 의 np.hanning 과 동일)
                HANN_SQRT_SYMMETRIC ->
                    w[i] = if (n <= 1) 1.0 else sqrt(0.5 - 0.5 * cos(2.0 * PI * i / (n - 1)))
                VORBIS -> {
                    val s = sin(PI * (i + 0.5) / n)
                    w[i] = sin(PI / 2.0 * s * s)
                }
            }
        }
        return w
    }
}

/**
 * 목적: frame 단위 STFT / iSTFT(weighted overlap-add).
 * 입력: nFft(변환 길이), hop(프레임 이동), windowType, center(반사 패딩 여부)
 * 비고:
 * - 프레임을 하나씩 처리하도록 만들었다. 전체 스펙트로그램을 메모리에 들고 있지 않으므로
 *   90초 음원도 n_fft 크기의 버퍼만 쓴다.
 * - HANN_SQRT / VORBIS 는 hop = nFft/2 에서 w^2 의 합이 정확히 1 이지만,
 *   안전을 위해 합성 시 w^2 누적으로 나눠 준다(어떤 창/hop 조합에서도 정확히 복원된다).
 */
class Stft(
    val nFft: Int,
    val hop: Int,
    windowType: WindowType,
    val center: Boolean,
) {
    /** 실수 입력의 주파수 bin 개수 (0 ~ Nyquist) */
    val bins: Int = nFft / 2 + 1

    private val window: DoubleArray = windowType.create(nFft)
    private val fft = Fft(nFft)
    private val re = DoubleArray(nFft)
    private val im = DoubleArray(nFft)

    /**
     * 목적: center=true 일 때 필요한 반사(reflect) 패딩을 적용한 신호를 만든다.
     * 입력: x — 원본 신호
     * 리턴: 앞뒤로 패딩된 신호. center=false 면 뒤쪽에만 nFft 만큼 0 을 붙인다.
     * 비고: DPDFNet 은 metadata 에 center=1, pad_mode=reflect 로 명시되어 있다.
     */
    fun pad(x: DoubleArray): DoubleArray {
        return if (center) {
            val p = nFft / 2
            val out = DoubleArray(x.size + 2 * p + nFft)
            for (i in 0 until p) out[i] = x[reflectIndex(p - i, x.size)]      // 앞쪽 반사
            System.arraycopy(x, 0, out, p, x.size)                            // 원본
            for (i in 0 until p) out[p + x.size + i] = x[reflectIndex(x.size - 2 - i, x.size)]
            out
        } else {
            val out = DoubleArray(x.size + nFft)
            System.arraycopy(x, 0, out, 0, x.size)
            out
        }
    }

    /** 반사 패딩용 인덱스 보정(길이가 1인 극단적인 경우까지 안전하게 처리). */
    private fun reflectIndex(i: Int, len: Int): Int {
        if (len <= 1) return 0
        var k = i
        if (k < 0) k = -k
        if (k >= len) k = 2 * (len - 1) - k
        return k.coerceIn(0, len - 1)
    }

    /** 패딩된 신호에서 만들어지는 총 프레임 수. */
    fun frameCount(paddedLength: Int): Int =
        if (paddedLength < nFft) 0 else 1 + (paddedLength - nFft) / hop

    /** center=true 일 때 결과에서 잘라내야 하는 앞쪽 오프셋. */
    fun trimOffset(): Int = if (center) nFft / 2 else 0

    /**
     * 목적: frameIndex 번째 프레임을 분석해 복소 스펙트럼을 outRe/outIm 에 채운다.
     * 입력: padded — pad() 결과, frameIndex — 프레임 번호,
     *       outRe/outIm — 길이 bins 이상의 출력 버퍼(재사용해서 할당을 줄인다)
     * 출력: 없음 (outRe/outIm 을 채운다)
     * 리턴: 없음
     */
    fun analyze(padded: DoubleArray, frameIndex: Int, outRe: DoubleArray, outIm: DoubleArray) {
        val start = frameIndex * hop
        for (i in 0 until nFft) {
            re[i] = padded[start + i] * window[i]   // 창을 곱한다
            im[i] = 0.0                              // 실수 입력이므로 허수부는 0
        }
        fft.transform(re, im, inverse = false)
        for (k in 0 until bins) {                    // 0 ~ Nyquist 만 쓴다(나머지는 켤레대칭)
            outRe[k] = re[k]
            outIm[k] = im[k]
        }
    }

    /**
     * 목적: 복소 스펙트럼 1프레임을 시간영역으로 되돌려 out 버퍼에 overlap-add 한다.
     * 입력: specRe/specIm — 길이 bins 의 스펙트럼, frameIndex — 프레임 번호,
     *       out — 누적 출력 버퍼, winSquareSum — 창^2 누적 버퍼(정규화용)
     * 출력: 없음 (out / winSquareSum 을 갱신)
     * 리턴: 없음
     */
    fun overlapAdd(
        specRe: DoubleArray,
        specIm: DoubleArray,
        frameIndex: Int,
        out: DoubleArray,
        winSquareSum: DoubleArray,
    ) {
        // 켤레대칭을 이용해 전체 스펙트럼을 복원한다.
        for (k in 0 until bins) {
            re[k] = specRe[k]
            im[k] = specIm[k]
        }
        for (k in bins until nFft) {
            re[k] = specRe[nFft - k]
            im[k] = -specIm[nFft - k]
        }
        fft.transform(re, im, inverse = true)
        val start = frameIndex * hop
        for (i in 0 until nFft) {
            val idx = start + i
            if (idx >= out.size) break
            out[idx] += re[i] * window[i]                 // 합성창을 곱해 누적
            winSquareSum[idx] += window[i] * window[i]    // 정규화를 위해 창^2 도 누적
        }
    }

    /**
     * 목적: overlap-add 누적 결과를 창^2 합으로 나눠 최종 신호를 만든다.
     * 입력: out — 누적 버퍼, winSquareSum — 창^2 누적 버퍼
     * 출력: 없음 (out 을 직접 수정)
     * 리턴: 없음
     */
    fun normalize(out: DoubleArray, winSquareSum: DoubleArray) {
        for (i in out.indices) {
            val w = winSquareSum[i]
            if (w > 1e-8) out[i] /= w        // 창이 거의 0인 구간은 나누지 않는다(증폭 방지)
        }
    }
}
