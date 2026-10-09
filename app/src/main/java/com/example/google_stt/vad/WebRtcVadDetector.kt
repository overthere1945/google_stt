/*
 * 파일명: vad/WebRtcVadDetector.kt
 * 목적 및 기능:
 * - Google WebRTC VAD(GMM 기반, BSD-3)로 프레임마다 말/무음을 판정하고,
 *   그 결과(확률 0 또는 1)를 Silero 방식 상태 기계(FrameSegmenter)로 말 구간으로 만든다.
 * - Android 바인딩: gkonovalov/android-vad 의 webrtc 모듈(MIT, JitPack `com.github.gkonovalov.android-vad:webrtc:2.0.10`)
 *     · VadWebRTC(sampleRate, frameSize, mode, speechDurationMs = 0, silenceDurationMs = 0)
 *       speech/silence 지속시간을 0 으로 두면 isSpeech() 가 "그 프레임의 원래 판정" 을 그대로 돌려준다
 *       (라이브러리 isContinuousSpeech() 소스로 확인). 지속시간 규칙은 FrameSegmenter 가 대신한다.
 *     · 16 kHz 프레임: 160 / 320 / 480 샘플 = 10 / 20 / 30 ms (WebRTC 규격)
 *     · mode: 0 NORMAL / 1 LOW_BITRATE / 2 AGGRESSIVE / 3 VERY_AGGRESSIVE (숫자가 클수록 비음성을 더 많이 거른다)
 *
 * 비고: WebRTC 판정은 이진값이라 threshold 는 0.5 로 고정한다(neg_threshold = 0.35 → 0 이면 무음).
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import com.example.google_stt.VadConfig
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import kotlin.math.roundToInt

/**
 * 목적: WebRTC VAD 실행기.
 * 비고: 네이티브 핸들은 파일마다 새로 만든다(GMM 적응 상태가 파일 사이에 섞이지 않게 하기 위함).
 */
class WebRtcVadDetector : VadDetector {

    override val engine: VadEngine = VadEngine.WEBRTC                      // 엔진 종류
    override val label: String = "webrtc-vad (android-vad 2.0.10)"         // CSV vad_model
    override val runtimeVersion: String = "android-vad 2.0.10"             // 라이브러리 판(build.gradle 과 같게 유지)
    override val modelSha256: String = ""                                  // 모델 파일이 없다(GMM 내장)

    /**
     * 목적: PCM 1건에서 말 구간을 찾는다.
     * 입력: pcm16Le — 16 kHz/mono/PCM16 LE, setting — WEBRTC_* 프리셋 또는 Custom
     * 리턴: FrameSegmenter.Result
     */
    @Synchronized
    override fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result {
        val config = VadConfig.fromSetting(setting, thresholdOverride = BINARY_THRESHOLD)  // 범위 검증
        val frameMs = setting[VadKeys.FRAME_MS].roundToInt()               // 10 / 20 / 30 ms
        val frameSize = when (frameMs) {                                   // ms → 라이브러리 프레임 크기
            10 -> FrameSize.FRAME_SIZE_160
            20 -> FrameSize.FRAME_SIZE_320
            30 -> FrameSize.FRAME_SIZE_480
            else -> throw IllegalArgumentException("WebRTC Frame 은 10 / 20 / 30 ms 만 됩니다: $frameMs")
        }
        val mode = when (setting[VadKeys.WEBRTC_MODE].roundToInt()) {      // 숫자 → 라이브러리 모드
            0 -> Mode.NORMAL
            1 -> Mode.LOW_BITRATE
            2 -> Mode.AGGRESSIVE
            3 -> Mode.VERY_AGGRESSIVE
            else -> throw IllegalArgumentException("WebRTC Mode 는 0~3 입니다.")
        }
        val samples = VadAudio.toShort(pcm16Le)                            // PCM16 → short
        val window = frameSize.value                                       // 프레임 샘플 수
        val frameCount = (samples.size + window - 1) / window              // 마지막 짧은 프레임 포함
        val probs = FloatArray(frameCount)                                 // 프레임별 판정(0/1)

        // change-hyungchul-20261009-1626 : JitPack AAR 이 minify 되어 Kotlin 메타데이터가 없을 때도 컴파일되도록 위치 인자로 부른다
        //   (선언 순서: sampleRate, frameSize, mode, speechDurationMs, silenceDurationMs)
        VadWebRTC(                                                         // 파일마다 새 핸들
            SampleRate.SAMPLE_RATE_16K,
            frameSize,
            mode,
            0,                                                             // speechDurationMs = 0 → 원래 판정 그대로
            0,                                                             // silenceDurationMs = 0
        ).use { vad ->
            val frame = ShortArray(window)                                 // 재사용 버퍼
            for (i in 0 until frameCount) {
                val start = i * window
                val n = minOf(window, samples.size - start)
                frame.fill(0)                                              // 마지막 프레임은 0 으로 채운다(Silero 와 같은 규칙)
                System.arraycopy(samples, start, frame, 0, n)
                probs[i] = if (vad.isSpeech(frame)) 1f else 0f             // 말이면 1, 아니면 0
            }
        }

        return FrameSegmenter.collect(                                     // Silero 방식 구간 규칙
            probabilities = probs,
            windowSamples = window,
            audioLengthSamples = samples.size,
            threshold = config.threshold,
            minSpeechMs = config.minSpeechDurationMs,
            minSilenceMs = config.minSilenceDurationMs,
            speechPadMs = config.speechPadMs,
            maxSpeechSec = config.maxSpeechDurationSec,
        )
    }

    /** 배치 끝 정리 — 핸들은 detect() 안에서 매번 닫으므로 할 일이 없다 */
    override fun close() = Unit

    private companion object {
        /** 이진 판정용 고정 임계값(0/1 확률을 0.5 로 가른다) */
        const val BINARY_THRESHOLD = 0.5f
    }
}
