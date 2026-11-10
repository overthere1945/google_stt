/*
 * 파일명: domain/csv/DeviceCsv.kt
 * 목적 및 기능:
 * - 보조 CSV(<엔진>_<조건>_device.csv)의 열을 정의한다(D15, FR-07-03, 4단계 7-3).
 *   서버 74열에 자리가 없는 Android 전용 계측(배터리·발열·네트워크·단계별 시간·VAD·조각·공급)을 담는다.
 * - result.csv 와 같은 FileRecord 에서 값을 만들며, run_id + file + rel_dir 로 두 CSV 를 맞물린다.
 * - 추론 구간의 앱 네트워크 송수신(net_rx_delta / net_tx_delta)이 0 이면 온디바이스로 돌았다는 증거다(FR-10-02).
 *
 * add-hyungchul-20261106-0051 — [T2-8]
 */
package com.example.google_stt.domain.csv

/** 목적: device.csv 열 정의(31열). */
object DeviceCsv {

    /** 파일 이름 꼬리: <언어 폴더>/<엔진>_<조건>_device.csv */
    const val FILE_SUFFIX = "_device.csv"

    /**
     * 목적: 열 1개를 만든다.
     * 입력: name - 열 이름, value - FileRecord → 칸 값 / 출력: 없음 / 리턴: CsvColumn<FileRecord>
     */
    private fun c(name: String, value: (FileRecord) -> String) = CsvColumn(name, value)

    /** 열 목록 — 이 순서가 곧 CSV 의 열 순서다 */
    val columns: List<CsvColumn<FileRecord>> = listOf(
        c("run_id") { it.runId },                                             // result.csv 와 맞물리는 키 1
        c("file") { it.file },                                                // 키 2
        c("rel_dir") { it.relDir },                                           // 키 3
        c("batt_pct_start") { it.device.battPctStart?.toString() ?: "" },     // 배터리 %(시작)
        c("batt_pct_end") { it.device.battPctEnd?.toString() ?: "" },
        c("batt_uah_delta") { it.device.battUahDelta?.toString() ?: "" },     // 충전량 변화(µAh)
        c("batt_temp_c_start") { CsvFormat.num(it.device.battTempCStart, 1) },
        c("batt_temp_c_end") { CsvFormat.num(it.device.battTempCEnd, 1) },
        c("charging") { it.device.charging },                                 // AC / USB / WIRELESS / NONE
        c("thermal_start") { it.device.thermalStart },
        c("thermal_end") { it.device.thermalEnd },
        c("thermal_headroom") { CsvFormat.num(it.device.thermalHeadroom, 3) },
        c("pss_peak_mb") { CsvFormat.num(it.device.pssPeakMb, 1) },
        c("cpu_time_sec") { CsvFormat.num(it.device.cpuTimeSec, 3) },
        c("net_rx_delta") { it.device.netRxDelta?.toString() ?: "" },         // 추론 중 수신 바이트(0 이어야 한다)
        c("net_tx_delta") { it.device.netTxDelta?.toString() ?: "" },
        c("decode_sec") { CsvFormat.num(it.timings.decodeSec, 3) },
        c("ns_sec") { CsvFormat.num(it.timings.nsSec, 3) },
        c("vad_sec") { CsvFormat.num(it.timings.vadSec, 3) },
        c("vad_segments") { it.vad?.segments?.toString() ?: "" },             // VAD 를 안 쓰면 빈 칸
        c("vad_kept_sec") { CsvFormat.num(it.vad?.keptSec, 3) },
        c("chunks") { it.chunks.toString() },
        c("chunk_max_sec") { CsvFormat.num(it.chunkMaxSec, 3) },
        c("first_result_ms") { it.stt.firstResultMs?.toString() ?: "" },
        c("tail_ms") { it.stt.tailMs?.toString() ?: "" },
        c("feed_ms") { it.stt.feedSec?.let { s -> Math.round(s * 1000.0).toString() } ?: "" },   // 초 → ms
        c("feed_stall") { CsvFormat.num(feedStall(it), 3) },
        c("model_load_sec") { CsvFormat.num(it.modelLoadSec, 3) },
        c("workers") { it.workers.toString() },
        c("purpose") { it.purpose },                                          // 실행 목적(시간 측정 / 정확도)
        c("download_overlap") { if (it.downloadOverlap) "1" else "0" },       // 다운로드와 겹쳐 돌았으면 1(분석 때 걸러 낸다)
    )

    /**
     * 목적: 공급 지연 비율을 구한다 = 실제 공급 시간 / 기대 공급 시간(v3 feed_stall_ratio 와 같은 뜻, 1 에 가까우면 정상).
     *       기대 공급 시간 = 엔진에 넣은 길이 × (간격 ms / 100 ms).
     * 입력: r - 파일 기록
     * 출력: 없음
     * 리턴: Double? - 공급 속도를 쓰지 않았거나(최대 속도) 공급 시간을 재지 못했으면 null
     */
    private fun feedStall(r: FileRecord): Double? {
        val delayMs = r.job.opt.feed.delayMs ?: return null                   // 공급 속도 사용 안 함
        val feedSec = r.stt.feedSec ?: return null                            // 공급 시간을 재지 못함
        val inputSec = r.vad?.keptSec ?: r.audio.sec                          // 엔진에 넣은 길이
        return CsvFormat.ratio(feedSec, inputSec * delayMs / 100.0)
    }

    /**
     * 목적: device.csv 파일 내용 전체를 만든다.
     * 입력: rows - 파일 기록들(result.csv 와 같은 순서) / 출력: 없음 / 리턴: String - BOM + 머리줄 + 행
     */
    fun format(rows: List<FileRecord>): String = CsvFormat.table(columns, rows)
}
