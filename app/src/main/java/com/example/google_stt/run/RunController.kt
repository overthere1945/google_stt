/*
 * 파일명: run/RunController.kt
 * 목적 및 기능:
 * - 실행을 시작·중지하고 그 상태를 들고 있는다(앱 전역 1개 — 4단계 5-1). 화면은 state 를 보고, 시작·중지만 요청한다.
 * - start: 요청을 기억하고 포그라운드 서비스를 시작한다 → 서비스가 execute 를 불러 실제로 돈다(화면이 꺼져도 이어진다).
 * - execute(실행 1회): 출력 폴더 연결 → 꺼진 이유 기록 → 조합 목록(_plan.csv) → 메모리 기록 시작 → 스케줄러 실행 →
 *   작업이 끝날 때마다 실측 RTF 저장·_summary.csv 갱신 → 끝 상태(FINISHED / STOPPED / ERROR)
 * - stop: "지금" 은 실행 코루틴을 취소하고, "이 작업 끝나고" 는 스케줄러에 알린다.
 *
 * add-hyungchul-20261106-2242 — [T6-6]
 */
package com.example.google_stt.run

import android.app.ActivityManager                                   // 앱이 꺼진 기록
import android.app.ApplicationExitInfo                               // 꺼진 이유 값
import android.content.Context                                       // 서비스 시작·시스템 서비스
import androidx.documentfile.provider.DocumentFile                   // 출력 폴더
import com.example.google_stt.AppContainer                           // 객체 묶음
import com.example.google_stt.data.saf.AudioItem                     // 음원 항목
import com.example.google_stt.data.saf.OutputLayout                  // 파일 이름
import com.example.google_stt.data.saf.ResultWriter                  // 요약 쓰기
import com.example.google_stt.data.saf.SafTree                       // 출력 폴더 트리
import com.example.google_stt.data.saf.SummaryRow                    // 요약 한 줄
import com.example.google_stt.domain.AppError                        // 실행을 멈추는 오류
import com.example.google_stt.domain.condition.RunPurpose            // 실행 목적
import com.example.google_stt.domain.csv.CsvFormat                   // BOM·줄 끝
import com.example.google_stt.domain.eta.RtfKey                      // 실측 RTF 키
import com.example.google_stt.domain.plan.Job                        // 작업
import com.example.google_stt.domain.plan.PlanCsv                    // 조합 목록 형식
import com.example.google_stt.domain.plan.PlanProgress               // 상태 이름
import java.text.SimpleDateFormat                                    // 시각 글자
import java.util.Date                                                // 지금 시각
import java.util.Locale                                              // 표기 고정
import kotlinx.coroutines.CancellationException                      // 취소
import kotlinx.coroutines.NonCancellable                             // 마무리
import kotlinx.coroutines.coroutineScope                             // 메모리 기록을 같이 돌린다
import kotlinx.coroutines.currentCoroutineContext                    // 실행 코루틴 얻기
import kotlinx.coroutines.flow.MutableStateFlow                      // 상태 보관
import kotlinx.coroutines.flow.StateFlow                             // 상태 읽기
import kotlinx.coroutines.flow.update                                // 상태 고치기
import kotlinx.coroutines.job                                        // 실행 코루틴의 Job
import kotlinx.coroutines.launch                                     // 메모리 기록 시작
import kotlinx.coroutines.withContext                                // 스레드 바꾸기

/**
 * 목적: 실행 요청(화면이 만든다).
 * 필드: output - 출력 폴더, jobs - 작업들(실행 순서), audio - 언어 폴더 이름 → 그 폴더의 음원들, workers - 동시 작업 수,
 *       purpose - 실행 목적, restart - 끝난 작업도 다시 돌릴지("처음부터"), planName - 목록 이름
 */
class RunRequest(
    val output: DocumentFile,
    val jobs: List<Job>,
    val audio: Map<String, List<AudioItem>>,
    val workers: Int,
    val purpose: RunPurpose,
    val restart: Boolean = false,
    val planName: String = "",
)

/**
 * 목적: 실행 컨트롤러.
 * 입력(생성자): context - Application Context, c - 객체 묶음
 */
class RunController(private val context: Context, private val c: AppContainer) {

    private val _state = MutableStateFlow(RunState())

    /** 실행 상태(화면·알림이 본다) */
    val state: StateFlow<RunState> = _state

    @Volatile private var request: RunRequest? = null               // 서비스가 가져갈 요청
    @Volatile private var runJob: kotlinx.coroutines.Job? = null    // 실행 코루틴("지금 중지" 가 취소한다)
    @Volatile private var scheduler: JobScheduler? = null           // 돌고 있는 스케줄러

    /**
     * 목적: 실행을 시작한다(앱 화면이 앞에 있을 때 부른다).
     * 입력: req - 실행 요청
     * 출력: 포그라운드 서비스가 시작되고 상태가 PREPARING 이 된다
     * 리턴: Boolean - 이미 실행 중이면 false(새로 시작하지 않는다)
     */
    @Synchronized
    fun start(req: RunRequest): Boolean {
        if (_state.value.phase.active) return false
        request = req
        _state.value = RunState(RunPhase.PREPARING, RunProgress(total = req.jobs.size))
        try {
            RunService.run(context)
        } catch (e: IllegalStateException) {                                    // E-RUN-01: 앱이 뒤에 있어 서비스를 시작할 수 없다
            request = null
            _state.value = RunState(RunPhase.ERROR, message = "E-RUN-01: 실행 서비스를 시작할 수 없다 — 앱 화면에서 다시 시작한다(${e.message})")
            c.log.e(_state.value.message, e)
        }
        return true
    }

    /**
     * 목적: 실행을 멈춘다.
     * 입력: mode - 지금 / 이 작업 끝나고 / 출력: 상태가 바뀌고 실행이 멈춘다 / 리턴: 없음
     */
    fun stop(mode: StopMode) {
        if (!_state.value.phase.active) return
        when (mode) {
            StopMode.NOW -> { _state.update { it.copy(phase = RunPhase.STOPPING) }; runJob?.cancel() }
            StopMode.AFTER_CURRENT -> { _state.update { it.copy(stopAfterCurrent = true) }; scheduler?.stopAfterCurrent() }
        }
    }

    /**
     * 목적: 기억해 둔 요청을 실제로 돌린다(RunService 가 자기 코루틴에서 부른다).
     * 입력: 없음 / 출력: 결과 폴더·기록, 상태 / 리턴: 없음(취소되면 CancellationException)
     */
    suspend fun execute() {
        val req = request ?: return                                             // 요청 없이 불렸다
        request = null
        runJob = currentCoroutineContext().job
        if (_state.value.phase == RunPhase.STOPPING) {   // change-hyungchul-20261106-2304 : WP6 독립 검토 4 — 준비 중에 누른 "지금 중지" 를 놓치지 않는다
            _state.update { it.copy(phase = RunPhase.STOPPED, message = "사용자 중지") }
            runJob = null
            return
        }
        val clock = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())   // 실행 ID
        val tree = SafTree(context.contentResolver, req.output) { c.log.w(it) }
        try {
            c.log.attach(tree)                                                  // 이제부터 로그가 출력 폴더의 _app_log.txt 에도 남는다
            c.log.i("실행 준비: 작업 ${req.jobs.size}개, 동시 ${req.workers}, 목적 ${req.purpose}, 실행 ID $stamp")
            val resume = ResumeManager(SafResumeStore(tree), { synchronized(clock) { clock.format(Date()) } }, { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) }) { c.log.i(it) }
            val exits = withContext(kotlinx.coroutines.Dispatchers.IO) {
                tree.recoverTmp(tree.root)   // change-hyungchul-20261106-2304 : WP6 독립 검토 3 — 진행 기록을 쓰다 죽었으면 남은 임시 파일을 살린다(안 그러면 기록이 통째로 사라진다)
                tree.writeAtomic(tree.root, OutputLayout.PLAN_FILE, PlanCsv.format(PlanCsv.fromJobs(req.jobs), "run=$stamp").toByteArray(Charsets.UTF_8))   // 조합 목록
                resume.recordExits(exitRecords(clock))                          // 지난번에 앱이 꺼진 이유
            }
            exits.forEach { c.log.w("[종료 이유] ${it.time} ${it.reason} ${it.description} (PSS ${it.pssMb} MB)") }
            val pipeline = JobPipeline(c.pipelineDeps(tree))
            val sources = req.audio.mapValues { (_, items) -> items.map(c::audioSource) }
            val summary = ArrayList<SummaryRow>()
            val writer = ResultWriter(tree)
            val sched = JobScheduler(SchedulerDeps(
                execute = pipeline::execute,
                ensureAssets = { ids -> c.assets.ensureReady(ids).mapValues { it.value.error.message ?: it.value.reason.name } },
                resume = resume,
                thermal = ThermalGate(c.probe::thermalLevel, { c.log.i(it) }),
                onProgress = { p -> _state.update { s -> if (s.phase == RunPhase.STOPPING) s.copy(progress = p) else s.copy(phase = if (p.waiting != null) RunPhase.WAITING else RunPhase.RUNNING, progress = p) } },
                onResult = { r -> onResult(r, req, summary, writer) },
                log = { c.log.i(it) },
            )).also { scheduler = it }
            if (_state.value.stopAfterCurrent) sched.stopAfterCurrent()   // change-hyungchul-20261106-2304 : WP6 독립 검토 4 — 준비 중에 누른 "이 작업 끝나고" 를 전한다
            val plan = RunPlan(req.jobs, { job -> sources[job.lang.name].orEmpty() }, RunParams(stamp, c.isoNow(), req.workers, req.purpose,
                downloadOverlap = { c.assets.states.values.any { it.value is com.example.google_stt.data.assets.AssetState.Downloading } }), req.planName, req.restart)
            coroutineScope {
                val mem = launch(kotlinx.coroutines.Dispatchers.IO) { memLogger(tree, clock).run() }   // 30초마다 메모리 기록   // change-hyungchul-20261106-2304 : WP6 독립 검토 11 — 파일 쓰기를 입출력 스레드에서
                try { sched.run(plan) } finally { mem.cancel() }
            }
            val p = _state.value.progress
            val all = p.finished >= p.total
            _state.update { it.copy(phase = if (all) RunPhase.FINISHED else RunPhase.STOPPED, message = if (all) "" else "남은 작업 ${p.total - p.finished}개 — 이어 하기로 계속할 수 있다") }
            c.log.i("실행 끝: ${_state.value.phase} — 완료 ${p.done}, 실패 ${p.failed}, 건너뜀 ${p.skipped} / 전체 ${p.total}")
        } catch (e: CancellationException) {
            _state.update { it.copy(phase = RunPhase.STOPPED, message = "사용자 중지 — 이어 하기로 계속할 수 있다") }
            c.log.i("실행 중지(사용자)")
            throw e
        } catch (e: AppError) {                                                 // 출력 폴더를 쓸 수 없다 등 — 실행 전체를 멈춘다
            _state.update { it.copy(phase = RunPhase.ERROR, message = e.message ?: e.code) }
            c.log.e("실행 중단: ${e.message}", e)
        } catch (e: Exception) {   // change-hyungchul-20261106-2304 : WP6 독립 검토 1 — 예상하지 못한 예외에도 상태를 끝 단계로 둔다(안 그러면 "실행 중" 에 고착된다)
            _state.update { it.copy(phase = RunPhase.ERROR, message = "예상하지 못한 오류 — ${e.javaClass.simpleName}: ${e.message}") }
            c.log.e("실행 중단(예상하지 못한 오류)", e)
        } finally {
            scheduler = null; runJob = null
            withContext(NonCancellable) { c.log.flush(); c.log.attach(null) }
        }
    }

    /**
     * 목적: 작업이 끝날 때마다 실측 RTF 를 저장하고 요약을 다시 쓴다.
     * 입력: r - 작업 결과, req - 실행 요청, summary - 지금까지의 요약(이 함수가 더한다), writer - 요약 쓰기
     * 출력: 실측 RTF 저장소, _summary.csv / 리턴: 없음
     */
    private suspend fun onResult(r: JobResult, req: RunRequest, summary: MutableList<SummaryRow>, writer: ResultWriter) {
        summary += SummaryRow(r.job.key, r.status, r.files, r.ok, r.fail, r.elapsedSec, r.audioSec)
        withContext(kotlinx.coroutines.Dispatchers.IO) { writer.writeSummary(summary) }
        if (r.status == PlanProgress.DONE && r.fail == 0 && r.audioSec > 0.0) {     // 끝까지 깨끗하게 돈 작업만 예상 시간 계산에 쓴다
            c.rtf.addMeasured(RtfKey(c.probe.deviceKey, r.job.model.engine, r.job.vad.engine.token, req.workers), r.elapsedSec / r.audioSec)
        }
    }

    /**
     * 목적: 메모리 기록기를 만든다(머리줄은 파일이 비어 있을 때만 쓴다).
     * 입력: tree - 출력 폴더, clock - 시각 표기 / 출력: 없음 / 리턴: MemLogger
     */
    private fun memLogger(tree: SafTree, clock: SimpleDateFormat) = MemLogger(
        memory = c.probe::memory, pssMb = c.probe::pssMb, thermal = c.probe::thermalStatus,
        progress = { _state.value.progress.let { Triple(it.finished, it.total, it.running) } },
        now = { synchronized(clock) { clock.format(Date()) } },
        append = { line ->
            val head = if (tree.size(tree.root, OutputLayout.MEMLOG_FILE) == 0L) CsvFormat.BOM + MemLogger.HEADER + CsvFormat.EOL else ""
            tree.append(tree.root, OutputLayout.MEMLOG_FILE, (head + line + CsvFormat.EOL).toByteArray(Charsets.UTF_8))
        },
        log = { c.log.w(it) },
    )

    /**
     * 목적: 시스템이 기억하는 "이 앱이 꺼진 기록"(최근 10건)을 읽는다.
     * 입력: clock - 시각 표기 / 출력: 없음 / 리턴: List<ExitRecord>
     */
    private fun exitRecords(clock: SimpleDateFormat): List<ExitRecord> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        return am.getHistoricalProcessExitReasons(context.packageName, 0, 10).map { info ->
            ExitRecord(synchronized(clock) { clock.format(Date(info.timestamp)) }, reasonName(info.reason), info.description.orEmpty(), info.pss / 1024, info.rss / 1024)
        }
    }

    /** 목적: 꺼진 이유 값을 이름으로 바꾼다(v3 PlanState.reasonName 과 같은 이름). 입력: reason - REASON_* / 출력: 없음 / 리턴: String */
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"                 // 메모리 부족으로 시스템이 종료
        ApplicationExitInfo.REASON_CRASH -> "CRASH"                           // Java/Kotlin 예외로 종료
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"             // 네이티브(sherpa·onnxruntime 등) 충돌
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"                     // 신호로 종료
        ApplicationExitInfo.REASON_ANR -> "ANR"                               // 응답 없음
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"                   // 앱 스스로 종료
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"         // 사용자가 강제 종료
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        else -> "REASON_$reason"
    }
}
