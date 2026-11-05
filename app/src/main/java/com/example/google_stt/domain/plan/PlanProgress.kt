/*
 * 파일명: domain/plan/PlanProgress.kt
 * 목적 및 기능:
 * - 진행 기록(출력 폴더의 _plan_progress.csv)을 읽고 쓴다. 작업이 끝날 때마다 다시 쓰고, 이어 하기 때 DONE 만 건너뛴다(FR-06-06).
 * - v3 plan/PlanModels.kt 의 PlanProgress 를 옮겼다. 머리줄은 그대로(key,status,worker,started,finished,ok,fail,note).
 * - v3 와 달라진 점:
 *     · key 는 Job.key(<언어 폴더>/<엔진>_<모델>_<조건>) — 날짜 폴더가 없어져 "# date=" 줄을 쓰지 않는다(D2)
 *     · 깨진 줄은 건너뛰되 경고 목록으로 돌려준다(E-PROG-01 — 조용히 삼키지 않는다)
 *     · SKIPPED 상태 추가(ML Kit 먹통으로 건너뛴 작업 — D29). 이어 하기 때 다시 돌린다
 *
 * add-hyungchul-20261106-0046 — [T2-6a]
 */
package com.example.google_stt.domain.plan

/**
 * 목적: 진행 기록 한 줄(작업 1개의 끝 상태).
 * 필드: key - 작업 식별(Job.key), status - DONE / FAILED / INTERRUPTED / SKIPPED, worker - 실행한 슬롯 번호,
 *       started / finished - 시각(yyyy-MM-dd HH:mm:ss), ok / fail - 파일 성공·실패 수, note - 메모(오류 코드 등)
 */
data class PlanProgressRow(
    val key: String,        // 작업 식별
    val status: String,     // 끝 상태
    val worker: Int,        // 슬롯 번호
    val started: String,    // 시작 시각
    val finished: String,   // 끝 시각
    val ok: Int,            // 성공 파일 수
    val fail: Int,          // 실패 파일 수
    val note: String,       // 메모
)

/**
 * 목적: 진행 기록을 읽은 결과.
 * 필드: rows - key → 마지막 기록, warnings - 건너뛴 깨진 줄에 대한 경고(줄 번호 포함)
 */
data class PlanProgressParse(
    val rows: Map<String, PlanProgressRow>,   // 기록
    val warnings: List<String>,               // 경고
) {
    /** 끝까지 돈 작업의 key 모음(이어 하기 때 건너뛴다) */
    val doneKeys: Set<String> get() = rows.values.filter { it.status == PlanProgress.DONE }.map { it.key }.toSet()
}

/** 목적: 진행 기록 읽기·쓰기. */
object PlanProgress {
    const val DONE = "DONE"                    // 끝까지 돌았다(일부 파일 ERROR 여도 완료 — 평가기가 실패도 채점)
    const val FAILED = "FAILED"                // 시작하지 못했거나 오류로 끝났다 → 이어 하기 때 다시
    const val INTERRUPTED = "INTERRUPTED"      // 사용자 중지·앱 종료로 끊겼다 → 이어 하기 때 처음부터 다시
    const val SKIPPED = "SKIPPED"              // 엔진 먹통 등으로 시작하지 않고 건너뛰었다 → 이어 하기 때 다시

    /** 진행 기록 머리줄 */
    const val HEADER = "key,status,worker,started,finished,ok,fail,note"

    private val STATUSES = setOf(DONE, FAILED, INTERRUPTED, SKIPPED)   // 쓸 수 있는 상태 값

    /**
     * 목적: 진행 기록 글을 만든다.
     * 입력: plan - 목록 이름(참고용), rows - 기록(키별 마지막 상태)
     * 출력: 없음
     * 리턴: String - UTF-8 BOM + "# plan=" + 머리줄 + 줄들
     */
    fun format(plan: String, rows: Collection<PlanProgressRow>): String {
        val sb = StringBuilder("﻿")                                   // BOM
        sb.append("# plan=").append(clean(plan)).append('\n')              // 참고용 목록 이름
        sb.append(HEADER).append('\n')
        for (r in rows) {
            sb.append(listOf(r.key, r.status, r.worker, r.started, r.finished, r.ok, r.fail, clean(r.note)).joinToString(","))
                .append('\n')
        }
        return sb.toString()
    }

    /**
     * 목적: 진행 기록 글을 읽는다.
     * 입력: text - 파일 내용
     * 출력: 없음
     * 리턴: PlanProgressParse - 같은 key 가 여러 번 나오면 마지막 줄이 남는다. 깨진 줄은 건너뛰고 경고로 알린다
     */
    fun parse(text: String): PlanProgressParse {
        val rows = LinkedHashMap<String, PlanProgressRow>()               // key → 마지막 기록
        val warnings = ArrayList<String>()                                // 깨진 줄 경고
        for ((i, raw) in text.removePrefix("﻿").split("\n").withIndex()) {
            val line = raw.trim().removeSuffix("\r")                      // CRLF·앞뒤 공백 제거
            if (line.isEmpty() || line.startsWith("#") || line == HEADER) continue   // 빈 줄·주석·머리줄
            val c = line.split(",")
            if (c.size < 8 || c[0].isEmpty() || c[1] !in STATUSES) {      // 칸이 모자라거나 상태 값이 틀리다
                warnings += "${i + 1}행: 진행 기록 줄을 읽을 수 없어 건너뛴다(미완료로 본다) — '$line'"
                continue
            }
            rows[c[0]] = PlanProgressRow(
                key = c[0], status = c[1], worker = c[2].toIntOrNull() ?: 0,
                started = c[3], finished = c[4],
                ok = c[5].toIntOrNull() ?: 0, fail = c[6].toIntOrNull() ?: 0,
                note = c.subList(7, c.size).joinToString(" "),            // 메모에 쉼표가 있었으면 다시 잇는다
            )
        }
        return PlanProgressParse(rows, warnings)
    }

    /**
     * 목적: CSV 칸을 깨뜨리는 쉼표·줄바꿈을 공백으로 바꾼다.
     * 입력: s - 원래 글 / 출력: 없음 / 리턴: String - 정리된 글
     */
    private fun clean(s: String): String = s.replace(",", " ").replace("\r", " ").replace("\n", " ")
}
