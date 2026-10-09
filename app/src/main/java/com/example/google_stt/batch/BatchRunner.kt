/*
 * 파일명: batch/BatchRunner.kt
 * 목적 및 기능:
 * - START 한 번에 해당하는 배치 전체를 실행한다.
 *     입력 폴더 수집 → 사전 점검(preflight / warm-up) → 파일마다
 *     디코딩 → [노이즈 저감] → [VAD] → [Speech EPD] → STT → TXT 저장 → result.csv / run_meta.csv
 * - 실패·취소·엔진 먹통에도 그때까지의 계측(result.csv)을 반드시 남긴다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.startTranscription() 의 lifecycleScope.launch { } 본문과
 *   executionLabel / preflight / fillVadConfig / fillEpdConfig / fillNoiseConfig / vadConfigText 를
 *   내용 변경 없이 옮겼다. Activity 에 묶인 호출만 아래처럼 바꿨다.
 *     this@MainActivity → activity, applicationContext → appContext, getString → activity.getString,
 *     return@launch → return, sttSessionCount → session.sttSessionCount,
 *     warmUpEngine / transcribeWithQuotaRetry → session.*, 마지막 running/버튼 복구 → host.onBatchFinished()
 */
package com.example.google_stt.batch

import android.net.Uri
import android.os.Build                                   // preflight 의 API 레벨 확인
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.example.google_stt.AudioDecoder
import com.example.google_stt.EpdMode
import com.example.google_stt.R
import com.example.google_stt.SileroVadProcessor
import com.example.google_stt.SpeechEpd
import com.example.google_stt.SttResourceSnapshot
import com.example.google_stt.SttRow
import com.example.google_stt.SttTelemetry
import com.example.google_stt.VadConfig
import com.example.google_stt.denoise.NoiseAlgorithm
import com.example.google_stt.denoise.NoiseReduceConfig
import com.example.google_stt.denoise.NoiseReducer
import com.example.google_stt.denoise.NoiseReducerFactory
import com.example.google_stt.denoise.OnnxNoiseReducerBase
import com.example.google_stt.io.AudioItem
import com.example.google_stt.io.ResultStore
import com.example.google_stt.io.collectAudio
import com.example.google_stt.io.displayRel
import com.example.google_stt.stt.BYTES_PER_SEC
import com.example.google_stt.stt.CONSECUTIVE_TIMEOUT_ABORT
import com.example.google_stt.stt.CSV_FLUSH_EVERY
import com.example.google_stt.stt.FEATURE_STATUS_TIMEOUT_MS
import com.example.google_stt.stt.FEED_STALL_WARN_RATIO
import com.example.google_stt.stt.FeedConfig
import com.example.google_stt.stt.GoogleSttSession
import com.example.google_stt.stt.MLKIT_SEGMENT_TRUNCATION_SEC
import com.example.google_stt.stt.MLKIT_TRUNCATION_CHARS_PER_SEC
import com.example.google_stt.stt.MlKitTranscriber
import com.example.google_stt.stt.ModelConfig
import com.example.google_stt.stt.PlatformOnDeviceTranscriber
import com.example.google_stt.stt.QUOTA_BUDGET_SESSIONS
import com.example.google_stt.stt.QUOTA_COOLDOWN_MS
import com.example.google_stt.stt.SttEngine
import com.example.google_stt.stt.WARMUP_TIMEOUT_MS
import com.example.google_stt.stt.describe
import com.example.google_stt.stt.expectedFeedMs
import com.example.google_stt.stt.isEngineDead
import com.example.google_stt.stt.sttTimeoutSec
import com.example.google_stt.ui.EpdSelection
import com.example.google_stt.ui.VadSelection
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * 목적: 배치 실행기가 화면(MainActivity)에 요청하는 일을 정의한다.
 * - activity            : DocumentFile / getString / applicationContext 에 쓸 Activity
 * - appendLog / toast   : 화면 로그와 토스트
 * - updateProgress      : 진행률(0.0~1.0) 표시
 * - openSpeechServiceSettings : 엔진 먹통 시 'Google 음성 서비스' 앱 정보 화면 열기
 * - onBatchFinished     : 배치가 어떤 경로로든 끝났을 때(running 해제, START 버튼 복구)
 */
interface BatchHost {
    val activity: AppCompatActivity
    fun appendLog(line: String)
    fun toast(msg: String)
    fun updateProgress(fraction: Double)
    fun openSpeechServiceSettings()
    fun onBatchFinished()
}

/**
 * 목적: START 를 누른 순간의 실행 조건 묶음(불변).
 * 비고: change-hyungchul-20261009-1514 — MainActivity.startTranscription() 의 지역 변수들을 그대로 담는다.
 */
data class BatchRequest(
    val inUri: Uri,                       // 입력 폴더 트리 URI
    val outUri: Uri,                      // 출력 폴더 트리 URI
    val locale: Locale,                   // 인식 언어
    val modelConfig: ModelConfig,         // STT 경로
    val feed: FeedConfig,                 // 오디오 공급 속도
    val vadSelection: VadSelection,       // VAD 설정 snapshot
    val epdSelection: EpdSelection,       // Speech EPD 설정 snapshot
    val noiseConfig: NoiseReduceConfig,   // 노이즈 저감 설정
    val variantSegments: List<String>,    // 결과 폴더 조각 목록
)

/**
 * 목적: 배치 실행기.
 * 입력(생성자): host — 화면 연동, session — Google STT 세션 관리자,
 *              mlKit / platform — preflight 에 쓰는 전사기, store — 결과 저장기, algoName — "google"
 */
class BatchRunner(
    private val host: BatchHost,
    private val session: GoogleSttSession,
    private val mlKit: MlKitTranscriber,
    private val platform: PlatformOnDeviceTranscriber,
    private val store: ResultStore,
    private val algoName: String,
) {
    private val logTag = "GoogleStt"                       // MainActivity 의 logTag 와 같은 값
    private val activity: AppCompatActivity get() = host.activity
    private val appContext get() = host.activity.applicationContext

    // ── 화면/저장 호출 위임: 옮겨 온 본문을 한 글자도 바꾸지 않기 위한 얇은 연결부 ──
    private fun appendLog(line: String) = host.appendLog(line)
    private fun toast(msg: String) = host.toast(msg)
    private fun updateProgress(fraction: Double) = host.updateProgress(fraction)
    private fun openSpeechServiceSettings() = host.openSpeechServiceSettings()
    private fun ensureRunDir(outputRoot: DocumentFile, modelFolder: String, variant: List<String>) =
        store.ensureRunDir(outputRoot, modelFolder, variant)
    private fun writeCsv(outputRoot: DocumentFile, modelFolder: String, variant: List<String>, body: String) =
        store.writeCsv(outputRoot, modelFolder, variant, body)
    private fun saveResult(outputRoot: DocumentFile, modeFolder: String, variant: List<String>, item: AudioItem, text: String) =
        store.saveResult(outputRoot, modeFolder, variant, item, text)
    private fun writeBytes(dir: DocumentFile, fileName: String, bytes: ByteArray, mime: String = "text/plain") =
        store.writeBytes(dir, fileName, bytes, mime)

    /**
     * 목적: 배치 1회를 끝까지 실행한다(MainActivity 의 lifecycleScope.launch 안에서 부른다).
     * 입력: req — START 순간의 실행 조건
     * 출력: 화면 로그 / 진행률 / 결과 파일
     * 리턴: 없음
     */
    suspend fun run(req: BatchRequest) {
        val inUri = req.inUri
        val outUri = req.outUri
        val locale = req.locale
        val modelConfig = req.modelConfig
        val modelFolder = modelConfig.folder
        val feed = req.feed
        val vadSelection = req.vadSelection
        val epdSelection = req.epdSelection
        val noiseConfig = req.noiseConfig
        val variantSegments = req.variantSegments

        var ok = 0
        var fail = 0
        val csv = StringBuilder(SttTelemetry.CSV_HEADER)
        var abortedByWedge = false                    // add-hyungchul-20260914-2350
        var vadProcessor: SileroVadProcessor? = null   // add-hyungchul-20260825-1430
        var noiseReducer: NoiseReducer? = null         // add-hyungchul-20260826-1100
        try {
            val inputRoot = DocumentFile.fromTreeUri(activity, inUri)
            val outputRoot = DocumentFile.fromTreeUri(activity, outUri)
            if (inputRoot == null || outputRoot == null) {
                toast(activity.getString(R.string.msg_folder_open_fail)); return
            }

            val items = withContext(Dispatchers.IO) {
                val acc = mutableListOf<AudioItem>()
                collectAudio(inputRoot, emptyList(), acc)
                acc.sortedBy { displayRel(it) }
            }
            if (items.isEmpty()) {
                toast(activity.getString(R.string.msg_no_audio)); return
            }
            appendLog(activity.getString(R.string.log_found, items.size))
            appendLog(activity.getString(R.string.log_model, modelFolder))
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
            session.sttSessionCount = 0                       // add-hyungchul-20260915-2200 : 배치마다 초기화
            val warmUpStatus = session.warmUpEngine(modelConfig, locale)
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
                return
            }

            // add-hyungchul-20260826-1100
            // 노이즈 저감기도 배치 전체에서 1회만 만든다(모델 로딩 시간이 파일별 계측에 섞이지 않게).
            if (noiseConfig.algorithm != NoiseAlgorithm.NONE) {
                val reducer = withContext(Dispatchers.Default) {
                    NoiseReducerFactory.create(appContext, noiseConfig)
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
                    SileroVadProcessor(appContext)
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
                        appContext,
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
                    netType = SttTelemetry.networkType(appContext),
                    airplane = SttTelemetry.airplaneMode(appContext),
                    startedAt = SttTelemetry.isoNow(),
                )
                fillVadConfig(row, vadSelection)                       // add-hyungchul-20260825-1430
                fillEpdConfig(row, epdSelection)                       // add-hyungchul-20260914-2350
                fillNoiseConfig(row, noiseConfig, noiseReducer)         // add-hyungchul-20260826-1100
                val snap = SttResourceSnapshot.take(appContext)

                // ── 1) 디코딩 ──
                val decoded = try {
                    withContext(Dispatchers.IO) {
                        AudioDecoder.decodeDetailed(appContext, item.uri)
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
                    snap.fillDelta(appContext, row)
                    csv.append(row.toCsvLine())
                    fail++
                    completedUnits++
                    updateProgress(completedUnits.toDouble() / totalUnits)
                    appendLog(activity.getString(R.string.log_decode_fail, relName, msg))
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
                        snap.fillDelta(appContext, row)
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
                            snap.fillDelta(appContext, row)
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
                        snap.fillDelta(appContext, row)
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
                    row.sttSessionIndex = session.sttSessionCount + 1
                    val outcome = session.transcribeWithQuotaRetry(
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
                        activity.getString(R.string.log_done, modelFolder, relName) +
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
                    appendLog(activity.getString(R.string.log_fail, modelFolder, relName, msg))

                    // change-hyungchul-20260916-1000
                    //   타임아웃뿐 아니라 "checkStatus 무응답" 도 엔진 사망으로 센다(위 isEngineDead 주석 참조).
                    if (isEngineDead(e)) consecutiveTimeouts++ else consecutiveTimeouts = 0
                    //   세션 한도를 이미 넘긴 뒤의 실패는 회복된 전례가 없다(3회 실행 전부 동일).
                    //   그때는 한 건만 실패해도 즉시 끝낸다. 남은 파일을 시도해 봐야 전부 실패한다.
                    val pastBudget = session.sttSessionCount > QUOTA_BUDGET_SESSIONS
                    if (isEngineDead(e) &&
                        (pastBudget || consecutiveTimeouts >= CONSECUTIVE_TIMEOUT_ABORT)
                    ) {
                        abortedByWedge = true
                        snap.fillDelta(appContext, row)
                        csv.append(row.toCsvLine())
                        completedUnits++
                        updateProgress(completedUnits.toDouble() / totalUnits)
                        appendLog(
                            "[중단] 엔진이 죽었다(세션 ${session.sttSessionCount}개 사용, 연속 실패 ${consecutiveTimeouts}건). " +
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

                snap.fillDelta(appContext, row)
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
                toast(activity.getString(R.string.msg_stt_done, ok, fail))
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
                val outputRoot = DocumentFile.fromTreeUri(activity, outUri)
                if (outputRoot != null) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        writeCsv(outputRoot, modelFolder, variantSegments, csv.toString())
                    }
                }
            }
            toast(activity.getString(R.string.msg_error, describe(e)))
        } finally {
            // ★ add-hyungchul-20260914-1500 : 마지막 방어선
            //   취소든 예외든 타임아웃이든, 어떤 경로로 빠져나가도 여기서 CSV 를 반드시 남긴다.
            //   writeCsv 는 같은 파일을 덮어쓰므로 catch 에서 이미 저장했어도 문제없다.
            runCatching {
                withContext(NonCancellable + Dispatchers.IO) {
                    val root = DocumentFile.fromTreeUri(activity, outUri)
                    if (root != null) {
                        writeCsv(root, modelFolder, variantSegments, csv.toString())
                    }
                }
            }
            runCatching { vadProcessor?.close() }   // add-hyungchul-20260825-1430
            runCatching { noiseReducer?.close() }   // add-hyungchul-20260826-1100
            host.onBatchFinished()   // change-hyungchul-20261009-1514 : running=false / START 버튼 복구를 MainActivity 에 맡긴다
        }
    }

    /**
     * 목적: 이 경로가 API 계약상 어디서 실행되는지 문자열로 만든다.
     * 비고: 세 경로 모두 문서상 온디바이스다. 실제 증명은 CSV 의 net_type/airplane 컬럼으로 한다.
     */
    private fun executionLabel(config: ModelConfig): String = when (config.engine) {
        // change-hyungchul-20261009-1524 : MODE_ADVANCED("ON_DEVICE(MLKit GenAI)") 분기 제거 — ML Kit 경로는 BASIC 만 남았다.
        SttEngine.ML_KIT -> "ON_DEVICE(MLKit basic=platform SpeechRecognizer)"

        SttEngine.ANDROID_PLATFORM_ON_DEVICE -> "ON_DEVICE(platform on-device service)"
    }

    /** 배치 시작 전 1회 가용성 점검. 로그에 남기고 run_meta.csv 용 문자열도 돌려준다. */
    private suspend fun preflight(config: ModelConfig, locale: Locale): String {
        val sb = StringBuilder()
        when (config.engine) {
            SttEngine.ML_KIT -> {
                for (m in listOf(
                    SpeechRecognizerOptions.Mode.MODE_BASIC,
                    // change-hyungchul-20261009-1524 : MODE_ADVANCED(Pixel 10 전용) 점검 제거
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
                    mlKit.closeRecognizerBounded(client)
                    // change-hyungchul-20261009-1524 : ADVANCED 가 없어졌으므로 이름은 항상 BASIC 이다.
                    val name = "BASIC"
                    val text = st.fold({ mlKit.statusName(it) }, { describe(it) })
                    val line = "ML Kit $name (${locale.toLanguageTag()}) = $text"
                    appendLog("[preflight] $line")
                    sb.append(line).append(" ; ")
                }
            }

            SttEngine.ANDROID_PLATFORM_ON_DEVICE -> {
                val avail = AndroidSpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
                val line1 = "isOnDeviceRecognitionAvailable=$avail"
                appendLog("[preflight] $line1")
                sb.append(line1).append(" ; ")
                if (avail && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val line2 = platform.checkAndroidOnDeviceSupport(locale)
                    appendLog("[preflight] $line2")
                    sb.append(line2)
                }
            }
        }
        val svc = SttTelemetry.recognitionServices(appContext)
        appendLog("[preflight] RecognitionService: $svc")
        appendLog(
            "[preflight] net=${SttTelemetry.networkType(appContext)} " +
                    "airplane=${SttTelemetry.airplaneMode(appContext)} " +
                    "thermal=${SttTelemetry.thermalStatus(appContext)}",
        )
        return sb.toString()
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
}
