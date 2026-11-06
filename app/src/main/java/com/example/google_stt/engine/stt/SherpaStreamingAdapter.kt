/*
 * 파일명: engine/stt/SherpaStreamingAdapter.kt
 * 목적 및 기능:
 * - sherpa-onnx 스트리밍 인식기(OnlineRecognizer)로 PCM 조각을 전사한다(Nemotron 3.5 스트리밍, Zipformer KO 스트리밍).
 * - v3 의 stt/SherpaStreamingTranscriber.kt 를 옮긴 것이다. 디코딩 본문(100 ms 씩 넣고 바로 디코딩, 끝에 0.5 s 무음 +
 *   inputFinished, 토큰으로 글자 만들기)은 그대로이고 바뀐 점은:
 *     · 모델 폴더를 인자로 받는다. 모델 정의는 ModelSpec, 언어는 Language
 *     · 기다림이 Thread.sleep → delay(취소에 바로 반응). 100 ms 조각마다 취소를 확인한다
 *     · 공급 속도 NONE(사용 안 함)이면 기다리지 않고 최대 속도로 넣는다(v4 기본 — D26). 이때도 공급 시간은 잰다
 * - 계측: 공급 시간 = 조각을 다 넣기까지, 꼬리 지연 = 공급 끝 → 최종 결과(조각이 여러 개면 합).
 *
 * 공식 API 근거(sherpa-onnx v1.13.8 kotlin-api/OnlineRecognizer.kt):
 *   OnlineRecognizer(assetManager = null, config), createStream(hotwords), OnlineStream.acceptWaveform / inputFinished / setOption,
 *   isReady(stream) / decode(stream) / getResult(stream).tokens
 *
 * add-hyungchul-20261106-2140 — [T5-3]
 */
package com.example.google_stt.engine.stt

import android.util.Log                                                  // Logcat 디버그 출력
import com.example.google_stt.domain.AppError                            // E-EN-01
import com.example.google_stt.domain.catalog.FileRole                    // 파일 역할 이름
import com.example.google_stt.domain.catalog.Language                    // 평가 언어
import com.example.google_stt.domain.catalog.ModelFamily                 // 계열 이름
import com.example.google_stt.domain.catalog.ModelSpec                   // 모델 정의
import com.example.google_stt.domain.condition.FeedChoice                // 공급 속도
import com.example.google_stt.domain.csv.SttOutcome                      // 전사 결과
import com.example.google_stt.engine.audio.Pcm16                         // PCM16 → float
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers                                    // 계산 스레드
import kotlinx.coroutines.delay                                          // 공급 간격 기다리기
import kotlinx.coroutines.ensureActive                                   // 취소 확인
import kotlinx.coroutines.withContext                                    // 스레드 바꾸기

/**
 * 목적: sherpa-onnx 스트리밍 어댑터(작업 1개에 1개).
 * 입력(생성자): spec - 모델 정의(Runtime.SHERPA_STREAMING)
 */
class SherpaStreamingAdapter(override val spec: ModelSpec) : SttEngineAdapter {

    private var recognizer: OnlineRecognizer? = null      // 불러 둔 인식기(load 전에는 null)

    /**
     * 목적: 모델 파일을 확인하고 인식기를 만든다(작업 시작 때 1회).
     * 입력: modelDir - 자산 폴더, lang - 평가 언어(로그용 — 언어 옵션은 스트림마다 넣는다)
     * 출력: 인식기가 메모리에 올라간다
     * 리턴: LoadInfo
     * 예외: AppError.ModelLoad(E-EN-01) - 파일이 없거나 인식기를 만들 수 없을 때
     */
    override suspend fun load(modelDir: File?, lang: Language): LoadInfo = withContext(Dispatchers.Default) {
        val dir = requireModelDir(spec, modelDir)                                   // 빠진 파일이 있으면 여기서 멈춘다
        close()                                                                     // add-hyungchul-20261106-2215 : 다시 불러도 앞서 올린 것을 놓고 시작한다(네이티브 메모리 누수 방지 — 독립 검토)
        fun path(role: String): String = File(dir, spec.files.getValue(role)).absolutePath   // 역할 → 절대 경로
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = path(FileRole.ENCODER),
                    decoder = path(FileRole.DECODER),
                    joiner = path(FileRole.JOINER),
                ),
                tokens = path(FileRole.TOKENS),
                numThreads = SherpaOfflineAdapter.NUM_THREADS,                      // 오프라인 엔진과 같은 4 스레드
                provider = "cpu",
            ),
            enableEndpoint = false,                                                 // 파일을 끝까지 한 스트림으로 넣는다(끝점에서 끊지 않는다)
        )
        dbg("load start: family=${spec.family} threads=${SherpaOfflineAdapter.NUM_THREADS} lang='${streamLanguage(lang)}' " +
            spec.files.keys.joinToString(" ") { "$it=${path(it)}" })               // 네이티브에 넘기는 경로(여기서 죽으면 마지막 줄)
        val t0 = System.nanoTime()                                                  // 불러오기 시작
        recognizer = try {
            OnlineRecognizer(config = config)                                       // assetManager = null → 파일 경로
        } catch (e: RuntimeException) {
            throw AppError.ModelLoad("${spec.displayName}: 인식기를 만들 수 없다 — ${e.message}", e)
        }
        val loadMs = ms(t0, System.nanoTime())
        dbg("load done: ${loadMs}ms (${spec.assetId})")
        LoadInfo(loadMs / 1000.0, SherpaOfflineAdapter.RUNTIME)
    }

    /**
     * 목적: PCM 조각들을 공급 간격에 맞춰 스트리밍 디코딩하고 결과를 잇는다.
     * 입력: chunks - 조각들("엔진별 최대 길이" 면 파일 전체 1개, SEG10 이면 10초 이하 조각들),
     *       feed - 공급 속도(100 ms 조각마다 delayMs. NONE 이면 기다리지 않는다), lang - 평가 언어
     * 출력: 없음
     * 리턴: SttOutcome(전사문, 디코딩한 조각 수, 첫 글자 시각, 꼬리 지연, 공급 시간)
     */
    override suspend fun transcribe(chunks: List<AudioChunk>, feed: FeedChoice, lang: Language): SttOutcome = withContext(Dispatchers.Default) {
        val rec = recognizer ?: throw IllegalStateException("sherpa 스트리밍 모델을 먼저 불러야 한다(load).")
        val option = streamLanguage(lang)                                           // Nemotron "ko" / "en", 그 밖 ""(옵션 안 넣음)
        val delayMs = feed.delayMs                                                  // null = 최대 속도
        val sb = StringBuilder()
        var decoded = 0                                                             // 실제로 디코딩한 조각 수
        var firstNs = 0L                                                            // 첫 글자가 나온 시각
        var feedNs = 0L                                                             // 공급 시간 합
        var tailNs = 0L                                                             // 공급 끝 → 최종 결과 시간 합
        val startNs = System.nanoTime()
        for (chunk in chunks) {
            val c = chunk.pcm
            if (c.isEmpty()) continue                                               // 빈 조각은 넣지 않는다
            val stream = rec.createStream("")                                       // 조각마다 새 스트림(핫워드 없음)
            try {
                if (option.isNotEmpty()) stream.setOption("language", option)
                dbg("chunk ${decoded + 1}/${chunks.size} start: bytes=${c.size} (${"%.2f".format(Locale.US, chunk.sec)}s) " +
                    "lang='$option' feed=${feed.name}/${delayMs}ms")
                val chunkStart = System.nanoTime()                                  // 이 조각 공급 시작(간격 기준점)
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
                    stream.acceptWaveform(Pcm16.toFloat(c.copyOfRange(off, end)), Pcm16.SAMPLE_RATE)
                    while (rec.isReady(stream)) rec.decode(stream)                  // 들어온 만큼 바로 디코딩
                    if (firstNs == 0L && rec.getResult(stream).tokens.isNotEmpty()) firstNs = System.nanoTime()   // 첫 글자
                    off = end
                    k++
                }
                val fedEnd = System.nanoTime()                                      // 마지막 조각 공급 끝
                feedNs += fedEnd - chunkStart
                stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), Pcm16.SAMPLE_RATE)   // 0.5 s 무음(마지막 청크 밀어내기)
                stream.inputFinished()
                while (rec.isReady(stream)) rec.decode(stream)
                val t = SherpaOfflineAdapter.textFromTokens(rec.getResult(stream).tokens)   // ★ 띄어쓰기를 살린 글자
                tailNs += System.nanoTime() - fedEnd
                dbg("chunk ${decoded + 1}/${chunks.size} done: feed=${ms(chunkStart, fedEnd)}ms tail=${ms(fedEnd, System.nanoTime())}ms chars=${t.length}")
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')                             // 조각 결과는 공백 하나로 잇는다
                    sb.append(t)
                }
            } finally {
                stream.release()                                                    // 네이티브 메모리 즉시 해제(취소돼도)
            }
            decoded++
        }
        val first = if (firstNs != 0L) ms(startNs, firstNs) else -1
        dbg("transcribe done: decoded=$decoded/${chunks.size} first=${first}ms feed=${feedNs / 1_000_000}ms " +
            "tail=${tailNs / 1_000_000}ms wall=${ms(startNs, System.nanoTime())}ms chars=${sb.length}")
        sttOutcome(sb.toString(), decoded, first, tailMs = tailNs / 1_000_000, feedMs = feedNs / 1_000_000)
    }

    /**
     * 목적: 인식기를 해제한다(작업 끝, finally 에서 부른다). 여러 번 불러도 안전하다.
     * 입력: 없음 / 출력: 네이티브 메모리가 풀린다 / 리턴: 없음
     */
    override fun close() {
        dbg("close: recognizer=${if (recognizer != null) "loaded" else "null"}")
        recognizer?.release()
        recognizer = null
    }

    /**
     * 목적: 스트림에 넣을 언어 옵션 값을 정한다.
     * 입력: lang - 평가 언어 / 출력: 없음
     * 리턴: Nemotron 이면 "ko" / "en"(패키지 README 의 형식), 그 밖(Zipformer KO — 한국어 전용)은 ""(옵션을 넣지 않는다)
     */
    private fun streamLanguage(lang: Language): String = if (spec.family == ModelFamily.NEMOTRON) lang.code else ""

    private companion object {
        const val TAG = "GoogleStt"                                                 // 앱 로그와 같은 태그
        /** 꼬리 무음 0.5 s — sherpa 공식 Kotlin 스트리밍 예제의 tailPaddings 와 같은 길이(실측: 0~1 s 에서 결과 동일) */
        const val TAIL_PADDING_SAMPLES = Pcm16.SAMPLE_RATE / 2

        /**
         * 목적: Logcat 전용 디버그 한 줄(태그 GoogleStt, 접두어 "[D][sherpa-stream]").
         * 입력: msg - 내용 / 출력: Logcat / 리턴: 없음
         */
        fun dbg(msg: String) {
            Log.d(TAG, "[D][sherpa-stream] $msg")
        }
    }
}
