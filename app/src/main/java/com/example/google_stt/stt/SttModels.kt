/*
 * 파일명: stt/SttModels.kt
 * 목적 및 기능:
 * - STT 경로(엔진/모델) 정의, 오디오 공급 속도 정의, 전사 결과(SttOutcome) 자료형을 모은다.
 * - 배치 실행기(BatchRunner)와 각 엔진 전사기(MlKitTranscriber / PlatformOnDeviceTranscriber)가 함께 쓴다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 에 있던 SttEngine / ModelConfig / models / FeedConfig / feeds / SttOutcome 를
 *   기능별 파일 분리를 위해 그대로 옮겼다(내용·순서 변경 없음, private → internal/public 만 변경).
 */
package com.example.google_stt.stt

import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions

enum class SttEngine { ML_KIT, ANDROID_PLATFORM_ON_DEVICE }

data class ModelConfig(
    val folder: String,
    val engine: SttEngine,
    val mlKitMode: Int? = null,
)

// change(add)-hyungchul-20260820
/**
 * 오디오 공급 속도 설정.
 * - REALTIME: 100 ms 분량을 100 ms 마다 → 실제 마이크와 같은 속도. ML Kit 공식 요건.
 *             이 모드에서는 wall ≈ 음원 길이가 되므로 엔진 비교는 tail_ms 로 해야 한다.
 * - FAST    : 15 ms 간격. 배치 처리량 측정용(expo-speech-recognition 의 on-device 기본값).
 * - MAX     : 지연 없이 파이프가 받아주는 최대 속도. 엔진이 감당 못하면 결과가 잘릴 수 있다.
 */
data class FeedConfig(val name: String, val delayMs: Long)

// ─────────────────────────── STT 결과 + 계측 ───────────────────────────
/**
 * 목적: 전사 결과와 지연 계측치를 함께 나른다.
 * - readyMs        : 세션 시작 → 엔진 준비 완료(onReadyForSpeech). 플랫폼 경로만 측정 가능.
 * - firstResultMs  : 세션 시작 → 첫 응답. 체감 응답성(TTFT).
 * - feedMs         : 오디오 공급에 걸린 시간(공급 속도 설정에 좌우됨).
 * - tailMs         : ★ 공급 EOF → 최종 결과. 공급 속도와 무관한 "엔진 처리 지연".
 * - sttWallMs      : 세션 시작 → 최종 결과(전체).
 */
data class SttOutcome(
    val text: String,
    val readyMs: Long = -1,
    val firstResultMs: Long = -1,
    val feedMs: Long = -1,
    val tailMs: Long = -1,
    val sttWallMs: Long = -1,
    val segments: Int = 0,
    val mlkitStatus: String = "",
    val checkStatusMs: Long = -1,
    val downloadMs: Long = -1,
    /** add-hyungchul-20260914-1500: 엔진이 남긴 메시지(NO_SPEECH_DETECTED 등). 실패가 아닐 때만 채운다. */
    val engineNote: String = "",
)

/**
 * 목적: 화면 스피너에 보여 줄 STT 경로 목록과 공급 속도 목록을 한곳에 둔다.
 * 비고: change-hyungchul-20261009-1514 — MainActivity 의 멤버 변수였던 models / feeds 를 그대로 옮겼다.
 */
object SttCatalog {
    val models = listOf(
        ModelConfig("base", SttEngine.ML_KIT, SpeechRecognizerOptions.Mode.MODE_BASIC),
        // change-hyungchul-20261009-1524 : Pixel 10 전용 ML Kit MODE_ADVANCED("advanced") 경로 제거
        ModelConfig("android_ondevice", SttEngine.ANDROID_PLATFORM_ON_DEVICE),
    )

    // change-hyungchul-20260824
    // 실측 결과 15 ms 는 전량 성공(REALTIME 과 전사 텍스트 8/8 완전 일치),
    // 0 ms 는 전량 ERROR_TYPE_AUDIO_BUFFER_OVERFLOW 였다.
    // → 엔진의 최대 처리 속도는 그 사이에 있다. 이진 탐색이 가능하도록 지연값을 세분화한다.
    //   "전량 OK + 전사 텍스트가 REALTIME 기준본과 동일" 을 만족하는 가장 낮은 지연값이
    //   이 엔진의 진짜 RTF( = delay / 100 )가 된다.
    // change-hyungchul-20260914-2350
    //   10 ms 보다 빠르게(=지연 10 ms 미만) 밀어 넣으면 엔진이 동작하지 않는다.
    //   실측에서도 0 ms 는 전량 ERROR_TYPE_AUDIO_BUFFER_OVERFLOW 였다.
    //   그래서 D8 이하와 MAX 를 전부 걷어내고, 실사용 구간만 5단계로 남긴다.
    //   배속(실시간 ×N) 은 100 ms(=1 chunk 분량) / 지연 으로 자동 계산된다.
    val feeds = listOf(
        FeedConfig("REALTIME", 100L),   // 실시간 ×1.0  — ML Kit 공식 문서가 요구하는 속도
        FeedConfig("D50", 50L),         // 실시간 ×2.0
        FeedConfig("D30", 30L),         // 실시간 ×3.3
        FeedConfig("D20", 20L),         // 실시간 ×5.0
        FeedConfig("D10", 10L),         // 실시간 ×10.0 — 현재까지 확인된 하한
    )
}
