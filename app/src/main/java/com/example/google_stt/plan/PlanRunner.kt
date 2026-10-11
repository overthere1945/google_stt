/*
 * 파일명: plan/PlanRunner.kt
 * 목적 및 기능:
 * - 목록(작업 여러 개)을 동시에 최대 3개(PLAN_WORKERS)까지 돌린다. 오프라인 엔진 작업은 동시에 1개(PLAN_OFFLINE_MAX)만.
 *   작업 1개 = START 한 번과 같은 배치(BatchRunner) — 결과 폴더·CSV 이름 규칙도 같다.
 * - 폴더: 입력 = <고른 입력 폴더>/<kr|en>, 출력 = <고른 출력 폴더>/<kr|en>/<날짜>_<FEED>/<엔진>_<모델>_<조건>/…
 * - 이어 하기: 출력 폴더의 _plan_progress.csv 를 읽어 DONE 작업은 건너뛰고, 같은 날짜 폴더를 다시 쓴다.
 *   작업이 끝날 때마다(DONE / FAILED / INTERRUPTED) 진행 기록을 다시 쓴다. 끊긴 작업은 다음에 첫 파일부터 다시 돈다.
 * - 작업 사이 쉬기(Q35): 다음 작업을 잡기 전에 발열이 SEVERE 이상이면 MODERATE 이하가 될 때까지(최대 20분) 기다린다.
 * - 중지(Q39): "이 작업 끝나고" = stopAfterCurrent 로 새 작업을 잡지 않음 / "지금 바로" = 부른 쪽이 코루틴을 취소.
 *
 * add-hyungchul-20261011-2000 — v2.5.0 목록 실행
 * add-hyungchul-20261012-0310 — v2.6.0
 *   · 동시 작업 수를 run() 인자로 받는다(화면에서 1·2·3, Q47). run_meta parallel_jobs 도 그 값.
 *   · 시작할 때 목록 사본을 출력 폴더 _plan.csv 로 남긴다(Q45) — 이어 하기 창이 읽는다(readPlanText).
 *   · 30초마다 휴대폰 남은 메모리·앱 PSS·발열·돌고 있는 작업을 _plan_memlog.csv 에 덧붙인다(Q46). 앱이 꺼진 이유는 recordExit 로 _plan_exits.csv 에.
 *   · 작업은 "_<결과 폴더>" 에 쓰고 DONE 이면 원래 이름으로 바꾼다(Q48) — 끝나지 않은 결과는 PC 평가기가 건너뛴다.
 *     같은 이름의 예전 폴더(v2.5.0 에서 끊긴 작업 등)는 지우지 않고 "_stale_<이름>_<시각>" 으로 비켜 둔다. 이름 바꾸기에 실패하면 FAILED(다음에 다시).
 */
package com.example.google_stt.plan

import android.app.ActivityManager                       // add-hyungchul-20261012-0310 : 남은 메모리(MemoryInfo)
import android.net.Uri
import android.os.Debug                                  // add-hyungchul-20261012-0310 : 앱 PSS
import android.os.Environment
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.example.google_stt.SttTelemetry
import com.example.google_stt.batch.BatchHost
import com.example.google_stt.batch.BatchRequest
import com.example.google_stt.batch.BatchRunner
import com.example.google_stt.denoise.NoiseAlgorithm
import com.example.google_stt.denoise.NoiseReduceConfig
import com.example.google_stt.EpdConfig
import com.example.google_stt.EpdMode
import com.example.google_stt.io.ResultStore
import com.example.google_stt.stt.FeedConfig
import com.example.google_stt.stt.ModelConfig
import com.example.google_stt.stt.SherpaOfflineTranscriber
import com.example.google_stt.stt.SttCatalog
import com.example.google_stt.stt.SttEngine
import com.example.google_stt.stt.describe
import com.example.google_stt.ui.EpdSelection
import com.example.google_stt.ui.VadSelection
import com.example.google_stt.vad.SegMode
import com.example.google_stt.vad.VadEngine
import com.example.google_stt.vad.VadPreset
import com.example.google_stt.vad.VadPresetCatalog
import com.example.google_stt.vad.VadSetting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException                               // add-hyungchul-20261012-0310
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 작업 사이 쉬기 상한(ms) — 사용자 결정 Q35: 최대 20분 */
private const val THERMAL_WAIT_MAX_MS = 20 * 60_000L

/** 발열 다시 보기 간격(ms) */
private const val THERMAL_POLL_MS = 30_000L

/** 오프라인 작업이 돌고 있어 잡을 작업이 없을 때 다시 보는 간격(ms) */
private const val TAKE_RETRY_MS = 2_000L

/** 기다려야 하는 발열 단계(Android 공식: SEVERE 부터 UX 에 크게 영향) */
private val HOT_STATUSES = setOf("SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

/** add-hyungchul-20261012-0310 : 메모리 기록 머리줄(MB 단위, low_memory = 시스템이 메모리 부족 상태로 보는지) */
private const val MEMLOG_HEADER = "time,avail_mb,total_mb,threshold_mb,low_memory,app_pss_mb,thermal,finished,total,running"

/** add-hyungchul-20261012-0310 : 종료 기록 머리줄 */
private const val EXITS_HEADER = "logged,exit_time,reason,status,pss_mb,rss_mb,low_memory_report_supported,plan_was_running,description"

/** add-hyungchul-20261012-0310 : 1 MB(바이트) */
private const val MB = 1024L * 1024L

/**
 * 목적: 실행에 필요한 값으로 바꾼 작업 1개.
 * 필드: job — 목록 작업, model — 모델 정의, feed — 공급 속도, vad — VAD 선택(프리셋·구간 처리), locale — 인식 언어,
 *       resultDir — 결과 폴더 이름, key — 진행 기록 키, offline — 오프라인 엔진(동시 1개 제한 대상)인지
 */
data class PreparedJob(
    val job: PlanJob,             // 목록 작업
    val model: ModelConfig,       // 모델 정의
    val feed: FeedConfig,         // 공급 속도
    val vad: VadSelection,        // VAD 프리셋 + 구간 처리
    val locale: Locale,           // 인식 언어
    val resultDir: String,        // <엔진>_<모델>_<조건>
    val key: String,              // <lang>/<날짜>_<feed>/<결과 폴더>
    val offline: Boolean,         // 오프라인 엔진인지
)

/**
 * 목적: 시작 전 점검 결과.
 * 필드: date — 날짜 폴더 값(이어 하기면 진행 기록의 값), jobs — 바꾼 작업 전체, done — 이미 DONE 인 키,
 *       rows — 기존 진행 기록, errors — 시작을 막는 오류(빈 목록이면 시작 가능), resumed — 진행 기록을 찾았는지
 */
class PlanPrep(
    val date: String,
    val jobs: List<PreparedJob>,
    val done: Set<String>,
    val rows: LinkedHashMap<String, PlanProgressRow>,
    val errors: List<String>,
    val resumed: Boolean,
) {
    /** 이번에 돌릴 작업(DONE 제외) */
    val pending: List<PreparedJob> get() = jobs.filter { it.key !in done }
}

/**
 * 목적: 목록 실행기.
 * 입력(생성자): activity — DocumentFile·발열 조회용, ui — 화면 로그·진행률(MainActivity), store — 결과 저장기,
 *              newRunner — 작업마다 배치 실행기를 만드는 함수(work 별 화면 연결을 받는다), onStatus — 목록 상태 한 줄 표시
 */
class PlanRunner(
    private val activity: AppCompatActivity,
    private val ui: BatchHost,
    private val store: ResultStore,
    private val newRunner: (BatchHost) -> BatchRunner,
    private val onStatus: (String) -> Unit,
) {
    /** "이 작업 끝나고 중지" — true 면 work 가 새 작업을 잡지 않는다 */
    @Volatile
    var stopAfterCurrent = false

    private val lock = Mutex()                                   // 대기열·진행 기록·카운터 보호
    private val laneFrac = DoubleArray(PLAN_WORKERS_MAX)         // work 별 지금 작업 진행률(0.0~1.0) / change-hyungchul-20261012-0310 : 최댓값 크기
    private val runningLabels = arrayOfNulls<String>(PLAN_WORKERS_MAX)   // add-hyungchul-20261012-0310 : work 별 지금 작업 이름(메모리 기록용)
    @Volatile private var workers = PLAN_WORKERS                 // add-hyungchul-20261012-0310 : 이번 실행의 동시 작업 수
    @Volatile private var total = 0                              // 이번에 돌릴 작업 수(IO 스레드의 진행률 계산에서도 읽는다)
    @Volatile private var finished = 0                           // 이번에 끝난 작업 수(상태와 무관)
    private var offlineRunning = 0                               // 지금 돌고 있는 오프라인 작업 수

    companion object {
        /**
         * 목적: 목록에 쓸 수 있는 VAD 프리셋(이름 → 프리셋). 고정 프리셋 + NOVAD, TEN·CUSTOM 제외(사용자 결정 Q40).
         * 리턴: LinkedHashMap(표시 순서)
         */
        fun vadPresets(): Map<String, VadPreset> =
            VadEngine.entries.filter { it != VadEngine.TEN }                       // TEN 은 상업 조건부 — 견본에서 뺐다
                .flatMap { VadPresetCatalog.presets(it) }
                .filter { !it.isCustom }                                           // CUSTOM 은 값이 없어 목록에 못 쓴다
                .associateBy { it.name }

        /** 목적: Google 엔진(목록 실행 제외) 모델 이름. 리턴: folder 이름 집합 */
        fun googleModels(): Set<String> =
            SttCatalog.models.filter { it.sherpa == null }.map { it.folder }.toSet()

        /** 목적: 목록에 쓸 수 있는 앱 엔진 모델 이름. 리턴: folder 이름 집합 */
        fun appModels(): Set<String> =
            SttCatalog.models.filter { it.sherpa != null }.map { it.folder }.toSet()

        /** 목적: lang 토큰 → 인식 언어. 입력: kr / en / 리턴: Locale.KOREA / Locale.US */
        fun localeOf(lang: String): Locale = if (lang == "kr") Locale.KOREA else Locale.US

        /** 목적: 시각 문자열. 리턴: yyyy-MM-dd HH:mm:ss */
        private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    }

    /**
     * 목적: 시작 전 점검 — 작업을 실행 값으로 바꾸고, 진행 기록(이어 하기)·입력 하위 폴더·모델 파일을 확인한다.
     * 입력: jobs — 검증(PlanCheck)을 통과한 작업, inUri / outUri — 고른 입력·출력 폴더
     * 리턴: PlanPrep (errors 가 비어 있어야 시작)
     */
    suspend fun prepare(jobs: List<PlanJob>, inUri: Uri, outUri: Uri): PlanPrep = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val presets = vadPresets()
        val inRoot = DocumentFile.fromTreeUri(activity, inUri)
        val outRoot = DocumentFile.fromTreeUri(activity, outUri)
        if (inRoot == null || outRoot == null) errors += "입력·출력 폴더를 열 수 없다"

        // 진행 기록 → 이어 하기(같은 날짜 폴더, DONE 건너뛰기)
        val progressFile = outRoot?.findFile(PLAN_PROGRESS_FILE)
        val (oldDate, oldRows) = progressFile?.let { f ->
            runCatching {
                activity.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.getOrNull()?.let { PlanProgress.parse(it) }
        } ?: Pair(null, emptyMap())
        val date = oldDate ?: SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())   // 새 목록이면 오늘 날짜
        val rows = LinkedHashMap(oldRows)

        // 입력 하위 폴더(kr / en)가 있는지
        for (lang in jobs.map { it.lang }.distinct()) {
            if (inRoot?.findFile(lang)?.isDirectory != true) errors += "입력 폴더 아래 '$lang' 폴더가 없다(입력은 input_sound 처럼 kr·en 을 담은 폴더를 고른다)"
        }
        // 모델 파일 — 공용 폴더(Documents/stt_models)를 읽으려면 '모든 파일 접근' 이 필요하다
        if (!Environment.isExternalStorageManager()) errors += "'모든 파일 접근' 권한이 꺼져 있다 — 설정에서 켠 뒤 다시"
        val prepared = jobs.mapNotNull { j ->
            val model = SttCatalog.models.firstOrNull { it.folder == j.model }
            val feed = SttCatalog.feeds.firstOrNull { it.name == j.feed }
            val preset = presets[j.vad]
            if (model == null || feed == null || preset == null) return@mapNotNull null   // PlanCheck 에서 이미 걸렀다
            val setting = if (preset.engine == VadEngine.NONE) VadSetting(VadEngine.NONE, preset, emptyMap())
            else VadSetting(preset.engine, preset, preset.values)                         // 고정 프리셋 값 그대로(START 와 같음)
            val seg = if (j.seg == SEG_10) SegMode.SEG10 else SegMode.ENGINE_MAX
            val resultDir = store.resultDirName(model, j.condition)
            PreparedJob(
                job = j, model = model, feed = feed, vad = VadSelection(setting, seg), locale = localeOf(j.lang),
                resultDir = resultDir, key = PlanProgress.key(j.lang, date, j.feed, resultDir),
                offline = model.engine == SttEngine.SHERPA_OFFLINE,
            )
        }
        for (m in prepared.map { it.model }.distinct()) {
            val spec = m.sherpa ?: continue
            val missing = SherpaOfflineTranscriber.missingFiles(activity, spec)
            if (missing.isNotEmpty()) errors += "모델 '${m.folder}' 파일 없음: ${missing.joinToString(", ")}"
        }
        val done = rows.values.filter { it.status == PlanProgress.DONE }.map { it.key }.toSet()
        PlanPrep(date, prepared, done, rows, errors, resumed = oldDate != null)
    }

    /**
     * 목적: 목록을 끝까지(또는 중지까지) 돌린다.
     * 입력: prep — prepare() 결과(errors 비어 있음), planName — 목록 이름(진행 기록 참고용), inUri / outUri — 고른 폴더,
     *       workerCount — 동시 작업 수(1~3, add-hyungchul-20261012-0310)
     * 출력: 결과 파일·진행 기록·화면 로그 / 리턴: 없음 (취소되면 CancellationException 을 그대로 던진다)
     */
    suspend fun run(prep: PlanPrep, planName: String, inUri: Uri, outUri: Uri, workerCount: Int) = coroutineScope {   // change-hyungchul-20261012-0310 : workerCount 인자
        workers = workerCount.coerceIn(1, PLAN_WORKERS_MAX)            // add-hyungchul-20261012-0310
        runningLabels.fill(null)
        val queue = prep.pending.toMutableList()                       // 대기열(목록 순서)
        total = queue.size
        finished = 0
        offlineRunning = 0
        stopAfterCurrent = false
        laneFrac.fill(0.0)
        val rows = prep.rows                                           // 진행 기록(키별 마지막 상태)
        val outRoot = DocumentFile.fromTreeUri(activity, outUri)
        if (outRoot == null) { ui.appendLog("[목록][중단] 출력 폴더를 열 수 없다"); return@coroutineScope }

        // 날짜 폴더를 미리 만든다(work 들이 같은 폴더를 동시에 만들다 "(1)" 폴더가 생기는 것을 막는다)
        withContext(Dispatchers.IO) {
            prep.jobs.map { listOf(it.job.lang, "${prep.date}_${it.job.feed}") }.distinct()
                .forEach { store.ensureDir(outRoot, it) }
        }
        writeProgress(outRoot, prep.date, planName, rows)              // 날짜 값을 먼저 남긴다(첫 작업 전에 앱이 꺼져도 같은 날짜로 이어 간다)
        // add-hyungchul-20261012-0310 : 목록 사본(Q45) — 이어 하기 창이 이 파일로 같은 목록을 다시 읽는다
        writeRootFile(
            outRoot, PLAN_FILE,
            PlanCsv.format(prep.jobs.map { it.job }, "google_stt 목록 사본 — $planName, 작업 ${prep.jobs.size}개").toByteArray(Charsets.UTF_8),
        )
        ui.appendLog(
            "[목록] 시작: 작업 ${prep.jobs.size}개 중 ${total}개 실행(완료 ${prep.done.size}개 건너뜀), " +
                "동시 ${workers}개(오프라인 ${PLAN_OFFLINE_MAX}개), 날짜 폴더 ${prep.date}",   // change-hyungchul-20261012-0310 : 고른 동시 작업 수
        )
        updateOverall()

        /**
         * 목적: 다음 작업을 잡는다(오프라인 동시 상한을 지킨다).
         * 리턴: 작업, 더 없거나 "이 작업 끝나고 중지" 면 null
         */
        suspend fun take(): PreparedJob? {
            while (true) {
                // 검토 지적 1: Mutex.lock 은 경합이 없으면 취소를 확인하지 않는다 → "지금 바로 중지" 뒤에 새 작업을 잡지 않게 먼저 확인
                currentCoroutineContext().ensureActive()
                if (stopAfterCurrent) return null
                lock.withLock {
                    if (queue.isEmpty()) return null
                    val i = queue.indexOfFirst { !it.offline || offlineRunning < PLAN_OFFLINE_MAX }
                    if (i >= 0) {
                        val pj = queue.removeAt(i)
                        if (pj.offline) offlineRunning++
                        return pj
                    }
                }
                delay(TAKE_RETRY_MS)                                   // 남은 것이 오프라인뿐이고 하나가 돌고 있다 → 기다린다
            }
        }

        // add-hyungchul-20261012-0310 : 30초마다 메모리 기록(Q46) — 쓰기에 실패하면 그만둔다(결과에는 영향 없음)
        val memJob = launch {
            var ok = true
            while (isActive && ok) {
                ok = writeMemLine(outRoot)
                delay(PLAN_MEMLOG_INTERVAL_MS)
            }
        }
        val workerJobs = (1..workers).map { w ->                       // change-hyungchul-20261012-0310 : 고른 동시 작업 수만큼(이름 workers → workerJobs)
            launch {
                while (isActive) {                                     // 취소(지금 바로 중지·화면 종료)면 다음 작업으로 가지 않는다
                    waitThermal(w) { stopAfterCurrent || queue.isEmpty() }   // Q35: SEVERE 이상이면 식을 때까지(잡을 작업이 없으면 기다리지 않는다)
                    val pj = take() ?: break
                    runOne(w, pj, prep, planName, inUri, outUri, outRoot, rows)
                }
                ui.appendLog("[목록] W$w 끝 — 더 잡을 작업이 없다")
            }
        }
        workerJobs.joinAll()
        memJob.cancel()                                                // add-hyungchul-20261012-0310 : 메모리 기록 끝
        val c = rows.values.filter { r -> prep.jobs.any { it.key == r.key } }.groupingBy { it.status }.eachCount()
        ui.appendLog(
            "[목록] 끝: DONE ${c[PlanProgress.DONE] ?: 0} / FAILED ${c[PlanProgress.FAILED] ?: 0} / " +
                "INTERRUPTED ${c[PlanProgress.INTERRUPTED] ?: 0} / 전체 ${prep.jobs.size}" +
                if (stopAfterCurrent) " (이 작업 끝나고 중지)" else "",
        )
        onStatus("끝 — DONE ${c[PlanProgress.DONE] ?: 0} / 전체 ${prep.jobs.size} (진행 기록: ${PLAN_PROGRESS_FILE})")
    }

    /**
     * 목적: 작업 1개를 돌리고 진행 기록을 남긴다.
     * 입력: w — work 번호, pj — 작업, prep / planName / inUri / outUri / outRoot / rows — 목록 공통 값
     * 리턴: 없음 (취소면 기록을 남긴 뒤 CancellationException 을 다시 던진다)
     */
    private suspend fun runOne(
        w: Int, pj: PreparedJob, prep: PlanPrep, planName: String,
        inUri: Uri, outUri: Uri, outRoot: DocumentFile, rows: LinkedHashMap<String, PlanProgressRow>,
    ) {
        val started = now()
        var runner: BatchRunner? = null                                // 작업마다 새 배치 실행기(상태를 나누지 않는다)
        var status = PlanProgress.FAILED
        var note = ""
        ui.appendLog("[목록] W$w 시작 ${pj.job.label} → ${pj.key}")
        onStatus("실행 중 — 끝 $finished / $total, W$w: ${pj.job.label}")
        synchronized(runningLabels) { runningLabels[w - 1] = pj.job.label }    // add-hyungchul-20261012-0310 : 메모리 기록용
        try {
            // add-hyungchul-20261012-0310 : 검토 지적 3a — 같은 이름으로 보이는 예전 결과 폴더(끊긴 v2.5.0 작업 등)를 돌기 전에 '_stale_' 로 비켜 둔다
            //   (다시 도는 동안·실패해도 평가기에 반쪽 결과가 보이지 않게). 비키지 못하면 예외 → 이 작업은 FAILED.
            moveAsideOld(outRoot, prep, pj, w)
            runner = newRunner(LaneHost(w))
            runner.run(
                BatchRequest(
                    inUri = inUri,
                    outUri = outUri,
                    locale = pj.locale,
                    modelConfig = pj.model,
                    feed = pj.feed,
                    vadSelection = pj.vad,
                    epdSelection = EpdSelection(EpdConfig(EpdMode.OFF), null),      // 앱 엔진은 EPD 를 쓰지 않는다
                    noiseConfig = NoiseReduceConfig(NoiseAlgorithm.NONE),            // 노이즈 저감 없음(사용자 결정)
                    condition = pj.job.condition,
                    inSub = listOf(pj.job.lang),                                     // <입력>/kr
                    outSub = listOf(pj.job.lang, "${prep.date}_${pj.job.feed}"),     // <출력>/kr/20261012_REALTIME
                    parallelJobs = workers,                                          // change-hyungchul-20261012-0310 : 고른 동시 작업 수
                    runDirPrefix = RUN_DIR_PREFIX,                                   // add-hyungchul-20261012-0310 : "_<결과 폴더>" 에 쓴다(Q48)
                ),
            )
            // BatchRunner 는 예외·취소를 안에서 받아 CSV 를 남기고 돌아온다 → 취소 여부는 코루틴 상태로 판정한다
            status = when {
                !currentCoroutineContext().isActive -> PlanProgress.INTERRUPTED
                runner.lastCompleted -> PlanProgress.DONE
                else -> PlanProgress.FAILED
            }
            if (status == PlanProgress.FAILED) note = "중간에 끝남 — 화면 로그 [W$w] 참고"
        } catch (e: CancellationException) {
            status = PlanProgress.INTERRUPTED
            throw e
        } catch (e: Throwable) {                                       // 검토 지적 2: 메모리 부족·네이티브 오류도 이 작업만 FAILED 로 남기고 목록은 계속
            status = PlanProgress.FAILED
            note = describe(e)
        } finally {
            withContext(NonCancellable) {
                // add-hyungchul-20261012-0310 : DONE 이면 "_<결과 폴더>" → "<결과 폴더>". 실패하면 결과가 평가기에서 안 보이므로 FAILED(다음에 다시).
                //   검토 지적 5: 취소가 끼어도 이름 바꾸기 결과와 기록이 어긋나지 않게 NonCancellable 안(여기)에서 한다.
                if (status == PlanProgress.DONE && !finalizeRunDir(outRoot, prep, pj, w)) {
                    status = PlanProgress.FAILED
                    note = "결과는 끝났지만 폴더 이름 바꾸기 실패 — '$RUN_DIR_PREFIX' 폴더 그대로, 다음 실행 때 다시"
                }
                lock.withLock {
                    if (pj.offline) offlineRunning--
                    finished++
                    synchronized(laneFrac) { laneFrac[w - 1] = 0.0 }
                    synchronized(runningLabels) { runningLabels[w - 1] = null }   // add-hyungchul-20261012-0310
                    rows[pj.key] = PlanProgressRow(pj.key, status, w, started, now(), runner?.lastOk ?: 0, runner?.lastFail ?: 0, note)
                    writeProgress(outRoot, prep.date, planName, rows)
                }
                ui.appendLog("[목록] W$w 끝 $status ${pj.job.label} (성공 ${runner?.lastOk ?: 0} / 실패 ${runner?.lastFail ?: 0}) — 끝 $finished / $total")
                updateOverall()
            }
        }
    }

    /**
     * 목적: 진행 기록 파일을 다시 쓴다(출력 폴더 바로 아래).
     * 입력: outRoot, date, planName, rows / 리턴: 없음 (쓰기 실패는 로그만 — 결과 파일은 이미 저장돼 있다)
     * 비고: 검토 지적 6 — 이미 있으면 지우고 새로 만들지 않고 "wt"(내용 비우고 쓰기)로 덮어쓴다(지운 직후 앱이 죽어 기록을 잃지 않게).
     */
    private suspend fun writeProgress(outRoot: DocumentFile, date: String, planName: String, rows: Map<String, PlanProgressRow>) {
        // change-hyungchul-20261012-0310 : 파일 쓰기 본문을 writeRootFile 로 옮겼다(_plan.csv 와 같이 쓴다). 동작은 같다.
        writeRootFile(outRoot, PLAN_PROGRESS_FILE, PlanProgress.format(date, planName, rows.values).toByteArray(Charsets.UTF_8))
    }

    /**
     * add-hyungchul-20261012-0310
     * 목적: 출력 폴더 바로 아래 파일을 통째로 쓴다(있으면 "wt" 로 덮어쓰기 — 지웠다 만드는 사이 앱이 죽어 파일을 잃지 않게).
     * 입력: outRoot, name — 파일 이름, bytes — 내용 / 리턴: 없음 (실패는 로그만)
     */
    private suspend fun writeRootFile(outRoot: DocumentFile, name: String, bytes: ByteArray) {
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val existing = outRoot.findFile(name)?.takeIf { it.isFile }
                if (existing == null) store.writeBytes(outRoot, name, bytes, "text/csv")                // 처음: 새로 만든다
                else activity.contentResolver.openOutputStream(existing.uri, "wt")?.use { it.write(bytes) }
                    ?: throw IOException("$name 열기 실패")
            }
        } catch (e: CancellationException) {
            throw e                                                                                     // 검토 지적 5: 취소는 실패로 적지 않는다
        } catch (e: Throwable) {                                                                        // 2차 검토 A: 메모리 부족(Error)도 목록 전체를 멈추지 않게
            ui.appendLog("[목록][경고] $name 저장 실패 — ${describe(e)}")
        }
    }

    /**
     * add-hyungchul-20261012-0310
     * 목적: 출력 폴더 바로 아래 CSV 에 한 줄 덧붙인다(없으면 머리줄과 함께 만든다, 있으면 "wa" 로 끝에 붙인다).
     * 입력: outRoot, name — 파일 이름, header — 머리줄, line — 덧붙일 줄 / 리턴: 성공하면 true
     */
    private suspend fun appendRootLine(outRoot: DocumentFile, name: String, header: String, line: String): Boolean =
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                val existing = outRoot.findFile(name)?.takeIf { it.isFile }
                if (existing == null) store.writeBytes(outRoot, name, "\uFEFF$header\n$line\n".toByteArray(Charsets.UTF_8), "text/csv")
                else activity.contentResolver.openOutputStream(existing.uri, "wa")?.use { it.write("$line\n".toByteArray(Charsets.UTF_8)) }
                    ?: throw IOException("$name 열기 실패")
            }
            true
        } catch (e: CancellationException) {
            throw e                                                                                     // 검토 지적 5: 취소는 실패로 적지 않는다
        } catch (e: Throwable) {                                                                        // 2차 검토 A: 메모리 부족(Error)도 목록 전체를 멈추지 않게
            ui.appendLog("[목록][경고] $name 쓰기 실패 — ${describe(e)}")
            false
        }

    /**
     * add-hyungchul-20261012-0310
     * 목적: Q46 메모리 기록 한 줄 — 휴대폰 남은·전체 메모리, 시스템 기준선, 메모리 부족 여부, 앱 PSS, 발열, 진행, 돌고 있는 작업.
     * 입력: outRoot / 리턴: 쓰기에 성공하면 true
     */
    private suspend fun writeMemLine(outRoot: DocumentFile): Boolean {
        // 검토 지적 8: 메모리 기록은 덤이다 — 값을 읽다 예외가 나도 목록 실행 전체를 멈추지 않게 받아서 false(기록 중단)
        val line = try { withContext(Dispatchers.IO) {
            val mi = ActivityManager.MemoryInfo()
            activity.getSystemService(ActivityManager::class.java)?.getMemoryInfo(mi)              // 휴대폰 전체 메모리 상태
            val pssMb = runCatching { Debug.getPss() / 1024 }.getOrDefault(-1L)                    // 이 앱(프로세스) PSS, KB → MB
            val running = synchronized(runningLabels) { runningLabels.filterNotNull().joinToString(";") }
            listOf(
                now(), mi.availMem / MB, mi.totalMem / MB, mi.threshold / MB, mi.lowMemory, pssMb,
                SttTelemetry.thermalStatus(activity), finished, total, running,
            ).joinToString(",")
        } } catch (e: CancellationException) { throw e } catch (e: Throwable) {   // 2차 검토 A: Error 도 받는다
            ui.appendLog("[목록][경고] 메모리 기록을 멈춘다 — ${describe(e)}")
            return false
        }
        return appendRootLine(outRoot, PLAN_MEMLOG_FILE, MEMLOG_HEADER, line)
    }

    /**
     * add-hyungchul-20261012-0310
     * 목적: 결과 폴더 dir 를 지우지 않고 "_stale_<이름>_<시각>" 으로 비켜 둔다(PC 평가기는 '_' 폴더를 건너뛴다).
     * 입력: dir — 비킬 폴더, name — 원래 이름, w — work 번호 / 리턴: 없음 (실패하면 IOException)
     */
    private fun renameStale(dir: DocumentFile, name: String, w: Int) {
        val stale = "${RUN_DIR_PREFIX}stale_${name}_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        if (!dir.renameTo(stale)) throw IOException("폴더를 비키지 못했다: $name")
        ui.appendLog("[목록] W$w '$name' 폴더를 $stale 로 옮겼다(평가기는 '$RUN_DIR_PREFIX' 폴더를 건너뛴다)")
    }

    /**
     * add-hyungchul-20261012-0310 (검토 지적 3a)
     * 목적: 작업을 돌리기 전에, 같은 이름으로 보이는 예전 결과 폴더(끊긴 v2.5.0 작업·이름을 바꾼 뒤 끊긴 작업)를 비켜 둔다.
     *       대기열에는 DONE 이 아닌 작업만 있으므로 그 이름의 폴더는 끝나지 않은 결과다.
     * 입력: outRoot, prep, pj, w / 리턴: 없음 (비키지 못하면 IOException → 이 작업은 FAILED)
     */
    private suspend fun moveAsideOld(outRoot: DocumentFile, prep: PlanPrep, pj: PreparedJob, w: Int) =
        withContext(Dispatchers.IO) {
            val dateDir = store.ensureDir(outRoot, listOf(pj.job.lang, "${prep.date}_${pj.job.feed}"))
            dateDir.findFile(pj.resultDir)?.takeIf { it.isDirectory }?.let { renameStale(it, pj.resultDir, w) }
        }

    /**
     * add-hyungchul-20261012-0310
     * 목적: Q48 — DONE 작업의 "_<결과 폴더>" 를 "<결과 폴더>" 로 바꾼다(같은 이름 폴더가 생겨 있으면 먼저 비켜 둔다).
     * 입력: outRoot, prep, pj — 작업, w — work 번호 / 리턴: 성공(또는 바꿀 폴더가 없음)이면 true
     * 비고: 이름이 "<결과 폴더> (1)" 처럼 바뀌면(검토 지적 4) 평가기에 보이지 않게 "_stale_…" 로 되돌리고 false.
     */
    private suspend fun finalizeRunDir(outRoot: DocumentFile, prep: PlanPrep, pj: PreparedJob, w: Int): Boolean =
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val dateDir = store.ensureDir(outRoot, listOf(pj.job.lang, "${prep.date}_${pj.job.feed}"))   // <출력>/kr/20261012_REALTIME
                val tmp = dateDir.findFile(RUN_DIR_PREFIX + pj.resultDir)?.takeIf { it.isDirectory }
                    ?: return@withContext true                                                        // TXT 를 하나도 쓰지 않았다 → 바꿀 것 없음
                dateDir.findFile(pj.resultDir)?.takeIf { it.isDirectory }?.let { renameStale(it, pj.resultDir, w) }   // 그사이 생긴 같은 이름 폴더
                if (!tmp.renameTo(pj.resultDir)) return@withContext false                             // 이름 바꾸기 실패 → '_' 폴더 그대로
                if (tmp.name == pj.resultDir) return@withContext true                                 // 바꾼 이름까지 확인
                renameStale(tmp, pj.resultDir, w)                                                     // "(1)" 등이 붙었다 → 보이지 않게 되돌린다
                false
            } catch (e: Exception) {
                ui.appendLog("[목록][경고] W$w 폴더 이름 바꾸기 실패 — ${describe(e)}")
                false
            }
        }

    /**
     * add-hyungchul-20261012-0310
     * 목적: 출력 폴더의 목록 사본(_plan.csv)을 읽는다(이어 하기 창).
     * 입력: outUri / 리턴: 파일 내용 또는 null(없음·읽기 실패)
     */
    suspend fun readPlanText(outUri: Uri): String? = withContext(Dispatchers.IO) {
        val f = DocumentFile.fromTreeUri(activity, outUri)?.findFile(PLAN_FILE)?.takeIf { it.isFile } ?: return@withContext null
        runCatching { activity.contentResolver.openInputStream(f.uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
    }

    /**
     * add-hyungchul-20261012-0310
     * 목적: Q46 — 지난번 앱이 꺼진 이유를 출력 폴더 _plan_exits.csv 에 한 줄 남긴다.
     * 입력: outUri, exit — 종료 기록, wasRunning — 목록 실행 도중이었는지 / 리턴: 없음
     */
    suspend fun recordExit(outUri: Uri, exit: PlanExit, wasRunning: Boolean) {
        val root = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(activity, outUri) } ?: return
        val line = listOf(
            now(), SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(exit.timestamp)), exit.reasonName, exit.status,
            exit.pssMb, exit.rssMb, exit.lowMemoryReportSupported, wasRunning, exit.description.replace(",", " ").replace("\n", " "),
        ).joinToString(",")
        appendRootLine(root, PLAN_EXITS_FILE, EXITS_HEADER, line)
    }

    /**
     * 목적: Q35 작업 사이 쉬기 — 발열이 SEVERE 이상이면 MODERATE 이하가 될 때까지 기다린다(최대 20분, 넘으면 기록 후 진행).
     * 입력: w — work 번호, idle — true 면 기다릴 필요 없음(잡을 작업이 없거나 "이 작업 끝나고 중지") / 리턴: 없음
     * 비고: 검토 지적 3 — 할 일이 없는 work 가 20분을 붙잡아 목록 끝이 늦어지지 않게 idle 이면 바로 돌아간다.
     */
    private suspend fun waitThermal(w: Int, idle: () -> Boolean) {
        var st = SttTelemetry.thermalStatus(activity)
        if (st !in HOT_STATUSES || idle()) return
        ui.appendLog("[목록] W$w 발열 $st — MODERATE 이하가 될 때까지 대기(최대 ${THERMAL_WAIT_MAX_MS / 60_000}분)")
        val t0 = SystemClock.elapsedRealtime()
        while (st in HOT_STATUSES && !idle() && SystemClock.elapsedRealtime() - t0 < THERMAL_WAIT_MAX_MS) {
            delay(THERMAL_POLL_MS)
            st = SttTelemetry.thermalStatus(activity)
        }
        val waited = (SystemClock.elapsedRealtime() - t0) / 1000
        ui.appendLog(
            if (st in HOT_STATUSES) "[목록][경고] W$w 발열 $st 가 ${waited}초 동안 내려가지 않아 그대로 진행한다"
            else "[목록] W$w 발열 $st — ${waited}초 기다린 뒤 진행",
        )
    }

    /** 목적: 전체 진행률 = (끝난 작업 + 돌고 있는 작업들의 진행률 합) / 이번 작업 수. 리턴: 없음 */
    private fun updateOverall() {
        val running = synchronized(laneFrac) { laneFrac.sum() }
        ui.updateProgress(if (total == 0) 1.0 else (finished + running) / total)
    }

    /**
     * 목적: work 1개용 화면 연결 — 로그에 [W번호] 를 붙이고, 진행률은 전체 진행률로 합친다.
     * 입력(생성자): w — work 번호
     */
    private inner class LaneHost(private val w: Int) : BatchHost {
        override val activity: AppCompatActivity get() = this@PlanRunner.activity
        override fun appendLog(line: String) = ui.appendLog("[W$w] $line")                // 어느 work 의 로그인지
        override fun toast(msg: String) = ui.appendLog("[W$w][알림] $msg")                 // 작업마다 토스트가 쌓이지 않게 로그로
        override fun updateProgress(fraction: Double) {
            synchronized(laneFrac) { laneFrac[w - 1] = fraction.coerceIn(0.0, 1.0) }
            updateOverall()
        }
        override fun openSpeechServiceSettings() {}                                        // Google 엔진은 목록 실행에서 제외
        override fun onBatchFinished() {}                                                  // 목록의 끝은 PlanRunner 가 처리
    }
}
