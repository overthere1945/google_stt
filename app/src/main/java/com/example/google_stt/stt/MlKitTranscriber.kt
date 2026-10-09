/*
 * 파일명: stt/MlKitTranscriber.kt
 * 목적 및 기능:
 * - ML Kit GenAI Speech Recognition 으로 PCM 1건을 전사하고 지연(ready/first/feed/tail/wall)을 계측한다.
 * - 인식기 close()/stopRecognition() 이 네이티브에서 멈춰도 배치가 끝나지 않도록 "포기 가능한" 시간 제한을 건다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 transcribeMlKit / closeRecognizerBounded / abandonable / statusName 을
 *   내용 변경 없이 이 클래스로 옮겼다. 화면 로그(appendLog)는 생성자로 받은 log 함수로 대신한다.
 */
package com.example.google_stt.stt

import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * 목적: ML Kit GenAI Speech Recognition 전사기.
 * 입력(생성자): log — 화면 로그에 한 줄을 남기는 함수(MainActivity.appendLog 를 넘긴다)
 */
class MlKitTranscriber(private val log: (String) -> Unit) {

    private val logTag = "GoogleStt"   // change-hyungchul-20261009-1514 : MainActivity 의 logTag 와 같은 값

    // ─────────────────────── (1)(2) ML Kit GenAI Speech Recognition ───────────────────────
    /**
     * 목적: ML Kit GenAI Speech Recognition 으로 PCM 1건을 전사하고 지연을 계측한다.
     * 입력: pcm(16k/mono/PCM16), locale, mode(MODE_BASIC/MODE_ADVANCED), feed(공급 속도), onFed(진행률)
     * 출력: SttOutcome
     * 예외: 모델이 AVAILABLE 이 아니거나 인식 오류 시 IOException
     */
    suspend fun transcribeMlKit(
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
    suspend fun closeRecognizerBounded(closeable: AutoCloseable) {
        // change-hyungchul-20260915-1400 : 진짜로 포기할 수 있게 별도 스레드로 옮긴다.
        val finished = withContext(NonCancellable) {          // 취소 중에도 닫기는 시도한다
            abandonable(RECOGNIZER_CLOSE_TIMEOUT_MS, "인식기 close()") { closeable.close() }
        }
        if (!finished) {
            log(
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

    fun statusName(status: Int): String = when (status) {
        FeatureStatus.AVAILABLE -> "AVAILABLE"
        FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE"
        FeatureStatus.DOWNLOADING -> "DOWNLOADING"
        FeatureStatus.UNAVAILABLE -> "UNAVAILABLE"
        else -> "UNKNOWN($status)"
    }
}
