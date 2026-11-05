/*
 * 파일명: stt/SherpaModels.kt
 * 목적 및 기능:
 * - sherpa-onnx(오프라인) STT 모델 목록과 모델별 파일·조각 상한·라이선스를 정의한다(2단계 2-1).
 * - 모델 파일은 APK 에 넣지 않는다. PC 에서 tools/download_stt_models.sh 로 받아 adb 로
 *   휴대폰 공용 폴더  /sdcard/Documents/stt_models/<dirName>/  에 넣는다(앱은 '모든 파일 접근' 권한으로 읽는다).
 *   change-hyungchul-20261010-2238 : 사용자 결정 Q24 — 예전(Q18) 앱 전용 폴더 Android/data 는 adb 가 만든 폴더를 앱이 못 읽어 바꿨다.
 *   (sherpa-onnx 는 파일 경로로만 모델을 읽는다).
 * - 파일 이름·폴더 이름은 sherpa-onnx v1.13.7 공식 배포본 그대로다
 *   (확인: kotlin-api/OfflineRecognizer.kt getOfflineModelConfig, Hugging Face csukuangfj/sherpa-onnx-whisper-*,
 *    k2-fsa.github.io/sherpa/onnx/qwen3-asr/pretrained.html, …/omnilingual-asr/models.html).
 *
 * add-hyungchul-20261010-1800
 *
 * add-hyungchul-20261011-0031 — 2단계 2-2: Nemotron 3.5 ASR 스트리밍 0.6B(int8) 5종(80/160/320/560/1120 ms) 추가(사용자 결정 Q25·Q26)
 *   change-hyungchul-20261011-0150 : 2종(320 / 560 ms)으로 줄였다(사용자 결정 Q28 — 80 / 160 / 1120 ms 는 필요 없음)
 *   · 패키지 sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-<지연>-int8-2026-06-11 (encoder/decoder/joiner .int8.onnx + tokens.txt)
 *   · 스트리밍이라 조각 상한이 없다(maxChunkSec = null). 라이선스 OpenMDW-1.1("ready for commercial use", HF 모델 카드)
 *
 * add-hyungchul-20261011-0150 — 2단계 2-3: 상업 조건부 엔진(사용자 결정 Q5 "포함하되 commercial=conditional 표시") 4종
 *   · Zipformer KO(오프라인 transducer) / Zipformer KO 스트리밍(transducer) — k2-fsa 공식 패키지, KsponSpeech 학습
 *   · SenseVoice Small int8(2024-07-17, ITN 지원판) — FunASR MODEL_LICENSE
 *   · Moonshine v2 tiny KO(quantized, .ort) — Moonshine AI Community License(연매출 100만 달러 이상이면 상업 사용 불가, 등록·표시 의무)
 *   · 조각 상한은 이 작업 환경(sherpa 1.13.7 리눅스 JNI + 같은 Kotlin API)에서 휴대폰 kr 음원으로 실측해 정했다(각 정의의 주석)
 *     change-hyungchul-20261011-0340 : sherpa 1.13.8 로 올린 뒤 다시 확인 — Moonshine 9.5 / 10 / 12 / 15 s 조각은 여전히 빈 결과(9 s 정상), 4종 결과 글자는 1.13.7 과 같다
 *
 * add-hyungchul-20261011-0301 — 2단계 2-4: Vosk small ko 0.22 / en-us 0.15 (SherpaFamily.VOSK)
 *   · 런타임은 sherpa 가 아니라 vosk-android(Kaldi)지만, 모델 폴더 위치·필수 파일 점검·run_meta 기록 규칙이 같아 이 정의를 함께 쓴다.
 *   · 모델은 폴더째로 쓰며 필수 파일은 am/final.mdl + conf/model.conf(vosk-api src/model.cc 의 v2 배치 확인 규칙). Apache-2.0(commercial=allowed)
 *
 * change-hyungchul-20261104-1750 — v3.0.0 (사용자 요청 2026-11-04: 서버 stt_all_gui_server 엔진 중 갤럭시에서 도는 것, 상업 사용 가능한 것만)
 *   · 제거: Moonshine v2 tiny KO(MOONSHINE_TINY_KO, 계열 MOONSHINE_V2) — Moonshine Community License 는 연매출 100만 달러 초과 시 상업 사용 불가
 *   · 추가(모두 영어 전용, 서버와 같은 가중치·같은 공식 패키지 — k2-fsa/sherpa-onnx releases "asr-models"):
 *       ZIPFORMER_EN        sherpa-onnx-zipformer-en-2023-06-26 (서버 sherpa_onnx_stt.py zipformer-en, LibriSpeech, icefall Apache-2.0)
 *       PARAKEET_TDT_06B_V2 sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8 (서버 parakeet-tdt-0.6b-v2, NVIDIA CC-BY-4.0)
 *       MOONSHINE_TINY_EN / MOONSHINE_BASE_EN  sherpa-onnx-moonshine-{tiny,base}-en-int8 (서버 moonshine_stt.py 영어 Tiny/Base 와 같은 v1 가중치, MIT)
 *   · 파일 이름 근거: sherpa-onnx v1.13.8 kotlin-api/OfflineRecognizer.kt getOfflineModelConfig(type 21·22·30),
 *     서버 sherpa_onnx_stt.py MODEL_MAP(zipformer-en int8 조합: encoder·joiner int8 + decoder fp32), HF csukuangfj/sherpa-onnx-zipformer-en-2023-06-26 파일 목록
 */
package com.example.google_stt.stt

/** sherpa-onnx 모델 계열(설정 방식이 계열마다 다르다) */
// change-hyungchul-20261011-0031 : NEMOTRON_STREAMING(OnlineRecognizer · transducer) 추가
// change-hyungchul-20261011-0150 : 2-3 계열 4개 추가 + streaming 표시(true = OnlineRecognizer 로 Feed 간격 공급, SherpaStreamingTranscriber 가 맡는다)
enum class SherpaFamily(val streaming: Boolean = false) {
    WHISPER, QWEN3_ASR, OMNILINGUAL, NEMOTRON_STREAMING(true),
    ZIPFORMER, ZIPFORMER_STREAMING(true), SENSE_VOICE,                    // add-hyungchul-20261011-0150 / change-hyungchul-20261104-1750 : MOONSHINE_V2 제거(Moonshine tiny KO 삭제)
    VOSK(true),                                                           // add-hyungchul-20261011-0301 : Vosk(Kaldi) — VoskTranscriber 가 맡는다
    // add-hyungchul-20261104-1750 : 서버와 같은 영어 전용 오프라인 모델 계열
    ZIPFORMER_EN,                                                         // zipformer transducer(영어) — 결과는 result.text 그대로(서버와 같음)
    NEMO_TRANSDUCER,                                                      // NeMo Parakeet-TDT(modelType "nemo_transducer")
    MOONSHINE_V1,                                                         // Moonshine v1(preprocess / encode / uncached·cached decode)
}

/**
 * 목적: sherpa-onnx 오프라인 모델 1개의 정의.
 * - dirName     : 휴대폰 models/ 아래 폴더 이름 = 공식 배포 패키지 이름(tar.bz2 이름에서 확장자 뺀 것)
 * - files       : 역할 → 폴더 안 상대 경로(encoder / decoder / tokens / model / tokenizer …)
 * - maxChunkSec : "엔진별 최대 길이" 모드에서 한 번에 넣는 조각 상한(초)
 *                 (change-hyungchul-20261011-0031 : null = 상한 없음 — 스트리밍 모델은 파일 전체를 스트림 하나로 넣는다)
 * - license / commercial : 결과 CSV·run_meta 에 남기는 가중치 라이선스와 상업 사용 판정
 * - displayName : 화면 Model 스피너 표시 이름
 */
data class SherpaModelSpec(
    val family: SherpaFamily,
    val dirName: String,
    val files: Map<String, String>,
    val maxChunkSec: Double?,                 // change-hyungchul-20261011-0031 : Double → Double?(스트리밍은 null)
    val license: String,
    val commercial: String,
    val displayName: String,
)

/** 역할 이름(files 의 키) — 오타로 다른 키를 찾지 않도록 상수로 둔다 */
object SherpaRole {
    const val ENCODER = "encoder"
    const val DECODER = "decoder"
    const val TOKENS = "tokens"
    const val MODEL = "model"
    const val CONV_FRONTEND = "conv_frontend"
    const val TOKENIZER = "tokenizer"       // Qwen3-ASR 토크나이저 폴더(merges.txt / vocab.json / tokenizer_config.json)
    const val JOINER = "joiner"             // add-hyungchul-20261011-0031 : transducer(Nemotron) joiner
    const val CONF = "conf"                 // add-hyungchul-20261011-0301 : Vosk conf/model.conf(필수 파일 점검용)
    const val PREPROCESSOR = "preprocessor"         // add-hyungchul-20261104-1750 : Moonshine v1 preprocess.onnx
    const val UNCACHED_DECODER = "uncached_decoder" // add-hyungchul-20261104-1750 : Moonshine v1 uncached_decode.int8.onnx
    const val CACHED_DECODER = "cached_decoder"     // add-hyungchul-20261104-1750 : Moonshine v1 cached_decode.int8.onnx
}

/** 모델 목록 */
object SherpaCatalog {

    /**
     * Whisper 조각 상한: sherpa-onnx offline-recognizer-whisper-impl.h 가 입력을 3000 - 50 = 2950 프레임(29.5 초)으로 자른다
     * ("Only waves less than 30 seconds are supported …"). 끝이 잘리지 않게 29 초로 둔다.
     */
    private const val WHISPER_MAX_SEC = 29.0

    /**
     * 목적: Whisper 다국어 모델 정의를 만든다(파일 이름 규칙: <size>-encoder.int8.onnx 등).
     * 입력: size — tiny / base / small / medium / turbo
     * 리턴: SherpaModelSpec
     */
    private fun whisper(size: String) = SherpaModelSpec(
        family = SherpaFamily.WHISPER,
        dirName = "sherpa-onnx-whisper-$size",
        files = mapOf(
            SherpaRole.ENCODER to "$size-encoder.int8.onnx",
            SherpaRole.DECODER to "$size-decoder.int8.onnx",
            SherpaRole.TOKENS to "$size-tokens.txt",
        ),
        maxChunkSec = WHISPER_MAX_SEC,
        license = "MIT",
        commercial = "allowed",
        displayName = "Whisper $size (sherpa-onnx int8)",
    )

    val WHISPER_TINY = whisper("tiny")
    val WHISPER_BASE = whisper("base")
    val WHISPER_SMALL = whisper("small")
    val WHISPER_MEDIUM = whisper("medium")
    val WHISPER_TURBO = whisper("turbo")

    /**
     * Qwen3-ASR 0.6B int8 (Apache-2.0). 조각 상한은 공식 수치가 없어 30 초로 둔다
     * (sherpa 문서 예제는 긴 음원을 VAD 로 나눠 넣는다). 언어는 스트림 옵션 "language" = "Korean"/"English"
     * (PC qwen3_asr_stt.py 와 같은 이름 규칙 — Qwen 공식 SUPPORTED_LANGUAGES).
     */
    val QWEN3_ASR_06B = SherpaModelSpec(
        family = SherpaFamily.QWEN3_ASR,
        dirName = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        files = mapOf(
            SherpaRole.CONV_FRONTEND to "conv_frontend.onnx",
            SherpaRole.ENCODER to "encoder.int8.onnx",
            SherpaRole.DECODER to "decoder.int8.onnx",
            SherpaRole.TOKENIZER to "tokenizer",
        ),
        maxChunkSec = 30.0,
        license = "Apache-2.0",
        commercial = "allowed",
        displayName = "Qwen3-ASR 0.6B (sherpa-onnx int8)",
    )

    /**
     * Meta Omnilingual ASR CTC 300M int8 (Apache-2.0). 언어 지정이 없는 CTC 모델이다.
     * 조각 상한: 공식 omnilingual-asr 추론 파이프라인이 40 초 미만 입력만 받으므로 39 초로 둔다.
     */
    val OMNILINGUAL_300M = SherpaModelSpec(
        family = SherpaFamily.OMNILINGUAL,
        dirName = "sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12",
        files = mapOf(
            SherpaRole.MODEL to "model.int8.onnx",
            SherpaRole.TOKENS to "tokens.txt",
        ),
        maxChunkSec = 39.0,
        license = "Apache-2.0",
        commercial = "allowed",
        displayName = "Omnilingual ASR 300M CTC (sherpa-onnx int8)",
    )

    /**
     * add-hyungchul-20261011-0031
     * 목적: Nemotron 3.5 ASR 스트리밍 0.6B int8 모델 정의를 만든다(공식 패키지 이름 규칙 그대로).
     * 입력: chunkMs — 지연(청크) 320 / 560 (change-hyungchul-20261011-0150 : 공식 패키지는 80 / 160 / 320 / 560 / 1120 이 있고 앱은 2종만 쓴다)
     * 리턴: SherpaModelSpec(maxChunkSec = null — 스트리밍이라 파일 전체를 한 스트림으로 넣는다)
     * 근거: k2-fsa/sherpa-onnx PR #3671(다국어 Nemotron 3.5, 언어는 스트림 옵션 "language"),
     *       패키지 README "Use per-stream language strings such as "en", "ja", or "auto"",
     *       HF nvidia/nemotron-3.5-asr-streaming-0.6b(OpenMDW-1.1, ko-KR transcription-ready, 지연 5단계)
     */
    private fun nemotron(chunkMs: Int) = SherpaModelSpec(
        family = SherpaFamily.NEMOTRON_STREAMING,
        dirName = "sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-${chunkMs}ms-int8-2026-06-11",
        files = mapOf(
            SherpaRole.ENCODER to "encoder.int8.onnx",
            SherpaRole.DECODER to "decoder.int8.onnx",
            SherpaRole.JOINER to "joiner.int8.onnx",
            SherpaRole.TOKENS to "tokens.txt",
        ),
        maxChunkSec = null,                   // 조각 상한 없음(스트리밍)
        license = "OpenMDW-1.1",
        commercial = "allowed",
        displayName = "Nemotron 3.5 streaming ${chunkMs}ms (sherpa-onnx int8)",
    )

    // change-hyungchul-20261011-0150 : 사용자 요청 — 320 / 560 ms 만 남기고 80 / 160 / 1120 ms 는 뺐다(Q28)
    val NEMOTRON_320MS = nemotron(320)        // add-hyungchul-20261011-0031
    val NEMOTRON_560MS = nemotron(560)

    // ─────────── add-hyungchul-20261011-0150 : 2단계 2-3 — 상업 조건부 엔진(commercial = "conditional") ───────────

    /**
     * Zipformer 한국어 transducer 파일 이름(오프라인·스트리밍 패키지가 같은 규칙 — 공식 패키지 안 파일 이름 확인).
     * int8 판을 쓴다(사용자 결정 Q20 "모델 = sherpa-int8").
     */
    private val ZIPFORMER_KO_FILES = mapOf(
        SherpaRole.ENCODER to "encoder-epoch-99-avg-1.int8.onnx",
        SherpaRole.DECODER to "decoder-epoch-99-avg-1.int8.onnx",
        SherpaRole.JOINER to "joiner-epoch-99-avg-1.int8.onnx",
        SherpaRole.TOKENS to "tokens.txt",
    )

    /** Zipformer KO 라이선스 표기 — 학습 코드(icefall) Apache-2.0, 학습 데이터 KsponSpeech(AI Hub) 약관 영향 미확인(조사 문서 5.2) */
    private const val ZIPFORMER_KO_LICENSE = "Apache-2.0(icefall)/KsponSpeech"

    /**
     * Zipformer KO 오프라인(sherpa-onnx-zipformer-korean-2024-06-24). 한국어 전용, 언어 지정 없음.
     * 조각 상한 15 초: 공식 수치가 없어 실측으로 정했다 — 20·30·84 초 조각은 앞 문장이 빠졌고(customer 01 "안녕하세요." 누락),
     * 15 초는 빠짐없이 가장 길게 나왔다(12·10 초도 정상). 결과 text 는 띄어쓰기가 없어 토큰으로 글자를 만든다(SherpaOfflineTranscriber.resultText).
     */
    val ZIPFORMER_KO = SherpaModelSpec(
        family = SherpaFamily.ZIPFORMER,
        dirName = "sherpa-onnx-zipformer-korean-2024-06-24",
        files = ZIPFORMER_KO_FILES,
        maxChunkSec = 15.0,
        license = ZIPFORMER_KO_LICENSE,
        commercial = "conditional",
        displayName = "Zipformer KO (sherpa-onnx int8) [상업 조건부]",
    )

    /**
     * Zipformer KO 스트리밍(sherpa-onnx-streaming-zipformer-korean-2024-06-16). Nemotron 과 같은 transducer 스트리밍 경로(Feed 간격 공급).
     * 결과 text 의 띄어쓰기 삭제도 같아서 토큰으로 글자를 만든다(실측: text 390자 / 토큰 480자, customer 01).
     */
    val ZIPFORMER_KO_STREAMING = SherpaModelSpec(
        family = SherpaFamily.ZIPFORMER_STREAMING,
        dirName = "sherpa-onnx-streaming-zipformer-korean-2024-06-16",
        files = ZIPFORMER_KO_FILES,
        maxChunkSec = null,                   // 조각 상한 없음(스트리밍)
        license = ZIPFORMER_KO_LICENSE,
        commercial = "conditional",
        displayName = "Zipformer KO streaming (sherpa-onnx int8) [상업 조건부]",
    )

    /**
     * SenseVoice Small int8(sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17 — ITN 으로 문장부호·숫자 복원 지원판).
     * 언어 "ko" / "en"(지원: auto, zh, en, ko, ja, yue), ITN 켬(PC sensevoice.py --use-itn 기본과 같게).
     * 조각 상한 30 초: PC 의 FunASR 내장 FSMN-VAD 단일 구간 최대 30 초와 같게(긴 입력은 내용이 빠진다 — 84 초 한 번에: 125자 / 30 초 조각: 337자, customer 01).
     */
    val SENSE_VOICE_SMALL = SherpaModelSpec(
        family = SherpaFamily.SENSE_VOICE,
        dirName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        files = mapOf(
            SherpaRole.MODEL to "model.int8.onnx",
            SherpaRole.TOKENS to "tokens.txt",
        ),
        maxChunkSec = 30.0,
        license = "FunASR-MODEL-LICENSE",
        commercial = "conditional",
        displayName = "SenseVoice Small (sherpa-onnx int8) [상업 조건부]",
    )

    // change-hyungchul-20261104-1750 : MOONSHINE_TINY_KO(sherpa-onnx-moonshine-tiny-ko-quantized-2026-02-27, Moonshine Community License) 제거
    //   — 연매출 100만 달러 초과 시 상업 사용 불가라 '상업 이용 가능한 것만' 기준에 맞지 않는다(사용자 요청 2026-11-04)

    // ─────────── add-hyungchul-20261011-0301 : 2단계 2-4 — Vosk(Kaldi) small 모델 ───────────

    /**
     * 목적: Vosk small 모델 정의를 만든다(공식 모델 이름 그대로, alphacephei.com/vosk/models).
     * 입력: dirName — 모델 폴더 이름(zip 이름에서 확장자 뺀 것), displayName — 화면 이름
     * 리턴: SherpaModelSpec(family = VOSK, maxChunkSec = null — 스트리밍이라 파일 전체를 한 번에 넣는다)
     */
    private fun vosk(dirName: String, displayName: String) = SherpaModelSpec(
        family = SherpaFamily.VOSK,
        dirName = dirName,
        files = mapOf(
            SherpaRole.MODEL to "am/final.mdl",                             // 음향 모델(v2 배치)
            SherpaRole.CONF to "conf/model.conf",                           // 모델 설정(v2 배치)
        ),
        maxChunkSec = null,                   // 조각 상한 없음(스트리밍)
        license = "Apache-2.0",
        commercial = "allowed",
        displayName = displayName,
    )

    /** 한국어 small 0.22 (82 MB, 공식 WER 28.1 — Zeroth test) */
    val VOSK_SMALL_KO = vosk("vosk-model-small-ko-0.22", "Vosk small KO 0.22 (Kaldi)")
    /** 영어(미국) small 0.15 (40 MB, 공식 WER 9.85 — LibriSpeech test-clean) */
    val VOSK_SMALL_EN = vosk("vosk-model-small-en-us-0.15", "Vosk small EN-US 0.15 (Kaldi)")

    // ─────────── add-hyungchul-20261104-1750 : v3.0.0 — 서버와 같은 가중치의 영어 전용 오프라인 모델 4종 ───────────

    /**
     * Zipformer EN 오프라인(sherpa-onnx-zipformer-en-2023-06-26, LibriSpeech 960h). 영어 전용, 언어 지정 없음.
     * 파일 조합은 서버 sherpa_onnx_stt.py MODEL_MAP "zipformer-en" int8 과 같다(공식 int8 명령: encoder·joiner int8 + decoder fp32).
     * 조각 상한 15 초: 공식 수치가 없어 실측으로 정했다(2026-11-04, sherpa-onnx 1.13.8 리눅스 CLI, 패키지 test_wavs 0·1 을 0.5 초 쉼으로 이어 붙인 음원):
     *   15 초 조각은 빠짐없음, 24.3 초(문장 2개)는 첫 문장 뒷부분이 빠짐(66→57 단어), 97 초 통째는 264→193 단어 — Zipformer KO 실측(15 초)과 같은 현상.
     *   서버 sherpa_onnx_stt.py 는 파일 전체를 한 번에 넣는다(긴 음원에서 같은 빠짐이 생길 수 있음). 결과는 구두점 없는 대문자(서버와 같이 원문 그대로 저장).
     */
    val ZIPFORMER_EN = SherpaModelSpec(
        family = SherpaFamily.ZIPFORMER_EN,
        dirName = "sherpa-onnx-zipformer-en-2023-06-26",
        files = mapOf(
            SherpaRole.ENCODER to "encoder-epoch-99-avg-1.int8.onnx",
            SherpaRole.DECODER to "decoder-epoch-99-avg-1.onnx",            // 서버·공식 int8 조합과 같게 decoder 는 fp32
            SherpaRole.JOINER to "joiner-epoch-99-avg-1.int8.onnx",
            SherpaRole.TOKENS to "tokens.txt",
        ),
        maxChunkSec = 15.0,
        license = "Apache-2.0(icefall)/LibriSpeech CC-BY-4.0",
        commercial = "allowed",
        displayName = "Zipformer EN (sherpa-onnx int8)",
    )

    /**
     * NVIDIA Parakeet-TDT 0.6B v2 int8(sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8). 영어 전용, 구두점·대소문자 포함 출력.
     * 공식 Kotlin 예제(getOfflineModelConfig type 30)와 같이 transducer + modelType "nemo_transducer".
     * 조각 상한 30 초: 실측(2026-11-04, sherpa-onnx 1.13.8 리눅스 CLI, 위 Zipformer EN 과 같은 이어 붙인 음원) — 24.3 / 48.7 초는 빠짐없음(66 / 132 단어),
     *   73 초는 198→155 단어, 97 초 통째는 264→156 단어로 뒷부분이 빠졌다. 그래서 서버(파일 전체를 한 번에)와 달리 30 초 이하로 나눠 넣는다.
     * SEG10 이면 10 초 조각. 라이선스 CC-BY-4.0(HF nvidia/parakeet-tdt-0.6b-v2 — 상업·비상업 사용 가능, 출처 표기).
     */
    val PARAKEET_TDT_06B_V2 = SherpaModelSpec(
        family = SherpaFamily.NEMO_TRANSDUCER,
        dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8",
        files = mapOf(
            SherpaRole.ENCODER to "encoder.int8.onnx",
            SherpaRole.DECODER to "decoder.int8.onnx",
            SherpaRole.JOINER to "joiner.int8.onnx",
            SherpaRole.TOKENS to "tokens.txt",
        ),
        maxChunkSec = 30.0,
        license = "CC-BY-4.0",
        commercial = "allowed",
        displayName = "Parakeet-TDT 0.6B v2 EN (sherpa-onnx int8)",
    )

    /**
     * 목적: Moonshine v1 영어 int8 모델 정의를 만든다(sherpa-onnx-moonshine-<size>-en-int8, 공식 Kotlin 예제 type 21·22 와 같은 파일).
     * 입력: size — tiny / base
     * 리턴: SherpaModelSpec(조각 상한 25 초 — 서버 moonshine_stt.py DEFAULT_CHUNK_SEC 25 와 같게, Moonshine 학습 구간 4~30 초 안)
     *   실측(2026-11-04, sherpa-onnx 1.13.8 리눅스 CLI, tiny): 24.3 초 66/66 단어, 97 초 통째는 264 단어 음원에서 410 단어(반복)가 나와 상한이 필요하다.
     * 라이선스: MIT(moonshine LICENSE — 모든 영어 모델과 영어 Tiny·Base 는 MIT)
     */
    private fun moonshineEn(size: String) = SherpaModelSpec(
        family = SherpaFamily.MOONSHINE_V1,
        dirName = "sherpa-onnx-moonshine-$size-en-int8",
        files = mapOf(
            SherpaRole.PREPROCESSOR to "preprocess.onnx",
            SherpaRole.ENCODER to "encode.int8.onnx",
            SherpaRole.UNCACHED_DECODER to "uncached_decode.int8.onnx",
            SherpaRole.CACHED_DECODER to "cached_decode.int8.onnx",
            SherpaRole.TOKENS to "tokens.txt",
        ),
        maxChunkSec = 25.0,
        license = "MIT",
        commercial = "allowed",
        displayName = "Moonshine $size EN (sherpa-onnx int8)",
    )

    val MOONSHINE_TINY_EN = moonshineEn("tiny")
    val MOONSHINE_BASE_EN = moonshineEn("base")
}
