/*
 * 파일명: vad/VadPipeline.kt
 * 목적 및 기능:
 * - 모든 VAD 엔진이 지켜야 하는 공통 계약(VadDetector)과 공통 처리 단계를 정의한다.
 *     · VadDetector      : PCM → 말 구간(패딩 전/후) — 엔진마다 구현이 다르다
 *     · VadPcmJoiner     : 말 구간만 이어 붙이고 구간 사이에 무음(join)을 넣는다 — 모든 엔진 공통
 *     · VadRunner        : 위 두 단계를 묶어 기존 VadResult(배치 실행기가 쓰던 자료형)로 돌려준다
 *     · VadDetectorFactory : 엔진 종류로 실행기를 만든다(배치 시작 시 1회)
 *     · VadAudio         : PCM16 ↔ float / short 변환
 *
 * add-hyungchul-20261009-1530
 *   SileroVadProcessor.kt 에 있던 collectPcm()(구간 이어 붙이기 + 사이 무음 + EPD 절단 후보 seams)을
 *   VadPcmJoiner.join() 으로 그대로 옮겨 모든 VAD 엔진이 같은 방식으로 STT 입력을 만들게 했다.
 *   (VAD 마다 이어 붙이는 방식이 다르면 WER 차이가 VAD 때문인지 이어 붙이기 때문인지 구분할 수 없다)
 */
package com.example.google_stt.vad

import android.content.Context
import com.example.google_stt.SileroVadProcessor
import com.example.google_stt.VadResult
import com.example.google_stt.VadSegment
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * 목적: VAD 엔진 공통 계약.
 * - label          : CSV vad_model 에 남길 모델/라이브러리 이름
 * - runtimeVersion : 실행 런타임 판(ORT 판, 라이브러리 판)
 * - modelSha256    : 모델 파일 해시(파일이 없는 엔진은 빈 문자열)
 * - detect()       : 16 kHz/mono/PCM16 LE 전체 → 말 구간(원본 샘플 좌표)
 * 비고: 배치 전체에서 1개 인스턴스를 재사용하므로, detect() 는 파일마다 내부 상태를 초기화해야 한다.
 */
interface VadDetector : AutoCloseable {
    val engine: VadEngine                                             // 어느 엔진인지
    val label: String                                                 // 모델/라이브러리 이름
    val runtimeVersion: String                                        // 런타임 판
    val modelSha256: String                                           // 모델 해시

    /**
     * 목적: PCM 1건에서 말 구간을 찾는다.
     * 입력: pcm16Le — 16 kHz/mono/PCM16 LE, setting — 실행 snapshot
     * 리턴: FrameSegmenter.Result(raw = 패딩 전, padded = 패딩 후, 둘 다 원본 샘플 좌표)
     */
    fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result
}

/** PCM 변환·모델 읽기 도우미 */
object VadAudio {
    /**
     * 목적: PCM16 LE 바이트를 [-1, 1) float 로 바꾼다(SileroVadProcessor.pcm16ToFloat 와 같은 식).
     * 입력: pcm — PCM16 LE
     * 리턴: FloatArray(샘플 수 = pcm.size / 2)
     */
    fun toFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        var bi = 0
        for (i in out.indices) {
            val lo = pcm[bi].toInt() and 0xFF                             // little-endian: 낮은 바이트가 먼저
            val hi = pcm[bi + 1].toInt()                                  // 부호 확장을 위해 마스킹하지 않는다
            out[i] = ((hi shl 8) or lo).toShort().toInt() / 32768.0f
            bi += 2
        }
        return out
    }

    /**
     * 목적: PCM16 LE 바이트를 ShortArray 로 바꾼다(WebRTC / TEN 입력용).
     * 입력: pcm — PCM16 LE
     * 리턴: ShortArray
     */
    fun toShort(pcm: ByteArray): ShortArray {
        val out = ShortArray(pcm.size / 2)
        var bi = 0
        for (i in out.indices) {
            val lo = pcm[bi].toInt() and 0xFF                             // 낮은 바이트
            val hi = pcm[bi + 1].toInt()                                  // 높은 바이트(부호 포함)
            out[i] = ((hi shl 8) or lo).toShort()
            bi += 2
        }
        return out
    }

    /**
     * 목적: assets 의 모델 파일을 읽는다. 없으면 받는 방법을 알려 주는 예외를 던진다.
     * 입력: context, asset — assets 상대 경로
     * 리턴: 파일 바이트
     * 예외: FileNotFoundException
     */
    fun readAsset(context: Context, asset: String): ByteArray = try {
        context.assets.open(asset).use { it.readBytes() }
    } catch (e: IOException) {
        throw FileNotFoundException(
            "assets/$asset 이 없습니다. tools/download_models.sh 를 실행한 뒤 다시 빌드하세요. (원인: ${e.message})",
        )
    }

    /** 바이트의 SHA-256 16진 문자열(run_meta 재현성 기록용) */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { String.format(Locale.US, "%02x", it) }
}

/** 말 구간 이어 붙이기(모든 엔진 공통) */
object VadPcmJoiner {

    /**
     * 목적: join() 의 반환 묶음.
     * add-hyungchul-20260914-2130 / 2350 (원래 SileroVadProcessor.JoinedPcm)
     */
    class JoinedPcm(
        val pcm: ByteArray,             // 최종 STT 입력 PCM16 LE
        val silenceSamples: Int,        // 구간 사이에 끼워 넣은 무음 sample 합
        val seams: List<Int>,           // Speech EPD 가 쓸 "안전한 절단 후보" 좌표(출력 PCM 기준)
    )

    /**
     * 목적: 구간에 해당하는 "원본" PCM 바이트를 순서대로 이어 붙이고,
     *       구간과 구간 사이에 joinSilenceMs 만큼 digital silence 를 끼워 넣는다.
     * 입력: pcm — 원본 PCM16 LE, segments — 잘라낼 구간(패딩 후), joinSilenceMs — 사이 무음(ms, 0 이면 삽입 없음)
     * 리턴: JoinedPcm(최종 PCM, 끼워 넣은 무음 sample 수, 절단 후보)
     * 비고: add-hyungchul-20261009-1530 — SileroVadProcessor.collectPcm() 본문을 그대로 옮겼다.
     */
    fun join(
        pcm: ByteArray,
        segments: List<VadSegment>,
        joinSilenceMs: Int,
    ): JoinedPcm {
        if (segments.isEmpty()) return JoinedPcm(ByteArray(0), 0, emptyList())

        // 무음 1회분의 sample / byte 수. PCM16 은 sample 당 2 byte 이고, 0x0000 이 무음이다.
        val gapSamples = (FrameSegmenter.SAMPLE_RATE.toLong() * joinSilenceMs / 1_000L).toInt().coerceAtLeast(0)
        val gapBytes = gapSamples * 2
        val gap = if (gapBytes > 0) ByteArray(gapBytes) else ByteArray(0)  // ByteArray 는 0 으로 초기화된다

        // 대략적인 최종 크기(구간 합 + 무음 (구간수-1) 회분)로 버퍼를 미리 잡아 재할당을 줄인다.
        val estimated = segments.sumOf { (it.endSample - it.startSample).coerceAtLeast(0) * 2 } +
            gapBytes * (segments.size - 1).coerceAtLeast(0)
        val out = ByteArrayOutputStream(estimated)

        var wrote = false            // 실제로 뭔가 쓴 적이 있는지(맨 앞에 무음이 붙는 것을 막는다)
        var insertedGaps = 0         // 실제로 끼워 넣은 무음 횟수
        val seams = ArrayList<Int>() // 절단 후보 좌표
        for (s in segments) {
            val startByte = (s.startSample * 2).coerceIn(0, pcm.size)
            val endByte = (s.endSample * 2).coerceIn(startByte, pcm.size)
            if (endByte <= startByte) continue                 // 빈 구간은 건너뛴다
            if (wrote) {                                       // 두 번째 구간부터
                val seamStart = out.size() / 2                 // 현재 출력 길이(sample) 가 곧 이음매 시작점
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
}

/** 감지 + 이어 붙이기를 묶어 VadResult 로 만든다 */
object VadRunner {
    /**
     * 목적: 파일 1건을 VAD 로 처리한다.
     * 입력: detector — 배치 시작 때 만든 실행기, pcm16Le — 노이즈 저감까지 끝난 PCM, setting — 실행 snapshot
     * 리턴: VadResult(이어 붙인 PCM, 패딩 후 구간, 처리 시간, 길이 계측, 절단 후보)
     * 예외: PCM 바이트 수가 홀수이면 IllegalArgumentException, 엔진 실행 오류는 그대로 던진다
     */
    fun process(detector: VadDetector, pcm16Le: ByteArray, setting: VadSetting): VadResult {
        require(pcm16Le.size % 2 == 0) { "PCM16 은 byte 수가 짝수여야 합니다: ${pcm16Le.size}" }
        val startedNs = System.nanoTime()                                  // 처리시간 계측 시작
        val samples = pcm16Le.size / 2                                     // 원본 샘플 수
        if (samples == 0) return VadResult(ByteArray(0), emptyList(), 0, 0, 0, 0)

        val found = detector.detect(pcm16Le, setting)                      // 엔진별 감지
        val joined = VadPcmJoiner.join(pcm16Le, found.padded, setting.joinMs)   // 공통 이어 붙이기
        val processMs = (System.nanoTime() - startedNs) / 1_000_000

        return VadResult(
            pcm = joined.pcm,
            segments = found.padded,
            processMs = processMs,
            originalSamples = samples,
            outputSamples = joined.pcm.size / 2,
            detectedSpeechSamples = found.raw.sumOf { (it.endSample - it.startSample).coerceAtLeast(0) },
            joinSilenceSamples = joined.silenceSamples,
            seams = joined.seams,
        )
    }
}

/** 엔진 종류로 실행기를 만든다 */
object VadDetectorFactory {
    /**
     * 목적: VAD 실행기를 만든다(모델 로딩 포함 — 배치 시작 때 1회만 부른다).
     * 입력: context, engine
     * 리턴: VadDetector
     * 예외: 모델/라이브러리가 없으면 FileNotFoundException 등(원인을 그대로 알린다)
     */
    fun create(context: Context, engine: VadEngine): VadDetector = when (engine) {
        VadEngine.NONE -> throw IllegalArgumentException("VAD 를 쓰지 않는 설정입니다.")
        VadEngine.SILERO -> SileroVadProcessor(context)
        VadEngine.WEBRTC -> WebRtcVadDetector()
        VadEngine.PYANNOTE -> PyannoteVadDetector(context)
        VadEngine.FSMN -> FsmnVadDetector(context)
        VadEngine.FIRERED -> FireRedVadDetector(context)
        VadEngine.TEN -> TenVadDetector()
    }

    /**
     * 목적: 엔진이 쓰는 assets 파일 목록(화면에서 미리 있는지 확인할 때 쓴다).
     * 입력: engine
     * 리턴: assets 파일 이름 목록(네이티브 라이브러리는 포함하지 않는다)
     */
    fun requiredAssets(engine: VadEngine): List<String> = when (engine) {
        VadEngine.SILERO -> listOf(SileroVadProcessor.MODEL_ASSET)
        VadEngine.PYANNOTE -> listOf(PyannoteVadDetector.MODEL_ASSET)
        VadEngine.FSMN -> listOf(FsmnVadDetector.MODEL_ASSET, FsmnVadDetector.CMVN_ASSET)
        VadEngine.FIRERED -> listOf(FireRedVadDetector.MODEL_ASSET, FireRedVadDetector.CMVN_ASSET)
        else -> emptyList()
    }
}
