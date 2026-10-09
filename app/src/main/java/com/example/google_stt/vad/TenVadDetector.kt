/*
 * 파일명: vad/TenVadDetector.kt
 * 목적 및 기능:
 * - TEN VAD(Agora, 프레임 단위 말 확률)를 공식 Android 네이티브 라이브러리(libten_vad.so)로 실행하고,
 *   프레임 확률을 Silero 방식 상태 기계(FrameSegmenter)로 말 구간으로 만든다.
 * - 라이브러리는 C API 만 제공하므로(공식 Java 래퍼도 JNA 사용) JNA 로 부른다.
 *     int ten_vad_create(ten_vad_handle_t *handle, size_t hop_size, float threshold);
 *     int ten_vad_process(ten_vad_handle_t handle, const int16_t *audio_data, size_t audio_data_length,
 *                         float *out_probability, int *out_flag);
 *     int ten_vad_destroy(ten_vad_handle_t *handle);
 *     const char *ten_vad_get_version(void);
 *   ※ size_t 는 arm64 에서 64비트이므로 JNA 에서는 NativeLong(C long, LP64 = 64비트)으로 넘긴다.
 *     (공식 TenVad.java 는 int 로 선언했지만, AArch64 에서 상위 32비트가 정의되지 않을 수 있어 안전한 쪽을 택했다)
 * - 공식 값: 16 kHz 전용, hop 160 또는 256 권장, threshold 0.5 (ten-vad README / ten_vad.h)
 *
 * 라이선스 주의: Apache-2.0 + 추가 조건(Agora 와 경쟁하는 배포 금지, 자사 앱·직접 최종사용자 용도만).
 *   화면과 CSV 에 commercial=conditional 로 표시한다.
 * 파일 위치: app/src/main/jniLibs/arm64-v8a/libten_vad.so (tools/download_models.sh 가 받는다)
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import com.example.google_stt.VadConfig
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlin.math.roundToInt

/**
 * 목적: TEN VAD 실행기.
 * 비고: 핸들은 파일마다 새로 만든다(내부 순환 상태가 파일 사이에 섞이지 않게 하기 위함).
 */
class TenVadDetector : VadDetector {

    /** JNA 로 연결할 C 함수 목록(libten_vad.so) */
    private interface TenVadLib : Library {
        fun ten_vad_create(handle: PointerByReference, hopSize: NativeLong, threshold: Float): Int
        fun ten_vad_process(
            handle: Pointer,
            audioData: ShortArray,
            audioDataLength: NativeLong,
            outProbability: FloatArray,
            outFlag: IntArray,
        ): Int
        fun ten_vad_destroy(handle: PointerByReference): Int
        fun ten_vad_get_version(): String
    }

    /** 라이브러리 로드(없으면 받는 방법을 알려 주는 예외로 바꾼다) */
    private val lib: TenVadLib = try {
        Native.load("ten_vad", TenVadLib::class.java)                      // jniLibs/<abi>/libten_vad.so
    } catch (e: Throwable) {
        throw IllegalStateException(
            "libten_vad.so 를 불러오지 못했습니다. tools/download_models.sh 로 " +
                "app/src/main/jniLibs/arm64-v8a/libten_vad.so 를 받은 뒤 다시 빌드하세요. (원인: ${e.message})",
            e,
        )
    }

    override val engine: VadEngine = VadEngine.TEN                         // 엔진 종류
    override val label: String = "libten_vad.so"                           // CSV vad_model
    override val runtimeVersion: String = runCatching { "ten-vad " + lib.ten_vad_get_version() }
        .getOrDefault("ten-vad (판 확인 실패)")                            // 라이브러리 판
    override val modelSha256: String = ""                                  // 모델은 라이브러리에 내장

    /**
     * 목적: PCM 1건에서 말 구간을 찾는다.
     * 입력: pcm16Le — 16 kHz/mono/PCM16 LE, setting — TEN_* 프리셋 또는 Custom
     * 리턴: FrameSegmenter.Result
     * 예외: 네이티브 호출이 실패 코드를 돌려주면 IllegalStateException
     */
    @Synchronized
    override fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result {
        val config = VadConfig.fromSetting(setting)                        // 범위 검증
        val hop = setting[VadKeys.HOP].roundToInt()                        // 160 / 256
        val samples = VadAudio.toShort(pcm16Le)                            // PCM16 → short
        val frameCount = (samples.size + hop - 1) / hop                    // 마지막 짧은 프레임 포함
        val probs = FloatArray(frameCount)                                 // 프레임별 확률

        val ref = PointerByReference()                                     // 핸들을 받을 자리
        check(lib.ten_vad_create(ref, NativeLong(hop.toLong()), config.threshold) == 0) {
            "ten_vad_create 실패(hop=$hop, threshold=${config.threshold})"
        }
        val handle = ref.value                                             // 만들어진 핸들
        try {
            val frame = ShortArray(hop)                                    // 재사용 버퍼
            val outProb = FloatArray(1)                                    // 확률 출력 자리
            val outFlag = IntArray(1)                                      // 판정 출력 자리(쓰지 않음)
            for (i in 0 until frameCount) {
                val start = i * hop
                val n = minOf(hop, samples.size - start)
                frame.fill(0)                                              // 마지막 프레임은 0 으로 채운다
                System.arraycopy(samples, start, frame, 0, n)
                check(lib.ten_vad_process(handle, frame, NativeLong(hop.toLong()), outProb, outFlag) == 0) {
                    "ten_vad_process 실패(frame=$i)"
                }
                probs[i] = outProb[0].coerceIn(0f, 1f)                     // 말 확률
            }
        } finally {
            runCatching { lib.ten_vad_destroy(PointerByReference(handle)) }  // 핸들 정리
        }

        return FrameSegmenter.collect(                                     // Silero 방식 구간 규칙
            probabilities = probs,
            windowSamples = hop,
            audioLengthSamples = samples.size,
            threshold = config.threshold,
            minSpeechMs = config.minSpeechDurationMs,
            minSilenceMs = config.minSilenceDurationMs,
            speechPadMs = config.speechPadMs,
            maxSpeechSec = config.maxSpeechDurationSec,
        )
    }

    /** 배치 끝 정리 — 핸들은 detect() 안에서 매번 닫으므로 할 일이 없다 */
    override fun close() = Unit
}
