/*
 * 파일명: MainActivity.kt
 * 목적 및 기능:
 * - 입력 폴더의 음성 파일을 16 kHz/mono/PCM16으로 변환한 뒤 Google On-device STT로 전사한다.
 * - ML Kit GenAI Speech Recognition의 Basic/Advanced 모드와 Android Platform
 *   SpeechRecognizer#createOnDeviceSpeechRecognizer() 경로를 선택하여 비교 평가할 수 있다.
 * - 결과를 output/google/<모드>/<입력 하위 폴더>/<파일명>.txt 구조로 저장한다.
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
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity() {

    private val logTag = "GoogleStt"

    // 처리 대상 오디오 확장자
    private val audioExtensions =
        setOf("mp3", "wav", "m4a", "flac", "ogg", "aac", "mp4", "wma", "opus")

    // 출력 최상위(요청하신 구조: output/google/base/... , output/google/advanced/...)
    private val algoName = "google"

    // change(add)-hyungchul-20260812-1709
    // 목적: ML Kit 모드와 Android Platform On-device 경로를 같은 선택 목록에서 구분한다.
    private enum class SttEngine {
        ML_KIT,
        ANDROID_PLATFORM_ON_DEVICE,
    }

    // change(add)-hyungchul-20260812-1709
    // 목적: 출력 폴더명, 실행 엔진, ML Kit 모드값을 하나의 설정으로 관리한다.
    private data class ModelConfig(
        val folder: String,
        val engine: SttEngine,
        val mlKitMode: Int? = null,
    )

    // 선택 가능한 모델/경로 — Model 스피너 항목과 순서 동일
    private val models = listOf(
        ModelConfig(
            folder = "base",
            engine = SttEngine.ML_KIT,
            mlKitMode = SpeechRecognizerOptions.Mode.MODE_BASIC,
        ),
        ModelConfig(
            folder = "advanced",
            engine = SttEngine.ML_KIT,
            mlKitMode = SpeechRecognizerOptions.Mode.MODE_ADVANCED,
        ),
        // change(add)-hyungchul-20260812-1709
        // Android Framework가 제공하는 명시적 on-device SpeechRecognizer 경로이다.
        ModelConfig(
            folder = "android_ondevice",
            engine = SttEngine.ANDROID_PLATFORM_ON_DEVICE,
        ),
    )

    private var inputTreeUri: Uri? = null
    private var outputTreeUri: Uri? = null

    private lateinit var inputPathView: TextView
    private lateinit var outputPathView: TextView
    private lateinit var langSpinner: Spinner
    private lateinit var modelSpinner: Spinner
    private lateinit var startButton: Button
    private lateinit var logView: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView

    private var running = false

    // ── 런타임 권한 요청 (RECORD_AUDIO) ──
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startTranscription()
            } else {
                toast("RECORD_AUDIO 권한이 필요합니다. 설정에서 허용해주세요.")
            }
        }

    // ── 폴더 선택기 (Storage Access Framework) ──
    private val inputPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
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
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
        startButton = findViewById(R.id.startButton)
        logView = findViewById(R.id.logView)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        progressBar.max = 1000   // 0.1% 단위(0~1000)로 표시
        updateProgress(0.0)

        // Lang: 영어 / 한국어 / auto
        langSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.lang_english),
                getString(R.string.lang_korean),
                getString(R.string.lang_auto),
            )
        )

        // Model: Basic / Advanced / Android Platform On-device — 하나만 선택
        modelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.model_base),
                getString(R.string.model_advanced),
                // change(add)-hyungchul-20260812-1709
                getString(R.string.model_android_ondevice),
            )
        )

        findViewById<Button>(R.id.inputButton).setOnClickListener { inputPicker.launch(null) }
        findViewById<Button>(R.id.outputButton).setOnClickListener { outputPicker.launch(null) }
        startButton.setOnClickListener { startTranscription() }
    }

    private fun startTranscription() {
        if (running) {
            toast(getString(R.string.msg_already_running))
            return
        }

        // ★ RECORD_AUDIO 런타임 권한 체크 (ML Kit / Android SpeechRecognizer 모두 요구함)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val inUri = inputTreeUri
        val outUri = outputTreeUri
        if (inUri == null || outUri == null) {
            toast(getString(R.string.msg_pick_folders))
            return
        }
        val locale = localeForSelection(langSpinner.selectedItemPosition)

        // 선택한 모델/경로 1개만 실행
        val modelIndex = modelSpinner.selectedItemPosition.coerceIn(0, models.size - 1)
        val modelConfig = models[modelIndex]
        val modelFolder = modelConfig.folder

        running = true
        startButton.isEnabled = false
        logView.text = ""
        updateProgress(0.0)
        toast(getString(R.string.msg_stt_started))   // 시작 Toast

        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            try {
                val inputRoot = DocumentFile.fromTreeUri(this@MainActivity, inUri)
                val outputRoot = DocumentFile.fromTreeUri(this@MainActivity, outUri)
                if (inputRoot == null || outputRoot == null) {
                    toast(getString(R.string.msg_folder_open_fail))
                    return@launch
                }

                val items = withContext(Dispatchers.IO) {
                    val acc = mutableListOf<AudioItem>()
                    collectAudio(inputRoot, emptyList(), acc)
                    acc
                }
                if (items.isEmpty()) {
                    toast(getString(R.string.msg_no_audio))
                    return@launch
                }
                appendLog(getString(R.string.log_found, items.size))
                appendLog(getString(R.string.log_model, modelFolder))

                // 전체 작업량 = 파일 수 (선택한 모델/경로 1개만 실행)
                val totalUnits = items.size
                var completedUnits = 0

                for (item in items) {
                    val relName = displayRel(item)

                    // 오디오 디코딩(16kHz/모노/16bit PCM)
                    val pcm = try {
                        withContext(Dispatchers.IO) {
                            AudioDecoder.decodeTo16kMonoPcm(applicationContext, item.uri)
                        }
                    } catch (e: Exception) {
                        val msg = describe(e)
                        Log.e(logTag, "decode failed: $relName", e)
                        saveResult(outputRoot, modelFolder, item, "[ERROR] 디코딩 실패: $msg")
                        fail++
                        completedUnits++
                        updateProgress(completedUnits.toDouble() / totalUnits)
                        appendLog(getString(R.string.log_decode_fail, relName, msg))
                        continue
                    }

                    val baseUnits = completedUnits
                    try {
                        // change(add)-hyungchul-20260812-1709
                        // 선택한 엔진에 따라 기존 ML Kit 또는 Android Platform On-device를 실행한다.
                        val text = when (modelConfig.engine) {
                            SttEngine.ML_KIT -> {
                                val mlKitMode = modelConfig.mlKitMode
                                    ?: throw IOException("ML Kit 모드 설정이 없습니다.")
                                transcribe(pcm, locale, mlKitMode) { fed ->
                                    updateProgress((baseUnits + fed) / totalUnits.toDouble())
                                }
                            }

                            SttEngine.ANDROID_PLATFORM_ON_DEVICE -> {
                                transcribeAndroidOnDevice(pcm, locale) { fed ->
                                    updateProgress((baseUnits + fed) / totalUnits.toDouble())
                                }
                            }
                        }

                        withContext(Dispatchers.IO) {
                            saveResult(outputRoot, modelFolder, item, text)
                        }
                        ok++
                        appendLog(getString(R.string.log_done, modelFolder, relName))
                    } catch (e: Exception) {
                        val msg = describe(e)
                        Log.e(logTag, "transcribe failed: $modelFolder/$relName", e)
                        withContext(Dispatchers.IO) {
                            saveResult(outputRoot, modelFolder, item, "[ERROR] $msg")
                        }
                        fail++
                        appendLog(getString(R.string.log_fail, modelFolder, relName, msg))
                    }
                    completedUnits++
                    updateProgress(completedUnits.toDouble() / totalUnits)
                }
                updateProgress(1.0)
                toast(getString(R.string.msg_stt_done, ok, fail))  // 완료 Toast
            } catch (e: Exception) {
                Log.e(logTag, "run failed", e)
                toast(getString(R.string.msg_error, describe(e)))
            } finally {
                running = false
                startButton.isEnabled = true
            }
        }
    }

    /** 전체 진행률(0.0 ~ 1.0)을 막대와 퍼센트 텍스트에 반영한다. */
    private fun updateProgress(fraction: Double) {
        val clamped = fraction.coerceIn(0.0, 1.0)
        runOnUiThread {
            progressBar.progress = (clamped * 1000).toInt()
            progressText.text = String.format(Locale.US, "%.1f%%", clamped * 100)
        }
    }

    /** FeatureStatus 정수값을 사람이 읽을 수 있는 이름으로 바꾼다. */
    private fun statusName(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE(사용 가능)"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE(다운로드 필요)"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING(다운로드 중)"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE(이 기기에서 미지원)"
        else -> "UNKNOWN($status)"
    }

    /** 예외 메시지가 비어 있어도 원인을 알 수 있도록 예외 종류를 함께 표시한다. */
    private fun describe(e: Throwable): String {
        val m = e.message
        return if (m.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $m"
    }

    // ─────────────────────────── ML Kit STT 한 건(파일 × 모드) ───────────────────────────
    private suspend fun transcribe(
        pcm: ByteArray,
        locale: Locale,
        mode: Int,
        onFed: (Double) -> Unit,
    ): String {
        val options: SpeechRecognizerOptions = speechRecognizerOptions {
            this.locale = locale
            preferredMode = mode
        }
        // SpeechRecognizer 는 Closeable → use{} 로 항상 close 되도록 한다
        return SpeechRecognition.getClient(options).use { recognizer ->
            // 1) 모델 준비 상태 확인 및 필요 시 다운로드
            var status = recognizer.checkStatus()
            Log.i(logTag, "checkStatus=${statusName(status)} (mode=$mode, locale=$locale)")
            if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                recognizer.download().collect { ds ->
                    Log.i(logTag, "download status: $ds")
                    if (ds is DownloadStatus.DownloadFailed) {
                        throw IOException("모델 다운로드 실패: ${ds.e.message ?: ds.e}")
                    }
                }
                status = recognizer.checkStatus()
                Log.i(logTag, "after download, checkStatus=${statusName(status)}")
            }
            if (status != FeatureStatus.AVAILABLE) {
                throw IOException(
                    "모델 사용 불가: ${statusName(status)}. " +
                        "Advanced(GenAI)는 Pixel 10 등 지원 기기가 필요하고, " +
                        "Basic 은 API 31+ 지원 기기가 필요합니다."
                )
            }

            // 2) 파이프 생성: 읽는 쪽은 인식기에, 쓰는 쪽은 오디오 공급에 사용
            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            val request = speechRecognizerRequest {
                audioSource = AudioSource.fromPfd(readSide)
            }

            val sb = StringBuilder()
            var errorSeen = false
            var errorMessage: String? = null

            // 파일 길이에 비례한 넉넉한 타임아웃(실시간 공급이라 오디오 길이만큼 소요)
            val approxSec = (pcm.size / 32000) + 1
            val timeout = (approxSec.toLong() * 2 + 120).seconds

            withTimeout(timeout) {
                coroutineScope {
                    val collectJob = launch {
                        recognizer.startRecognition(request).collect { resp ->
                            when (resp) {
                                is SpeechRecognizerResponse.FinalTextResponse -> {
                                    val t = resp.text
                                    if (t.isNotEmpty()) {
                                        if (sb.isNotEmpty()) sb.append(' ')
                                        sb.append(t)
                                    }
                                }
                                is SpeechRecognizerResponse.ErrorResponse -> {
                                    errorSeen = true
                                    errorMessage = resp.e.message ?: resp.e.toString()
                                    Log.e(logTag, "recognition ErrorResponse", resp.e)
                                }
                                else -> { /* Partial / Completed 는 무시 */ }
                            }
                        }
                    }

                    // 3) 오디오를 실시간 속도(~32KB/s)로 공급 → 끝나면 write 쪽 닫힘(EOF)
                    feedRealtime(writeSide, pcm, onFed)

                    // 4) 오디오 종료를 알림. 이미 EOF 로 끝났을 수 있으므로 실패는 무시.
                    try {
                        recognizer.stopRecognition()
                    } catch (e: Exception) {
                        Log.w(logTag, "stopRecognition ignored: ${describe(e)}")
                    }
                    collectJob.join()
                }
            }

            val result = sb.toString().trim()
            if (result.isEmpty() && errorSeen) {
                throw IOException(errorMessage ?: "인식 중 오류가 발생했습니다.")
            }
            result
        }
    }

    // change(add)-hyungchul-20260812-1709
    /**
     * 목적 및 기능:
     * - Android Framework의 createOnDeviceSpeechRecognizer()를 사용해 로컬 RecognitionService로 전사한다.
     * - API 33+의 EXTRA_AUDIO_SOURCE를 이용하여 마이크 대신 16 kHz/mono/PCM16 파일 데이터를 주입한다.
     * - EXTRA_SEGMENTED_SESSION을 사용하여 가능한 경우 긴 입력을 segment callback으로 수집한다.
     * 입력 변수:
     * - pcm: 16 kHz, mono, PCM 16-bit little-endian 오디오 데이터.
     * - locale: 인식할 언어(Locale.US 또는 Locale.KOREA 등).
     * - onFed: 오디오 공급 진행률(0.0~1.0) callback.
     * 출력/리턴:
     * - 인식된 최종 문자열을 반환한다.
     * 예외:
     * - API 33 미만, on-device RecognitionService 부재, RecognitionListener 오류 시 IOException 등을 발생시킨다.
     */
    private suspend fun transcribeAndroidOnDevice(
        pcm: ByteArray,
        locale: Locale,
        onFed: (Double) -> Unit,
    ): String {
        // 파일 오디오 주입 EXTRA_AUDIO_SOURCE는 API 33부터 제공된다.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw IOException(
                "Android Platform On-device의 파일 입력은 API 33(Android 13) 이상이 필요합니다. " +
                    "createOnDeviceSpeechRecognizer 자체는 API 31부터이지만 EXTRA_AUDIO_SOURCE는 API 33부터입니다."
            )
        }

        // createOnDeviceSpeechRecognizer 호출 전에 공식 availability API로 지원 여부를 확인한다.
        if (!AndroidSpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            throw IOException("이 기기에는 Android on-device Speech RecognitionService가 없습니다.")
        }

        // RecognitionService에 전달할 PCM pipe를 생성한다.
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        // 명시적으로 on-device SpeechRecognizer를 생성한다.
        val recognizer = AndroidSpeechRecognizer.createOnDeviceSpeechRecognizer(this)

        // segment callback과 최종 callback의 문자열을 누적한다.
        val sb = StringBuilder()
        var segmentSeen = false
        val completed = CompletableDeferred<String>()

        // 결과 Bundle에서 최우선 후보 1개를 가져와 중복되지 않게 누적한다.
        fun appendBestResult(results: Bundle) {
            val best = results.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
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
                Log.i(logTag, "Android on-device: ready (locale=$locale)")
            }

            override fun onBeginningOfSpeech() {
                Log.d(logTag, "Android on-device: beginning of speech")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 파일 비교 앱에서는 RMS UI를 사용하지 않는다.
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // RecognitionService가 제공하는 입력 buffer callback은 사용하지 않는다.
            }

            override fun onEndOfSpeech() {
                Log.d(logTag, "Android on-device: end of speech")
            }

            override fun onError(error: Int) {
                if (!completed.isCompleted) {
                    completed.completeExceptionally(
                        IOException("Android on-device SpeechRecognizer 오류 코드=$error")
                    )
                }
            }

            override fun onResults(results: Bundle?) {
                if (results != null && !segmentSeen) {
                    appendBestResult(results)
                }
                if (!completed.isCompleted) {
                    completed.complete(sb.toString().trim())
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // 평가 결과에는 partial hypothesis를 넣지 않는다.
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // 예약된 callback이므로 별도 처리하지 않는다.
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                segmentSeen = true
                appendBestResult(segmentResults)
            }

            override fun onEndOfSegmentedSession() {
                if (!completed.isCompleted) {
                    completed.complete(sb.toString().trim())
                }
            }
        })

        // Android 공식 RecognizerIntent 규격으로 파일 PCM 형식을 명시한다.
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        // 파일 길이에 비례한 타임아웃을 둔다.
        val approxSec = (pcm.size / 32000) + 1
        val timeout = (approxSec.toLong() * 2 + 120).seconds

        return try {
            withTimeout(timeout) {
                coroutineScope {
                    // startListening은 현재 lifecycleScope의 main thread에서 호출된다.
                    recognizer.startListening(intent)

                    // writeSide에 PCM을 실시간 속도로 공급한다. EOF가 세션 종료 조건이 된다.
                    val feedJob = launch {
                        feedRealtime(writeSide, pcm, onFed)
                    }

                    val result = completed.await()
                    feedJob.join()
                    result
                }
            }
        } finally {
            // 호출자가 EXTRA_AUDIO_SOURCE의 PFD와 SpeechRecognizer를 반드시 정리한다.
            try {
                readSide.close()
            } catch (_: Exception) {
                // close 중 오류는 기존 인식 결과보다 우선하지 않는다.
            }
            try {
                recognizer.destroy()
            } catch (_: Exception) {
                // destroy 중 오류는 기존 인식 결과보다 우선하지 않는다.
            }
        }
    }

    /**
     * PCM 을 16kHz 실시간 속도로 파이프에 흘려보낸다(100ms 분량씩).
     * onFed 로 공급된 비율(0.0~1.0)을 알려 진행 막대를 갱신한다.
     */
    private suspend fun feedRealtime(
        writeSide: ParcelFileDescriptor,
        pcm: ByteArray,
        onFed: (Double) -> Unit,
    ) {
        val chunk = 3200 // 100ms @ 16kHz * 2byte = 실시간 속도
        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { os ->
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + chunk, pcm.size)
                try {
                    withContext(Dispatchers.IO) {
                        os.write(pcm, offset, end - offset)
                        os.flush()
                    }
                } catch (e: IOException) {
                    // 인식 엔진이 먼저 닫힌 경우 (EPIPE) → 쓰기 중단
                    Log.w(logTag, "feedRealtime: pipe broken, stopping feed", e)
                    break
                }
                offset = end
                onFed(offset.toDouble() / pcm.size)
                delay(100.milliseconds)
            }
        }
        onFed(1.0)
    }

    // ─────────────────────────── 입력 폴더 탐색 ───────────────────────────
    private data class AudioItem(
        val uri: Uri,
        val relPath: List<String>,  // 입력 루트로부터의 하위 폴더 목록
        val nameNoExt: String,      // 확장자를 뺀 파일명
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
                    acc.add(AudioItem(child.uri, prefix, base))
                }
            }
        }
    }

    // ─────────────────────────── 출력 저장(폴더 구조 미러링) ───────────────────────────
    private fun saveResult(outputRoot: DocumentFile, modeFolder: String, item: AudioItem, text: String) {
        // output/google/<modeFolder>/<입력 하위 구조>/<파일명>.txt
        val segments = listOf(algoName, modeFolder) + item.relPath
        val dir = ensureDir(outputRoot, segments)
        writeTextFile(dir, item.nameNoExt + ".txt", text)
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

    private fun writeTextFile(dir: DocumentFile, fileName: String, content: String) {
        // 같은 이름이 있으면 지우고 새로 만든다(재실행 시 최신 결과 유지)
        dir.findFile(fileName)?.let { if (it.isFile) it.delete() }
        val file = dir.createFile("text/plain", fileName)
            ?: throw IOException("파일 생성 실패: $fileName")
        contentResolver.openOutputStream(file.uri)?.use { os ->
            os.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IOException("파일 쓰기 실패: $fileName")
    }

    // ─────────────────────────── 유틸 ───────────────────────────
    private fun localeForSelection(pos: Int): Locale = when (pos) {
        0 -> Locale.US       // 영어 (en-US)
        1 -> Locale.KOREA    // 한국어 (ko-KR)
        else -> {            // auto: ML Kit 은 언어 자동감지가 없어 기기 언어를 따른다
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
        runOnUiThread {
            logView.append(line + "\n")
        }
    }

    private fun toast(msg: String) {
        runOnUiThread {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }
}
