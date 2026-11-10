/*
 * 파일명: run/JobScheduler.kt
 * 목적 및 기능:
 * - 실행 1회의 작업들을 차례로(또는 동시에) 돌린다(4단계 5-1·5-3). v3 plan/PlanRunner.kt 의 규칙을 옮겼다.
 *     · 이어 하기: 끝난 작업은 건너뛴다(ResumeManager)
 *     · 동시 슬롯: 시간 측정 1, 정확도 1~3. 그 안에서도 오프라인 엔진은 동시 1, ML Kit 은 동시 1
 *       (오프라인은 엔진이 4 스레드를 다 쓰고, ML Kit 은 시스템의 인식 세션을 나눠 쓴다)
 *     · 작업을 시작하기 전: 자산 준비(없으면 받는다) → 발열 대기 → 결과 폴더 정리 → "돌고 있음" 기록
 *       자산을 받는 동안 그 슬롯은 묶여 있다 → 슬롯이 1개인 시간 측정 실행에서는 받는 동안 다른 작업이 시작되지 않는다
 *     · ML Kit 이 먹통이 되면 남은 ML Kit 작업만 SKIPPED 로 건너뛰고 다른 작업은 계속한다(D29)
 *     · 중지 2종: "이 작업 끝나고"(stopAfterCurrent — 새 작업을 꺼내지 않는다) / "지금"(run 을 돌리는 코루틴을 취소한다 —
 *       돌던 작업은 마무리 쓰기를 하고 INTERRUPTED 로 적힌다)
 *     · 출력 폴더 오류(E-OUT)는 실행 전체를 멈춘다(run 이 그 오류를 던진다)
 * - 작업 실행·자산 준비는 함수로 받는다 → PC(JVM)에서 가짜 파이프라인으로 순서·동시 수·중지를 시험한다.
 *
 * add-hyungchul-20261106-2240 — [T6-5]
 */
package com.example.google_stt.run

import com.example.google_stt.domain.AppError                    // 출력 폴더 오류
import com.example.google_stt.domain.catalog.Runtime             // 엔진 종류(동시 제한)
import com.example.google_stt.domain.plan.Job                    // 작업
import com.example.google_stt.domain.plan.PlanProgress           // 상태 이름
import kotlinx.coroutines.CancellationException                  // 취소
import kotlinx.coroutines.CoroutineDispatcher                    // 상태를 다루는 스레드
import kotlinx.coroutines.Dispatchers                            // 기본 스레드
import kotlinx.coroutines.NonCancellable                         // 마무리 기록
import kotlinx.coroutines.channels.Channel                       // 끝난 작업 알림
import kotlinx.coroutines.coroutineScope                         // 자식 작업 범위
import kotlinx.coroutines.ensureActive                           // 실행이 취소됐는지
import kotlinx.coroutines.launch                                 // 작업 시작
import kotlinx.coroutines.withContext                            // 마무리 기록

/**
 * 목적: 실행 1회의 계획.
 * 필드: jobs - 전체 작업(실행 순서), sources - 작업의 음원 목록을 주는 함수(같은 언어 폴더면 같은 목록), params - 실행 값,
 *       planName - 목록 이름(진행 기록 머리), restart - 끝난 작업도 다시 돌릴지
 */
class RunPlan(
    val jobs: List<Job>,
    val sources: (Job) -> List<AudioSource>,
    val params: RunParams,
    val planName: String = "",
    val restart: Boolean = false,
)

/**
 * 목적: 진행 상황(화면·알림·메모리 기록이 읽는다).
 * 필드: total - 전체 작업 수, done / failed / skipped - 이번 실행에서 끝난 수(이미 끝나 있던 것은 done 에 든다),
 *       running - 돌고 있는 작업 이름들, waiting - 기다리는 이유("THERMAL" / "ASSET", 없으면 null)
 */
data class RunProgress(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val running: List<String> = emptyList(),
    val waiting: String? = null,
) {
    /** 끝난 작업 수(성공·실패·건너뜀) */
    val finished: Int get() = done + failed + skipped
}

/**
 * 목적: 스케줄러가 바깥에서 받는 것.
 * 필드: execute - 작업 1개를 돌린다(JobPipeline.execute), ensureAssets - 자산을 준비하고 실패한 자산 ID → 사유를 준다(AssetRepository.ensureReady),
 *       resume - 이어 하기 기록, thermal - 발열 대기, onProgress - 진행이 바뀔 때마다 불린다, onResult - 작업이 끝날 때마다(실측 RTF 저장·요약 쓰기), log - 로그,
 *       control - 스케줄러의 상태(대기열·슬롯·진행)를 다루는 스레드. **한 번에 하나만 도는 디스패처여야 한다** — 여러 작업이 동시에 끝나도
 *                 상태가 섞이지 않게 한다(무거운 계산은 파이프라인·어댑터가 스스로 다른 스레드로 옮긴다)
 */
class SchedulerDeps(
    val execute: suspend (Job, List<AudioSource>, RunParams) -> JobResult,
    val ensureAssets: suspend (Collection<String>) -> Map<String, String>,
    val resume: ResumeManager,
    val thermal: ThermalGate,
    val onProgress: (RunProgress) -> Unit = {},
    val onResult: suspend (JobResult) -> Unit = {},
    val log: (String) -> Unit = {},
    val control: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
)

/**
 * 목적: 작업 스케줄러(실행 1회에 1개).
 * 입력(생성자): deps - 바깥에서 받는 것
 */
class JobScheduler(private val deps: SchedulerDeps) {

    /** 끝난 작업 알림 — 결과 또는 실행을 멈춰야 하는 오류 */
    private class Done(val job: Job, val slot: Int, val result: JobResult?, val fatal: AppError?, val notStarted: Boolean = false)   // change-hyungchul-20261109-1016 : 9단계 통합 검토 7 — notStarted: 시작 전에 "이 작업 끝나고 중지" 가 걸려 돌리지 않았다

    @Volatile
    private var stopAfter = false                                 // "이 작업 끝나고 중지" 요청

    /** 목적: 새 작업을 더 시작하지 않게 한다(돌던 작업은 끝까지 간다). 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    fun stopAfterCurrent() {
        stopAfter = true
        deps.log("이 작업이 끝나면 멈춘다(새 작업을 시작하지 않는다)")
    }

    /**
     * 목적: 계획의 작업들을 돌린다.
     * 입력: plan - 실행 계획
     * 출력: 결과 폴더·진행 기록
     * 리턴: List<JobResult> - 이번 실행에서 돌린(또는 건너뛴) 작업의 결과(끝난 순서)
     * 예외: 취소되면 CancellationException(돌던 작업은 INTERRUPTED 로 적힌다), 출력 폴더를 쓸 수 없으면 AppError.OutputUnavailable / OutputNoSpace
     */
    suspend fun run(plan: RunPlan): List<JobResult> = withContext(deps.control) { runConfined(plan) }

    /**
     * 목적: run 의 본체(control 스레드 하나에서만 돈다 — 아래의 대기열·슬롯·진행 값은 잠금 없이 다룬다).
     * 입력: plan - 실행 계획 / 출력: run 과 같다 / 리턴: run 과 같다
     */
    private suspend fun runConfined(plan: RunPlan): List<JobResult> = coroutineScope {
        deps.resume.load(plan.planName).forEach { deps.log("[이어 하기] $it") }
        val picked = deps.resume.plan(plan.jobs, plan.restart)
        val pending = ArrayList(picked.todo)                                   // 아직 시작하지 않은 작업(순서 유지)
        val results = ArrayList<JobResult>()
        val running = LinkedHashMap<Int, Job>()                                // 슬롯 → 돌고 있는 작업
        val freeSlots = ArrayDeque((1..plan.params.workers.coerceAtLeast(1)).toList())
        val done = Channel<Done>(Channel.UNLIMITED)
        var waiting: String? = null                                            // 기다리는 이유
        var mlKitStuck = false                                                 // ML Kit 먹통(남은 ML Kit 작업을 건너뛴다)
        var progress = RunProgress(total = plan.jobs.size, done = picked.done.size, failed = picked.crashLoop.size)

        // 목적: 진행 상황을 알린다. 입력: 없음 / 리턴: 없음
        fun publish() {
            progress = progress.copy(running = running.values.map { it.label }, waiting = waiting)
            deps.onProgress(progress)
        }
        deps.log("실행 시작: 전체 ${plan.jobs.size}개 중 ${pending.size}개를 돌린다(이미 끝남 ${picked.done.size}, 포기 ${picked.crashLoop.size}), 동시 ${freeSlots.size}")
        publish()

        while (true) {
            // ── 시작할 수 있는 만큼 시작한다 ──
            var i = 0
            while (!stopAfter && freeSlots.isNotEmpty() && i < pending.size) {
                val job = pending[i]
                val kind = job.model.runtime
                if (kind == Runtime.MLKIT && mlKitStuck) {                     // D29: 먹통이면 남은 ML Kit 작업은 건너뛴다
                    pending.removeAt(i)
                    deps.resume.mark(job, PlanProgress.SKIPPED, 0, "", 0, 0, "ERR_MLKIT_STUCK")
                    results += JobResult(job, PlanProgress.SKIPPED, 0, 0, 0, 0.0, 0.0, "ERR_MLKIT_STUCK")
                    progress = progress.copy(skipped = progress.skipped + 1)
                    deps.log("[${job.label}] 건너뜀 — ML Kit 엔진이 응답하지 않는다")
                    continue
                }
                val busy = running.values.any { it.model.runtime == kind }     // 같은 종류가 돌고 있는지
                if (busy && (kind == Runtime.SHERPA_OFFLINE || kind == Runtime.MLKIT)) { i++; continue }   // 오프라인 1, ML Kit 1
                pending.removeAt(i)
                val slot = freeSlots.removeFirst()
                running[slot] = job
                launch {   // change-hyungchul-20261106-2304 : WP6 독립 검토 2 — 작업 안에서 난 취소(실행 자체는 취소되지 않음)로 알림이 빠지면 루프가 영원히 기다린다
                    val d = try {
                        runOne(plan, job, slot) { w -> waiting = w; publish() }
                    } catch (e: CancellationException) {
                        ensureActive()                                         // 실행이 취소된 것이면 그대로 끝낸다
                        deps.log("[${job.label}] 작업 안에서 취소가 났다(실행은 계속) — 이 작업만 실패로 둔다")
                        markQuietly(job, PlanProgress.FAILED, slot, "", "ERR_INTERNAL_CANCEL")
                        Done(job, slot, JobResult(job, PlanProgress.FAILED, 0, 0, 0, 0.0, 0.0, "ERR_INTERNAL_CANCEL"), null)
                    }
                    done.send(d)
                }
            }
            publish()
            if (running.isEmpty()) break                                       // 돌고 있는 것이 없으면 끝(중지 요청으로 남은 작업이 있을 수 있다)

            // ── 하나가 끝나기를 기다린다 ──
            val d = done.receive()
            running.remove(d.slot); freeSlots.addFirst(d.slot)
            if (d.notStarted) { pending.add(0, d.job); continue }               // change-hyungchul-20261109-1016 : 9단계 통합 검토 7 — 시작하지 않은 작업은 대기열 맨 앞으로 되돌린다
            d.fatal?.let { throw it }                                          // 출력 폴더 오류 → 실행 전체를 멈춘다(다른 작업은 취소된다)
            val r = d.result!!
            results += r
            if (r.mlKitStuck) mlKitStuck = true
            progress = if (r.status == PlanProgress.DONE) progress.copy(done = progress.done + 1) else progress.copy(failed = progress.failed + 1)
            try {   // change-hyungchul-20261106-2304 : WP6 독립 검토 1 — 결과 뒤처리(RTF 저장·요약 쓰기)의 실패가 실행을 죽이지 않게
                deps.onResult(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError.OutputUnavailable) {
                throw e                                                        // 출력 폴더 오류는 실행 전체를 멈춘다
            } catch (e: AppError.OutputNoSpace) {
                throw e
            } catch (e: Exception) {
                deps.log("[${r.job.label}] 결과 뒤처리 실패(실행은 계속): ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        if (pending.isNotEmpty()) deps.log("멈춤: 시작하지 않은 작업 ${pending.size}개가 남았다")
        results
    }

    /**
     * 목적: 작업 1개를 준비하고 돌린 뒤 끝 상태를 적는다.
     * 입력: plan - 실행 계획, job - 작업, slot - 슬롯 번호, onWaiting - 기다리는 이유를 알리는 함수(없어지면 null)
     * 출력: 진행 기록
     * 리턴: Done - 결과(또는 실행을 멈춰야 하는 오류)
     * 예외: 취소되면 INTERRUPTED 로 적고 CancellationException
     */
    private suspend fun runOne(plan: RunPlan, job: Job, slot: Int, onWaiting: (String?) -> Unit): Done {
        var started = ""
        try {
            if (job.assetIds.isNotEmpty()) {
                onWaiting("ASSET")
                val failed = try { deps.ensureAssets(job.assetIds) } finally { onWaiting(null) }
                if (failed.isNotEmpty()) {                                     // 자산을 받지 못하면 이 작업만 실패
                    val note = "ERR_ASSET:" + failed.entries.joinToString("; ") { "${it.key} ${it.value}" }
                    deps.log("[${job.label}] 자산 준비 실패 — $note")
                    deps.resume.mark(job, PlanProgress.FAILED, slot, "", 0, 0, note)
                    return Done(job, slot, JobResult(job, PlanProgress.FAILED, 0, 0, 0, 0.0, 0.0, note), null)
                }
            }
            deps.thermal.awaitCool { hot -> onWaiting(if (hot) "THERMAL" else null) }
            if (stopAfter) return Done(job, slot, null, null, notStarted = true)   // change-hyungchul-20261109-1016 : 9단계 통합 검토 7 — 자산·발열을 기다리는 사이 "이 작업 끝나고 중지" 가 걸렸으면 시작하지 않는다
            deps.resume.prepareDir(job)                                        // 중단된 "_" 폴더 삭제, 앞선 결과는 "_stale_" 로
            started = deps.resume.begin(job, slot)
            val r = deps.execute(job, plan.sources(job), plan.params)
            deps.resume.mark(job, r.status, slot, started, r.ok, r.fail, r.note)
            deps.log("[${job.label}] ${r.status} — 파일 ${r.files}개(실패 ${r.fail}), ${"%.1f".format(r.elapsedSec)}초" + if (r.note.isEmpty()) "" else " [${r.note}]")
            return Done(job, slot, r, null)
        } catch (e: CancellationException) {
            if (started.isNotEmpty()) withContext(NonCancellable) { markQuietly(job, PlanProgress.INTERRUPTED, slot, started, "사용자 중지") }
            throw e
        } catch (e: AppError.OutputUnavailable) {
            markQuietly(job, PlanProgress.FAILED, slot, started, e.code)
            return Done(job, slot, null, e)
        } catch (e: AppError.OutputNoSpace) {
            markQuietly(job, PlanProgress.FAILED, slot, started, e.code)
            return Done(job, slot, null, e)
        } catch (e: Exception) {   // change-hyungchul-20261106-2304 : WP6 독립 검토 1 — 그 밖의 예외는 이 작업만 실패시키고 다음 작업으로 간다
            val note = "ERR_INTERNAL:${e.javaClass.simpleName}: ${e.message}"
            deps.log("[${job.label}] 예상하지 못한 오류 — $note")
            markQuietly(job, PlanProgress.FAILED, slot, started, note)
            return Done(job, slot, JobResult(job, PlanProgress.FAILED, 0, 0, 0, 0.0, 0.0, note), null)
        }
    }

    /**
     * 목적: 끝 상태를 적되, 출력 폴더가 이미 탈이 나 쓸 수 없어도 원래 오류(또는 취소)를 덮지 않는다.
     * 입력: job, status, slot, started, note / 출력: 진행 기록(실패하면 로그) / 리턴: 없음
     */
    private fun markQuietly(job: Job, status: String, slot: Int, started: String, note: String) {
        try {
            deps.resume.mark(job, status, slot, started, 0, 0, note)
        } catch (e: AppError) {
            deps.log("[${job.label}] 진행 기록을 쓰지 못했다: ${e.message}")
        }
    }
}
