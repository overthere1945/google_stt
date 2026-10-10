/*
 * 파일명: stt/SherpaOfflineTranscriber.kt
 * 목적 및 기능:
 * - sherpa-onnx(정적 링크 AAR 1.13.7) 오프라인 인식기로 PCM 조각을 전사한다(2단계 2-1: Whisper / Qwen3-ASR / Omnilingual).
 * - 모델은 배치 시작 때 1번만 불러(load) 배치 끝에 해제한다(close) — 불러오는 시간이 파일별 계측에 섞이지 않게.
 * - 조각마다 OfflineStream 을 새로 만들어 acceptWaveform → decode → getResult 하고, 결과를 공백 하나로 잇는다.
 * - 계측(사용자 결정 2026-10-10 "오프라인 = 최대 속도, 4 스레드"):
 *     · 실시간 공급을 하지 않는다(feed_ms = -1). 조각을 받자마자 디코딩한다.
 *     · stt_wall_ms = 첫 조각 디코딩 시작 → 마지막 조각 결과. 입력이 처음부터 다 있으므로 tail_ms 도 같은 값이다.
 *     · segments = 실제로 디코딩한 조각 수.
 * - 모델 위치: getExternalFilesDir(null)/models/<SherpaModelSpec.dirName>/
 *   = /sdcard/Android/data/com.example.google_stt/files/models/…  (tools/download_stt_models.sh 가 adb 로 넣는다)
 *
 * 공식 API 근거(sherpa-onnx v1.13.7 kotlin-api):
 *   OfflineRecognizer(assetManager: AssetManager? = null, config: OfflineRecognizerConfig) — assetManager 가 null 이면 파일 경로로 읽는다
 *   OfflineModelConfig(whisper / qwen3Asr / omnilingual / tokens / numThreads / provider / modelType …)
 *   OfflineStream.acceptWaveform(samples, sampleRate) / setOption(key, value), OfflineRecognizer.decode(stream) / getResult(stream).text
 *   Qwen3-ASR 언어: 스트림 옵션 "language" (offline-recognizer-qwen3-asr-impl.cc: stream->GetOption("language"))
 *
 * add-hyungchul-20261010-1800
 */
package com.example.google_stt.stt

import android.content.Context
import com.example.google_stt.vad.VadAudio
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * 목적: sherpa-onnx 오프라인 전사기(배치 1회에 1개).
 * 입력(생성자): context — 모델 폴더(getExternalFilesDir) 위치를 얻는 데 쓴다
 */
class SherpaOfflineTranscriber(private val context: Context) : AutoCloseable {

    private var recognizer: OfflineRecognizer? = null     // 불러 둔 인식기(load 전에는 null)
    private var spec: SherpaModelSpec? = null             // 불러 둔 모델 정의

    /** 모델을 불러오는 데 걸린 시간(ms). load 전에는 -1 */
    var loadMs: Long = -1
        private set

    /**
     * 목적: 모델 파일을 확인하고 인식기를 만든다(배치 시작 때 1회).
     * 입력: spec — 모델 정의, locale — 인식 언어(Whisper 설정에 쓴다)
     * 출력: 없음
     * 리턴: 없음
     * 예외: 파일이 없으면 IOException(받는 방법 포함). 없는 파일로 네이티브를 부르면 앱이 통째로 죽으므로 먼저 확인한다.
     */
    fun load(spec: SherpaModelSpec, locale: Locale) {
        val missing = missingFiles(context, spec)                                   // 빠진 파일 목록
        if (missing.isNotEmpty()) throw IOException(missingMessage(context, spec, missing))
        val dir = modelDir(context, spec)                                           // 모델 폴더

        // 역할 이름 → 절대 경로
        fun path(role: String): String = File(dir, spec.files.getValue(role)).absolutePath

        val modelConfig = when (spec.family) {
            SherpaFamily.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(SherpaRole.ENCODER),
                    decoder = path(SherpaRole.DECODER),
                    language = whisperLanguage(locale),                             // "ko" / "en"
                    task = "transcribe",                                            // 번역이 아니라 전사
                ),
                tokens = path(SherpaRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
                modelType = "whisper",                                              // 공식 예제(getOfflineModelConfig)와 같은 값
            )

            SherpaFamily.QWEN3_ASR -> OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = path(SherpaRole.CONV_FRONTEND),
                    encoder = path(SherpaRole.ENCODER),
                    decoder = path(SherpaRole.DECODER),
                    tokenizer = path(SherpaRole.TOKENIZER),                         // 토크나이저 폴더
                    maxTotalLen = QWEN3_MAX_TOTAL_LEN,                              // 기본 512 → 30초 조각에서 출력이 잘리지 않게 늘린다
                    maxNewTokens = QWEN3_MAX_NEW_TOKENS,                            // PC qwen3_asr_stt.py --max-new-tokens 1024 와 같게
                ),
                tokens = "",                                                        // 공식 예제와 같다(토크나이저 폴더를 쓴다)
                numThreads = NUM_THREADS,
                provider = "cpu",
            )

            SherpaFamily.OMNILINGUAL -> OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(model = path(SherpaRole.MODEL)),
                tokens = path(SherpaRole.TOKENS),
                numThreads = NUM_THREADS,
                provider = "cpu",
            )
        }

        val t0 = System.nanoTime()                                                  // 불러오기 시작
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))   // assetManager = null → 파일 경로
        loadMs = ms(t0, System.nanoTime())
        this.spec = spec
    }

    /**
     * 목적: PCM 조각들을 차례로 전사하고 결과를 잇는다.
     * 입력: chunks — 16 kHz/mono/PCM16 LE 조각(각각 spec.maxChunkSec 이하), locale — 인식 언어,
     *       onFed — 진행률(0.0~1.0, 조각 단위)
     * 리턴: SttOutcome(text, first_result_ms, tail_ms = stt_wall_ms, segments)
     */
    fun transcribe(chunks: List<ByteArray>, locale: Locale, onFed: (Double) -> Unit): SttOutcome {
        val rec = recognizer ?: throw IllegalStateException("sherpa 모델을 먼저 불러야 합니다(load).")
        val s = spec ?: throw IllegalStateException("sherpa 모델 정의가 없습니다.")
        val totalBytes = chunks.sumOf { it.size }.coerceAtLeast(1)                  // 진행률 분모
        val sb = StringBuilder()
        var doneBytes = 0
        var decoded = 0                                                             // 실제로 디코딩한 조각 수
        var firstNs = 0L
        val startNs = System.nanoTime()
        for (c in chunks) {
            if (c.isEmpty()) continue                                               // 빈 조각은 넣지 않는다
            val samples = VadAudio.toFloat(c)                                       // PCM16 → float(-1~1)
            val stream = rec.createStream()                                         // 조각마다 새 스트림(앞 조각 문맥 없음)
            try {
                if (s.family == SherpaFamily.QWEN3_ASR) {
                    val lang = qwen3Language(locale)
                    if (lang.isNotEmpty()) stream.setOption("language", lang)       // "Korean" / "English"
                }
                stream.acceptWaveform(samples, SAMPLE_RATE)
                rec.decode(stream)
                val t = rec.getResult(stream).text.trim()
                if (t.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')                             // 조각 결과는 공백 하나로 잇는다
                    sb.append(t)
                }
            } finally {
                stream.release()                                                    // 네이티브 메모리 즉시 해제
            }
            decoded++
            if (firstNs == 0L) firstNs = System.nanoTime()                          // 첫 조각 결과 시각
            doneBytes += c.size
            onFed(doneBytes.toDouble() / totalBytes)
        }
        val endNs = System.nanoTime()
        val wall = ms(startNs, endNs)
        return SttOutcome(
            text = sb.toString().trim(),
            firstResultMs = if (firstNs != 0L) ms(startNs, firstNs) else -1,
            feedMs = -1,                                                            // 실시간 공급 없음
            tailMs = wall,                                                          // 입력이 처음부터 다 있으므로 = 전체 처리 시간
            sttWallMs = wall,
            segments = decoded,
        )
    }

    /**
     * 목적: 인식기를 해제한다(배치 끝, finally 에서 부른다). 여러 번 불러도 안전하다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    override fun close() {
        recognizer?.release()
        recognizer = null
        spec = null
    }

    companion object {
        /** 디코딩 스레드 수 — PC sherpa_onnx_stt.py --num-threads 기본값과 같다(사용자 결정 2026-10-10) */
        const val NUM_THREADS = 4
        /** run_meta·CSV 에 남기는 런타임 이름(app/build.gradle.kts 의 AAR 판과 같게 유지) */
        const val RUNTIME = "sherpa-onnx 1.13.7 (static-link onnxruntime)"
        /** Qwen3-ASR 최대 문맥 길이(오디오 토큰 + 프롬프트 + 출력). 공식 문서의 긴 음원 예제 값 */
        const val QWEN3_MAX_TOTAL_LEN = 1024
        /** Qwen3-ASR 최대 출력 토큰 */
        const val QWEN3_MAX_NEW_TOKENS = 1024
        /** 휴대폰 모델 폴더 아래에 둘 해시 목록 파일(PC 스크립트가 만든다) */
        const val SHA256_FILE = "SHA256SUMS.txt"
        private const val SAMPLE_RATE = 16_000

        /**
         * 목적: 앱 전용 모델 폴더(…/files/models).
         * 입력: context / 리턴: File
         */
        fun modelsRoot(context: Context): File =
            File(context.getExternalFilesDir(null) ?: context.filesDir, "models")

        /** 목적: 모델 1개의 폴더. 입력: context, spec / 리턴: File */
        fun modelDir(context: Context, spec: SherpaModelSpec): File = File(modelsRoot(context), spec.dirName)

        /**
         * 목적: 빠진 모델 파일(또는 폴더)을 찾는다.
         * 입력: context, spec / 리턴: 빠진 상대 경로 목록(없으면 빈 목록)
         */
        fun missingFiles(context: Context, spec: SherpaModelSpec): List<String> {
            val dir = modelDir(context, spec)
            return spec.files.values.filter { !File(dir, it).exists() }
        }

        /**
         * 목적: 파일이 없을 때 화면에 보여 줄 안내문.
         * 입력: context, spec, missing / 리턴: 문자열
         */
        fun missingMessage(context: Context, spec: SherpaModelSpec, missing: List<String>): String =
            "${spec.displayName} 모델 파일이 없습니다: ${missing.joinToString(", ")} " +
                "(위치: ${modelDir(context, spec).absolutePath}). " +
                "PC 에서 bash tools/download_stt_models.sh 로 받아 adb 로 넣으세요."

        /**
         * 목적: run_meta 용 파일 목록 요약 "이름=바이트;…" (폴더는 안의 파일 크기 합).
         * 입력: context, spec / 리턴: 문자열
         */
        fun filesSummary(context: Context, spec: SherpaModelSpec): String {
            val dir = modelDir(context, spec)
            return spec.files.values.joinToString(";") { rel ->
                val f = File(dir, rel)
                val size = if (f.isDirectory) f.walkTopDown().filter { it.isFile }.sumOf { it.length() } else f.length()
                "$rel=$size"
            }
        }

        /**
         * 목적: PC 스크립트가 넣어 둔 SHA256SUMS.txt 를 한 줄로 읽는다(휴대폰에서 수백 MB 를 다시 해시하지 않기 위함).
         * 입력: context, spec / 리턴: "해시 이름;…" 또는 빈 문자열(파일 없음)
         */
        fun sha256Summary(context: Context, spec: SherpaModelSpec): String {
            val f = File(modelDir(context, spec), SHA256_FILE)
            if (!f.isFile) return ""
            return f.readLines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(";")
        }

        /** 목적: Whisper 언어 코드. 입력: locale / 리턴: "ko" / "en" … */
        fun whisperLanguage(locale: Locale): String = locale.language

        /**
         * 목적: Qwen3-ASR 언어 이름(Qwen 공식 SUPPORTED_LANGUAGES 표기 — PC qwen3_asr_stt.py 와 같다).
         * 입력: locale / 리턴: "Korean" / "English" / ""(자동 판별)
         */
        fun qwen3Language(locale: Locale): String = when (locale.language) {
            "ko" -> "Korean"
            "en" -> "English"
            else -> ""
        }

        /**
         * 목적: 화면·CSV 에 남길 언어 설정 표기.
         * 입력: spec, locale / 리턴: 예) "whisper language=ko", "qwen3 language=Korean", "omnilingual (언어 지정 없음)"
         */
        fun languageLabel(spec: SherpaModelSpec, locale: Locale): String = when (spec.family) {
            SherpaFamily.WHISPER -> "whisper language=${whisperLanguage(locale)}"
            SherpaFamily.QWEN3_ASR -> "qwen3 language=${qwen3Language(locale).ifEmpty { "auto" }}"
            SherpaFamily.OMNILINGUAL -> "omnilingual (언어 지정 없음)"
        }
    }
}
