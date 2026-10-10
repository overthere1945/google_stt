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
 */
package com.example.google_stt.stt

/** sherpa-onnx 모델 계열(설정 방식이 계열마다 다르다) */
enum class SherpaFamily { WHISPER, QWEN3_ASR, OMNILINGUAL }

/**
 * 목적: sherpa-onnx 오프라인 모델 1개의 정의.
 * - dirName     : 휴대폰 models/ 아래 폴더 이름 = 공식 배포 패키지 이름(tar.bz2 이름에서 확장자 뺀 것)
 * - files       : 역할 → 폴더 안 상대 경로(encoder / decoder / tokens / model / tokenizer …)
 * - maxChunkSec : "엔진별 최대 길이" 모드에서 한 번에 넣는 조각 상한(초)
 * - license / commercial : 결과 CSV·run_meta 에 남기는 가중치 라이선스와 상업 사용 판정
 * - displayName : 화면 Model 스피너 표시 이름
 */
data class SherpaModelSpec(
    val family: SherpaFamily,
    val dirName: String,
    val files: Map<String, String>,
    val maxChunkSec: Double,
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
}
