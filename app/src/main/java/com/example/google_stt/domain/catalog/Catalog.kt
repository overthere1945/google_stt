/*
 * 파일명: domain/catalog/Catalog.kt
 * 목적 및 기능:
 * - 앱이 평가하는 STT 모델 19개(sherpa-onnx 16 + Vosk 2 + ML Kit 1)를 코드 한 곳에 정의한다(FR-02-01).
 *   값(패키지 이름·파일·조각 상한·라이선스)은 v3 의 stt/SherpaModels.kt·stt/SttModels.kt 에서 그대로 옮겼다.
 *   조각 상한의 근거(실측·공식 수치)는 v3 SherpaModels.kt 주석에 있다.
 * - 언어 전용 모델은 langSupport 로만 구분한다. 한국어 폴더에 쓸 수 있는 모델 13개, 영어 폴더 15개(ML Kit 제외).
 * - 자산 표(assets)는 CatalogAssets.kt 가 이 모델 목록에서 만든다(add-hyungchul-20261106-0037 : [T2-2a]).
 * - 목록 순서 = 화면 표시 순서. 실행 순서(오프라인 → 스트리밍·Vosk → ML Kit)는 ComboGenerator 가 정한다.
 *
 * add-hyungchul-20261106-0040 — [T2-2]
 */
package com.example.google_stt.domain.catalog

import com.example.google_stt.domain.vad.VadEngine   // add-hyungchul-20261106-0037 : [T2-2a] VAD 엔진 → 자산 ID

/** 목적: 모델 카탈로그(자산 목록은 CatalogAssets.kt 에서 더한다). */
object Catalog {

    private const val SHERPA_INT8 = "sherpa-int8"   // sherpa-onnx int8 모델의 결과 모델 이름(런타임-정밀도)

    /** Zipformer 한국어 transducer 파일(오프라인·스트리밍 패키지가 같은 이름을 쓴다) */
    private val ZIPFORMER_KO_FILES = mapOf(
        FileRole.ENCODER to "encoder-epoch-99-avg-1.int8.onnx",
        FileRole.DECODER to "decoder-epoch-99-avg-1.int8.onnx",
        FileRole.JOINER to "joiner-epoch-99-avg-1.int8.onnx",
        FileRole.TOKENS to "tokens.txt",
    )

    /** transducer 3파일 + 토큰(Nemotron·Parakeet 이 같은 이름을 쓴다) */
    private val TRANSDUCER_FILES = mapOf(
        FileRole.ENCODER to "encoder.int8.onnx",
        FileRole.DECODER to "decoder.int8.onnx",
        FileRole.JOINER to "joiner.int8.onnx",
        FileRole.TOKENS to "tokens.txt",
    )

    /**
     * 목적: sherpa-onnx 모델 정의를 만든다(결과 모델 이름은 sherpa-int8 고정).
     * 입력: engine - 결과 엔진 이름, runtime - 오프라인/스트리밍, family - 계열, pkg - 공식 패키지 이름(= 자산 ID),
     *       files - 역할 → 파일, maxChunkSec - 조각 상한(스트리밍은 null), lang - 언어 범위,
     *       license - 라이선스, commercial - 상업 판정, display - 화면 이름
     * 출력: 없음
     * 리턴: ModelSpec
     */
    private fun sherpa(
        engine: String, runtime: Runtime, family: String, pkg: String, files: Map<String, String>, maxChunkSec: Int?,
        lang: LangSupport, license: String, commercial: Commercial, display: String,
    ) = ModelSpec(engine, SHERPA_INT8, runtime, family, pkg, files, maxChunkSec, lang, license, commercial, display)

    /**
     * 목적: Whisper 다국어 모델 정의를 만든다(파일 이름 규칙 "<size>-encoder.int8.onnx").
     * 입력: size - tiny / base / small / medium / turbo
     * 출력: 없음
     * 리턴: ModelSpec(조각 상한 29초 — sherpa-onnx 가 29.5초에서 자르므로 끝이 잘리지 않게)
     */
    private fun whisper(size: String) = sherpa(
        "whisper-$size", Runtime.SHERPA_OFFLINE, ModelFamily.WHISPER, "sherpa-onnx-whisper-$size",
        mapOf(
            FileRole.ENCODER to "$size-encoder.int8.onnx",
            FileRole.DECODER to "$size-decoder.int8.onnx",
            FileRole.TOKENS to "$size-tokens.txt",
        ),
        29, LangSupport.MULTI, "MIT", Commercial.ALLOWED, "Whisper $size (sherpa-onnx int8)",
    )

    /**
     * 목적: Nemotron 3.5 스트리밍 모델 정의를 만든다.
     * 입력: chunkMs - 지연(청크) 320 / 560
     * 출력: 없음
     * 리턴: ModelSpec(조각 상한 없음)
     */
    private fun nemotron(chunkMs: Int) = sherpa(
        "nemotron3.5-${chunkMs}ms", Runtime.SHERPA_STREAMING, ModelFamily.NEMOTRON,
        "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-${chunkMs}ms-int8-2026-06-11", TRANSDUCER_FILES,
        null, LangSupport.MULTI, "OpenMDW-1.1", Commercial.ALLOWED, "Nemotron 3.5 streaming ${chunkMs}ms (sherpa-onnx int8)",
    )

    /**
     * 목적: Moonshine v1 영어 모델 정의를 만든다.
     * 입력: size - tiny / base
     * 출력: 없음
     * 리턴: ModelSpec(조각 상한 25초 — 서버 moonshine_stt.py 와 같게)
     */
    private fun moonshineEn(size: String) = sherpa(
        "moonshine-$size-en", Runtime.SHERPA_OFFLINE, ModelFamily.MOONSHINE, "sherpa-onnx-moonshine-$size-en-int8",
        mapOf(
            FileRole.PREPROCESSOR to "preprocess.onnx",
            FileRole.ENCODER to "encode.int8.onnx",
            FileRole.UNCACHED_DECODER to "uncached_decode.int8.onnx",
            FileRole.CACHED_DECODER to "cached_decode.int8.onnx",
            FileRole.TOKENS to "tokens.txt",
        ),
        25, LangSupport.EN_ONLY, "MIT", Commercial.ALLOWED, "Moonshine $size EN (sherpa-onnx int8)",
    )

    /**
     * 목적: Vosk small 모델 정의를 만든다(결과 모델 이름 vosk).
     * 입력: engine - 결과 엔진 이름, dir - 공식 모델 폴더 이름(= 자산 ID), lang - 언어 범위, display - 화면 이름
     * 출력: 없음
     * 리턴: ModelSpec(조각 상한 없음)
     */
    private fun vosk(engine: String, dir: String, lang: LangSupport, display: String) = ModelSpec(
        engine, "vosk", Runtime.VOSK, ModelFamily.VOSK, dir,
        mapOf(FileRole.MODEL to "am/final.mdl", FileRole.CONF to "conf/model.conf"),
        null, lang, "Apache-2.0", Commercial.ALLOWED, display,
    )

    /** 모델 목록(19개) */
    val models: List<ModelSpec> = listOf(
        whisper("tiny"), whisper("base"), whisper("small"), whisper("medium"), whisper("turbo"),
        sherpa(   // Qwen3-ASR 0.6B — 조각 상한 30초(공식 수치 없음, 문서 예제는 VAD 로 나눠 넣는다)
            "qwen3asr-0.6b", Runtime.SHERPA_OFFLINE, ModelFamily.QWEN3ASR, "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
            mapOf(
                FileRole.CONV_FRONTEND to "conv_frontend.onnx",
                FileRole.ENCODER to "encoder.int8.onnx",
                FileRole.DECODER to "decoder.int8.onnx",
                FileRole.TOKENIZER to "tokenizer",
            ),
            30, LangSupport.MULTI, "Apache-2.0", Commercial.ALLOWED, "Qwen3-ASR 0.6B (sherpa-onnx int8)",
        ),
        sherpa(   // Omnilingual CTC 300M — 공식 추론이 40초 미만만 받아 39초
            "omnilingual-300m", Runtime.SHERPA_OFFLINE, ModelFamily.OMNILINGUAL,
            "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
            mapOf(FileRole.MODEL to "model.int8.onnx", FileRole.TOKENS to "tokens.txt"),
            39, LangSupport.MULTI, "Apache-2.0", Commercial.ALLOWED, "Omnilingual ASR 300M CTC (sherpa-onnx int8)",
        ),
        nemotron(320), nemotron(560),
        sherpa(   // Zipformer KO 오프라인 — 조각 상한 15초(실측), 상업 조건부
            "zipformer-ko", Runtime.SHERPA_OFFLINE, ModelFamily.ZIPFORMER, "sherpa-onnx-zipformer-korean-2024-06-24",
            ZIPFORMER_KO_FILES, 15, LangSupport.KO_ONLY, "Apache-2.0(icefall)/KsponSpeech", Commercial.CONDITIONAL,
            "Zipformer KO (sherpa-onnx int8) [상업 조건부]",
        ),
        sherpa(   // Zipformer KO 스트리밍 — 상업 조건부
            "zipformer-ko-stream", Runtime.SHERPA_STREAMING, ModelFamily.ZIPFORMER,
            "sherpa-onnx-streaming-zipformer-korean-2024-06-16", ZIPFORMER_KO_FILES,
            null, LangSupport.KO_ONLY, "Apache-2.0(icefall)/KsponSpeech", Commercial.CONDITIONAL,
            "Zipformer KO streaming (sherpa-onnx int8) [상업 조건부]",
        ),
        sherpa(   // SenseVoice Small — 조각 상한 30초, 상업 조건부
            "sensevoice-small", Runtime.SHERPA_OFFLINE, ModelFamily.SENSEVOICE,
            "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
            mapOf(FileRole.MODEL to "model.int8.onnx", FileRole.TOKENS to "tokens.txt"),
            30, LangSupport.MULTI, "FunASR-MODEL-LICENSE", Commercial.CONDITIONAL,
            "SenseVoice Small (sherpa-onnx int8) [상업 조건부]",
        ),
        sherpa(   // Zipformer EN — 조각 상한 15초(실측), decoder 는 fp32(서버·공식 int8 조합과 같게)
            "zipformer-en", Runtime.SHERPA_OFFLINE, ModelFamily.ZIPFORMER, "sherpa-onnx-zipformer-en-2023-06-26",
            mapOf(
                FileRole.ENCODER to "encoder-epoch-99-avg-1.int8.onnx",
                FileRole.DECODER to "decoder-epoch-99-avg-1.onnx",
                FileRole.JOINER to "joiner-epoch-99-avg-1.int8.onnx",
                FileRole.TOKENS to "tokens.txt",
            ),
            15, LangSupport.EN_ONLY, "Apache-2.0(icefall)/LibriSpeech CC-BY-4.0", Commercial.ALLOWED,
            "Zipformer EN (sherpa-onnx int8)",
        ),
        sherpa(   // Parakeet-TDT 0.6B v2 — 조각 상한 30초(실측)
            "parakeet-tdt-0.6b-v2", Runtime.SHERPA_OFFLINE, ModelFamily.PARAKEET, "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
            TRANSDUCER_FILES, 30, LangSupport.EN_ONLY, "CC-BY-4.0", Commercial.ALLOWED,
            "Parakeet-TDT 0.6B v2 EN (sherpa-onnx int8)",
        ),
        moonshineEn("tiny"), moonshineEn("base"),
        vosk("vosk-small-ko", "vosk-model-small-ko-0.22", LangSupport.KO_ONLY, "Vosk small KO 0.22 (Kaldi)"),
        vosk("vosk-small-en", "vosk-model-small-en-us-0.15", LangSupport.EN_ONLY, "Vosk small EN-US 0.15 (Kaldi)"),
        ModelSpec(   // Google ML Kit GenAI Basic — 모델은 시스템이 관리한다(자산 없음), 공급은 REALTIME 고정
            "google-mlkit", "basic", Runtime.MLKIT, ModelFamily.MLKIT, null, emptyMap(),
            null, LangSupport.MULTI, "Google ML Kit 약관", Commercial.ALLOWED, "ML Kit GenAI — Basic (API 31+)",
        ),
    )

    /** add-hyungchul-20261106-0037 : [T2-2a] 자산 표(22개) — 자산 ID → 정의. 해시는 AssetManifest.merge 로 합친다 */
    val assets: Map<String, AssetSpec> = CatalogAssets.all(models)

    /**
     * add-hyungchul-20261106-0037 : [T2-2a]
     * 목적: VAD 엔진이 필요로 하는 자산 ID 를 알려 준다.
     * 입력: engine - VAD 엔진
     * 출력: 없음
     * 리턴: String? - 자산 ID(VAD 를 안 쓰거나 TEN 처럼 APK 에 든 것은 null)
     */
    fun vadAssetId(engine: VadEngine): String? = CatalogAssets.vadAssetId(engine)   // 대응표는 CatalogAssets 에 있다

    /**
     * 목적: 주어진 언어의 폴더에 쓸 수 있는 모델만 고른다(언어 전용 모델 제외).
     * 입력: lang - 평가 언어
     * 출력: 없음
     * 리턴: List<ModelSpec> - 목록 순서 그대로
     */
    fun modelsFor(lang: Language): List<ModelSpec> = models.filter { it.langSupport.supports(lang) }   // 언어가 맞는 것만

    /**
     * 목적: 결과 엔진 이름으로 모델을 찾는다(목록 CSV 의 model 칸 해석용).
     * 입력: engine - 결과 엔진 이름
     * 출력: 없음
     * 리턴: ModelSpec? - 없으면 null
     */
    fun model(engine: String): ModelSpec? = models.firstOrNull { it.engine == engine }   // 이름이 같은 첫 모델
}
