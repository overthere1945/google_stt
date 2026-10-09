/*
 * 파일명: stt/SttConstants.kt
 * 목적 및 기능:
 * - Google 계열 STT 배치 실행에 쓰는 상수(PCM 규격, 타임아웃, SODA 세션 한도, 잘림 판정 기준 등)를 모은다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 companion object 에 있던 상수를 그대로 옮겼다(값·주석 변경 없음).
 *   여러 파일(BatchRunner / GoogleSttSession / MlKitTranscriber / PlatformOnDeviceTranscriber)이
 *   함께 쓰므로 패키지 최상위 상수로 둔다. private → 공개(const val) 만 바뀌었다.
 *   (SPEECH_SERVICE_PACKAGE 는 MainActivity, DPDFNET_* 는 NoiseUi 로 옮겼다)
 */
package com.example.google_stt.stt

/** 16 kHz × 2바이트 = 32,000 바이트/초 */
const val BYTES_PER_SEC = 32_000

/** 100 ms 분량 = 3,200 바이트 */
const val CHUNK_BYTES = 3_200

/** 중간 저장 주기(파일 수) */
const val CSV_FLUSH_EVERY = 10

/**
 * 플랫폼 경로에서 partial 결과를 받을지 여부.
 * true 로 두면 first_result_ms(TTFT)를 제대로 잴 수 있다. partial 텍스트는 결과에 넣지 않는다.
 * 이전 실행과 결과를 엄격히 동일하게 맞춰야 한다면 false 로 되돌린다.
 */
const val REQUEST_PARTIAL_RESULTS = true

// add-hyungchul-20260914-1500
/** 인식기 close() 를 기다려 주는 최대 시간(ms). 넘으면 배치를 계속 진행한다. */
const val RECOGNIZER_CLOSE_TIMEOUT_MS = 5_000L

/** 이론 공급시간의 몇 배를 넘으면 "공급 정체" 로 보고 경고할지. */
const val FEED_STALL_WARN_RATIO = 2.0

// add-hyungchul-20260914-2130 : STT 타임아웃 계산 상수 (sttTimeoutSec() 주석에 근거 정리)
/** 타임아웃 고정 여유(초). 엔진 준비 + 공급 EOF 후 처리분(실측 최대 0.287초)을 덮는다. */
const val STT_TIMEOUT_FIXED_SEC = 45.0

/** (오디오 또는 이론 공급시간 중 큰 값) 에 곱할 배율. 관측 최악 1.67배 대비 1.5배 여유. */
const val STT_TIMEOUT_FACTOR = 2.5

/** 아주 짧은 음원에서도 보장할 최소 타임아웃(초). */
const val STT_TIMEOUT_MIN_SEC = 90L

/**
 * ML Kit segments 결함 경계(초).
 * 실측: 60.72초·61.32초는 정상, 67.06초는 앞부분 유실. 보수적으로 61초를 경계로 쓴다.
 */
const val MLKIT_SEGMENT_TRUNCATION_SEC = 61.0

// add-hyungchul-20260914-2350
/**
 * 잘림 판정에 쓰는 글자 밀도 하한(자/초).
 * 실측: 정상 행은 7.1~7.4자/초, 잘린 행은 0.3~1.6자/초 였다. 그 사이를 3.0 으로 가른다.
 * 영어(공백 포함 약 12자/초)에도 안전한 값이다.
 */
const val MLKIT_TRUNCATION_CHARS_PER_SEC = 3.0

/** 사전 점검(warm-up) 에 쓸 무음 길이(초). */
const val WARMUP_SECONDS = 1

/**
 * 사전 점검의 공급 지연(ms/chunk).
 * change-hyungchul-20260915-1400 : 10 ms(×10) → 100 ms(실시간).
 * 공식 문서가 요구하는 속도가 실시간이므로 점검도 같은 속도로 한다. 1초 무음이니 1초면 끝난다.
 */
const val WARMUP_FEED_DELAY_MS = 100L

/**
 * 사전 점검 제한 시간(ms). 이 안에 아무 응답이 없으면 엔진이 먹통이다.
 * change-hyungchul-20260915-1400 : checkStatus 제한(15초)보다 길게 잡아야
 * "checkStatus 무응답" 이라는 더 정확한 원인이 먼저 잡힌다.
 */
const val WARMUP_TIMEOUT_MS = 30_000L

/**
 * 연속 몇 건이 실패하면 배치를 끝낼지.
 * change-hyungchul-20260916-1000 : 3 → 2.
 * 실행 3회 전부 "한 번 죽으면 끝까지 회복 없음" 이었다. 3건까지 기다릴 이유가 없다.
 * 세션 한도를 이미 넘긴 뒤라면 이 값과 무관하게 1건에서 바로 끝낸다.
 */
const val CONSECUTIVE_TIMEOUT_ABORT = 2

// add-hyungchul-20260915-1400
/** checkStatus() 응답 제한(ms). 정상이면 수십 ms 안에 온다. */
const val FEATURE_STATUS_TIMEOUT_MS = 15_000L

/** 모델 다운로드 제한(ms). 최초 1회만 걸리는 경로다. */
const val MODEL_DOWNLOAD_TIMEOUT_MS = 600_000L

/** stopRecognition() 을 기다려 줄 시간(ms). */
const val STOP_RECOGNITION_TIMEOUT_MS = 5_000L

// add-hyungchul-20260915-2200 : SODA 세션 한도 대응
// change-hyungchul-20260916-1800 : 2026-09-16 logcat 분석으로 엔진과 정지 지점을 확정했다.
//   엔진 = com.google.android.tts 안의 SODA (AICore 아님. "aicore streaming: false" 로 확인)
//   정지 지점 = SodaSpeechRecognizer.SodaDetectionHandler.blockingReconnect
//     정상:  Initialize Soda with language pack directory
//            → blockingReconnect → ConcurrentSodaManager#connect → Creating SODA → ...
//     먹통:  Initialize Soda with language pack directory 까지만 찍히고 그 다음이 전혀 없다.
//            엔진 스스로도 "Recognition not started, drop cancelRecognition" 이라고 남긴다.
//   회복 조건 = tts 프로세스 재시작뿐. 18분을 기다려도 같은 PID 에서는 회복되지 않았다.
/**
 * 실측으로 확인한 세션 한도.
 * D20 실행과 REALTIME 실행 모두 "35세션 성공 → 36번째부터 먹통" 으로 동일했다.
 * 커뮤니티 보고(googlesamples/mlkit issue #1070)의 "약 36~41회" 와도 일치한다.
 */
const val QUOTA_BUDGET_SESSIONS = 35

/** 이 세션 수에 닿으면 미리 경고한다(한도의 약 80%). */
const val QUOTA_WARN_SESSIONS = 28

/**
 * 할당량에 막혔을 때 쉬어 줄 시간(ms).
 * 위 이슈에서 약 2.5분이면 할당량이 거의 원래 크기로 회복된다고 보고되었다. 여유를 두어 3분으로 잡는다.
 */
const val QUOTA_COOLDOWN_MS = 180_000L

/** 파일 1건당 할당량 재시도 횟수. */
const val QUOTA_RETRY_MAX = 1
