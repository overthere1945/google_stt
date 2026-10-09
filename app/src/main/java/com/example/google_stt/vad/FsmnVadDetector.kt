/*
 * 파일명: vad/FsmnVadDetector.kt
 * 목적 및 기능:
 * - FunASR FSMN-VAD(ONNX, Apache-2.0)를 ONNX Runtime 으로 실행해 말 구간을 찾는다.
 *     ① Kaldi fbank 80(hamming) → ② LFR(m=5, n=1, 앞 2프레임 복사) → ③ CMVN(am.mvn: (x + shift) × scale)
 *     → ④ FSMN 인코더(ONNX, 프레임별 248 클래스 확률, 0번 = 무음) → ⑤ E2E VAD 상태 기계
 * - ⑤ 는 FunASR 의 단일 헤더 구현 runtime/llama.cpp/funasr-common/funasr_vad.h 를 그대로 옮겼다.
 *   (그 파일은 "PyTorch fsmn-vad generate() 와 184개 음원에서 1프레임(10 ms) 이내 일치" 로 검증되어 있다)
 *     · 동적 끝 무음(max_end_silence_time = 0, FSMN_DEFAULT): 말 누적 0~10 s 2000 ms → 20 s 1000 → 30 s 800
 *       → 40 s 600 → 50 s 400 → 60 s 200 → 그 뒤 100 ms. 누적은 60 s(6000 프레임) 단위로만 늘어난다.
 *       speech_noise_thres 는 0.5 로 고정된다(FunASR 1.3.4+ 기본 동작, PC funasr 1.4.16 과 같다).
 *     · 고정 끝 무음(max_end_silence_time > 0, FSMN_CONFIG / FSMN_S500): max_end_sil = (값 - 150 ms) / 10 ms,
 *       speech_noise_thres 는 설정값(모델 config 기본 0.6). (FunASR model.py Stats 생성 식과 같다)
 *     · 말 판정: (1 - p_sil) >= p_sil + speech_noise_thres  (model.py: p_speech >= p_sil + thres)
 *     · 창 200 ms(20 프레임) 안의 말 프레임 수 >= 15 → 말 시작, <= 15 → 말 끝 후보
 *     · 시작점은 창 + 200 ms(40 프레임) 앞으로 당기고, 끝점은 lookahead 100 ms 를 반영한다.
 * - ONNX 입력: feats(1, T, 400) + cache 4개(1, 128, 19, 1). 긴 음원은 3000 프레임씩 나눠 cache 를 이어 준다
 *   (FunASR runtime/onnxruntime/src/fsmn-vad.cpp 와 같은 입출력, FSMN 은 왼쪽 문맥만 쓰므로 나눠도 결과가 같다).
 *
 * 모델 파일(assets, tools/download_models.sh 가 받는다):
 *   fsmn_vad_quant.onnx ← ModelScope iic/speech_fsmn_vad_zh-cn-16k-common-onnx 의 model_quant.onnx
 *   fsmn_vad_am.mvn     ← 같은 저장소의 am.mvn
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.nio.FloatBuffer
import kotlin.math.roundToInt

/**
 * 목적: FSMN-VAD 실행기.
 * 입력(생성자): context — assets 에서 모델·CMVN 을 읽을 Context
 */
class FsmnVadDetector(context: Context) : VadDetector {

    companion object {
        const val MODEL_ASSET = "fsmn_vad_quant.onnx"     // ONNX 모델(양자화판)
        const val CMVN_ASSET = "fsmn_vad_am.mvn"          // CMVN 통계(Kaldi nnet 텍스트)
        private const val LFR_M = 5                       // LFR 묶음 프레임 수
        private const val LFR_N = 1                       // LFR 이동 프레임 수
        private const val FEAT_DIM = KaldiFbank.NUM_MEL * LFR_M   // 400
        private const val CACHE_DIM = 128                 // cache 채널
        private const val CACHE_LEN = 19                  // cache 길이(lorder - 1)
        private const val CHUNK_FRAMES = 3000             // 한 번에 넣을 프레임 수(30 s)
        private const val FRAME_MS = 10                   // 프레임 간격(ms)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions = OrtSession.SessionOptions()               // 세션보다 먼저 닫으면 안 된다
    private val session: OrtSession
    private val featInputName: String                                      // 특징 입력 이름
    private val cacheInputNames: List<String>                              // cache 입력 이름(이름순)
    private val shift: FloatArray                                          // CMVN AddShift
    private val scale: FloatArray                                          // CMVN Rescale
    private val fbank = KaldiFbank(KaldiFbank.Window.HAMMING)              // FSMN 은 hamming 창(config.yaml)

    override val engine: VadEngine = VadEngine.FSMN                        // 엔진 종류
    override val label: String = MODEL_ASSET                               // CSV vad_model
    override val runtimeVersion: String = env.version                      // ORT 판
    override val modelSha256: String                                       // 모델 해시

    init {
        val modelBytes = VadAudio.readAsset(context, MODEL_ASSET)          // 없으면 받는 방법을 알려 준다
        modelSha256 = VadAudio.sha256(modelBytes)
        sessionOptions.setIntraOpNumThreads(1)                              // 계측 비교를 위해 단일 스레드
        sessionOptions.setInterOpNumThreads(1)
        session = env.createSession(modelBytes, sessionOptions)

        // 입력 이름을 모양(rank)으로 구분한다: 3차 = 특징, 4차 = cache (이름을 추측하지 않는다)
        val infos = session.inputInfo
        featInputName = infos.entries.firstOrNull { (it.value.info as? TensorInfo)?.shape?.size == 3 }?.key
            ?: error("FSMN ONNX 에 3차 특징 입력이 없습니다: ${infos.keys}")
        cacheInputNames = infos.entries.filter { (it.value.info as? TensorInfo)?.shape?.size == 4 }
            .map { it.key }.sorted()
        require(cacheInputNames.size == 4) { "FSMN ONNX cache 입력이 4개가 아닙니다: ${infos.keys}" }

        val mvn = String(VadAudio.readAsset(context, CMVN_ASSET), Charsets.UTF_8)
        val parsed = parseCmvn(mvn)                                        // am.mvn 읽기
        shift = parsed.first
        scale = parsed.second
        require(shift.size == FEAT_DIM && scale.size == FEAT_DIM) {
            "am.mvn 차원이 $FEAT_DIM 이 아닙니다(shift=${shift.size}, scale=${scale.size})"
        }
    }

    /**
     * 목적: Kaldi nnet 텍스트 am.mvn 에서 <AddShift>/<Rescale> 벡터를 읽는다.
     * 입력: text — am.mvn 내용
     * 리턴: (shift, scale)
     * 비고: FunASR fsmn-vad.cpp LoadCmvn() 과 같은 규칙 — "<LearnRateCoef> 0 [ v1 … vN ]" 줄에서 4번째~끝-1 토큰.
     */
    private fun parseCmvn(text: String): Pair<FloatArray, FloatArray> {
        val lines = text.lines()
        var shiftV: FloatArray? = null
        var scaleV: FloatArray? = null
        var i = 0
        while (i < lines.size) {
            val head = lines[i].trim().split(Regex("\\s+")).firstOrNull() ?: ""
            if ((head == "<AddShift>" || head == "<Rescale>") && i + 1 < lines.size) {
                val tok = lines[i + 1].trim().split(Regex("\\s+"))         // <LearnRateCoef> 0 [ ... ]
                if (tok.firstOrNull() == "<LearnRateCoef>" && tok.size > 4) {
                    val v = tok.subList(3, tok.size - 1).map { it.toFloat() }.toFloatArray()
                    if (head == "<AddShift>") shiftV = v else scaleV = v
                }
                i += 2
                continue
            }
            i++
        }
        return Pair(
            shiftV ?: error("am.mvn 에 <AddShift> 가 없습니다."),
            scaleV ?: error("am.mvn 에 <Rescale> 가 없습니다."),
        )
    }

    /**
     * 목적: PCM 1건에서 말 구간을 찾는다.
     * 입력: pcm16Le — 16 kHz/mono/PCM16 LE, setting — FSMN_* 프리셋 또는 Custom
     * 리턴: FrameSegmenter.Result(raw = padded — FSMN 은 시작 lookback/끝 lookahead 가 이미 구간에 들어 있다)
     */
    @Synchronized
    override fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result {
        val total = pcm16Le.size / 2                                       // 원본 샘플 수
        val wav = VadAudio.toFloat(pcm16Le)                                // [-1, 1)
        for (i in wav.indices) wav[i] *= 32768f                            // FunASR: wav × 32768(int16 스케일)
        val feats = fbank.compute(wav)                                     // [T][80]
        val t = feats.size
        if (t < 1) return FrameSegmenter.Result(emptyList(), emptyList())  // 한 프레임도 안 되면 말 없음

        val silProb = runEncoder(feats)                                    // 프레임별 무음 확률(change-hyungchul-20261009-1626 : LFR·CMVN 을 조각마다 만든다)

        val segsMs = stateMachine(                                         // E2E VAD 상태 기계
            silProb = silProb,
            maxEndSilenceMs = setting[VadKeys.MAX_END_SILENCE_MS].roundToInt(),
            speechNoiseThres = setting[VadKeys.SPEECH_NOISE_THRES].toFloat(),
            maxSegMs = setting[VadKeys.MAX_SINGLE_SEGMENT_MS].roundToInt(),
        )
        val segs = segsMs.mapNotNull { (s, e) ->                           // ms → 샘플
            FrameSegmenter.toSegment(s * 16, e * 16, total)
        }
        return FrameSegmenter.Result(raw = segs, padded = segs)
    }

    /**
     * 목적: LFR(m=5, n=1) 과 CMVN 을 적용한 [n × 400] 특징을 프레임 [start, start+n) 만큼 만든다(funasr_vad.h lfr() 와 같다).
     * 입력: feats — [T][80], start — 시작 LFR 프레임, n — 만들 프레임 수
     * 리턴: 길이 n × 400 의 평탄화 배열
     * 비고: change-hyungchul-20261009-1626 — 예전에는 파일 전체(T × 400)를 한 번에 만들어 긴 음원에서 메모리가 컸다(검토 지적 I). LFR_N = 1 이라
     *       LFR 프레임 i 는 fbank 프레임 i-2 ~ i+2 만 보므로 조각으로 나눠 만들어도 값이 같다.
     */
    private fun buildLfrCmvn(feats: Array<FloatArray>, start: Int, n: Int): FloatArray {
        val t = feats.size
        val pad = (LFR_M - 1) / 2                                          // 앞쪽 복사 프레임 수(2)
        val out = FloatArray(n * FEAT_DIM)
        for (k in 0 until n) {
            val i = start + k                                              // LFR 프레임 번호
            for (j in 0 until LFR_M) {
                val src = (i * LFR_N + j - pad).coerceIn(0, t - 1)         // 앞은 첫 프레임, 뒤는 마지막 프레임 복사
                System.arraycopy(feats[src], 0, out, (k * LFR_M + j) * KaldiFbank.NUM_MEL, KaldiFbank.NUM_MEL)
            }
        }
        for (k in 0 until n) {                                             // CMVN: (x + shift) × scale
            val base = k * FEAT_DIM
            for (d in 0 until FEAT_DIM) out[base + d] = (out[base + d] + shift[d]) * scale[d]
        }
        return out
    }

    /**
     * 목적: FSMN 인코더를 3000 프레임씩 돌려 프레임별 무음 확률(출력 0번 클래스)을 모은다.
     * 입력: feats — fbank [T][80] (change-hyungchul-20261009-1626 : LFR·CMVN 은 조각마다 buildLfrCmvn 으로 만든다)
     * 리턴: 길이 T 의 무음 확률
     */
    private fun runEncoder(feats: Array<FloatArray>): FloatArray {
        val t = (feats.size + LFR_N - 1) / LFR_N                           // LFR 프레임 수(= T)
        val sil = FloatArray(t)
        var caches = List(4) { FloatArray(CACHE_DIM * CACHE_LEN) }        // 처음 cache 는 0
        var start = 0
        while (start < t) {
            val n = minOf(CHUNK_FRAMES, t - start)                         // 이번 조각 프레임 수
            val chunk = FloatBuffer.wrap(buildLfrCmvn(feats, start, n))     // change-hyungchul-20261009-1626 : 이 조각만 만든다
            val inputs = LinkedHashMap<String, OnnxTensor>()
            try {
                inputs[featInputName] = OnnxTensor.createTensor(env, chunk, longArrayOf(1, n.toLong(), FEAT_DIM.toLong()))
                cacheInputNames.forEachIndexed { k, name ->
                    inputs[name] = OnnxTensor.createTensor(
                        env, FloatBuffer.wrap(caches[k]), longArrayOf(1, CACHE_DIM.toLong(), CACHE_LEN.toLong(), 1),
                    )
                }
                session.run(inputs).use { out ->
                    val prob = (out[0] as OnnxTensor).floatBuffer               // (1, n, 248)
                    val dim = (out[0] as OnnxTensor).info.shape[2].toInt()
                    for (f in 0 until n) sil[start + f] = prob.get(f * dim)     // 0번 = 무음
                    // 다음 조각을 위해 cache 출력(1~4번)을 이어 받는다
                    caches = (1..4).map { k ->
                        val b = (out[k] as OnnxTensor).floatBuffer
                        FloatArray(CACHE_DIM * CACHE_LEN).also { b.get(it) }
                    }
                }
            } finally {
                inputs.values.forEach { runCatching { it.close() } }       // 입력 텐서 정리
            }
            start += n
        }
        return sil
    }

    /**
     * 목적: FunASR funasr_vad.h 의 E2EVadModel 상태 기계를 그대로 옮긴다.
     * 입력: silProb — 프레임별 무음 확률, maxEndSilenceMs — 0 이면 동적 기준, speechNoiseThres — 고정 모드 기준,
     *       maxSegMs — 최대 구간 길이(ms)
     * 리턴: [start_ms, end_ms] 목록
     */
    private fun stateMachine(
        silProb: FloatArray,
        maxEndSilenceMs: Int,
        speechNoiseThres: Float,
        maxSegMs: Int,
    ): List<Pair<Int, Int>> {
        val t = silProb.size
        val dynamic = maxEndSilenceMs <= 0                                 // 0 = 동적 끝 무음 기준
        val thres = if (dynamic) 0.5f else speechNoiseThres                // 동적이면 0.5 고정(FunASR 동작)
        val win = 20                                                       // window_size_ms 200 / 10
        val s2s = 15                                                       // sil_to_speech 150 / 10
        val sp2s = 15                                                      // speech_to_sil 150 / 10
        val lookaheadEnd = 100 / FRAME_MS                                  // lookahead_time_end_point 100 / 10
        val maxSeg = (if (maxSegMs > 0) maxSegMs else 60000) / FRAME_MS    // max_single_segment 프레임
        val startLookback = win + 200 / FRAME_MS                           // LatencyFrmNumAtStartPoint = 40
        val chunk = 60000 / FRAME_MS                                       // 동적 누적 단위(60 s)
        var acc = 0                                                        // 누적 ms
        var insp = false                                                   // 말 안에 들어간 적 있는지(누적 래치)
        var maxEndSil = 0                                                  // 끝 무음 프레임 기준
        var endLookback = 0                                                // 끝점 되돌림 프레임

        fun recompute() {                                                  // 끝 무음 기준 다시 계산
            val s = if (!dynamic) maxEndSilenceMs else when {
                acc <= 10000 -> 2000
                acc <= 20000 -> 1000
                acc <= 30000 -> 800
                acc <= 40000 -> 600
                acc <= 50000 -> 400
                acc <= 60000 -> 200
                else -> 100
            }
            // change-hyungchul-20261009-1626 : FunASR 는 csil*10 >= (끝 무음 - 150) 으로 비교한다 → 프레임 기준은 올림(10 의 배수가 아닌 Custom 값 대응, 검토 지적 B)
            val endSilMs = maxOf(s - 150, 0)                                // (끝 무음 - speech_to_sil) ms
            maxEndSil = (endSilMs + FRAME_MS - 1) / FRAME_MS               // 올림 프레임
            endLookback = maxOf(endSilMs / FRAME_MS - lookaheadEnd - 1, 0)
        }
        recompute()

        val wbuf = IntArray(win)                                           // 최근 20 프레임 판정
        var wpos = 0
        var wsum = 0
        var pre = 0                                                        // 창 판정 이전 상태(0 무음, 1 말)
        var st = 0                                                         // 0 = 시작 전, 1 = 말 구간 안
        var cstart = -1                                                    // 현재 구간 시작 프레임
        var csil = 0                                                       // 연속 무음 프레임
        var prevEnd = 0                                                    // 직전 구간 끝(겹침 방지)
        val segs = ArrayList<Pair<Int, Int>>()

        fun reset() {                                                      // 구간을 내보낸 뒤 상태 초기화
            wbuf.fill(0); wpos = 0; wsum = 0; pre = 0; csil = 0; st = 0; cstart = -1
            acc = 0; insp = false                                          // 누적만 0 으로(기준 재계산은 60 s 경계에서만 — 원본과 같다)
        }
        fun emit(s0: Int, e0: Int) {                                       // 구간 하나 내보내기
            val s = maxOf(s0, prevEnd, 0)
            val e = minOf(e0, t)
            if (e > s) { segs.add(s to e); prevEnd = e }
        }

        for (f in 0 until t) {
            if (dynamic && f > 0 && f % chunk == 0) {                      // 60 s 경계마다 누적 갱신
                if (st == 1 || insp) { acc += 60000; insp = true }
                recompute()
            }
            val sil = silProb[f]
            val fs = if ((1.0f - sil) >= sil + thres) 1 else 0             // 프레임 말 판정
            wsum -= wbuf[wpos]; wsum += fs; wbuf[wpos] = fs; wpos = (wpos + 1) % win
            val ch = when {                                                 // 창 상태 변화
                pre == 0 && wsum >= s2s -> { pre = 1; 3 }                   // 무음 → 말
                pre == 1 && wsum <= sp2s -> { pre = 0; 1 }                  // 말 → 무음
                else -> if (pre == 0) 0 else 2                              // 유지
            }
            if (ch == 3) {
                csil = 0
                if (st == 0) {
                    cstart = maxOf(f - startLookback, prevEnd, 0)          // 시작점은 40 프레임 앞으로
                    st = 1
                } else if (st == 1 && f - cstart + 1 > maxSeg) {
                    emit(cstart, f); reset()
                }
            } else if (ch == 1 || ch == 2) {
                csil = 0
                if (st == 1 && f - cstart + 1 > maxSeg) { emit(cstart, f); reset() }
            } else {
                csil++
                if (st == 1) {
                    if (csil >= maxEndSil) { emit(cstart, f - endLookback); reset() }
                    else if (f - cstart + 1 > maxSeg) { emit(cstart, f); reset() }
                }
            }
        }
        if (st == 1) emit(cstart, t)                                       // 끝까지 말이면 마지막 구간
        return segs.map { (s, e) -> (s * FRAME_MS) to (e * FRAME_MS) }      // 프레임 → ms
    }

    /** 배치 끝 정리(OrtEnvironment 는 프로세스 공용이라 닫지 않는다) */
    override fun close() {
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
    }
}
