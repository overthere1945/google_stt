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
 */
package com.example.google_stt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 앱 UI 에 노출하는 VAD Profile.
 * change-hyungchul-20260914-2130
 *   기존 ASR Safe / Balanced / Aggressive(내가 임의로 정했던 값)를 전부 걷어내고,
 *   PC 쪽 "STT모델 비교 GUI"(파이썬) 의 Silero VAD 설정 화면과 1:1 로 맞춘다.
 *   - Default : GUI 가 처음 띄우는 기본값
 *   - Safe    : GUI 의 Safe 설정
 *   같은 음원을 PC GUI 와 안드로이드 앱에서 각각 돌렸을 때 STT 입력이 동일해야
 *   WER/CER 비교가 성립하므로, 이름과 값을 임의로 바꾸지 않는다.
 */
enum class VadProfile(
    val displayName: String,   // 스피너에 보여줄 이름
    val folderName: String,    // 결과 폴더명 조각 (output/google/<모드>/vad_<folderName>)
) {
    DEFAULT("Default (GUI 기본값)", "default"),
    SAFE("Safe (GUI Safe)", "safe"),
    CUSTOM("Custom", "custom"),
}

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

/**
 * 프로젝트 평가용 preset 모음.
 * change-hyungchul-20260914-2130
 *   값의 출처를 "내 임의 판단" → "PC GUI 화면" 으로 바꾼다.
 *
 *   [Silero 공식 기본값] (참고용, 아래 preset 과 다르다)
 *     threshold=0.5, min_speech_duration_ms=250, min_silence_duration_ms=100,
 *     speech_pad_ms=30, max_speech_duration_s=inf
 *
 *   [PC GUI Default]  threshold 0.5 / min speech 250 ms / min silence 400 ms /
 *                     speech pad 150 ms / max speech 0(Unlimited) / 구간 사이 무음 200 ms
 *   [PC GUI Safe]     threshold 0.3 / min speech 150 ms / min silence 600 ms /
 *                     speech pad 300 ms / max speech 0(Unlimited) / 구간 사이 무음 200 ms
 *
 *   Safe 가 threshold 를 0.5 → 0.3 으로 "낮추는" 것에 주의한다.
 *   threshold 가 낮을수록 작은 소리도 speech 로 보므로 말이 덜 잘린다(=안전하다).
 */
object VadPresets {
    /**
     * 목적: Profile 에 해당하는 파라미터를 돌려준다.
     * 입력: profile
     * 출력: 없음
     * 리턴: VadConfig
     */
    fun config(profile: VadProfile): VadConfig = when (profile) {
        // change-hyungchul-20260914-2130 : PC GUI 기본값과 동일
        VadProfile.DEFAULT -> VadConfig(
            threshold = 0.50f,              // GUI: Threshold 0.5
            minSpeechDurationMs = 250,      // GUI: Min speech (ms) 250
            minSilenceDurationMs = 400,     // GUI: Min silence (ms) 400
            speechPadMs = 150,              // GUI: Speech pad (ms) 150
            maxSpeechDurationSec = null,    // GUI: Max speech (s) 0 = Unlimited
            joinSilenceMs = 200,            // GUI: 구간 사이 무음(ms) 200
        )

        // change-hyungchul-20260914-2130 : PC GUI Safe 설정과 동일
        VadProfile.SAFE -> VadConfig(
            threshold = 0.30f,              // GUI Safe: Threshold 0.3
            minSpeechDurationMs = 150,      // GUI Safe: Min speech (ms) 150
            minSilenceDurationMs = 600,     // GUI Safe: Min silence (ms) 600
            speechPadMs = 300,              // GUI Safe: Speech pad (ms) 300
            maxSpeechDurationSec = null,    // GUI Safe: Max speech (s) 0 = Unlimited
            joinSilenceMs = 200,            // GUI Safe: 구간 사이 무음(ms) 200
        )

        // Custom 의 시작값은 Default 와 같게 둔다(사용자가 UI 에서 고쳐 쓴다).
        VadProfile.CUSTOM -> VadConfig(
            threshold = 0.50f,
            minSpeechDurationMs = 250,
            minSilenceDurationMs = 400,
            speechPadMs = 150,
            maxSpeechDurationSec = null,
            joinSilenceMs = 200,
        )
    }
}

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
class SileroVadProcessor(context: Context) : AutoCloseable {

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

        /** 공식 상수: min_silence_samples_at_max_speech = sampling_rate * 98 / 1000 */
        private const val MIN_SILENCE_AT_MAX_SPEECH_MS = 98
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    // SessionOptions 는 이를 사용하는 Session 보다 먼저 close 하면 안 되므로 필드로 잡아 둔다.
    private val sessionOptions: OrtSession.SessionOptions = OrtSession.SessionOptions()
    private val session: OrtSession

    /** sr 입력은 값이 항상 16000 이라 배치 시작 시 1회만 만들어 재사용한다(프레임마다 만들면 낭비다). */
    private val srTensor: OnnxTensor

    /** run_meta.csv 에 남길 ONNX Runtime 버전 */
    val runtimeVersion: String = env.version

    /** run_meta.csv 에 남길 모델 파일 해시 — "어떤 모델로 낸 수치인지"를 재현 가능하게 만든다. */
    val modelSha256: String

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
        modelSha256 = sha256(modelBytes)

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
     * 목적: PCM 1건에서 speech 구간만 남긴 PCM 을 만든다.
     * 입력: pcm16Le — 16 kHz / mono / PCM16 LE, config — VAD 파라미터
     * 출력: 없음
     * 리턴: VadResult (구간 목록 + 재구성 PCM + 계측치)
     * 예외: PCM 바이트 수가 홀수이면 IllegalArgumentException, ONNX 실행 실패 시 OrtException
     */
    @Synchronized
    fun process(pcm16Le: ByteArray, config: VadConfig): VadResult {
        require(pcm16Le.size % 2 == 0) { "PCM16 은 byte 수가 짝수여야 합니다: ${pcm16Le.size}" }

        val startedNs = System.nanoTime()                       // 처리시간 계측 시작
        val samples = pcm16ToFloat(pcm16Le)                     // PCM16 → [-1, 1) float
        if (samples.isEmpty()) {                                // 빈 입력은 그대로 빈 결과
            return VadResult(ByteArray(0), emptyList(), 0, 0, 0, 0)
        }

        val probabilities = inferProbabilities(samples)         // frame 별 speech 확률
        val collected = collectSpeechSegments(                  // 확률 → 구간 목록
            probabilities = probabilities,
            audioLengthSamples = samples.size,
            config = config,
        )
        // change-hyungchul-20260914-2130 : 구간 사이 무음(joinSilenceMs) 을 끼워 넣는다
        val joined = collectPcm(pcm16Le, collected.padded, config.joinSilenceMs)
        val outputPcm = joined.pcm                              // 구간 PCM + 구간 사이 무음
        val processMs = (System.nanoTime() - startedNs) / 1_000_000

        return VadResult(
            pcm = outputPcm,
            segments = collected.padded,
            processMs = processMs,
            originalSamples = samples.size,
            outputSamples = outputPcm.size / 2,
            detectedSpeechSamples = collected.raw.sumOf {
                (it.endSample - it.startSample).coerceAtLeast(0)
            },
            // add-hyungchul-20260914-2130
            joinSilenceSamples = joined.silenceSamples,
            // add-hyungchul-20260914-2350
            seams = joined.seams,
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

    /** raw = padding 전 구간(계측용), padded = padding 후 구간(실제 잘라낼 구간) */
    private class SegmentCollection(
        val raw: List<VadSegment>,
        val padded: List<VadSegment>,
    )

    /** 상태 기계를 도는 동안 start/end 를 고쳐 쓰기 위한 가변 구간 */
    private class MutableSegment(var start: Int, var end: Int)

    /**
     * 목적: 공식 get_speech_timestamps() 의 상태 기계를 그대로 옮겨 speech 구간을 만든다.
     * 입력: probabilities(frame 확률), audioLengthSamples(전체 sample 수), config
     * 출력: 없음
     * 리턴: SegmentCollection
     * 비고:
     * - use_max_poss_sil_at_max_speech = true (공식 기본값) 동작만 구현한다.
     * - ★ Max Speech 분할 분기는 Custom 에서 Max Speech 를 0 보다 크게 넣었을 때만 실행된다.
     *   기본 3개 Profile 은 모두 Unlimited 라 이 분기를 타지 않는다.
     * - 공식 Python 과 동일하게 min speech / min silence 비교에 엄격한 '>' 와 '<' 를 쓴다.
     */
    private fun collectSpeechSegments(
        probabilities: FloatArray,
        audioLengthSamples: Int,
        config: VadConfig,
    ): SegmentCollection {
        // ── 공식 식 그대로: ms → sample 환산 ──
        val minSpeechSamples = SAMPLE_RATE * config.minSpeechDurationMs / 1000.0
        val speechPadSamples = SAMPLE_RATE * config.speechPadMs / 1000.0
        val minSilenceSamples = SAMPLE_RATE * config.minSilenceDurationMs / 1000.0
        val minSilenceAtMaxSamples = SAMPLE_RATE * MIN_SILENCE_AT_MAX_SPEECH_MS / 1000.0
        val maxSpeechSamples = config.maxSpeechDurationSec?.let {
            SAMPLE_RATE * it - WINDOW_SAMPLES - 2.0 * speechPadSamples
        } ?: Double.POSITIVE_INFINITY                            // null = Unlimited

        var triggered = false                                    // 지금 speech 안인지
        var currentSpeech: MutableSegment? = null                // 진행 중인 구간
        var tempEnd = 0                                          // 잠정 무음 시작 위치
        var prevEnd = 0                                          // 직전 분할 후보 위치
        var nextStart = 0                                        // 분할 후 다음 구간 시작 위치
        val possibleEnds = mutableListOf<Pair<Int, Int>>()        // (무음 시작, 무음 길이) 후보들
        val speeches = mutableListOf<MutableSegment>()

        for (i in probabilities.indices) {
            val speechProb = probabilities[i]
            val curSample = WINDOW_SAMPLES * i                    // 공식: cur_sample = window * i

            // (1) 무음이 끝나고 speech 가 다시 나오면, 그 무음을 max-speech 분할 후보로 저장한다.
            if (speechProb >= config.threshold && tempEnd != 0) {
                val silenceDuration = curSample - tempEnd
                if (silenceDuration > minSilenceAtMaxSamples) {
                    possibleEnds.add(tempEnd to silenceDuration)
                }
                tempEnd = 0
                if (nextStart < prevEnd) nextStart = curSample
            }

            // (2) speech 시작
            if (speechProb >= config.threshold && !triggered) {
                triggered = true
                currentSpeech = MutableSegment(start = curSample, end = 0)
                continue
            }

            // (3) Max Speech 초과 → 구간을 강제로 끊는다. (Unlimited 면 절대 실행되지 않는다)
            val current = currentSpeech
            if (triggered && current != null && (curSample - current.start) > maxSpeechSamples) {
                if (possibleEnds.isNotEmpty()) {
                    // 공식: prev_end, dur = max(possible_ends, key=lambda x: x[1])
                    val best = possibleEnds.maxByOrNull { it.second }!!
                    prevEnd = best.first
                    val duration = best.second
                    current.end = prevEnd                          // 가장 긴 무음 지점에서 끊는다
                    speeches.add(current)

                    nextStart = prevEnd + duration
                    // 공식: if next_start < prev_end -> triggered = False, else 새 구간을 next_start 에서 시작
                    if (nextStart < prevEnd) {
                        triggered = false
                        currentSpeech = null
                    } else {
                        currentSpeech = MutableSegment(start = nextStart, end = 0)
                    }
                    prevEnd = 0
                    nextStart = 0
                    tempEnd = 0
                    possibleEnds.clear()
                } else {
                    // 쓸 만한 무음 후보가 없으면 현재 frame 위치에서 그냥 끊는다.
                    current.end = curSample
                    speeches.add(current)
                    currentSpeech = null
                    prevEnd = 0
                    nextStart = 0
                    tempEnd = 0
                    triggered = false
                    possibleEnds.clear()
                    continue
                }
            }

            // (4) speech 중에 neg_threshold 아래로 떨어지면 잠정 무음이 시작된다.
            if (speechProb < config.negThreshold && triggered) {
                if (tempEnd == 0) tempEnd = curSample
                // 무음이 min silence 보다 짧으면 아직 구간을 끊지 않는다.
                if (curSample - tempEnd < minSilenceSamples) continue

                val speech = currentSpeech
                if (speech != null) {
                    speech.end = tempEnd
                    // 너무 짧은 speech 는 버린다(공식과 같이 엄격한 '>').
                    if (speech.end - speech.start > minSpeechSamples) speeches.add(speech)
                }
                currentSpeech = null
                prevEnd = 0
                nextStart = 0
                tempEnd = 0
                triggered = false
                possibleEnds.clear()
                continue
            }
        }

        // (5) 파일 끝까지 speech 가 이어졌으면 마지막 구간을 닫는다.
        val lastSpeech = currentSpeech
        if (lastSpeech != null && audioLengthSamples - lastSpeech.start > minSpeechSamples) {
            lastSpeech.end = audioLengthSamples
            speeches.add(lastSpeech)
        }

        // padding 전 구간 — result.csv 의 vad_detected_speech_sec 계산에 쓴다.
        val raw = speeches.mapNotNull { toSegment(it.start, it.end, audioLengthSamples) }

        // (6) 공식 padding 로직을 그대로 적용한다.
        val padded = raw.map { MutableSegment(it.startSample, it.endSample) }.toMutableList()
        val pad = speechPadSamples.toInt()
        for (i in padded.indices) {
            val speech = padded[i]
            if (i == 0) {
                speech.start = max(0, speech.start - pad)          // 첫 구간은 앞으로 pad
            }
            if (i != padded.lastIndex) {
                val next = padded[i + 1]
                val silenceDuration = next.start - speech.end
                if (silenceDuration < 2 * pad) {
                    // 두 구간 사이가 좁으면 무음을 절반씩 나눠 갖는다(겹침 방지).
                    val half = silenceDuration / 2
                    speech.end += half
                    next.start = max(0, next.start - half)
                } else {
                    speech.end = min(audioLengthSamples, speech.end + pad)
                    next.start = max(0, next.start - pad)
                }
            } else {
                speech.end = min(audioLengthSamples, speech.end + pad)  // 마지막 구간은 뒤로 pad
            }
        }

        val paddedImmutable = padded.mapNotNull { toSegment(it.start, it.end, audioLengthSamples) }
        return SegmentCollection(raw = raw, padded = paddedImmutable)
    }

    /**
     * 목적: 구간 좌표를 오디오 길이 안으로 자르고, 길이가 0 이하이면 버린다.
     * 입력: start, end, length
     * 리턴: VadSegment 또는 null
     */
    private fun toSegment(start: Int, end: Int, length: Int): VadSegment? {
        val s = start.coerceIn(0, length)
        val e = end.coerceIn(0, length)
        return if (e > s) VadSegment(s, e) else null
    }

    /**
     * 목적: PCM16 LE 바이트를 [-1, 1) 범위 float 로 바꾼다.
     * 입력: pcm — PCM16 LE
     * 리턴: FloatArray (sample 수 = pcm.size / 2)
     */
    private fun pcm16ToFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        var bi = 0
        for (i in out.indices) {
            val lo = pcm[bi].toInt() and 0xFF                     // little-endian: 낮은 바이트가 먼저
            val hi = pcm[bi + 1].toInt()                          // 부호 확장을 위해 마스킹하지 않는다
            out[i] = ((hi shl 8) or lo).toShort().toInt() / 32768.0f
            bi += 2
        }
        return out
    }

    /**
     * 목적: collectPcm() 의 반환 묶음.
     * add-hyungchul-20260914-2130
     *   끼워 넣은 무음 길이를 CSV 에 남겨야 해서 PCM 과 함께 돌려준다.
     */
    private class JoinedPcm(
        val pcm: ByteArray,             // 최종 STT 입력 PCM16 LE
        val silenceSamples: Int,        // 구간 사이에 끼워 넣은 무음 sample 합
        // add-hyungchul-20260914-2350 : Speech EPD 가 쓸 "안전한 절단 후보" 좌표
        //   구간과 구간 사이(끼워 넣은 무음 한가운데)의 출력 PCM sample 좌표다.
        //   여기서 자르면 말이 잘리지 않는다는 것이 VAD 로 이미 보장되어 있다.
        val seams: List<Int>,
    )

    /**
     * 목적: 구간에 해당하는 "원본" PCM 바이트를 순서대로 이어 붙이고,
     *       구간과 구간 사이에 joinSilenceMs 만큼 digital silence 를 끼워 넣는다.
     * 입력: pcm — 원본 PCM16 LE
     *       segments — 잘라낼 구간(padding 적용 후)
     *       joinSilenceMs — 구간 사이에 넣을 무음 길이(ms). 0 이면 예전 동작과 동일하다.
     * 출력: 없음
     * 리턴: JoinedPcm(최종 PCM, 끼워 넣은 무음 sample 수)
     * 비고: change-hyungchul-20260914-2130
     *   예전에는 "무음을 넣으면 STT 입력을 바꾸는 것" 이라는 이유로 넣지 않았다.
     *   그런데 실측 결과, 구간을 딱 붙여 버리면 ML Kit 가 문장 경계를 못 잡고
     *   segments=1 로 처리하다가 앞부분을 통째로 버리는 사례가 나왔다.
     *   PC GUI 도 같은 이유로 --vad-join-silence-ms 를 200 ms 로 기본 적용한다.
     *   PC/안드로이드 STT 입력을 같게 만들어야 WER/CER 비교가 성립하므로 동작을 맞춘다.
     */
    private fun collectPcm(
        pcm: ByteArray,
        segments: List<VadSegment>,
        joinSilenceMs: Int,
    ): JoinedPcm {
        if (segments.isEmpty()) return JoinedPcm(ByteArray(0), 0, emptyList())

        // 무음 1회분의 sample / byte 수. PCM16 은 sample 당 2 byte 이고, 0x0000 이 무음이다.
        val gapSamples = (SAMPLE_RATE.toLong() * joinSilenceMs / 1_000L).toInt().coerceAtLeast(0)
        val gapBytes = gapSamples * 2
        val gap = if (gapBytes > 0) ByteArray(gapBytes) else ByteArray(0)  // ByteArray 는 0 으로 초기화된다

        // 대략적인 최종 크기(구간 합 + 무음 (구간수-1) 회분)로 버퍼를 미리 잡아 재할당을 줄인다.
        val estimated = segments.sumOf { (it.endSample - it.startSample).coerceAtLeast(0) * 2 } +
            gapBytes * (segments.size - 1).coerceAtLeast(0)
        val out = ByteArrayOutputStream(estimated)

        var wrote = false            // 실제로 뭔가 쓴 적이 있는지(맨 앞에 무음이 붙는 것을 막는다)
        var insertedGaps = 0         // 실제로 끼워 넣은 무음 횟수
        val seams = ArrayList<Int>() // add-hyungchul-20260914-2350 : 절단 후보 좌표
        for (s in segments) {
            val startByte = (s.startSample * 2).coerceIn(0, pcm.size)
            val endByte = (s.endSample * 2).coerceIn(startByte, pcm.size)
            if (endByte <= startByte) continue                 // 빈 구간은 건너뛴다
            if (wrote) {                                       // 두 번째 구간부터
                // add-hyungchul-20260914-2350
                // 현재 출력 길이(sample) 가 곧 이 이음매의 시작점이다.
                val seamStart = out.size() / 2
                if (gapBytes > 0) {
                    out.write(gap, 0, gapBytes)
                    insertedGaps++
                    seams.add(seamStart + gapSamples / 2)      // 무음 한가운데
                } else {
                    seams.add(seamStart)                       // 무음이 없으면 이음매 그 자리
                }
            }
            out.write(pcm, startByte, endByte - startByte)     // 구간의 원본 PCM
            wrote = true
        }
        return JoinedPcm(out.toByteArray(), gapSamples * insertedGaps, seams)
    }

    /** 모델 파일의 SHA-256 (run_meta.csv 에 남겨 재현성을 확보한다) */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it) }

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
