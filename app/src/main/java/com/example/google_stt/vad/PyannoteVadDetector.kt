/*
 * 파일명: vad/PyannoteVadDetector.kt
 * 목적 및 기능:
 * - pyannote/segmentation-3.0(MIT, powerset 7클래스) ONNX 모델로 말 구간을 찾는다.
 *     ① 10 s 창을 1 s(창의 10 %) 간격으로 밀며 추론 — 출력 (N, 589, 7) log 확률
 *     ② 프레임마다 argmax ≠ 0 이면 말(= 화자 1명 이상) — pyannote VoiceActivityDetection 의
 *        "powerset → multilabel(hard) → 화자 축 max" 와 같다
 *     ③ 겹치는 창을 hamming 가중 평균으로 합친다(pyannote Inference 집계와 같은 방식)
 *     ④ Binarize: onset/offset 히스테리시스 → min_duration_off 보다 짧은 무음 메움 → min_duration_on 보다 짧은 말 제거
 *     ⑤ 앞뒤 여유(pad_onset / pad_offset) 를 붙이고 겹치거나 맞닿은 구간을 합친 뒤 0 ~ 음원 길이로 자른다
 * - ①~③ 은 sherpa-onnx scripts/pyannote/segmentation/vad-onnx.py 를, ④ 는 pyannote-audio utils/signal.py Binarize 를,
 *   ⑤ 는 PC "STT 통합 실행기" v8.1 vad_segments.py 의 pad_and_merge() 순서를 그대로 따른다
 *   (PC 와 같은 조건 이름 PYANNOTE_S500 / S2000 의 결과가 같은 방식으로 만들어지게 하기 위함).
 * - 공식 기본값: onset = offset = 0.5(powerset 모델 고정), min_duration_on = min_duration_off = 0.0
 *   (pyannote-audio pipelines/voice_activity_detection.py default_parameters)
 *
 * 모델 파일(assets, tools/download_models.sh 가 받는다):
 *   pyannote_seg3.onnx ← sherpa-onnx 배포본 sherpa-onnx-pyannote-segmentation-3-0 의 model.onnx(fp32)
 *   (원본 pyannote/segmentation-3.0 은 Hugging Face 에서 사용 조건 동의가 필요한 MIT 모델이다)
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.example.google_stt.VadSegment
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * 목적: pyannote segmentation-3.0 VAD 실행기.
 * 입력(생성자): context — assets 에서 모델을 읽을 Context
 */
class PyannoteVadDetector(context: Context) : VadDetector {

    companion object {
        const val MODEL_ASSET = "pyannote_seg3.onnx"      // sherpa-onnx 변환 fp32 모델
        private const val BATCH = 8                       // 한 번에 추론할 창 수(메모리 제한)
        private const val SAMPLE_RATE = 16_000
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()
    private val session: OrtSession
    private val inputName: String                                          // 입력 이름(x)
    private val windowSize: Int                                            // 창 샘플 수(160000)
    private val windowShift: Int                                           // 창 이동(창의 10 % = 16000)
    private val rfSize: Int                                                // receptive field 샘플(991)
    private val rfShift: Int                                               // 프레임 간격 샘플(270)

    override val engine: VadEngine = VadEngine.PYANNOTE                    // 엔진 종류
    override val label: String = MODEL_ASSET                               // CSV vad_model
    override val runtimeVersion: String = env.version                      // ORT 판
    override val modelSha256: String                                       // 모델 해시

    init {
        val bytes = VadAudio.readAsset(context, MODEL_ASSET)
        modelSha256 = VadAudio.sha256(bytes)
        sessionOptions.setIntraOpNumThreads(1)
        sessionOptions.setInterOpNumThreads(1)
        session = env.createSession(bytes, sessionOptions)
        inputName = session.inputNames.first()
        // sherpa export-onnx.py 가 넣은 메타데이터를 읽는다(없으면 segmentation-3.0 고정값)
        val meta = runCatching { session.metadata.customMetadata }.getOrDefault(emptyMap())
        windowSize = meta["window_size"]?.toIntOrNull() ?: 160_000
        windowShift = (0.1 * windowSize).toInt()                           // vad-onnx.py: int(0.1 * window_size)
        rfSize = meta["receptive_field_size"]?.toIntOrNull() ?: 991
        rfShift = meta["receptive_field_shift"]?.toIntOrNull() ?: 270
    }

    /**
     * 목적: PCM 1건에서 말 구간을 찾는다.
     * 입력: pcm16Le — 16 kHz/mono/PCM16 LE, setting — PYANNOTE_* 프리셋 또는 Custom
     * 리턴: FrameSegmenter.Result(raw = 여유 붙이기 전, padded = 여유 붙인 뒤)
     */
    @Synchronized
    override fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result {
        val audio = VadAudio.toFloat(pcm16Le)                              // [-1, 1) float (pyannote 입력 규격)
        val total = audio.size
        if (total == 0) return FrameSegmenter.Result(emptyList(), emptyList())
        val cls = frameSpeechScores(audio)                                 // ①~③ 프레임별 말 점수(0~1)

        // ④ Binarize(onset/offset 히스테리시스) — 시각은 프레임 가운데(pyannote frames[i].middle)
        val scale = rfShift.toDouble() / SAMPLE_RATE
        val scaleOffset = rfSize.toDouble() / SAMPLE_RATE * 0.5
        fun ts(i: Int) = i * scale + scaleOffset
        val onset = setting[VadKeys.ONSET]
        val offset = setting[VadKeys.OFFSET]
        val regions = ArrayList<DoubleArray>()                             // [시작초, 끝초]
        var start = ts(0)
        var active = cls[0] > onset
        for (i in 1 until cls.size) {
            val t = ts(i)
            if (active) {
                if (cls[i] < offset) { regions.add(doubleArrayOf(start, t)); start = t; active = false }
            } else if (cls[i] > onset) { start = t; active = true }
        }
        if (active) regions.add(doubleArrayOf(start, ts(cls.size - 1)))   // 끝까지 말이면 마지막 프레임까지

        // min_duration_off: 간격이 그보다 짧으면 합친다(Timeline.support(collar))
        val minOff = setting[VadKeys.MIN_OFF_SEC]
        val merged = if (minOff > 0.0) mergeGaps(regions, minOff) else regions
        // min_duration_on: 그보다 짧은 말 구간은 지운다
        val minOn = setting[VadKeys.MIN_ON_SEC]
        val kept = if (minOn > 0.0) merged.filter { it[1] - it[0] >= minOn } else merged

        // ⑤ 앞뒤 여유 → 겹침·맞닿음 합치기 → 0 ~ 음원 길이로 자르기(PC v8.1 pad_and_merge 순서)
        val dur = total.toDouble() / SAMPLE_RATE
        val padOn = setting[VadKeys.PAD_ONSET_SEC]
        val padOff = setting[VadKeys.PAD_OFFSET_SEC]
        val padded = if (padOn > 0.0 || padOff > 0.0) {
            mergeGaps(kept.map { doubleArrayOf(it[0] - padOn, it[1] + padOff) }, 0.0, touching = true)
        } else kept

        return FrameSegmenter.Result(
            raw = toSegments(kept, dur, total),
            padded = toSegments(padded, dur, total),
        )
    }

    /**
     * 목적: ①~③ 창 추론 → 말 여부 → hamming 가중 평균(vad-onnx.py 와 같은 계산).
     * 입력: audio — [-1, 1) float 전체
     * 리턴: 프레임별 말 점수(0~1)
     */
    private fun frameSpeechScores(audio: FloatArray): DoubleArray {
        val n = audio.size
        val numFull = if (n >= windowSize) (n - windowSize) / windowShift + 1 else 0   // 완전한 창 수
        val hasLast = n < windowSize || (n - windowSize) % windowShift > 0              // 남는 꼬리 창 여부
        val chunkStarts = ArrayList<Int>()
        for (i in 0 until numFull) chunkStarts.add(i * windowShift)
        if (hasLast) chunkStarts.add(numFull * windowShift)                // 꼬리 창(0 으로 채움)
        val numChunks = chunkStarts.size

        var labels: Array<IntArray>? = null                                // [창][프레임] 0/1
        var b = 0
        while (b < numChunks) {
            val bs = minOf(BATCH, numChunks - b)
            val buf = FloatArray(bs * windowSize)                          // 0 으로 시작(꼬리 창 채우기)
            for (k in 0 until bs) {
                val s = chunkStarts[b + k]
                val len = minOf(windowSize, n - s)
                System.arraycopy(audio, s, buf, k * windowSize, len)
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(bs.toLong(), 1, windowSize.toLong())).use { x ->
                session.run(mapOf(inputName to x)).use { res ->
                    val y = res[0] as OnnxTensor                           // (bs, frames, classes)
                    val shape = y.info.shape
                    val frames = shape[1].toInt()
                    val classes = shape[2].toInt()
                    val fb = y.floatBuffer
                    if (labels == null) labels = Array(numChunks) { IntArray(frames) }
                    for (k in 0 until bs) for (f in 0 until frames) {
                        val base = (k * frames + f) * classes
                        var arg = 0
                        for (c in 1 until classes) if (fb.get(base + c) > fb.get(base + arg)) arg = c   // argmax
                        labels!![b + k][f] = if (arg != 0) 1 else 0         // 0 = 아무도 말하지 않음
                    }
                }
            }
            b += bs
        }
        val lab = labels ?: return DoubleArray(1)
        val frames = lab[0].size
        val numFrames = ((windowSize + (numChunks - 1) * windowShift).toDouble() / rfShift).toInt() + 1
        val classification = DoubleArray(numFrames)
        val count = DoubleArray(numFrames)
        val weight = DoubleArray(frames) { i ->                             // np.hamming(frames)
            if (frames == 1) 1.0 else 0.54 - 0.46 * cos(2.0 * PI * i / (frames - 1))
        }
        for (i in 0 until numChunks) {
            val s = (i * windowShift.toDouble() / rfShift + 0.5).toInt()   // vad-onnx.py: int(i*shift/rf + 0.5)
            for (f in 0 until frames) {
                val idx = s + f
                if (idx >= numFrames) break
                classification[idx] += lab[i][f] * weight[f]
                count[idx] += weight[f]
            }
        }
        for (i in 0 until numFrames) classification[i] /= maxOf(count[i], 1e-12)
        val stop = if (hasLast) minOf(numFrames, n / rfShift) else numFrames   // 꼬리 창의 0 채운 부분 버리기
        return classification.copyOf(maxOf(stop, 1))
    }

    /**
     * 목적: 시간순 구간 목록에서 간격이 gap 보다 짧은(touching 이면 같거나 겹치는) 구간끼리 합친다.
     * 입력: regions — [시작초, 끝초] 목록, gap — 합칠 최대 간격(초), touching — true 면 간격 0 이하도 합침
     * 리턴: 합친 구간 목록
     */
    private fun mergeGaps(regions: List<DoubleArray>, gap: Double, touching: Boolean = false): List<DoubleArray> {
        if (regions.isEmpty()) return regions
        val sorted = regions.sortedBy { it[0] }
        val out = ArrayList<DoubleArray>()
        var cur = sorted[0].copyOf()
        for (k in 1 until sorted.size) {
            val r = sorted[k]
            val g = r[0] - cur[1]                                          // 두 구간 사이 간격
            if (g < gap || (touching && g <= 0.0)) cur[1] = maxOf(cur[1], r[1])  // 합친다
            else { out.add(cur); cur = r.copyOf() }
        }
        out.add(cur)
        return out
    }

    /**
     * 목적: 초 단위 구간을 0 ~ 음원 길이로 자르고 샘플 좌표로 바꾼다.
     * 입력: regions, dur(초), total(샘플 수)
     * 리턴: VadSegment 목록
     */
    private fun toSegments(regions: List<DoubleArray>, dur: Double, total: Int): List<VadSegment> =
        regions.mapNotNull { r ->
            val s = r[0].coerceIn(0.0, dur)
            val e = r[1].coerceIn(0.0, dur)
            FrameSegmenter.toSegment((s * SAMPLE_RATE).roundToInt(), (e * SAMPLE_RATE).roundToInt(), total)
        }

    /** 배치 끝 정리 */
    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }
}
