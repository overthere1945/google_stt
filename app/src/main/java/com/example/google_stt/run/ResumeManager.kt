/*
 * 파일명: run/ResumeManager.kt
 * 목적 및 기능:
 * - 이어 하기에 필요한 기록을 출력 폴더에 남기고 읽는다(FR-06-06·07, 4단계 7-1).
 *     _plan_progress.csv : 작업(key)별 마지막 상태 DONE / FAILED / INTERRUPTED / SKIPPED (v3 와 같은 형식)
 *     _plan_exits.csv    : 앱이 꺼진 이유(시스템의 종료 기록 — 메모리 부족·크래시 등)
 * - 규칙
 *     · DONE 은 건너뛴다. FAILED·INTERRUPTED·SKIPPED 는 처음부터 다시 돈다("처음부터" 를 고르면 DONE 도 다시)
 *     · 작업을 시작할 때 "돌고 있음" 을 적어 둔다 → 앱이 그 상태로 죽으면 다음에 읽을 때 "비정상 종료 1회" 로 센다.
 *       같은 작업이 **연속 2회** 비정상 종료하면 다시 돌리지 않고 FAILED(ERR_CRASH_LOOP)로 둔다(E-EN-07) — 같은 자리에서 계속 죽는 것을 막는다
 *     · 작업을 시작하기 전: 중단된 작업이 남긴 "_<이름>" 폴더는 지운다(D30). 같은 이름의 완료 폴더가 있으면 "_stale_<이름>_<시각>" 으로 치운다
 * - 파일·폴더를 다루는 일은 ResumeStore 로 받는다(앱에서는 SafResumeStore) → PC(JVM)에서 가짜로 규칙을 시험한다.
 *
 * add-hyungchul-20261106-2237 — [T6-4]
 */
package com.example.google_stt.run

import com.example.google_stt.data.saf.OutputLayout              // 파일·폴더 이름 규칙
import com.example.google_stt.data.saf.SafTree                   // 출력 폴더
import com.example.google_stt.domain.csv.CsvFormat               // BOM·줄 끝·따옴표
import com.example.google_stt.domain.csv.DeviceCsv               // CSV 이름 꼬리
import com.example.google_stt.domain.csv.RunMeta                 // CSV 이름 꼬리
import com.example.google_stt.domain.csv.UnifiedCsv              // CSV 이름 꼬리
import com.example.google_stt.domain.plan.Job                    // 작업
import com.example.google_stt.domain.plan.PlanProgress           // 진행 기록 형식
import com.example.google_stt.domain.plan.PlanProgressRow        // 진행 기록 한 줄

/** 목적: 이어 하기가 쓰는 파일·폴더 일(출력 폴더 기준). */
interface ResumeStore {
    /** 출력 폴더 바로 아래 파일을 읽는다. 입력: name - 파일 이름 / 리턴: String? - 없으면 null */
    fun read(name: String): String?

    /** 출력 폴더 바로 아래 파일을 통째로 쓴다(원자적). 입력: name, text / 리턴: 없음 */
    fun write(name: String, text: String)

    /** 결과 폴더가 있는지. 입력: lang - 언어 폴더 조각, dir - 폴더 이름 / 리턴: Boolean */
    fun exists(lang: List<String>, dir: String): Boolean

    /** 결과 폴더를 지운다(안의 것까지). 입력: lang, dir / 리턴: Boolean - 지웠으면 true */
    fun delete(lang: List<String>, dir: String): Boolean

    /** 결과 폴더 이름을 바꾼다. 입력: lang, from, to / 리턴: Boolean - 바꿨으면 true */
    fun rename(lang: List<String>, from: String, to: String): Boolean
}

/**
 * 목적: 앱이 꺼진 기록 1건(시스템의 ApplicationExitInfo 에서 읽은 값).
 * 필드: time - 꺼진 시각("yyyy-MM-dd HH:mm:ss"), reason - 이유 이름(LOW_MEMORY / CRASH_NATIVE / USER_REQUESTED …),
 *       description - 시스템 설명, pssMb / rssMb - 꺼질 때의 메모리(MB)
 */
data class ExitRecord(val time: String, val reason: String, val description: String, val pssMb: Long, val rssMb: Long) {
    /** 메모리 부족으로 꺼졌는지(동시 작업 수를 줄이라고 권하는 근거) */
    val lowMemory: Boolean get() = reason == "LOW_MEMORY"
}

/**
 * 목적: 이어 하기 판정 결과.
 * 필드: todo - 이번에 돌릴 작업(순서 유지), done - 이미 끝나 건너뛰는 작업, crashLoop - 연속 2회 비정상 종료라 돌리지 않는 작업
 */
class ResumePlan(val todo: List<Job>, val done: List<Job>, val crashLoop: List<Job>)

/**
 * 목적: 이어 하기 기록 관리.
 * 입력(생성자): store - 파일·폴더 일, now - 지금 시각("yyyy-MM-dd HH:mm:ss"), stamp - 폴더 이름용 시각("yyyyMMdd_HHmmss"), log - 로그
 */
class ResumeManager(
    private val store: ResumeStore,
    private val now: () -> String,
    private val stamp: () -> String,
    private val log: (String) -> Unit = {},
) {
    companion object {
        const val RUNNING = "RUNNING"                 // 돌고 있음(이 표시가 남은 채 읽히면 비정상 종료)
        const val CRASH = "CRASH"                     // 비정상 종료 횟수 표시 "CRASH:<n>"
        const val CRASH_LOOP = "ERR_CRASH_LOOP"       // 연속 2회 비정상 종료
        const val CRASH_LIMIT = 2                     // 이 횟수면 다시 돌리지 않는다
        const val EXITS_HEADER = "time,reason,description,pss_mb,rss_mb"
    }

    private val rows = LinkedHashMap<String, PlanProgressRow>()   // key → 마지막 상태(쓴 순서 유지)
    private var planName = ""                                     // 목록 이름(파일 머리의 참고 값)

    /** 지금 기억하는 기록(key → 마지막 상태)의 사본 */
    val snapshot: Map<String, PlanProgressRow> @Synchronized get() = LinkedHashMap(rows)

    /**
     * 목적: 진행 기록을 읽는다. "돌고 있음" 으로 남은 줄은 비정상 종료로 바꿔 센다.
     * 입력: plan - 목록 이름(새로 쓸 때 머리에 적는다)
     * 출력: 기록이 메모리에 올라가고, 바뀐 것이 있으면 파일을 다시 쓴다
     * 리턴: List<String> - 읽으면서 생긴 경고(깨진 줄 등)
     */
    /** add-hyungchul-20261109-1016 : 9단계 통합 검토 4 — 가장 최근 종료가 사용자 강제 종료·재설치였는지(true 면 충돌 횟수를 올리지 않는다). load 전에 정한다 */
    @Volatile
    var lastExitByUser = false

    @Synchronized
    fun load(plan: String = ""): List<String> {
        planName = plan
        rows.clear()
        val text = store.read(OutputLayout.PROGRESS_FILE) ?: return emptyList()   // 처음 실행
        val parsed = PlanProgress.parse(text)
        var changed = false
        val alone = parsed.rows.values.count { it.note.startsWith(RUNNING) } == 1  //// change-hyungchul-20261106-2304 : WP6 독립 검토 6 — 여럿이 돌던 중이면 누가 죽였는지 알 수 없어 횟수를 올리지 않는다
        for ((key, row) in parsed.rows) {
            if (row.note.startsWith(RUNNING)) {                                    // 돌다가 앱이 죽었다
                val n = crashes(row.note) + if (alone && !lastExitByUser) 1 else 0   // change-hyungchul-20261109-1016 : 9단계 통합 검토 4 — 사용자가 끈 것은 세지 않는다
                rows[key] = row.copy(status = PlanProgress.INTERRUPTED, finished = now(), note = "$CRASH:$n")
                log("[이어 하기] $key 는 지난 실행에서 비정상 종료했다(연속 ${n}회)")
                changed = true
            } else {
                rows[key] = row
            }
        }
        if (changed) save()
        return parsed.warnings
    }

    /**
     * 목적: 이번에 돌릴 작업을 고른다.
     * 입력: jobs - 전체 작업(실행 순서), restart - true 면 끝난 작업도 다시 돌린다("처음부터")
     * 출력: 연속 2회 비정상 종료한 작업은 FAILED(ERR_CRASH_LOOP)로 기록된다
     * 리턴: ResumePlan
     */
    @Synchronized
    fun plan(jobs: List<Job>, restart: Boolean = false): ResumePlan {
        val todo = ArrayList<Job>(); val done = ArrayList<Job>(); val loop = ArrayList<Job>()
        var changed = false
        for (job in jobs) {
            val row = rows[job.key]
            when {
                restart -> todo += job                                             // 기록과 상관없이 모두
                row == null -> todo += job                                         // 처음 도는 작업
                row.status == PlanProgress.DONE -> done += job
                row.note.startsWith(CRASH_LOOP) -> loop += job                     // 이미 포기한 작업
                crashes(row.note) >= CRASH_LIMIT -> {
                    rows[job.key] = row.copy(status = PlanProgress.FAILED, note = CRASH_LOOP)
                    log("[이어 하기] ${job.key} 는 연속 ${crashes(row.note)}회 비정상 종료해 다시 돌리지 않는다($CRASH_LOOP)")
                    loop += job; changed = true
                }
                else -> todo += job                                                // FAILED / INTERRUPTED / SKIPPED → 처음부터
            }
        }
        if (changed) save()
        return ResumePlan(todo, done, loop)
    }

    /**
     * 목적: 작업을 시작한다고 적는다(앱이 죽으면 이 표시로 비정상 종료를 안다).
     * 입력: job - 작업, worker - 슬롯 번호 / 출력: 진행 기록 파일 / 리턴: String - 시작 시각(mark 에 다시 넘긴다)
     */
    @Synchronized
    fun begin(job: Job, worker: Int): String {
        val started = now()
        val before = rows[job.key]?.note?.let(::crashes) ?: 0                      // 앞서 비정상 종료한 횟수를 이어 간다
        rows[job.key] = PlanProgressRow(job.key, PlanProgress.INTERRUPTED, worker, started, "", 0, 0, "$RUNNING:$before")
        save()
        return started
    }

    /**
     * 목적: 작업의 끝 상태를 적는다(정상으로 적히면 비정상 종료 횟수는 지워진다).
     * 입력: job, status - DONE / FAILED / INTERRUPTED / SKIPPED, worker, started - begin 이 준 시각(시작하지 않았으면 빈 글),
     *       ok / fail - 파일 수, note - 사유
     * 출력: 진행 기록 파일 / 리턴: 없음
     */
    @Synchronized
    fun mark(job: Job, status: String, worker: Int, started: String, ok: Int, fail: Int, note: String) {
        rows[job.key] = PlanProgressRow(job.key, status, worker, started, now(), ok, fail, note)
        save()
    }

    /**
     * 목적: 작업을 시작하기 전에 결과 폴더 자리를 비운다.
     * 입력: job - 작업
     * 출력: "_<이름>"(중단된 작업의 산출물)은 지워지고, "<이름>"(끝난 결과)은 "_stale_<이름>_<시각>" 으로 바뀐다
     * 리턴: 없음
     */
    fun prepareDir(job: Job) {
        val lang = OutputLayout.langSegments(job)
        val working = OutputLayout.resultDirName(job, working = true)
        val final = OutputLayout.resultDirName(job, working = false)
        if (store.exists(lang, working) && store.delete(lang, working)) {          // D30
            log("[이어 하기] 중단된 작업 폴더를 지웠다: $working")
            for (suffix in listOf(UnifiedCsv.FILE_SUFFIX, DeviceCsv.FILE_SUFFIX, RunMeta.FILE_SUFFIX)) {   // change-hyungchul-20261106-2304 : WP6 독립 검토 9 — 그 시도의 CSV 도 지운다(지워진 TXT 를 가리키는 행이 남지 않게)
                store.delete(lang, OutputLayout.csvName(job, suffix))
            }
        }
        if (store.exists(lang, final)) {
            val stale = OutputLayout.staleDirName(job, stamp())
            if (store.rename(lang, final, stale)) log("[이어 하기] 앞선 결과를 치웠다: $final → $stale")
            else log("[이어 하기] 앞선 결과 폴더 '$final' 을 치우지 못했다 — 새 결과는 \"_\" 폴더로 남는다")
        }
    }

    /**
     * 목적: 앱이 꺼진 기록을 _plan_exits.csv 에 더한다(이미 적힌 시각의 기록은 다시 적지 않는다).
     * 입력: records - 시스템에서 읽은 종료 기록(최근 것부터여도 된다)
     * 출력: 파일이 다시 쓰인다
     * 리턴: List<ExitRecord> - 이번에 새로 적은 것(메모리 부족이 있으면 동시 작업 수를 줄이라고 권한다)
     */
    @Synchronized
    fun recordExits(records: List<ExitRecord>): List<ExitRecord> {
        val old = store.read(OutputLayout.EXITS_FILE)?.removePrefix(CsvFormat.BOM).orEmpty()
        val lines = old.split(CsvFormat.EOL).filter { it.isNotBlank() && it != EXITS_HEADER }
        val known = lines.map { it.substringBefore(',') }.toSet()                  // 이미 적힌 시각
        val fresh = records.filter { it.time !in known }.sortedBy { it.time }
        if (fresh.isEmpty()) return emptyList()
        val added = fresh.map { r -> listOf(r.time, r.reason, r.description, r.pssMb.toString(), r.rssMb.toString()).joinToString(",") { CsvFormat.quote(it) } }
        store.write(OutputLayout.EXITS_FILE, CsvFormat.BOM + (listOf(EXITS_HEADER) + lines + added).joinToString(CsvFormat.EOL) + CsvFormat.EOL)
        return fresh
    }

    /** 목적: 기록을 파일에 쓴다. 입력: 없음 / 출력: _plan_progress.csv / 리턴: 없음 */
    private fun save() = store.write(OutputLayout.PROGRESS_FILE, PlanProgress.format(planName, rows.values))

    /** 목적: 메모에서 비정상 종료 횟수를 읽는다. 입력: note - "RUNNING:1" / "CRASH:2" / 그 밖 / 리턴: Int - 없으면 0 */
    private fun crashes(note: String): Int =
        if (note.startsWith(RUNNING) || note.startsWith("$CRASH:")) note.substringAfter(':', "0").toIntOrNull() ?: 0 else 0
}

/**
 * 목적: 출력 폴더(SAF)에 대한 ResumeStore.
 * 입력(생성자): tree - 출력 폴더 트리
 */
class SafResumeStore(private val tree: SafTree) : ResumeStore {
    override fun read(name: String): String? = tree.readBytes(tree.root, name)?.toString(Charsets.UTF_8)
    override fun write(name: String, text: String) = tree.writeAtomic(tree.root, name, text.toByteArray(Charsets.UTF_8))
    override fun exists(lang: List<String>, dir: String): Boolean = tree.find(tree.ensureDirs(lang), dir) != null
    override fun delete(lang: List<String>, dir: String): Boolean = tree.delete(tree.ensureDirs(lang), dir)
    override fun rename(lang: List<String>, from: String, to: String): Boolean = tree.rename(tree.ensureDirs(lang), from, to)
}
