/*
 * 파일명: domain/catalog/ModelSpec.kt
 * 목적 및 기능:
 * - STT 모델 1개의 정의(ModelSpec)와 그 분류(실행 런타임, 상업 사용 판정, 계열 이름, 파일 역할 이름)를 둔다.
 * - v3 의 stt/SherpaModels.kt(SherpaModelSpec) 와 stt/SttModels.kt(ModelConfig) 를 하나로 합친 자료형이다(4단계 2-1).
 *   결과 이름 규칙은 그대로다: 결과 폴더 = "<engine>_<model>_<조건>", engine 에는 '_' 를 쓰지 않는다(PC 평가기 규칙).
 *
 * add-hyungchul-20261106-0033 — [T2-1]
 */
package com.example.google_stt.domain.catalog

/** 목적: 모델을 실행하는 런타임(엔진 어댑터 종류). whisper.cpp 는 v4.1.0 에서 추가한다(D18). */
enum class Runtime {
    SHERPA_OFFLINE,     // sherpa-onnx OfflineRecognizer(공급 속도 없음, 조각 상한 있음)
    SHERPA_STREAMING,   // sherpa-onnx OnlineRecognizer(공급 속도 선택 가능)
    VOSK,               // vosk-android(공급 속도 선택 가능)
    MLKIT,              // ML Kit GenAI Basic(REALTIME 고정, 시스템이 모델 관리)
}

/** 목적: 가중치의 상업 사용 판정. value 는 run_meta·note 에 남기는 글자다. */
enum class Commercial(val value: String) {
    ALLOWED("allowed"),           // 상업 사용 가능
    CONDITIONAL("conditional"),   // 조건부(화면에 "[상업 조건부]" 표시 — FR-02-04)
}

/** 목적: 모델 계열 이름(엔진 어댑터가 설정 방식을 고를 때 쓴다 — 4단계 2-1 의 family 값). */
object ModelFamily {
    const val WHISPER = "whisper"           // Whisper(다국어, language 지정)
    const val QWEN3ASR = "qwen3asr"         // Qwen3-ASR(스트림 옵션 language)
    const val OMNILINGUAL = "omnilingual"   // Omnilingual CTC(언어 지정 없음)
    const val NEMOTRON = "nemotron"         // Nemotron 3.5 스트리밍 transducer
    const val ZIPFORMER = "zipformer"       // Zipformer transducer(한국어 오프라인·스트리밍, 영어 오프라인)
    const val SENSEVOICE = "sensevoice"     // SenseVoice Small
    const val PARAKEET = "parakeet"         // NeMo Parakeet-TDT(modelType nemo_transducer)
    const val MOONSHINE = "moonshine"       // Moonshine v1(영어)
    const val VOSK = "vosk"                 // Vosk(Kaldi)
    const val MLKIT = "mlkit"               // ML Kit GenAI Basic
}

/** 목적: 모델 파일의 역할 이름(ModelSpec.files 의 키). 오타로 다른 키를 찾지 않도록 상수로 둔다(v3 SherpaRole 과 같은 값). */
object FileRole {
    const val ENCODER = "encoder"                     // 인코더
    const val DECODER = "decoder"                     // 디코더
    const val JOINER = "joiner"                       // transducer joiner
    const val TOKENS = "tokens"                       // 토큰 목록
    const val MODEL = "model"                         // 단일 모델 파일(CTC·SenseVoice·Vosk 음향 모델)
    const val CONV_FRONTEND = "conv_frontend"         // Qwen3-ASR 앞단
    const val TOKENIZER = "tokenizer"                 // Qwen3-ASR 토크나이저 폴더
    const val CONF = "conf"                           // Vosk conf/model.conf
    const val PREPROCESSOR = "preprocessor"           // Moonshine v1 preprocess.onnx
    const val UNCACHED_DECODER = "uncached_decoder"   // Moonshine v1 uncached_decode.int8.onnx
    const val CACHED_DECODER = "cached_decoder"       // Moonshine v1 cached_decode.int8.onnx
}

/**
 * 목적: STT 모델 1개의 정의.
 * 필드:
 * - engine      : 결과 엔진 이름('_' 금지) 예 whisper-base — 결과 폴더·CSV algo 열
 * - model       : 결과 모델 이름 sherpa-int8 / vosk / basic — 결과 폴더·CSV model 열
 * - runtime     : 실행 런타임
 * - family      : 계열 이름(ModelFamily 상수)
 * - assetId     : 필요한 자산 ID(AssetSpec.id). ML Kit 은 시스템이 관리하므로 null
 * - files       : 역할(FileRole) → 자산 폴더 안 상대 경로. 자산이 없으면 빈 맵
 * - maxChunkSec : 오프라인 엔진이 한 번에 받는 조각 상한(초). 스트리밍·Vosk·ML Kit 은 null(FR-02-07, 화면에서 바꾸지 않는다)
 * - langSupport : 언어 지원 범위(언어 전용 판정의 유일한 원천)
 * - license     : 가중치 라이선스 표기
 * - commercial  : 상업 사용 판정
 * - displayName : 화면에 보여 줄 이름
 */
data class ModelSpec(
    val engine: String,                // 결과 엔진 이름
    val model: String,                 // 결과 모델 이름
    val runtime: Runtime,              // 런타임
    val family: String,                // 계열
    val assetId: String?,              // 자산 ID(없으면 null)
    val files: Map<String, String>,    // 역할 → 파일 경로
    val maxChunkSec: Int?,             // 조각 상한(초)
    val langSupport: LangSupport,      // 언어 지원 범위
    val license: String,               // 라이선스
    val commercial: Commercial,        // 상업 사용 판정
    val displayName: String,           // 화면 이름
) {
    /** 오프라인 엔진인지(동시 실행 1개 제한 대상, 공급 속도 없음) */
    val isOffline: Boolean get() = runtime == Runtime.SHERPA_OFFLINE

    /** 공급 속도(Feed)를 고를 수 있는 엔진인지 — 스트리밍·Vosk 만(FR-03-05) */
    val feedSelectable: Boolean get() = runtime == Runtime.SHERPA_STREAMING || runtime == Runtime.VOSK
}
