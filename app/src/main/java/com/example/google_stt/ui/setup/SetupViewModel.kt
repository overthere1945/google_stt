/*
 * 파일명: ui/setup/SetupViewModel.kt
 * 목적 및 기능:
 * - 설정 탭의 상태와 계산을 맡는다(4단계 4-2·4-5, FR-01~04·08).
 *     · 입력 폴더를 훑어 언어 폴더를 판정하고(이름 → 기억한 값 → 물어보기) 폴더별 음원 수·길이를 센다
 *     · 고른 모델·VAD·옵션으로 조합(작업 목록)을 만들고, 필요한 자산·예상 소요 시간을 요약한다
 *     · 고른 값은 SettingsRepository 에 저장해 다음에 그대로 뜬다
 *     · 실행 요청(RunRequest)·시작 전 점검 항목·조합 CSV 저장을 만든다
 * - 화면(SetupScreen)은 state 를 보고 그리고, 여기의 함수만 부른다.
 * - 아직 없는 것: VAD 사용자 지정 값(CUSTOM 프리셋), 노이즈 저감·EPD 의 세부 값, "빠른 측정"(엔진별 짧은 측정) — 프리셋·기본 설정만 고를 수 있다.
 *
 * add-hyungchul-20261106-2326 — [T7-2]
 */
package com.example.google_stt.ui.setup

import android.content.Context                                       // 폴더 권한·음원 길이
import android.content.Intent                                        // 폴더 권한 보존 표시
import android.media.MediaMetadataRetriever                          // 음원 길이 읽기
import android.net.Uri                                               // 폴더 주소
import android.provider.DocumentsContract                            // 폴더 경로 글자
import androidx.documentfile.provider.DocumentFile                   // 폴더
import androidx.lifecycle.ViewModel                                  // 화면 상태 보관
import androidx.lifecycle.viewModelScope                             // 화면 수명의 코루틴
import com.example.google_stt.AppContainer                           // 객체 묶음
import com.example.google_stt.data.saf.AudioItem                     // 음원 항목
import com.example.google_stt.data.saf.AudioScanner                  // 폴더 훑기
import com.example.google_stt.data.saf.OutputLayout                  // 진행 기록 파일 이름
import com.example.google_stt.domain.catalog.Catalog                 // 모델·자산 목록
import com.example.google_stt.domain.catalog.Language                // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec               // 모델 정의
import com.example.google_stt.domain.condition.EpdChoice             // EPD 선택
import com.example.google_stt.domain.condition.FeedChoice            // 공급 속도
import com.example.google_stt.domain.condition.NsChoice              // 노이즈 저감 선택
import com.example.google_stt.domain.condition.RunOptions            // 실행 옵션
import com.example.google_stt.domain.condition.RunPurpose            // 실행 목적
import com.example.google_stt.domain.epd.EpdConfig                   // EPD 설정
import com.example.google_stt.domain.epd.EpdMode                     // EPD 방식
import com.example.google_stt.domain.eta.EtaEstimator                // 예상 소요 시간
import com.example.google_stt.domain.lang.LangFolder                 // 언어 폴더
import com.example.google_stt.domain.lang.LangPlan                   // 언어 판정 결과
import com.example.google_stt.domain.lang.LanguageResolver           // 언어 판정
import com.example.google_stt.domain.plan.ComboGenerator             // 조합 만들기
import com.example.google_stt.domain.plan.Job                        // 작업
import com.example.google_stt.domain.plan.PlanCsv                    // 조합 CSV
import com.example.google_stt.domain.plan.PlanProgress               // 진행 기록 읽기
import com.example.google_stt.domain.vad.SegMode                     // 구간 처리
import com.example.google_stt.domain.vad.VadEngine                   // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog            // VAD 프리셋
import com.example.google_stt.domain.vad.VadSetting                  // VAD 설정
import com.example.google_stt.engine.denoise.NoiseAlgorithm          // 노이즈 저감 알고리즘
import com.example.google_stt.engine.denoise.NoiseReduceConfig       // 노이즈 저감 기본 설정
import com.example.google_stt.run.RunRequest                         // 실행 요청
import java.text.SimpleDateFormat                                    // 파일 이름용 시각
import java.util.Date                                                // 지금 시각
import java.util.Locale                                              // 표기 고정
import kotlinx.coroutines.Dispatchers                                // 입출력 스레드
import kotlinx.coroutines.flow.MutableStateFlow                      // 상태 보관
import kotlinx.coroutines.flow.StateFlow                             // 상태 읽기
import kotlinx.coroutines.flow.first                                 // 저장된 값 한 번 읽기
import kotlinx.coroutines.flow.update                                // 상태 고치기
import kotlinx.coroutines.launch                                     // 코루틴 시작
import kotlinx.coroutines.sync.withLock                              // 훑기 직렬화
import kotlinx.coroutines.withContext                                // 스레드 바꾸기

/**
 * 목적: 조합 요약(4-2 의 아래 카드).
 * 필드: jobs - 작업 수, excluded - 언어가 맞지 않아 빠진 조합 수, assetsNeeded / assetsReady - 필요한 자산 수·준비된 수,
 *       missingBytes - 받아야 할 크기, freeBytes - 여유 공간, etaSec - 예상 소요(초, 모르면 null), etaUnknown - 실측이 없는 작업 수
 */
data class ComboSummary(
    val jobs: Int = 0,
    val excluded: Int = 0,
    val assetsNeeded: Int = 0,
    val assetsReady: Int = 0,
    val missingBytes: Long = 0,
    val freeBytes: Long = 0,
    val etaSec: Double? = null,
    val etaUnknown: Int = 0,
)

/**
 * 목적: 설정 탭의 화면 상태.
 * 필드: inputPath / outputPath - 고른 폴더의 경로 글자(없으면 null), langPlan - 언어 판정, counts - 언어 폴더 이름 → 음원 수,
 *       models / vads - 고른 모델(엔진 이름)·VAD 프리셋 이름, seg·feed·ns·epd·purpose·workers·wifiOnly - 고급 옵션,
 *       summary - 조합 요약, busy - 폴더를 훑는 중, message - 안내·오류 한 줄
 */
data class SetupUiState(
    val inputPath: String? = null,
    val outputPath: String? = null,
    val langPlan: LangPlan? = null,
    val counts: Map<String, Int> = emptyMap(),
    val models: Set<String> = emptySet(),
    val vads: Set<String> = emptySet(),
    val seg: SegMode = SegMode.ENGINE_MAX,
    val feed: FeedChoice = FeedChoice.NONE,
    val ns: String? = null,
    val epd: EpdMode = EpdMode.OFF,
    val purpose: RunPurpose = RunPurpose.TIMING,
    val workers: Int = 1,
    val wifiOnly: Boolean = true,
    val summary: ComboSummary = ComboSummary(),
    val busy: Boolean = false,
    val message: String = "",
)

/**
 * 목적: 지난 실행의 진행 기록 요약(이어 하기 대화창용).
 * 필드: done / failed / interrupted - 상태별 작업 수, remaining - 이번 조합 중 아직 끝나지 않은 작업 수
 */
data class ResumeInfo(val done: Int, val failed: Int, val interrupted: Int, val remaining: Int)

/**
 * 목적: 설정 탭 ViewModel.
 * 입력(생성자): c - 객체 묶음, context - Application Context
 */
class SetupViewModel(private val c: AppContainer, private val context: Context) : ViewModel() {

    companion object {
        const val NOVAD = "NOVAD"                                                   // "VAD 없음" 의 이름
        val DEFAULT_MODELS = setOf("whisper-base")                                  // 처음 켰을 때 고른 모델
        val DEFAULT_VADS = setOf(NOVAD)                                             // 처음 켰을 때 고른 VAD

        /** 고를 수 있는 VAD 프리셋(사용자 지정 제외): 이름 → 설정 */
        val VAD_SETTINGS: Map<String, VadSetting> = linkedMapOf(NOVAD to VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())) +
            VadEngine.values().filter { it != VadEngine.NONE }.flatMap { e -> VadPresetCatalog.presets(e).filter { !it.isCustom }.map { it.name to VadSetting(e, it, it.values) } }

        /** 고를 수 있는 노이즈 저감(알고리즘별 기본 설정): 조건 이름 조각 → 화면 이름 */
        val NS_CHOICES: List<Pair<String, String>> = NoiseAlgorithm.values().filter { it.runnable }
            .mapNotNull { a -> NoiseReduceConfig.defaults(a).outputFolder()?.let { it to a.displayName } }
    }

    private val _state = MutableStateFlow(SetupUiState())

    /** 화면 상태 */
    val state: StateFlow<SetupUiState> = _state

    private var inputRoot: DocumentFile? = null                                     // 입력 폴더
    private var outputRoot: DocumentFile? = null                                    // 출력 폴더
    private var audio: Map<String, List<AudioItem>> = emptyMap()                    // 언어 폴더 이름 → 음원들
    private var audioSec: Map<String, Double> = emptyMap()                          // 언어 폴더 이름 → 길이 합(초)
    private var unknown: List<LangFolder> = emptyList()                             // 언어를 물어야 하는 폴더
    private val scanLock = kotlinx.coroutines.sync.Mutex()                          // 폴더 훑기는 한 번에 하나

    /** 지금 조합의 작업들(실행 요청·조합 CSV 에 쓴다) */
    var jobs: List<Job> = emptyList()
        private set

    init {
        viewModelScope.launch {
            val s = c.settings.settings.first()                                     // 지난번에 고른 값
            _state.update {
                it.copy(models = s.selectedModels ?: DEFAULT_MODELS, vads = s.selectedVads ?: DEFAULT_VADS, seg = s.seg, feed = s.feed,
                    purpose = s.purpose, workers = s.workers, wifiOnly = s.wifiOnly)
            }
            s.outputTreeUri?.let { openOutput(Uri.parse(it), persist = false) }
            s.inputTreeUri?.let { openInput(Uri.parse(it), persist = false) } ?: recompute()
        }
    }

    // ───────────── 폴더 ─────────────

    /** 목적: 입력 폴더를 고른다. 입력: uri - 폴더 선택기가 준 주소 / 출력: 권한 보존, 설정 저장, 폴더 훑기 / 리턴: 없음 */
    fun setInput(uri: Uri) = viewModelScope.launch { openInput(uri, persist = true) }

    /** 목적: 출력 폴더를 고른다. 입력: uri - 폴더 선택기가 준 주소 / 출력: 권한 보존, 설정 저장 / 리턴: 없음 */
    fun setOutput(uri: Uri) = viewModelScope.launch { openOutput(uri, persist = true); recompute() }

    /**
     * 목적: 입력 폴더를 열고 훑는다.
     * 입력: uri - 폴더 주소, persist - 권한을 보존하고 설정에 저장할지(새로 골랐을 때 true)
     * 출력: 상태(경로·언어 판정·음원 수) / 리턴: 없음
     */
    private suspend fun openInput(uri: Uri, persist: Boolean) {
        if (persist) {
            takePermission(uri, write = false)
            c.settings.update { it.copy(inputTreeUri = uri.toString()) }
        }
        inputRoot = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canRead() } }   // change-hyungchul-20261107-0000 : WP7 독립 검토 9·12
        _state.update { it.copy(inputPath = if (inputRoot == null) null else pathText(uri), message = if (inputRoot == null) "입력 폴더를 읽을 수 없다 — 다시 고른다" else it.message) }
        scan()
    }

    /** 목적: 출력 폴더를 연다. 입력: uri, persist / 출력: 상태(경로) / 리턴: 없음 */
    private suspend fun openOutput(uri: Uri, persist: Boolean) {
        if (persist) {
            takePermission(uri, write = true)
            c.settings.update { it.copy(outputTreeUri = uri.toString()) }
        }
        outputRoot = withContext(Dispatchers.IO) { DocumentFile.fromTreeUri(context, uri)?.takeIf { it.canWrite() } }   // change-hyungchul-20261107-0000 : WP7 독립 검토 9·12
        _state.update { it.copy(outputPath = if (outputRoot == null) null else pathText(uri), message = if (outputRoot == null) "출력 폴더에 쓸 수 없다 — 다시 고른다" else it.message) }
    }

    /** 목적: 폴더 권한을 앱을 다시 켜도 남게 한다. 입력: uri, write - 쓰기 권한도 / 출력: 권한 보존 / 리턴: 없음 */
    private fun takePermission(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (e: SecurityException) {
            c.log.w("폴더 권한을 보존하지 못했다(${e.message}) — 앱을 다시 켜면 폴더를 다시 골라야 한다")
        }
    }

    /** 목적: 폴더 주소를 사람이 읽는 경로로 바꾼다. 입력: uri / 출력: 없음 / 리턴: String - 예 "Documents/input_sound/scarlett" */
    private fun pathText(uri: Uri): String = DocumentsContract.getTreeDocumentId(uri).substringAfter(':')

    /**
     * 목적: 입력 폴더를 훑어 언어 폴더를 판정하고 음원 수·길이를 센다.
     * 입력: 없음 / 출력: 상태(언어 판정·음원 수), 조합 요약 / 리턴: 없음
     */
    private suspend fun scan() = scanLock.withLock {   // change-hyungchul-20261107-0000 : WP7 독립 검토 3 — 훑기를 하나씩만 돌린다(겹치면 늦게 끝난 옛 결과가 새 폴더를 덮었다)
        val root = inputRoot
        if (root == null) { audio = emptyMap(); audioSec = emptyMap(); unknown = emptyList(); _state.update { it.copy(langPlan = null, counts = emptyMap()) }; recompute(); return@withLock }
        _state.update { it.copy(busy = true) }
        val memory = c.langMemory.memory.first()
        val plan = withContext(Dispatchers.IO) {
            val p = LanguageResolver.plan(_state.value.inputPath.orEmpty(), AudioScanner.subDirs(root), memory)
            val found = LinkedHashMap<String, List<AudioItem>>(); val secs = LinkedHashMap<String, Double>()
            for (f in p.folders) {
                val dir = if (f.name.isEmpty()) root else root.findFile(f.name) ?: continue   // 입력 폴더 자체가 한 언어면 이름이 비어 있다
                val items = AudioScanner.scan(dir)
                found[f.name] = items
                secs[f.name] = items.sumOf { duration(it.uri) }
            }
            audio = found; audioSec = secs
            p
        }
        unknown = plan.unknown
        _state.update { it.copy(busy = false, langPlan = plan, counts = audio.mapValues { (_, v) -> v.size }) }
        recompute()
    }

    /** 목적: 음원 길이를 읽는다. 입력: uri - 음원 / 출력: 없음 / 리턴: Double - 초(읽지 못하면 0) */
    private fun duration(uri: Uri): Double {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
        } catch (e: RuntimeException) {
            0.0                                                                     // 길이를 모르면 예상 시간 계산에서만 빠진다
        } finally {
            r.release()
        }
    }

    /** 언어를 물어야 하는 폴더들(언어 선택 대화창이 쓴다) */
    val unknownFolders: List<LangFolder> get() = unknown

    /**
     * 목적: 폴더의 언어를 기억한다(다음부터 묻지 않는다).
     * 입력: folder - 폴더, lang - 고른 언어(null = 건너뛰기) / 출력: 기억 저장, 다시 훑기 / 리턴: 없음
     */
    fun chooseLang(folder: LangFolder, lang: Language?) = viewModelScope.launch {
        c.langMemory.remember(folder.docId, lang)
        scan()
    }

    // ───────────── 고르기 ─────────────

    /** 목적: 모델 하나를 고르거나 뺀다. 입력: engine - 결과 엔진 이름 / 출력: 상태·설정 / 리턴: 없음 */
    fun toggleModel(engine: String) = setModels(_state.value.models.let { if (engine in it) it - engine else it + engine })

    /** 목적: 고른 모델을 통째로 바꾼다(묶음 버튼). 입력: engines - 엔진 이름들 / 출력: 상태·설정 / 리턴: 없음 */
    fun setModels(engines: Set<String>) = change({ it.copy(models = engines) }) { it.copy(selectedModels = engines) }

    /** 목적: VAD 프리셋 하나를 고르거나 뺀다. 입력: name - 프리셋 이름 / 출력: 상태·설정 / 리턴: 없음 */
    fun toggleVad(name: String) = setVads(_state.value.vads.let { if (name in it) it - name else it + name })

    /** 목적: 고른 VAD 를 통째로 바꾼다(묶음 버튼). 입력: names - 프리셋 이름들 / 출력: 상태·설정 / 리턴: 없음 */
    fun setVads(names: Set<String>) = change({ it.copy(vads = names) }) { it.copy(selectedVads = names) }

    /** 목적: 구간 처리를 고른다. 입력: v / 출력: 상태·설정 / 리턴: 없음 */
    fun setSeg(v: SegMode) = change({ it.copy(seg = v) }) { it.copy(seg = v) }

    /** 목적: 공급 속도를 고른다. 입력: v / 출력: 상태·설정 / 리턴: 없음 */
    fun setFeed(v: FeedChoice) = change({ it.copy(feed = v) }) { it.copy(feed = v) }

    /** 목적: 노이즈 저감을 고른다(이 값은 저장하지 않는다). 입력: folder - 조건 이름 조각(null = 사용 안 함) / 출력: 상태 / 리턴: 없음 */
    fun setNs(folder: String?) = change({ it.copy(ns = folder) }) { it }

    /** 목적: EPD 방식을 고른다(이 값은 저장하지 않는다). 입력: mode / 출력: 상태 / 리턴: 없음 */
    fun setEpd(mode: EpdMode) = change({ it.copy(epd = mode) }) { it }

    /** 목적: 실행 목적을 고른다(시간 측정이면 동시 1 로 돌린다). 입력: v / 출력: 상태·설정 / 리턴: 없음 */
    fun setPurpose(v: RunPurpose) = change({ it.copy(purpose = v, workers = it.workers.coerceIn(1, v.maxWorkers)) }) { it.copy(purpose = v, workers = it.workers.coerceIn(1, v.maxWorkers)) }

    /** 목적: 동시 작업 수를 고른다. 입력: n / 출력: 상태·설정 / 리턴: 없음 */
    fun setWorkers(n: Int) = change({ it.copy(workers = n.coerceIn(1, it.purpose.maxWorkers)) }) { it.copy(workers = n.coerceIn(1, it.purpose.maxWorkers)) }

    /** 목적: 다운로드를 Wi-Fi 에서만 할지 고른다. 입력: on / 출력: 상태·설정 / 리턴: 없음 */
    fun setWifiOnly(on: Boolean) = change({ it.copy(wifiOnly = on) }) { it.copy(wifiOnly = on) }

    /**
     * 목적: 화면 상태를 고치고, 설정을 저장하고, 조합을 다시 계산한다.
     * 입력: ui - 화면 상태를 고치는 함수, saved - 저장할 설정을 고치는 함수 / 출력: 상태·설정·요약 / 리턴: 없음
     */
    private fun change(ui: (SetupUiState) -> SetupUiState, saved: (com.example.google_stt.data.prefs.Settings) -> com.example.google_stt.data.prefs.Settings) {
        _state.update(ui)
        viewModelScope.launch { c.settings.update(saved); recompute() }
    }

    // ───────────── 조합·요약 ─────────────

    /** 목적: 지금 고른 값으로 조합과 요약을 다시 만든다. 입력: 없음 / 출력: jobs, 상태(요약) / 리턴: 없음 */
    suspend fun recompute() {
        val s = _state.value
        val plan = s.langPlan
        val models: List<ModelSpec> = Catalog.models.filter { it.engine in s.models }
        val vads = s.vads.mapNotNull { VAD_SETTINGS[it] }
        val opt = RunOptions(seg = s.seg, feed = s.feed, ns = NsChoice(s.ns), epd = if (s.epd == EpdMode.OFF) EpdChoice.OFF else EpdChoice(EpdConfig(s.epd)))
        val combo = if (plan == null) null else ComboGenerator.generate(plan, models, vads, listOf(opt))
        jobs = combo?.jobs.orEmpty()
        val table = c.rtf.table.first()
        val summary = withContext(Dispatchers.IO) {
            val needed = jobs.flatMap { it.assetIds }.distinct().mapNotNull { c.assetSpecs[it] }
            val missing = needed.filter { !c.assetStore.isReady(it) }
            val eta = EtaEstimator(table, c.probe.deviceKey, s.workers).estimate(jobs, audioSec)
            ComboSummary(
                jobs = jobs.size, excluded = combo?.excluded?.sumOf { it.count } ?: 0, assetsNeeded = needed.size, assetsReady = needed.size - missing.size,
                missingBytes = missing.sumOf { it.archiveBytes ?: 0L }, freeBytes = c.assetStore.freeBytes(), etaSec = eta.totalSec, etaUnknown = eta.unknownJobs,
            )
        }
        _state.update { it.copy(summary = summary) }
    }

    /** 받아야 하는(아직 준비되지 않은) 자산 ID 들 — 다운로드 확인 대화창이 쓴다 */
    suspend fun missingAssetIds(): List<String> = withContext(Dispatchers.IO) {   // change-hyungchul-20261107-0000 : WP7 독립 검토 9 — 파일 확인을 입출력 스레드에서
        c.assets.missing(jobs.flatMap { it.assetIds }.distinct().filter { it in c.assetSpecs }).map { it.id }
    }

    // ───────────── 실행 준비 ─────────────

    /** 실행을 시작할 수 있는지와, 없으면 그 이유(빈 글이면 시작할 수 있다) */
    fun blocker(): String = when {
        inputRoot == null -> "입력 폴더를 고른다"
        outputRoot == null -> "출력 폴더를 고른다"
        jobs.isEmpty() -> "돌릴 작업이 없다 — 모델과 VAD 를 고르고 언어 폴더를 확인한다"
        c.controller.state.value.phase.active -> "이미 실행 중이다"
        else -> ""
    }

    /**
     * 목적: 출력 폴더에 남은 지난 실행의 진행 기록을 요약한다(이어 하기 대화창).
     * 입력: 없음 / 출력: 없음 / 리턴: ResumeInfo? - 기록이 없거나 이번 조합과 겹치는 작업이 없으면 null
     */
    suspend fun resumeInfo(): ResumeInfo? = withContext(Dispatchers.IO) {
        val file = outputRoot?.findFile(OutputLayout.PROGRESS_FILE) ?: return@withContext null
        val text = try {   // change-hyungchul-20261107-0000 : WP7 독립 검토 10 — 열 수 없으면 기록이 없는 것으로 본다(앱이 죽지 않게)
            context.contentResolver.openInputStream(file.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: java.io.IOException) {
            c.log.w("진행 기록을 읽을 수 없다: ${e.message}"); null
        } catch (e: SecurityException) {
            c.log.w("진행 기록을 읽을 권한이 없다: ${e.message}"); null
        } ?: return@withContext null
        val rows = PlanProgress.parse(text).rows.filterKeys { key -> jobs.any { it.key == key } }   // 이번 조합에 든 작업만
        if (rows.isEmpty()) return@withContext null
        val done = rows.values.count { it.status == PlanProgress.DONE }
        ResumeInfo(done, rows.values.count { it.status == PlanProgress.FAILED }, rows.values.count { it.status == PlanProgress.INTERRUPTED }, jobs.size - done)
    }

    /**
     * 목적: 실행 요청을 만든다.
     * 입력: restart - 끝난 작업도 다시 돌릴지("처음부터") / 출력: 없음 / 리턴: RunRequest? - 폴더가 없으면 null
     */
    fun request(restart: Boolean): RunRequest? {
        val out = outputRoot ?: return null
        val s = _state.value
        return RunRequest(out, jobs, audio, s.workers, s.purpose, restart, planName = "setup")
    }

    /**
     * 목적: 지금 조합을 CSV 로 출력 폴더에 저장한다(v3 의 조합 목록 형식 — PC 에서 고쳐 다시 쓸 수 있다).
     * 입력: 없음 / 출력: 출력 폴더에 plan_<시각>.csv, 상태의 안내 문구 / 리턴: 없음
     */
    fun savePlanCsv() = viewModelScope.launch {
        val out = outputRoot
        if (out == null || jobs.isEmpty()) { _state.update { it.copy(message = "저장할 조합이 없다(출력 폴더와 조합을 확인한다)") }; return@launch }
        val name = "plan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
        val ok = withContext(Dispatchers.IO) {
            val doc = out.createFile("text/csv", name) ?: return@withContext false
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { it.write(PlanCsv.format(PlanCsv.fromJobs(jobs), "saved from setup").toByteArray(Charsets.UTF_8)) } != null
        }
        _state.update { it.copy(message = if (ok) "조합 ${jobs.size}개를 저장했다: $name" else "조합 CSV 를 저장하지 못했다") }
    }

    // ───────────── add-hyungchul-20261106-2330 : [T7-5] 시작 전 점검 ─────────────

    /** 목적: 안내 문구를 바꾼다(실행을 시작할 수 없는 이유 등). 입력: text / 출력: 상태 / 리턴: 없음 */
    fun setMessage(text: String) = _state.update { it.copy(message = text) }

    /** 지금 조합이 ML Kit 을 쓰는지(마이크 권한이 필요하다) */
    val usesMlKit: Boolean get() = jobs.any { it.model.runtime == com.example.google_stt.domain.catalog.Runtime.MLKIT }

    /**
     * 목적: 시작 전 점검 항목을 만든다(4단계 4-4 — ①⑤⑥과 마이크 권한은 통과해야 시작할 수 있고, 나머지는 경고).
     * 입력: 없음 / 출력: VAD 실행기를 한 번 만들어 본다(적재 시험) / 리턴: List<PreflightCheck>
     */
    suspend fun preflight(): List<PreflightCheck> = withContext(Dispatchers.IO) {
        val out = ArrayList<PreflightCheck>()
        fun granted(permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        out += PreflightCheck("알림 권한", granted(android.Manifest.permission.POST_NOTIFICATIONS), true, "실행 중 진행 알림과 [중지] 버튼에 필요하다")
        if (usesMlKit) out += PreflightCheck("마이크 권한(ML Kit)", granted(android.Manifest.permission.RECORD_AUDIO), true, "ML Kit 은 파일을 넣을 때도 이 권한을 요구한다")
        val power = context.getSystemService(android.os.PowerManager::class.java)
        out += PreflightCheck("배터리 최적화 예외", power.isIgnoringBatteryOptimizations(context.packageName), false, "긴 실행이 절전으로 끊기지 않게 한다")
        out += PreflightCheck("충전 연결", c.probe.charging().isNotEmpty(), false, "긴 실행은 충전기를 연결하고 돌린다")
        out += PreflightCheck("\"절대 잠자지 않는 앱\" 등록", null, false, "삼성 기기: 설정 → 배터리 → 백그라운드 사용 제한 → 절대 잠자지 않는 앱에 이 앱을 넣는다")
        // ⑤ VAD 적재 시험: 자산이 준비된 엔진만 만들어 본다(없는 것은 받은 뒤 작업 시작 때 드러난다)
        val failed = ArrayList<String>()
        for (engine in jobs.map { it.vad.engine }.distinct()) {
            val id = Catalog.vadAssetId(engine)
            if (id != null && c.assetSpecs[id]?.let { c.assetStore.isReady(it) } != true) continue
            try {
                com.example.google_stt.engine.vad.VadDetectorFactory.create(engine, c.assetStore::dir)?.close()
            } catch (e: com.example.google_stt.domain.AppError.VadLoad) {
                failed += "${engine.token}: ${e.message}"
            }
        }
        out += PreflightCheck("VAD 적재 시험", failed.isEmpty(), true, if (failed.isEmpty()) "고른 VAD 엔진을 만들 수 있다" else failed.joinToString("; "))
        // ⑥ 저장공간: 받아야 할 자산의 필요 용량(압축 × 2 + 1 GB)
        val missing = c.assets.missing(jobs.flatMap { it.assetIds }.distinct().filter { it in c.assetSpecs })
        val need = com.example.google_stt.data.assets.AssetStore.requiredBytes(missing); val free = c.assetStore.freeBytes()
        out += PreflightCheck("저장공간", free >= need, true, "모델 받기에 필요 ${need / 1024 / 1024} MB · 여유 ${free / 1024 / 1024} MB")
        out
    }
}

/**
 * add-hyungchul-20261106-2330 : [T7-5]
 * 목적: 시작 전 점검 항목 하나.
 * 필드: title - 항목 이름, ok - 통과 여부(null = 앱이 확인할 수 없는 안내 항목), required - 통과해야 시작할 수 있는지, detail - 설명
 */
data class PreflightCheck(val title: String, val ok: Boolean?, val required: Boolean, val detail: String)
