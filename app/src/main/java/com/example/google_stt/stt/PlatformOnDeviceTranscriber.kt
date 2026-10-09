/*
 * 파일명: stt/PlatformOnDeviceTranscriber.kt
 * 목적 및 기능:
 * - Android Framework 의 SpeechRecognizer.createOnDeviceSpeechRecognizer() 로 PCM 1건을 전사하고 지연을 계측한다.
 * - on-device 언어 모델 설치 여부 조회(checkRecognitionSupport)와 다운로드 요청을 담당한다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 transcribeAndroidOnDevice / checkAndroidOnDeviceSupport / errorName 을
 *   내용 변경 없이 옮겼다. Activity 의 this(Context) 는 생성자로 받은 context 로 바꿨다.
 *   ※ startListening()/destroy() 는 메인 스레드에서 불러야 하므로, 호출부는 Main 디스패처에서 불러야 한다.
 */
package com.example.google_stt.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.google_stt.AudioDecoder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * 목적: Android 플랫폼 on-device 인식 전사기.
 * 입력(생성자): context — 인식기 생성에 쓸 Context(Activity 를 넘긴다. 기존 코드와 동일)
 */
class PlatformOnDeviceTranscriber(private val context: Context) {

    private val logTag = "GoogleStt"   // change-hyungchul-20261009-1514 : MainActivity 의 logTag 와 같은 값

    // ─────────────── (3) Android Platform On-device SpeechRecognizer ───────────────
    /**
     * 목적: Android Framework 의 createOnDeviceSpeechRecognizer() 로 PCM 1건을 전사하고 지연을 계측한다.
     * 입력: pcm(16k/mono/PCM16), locale, feed(공급 속도), onFed(진행률)
     * 출력: SttOutcome (NO_MATCH 이면 text 가 빈 문자열)
     * 예외: API 33 미만, on-device RecognitionService 부재, 그 외 인식 오류 시 IOException
     */
    suspend fun transcribeAndroidOnDevice(
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
        if (!AndroidSpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            throw IOException("이 기기에는 Android on-device RecognitionService 가 없다.")
        }

        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        val recognizer = AndroidSpeechRecognizer.createOnDeviceSpeechRecognizer(context)
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
    suspend fun checkAndroidOnDeviceSupport(locale: Locale): String {
        val recognizer = AndroidSpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        }
        val out = CompletableDeferred<String>()
        return try {
            recognizer.checkRecognitionSupport(
                intent,
                ContextCompat.getMainExecutor(context),
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

    fun errorName(error: Int): String = when (error) {
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
}
