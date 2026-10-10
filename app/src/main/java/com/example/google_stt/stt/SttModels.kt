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

// change-hyungchul-20261010-1800 : 2단계 — sherpa-onnx 오프라인 엔진(SHERPA_OFFLINE) 추가
// change-hyungchul-20261011-0031 : 2단계 2-2 — sherpa-onnx 스트리밍 엔진(SHERPA_STREAMING, Nemotron 3.5) 추가
//   (add-hyungchul-20261011-0150 : 2-3 의 Zipformer KO 스트리밍도 SHERPA_STREAMING)
enum class SttEngine { ML_KIT, ANDROID_PLATFORM_ON_DEVICE, SHERPA_OFFLINE, SHERPA_STREAMING }

data class ModelConfig(
    val folder: String,
    val engine: SttEngine,
    val mlKitMode: Int? = null,
    // change-hyungchul-20261009-1530 : PC GUI v7.7 평탄화 결과 폴더 <엔진>_<모델>_<조건> 의 <엔진>·<모델> 이름.
    //   PC 의 Google Cloud 엔진(algo "google")과 섞이지 않게 엔진 이름을 따로 두고,
    //   이름 안에 '_' 를 쓰지 않는다(PC 평가기가 '_' 로 엔진/모델/조건을 나누기 때문).
    val resultEngine: String = "",
    val resultModel: String = "",
    // add-hyungchul-20261010-1800 : sherpa-onnx 모델 정의(SHERPA_OFFLINE 일 때만). 파일·조각 상한·라이선스는 stt/SherpaModels.kt
    val sherpa: SherpaModelSpec? = null,
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
        // change-hyungchul-20261009-1530 : 결과 폴더 이름(google-mlkit_basic_<조건>) 추가
        ModelConfig("base", SttEngine.ML_KIT, SpeechRecognizerOptions.Mode.MODE_BASIC, "google-mlkit", "basic"),
        // change-hyungchul-20261009-1524 : Pixel 10 전용 ML Kit MODE_ADVANCED("advanced") 경로 제거
        // change-hyungchul-20261009-1530 : 결과 폴더 이름(google-platform_ondevice_<조건>) 추가
        ModelConfig("android_ondevice", SttEngine.ANDROID_PLATFORM_ON_DEVICE, null, "google-platform", "ondevice"),
        // add-hyungchul-20261010-1800 : 2단계 2-1 — sherpa-onnx 오프라인 엔진(사용자 결정: Whisper / Qwen3-ASR 0.6B / Omnilingual 300M 먼저)
        //   결과 이름(사용자 결정 "엔진 = 계열-크기"): <엔진>=whisper-base 등, <모델>=sherpa-int8(런타임-정밀도)
        //   → 폴더 whisper-base_sherpa-int8_<조건>/ , CSV whisper-base_<조건>_result.csv (모델마다 CSV 가 따로 생겨 덮어쓰지 않는다)
        sherpaModel("whisper-tiny", SherpaCatalog.WHISPER_TINY),
        sherpaModel("whisper-base", SherpaCatalog.WHISPER_BASE),
        sherpaModel("whisper-small", SherpaCatalog.WHISPER_SMALL),
        sherpaModel("whisper-medium", SherpaCatalog.WHISPER_MEDIUM),
        sherpaModel("whisper-turbo", SherpaCatalog.WHISPER_TURBO),
        sherpaModel("qwen3asr-0.6b", SherpaCatalog.QWEN3_ASR_06B),
        sherpaModel("omnilingual-300m", SherpaCatalog.OMNILINGUAL_300M),
        // add-hyungchul-20261011-0031 : 2단계 2-2 — Nemotron 3.5 스트리밍(사용자 결정 Q26: 기본 받기는 560 ms)
        //   결과 이름: <엔진>=nemotron3.5-<지연>ms, <모델>=sherpa-int8 → nemotron3.5-560ms_sherpa-int8_<조건>/
        // change-hyungchul-20261011-0150 : 5종 → 320 / 560 ms 2종(사용자 결정 Q28 — 80 / 160 / 1120 ms 삭제)
        sherpaModel("nemotron3.5-320ms", SherpaCatalog.NEMOTRON_320MS, SttEngine.SHERPA_STREAMING),
        sherpaModel("nemotron3.5-560ms", SherpaCatalog.NEMOTRON_560MS, SttEngine.SHERPA_STREAMING),
        // add-hyungchul-20261011-0150 : 2단계 2-3 — 상업 조건부 엔진 4종(사용자 결정 Q5: 포함하되 commercial=conditional 표시)
        //   결과 이름(Q20 "엔진 = 계열-크기", '_' 금지): zipformer-ko / zipformer-ko-stream / sensevoice-small / moonshine-tiny-ko
        sherpaModel("zipformer-ko", SherpaCatalog.ZIPFORMER_KO),
        sherpaModel("zipformer-ko-stream", SherpaCatalog.ZIPFORMER_KO_STREAMING, SttEngine.SHERPA_STREAMING),
        sherpaModel("sensevoice-small", SherpaCatalog.SENSE_VOICE_SMALL),
        sherpaModel("moonshine-tiny-ko", SherpaCatalog.MOONSHINE_TINY_KO),
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

    /**
     * add-hyungchul-20261010-1800
     * 목적: sherpa-onnx 모델의 ModelConfig 를 만든다.
     * 입력: name — 결과 엔진 이름이자 mode 컬럼 값("계열-크기", '_' 금지), spec — 모델 정의,
     *       engine — SHERPA_OFFLINE(기본) / SHERPA_STREAMING (change-hyungchul-20261011-0031 : 인자 추가)
     * 리턴: ModelConfig(engine = engine, resultModel = "sherpa-int8")
     */
    private fun sherpaModel(name: String, spec: SherpaModelSpec, engine: SttEngine = SttEngine.SHERPA_OFFLINE) = ModelConfig(
        folder = name,                       // 로그 "모델/경로" · CSV mode 컬럼
        engine = engine,                     // change-hyungchul-20261011-0031 : SHERPA_OFFLINE 고정 → 인자
        resultEngine = name,                 // 결과 폴더 <엔진>
        resultModel = "sherpa-int8",         // 결과 폴더 <모델> = 런타임-정밀도
        sherpa = spec,
    )
}
