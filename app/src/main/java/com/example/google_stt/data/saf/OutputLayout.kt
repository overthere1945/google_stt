/*
 * 파일명: data/saf/OutputLayout.kt
 * 목적 및 기능:
 * - 출력 폴더 안의 이름·경로 규칙을 한 곳에 둔다(D2, 4단계 7-1). Android 를 쓰지 않는 순수 Kotlin 이라 JVM 에서 시험한다.
 *     <출력>/
 *       _plan.csv · _plan_progress.csv · _plan_exits.csv · _plan_memlog.csv · _summary.csv · _app_log.txt
 *       <언어 폴더>/                                   (입력 폴더 자체가 한 언어면 이 단계가 없다)
 *         <엔진>_<모델>_<조건>/<시나리오>/<이름>.txt     전사문(실패하면 <이름>.ERROR.txt — D28)
 *         <엔진>_<조건>_result.csv · _device.csv · _run_meta.csv
 * - 작업 중인 결과 폴더는 "_" 를 앞에 붙인다(PC 평가기가 "_" 폴더를 건너뛴다). 끝나면 원래 이름으로 바꾼다.
 *   같은 이름의 완료 폴더를 다시 돌릴 때는 옛 폴더를 "_stale_<이름>_<시각>" 으로 비켜 둔다.
 * - 실패 기록 글 형식은 서버 stt_common.save_failure 와 같다(첫 줄 "[ERROR] <원인 한 줄>").
 *
 * add-hyungchul-20261106-1636 — [T3-2]
 */
package com.example.google_stt.data.saf

import com.example.google_stt.domain.csv.CsvFormat   // 한 줄 정리(one_line)
import com.example.google_stt.domain.plan.Job        // 작업(언어 폴더·결과 폴더 이름)

/** 목적: 출력 폴더의 이름·경로 규칙. */
object OutputLayout {

    /** 작업 중인 결과 폴더 이름 앞에 붙이는 글자 */
    const val RUN_PREFIX = "_"

    /** 비켜 둔 묵은 결과 폴더 이름의 앞부분 */
    const val STALE_PREFIX = "_stale_"

    const val PLAN_FILE = "_plan.csv"                    // 조합 목록
    const val PROGRESS_FILE = "_plan_progress.csv"       // 진행 기록
    const val EXITS_FILE = "_plan_exits.csv"             // 앱이 꺼진 이유
    const val MEMLOG_FILE = "_plan_memlog.csv"           // 30초 간격 메모리 기록
    const val SUMMARY_FILE = "_summary.csv"              // 실행 요약
    const val APP_LOG_FILE = "_app_log.txt"              // 앱 로그
    const val APP_LOG_OLD_FILE = "_app_log.1.txt"        // 돌려 둔 앞 로그(1개만 보관)

    /** 실패 기록 첫 줄의 표시(평가기의 실패 판정 규칙) */
    const val ERROR_MARK = "[ERROR]"

    /**
     * 목적: 작업의 언어 폴더 경로 조각을 준다.
     * 입력: job - 작업 / 출력: 없음 / 리턴: List<String> - ["kr"] 처럼 1개, 입력 폴더 자체가 한 언어면 빈 목록
     */
    fun langSegments(job: Job): List<String> = if (job.lang.name.isEmpty()) emptyList() else listOf(job.lang.name)

    /**
     * 목적: 결과 폴더 이름을 준다.
     * 입력: job - 작업, working - 작업 중이면 true / 출력: 없음 / 리턴: String - "_<엔진>_<모델>_<조건>" 또는 "<엔진>_<모델>_<조건>"
     */
    fun resultDirName(job: Job, working: Boolean): String = (if (working) RUN_PREFIX else "") + job.resultDirName

    /**
     * 목적: 비켜 둘 묵은 폴더 이름을 만든다.
     * 입력: job - 작업, stamp - 시각 "yyyyMMdd_HHmmss" / 출력: 없음 / 리턴: String - "_stale_<결과 폴더>_<시각>"
     */
    fun staleDirName(job: Job, stamp: String): String = "$STALE_PREFIX${job.resultDirName}_$stamp"

    /**
     * 목적: 전사 TXT 를 둘 폴더의 경로 조각을 준다(출력 폴더 기준).
     * 입력: job - 작업, relDir - 언어 폴더 아래 음원의 상대 폴더 조각, working - 작업 중이면 true
     * 출력: 없음
     * 리턴: List<String> - [언어 폴더] + 결과 폴더 + relDir
     */
    fun textDirSegments(job: Job, relDir: List<String>, working: Boolean): List<String> =
        langSegments(job) + resultDirName(job, working) + relDir

    /**
     * 목적: 전사 TXT 파일 이름을 준다.
     * 입력: baseName - 확장자를 뺀 음원 이름 / 출력: 없음 / 리턴: String - "<이름>.txt"
     */
    fun textName(baseName: String): String = "$baseName.txt"

    /**
     * 목적: 실패 기록 파일 이름을 준다(D28).
     * 입력: baseName - 확장자를 뺀 음원 이름 / 출력: 없음 / 리턴: String - "<이름>.ERROR.txt"
     */
    fun errorName(baseName: String): String = "$baseName.ERROR.txt"

    /**
     * 목적: 작업의 CSV 파일 이름을 준다(언어 폴더 바로 아래에 둔다. 모델 이름은 넣지 않는다).
     * 입력: job - 작업, suffix - "_result.csv" / "_device.csv" / "_run_meta.csv" / 출력: 없음 / 리턴: String
     */
    fun csvName(job: Job, suffix: String): String = job.csvBaseName + suffix

    /**
     * 목적: 실패 기록 파일의 내용을 만든다(서버 save_failure 와 같은 형식).
     * 입력: cause - 실패 원인(오류 코드 포함), partial - 실패 전까지 받은 부분 결과(없으면 빈 글)
     * 출력: 없음
     * 리턴: String - "[ERROR] <원인 한 줄>\n" + (부분 결과가 있으면 참고 줄 1개)
     */
    fun errorText(cause: String, partial: String = ""): String {
        val sb = StringBuilder("$ERROR_MARK ${CsvFormat.oneLine(cause)}\n")   // 첫 줄은 반드시 [ERROR] 로 시작
        if (partial.isNotBlank()) sb.append("[부분 결과(참고용, 평가 제외)] ${CsvFormat.oneLine(partial)}\n")
        return sb.toString()
    }

    /**
     * 목적: 상대 폴더 조각을 CSV rel_dir 값으로 만든다.
     * 입력: relDir - 폴더 조각 / 출력: 없음 / 리턴: String - "/" 로 이은 경로(최상위면 빈 글)
     */
    fun relDirText(relDir: List<String>): String = relDir.joinToString("/")

    /**
     * 목적: 파일 이름을 (확장자를 뺀 이름, 점 포함 소문자 확장자)로 나눈다.
     * 입력: fileName - 파일 이름 / 출력: 없음 / 리턴: Pair<String, String> - 예 ("01", ".wav"). 확장자가 없으면 ("이름", "")
     */
    fun splitName(fileName: String): Pair<String, String> {
        val dot = fileName.lastIndexOf('.')                                   // 마지막 점
        return if (dot > 0) fileName.substring(0, dot) to fileName.substring(dot).lowercase() else fileName to ""
    }
}
