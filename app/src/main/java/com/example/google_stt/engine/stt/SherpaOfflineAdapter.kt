/*
 * 파일명: engine/stt/SherpaOfflineAdapter.kt
 * 목적 및 기능:
 * - sherpa-onnx(정적 링크 AAR 1.13.8) 오프라인 인식기로 PCM 조각을 전사한다.
 *   대상 계열: Whisper / Qwen3-ASR / Omnilingual / Zipformer(KO·EN) / SenseVoice / Parakeet-TDT / Moonshine v1.
 * - v3 의 stt/SherpaOfflineTranscriber.kt 를 옮긴 것이다. 디코딩 본문(설정 값·조각마다 새 스트림·결과 글자 만들기)은 그대로이고 바뀐 점은:
 *     · 모델 폴더를 인자로 받는다(공용 Documents/stt_models + '모든 파일 접근' → 앱 전용 저장소의 자산 폴더)
 *     · 모델 정의는 domain 의 ModelSpec(계열 이름은 ModelFamily 상수). 언어는 Language
 *     · suspend 함수이고 조각마다 취소를 확인한다. 결과는 domain 의 SttOutcome
 *     · 폴더 점검·해시 요약 등 화면·run_meta 용 도우미는 옮기지 않았다(자산 상태는 AssetRepository, 해시는 준비 표시가 맡는다)
 * - 계측(사용자 결정 2026-10-10 "오프라인 = 최대 속도, 4 스레드"): 실시간 공급을 하지 않는다(공급 시간 없음).
 *   꼬리 지연 = 첫 조각 디코딩 시작 → 마지막 조각 결과(입력이 처음부터 다 있으므로 전체 처리 시간과 같다).
 *
 * 공식 API 근거(sherpa-onnx v1.13.8 kotlin-api/OfflineRecognizer.kt):
 *   OfflineRecognizer(assetManager = null, config) — assetManager 가 null 이면 파일 경로로 읽는다
 *   OfflineStream.acceptWaveform(samples, sampleRate) / setOption(key, value), OfflineRecognizer.decode(stream) / getResult(stream)
 *
 * add-hyungchul-20261106-2135 — [T5-2]
 */
package com.example.google_stt.engine.stt

import android.util.Log                                                  // Logcat 디버그 출력
import com.example.google_stt.domain.AppError                            // E-EN-01
import com.example.google_stt.domain.catalog.FileRole                    // 파일 역할 이름
import com.example.google_stt.domain.catalog.LangSupport                 // 한국어 전용 판정
import com.example.google_stt.domain.catalog.Language                    // 평가 언어
import com.example.google_stt.domain.catalog.ModelFamily                 // 계열 이름
import com.example.google_stt.domain.catalog.ModelSpec                   // 모델 정의
import com.example.google_stt.domain.condition.FeedChoice                // 공급 속도(쓰지 않는다)
import com.example.google_stt.domain.csv.SttOutcome                      // 전사 결과
import com.example.google_stt.engine.audio.Pcm16                         // PCM16 → float
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers                                    // 계산 스레드
import kotlinx.coroutines.ensureActive                                   // 취소 확인
import kotlinx.coroutines.withContext                                    // 스레드 바꾸기

/**
 * 목적: sherpa-onnx 오프라인 어댑터(작업 1개에 1개).
 * 입력(생성자): spec - 모델 정의(Runtime.SHERPA_OFFLINE)
 */
class SherpaOfflineAdapter(override val spec: ModelSpec) : SttEngineAdapter {

    private var recognizer: OfflineRecognizer? = null     // 불러 둔 인식기(load 전에는 null)

    /**
     * 목적: 모델 파일을 확인하고 인식기를 만든다(작업 시작 때 1회).
     * 입력: modelDir - 자산 폴더, lang - 평가 언어(Whisper·SenseVoice 설정에 쓴다)
     * 출력: 인식기가 메모리에 올라간다
     * 리턴: LoadInfo
     * 예외: AppError.ModelLoad(E-EN-01) - 파일이 없거나 인식기를 만들 수 없을 때
     */
    override suspend fun load(modelDir: File?, lang: Language): LoadInfo = withContext(Dispatchers.Default) {
        val dir = requireModelDir(spec, modelDir)                                   // 빠진 파일이 있으면 여기서 멈춘다
        close()                                                                     // add-hyungchul-20261106-2215 : 다시 불러도 앞서 올린 것을 놓고 시작한다(네이티브 메모리 누수 방지 — 독립 검토)

        // 역할 이름 → 절대 경로
        fun path(role: String): String = File(dir, spec.files.getValue(role)).absolutePath

        val modelConfig = when (spec.family) {
            ModelFamily.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(FileRole.ENCODER),
                    decoder = path(FileRole.DECODER),
                    language = lang.code,                                           // "ko" / "en"
                    task = "transcribe",                                            // 번역이 아니라 전사
                ),
                tokens = path(FileRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
                modelType = "whisper",                                              // 공식 예제(getOfflineModelConfig)와 같은 값
            )

            ModelFamily.QWEN3ASR -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = path(FileRole.CONV_FRONTEND),
                    encoder = path(FileRole.ENCODER),
                    decoder = path(FileRole.DECODER),
                    tokenizer = path(FileRole.TOKENIZER),                           // 토크나이저 폴더
                    maxTotalLen = QWEN3_MAX_TOTAL_LEN,                              // 기본 512 → 30초 조각에서 출력이 잘리지 않게 늘린다
                    maxNewTokens = QWEN3_MAX_NEW_TOKENS,                            // PC qwen3_asr_stt.py --max-new-tokens 1024 와 같게
                ),
                tokens = "",                                                        // 공식 예제와 같다(토크나이저 폴더를 쓴다)
                numThreads = NUM_THREADS,
                provider = "cpu",
            )

            ModelFamily.OMNILINGUAL -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = path(FileRole.MODEL)),
                tokens = path(FileRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
            )

            // Zipformer 오프라인 transducer — 한국어·영어 전용 모델 모두 같은 설정(언어 지정 없음)
            ModelFamily.ZIPFORMER -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(FileRole.ENCODER),
                    decoder = path(FileRole.DECODER),
                    joiner = path(FileRole.JOINER),
                ),
                tokens = path(FileRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
            )

            // SenseVoice Small(언어 ko/en, ITN 켬 — PC sensevoice.py 기본과 같게)
            ModelFamily.SENSEVOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = path(FileRole.MODEL),
                    language = lang.code,                                           // "ko" / "en"(둘 다 SenseVoice 가 지원하는 값)
                    useInverseTextNormalization = true,                             // 문장부호·숫자 복원
                ),
                tokens = path(FileRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
            )

            // Parakeet-TDT 0.6B v2(NeMo transducer — 공식 Kotlin 예제 type 30 과 같이 modelType "nemo_transducer")
            ModelFamily.PARAKEET -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = path(FileRole.ENCODER),
                    decoder = path(FileRole.DECODER),
                    joiner = path(FileRole.JOINER),
                ),
                tokens = path(FileRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
                modelType = "nemo_transducer",                                      // NeMo TDT 디코딩(공식 예제와 같은 값)
            )

            // Moonshine v1 영어 tiny / base(공식 Kotlin 예제 type 21·22 와 같은 4개 파일, 언어 지정 없음)
            ModelFamily.MOONSHINE -> OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = path(FileRole.PREPROCESSOR),                    // preprocess.onnx
                    encoder = path(FileRole.ENCODER),                               // encode.int8.onnx
                    uncachedDecoder = path(FileRole.UNCACHED_DECODER),              // uncached_decode.int8.onnx
                    cachedDecoder = path(FileRole.CACHED_DECODER),                  // cached_decode.int8.onnx
                ),
                tokens = path(FileRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
            )

            else -> throw AppError.ModelLoad("${spec.displayName} 은 오프라인 sherpa 모델이 아니다(계열 ${spec.family})")   // 팩토리 오류
        }

        // 네이티브에 넘기는 경로·설정을 불러오기 직전에 남긴다(여기서 앱이 죽으면 마지막 줄이 이것이 된다)
        dbg("load start: family=${spec.family} threads=$NUM_THREADS provider=cpu lang=${lang.code} " +
            spec.files.keys.joinToString(" ") { "$it=${path(it)}" })                // 역할=절대 경로 목록
        val t0 = System.nanoTime()                                                  // 불러오기 시작
        recognizer = try {
            OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))   // assetManager = null → 파일 경로
        } catch (e: RuntimeException) {
            throw AppError.ModelLoad("${spec.displayName}: 인식기를 만들 수 없다 — ${e.message}", e)
        }
        val loadMs = ms(t0, System.nanoTime())
        dbg("load done: ${loadMs}ms (${spec.assetId})")
        LoadInfo(loadMs / 1000.0, RUNTIME)
    }

    /**
     * 목적: PCM 조각들을 차례로 전사하고 결과를 잇는다.
     * 입력: chunks - 조각들(각각 spec.maxChunkSec 이하), feed - 쓰지 않는다(오프라인은 받자마자 디코딩), lang - 평가 언어
     * 출력: 없음
     * 리턴: SttOutcome(전사문, 디코딩한 조각 수, 첫 조각 결과 시각, 꼬리 지연 = 전체 처리 시간, 공급 시간 없음)
     */
    override suspend fun transcribe(chunks: List<AudioChunk>, feed: FeedChoice, lang: Language): SttOutcome = withContext(Dispatchers.Default) {
        val rec = recognizer ?: throw IllegalStateException("sherpa 모델을 먼저 불러야 한다(load).")
        val sb = StringBuilder()
        var decoded = 0                                                             // 실제로 디코딩한 조각 수
        var firstNs = 0L
        val startNs = System.nanoTime()
        for (chunk in chunks) {
            ensureActive()                                                          // 조각마다 취소 확인(조각 하나의 디코딩은 끊을 수 없다)
            val c = chunk.pcm
            if (c.isEmpty()) continue                                               // 빈 조각은 넣지 않는다
            val samples = Pcm16.toFloat(c)                                          // PCM16 → float(-1~1)
            // 조각 시작(번호 = 지금까지 디코딩한 수 + 1). 네이티브에서 죽으면 이 줄이 마지막이 된다
            dbg("chunk ${decoded + 1}/${chunks.size} start: samples=${samples.size} " +
                "(${"%.2f".format(Locale.US, samples.size / Pcm16.SAMPLE_RATE.toDouble())}s)")
            val stream = rec.createStream()                                         // 조각마다 새 스트림(앞 조각 문맥 없음)
            try {
                if (spec.family == ModelFamily.QWEN3ASR) {
                    stream.setOption("language", qwen3Language(lang))               // "Korean" / "English"
                }
                val c0 = System.nanoTime()                                          // 조각 1개 디코딩 시작(디버그 표시용)
                stream.acceptWaveform(samples, Pcm16.SAMPLE_RATE)
                rec.decode(stream)
                val res = rec.getResult(stream)                                     // 결과 1번만 가져온다
                val t = resultText(res.text, res.tokens)                            // 계열별 글자 만들기
                // 조각 끝 — 디코딩 시간과 글자 수(빈 결과면 chars=0 으로 바로 보인다)
                dbg("chunk ${decoded + 1}/${chunks.size} done: decode=${ms(c0, System.nanoTime())}ms chars=${t.length}")
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')                             // 조각 결과는 공백 하나로 잇는다
                    sb.append(t)
                }
            } finally {
                stream.release()                                                    // 네이티브 메모리 즉시 해제
            }
            decoded++
            if (firstNs == 0L) firstNs = System.nanoTime()                          // 첫 조각 결과 시각
        }
        val wall = ms(startNs, System.nanoTime())
        val first = if (firstNs != 0L) ms(startNs, firstNs) else -1
        // 파일 1건 요약(디코딩한 조각 수 / 첫 결과 / 전체 시간 / 글자 수)
        dbg("transcribe done: decoded=$decoded/${chunks.size} first=${first}ms wall=${wall}ms chars=${sb.length}")
        sttOutcome(sb.toString(), decoded, first, tailMs = wall, feedMs = null)     // 실시간 공급 없음 → 꼬리 = 전체 처리 시간
    }

    /**
     * 목적: 인식기를 해제한다(작업 끝, finally 에서 부른다). 여러 번 불러도 안전하다.
     * 입력: 없음 / 출력: 네이티브 메모리가 풀린다 / 리턴: 없음
     */
    override fun close() {
        dbg("close: recognizer=${if (recognizer != null) "loaded" else "null"}")    // 해제 시점 확인
        recognizer?.release()
        recognizer = null
    }

    /**
     * 목적: 오프라인 결과에서 저장할 글자를 만든다.
     * 입력: text - OfflineRecognizerResult.text, tokens - OfflineRecognizerResult.tokens
     * 출력: 없음
     * 리턴: Zipformer 한국어 = 토큰 이어 붙이기("▁" → 공백 — text 는 한글 띄어쓰기가 없다, 실측) / 그 밖 = text.trim()
     */
    private fun resultText(text: String, tokens: Array<String>): String =
        if (spec.family == ModelFamily.ZIPFORMER && spec.langSupport == LangSupport.KO_ONLY) {
            textFromTokens(tokens.map { it.replace('▁', ' ') }.toTypedArray())
        } else {
            text.trim()
        }

    companion object {
        /** 디코딩 스레드 수 — PC sherpa_onnx_stt.py --num-threads 기본값과 같다(사용자 결정 2026-10-10) */
        const val NUM_THREADS = 4
        /** run_meta·CSV 에 남기는 런타임 이름(app/build.gradle.kts 의 AAR 판과 같게 유지) */
        const val RUNTIME = "sherpa-onnx 1.13.8 (static-link onnxruntime)"
        /** Qwen3-ASR 최대 문맥 길이(오디오 토큰 + 프롬프트 + 출력). 공식 문서의 긴 음원 예제 값 */
        const val QWEN3_MAX_TOTAL_LEN = 1024
        /** Qwen3-ASR 최대 출력 토큰 */
        const val QWEN3_MAX_NEW_TOKENS = 1024
        /** Logcat 태그(앱 로그와 같은 값) */
        private const val TAG = "GoogleStt"

        private val WHITESPACE = Regex("\\s+")                                      // 연속 공백(두 칸 이상 포함)

        /**
         * 목적: Logcat 전용 디버그 한 줄을 남긴다(화면 로그에는 넣지 않는다).
         * 입력: msg - 내용 / 출력: Logcat "GoogleStt" 태그, "[D][sherpa] " 접두어 / 리턴: 없음
         */
        private fun dbg(msg: String) {
            Log.d(TAG, "[D][sherpa] $msg")                                          // 디버그 수준으로 출력
        }

        /**
         * 목적: 결과 토큰을 이어 붙여 글자를 만든다(sherpa 의 text 가 한글 띄어쓰기를 지우는 문제를 피한다 — 스트리밍 어댑터도 쓴다).
         * 입력: tokens - 결과 토큰(단어 시작 토큰은 앞에 공백이 붙어 있다)
         * 출력: 없음
         * 리턴: 공백을 하나로 줄이고 앞뒤를 자른 문자열
         */
        fun textFromTokens(tokens: Array<String>): String =
            tokens.joinToString("").replace(WHITESPACE, " ").trim()

        /**
         * 목적: Qwen3-ASR 언어 이름(Qwen 공식 SUPPORTED_LANGUAGES 표기 — PC qwen3_asr_stt.py 와 같다).
         * 입력: lang - 평가 언어 / 출력: 없음 / 리턴: "Korean" / "English"
         */
        fun qwen3Language(lang: Language): String = when (lang) {
            Language.KO -> "Korean"
            Language.EN -> "English"
        }
    }
}
