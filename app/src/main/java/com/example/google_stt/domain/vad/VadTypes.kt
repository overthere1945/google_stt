/*
 * 파일명: domain/vad/VadTypes.kt
 * 목적 및 기능:
 * - VAD 공용 자료형 3개(VadConfig / VadSegment / VadResult)를 모은다. Android·ONNX 를 쓰지 않는 순수 Kotlin 이다.
 * - 원래 SileroVadProcessor.kt(Silero ONNX 실행기) 안에 있어서, 다른 VAD 엔진과 구간 계산 코드가
 *   Silero 구현 파일에 거꾸로 의존했다(1단계 분석 4장 9번). 내용은 그대로 두고 위치만 옮겼다.
 * - VadConfig 가 쓰던 SileroVadProcessor.WINDOW_SAMPLES / SAMPLE_RATE 는 이 파일의 상수로 옮기고,
 *   SileroVadProcessor 가 이 상수를 가리키게 했다(값은 같다: 512, 16000).
 *
 * add-hyungchul-20261105-1915 — [T1-1] SileroVadProcessor.kt 에서 분리
 */
package com.example.google_stt.domain.vad

import java.util.Locale                        // 오류 문구 숫자 형식 고정
import kotlin.math.max                         // negThreshold 계산
import kotlin.math.roundToInt                  // 설정 값(Double) → Int

/** add-hyungchul-20261105-1915 : 이 앱의 VAD·STT 입력 표본화 주파수(Hz). SileroVadProcessor.SAMPLE_RATE 가 이 값을 가리킨다. */
const val VAD_SAMPLE_RATE = 16_000

/** add-hyungchul-20261105-1915 : Silero VAD 프레임 길이(샘플) — 32 ms @ 16 kHz, 공식 규격이라 바꾸면 안 된다. SileroVadProcessor.WINDOW_SAMPLES 가 이 값을 가리킨다. */
const val SILERO_WINDOW_SAMPLES = 512

/**
 * 목적: Silero get_speech_timestamps() 에 넘길 파라미터 묶음.
 * 비고: maxSpeechDurationSec 이 null 이면 Unlimited(공식 기본값 float('inf')) 를 뜻한다.
 */
data class VadConfig(
    val threshold: Float,               // 이 값 이상이면 speech 로 본다
    val minSpeechDurationMs: Int,       // 이보다 짧은 speech 구간은 버린다
    val minSilenceDurationMs: Int,      // 이보다 짧은 무음은 구간을 끊지 않는다
    val speechPadMs: Int,               // 구간 앞뒤로 붙여줄 여유 (앞말/뒷말 잘림 방지)
    val maxSpeechDurationSec: Double?,  // null = Unlimited
    // add-hyungchul-20260914-2130 : 구간 사이 무음(ms)
    //   PC GUI 의 --vad-join-silence-ms 와 같은 뜻이다(GUI 기본값 200).
    //   speech 구간만 그대로 이어 붙이면 문장 경계가 사라져서 인식기가 한 덩어리로 붙여 읽는다.
    //   구간 사이에 이 길이만큼 digital silence 를 끼워 넣어 문장 경계를 남긴다.
    //   0 이면 예전 동작(무음 삽입 없음)과 완전히 같다.
    val joinSilenceMs: Int = 200,
) {
    /** Silero 공식 기본 동작: neg_threshold = max(threshold - 0.15, 0.01) */
    val negThreshold: Float
        get() = max(threshold - 0.15f, 0.01f)

    // change-hyungchul-20261009-1530
    companion object {
        /**
         * 목적: 화면에서 고른 VAD 설정(VadSetting) 값을 Silero 파라미터로 바꾼다(init 의 범위 검증을 그대로 탄다).
         * 입력: s — Silero 방식 키(threshold / min_speech_ms / min_silence_ms / pad_ms / max_speech_sec / join_ms)를 가진 설정
         *       thresholdOverride — threshold 항목이 없는 엔진(WebRTC)이 넘기는 고정 임계값
         * 리턴: VadConfig
         * 비고: max_speech_sec 0 이하 = Unlimited(null) — 기존 화면 규칙과 같다.
         */
        fun fromSetting(s: VadSetting, thresholdOverride: Float? = null): VadConfig {
            val maxSpeech = s[VadKeys.MAX_SPEECH_SEC]                     // 0 이하면 무제한
            return VadConfig(
                threshold = thresholdOverride ?: s[VadKeys.THRESHOLD].toFloat(),
                minSpeechDurationMs = s[VadKeys.MIN_SPEECH_MS].roundToInt(),
                minSilenceDurationMs = s[VadKeys.MIN_SILENCE_MS].roundToInt(),
                speechPadMs = s[VadKeys.PAD_MS].roundToInt(),
                maxSpeechDurationSec = if (maxSpeech <= 0.0) null else maxSpeech,
                joinSilenceMs = s[VadKeys.JOIN_MS].roundToInt(),
            )
        }
    }

    init {
        // UI 입력값을 여기서 한 번 더 검증한다(잘못된 값으로 배치가 돌면 결과 전체를 버려야 하므로).
        require(threshold in 0.01f..0.99f) { "Threshold 는 0.01 ~ 0.99 범위여야 합니다." }
        require(minSpeechDurationMs >= 0) { "Min Speech 는 0 이상이어야 합니다." }
        require(minSilenceDurationMs >= 0) { "Min Silence 는 0 이상이어야 합니다." }
        require(speechPadMs >= 0) { "Speech Padding 은 0 이상이어야 합니다." }
        // add-hyungchul-20260914-2130 : 구간 사이 무음 검증 (0 ~ 5000 ms)
        require(joinSilenceMs in 0..5_000) { "구간 사이 무음은 0 ~ 5000 ms 여야 합니다." }
        require(maxSpeechDurationSec == null || maxSpeechDurationSec > 0.0) {
            "Max Speech 는 0(Unlimited) 이거나 0보다 커야 합니다."
        }
        if (maxSpeechDurationSec != null) {
            // 공식 식: max_speech_samples = sr*max - window - 2*pad.
            // 이 값이 0 이하가 되면 매 frame 마다 강제 분할이 일어나 의미가 없다. 미리 막는다.
            val minimumUsefulSec =
                SILERO_WINDOW_SAMPLES.toDouble() / VAD_SAMPLE_RATE +   // change-hyungchul-20261105-1915 : SileroVadProcessor 상수 → 이 파일 상수(domain 이 엔진 클래스를 보지 않게)
                    2.0 * speechPadMs / 1000.0
            require(maxSpeechDurationSec > minimumUsefulSec) {
                String.format(
                    Locale.US,
                    "Max Speech 는 현재 Padding 기준 %.3f 초보다 커야 합니다.",
                    minimumUsefulSec,
                )
            }
        }
    }
}

/** 원본 PCM 기준 sample 좌표. endSample 은 exclusive(그 sample 은 포함하지 않음). */
data class VadSegment(
    val startSample: Int,
    val endSample: Int,
)

/**
 * 목적: 파일 1건의 VAD 처리 결과를 나른다.
 * 비고: ByteArray 를 담으므로 data class 로 만들지 않는다(equals/hashCode 가 참조 비교라 오해를 부른다).
 */
class VadResult(
    /** speech 구간만 이어 붙인 16 kHz/mono/PCM16 LE */
    val pcm: ByteArray,
    /** padding 까지 적용된 최종 구간 목록 */
    val segments: List<VadSegment>,
    /** VAD 추론 + PCM 재구성에 걸린 시간(ms) */
    val processMs: Long,
    val originalSamples: Int,
    val outputSamples: Int,
    /** padding 적용 전, 순수하게 검출된 speech sample 합 */
    val detectedSpeechSamples: Int,
    // add-hyungchul-20260914-2130 : 구간 사이에 끼워 넣은 무음 sample 합(계측/CSV 용)
    val joinSilenceSamples: Int = 0,
    /**
     * add-hyungchul-20260914-2350
     * Speech EPD 가 쓸 "안전한 절단 후보" 좌표(출력 PCM 기준 sample).
     * 구간 사이 무음 한가운데이므로 여기서 자르면 말이 잘리지 않는다.
     */
    val seams: List<Int> = emptyList(),
) {
    val originalSec: Double get() = originalSamples / SAMPLE_RATE_D
    val outputSec: Double get() = outputSamples / SAMPLE_RATE_D
    val detectedSpeechSec: Double get() = detectedSpeechSamples / SAMPLE_RATE_D
    // add-hyungchul-20260914-2130 : 끼워 넣은 무음 길이(초)
    val joinSilenceSec: Double get() = joinSilenceSamples / SAMPLE_RATE_D
    val removedSec: Double get() = (originalSec - outputSec).coerceAtLeast(0.0)

    /** 제거 비율(%) — 0 이면 아무것도 안 지웠다는 뜻이다. */
    val removedRatio: Double
        get() = if (originalSamples <= 0) 0.0
        else (originalSamples - outputSamples).coerceAtLeast(0) * 100.0 / originalSamples

    private companion object {
        const val SAMPLE_RATE_D = 16_000.0
    }
}
