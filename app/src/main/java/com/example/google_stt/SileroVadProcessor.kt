/*
 * 파일명: SileroVadProcessor.kt
 * 목적 및 기능:
 * - Android 앱에서 Silero VAD 16 kHz ONNX 모델을 ONNX Runtime 으로 실행한다.
 * - 입력은 AudioDecoder 가 만든 16 kHz / mono / PCM16 little-endian ByteArray 다.
 * - Silero 공식 규격인 512 sample(32 ms) frame, 64 sample context, recurrent state(2 x 1 x 128) 를 그대로 쓴다.
 * - Silero 공식 get_speech_timestamps() 의 threshold / min speech / min silence / padding 의미를 그대로 옮겨
 *   speech 구간을 계산하고, 그 구간의 PCM 만 이어 붙여 하나의 ByteArray 로 돌려준다.
 *
 * 공식 근거(2026-08-25 확인):
 * - https://github.com/snakers4/silero-vad  (MIT License)
 * - src/silero_vad/utils_vad.py  → OnnxWrapper.__call__ / get_speech_timestamps()
 *     · ort_inputs = {'input': x, 'state': self._state, 'sr': np.array(sr, dtype='int64')}
 *     · self._state = torch.zeros((2, batch_size, 128))
 *     · context_size = 64 (16 kHz), num_samples = 512 (16 kHz)
 *     · self._context = x[..., -context_size:]
 *     · neg_threshold 기본값 = max(threshold - 0.15, 0.01)
 *     · min_silence_samples_at_max_speech = sampling_rate * 98 / 1000
 * - src/silero_vad/model.py → opset 15 파일명은 silero_vad_16k_op15.onnx (16 kHz 전용)
 *
 * ※ 모델 파일은 라이선스(MIT) 원본을 그대로 받아 app/src/main/assets/ 에 넣어야 한다.
 *    받는 방법은 app/src/main/assets/README_SILERO_MODEL.md 와 tools/download_models.sh 참고.
 *
 * add-hyungchul-20260825-1430
 *
 * change-hyungchul-20261009-1530 — VAD 엔진 선택 구조(vad/ 패키지) 도입
 *   · VadProfile(Default/Safe/Custom) 와 VadPresets 를 지웠다. 프리셋은 vad/VadModels.kt 의
 *     VadPresetCatalog(SILERO 8종 + SILERO_CUSTOM)로 옮겼다.
 *   · SileroVadProcessor 는 공통 계약 VadDetector 를 구현한다(detect = 확률 계산 + 구간 계산).
 *   · 구간 상태 기계(collectSpeechSegments)는 vad/FrameSegmenter.collect() 로, 구간 이어 붙이기(collectPcm)는
 *     vad/VadPipeline.kt 의 VadPcmJoiner.join() 으로 내용 그대로 옮겼다(WebRTC/TEN/다른 VAD 도 같이 쓴다).
 *   · 확률 계산(inferProbabilities)과 ONNX 세션 관리는 그대로다 → 같은 설정이면 결과가 이전과 같다.
 */
package com.example.google_stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.google_stt.vad.FrameSegmenter   // change-hyungchul-20261009-1530
import com.example.google_stt.vad.VadAudio   // change-hyungchul-20261009-1530
import com.example.google_stt.vad.VadDetector   // change-hyungchul-20261009-1530
import com.example.google_stt.vad.VadEngine   // change-hyungchul-20261009-1530
import com.example.google_stt.vad.VadKeys   // change-hyungchul-20261009-1530
import com.example.google_stt.vad.VadSetting   // change-hyungchul-20261009-1530
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt   // change-hyungchul-20261009-1530

// change-hyungchul-20261009-1530 : VadProfile(Default/Safe/Custom) 제거 → vad/VadModels.kt 의 VadPresetCatalog 로 대체

/**
 * 목적: Silero get_speech_timestamps() 에 넘길 파라미터 묶음.
 * 비고: maxSpeechDurationSec 이 null 이면 Unlimited(공식 기본값 float('inf')) 를 뜻한다.
 */
data class VadConfig(
    val threshold: Float,               // 이 값 이상이면 speech 로 본다
    val minSpeechDurationMs: Int,       // 이보다 짧은 speech 구간은 버린다
    val minSilenceDurationMs: Int,      // 이보다 짧은 무음은 구간을 끊지 않는다
    val speechPadMs: Int,               // 구간 앞뒤로 붙여줄 여유 (앞말/뒷말 잘림 방지)
    val maxSpeechDurationSec: Double?,  // null = Unlimited
    // add-hyungchul-20260914-2130 : 구간 사이 무음(ms)
    //   PC GUI 의 --vad-join-silence-ms 와 같은 뜻이다(GUI 기본값 200).
    //   speech 구간만 그대로 이어 붙이면 문장 경계가 사라져서 인식기가 한 덩어리로 붙여 읽는다.
    //   구간 사이에 이 길이만큼 digital silence 를 끼워 넣어 문장 경계를 남긴다.
    //   0 이면 예전 동작(무음 삽입 없음)과 완전히 같다.
    val joinSilenceMs: Int = 200,
) {
    /** Silero 공식 기본 동작: neg_threshold = max(threshold - 0.15, 0.01) */
    val negThreshold: Float
        get() = max(threshold - 0.15f, 0.01f)

    // change-hyungchul-20261009-1530
    companion object {
        /**
         * 목적: 화면에서 고른 VAD 설정(VadSetting) 값을 Silero 파라미터로 바꾼다(init 의 범위 검증을 그대로 탄다).
         * 입력: s — Silero 방식 키(threshold / min_speech_ms / min_silence_ms / pad_ms / max_speech_sec / join_ms)를 가진 설정
         *       thresholdOverride — threshold 항목이 없는 엔진(WebRTC)이 넘기는 고정 임계값
         * 리턴: VadConfig
         * 비고: max_speech_sec 0 이하 = Unlimited(null) — 기존 화면 규칙과 같다.
         */
        fun fromSetting(s: VadSetting, thresholdOverride: Float? = null): VadConfig {
            val maxSpeech = s[VadKeys.MAX_SPEECH_SEC]                     // 0 이하면 무제한
            return VadConfig(
                threshold = thresholdOverride ?: s[VadKeys.THRESHOLD].toFloat(),
                minSpeechDurationMs = s[VadKeys.MIN_SPEECH_MS].roundToInt(),
                minSilenceDurationMs = s[VadKeys.MIN_SILENCE_MS].roundToInt(),
                speechPadMs = s[VadKeys.PAD_MS].roundToInt(),
                maxSpeechDurationSec = if (maxSpeech <= 0.0) null else maxSpeech,
                joinSilenceMs = s[VadKeys.JOIN_MS].roundToInt(),
            )
        }
    }

    init {
        // UI 입력값을 여기서 한 번 더 검증한다(잘못된 값으로 배치가 돌면 결과 전체를 버려야 하므로).
        require(threshold in 0.01f..0.99f) { "Threshold 는 0.01 ~ 0.99 범위여야 합니다." }
        require(minSpeechDurationMs >= 0) { "Min Speech 는 0 이상이어야 합니다." }
        require(minSilenceDurationMs >= 0) { "Min Silence 는 0 이상이어야 합니다." }
        require(speechPadMs >= 0) { "Speech Padding 은 0 이상이어야 합니다." }
        // add-hyungchul-20260914-2130 : 구간 사이 무음 검증 (0 ~ 5000 ms)
        require(joinSilenceMs in 0..5_000) { "구간 사이 무음은 0 ~ 5000 ms 여야 합니다." }
        require(maxSpeechDurationSec == null || maxSpeechDurationSec > 0.0) {
            "Max Speech 는 0(Unlimited) 이거나 0보다 커야 합니다."
        }
        if (maxSpeechDurationSec != null) {
            // 공식 식: max_speech_samples = sr*max - window - 2*pad.
            // 이 값이 0 이하가 되면 매 frame 마다 강제 분할이 일어나 의미가 없다. 미리 막는다.
            val minimumUsefulSec =
                SileroVadProcessor.WINDOW_SAMPLES.toDouble() / SileroVadProcessor.SAMPLE_RATE +
                    2.0 * speechPadMs / 1000.0
            require(maxSpeechDurationSec > minimumUsefulSec) {
                String.format(
                    Locale.US,
                    "Max Speech 는 현재 Padding 기준 %.3f 초보다 커야 합니다.",
                    minimumUsefulSec,
                )
            }
        }
    }
}

// change-hyungchul-20261009-1530 : VadPresets(Default/Safe/Custom 값) 제거 → vad/VadModels.kt 의 VadPresetCatalog(SILERO 8종 + Custom)로 대체

/** 원본 PCM 기준 sample 좌표. endSample 은 exclusive(그 sample 은 포함하지 않음). */
data class VadSegment(
    val startSample: Int,
    val endSample: Int,
)

/**
 * 목적: 파일 1건의 VAD 처리 결과를 나른다.
 * 비고: ByteArray 를 담으므로 data class 로 만들지 않는다(equals/hashCode 가 참조 비교라 오해를 부른다).
 */
class VadResult(
    /** speech 구간만 이어 붙인 16 kHz/mono/PCM16 LE */
    val pcm: ByteArray,
    /** padding 까지 적용된 최종 구간 목록 */
    val segments: List<VadSegment>,
    /** VAD 추론 + PCM 재구성에 걸린 시간(ms) */
    val processMs: Long,
    val originalSamples: Int,
    val outputSamples: Int,
    /** padding 적용 전, 순수하게 검출된 speech sample 합 */
    val detectedSpeechSamples: Int,
    // add-hyungchul-20260914-2130 : 구간 사이에 끼워 넣은 무음 sample 합(계측/CSV 용)
    val joinSilenceSamples: Int = 0,
    /**
     * add-hyungchul-20260914-2350
     * Speech EPD 가 쓸 "안전한 절단 후보" 좌표(출력 PCM 기준 sample).
     * 구간 사이 무음 한가운데이므로 여기서 자르면 말이 잘리지 않는다.
     */
    val seams: List<Int> = emptyList(),
) {
    val originalSec: Double get() = originalSamples / SAMPLE_RATE_D
    val outputSec: Double get() = outputSamples / SAMPLE_RATE_D
    val detectedSpeechSec: Double get() = detectedSpeechSamples / SAMPLE_RATE_D
    // add-hyungchul-20260914-2130 : 끼워 넣은 무음 길이(초)
    val joinSilenceSec: Double get() = joinSilenceSamples / SAMPLE_RATE_D
    val removedSec: Double get() = (originalSec - outputSec).coerceAtLeast(0.0)

    /** 제거 비율(%) — 0 이면 아무것도 안 지웠다는 뜻이다. */
    val removedRatio: Double
        get() = if (originalSamples <= 0) 0.0
        else (originalSamples - outputSamples).coerceAtLeast(0) * 100.0 / originalSamples

    private companion object {
        const val SAMPLE_RATE_D = 16_000.0
    }
}

/**
 * 목적: Silero VAD ONNX 실행기.
 * 비고:
 * - OrtSession 은 배치 전체에서 재사용한다(파일마다 새로 만들면 모델 로딩 시간이 계측에 섞인다).
 * - recurrent state 와 context 는 process() 를 시작할 때마다 초기화해 파일 간 상태가 섞이지 않게 한다.
 * - 이 클래스는 thread-safe 하지 않으므로 process() 를 @Synchronized 로 감쌌다.
 */
// change-hyungchul-20261009-1530 : 공통 계약 VadDetector 를 구현한다(AutoCloseable 는 VadDetector 가 이어받는다).
class SileroVadProcessor(context: Context) : VadDetector {

    companion object {
        /** app/src/main/assets/ 아래에 있어야 하는 파일명 */
        const val MODEL_ASSET = "silero_vad_16k_op15.onnx"

        /** CSV/run_meta 에 남길 모델 이름 */
        const val MODEL_LABEL = "silero_vad_16k_op15.onnx"

        const val SAMPLE_RATE = 16_000
        const val WINDOW_SAMPLES = 512      // 32 ms @ 16 kHz (공식 규격, 바꾸면 안 된다)
        const val CONTEXT_SAMPLES = 64      // 공식 규격 (16 kHz 기준)

        /** state tensor 총 float 개수: 2 x batch(1) x 128 */
        private const val STATE_FLOATS = 2 * 1 * 128

        // change-hyungchul-20261009-1530 : MIN_SILENCE_AT_MAX_SPEECH_MS(98) 는 구간 계산과 함께 vad/FrameSegmenter 로 옮겼다.
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // SessionOptions 는 이를 사용하는 Session 보다 먼저 close 하면 안 되므로 필드로 잡아 둔다.
    private val sessionOptions: OrtSession.SessionOptions = OrtSession.SessionOptions()
    private val session: OrtSession

    /** sr 입력은 값이 항상 16000 이라 배치 시작 시 1회만 만들어 재사용한다(프레임마다 만들면 낭비다). */
    private val srTensor: OnnxTensor

    /** run_meta.csv 에 남길 ONNX Runtime 버전 */
    override val runtimeVersion: String = env.version   // change-hyungchul-20261009-1530 : override

    /** run_meta.csv 에 남길 모델 파일 해시 — "어떤 모델로 낸 수치인지"를 재현 가능하게 만든다. */
    override val modelSha256: String   // change-hyungchul-20261009-1530 : override

    // change-hyungchul-20261009-1530 : VadDetector 계약 — 엔진 종류와 CSV vad_model 이름
    override val engine: VadEngine = VadEngine.SILERO
    override val label: String = MODEL_LABEL

    init {
        // ── 1) assets 에서 모델을 읽는다. 없으면 원인을 정확히 알려주고 즉시 실패한다. ──
        val modelBytes = try {
            context.assets.open(MODEL_ASSET).use { it.readBytes() }
        } catch (e: IOException) {
            throw FileNotFoundException(
                "assets/$MODEL_ASSET 이 없습니다. tools/download_models.sh 를 실행하거나 " +
                    "Silero 공식 모델을 app/src/main/assets/$MODEL_ASSET 에 넣어주세요. (원인: ${e.message})",
            )
        }
        modelSha256 = VadAudio.sha256(modelBytes)               // change-hyungchul-20261009-1530 : 공통 해시 함수 사용

        // ── 2) 공식 Python OnnxWrapper 와 같은 단일 thread 설정으로 세션을 만든다. ──
        sessionOptions.setInterOpNumThreads(1)
        sessionOptions.setIntraOpNumThreads(1)
        session = env.createSession(modelBytes, sessionOptions)

        // ── 3) 모델 인터페이스를 여기서 검증한다(추측해서 쓰다가 런타임에 깨지는 것을 막는다). ──
        val requiredInputs = setOf("input", "state", "sr")
        require(session.inputNames.containsAll(requiredInputs)) {
            "Silero ONNX input 이름이 예상과 다릅니다: ${session.inputNames} (기대: $requiredInputs)"
        }
        require(session.outputNames.size >= 2) {
            "Silero ONNX output 이 2개 미만입니다: ${session.outputNames}"
        }

        srTensor = createSrTensor()
    }

    /**
     * 목적: sr 입력 tensor 를 만든다.
     * 입력: 없음
     * 출력: 없음
     * 리턴: int64 scalar tensor (공식 Python 의 np.array(16000, dtype='int64') 와 같다)
     * 비고: ORT Java 는 boxed primitive 를 넘기면 rank-0 scalar 로 만든다.
     *       버전에 따라 이 경로가 막히는 경우를 대비해 shape [1] fallback 을 둔다.
     */
    private fun createSrTensor(): OnnxTensor = runCatching {
        OnnxTensor.createTensor(env, SAMPLE_RATE.toLong())
    }.getOrElse {
        OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
            longArrayOf(1),
        )
    }

    /**
     * change-hyungchul-20261009-1530
     * 목적: PCM 1건에서 말 구간을 찾는다(VadDetector 계약).
     * 입력: pcm16Le — 16 kHz / mono / PCM16 LE, setting — Silero 방식 설정(SILERO_* 프리셋 또는 Custom)
     * 출력: 없음
     * 리턴: FrameSegmenter.Result(raw = 패딩 전, padded = 패딩 후)
     * 예외: 값 범위 오류는 IllegalArgumentException(VadConfig init), ONNX 실행 실패 시 OrtException
     * 비고: 예전 process() 에서 "확률 계산 → 구간 계산" 까지만 남겼다. 이어 붙이기는 VadRunner 가 공통으로 한다.
     */
    @Synchronized
    override fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result {
        val config = VadConfig.fromSetting(setting)                      // 범위 검증 포함
        val samples = VadAudio.toFloat(pcm16Le)                          // PCM16 → [-1, 1) float
        if (samples.isEmpty()) return FrameSegmenter.Result(emptyList(), emptyList())
        val probabilities = inferProbabilities(samples)                  // frame 별 speech 확률(기존 그대로)
        return FrameSegmenter.collect(                                   // 기존 collectSpeechSegments 와 같은 규칙
            probabilities = probabilities,
            windowSamples = WINDOW_SAMPLES,
            audioLengthSamples = samples.size,
            threshold = config.threshold,
            minSpeechMs = config.minSpeechDurationMs,
            minSilenceMs = config.minSilenceDurationMs,
            speechPadMs = config.speechPadMs,
            maxSpeechSec = config.maxSpeechDurationSec,
        )
    }

    /**
     * 목적: 공식 OnnxWrapper 와 같은 순서로 frame 별 speech 확률을 계산한다.
     * 입력: samples — 전체 오디오(float)
     * 출력: 없음
     * 리턴: frame 개수 길이의 확률 배열
     * 비고: state 와 context 를 여기서 새로 만들기 때문에 파일마다 자동으로 초기화된다.
     */
    private fun inferProbabilities(samples: FloatArray): FloatArray {
        val state = FloatArray(STATE_FLOATS)                     // 공식: torch.zeros((2, 1, 128))
        var context = FloatArray(CONTEXT_SAMPLES)                // 공식: 첫 frame 의 context 는 0
        val frameCount = (samples.size + WINDOW_SAMPLES - 1) / WINDOW_SAMPLES
        val probs = FloatArray(frameCount)

        for (frameIndex in 0 until frameCount) {
            val start = frameIndex * WINDOW_SAMPLES
            val remaining = min(WINDOW_SAMPLES, samples.size - start)

            // 공식: x = torch.cat([self._context, x], dim=1) → 64 + 512 = 576 sample
            val input = FloatArray(CONTEXT_SAMPLES + WINDOW_SAMPLES)
            context.copyInto(input, destinationOffset = 0)
            if (remaining > 0) {
                samples.copyInto(
                    destination = input,
                    destinationOffset = CONTEXT_SAMPLES,
                    startIndex = start,
                    endIndex = start + remaining,
                )
            }
            // 마지막 frame 이 512 보다 짧으면 FloatArray 기본값 0 으로 채워진다
            // (공식 Python 의 torch.nn.functional.pad(chunk, (0, ...)) 와 같다).

            OnnxTensor.createTensor(
                env,
                FloatBuffer.wrap(input),
                longArrayOf(1, input.size.toLong()),             // shape [1, 576]
            ).use { inputTensor ->
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(state),
                    longArrayOf(2, 1, 128),                      // shape [2, 1, 128]
                ).use { stateTensor ->
                    val inputs = mapOf(
                        "input" to inputTensor,
                        "state" to stateTensor,
                        "sr" to srTensor,
                    )
                    session.run(inputs).use { outputs ->
                        // 공식: out, state = ort_outs (출력 순서가 확률, 다음 state)
                        val probTensor = outputs[0] as? OnnxTensor
                            ?: error("Silero output[0] 이 tensor 가 아닙니다.")
                        val nextStateTensor = outputs[1] as? OnnxTensor
                            ?: error("Silero output[1] 이 tensor 가 아닙니다.")

                        val probBuffer = probTensor.floatBuffer
                            ?: error("Silero 확률 출력이 FLOAT 가 아닙니다.")
                        probs[frameIndex] = probBuffer.get(0).coerceIn(0f, 1f)

                        val stateBuffer = nextStateTensor.floatBuffer
                            ?: error("Silero state 출력이 FLOAT 가 아닙니다.")
                        require(stateBuffer.remaining() >= STATE_FLOATS) {
                            "Silero state 출력 크기가 작습니다: ${stateBuffer.remaining()}"
                        }
                        stateBuffer.get(state, 0, STATE_FLOATS)  // 다음 frame 을 위해 state 갱신
                    }
                }
            }

            // 공식: self._context = x[..., -64:]
            context = input.copyOfRange(input.size - CONTEXT_SAMPLES, input.size)
        }
        return probs
    }

    // change-hyungchul-20261009-1530
    //   collectSpeechSegments() → vad/FrameSegmenter.collect() (창 크기만 인자로 바꾼 같은 코드)
    //   pcm16ToFloat()          → vad/VadPipeline.kt VadAudio.toFloat()
    //   collectPcm()/JoinedPcm  → vad/VadPipeline.kt VadPcmJoiner.join()
    //   로 옮겼다(모든 VAD 엔진이 같은 구간 규칙·이어 붙이기를 쓰게 하기 위함).

    // change-hyungchul-20261009-1530 : sha256() → VadAudio.sha256() 로 옮겼다.

    /**
     * 목적: 배치가 끝날 때 ONNX 자원을 정리한다.
     * 비고: OrtEnvironment 는 프로세스 공용 singleton 이므로 닫지 않는다.
     */
    override fun close() {
        runCatching { srTensor.close() }
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }
}
