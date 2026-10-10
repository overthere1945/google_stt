/*
 * 파일명: stt/SherpaStreamingTranscriber.kt
 * 목적 및 기능:
 * - sherpa-onnx(정적 링크 AAR 1.13.7) 스트리밍 인식기(OnlineRecognizer, transducer)로 Nemotron 3.5 ASR 스트리밍 0.6B 를 돌린다
 *   (2단계 2-2, 사용자 결정 Q25: Nemotron 먼저 / Cohere 보류, Q26: 지연 5종 등록).
 *   change-hyungchul-20261011-0150 : 등록은 320 / 560 ms 2종으로 줄였다(사용자 결정 Q28).
 * - 모델은 배치 시작 때 1번만 불러(load) 배치 끝에 해제한다(close) — 불러오는 시간이 파일별 계측에 섞이지 않게.
 * - 공급(사용자 결정 Q19·Q27 "Feed 설정을 따른다"):
 *     · 100 ms(CHUNK_BYTES = 3,200 바이트) 조각을 FeedConfig.delayMs 간격으로 넣는다(REALTIME = 실시간, D50 = 2배속 …).
 *     · 간격은 "시작 시각 + k × delayMs" 기준으로 맞춘다(마이크처럼 오디오가 정해진 시각에 도착 — 디코딩이 늦으면 밀린 만큼 바로 넣는다).
 *     · 조각을 넣을 때마다 isReady 인 동안 decode 하고, 첫 글자가 나온 시각을 first_result_ms 로 남긴다.
 *     · 다 넣으면 0.5 s 무음(공식 Kotlin 예제의 tailPaddings 와 같은 길이) + inputFinished → 남은 decode → 최종 결과.
 * - 계측: first_result_ms(시작 → 첫 글자), feed_ms(공급 시간 합), tail_ms(마지막 조각 공급 끝 → 최종 결과, 조각마다 합),
 *         stt_wall_ms(시작 → 끝), segments(디코딩한 조각 수 — "엔진별 최대 길이" 면 1, SEG10 이면 10초 조각 수)
 * - ★ 결과 글자: sherpa 1.13.7 의 OnlineRecognizerResult.text 는 한국어(한글)의 띄어쓰기를 모두 지운다
 *   (이 작업 환경에서 같은 AAR Kotlin API + 리눅스 JNI 로 실측: text="조국이당신을…", tokens=" 조|국|이| |당|신|을…").
 *   그래서 tokens 를 이어 붙여 글자를 만들고 공백을 하나로 줄인다(영어·프랑스어 결과는 text 와 같아진다 — 실측 확인).
 * - 언어: OnlineStream.setOption("language", "ko" / "en") — 패키지 README 의 per-stream language string.
 *   지원하지 않는 값이면 네이티브가 "using auto" 로 바꾼다(libsherpa-onnx-jni.so 문자열 확인).
 * - 모델 위치: SherpaOfflineTranscriber.modelsRoot 와 같은 /sdcard/Documents/stt_models/<SherpaModelSpec.dirName>/
 *
 * 공식 API 근거(sherpa-onnx v1.13.7 kotlin-api, AAR classes.jar javap 확인):
 *   OnlineRecognizer(assetManager: AssetManager? = null, config: OnlineRecognizerConfig) — null 이면 파일 경로로 읽는다
 *   OnlineRecognizerConfig(modelConfig = OnlineModelConfig(transducer = OnlineTransducerModelConfig(encoder, decoder, joiner), tokens, numThreads, provider), enableEndpoint)
 *   OnlineRecognizer.createStream(hotwords) / isReady / decode / getResult(stream).tokens
 *   OnlineStream.acceptWaveform(samples, sampleRate) / inputFinished() / setOption(key, value) / release()
 *
 * add-hyungchul-20261011-0031
 *
 * add-hyungchul-20261011-0150 — 2단계 2-3: Zipformer KO 스트리밍(transducer)도 이 전사기로 돌린다.
 *   · 설정(encoder/decoder/joiner + tokens)·공급·계측은 Nemotron 과 같다. 언어 옵션은 Nemotron 에만 넣는다(Zipformer KO 는 한국어 전용).
 *   · 결과 text 의 한글 띄어쓰기 삭제도 같아서(실측) 같은 textFromTokens 를 쓴다.
 */
package com.example.google_stt.stt

import android.content.Context
import android.util.Log
import com.example.google_stt.vad.VadAudio
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 목적: sherpa-onnx 스트리밍 전사기(배치 1회에 1개).
 * 입력(생성자): context — 모델 폴더 위치를 얻는 함수(SherpaOfflineTranscriber.modelDir 등)에 넘긴다
 */
class SherpaStreamingTranscriber(private val context: Context) : AutoCloseable {

    private var recognizer: OnlineRecognizer? = null      // 불러 둔 인식기(load 전에는 null)
    private var family: SherpaFamily? = null              // add-hyungchul-20261011-0150 : 불러 둔 모델 계열(언어 옵션을 넣을지 정한다)

    /** 모델을 불러오는 데 걸린 시간(ms). load 전에는 -1 */
    var loadMs: Long = -1
        private set

    /**
     * 목적: 모델 폴더를 점검하고 인식기를 만든다(배치 시작 때 1회).
     * 입력: spec — 모델 정의(NEMOTRON_STREAMING), locale — 로그용 언어
     * 출력: 없음 / 리턴: 없음
     * 예외: 파일이 없으면 IOException(없는 파일로 네이티브를 부르면 앱이 통째로 죽으므로 먼저 확인한다)
     */
    fun load(spec: SherpaModelSpec, locale: Locale) {
        SherpaOfflineTranscriber.logDirReport(context, spec)                       // 모델 폴더 상태를 Logcat 에 먼저 남긴다
        val missing = SherpaOfflineTranscriber.missingFiles(context, spec)          // 빠진 파일 목록
        if (missing.isNotEmpty()) throw IOException(SherpaOfflineTranscriber.missingMessage(context, spec, missing))
        loadFromDir(SherpaOfflineTranscriber.modelDir(context, spec), spec, locale)
    }

    /**
     * 목적: 폴더 안의 모델 파일로 인식기를 만든다(load 의 본체 — 경로 점검 없이 바로 만든다).
     * 입력: dir — 모델 폴더, spec — 모델 정의, locale — 로그용 언어
     * 출력: 없음 / 리턴: 없음
     */
    internal fun loadFromDir(dir: File, spec: SherpaModelSpec, locale: Locale) {
        require(spec.family.streaming) { "스트리밍 모델이 아닙니다: ${spec.family}" }   // change-hyungchul-20261011-0150 : Nemotron 만 → streaming 계열 모두
        fun path(role: String): String = File(dir, spec.files.getValue(role)).absolutePath   // 역할 → 절대 경로
        val config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = path(SherpaRole.ENCODER),
                    decoder = path(SherpaRole.DECODER),
                    joiner = path(SherpaRole.JOINER),
                ),
                tokens = path(SherpaRole.TOKENS),
                numThreads = SherpaOfflineTranscriber.NUM_THREADS,                  // 오프라인 엔진과 같은 4 스레드
                provider = "cpu",
            ),
            enableEndpoint = false,                                                 // 파일을 끝까지 한 스트림으로 넣는다(끝점에서 끊지 않는다)
        )
        dbg("load start: family=${spec.family} threads=${SherpaOfflineTranscriber.NUM_THREADS} lang='${streamLanguage(spec.family, locale)}' " +   // change-hyungchul-20261011-0150
            spec.files.keys.joinToString(" ") { "$it=${path(it)}" })               // 네이티브에 넘기는 경로(여기서 죽으면 마지막 줄)
        val t0 = System.nanoTime()                                                  // 불러오기 시작
        recognizer = OnlineRecognizer(config = config)                              // assetManager = null → 파일 경로
        loadMs = ms(t0, System.nanoTime())
        family = spec.family                                                        // add-hyungchul-20261011-0150
        dbg("load done: ${loadMs}ms (${spec.dirName})")
    }

    /**
     * 목적: PCM 조각들을 Feed 간격으로 스트리밍 디코딩하고 결과를 잇는다.
     * 입력: chunks — 16 kHz/mono/PCM16 LE 조각("엔진별 최대 길이" 면 파일 전체 1개, SEG10 이면 10초 이하 조각들),
     *       locale — 인식 언어, feed — 공급 간격(100 ms 조각마다 delayMs), onFed — 진행률(0.0~1.0, 바이트 기준)
     * 리턴: SttOutcome(text, first_result_ms, feed_ms, tail_ms, stt_wall_ms, segments)
     */
    fun transcribe(chunks: List<ByteArray>, locale: Locale, feed: FeedConfig, onFed: (Double) -> Unit): SttOutcome {
        val rec = recognizer ?: throw IllegalStateException("sherpa 스트리밍 모델을 먼저 불러야 합니다(load).")
        val lang = streamLanguage(family, locale)                                   // change-hyungchul-20261011-0150 : Nemotron "ko" / "en", 그 밖 ""(옵션 안 넣음)
        val totalBytes = chunks.sumOf { it.size }.coerceAtLeast(1)                  // 진행률 분모
        val sb = StringBuilder()
        var doneBytes = 0
        var decoded = 0                                                             // 실제로 디코딩한 조각 수
        var firstNs = 0L                                                            // 첫 글자가 나온 시각
        var feedNs = 0L                                                             // 공급 시간 합
        var tailNs = 0L                                                             // 공급 끝 → 최종 결과 시간 합
        val startNs = System.nanoTime()
        for (c in chunks) {
            if (c.isEmpty()) continue                                               // 빈 조각은 넣지 않는다
            val stream = rec.createStream("")                                       // 조각마다 새 스트림(핫워드 없음)
            try {
                if (lang.isNotEmpty()) stream.setOption("language", lang)
                dbg("chunk ${decoded + 1}/${chunks.size} start: bytes=${c.size} (${"%.2f".format(Locale.US, c.size / BYTES_PER_SEC.toDouble())}s) " +
                    "lang='$lang' feed=${feed.name}/${feed.delayMs}ms")
                val chunkStart = System.nanoTime()                                  // 이 조각 공급 시작(간격 기준점)
                var k = 0                                                           // 넣은 100 ms 조각 번호
                var off = 0
                while (off < c.size) {
                    val due = chunkStart + k * feed.delayMs * 1_000_000L            // 이 조각이 "도착" 해야 할 시각
                    val waitMs = (due - System.nanoTime()) / 1_000_000L
                    if (waitMs > 0) Thread.sleep(waitMs)                            // 일찍 왔으면 기다린다(늦었으면 바로 넣는다)
                    val end = minOf(off + CHUNK_BYTES, c.size)
                    stream.acceptWaveform(VadAudio.toFloat(c.copyOfRange(off, end)), SAMPLE_RATE)
                    while (rec.isReady(stream)) rec.decode(stream)                  // 들어온 만큼 바로 디코딩
                    if (firstNs == 0L && rec.getResult(stream).tokens.isNotEmpty()) firstNs = System.nanoTime()   // 첫 글자
                    doneBytes += end - off
                    off = end
                    k++
                    onFed(doneBytes.toDouble() / totalBytes)
                }
                val fedEnd = System.nanoTime()                                      // 마지막 조각 공급 끝
                feedNs += fedEnd - chunkStart
                stream.acceptWaveform(FloatArray(TAIL_PADDING_SAMPLES), SAMPLE_RATE)   // 0.5 s 무음(마지막 청크 밀어내기)
                stream.inputFinished()
                while (rec.isReady(stream)) rec.decode(stream)
                val t = textFromTokens(rec.getResult(stream).tokens)               // ★ 띄어쓰기를 살린 글자
                tailNs += System.nanoTime() - fedEnd
                dbg("chunk ${decoded + 1}/${chunks.size} done: feed=${ms(chunkStart, fedEnd)}ms tail=${ms(fedEnd, System.nanoTime())}ms chars=${t.length}")
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')                             // 조각 결과는 공백 하나로 잇는다
                    sb.append(t)
                }
            } finally {
                stream.release()                                                    // 네이티브 메모리 즉시 해제
            }
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
     * 목적: 인식기를 해제한다(배치 끝, finally 에서 부른다). 여러 번 불러도 안전하다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    override fun close() {
        dbg("close: recognizer=${if (recognizer != null) "loaded" else "null"}")
        recognizer?.release()
        recognizer = null
        family = null                                                               // add-hyungchul-20261011-0150
    }

    companion object {
        private const val TAG = "GoogleStt"                                         // MainActivity / BatchRunner 의 logTag 와 같은 값
        private const val SAMPLE_RATE = 16_000
        /** 꼬리 무음 0.5 s — sherpa 공식 Kotlin 스트리밍 예제의 tailPaddings 와 같은 길이(실측: 0~1 s 에서 결과 동일) */
        private const val TAIL_PADDING_SAMPLES = SAMPLE_RATE / 2

        /**
         * 목적: Logcat 전용 디버그 한 줄(사용자 결정 Q22·Q23 — 태그 GoogleStt, 접두어 "[D][sherpa-stream]").
         * 입력: msg / 출력: Logcat / 리턴: 없음
         */
        private fun dbg(msg: String) {
            Log.d(TAG, "[D][sherpa-stream] $msg")
        }

        /**
         * 목적: 결과 토큰을 이어 붙여 글자를 만든다(sherpa 의 text 가 한글 띄어쓰기를 지우는 문제를 피한다).
         * 입력: tokens — OnlineRecognizerResult.tokens(단어 시작 토큰은 앞에 공백이 붙어 있다)
         * 리턴: 공백을 하나로 줄이고 앞뒤를 자른 문자열
         */
        fun textFromTokens(tokens: Array<String>): String =
            tokens.joinToString("").replace(WHITESPACE, " ").trim()

        private val WHITESPACE = Regex("\\s+")                                      // 연속 공백(두 칸 이상 포함)

        /**
         * add-hyungchul-20261011-0150
         * 목적: 스트림에 넣을 언어 옵션 값. 입력: family — 모델 계열(null 이면 불러오기 전), locale
         * 리턴: Nemotron 이면 nemotronLanguage, 그 밖(Zipformer KO — 한국어 전용)은 ""(옵션을 넣지 않는다)
         */
        fun streamLanguage(family: SherpaFamily?, locale: Locale): String =
            if (family == SherpaFamily.NEMOTRON_STREAMING) nemotronLanguage(locale) else ""

        /** 목적: Nemotron 스트림 언어 값. 입력: locale / 리턴: "ko" / "en" … (패키지 README 의 "en", "ja" 형식) */
        fun nemotronLanguage(locale: Locale): String = locale.language
    }
}
