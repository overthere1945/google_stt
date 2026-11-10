/*
 * 파일명: engine/stt/VoskAdapter.kt
 * 목적 및 기능:
 * - Vosk(Kaldi) 로 PCM 조각을 전사한다(vosk-model-small-ko-0.22 / vosk-model-small-en-us-0.15 — 모델이 언어별이다).
 * - v3 의 stt/VoskTranscriber.kt 를 옮긴 것이다. 전사 본문(100 ms 씩 넣기, 끝점마다 문장 확정, finalResult 로 마무리)은
 *   그대로이고 바뀐 점은 SherpaStreamingAdapter 와 같다: 모델 폴더 주입, delay 로 기다리기, 취소 확인, 공급 속도 NONE = 최대 속도.
 * - Vosk 결과는 실행마다 조금씩 달라질 수 있다(Kaldi 특징 추출의 dither 기본값 1.0 — 배포 모델의 mfcc.conf 에 --dither=0 이 없다).
 *   그래서 회귀 기준은 "오류 없이 끝남 + 글자 수 > 0" 이다(사용자 결정 2026-11-06). 모델 파일은 고치지 않는다.
 *
 * 공식 API 근거(vosk-android 0.3.75): Model(path), Recognizer(model, sampleRate), acceptWaveForm(byte[], len): boolean,
 *   getResult() / getPartialResult() / getFinalResult() — JSON 글자
 *
 * add-hyungchul-20261106-2140 — [T5-4]
 */
package com.example.google_stt.engine.stt

import android.util.Log                                       // Logcat 디버그 출력
import com.example.google_stt.domain.AppError                 // E-EN-01
import com.example.google_stt.domain.catalog.Language         // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec        // 모델 정의
import com.example.google_stt.domain.condition.FeedChoice     // 공급 속도
import com.example.google_stt.domain.csv.SttOutcome           // 전사 결과
import com.example.google_stt.engine.audio.Pcm16              // 표본화 주파수
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers                         // 계산 스레드
import kotlinx.coroutines.delay                               // 공급 간격 기다리기
import kotlinx.coroutines.ensureActive                        // 취소 확인
import kotlinx.coroutines.withContext                         // 스레드 바꾸기
import org.json.JSONException
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * 목적: Vosk 어댑터(작업 1개에 1개).
 * 입력(생성자): spec - 모델 정의(Runtime.VOSK)
 */
class VoskAdapter(override val spec: ModelSpec) : SttEngineAdapter {

    private var model: Model? = null                      // 불러 둔 모델(load 전에는 null)

    /**
     * 목적: 모델 폴더를 확인하고 Vosk 모델을 불러온다(작업 시작 때 1회).
     * 입력: modelDir - 자산 폴더(Vosk 는 폴더 경로를 받는다), lang - 평가 언어(모델이 언어별이라 쓰지 않는다)
     * 출력: 모델이 메모리에 올라간다
     * 리턴: LoadInfo
     * 예외: AppError.ModelLoad(E-EN-01) - 필수 파일이 없거나 모델을 만들 수 없을 때
     */
    override suspend fun load(modelDir: File?, lang: Language): LoadInfo = withContext(Dispatchers.Default) {
        val dir = requireModelDir(spec, modelDir)                                   // 빠진 필수 파일이 있으면 여기서 멈춘다
        close()                                                                     // add-hyungchul-20261106-2215 : 다시 불러도 앞서 올린 것을 놓고 시작한다(네이티브 메모리 누수 방지 — 독립 검토)
        dbg("load start: model=${dir.absolutePath} lang=${lang.code}")
        val t0 = System.nanoTime()                                                  // 불러오기 시작
        model = try {
            Model(dir.absolutePath)                                                 // 실패하면 IOException
        } catch (e: IOException) {
            throw AppError.ModelLoad("${spec.displayName}: 모델을 만들 수 없다 — ${e.message}", e)
        }
        val loadMs = ms(t0, System.nanoTime())
        dbg("load done: ${loadMs}ms (${spec.assetId})")
        LoadInfo(loadMs / 1000.0, RUNTIME)
    }

    /**
     * 목적: PCM 조각들을 공급 간격에 맞춰 넣어 전사하고 결과를 잇는다.
     * 입력: chunks - 조각들("엔진별 최대 길이" 면 파일 전체 1개, SEG10 이면 10초 이하 조각들),
     *       feed - 공급 속도(NONE 이면 기다리지 않는다), lang - 평가 언어(쓰지 않는다)
     * 출력: 없음
     * 리턴: SttOutcome(전사문, 디코딩한 조각 수, 첫 글자 시각, 꼬리 지연, 공급 시간)
     */
    override suspend fun transcribe(chunks: List<AudioChunk>, feed: FeedChoice, lang: Language): SttOutcome = withContext(Dispatchers.Default) {
        val m = model ?: throw IllegalStateException("Vosk 모델을 먼저 불러야 한다(load).")
        val delayMs = feed.delayMs                                                  // null = 최대 속도
        val sb = StringBuilder()
        var decoded = 0                                                             // 실제로 디코딩한 조각 수
        var firstNs = 0L                                                            // 첫 글자가 나온 시각
        var feedNs = 0L                                                             // 공급 시간 합
        var tailNs = 0L                                                             // 공급 끝 → 최종 결과 시간 합
        val startNs = System.nanoTime()

        // 목적: 결과 문장을 잇는다(빈 문장은 넣지 않는다). 입력: t - 문장 / 리턴: 없음
        fun append(t: String) {
            if (t.isEmpty()) return
            if (sb.isNotEmpty()) sb.append(' ')                                     // 문장 사이는 공백 하나
            sb.append(t)
        }

        for (chunk in chunks) {
            val c = chunk.pcm
            if (c.isEmpty()) continue                                               // 빈 조각은 넣지 않는다
            Recognizer(m, Pcm16.SAMPLE_RATE.toFloat()).use { rec ->                 // 조각마다 새 인식기(문맥 없음)
                dbg("chunk ${decoded + 1}/${chunks.size} start: bytes=${c.size} (${"%.2f".format(Locale.US, chunk.sec)}s) " +
                    "feed=${feed.name}/${delayMs}ms")
                val chunkStart = System.nanoTime()                                  // 이 조각 공급 시작(간격 기준점)
                var utterances = 0                                                  // 끝점으로 끝난 문장 수(디버그용)
                var k = 0                                                           // 넣은 100 ms 조각 번호
                var off = 0
                while (off < c.size) {
                    if (delayMs != null) {
                        val due = chunkStart + k * delayMs * 1_000_000L             // 이 조각이 "도착" 해야 할 시각
                        val waitMs = (due - System.nanoTime()) / 1_000_000L
                        if (waitMs > 0) delay(waitMs)                               // 일찍 왔으면 기다린다(늦었으면 바로 넣는다)
                    }
                    ensureActive()                                                  // 100 ms 조각마다 취소 확인
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
                    off = end
                    k++
                }
                val fedEnd = System.nanoTime()                                      // 마지막 조각 공급 끝
                feedNs += fedEnd - chunkStart
                val last = jsonText(rec.finalResult, "text")                        // 남은 오디오 처리 + 마지막 문장
                if (firstNs == 0L && last.isNotEmpty()) firstNs = System.nanoTime()
                append(last)
                tailNs += System.nanoTime() - fedEnd
                dbg("chunk ${decoded + 1}/${chunks.size} done: feed=${ms(chunkStart, fedEnd)}ms tail=${ms(fedEnd, System.nanoTime())}ms " +
                    "utterances=${utterances + 1} chars=${sb.length}")
            }                                                                       // use → 인식기 즉시 해제(취소돼도)
            decoded++
        }
        val first = if (firstNs != 0L) ms(startNs, firstNs) else -1
        dbg("transcribe done: decoded=$decoded/${chunks.size} first=${first}ms feed=${feedNs / 1_000_000}ms " +
            "tail=${tailNs / 1_000_000}ms wall=${ms(startNs, System.nanoTime())}ms chars=${sb.length}")
        sttOutcome(sb.toString(), decoded, first, tailMs = tailNs / 1_000_000, feedMs = feedNs / 1_000_000)
    }

    /**
     * 목적: 모델을 해제한다(작업 끝, finally 에서 부른다). 여러 번 불러도 안전하다.
     * 입력: 없음 / 출력: 네이티브 메모리가 풀린다 / 리턴: 없음
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
        private const val TAG = "GoogleStt"                                         // 앱 로그와 같은 태그

        /**
         * 목적: Logcat 전용 디버그 한 줄(태그 GoogleStt, 접두어 "[D][vosk]").
         * 입력: msg - 내용 / 출력: Logcat / 리턴: 없음
         */
        private fun dbg(msg: String) {
            Log.d(TAG, "[D][vosk] $msg")
        }

        /**
         * 목적: Vosk 결과 JSON 에서 글자를 꺼낸다.
         * 입력: json - getResult / getPartialResult / getFinalResult 글자, key - "text" 또는 "partial"
         * 출력: 없음
         * 리턴: 앞뒤 공백을 자른 글자(없거나 JSON 이 아니면 "")
         */
        fun jsonText(json: String?, key: String): String =
            try {
                JSONObject(json ?: "").optString(key, "").trim()
            } catch (e: JSONException) {
                ""                                                                  // JSON 이 아닌 값은 빈 결과로 본다(v3 와 같은 동작)
            }
    }
}
