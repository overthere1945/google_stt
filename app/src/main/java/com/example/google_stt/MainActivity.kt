/*
 * 파일명: MainActivity.kt
 * 목적 및 기능:
 * - 입력 폴더의 음성 파일을 16 kHz / mono / PCM16 으로 변환한 뒤 Google 계열 On-device STT 로 전사한다.
 * - 비교 가능한 3개 경로:
 *     (1) ML Kit GenAI Speech Recognition — MODE_BASIC   → 폴더 base
 *     (2) ML Kit GenAI Speech Recognition — MODE_ADVANCED→ 폴더 advanced (Pixel 10 계열만)
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
 * ※ 이 3개 경로 중 어느 것도 Google Cloud 인증(서비스 계정 JSON, API Key, OAuth 토큰)을 사용하지 않는다.
 */
package com.example.google_stt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.net.Uri
import android.provider.Settings                 // add-hyungchul-20260916-1800
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import android.text.Editable                       // add-hyungchul-20260825-1430
import android.text.TextWatcher                    // add-hyungchul-20260825-1430
import android.util.Log
import android.view.View                           // add-hyungchul-20260825-1430
import android.view.ViewGroup                      // add-hyungchul-20260825-1430
import android.widget.AdapterView                  // add-hyungchul-20260825-1430
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText                     // add-hyungchul-20260825-1430
import android.widget.LinearLayout                 // add-hyungchul-20260825-1430
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat      // add-hyungchul-20260825-1430
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.google_stt.denoise.NoiseAlgorithm            // add-hyungchul-20260826-1100
import com.example.google_stt.denoise.NoiseAlgorithmKind        // add-hyungchul-20260826-1100
import com.example.google_stt.denoise.NoiseReduceConfig         // add-hyungchul-20260826-1100
import com.example.google_stt.denoise.NoiseReducer              // add-hyungchul-20260826-1100
import com.example.google_stt.denoise.NoiseReducerFactory       // add-hyungchul-20260826-1100
import com.example.google_stt.denoise.OnnxNoiseReducerBase      // add-hyungchul-20260826-1100
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable        // add-hyungchul-20260914-1500
import kotlinx.coroutines.TimeoutCancellationException  // add-hyungchul-20260914-2350
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay                  // add-hyungchul-20260915-2200
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking            // add-hyungchul-20260915-1400
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.math.roundToInt                     // add-hyungchul-20260825-1430
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity() {

    private val logTag = "GoogleStt"

    private val audioExtensions =
        setOf("mp3", "wav", "m4a", "flac", "ogg", "aac", "mp4", "wma", "opus")

    private val algoName = "google"

    private enum class SttEngine { ML_KIT, ANDROID_PLATFORM_ON_DEVICE }

    private data class ModelConfig(
        val folder: String,
        val engine: SttEngine,
        val mlKitMode: Int? = null,
    )

    private val models = listOf(
        ModelConfig("base", SttEngine.ML_KIT, SpeechRecognizerOptions.Mode.MODE_BASIC),
        ModelConfig("advanced", SttEngine.ML_KIT, SpeechRecognizerOptions.Mode.MODE_ADVANCED),
        ModelConfig("android_ondevice", SttEngine.ANDROID_PLATFORM_ON_DEVICE),
    )

    // change(add)-hyungchul-20260820
    /**
     * 오디오 공급 속도 설정.
     * - REALTIME: 100 ms 분량을 100 ms 마다 → 실제 마이크와 같은 속도. ML Kit 공식 요건.
     *             이 모드에서는 wall ≈ 음원 길이가 되므로 엔진 비교는 tail_ms 로 해야 한다.
     * - FAST    : 15 ms 간격. 배치 처리량 측정용(expo-speech-recognition 의 on-device 기본값).
     * - MAX     : 지연 없이 파이프가 받아주는 최대 속도. 엔진이 감당 못하면 결과가 잘릴 수 있다.
     */
    private data class FeedConfig(val name: String, val delayMs: Long)

    // change-hyungchul-20260824
    // 실측 결과 15 ms 는 전량 성공(REALTIME 과 전사 텍스트 8/8 완전 일치),
    // 0 ms 는 전량 ERROR_TYPE_AUDIO_BUFFER_OVERFLOW 였다.
    // → 엔진의 최대 처리 속도는 그 사이에 있다. 이진 탐색이 가능하도록 지연값을 세분화한다.
    //   "전량 OK + 전사 텍스트가 REALTIME 기준본과 동일" 을 만족하는 가장 낮은 지연값이
    //   이 엔진의 진짜 RTF( = delay / 100 )가 된다.
    // change-hyungchul-20260914-2350
    //   10 ms 보다 빠르게(=지연 10 ms 미만) 밀어 넣으면 엔진이 동작하지 않는다.
    //   실측에서도 0 ms 는 전량 ERROR_TYPE_AUDIO_BUFFER_OVERFLOW 였다.
    //   그래서 D8 이하와 MAX 를 전부 걷어내고, 실사용 구간만 5단계로 남긴다.
    //   배속(실시간 ×N) 은 100 ms(=1 chunk 분량) / 지연 으로 자동 계산된다.
    private val feeds = listOf(
        FeedConfig("REALTIME", 100L),   // 실시간 ×1.0  — ML Kit 공식 문서가 요구하는 속도
        FeedConfig("D50", 50L),         // 실시간 ×2.0
        FeedConfig("D30", 30L),         // 실시간 ×3.3
        FeedConfig("D20", 20L),         // 실시간 ×5.0
        FeedConfig("D10", 10L),         // 실시간 ×10.0 — 현재까지 확인된 하한
    )

    // add-hyungchul-20260825-1430
    /**
     * 목적: START 를 누른 "그 순간"의 VAD 설정을 불변 snapshot 으로 고정한다.
     *       배치가 도는 중에 사용자가 UI 를 만져도 실행 조건이 바뀌지 않게 하기 위함이다.
     * 필드: enabled(ON/OFF), profile(표시용), config(실제 파라미터),
     *       outputFolder — null 이면 VAD OFF 이며 기존 output/google/<모드>/ 경로를 그대로 쓴다.
     */
    private data class VadSelection(
        val enabled: Boolean,
        val profile: VadProfile,
        val config: VadConfig,
        val outputFolder: String?,
    )

    // add-hyungchul-20260914-2350
    /**
     * 목적: START 를 누른 "그 순간"의 Speech EPD 설정을 불변 snapshot 으로 고정한다.
     * 필드: config — EPD 파라미터,
     *       outputFolder — null 이면 EPD OFF 이며 폴더명을 더 붙이지 않는다.
     */
    private data class EpdSelection(
        val config: EpdConfig,
        val outputFolder: String?,
    ) {
        val enabled: Boolean get() = config.mode != EpdMode.OFF
    }

    private var inputTreeUri: Uri? = null
    private var outputTreeUri: Uri? = null

    private lateinit var inputPathView: TextView
    private lateinit var outputPathView: TextView
    private lateinit var langSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var feedSpinner: Spinner

    // add-hyungchul-20260825-1430 : Silero VAD UI
    private lateinit var vadSwitch: SwitchCompat
    private lateinit var vadOptionsGroup: LinearLayout
    private lateinit var vadProfileSpinner: Spinner
    private lateinit var vadThresholdEdit: EditText
    private lateinit var vadMinSpeechEdit: EditText
    private lateinit var vadMinSilenceEdit: EditText
    private lateinit var vadSpeechPadEdit: EditText
    private lateinit var vadMaxSpeechEdit: EditText

    // add-hyungchul-20260914-2130 : 구간 사이 무음(ms) 입력창 (PC GUI 의 --vad-join-silence-ms)
    private lateinit var vadJoinSilenceEdit: EditText

    // add-hyungchul-20260914-2350 : Speech EPD UI
    private lateinit var epdModeSpinner: Spinner
    private lateinit var epdOptionsGroup: LinearLayout
    private lateinit var epdTargetEdit: EditText
    private lateinit var epdMaxEdit: EditText
    private lateinit var epdSilenceEdit: EditText

    /** preset 을 코드로 채워 넣는 동안 TextWatcher 가 Custom 으로 되돌리지 않게 막는 재진입 가드 */
    private var applyingVadPreset = false

    // add-hyungchul-20260915-2200
    /**
     * 배치를 시작한 뒤 지금까지 연 STT 세션 수(사전 점검 warm-up 포함).
     *
     * ★ 이 값을 세는 이유 (2026-09-15 원인 확정)
     *   AICore 에는 공개 문서에 없는 "앱당 추론 할당량" 이 있다.
     *   실측에서 D20(실시간 ×5) 실행과 REALTIME(실시간 ×1) 실행이
     *   경과 시간은 256초 대 1240초로 5배 차이가 나는데도
     *   똑같이 "35세션 성공 → 36번째 세션부터 먹통" 으로 죽었다.
     *   즉 공급 속도도 경과 시간도 아니고 "세션 수" 가 한도다.
     *   그래서 세션 수를 세어 로그와 CSV 에 남기고, 한도에 닿기 전에 미리 경고한다.
     */
    private var sttSessionCount = 0

    // add-hyungchul-20260826-1100 : Noise Reduction UI
    private lateinit var nsAlgoSpinner: Spinner
    private lateinit var nsOptionsGroup: LinearLayout
    private lateinit var nsVariantSpinner: Spinner
    private lateinit var nsAttenLimitEdit: EditText
    private lateinit var nsThreadsEdit: EditText
    private lateinit var nsMinGainEdit: EditText
    private lateinit var nsAlphaEdit: EditText
    private lateinit var nsBetaEdit: EditText
    private lateinit var nsQuantileEdit: EditText
    private lateinit var nsHint: TextView

    /** 알고리즘 preset 을 코드로 채우는 동안의 재진입 가드 */
    private var applyingNsPreset = false

    private lateinit var startButton: Button
    private lateinit var logView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var running = false

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

        // add-hyungchul-20260825-1430
        vadSwitch = findViewById(R.id.vadSwitch)
        vadOptionsGroup = findViewById(R.id.vadOptionsGroup)
        vadProfileSpinner = findViewById(R.id.vadProfileSpinner)
        vadThresholdEdit = findViewById(R.id.vadThresholdEdit)
        vadMinSpeechEdit = findViewById(R.id.vadMinSpeechEdit)
        vadMinSilenceEdit = findViewById(R.id.vadMinSilenceEdit)
        vadSpeechPadEdit = findViewById(R.id.vadSpeechPadEdit)
        vadMaxSpeechEdit = findViewById(R.id.vadMaxSpeechEdit)
        // add-hyungchul-20260914-2130
        vadJoinSilenceEdit = findViewById(R.id.vadJoinSilenceEdit)

        // add-hyungchul-20260914-2350
        epdModeSpinner = findViewById(R.id.epdModeSpinner)
        epdOptionsGroup = findViewById(R.id.epdOptionsGroup)
        epdTargetEdit = findViewById(R.id.epdTargetEdit)
        epdMaxEdit = findViewById(R.id.epdMaxEdit)
        epdSilenceEdit = findViewById(R.id.epdSilenceEdit)

        // add-hyungchul-20260826-1100
        nsAlgoSpinner = findViewById(R.id.nsAlgoSpinner)
        nsOptionsGroup = findViewById(R.id.nsOptionsGroup)
        nsVariantSpinner = findViewById(R.id.nsVariantSpinner)
        nsAttenLimitEdit = findViewById(R.id.nsAttenLimitEdit)
        nsThreadsEdit = findViewById(R.id.nsThreadsEdit)
        nsMinGainEdit = findViewById(R.id.nsMinGainEdit)
        nsAlphaEdit = findViewById(R.id.nsAlphaEdit)
        nsBetaEdit = findViewById(R.id.nsBetaEdit)
        nsQuantileEdit = findViewById(R.id.nsQuantileEdit)
        nsHint = findViewById(R.id.nsHint)

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
                getString(R.string.model_advanced),
                getString(R.string.model_android_ondevice),
            ),
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

        setupVadUi()     // add-hyungchul-20260825-1430
        setupEpdUi()     // add-hyungchul-20260914-2350
        setupNoiseUi()   // add-hyungchul-20260826-1100

        findViewById<Button>(R.id.inputButton).setOnClickListener { inputPicker.launch(null) }
        findViewById<Button>(R.id.outputButton).setOnClickListener { outputPicker.launch(null) }
        startButton.setOnClickListener { startTranscription() }
    }

    // ────────────────── add-hyungchul-20260825-1430 : Silero VAD UI ──────────────────
    /**
     * 목적: VAD 스위치 / Profile 스피너 / 파라미터 입력창을 초기화하고 서로 연동시킨다.
     * 입력: 없음 (멤버 View 를 사용)
     * 출력: 없음
     * 리턴: 없음
     * 동작: 시작 상태는 "VAD OFF + Default(PC GUI 기본값) 가 채워진 상태"다.  // change-hyungchul-20260914-2130
     *       값을 직접 고치면 Profile 이 Custom 으로 자동 전환된다.
     */
    private fun setupVadUi() {
        // change-hyungchul-20260828-1000: values() 는 호출마다 배열을 새로 만든다. entries 는 불변 List 라 권장값이다.
        val profiles = VadProfile.entries                               // 표시 순서 = enum 선언 순서
        vadProfileSpinner.adapter = ArrayAdapter(                        // Profile 목록을 스피너에 채운다
            this,
            android.R.layout.simple_spinner_dropdown_item,
            profiles.map { it.displayName },
        )

        applyingVadPreset = true                                         // 초기 세팅 중에는 watcher 를 무시한다
        // change-hyungchul-20260914-2130 : ASR_SAFE → DEFAULT (PC GUI 기본값)
        vadProfileSpinner.setSelection(profiles.indexOf(VadProfile.DEFAULT))
        applyVadPreset(VadProfile.DEFAULT)                               // PC GUI 기본값을 입력창에 채운다
        applyingVadPreset = false

        vadSwitch.isChecked = false                                      // 기본값은 OFF = 기존 baseline
        setGroupEnabled(vadOptionsGroup, false)                          // OFF 면 옵션 입력을 비활성화한다
        vadSwitch.setOnCheckedChangeListener { _, checked ->
            setGroupEnabled(vadOptionsGroup, checked)
            // add-hyungchul-20260825-1620
            // START 를 눌러 preflight 까지 다 돈 뒤에 실패하면 시간이 아깝다. 켜는 즉시 알린다.
            if (checked) warnIfVadModelMissing()
        }

        vadProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingVadPreset) return                            // 코드가 고른 것이면 무시
                val profile = profiles[position.coerceIn(0, profiles.lastIndex)]
                if (profile != VadProfile.CUSTOM) applyVadPreset(profile)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingVadPreset) return                            // preset 적용 중이면 무시
                val position = vadProfileSpinner.selectedItemPosition.coerceIn(0, profiles.lastIndex)
                if (profiles[position] != VadProfile.CUSTOM) {           // 사람이 고친 값이면 Custom 으로 전환
                    applyingVadPreset = true
                    vadProfileSpinner.setSelection(profiles.indexOf(VadProfile.CUSTOM))
                    applyingVadPreset = false
                }
            }
        }

        listOf(
            vadThresholdEdit,
            vadMinSpeechEdit,
            vadMinSilenceEdit,
            vadSpeechPadEdit,
            vadMaxSpeechEdit,
            vadJoinSilenceEdit,                                          // add-hyungchul-20260914-2130
        ).forEach { it.addTextChangedListener(watcher) }
    }

    /**
     * 목적: Profile 을 고르면 그 Profile 의 추천값을 입력창에 채운다.
     * 입력: profile — 채워 넣을 Profile (CUSTOM 이면 아무것도 하지 않는다)
     * 출력: 없음 (입력창 텍스트를 바꾼다)
     * 리턴: 없음
     */
    private fun applyVadPreset(profile: VadProfile) {
        if (profile == VadProfile.CUSTOM) return                          // Custom 은 사용자 값을 유지한다
        val config = VadPresets.config(profile)
        applyingVadPreset = true
        try {
            vadThresholdEdit.setText(String.format(Locale.US, "%.2f", config.threshold))
            vadMinSpeechEdit.setText(config.minSpeechDurationMs.toString())
            vadMinSilenceEdit.setText(config.minSilenceDurationMs.toString())
            vadSpeechPadEdit.setText(config.speechPadMs.toString())
            vadMaxSpeechEdit.setText(                                     // null(Unlimited) 은 UI 에서 0 으로 표기
                config.maxSpeechDurationSec?.let { String.format(Locale.US, "%.1f", it) } ?: "0",
            )
            // add-hyungchul-20260914-2130
            vadJoinSilenceEdit.setText(config.joinSilenceMs.toString())
        } finally {
            applyingVadPreset = false
        }
    }

    /**
     * 목적: VAD 옵션 컨테이너 안의 모든 자식 View 의 enabled 상태를 한 번에 바꾼다.
     * 입력: group — 대상 ViewGroup, enabled — 활성화 여부
     * 출력: 없음
     * 리턴: 없음
     */
    private fun setGroupEnabled(group: ViewGroup, enabled: Boolean) {
        group.isEnabled = enabled
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            child.isEnabled = enabled
            if (child is ViewGroup) setGroupEnabled(child, enabled)      // 중첩 레이아웃까지 재귀 적용
        }
    }

    /**
     * 목적: START 를 누른 순간의 VAD 설정을 읽어 검증하고 snapshot 으로 만든다.
     * 입력: 없음 (입력창 값을 읽는다)
     * 출력: 없음
     * 리턴: VadSelection
     * 예외: 값이 비었거나 범위를 벗어나면 IllegalArgumentException (호출부에서 토스트로 안내한다)
     * 비고: Max Speech 에 0 을 넣으면 Unlimited(null) 로 해석한다.
     */
    private fun readVadSelection(): VadSelection {
        if (!vadSwitch.isChecked) {                                       // OFF: 기존 baseline 경로 유지
            return VadSelection(
                enabled = false,
                profile = VadProfile.DEFAULT,                             // change-hyungchul-20260914-2130
                config = VadPresets.config(VadProfile.DEFAULT),
                outputFolder = null,
            )
        }

        val profiles = VadProfile.entries   // change-hyungchul-20260828-1000
        val profile = profiles[vadProfileSpinner.selectedItemPosition.coerceIn(0, profiles.lastIndex)]

        fun requiredFloat(edit: EditText, name: String): Float =
            edit.text.toString().trim().toFloatOrNull()
                ?: throw IllegalArgumentException("$name 값을 확인하세요.")

        fun requiredInt(edit: EditText, name: String): Int =
            edit.text.toString().trim().toIntOrNull()
                ?: throw IllegalArgumentException("$name 값을 확인하세요.")

        val maxSpeech = vadMaxSpeechEdit.text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Max Speech 값을 확인하세요.")

        val config = VadConfig(                                           // VadConfig 의 init 이 범위를 재검증한다
            threshold = requiredFloat(vadThresholdEdit, "Threshold"),
            minSpeechDurationMs = requiredInt(vadMinSpeechEdit, "Min Speech"),
            minSilenceDurationMs = requiredInt(vadMinSilenceEdit, "Min Silence"),
            speechPadMs = requiredInt(vadSpeechPadEdit, "Speech Padding"),
            maxSpeechDurationSec = if (maxSpeech <= 0.0) null else maxSpeech,
            // add-hyungchul-20260914-2130 : 구간 사이 무음(ms)
            joinSilenceMs = requiredInt(vadJoinSilenceEdit, "구간 사이 무음"),
        )

        val outputFolder = when (profile) {                               // 결과를 profile 별로 분리 저장
            VadProfile.CUSTOM -> customVadFolder(config)
            else -> "vad_" + profile.folderName
        }
        return VadSelection(true, profile, config, outputFolder)
    }

    /**
     * 목적: Custom 값을 바꿔가며 여러 번 돌려도 서로 덮어쓰지 않도록 설정값을 폴더명에 녹인다.
     * 입력: config — Custom 파라미터
     * 출력: 없음
     * 리턴: "vad_custom_t50_sp250_si400_pad150_maxinf_join200" 형태의 폴더명
     * 비고: change-hyungchul-20260914-2130
     *   구간 사이 무음(join) 도 STT 입력을 바꾸므로 폴더명에 포함시켜야
     *   join 값만 다른 실행이 서로 덮어쓰지 않는다.
     */
    private fun customVadFolder(config: VadConfig): String {
        val threshold100 = (config.threshold * 100).roundToInt()          // 0.50 → 50 (폴더명에 소수점을 피한다)
        val maxText = config.maxSpeechDurationSec
            ?.let { String.format(Locale.US, "%.1f", it).replace('.', 'p') }
            ?: "inf"
        return "vad_custom_t${threshold100}_sp${config.minSpeechDurationMs}" +
                "_si${config.minSilenceDurationMs}_pad${config.speechPadMs}_max$maxText" +
                "_join${config.joinSilenceMs}"       // add-hyungchul-20260914-2130
    }

    /**
     * 목적: result.csv 의 VAD 설정 공통 컬럼을 채운다(파일마다 같은 값).
     * 입력: row — 계측 행, selection — 실행 snapshot
     * 출력: 없음 (row 를 직접 수정)
     * 리턴: 없음
     */
    private fun fillVadConfig(row: SttRow, selection: VadSelection) {
        if (!selection.enabled) {
            row.vadEnabled = 0
            row.vadProfile = "OFF"
            row.vadModel = ""
            return
        }
        val c = selection.config
        row.vadEnabled = 1
        row.vadModel = SileroVadProcessor.MODEL_LABEL
        row.vadProfile = selection.profile.displayName
        row.vadThreshold = c.threshold.toDouble()
        row.vadNegThreshold = c.negThreshold.toDouble()
        row.vadMinSpeechMs = c.minSpeechDurationMs
        row.vadMinSilenceMs = c.minSilenceDurationMs
        row.vadSpeechPadMs = c.speechPadMs
        row.vadMaxSpeechSec = c.maxSpeechDurationSec ?: -1.0              // -1 = Unlimited
        row.vadJoinSilenceMs = c.joinSilenceMs                            // add-hyungchul-20260914-2130
    }

    /**
     * add-hyungchul-20260825-1620
     * 목적: assets 에 Silero 모델 파일이 있는지 확인하고, 없으면 화면 로그와 토스트로 즉시 알린다.
     * 입력: 없음
     * 출력: 없음 (로그/토스트 출력)
     * 리턴: 파일이 있으면 true
     * 비고: 여기서 막지는 않는다. 실제 차단은 SileroVadProcessor 생성 시점에서 예외로 처리한다.
     */
    private fun warnIfVadModelMissing(): Boolean {
        val exists = runCatching {
            assets.list("")?.contains(SileroVadProcessor.MODEL_ASSET) == true
        }.getOrDefault(false)
        if (!exists) {
            val msg = "assets/${SileroVadProcessor.MODEL_ASSET} 이 없습니다. " +
                    "tools/download_models.sh 를 실행한 뒤 다시 빌드하세요."
            appendLog("[vad][경고] $msg")
            toast(msg)
        }
        return exists
    }

    // ──────────── add-hyungchul-20260914-2350 : Speech EPD UI ────────────
    /**
     * 목적: Speech EPD(무음 지점 분할) 스피너와 옵션 입력창을 초기화한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     * 동작: 기본값은 OFF 다(기존 baseline 과 100% 동일하게 시작한다).
     *       OFF 를 고르면 옵션 입력을 비활성화한다.
     *       B안(긴 무음)이 아닐 때는 "긴 무음(ms)" 칸을 비활성화한다(값을 바꿔도 결과가 안 바뀌므로).
     */
    private fun setupEpdUi() {
        val modes = EpdMode.entries                                      // 표시 순서 = enum 선언 순서
        epdModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.displayName },
        )
        epdModeSpinner.setSelection(modes.indexOf(EpdMode.OFF))          // 기본은 OFF
        applyEpdEnabled(EpdMode.OFF)

        epdModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyEpdEnabled(modes[position.coerceIn(0, modes.lastIndex)])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * 목적: 고른 EPD 방식에 맞춰 입력창 활성/비활성을 정리하고 안내를 남긴다.
     * 입력: mode — 고른 방식
     * 출력: 없음 (View 상태를 바꾸고 로그를 남긴다)
     * 리턴: 없음
     */
    private fun applyEpdEnabled(mode: EpdMode) {
        setGroupEnabled(epdOptionsGroup, mode != EpdMode.OFF)
        // 긴 무음(ms) 은 B안에서만 의미가 있다.
        epdSilenceEdit.isEnabled = (mode == EpdMode.LONG_SILENCE)
        when (mode) {
            EpdMode.OFF -> Unit
            EpdMode.SPLIT_SESSION -> appendLog(
                "[epd] A안 — 조각마다 STT 세션을 따로 돌린다. 61초 결함을 확실히 피하지만 " +
                    "세션 수가 늘어 엔진 먹통 노출도 함께 늘어난다.",
            )
            EpdMode.LONG_SILENCE -> appendLog(
                "[epd] B안 — 세션은 1개이고 절단 지점의 무음만 길게 늘린다. 문맥은 유지되지만 " +
                    "ML Kit 가 그 무음에서 반드시 끊어준다는 보장은 없다.",
            )
        }
    }

    /**
     * 목적: START 를 누른 순간의 EPD 설정을 읽어 검증하고 snapshot 으로 만든다.
     * 입력: 없음 (입력창 값을 읽는다)
     * 출력: 없음
     * 리턴: EpdSelection
     * 예외: 값이 비었거나 범위를 벗어나면 IllegalArgumentException
     */
    private fun readEpdSelection(): EpdSelection {
        val modes = EpdMode.entries
        val mode = modes[epdModeSpinner.selectedItemPosition.coerceIn(0, modes.lastIndex)]
        if (mode == EpdMode.OFF) {
            return EpdSelection(EpdConfig(EpdMode.OFF), null)            // 폴더명을 바꾸지 않는다
        }

        val target = epdTargetEdit.text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("EPD 목표 길이 값을 확인하세요.")
        val max = epdMaxEdit.text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("EPD 최대 길이 값을 확인하세요.")
        val silence = epdSilenceEdit.text.toString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("EPD 긴 무음 값을 확인하세요.")

        val config = EpdConfig(                                          // EpdConfig 의 init 이 범위를 재검증한다
            mode = mode,
            targetSec = target,
            maxSec = max,
            longSilenceMs = silence,
        )
        return EpdSelection(config, customEpdFolder(config))
    }

    /**
     * 목적: EPD 설정이 다른 실행끼리 결과가 덮어써지지 않도록 설정값을 폴더명에 녹인다.
     * 입력: config — EPD 파라미터
     * 출력: 없음
     * 리턴: "epd_split_t45_m55" / "epd_gap_t45_m55_s1000" 형태
     */
    private fun customEpdFolder(config: EpdConfig): String {
        val t = String.format(Locale.US, "%.0f", config.targetSec)       // 45.0 → "45"
        val m = String.format(Locale.US, "%.0f", config.maxSec)
        val base = "epd_${config.mode.folderName}_t${t}_m$m"
        // 긴 무음은 B안에서만 결과를 바꾸므로 B안일 때만 폴더명에 넣는다.
        return if (config.mode == EpdMode.LONG_SILENCE) base + "_s${config.longSilenceMs}" else base
    }

    /**
     * 목적: result.csv 의 EPD 설정 공통 컬럼을 채운다(파일마다 같은 값).
     * 입력: row — 계측 행, selection — 실행 snapshot
     * 출력: 없음 (row 를 직접 수정)
     * 리턴: 없음
     */
    private fun fillEpdConfig(row: SttRow, selection: EpdSelection) {
        row.epdMode = selection.config.mode.name
    }

    /** 로그/run_meta 에 남길 VAD 설정 한 줄 요약. */
    private fun vadConfigText(c: VadConfig): String =
        "threshold=${c.threshold}, neg=${c.negThreshold}, minSpeech=${c.minSpeechDurationMs}ms, " +
                "minSilence=${c.minSilenceDurationMs}ms, pad=${c.speechPadMs}ms, " +
                "maxSpeech=${c.maxSpeechDurationSec?.toString() ?: "Unlimited"}, " +
                "joinSilence=${c.joinSilenceMs}ms"   // add-hyungchul-20260914-2130

    // ──────────── add-hyungchul-20260826-1100 : Noise Reduction UI ────────────
    /**
     * 목적: 노이즈 저감 알고리즘 스피너와 옵션 입력창을 초기화한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     * 동작:
     *  - 알고리즘을 바꾸면 그 알고리즘의 권장값으로 옵션을 다시 채우고,
     *    쓰지 않는 옵션은 비활성화한다(무슨 값을 넣어도 결과가 안 바뀌는 칸을 만지지 않도록).
     *  - ONNX 계열을 고르면 assets 에 모델이 있는지 즉시 확인해 알려 준다.
     *  - 파일 입력에 적용 불가한 항목을 고르면 사유를 바로 로그에 띄운다.
     */
    private fun setupNoiseUi() {
        val algos = NoiseAlgorithm.entries   // change-hyungchul-20260828-1000
        nsAlgoSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            algos.map { it.displayName },
        )
        nsVariantSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            DPDFNET_VARIANT_LABELS,
        )

        applyingNsPreset = true
        nsAlgoSpinner.setSelection(algos.indexOf(NoiseAlgorithm.NONE))   // 기본은 기존 baseline
        applyNoisePreset(NoiseAlgorithm.NONE)
        applyingNsPreset = false

        nsAlgoSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingNsPreset) return
                val algo = algos[position.coerceIn(0, algos.lastIndex)]
                applyNoisePreset(algo)
                when {
                    !algo.runnable -> {
                        appendLog("[ns][사용 불가] ${algo.displayName}")
                        appendLog("  → ${algo.unsupportedReason}")
                        toast("이 알고리즘은 파일 입력에 적용할 수 없습니다. 로그를 확인하세요.")
                    }

                    algo.kind == NoiseAlgorithmKind.ONNX -> warnIfNoiseModelMissing(algo)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * 목적: 알고리즘을 고르면 권장 초기값을 채우고, 쓰지 않는 옵션은 비활성화한다.
     * 입력: algo
     * 출력: 없음 (입력창 값/활성 상태와 안내 문구를 바꾼다)
     * 리턴: 없음
     */
    private fun applyNoisePreset(algo: NoiseAlgorithm) {
        val cfg = NoiseReduceConfig.defaults(algo)
        applyingNsPreset = true
        try {
            nsAttenLimitEdit.setText(String.format(Locale.US, "%.0f", cfg.attenuationLimitDb))
            nsThreadsEdit.setText(cfg.numThreads.toString())
            nsMinGainEdit.setText(String.format(Locale.US, "%.0f", cfg.minGainDb))
            nsAlphaEdit.setText(
                if (algo == NoiseAlgorithm.SPECTRAL_SUB) {
                    String.format(Locale.US, "%.2f", cfg.overSubtraction)
                } else {
                    String.format(Locale.US, "%.2f", cfg.ddAlpha)
                },
            )
            nsBetaEdit.setText(String.format(Locale.US, "%.3f", cfg.spectralFloor))
            nsQuantileEdit.setText(cfg.noiseQuantilePct.toString())
            nsVariantSpinner.setSelection(
                DPDFNET_VARIANTS.indexOf(cfg.dpdfnetVariant).coerceAtLeast(0),
            )
        } finally {
            applyingNsPreset = false
        }

        // 알고리즘별로 실제 쓰이는 옵션만 켠다.
        val onnx = algo.kind == NoiseAlgorithmKind.ONNX
        val dsp = algo.kind == NoiseAlgorithmKind.DSP
        val active = onnx || dsp
        setGroupEnabled(nsOptionsGroup, active)
        nsVariantSpinner.isEnabled = algo == NoiseAlgorithm.DPDFNET
        nsThreadsEdit.isEnabled = onnx
        nsAttenLimitEdit.isEnabled = active
        nsMinGainEdit.isEnabled = dsp || algo == NoiseAlgorithm.NSNET2
        nsAlphaEdit.isEnabled = dsp
        nsBetaEdit.isEnabled = algo == NoiseAlgorithm.SPECTRAL_SUB
        nsQuantileEdit.isEnabled = dsp

        nsHint.text = when (algo.kind) {
            NoiseAlgorithmKind.NONE ->
                "전처리를 하지 않는다. PCM 을 1 byte 도 바꾸지 않고 그대로 STT 에 넣는다(기존 baseline)."

            NoiseAlgorithmKind.UNSUPPORTED ->
                "이 알고리즘은 이 앱의 파일 입력 방식에 적용할 수 없다.\n${algo.unsupportedReason}"

            NoiseAlgorithmKind.ONNX ->
                "assets/${algo.modelAsset} 가 필요하다. Attenuation Limit 은 '최대 몇 dB 까지만 줄일지'를 " +
                    "정해 과도한 제거로 WER 이 나빠지는 것을 막는 안전장치다(0 = 무제한)."

            NoiseAlgorithmKind.DSP ->
                "모델 파일이 필요 없다. 잡음 스펙트럼은 파일 전체에서 가장 조용한 하위 N% 프레임으로 추정한다. " +
                    "비정상 소음(카페·음악)에는 딥러닝 계열보다 약하지만, 다운로드 없이 바로 비교할 수 있다."
        }
    }

    /**
     * 목적: ONNX 계열을 고른 즉시 assets 에 모델이 있는지 확인해 알린다.
     * 입력: algo
     * 리턴: 파일이 있으면 true
     * 비고: 여기서 막지는 않는다. 실제 차단은 NoiseReducerFactory 생성 시점의 예외로 처리한다.
     */
    private fun warnIfNoiseModelMissing(algo: NoiseAlgorithm): Boolean {
        val asset = algo.modelAsset ?: return true
        val exists = runCatching { assets.list("")?.contains(asset) == true }.getOrDefault(false)
        if (!exists) {
            val msg = "assets/$asset 이 없습니다. tools/download_models.sh 를 실행한 뒤 다시 빌드하세요."
            appendLog("[ns][경고] $msg")
            toast(msg)
        }
        return exists
    }

    /**
     * 목적: START 를 누른 순간의 노이즈 저감 설정을 읽어 검증한다.
     * 입력: 없음 / 출력: 없음
     * 리턴: NoiseReduceConfig
     * 예외: 값이 잘못됐거나 적용 불가 알고리즘이면 IllegalArgumentException
     */
    private fun readNoiseConfig(): NoiseReduceConfig {
        val algos = NoiseAlgorithm.entries   // change-hyungchul-20260828-1000
        val algo = algos[nsAlgoSpinner.selectedItemPosition.coerceIn(0, algos.lastIndex)]
        if (algo == NoiseAlgorithm.NONE) return NoiseReduceConfig(NoiseAlgorithm.NONE)
        if (!algo.runnable) {
            throw IllegalArgumentException(
                "${algo.displayName}\n${algo.unsupportedReason}",
            )
        }

        fun num(edit: EditText, name: String): Double =
            edit.text.toString().trim().toDoubleOrNull()
                ?: throw IllegalArgumentException("$name 값을 확인하세요.")

        val variantIndex = nsVariantSpinner.selectedItemPosition.coerceIn(0, DPDFNET_VARIANTS.lastIndex)
        return NoiseReduceConfig(
            algorithm = algo,
            attenuationLimitDb = num(nsAttenLimitEdit, "Attenuation Limit"),
            numThreads = num(nsThreadsEdit, "Threads").toInt(),
            dpdfnetVariant = DPDFNET_VARIANTS[variantIndex],
            minGainDb = num(nsMinGainEdit, "Min Gain"),
            ddAlpha = if (algo == NoiseAlgorithm.SPECTRAL_SUB) 0.98 else num(nsAlphaEdit, "alpha"),
            overSubtraction = if (algo == NoiseAlgorithm.SPECTRAL_SUB) num(nsAlphaEdit, "alpha") else 4.0,
            spectralFloor = num(nsBetaEdit, "beta"),
            noiseQuantilePct = num(nsQuantileEdit, "Noise Quantile").toInt(),
        )
    }

    /**
     * 목적: result.csv 의 노이즈 저감 설정 공통 컬럼을 채운다(파일마다 같은 값).
     * 입력: row, cfg, reducer(라벨을 얻기 위해, 없으면 null)
     * 출력: 없음 (row 를 직접 수정)
     * 리턴: 없음
     */
    private fun fillNoiseConfig(row: SttRow, cfg: NoiseReduceConfig, reducer: NoiseReducer?) {
        if (cfg.algorithm == NoiseAlgorithm.NONE) {
            row.nsEnabled = 0
            row.nsAlgorithm = "NONE"
            return
        }
        row.nsEnabled = 1
        row.nsAlgorithm = cfg.algorithm.name
        row.nsLabel = reducer?.label ?: (cfg.algorithm.modelAsset ?: "")
        row.nsConfig = cfg.summary()
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
        val modelFolder = modelConfig.folder

        // change(add)-hyungchul-20260820
        val feed = feeds[feedSpinner.selectedItemPosition.coerceIn(0, feeds.size - 1)]

        // add-hyungchul-20260825-1430
        // 값이 잘못돼 있으면 배치를 시작하기 전에 알린다(실행 중간에 죽는 것보다 낫다).
        val vadSelection = try {
            readVadSelection()
        } catch (e: Exception) {
            toast("VAD 설정 오류: ${describe(e)}"); return
        }

        // add-hyungchul-20260914-2350
        val epdSelection = try {
            readEpdSelection()
        } catch (e: Exception) {
            appendLog("[epd][설정 오류] ${describe(e)}")
            toast("Speech EPD 설정 오류: ${describe(e)}"); return
        }

        // add-hyungchul-20260826-1100
        val noiseConfig = try {
            readNoiseConfig()
        } catch (e: Exception) {
            appendLog("[ns][설정 오류] ${describe(e)}")
            toast("노이즈 저감 설정 오류: ${describe(e)}"); return
        }

        // 결과 폴더 조합: output/google/<모드>/[ns_xxx/][vad_yyy/]...
        // baseline(둘 다 미사용)이면 빈 목록이라 기존 경로를 그대로 쓴다.
        val variantSegments = buildList {
            noiseConfig.outputFolder()?.let { add(it) }
            vadSelection.outputFolder?.let { add(it) }
            epdSelection.outputFolder?.let { add(it) }   // add-hyungchul-20260914-2350
        }

        running = true
        startButton.isEnabled = false
        logView.text = ""
        updateProgress(0.0)
        toast(getString(R.string.msg_stt_started))

        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            val csv = StringBuilder(SttTelemetry.CSV_HEADER)
            var abortedByWedge = false                    // add-hyungchul-20260914-2350
            var vadProcessor: SileroVadProcessor? = null   // add-hyungchul-20260825-1430
            var noiseReducer: NoiseReducer? = null         // add-hyungchul-20260826-1100
            try {
                val inputRoot = DocumentFile.fromTreeUri(this@MainActivity, inUri)
                val outputRoot = DocumentFile.fromTreeUri(this@MainActivity, outUri)
                if (inputRoot == null || outputRoot == null) {
                    toast(getString(R.string.msg_folder_open_fail)); return@launch
                }

                val items = withContext(Dispatchers.IO) {
                    val acc = mutableListOf<AudioItem>()
                    collectAudio(inputRoot, emptyList(), acc)
                    acc.sortedBy { displayRel(it) }
                }
                if (items.isEmpty()) {
                    toast(getString(R.string.msg_no_audio)); return@launch
                }
                appendLog(getString(R.string.log_found, items.size))
                appendLog(getString(R.string.log_model, modelFolder))
                appendLog("[feed] ${feed.name} (chunk delay ${feed.delayMs} ms)")
                // add-hyungchul-20260825-1430
                appendLog(
                    if (vadSelection.enabled) {
                        "[vad] ON / ${vadSelection.profile.displayName} / ${vadConfigText(vadSelection.config)}"
                    } else {
                        "[vad] OFF — AudioDecoder 가 만든 PCM 을 그대로 STT 에 넣는다(기존 baseline)."
                    },
                )
                // add-hyungchul-20260826-1100
                appendLog(
                    if (noiseConfig.algorithm == NoiseAlgorithm.NONE) {
                        "[ns] 사용 안 함 — 디코딩한 PCM 을 그대로 다음 단계로 넘긴다(기존 baseline)."
                    } else {
                        "[ns] ${noiseConfig.algorithm.displayName} / ${noiseConfig.summary()}"
                    },
                )
                if (modelConfig.engine == SttEngine.ML_KIT && feed.delayMs < 100L) {
                    // change-hyungchul-20260915-1400 : 경고 문구를 공식 문서 근거로 강화한다.
                    //   ML Kit GenAI Speech Recognition 공식 문서 원문:
                    //     "Data must be provided to the file descriptor at a real-time rate
                    //      (e.g., yielding 16,000 samples, or approximately 32 KB, every second).
                    //      Standard file-backed descriptors reading at full speed are unsupported."
                    //   즉 REALTIME 이 아닌 모든 설정은 "지원되지 않는 사용법" 이다.
                    //   실측에서도 0 ms 는 전량 AUDIO_BUFFER_OVERFLOW, 15~20 ms 에서는
                    //   엔진이 파이프를 안 읽고 멈추는 현상(먹통)이 반복 관측되었다.
                    appendLog(
                        "[warn][중요] ML Kit 공식 문서는 '실시간 속도'(초당 약 32 KB) 공급을 요구하며 " +
                            "그보다 빠른 공급은 '지원되지 않음' 으로 명시되어 있다. " +
                            "${feed.name}(실시간 ×${"%.1f".format(Locale.US, 100.0 / feed.delayMs)}) 는 " +
                            "엔진이 도중에 응답을 멈추는 원인이 될 수 있다.",
                    )
                    appendLog(
                        "[warn] WER/CER 을 확정하는 기준 실행은 REALTIME 으로 돌릴 것. " +
                            "빠른 공급은 '속도 한계를 재는 실험' 으로만 쓸 것.",
                    )
                }

                // 실행 전 1회 가용성 점검 — 어떤 엔진이 실제로 쓰이는지 로그와 run_meta 에 남긴다.
                val preflightText = preflight(modelConfig, locale)

                // add-hyungchul-20260914-2350
                // preflight 는 "모델이 있는가" 만 본다. 엔진이 먹통이어도 AVAILABLE 로 나온다.
                // 그래서 1초짜리 무음을 실제로 넣어 보고 응답이 오는지까지 확인한다.
                sttSessionCount = 0                       // add-hyungchul-20260915-2200 : 배치마다 초기화
                val warmUpStatus = warmUpEngine(modelConfig, locale)
                if (warmUpStatus == "OK") {
                    appendLog("[warmup] 엔진 응답 확인 — 배치를 시작한다.")
                } else {
                    appendLog(
                        "[warmup][중단] 1초짜리 무음을 넣었는데 ${WARMUP_TIMEOUT_MS / 1000}초 안에 응답이 없다. " +
                            "인식 엔진이 먹통인 상태라 지금 돌리면 파일마다 타임아웃까지 기다리다 끝난다.",
                    )
                    appendLog(
                        "[warmup] 조치: 설정 → 애플리케이션 → '음성 검색'/'Google 음성 서비스'(com.google.android.tts) " +
                            "강제 종료 후 다시 실행할 것. 효과가 없으면 재부팅한 뒤 다시 START 를 누를 것.",
                    )
                    toast("인식 엔진이 응답하지 않아 배치를 시작하지 않았습니다.")
                    // add-hyungchul-20260916-1800
                    // 강제 중지 버튼이 있는 화면으로 바로 보내 준다(직접 죽일 수는 없다).
                    appendLog("[warmup] '강제 중지' 버튼이 있는 앱 정보 화면을 띄운다.")
                    openSpeechServiceSettings()
                    return@launch
                }

                // add-hyungchul-20260826-1100
                // 노이즈 저감기도 배치 전체에서 1회만 만든다(모델 로딩 시간이 파일별 계측에 섞이지 않게).
                if (noiseConfig.algorithm != NoiseAlgorithm.NONE) {
                    val reducer = withContext(Dispatchers.Default) {
                        NoiseReducerFactory.create(applicationContext, noiseConfig)
                    }
                    noiseReducer = reducer
                    if (reducer is OnnxNoiseReducerBase) {
                        appendLog(
                            "[ns] model=${reducer.label}, ORT=${reducer.runtimeVersion}, " +
                                "sha256=${reducer.modelSha256.take(16)}...",
                        )
                    } else {
                        appendLog("[ns] impl=${reducer.label} (모델 파일 없이 동작)")
                    }
                }

                // add-hyungchul-20260825-1430
                // ONNX 세션은 배치 전체에서 1회만 만든다(파일마다 만들면 로딩 시간이 계측에 섞인다).
                // 파일 사이의 상태 오염은 process() 안에서 state/context 를 reset 해서 막는다.
                if (vadSelection.enabled) {
                    val processor = withContext(Dispatchers.Default) {
                        SileroVadProcessor(applicationContext)
                    }
                    vadProcessor = processor
                    appendLog(
                        "[vad] model=${SileroVadProcessor.MODEL_LABEL}, " +
                                "ORT=${processor.runtimeVersion}, " +
                                "sha256=${processor.modelSha256.take(16)}...",
                    )
                }

                // change(add)-hyungchul-20260820 — 실행 환경 요약을 먼저 저장해 둔다.
                withContext(Dispatchers.IO) {
                    runCatching {
                        // change-hyungchul-20260825-1430: VAD ON 이면 vad_<profile> 하위에 따로 남긴다.
                        val dir = ensureRunDir(outputRoot, modelFolder, variantSegments)
                        // add-hyungchul-20260825-1430
                        // VAD OFF 면 설정 컬럼을 빈 칸으로 남긴다(0 과 "미사용"을 구분하기 위함).
                        val cfg = vadSelection.config
                        val on = vadSelection.enabled
                        val vadProfileText = if (on) vadSelection.profile.displayName else "OFF"
                        val vadModelText = if (on) SileroVadProcessor.MODEL_LABEL else ""
                        val vadThresholdText = if (on) cfg.threshold.toString() else ""
                        val vadNegThresholdText = if (on) cfg.negThreshold.toString() else ""
                        val vadMinSpeechText = if (on) cfg.minSpeechDurationMs.toString() else ""
                        val vadMinSilenceText = if (on) cfg.minSilenceDurationMs.toString() else ""
                        val vadSpeechPadText = if (on) cfg.speechPadMs.toString() else ""
                        val vadMaxSpeechText =
                            if (on) (cfg.maxSpeechDurationSec?.toString() ?: "Unlimited") else ""
                        // add-hyungchul-20260914-2130
                        val vadJoinSilenceText = if (on) cfg.joinSilenceMs.toString() else ""
                        // add-hyungchul-20260826-1100
                        val nsOn = noiseConfig.algorithm != NoiseAlgorithm.NONE
                        val nsReducer = noiseReducer
                        val nsModelSha =
                            if (nsReducer is OnnxNoiseReducerBase) nsReducer.modelSha256 else ""
                        val meta = SttTelemetry.runMetaCsv(
                            applicationContext,
                            linkedMapOf(
                                "algo" to algoName,
                                "mode" to modelFolder,
                                "engine" to modelConfig.engine.name,
                                "execution_declared" to executionLabel(modelConfig),
                                "lang" to locale.toLanguageTag(),
                                "feed_mode" to feed.name,
                                "feed_delay_ms" to feed.delayMs.toString(),
                                "file_count" to items.size.toString(),
                                "preflight" to preflightText,
                                // add-hyungchul-20260825-1430
                                "vad_enabled" to on.toString(),
                                "vad_profile" to vadProfileText,
                                "vad_model" to vadModelText,
                                "vad_threshold" to vadThresholdText,
                                "vad_neg_threshold" to vadNegThresholdText,
                                "vad_min_speech_ms" to vadMinSpeechText,
                                "vad_min_silence_ms" to vadMinSilenceText,
                                "vad_speech_pad_ms" to vadSpeechPadText,
                                "vad_max_speech_sec" to vadMaxSpeechText,
                                // add-hyungchul-20260914-2130
                                "vad_join_silence_ms" to vadJoinSilenceText,
                                // add-hyungchul-20260914-2350
                                "epd_mode" to epdSelection.config.mode.name,
                                "epd_config" to epdSelection.config.summary(),
                                "engine_warmup" to warmUpStatus,
                                "onnxruntime_version" to (vadProcessor?.runtimeVersion ?: ""),
                                "silero_model_sha256" to (vadProcessor?.modelSha256 ?: ""),
                                // add-hyungchul-20260826-1100
                                "ns_enabled" to nsOn.toString(),
                                "ns_algorithm" to if (nsOn) noiseConfig.algorithm.name else "NONE",
                                "ns_label" to (nsReducer?.label ?: ""),
                                "ns_config" to if (nsOn) noiseConfig.summary() else "",
                                "ns_model_sha256" to nsModelSha,
                                "pipeline_order" to "decode -> noise_reduction -> vad -> epd -> stt",
                                "output_variant" to
                                    (if (variantSegments.isEmpty()) "baseline" else variantSegments.joinToString("/")),
                            ),
                        )
                        writeBytes(
                            dir, "run_meta.csv",
                            SttTelemetry.BOM + meta.toByteArray(Charsets.UTF_8), "text/csv",
                        )
                    }
                }

                val totalUnits = items.size
                var completedUnits = 0
                // add-hyungchul-20260914-2350 : 엔진 먹통 감지용 연속 타임아웃 카운터
                var consecutiveTimeouts = 0

                // add-hyungchul-20260915-2200 : 세션 예산 사전 점검
                // EPD A안은 파일 하나를 2조각으로 나누므로 세션을 2배 쓴다.
                // 실측 한도가 약 35세션이므로, 시작 전에 넘길지 말지를 알려 준다.
                val projectedSessions = if (epdSelection.config.mode == EpdMode.SPLIT_SESSION) {
                    items.size * 2 + 1        // 조각 2개 가정 + warm-up. 정확치는 파일 길이에 달렸다.
                } else {
                    items.size + 1
                }
                appendLog(
                    "[quota] 예상 세션 수 약 ${projectedSessions}개 / 실측 한도 약 " +
                        "${QUOTA_BUDGET_SESSIONS}개 (com.google.android.tts 의 SODA 세션 한도).",
                )
                if (projectedSessions > QUOTA_BUDGET_SESSIONS) {
                    appendLog(
                        "[quota][경고] 한도를 넘길 것으로 보인다. 중간부터 응답이 멈출 수 있다. " +
                            "대안: (1) EPD 를 B안(세션 1개)으로 바꾼다 " +
                            "(2) 입력 폴더를 나눠 두 번에 돌린다 " +
                            "(3) 그대로 두면 막힐 때마다 ${QUOTA_COOLDOWN_MS / 1000}초씩 쉬었다가 재시도한다(느리지만 완주 가능).",
                    )
                }

                for ((index, item) in items.withIndex()) {
                    val relName = displayRel(item)

                    // ── 파일 1건의 계측 행 준비 ──
                    val row = SttRow(
                        path = relName,
                        file = item.nameNoExt,
                        engine = modelConfig.engine.name,
                        mode = modelFolder,
                        execution = executionLabel(modelConfig),
                        lang = locale.toLanguageTag(),
                        feedMode = feed.name,
                        feedDelayMs = feed.delayMs,
                        srcBytes = item.sizeBytes,
                        netType = SttTelemetry.networkType(applicationContext),
                        airplane = SttTelemetry.airplaneMode(applicationContext),
                        startedAt = SttTelemetry.isoNow(),
                    )
                    fillVadConfig(row, vadSelection)                       // add-hyungchul-20260825-1430
                    fillEpdConfig(row, epdSelection)                       // add-hyungchul-20260914-2350
                    fillNoiseConfig(row, noiseConfig, noiseReducer)         // add-hyungchul-20260826-1100
                    val snap = SttResourceSnapshot.take(applicationContext)

                    // ── 1) 디코딩 ──
                    val decoded = try {
                        withContext(Dispatchers.IO) {
                            AudioDecoder.decodeDetailed(applicationContext, item.uri)
                        }
                    } catch (e: Exception) {
                        val msg = describe(e)
                        Log.e(logTag, "decode failed: $relName", e)
                        withContext(Dispatchers.IO) {
                            saveResult(
                                outputRoot, modelFolder, variantSegments, item,
                                "[ERROR] 디코딩 실패: $msg",
                            )
                        }
                        row.status = "ERROR"
                        row.error = "decode: $msg"
                        snap.fillDelta(applicationContext, row)
                        csv.append(row.toCsvLine())
                        fail++
                        completedUnits++
                        updateProgress(completedUnits.toDouble() / totalUnits)
                        appendLog(getString(R.string.log_decode_fail, relName, msg))
                        continue
                    }

                    row.decodeMs = decoded.decodeMs
                    row.audioSec = decoded.durationSec
                    row.srcMime = decoded.srcMime
                    row.srcRate = decoded.srcRate
                    row.srcCh = decoded.srcChannels
                    row.vadOriginalSec = decoded.durationSec   // add-hyungchul-20260825-1430

                    // ── add-hyungchul-20260826-1100 : 2) Noise Reduction (선택) ──
                    // stagePcm 은 노이즈 저감까지 끝난 PCM 이다. 미사용이면 decoded.pcm 과 같은 객체다.
                    // 길이는 바뀌지 않으므로 audio_sec 은 그대로 유효하다.
                    var stagePcm = decoded.pcm
                    if (noiseConfig.algorithm != NoiseAlgorithm.NONE) {
                        try {
                            val reducer = noiseReducer
                                ?: throw IllegalStateException("노이즈 저감기가 초기화되지 않았습니다.")
                            val ns = withContext(Dispatchers.Default) {
                                reducer.process(decoded.pcm, noiseConfig)
                            }
                            stagePcm = ns.pcm
                            row.nsFrames = ns.frames
                            row.nsPrepareMs = ns.prepareMs
                            row.nsStftMs = ns.stftMs
                            row.nsInferMs = ns.inferMs
                            row.nsIstftMs = ns.istftMs
                            row.nsPostMs = ns.postMs
                            row.nsTotalMs = ns.totalMs
                            row.nsInferPerFrameUs = ns.inferPerFrameUs
                            row.nsInRmsDb = ns.inRmsDb
                            row.nsOutRmsDb = ns.outRmsDb
                            row.nsReductionDb = ns.reductionDb
                            row.nsPeak = ns.peak
                            row.nsNote = ns.note

                            appendLog(
                                "[ns] $relName : ${"%.1f".format(Locale.US, ns.inRmsDb)}dB → " +
                                        "${"%.1f".format(Locale.US, ns.outRmsDb)}dB " +
                                        "(감쇠 ${"%.1f".format(Locale.US, ns.reductionDb)}dB, " +
                                        "frames=${ns.frames}, ${ns.totalMs}ms " +
                                        "[stft ${ns.stftMs} / infer ${ns.inferMs} / istft ${ns.istftMs}])",
                            )
                            if (ns.peak > 0.999) {
                                appendLog("[ns][경고] 출력이 클리핑되었다(peak=${"%.3f".format(Locale.US, ns.peak)}).")
                            }
                        } catch (e: Exception) {
                            // ★ VAD 와 같은 원칙: 실패해도 조용히 원음으로 되돌리지 않는다.
                            //   되돌리면 CSV 에는 ns_enabled=1 인데 실제로는 처리 안 된 행이 섞여 비교가 깨진다.
                            val msg = describe(e)
                            Log.e(logTag, "noise reduction failed: $relName", e)
                            row.status = "ERROR"
                            row.error = "NS: $msg"
                            withContext(Dispatchers.IO) {
                                saveResult(
                                    outputRoot, modelFolder, variantSegments, item,
                                    "[ERROR] 노이즈 저감 실패: $msg",
                                )
                            }
                            snap.fillDelta(applicationContext, row)
                            csv.append(row.toCsvLine())
                            fail++
                            completedUnits++
                            updateProgress(completedUnits.toDouble() / totalUnits)
                            appendLog("[노이즈 저감 실패] $relName — $msg")
                            continue
                        }
                    }

                    // ── add-hyungchul-20260825-1430 : 3) Silero VAD (선택) ──
                    // sttPcm 이 실제로 STT 에 들어가는 PCM 이다. OFF 면 stagePcm 과 완전히 같은 객체다.
                    // ★ VAD 는 "노이즈 저감을 끝낸" 신호를 본다. 잡음을 먼저 없애야 음성 구간 판정이 정확해진다
                    //   (사용자 조사 문서 5장의 권장 순서와 동일).
                    var sttPcm = stagePcm
                    // add-hyungchul-20260914-2350
                    // VAD 가 알려주는 "안전한 절단 후보". VAD OFF 면 비어 있고,
                    // 그때 EPD 는 32 ms 역방향 탐색만으로 절단 지점을 찾는다.
                    var vadSeams: List<Int> = emptyList()
                    if (vadSelection.enabled) {
                        try {
                            val processor = vadProcessor
                                ?: throw IllegalStateException("Silero VAD processor 가 초기화되지 않았습니다.")
                            val vadResult = withContext(Dispatchers.Default) {
                                processor.process(stagePcm, vadSelection.config)
                            }
                            sttPcm = vadResult.pcm
                            vadSeams = vadResult.seams        // add-hyungchul-20260914-2350
                            row.vadProcessMs = vadResult.processMs
                            row.vadOriginalSec = vadResult.originalSec
                            row.vadOutputSec = vadResult.outputSec
                            row.vadDetectedSpeechSec = vadResult.detectedSpeechSec
                            row.vadRemovedSec = vadResult.removedSec
                            row.vadRemovedRatio = vadResult.removedRatio
                            row.vadSegmentCount = vadResult.segments.size
                            // add-hyungchul-20260914-2130 : 구간 사이에 끼워 넣은 무음 길이(초)
                            row.vadJoinSilenceSec = vadResult.joinSilenceSec

                            appendLog(
                                "[vad] $relName : ${"%.2f".format(Locale.US, vadResult.originalSec)}s → " +
                                        "${"%.2f".format(Locale.US, vadResult.outputSec)}s " +
                                        "(제거 ${"%.1f".format(Locale.US, vadResult.removedRatio)}%, " +
                                        "segments=${vadResult.segments.size}, ${vadResult.processMs}ms)",
                            )

                            // speech 가 하나도 없으면 STT 를 부르지 않는다.
                            // (빈 PCM 을 넣으면 엔진 오류가 나서 "VAD 가 다 지웠다"는 사실이 가려진다)
                            if (vadResult.segments.isEmpty() || sttPcm.isEmpty()) {
                                row.status = "NO_SPEECH_BY_VAD"
                                row.text = ""
                                row.chars = 0
                                row.words = 0
                                withContext(Dispatchers.IO) {
                                    saveResult(
                                        outputRoot, modelFolder, variantSegments, item, "",
                                    )
                                }
                                snap.fillDelta(applicationContext, row)
                                csv.append(row.toCsvLine())
                                ok++
                                completedUnits++
                                updateProgress(completedUnits.toDouble() / totalUnits)
                                appendLog("[$modelFolder] VAD 가 speech 를 찾지 못함: $relName")
                                if ((index + 1) % CSV_FLUSH_EVERY == 0) {
                                    withContext(Dispatchers.IO) {
                                        runCatching {
                                            writeCsv(
                                                outputRoot, modelFolder,
                                                variantSegments, csv.toString(),
                                            )
                                        }
                                    }
                                }
                                continue
                            }
                        } catch (e: Exception) {
                            // ★ 벤치마크 오염 방지: VAD 가 실패해도 조용히 baseline 으로 되돌리지 않는다.
                            //   되돌리면 CSV 에는 VAD ON 으로 남는데 실제로는 OFF 로 돈 행이 섞여 비교가 깨진다.
                            val msg = describe(e)
                            Log.e(logTag, "VAD failed: $relName", e)
                            row.status = "ERROR"
                            row.error = "VAD: $msg"
                            withContext(Dispatchers.IO) {
                                saveResult(
                                    outputRoot, modelFolder, variantSegments, item,
                                    "[ERROR] VAD 실패: $msg",
                                )
                            }
                            snap.fillDelta(applicationContext, row)
                            csv.append(row.toCsvLine())
                            fail++
                            completedUnits++
                            updateProgress(completedUnits.toDouble() / totalUnits)
                            appendLog("[VAD 실패] $relName — $msg")
                            continue
                        }
                    } else {
                        // VAD OFF: 앞 단계(노이즈 저감까지)의 PCM 을 단 1 byte 도 더 바꾸지 않는다.
                        row.vadProcessMs = 0
                        row.vadOutputSec = decoded.durationSec
                        row.vadDetectedSpeechSec = -1.0   // 미측정(-1)과 0.0 을 구분한다
                        row.vadRemovedSec = 0.0
                        row.vadRemovedRatio = 0.0
                        row.vadSegmentCount = 0
                    }

                    // ── add-hyungchul-20260914-2350 : 3.5) Speech EPD (선택) ──
                    // Google on-device 는 한 세션 오디오가 약 61초를 넘고 segments 가 1개로 잡히면
                    // 전사문의 앞부분을 통째로 버린다. 그래서 무음 지점에서 미리 잘라 둔다.
                    // sttChunks 가 실제로 STT 에 들어갈 조각 목록이다. EPD OFF 면 1개짜리 목록이다.
                    var sttChunks: List<ByteArray> = listOf(sttPcm)
                    if (epdSelection.enabled && sttPcm.isNotEmpty()) {
                        val epdCfg = epdSelection.config
                        // 어디를 자를지 계획만 먼저 세운다(PCM 은 아직 건드리지 않는다).
                        val plan = withContext(Dispatchers.Default) {
                            SpeechEpd.plan(sttPcm, vadSeams, epdCfg)
                        }
                        row.epdChunks = plan.chunkCount
                        row.epdCutPointsSec = plan.cutPointsText()
                        row.epdPlanMs = plan.planMs

                        when (epdCfg.mode) {
                            EpdMode.SPLIT_SESSION -> {
                                // A안: 조각으로 나눈다. 어떤 sample 도 버리거나 더하지 않는다.
                                sttChunks = SpeechEpd.split(sttPcm, plan)
                                row.epdAddedSec = 0.0
                            }

                            EpdMode.LONG_SILENCE -> {
                                // B안: 세션은 1개, 절단 지점의 무음만 길게 늘린다.
                                val ins = SpeechEpd.insertLongSilence(sttPcm, plan, epdCfg.longSilenceMs)
                                sttPcm = ins.pcm                       // STT 입력 자체가 길어진다
                                sttChunks = listOf(sttPcm)
                                row.epdAddedSec = ins.addedSec
                                row.vadOutputSec = sttPcm.size / BYTES_PER_SEC.toDouble()  // 실제 입력 길이로 갱신
                            }

                            EpdMode.OFF -> Unit                        // 여기 올 일은 없다(enabled 로 걸렀다)
                        }

                        appendLog(
                            "[epd] $relName : ${plan.chunkCount}조각 " +
                                "(무음 절단 ${plan.seamCuts}회 / 역방향 탐색 ${plan.searchedCuts}회" +
                                (if (plan.cuts.isEmpty()) "" else ", 절단 ${plan.cutPointsText()}s") +
                                (if (row.epdAddedSec > 0) ", 무음 +${"%.2f".format(Locale.US, row.epdAddedSec)}s" else "") +
                                ", ${plan.planMs}ms)",
                        )
                        if (plan.searchedCuts > 0) {
                            appendLog(
                                "[epd][주의] $relName 은 VAD 무음 후보가 없어 말 도중에서 잘렸을 수 있다" +
                                    "(역방향 탐색 ${plan.searchedCuts}회). VAD 를 켜거나 Safe 설정을 쓰면 줄어든다.",
                            )
                        }
                    }

                    // ── 4) STT ──
                    val baseUnits = completedUnits
                    // add-hyungchul-20260914-2130
                    // 타임아웃이 실제로 터진 행에도 값이 남도록 호출 "전에" 기록한다.
                    // change-hyungchul-20260914-2350 : 조각별 타임아웃의 합(= 이 파일 1건의 최악 대기시간)
                    row.sttTimeoutSec = sttChunks.sumOf { sttTimeoutSec(it.size, feed) }
                    try {
                        // change-hyungchul-20260914-2350
                        // 조각이 1개면 예전과 완전히 같은 경로다. 2개 이상이면 세션을 나눠 돌리고 합친다.
                        // change-hyungchul-20260915-2200 : 할당량 재시도를 한 겹 씌운다.
                        // change-hyungchul-20260916-1000
                        //   세션 번호는 호출 "전에" 기록한다. 전에는 호출이 끝난 뒤에 넣어서
                        //   정작 원인을 알고 싶은 실패 행의 stt_session_index 가 -1 로 남았다.
                        row.sttSessionIndex = sttSessionCount + 1
                        val outcome = transcribeWithQuotaRetry(
                            sttChunks, modelConfig, locale, feed, relName,
                        ) { fed ->
                            updateProgress((baseUnits + fed) / totalUnits.toDouble())
                        }

                        row.readyMs = outcome.readyMs
                        row.firstResultMs = outcome.firstResultMs
                        row.feedMs = outcome.feedMs
                        row.tailMs = outcome.tailMs
                        row.sttWallMs = outcome.sttWallMs
                        row.segments = outcome.segments
                        row.mlkitStatus = outcome.mlkitStatus
                        row.checkStatusMs = outcome.checkStatusMs
                        row.downloadMs = outcome.downloadMs
                        row.text = outcome.text
                        row.chars = outcome.text.length
                        row.words = SttTelemetry.wordCount(outcome.text)
                        row.status = if (outcome.text.isBlank()) "NO_MATCH" else "OK"

                        // add-hyungchul-20260914-2130 : ML Kit segments 결함 자동 표시
                        // change-hyungchul-20260914-2350 : "글자 밀도" 조건을 추가해 오탐을 없앴다.
                        //
                        // 실측: segments==1 이면서 STT 입력이 약 61초를 넘으면
                        //       전사문의 "앞부분" 이 통째로 사라진다(중간이 아니라 머리쪽이다).
                        // 그런데 길이만 보면 오탐이 난다. 2026-09-14 실행에서
                        //       61.32초 / segments=1 / 433자(=7.1자/초) 인 멀쩡한 행이 잘림으로 찍혔다.
                        // 반면 진짜 잘린 행은 86~89초에 143자(=1.6자/초) 로 밀도가 4.5배 낮았다.
                        // 그래서 "길다 + segments=1 + 글자 밀도가 비정상적으로 낮다" 를 모두 만족할 때만 표시한다.
                        // ※ ML Kit 경로에서만 판정한다. 플랫폼 경로는 segment 를 세는 방식이 달라
                        //    같은 기준을 대면 멀쩡한 행을 의심행으로 만든다.
                        val sttInputSec = sttPcm.size.toDouble() / BYTES_PER_SEC
                        val charsPerSec =
                            if (sttInputSec > 0) outcome.text.length / sttInputSec else 0.0
                        if (modelConfig.engine == SttEngine.ML_KIT &&
                            row.status == "OK" &&
                            outcome.segments == 1 &&
                            sttInputSec > MLKIT_SEGMENT_TRUNCATION_SEC &&
                            charsPerSec < MLKIT_TRUNCATION_CHARS_PER_SEC
                        ) {
                            row.truncatedSuspect = 1
                            row.status = "TRUNCATED_SUSPECT"
                            appendLog(
                                "[warn] $relName 앞부분 유실 의심 — segments=1 인데 " +
                                    "stt-in ${"%.1f".format(Locale.US, sttInputSec)}s " +
                                    "(> ${MLKIT_SEGMENT_TRUNCATION_SEC}s) 인데 " +
                                    "${"%.1f".format(Locale.US, charsPerSec)}자/초 " +
                                    "(< ${MLKIT_TRUNCATION_CHARS_PER_SEC}자/초) 밖에 안 나왔다. " +
                                    "이 행의 WER/CER 은 엔진 결함이 섞인 값이므로 그대로 쓰지 말 것. " +
                                    "Speech EPD 를 켜면 이 결함을 피할 수 있다.",
                            )
                        }

                        // add-hyungchul-20260914-1500
                        // 엔진이 "말이 없다" 고 한 경우 그 메시지를 남긴다(status 는 NO_MATCH 유지).
                        if (outcome.engineNote.isNotEmpty()) row.error = outcome.engineNote

                        // add-hyungchul-20260914-1500 : 공급 정체 계측
                        row.feedExpectedMs = expectedFeedMs(sttPcm.size, feed.delayMs)
                        row.feedStallRatio =
                            if (row.feedExpectedMs > 0 && row.feedMs >= 0) {
                                row.feedMs.toDouble() / row.feedExpectedMs
                            } else {
                                -1.0
                            }
                        if (row.feedStallRatio > FEED_STALL_WARN_RATIO) {
                            appendLog(
                                "[warn] $relName 공급이 막혔다 — feed ${row.feedMs}ms " +
                                    "(이론 ${row.feedExpectedMs}ms, " +
                                    "${"%.1f".format(Locale.US, row.feedStallRatio)}배). " +
                                    "엔진이 파이프를 늦게 읽은 것이므로 이 행의 stt_wall_ms/rtf 는 신뢰하지 말 것.",
                            )
                        }

                        withContext(Dispatchers.IO) {
                            saveResult(
                                outputRoot, modelFolder, variantSegments, item, outcome.text,
                            )
                        }
                        ok++
                        appendLog(
                            getString(R.string.log_done, modelFolder, relName) +
                                    "  (audio ${"%.1f".format(Locale.US, decoded.durationSec)}s / " +
                                    "stt-in ${"%.1f".format(Locale.US, sttPcm.size / BYTES_PER_SEC.toDouble())}s / " +
                                    "tail ${outcome.tailMs}ms / wall ${outcome.sttWallMs}ms)",
                        )
                        consecutiveTimeouts = 0        // add-hyungchul-20260914-2350 : 정상이면 초기화
                    } catch (e: Exception) {
                        val msg = describe(e)
                        Log.e(logTag, "transcribe failed: $modelFolder/$relName", e)
                        withContext(Dispatchers.IO) {
                            saveResult(
                                outputRoot, modelFolder, variantSegments, item, "[ERROR] $msg",
                            )
                        }
                        row.status = "ERROR"
                        row.error = msg
                        fail++
                        appendLog(getString(R.string.log_fail, modelFolder, relName, msg))

                        // change-hyungchul-20260916-1000
                        //   타임아웃뿐 아니라 "checkStatus 무응답" 도 엔진 사망으로 센다(위 isEngineDead 주석 참조).
                        if (isEngineDead(e)) consecutiveTimeouts++ else consecutiveTimeouts = 0
                        //   세션 한도를 이미 넘긴 뒤의 실패는 회복된 전례가 없다(3회 실행 전부 동일).
                        //   그때는 한 건만 실패해도 즉시 끝낸다. 남은 파일을 시도해 봐야 전부 실패한다.
                        val pastBudget = sttSessionCount > QUOTA_BUDGET_SESSIONS
                        if (isEngineDead(e) &&
                            (pastBudget || consecutiveTimeouts >= CONSECUTIVE_TIMEOUT_ABORT)
                        ) {
                            abortedByWedge = true
                            snap.fillDelta(applicationContext, row)
                            csv.append(row.toCsvLine())
                            completedUnits++
                            updateProgress(completedUnits.toDouble() / totalUnits)
                            appendLog(
                                "[중단] 엔진이 죽었다(세션 ${sttSessionCount}개 사용, 연속 실패 ${consecutiveTimeouts}건). " +
                                    "남은 ${items.size - index - 1}건을 포기하고 배치를 끝낸다. " +
                                    "여기까지의 결과는 result.csv 에 그대로 저장된다.",
                            )
                            appendLog(
                                "[중단] 실측상 세션 약 ${QUOTA_BUDGET_SESSIONS}개를 쓰고 나면 엔진이 응답을 멈추며, " +
                                    "기다려도 스스로 회복되지 않는다(3분 휴식 후 재시도도 실패했다). " +
                                    "조치: '음성 검색'/'Google 음성 서비스'(com.google.android.tts) 를 강제 종료한 뒤 " +
                                    "다시 실행할 것. 한 번에 ${QUOTA_BUDGET_SESSIONS}세션 이하로 나눠 돌리면 이 문제를 피할 수 있다.",
                            )
                            break
                        }
                    }

                    snap.fillDelta(applicationContext, row)
                    csv.append(row.toCsvLine())

                    completedUnits++
                    updateProgress(completedUnits.toDouble() / totalUnits)

                    // change(add)-hyungchul-20260820
                    // 중간 저장: 배치가 도중에 죽어도 그때까지의 계측이 남도록 10건마다 flush 한다.
                    if ((index + 1) % CSV_FLUSH_EVERY == 0) {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                writeCsv(
                                    outputRoot, modelFolder, variantSegments, csv.toString(),
                                )
                            }
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    writeCsv(outputRoot, modelFolder, variantSegments, csv.toString())
                }

                updateProgress(1.0)
                // add-hyungchul-20260914-2350 : 먹통으로 끊긴 배치인지 결과 요약에 분명히 남긴다.
                if (abortedByWedge) {
                    appendLog(
                        "[요약] 성공 ${ok}건 / 실패 ${fail}건 — 엔진 먹통으로 배치를 중간에 끝냈다. " +
                            "엔진을 되살린 뒤 처음부터 다시 돌릴 것(이번 result.csv 는 전체가 아니다).",
                    )
                    toast("엔진 먹통으로 중단 — 성공 ${ok}건 / 실패 ${fail}건")
                } else {
                    toast(getString(R.string.msg_stt_done, ok, fail))
                }
            } catch (e: Exception) {
                Log.e(logTag, "run failed", e)
                // add-hyungchul-20260825-1620
                // 토스트는 금방 사라진다. 실패 원인을 화면 로그에도 남겨 원인을 놓치지 않게 한다.
                appendLog("[실패] 실행이 중단되었습니다 — ${describe(e)}")
                // 예외로 빠져나가도 지금까지 모은 계측은 남긴다.
                // change-hyungchul-20260914-1500: NonCancellable 추가.
                //   코루틴이 이미 취소된 상태라면 그냥 withContext 는 즉시 예외를 던지고
                //   runCatching 이 그걸 삼켜 "조용히 저장 실패" 가 된다(vLog/08 사고의 원인).
                runCatching {
                    val outputRoot = DocumentFile.fromTreeUri(this@MainActivity, outUri)
                    if (outputRoot != null) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            writeCsv(outputRoot, modelFolder, variantSegments, csv.toString())
                        }
                    }
                }
                toast(getString(R.string.msg_error, describe(e)))
            } finally {
                // ★ add-hyungchul-20260914-1500 : 마지막 방어선
                //   취소든 예외든 타임아웃이든, 어떤 경로로 빠져나가도 여기서 CSV 를 반드시 남긴다.
                //   writeCsv 는 같은 파일을 덮어쓰므로 catch 에서 이미 저장했어도 문제없다.
                runCatching {
                    withContext(NonCancellable + Dispatchers.IO) {
                        val root = DocumentFile.fromTreeUri(this@MainActivity, outUri)
                        if (root != null) {
                            writeCsv(root, modelFolder, variantSegments, csv.toString())
                        }
                    }
                }
                runCatching { vadProcessor?.close() }   // add-hyungchul-20260825-1430
                runCatching { noiseReducer?.close() }   // add-hyungchul-20260826-1100
                running = false
                startButton.isEnabled = true
            }
        }
    }

    /**
     * add-hyungchul-20260825-1430
     * change-hyungchul-20260826-1100: vadFolder(String?) → variant(List<String>) 로 일반화.
     *   노이즈 저감 폴더가 하나 더 끼어들어야 해서, 앞으로 단계가 늘어나도 시그니처를 안 바꾸도록
     *   "추가 경로 조각 목록"을 그대로 받는다.
     * 목적: 이번 실행의 결과 폴더를 만든다.
     * 입력: outputRoot(출력 트리), modelFolder(base/advanced/android_ondevice),
     *       variant — 비어 있으면 기존 경로를 그대로 쓴다. 예: ["ns_gtcrn_lim12", "vad_asr_safe"]
     * 리턴: output/google/<모드>[/ns_xxx][/vad_yyy] DocumentFile
     */
    private fun ensureRunDir(
        outputRoot: DocumentFile,
        modelFolder: String,
        variant: List<String>,
    ): DocumentFile {
        val segments = mutableListOf(algoName, modelFolder)
        segments.addAll(variant)
        return ensureDir(outputRoot, segments)
    }

    // change-hyungchul-20260826-1100: variant 목록을 받는다(빈 목록이면 기존 동작과 동일)
    private fun writeCsv(
        outputRoot: DocumentFile,
        modelFolder: String,
        variant: List<String>,
        body: String,
    ) {
        val dir = ensureRunDir(outputRoot, modelFolder, variant)
        writeBytes(
            dir, "result.csv",
            SttTelemetry.BOM + body.toByteArray(Charsets.UTF_8), "text/csv",
        )
    }

    /**
     * 목적: 이 경로가 API 계약상 어디서 실행되는지 문자열로 만든다.
     * 비고: 세 경로 모두 문서상 온디바이스다. 실제 증명은 CSV 의 net_type/airplane 컬럼으로 한다.
     */
    private fun executionLabel(config: ModelConfig): String = when (config.engine) {
        SttEngine.ML_KIT ->
            if (config.mlKitMode == SpeechRecognizerOptions.Mode.MODE_ADVANCED) {
                "ON_DEVICE(MLKit GenAI)"
            } else {
                "ON_DEVICE(MLKit basic=platform SpeechRecognizer)"
            }

        SttEngine.ANDROID_PLATFORM_ON_DEVICE -> "ON_DEVICE(platform on-device service)"
    }

    /** 배치 시작 전 1회 가용성 점검. 로그에 남기고 run_meta.csv 용 문자열도 돌려준다. */
    private suspend fun preflight(config: ModelConfig, locale: Locale): String {
        val sb = StringBuilder()
        when (config.engine) {
            SttEngine.ML_KIT -> {
                for (m in listOf(
                    SpeechRecognizerOptions.Mode.MODE_BASIC,
                    SpeechRecognizerOptions.Mode.MODE_ADVANCED,
                )) {
                    // change-hyungchul-20260915-1400
                    //   use{} 는 블록을 벗어날 때 close() 를 부른다. 엔진이 먹통이면 그 close() 가
                    //   네이티브에서 블로킹해 preflight 단계에서 앱이 통째로 멈춘다(로그도 안 나온다).
                    //   그래서 checkStatus() 에는 시간 제한을, close() 에는 포기 가능한 제한을 건다.
                    val client = SpeechRecognition.getClient(
                        speechRecognizerOptions {
                            this.locale = locale
                            preferredMode = m
                        },
                    )
                    val st = runCatching {
                        withTimeoutOrNull(FEATURE_STATUS_TIMEOUT_MS) { client.checkStatus() }
                            ?: throw IOException(
                                "checkStatus() 무응답 — 인식 엔진이 먹통이다",
                            )
                    }
                    closeRecognizerBounded(client)
                    val name =
                        if (m == SpeechRecognizerOptions.Mode.MODE_BASIC) "BASIC" else "ADVANCED"
                    val text = st.fold({ statusName(it) }, { describe(it) })
                    val line = "ML Kit $name (${locale.toLanguageTag()}) = $text"
                    appendLog("[preflight] $line")
                    sb.append(line).append(" ; ")
                }
            }

            SttEngine.ANDROID_PLATFORM_ON_DEVICE -> {
                val avail = AndroidSpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                val line1 = "isOnDeviceRecognitionAvailable=$avail"
                appendLog("[preflight] $line1")
                sb.append(line1).append(" ; ")
                if (avail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val line2 = checkAndroidOnDeviceSupport(locale)
                    appendLog("[preflight] $line2")
                    sb.append(line2)
                }
            }
        }
        val svc = SttTelemetry.recognitionServices(applicationContext)
        appendLog("[preflight] RecognitionService: $svc")
        appendLog(
            "[preflight] net=${SttTelemetry.networkType(applicationContext)} " +
                    "airplane=${SttTelemetry.airplaneMode(applicationContext)} " +
                    "thermal=${SttTelemetry.thermalStatus(applicationContext)}",
        )
        return sb.toString()
    }

    // ─────────────────────────── STT 결과 + 계측 ───────────────────────────
    /**
     * 목적: 전사 결과와 지연 계측치를 함께 나른다.
     * - readyMs        : 세션 시작 → 엔진 준비 완료(onReadyForSpeech). 플랫폼 경로만 측정 가능.
     * - firstResultMs  : 세션 시작 → 첫 응답. 체감 응답성(TTFT).
     * - feedMs         : 오디오 공급에 걸린 시간(공급 속도 설정에 좌우됨).
     * - tailMs         : ★ 공급 EOF → 최종 결과. 공급 속도와 무관한 "엔진 처리 지연".
     * - sttWallMs      : 세션 시작 → 최종 결과(전체).
     */
    private data class SttOutcome(
        val text: String,
        val readyMs: Long = -1,
        val firstResultMs: Long = -1,
        val feedMs: Long = -1,
        val tailMs: Long = -1,
        val sttWallMs: Long = -1,
        val segments: Int = 0,
        val mlkitStatus: String = "",
        val checkStatusMs: Long = -1,
        val downloadMs: Long = -1,
        /** add-hyungchul-20260914-1500: 엔진이 남긴 메시지(NO_SPEECH_DETECTED 등). 실패가 아닐 때만 채운다. */
        val engineNote: String = "",
    )

    /**
     * add-hyungchul-20260914-2130
     * 목적: 이 파일 1건에 적용할 STT 타임아웃(초)을 계산한다.
     * 입력: pcmBytes — 실제로 STT 에 들어가는 PCM 바이트 수
     *       feed     — 공급 속도 설정(chunk 당 지연)
     * 출력: 없음
     * 리턴: 타임아웃(초)
     *
     * 배경(실측 근거 — 기존 3회 실행, 정상 완료 112행을 전수 집계했다):
     *   기존 식은 (오디오초 × 2 + 180) 이라 90초 음원에서 360초를 기다렸다.
     *   실제로 엔진이 먹통이 된 3건(vLog/08 ×2, customer/06)에서 이 시간을 전부 날렸다.
     *
     *   [1] 공급 EOF → 최종 결과(tail_ms) 는 112행 전부에서 최대 287 ms 였다.
     *       즉 기존 식의 고정 +180초는 관측 최악값의 628배로, 순전히 군더더기였다.
     *   [2] 반대로 stt_wall_ms ≒ feed_ms 였다(둘의 차이 최대 287 ms).
     *       전체 소요시간은 "엔진이 파이프를 얼마나 빨리 읽어 가느냐" 가 지배한다.
     *   [3] 정상 완료한 행 중 가장 오래 걸린 경우는
     *       STT 입력 60.8초 → 101.7초(= 입력 길이의 1.67배) 였다.
     *
     *   그래서 고정분은 45초(=[1] 의 150배 여유), 비례분은 2.5배([3] 의 1.5배 여유)로 잡는다.
     *   이 값이면 관측된 모든 정상 행이 1.94배 이상 여유를 두고 통과하면서,
     *   먹통일 때는 기존보다 90초 이상 일찍 끊는다.
     *   ※ 만약 정상인데 타임아웃으로 끊기는 행이 생기면 STT_TIMEOUT_FACTOR 부터 올릴 것.
     *
     * 주의: 공급 지연이 0(MAX 모드) 이면 이론 공급시간을 0 으로 보고 오디오 길이만 쓴다.
     */
    // ──────────── add-hyungchul-20260914-2350 : 조각 전사 / 사전 점검 ────────────
    /**
     * 목적: PCM 1조각을 엔진에 맞는 경로로 전사한다(기존 호출부를 한 군데로 모은 것뿐이다).
     * 입력: pcm, modelConfig, locale, feed, onFed
     * 출력: 없음
     * 리턴: SttOutcome
     * 예외: ML Kit 모드 설정이 없으면 IOException
     */
    private suspend fun transcribeOne(
        pcm: ByteArray,
        modelConfig: ModelConfig,
        locale: Locale,
        feed: FeedConfig,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        // add-hyungchul-20260915-2200 : 세션을 하나 여는 시점에 센다(실패해도 할당량은 소모된다).
        sttSessionCount++
        if (sttSessionCount == QUOTA_WARN_SESSIONS) {
            appendLog(
                "[quota] 세션 ${sttSessionCount}개째다. SODA 세션 한도(실측 약 " +
                    "${QUOTA_BUDGET_SESSIONS}세션)이 가까워졌다. 곧 응답이 멈출 수 있다.",
            )
        }
        return when (modelConfig.engine) {
            SttEngine.ML_KIT -> {
                val mode = modelConfig.mlKitMode
                    ?: throw IOException("ML Kit 모드 설정이 없습니다.")
                transcribeMlKit(pcm, locale, mode, feed, onFed)
            }

            SttEngine.ANDROID_PLATFORM_ON_DEVICE ->
                transcribeAndroidOnDevice(pcm, locale, feed, onFed)
        }
    }

    /**
     * add-hyungchul-20260915-2200
     * 목적: 할당량으로 막힌 경우 잠깐 쉬었다가 다시 시도한다.
     * 입력: chunks, modelConfig, locale, feed, onFed, relName(로그용)
     * 출력: 없음
     * 리턴: SttOutcome
     * 예외: 재시도까지 실패하면 마지막 예외를 그대로 던진다.
     *
     * 배경: AICore 할당량은 "굴러가는 창(rolling window)" 이라 시간이 지나면 다시 채워진다는
     *       보고가 있다(googlesamples/mlkit issue #1070, 약 2.5분 뒤 회복).
     *       그래서 타임아웃 한 번에 파일을 포기하지 말고, 한 번은 쉬었다가 다시 해 본다.
     *       배치 전체를 버리는 것보다 몇 분 기다리는 쪽이 훨씬 싸다.
     */
    private suspend fun transcribeWithQuotaRetry(
        chunks: List<ByteArray>,
        modelConfig: ModelConfig,
        locale: Locale,
        feed: FeedConfig,
        relName: String,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        var attempt = 0                                          // 재시도 횟수
        while (true) {
            try {
                return transcribeChunks(chunks, modelConfig, locale, feed, onFed)
            } catch (e: TimeoutCancellationException) {
                attempt++
                // change-hyungchul-20260916-1000
                //   한도를 이미 넘긴 뒤라면 쉬어도 소용이 없다(실측: 180초 휴식 + 재시도 전부 실패,
                //   파일당 8.1분을 헛되이 썼다). 그때는 바로 던져서 상위에서 배치를 끝내게 한다.
                if (attempt > QUOTA_RETRY_MAX || sttSessionCount > QUOTA_BUDGET_SESSIONS) throw e
                appendLog(
                    "[quota] $relName 이 응답하지 않았다(세션 ${sttSessionCount}개째). " +
                        "AICore 할당량으로 보고 ${QUOTA_COOLDOWN_MS / 1000}초 쉬었다가 " +
                        "다시 시도한다 ($attempt/$QUOTA_RETRY_MAX).",
                )
                delay(QUOTA_COOLDOWN_MS)                          // 할당량이 다시 채워질 시간을 준다
            }
        }
    }

    /**
     * 목적: Speech EPD 로 나눈 조각들을 차례로 전사하고 하나의 결과로 합친다.
     * 입력: chunks — 조각 목록(1개면 예전과 동일한 단일 세션),
     *       modelConfig, locale, feed, onFed(파일 전체 기준 진행률 0.0~1.0)
     * 출력: 없음
     * 리턴: 합쳐진 SttOutcome
     *
     * 합치는 규칙:
     *   text          — 조각 전사문을 공백 하나로 이어 붙인다(빈 조각은 건너뛴다).
     *   readyMs/first — 첫 조각 값(체감 응답성은 첫 조각이 대표한다).
     *   feed/tail/wall— 전 조각의 합(파일 1건을 처리하는 데 실제로 든 시간).
     *   segments      — 전 조각의 합.
     *   engineNote    — 조각 번호를 붙여 모두 남긴다.
     * 비고: 조각 하나라도 예외가 나면 그대로 위로 던진다. 반쪽짜리 전사문을
     *       정상처럼 기록하면 WER 이 조용히 오염되기 때문이다.
     */
    private suspend fun transcribeChunks(
        chunks: List<ByteArray>,
        modelConfig: ModelConfig,
        locale: Locale,
        feed: FeedConfig,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        // 조각이 1개면 예전 경로를 그대로 탄다(진행률 계산도 건드리지 않는다).
        if (chunks.size <= 1) {
            return transcribeOne(chunks.firstOrNull() ?: ByteArray(0), modelConfig, locale, feed, onFed)
        }

        val totalBytes = chunks.sumOf { it.size }.coerceAtLeast(1)   // 진행률 분모
        var doneBytes = 0                                            // 여기까지 끝난 바이트
        val texts = ArrayList<String>(chunks.size)
        val notes = ArrayList<String>()

        var readyMs = -1L
        var firstResultMs = -1L
        var feedMs = 0L
        var tailMs = 0L
        var wallMs = 0L
        var segments = 0
        var mlkitStatus = ""
        var checkStatusMs = -1L
        var downloadMs = -1L

        for ((i, chunk) in chunks.withIndex()) {
            val base = doneBytes                                     // 이 조각이 시작될 때의 누적 바이트
            val weight = chunk.size.toDouble()
            val one = transcribeOne(chunk, modelConfig, locale, feed) { fed ->
                // 조각 내부 진행률(0~1)을 파일 전체 진행률로 환산한다.
                onFed((base + fed * weight) / totalBytes)
            }

            if (one.text.isNotBlank()) texts.add(one.text.trim())
            if (i == 0) {                                            // 첫 조각만 대표값으로 쓴다
                readyMs = one.readyMs
                firstResultMs = one.firstResultMs
                mlkitStatus = one.mlkitStatus
                checkStatusMs = one.checkStatusMs
                downloadMs = one.downloadMs
            }
            if (one.feedMs >= 0) feedMs += one.feedMs
            if (one.tailMs >= 0) tailMs += one.tailMs
            if (one.sttWallMs >= 0) wallMs += one.sttWallMs
            segments += one.segments
            if (one.engineNote.isNotEmpty()) notes.add("[조각${i + 1}] ${one.engineNote}")

            doneBytes += chunk.size
        }

        return SttOutcome(
            text = texts.joinToString(" "),
            readyMs = readyMs,
            firstResultMs = firstResultMs,
            feedMs = feedMs,
            tailMs = tailMs,
            sttWallMs = wallMs,
            segments = segments,
            mlkitStatus = mlkitStatus,
            checkStatusMs = checkStatusMs,
            downloadMs = downloadMs,
            engineNote = notes.joinToString(" | "),
        )
    }

    /**
     * 목적: 배치를 시작하기 전에 엔진이 살아 있는지 1초짜리 무음으로 시험해 본다.
     * 입력: modelConfig, locale
     * 출력: 없음 (로그를 남긴다)
     * 리턴: "OK" / "TIMEOUT" / "SKIP"
     *
     * 배경(실측):
     *   2026-09-14 실행에서 customer/01,02,03 이 연속으로 248초씩 타임아웃했다.
     *   같은 PCM 이 전날에는 12.4초에 끝났으므로 음원이나 설정 문제가 아니라
     *   ML Kit 인식 서비스가 먹통이 된 상태였다.
     *   그대로 두면 24개 × 248초 = 약 99분을 통째로 날린다.
     *   그래서 배치 시작 전에 짧은 입력으로 응답 여부만 먼저 본다.
     *
     * 비고: 무음이므로 NO_SPEECH_DETECTED 나 빈 결과가 나오는 것이 정상이다.
     *       "응답이 왔는가" 만 보면 되므로 예외가 나도 살아 있는 것으로 친다.
     *       응답 자체가 없을 때(=타임아웃) 만 먹통으로 판정한다.
     */
    private suspend fun warmUpEngine(modelConfig: ModelConfig, locale: Locale): String {
        val silence = ByteArray(BYTES_PER_SEC * WARMUP_SECONDS)      // 1초 무음 (0x00 = 무음)
        // change-hyungchul-20260915-1400 : 사전 점검도 실시간 속도로 넣는다.
        //   공식 문서가 요구하는 속도가 실시간이므로, 점검부터 규격대로 해야 의미가 있다.
        val warmFeed = FeedConfig("WARMUP", WARMUP_FEED_DELAY_MS)
        val outcome = withTimeoutOrNull(WARMUP_TIMEOUT_MS) {
            runCatching { transcribeOne(silence, modelConfig, locale, warmFeed) {} }
        } ?: return "TIMEOUT"                                        // 아예 응답이 없다

        // change-hyungchul-20260915-1400
        //   무음이므로 NO_SPEECH 같은 예외가 나오는 것은 "엔진이 살아서 대답했다" 는 뜻이다.
        //   반대로 우리가 건 시간 제한(checkStatus 무응답 등)에 걸린 예외라면 먹통이다.
        val e = outcome.exceptionOrNull() ?: return "OK"
        val msg = e.message ?: ""
        return if (e is TimeoutCancellationException ||
            msg.contains("응답하지 않았다") ||
            msg.contains("무응답")
        ) {
            "TIMEOUT"
        } else {
            "OK"
        }
    }

    private fun sttTimeoutSec(pcmBytes: Int, feed: FeedConfig): Long {
        val audioSec = pcmBytes.toDouble() / BYTES_PER_SEC           // 실제 STT 입력 길이(초)
        val expectedMs = expectedFeedMs(pcmBytes, feed.delayMs)      // 이론 공급시간(ms), 잴 수 없으면 -1
        val feedSec = if (expectedMs > 0) expectedMs / 1000.0 else 0.0
        val base = maxOf(audioSec, feedSec)                          // 둘 중 큰 쪽을 기준으로 삼는다
        val sec = STT_TIMEOUT_FIXED_SEC + base * STT_TIMEOUT_FACTOR  // 고정 여유 + 기준 × 배율
        return sec.toLong().coerceAtLeast(STT_TIMEOUT_MIN_SEC)       // 아주 짧은 음원도 최소치는 보장
    }

    // ─────────────────────── (1)(2) ML Kit GenAI Speech Recognition ───────────────────────
    /**
     * 목적: ML Kit GenAI Speech Recognition 으로 PCM 1건을 전사하고 지연을 계측한다.
     * 입력: pcm(16k/mono/PCM16), locale, mode(MODE_BASIC/MODE_ADVANCED), feed(공급 속도), onFed(진행률)
     * 출력: SttOutcome
     * 예외: 모델이 AVAILABLE 이 아니거나 인식 오류 시 IOException
     */
    private suspend fun transcribeMlKit(
        pcm: ByteArray,
        locale: Locale,
        mode: Int,
        feed: FeedConfig,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        val options: SpeechRecognizerOptions = speechRecognizerOptions {
            this.locale = locale
            preferredMode = mode
        }

        // ★ change-hyungchul-20260914-1500 : use{} 를 try/finally 로 바꾼다.
        //   use{} 는 블록을 벗어날 때 close() 를 부르는데, 엔진이 먹통이면 그 close() 가 블록된다.
        //   그러면 withTimeout 이 제때 터져도 배치 전체가 여기서 영원히 멈춘다.
        //   closeRecognizerBounded() 로 시간 제한을 두어 그런 경우에도 다음 파일로 넘어가게 한다.
        val recognizer = SpeechRecognition.getClient(options)
        return try {
            val tCheck = System.nanoTime()
            // change-hyungchul-20260915-1400 : 먹통일 때 여기서 무한 대기하던 것을 막는다.
            //   기존에는 시간 제한이 전혀 없어서, 엔진이 죽으면 로그 한 줄 없이 멈춰 있었다.
            var status = withTimeoutOrNull(FEATURE_STATUS_TIMEOUT_MS) { recognizer.checkStatus() }
                ?: throw IOException(
                    "checkStatus() 가 ${FEATURE_STATUS_TIMEOUT_MS / 1000}초 안에 응답하지 않았다. " +
                        "인식 엔진(com.google.android.tts 의 SODA)이 먹통인 상태다.",
                )
            val checkStatusMs = (System.nanoTime() - tCheck) / 1_000_000
            Log.i(logTag, "checkStatus=${statusName(status)} (mode=$mode, locale=$locale)")

            var downloadMs = -1L
            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                val tDl = System.nanoTime()
                // change-hyungchul-20260915-1400 : 다운로드도 무한정 기다리지 않는다.
                withTimeoutOrNull(MODEL_DOWNLOAD_TIMEOUT_MS) {
                    recognizer.download().collect { ds ->
                        Log.i(logTag, "download status: $ds")
                        if (ds is DownloadStatus.DownloadFailed) {
                            throw IOException("모델 다운로드 실패: ${ds.e.message ?: ds.e}")
                        }
                    }
                } ?: throw IOException(
                    "모델 다운로드가 ${MODEL_DOWNLOAD_TIMEOUT_MS / 60_000}분 안에 끝나지 않았다.",
                )
                downloadMs = (System.nanoTime() - tDl) / 1_000_000
                status = withTimeoutOrNull(FEATURE_STATUS_TIMEOUT_MS) { recognizer.checkStatus() }
                    ?: throw IOException("다운로드 후 checkStatus() 가 응답하지 않았다.")
                Log.i(logTag, "after download, checkStatus=${statusName(status)}")
            }
            if (status != FeatureStatus.AVAILABLE) {
                throw IOException(
                    "모델 사용 불가: ${statusName(status)}. " +
                            "MODE_ADVANCED 는 Pixel 10 계열에서만 제공되고, " +
                            "MODE_BASIC 은 API 31+ 대부분의 기기에서 제공된다.",
                )
            }

            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            val feeder = PcmFeeder(writeSide, pcm, CHUNK_BYTES, feed.delayMs, onFed)

            val sb = StringBuilder()
            var errorMessage: String? = null
            var firstResponseNs = 0L
            var finalCount = 0

            // ※ IDE 가 "Initializer is redundant" 로 표시할 수 있으나 오탐이다.
            //   try 블록 안에서만 대입되므로 초기값이 없으면 Kotlin 의 확정 대입(definite assignment)
            //   검사를 통과하지 못해 컴파일 자체가 안 된다. 지우면 안 된다.
            var sessionStartNs = 0L
            var endNs = 0L
            // add-hyungchul-20260915-1400 : 세션을 정상으로 닫았는지 표시한다.
            var stoppedCleanly = false

            try {
                val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(readSide) }
                // change-hyungchul-20260914-2130 : (오디오초 × 2 + 180) → 공급속도까지 반영한 식
                val timeout = sttTimeoutSec(pcm.size, feed).seconds

                sessionStartNs = System.nanoTime()
                withTimeout(timeout) {
                    coroutineScope {
                        val collectJob = launch {
                            recognizer.startRecognition(request).collect { resp ->
                                if (firstResponseNs == 0L) firstResponseNs = System.nanoTime()
                                when (resp) {
                                    is SpeechRecognizerResponse.FinalTextResponse -> {
                                        finalCount++
                                        val t = resp.text.trim()
                                        if (t.isNotEmpty()) {
                                            if (sb.isNotEmpty()) sb.append(' ')
                                            sb.append(t)
                                        }
                                    }

                                    is SpeechRecognizerResponse.ErrorResponse -> {
                                        errorMessage = resp.e.message ?: resp.e.toString()
                                        Log.e(logTag, "recognition ErrorResponse", resp.e)
                                    }

                                    else -> Unit // Partial / Completed 는 텍스트에 넣지 않는다
                                }
                            }
                        }

                        feeder.start()
                        feeder.finished.await()
                        runCatching { recognizer.stopRecognition() }
                        stoppedCleanly = true                  // add-hyungchul-20260915-1400
                        collectJob.join()
                        endNs = System.nanoTime()
                    }
                }
            } finally {
                feeder.abort()
                // readSide 를 닫지 않으면 파일마다 FD 가 새어 배치 도중 EMFILE 로 실패한다.
                runCatching { readSide.close() }

                // add-hyungchul-20260915-1400
                // ★ 타임아웃으로 빠져나온 경우 stopRecognition() 이 한 번도 불리지 않는다.
                //   공식 문서상 stopRecognition() 이 "세션을 정상 종료" 시키는 호출이므로,
                //   이걸 건너뛰면 엔진 쪽 세션이 살아남아 다음 파일까지 먹통이 이어진다
                //   (실측: 먹통이 한 번 나면 그 뒤 파일이 줄줄이 타임아웃났다).
                //   파이프를 먼저 풀어 준 뒤에 불러야 엔진이 입력 끝을 인식하고 정리할 수 있다.
                //   취소된 코루틴에서도 실행되도록 NonCancellable 로 감싼다.
                if (!stoppedCleanly) {
                    withContext(NonCancellable) {
                        abandonable(STOP_RECOGNITION_TIMEOUT_MS, "stopRecognition()") {
                            runBlocking { recognizer.stopRecognition() }
                        }
                    }
                }
            }

            val result = sb.toString().trim()
            val err = errorMessage
            // change-hyungchul-20260914-1500
            //   ERROR_TYPE_NO_SPEECH_DETECTED 는 엔진이 "말이 없다" 고 판정한 결과이지 고장이 아니다.
            //   예외로 던지면 그 파일 행이 통째로 ERROR 가 되어 WER/CER 비교에서 빠져 버린다.
            //   빈 결과(= status NO_MATCH)로 돌려주고, 엔진이 뭐라고 했는지는 engineNote 로 남긴다.
            var engineNote = ""
            if (result.isEmpty() && err != null) {
                if (isNoSpeechError(err)) {
                    engineNote = err
                } else {
                    throw IOException(err)
                }
            }

            if (endNs == 0L) endNs = System.nanoTime()
            val eofNs = feeder.finishedAtNs.takeIf { it != 0L } ?: endNs

            SttOutcome(
                text = result,
                readyMs = -1, // ML Kit 은 준비 완료 콜백이 없다
                firstResultMs = ms(sessionStartNs, firstResponseNs),
                feedMs = ms(feeder.startedAtNs, feeder.finishedAtNs),
                tailMs = ms(eofNs, endNs),
                sttWallMs = ms(sessionStartNs, endNs),
                segments = finalCount,
                mlkitStatus = statusName(status),
                checkStatusMs = checkStatusMs,
                downloadMs = downloadMs,
                engineNote = engineNote,
            )
        } finally {
            closeRecognizerBounded(recognizer)   // add-hyungchul-20260914-1500
        }
    }

    /**
     * add-hyungchul-20260914-1500
     * 목적: 인식기를 닫되, close() 가 멈춰도 배치가 끝나지 않는 사태를 막는다.
     * 입력: closeable — 닫을 인식기
     * 출력: 없음 (제한 시간을 넘기면 화면 로그에 경고를 남긴다)
     * 리턴: 없음
     * 비고: close() 는 네이티브 정리를 하는 블로킹 호출이라 코루틴 취소로 끊을 수 없다.
     *       제한 시간이 지나면 그 스레드는 IO 디스패처에 남겨 두고 배치만 계속 진행시킨다.
     *       스레드 하나를 잠시 놓치는 손해보다 배치 전체가 멈추는 손해가 훨씬 크다.
     */
    private suspend fun closeRecognizerBounded(closeable: AutoCloseable) {
        // change-hyungchul-20260915-1400 : 진짜로 포기할 수 있게 별도 스레드로 옮긴다.
        val finished = withContext(NonCancellable) {          // 취소 중에도 닫기는 시도한다
            abandonable(RECOGNIZER_CLOSE_TIMEOUT_MS, "인식기 close()") { closeable.close() }
        }
        if (!finished) {
            appendLog(
                "[warn] 인식기 close() 가 ${RECOGNIZER_CLOSE_TIMEOUT_MS / 1000}초 안에 끝나지 않았다. " +
                    "정리는 백그라운드에 맡기고 다음 파일로 진행한다.",
            )
        }
    }

    /**
     * add-hyungchul-20260915-1400
     * 목적: "코루틴 취소가 통하지 않는" 블로킹 호출에 진짜 시간 제한을 건다.
     * 입력: timeoutMs — 기다려 줄 시간(ms)
     *       tag       — 로그에 남길 이름
     *       block     — 실행할 블로킹 코드
     * 출력: 없음 (시간 안에 못 끝내면 로그를 남긴다)
     * 리턴: 시간 안에 끝났으면 true, 포기했으면 false
     *
     * ★ 이 함수가 필요한 이유 (2026-09-15 원인 규명)
     *   Kotlin 코루틴의 취소는 "협조적" 이다. 즉 취소 신호는 suspend 지점에서만 확인된다.
     *   AutoCloseable.close() 처럼 네이티브에서 블로킹하는 호출은 suspend 지점이 없으므로
     *   withTimeout / withTimeoutOrNull 로 감싸도 그 블록이 끝날 때까지 절대 돌아오지 않는다.
     *   즉 기존 closeRecognizerBounded() 의 "5초 제한" 은 실제로는 걸리지 않았다.
     *   엔진이 먹통이면 여기서 배치 전체가 로그 한 줄 없이 영원히 멈춘다
     *   (= "Safe 를 돌렸는데 아무 반응이 없다" 의 정체).
     *   그래서 블로킹 호출을 데몬 스레드로 내보내고, 본체는 그 결과만 기다린다.
     *   시간이 지나면 스레드는 그대로 버리고(데몬이라 앱 종료를 막지 않는다) 다음으로 넘어간다.
     */
    private suspend fun abandonable(timeoutMs: Long, tag: String, block: () -> Unit): Boolean {
        val done = CompletableDeferred<Unit>()                // 스레드가 끝났음을 알리는 신호
        Thread({
            runCatching { block() }                           // 예외는 삼킨다(정리 실패는 치명적이지 않다)
            done.complete(Unit)
        }, "abandonable").apply { isDaemon = true }.start()    // 데몬 = 앱 종료를 막지 않는다
        // await() 는 suspend 지점이라 여기서는 시간 제한이 실제로 걸린다.
        return withTimeoutOrNull(timeoutMs) { done.await() } != null
    }

    // ─────────────── (3) Android Platform On-device SpeechRecognizer ───────────────
    /**
     * 목적: Android Framework 의 createOnDeviceSpeechRecognizer() 로 PCM 1건을 전사하고 지연을 계측한다.
     * 입력: pcm(16k/mono/PCM16), locale, feed(공급 속도), onFed(진행률)
     * 출력: SttOutcome (NO_MATCH 이면 text 가 빈 문자열)
     * 예외: API 33 미만, on-device RecognitionService 부재, 그 외 인식 오류 시 IOException
     */
    private suspend fun transcribeAndroidOnDevice(
        pcm: ByteArray,
        locale: Locale,
        feed: FeedConfig,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw IOException(
                "Android Platform On-device 의 파일 입력은 API 33(Android 13) 이상이 필요하다. " +
                        "createOnDeviceSpeechRecognizer 자체는 API 31부터지만 EXTRA_AUDIO_SOURCE 는 API 33부터다.",
            )
        }
        if (!AndroidSpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            throw IOException("이 기기에는 Android on-device RecognitionService 가 없다.")
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val recognizer = AndroidSpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val feeder = PcmFeeder(writeSide, pcm, CHUNK_BYTES, feed.delayMs, onFed)

        val sb = StringBuilder()
        var segmentCount = 0
        var readyNs = 0L
        var firstResultNs = 0L
        val completed = CompletableDeferred<String>()

        fun appendBest(results: Bundle?) {
            val best = results
                ?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (best.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(best)
            }
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (readyNs == 0L) readyNs = System.nanoTime()
                Log.i(logTag, "Android on-device: ready (locale=$locale)")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onPartialResults(partialResults: Bundle?) {
                // 텍스트에는 넣지 않고, 첫 응답 시각만 기록한다(TTFT 계측용).
                if (firstResultNs == 0L) firstResultNs = System.nanoTime()
            }

            override fun onError(error: Int) {
                if (completed.isCompleted) return
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT 은 "인식 결과 없음"이므로 빈 결과로 처리한다.
                if (error == AndroidSpeechRecognizer.ERROR_NO_MATCH ||
                    error == AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    completed.complete(sb.toString().trim())
                } else {
                    completed.completeExceptionally(
                        IOException("SpeechRecognizer 오류: ${errorName(error)}"),
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                if (firstResultNs == 0L) firstResultNs = System.nanoTime()
                if (segmentCount == 0) appendBest(results)
                if (!completed.isCompleted) completed.complete(sb.toString().trim())
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                if (firstResultNs == 0L) firstResultNs = System.nanoTime()
                segmentCount++
                appendBest(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                if (!completed.isCompleted) completed.complete(sb.toString().trim())
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, REQUEST_PARTIAL_RESULTS)
            // 파일(파이프) 입력 — 오디오가 닫힐 때(EOF) 세션이 종료된다.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioDecoder.TARGET_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            // 평가용: 기본값 true 인 비속어 마스킹(****)을 끄고, 문장부호 포맷팅을 켠다.
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
            )
        }

        // change-hyungchul-20260914-2130 : (오디오초 × 2 + 180) → 공급속도까지 반영한 식
        val timeout = sttTimeoutSec(pcm.size, feed).seconds

        val sessionStartNs = System.nanoTime()
        return try {
            // startListening / destroy 는 반드시 메인 스레드에서 호출해야 한다(lifecycleScope 기본값 = Main).
            recognizer.startListening(intent)
            feeder.start()
            val text = withTimeout(timeout) { completed.await() }
            val endNs = System.nanoTime()
            val eofNs = feeder.finishedAtNs.takeIf { it != 0L } ?: endNs

            SttOutcome(
                text = text,
                readyMs = ms(sessionStartNs, readyNs),
                firstResultMs = ms(sessionStartNs, firstResultNs),
                feedMs = ms(feeder.startedAtNs, feeder.finishedAtNs),
                tailMs = ms(eofNs, endNs),
                sttWallMs = ms(sessionStartNs, endNs),
                segments = segmentCount,
            )
        } finally {
            feeder.abort()
            runCatching { readSide.close() }
            runCatching { recognizer.destroy() }
        }
    }

    /** on-device 언어 모델 설치 여부를 조회하고, 없으면 다운로드를 요청한다. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun checkAndroidOnDeviceSupport(locale: Locale): String {
        val recognizer = AndroidSpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        }
        val out = CompletableDeferred<String>()
        return try {
            recognizer.checkRecognitionSupport(
                intent,
                ContextCompat.getMainExecutor(this),
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val tag = locale.toLanguageTag()
                        val installed = recognitionSupport.installedOnDeviceLanguages
                        val pending = recognitionSupport.pendingOnDeviceLanguages
                        val supported = recognitionSupport.supportedOnDeviceLanguages
                        val has = installed.any { it.replace('_', '-').equals(tag, true) }
                        if (!has) runCatching { recognizer.triggerModelDownload(intent) }
                        val extra =
                            if (has) "" else " → $tag 미설치, 다운로드를 요청했다. 잠시 후 재실행할 것."
                        out.complete(
                            "on-device 언어: installed=$installed / pending=$pending " +
                                    "/ supported=$supported" + extra,
                        )
                    }

                    override fun onError(error: Int) {
                        out.complete("checkRecognitionSupport 실패: ${errorName(error)}")
                    }
                },
            )
            withTimeoutOrNull(20.seconds) { out.await() } ?: "checkRecognitionSupport 응답 없음(시간 초과)"
        } finally {
            runCatching { recognizer.destroy() }
        }
    }

    // ─────────────────────────── PCM 공급기 ───────────────────────────
    /**
     * 목적: PCM 을 파이프에 일정 속도로 흘려보낸다. 공급이 끝나면 write 쪽이 닫혀 EOF → 세션 종료.
     * 비고: 코루틴 대신 전용 데몬 스레드를 쓴다. 인식기가 먼저 죽어 파이프가 막히면
     *       블로킹 write 는 코루틴 취소로 풀리지 않아 배치 전체가 멈추기 때문이다.
     * change(add)-hyungchul-20260820: 공급 시작/종료 시각을 남겨 tail latency 계산에 쓴다.
     */
    private class PcmFeeder(
        private val writeSide: ParcelFileDescriptor,
        private val pcm: ByteArray,
        private val chunkBytes: Int,
        private val chunkDelayMs: Long,
        private val onFed: (Double) -> Unit,
    ) {
        @Volatile
        private var aborted = false

        @Volatile
        private var started = false

        @Volatile
        var startedAtNs: Long = 0L
            private set

        @Volatile
        var finishedAtNs: Long = 0L
            private set

        val finished = CompletableDeferred<Unit>()

        private val thread = Thread({
            try {
                feed()
            } finally {
                finishedAtNs = System.nanoTime()
                onFed(1.0)
                finished.complete(Unit)
            }
        }, "pcm-feeder").apply { isDaemon = true }

        fun start() {
            started = true
            startedAtNs = System.nanoTime()
            thread.start()
        }

        /** 시작 전에 중단되면 write 쪽 FD 가 새므로 여기서 직접 닫는다. */
        fun abort() {
            aborted = true
            if (started) {
                thread.interrupt()
            } else {
                runCatching { writeSide.close() }
                finished.complete(Unit)
            }
        }

        private fun feed() {
            ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { os ->
                var offset = 0
                while (offset < pcm.size) {
                    if (aborted) return
                    val end = minOf(offset + chunkBytes, pcm.size)
                    try {
                        os.write(pcm, offset, end - offset)
                        os.flush()
                    } catch (_: IOException) {
                        // change-hyungchul-20260828-1000: 예외 객체를 쓰지 않으므로 _ 로 둔다.
                        return // 인식기가 먼저 닫힘(EPIPE)
                    }
                    offset = end
                    if (pcm.isNotEmpty()) onFed(offset.toDouble() / pcm.size)
                    if (chunkDelayMs > 0) {
                        try {
                            Thread.sleep(chunkDelayMs)
                        } catch (_: InterruptedException) {
                            // change-hyungchul-20260828-1000: 예외 객체를 쓰지 않으므로 _ 로 둔다.
                            return
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────── 입력 폴더 탐색 ───────────────────────────
    private data class AudioItem(
        val uri: Uri,
        val relPath: List<String>,
        val nameNoExt: String,
        val sizeBytes: Long,   // change(add)-hyungchul-20260820: 원본 파일 크기(CSV src_bytes)
    )

    private fun collectAudio(dir: DocumentFile, prefix: List<String>, acc: MutableList<AudioItem>) {
        for (child in dir.listFiles()) {
            if (child.isDirectory) {
                collectAudio(child, prefix + (child.name ?: "unknown"), acc)
            } else if (child.isFile) {
                val name = child.name ?: continue
                val dot = name.lastIndexOf('.')
                val ext = if (dot >= 0) name.substring(dot + 1).lowercase(Locale.US) else ""
                if (ext in audioExtensions) {
                    val base = if (dot >= 0) name.substring(0, dot) else name
                    acc.add(AudioItem(child.uri, prefix, base, child.length()))
                }
            }
        }
    }

    // ─────────────────────────── 출력 저장 ───────────────────────────
    // change-hyungchul-20260826-1100: variant 목록을 받는다(빈 목록이면 기존 경로와 완전히 동일)
    private fun saveResult(
        outputRoot: DocumentFile,
        modeFolder: String,
        variant: List<String>,
        item: AudioItem,
        text: String,
    ) {
        val segments = mutableListOf(algoName, modeFolder)
        segments.addAll(variant)
        segments.addAll(item.relPath)
        val dir = ensureDir(outputRoot, segments)
        writeBytes(dir, item.nameNoExt + ".txt", text.toByteArray(Charsets.UTF_8))
    }

    private fun ensureDir(root: DocumentFile, segments: List<String>): DocumentFile {
        var cur = root
        for (seg in segments) {
            val existing = cur.findFile(seg)
            cur = if (existing != null && existing.isDirectory) existing
            else cur.createDirectory(seg) ?: throw IOException("폴더 생성 실패: $seg")
        }
        return cur
    }

    private fun writeBytes(
        dir: DocumentFile,
        fileName: String,
        bytes: ByteArray,
        mime: String = "text/plain",
    ) {
        dir.findFile(fileName)?.let { if (it.isFile) it.delete() }
        val file = dir.createFile(mime, fileName)
            ?: throw IOException("파일 생성 실패: $fileName")
        contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
            ?: throw IOException("파일 쓰기 실패: $fileName")
    }

    // ─────────────────────────── 유틸 ───────────────────────────
    /** 두 nanoTime 사이를 ms 로. 끝값이 0(미측정)이면 -1 을 돌려 CSV 에서 구분되게 한다. */
    private fun ms(fromNs: Long, toNs: Long): Long =
        if (fromNs <= 0L || toNs <= 0L || toNs < fromNs) -1L else (toNs - fromNs) / 1_000_000

    private fun updateProgress(fraction: Double) {
        val clamped = fraction.coerceIn(0.0, 1.0)
        runOnUiThread {
            progressBar.progress = (clamped * 1000).toInt()
            progressText.text = String.format(Locale.US, "%.1f%%", clamped * 100)
        }
    }

    private fun statusName(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    private fun errorName(error: Int): String = when (error) {
        AndroidSpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT(1)"
        AndroidSpeechRecognizer.ERROR_NETWORK -> "NETWORK(2)"
        AndroidSpeechRecognizer.ERROR_AUDIO -> "AUDIO(3)"
        AndroidSpeechRecognizer.ERROR_SERVER -> "SERVER(4)"
        AndroidSpeechRecognizer.ERROR_CLIENT -> "CLIENT(5)"
        AndroidSpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT(6)"
        AndroidSpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH(7)"
        AndroidSpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY(8)"
        AndroidSpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS(9)"
        AndroidSpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS(10)"
        AndroidSpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED(11)"
        AndroidSpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED(12)"
        AndroidSpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE(13)"
        14 -> "CANNOT_CHECK_SUPPORT(14)" // API 33 상수. minSdk 31 이므로 리터럴로 둔다.
        else -> "UNKNOWN($error)"
    }

    /**
     * add-hyungchul-20260914-1500
     * 목적: ML Kit 오류 메시지가 "말이 없다" 판정인지 가려낸다.
     * 입력: message — 엔진이 준 오류 문자열
     * 출력: 없음
     * 리턴: 음성 미검출 판정이면 true
     * 비고: 실측된 문자열 예:
     *   "Speech recognition engine is closed due to internal error: ERROR_TYPE_NO_SPEECH_DETECTED"
     */
    /**
     * add-hyungchul-20260916-1000
     * 목적: "엔진이 죽어서 난 실패" 인지 판정한다.
     * 입력: e — 잡은 예외
     * 출력: 없음
     * 리턴: 엔진이 죽은 것으로 보이면 true
     *
     * ★ 이 판정이 필요한 이유 (2026-09-16 실측)
     *   엔진이 한도를 넘으면 증상이 두 단계로 진행된다.
     *     1단계 — startRecognition 이 아무것도 내보내지 않는다 → TimeoutCancellationException
     *     2단계 — checkStatus() 조차 대답하지 않는다            → IOException("...응답하지 않았다")
     *   기존 코드는 1단계(TimeoutCancellationException)만 세었다.
     *   그래서 2단계로 넘어가는 순간 연속 카운터가 0으로 초기화되어 배치 중단이 걸리지 않았고,
     *   죽은 엔진에 대고 남은 파일을 끝까지 다 시도했다(실측: 5건을 헛돌렸다).
     */
    private fun isEngineDead(e: Throwable): Boolean {
        if (e is TimeoutCancellationException) return true          // 1단계
        val m = e.message ?: return false
        return m.contains("응답하지 않았다") || m.contains("무응답")  // 2단계
    }

    private fun isNoSpeechError(message: String): Boolean =
        message.contains("NO_SPEECH_DETECTED", ignoreCase = true) ||
                message.contains("ERROR_TYPE_NO_SPEECH", ignoreCase = true)

    /**
     * add-hyungchul-20260914-1500
     * 목적: 이 PCM 을 현재 공급 속도로 흘려보낼 때 걸려야 하는 "이론상" 시간을 계산한다.
     * 입력: pcmBytes — STT 에 넣는 PCM 바이트 수, chunkDelayMs — chunk 사이 지연(ms)
     * 출력: 없음
     * 리턴: 이론 공급시간(ms). 지연 0(MAX)이거나 빈 PCM 이면 -1.
     * 비고: 실제 feed_ms 가 이 값보다 크게 늘어났다면 엔진이 파이프를 안 읽어
     *       write 가 막힌 것이다. 그 경우 stt_wall_ms 와 rtf 는 엔진 속도가 아니라
     *       "엔진이 멈춰 있던 시간" 을 재게 되므로 그대로 쓰면 안 된다.
     */
    private fun expectedFeedMs(pcmBytes: Int, chunkDelayMs: Long): Long {
        if (chunkDelayMs <= 0L || pcmBytes <= 0) return -1
        val chunks = (pcmBytes + CHUNK_BYTES - 1) / CHUNK_BYTES   // 올림 나눗셈
        return chunks * chunkDelayMs
    }

    private fun describe(e: Throwable): String {
        val m = e.message
        return if (m.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $m"
    }

    private fun localeForSelection(pos: Int): Locale = when (pos) {
        0 -> Locale.US
        1 -> Locale.KOREA
        else -> {
            val def = Locale.getDefault()
            if (def.language == "ko") Locale.KOREA else Locale.US
        }
    }

    private fun displayRel(item: AudioItem): String {
        val p = if (item.relPath.isEmpty()) "" else item.relPath.joinToString("/") + "/"
        return p + item.nameNoExt
    }

    private fun prettyPath(uri: Uri): String {
        val last = uri.lastPathSegment ?: uri.toString()
        return last.substringAfterLast(':').ifEmpty { last }
    }

    private fun appendLog(line: String) {
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
    private fun openSpeechServiceSettings() {
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

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    companion object {
        /** 16 kHz × 2바이트 = 32,000 바이트/초 */
        private const val BYTES_PER_SEC = 32_000

        /** 100 ms 분량 = 3,200 바이트 */
        private const val CHUNK_BYTES = 3_200

        /** 중간 저장 주기(파일 수) */
        private const val CSV_FLUSH_EVERY = 10

        /**
         * 플랫폼 경로에서 partial 결과를 받을지 여부.
         * true 로 두면 first_result_ms(TTFT)를 제대로 잴 수 있다. partial 텍스트는 결과에 넣지 않는다.
         * 이전 실행과 결과를 엄격히 동일하게 맞춰야 한다면 false 로 되돌린다.
         */
        private const val REQUEST_PARTIAL_RESULTS = true

        // add-hyungchul-20260914-1500
        /** 인식기 close() 를 기다려 주는 최대 시간(ms). 넘으면 배치를 계속 진행한다. */
        private const val RECOGNIZER_CLOSE_TIMEOUT_MS = 5_000L

        /** 이론 공급시간의 몇 배를 넘으면 "공급 정체" 로 보고 경고할지. */
        private const val FEED_STALL_WARN_RATIO = 2.0

        // add-hyungchul-20260914-2130 : STT 타임아웃 계산 상수 (sttTimeoutSec() 주석에 근거 정리)
        /** 타임아웃 고정 여유(초). 엔진 준비 + 공급 EOF 후 처리분(실측 최대 0.287초)을 덮는다. */
        private const val STT_TIMEOUT_FIXED_SEC = 45.0

        /** (오디오 또는 이론 공급시간 중 큰 값) 에 곱할 배율. 관측 최악 1.67배 대비 1.5배 여유. */
        private const val STT_TIMEOUT_FACTOR = 2.5

        /** 아주 짧은 음원에서도 보장할 최소 타임아웃(초). */
        private const val STT_TIMEOUT_MIN_SEC = 90L

        /**
         * ML Kit segments 결함 경계(초).
         * 실측: 60.72초·61.32초는 정상, 67.06초는 앞부분 유실. 보수적으로 61초를 경계로 쓴다.
         */
        private const val MLKIT_SEGMENT_TRUNCATION_SEC = 61.0

        // add-hyungchul-20260914-2350
        /**
         * 잘림 판정에 쓰는 글자 밀도 하한(자/초).
         * 실측: 정상 행은 7.1~7.4자/초, 잘린 행은 0.3~1.6자/초 였다. 그 사이를 3.0 으로 가른다.
         * 영어(공백 포함 약 12자/초)에도 안전한 값이다.
         */
        private const val MLKIT_TRUNCATION_CHARS_PER_SEC = 3.0

        /** 사전 점검(warm-up) 에 쓸 무음 길이(초). */
        private const val WARMUP_SECONDS = 1

        /**
         * 사전 점검의 공급 지연(ms/chunk).
         * change-hyungchul-20260915-1400 : 10 ms(×10) → 100 ms(실시간).
         * 공식 문서가 요구하는 속도가 실시간이므로 점검도 같은 속도로 한다. 1초 무음이니 1초면 끝난다.
         */
        private const val WARMUP_FEED_DELAY_MS = 100L

        /**
         * 사전 점검 제한 시간(ms). 이 안에 아무 응답이 없으면 엔진이 먹통이다.
         * change-hyungchul-20260915-1400 : checkStatus 제한(15초)보다 길게 잡아야
         * "checkStatus 무응답" 이라는 더 정확한 원인이 먼저 잡힌다.
         */
        private const val WARMUP_TIMEOUT_MS = 30_000L

        /**
         * 연속 몇 건이 실패하면 배치를 끝낼지.
         * change-hyungchul-20260916-1000 : 3 → 2.
         * 실행 3회 전부 "한 번 죽으면 끝까지 회복 없음" 이었다. 3건까지 기다릴 이유가 없다.
         * 세션 한도를 이미 넘긴 뒤라면 이 값과 무관하게 1건에서 바로 끝낸다.
         */
        private const val CONSECUTIVE_TIMEOUT_ABORT = 2

        // add-hyungchul-20260915-1400
        /** checkStatus() 응답 제한(ms). 정상이면 수십 ms 안에 온다. */
        private const val FEATURE_STATUS_TIMEOUT_MS = 15_000L

        /** 모델 다운로드 제한(ms). 최초 1회만 걸리는 경로다. */
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = 600_000L

        /** stopRecognition() 을 기다려 줄 시간(ms). */
        private const val STOP_RECOGNITION_TIMEOUT_MS = 5_000L

        // add-hyungchul-20260915-2200 : SODA 세션 한도 대응
        // change-hyungchul-20260916-1800 : 2026-09-16 logcat 분석으로 엔진과 정지 지점을 확정했다.
        //   엔진 = com.google.android.tts 안의 SODA (AICore 아님. "aicore streaming: false" 로 확인)
        //   정지 지점 = SodaSpeechRecognizer.SodaDetectionHandler.blockingReconnect
        //     정상:  Initialize Soda with language pack directory
        //            → blockingReconnect → ConcurrentSodaManager#connect → Creating SODA → ...
        //     먹통:  Initialize Soda with language pack directory 까지만 찍히고 그 다음이 전혀 없다.
        //            엔진 스스로도 "Recognition not started, drop cancelRecognition" 이라고 남긴다.
        //   회복 조건 = tts 프로세스 재시작뿐. 18분을 기다려도 같은 PID 에서는 회복되지 않았다.
        /**
         * 실측으로 확인한 세션 한도.
         * D20 실행과 REALTIME 실행 모두 "35세션 성공 → 36번째부터 먹통" 으로 동일했다.
         * 커뮤니티 보고(googlesamples/mlkit issue #1070)의 "약 36~41회" 와도 일치한다.
         */
        private const val QUOTA_BUDGET_SESSIONS = 35

        /** 이 세션 수에 닿으면 미리 경고한다(한도의 약 80%). */
        private const val QUOTA_WARN_SESSIONS = 28

        /**
         * 할당량에 막혔을 때 쉬어 줄 시간(ms).
         * 위 이슈에서 약 2.5분이면 할당량이 거의 원래 크기로 회복된다고 보고되었다. 여유를 두어 3분으로 잡는다.
         */
        private const val QUOTA_COOLDOWN_MS = 180_000L

        /** 파일 1건당 할당량 재시도 횟수. */
        private const val QUOTA_RETRY_MAX = 1

        // add-hyungchul-20260916-1800
        /**
         * 실제 인식을 수행하는 서비스 패키지.
         * logcat 으로 확인: ML Kit BASIC → com.google.android.tts 안의 SODA 로 내려간다.
         * (com.google.android.as 나 com.google.android.aicore 가 아니다)
         */
        private const val SPEECH_SERVICE_PACKAGE = "com.google.android.tts"

        // add-hyungchul-20260826-1100
        /** DPDFNet 변형 모델 키. assets 파일명은 baseline→dpdfnet_baseline.onnx, 2→dpdfnet2.onnx 이다. */
        private val DPDFNET_VARIANTS = listOf("baseline", "2", "4", "8")

        /** 스피너에 보여줄 이름(파일 크기를 같이 적어 고르기 쉽게 한다). */
        private val DPDFNET_VARIANT_LABELS = listOf(
            "dpdfnet_baseline.onnx  (8.4 MB, 가장 빠름)",
            "dpdfnet2.onnx  (10.2 MB)",
            "dpdfnet4.onnx  (11.7 MB)",
            "dpdfnet8.onnx  (14.6 MB, 가장 무거움)",
        )
    }
}