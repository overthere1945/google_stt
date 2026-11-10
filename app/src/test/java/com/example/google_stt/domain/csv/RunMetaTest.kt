/*
 * 파일명: domain/csv/RunMetaTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-CSV-08] run_meta 가 v3 의 키 71개를 같은 순서로 담고 v4 키를 뒤에 더하는지 확인한다(FR-07-04).
 *   v3 키 목록은 기준 결과(docs/baseline_v3/)의 실제 run_meta.csv 에서 읽어 대조한다.
 * - 작업·옵션에서 계산되는 값이 v3 기록과 같은 표기인지 확인한다.
 *
 * add-hyungchul-20261106-0054 — [T2-8a]
 */
package com.example.google_stt.domain.csv

import com.example.google_stt.domain.catalog.Catalog          // 모델 찾기
import com.example.google_stt.domain.catalog.Language         // 평가 언어
import com.example.google_stt.domain.condition.FeedChoice     // 공급 속도
import com.example.google_stt.domain.condition.RunOptions     // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder          // 언어 폴더
import com.example.google_stt.domain.lang.LangSource          // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                 // 작업
import com.example.google_stt.domain.vad.VadEngine            // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog     // 프리셋 목록
import com.example.google_stt.domain.vad.VadSetting           // VAD 설정
import java.io.File                                           // 기준 결과 읽기
import org.junit.Assert.assertEquals                          // 값 비교
import org.junit.Assert.assertTrue                            // 조건 확인
import org.junit.Assume.assumeTrue                            // 기준 결과가 없으면 건너뛰기
import org.junit.Test                                         // JUnit 4 시험 표시

/** 목적: run_meta 시험. */
class RunMetaTest {

    private val kr = LangFolder("kr", "root/kr", Language.KO, LangSource.AUTO_SUBFOLDER)   // 한국어 폴더

    /**
     * 목적: 이름으로 고정 프리셋 설정을 만든다.
     * 입력: engine - 엔진, name - 프리셋 이름 / 출력: 없음 / 리턴: VadSetting
     */
    private fun preset(engine: VadEngine, name: String): VadSetting {
        val p = VadPresetCatalog.presets(engine).first { it.name == name }   // 이름이 같은 프리셋
        return VadSetting(engine, p, p.values)
    }

    /**
     * 목적: 시험용 입력을 만든다.
     * 입력: job - 작업 / 출력: 없음 / 리턴: RunMetaInput
     */
    private fun input(job: Job) = RunMetaInput(
        job = job, runStartedAt = "2026-11-06T01:02:03.000+09:00", appVersion = "4.0.0-dev", fileCount = 1,
        engineVersion = "sherpa-onnx 1.13.8 (static-link onnxruntime)", modelLoadSec = 0.454, numThreads = 4,
        modelDir = "/storage/emulated/0/Android/data/com.example.google_stt/files/models/sherpa-onnx-whisper-base",
        parallelJobs = 1, runPurpose = "timing", downloadSource = "download", deviceKey = "SM-S928N/SM8650",
        assetSha256 = mapOf("sherpa-onnx-whisper-base" to "abc123"),
        env = mapOf("manufacturer" to "samsung", "model" to "SM-S928N", "onnxruntime_version" to "1.25.1", "algo" to "무시되어야 함"),
    )

    /** v3 기준 결과의 run_meta 파일(저장소에 없으면 대조 시험을 건너뛴다 — 원격에 올릴지는 사용자 결정) */
    private val v3File = File("../docs/baseline_v3/kr/20261105_REALTIME/whisper-base_SILERO_run_meta.csv")

    /**
     * 목적: v3 run_meta 파일을 (키, 값) 목록으로 읽는다(머리줄 제외. v3 는 값을 큰따옴표로 감싸 썼다).
     * 입력: 없음 / 출력: 없음 / 리턴: List<Pair<String, String>>
     */
    private fun v3Rows(): List<Pair<String, String>> =
        v3File.readLines(Charsets.UTF_8).map { it.removePrefix("\uFEFF") }.filter { it.isNotEmpty() }.drop(1).map { line ->
            val value = line.substringAfter(',')                              // 첫 쉼표 뒤가 값
            val plain = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) value.substring(1, value.length - 1).replace("\"\"", "\"") else value
            line.substringBefore(',') to plain
        }

    /**
     * 목적: [UT-CSV-08] v3 키 71개가 같은 순서로 맨 앞에 있고, 그 뒤에 v4 키와 자산 해시 줄이 온다. 키는 겹치지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun keys_keepV3OrderThenNewKeys() {
        assumeTrue("기준 결과 파일이 없어 건너뜀", v3File.isFile)
        val v3 = v3Rows().map { it.first }
        assertEquals(71, v3.size)
        assertEquals(v3, RunMeta.KEYS)                                        // 이름과 순서가 v3 실제 파일과 같다
        val job = Job(kr, Catalog.model("whisper-base")!!, preset(VadEngine.SILERO, "SILERO"), RunOptions())
        val keys = RunMeta.rows(input(job)).map { it.first }
        assertEquals(v3, keys.take(71))
        assertEquals(listOf("run_purpose", "download_source", "device_key",
            "asset_sha256:sherpa-onnx-whisper-base", "asset_sha256:silero-vad"), keys.drop(71))
        assertEquals(keys.size, keys.toSet().size)                            // 키 중복 없음
    }

    /**
     * 목적: 계산되는 값이 v3 기록과 같은 표기다(오프라인 whisper-base × SILERO — 기준 결과 파일의 값과 대조).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun values_matchV3Notation() {
        val job = Job(kr, Catalog.model("whisper-base")!!, preset(VadEngine.SILERO, "SILERO"), RunOptions())
        val row = RunMeta.rows(input(job)).toMap()
        assumeTrue("기준 결과 파일이 없어 건너뜀", v3File.isFile)
        val v3 = v3Rows().toMap()
        val same = listOf("mode", "engine", "lang", "file_count", "vad_enabled", "vad_profile", "vad_threshold", "vad_neg_threshold",
            "vad_min_speech_ms", "vad_min_silence_ms", "vad_speech_pad_ms", "vad_max_speech_sec", "vad_join_silence_ms",
            "epd_mode", "epd_config", "stt_runtime", "stt_num_threads", "stt_model_load_ms", "stt_chunk_max_sec", "stt_license",
            "stt_commercial", "vad_engine", "vad_preset", "vad_params", "vad_license", "vad_commercial", "seg_mode", "condition", "result_dir",
            "ns_enabled", "ns_algorithm", "pipeline_order", "output_variant", "parallel_jobs")
        for (k in same) assertEquals(k, v3.getValue(k), row.getValue(k))      // v3 파일과 글자까지 같다
        assertEquals("whisper-base", row["algo"])                             // v3 는 "google" — result.csv 와 같게 바꿨다(env 값보다 계산값 우선)
        assertEquals("NONE", row["feed_mode"])                                // 오프라인은 공급 속도가 없다(v3 는 REALTIME 으로 적었다)
        assertEquals("", row["feed_delay_ms"])
        assertEquals("4.0.0-dev", row["app_version"])
        assertEquals("samsung", row["manufacturer"])                          // env 에서 온 값
        assertEquals("1.25.1", row["onnxruntime_version"])
        assertEquals("", row["build_fingerprint"])                            // env 에 없으면 빈 칸
        assertEquals("timing", row["run_purpose"])
        assertEquals("abc123", row["asset_sha256:sherpa-onnx-whisper-base"])
        assertEquals("unverified", row["asset_sha256:silero-vad"])            // 해시를 모르면 미검증 표시
    }

    /**
     * 목적: 스트리밍 + pyannote + 공급 속도 — Silero 방식이 아닌 VAD 는 threshold 칸이 비고, Feed 토큰이 조건에 들어간다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun streamingJob_feedAndNonSileroVad() {
        val job = Job(kr, Catalog.model("vosk-small-ko")!!, preset(VadEngine.PYANNOTE, "PYANNOTE_S500"), RunOptions(feed = FeedChoice.REALTIME))
        val row = RunMeta.rows(input(job).copy(numThreads = null, modelLoadSec = null)).toMap()
        assertEquals("VOSK", row["engine"])
        assertEquals("REALTIME", row["feed_mode"]); assertEquals("100", row["feed_delay_ms"])
        assertEquals("", row["vad_threshold"]); assertEquals("", row["vad_neg_threshold"]); assertEquals("", row["vad_min_speech_ms"])
        assertEquals("200", row["vad_join_silence_ms"])
        assertEquals("none", row["stt_chunk_max_sec"])
        assertEquals("", row["stt_num_threads"]); assertEquals("", row["stt_model_load_ms"])
        assertEquals("PYANNOTE_S500_FEED_REALTIME", row["condition"])
        assertEquals("vosk-small-ko_vosk_PYANNOTE_S500_FEED_REALTIME", row["result_dir"])
        assertEquals("ON_DEVICE(sherpa-onnx 1.13.8 (static-link onnxruntime), cpu)", row["execution_declared"])
        val csv = RunMeta.format(input(job))
        assertTrue(csv.startsWith(CsvFormat.BOM + "key,value\r\nrun_started_at,2026-11-06T01:02:03.000+09:00\r\n"))
    }
}
