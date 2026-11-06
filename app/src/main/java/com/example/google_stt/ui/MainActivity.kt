/*
 * 파일명: ui/MainActivity.kt
 * 목적 및 기능:
 * - v4 화면(Compose)의 Activity. 테마를 입히고 탭 뼈대(AppRoot)에 세 탭을 끼운다.
 * - 실행 흐름(4단계 4-1)의 순서를 맡는다:
 *     [받고 실행] → (언어를 모르는 폴더가 있으면) 언어 선택 → (지난 실행 기록이 있으면) 이어 하기 → 시작 전 점검 → (받을 모델이 있으면) 다운로드 확인 → 실행 시작, 실행 탭으로
 *     실행 탭의 [중지] → 중지 대화창(지금 / 이 작업 끝나고)
 * - v3 화면(루트 패키지의 MainActivity, View 기반)은 WP8 까지 그대로 두고, 그때 런처를 이 Activity 로 바꾼다(T8-1).
 *   그 전에는  adb shell am start -n com.example.google_stt/.ui.MainActivity  로 연다.
 *
 * add-hyungchul-20261106-2324 — [T7-1]  (change-hyungchul-20261106-2331 : [T7-2~T7-5] 탭 화면과 대화창 흐름 연결)
 */
package com.example.google_stt.ui

import android.Manifest                                           // 권한 이름
import android.content.Intent                                     // 배터리 예외 요청 화면
import android.net.Uri                                            // 패키지 주소
import android.os.Bundle                                          // 저장된 상태
import android.provider.Settings                                  // 배터리 예외 요청 동작
import androidx.activity.ComponentActivity                        // Compose 를 올리는 Activity
import androidx.activity.compose.rememberLauncherForActivityResult   // 권한 요청 띄우기
import androidx.activity.compose.setContent                       // Compose 화면 넣기
import androidx.activity.enableEdgeToEdge                         // 화면 끝까지 그리기 + 표시줄 색
import androidx.activity.result.contract.ActivityResultContracts  // 권한 요청 약속
import androidx.compose.runtime.Composable                        // 컴포저블 표시
import androidx.compose.runtime.getValue                          // 상태 읽기
import androidx.compose.runtime.mutableStateOf                    // 상태
import androidx.compose.runtime.remember                          // 화면이 떠 있는 동안의 상태
import androidx.compose.runtime.rememberCoroutineScope            // 대화창 흐름의 코루틴
import androidx.compose.runtime.saveable.rememberSaveable         // 화면 회전에도 남는 상태
import androidx.compose.runtime.setValue                          // 상태 쓰기
import androidx.lifecycle.compose.collectAsStateWithLifecycle     // 상태 흐름 읽기
import androidx.lifecycle.viewmodel.compose.viewModel             // ViewModel 얻기
import com.example.google_stt.AppContainer                        // 객체 묶음
import com.example.google_stt.SttApp                              // Application
import com.example.google_stt.domain.lang.LangFolder              // 언어 폴더
import com.example.google_stt.run.StopMode                        // 중지 방식
import com.example.google_stt.ui.dialogs.DownloadConfirmDialog    // 다운로드 확인
import com.example.google_stt.ui.dialogs.LangDialog               // 언어 선택
import com.example.google_stt.ui.dialogs.PreflightDialog          // 시작 전 점검
import com.example.google_stt.ui.dialogs.ResumeDialog             // 이어 하기
import com.example.google_stt.ui.dialogs.StopDialog               // 중지
import com.example.google_stt.ui.models.ModelsScreen              // 모델 탭
import com.example.google_stt.ui.models.ModelsViewModel           // 모델 탭 상태
import com.example.google_stt.ui.run.DashboardScreen              // 실행 탭
import com.example.google_stt.ui.run.DashboardViewModel           // 실행 탭 상태
import com.example.google_stt.ui.setup.PreflightCheck             // 점검 항목
import com.example.google_stt.ui.setup.ResumeInfo                 // 지난 실행 요약
import com.example.google_stt.ui.setup.SetupScreen                // 설정 탭
import com.example.google_stt.ui.setup.SetupViewModel             // 설정 탭 상태
import com.example.google_stt.ui.theme.SttTheme                   // 테마
import kotlinx.coroutines.launch                                  // 코루틴 시작

/** 목적: v4 화면 Activity. */
class MainActivity : ComponentActivity() {

    /** 목적: 화면을 올린다. 입력: savedInstanceState - 저장된 상태 / 출력: 화면 / 리턴: 없음 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()                                                         // 상태 표시줄 글자색을 테마(라이트/다크)에 맞춘다
        val c = (application as SttApp).container
        setContent { SttTheme { App(c) } }
    }

    /** 실행 흐름에서 지금 떠 있는 대화창 */
    private enum class Step { NONE, LANG, RESUME, PREFLIGHT, DOWNLOAD, STOP }

    /**
     * 목적: 탭 세 개와 대화창 흐름을 그린다.
     * 입력: c - 객체 묶음 / 출력: 화면 / 리턴: 없음
     */
    @Composable
    private fun App(c: AppContainer) {
        var tab by rememberSaveable { mutableStateOf(AppTab.SETUP) }               // 고른 탭(회전·재생성에도 유지 — FR-09-05)
        val setupVm: SetupViewModel = viewModel { SetupViewModel(c, applicationContext) }
        val dashVm: DashboardViewModel = viewModel { DashboardViewModel(c) }
        val modelsVm: ModelsViewModel = viewModel { ModelsViewModel(c, applicationContext) }
        val setup by setupVm.state.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()

        var step by remember { mutableStateOf(Step.NONE) }                         // 지금 대화창
        var restart by remember { mutableStateOf(false) }                          // "처음부터" 를 골랐는지
        var askLang by remember { mutableStateOf<LangFolder?>(null) }              // 언어를 묻고 있는 폴더
        var resume by remember { mutableStateOf<ResumeInfo?>(null) }               // 지난 실행 요약
        var checks by remember { mutableStateOf<List<PreflightCheck>?>(null) }     // 점검 결과(null = 확인 중)

        // 목적: 점검을 (다시) 돌린다. 입력: 없음 / 리턴: 없음
        fun runChecks() { checks = null; scope.launch { checks = setupVm.preflight() } }
        val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { runChecks() }   // 답을 받으면 다시 점검

        // 목적: 실행을 시작하고 실행 탭으로 간다. 입력: 없음 / 리턴: 없음
        fun launchRun() {
            step = Step.NONE
            val req = setupVm.request(restart) ?: return
            if (c.controller.start(req)) tab = AppTab.RUN else setupVm.setMessage("이미 실행 중이다")
        }
        // 목적: 점검 대화창으로 간다. 입력: 없음 / 리턴: 없음
        fun toPreflight() { step = Step.PREFLIGHT; runChecks() }
        // 목적: 언어 선택이 끝난 뒤의 순서 — 지난 실행 기록이 있으면 이어 하기를 묻는다. 입력: 없음 / 리턴: 없음
        fun afterLang() = scope.launch {
            val block = setupVm.blocker()
            if (block.isNotEmpty()) { step = Step.NONE; setupVm.setMessage(block); return@launch }
            restart = false
            resume = setupVm.resumeInfo()
            if (resume != null) step = Step.RESUME else toPreflight()
        }
        // 목적: [받고 실행] 을 눌렀을 때. 입력: 없음 / 리턴: 없음
        fun onStart() {
            setupVm.setMessage("")
            askLang = setupVm.unknownFolders.firstOrNull()
            if (askLang != null) step = Step.LANG else afterLang()
        }

        AppRoot(
            tab = tab, onTab = { tab = it },
            setup = { SetupScreen(setupVm, onStart = ::onStart) },
            run = { DashboardScreen(dashVm, onStop = { step = Step.STOP }) },
            models = { ModelsScreen(modelsVm, neededIds = setupVm::missingAssetIds) },
        )

        when (step) {
            Step.LANG -> askLang?.let { folder ->
                LangDialog(folder, left = setupVm.unknownFolders.size - 1, onCancel = { step = Step.NONE }, onChoose = { lang ->
                    scope.launch {
                        setupVm.chooseLang(folder, lang).join()                    // 기억하고 다시 훑는다
                        askLang = setupVm.unknownFolders.firstOrNull()
                        if (askLang == null) afterLang()                           // 다 물었으면 다음 순서로
                    }
                })
            }
            Step.RESUME -> resume?.let { info ->
                ResumeDialog(info, onResume = { restart = false; toPreflight() }, onRestart = { restart = true; toPreflight() }, onClose = { step = Step.NONE })
            }
            Step.PREFLIGHT -> PreflightDialog(
                checks,
                onRequestPermissions = { askPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)) },
                onBattery = { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) },
                onProceed = { if (setupVm.missingAssetIds().isNotEmpty()) step = Step.DOWNLOAD else launchRun() },
                onCancel = { step = Step.NONE },
            )
            Step.DOWNLOAD -> DownloadConfirmDialog(
                count = setup.summary.assetsNeeded - setup.summary.assetsReady, bytes = setup.summary.missingBytes, freeBytes = setup.summary.freeBytes,
                wifiOnly = setup.wifiOnly, onConfirm = ::launchRun, onCancel = { step = Step.NONE },
            )
            Step.STOP -> StopDialog(
                onNow = { step = Step.NONE; dashVm.stop(StopMode.NOW) },
                onAfter = { step = Step.NONE; dashVm.stop(StopMode.AFTER_CURRENT) },
                onCancel = { step = Step.NONE },
            )
            Step.NONE -> Unit
        }
    }
}
