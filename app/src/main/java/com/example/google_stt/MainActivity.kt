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
 *     그래서 Feed 속도(REALTIME/FAST/MAX)를 UI에서 고를 수 있게 하고 CSV에 함께 남긴다.
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
 * ※ 이 3개 경로 중 어느 것도 Google Cloud 인증(서비스 계정 JSON, API Key, OAuth 토큰)을 사용하지 않는다.
 */
package com.example.google_stt

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.net.Uri
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    private val feeds = listOf(
        FeedConfig("REALTIME", 100L),
        FeedConfig("D30", 30L),
        FeedConfig("D15", 15L),
        FeedConfig("D10", 10L),
        FeedConfig("D8", 8L),
        FeedConfig("D6", 6L),
        FeedConfig("D5", 5L),
        FeedConfig("D4", 4L),
        FeedConfig("D3", 3L),
        FeedConfig("D2", 2L),
        FeedConfig("D1", 1L),
        FeedConfig("MAX", 0L),
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

    /** preset 을 코드로 채워 넣는 동안 TextWatcher 가 Custom 으로 되돌리지 않게 막는 재진입 가드 */
    private var applyingVadPreset = false

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

        setupVadUi()   // add-hyungchul-20260825-1430

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
     * 동작: 시작 상태는 "VAD OFF + ASR Safe 값이 채워진 상태"다.
     *       값을 직접 고치면 Profile 이 Custom 으로 자동 전환된다.
     */
    private fun setupVadUi() {
        val profiles = VadProfile.values()                              // 표시 순서 = enum 선언 순서
        vadProfileSpinner.adapter = ArrayAdapter(                        // Profile 목록을 스피너에 채운다
            this,
            android.R.layout.simple_spinner_dropdown_item,
            profiles.map { it.displayName },
        )

        applyingVadPreset = true                                         // 초기 세팅 중에는 watcher 를 무시한다
        vadProfileSpinner.setSelection(profiles.indexOf(VadProfile.ASR_SAFE))
        applyVadPreset(VadProfile.ASR_SAFE)                              // 기본 추천값을 입력창에 채운다
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
                profile = VadProfile.ASR_SAFE,
                config = VadPresets.config(VadProfile.ASR_SAFE),
                outputFolder = null,
            )
        }

        val profiles = VadProfile.values()
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
     * 리턴: "vad_custom_t50_sp100_si300_pad200_maxinf" 형태의 폴더명
     */
    private fun customVadFolder(config: VadConfig): String {
        val threshold100 = (config.threshold * 100).roundToInt()          // 0.50 → 50 (폴더명에 소수점을 피한다)
        val maxText = config.maxSpeechDurationSec
            ?.let { String.format(Locale.US, "%.1f", it).replace('.', 'p') }
            ?: "inf"
        return "vad_custom_t${threshold100}_sp${config.minSpeechDurationMs}" +
                "_si${config.minSilenceDurationMs}_pad${config.speechPadMs}_max$maxText"
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
                    "tools/download_silero_vad_model.sh 를 실행한 뒤 다시 빌드하세요."
            appendLog("[vad][경고] $msg")
            toast(msg)
        }
        return exists
    }

    /** 로그/run_meta 에 남길 VAD 설정 한 줄 요약. */
    private fun vadConfigText(c: VadConfig): String =
        "threshold=${c.threshold}, neg=${c.negThreshold}, minSpeech=${c.minSpeechDurationMs}ms, " +
                "minSilence=${c.minSilenceDurationMs}ms, pad=${c.speechPadMs}ms, " +
                "maxSpeech=${c.maxSpeechDurationSec?.toString() ?: "Unlimited"}"

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

        running = true
        startButton.isEnabled = false
        logView.text = ""
        updateProgress(0.0)
        toast(getString(R.string.msg_stt_started))

        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            val csv = StringBuilder(SttTelemetry.CSV_HEADER)
            var vadProcessor: SileroVadProcessor? = null   // add-hyungchul-20260825-1430
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
                if (modelConfig.engine == SttEngine.ML_KIT && feed.delayMs < 100L) {
                    appendLog(
                        "[warn] ML Kit 공식 문서는 '실시간 속도' 공급을 요구한다. " +
                                "FAST/MAX 는 결과가 잘릴 수 있으니 몇 건으로 검증 후 사용할 것.",
                    )
                }

                // 실행 전 1회 가용성 점검 — 어떤 엔진이 실제로 쓰이는지 로그와 run_meta 에 남긴다.
                val preflightText = preflight(modelConfig, locale)

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
                        val dir = ensureRunDir(outputRoot, modelFolder, vadSelection.outputFolder)
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
                                "onnxruntime_version" to (vadProcessor?.runtimeVersion ?: ""),
                                "silero_model_sha256" to (vadProcessor?.modelSha256 ?: ""),
                                "output_variant" to (vadSelection.outputFolder ?: "baseline"),
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
                    fillVadConfig(row, vadSelection)   // add-hyungchul-20260825-1430
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
                                outputRoot, modelFolder, vadSelection.outputFolder, item,
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

                    // ── add-hyungchul-20260825-1430 : 2) Silero VAD (선택) ──
                    // sttPcm 이 실제로 STT 에 들어가는 PCM 이다. OFF 면 decoded.pcm 과 완전히 같은 객체다.
                    var sttPcm = decoded.pcm
                    if (vadSelection.enabled) {
                        try {
                            val processor = vadProcessor
                                ?: throw IllegalStateException("Silero VAD processor 가 초기화되지 않았습니다.")
                            val vadResult = withContext(Dispatchers.Default) {
                                processor.process(decoded.pcm, vadSelection.config)
                            }
                            sttPcm = vadResult.pcm
                            row.vadProcessMs = vadResult.processMs
                            row.vadOriginalSec = vadResult.originalSec
                            row.vadOutputSec = vadResult.outputSec
                            row.vadDetectedSpeechSec = vadResult.detectedSpeechSec
                            row.vadRemovedSec = vadResult.removedSec
                            row.vadRemovedRatio = vadResult.removedRatio
                            row.vadSegmentCount = vadResult.segments.size

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
                                        outputRoot, modelFolder, vadSelection.outputFolder, item, "",
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
                                                vadSelection.outputFolder, csv.toString(),
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
                                    outputRoot, modelFolder, vadSelection.outputFolder, item,
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
                        // VAD OFF baseline: STT 입력 PCM 을 단 1 byte 도 바꾸지 않는다.
                        row.vadProcessMs = 0
                        row.vadOutputSec = decoded.durationSec
                        row.vadDetectedSpeechSec = -1.0   // 미측정(-1)과 0.0 을 구분한다
                        row.vadRemovedSec = 0.0
                        row.vadRemovedRatio = 0.0
                        row.vadSegmentCount = 0
                    }

                    // ── 3) STT ──
                    val baseUnits = completedUnits
                    try {
                        val outcome = when (modelConfig.engine) {
                            SttEngine.ML_KIT -> {
                                val mode = modelConfig.mlKitMode
                                    ?: throw IOException("ML Kit 모드 설정이 없습니다.")
                                transcribeMlKit(sttPcm, locale, mode, feed) { fed ->
                                    updateProgress((baseUnits + fed) / totalUnits.toDouble())
                                }
                            }

                            SttEngine.ANDROID_PLATFORM_ON_DEVICE -> {
                                transcribeAndroidOnDevice(sttPcm, locale, feed) { fed ->
                                    updateProgress((baseUnits + fed) / totalUnits.toDouble())
                                }
                            }
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

                        withContext(Dispatchers.IO) {
                            saveResult(
                                outputRoot, modelFolder, vadSelection.outputFolder, item, outcome.text,
                            )
                        }
                        ok++
                        appendLog(
                            getString(R.string.log_done, modelFolder, relName) +
                                    "  (audio ${"%.1f".format(Locale.US, decoded.durationSec)}s / " +
                                    "stt-in ${"%.1f".format(Locale.US, sttPcm.size / BYTES_PER_SEC.toDouble())}s / " +
                                    "tail ${outcome.tailMs}ms / wall ${outcome.sttWallMs}ms)",
                        )
                    } catch (e: Exception) {
                        val msg = describe(e)
                        Log.e(logTag, "transcribe failed: $modelFolder/$relName", e)
                        withContext(Dispatchers.IO) {
                            saveResult(
                                outputRoot, modelFolder, vadSelection.outputFolder, item, "[ERROR] $msg",
                            )
                        }
                        row.status = "ERROR"
                        row.error = msg
                        fail++
                        appendLog(getString(R.string.log_fail, modelFolder, relName, msg))
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
                                    outputRoot, modelFolder, vadSelection.outputFolder, csv.toString(),
                                )
                            }
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    writeCsv(outputRoot, modelFolder, vadSelection.outputFolder, csv.toString())
                }

                updateProgress(1.0)
                toast(getString(R.string.msg_stt_done, ok, fail))
            } catch (e: Exception) {
                Log.e(logTag, "run failed", e)
                // add-hyungchul-20260825-1620
                // 토스트는 금방 사라진다. 실패 원인을 화면 로그에도 남겨 원인을 놓치지 않게 한다.
                appendLog("[실패] 실행이 중단되었습니다 — ${describe(e)}")
                // 예외로 빠져나가도 지금까지 모은 계측은 남긴다.
                runCatching {
                    val outputRoot = DocumentFile.fromTreeUri(this@MainActivity, outUri)
                    if (outputRoot != null) {
                        withContext(Dispatchers.IO) {
                            writeCsv(outputRoot, modelFolder, vadSelection.outputFolder, csv.toString())
                        }
                    }
                }
                toast(getString(R.string.msg_error, describe(e)))
            } finally {
                runCatching { vadProcessor?.close() }   // add-hyungchul-20260825-1430
                running = false
                startButton.isEnabled = true
            }
        }
    }

    /**
     * add-hyungchul-20260825-1430
     * 목적: 이번 실행의 결과 폴더를 만든다.
     * 입력: outputRoot(출력 트리), modelFolder(base/advanced/android_ondevice),
     *       vadFolder — null 이면 VAD OFF 이며 기존 경로를 그대로 쓴다.
     * 리턴: output/google/<모드>[/vad_<profile>] DocumentFile
     */
    private fun ensureRunDir(
        outputRoot: DocumentFile,
        modelFolder: String,
        vadFolder: String?,
    ): DocumentFile {
        val segments = mutableListOf(algoName, modelFolder)
        if (vadFolder != null) segments.add(vadFolder)
        return ensureDir(outputRoot, segments)
    }

    // change-hyungchul-20260825-1430: vadFolder 인자 추가(null 이면 기존 동작과 동일)
    private fun writeCsv(
        outputRoot: DocumentFile,
        modelFolder: String,
        vadFolder: String?,
        body: String,
    ) {
        val dir = ensureRunDir(outputRoot, modelFolder, vadFolder)
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
                    val st = runCatching {
                        SpeechRecognition.getClient(
                            speechRecognizerOptions {
                                this.locale = locale
                                preferredMode = m
                            },
                        ).use { it.checkStatus() }
                    }
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
    )

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

        return SpeechRecognition.getClient(options).use { recognizer ->
            val tCheck = System.nanoTime()
            var status = recognizer.checkStatus()
            val checkStatusMs = (System.nanoTime() - tCheck) / 1_000_000
            Log.i(logTag, "checkStatus=${statusName(status)} (mode=$mode, locale=$locale)")

            var downloadMs = -1L
            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                val tDl = System.nanoTime()
                recognizer.download().collect { ds ->
                    Log.i(logTag, "download status: $ds")
                    if (ds is DownloadStatus.DownloadFailed) {
                        throw IOException("모델 다운로드 실패: ${ds.e.message ?: ds.e}")
                    }
                }
                downloadMs = (System.nanoTime() - tDl) / 1_000_000
                status = recognizer.checkStatus()
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

            var sessionStartNs = 0L
            var endNs = 0L

            try {
                val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(readSide) }
                val approxSec = pcm.size / BYTES_PER_SEC + 1
                val timeout = (approxSec.toLong() * 2 + 180).seconds

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
                        collectJob.join()
                        endNs = System.nanoTime()
                    }
                }
            } finally {
                feeder.abort()
                // readSide 를 닫지 않으면 파일마다 FD 가 새어 배치 도중 EMFILE 로 실패한다.
                runCatching { readSide.close() }
            }

            val result = sb.toString().trim()
            val err = errorMessage
            if (result.isEmpty() && err != null) throw IOException(err)

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
            )
        }
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

        val approxSec = pcm.size / BYTES_PER_SEC + 1
        val timeout = (approxSec.toLong() * 2 + 180).seconds

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
                    } catch (e: IOException) {
                        return // 인식기가 먼저 닫힘(EPIPE)
                    }
                    offset = end
                    if (pcm.isNotEmpty()) onFed(offset.toDouble() / pcm.size)
                    if (chunkDelayMs > 0) {
                        try {
                            Thread.sleep(chunkDelayMs)
                        } catch (e: InterruptedException) {
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
    // change-hyungchul-20260825-1430: vadFolder 인자 추가(null 이면 기존 경로와 완전히 동일)
    private fun saveResult(
        outputRoot: DocumentFile,
        modeFolder: String,
        vadFolder: String?,
        item: AudioItem,
        text: String,
    ) {
        val segments = mutableListOf(algoName, modeFolder)
        if (vadFolder != null) segments.add(vadFolder)
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
    }
}