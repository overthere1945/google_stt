/*
 * 파일명: MainActivity.kt
 * 목적 및 기능:
 * - 입력 폴더의 음성 파일을 16 kHz / mono / PCM16 으로 변환한 뒤 Google 계열 On-device STT 로 전사한다.
 * - 비교 가능한 3개 경로:
 *     (1) ML Kit GenAI Speech Recognition — MODE_BASIC   → 폴더 base
 *     (2) ML Kit GenAI Speech Recognition — MODE_ADVANCED→ 폴더 advanced (Pixel 10 계열만)
 *     (3) Android Platform SpeechRecognizer.createOnDeviceSpeechRecognizer() → 폴더 android_ondevice
 * - 결과를 output/google/<모드>/<입력 하위 폴더>/<파일명>.txt 로 저장하고
 *   output/google/<모드>/result.csv (utf-8-sig, status = OK/NO_MATCH/ERROR) 도 함께 남긴다.
 *
 * ※ 이 3개 경로 중 어느 것도 Google Cloud 인증(서비스 계정 JSON, API Key, OAuth 토큰)을 사용하지 않는다.
 *   모두 단말 내부에서 동작하며 네트워크 자격증명이 필요 없다.
 *   "Cloud Speech-to-Text On-Device"(파트너 전용 임베디드 SDK)는 여기에 포함되어 있지 않다.
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
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
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
        val modelFolder = modelConfig.folder

        running = true
        startButton.isEnabled = false
        logView.text = ""
        updateProgress(0.0)
        toast(getString(R.string.msg_stt_started))

        lifecycleScope.launch {
            var ok = 0
            var fail = 0
            val csv = StringBuilder("path,status,elapsed_sec,chars,text\n")
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

                // 실행 전 1회: 선택한 엔진의 가용성을 확인해 로그로 남긴다.
                preflight(modelConfig, locale)

                val totalUnits = items.size
                var completedUnits = 0

                for (item in items) {
                    val relName = displayRel(item)
                    val startedAt = System.nanoTime()

                    val pcm = try {
                        withContext(Dispatchers.IO) {
                            AudioDecoder.decodeTo16kMonoPcm(applicationContext, item.uri)
                        }
                    } catch (e: Exception) {
                        val msg = describe(e)
                        Log.e(logTag, "decode failed: $relName", e)
                        withContext(Dispatchers.IO) {
                            saveResult(outputRoot, modelFolder, item, "[ERROR] 디코딩 실패: $msg")
                        }
                        csv.append(csvRow(relName, "ERROR", startedAt, "디코딩 실패: $msg"))
                        fail++
                        completedUnits++
                        updateProgress(completedUnits.toDouble() / totalUnits)
                        appendLog(getString(R.string.log_decode_fail, relName, msg))
                        continue
                    }

                    val baseUnits = completedUnits
                    try {
                        val text = when (modelConfig.engine) {
                            SttEngine.ML_KIT -> {
                                val mode = modelConfig.mlKitMode
                                    ?: throw IOException("ML Kit 모드 설정이 없습니다.")
                                transcribeMlKit(pcm, locale, mode) { fed ->
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
                        if (text.isBlank()) {
                            csv.append(csvRow(relName, "NO_MATCH", startedAt, ""))
                        } else {
                            csv.append(csvRow(relName, "OK", startedAt, text))
                        }
                        ok++
                        appendLog(getString(R.string.log_done, modelFolder, relName))
                    } catch (e: Exception) {
                        val msg = describe(e)
                        Log.e(logTag, "transcribe failed: $modelFolder/$relName", e)
                        withContext(Dispatchers.IO) {
                            saveResult(outputRoot, modelFolder, item, "[ERROR] $msg")
                        }
                        csv.append(csvRow(relName, "ERROR", startedAt, msg))
                        fail++
                        appendLog(getString(R.string.log_fail, modelFolder, relName, msg))
                    }
                    completedUnits++
                    updateProgress(completedUnits.toDouble() / totalUnits)
                }

                withContext(Dispatchers.IO) {
                    val dir = ensureDir(outputRoot, listOf(algoName, modelFolder))
                    // utf-8-sig (BOM) — 기존 파이썬 파이프라인 규약과 동일하게 맞춘다.
                    writeBytes(
                        dir,
                        "result.csv",
                        BOM + csv.toString().toByteArray(Charsets.UTF_8),
                        "text/csv",
                    )
                }

                updateProgress(1.0)
                toast(getString(R.string.msg_stt_done, ok, fail))
            } catch (e: Exception) {
                Log.e(logTag, "run failed", e)
                toast(getString(R.string.msg_error, describe(e)))
            } finally {
                running = false
                startButton.isEnabled = true
            }
        }
    }

    /** 배치 시작 전 1회 가용성 점검 — 어떤 엔진이 실제로 쓰이는지 로그로 확인한다. */
    private suspend fun preflight(config: ModelConfig, locale: Locale) {
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
                    val name = if (m == SpeechRecognizerOptions.Mode.MODE_BASIC) "BASIC" else "ADVANCED"
                    val text = st.fold({ statusName(it) }, { describe(it) })
                    appendLog("[preflight] ML Kit $name (${locale.toLanguageTag()}) = $text")
                }
            }

            SttEngine.ANDROID_PLATFORM_ON_DEVICE -> {
                val avail = AndroidSpeechRecognizer.isOnDeviceRecognitionAvailable(this)
                appendLog("[preflight] isOnDeviceRecognitionAvailable = $avail")
                if (avail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appendLog("[preflight] " + checkAndroidOnDeviceSupport(locale))
                }
            }
        }
    }

    // ─────────────────────── (1)(2) ML Kit GenAI Speech Recognition ───────────────────────
    /**
     * 목적: ML Kit GenAI Speech Recognition 으로 PCM 1건을 전사한다.
     * 입력: pcm(16k/mono/PCM16), locale, mode(MODE_BASIC 또는 MODE_ADVANCED), onFed(진행률 0~1)
     * 출력: 최종 인식 문자열
     * 예외: 모델이 AVAILABLE 이 아니거나 인식 오류 시 IOException
     */
    private suspend fun transcribeMlKit(
        pcm: ByteArray,
        locale: Locale,
        mode: Int,
        onFed: (Double) -> Unit,
    ): String {
        val options: SpeechRecognizerOptions = speechRecognizerOptions {
            this.locale = locale
            preferredMode = mode
        }

        return SpeechRecognition.getClient(options).use { recognizer ->
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
                            "MODE_ADVANCED 는 Pixel 10 계열에서만 제공되고, " +
                            "MODE_BASIC 은 API 31+ 대부분의 기기에서 제공된다.",
                )
            }

            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]

            // 공식 문서 요건: PFD 에는 실시간 속도(16,000 samples ≒ 32 KB / 초)로 공급해야 한다.
            val feeder = PcmFeeder(writeSide, pcm, CHUNK_BYTES, MLKIT_CHUNK_DELAY_MS, onFed)

            val sb = StringBuilder()
            var errorMessage: String? = null

            try {
                val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(readSide) }
                val approxSec = pcm.size / BYTES_PER_SEC + 1
                val timeout = (approxSec.toLong() * 2 + 180).seconds

                withTimeout(timeout) {
                    coroutineScope {
                        val collectJob = launch {
                            recognizer.startRecognition(request).collect { resp ->
                                when (resp) {
                                    is SpeechRecognizerResponse.FinalTextResponse -> {
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

                                    else -> Unit // Partial / Completed 는 무시
                                }
                            }
                        }

                        feeder.start()
                        feeder.finished.await()
                        runCatching { recognizer.stopRecognition() }
                        collectJob.join()
                    }
                }
            } finally {
                feeder.abort()
                // ★ 원본 버그 수정: readSide 를 닫지 않으면 파일마다 FD 가 새어 배치 도중 EMFILE 로 실패한다.
                runCatching { readSide.close() }
            }

            val result = sb.toString().trim()
            val err = errorMessage
            if (result.isEmpty() && err != null) throw IOException(err)
            result
        }
    }

    // ─────────────── (3) Android Platform On-device SpeechRecognizer ───────────────
    /**
     * 목적: Android Framework 의 createOnDeviceSpeechRecognizer() 로 PCM 1건을 전사한다.
     * 입력: pcm(16k/mono/PCM16), locale, onFed(진행률 0~1)
     * 출력: 최종 인식 문자열(NO_MATCH 이면 빈 문자열)
     * 예외: API 33 미만, on-device RecognitionService 부재, 그 외 인식 오류 시 IOException
     * 비고: EXTRA_AUDIO_SOURCE(파일/파이프 입력)는 API 33(Android 13)부터,
     *       createOnDeviceSpeechRecognizer / isOnDeviceRecognitionAvailable 은 API 31부터다.
     */
    private suspend fun transcribeAndroidOnDevice(
        pcm: ByteArray,
        locale: Locale,
        onFed: (Double) -> Unit,
    ): String {
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
        val feeder = PcmFeeder(writeSide, pcm, CHUNK_BYTES, ANDROID_CHUNK_DELAY_MS, onFed)

        val sb = StringBuilder()
        var segmentSeen = false
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
                Log.i(logTag, "Android on-device: ready (locale=$locale)")
            }

            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                if (completed.isCompleted) return
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT 은 "인식 결과 없음"이므로 실패가 아니라 빈 결과로 처리한다.
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
                if (!segmentSeen) appendBest(results)
                if (!completed.isCompleted) completed.complete(sb.toString().trim())
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                segmentSeen = true
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
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            // 파일(파이프) 입력 — 오디오가 닫힐 때(EOF) 세션이 종료된다.
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AudioDecoder.TARGET_RATE)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            // ★ 평가용 추가: 기본값 true 인 비속어 마스킹(****)을 끄고, 문장부호 포맷팅을 켠다.
            putExtra(RecognizerIntent.EXTRA_MASK_OFFENSIVE_WORDS, false)
            putExtra(
                RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
            )
        }

        val approxSec = pcm.size / BYTES_PER_SEC + 1
        val timeout = (approxSec.toLong() * 2 + 180).seconds

        return try {
            // startListening / destroy 는 반드시 메인 스레드에서 호출해야 한다(lifecycleScope 기본값 = Main).
            recognizer.startListening(intent)
            feeder.start()
            withTimeout(timeout) { completed.await() }
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
     *       블로킹 write 는 코루틴 취소로 풀리지 않아 배치 전체가 멈추기 때문이다(원본 코드의 잠재적 데드락).
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

        val finished = CompletableDeferred<Unit>()

        private val thread = Thread({
            try {
                feed()
            } finally {
                onFed(1.0)
                finished.complete(Unit)
            }
        }, "pcm-feeder").apply { isDaemon = true }

        fun start() {
            started = true
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

    // ─────────────────────────── 출력 저장 ───────────────────────────
    private fun saveResult(
        outputRoot: DocumentFile,
        modeFolder: String,
        item: AudioItem,
        text: String,
    ) {
        val segments = listOf(algoName, modeFolder) + item.relPath
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

    private fun csvRow(path: String, status: String, startedAtNs: Long, text: String): String {
        val elapsed = (System.nanoTime() - startedAtNs) / 1_000_000_000.0
        fun q(s: String) = "\"" + s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""
        return "${q(path)},$status,${String.format(Locale.US, "%.2f", elapsed)},${text.length},${q(text)}\n"
    }

    // ─────────────────────────── 유틸 ───────────────────────────
    private fun updateProgress(fraction: Double) {
        val clamped = fraction.coerceIn(0.0, 1.0)
        runOnUiThread {
            progressBar.progress = (clamped * 1000).toInt()
            progressText.text = String.format(Locale.US, "%.1f%%", clamped * 100)
        }
    }

    private fun statusName(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE(사용 가능)"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE(다운로드 필요)"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING(다운로드 중)"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE(이 기기에서 미지원)"
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
        /** utf-8-sig BOM */
        private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        /** 16 kHz × 2바이트 = 32,000 바이트/초 */
        private const val BYTES_PER_SEC = 32_000

        /** 100 ms 분량 = 3,200 바이트 */
        private const val CHUNK_BYTES = 3_200

        /** ML Kit 은 공식 문서가 "실시간 속도" 공급을 요구하므로 100 ms 를 유지한다. */
        private const val MLKIT_CHUNK_DELAY_MS = 100L

        /**
         * Android Platform 경로도 기본은 실시간(100 ms).
         * 배치 속도를 올리고 싶으면 15~30 ms 로 낮춰볼 수 있다
         * (expo-speech-recognition 은 on-device 경로에서 15 ms 를 쓴다).
         * 낮춘 뒤에는 반드시 결과가 잘리지 않는지 몇 개 파일로 검증할 것.
         */
        private const val ANDROID_CHUNK_DELAY_MS = 100L
    }
}