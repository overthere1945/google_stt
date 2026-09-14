/*
 * 파일명: denoise/NoiseReducer.kt
 * 목적 및 기능:
 * - STT 앞단 노이즈 저감 알고리즘을 "런타임에 교체 가능한 플러그인"으로 추상화한다.
 * - 알고리즘 목록/옵션/결과(단계별 소요시간 포함)를 한 곳에 모아 둔다.
 * - 모든 알고리즘이 같은 STFT 루프를 타므로 stft/infer/istft 단계 시간을 공정하게 비교할 수 있다.
 *
 * ★ 조사 결과 요약 (2026-08-26, 실제 ONNX 파일을 내려받아 로딩해 확인)
 *   사용자가 요청한 8종 중 파일(오프라인) 입력에 실제로 적용 가능한 것과 불가능한 것이 갈린다.
 *     1순위 GTCRN            → 가능. gtcrn_simple.onnx (MIT, 523 KB, 16 kHz native)
 *     2순위 DeepFilterNet    → 업스트림은 48 kHz + ONNX 3개 + 공식 export 없음 → 사실상 불가.
 *                              같은 계열(DeepFilterNet2 개선판)인 DPDFNet 으로 대체한다.
 *                              dpdfnet_*.onnx (Apache-2.0, 16 kHz native, 단일 파일)
 *     3순위 WebRTC APM       → 불가. Kotlin 에서 쓸 수 있는 Maven 아티팩트가 없다(NDK C++ 빌드 필요).
 *     4순위 NSNet2           → 가능. nsnet2-20ms-baseline.onnx (MIT, 10.3 MB, 16 kHz)
 *     5순위 NoiseSuppressor  → 불가. android.media.audiofx.NoiseSuppressor 는 AudioRecord
 *                              audioSession 전용이라 파일/버퍼에 적용하는 API 자체가 없다.
 *     6순위 Conv-TasNet/SepFormer → 불가. 배포된 ONNX 없음. SepFormer 는 85초 파일에 메모리·연산 모두 비현실적.
 *     7순위 Spleeter         → 불가. 44.1 kHz 음악 스템 분리기이지 denoiser 가 아니다.
 *     8순위 FRCRN / DCCRN    → 불가. 복소 conv/LSTM 이라 ONNX export 자체가 막혀 배포본이 없다.
 *
 *   불가 5종도 목록에는 남겨 두고, 고르면 "왜 불가한지"를 즉시 알려 준다.
 *   대신 사용자가 첨부한 조사 문서의 10~12번(전통 DSP)을 순수 Kotlin 으로 구현해 넣었다.
 *   모델 파일이 전혀 필요 없으므로 다운로드 없이 오늘 바로 A/B 비교를 시작할 수 있다.
 *
 * add-hyungchul-20260826-1100
 */
package com.example.google_stt.denoise

import android.content.Context
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 알고리즘 구현 방식. UI 에서 어떤 옵션을 활성화할지 결정하는 데도 쓴다. */
enum class NoiseAlgorithmKind {
    /** 전처리를 하지 않는다(기존 baseline). */
    NONE,

    /** ONNX Runtime 으로 도는 딥러닝 모델. assets 에 모델 파일이 필요하다. */
    ONNX,

    /** 순수 Kotlin 전통 DSP. 모델 파일이 필요 없다. */
    DSP,

    /** 파일 입력에는 적용할 수 없는 항목(사유를 표시하고 실행을 막는다). */
    UNSUPPORTED,
}

/**
 * 앱에서 고를 수 있는 노이즈 저감 알고리즘.
 * displayName 앞의 숫자는 사용자가 요청한 우선순위 번호와 그대로 맞춰 두었다.
 */
enum class NoiseAlgorithm(
    val displayName: String,
    /** 결과 저장 폴더명 조각. null 이면 기존 경로를 그대로 쓴다. */
    val folderName: String?,
    val kind: NoiseAlgorithmKind,
    /** ONNX 계열이 assets 에서 찾는 파일명(변형 모델은 config 로 갈린다). */
    val modelAsset: String? = null,
    /** UNSUPPORTED 인 경우 사용자에게 보여줄 사유. */
    val unsupportedReason: String? = null,
) {
    NONE("사용 안 함 (기존 baseline)", null, NoiseAlgorithmKind.NONE),

    GTCRN(
        "1. GTCRN (ONNX, 16 kHz, 523 KB)",
        "ns_gtcrn",
        NoiseAlgorithmKind.ONNX,
        modelAsset = "gtcrn_simple.onnx",
    ),

    DPDFNET(
        "2. DPDFNet — DeepFilterNet 계열 (ONNX, 16 kHz)",
        "ns_dpdfnet",
        NoiseAlgorithmKind.ONNX,
        modelAsset = "dpdfnet_baseline.onnx",
    ),

    NSNET2(
        "4. NSNet2 / MS DNS (ONNX, 16 kHz, 10 MB)",
        "ns_nsnet2",
        NoiseAlgorithmKind.ONNX,
        modelAsset = "nsnet2-20ms-baseline.onnx",
    ),

    MMSE_STSA("MMSE-STSA (Kotlin, 모델 불필요)", "ns_mmse", NoiseAlgorithmKind.DSP),

    WIENER("Wiener decision-directed (Kotlin, 모델 불필요)", "ns_wiener", NoiseAlgorithmKind.DSP),

    SPECTRAL_SUB("Spectral Subtraction (Kotlin, 모델 불필요)", "ns_specsub", NoiseAlgorithmKind.DSP),

    WEBRTC_APM(
        "3. WebRTC APM — 파일 입력 불가",
        null,
        NoiseAlgorithmKind.UNSUPPORTED,
        unsupportedReason =
            "Kotlin 에서 바로 쓸 수 있는 Maven/Gradle 아티팩트가 없습니다. " +
                "Maven Central 의 webrtc 패키지들은 전부 PeerConnection 용 전체 스택이라 " +
                "APM 의 노이즈 억제만 오프라인 버퍼에 적용하는 API 를 노출하지 않습니다. " +
                "쓰려면 webrtc-audio-processing 을 NDK(C++)로 직접 빌드하고 JNI 래퍼를 만들어야 하며 " +
                "며칠 규모의 작업입니다. 같은 목적이라면 GTCRN 이 더 좋은 품질을 코드 없이 냅니다.",
    ),

    OS_NOISE_SUPPRESSOR(
        "5. NoiseSuppressor (OS 내장) — 파일 입력 불가",
        null,
        NoiseAlgorithmKind.UNSUPPORTED,
        unsupportedReason =
            "android.media.audiofx.NoiseSuppressor 는 create(audioSession) 생성자 하나뿐이고, " +
                "그 audioSession 은 살아 있는 AudioRecord 에서만 나옵니다. " +
                "공식 문서도 '캡처되는 신호(captured signal)에 붙는 audio pre-processor' 라고 못박고 있어 " +
                "ByteArray/파일을 넣는 API 가 아예 존재하지 않습니다. " +
                "이 앱은 마이크가 아니라 파일을 처리하므로 원천적으로 적용할 수 없습니다.",
    ),

    TASNET_SEPFORMER(
        "6. Conv-TasNet / SepFormer — 파일 입력 불가",
        null,
        NoiseAlgorithmKind.UNSUPPORTED,
        unsupportedReason =
            "16 kHz 음성 향상용으로 내려받을 수 있는 ONNX 배포본이 없습니다(SpeechBrain 은 ONNX export 미지원). " +
                "SepFormer 는 25.7 M 파라미터 / 69.6 GMAC-per-second 라 85초 파일이면 약 5.9 TMAC 이고, " +
                "시간영역 transformer 특성상 중간 텐서만 수백 MB가 되어 휴대폰에서 OOM 이 납니다.",
    ),

    SPLEETER(
        "7. Spleeter — 파일 입력 불가",
        null,
        NoiseAlgorithmKind.UNSUPPORTED,
        unsupportedReason =
            "Spleeter 는 노이즈 제거기가 아니라 44.1 kHz 음악 스템 분리기(보컬/드럼/베이스)입니다. " +
                "'노이즈'라는 개념 자체가 없습니다. 게다가 공식 SavedModel 은 TFLite 변환이 불가하고, " +
                "커뮤니티 변환본은 약 150 MB에 GPU 가속도 안 되며 ONNX 가 아니라 별도 런타임이 필요합니다.",
    ),

    FRCRN_DCCRN(
        "8. FRCRN / DCCRN — 파일 입력 불가",
        null,
        NoiseAlgorithmKind.UNSUPPORTED,
        unsupportedReason =
            "내려받을 수 있는 ONNX 배포본이 없습니다. 두 모델 모두 복소(complex) convolution 과 " +
                "복소 LSTM 을 쓰는데 이 연산들이 torch.onnx.export 에서 그대로 막혀서, " +
                "복소 연산을 실수 2채널로 다시 쓰지 않으면 변환 자체가 되지 않습니다. " +
                "가중치도 ModelScope 에만 있어 PyTorch 형태입니다.",
    ),
    ;

    /** 실제로 실행 가능한 항목인지. */
    val runnable: Boolean get() = kind != NoiseAlgorithmKind.UNSUPPORTED
}

/**
 * 목적: 실행 시점의 노이즈 저감 설정.
 * 비고: 알고리즘마다 쓰는 필드가 다르다. UI 는 알고리즘을 바꿀 때 해당 알고리즘의 기본값으로 다시 채운다.
 */
data class NoiseReduceConfig(
    val algorithm: NoiseAlgorithm,

    /**
     * ★ 과도한 노이즈 제거 방지 장치 (모든 알고리즘 공통).
     * 출력이 원음보다 최대 몇 dB 까지만 작아지도록 원음을 섞어 준다.
     *   lim = 10^(-attenuationLimitDb/20);  y = lim * 원음 + (1-lim) * 처리음
     * DeepFilterNet 의 atten_lim_db 와 같은 방식이며 sherpa-onnx 는 12 dB 를 기본으로 쓴다.
     * 0 이면 제한하지 않는다(알고리즘 출력 그대로).
     */
    val attenuationLimitDb: Double = 0.0,

    /** ONNX 세션 스레드 수. 스트리밍 모델이라 1~2 가 보통 가장 빠르다. */
    val numThreads: Int = 1,

    /** DPDFNet 변형 모델: baseline / 2 / 4 / 8 (뒤로 갈수록 무겁고 조금 더 좋다). */
    val dpdfnetVariant: String = "baseline",

    /** 최소 이득(dB). 이보다 더 깎지 않는다. NSNet2 공식 스크립트 기본값은 -80. */
    val minGainDb: Double = -20.0,

    /** DSP: decision-directed 평활 계수(MMSE-STSA / Wiener). Ephraim-Malah 권장값 0.98. */
    val ddAlpha: Double = 0.98,

    /** DSP: Spectral Subtraction 의 과감산 계수 alpha0 (Berouti). 보통 3~6, 권장 4. */
    val overSubtraction: Double = 4.0,

    /** DSP: Spectral Subtraction 의 스펙트럼 바닥 beta. 0.005~0.06. ASR 앞단은 큰 쪽이 안전하다. */
    val spectralFloor: Double = 0.02,

    /**
     * DSP: 잡음 스펙트럼을 "파일 전체에서 가장 조용한 하위 N% 프레임"으로 추정한다(단위 %).
     * ★ 왜 앞부분 몇 ms 가 아니라 이 방식인가
     *   이 앱은 파일을 통째로 들고 있는 오프라인 처리다. 앞 400 ms 만 보는 고전적 방식은
     *   (1) 파일이 말로 바로 시작하면 음성을 잡음으로 오인하고,
     *   (2) 그 400 ms 구간 자체는 전혀 처리되지 않은 채 남는다.
     *   파일 전체에서 조용한 프레임을 골라 쓰면 두 문제가 모두 사라진다.
     */
    val noiseQuantilePct: Int = 10,
) {
    init {
        require(attenuationLimitDb >= 0.0) { "Attenuation Limit 은 0 이상이어야 합니다(0 = 무제한)." }
        require(numThreads in 1..8) { "Threads 는 1~8 이어야 합니다." }
        require(minGainDb <= 0.0) { "Min Gain 은 0 이하(dB)여야 합니다." }
        require(ddAlpha in 0.0..0.9999) { "alpha 는 0 이상 1 미만이어야 합니다." }
        require(overSubtraction > 0.0) { "과감산 계수는 0보다 커야 합니다." }
        require(spectralFloor in 0.0..1.0) { "스펙트럼 바닥은 0~1 이어야 합니다." }
        require(noiseQuantilePct in 1..90) { "Noise Quantile 은 1~90(%) 이어야 합니다." }
    }

    /** 원음 혼합 비율. 0 이면 처리음 100%. */
    val dryMix: Double
        get() = if (attenuationLimitDb <= 0.0) 0.0 else 10.0.pow(-attenuationLimitDb / 20.0)

    /** 결과 폴더명. Custom 값까지 폴더명에 녹여 여러 조합을 나란히 남길 수 있게 한다. */
    fun outputFolder(): String? {
        val base = algorithm.folderName ?: return null
        val sb = StringBuilder(base)
        if (algorithm == NoiseAlgorithm.DPDFNET) sb.append('_').append(dpdfnetVariant)
        if (attenuationLimitDb > 0.0) sb.append("_lim").append(attenuationLimitDb.roundToInt())
        return sb.toString()
    }

    /** 로그/run_meta 에 남길 한 줄 요약. */
    fun summary(): String = when (algorithm.kind) {
        NoiseAlgorithmKind.ONNX -> buildString {
            append("threads=").append(numThreads)
            if (algorithm == NoiseAlgorithm.DPDFNET) append(", variant=").append(dpdfnetVariant)
            if (algorithm == NoiseAlgorithm.NSNET2) append(", minGain=").append(minGainDb).append("dB")
            append(", attenLimit=").append(if (attenuationLimitDb > 0) "${attenuationLimitDb}dB" else "무제한")
        }

        NoiseAlgorithmKind.DSP -> buildString {
            when (algorithm) {
                NoiseAlgorithm.SPECTRAL_SUB ->
                    append("alpha0=").append(overSubtraction).append(", beta=").append(spectralFloor)

                else -> append("ddAlpha=").append(ddAlpha)
            }
            append(", minGain=").append(minGainDb).append("dB")
            append(", noiseQuantile=").append(noiseQuantilePct).append("%")
            append(", attenLimit=").append(if (attenuationLimitDb > 0) "${attenuationLimitDb}dB" else "무제한")
        }

        else -> "-"
    }

    companion object {
        /**
         * 목적: 알고리즘별 권장 초기값을 만든다(UI 에서 알고리즘을 바꾸면 이 값으로 다시 채운다).
         * 입력: algorithm
         * 리턴: NoiseReduceConfig
         */
        fun defaults(algorithm: NoiseAlgorithm): NoiseReduceConfig = when (algorithm) {
            // 딥러닝 모델은 기본적으로 잘 동작하므로 12 dB 제한만 걸어 과도 제거를 막는다.
            // (sherpa-onnx 가 DPDFNet 에 쓰는 값과 같다)
            NoiseAlgorithm.GTCRN,
            NoiseAlgorithm.DPDFNET,
            -> NoiseReduceConfig(algorithm, attenuationLimitDb = 12.0, numThreads = 1)

            // NSNet2 공식 스크립트는 mingain = -80 dB 다.
            NoiseAlgorithm.NSNET2 ->
                NoiseReduceConfig(algorithm, attenuationLimitDb = 12.0, numThreads = 1, minGainDb = -80.0)

            // Ephraim-Malah 권장값.
            NoiseAlgorithm.MMSE_STSA ->
                NoiseReduceConfig(algorithm, ddAlpha = 0.98, minGainDb = -25.0, noiseQuantilePct = 10)

            NoiseAlgorithm.WIENER ->
                NoiseReduceConfig(algorithm, ddAlpha = 0.98, minGainDb = -20.0, noiseQuantilePct = 10)

            // Berouti 권장값(alpha0=4). ASR 앞단이라 바닥(beta)은 넉넉히 둔다.
            NoiseAlgorithm.SPECTRAL_SUB ->
                NoiseReduceConfig(
                    algorithm,
                    overSubtraction = 4.0,
                    spectralFloor = 0.02,
                    minGainDb = -20.0,
                    noiseQuantilePct = 10,
                )

            else -> NoiseReduceConfig(algorithm)
        }
    }
}

/**
 * 목적: 파일 1건의 노이즈 저감 결과와 단계별 소요 시간을 나른다.
 * 비고: ByteArray 를 담으므로 data class 로 만들지 않는다.
 */
class NoiseReduceResult(
    /** 처리된 16 kHz / mono / PCM16 LE */
    val pcm: ByteArray,
    /** 처리한 STFT 프레임 수 */
    val frames: Int,
    /** PCM→float 변환 + 패딩 등 준비 단계 */
    val prepareMs: Long,
    /** STFT(분석) 누적 시간 */
    val stftMs: Long,
    /** 모델 추론 또는 DSP 이득 계산 누적 시간 */
    val inferMs: Long,
    /** iSTFT(합성·overlap-add) 누적 시간 */
    val istftMs: Long,
    /** 감쇠 제한 적용 + float→PCM 변환 등 마무리 단계 */
    val postMs: Long,
    /** 위 전부를 합한 실제 벽시계 시간 */
    val totalMs: Long,
    /** 입력 RMS(dBFS) */
    val inRmsDb: Double,
    /** 출력 RMS(dBFS) */
    val outRmsDb: Double,
    /** 출력 최대 진폭(1.0 을 넘으면 클리핑이 일어났다는 뜻) */
    val peak: Double,
    /** 알고리즘이 남기는 부가 정보 */
    val note: String = "",
) {
    /** 전체 레벨이 몇 dB 줄었는지. 음수면 오히려 커진 것이다. */
    val reductionDb: Double get() = inRmsDb - outRmsDb

    /** 프레임당 추론 시간(마이크로초). 알고리즘 간 속도 비교의 핵심 지표. */
    val inferPerFrameUs: Double
        get() = if (frames > 0) inferMs * 1000.0 / frames else -1.0
}

/**
 * 목적: 노이즈 저감 알고리즘 공통 인터페이스.
 * 비고: 사용자 프롬프트의 NoiseReducer 규격을 따르되, 입출력을 ShortArray 대신 ByteArray 로 두었다.
 *       기존 AudioDecoder / SileroVadProcessor 가 모두 PCM16 LE ByteArray 로 주고받기 때문에
 *       중간에 ShortArray 로 바꾸면 90초 파일마다 불필요한 복사가 한 번 더 생긴다.
 */
interface NoiseReducer : AutoCloseable {
    /** 이 구현이 담당하는 알고리즘. */
    val algorithm: NoiseAlgorithm

    /** 요구 샘플레이트. 이 앱은 전부 16 kHz 로 통일되어 있다. */
    val requiredSampleRate: Int get() = 16_000

    /** run_meta 에 남길 모델/구현 라벨. */
    val label: String

    /**
     * 목적: PCM 1건을 처리한다.
     * 입력: pcm16Le — 16 kHz / mono / PCM16 LE, config — 실행 설정
     * 리턴: NoiseReduceResult
     * 예외: 모델 파일이 없거나 추론에 실패하면 예외를 던진다(조용히 원음을 돌려주지 않는다).
     */
    fun process(pcm16Le: ByteArray, config: NoiseReduceConfig): NoiseReduceResult
}

/** 전처리를 하지 않는 구현. VAD OFF baseline 과 마찬가지로 PCM 을 1 byte 도 바꾸지 않는다. */
class NoOpNoiseReducer : NoiseReducer {
    override val algorithm: NoiseAlgorithm = NoiseAlgorithm.NONE
    override val label: String = "none"

    override fun process(pcm16Le: ByteArray, config: NoiseReduceConfig): NoiseReduceResult {
        val rms = PcmUtil.rmsDb(pcm16Le)
        return NoiseReduceResult(
            pcm = pcm16Le,           // ★ 새 배열을 만들지 않는다. 원본 그대로 넘긴다.
            frames = 0,
            prepareMs = 0, stftMs = 0, inferMs = 0, istftMs = 0, postMs = 0, totalMs = 0,
            inRmsDb = rms, outRmsDb = rms, peak = PcmUtil.peak(pcm16Le),
            note = "bypass",
        )
    }

    override fun close() = Unit
}

/** PCM16 LE 와 float 사이 변환, 레벨 계산 같은 잡일 모음. */
object PcmUtil {

    /**
     * 목적: PCM16 LE 바이트를 [-1, 1) 범위 Double 배열로 바꾼다.
     * 입력: pcm — PCM16 LE
     * 리턴: DoubleArray(pcm.size / 2)
     */
    fun toDouble(pcm: ByteArray): DoubleArray {
        val out = DoubleArray(pcm.size / 2)
        var bi = 0
        for (i in out.indices) {
            val lo = pcm[bi].toInt() and 0xFF        // little-endian: 낮은 바이트가 먼저
            val hi = pcm[bi + 1].toInt()             // 부호 확장을 위해 마스킹하지 않는다
            out[i] = ((hi shl 8) or lo).toShort().toInt() / 32768.0
            bi += 2
        }
        return out
    }

    /**
     * 목적: Double 배열을 PCM16 LE 로 되돌린다. 범위를 벗어나면 잘라낸다(clipping).
     * 입력: x — [-1, 1) 신호
     * 리턴: PCM16 LE ByteArray
     */
    fun toPcm(x: DoubleArray): ByteArray {
        val out = ByteArray(x.size * 2)
        var bi = 0
        for (v in x) {
            // 32767 로 곱해 반올림한 뒤 short 범위로 자른다.
            var s = Math.round(v * 32767.0).toInt()
            if (s > 32767) s = 32767
            if (s < -32768) s = -32768
            out[bi] = (s and 0xFF).toByte()
            out[bi + 1] = ((s shr 8) and 0xFF).toByte()
            bi += 2
        }
        return out
    }

    /** RMS 레벨(dBFS). 무음이면 -120 을 돌려준다. */
    fun rmsDb(x: DoubleArray): Double {
        if (x.isEmpty()) return -120.0
        var sum = 0.0
        for (v in x) sum += v * v
        val rms = sqrt(sum / x.size)
        return if (rms < 1e-12) -120.0 else 20.0 * log10(rms)
    }

    fun rmsDb(pcm: ByteArray): Double = rmsDb(toDouble(pcm))

    /** 최대 절대 진폭. */
    fun peak(x: DoubleArray): Double {
        var m = 0.0
        for (v in x) {
            val a = if (v < 0) -v else v
            if (a > m) m = a
        }
        return m
    }

    fun peak(pcm: ByteArray): Double = peak(toDouble(pcm))
}

/** 알고리즘 → 구현 생성. */
object NoiseReducerFactory {

    /**
     * 목적: 선택된 알고리즘의 구현을 만든다. 배치 시작 시 1회만 호출한다.
     * 입력: context(assets 접근용), config
     * 리턴: NoiseReducer
     * 예외: UNSUPPORTED 항목이면 IllegalArgumentException(사유 포함),
     *       ONNX 모델 파일이 없으면 FileNotFoundException
     */
    fun create(context: Context, config: NoiseReduceConfig): NoiseReducer {
        val algo = config.algorithm
        if (!algo.runnable) {
            throw IllegalArgumentException(
                "${algo.displayName} 은(는) 이 앱의 파일 입력 방식에 적용할 수 없습니다.\n" +
                    (algo.unsupportedReason ?: ""),
            )
        }
        return when (algo) {
            NoiseAlgorithm.NONE -> NoOpNoiseReducer()
            NoiseAlgorithm.GTCRN -> GtcrnNoiseReducer(context, config)
            NoiseAlgorithm.DPDFNET -> DpdfNetNoiseReducer(context, config)
            NoiseAlgorithm.NSNET2 -> NsNet2NoiseReducer(context, config)
            NoiseAlgorithm.MMSE_STSA -> MmseStsaNoiseReducer()
            NoiseAlgorithm.WIENER -> WienerNoiseReducer()
            NoiseAlgorithm.SPECTRAL_SUB -> SpectralSubtractionNoiseReducer()
            else -> throw IllegalArgumentException("알 수 없는 알고리즘: $algo")
        }
    }
}
