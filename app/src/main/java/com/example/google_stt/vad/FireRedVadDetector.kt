/*
 * 파일명: vad/FireRedVadDetector.kt
 * 목적 및 기능:
 * - FireRedVAD(Xiaohongshu, Apache-2.0) 비스트리밍 모델을 ONNX Runtime 으로 실행해 말 구간을 찾는다.
 *     ① Kaldi fbank 80(povey 창, knf 기본값, int16 스케일) → ② CMVN(cmvn.ark: (x - mean) × 1/std)
 *     → ③ ONNX(feat (1, T, 80) → probs (1, T, 1)) → ④ 공식 VadPostprocessor 규칙으로 구간 결정
 * - ④ 는 FireRedVAD fireredvad/core/vad_postprocessor.py 를 그대로 옮겼다(커밋 c30ec49 확인).
 *     _smooth_prob → _apply_threshold → _smooth_preds_with_state_machine → _fix_smooth_window_start
 *     → _merge_short_silence_segments → _extend_speech_segments → _split_long_speech_segments → decision_to_segment
 * - 긴 입력은 공식과 같이 chunk_max_frame(30000 프레임 = 300 s)씩 따로 추론해 이어 붙인다.
 *
 * 모델 파일(assets, tools/download_models.sh 가 받는다 — 저장소 pretrained_models/onnx_models):
 *   change-hyungchul-20261010-1715 : 저장소 경로 표기 수정(examples/ 아래가 아니라 저장소 루트)
 *   fireredvad_vad.onnx  ← fireredvad_vad.onnx
 *   fireredvad_cmvn.ark  ← cmvn.ark (Kaldi 이진 double 행렬 2 × 81)
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 목적: FireRedVAD 실행기.
 * 입력(생성자): context — assets 에서 모델·CMVN 을 읽을 Context
 */
class FireRedVadDetector(context: Context) : VadDetector {

    companion object {
        const val MODEL_ASSET = "fireredvad_vad.onnx"     // 비스트리밍 VAD 모델
        const val CMVN_ASSET = "fireredvad_cmvn.ark"      // Kaldi CMVN 통계
        private const val CHUNK_MAX_FRAME = 30000         // 공식 chunk_max_frame(300 s)
        private const val FRAME_SHIFT_S = 0.010           // 공식 FRAME_SHIFT_S
        private const val FRAME_LENGTH_S = 0.025          // 공식 FRAME_LENGTH_S
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()
    private val session: OrtSession
    private val inputName: String                                          // 특징 입력 이름
    private val means: DoubleArray                                         // CMVN 평균
    private val istd: DoubleArray                                          // CMVN 1/표준편차
    private val fbank = KaldiFbank(KaldiFbank.Window.POVEY)                // knf 기본 창 = povey

    override val engine: VadEngine = VadEngine.FIRERED                     // 엔진 종류
    override val label: String = MODEL_ASSET                               // CSV vad_model
    override val runtimeVersion: String = env.version                      // ORT 판
    override val modelSha256: String                                       // 모델 해시

    init {
        val modelBytes = VadAudio.readAsset(context, MODEL_ASSET)
        modelSha256 = VadAudio.sha256(modelBytes)
        sessionOptions.setIntraOpNumThreads(1)
        sessionOptions.setInterOpNumThreads(1)
        session = env.createSession(modelBytes, sessionOptions)
        inputName = session.inputNames.first()                             // 입력은 feat 하나

        val stats = readKaldiDoubleMatrix(VadAudio.readAsset(context, CMVN_ASSET))
        require(stats.size == 2) { "cmvn.ark 는 2행이어야 합니다: ${stats.size}" }
        val dim = stats[0].size - 1                                        // 마지막 열 = count
        require(dim == KaldiFbank.NUM_MEL) { "cmvn.ark 차원이 ${KaldiFbank.NUM_MEL} 이 아닙니다: $dim" }
        val count = stats[0][dim]
        require(count >= 1) { "cmvn.ark count 가 1 미만입니다: $count" }
        means = DoubleArray(dim) { d -> stats[0][d] / count }              // 공식 read_kaldi_cmvn 과 같은 식
        istd = DoubleArray(dim) { d ->
            val variance = maxOf(stats[1][d] / count - means[d] * means[d], 1e-20)   // floor 1e-20
            1.0 / sqrt(variance)
        }
    }

    /**
     * 목적: Kaldi 이진 double 행렬("\u0000B" + "DM " + rows + cols + 값)을 읽는다.
     * 입력: bytes — cmvn.ark 내용
     * 리턴: [rows][cols]
     * 예외: 형식이 다르면 IllegalArgumentException
     */
    private fun readKaldiDoubleMatrix(bytes: ByteArray): Array<DoubleArray> {
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var p = 0
        require(bytes.size > 2 && bytes[0] == 0.toByte() && bytes[1] == 'B'.code.toByte()) {
            "cmvn.ark 가 Kaldi 이진 형식이 아닙니다."
        }
        p = 2                                                              // "\u0000B" 이진 표식 다음
        val token = String(bytes, p, 3, Charsets.US_ASCII); p += 3          // "DM "
        require(token == "DM ") { "cmvn.ark 가 double 행렬(DM)이 아닙니다: '$token'" }
        require(bytes[p].toInt() == 4) { "행 수 크기 표식 오류" }; p += 1
        val rows = bb.getInt(p); p += 4
        require(bytes[p].toInt() == 4) { "열 수 크기 표식 오류" }; p += 1
        val cols = bb.getInt(p); p += 4
        return Array(rows) { r -> DoubleArray(cols) { c -> bb.getDouble(p + (r * cols + c) * 8) } }
    }

    /**
     * 목적: PCM 1건에서 말 구간을 찾는다.
     * 입력: pcm16Le — 16 kHz/mono/PCM16 LE, setting — FIRERED_* 프리셋 또는 Custom
     * 리턴: FrameSegmenter.Result(raw = padded — extend_speech 가 이미 반영된 구간)
     */
    @Synchronized
    override fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result {
        val total = pcm16Le.size / 2
        val wav = VadAudio.toFloat(pcm16Le)
        for (i in wav.indices) wav[i] *= 32768f                            // int16 스케일(공식: sf.read dtype=int16)
        val feats = fbank.compute(wav)                                     // [T][80]
        if (feats.isEmpty()) return FrameSegmenter.Result(emptyList(), emptyList())
        for (row in feats) for (d in row.indices) row[d] = ((row[d] - means[d]) * istd[d]).toFloat()  // CMVN

        val probs = infer(feats)                                           // 프레임별 말 확률
        val p = Post(
            smoothWindow = maxOf(1, setting[VadKeys.SMOOTH_WINDOW].roundToInt()),
            threshold = setting[VadKeys.THRESHOLD],
            minSpeech = setting[VadKeys.MIN_SPEECH_FRAME].roundToInt(),
            maxSpeech = setting[VadKeys.MAX_SPEECH_FRAME].roundToInt(),
            minSilence = setting[VadKeys.MIN_SILENCE_FRAME].roundToInt(),
            mergeSilence = setting[VadKeys.MERGE_SILENCE_FRAME].roundToInt(),
            extendSpeech = setting[VadKeys.EXTEND_SPEECH_FRAME].roundToInt(),
        )
        val dur = total / 16000.0                                          // 음원 길이(초)
        val segSec = p.decisionToSegment(p.process(probs), dur)            // 공식 순서 그대로
        val segs = segSec.mapNotNull { (s, e) ->                           // 초 → 샘플
            FrameSegmenter.toSegment((s * 16000).roundToInt(), (e * 16000).roundToInt(), total)
        }
        return FrameSegmenter.Result(raw = segs, padded = segs)
    }

    /**
     * 목적: ONNX 추론(30000 프레임 초과 시 공식처럼 나눠서 돌리고 이어 붙인다).
     * 입력: feats — CMVN 까지 끝난 [T][80]
     * 리턴: 길이 T 의 확률
     */
    private fun infer(feats: Array<FloatArray>): DoubleArray {
        val t = feats.size
        val out = DoubleArray(t)
        var start = 0
        while (start < t) {
            val n = minOf(CHUNK_MAX_FRAME, t - start)
            val buf = FloatArray(n * KaldiFbank.NUM_MEL)
            for (i in 0 until n) System.arraycopy(feats[start + i], 0, buf, i * KaldiFbank.NUM_MEL, KaldiFbank.NUM_MEL)
            OnnxTensor.createTensor(env, FloatBuffer.wrap(buf), longArrayOf(1, n.toLong(), KaldiFbank.NUM_MEL.toLong())).use { x ->
                session.run(mapOf(inputName to x)).use { res ->
                    val pb = (res[0] as OnnxTensor).floatBuffer            // (1, n, 1)
                    for (i in 0 until n) out[start + i] = pb.get(i).toDouble()
                }
            }
            start += n
        }
        return out
    }

    /**
     * 목적: 공식 VadPostprocessor 를 그대로 옮긴 후처리기.
     * 입력(생성자): 공식 FireRedVadConfig 의 각 값(프레임 단위)
     */
    private class Post(
        val smoothWindow: Int,
        val threshold: Double,
        val minSpeech: Int,
        val maxSpeech: Int,
        val minSilence: Int,
        val mergeSilence: Int,
        val extendSpeech: Int,
    ) {
        /** 공식 process(): 확률 → 프레임 판정(0/1) */
        fun process(raw: DoubleArray): IntArray {
            if (raw.isEmpty()) return IntArray(0)
            val smoothed = smooth(raw)                                    // _smooth_prob
            val binary = IntArray(raw.size) { if (smoothed[it] >= threshold) 1 else 0 }   // _apply_threshold
            val decisions = stateMachine(binary)                          // _smooth_preds_with_state_machine
            val fixed = fixSmoothWindowStart(decisions)                   // _fix_smooth_window_start
            val merged = mergeShortSilence(fixed)                         // _merge_short_silence_segments
            val extended = extend(merged)                                 // _extend_speech_segments
            return splitLong(extended, raw)                               // _split_long_speech_segments
        }

        /** _smooth_prob: np.convolve(full)[:len] 이동 평균 + 앞쪽은 누적 평균 */
        private fun smooth(p: DoubleArray): DoubleArray {
            if (smoothWindow <= 1) return p.copyOf()
            val n = p.size
            val out = DoubleArray(n)
            var sum = 0.0
            for (i in 0 until n) {                                        // full 합성곱의 앞 n 개 = 직전 w 개 합 / w
                sum += p[i]
                if (i >= smoothWindow) sum -= p[i - smoothWindow]
                out[i] = sum / smoothWindow
            }
            var acc = 0.0
            for (i in 0 until minOf(smoothWindow - 1, n)) {               // 처음 w-1 개는 누적 평균
                acc += p[i]
                out[i] = acc / (i + 1)
            }
            return out
        }

        /** _smooth_preds_with_state_machine: min_speech / min_silence 로 상태 전이 제한 */
        private fun stateMachine(b: IntArray): IntArray {
            if (minSpeech <= 0 && minSilence <= 0) return b.copyOf()
            val d = IntArray(b.size)
            var state = 0                                                 // 0 SILENCE, 1 POSSIBLE_SPEECH, 2 SPEECH, 3 POSSIBLE_SILENCE
            var speechStart = -1
            var silenceStart = -1
            for (t in b.indices) {
                val isSpeech = b[t] == 1
                when (state) {
                    0 -> if (isSpeech) { state = 1; speechStart = t }
                    1 -> if (isSpeech) {
                        if (t - speechStart >= minSpeech) {
                            state = 2
                            for (k in speechStart until t) d[k] = 1        // 앞쪽 프레임을 말로 소급
                        }
                    } else { state = 0; speechStart = -1 }
                    2 -> if (!isSpeech) { state = 3; silenceStart = t }
                    3 -> if (!isSpeech) {
                        if (t - silenceStart >= minSilence) { state = 0; speechStart = -1 }
                    } else { state = 2; silenceStart = -1 }
                }
                d[t] = if (state == 2 || state == 3) 1 else 0             // 현재 프레임 판정
            }
            return d
        }

        /** _fix_smooth_window_start: 말 시작 앞 smooth_window 프레임을 말로 보정 */
        private fun fixSmoothWindowStart(d: IntArray): IntArray {
            val n = d.copyOf()
            for (t in 1 until d.size) {
                if (d[t - 1] == 0 && d[t] == 1) {
                    val s = maxOf(0, t - smoothWindow)
                    for (k in s until t) n[k] = 1
                }
            }
            return n
        }

        /** _merge_short_silence_segments: merge_silence 보다 짧은 무음을 말로 메움 */
        private fun mergeShortSilence(d: IntArray): IntArray {
            if (mergeSilence <= 0) return d
            val n = d.copyOf()
            var silenceStart = -1
            for (t in 1 until d.size) {
                if (d[t - 1] == 1 && d[t] == 0 && silenceStart == -1) silenceStart = t
                else if (d[t - 1] == 0 && d[t] == 1 && silenceStart != -1) {
                    val len = t - silenceStart
                    if (len < mergeSilence) for (k in silenceStart until t) n[k] = 1
                    silenceStart = -1
                }
            }
            return n
        }

        /** _extend_speech_segments: np.convolve(same, ones(2e+1)) > 0 — 말 앞뒤로 e 프레임 늘림 */
        private fun extend(d: IntArray): IntArray {
            if (extendSpeech <= 0) return d
            val n = IntArray(d.size)
            for (t in d.indices) {
                if (d[t] == 1) {
                    val s = maxOf(0, t - extendSpeech)
                    val e = minOf(d.size - 1, t + extendSpeech)
                    for (k in s..e) n[k] = 1
                }
            }
            return n
        }

        /** _split_long_speech_segments: max_speech 초과 구간을 원 확률 최저 프레임에서 1프레임 끊음 */
        private fun splitLong(d: IntArray, probs: DoubleArray): IntArray {
            val n = d.copyOf()
            for ((startS, endS) in decisionToSegment(d, null)) {
                val startF = (startS / FRAME_SHIFT_S).toInt()              // 공식: int(start_s / 0.010)
                val endRaw = (endS / FRAME_SHIFT_S).toInt()                // 공식 dur_frames 는 자르기 전 값으로 판단
                val endF = minOf(endRaw, probs.size)                       // 공식 probs[start:end] 슬라이스는 길이에서 잘린다
                if (endRaw - startF > maxSpeech) {
                    for (sp in findSplitPoints(probs, startF, endF)) n[startF + sp] = 0
                }
            }
            return n
        }

        /** _find_split_points: [start + max/2, start + max) 안에서 확률 최저 프레임을 반복해서 찾음 */
        private fun findSplitPoints(probs: DoubleArray, from: Int, to: Int): List<Int> {
            val length = to - from
            val points = ArrayList<Int>()
            var start = 0
            while (start < length) {
                if (length - start <= maxSpeech) break
                val ws = (start + maxSpeech / 2.0).toInt()
                val we = minOf((start + maxSpeech.toDouble()).toInt(), length)
                var minIdx = ws
                for (k in ws until we) if (probs[from + k] < probs[from + minIdx]) minIdx = k   // argmin(첫 최소)
                points.add(minIdx)
                start = minIdx + 1
            }
            return points
        }

        /** decision_to_segment: 판정 → [(시작초, 끝초)] (소수 셋째 자리 반올림) */
        fun decisionToSegment(d: IntArray, wavDur: Double?): List<Pair<Double, Double>> {
            val segs = ArrayList<Pair<Double, Double>>()
            var speechStart = -1
            for (t in d.indices) {
                if (d[t] == 1 && speechStart == -1) speechStart = t
                else if (d[t] == 0 && speechStart != -1) {
                    segs.add((speechStart * FRAME_SHIFT_S) to (t * FRAME_SHIFT_S))
                    speechStart = -1
                }
            }
            if (speechStart != -1) {
                var endTime = d.size * FRAME_SHIFT_S + FRAME_LENGTH_S       // 끝까지 말이면 마지막 프레임 길이까지
                if (wavDur != null) endTime = minOf(endTime, wavDur)
                segs.add((speechStart * FRAME_SHIFT_S) to endTime)
            }
            return segs.map { (s, e) -> round3(s) to round3(e) }
        }

        /** Python round(x, 3) 근사(소수 셋째 자리) */
        private fun round3(x: Double): Double = Math.round(x * 1000.0) / 1000.0
    }

    /** 배치 끝 정리 */
    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }
}
