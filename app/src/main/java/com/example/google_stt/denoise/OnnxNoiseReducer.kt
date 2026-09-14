/*
 * 파일명: denoise/OnnxNoiseReducer.kt
 * 목적 및 기능:
 * - ONNX Runtime 으로 도는 딥러닝 노이즈 저감 3종을 구현한다.
 *     (1) GtcrnNoiseReducer   — 1순위. gtcrn_simple.onnx
 *     (2) DpdfNetNoiseReducer — 2순위 DeepFilterNet 계열. dpdfnet_*.onnx
 *     (3) NsNet2NoiseReducer  — 4순위. nsnet2-20ms-baseline.onnx
 *
 * ★ 각 모델의 입출력 규격은 "추측하지 않고" 실제 .onnx 파일을 내려받아
 *   onnxruntime 으로 열어서 확인한 값이다 (2026-08-26 확인).
 *
 *   GTCRN (gtcrn_simple.onnx, MIT, 523 KB)
 *     입력  mix         float [1, 257, 1, 2]     (real, imag)
 *           conv_cache  float [2, 1, 16, 16, 33]
 *           tra_cache   float [2, 3, 1, 1, 16]
 *           inter_cache float [2, 1, 33, 16]
 *     출력  enh, conv_cache_out, tra_cache_out, inter_cache_out (같은 shape)
 *     메타  sample_rate=16000, n_fft=512, hop_length=256, window_type=hann_sqrt
 *     → 프레임 하나씩 넣고 cache 3개를 되먹이는 스트리밍 모델이다.
 *
 *   DPDFNet (dpdfnet_*.onnx, Apache-2.0, 8.4~14.6 MB)
 *     입력  spec     float [1, 1, 161, 2]
 *           state_in float [state_size]   (모델마다 38256 / 45424 / 52592 / 66928)
 *     출력  spec_e, state_out
 *     메타  n_fft=320, hop_length=160, window_type=vorbis, center=1, pad_mode=reflect,
 *           erb_norm_init(32개), spec_norm_init(96개)
 *     → state 를 0 으로만 채우면 잡음 감쇠가 52.7 dB 인데,
 *       erb_norm_init 을 offset 0, spec_norm_init 을 offset 32 에 넣으면 67.7 dB 로 올라간다.
 *       (실제 음원으로 측정해 확인했고 sherpa-onnx C++ 구현과도 일치한다)
 *
 *   NSNet2 (nsnet2-20ms-baseline.onnx, MIT, 10.3 MB)
 *     입력  [1, T, 161] float — log10(max(|Y|^2, 1e-12))
 *     출력  [1, T, 161] float — bin 별 이득(gain)
 *     → 파일 전체를 한 번에 넣는 오프라인 모델이라 80~90초 파일에 가장 잘 맞는다.
 *     → 공식 스크립트: 20 ms 창(320) / 10 ms hop(160) / sqrt(대칭 Hann),
 *       이득을 [10^(mingain/20), 1.0] 로 자른 뒤 "복소" 스펙트럼에 곱한다(위상 보존).
 *     ※ 입출력 "이름" 은 공식 스크립트가 get_inputs()[0].name 으로 동적으로 얻으므로
 *       문서화된 값이 없다. 이 구현도 세션에서 직접 읽어 쓴다(이름을 하드코딩하지 않는다).
 *
 * add-hyungchul-20260826-1100
 */
package com.example.google_stt.denoise

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * 목적: 같은 shape 로 매 프레임 다시 만들어지는 입력 텐서의 재할당을 없앤다.
 * 비고: ONNX Runtime Java 는 "direct" 버퍼로 만든 텐서는 복사 없이 그 버퍼를 참조한다.
 *       그래서 텐서를 한 번만 만들고 run() 사이에 버퍼 내용만 바꿔 쓰면 된다.
 *       (GTCRN 은 cache 가 프레임당 약 67 KB 라, 매번 새로 만들면 90초 파일에서
 *        수백 MB 를 할당하게 되고 그 GC 시간이 추론 시간 계측을 오염시킨다)
 *       위치(position)를 건드리지 않도록 반드시 절대 인덱스 put 만 쓴다.
 */
private class ReusableTensor(env: OrtEnvironment, val shape: LongArray) : AutoCloseable {
    val size: Int = shape.fold(1L) { a, b -> a * b }.toInt()
    private val buffer: FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    val tensor: OnnxTensor = OnnxTensor.createTensor(env, buffer, shape)

    /** src 의 앞 size 개를 버퍼에 복사한다(절대 인덱스 put 이라 position 이 움직이지 않는다). */
    fun copyFrom(src: FloatArray) {
        val n = if (src.size < size) src.size else size
        for (i in 0 until n) buffer.put(i, src[i])
        for (i in n until size) buffer.put(i, 0f)
    }

    fun set(index: Int, value: Float) = buffer.put(index, value)

    fun fillZero() {
        for (i in 0 until size) buffer.put(i, 0f)
    }

    override fun close() {
        runCatching { tensor.close() }
    }
}

/** ONNX 계열 공통 뼈대: assets 에서 모델을 읽고 세션을 만든다. */
abstract class OnnxNoiseReducerBase(
    context: Context,
    protected val config: NoiseReduceConfig,
    private val assetName: String,
    nFft: Int,
    hop: Int,
    windowType: WindowType,
    center: Boolean,
) : SpectralNoiseReducer(nFft, hop, windowType, center) {

    protected val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val sessionOptions: OrtSession.SessionOptions = OrtSession.SessionOptions()
    protected val session: OrtSession

    /** run_meta.csv 에 남길 ONNX Runtime 버전 */
    val runtimeVersion: String = env.version

    /** run_meta.csv 에 남길 모델 파일 해시 — 어떤 모델로 낸 수치인지 증명한다. */
    val modelSha256: String

    /** 모델에 박혀 있는 메타데이터(있는 모델만). */
    protected val metadata: Map<String, String>

    init {
        val bytes = try {
            context.assets.open(assetName).use { it.readBytes() }
        } catch (e: IOException) {
            throw FileNotFoundException(
                "assets/$assetName 이 없습니다. tools/download_models.sh 를 실행하거나 " +
                    "app/src/main/assets/ 에 모델 파일을 넣어주세요. (원인: ${e.message})",
            )
        }
        modelSha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it) }

        sessionOptions.setInterOpNumThreads(config.numThreads)
        sessionOptions.setIntraOpNumThreads(config.numThreads)
        session = env.createSession(bytes, sessionOptions)
        metadata = runCatching {
            session.metadata.customMetadata.toMap()
        }.getOrDefault(emptyMap())
    }

    override val label: String get() = assetName

    /** 모델 메타데이터에 콤마로 들어 있는 float 배열을 읽는다(없으면 null). */
    protected fun metaFloats(key: String): FloatArray? {
        val raw = metadata[key] ?: return null
        return runCatching {
            raw.split(',').filter { it.isNotBlank() }.map { it.trim().toFloat() }.toFloatArray()
        }.getOrNull()
    }

    override fun close() {
        runCatching { onClose() }
        runCatching { session.close() }
        runCatching { sessionOptions.close() }
        // OrtEnvironment 는 프로세스 공용 singleton 이므로 닫지 않는다.
    }

    protected open fun onClose() = Unit
}

/**
 * 1순위 — GTCRN. 프레임 하나씩 넣고 cache 3개를 되먹이는 스트리밍 모델.
 */
class GtcrnNoiseReducer(context: Context, config: NoiseReduceConfig) : OnnxNoiseReducerBase(
    context, config,
    assetName = NoiseAlgorithm.GTCRN.modelAsset!!,
    nFft = 512, hop = 256, windowType = WindowType.HANN_SQRT, center = false,
) {
    override val algorithm: NoiseAlgorithm = NoiseAlgorithm.GTCRN

    // 입력 텐서는 한 번만 만들고 내용만 갈아 끼운다.
    private val mixT = ReusableTensor(env, longArrayOf(1, 257, 1, 2))
    private val convT = ReusableTensor(env, longArrayOf(2, 1, 16, 16, 33))
    private val traT = ReusableTensor(env, longArrayOf(2, 3, 1, 1, 16))
    private val interT = ReusableTensor(env, longArrayOf(2, 1, 33, 16))

    // 출력 cache 를 잠시 담아 둘 배열(다음 프레임 입력으로 되먹인다).
    private val convBuf = FloatArray(convT.size)
    private val traBuf = FloatArray(traT.size)
    private val interBuf = FloatArray(interT.size)

    private val inputs: MutableMap<String, OnnxTensor> = LinkedHashMap()

    init {
        require(bins == 257) { "GTCRN 은 257 bin 이어야 합니다(현재 $bins)." }
        val names = session.inputNames
        require(names.containsAll(setOf("mix", "conv_cache", "tra_cache", "inter_cache"))) {
            "GTCRN ONNX 입력 이름이 예상과 다릅니다: $names"
        }
        inputs["mix"] = mixT.tensor
        inputs["conv_cache"] = convT.tensor
        inputs["tra_cache"] = traT.tensor
        inputs["inter_cache"] = interT.tensor
    }

    override fun beginFile(frameCount: Int, config: NoiseReduceConfig) {
        // 파일마다 cache 를 0 으로 초기화한다(앞 파일의 상태가 넘어오면 안 된다).
        convT.fillZero()
        traT.fillZero()
        interT.fillZero()
    }

    override fun processFrame(
        re: DoubleArray,
        im: DoubleArray,
        frameIndex: Int,
        config: NoiseReduceConfig,
    ) {
        // [1, 257, 1, 2] 는 bin 순서로 (real, imag) 가 번갈아 놓인 것과 같다.
        for (k in 0 until 257) {
            mixT.set(2 * k, re[k].toFloat())
            mixT.set(2 * k + 1, im[k].toFloat())
        }
        session.run(inputs).use { out ->
            val enh = out[0] as OnnxTensor
            val eb = enh.floatBuffer
            for (k in 0 until 257) {
                re[k] = eb.get(2 * k).toDouble()
                im[k] = eb.get(2 * k + 1).toDouble()
            }
            // cache 3개를 다음 프레임 입력으로 되먹인다.
            (out[1] as OnnxTensor).floatBuffer.get(convBuf, 0, convBuf.size)
            (out[2] as OnnxTensor).floatBuffer.get(traBuf, 0, traBuf.size)
            (out[3] as OnnxTensor).floatBuffer.get(interBuf, 0, interBuf.size)
        }
        convT.copyFrom(convBuf)
        traT.copyFrom(traBuf)
        interT.copyFrom(interBuf)
    }

    override fun onClose() {
        mixT.close(); convT.close(); traT.close(); interT.close()
    }
}

/**
 * 목적: DPDFNet 변형 이름 → assets 파일명.
 * 입력: variant — "baseline" / "2" / "4" / "8"
 * 리턴: assets 안의 파일명
 * 비고: 생성자 인자에서 부르므로 최상위 함수로 둔다.
 */
private fun assetFor(variant: String): String = when (variant.trim().lowercase(Locale.US)) {
    "2" -> "dpdfnet2.onnx"
    "4" -> "dpdfnet4.onnx"
    "8" -> "dpdfnet8.onnx"
    else -> "dpdfnet_baseline.onnx"
}

/**
 * 2순위 — DPDFNet (DeepFilterNet2 계열 개선판, CEVA).
 * 업스트림 DeepFilterNet 은 48 kHz 전용에 ONNX 3개로 갈라져 있고 공식 export 배포본도 없어서,
 * 16 kHz 네이티브 단일 ONNX 인 DPDFNet 을 이 자리에 넣었다.
 */
class DpdfNetNoiseReducer(context: Context, config: NoiseReduceConfig) : OnnxNoiseReducerBase(
    context, config,
    assetName = assetFor(config.dpdfnetVariant),
    nFft = 320, hop = 160, windowType = WindowType.VORBIS, center = true,
) {
    override val algorithm: NoiseAlgorithm = NoiseAlgorithm.DPDFNET

    private val stateSize: Int
    private val specT = ReusableTensor(env, longArrayOf(1, 1, 161, 2))
    private val stateT: ReusableTensor
    private val stateBuf: FloatArray
    private val initState: FloatArray
    private val inputs: MutableMap<String, OnnxTensor> = LinkedHashMap()

    init {
        require(bins == 161) { "DPDFNet 은 161 bin 이어야 합니다(현재 $bins)." }
        val names = session.inputNames.toList()
        require(names.size >= 2) { "DPDFNet ONNX 입력이 2개여야 합니다: $names" }
        // state_in 의 실제 길이를 모델에서 읽는다(모델 변형마다 다르다).
        val info = session.inputInfo["state_in"]
            ?: throw IllegalStateException("DPDFNet ONNX 에 state_in 입력이 없습니다: $names")
        val shape = (info.info as ai.onnxruntime.TensorInfo).shape
        stateSize = shape.fold(1L) { a, b -> a * b }.toInt()
        stateT = ReusableTensor(env, longArrayOf(stateSize.toLong()))
        stateBuf = FloatArray(stateSize)

        // ★ state 초기값: 0 으로만 채우지 않고 메타데이터의 정규화 초기값을 심는다.
        //   erb_norm_init 을 offset 0, spec_norm_init 을 그 바로 뒤(offset 32)에 넣는다.
        //   실제 음원 측정 결과 잡음 감쇠가 52.7 dB → 67.7 dB 로 좋아진다.
        initState = FloatArray(stateSize)
        val erb = metaFloats("erb_norm_init")
        val spec = metaFloats("spec_norm_init")
        if (erb != null) {
            System.arraycopy(erb, 0, initState, 0, minOf(erb.size, stateSize))
            if (spec != null && erb.size + spec.size <= stateSize) {
                System.arraycopy(spec, 0, initState, erb.size, spec.size)
            }
        }
        inputs["spec"] = specT.tensor
        inputs["state_in"] = stateT.tensor
    }

    override fun beginFile(frameCount: Int, config: NoiseReduceConfig) {
        stateT.copyFrom(initState)     // 파일마다 초기 state 로 되돌린다
    }

    override fun processFrame(
        re: DoubleArray,
        im: DoubleArray,
        frameIndex: Int,
        config: NoiseReduceConfig,
    ) {
        for (k in 0 until 161) {
            specT.set(2 * k, re[k].toFloat())
            specT.set(2 * k + 1, im[k].toFloat())
        }
        session.run(inputs).use { out ->
            val eb = (out[0] as OnnxTensor).floatBuffer
            for (k in 0 until 161) {
                re[k] = eb.get(2 * k).toDouble()
                im[k] = eb.get(2 * k + 1).toDouble()
            }
            (out[1] as OnnxTensor).floatBuffer.get(stateBuf, 0, stateSize)
        }
        stateT.copyFrom(stateBuf)
    }

    override fun onClose() {
        specT.close(); stateT.close()
    }

}

/**
 * 4순위 — NSNet2 (Microsoft DNS Challenge baseline).
 * 파일 전체를 한 번에 추론하는 오프라인 모델이라 80~90초 파일에 특히 잘 맞는다.
 * 사전 패스에서 전체 이득을 구해 두고, 본 루프에서는 곱하기만 한다.
 */
class NsNet2NoiseReducer(context: Context, config: NoiseReduceConfig) : OnnxNoiseReducerBase(
    context, config,
    assetName = NoiseAlgorithm.NSNET2.modelAsset!!,
    nFft = 320, hop = 160, windowType = WindowType.HANN_SQRT_SYMMETRIC, center = false,
) {
    override val algorithm: NoiseAlgorithm = NoiseAlgorithm.NSNET2

    /** 프레임별 이득 [frameCount * bins] */
    private var gains: FloatArray = FloatArray(0)
    private var gainFrames = 0

    /** 입력/출력 이름은 하드코딩하지 않고 세션에서 읽는다(공식 스크립트와 같은 방식). */
    private val inputName: String = session.inputNames.first()

    init {
        require(bins == 161) { "NSNet2 는 161 bin 이어야 합니다(현재 $bins)." }
    }

    override fun beginFile(frameCount: Int, config: NoiseReduceConfig) {
        gainFrames = 0
    }

    override fun prePass(
        padded: DoubleArray,
        frameCount: Int,
        config: NoiseReduceConfig,
    ): PrePassCost {
        if (frameCount <= 0) return PrePassCost()

        // ── 1) 전체 프레임의 log10 파워 스펙트럼을 만든다 ──
        var t0 = System.nanoTime()
        val re = DoubleArray(bins)
        val im = DoubleArray(bins)
        val feat = FloatArray(frameCount * bins)
        for (f in 0 until frameCount) {
            stft.analyze(padded, f, re, im)
            val base = f * bins
            for (k in 0 until bins) {
                // 공식: np.log10(np.maximum(np.abs(Spec)**2, 1e-12))
                val p = max(re[k] * re[k] + im[k] * im[k], 1e-12)
                feat[base + k] = (ln(p) / LN10).toFloat()
            }
        }
        val stftNs = System.nanoTime() - t0

        // ── 2) [1, T, 161] 로 한 번에 추론한다 ──
        t0 = System.nanoTime()
        gains = FloatArray(frameCount * bins)
        OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(feat),
            longArrayOf(1, frameCount.toLong(), bins.toLong()),
        ).use { input ->
            session.run(mapOf(inputName to input)).use { out ->
                val ob = (out[0] as OnnxTensor).floatBuffer
                require(ob.remaining() >= gains.size) {
                    "NSNet2 출력 크기가 예상과 다릅니다: ${ob.remaining()} < ${gains.size}"
                }
                ob.get(gains, 0, gains.size)
            }
        }
        gainFrames = frameCount
        val inferNs = System.nanoTime() - t0
        return PrePassCost(stftNs = stftNs, inferNs = inferNs)
    }

    override fun processFrame(
        re: DoubleArray,
        im: DoubleArray,
        frameIndex: Int,
        config: NoiseReduceConfig,
    ) {
        if (frameIndex >= gainFrames) return
        // 공식: Gain = clip(Gain, 10^(mingain/20), 1.0) 후 "복소" 스펙트럼에 곱한다(위상 보존).
        val minG = 10.0.pow(config.minGainDb / 20.0)
        val base = frameIndex * bins
        for (k in 0 until bins) {
            var g = gains[base + k].toDouble()
            if (g.isNaN()) g = 1.0
            if (g < minG) g = minG
            if (g > 1.0) g = 1.0
            re[k] *= g
            im[k] *= g
        }
    }

    private companion object {
        val LN10 = ln(10.0)
    }
}
