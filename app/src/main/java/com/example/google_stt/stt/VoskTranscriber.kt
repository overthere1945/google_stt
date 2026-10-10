/*
 * 파일명: stt/VoskTranscriber.kt
 * 목적 및 기능:
 * - Vosk(Kaldi) 스트리밍 인식기로 PCM 조각을 전사한다(2단계 2-4, 모델: vosk-model-small-ko-0.22 / vosk-model-small-en-us-0.15).
 * - 라이브러리: com.alphacephei:vosk-android:0.3.75 (Maven Central, Apache-2.0, JNA 5.18.1 로 libvosk.so 를 부른다).
 * - 모델은 배치 시작 때 1번만 불러(load) 배치 끝에 해제한다(close) — 불러오는 시간이 파일별 계측에 섞이지 않게.
 * - 공급(사용자 결정 Q19·Q27 "스트리밍 엔진은 Feed 설정을 따른다" — SherpaStreamingTranscriber 와 같은 방식):
 *     · 조각마다 Recognizer 를 새로 만들고(앞 조각 문맥 없음), 100 ms(CHUNK_BYTES) 를 "시작 시각 + k × delayMs" 에 넣는다.
 *     · acceptWaveForm 이 true 면(Vosk 내부 끝점 — 말 사이 무음) getResult 의 "text" 를 잇는다.
 *     · 첫 글자 시각: getPartialResult 의 "partial" 또는 getResult 의 "text" 가 처음 비지 않은 순간(first_result_ms).
 *     · 다 넣으면 getFinalResult(남은 오디오를 모두 처리하는 공식 마무리 호출)로 마지막 문장을 얻는다(꼬리 무음 없음).
 * - 계측: first_result_ms(시작 → 첫 글자), feed_ms(공급 시간 합), tail_ms(공급 끝 → 최종 결과, 조각마다 합),
 *         stt_wall_ms(시작 → 끝), segments(디코딩한 조각 수).
 * - 모델 위치: SherpaOfflineTranscriber.modelsRoot 와 같은 /sdcard/Documents/stt_models/<SherpaModelSpec.dirName>/
 *   (Vosk 모델은 폴더째로 쓴다. 필수 파일 am/final.mdl + conf/model.conf — vosk-api src/model.cc 의 v2 배치 확인 규칙)
 *
 * 공식 API 근거(vosk-api master = 0.3.75, android/lib/src/main/java/org/vosk):
 *   Model(path: String) — 실패하면 IOException("Failed to create a model"), close()
 *   Recognizer(model, sampleRate: Float), acceptWaveForm(data: ByteArray, len: Int): Boolean,
 *   getResult() {"text": …} / getPartialResult() {"partial": …} / getFinalResult() {"text": …}, close()
 *
 * add-hyungchul-20261011-0301
 */
package com.example.google_stt.stt

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.IOException
import java.util.Locale

/**
 * 목적: Vosk 전사기(배치 1회에 1개).
 * 입력(생성자): context — 모델 폴더 위치를 얻는 함수(SherpaOfflineTranscriber.modelDir 등)에 넘긴다
 */
class VoskTranscriber(private val context: Context) : StreamingSttTranscriber {

    private var model: Model? = null                      // 불러 둔 모델(load 전에는 null)

    /** 모델을 불러오는 데 걸린 시간(ms). load 전에는 -1 */
    override var loadMs: Long = -1
        private set

    /**
     * 목적: 모델 폴더를 점검하고 Vosk 모델을 불러온다(배치 시작 때 1회).
     * 입력: spec — 모델 정의(SherpaFamily.VOSK), locale — 로그용 언어
     * 출력: 없음 / 리턴: 없음
     * 예외: 필수 파일이 없으면 IOException(안내문 포함), 모델을 만들지 못하면 Vosk 의 IOException
     */
    override fun load(spec: SherpaModelSpec, locale: Locale) {
        require(spec.family == SherpaFamily.VOSK) { "Vosk 모델이 아닙니다: ${spec.family}" }
        SherpaOfflineTranscriber.logDirReport(context, spec)                       // 모델 폴더 상태를 Logcat 에 먼저 남긴다
        val missing = SherpaOfflineTranscriber.missingFiles(context, spec)          // 빠진 필수 파일 목록
        if (missing.isNotEmpty()) throw IOException(SherpaOfflineTranscriber.missingMessage(context, spec, missing))
        val dir = SherpaOfflineTranscriber.modelDir(context, spec)                  // Vosk 는 모델 폴더 경로를 받는다
        dbg("load start: model=${dir.absolutePath} locale=${locale.toLanguageTag()}")
        val t0 = System.nanoTime()                                                  // 불러오기 시작
        model = Model(dir.absolutePath)                                             // 실패하면 IOException
        loadMs = ms(t0, System.nanoTime())
        dbg("load done: ${loadMs}ms (${spec.dirName})")
    }

    /**
     * 목적: PCM 조각들을 Feed 간격으로 넣어 전사하고 결과를 잇는다.
     * 입력: chunks — 16 kHz/mono/PCM16 LE 조각("엔진별 최대 길이" 면 파일 전체 1개, SEG10 이면 10초 이하 조각들),
     *       locale — 인식 언어(모델이 언어별이라 쓰지 않음), feed — 공급 간격, onFed — 진행률(0.0~1.0, 바이트 기준)
     * 리턴: SttOutcome(text, first_result_ms, feed_ms, tail_ms, stt_wall_ms, segments)
     */
    override fun transcribe(chunks: List<ByteArray>, locale: Locale, feed: FeedConfig, onFed: (Double) -> Unit): SttOutcome {
        val m = model ?: throw IllegalStateException("Vosk 모델을 먼저 불러야 합니다(load).")
        val totalBytes = chunks.sumOf { it.size }.coerceAtLeast(1)                  // 진행률 분모
        val sb = StringBuilder()
        var doneBytes = 0
        var decoded = 0                                                             // 실제로 디코딩한 조각 수
        var firstNs = 0L                                                            // 첫 글자가 나온 시각
        var feedNs = 0L                                                             // 공급 시간 합
        var tailNs = 0L                                                             // 공급 끝 → 최종 결과 시간 합
        val startNs = System.nanoTime()

        // 목적: 결과 문장을 잇는다(빈 문장은 넣지 않는다). 입력: t — 문장 / 리턴: 없음
        fun append(t: String) {
            if (t.isEmpty()) return
            if (sb.isNotEmpty()) sb.append(' ')                                     // 문장 사이는 공백 하나
            sb.append(t)
        }

        for (c in chunks) {
            if (c.isEmpty()) continue                                               // 빈 조각은 넣지 않는다
            Recognizer(m, SAMPLE_RATE.toFloat()).use { rec ->                       // 조각마다 새 인식기(문맥 없음)
                dbg("chunk ${decoded + 1}/${chunks.size} start: bytes=${c.size} (${"%.2f".format(Locale.US, c.size / BYTES_PER_SEC.toDouble())}s) " +
                    "feed=${feed.name}/${feed.delayMs}ms")
                val chunkStart = System.nanoTime()                                  // 이 조각 공급 시작(간격 기준점)
                var utterances = 0                                                  // 끝점으로 끝난 문장 수(디버그용)
                var k = 0                                                           // 넣은 100 ms 조각 번호
                var off = 0
                while (off < c.size) {
                    val due = chunkStart + k * feed.delayMs * 1_000_000L            // 이 조각이 "도착" 해야 할 시각
                    val waitMs = (due - System.nanoTime()) / 1_000_000L
                    if (waitMs > 0) Thread.sleep(waitMs)                            // 일찍 왔으면 기다린다(늦었으면 바로 넣는다)
                    val end = minOf(off + CHUNK_BYTES, c.size)
                    val piece = c.copyOfRange(off, end)                             // 100 ms PCM16 LE
                    if (rec.acceptWaveForm(piece, piece.size)) {                    // true = 끝점(말 사이 무음) → 문장 확정
                        val t = jsonText(rec.result, "text")
                        if (firstNs == 0L && t.isNotEmpty()) firstNs = System.nanoTime()
                        append(t)
                        utterances++
                    } else if (firstNs == 0L && jsonText(rec.partialResult, "partial").isNotEmpty()) {
                        firstNs = System.nanoTime()                                 // 첫 글자(중간 결과)
                    }
                    doneBytes += end - off
                    off = end
                    k++
                    onFed(doneBytes.toDouble() / totalBytes)
                }
                val fedEnd = System.nanoTime()                                      // 마지막 조각 공급 끝
                feedNs += fedEnd - chunkStart
                val last = jsonText(rec.finalResult, "text")                        // 남은 오디오 처리 + 마지막 문장
                if (firstNs == 0L && last.isNotEmpty()) firstNs = System.nanoTime()
                append(last)
                tailNs += System.nanoTime() - fedEnd
                dbg("chunk ${decoded + 1}/${chunks.size} done: feed=${ms(chunkStart, fedEnd)}ms tail=${ms(fedEnd, System.nanoTime())}ms " +
                    "utterances=${utterances + 1} chars=${sb.length}")
            }                                                                       // use → 인식기 즉시 해제
            decoded++
        }
        val wall = ms(startNs, System.nanoTime())
        dbg("transcribe done: decoded=$decoded/${chunks.size} first=${if (firstNs != 0L) ms(startNs, firstNs) else -1}ms " +
            "feed=${feedNs / 1_000_000}ms tail=${tailNs / 1_000_000}ms wall=${wall}ms chars=${sb.length}")
        return SttOutcome(
            text = sb.toString().trim(),
            firstResultMs = if (firstNs != 0L) ms(startNs, firstNs) else -1,
            feedMs = feedNs / 1_000_000,
            tailMs = tailNs / 1_000_000,
            sttWallMs = wall,
            segments = decoded,
        )
    }

    /**
     * 목적: 모델을 해제한다(배치 끝, finally 에서 부른다). 여러 번 불러도 안전하다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    override fun close() {
        dbg("close: model=${if (model != null) "loaded" else "null"}")
        model?.close()
        model = null
    }

    companion object {
        /** run_meta·CSV 에 남기는 런타임 이름(app/build.gradle.kts 의 vosk-android 판과 같게 유지) */
        const val RUNTIME = "vosk-android 0.3.75 (Kaldi)"
        /** Vosk Recognizer 는 부른 스레드 하나에서 디코딩한다(run_meta stt_num_threads) */
        const val NUM_THREADS = 1
        private const val TAG = "GoogleStt"                                         // MainActivity / BatchRunner 의 logTag 와 같은 값
        private const val SAMPLE_RATE = 16_000

        /**
         * 목적: Logcat 전용 디버그 한 줄(사용자 결정 Q22·Q23 — 태그 GoogleStt, 접두어 "[D][vosk]").
         * 입력: msg / 출력: Logcat / 리턴: 없음
         */
        private fun dbg(msg: String) {
            Log.d(TAG, "[D][vosk] $msg")
        }

        /**
         * 목적: Vosk 결과 JSON 에서 글자를 꺼낸다.
         * 입력: json — getResult / getPartialResult / getFinalResult 문자열, key — "text" 또는 "partial"
         * 리턴: 앞뒤 공백을 자른 글자(없거나 JSON 이 아니면 "")
         */
        fun jsonText(json: String?, key: String): String =
            runCatching { JSONObject(json ?: "").optString(key, "").trim() }.getOrDefault("")
    }
}
