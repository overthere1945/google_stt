/*
 * 파일명: denoise/SpectralNoiseReducer.kt
 * 목적 및 기능:
 * - STFT → (프레임별 처리) → iSTFT 로 동작하는 모든 알고리즘의 공통 뼈대.
 * - 딥러닝(ONNX)이든 전통 DSP 든 전부 이 루프를 타므로 stft / infer / istft 단계 시간을
 *   같은 방식으로 재게 되어 알고리즘 간 비교가 공정해진다.
 * - 마지막에 "감쇠 제한(attenuation limit)"을 공통으로 적용한다.
 *   → 사용자 조사 문서의 원칙 1번(과도한 노이즈 제거 금지)을 코드로 강제하는 장치다.
 *
 * add-hyungchul-20260826-1100
 */
package com.example.google_stt.denoise

/**
 * 목적: 프레임 단위 스펙트럼 처리를 하는 노이즈 저감기의 공통 구현.
 * 입력(생성자): nFft, hop, windowType, center — 알고리즘이 요구하는 STFT 규격
 * 비고: 하위 클래스는 beginFile() 과 processFrame() 만 구현하면 된다.
 */
abstract class SpectralNoiseReducer(
    protected val nFft: Int,
    protected val hop: Int,
    windowType: WindowType,
    center: Boolean,
) : NoiseReducer {

    protected val stft = Stft(nFft, hop, windowType, center)

    /** 주파수 bin 수 (0 ~ Nyquist) */
    protected val bins: Int = stft.bins

    /**
     * 목적: 파일 하나를 시작할 때 상태를 초기화한다(프레임 간 상태가 파일을 넘어가지 않게).
     * 입력: frameCount — 이번 파일의 총 프레임 수, config — 실행 설정
     * 출력: 없음
     * 리턴: 없음
     */
    protected abstract fun beginFile(frameCount: Int, config: NoiseReduceConfig)

    /**
     * 목적: 스펙트럼 1프레임을 제자리에서(in-place) 처리한다.
     * 입력: re/im — 길이 bins 의 복소 스펙트럼(수정 대상), frameIndex, config
     * 출력: 없음 (re/im 을 직접 수정)
     * 리턴: 없음
     */
    protected abstract fun processFrame(
        re: DoubleArray,
        im: DoubleArray,
        frameIndex: Int,
        config: NoiseReduceConfig,
    )

    /**
     * 사전 패스에서 쓴 시간을 단계별로 나눠 담는다.
     * 이렇게 나눠야 "전통 DSP 의 잡음 추정 패스"와 "NSNet2 의 전체 구간 1회 추론"이
     * 각각 stft / infer 로 정확히 분류되어 알고리즘 간 비교가 왜곡되지 않는다.
     */
    protected class PrePassCost(
        val prepareNs: Long = 0L,
        val stftNs: Long = 0L,
        val inferNs: Long = 0L,
    )

    /**
     * 목적: 본 처리 루프에 들어가기 전에 파일 전체를 한 번 훑어야 하는 알고리즘을 위한 훅.
     *       - 전통 DSP: 파일 전체에서 가장 조용한 구간으로 잡음을 추정한다.
     *       - NSNet2 : 전체 구간을 한 번에 추론해 프레임별 이득을 미리 구한다.
     * 입력: padded — 패딩된 신호, frameCount, config
     * 리턴: 단계별 소요 나노초
     */
    protected open fun prePass(
        padded: DoubleArray,
        frameCount: Int,
        config: NoiseReduceConfig,
    ): PrePassCost = PrePassCost()

    /** 파일 처리가 끝난 뒤 결과 note 에 남길 문자열(기본은 빈 문자열). */
    protected open fun fileNote(): String = ""

    final override fun process(pcm16Le: ByteArray, config: NoiseReduceConfig): NoiseReduceResult {
        require(pcm16Le.size % 2 == 0) { "PCM16 은 byte 수가 짝수여야 합니다: ${pcm16Le.size}" }
        val wallStart = System.nanoTime()

        // ── 1) 준비: PCM → Double, 패딩 ──
        var t0 = System.nanoTime()
        val x = PcmUtil.toDouble(pcm16Le)
        if (x.isEmpty()) {
            return NoiseReduceResult(
                pcm = ByteArray(0), frames = 0,
                prepareMs = 0, stftMs = 0, inferMs = 0, istftMs = 0, postMs = 0, totalMs = 0,
                inRmsDb = -120.0, outRmsDb = -120.0, peak = 0.0, note = "empty",
            )
        }
        val inRms = PcmUtil.rmsDb(x)
        val padded = stft.pad(x)
        val frameCount = stft.frameCount(padded.size)
        val out = DoubleArray(padded.size)
        val winSq = DoubleArray(padded.size)
        val re = DoubleArray(bins)
        val im = DoubleArray(bins)
        beginFile(frameCount, config)
        var prepareNs = System.nanoTime() - t0

        // 알고리즘이 사전 패스를 요구하면 여기서 돈다(소요 시간은 단계별로 나눠 합산한다).
        val pre = prePass(padded, frameCount, config)
        prepareNs += pre.prepareNs

        // ── 2) 프레임 루프: 분석 → 처리 → 합성 ──
        var stftNs = pre.stftNs
        var inferNs = pre.inferNs
        var istftNs = 0L
        for (f in 0 until frameCount) {
            t0 = System.nanoTime()
            stft.analyze(padded, f, re, im)
            stftNs += System.nanoTime() - t0

            t0 = System.nanoTime()
            processFrame(re, im, f, config)
            inferNs += System.nanoTime() - t0

            t0 = System.nanoTime()
            stft.overlapAdd(re, im, f, out, winSq)
            istftNs += System.nanoTime() - t0
        }

        // ── 3) 마무리: 정규화 → 잘라내기 → 감쇠 제한 → PCM ──
        t0 = System.nanoTime()
        stft.normalize(out, winSq)
        val offset = stft.trimOffset()
        val y = DoubleArray(x.size)
        for (i in x.indices) {
            val src = offset + i
            y[i] = if (src < out.size) out[src] else 0.0
        }
        // ★ 감쇠 제한: 원음을 dryMix 만큼 섞어 "최대 몇 dB 까지만 줄이기" 를 보장한다.
        //   DeepFilterNet 의 atten_lim_db 와 같은 방식이다. 0 이면 건너뛴다.
        val dry = config.dryMix
        if (dry > 0.0) {
            val wet = 1.0 - dry
            for (i in y.indices) y[i] = dry * x[i] + wet * y[i]
        }
        val outRms = PcmUtil.rmsDb(y)
        val peak = PcmUtil.peak(y)
        val pcmOut = PcmUtil.toPcm(y)
        val postNs = System.nanoTime() - t0

        val totalMs = (System.nanoTime() - wallStart) / 1_000_000
        return NoiseReduceResult(
            pcm = pcmOut,
            frames = frameCount,
            prepareMs = prepareNs / 1_000_000,
            stftMs = stftNs / 1_000_000,
            inferMs = inferNs / 1_000_000,
            istftMs = istftNs / 1_000_000,
            postMs = postNs / 1_000_000,
            totalMs = totalMs,
            inRmsDb = inRms,
            outRmsDb = outRms,
            peak = peak,
            note = fileNote(),
        )
    }
}
