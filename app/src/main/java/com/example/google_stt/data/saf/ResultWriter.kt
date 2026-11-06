/*
 * 파일명: data/saf/ResultWriter.kt
 * 목적 및 기능:
 * - 실행 결과를 출력 폴더에 쓴다(FR-07). v3 io/ResultStore.kt 를 대신한다. 모든 쓰기는 SafTree 의 원자적 쓰기를 탄다.
 * - JobWriter(작업 1개 동안 쓰는 도구):
 *     · 전사 TXT: <언어 폴더>/_<엔진>_<모델>_<조건>/<상대 폴더>/<이름>.txt — 전사문만, 빈 결과는 0바이트(FR-07-01)
 *     · 실패: <이름>.txt 를 지우고 <이름>.ERROR.txt 를 쓴다. 성공·무음이면 묵은 .ERROR.txt 를 지운다(D28, 서버 규칙)
 *     · result.csv(74열)·device.csv: 행을 메모리에 모아 10파일마다 전체를 다시 쓴다(FR-07-05). 작업당 파일이 수십 개라 비용이 작다
 *     · run_meta.csv, 그리고 끝나면 결과 폴더 이름에서 "_" 를 뗀다
 * - 실행 전체 요약 _summary.csv(FR-07-06)
 * - 어떤 내용을 쓸지(열·형식)는 domain/csv 가 정하고, 여기서는 어디에 어떻게 쓸지만 다룬다.
 *
 * add-hyungchul-20261106-1643 — [T3-3]
 */
package com.example.google_stt.data.saf

import androidx.documentfile.provider.DocumentFile        // SAF 문서
import com.example.google_stt.domain.csv.CsvColumn        // 요약 열 정의
import com.example.google_stt.domain.csv.CsvFormat        // CSV 글자 형식
import com.example.google_stt.domain.csv.DeviceCsv        // 보조 CSV
import com.example.google_stt.domain.csv.FileRecord       // 파일 처리 결과
import com.example.google_stt.domain.csv.RunMeta          // 실행 조건 기록
import com.example.google_stt.domain.csv.RunMetaInput     // 실행 조건 입력
import com.example.google_stt.domain.csv.UnifiedCsv       // 통일 CSV 74열
import com.example.google_stt.domain.plan.Job             // 작업

/**
 * 목적: 실행 요약 한 줄(작업 1개).
 * 필드: key - 작업 식별(Job.key), status - DONE / FAILED / INTERRUPTED / SKIPPED, files - 음원 수, ok / fail - 성공·실패 수,
 *       elapsedSec - 작업 소요(초), audioSec - 처리한 음원 길이 합(초)
 */
data class SummaryRow(
    val key: String,          // 작업 식별
    val status: String,       // 끝 상태
    val files: Int,           // 음원 수
    val ok: Int,              // 성공 수
    val fail: Int,            // 실패 수
    val elapsedSec: Double,   // 소요
    val audioSec: Double,     // 음원 길이 합
)

/**
 * 목적: 결과 쓰기 도구.
 * 입력(생성자): tree - 출력 폴더 트리
 */
class ResultWriter(private val tree: SafTree) {

    companion object {
        /** result.csv·device.csv 를 다시 쓰는 간격(파일 수) */
        const val FLUSH_EVERY = 10

        /** _summary.csv 열 — rtf 는 작업 소요 / 음원 길이(예상 시간 계산에 쓰는 값과 같은 정의) */
        val SUMMARY_COLUMNS: List<CsvColumn<SummaryRow>> = listOf(
            CsvColumn("key") { it.key },
            CsvColumn("status") { it.status },
            CsvColumn("files") { it.files.toString() },
            CsvColumn("ok") { it.ok.toString() },
            CsvColumn("fail") { it.fail.toString() },
            CsvColumn("elapsed_sec") { CsvFormat.num(it.elapsedSec, 3) },
            CsvColumn("audio_sec") { CsvFormat.num(it.audioSec, 3) },
            CsvColumn("rtf") { CsvFormat.num(CsvFormat.ratio(it.elapsedSec, it.audioSec), 4) },
        )
    }

    /**
     * 목적: 작업 1개를 쓰기 시작한다.
     * 입력: job - 작업 / 출력: 언어 폴더와 작업 중 결과 폴더("_…")가 만들어진다 / 리턴: JobWriter
     */
    fun open(job: Job): JobWriter = JobWriter(tree, job)

    /**
     * 목적: 실행 요약 _summary.csv 를 출력 폴더 바로 아래에 쓴다.
     * 입력: rows - 작업별 요약(실행 순서) / 출력: 파일이 쓰인다 / 리턴: 없음
     */
    fun writeSummary(rows: List<SummaryRow>) {
        tree.writeAtomic(tree.root, OutputLayout.SUMMARY_FILE, CsvFormat.table(SUMMARY_COLUMNS, rows).toByteArray(Charsets.UTF_8))
    }
}

/**
 * 목적: 작업 1개 동안 결과를 쓰는 도구(작업 하나가 한 스레드·코루틴에서 차례로 부른다).
 * 입력(생성자): tree - 출력 폴더 트리, job - 작업
 */
class JobWriter internal constructor(private val tree: SafTree, private val job: Job) {

    /** 언어 폴더(CSV 3종을 여기에 둔다). 입력 폴더 자체가 한 언어면 출력 폴더 그 자체 */
    private val langDir: DocumentFile = tree.ensureDirs(OutputLayout.langSegments(job))

    /** 지금까지 모은 파일 기록(result.csv·device.csv 의 행) */
    private val rows = ArrayList<FileRecord>()

    init {
        tree.recoverTmp(langDir)                                           // 지난 실행이 남긴 임시 파일 정리(E-OUT-04)
        tree.ensureDir(langDir, OutputLayout.resultDirName(job, working = true))   // 작업 중 결과 폴더 "_<이름>"
    }

    /** 모은 파일 기록 수 */
    val count: Int get() = rows.size

    /**
     * 목적: 전사문을 저장한다(성공·무음). 묵은 실패 기록이 있으면 지운다.
     * 입력: relDir - 언어 폴더 아래 음원의 상대 폴더 조각, baseName - 확장자를 뺀 음원 이름, text - 전사문(빈 글이면 0바이트 파일)
     * 출력: <이름>.txt 가 쓰이고 <이름>.ERROR.txt 가 지워진다
     * 리턴: 없음
     */
    fun saveText(relDir: List<String>, baseName: String, text: String) {
        val dir = tree.ensureDirs(OutputLayout.textDirSegments(job, relDir, working = true))
        tree.writeAtomic(dir, OutputLayout.textName(baseName), text.toByteArray(Charsets.UTF_8))   // 전사문만 그대로
        tree.delete(dir, OutputLayout.errorName(baseName))                 // 성공했는데 실패로 세는 것을 막는다
    }

    /**
     * 목적: 실패 기록을 저장한다. 전사 TXT 는 만들지 않고, 묵은 TXT 가 있으면 지운다(평가기가 옛 결과를 채점하지 않게).
     * 입력: relDir - 상대 폴더 조각, baseName - 음원 이름, cause - 실패 원인(오류 코드 포함), partial - 부분 결과(없으면 빈 글)
     * 출력: <이름>.ERROR.txt 가 쓰이고 <이름>.txt 가 지워진다
     * 리턴: 없음
     */
    fun saveError(relDir: List<String>, baseName: String, cause: String, partial: String = "") {
        val dir = tree.ensureDirs(OutputLayout.textDirSegments(job, relDir, working = true))
        tree.delete(dir, OutputLayout.textName(baseName))                  // 옛 전사문 지우기
        tree.writeAtomic(dir, OutputLayout.errorName(baseName), OutputLayout.errorText(cause, partial).toByteArray(Charsets.UTF_8))
    }

    /**
     * 목적: 파일 기록 하나를 더한다. 10개째마다 result.csv·device.csv 를 다시 쓴다.
     * 입력: record - 파일 처리 결과 / 출력: CSV 가 다시 쓰일 수 있다 / 리턴: 없음
     */
    fun add(record: FileRecord) {
        rows += record
        if (rows.size % ResultWriter.FLUSH_EVERY == 0) flush()             // 10파일마다
    }

    /**
     * 목적: 지금까지의 행으로 result.csv·device.csv 를 다시 쓴다(중단될 때 마지막 저장에도 쓴다).
     * 입력: 없음 / 출력: 두 CSV 가 쓰인다 / 리턴: 없음
     */
    fun flush() {
        tree.writeAtomic(langDir, OutputLayout.csvName(job, UnifiedCsv.FILE_SUFFIX), UnifiedCsv.format(rows).toByteArray(Charsets.UTF_8))
        tree.writeAtomic(langDir, OutputLayout.csvName(job, DeviceCsv.FILE_SUFFIX), DeviceCsv.format(rows).toByteArray(Charsets.UTF_8))
    }

    /**
     * 목적: run_meta.csv 를 쓴다.
     * 입력: input - 실행 조건(이 작업의 것) / 출력: 파일이 쓰인다 / 리턴: 없음
     */
    fun writeRunMeta(input: RunMetaInput) {
        tree.writeAtomic(langDir, OutputLayout.csvName(job, RunMeta.FILE_SUFFIX), RunMeta.format(input).toByteArray(Charsets.UTF_8))
    }

    /**
     * 목적: 작업을 마무리한다 — CSV 를 마지막으로 쓰고, 결과 폴더 이름에서 "_" 를 뗀다.
     * 입력: 없음
     * 출력: CSV 가 쓰이고 결과 폴더 이름이 바뀐다
     * 리턴: Boolean - 이름을 바꿨으면 true. 같은 이름의 폴더가 이미 있는 등으로 못 바꿨으면 false("_" 폴더로 남는다)
     */
    fun finish(): Boolean {
        flush()
        return tree.rename(langDir, OutputLayout.resultDirName(job, working = true), OutputLayout.resultDirName(job, working = false))
    }
}
