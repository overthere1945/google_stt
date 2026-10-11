/*
 * 파일명: ui/PlanUi.kt
 * 목적 및 기능:
 * - "목록 실행" 화면 부분(v2.5.0, 사용자 결정 Q34 C안): [목록에 추가] [목록 비우기] [목록 저장] [목록 불러오기] [목록 실행] [중지]
 * - 목록 파일: /sdcard/Documents/stt_plan 폴더의 .csv (UTF-8 BOM, 머리줄 lang,model,feed,vad,seg, '#' 주석, 칸 안 ';' 로 여러 값)
 *   PC 에서 MTP 로 복사 → 고침 → 다시 복사해 [목록 불러오기] 로 읽는다. 저장 직후 MediaScanner 에 알려 MTP 에 바로 보이게 한다.
 * - [목록 실행]: 검증 → 시작 전 점검(이어 하기·입력 하위 폴더·모델 파일) → 확인 창 → PlanRunner 로 실행.
 *   실행 중에는 화면을 켜 둔 채로 두고(FLAG_KEEP_SCREEN_ON) 화면 회전을 잠근다(회전하면 Activity 가 다시 만들어져 실행이 끊긴다).
 * - [중지]: 선택 창 "지금 바로 / 이 작업 끝나고"(사용자 결정 Q39) — 어느 쪽이든 목록 전체가 멈춘다.
 *
 * add-hyungchul-20261011-2000 — v2.5.0 목록 실행
 */
package com.example.google_stt.ui

import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.google_stt.R
import com.example.google_stt.plan.PLAN_OFFLINE_MAX
import com.example.google_stt.plan.PLAN_WORKERS
import com.example.google_stt.plan.PlanCheck
import com.example.google_stt.plan.PlanCsv
import com.example.google_stt.plan.PlanJob
import com.example.google_stt.plan.PlanPrep
import com.example.google_stt.plan.PlanRunner
import com.example.google_stt.stt.SttCatalog
import com.example.google_stt.stt.describe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 목록 파일 폴더 이름(/sdcard/Documents 아래) */
private const val PLAN_DIR_NAME = "stt_plan"

/** 오류 창에 보여 줄 최대 줄 수 */
private const val MAX_ERROR_LINES = 30

/**
 * 목적: 목록 실행 화면 컨트롤러.
 * 입력(생성자): activity — 화면, runner — 목록 실행기, log / toast — 화면 로그·토스트,
 *              readCurrent — 지금 화면 설정을 작업 1개로 읽기(넣을 수 없으면 이유를 알리고 null),
 *              folders — 고른 입력·출력 폴더, isBusy — START 배치·목록이 돌고 있는지, setBusy — 실행 중 표시(START 버튼 잠금)
 */
class PlanUi(
    private val activity: AppCompatActivity,
    private val runner: PlanRunner,
    private val log: (String) -> Unit,
    private val toast: (String) -> Unit,
    private val readCurrent: () -> PlanJob?,
    private val folders: () -> Pair<Uri?, Uri?>,
    private val isBusy: () -> Boolean,
    private val setBusy: (Boolean) -> Unit,
) {
    private val jobs = mutableListOf<PlanJob>()                 // 지금 목록(펼친 작업)
    private var planName = "화면 목록"                           // 목록 이름(불러온·저장한 파일 이름)
    private var planJob: Job? = null                            // 실행 중인 목록 코루틴(지금 바로 중지 = cancel)
    private var preparing = false                               // 시작 전 점검 중(두 번 눌러 점검이 겹치지 않게)
    private var savedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED   // 회전 잠금 전 값

    private lateinit var addButton: Button
    private lateinit var clearButton: Button
    private lateinit var saveButton: Button
    private lateinit var loadButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var summaryView: TextView
    private lateinit var statusView: TextView

    /**
     * 목적: 버튼·글 View 를 찾고 누를 때 할 일을 붙인다(onCreate 에서 1회).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    fun setupPlanUi() {
        addButton = activity.findViewById(R.id.planAddButton)
        clearButton = activity.findViewById(R.id.planClearButton)
        saveButton = activity.findViewById(R.id.planSaveButton)
        loadButton = activity.findViewById(R.id.planLoadButton)
        startButton = activity.findViewById(R.id.planStartButton)
        stopButton = activity.findViewById(R.id.planStopButton)
        summaryView = activity.findViewById(R.id.planSummary)
        statusView = activity.findViewById(R.id.planStatus)
        addButton.setOnClickListener { add() }
        clearButton.setOnClickListener { clear() }
        saveButton.setOnClickListener { save() }
        loadButton.setOnClickListener { load() }
        startButton.setOnClickListener { start() }
        stopButton.setOnClickListener { stop() }
        refresh()
    }

    /** 목적: 목록 상태 한 줄을 화면에 쓴다(PlanRunner onStatus 로도 부른다). 입력: text / 리턴: 없음 */
    fun showStatus(text: String) {
        activity.runOnUiThread { statusView.text = text }
    }

    /** 목적: [목록에 추가] — 지금 화면 설정을 작업 1개로 넣는다. 리턴: 없음 */
    private fun add() {
        val job = readCurrent() ?: return                                       // 넣을 수 없으면 readCurrent 가 이유를 알렸다
        val errs = PlanCheck.errors(listOf(job), PlanRunner.appModels(), PlanRunner.googleModels(), feedNames(), PlanRunner.vadPresets().keys)
        if (errs.isNotEmpty()) { toast(errs.first()); return }
        if (jobs.any { it.csvLine == job.csvLine }) { toast("이미 목록에 있다: ${job.label}"); return }
        jobs += job
        log("[목록] 추가: ${job.label}")
        refresh()
    }

    /** 목적: [목록 비우기] — 확인 후 목록을 비운다. 리턴: 없음 */
    private fun clear() {
        if (jobs.isEmpty()) return
        AlertDialog.Builder(activity)
            .setTitle("목록 비우기")
            .setMessage("작업 ${jobs.size}개를 지운다(저장한 파일은 그대로).")
            .setPositiveButton("비우기") { _, _ -> jobs.clear(); planName = "화면 목록"; refresh() }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 목적: [목록 저장] — /sdcard/Documents/stt_plan/plan_<시각>.csv 로 저장한다. 리턴: 없음 */
    private fun save() {
        if (jobs.isEmpty()) { toast("목록이 비어 있다"); return }
        if (!ensureAllFilesAccess()) return
        val dir = planDir().apply { mkdirs() }                                   // 폴더가 없으면 만든다
        val name = "plan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val file = File(dir, name)
        runCatching {
            file.writeText(PlanCsv.format(jobs, "google_stt v2.5.0 목록 — 작업 ${jobs.size}개"), Charsets.UTF_8)
        }.onFailure { toast("저장 실패: ${describe(it)}"); return }
        MediaScannerConnection.scanFile(activity, arrayOf(file.absolutePath), arrayOf("text/csv"), null)   // MTP 에 바로 보이게
        planName = name
        log("[목록] 저장: ${file.absolutePath} (작업 ${jobs.size}개)")
        toast("저장: $name")
        refresh()
    }

    /** 목적: [목록 불러오기] — stt_plan 폴더의 .csv 를 골라 읽는다(오류가 있으면 읽지 않고 보여 준다). 리턴: 없음 */
    private fun load() {
        if (!ensureAllFilesAccess()) return
        val files = planDir().listFiles { f -> f.isFile && f.name.endsWith(".csv", ignoreCase = true) }
            ?.sortedByDescending { it.name }.orEmpty()                          // 최근 이름이 위로
        if (files.isEmpty()) { toast("${planDir().absolutePath} 에 .csv 가 없다"); return }
        AlertDialog.Builder(activity)
            .setTitle("목록 불러오기")
            .setItems(files.map { it.name }.toTypedArray()) { _, which -> loadFile(files[which]) }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 목적: 목록 파일 1개를 읽어 검증하고, 통과하면 지금 목록으로 바꾼다.
     * 입력: file — 목록 파일 / 리턴: 없음
     */
    private fun loadFile(file: File) {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse { toast("읽기 실패: ${describe(it)}"); return }
        val parsed = PlanCsv.parse(text)
        val errs = parsed.errors + PlanCheck.errors(parsed.jobs, PlanRunner.appModels(), PlanRunner.googleModels(), feedNames(), PlanRunner.vadPresets().keys)
        if (errs.isNotEmpty()) { showErrors("${file.name} 오류 — 읽지 않았다", errs); return }
        jobs.clear()
        jobs += parsed.jobs
        planName = file.name
        log("[목록] 불러오기: ${file.name} — 작업 ${jobs.size}개(중복 ${parsed.duplicates}개 제거)")
        refresh()
    }

    /** 목적: [목록 실행] — 검증 → 시작 전 점검 → 확인 창. 리턴: 없음 */
    private fun start() {
        if (isBusy() || preparing) { toast("이미 실행 중이다"); return }
        if (jobs.isEmpty()) { toast("목록이 비어 있다"); return }
        val (inUri, outUri) = folders()
        if (inUri == null || outUri == null) { toast("입력(input_sound 처럼 kr·en 을 담은 폴더)·출력 폴더를 먼저 고른다"); return }
        val errs = PlanCheck.errors(jobs, PlanRunner.appModels(), PlanRunner.googleModels(), feedNames(), PlanRunner.vadPresets().keys)
        if (errs.isNotEmpty()) { showErrors("목록 오류", errs); return }
        val snapshot = jobs.toList()                                            // 점검 중에 목록이 바뀌어도 영향 없게
        preparing = true
        refresh()
        activity.lifecycleScope.launch {
            val prep = runCatching { runner.prepare(snapshot, inUri, outUri) }
                .getOrElse { preparing = false; refresh(); toast("점검 실패: ${describe(it)}"); return@launch }
            preparing = false
            refresh()
            if (prep.errors.isNotEmpty()) { showErrors("시작할 수 없다", prep.errors); return@launch }
            confirm(prep, inUri, outUri)
        }
    }

    /**
     * 목적: 확인 창(작업 수·이어 하기·동시 실행·화면 켜 두기 안내) → [시작] 이면 실행.
     * 입력: prep — 점검 결과, inUri / outUri — 고른 폴더 / 리턴: 없음
     */
    private fun confirm(prep: PlanPrep, inUri: Uri, outUri: Uri) {
        val pending = prep.pending.size
        if (pending == 0) { toast("모든 작업이 이미 DONE 이다(진행 기록 기준)"); return }
        val msg = buildString {
            append("작업 ${prep.jobs.size}개 중 이번 실행 ${pending}개\n")
            if (prep.resumed) append("이어 하기: 진행 기록에서 DONE ${prep.done.size}개 건너뜀, 날짜 폴더 ${prep.date} 그대로\n")
            else append("새 목록: 날짜 폴더 ${prep.date}\n")
            append("동시 ${PLAN_WORKERS}개(오프라인 엔진은 ${PLAN_OFFLINE_MAX}개)\n")
            append("결과: <출력>/<kr|en>/${prep.date}_<FEED>/<엔진>_<모델>_<조건>/…\n\n")
            append("실행 중에는 화면을 켜 둔 채로 두고 회전을 잠근다. 충전기를 연결하고 전원 버튼·뒤로 가기를 누르지 않는다.")
        }
        AlertDialog.Builder(activity)
            .setTitle("목록 실행")
            .setMessage(msg)
            .setPositiveButton("시작") { _, _ -> begin(prep, inUri, outUri) }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 목적: 목록 실행을 시작하고, 끝나면(정상·중지·예외) 화면을 되돌린다.
     * 입력: prep, inUri, outUri / 리턴: 없음
     */
    private fun begin(prep: PlanPrep, inUri: Uri, outUri: Uri) {
        // 검토 지적 5: 확인 창이 떠 있는 동안 START 배치나 다른 목록이 시작됐을 수 있다 → 다시 확인
        if (isBusy() || planJob != null) { toast("다른 실행이 돌고 있어 시작하지 않았다"); return }
        setBusy(true)                                                           // START 버튼 잠금
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // 화면 켜 두기(목록 실행 중만)
        savedOrientation = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED  // 회전 잠금(Activity 재생성으로 끊기는 것을 막는다)
        // LAZY: planJob 에 먼저 넣은 뒤 시작한다(바로 끝나는 경우에도 finally 의 planJob = null 이 마지막이 되게)
        val job = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            var failure: Throwable? = null                                      // 중지가 아닌 오류(검토 지적 2)
            try {
                runner.run(prep, planName, inUri, outUri)
            } catch (e: CancellationException) {
                throw e                                                         // 지금 바로 중지 — finally 에서 안내
            } catch (e: Throwable) {
                failure = e                                                     // 앱이 죽지 않게 받아서 로그로 남긴다
                log("[목록][실패] 목록 실행이 오류로 멈췄다 — ${describe(e)} (같은 출력 폴더로 [목록 실행] 하면 이어서 돈다)")
                showStatus("오류로 멈춤 — ${describe(e)}")
            } finally {
                if (failure == null && coroutineContext[Job]?.isCancelled == true) {   // 지금 바로 중지(또는 화면 종료)로 끊겼다
                    log("[목록] 지금 바로 중지 — 끊긴 작업은 INTERRUPTED 로 남았고, 다음 [목록 실행] 때 처음부터 다시 돈다")
                    showStatus("중지됨 — 같은 출력 폴더로 [목록 실행] 하면 이어서 돈다")
                }
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                activity.requestedOrientation = savedOrientation
                planJob = null
                setBusy(false)
                refresh()
                toast("목록 실행 끝")
            }
        }
        planJob = job
        refresh()                                                               // [중지] 만 켜진 상태로
        job.start()
    }

    /** 목적: [중지] — "지금 바로 / 이 작업 끝나고" 선택 창(Q39). 리턴: 없음 */
    private fun stop() {
        val job = planJob ?: return
        AlertDialog.Builder(activity)
            .setTitle("목록 중지")
            .setItems(
                arrayOf(
                    "지금 바로 중지 (지금 파일이 끝나는 대로 — REALTIME 스트리밍은 그 파일 길이만큼 걸린다. 돌고 있는 작업은 INTERRUPTED, 다음에 처음부터)",
                    "이 작업 끝나고 중지 (돌고 있는 작업은 끝까지)",
                ),
            ) { _, which ->
                if (which == 0) {
                    log("[목록] 지금 바로 중지 요청")
                    job.cancel()
                } else {
                    log("[목록] 이 작업 끝나고 중지 요청 — 새 작업을 잡지 않는다")
                    runner.stopAfterCurrent = true
                    showStatus("돌고 있는 작업이 끝나면 멈춘다")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 목적: 목록 요약·버튼 상태를 갱신한다. 리턴: 없음 */
    private fun refresh() {
        val running = planJob != null
        val langs = jobs.groupingBy { it.lang }.eachCount().entries.joinToString(" / ") { "${it.key} ${it.value}" }
        summaryView.text = if (jobs.isEmpty()) "목록: 비어 있음"
        else "목록: 작업 ${jobs.size}개 ($langs) · 모델 ${jobs.map { it.model }.distinct().size}종 · " +
            "VAD ${jobs.map { it.vad }.distinct().size}종 — $planName"
        addButton.isEnabled = !running
        clearButton.isEnabled = !running
        loadButton.isEnabled = !running && !preparing
        startButton.isEnabled = !running && !preparing
        stopButton.isEnabled = running
    }

    /**
     * 목적: 오류 목록을 창으로 보여 준다(앞 30줄).
     * 입력: title, errors / 리턴: 없음
     */
    private fun showErrors(title: String, errors: List<String>) {
        val head = errors.take(MAX_ERROR_LINES).joinToString("\n")
        val more = if (errors.size > MAX_ERROR_LINES) "\n… 외 ${errors.size - MAX_ERROR_LINES}건" else ""
        errors.forEach { log("[목록][오류] $it") }
        AlertDialog.Builder(activity).setTitle(title).setMessage(head + more).setPositiveButton("확인", null).show()
    }

    /** 목적: 목록 파일 폴더. 리턴: /sdcard/Documents/stt_plan */
    private fun planDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), PLAN_DIR_NAME)

    /** 목적: 공급 속도 이름 집합. 리턴: REALTIME / D50 / … */
    private fun feedNames(): Set<String> = SttCatalog.feeds.map { it.name }.toSet()

    /**
     * 목적: '모든 파일 접근'(공용 Documents 읽기·쓰기)을 확인하고, 꺼져 있으면 설정 화면을 연다.
     * 리턴: 켜져 있으면 true
     */
    private fun ensureAllFilesAccess(): Boolean {
        if (Environment.isExternalStorageManager()) return true
        toast("'모든 파일 접근' 을 허용한 뒤 다시 누른다")
        val pkgUri = Uri.parse("package:${activity.packageName}")
        runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkgUri)) }
            .onFailure { runCatching { activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } }
        return false
    }
}
