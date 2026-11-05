/*
 * 파일명: domain/csv/RunMeta.kt
 * 목적 및 기능:
 * - 작업 1개의 실행 조건 기록(<엔진>_<조건>_run_meta.csv, key,value 두 열)을 만든다(FR-07-04, NFR-07 재현성).
 * - v3 의 키 71개를 같은 이름·같은 순서로 유지하고(PC 쪽 도구 호환), 뒤에 v4 키를 더한다:
 *     run_purpose, download_source, device_key, asset_sha256:<자산 ID>(작업이 쓰는 자산마다 1줄)
 *   (설계 문서가 "신규" 로 적은 app_version·parallel_jobs 는 v3 에 이미 있던 키다.)
 * - 작업·옵션에서 알 수 있는 값은 여기서 계산한다. 기기·런타임에서 읽어야 하는 값(제조사, Android 판, 네트워크, 배터리,
 *   인식 서비스 목록, ONNX Runtime 판, 모델 파일 크기 등)은 호출하는 쪽이 env 맵에 같은 키 이름으로 넣는다. 없으면 빈 칸.
 * - v3 와 달라진 값: algo 는 v3 가 항상 "google" 이었으나 result.csv 의 algo 와 같게 결과 엔진 이름을 쓴다.
 *
 * add-hyungchul-20261106-0054 — [T2-8a]
 */
package com.example.google_stt.domain.csv

import com.example.google_stt.domain.plan.Job          // 작업(엔진·모델·조건·옵션)
import com.example.google_stt.domain.vad.VadKeys       // Silero 방식 VAD 값 꺼내기
import kotlin.math.max                                 // neg_threshold 계산
import kotlin.math.roundToInt                          // 정수 표기

/**
 * 목적: run_meta 를 만들 때 필요한 입력.
 * 필드: job - 작업, runStartedAt - 작업 시작 시각(ISO8601), appVersion - 앱 판, fileCount - 음원 수,
 *       engineVersion - 런타임 판 글자, modelLoadSec - 모델 적재 시간(초), numThreads - 추론 스레드 수(모르면 null),
 *       modelDir - 모델 폴더 경로(없으면 ""), parallelJobs - 동시 작업 수, runPurpose - 실행 목적,
 *       downloadSource - 자산을 어떻게 구했는지(download / import / bundled 등), deviceKey - 기기 식별(RTF 학습 키),
 *       assetSha256 - 자산 ID → 해시 요약(없으면 "unverified"), env - 기기·런타임에서 읽은 값(키 이름은 KEYS 의 것)
 */
data class RunMetaInput(
    val job: Job,                                     // 작업
    val runStartedAt: String,                         // 시작 시각
    val appVersion: String,                           // 앱 판
    val fileCount: Int,                               // 음원 수
    val engineVersion: String,                        // 런타임 판
    val modelLoadSec: Double?,                        // 모델 적재 시간
    val numThreads: Int?,                             // 추론 스레드 수
    val modelDir: String,                             // 모델 폴더
    val parallelJobs: Int,                            // 동시 작업 수
    val runPurpose: String,                           // 실행 목적
    val downloadSource: String,                       // 자산 출처
    val deviceKey: String,                            // 기기 식별
    val assetSha256: Map<String, String> = emptyMap(),// 자산 해시
    val env: Map<String, String> = emptyMap(),        // 기기·런타임 값
)

/** 목적: run_meta 만들기. */
object RunMeta {

    /** 파일 이름 꼬리: <언어 폴더>/<엔진>_<조건>_run_meta.csv */
    const val FILE_SUFFIX = "_run_meta.csv"

    /** v3 의 키 71개(이 순서로 쓴다) */
    val KEYS: List<String> = listOf(
        "run_started_at", "app_version", "manufacturer", "model", "device", "hardware", "soc", "android_release", "sdk_int",
        "build_fingerprint", "abi", "cpu_cores", "net_type_at_start", "airplane_at_start", "thermal_at_start",
        "battery_pct_at_start", "battery_temp_c_at_start", "recognition_services", "default_voice_recognition_service",
        "algo", "mode", "engine", "execution_declared", "lang", "feed_mode", "feed_delay_ms", "file_count", "preflight",
        "vad_enabled", "vad_profile", "vad_model", "vad_threshold", "vad_neg_threshold", "vad_min_speech_ms",
        "vad_min_silence_ms", "vad_speech_pad_ms", "vad_max_speech_sec", "vad_join_silence_ms", "epd_mode", "epd_config",
        "engine_warmup", "stt_runtime", "stt_model_dir", "stt_model_files", "stt_model_sha256", "stt_num_threads",
        "stt_model_load_ms", "stt_chunk_max_sec", "stt_language", "stt_license", "stt_commercial", "onnxruntime_version",
        "silero_model_sha256", "vad_runtime", "vad_model_sha256", "vad_engine", "vad_preset", "vad_params", "vad_license",
        "vad_commercial", "seg_mode", "condition", "result_dir", "ns_enabled", "ns_algorithm", "ns_label", "ns_config",
        "ns_model_sha256", "pipeline_order", "output_variant", "parallel_jobs",
    )

    /** v4 에서 더한 고정 키(뒤에 asset_sha256:<자산 ID> 줄이 이어진다) */
    val NEW_KEYS: List<String> = listOf("run_purpose", "download_source", "device_key")

    /** 처리 단계 순서(pipeline_order 값) */
    private const val PIPELINE = "decode -> noise_reduction -> vad -> seg -> stt"

    /**
     * 목적: run_meta 의 (키, 값) 줄을 만든다.
     * 입력: input - 작업과 실행 정보
     * 출력: 없음
     * 리턴: List<Pair<String, String>> - v3 키 71개(순서 유지) + v4 키 + 자산 해시 줄
     */
    fun rows(input: RunMetaInput): List<Pair<String, String>> {
        val known = computed(input)                                           // 작업·옵션에서 계산한 값
        val out = ArrayList<Pair<String, String>>()
        for (k in KEYS) out += k to (known[k] ?: input.env[k] ?: "")          // 계산값 우선, 다음 env, 없으면 빈 칸
        out += "run_purpose" to input.runPurpose
        out += "download_source" to input.downloadSource
        out += "device_key" to input.deviceKey
        for (id in input.job.assetIds) out += "asset_sha256:$id" to (input.assetSha256[id] ?: "unverified")   // 자산마다 1줄
        return out
    }

    /**
     * 목적: run_meta.csv 파일 내용 전체를 만든다.
     * 입력: input - 작업과 실행 정보 / 출력: 없음 / 리턴: String - BOM + "key,value" + 줄들
     */
    fun format(input: RunMetaInput): String = CsvFormat.keyValue(rows(input))

    /**
     * 목적: 작업·옵션에서 알 수 있는 키의 값을 계산한다.
     * 입력: input - 작업과 실행 정보 / 출력: 없음 / 리턴: Map<String, String> - 키 → 값
     */
    private fun computed(input: RunMetaInput): Map<String, String> {
        val job = input.job; val m = job.model; val vad = job.vad; val opt = job.opt
        val threads = input.numThreads?.let { ", threads=$it" } ?: ""         // 스레드 수를 알 때만 적는다
        val map = LinkedHashMap<String, String>()
        map["run_started_at"] = input.runStartedAt
        map["app_version"] = input.appVersion
        map["algo"] = m.engine                                                // result.csv algo 와 같은 값
        map["mode"] = m.engine                                                // v3 mode = 모델 이름
        map["engine"] = m.runtime.name                                        // SHERPA_OFFLINE / SHERPA_STREAMING / VOSK / MLKIT
        map["execution_declared"] = "ON_DEVICE(${input.engineVersion}, cpu$threads)"
        map["lang"] = job.language.reqLang
        map["feed_mode"] = opt.feed.name                                      // NONE / REALTIME / D50 …
        map["feed_delay_ms"] = opt.feed.delayMs?.toString() ?: ""
        map["file_count"] = input.fileCount.toString()
        map["vad_enabled"] = vad.enabled.toString()
        map["vad_profile"] = if (vad.enabled) vad.conditionToken() else "OFF"
        vad.values[VadKeys.THRESHOLD]?.let { t ->                             // Silero 방식 값이 있는 엔진만
            map["vad_threshold"] = plain(t)
            if (VadKeys.MIN_SPEECH_MS in vad.values) map["vad_neg_threshold"] = plain(max(t - 0.15, 0.01))   // 공식 기본 neg_threshold
        }
        vad.values[VadKeys.MIN_SPEECH_MS]?.let { map["vad_min_speech_ms"] = it.roundToInt().toString() }
        vad.values[VadKeys.MIN_SILENCE_MS]?.let { map["vad_min_silence_ms"] = it.roundToInt().toString() }
        vad.values[VadKeys.PAD_MS]?.let { map["vad_speech_pad_ms"] = it.roundToInt().toString() }
        vad.values[VadKeys.MAX_SPEECH_SEC]?.let { map["vad_max_speech_sec"] = if (it <= 0.0) "Unlimited" else plain(it) }
        if (vad.enabled) map["vad_join_silence_ms"] = vad.joinMs.toString()
        map["epd_mode"] = opt.epd.config?.mode?.name ?: "OFF"
        map["epd_config"] = opt.epd.config?.summary() ?: "OFF"
        map["stt_runtime"] = input.engineVersion
        map["stt_model_dir"] = input.modelDir
        input.numThreads?.let { map["stt_num_threads"] = it.toString() }
        input.modelLoadSec?.let { map["stt_model_load_ms"] = (it * 1000.0).roundToInt().toString() }
        map["stt_chunk_max_sec"] = m.maxChunkSec?.let { "$it.0" } ?: "none"   // v3 표기(29.0 / none)
        map["stt_license"] = m.license
        map["stt_commercial"] = m.commercial.value
        map["vad_engine"] = vad.engine.name
        map["vad_preset"] = vad.conditionToken()
        map["vad_params"] = vad.summary()
        map["vad_license"] = vad.engine.license
        map["vad_commercial"] = vad.engine.commercial
        map["seg_mode"] = opt.seg.name
        map["condition"] = job.condition
        map["result_dir"] = job.resultDirName
        map["ns_enabled"] = (opt.ns.folder != null).toString()
        map["ns_algorithm"] = opt.ns.folder ?: "NONE"
        map["pipeline_order"] = PIPELINE
        map["output_variant"] = job.condition
        map["parallel_jobs"] = input.parallelJobs.toString()
        return map
    }

    /**
     * 목적: 소수를 군더더기 없이 적는다(0.5 → "0.5", 0.35 → "0.35", 20.0 → "20").
     * 입력: v - 값 / 출력: 없음 / 리턴: String - 소수 둘째 자리까지 반올림한 뒤 끝의 0 을 뗀 글자
     */
    private fun plain(v: Double): String =
        CsvFormat.num(v, 2).trimEnd('0').trimEnd('.')
}
