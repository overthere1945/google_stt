/*
 * 파일명: MainActivity.kt
 * 목적 및 기능:
 * - 입력 폴더의 음성 파일을 16 kHz / mono / PCM16 으로 변환한 뒤 Google 계열 On-device STT 로 전사한다.
 * - 비교 가능한 3개 경로:
 *     (1) ML Kit GenAI Speech Recognition — MODE_BASIC   → 폴더 base
 *     (2) (삭제) ML Kit GenAI — MODE_ADVANCED(Pixel 10 전용) — change-hyungchul-20261009-1524 : Galaxy S25 Ultra 평가에서 쓸 수 없어 제거
 *     (3) Android Platform SpeechRecognizer.createOnDeviceSpeechRecognizer() → 폴더 android_ondevice
 * - 결과 저장:
 *     output/google/<모드>/<입력 하위 폴더>/<파일명>.txt
 *     output/google/<모드>/result.csv    (파일별 지연/자원 계측, utf-8-sig)
 *     output/google/<모드>/run_meta.csv  (실행 1회 환경 요약, utf-8-sig)
 *
 * change(add)-hyungchul-20260820 — 성능/자원 계측 추가
 *   · 지연을 4단계로 분해해 측정한다: ready / first_result / feed / tail / wall
 *   · ★ tail(공급 EOF → 최종 결과)이 "엔진의 순수 처리 지연"이다.
 *     오디오를 실시간 속도로 흘려보내면 wall 은 거의 음원 길이와 같아져 엔진 비교에 쓸 수 없다.
 *     그래서 Feed 속도(REALTIME/D50/D30/D20/D10)를 UI에서 고를 수 있게 하고 CSV에 함께 남긴다.
 *     change-hyungchul-20260914-2350 : 지연 10 ms 미만은 엔진이 못 따라와서 목록에서 뺐다.
 *   · 온디바이스 여부 판정 근거(네트워크 상태/비행기모드/앱 UID 트래픽)를 파일마다 기록한다.
 *   · CPU 시간, PSS 메모리, 배터리 소모(µAh), 배터리 온도, thermal 상태를 함께 기록한다.
 *
 * change(add)-hyungchul-20260825-1430 — Silero VAD 를 STT 앞단의 "선택적" 전처리로 추가
 *   · UI 스위치로 ON/OFF 한다. OFF 면 AudioDecoder 가 만든 PCM 을 1 byte 도 바꾸지 않는다(= 기존 baseline 그대로).
 *   · ON 이면 Silero 공식 get_speech_timestamps() 규칙으로 speech 구간을 찾아 그 구간만 이어 붙인 PCM 을
 *     기존 STT 세션에 "정확히 1회" 흘려보낸다. 구간마다 세션을 새로 열지 않는다(엔진 endpointing 조건을 바꾸면 비교가 깨진다).
 *   · 결과 저장 경로가 갈린다.
 *       VAD OFF : output/google/<모드>/...              (기존 경로 그대로 — 기존 결과를 덮어쓰지 않는다)
 *       VAD ON  : output/google/<모드>/vad_<profile>/... (baseline 과 나란히 두고 WER/CER 을 비교한다)
 *   · ASR Safe / Balanced / Aggressive 는 Silero 공식 preset 이 아니라 본 프로젝트 평가용 초기값이다.
 *   · VAD 가 WER/CER 을 개선한다고 미리 단정하지 않는다. baseline 과 같은 음원으로 돌려서 수치로 확인해야 한다.
 *
 * change(add)-hyungchul-20260826-1100 — 노이즈 저감(Noise Reduction)을 VAD 앞단에 추가
 *   · 파이프라인: 디코딩 → [노이즈 저감] → [Silero VAD] → STT
 *     (사용자 조사 문서 5장의 권장 순서와 같다. 잡음을 먼저 없애야 VAD 판정도 정확해진다)
 *   · 알고리즘은 UI 스피너로 런타임에 고른다. 각 알고리즘의 옵션도 화면에서 조절한다.
 *   · VAD 는 Silero 로 통일했다. 이유:
 *       (1) 실행 가능한 노이즈 저감 알고리즘(GTCRN/DPDFNet/NSNet2/전통 DSP) 중
 *           쓸 만한 자체 VAD 를 가진 것이 하나도 없다.
 *       (2) 자체 VAD 가 있는 WebRTC APM 은 애초에 이 앱에서 실행할 수 없다.
 *       (3) 알고리즘마다 VAD 가 다르면 WER/CER 차이가 노이즈 저감 때문인지 VAD 때문인지
 *           구분할 수 없게 된다. 변수를 하나만 바꾸기 위해 VAD 를 고정한다.
 *   · 결과 저장 경로가 조합으로 갈린다(baseline 을 절대 덮어쓰지 않는다).
 *       output/google/<모드>/[ns_<알고리즘>/][vad_<프로필>/]...
 *   · result.csv 에 단계별 소요 시간(prepare/stft/infer/istft/post)을 모두 남긴다.
 *
 * change-hyungchul-20260914-1500 — 2026-09-14 실측(24개 x 3조건)에서 드러난 결함 4건 수정
 *   (1) 배치가 취소/중단되면 비상 CSV 저장까지 같이 취소되어 계측이 통째로 사라졌다.
 *       → NonCancellable 로 감싸고, catch 가 아니라 finally 에서 "마지막 방어선"으로 저장한다.
 *   (2) ★ 인식기 close() 가 멈추면 배치 전체가 영원히 멈춘다.
 *       엔진이 먹통이 되면 타임아웃은 제대로 나는데, 그 직후 close() 가 네이티브 정리 중 블록되어
 *       타임아웃이 아무 의미가 없어진다. → close() 에 시간 제한을 두고 넘으면 배치를 계속 진행한다.
 *   (3) ERROR_TYPE_NO_SPEECH_DETECTED 는 엔진의 "말이 없다" 판정이지 고장이 아닌데 ERROR 로 처리해
 *       그 파일 행이 비교에서 빠졌다. → NO_MATCH 로 기록하고 엔진 메시지는 따로 남긴다.
 *   (4) 엔진이 파이프를 안 읽어 공급이 막히면(실측 7~10배) stt_wall_ms/rtf 가 조용히 오염된다.
 *       → 이론 공급시간 대비 비율을 feed_stall_ratio 컬럼과 로그로 드러낸다.
 *
 * change-hyungchul-20261009-1514 — 기능별 파일 분리(전면 리팩터링 1단계: 이동만, 동작 변경 없음)
 *   · 이 파일에는 화면 초기화 / 폴더 선택 / 권한 / START 입력 검증 / 로그·진행률·토스트만 남겼다.
 *   · 옮긴 곳:
 *       stt/SttModels.kt          SttEngine, ModelConfig, FeedConfig, SttOutcome, models/feeds(SttCatalog)
 *       stt/SttConstants.kt       companion 상수(타임아웃, SODA 세션 한도 등)
 *       stt/SttUtil.kt            ms / describe / isEngineDead / isNoSpeechError / expectedFeedMs / sttTimeoutSec
 *       stt/PcmFeeder.kt          PCM 공급기
 *       stt/MlKitTranscriber.kt   ML Kit 전사 + 인식기 닫기 시간 제한
 *       stt/PlatformOnDeviceTranscriber.kt  Android 플랫폼 on-device 전사
 *       stt/GoogleSttSession.kt   transcribeOne / 할당량 재시도 / 조각 합치기 / warm-up / 세션 수
 *       io/AudioInput.kt          입력 폴더 탐색
 *       io/ResultStore.kt         결과 폴더·TXT·CSV 저장
 *       ui/VadUi.kt, ui/EpdUi.kt, ui/NoiseUi.kt, ui/UiUtils.kt, ui/Selections.kt  화면 컨트롤러
 *       batch/BatchRunner.kt      배치 실행 본문(lifecycleScope.launch 안쪽), preflight, CSV 공통 컬럼 채우기
 *
 * add-hyungchul-20261010-1800 — 2단계 2-1: sherpa-onnx 오프라인 STT 엔진(Whisper / Qwen3-ASR 0.6B / Omnilingual 300M)
 *       stt/SherpaModels.kt              모델 목록(폴더·파일·조각 상한·라이선스)
 *       stt/SherpaOfflineTranscriber.kt  모델 불러오기·조각 디코딩·해제
 *   · Model 스피너에 Google 2개 뒤로 sherpa 모델을 붙인다. 조건 이름에는 EPD 를 넣지 않는다(sherpa 는 EPD 를 쓰지 않음).
 *
 * ※ 이 3개 경로 중 어느 것도 Google Cloud 인증(서비스 계정 JSON, API Key, OAuth 토큰)을 사용하지 않는다.
 */
package com.example.google_stt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings                 // add-hyungchul-20260916-1800
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.example.google_stt.batch.BatchHost                        // change-hyungchul-20261009-1514
import com.example.google_stt.batch.BatchRequest                     // change-hyungchul-20261009-1514
import com.example.google_stt.batch.BatchRunner                      // change-hyungchul-20261009-1514
import com.example.google_stt.io.ResultStore                         // change-hyungchul-20261009-1514
import com.example.google_stt.stt.GoogleSttSession                   // change-hyungchul-20261009-1514
import com.example.google_stt.stt.MlKitTranscriber                   // change-hyungchul-20261009-1514
import com.example.google_stt.stt.PlatformOnDeviceTranscriber        // change-hyungchul-20261009-1514
import com.example.google_stt.stt.SttCatalog                         // change-hyungchul-20261009-1514
import com.example.google_stt.stt.describe                           // change-hyungchul-20261009-1514
import com.example.google_stt.ui.EpdUi                               // change-hyungchul-20261009-1514
import com.example.google_stt.ui.NoiseUi                             // change-hyungchul-20261009-1514
import com.example.google_stt.ui.VadUi                               // change-hyungchul-20261009-1514
import kotlinx.coroutines.launch
import java.util.Locale

// change-hyungchul-20261009-1514 : 배치 실행기(BatchRunner)가 화면에 요청하는 일을 받기 위해 BatchHost 를 구현한다.
class MainActivity : AppCompatActivity(), BatchHost {

    private val logTag = "GoogleStt"

    private val algoName = "google"

    // change-hyungchul-20261009-1514 : 정의는 stt/SttModels.kt(SttCatalog)로 옮기고 여기서는 참조만 한다.
    private val models = SttCatalog.models
    private val feeds = SttCatalog.feeds

    private var inputTreeUri: Uri? = null
    private var outputTreeUri: Uri? = null

    private lateinit var inputPathView: TextView
    private lateinit var outputPathView: TextView
    private lateinit var langSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var feedSpinner: Spinner

    // change-hyungchul-20261009-1514 : VAD / EPD / 노이즈 저감 화면 컨트롤러(멤버 View 와 함수는 ui/*.kt 로 옮겼다)
    private lateinit var vadUi: VadUi
    private lateinit var epdUi: EpdUi
    private lateinit var noiseUi: NoiseUi

    // change-hyungchul-20261009-1514 : STT 전사기 · 세션 관리자 · 결과 저장기 · 배치 실행기
    private lateinit var batchRunner: BatchRunner

    private lateinit var startButton: Button
    private lateinit var logView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var running = false

    /** change-hyungchul-20261009-1514 : BatchHost — 배치 실행기가 DocumentFile / getString 에 쓸 Activity */
    override val activity: AppCompatActivity get() = this

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startTranscription()
            else toast("RECORD_AUDIO 권한이 필요합니다. 설정에서 허용해주세요.")
        }

    private val inputPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                inputTreeUri = uri
                inputPathView.text = getString(R.string.input_label, prettyPath(uri))
            }
        }

    private val outputPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                outputTreeUri = uri
                outputPathView.text = getString(R.string.output_label, prettyPath(uri))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        inputPathView = findViewById(R.id.inputPath)
        outputPathView = findViewById(R.id.outputPath)
        langSpinner = findViewById(R.id.langSpinner)
        modelSpinner = findViewById(R.id.modelSpinner)
        feedSpinner = findViewById(R.id.feedSpinner)   // change(add)-hyungchul-20260820

        // change-hyungchul-20261009-1514 : VAD / EPD / 노이즈 저감 View 는 각 컨트롤러가 찾는다.
        vadUi = VadUi(this, ::appendLog, ::toast)
        epdUi = EpdUi(this, ::appendLog)
        noiseUi = NoiseUi(this, ::appendLog, ::toast)

        // change-hyungchul-20261009-1514 : 전사기와 배치 실행기를 만든다(기존 MainActivity 멤버 함수들을 옮긴 클래스).
        val mlKit = MlKitTranscriber(::appendLog)
        val platform = PlatformOnDeviceTranscriber(this)
        batchRunner = BatchRunner(
            host = this,
            session = GoogleSttSession(mlKit, platform, ::appendLog),
            mlKit = mlKit,
            platform = platform,
            store = ResultStore(contentResolver),   // change-hyungchul-20261009-1530 : 평탄화 규칙 — algoName 인자 제거
            algoName = algoName,
        )

        startButton = findViewById(R.id.startButton)
        logView = findViewById(R.id.logView)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        progressBar.max = 1000
        updateProgress(0.0)

        langSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.lang_english),
                getString(R.string.lang_korean),
                getString(R.string.lang_auto),
            ),
        )

        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.model_base),
                // change-hyungchul-20261009-1524 : Pixel 10 전용 Advanced 항목 제거(SttCatalog.models 와 순서를 맞춘다)
                getString(R.string.model_android_ondevice),
            ) + models.mapNotNull { it.sherpa?.displayName },   // add-hyungchul-20261010-1800 : sherpa-onnx 모델(SttCatalog 순서 그대로, Google 2개 뒤)
        )

        // change-hyungchul-20260824
        // 항목을 feeds 목록에서 자동 생성한다(지연값을 늘려도 strings.xml 을 고칠 필요가 없다).
        feedSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            feeds.map { fc ->
                if (fc.delayMs <= 0L) {
                    "${fc.name} — 지연 없음 (최대 속도)"
                } else {
                    val speed = String.format(Locale.US, "%.1f", 100.0 / fc.delayMs)
                    "${fc.name} — ${fc.delayMs} ms/chunk (실시간 ×$speed)"
                }
            },
        )

        vadUi.setupVadUi()     // add-hyungchul-20260825-1430
        epdUi.setupEpdUi()     // add-hyungchul-20260914-2350
        noiseUi.setupNoiseUi()   // add-hyungchul-20260826-1100

        findViewById<Button>(R.id.inputButton).setOnClickListener { inputPicker.launch(null) }
        findViewById<Button>(R.id.outputButton).setOnClickListener { outputPicker.launch(null) }
        startButton.setOnClickListener { startTranscription() }
    }

    // ─────────────────────────── 실행 ───────────────────────────
    private fun startTranscription() {
        if (running) {
            toast(getString(R.string.msg_already_running)); return
        }

        // SpeechRecognizer 클래스는 문서상 RECORD_AUDIO 권한을 요구한다(파일 입력이라도 동일).
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO); return
        }

        val inUri = inputTreeUri
        val outUri = outputTreeUri
        if (inUri == null || outUri == null) {
            toast(getString(R.string.msg_pick_folders)); return
        }
        val locale = localeForSelection(langSpinner.selectedItemPosition)

        val modelIndex = modelSpinner.selectedItemPosition.coerceIn(0, models.size - 1)
        val modelConfig = models[modelIndex]
        // change-hyungchul-20261009-1514 : modelFolder 는 BatchRunner.run() 안에서 modelConfig.folder 로 만든다.

        // change(add)-hyungchul-20260820
        val feed = feeds[feedSpinner.selectedItemPosition.coerceIn(0, feeds.size - 1)]

        // add-hyungchul-20260825-1430
        // 값이 잘못돼 있으면 배치를 시작하기 전에 알린다(실행 중간에 죽는 것보다 낫다).
        val vadSelection = try {
            vadUi.readVadSelection()
        } catch (e: Exception) {
            toast("VAD 설정 오류: ${describe(e)}"); return
        }

        // add-hyungchul-20260914-2350
        val epdSelection = try {
            epdUi.readEpdSelection()
        } catch (e: Exception) {
            appendLog("[epd][설정 오류] ${describe(e)}")
            toast("Speech EPD 설정 오류: ${describe(e)}"); return
        }

        // add-hyungchul-20260826-1100
        val noiseConfig = try {
            noiseUi.readNoiseConfig()
        } catch (e: Exception) {
            appendLog("[ns][설정 오류] ${describe(e)}")
            toast("노이즈 저감 설정 오류: ${describe(e)}"); return
        }

        // change-hyungchul-20261009-1530 : 결과 폴더 조합을 PC GUI v7.7 평탄화 규칙의 "조건 이름" 으로 바꿨다.
        //   예전: output/google/<모드>/[ns_xxx/][vad_yyy/][epd_zzz/]...
        //   지금: output/<엔진>_<모델>_<조건>/...  ,  <조건> = [NS_…_]<VAD 프리셋>[_SEG10][_EPD_…]
        //   예) NOVAD, SILERO_SAFE, NS_GTCRN_SILERO, PYANNOTE_S500_SEG10, SILERO_EPD_SPLIT_T45_M55
        val condition = buildList {
            noiseConfig.outputFolder()?.let { add(it.uppercase(Locale.US)) }         // ns_gtcrn_lim12 → NS_GTCRN_LIM12
            add(vadSelection.conditionToken)                                          // NOVAD / SILERO_SAFE / …
            if (vadSelection.segMode.conditionSuffix.isNotEmpty()) add(vadSelection.segMode.conditionSuffix)  // SEG10
            // EPD 는 10초 통일에서 쓰지 않으므로(BatchRunner) 그때는 이름에도 넣지 않는다
            // change-hyungchul-20261010-1800 : sherpa-onnx 엔진도 EPD 를 쓰지 않으므로 이름에 넣지 않는다
            if (vadSelection.segMode.maxSec == null && modelConfig.sherpa == null) {
                epdSelection.outputFolder?.let { add(it.uppercase(Locale.US)) }       // epd_split_t45_m55 → EPD_SPLIT_T45_M55
            }
        }.joinToString("_")

        running = true
        startButton.isEnabled = false
        logView.text = ""
        updateProgress(0.0)
        toast(getString(R.string.msg_stt_started))

        lifecycleScope.launch {
            // change-hyungchul-20261009-1514 : 배치 본문은 batch/BatchRunner.run() 으로 옮겼다(내용 동일).
            batchRunner.run(
                BatchRequest(
                    inUri = inUri,
                    outUri = outUri,
                    locale = locale,
                    modelConfig = modelConfig,
                    feed = feed,
                    vadSelection = vadSelection,
                    epdSelection = epdSelection,
                    noiseConfig = noiseConfig,
                    condition = condition,   // change-hyungchul-20261009-1530
                ),
            )
        }
    }

    /**
     * change-hyungchul-20261009-1514
     * 목적: BatchHost — 배치가 어떤 경로로든 끝났을 때 실행 중 표시를 풀고 START 버튼을 되살린다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     * 비고: 원래 startTranscription() 의 finally 마지막 두 줄(running = false / startButton.isEnabled = true)이다.
     */
    override fun onBatchFinished() {
        running = false
        startButton.isEnabled = true
    }

    // ─────────────────────────── 유틸 ───────────────────────────
    override fun updateProgress(fraction: Double) {
        val clamped = fraction.coerceIn(0.0, 1.0)
        runOnUiThread {
            progressBar.progress = (clamped * 1000).toInt()
            progressText.text = String.format(Locale.US, "%.1f%%", clamped * 100)
        }
    }

    private fun localeForSelection(pos: Int): Locale = when (pos) {
        0 -> Locale.US
        1 -> Locale.KOREA
        else -> {
            val def = Locale.getDefault()
            if (def.language == "ko") Locale.KOREA else Locale.US
        }
    }

    private fun prettyPath(uri: Uri): String {
        val last = uri.lastPathSegment ?: uri.toString()
        return last.substringAfterLast(':').ifEmpty { last }
    }

    override fun appendLog(line: String) {
        Log.i(logTag, line)
        runOnUiThread { logView.append(line + "\n") }
    }

    /**
     * add-hyungchul-20260916-1800
     * 목적: 'Google 음성 서비스'(com.google.android.tts) 앱 정보 화면을 띄운다.
     * 입력: 없음 / 출력: 없음 (설정 화면으로 이동) / 리턴: 없음
     *
     * 배경: 엔진이 먹통이 되면 그 프로세스를 강제 종료하는 것 말고는 회복 방법이 없다.
     *       (실측: 18분을 기다려도 같은 PID 에서는 끝내 회복되지 않았다)
     *       그런데 그 화면까지 손으로 찾아가려면 설정 → 애플리케이션 → 전체 목록 → 검색이라
     *       번거롭다. 그래서 '강제 중지' 버튼이 있는 화면으로 바로 보내 준다.
     *       앱이 남의 프로세스를 직접 죽일 수는 없으므로 여기까지가 최선이다.
     */
    override fun openSpeechServiceSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$SPEECH_SERVICE_PACKAGE")   // 어느 앱을 볼지 지정
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)               // 액티비티 밖에서도 뜨게 한다
        }
        // 기기에 따라 이 화면이 없을 수 있으므로 실패해도 배치 흐름을 깨지 않는다.
        val ok = runCatching { startActivity(intent) }.isSuccess
        if (!ok) {
            appendLog(
                "[warmup] 설정 화면을 열지 못했다. 설정 → 애플리케이션에서 " +
                    "'$SPEECH_SERVICE_PACKAGE' 를 찾아 강제 중지할 것.",
            )
        }
    }

    override fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        // add-hyungchul-20260916-1800
        /**
         * 실제 인식을 수행하는 서비스 패키지.
         * logcat 으로 확인: ML Kit BASIC → com.google.android.tts 안의 SODA 로 내려간다.
         * (com.google.android.as 나 com.google.android.aicore 가 아니다)
         */
        private const val SPEECH_SERVICE_PACKAGE = "com.google.android.tts"
    }
}
