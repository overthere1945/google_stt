/*
 * 파일명: domain/csv/UnifiedCsv.kt
 * 목적 및 기능:
 * - result.csv 의 열을 정의한다: 서버 통일 CSV v2.0 의 74열(stt_common.STT_CSV_HEADER)과 이름·순서가 같다(D10, FR-07-02).
 *   골든 목록 docs/AI_analyze/golden/STT_CSV_HEADER_v2.0.txt 와의 일치를 단위 시험(UT-CSV-01)이 지킨다.
 * - 값 매핑은 2단계 6장 표를 따른다. 숫자 자릿수는 서버 build_row 와 같다(초 3자리, RTF 4자리, CPU %·메모리 1자리).
 *     A 식별 7 · B 입력 7 · C 언어 8 · D 환경 13 · E 시간 14 · F 비용 10 · G 엔진 고유 8 · H 결과 7
 * - 로컬 추론이라 없는 값(GPU, 비용, Google 전용 열)은 빈 칸이다. upload_sec / wait_sec 은 서버 로컬 엔진처럼 0.000(= 미측정).
 *
 * add-hyungchul-20261106-0051 — [T2-8]
 */
package com.example.google_stt.domain.csv

/** 목적: result.csv 열 정의(74열). */
object UnifiedCsv {

    /** 파일 이름 꼬리: <언어 폴더>/<엔진>_<조건>_result.csv */
    const val FILE_SUFFIX = "_result.csv"

    /**
     * 목적: 열 1개를 만든다(이름과 계산식을 한 줄에 적기 위한 짧은 이름).
     * 입력: name - 열 이름, value - FileRecord → 칸 값 / 출력: 없음 / 리턴: CsvColumn<FileRecord>
     */
    private fun c(name: String, value: (FileRecord) -> String) = CsvColumn(name, value)

    /**
     * 목적: 값이 없는 열을 만든다(로컬 추론에 해당하지 않는 열).
     * 입력: name - 열 이름 / 출력: 없음 / 리턴: CsvColumn<FileRecord> - 항상 빈 칸
     */
    private fun blank(name: String) = CsvColumn<FileRecord>(name) { "" }

    /** 열 목록 — 이 순서가 곧 CSV 의 열 순서다 */
    val columns: List<CsvColumn<FileRecord>> = listOf(
        // ── A. 실행 식별 (7) ──
        c("run_id") { it.runId },                                             // 실행 ID
        c("started_at") { it.startedAt },                                     // 이 파일을 시작한 시각
        c("algo") { it.job.model.engine },                                    // 엔진 이름만(결과 폴더의 <엔진>과 같다)
        c("model") { it.job.model.model },                                    // sherpa-int8 / vosk / basic
        c("option") { it.job.condition },                                     // 조건 이름
        c("exec_mode") { "embedded" },                                        // 온디바이스 추론
        c("engine_version") { it.engineVersion },                             // 런타임 판
        // ── B. 입력 파일 (7) ──
        c("rel_dir") { it.relDir },
        c("file") { it.file },
        c("audio_ext") { it.audio.ext },
        c("audio_bytes") { it.audio.bytes.toString() },
        c("audio_sec") { CsvFormat.num(it.audio.sec, 3) },
        c("sample_rate") { if (it.audio.sampleRate > 0) it.audio.sampleRate.toString() else "" },   // 모르면 빈 칸
        c("channels") { if (it.audio.channels > 0) it.audio.channels.toString() else "" },
        // ── C. 언어 / 인식 옵션 (8) ──
        c("req_lang") { it.job.language.reqLang },                            // ko-KR / en-US
        c("detected_lang") { it.stt.detectedLang ?: "" },
        c("task") { "transcribe" },
        c("diarization") { "unsupported" },                                   // 화자 분리 없음
        c("num_speakers") { "0" },
        c("adaptation") { "off" },                                            // 구문 목록 없음
        c("phrase_count") { "0" },
        c("phrase_sets") { "0" },
        // ── D. 실행 환경 / 자원 (13) ──
        c("device") { "cpu" },
        c("device_name") { it.device.deviceName },
        c("compute_type") { if (it.job.model.model.endsWith("int8")) "int8" else "" },   // sherpa-int8 만 정밀도를 안다
        c("workers") { it.workers.toString() },
        c("cpu_percent_avg") { CsvFormat.num(it.device.cpuPercentAvg, 1) },
        c("cpu_time_sec") { CsvFormat.num(it.device.cpuTimeSec, 3) },
        c("rss_peak_mb") { CsvFormat.num(it.device.pssPeakMb, 1) },           // Android 는 PSS 로 잰다
        blank("gpu_util_avg"), blank("gpu_mem_peak_mb"), blank("gpu_energy_wh"),   // GPU 를 쓰지 않는다
        c("host_id") { it.device.hostId },
        c("model_load_sec") { CsvFormat.num(it.modelLoadSec, 3) },            // 작업의 첫 파일만 값, 나머지 0
        c("warmup_row") { if (it.warmupRow) "1" else "0" },
        // ── E. 시간 (14) ──
        c("preprocess_sec") { CsvFormat.num(it.timings.preprocessSec, 3) },   // 디코딩 + 노이즈 저감 + VAD
        c("upload_sec") { CsvFormat.num(0.0, 3) },                            // 로컬이라 업로드 없음(0 = 미측정)
        c("wait_sec") { CsvFormat.num(0.0, 3) },
        c("transcribe_sec") { CsvFormat.num(it.timings.transcribeSec, 3) },
        c("cloud_total_sec") { CsvFormat.num(it.timings.transcribeSec, 3) },  // upload 0 + transcribe
        c("elapsed_sec") { CsvFormat.num(it.timings.elapsedSec, 3) },
        c("rtf") { CsvFormat.num(CsvFormat.ratio(it.timings.transcribeSec, it.audio.sec), 4) },   // cloud_total / audio
        c("attempts") { it.attempts.toString() },
        c("feed_sec") { CsvFormat.num(it.stt.feedSec, 3) },                   // 오프라인은 빈 칸
        c("feed_measured") { if (it.stt.feedMeasured) "1" else "0" },
        c("proc_sec") { CsvFormat.num(procSec(it), 3) },                      // 공급 끝 → 최종 결과
        c("api_wall_sec") { CsvFormat.num(it.timings.transcribeSec, 3) },     // 요청 시작 → 최종 결과
        c("rtf_proc") { CsvFormat.num(CsvFormat.ratio(procSec(it), it.audio.sec), 4) },
        c("rtf_api") { CsvFormat.num(CsvFormat.ratio(it.timings.transcribeSec, it.audio.sec), 4) },
        // ── F. 비용 (10) ──
        blank("billed_unit"), blank("billed_qty"), blank("unit_price"), blank("currency"), blank("cost"), blank("price_ref"),
        c("vad_kept_sec") { CsvFormat.num(it.vad?.keptSec, 3) },              // VAD 를 안 쓰면 빈 칸
        c("billed_sec") { CsvFormat.num(billedSec(it), 3) },
        c("billed_channels") { "1" },                                         // 16 kHz mono 로 넣는다
        blank("pricing_tier"),
        // ── G. 엔진 고유 (8) ──
        blank("api"), blank("model_sent"), blank("enhanced"), blank("location"), blank("recognition_mode"),
        c("chunks") { it.chunks.toString() },                                 // STT 에 넣은 조각 수
        blank("model_version"), blank("locales"),
        // ── H. 결과 (7) ──
        c("status") { it.stt.status.name },                                   // OK / NO_MATCH / ERROR
        c("utterances") { it.stt.utterances.toString() },
        c("chars") { CsvFormat.textStats(it.stt.text).first.toString() },
        c("words") { CsvFormat.textStats(it.stt.text).second.toString() },
        c("text_sha1") { CsvFormat.textStats(it.stt.text).third },
        c("note") { it.note },
        c("text") { CsvFormat.oneLine(it.stt.text) },                         // 한 줄로 정리한 전사문
    )

    /**
     * 목적: 공급을 마친 뒤 최종 결과까지의 시간(초)을 구한다.
     * 입력: r - 파일 기록 / 출력: 없음 / 리턴: Double? - tailMs 가 없으면 null
     */
    private fun procSec(r: FileRecord): Double? = r.stt.tailMs?.let { it / 1000.0 }   // ms → 초

    /**
     * 목적: 과금 대상 길이(초)를 구한다(서버 규칙: 엔진을 부르지 못했으면 0, VAD 를 썼으면 남은 길이, 아니면 음원 길이).
     * 입력: r - 파일 기록 / 출력: 없음 / 리턴: Double
     */
    private fun billedSec(r: FileRecord): Double = when {
        r.timings.transcribeSec == null -> 0.0        // 엔진을 부르지 못함
        r.vad != null -> r.vad.keptSec                // VAD 뒤 길이
        else -> r.audio.sec                           // 음원 전체
    }

    /**
     * 목적: result.csv 파일 내용 전체를 만든다.
     * 입력: rows - 파일 기록들(처리 순서) / 출력: 없음 / 리턴: String - BOM + 머리줄 + 행
     */
    fun format(rows: List<FileRecord>): String = CsvFormat.table(columns, rows)
}
