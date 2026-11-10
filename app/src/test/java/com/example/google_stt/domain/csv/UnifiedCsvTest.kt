/*
 * 파일명: domain/csv/UnifiedCsvTest.kt (app/src/test)
 * 목적 및 기능:
 * - result.csv(74열)와 device.csv 의 열 정의를 확인한다(D10, D15, NFR-14).
 *     · UT-CSV-01 열 이름 74개가 서버 골든 목록과 순서까지 같다
 *     · UT-CSV-02 OK / NO_MATCH / ERROR 행 모두 74칸
 *     · UT-CSV-06 RTF 계산(음원 길이 0 이면 빈 칸)
 *     · UT-CSV-07 device.csv 열 이름 중복 없음, result.csv 와 키로 맞물림
 *
 * add-hyungchul-20261106-0051 — [T2-8]
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
import java.io.File                                           // 골든 파일 읽기
import org.junit.Assert.assertEquals                          // 값 비교
import org.junit.Assert.assertTrue                            // 조건 확인
import org.junit.Test                                         // JUnit 4 시험 표시

/** 목적: 통일 CSV·보조 CSV 열 시험. */
class UnifiedCsvTest {

    private val kr = LangFolder("kr", "root/kr", Language.KO, LangSource.AUTO_SUBFOLDER)   // 한국어 폴더
    private val sileroPreset = VadPresetCatalog.presets(VadEngine.SILERO).first { it.name == "SILERO" }
    private val silero = VadSetting(VadEngine.SILERO, sileroPreset, sileroPreset.values)   // SILERO 프리셋
    private val novad = VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())
    private val dev = DeviceSample(deviceName = "SM-S928N SM8650", hostId = "SM-S928N/SM8650", pssPeakMb = 612.34,
        cpuTimeSec = 31.5, cpuPercentAvg = 380.26, battPctStart = 100, battPctEnd = 99, netRxDelta = 0, netTxDelta = 0)

    /**
     * 목적: 시험용 파일 기록을 만든다(오프라인 whisper-base × SILERO, 84.13초 음원).
     * 입력: 없음 / 출력: 없음 / 리턴: FileRecord
     */
    private fun okRecord() = FileRecord(
        runId = "20261106_010203", startedAt = "2026-11-06T01:02:03+09:00",
        job = Job(kr, Catalog.model("whisper-base")!!, silero, RunOptions()),
        relDir = "Customer_Service_KR", file = "01.wav",
        audio = AudioInfo(".wav", 2_703_204, 84.13, 16_000, 1),
        timings = Timings(decodeSec = 0.031, nsSec = 0.0, vadSec = 1.2, transcribeSec = 8.413, elapsedSec = 9.9),
        stt = SttOutcome("안녕하세요.\n예약, \"토요일\" 가능할까요?", 3, SttStatus.OK, tailMs = 8413),
        vad = VadSummary(21, 80.252), device = dev, engineVersion = "sherpa-onnx 1.13.8 static",
        workers = 1, modelLoadSec = 0.454, chunks = 3, chunkMaxSec = 28.5,
    )

    /**
     * 목적: CSV 글을 칸 목록의 목록으로 나눈다(RFC 4180 — 큰따옴표 안의 쉼표·줄바꿈을 한 칸으로).
     * 입력: csv - BOM 을 뗀 CSV 글 / 출력: 없음 / 리턴: List<List<String>> - 줄마다의 칸
     */
    private fun cells(csv: String): List<List<String>> {
        val rows = ArrayList<List<String>>(); var row = ArrayList<String>(); val cur = StringBuilder(); var quoted = false; var i = 0
        while (i < csv.length) {
            val ch = csv[i]
            when {
                quoted && ch == '"' && i + 1 < csv.length && csv[i + 1] == '"' -> { cur.append('"'); i++ }   // "" → "
                ch == '"' -> quoted = !quoted                                  // 따옴표 열고 닫기
                !quoted && ch == ',' -> { row.add(cur.toString()); cur.setLength(0) }   // 칸 끝
                !quoted && ch == '\r' -> Unit                                  // CRLF 의 CR 은 넘긴다
                !quoted && ch == '\n' -> { row.add(cur.toString()); cur.setLength(0); rows.add(row); row = ArrayList() }   // 줄 끝
                else -> cur.append(ch)
            }
            i++
        }
        return rows
    }

    /**
     * 목적: [UT-CSV-01] 열 이름 74개가 서버 헤더 사본(골든 파일)과 순서까지 같다.
     * 입력: 없음(작업 폴더는 모듈 폴더 app/) / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun columns_matchGoldenHeader() {
        val golden = File("../docs/AI_analyze/golden/STT_CSV_HEADER_v2.0.txt").readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }
        assertEquals(74, golden.size)
        assertEquals(golden, UnifiedCsv.columns.map { it.name })              // 이름과 순서
    }

    /**
     * 목적: [UT-CSV-02] OK / NO_MATCH / ERROR 행 모두 74칸이고, 따옴표가 든 전사문도 한 칸에 들어간다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun everyRow_has74Cells() {
        val ok = okRecord()
        val noMatch = ok.copy(file = "02.wav", stt = SttOutcome("", 0, SttStatus.NO_MATCH, tailMs = 100), note = "VAD_NO_SPEECH")
        val error = ok.copy(file = "03.wav", audio = AudioInfo(".wav", 10, 0.0, 0, 0),
            timings = Timings(0.0, 0.0, 0.0, null, 0.01), stt = SttOutcome("", 0, SttStatus.ERROR), vad = null, chunks = 0,
            note = "ERR_DECODE: bad header, unexpected \"RIFX\"")
        val csv = UnifiedCsv.format(listOf(ok, noMatch, error))
        assertTrue(csv.startsWith(CsvFormat.BOM + "run_id,started_at,algo,"))  // BOM + 머리줄
        val table = cells(csv.removePrefix(CsvFormat.BOM))
        assertEquals(4, table.size)                                            // 머리줄 + 3행
        assertTrue(table.all { it.size == 74 })                                // 모든 줄 74칸
        val h = table[0]
        fun v(row: Int, name: String) = table[row][h.indexOf(name)]            // 줄·열 이름으로 칸 읽기
        assertEquals(listOf("OK", "NO_MATCH", "ERROR"), (1..3).map { v(it, "status") })
        assertEquals("안녕하세요. 예약, \"토요일\" 가능할까요?", v(1, "text"))    // 한 줄로, 따옴표 보존
        assertEquals("ERR_DECODE: bad header, unexpected \"RIFX\"", v(3, "note"))
        assertEquals("", v(3, "rtf"))                                          // 음원 길이 0 → 빈 칸
        assertEquals("", v(3, "transcribe_sec"))                               // 엔진을 부르지 못함
        assertEquals("0.000", v(3, "billed_sec"))                              // 엔진을 부르지 못하면 0
        assertEquals("", v(3, "sample_rate"))
        assertEquals("0", v(2, "chars"))
        assertEquals("", v(2, "text_sha1"))
    }

    /**
     * 목적: 2단계 6장 매핑 값 — 식별·언어·환경·시간·비용·결과.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun values_followMappingTable() {
        val table = cells(UnifiedCsv.format(listOf(okRecord())).removePrefix(CsvFormat.BOM))
        val row = table[0].zip(table[1]).toMap()                               // 열 이름 → 값
        val want = mapOf(
            "run_id" to "20261106_010203", "algo" to "whisper-base", "model" to "sherpa-int8", "option" to "SILERO",
            "exec_mode" to "embedded", "engine_version" to "sherpa-onnx 1.13.8 static",
            "rel_dir" to "Customer_Service_KR", "file" to "01.wav", "audio_ext" to ".wav", "audio_bytes" to "2703204",
            "audio_sec" to "84.130", "sample_rate" to "16000", "channels" to "1",
            "req_lang" to "ko-KR", "detected_lang" to "", "task" to "transcribe", "diarization" to "unsupported",
            "num_speakers" to "0", "adaptation" to "off", "phrase_count" to "0", "phrase_sets" to "0",
            "device" to "cpu", "device_name" to "SM-S928N SM8650", "compute_type" to "int8", "workers" to "1",
            "cpu_percent_avg" to "380.3", "cpu_time_sec" to "31.500", "rss_peak_mb" to "612.3",
            "gpu_util_avg" to "", "host_id" to "SM-S928N/SM8650", "model_load_sec" to "0.454", "warmup_row" to "0",
            "preprocess_sec" to "1.231", "upload_sec" to "0.000", "wait_sec" to "0.000", "transcribe_sec" to "8.413",
            "cloud_total_sec" to "8.413", "elapsed_sec" to "9.900", "attempts" to "1",
            "feed_sec" to "", "feed_measured" to "0", "proc_sec" to "8.413", "api_wall_sec" to "8.413",
            "cost" to "", "vad_kept_sec" to "80.252", "billed_sec" to "80.252", "billed_channels" to "1",
            "api" to "", "chunks" to "3", "status" to "OK", "utterances" to "3", "words" to "4", "note" to "",
        )
        for ((k, v) in want) assertEquals(k, v, row[k])
        assertEquals("", Job(kr, Catalog.model("vosk-small-ko")!!, novad, RunOptions()).let { j ->
            cells(UnifiedCsv.format(listOf(okRecord().copy(job = j))).removePrefix(CsvFormat.BOM))[1][UnifiedCsv.columns.indexOfFirst { it.name == "compute_type" }]
        })                                                                     // Vosk 는 정밀도 표기가 없다
    }

    /**
     * 목적: [UT-CSV-06] rtf = cloud_total_sec / audio_sec, rtf_proc = proc_sec / audio_sec, rtf_api = api_wall_sec / audio_sec.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rtf_definitions() {
        val stream = okRecord().copy(
            job = Job(kr, Catalog.model("nemotron3.5-560ms")!!, novad, RunOptions(feed = FeedChoice.REALTIME)),
            timings = Timings(0.03, 0.0, 0.0, 84.5, 85.0), vad = null,
            stt = SttOutcome("가 나", 1, SttStatus.OK, firstResultMs = 1500, tailMs = 370, feedSec = 84.1, feedMeasured = true),
        )
        val table = cells(UnifiedCsv.format(listOf(okRecord(), stream)).removePrefix(CsvFormat.BOM))
        val h = table[0]
        fun v(row: Int, name: String) = table[row][h.indexOf(name)]
        assertEquals("0.1000", v(1, "rtf"))                                    // 8.413 / 84.13
        assertEquals("0.1000", v(1, "rtf_proc"))                               // 오프라인은 꼬리 = 추론 시간
        assertEquals("1.0044", v(2, "rtf"))                                    // 84.5 / 84.13
        assertEquals("0.0044", v(2, "rtf_proc"))                               // 0.370 / 84.13
        assertEquals("1.0044", v(2, "rtf_api"))
        assertEquals("84.100", v(2, "feed_sec"))
        assertEquals("1", v(2, "feed_measured"))
        assertEquals("", v(2, "vad_kept_sec"))                                 // VAD 를 안 썼다
        assertEquals("84.130", v(2, "billed_sec"))                             // 음원 전체
        assertEquals("NOVAD_FEED_REALTIME", v(2, "option"))                    // 스트리밍 Feed 토큰
    }

    /**
     * 목적: [UT-CSV-07] device.csv — 열 31개, 이름 중복 없음, 키 3열이 result.csv 와 같은 값이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun deviceCsv_joinsWithResultCsv() {
        val names = DeviceCsv.columns.map { it.name }
        assertEquals(31, names.size)
        assertEquals(names.size, names.toSet().size)                           // 중복 없음
        assertEquals(listOf("run_id", "file", "rel_dir"), names.take(3))       // 맞물리는 키
        val stream = okRecord().copy(
            job = Job(kr, Catalog.model("vosk-small-ko")!!, silero, RunOptions(feed = FeedChoice.REALTIME)),
            stt = SttOutcome("가", 1, SttStatus.OK, firstResultMs = 1104, tailMs = 91, feedSec = 80.206, feedMeasured = true),
            purpose = "시간 측정", downloadOverlap = true,
        )
        val d = cells(DeviceCsv.format(listOf(okRecord(), stream)).removePrefix(CsvFormat.BOM))
        val u = cells(UnifiedCsv.format(listOf(okRecord(), stream)).removePrefix(CsvFormat.BOM))
        assertTrue(d.all { it.size == 31 })
        for (key in listOf("run_id", "file", "rel_dir")) {
            assertEquals(key, (1..2).map { u[it][u[0].indexOf(key)] }, (1..2).map { d[it][d[0].indexOf(key)] })
        }
        val row1 = d[0].zip(d[1]).toMap(); val row2 = d[0].zip(d[2]).toMap()
        assertEquals("0", row1["net_rx_delta"]); assertEquals("0", row1["net_tx_delta"])   // 온디바이스 증명
        assertEquals("21", row1["vad_segments"]); assertEquals("28.500", row1["chunk_max_sec"])
        assertEquals("", row1["feed_ms"]); assertEquals("", row1["feed_stall"])            // 오프라인은 공급이 없다
        assertEquals("80206", row2["feed_ms"])
        assertEquals("0.999", row2["feed_stall"])                              // 80.206 / (80.252 × 100/100) — v3 기준 결과와 같은 값
        assertEquals("1104", row2["first_result_ms"]); assertEquals("91", row2["tail_ms"])
        assertEquals("시간 측정", row2["purpose"]); assertEquals("1", row2["download_overlap"])
    }
}
