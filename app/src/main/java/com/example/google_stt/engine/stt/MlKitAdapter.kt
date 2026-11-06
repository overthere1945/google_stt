/*
 * 파일명: engine/stt/MlKitAdapter.kt
 * 목적 및 기능:
 * - ML Kit GenAI Speech Recognition(Basic) 으로 PCM 조각을 전사한다. 모델은 시스템(AICore / com.google.android.tts 의 SODA)이
 *   관리하므로 자산 폴더가 없다. 공급은 실시간(100 ms 분량을 100 ms 마다) 고정이다 — ML Kit 공식 요건(D: REALTIME 고정).
 * - v3 의 stt/MlKitTranscriber.kt(세션 1개 전사)를 옮기고 SttEngineAdapter 로 감쌌다. 세션 본문(상태 확인·모델 받기·파이프 공급·
 *   응답 모으기·시간 제한·stopRecognition·포기 가능한 close)은 그대로이고 바뀐 점은:
 *     · load = 세션 수 초기화 + 1초 무음 warm-up. 응답이 없으면 AppError.MlKitStuck(E-EN-05, 조치 안내 포함)
 *     · 여러 조각·세션 한도·재시도는 GoogleSttSession 이 맡는다
 *     · 코루틴 안의 runCatching 을 없앴다(취소를 삼키지 않게). 정리 중 오류는 로그에 남긴다
 * - 인식기 close() / stopRecognition() 이 네이티브에서 멈춰도 작업이 끝나지 않는 일이 없게 "포기 가능한" 시간 제한을 건다.
 *
 * add-hyungchul-20261106-2146 — [T5-5]
 */
package com.example.google_stt.engine.stt

import android.os.ParcelFileDescriptor                                   // 오디오를 넘기는 파이프
import android.util.Log                                                  // Logcat
import com.example.google_stt.domain.AppError                            // E-EN-05
import com.example.google_stt.domain.catalog.Language                    // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec                   // 모델 정의
import com.example.google_stt.domain.condition.FeedChoice                // 공급 속도(쓰지 않는다 — 실시간 고정)
import com.example.google_stt.domain.csv.SttOutcome                      // 전사 결과
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 목적: ML Kit 어댑터(작업 1개에 1개).
 * 입력(생성자): spec - 모델 정의(Runtime.MLKIT), log - 경고 한 줄을 받는 함수(세션 한도 경고, close 지연 등)
 */
class MlKitAdapter(override val spec: ModelSpec, private val log: (String) -> Unit) : SttEngineAdapter {

    private val logTag = "GoogleStt"                                     // 앱 로그와 같은 태그
    private var locale: Locale = Locale.forLanguageTag(Language.KO.reqLang)   // 지금 인식 언어(load·transcribe 가 정한다)
    private val session = GoogleSttSession(::recognize, log)             // 세션 수·재시도·조각 합치기

    /** 적재 뒤 지금까지 연 세션 수(warm-up 포함) */
    val sessionCount: Int get() = session.sessionCount

    /** 세션 한도(약 35)를 넘겼는지 — 넘긴 뒤 엔진이 죽으면 남은 ML Kit 작업을 건너뛴다(D29) */
    override val pastBudget: Boolean get() = session.pastBudget   // change-hyungchul-20261106-2229 : [T6-2] 약속의 값을 채운다

    /**
     * 목적: 엔진이 대답하는지 확인한다(모델은 시스템이 관리해 따로 불러올 것이 없다).
     * 입력: modelDir - 쓰지 않는다(null), lang - 평가 언어
     * 출력: 세션 수가 초기화되고 warm-up 세션 1개를 쓴다
     * 리턴: LoadInfo(적재 시간 = warm-up 에 걸린 시간)
     * 예외: AppError.MlKitStuck(E-EN-05) - 1초 무음에 30초 안에 응답이 없을 때,
     *       AppError.ModelLoad(E-EN-01) - 엔진은 대답했지만 쓸 수 없을 때(언어 모델 받기 실패, 마이크 권한 없음 등)
     */
    override suspend fun load(modelDir: File?, lang: Language): LoadInfo {
        locale = Locale.forLanguageTag(lang.reqLang)
        session.reset()
        val t0 = System.nanoTime()
        // change-hyungchul-20261106-2215 : 언어 모델 확인·받기를 warm-up 앞에서 따로 한다 — warm-up 의 30초 제한이 모델 받기(최대 10분)까지
        //   감싸면, 받는 중인 것을 "먹통"(E-EN-05)으로 잘못 알리고 받기도 끊는다(독립 검토 지적 2)
        val client = SpeechRecognition.getClient(options())
        try {
            ensureAvailable(client)
        } catch (e: IOException) {
            if (isEngineDead(e)) throw AppError.MlKitStuck("${e.message}", e)      // checkStatus 조차 대답하지 않는다
            throw AppError.ModelLoad("ML Kit($locale)을 쓸 수 없다 — ${e.message}", e)   // 모델 받기 실패·사용 불가
        } finally {
            closeRecognizerBounded(client)
        }
        // 상태 확인은 "모델이 있는가" 만 본다. 엔진이 먹통이어도 AVAILABLE 로 나오므로 1초 무음을 실제로 넣어 본다.
        val failure = session.warmUp()
        if (failure != null) {
            if (isEngineDead(failure)) {
                throw AppError.MlKitStuck(
                    "1초 무음에 ${WARMUP_TIMEOUT_MS / 1000}초 안에 응답이 없다. 설정 → 애플리케이션 → 'Google 음성 서비스'" +
                        "(com.google.android.tts) 강제 중지 후 ML Kit 만 다시 실행한다. 효과가 없으면 재부팅한다.",
                    failure,
                )
            }
            // 엔진은 대답했지만 쓸 수 없다(언어 모델을 아직 받지 못함, 마이크 권한 없음 등) → 파일마다 같은 오류를 내지 않고 여기서 멈춘다
            throw AppError.ModelLoad("ML Kit($locale)을 쓸 수 없다 — ${failure.message}", failure)
        }
        return LoadInfo(ms(t0, System.nanoTime()) / 1000.0, RUNTIME)
    }

    /**
     * 목적: 조각들을 세션 하나씩으로 전사해 잇는다.
     * 입력: chunks - 조각들, feed - 쓰지 않는다(실시간 고정), lang - 평가 언어
     * 출력: 조각 수만큼 세션을 쓴다
     * 리턴: SttOutcome(전사문, 확정 문장 수, 첫 응답, 꼬리 지연, 공급 시간, 엔진 메모)
     * 예외: IOException(엔진 오류·모델 사용 불가), AppError.SttTimeout(E-EN-03, 응답 없음 — isEngineDead 로 판정)
     */
    override suspend fun transcribe(chunks: List<AudioChunk>, feed: FeedChoice, lang: Language): SttOutcome {
        locale = Locale.forLanguageTag(lang.reqLang)
        val pcms = chunks.map { it.pcm }.filter { it.isNotEmpty() }                 // change-hyungchul-20261106-2215 : 빈 조각은 건너뛴다(세션을 쓰지 않는다 — 약속대로)
        if (pcms.isEmpty()) return sttOutcome("", 0, -1, -1, 0)                     // 넣을 것이 없으면 세션 없이 빈 결과(NO_MATCH)
        val r = session.transcribeChunks(pcms, REALTIME_DELAY_MS)
        return sttOutcome(r.text, r.finals, r.firstResultMs, r.tailMs, r.feedMs).copy(note = r.note)
    }

    /** 목적: 정리할 것이 없다(인식기는 세션마다 닫는다). 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    override fun close() = Unit

    // ─────────────────────── ML Kit GenAI Speech Recognition 세션 1개 ───────────────────────
    /**
     * 목적: PCM 1건을 세션 1개로 전사하고 지연을 잰다.
     * 입력: pcm - 16 kHz / mono / PCM16 LE, delayMs - 100 ms 조각 사이 간격
     * 출력: 없음
     * 리턴: SessionResult
     * 예외: 모델이 AVAILABLE 이 아니거나 인식 오류면 IOException, 응답이 없으면 AppError.SttTimeout(E-EN-03)
     */
    private suspend fun recognize(pcm: ByteArray, delayMs: Long): SessionResult {
        // ★ use{} 를 쓰지 않는다. use{} 는 블록을 벗어날 때 close() 를 부르는데, 엔진이 먹통이면 그 close() 가 블록된다.
        //   closeRecognizerBounded() 로 시간 제한을 두어 그런 경우에도 다음 파일로 넘어가게 한다.
        val recognizer = SpeechRecognition.getClient(options())
        return try {
            ensureAvailable(recognizer)                                            // 상태 확인(필요하면 모델 받기)

            val pipe = ParcelFileDescriptor.createPipe()
            val readSide = pipe[0]
            val writeSide = pipe[1]
            val feeder = PcmFeeder(writeSide, pcm, CHUNK_BYTES, delayMs) {}        // 데몬 스레드로 공급(막힌 write 는 코루틴 취소로 풀리지 않는다)

            val sb = StringBuilder()
            var errorMessage: String? = null
            var firstResponseNs = 0L
            var finalCount = 0
            var sessionStartNs = 0L
            var endNs = 0L
            var stoppedCleanly = false                                             // 세션을 정상으로 닫았는지

            try {
                val request = speechRecognizerRequest { audioSource = AudioSource.fromPfd(readSide) }
                val timeout = sttTimeoutSec(pcm.size, delayMs).seconds             // 입력 길이·공급 속도를 반영한 시간 제한
                sessionStartNs = System.nanoTime()
                // change-hyungchul-20261106-2215 : withTimeout → withTimeoutOrNull. 이 제한에 걸리면 null 이 오고, 취소 예외가 아닌 SttTimeout 으로 알린다.
                //   바깥 작업이 취소된 것이면 null 이 아니라 취소 예외가 그대로 올라간다(독립 검토 지적 1)
                withTimeoutOrNull(timeout) {
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
                        try {
                            recognizer.stopRecognition()                           // 세션 정상 종료
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(logTag, "stopRecognition() 실패: ${e.message}")  // 정리 실패는 결과에 영향을 주지 않는다
                        }
                        stoppedCleanly = true
                        collectJob.join()
                        endNs = System.nanoTime()
                    }
                } ?: throw AppError.SttTimeout("${timeout.inWholeSeconds}초 안에 응답이 없다(입력 ${pcm.size / BYTES_PER_SEC}초)")
            } finally {
                feeder.abort()
                // readSide 를 닫지 않으면 파일마다 FD 가 새어 작업 도중 EMFILE 로 실패한다.
                try { readSide.close() } catch (e: IOException) { Log.w(logTag, "파이프 닫기 실패: ${e.message}") }
                // ★ 시간 초과·취소로 빠져나온 경우 stopRecognition() 이 한 번도 불리지 않는다.
                //   이걸 건너뛰면 엔진 쪽 세션이 살아남아 다음 파일까지 먹통이 이어진다(실측).
                //   파이프를 먼저 풀어 준 뒤에 불러야 엔진이 입력 끝을 인식하고 정리할 수 있다.
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
            // ERROR_TYPE_NO_SPEECH_DETECTED 는 엔진이 "말이 없다" 고 판정한 결과이지 고장이 아니다.
            //   예외로 던지면 그 파일 행이 통째로 ERROR 가 되어 WER/CER 비교에서 빠진다. 빈 결과(NO_MATCH)로 돌려주고 메모로 남긴다.
            var engineNote = ""
            if (result.isEmpty() && err != null) {
                if (isNoSpeechError(err)) engineNote = err else throw IOException(err)
            }
            if (endNs == 0L) endNs = System.nanoTime()
            val eofNs = feeder.finishedAtNs.takeIf { it != 0L } ?: endNs

            SessionResult(
                text = result,
                firstResultMs = ms(sessionStartNs, firstResponseNs),
                feedMs = ms(feeder.startedAtNs, feeder.finishedAtNs),
                tailMs = ms(eofNs, endNs),
                finals = finalCount,
                note = engineNote,
            )
        } finally {
            closeRecognizerBounded(recognizer)
        }
    }

    /** 목적: 지금 언어의 인식 옵션을 만든다. 입력: 없음 / 출력: 없음 / 리턴: SpeechRecognizerOptions(MODE_BASIC) */
    private fun options(): SpeechRecognizerOptions = speechRecognizerOptions {
        this.locale = this@MlKitAdapter.locale
        preferredMode = SpeechRecognizerOptions.Mode.MODE_BASIC
    }

    /**
     * add-hyungchul-20261106-2215 : recognize() 안에 있던 상태 확인·모델 받기를 함수로 뺐다(load 도 쓴다 — 본문은 v3 그대로)
     * 목적: 언어 모델을 쓸 수 있는지 확인하고, 받아야 하면 받는다.
     * 입력: recognizer - 인식기
     * 출력: 시스템이 모델을 받을 수 있다(최대 10분)
     * 리턴: 없음(AVAILABLE 이면 돌아온다)
     * 예외: IOException - checkStatus 무응답("응답하지 않았다" → isEngineDead), 받기 실패, 사용 불가
     */
    private suspend fun ensureAvailable(recognizer: SpeechRecognizer) {
        // 먹통일 때 여기서 무한 대기하지 않게 시간 제한을 둔다.
        var status = withTimeoutOrNull(FEATURE_STATUS_TIMEOUT_MS) { recognizer.checkStatus() }
            ?: throw IOException(
                "checkStatus() 가 ${FEATURE_STATUS_TIMEOUT_MS / 1000}초 안에 응답하지 않았다. " +
                    "인식 엔진(com.google.android.tts 의 SODA)이 먹통인 상태다.",
            )
        Log.i(logTag, "checkStatus=${statusName(status)} (locale=$locale)")
        if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
            // 다운로드도 무한정 기다리지 않는다.
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
            status = withTimeoutOrNull(FEATURE_STATUS_TIMEOUT_MS) { recognizer.checkStatus() }
                ?: throw IOException("다운로드 후 checkStatus() 가 응답하지 않았다.")
            Log.i(logTag, "after download, checkStatus=${statusName(status)}")
        }
        if (status != FeatureStatus.AVAILABLE) {
            throw IOException("모델 사용 불가: ${statusName(status)}. MODE_BASIC 은 API 31+ 대부분의 기기에서 제공된다.")
        }
    }

    /**
     * 목적: 인식기를 닫되, close() 가 멈춰도 작업이 끝나지 않는 사태를 막는다.
     * 입력: closeable - 닫을 인식기
     * 출력: 제한 시간을 넘기면 경고를 남긴다
     * 리턴: 없음
     * 비고: close() 는 네이티브 정리를 하는 블로킹 호출이라 코루틴 취소로 끊을 수 없다.
     *       제한 시간이 지나면 그 스레드는 남겨 두고 작업만 계속 진행시킨다.
     */
    private suspend fun closeRecognizerBounded(closeable: AutoCloseable) {
        val finished = withContext(NonCancellable) {          // 취소 중에도 닫기는 시도한다
            abandonable(RECOGNIZER_CLOSE_TIMEOUT_MS, "인식기 close()") { closeable.close() }
        }
        if (!finished) {
            log("[warn] 인식기 close() 가 ${RECOGNIZER_CLOSE_TIMEOUT_MS / 1000}초 안에 끝나지 않았다. 정리는 백그라운드에 맡기고 다음 파일로 진행한다.")
        }
    }

    /**
     * 목적: "코루틴 취소가 통하지 않는" 블로킹 호출에 진짜 시간 제한을 건다.
     * 입력: timeoutMs - 기다려 줄 시간(ms), tag - 로그에 남길 이름, block - 실행할 블로킹 코드
     * 출력: 블로킹 호출이 예외를 내면 로그에 남긴다
     * 리턴: 시간 안에 끝났으면 true, 포기했으면 false
     *
     * ★ 필요한 이유(2026-09-15 원인 규명): 코루틴의 취소는 suspend 지점에서만 확인된다. close() 처럼 네이티브에서 블로킹하는 호출은
     *   withTimeout 으로 감싸도 끝날 때까지 돌아오지 않는다. 그래서 데몬 스레드로 내보내고 본체는 그 결과만 기다린다.
     */
    private suspend fun abandonable(timeoutMs: Long, tag: String, block: () -> Unit): Boolean {
        val done = CompletableDeferred<Unit>()                // 스레드가 끝났음을 알리는 신호
        Thread({
            try {
                block()
            } catch (e: Exception) {
                Log.w(logTag, "$tag 실패: ${e.message}")      // 정리 실패는 치명적이지 않다(기록만 한다)
            }
            done.complete(Unit)
        }, "abandonable").apply { isDaemon = true }.start()    // 데몬 = 앱 종료를 막지 않는다
        // await() 는 suspend 지점이라 여기서는 시간 제한이 실제로 걸린다.
        return withTimeoutOrNull(timeoutMs) { done.await() } != null
    }

    /** 목적: 상태 값을 이름으로 바꾼다. 입력: status - FeatureStatus 값 / 출력: 없음 / 리턴: String */
    private fun statusName(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }

    companion object {
        /** run_meta·CSV 에 남기는 런타임 이름(app/build.gradle.kts 의 genai-speech-recognition 판과 같게 유지) */
        const val RUNTIME = "ML Kit GenAI Speech Recognition 1.0.0-alpha1 (basic)"
        /** 공급 간격 — 100 ms 분량을 100 ms 마다(실시간). ML Kit 공식 문서가 요구하는 속도 */
        const val REALTIME_DELAY_MS = 100L
    }
}
