/*
 * 파일명: engine/vad/VadPipeline.kt
 * 목적 및 기능:
 * - 모든 VAD 엔진이 지켜야 하는 공통 계약(VadDetector)과 공통 처리 단계를 정의한다.
 *     · VadDetector  : PCM → 말 구간(패딩 전/후) — 엔진마다 구현이 다르다
 *     · VadPcmJoiner : 말 구간만 이어 붙이고 구간 사이에 무음(join)을 넣는다 — 모든 엔진 공통
 *     · VadRunner    : 위 두 단계를 묶어 VadResult 로 돌려준다
 *     · VadFiles     : 모델 파일 읽기·해시
 * - v3 의 vad/VadPipeline.kt 를 옮긴 것이다. 계약·이어 붙이기·실행의 본문은 그대로이고 바뀐 점은:
 *     · 모델을 APK assets 가 아니라 자산 폴더의 파일에서 읽는다(VadAudio.readAsset → VadFiles.read)
 *     · PCM 변환은 engine/audio/Pcm16 으로, 디텍터 만들기는 VadDetectorFactory.kt 로 나눴다
 *   (VAD 마다 이어 붙이는 방식이 다르면 WER 차이가 VAD 때문인지 이어 붙이기 때문인지 구분할 수 없다 — 그래서 공통이다)
 *
 * add-hyungchul-20261106-2159 — [T5-6]
 */
package com.example.google_stt.engine.vad

import android.util.Log                               // 정리 실패 기록
import com.example.google_stt.domain.AppError          // E-VAD-01
import com.example.google_stt.domain.vad.FrameSegmenter
import com.example.google_stt.domain.vad.VadEngine
import com.example.google_stt.domain.vad.VadResult
import com.example.google_stt.domain.vad.VadSegment
import com.example.google_stt.domain.vad.VadSetting
import java.io.ByteArrayOutputStream
import java.io.File
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

/** 모델 파일 읽기 도우미 */
object VadFiles {
    /**
     * 목적: 자산 폴더의 모델 파일을 읽는다. 없으면 무엇이 없는지 알려 주는 오류를 던진다.
     * 입력: dir - 자산 폴더, name - 파일 이름
     * 출력: 없음
     * 리턴: 파일 바이트
     * 예외: AppError.VadLoad(E-VAD-01)
     */
    fun read(dir: File, name: String): ByteArray = try {
        File(dir, name).readBytes()
    } catch (e: IOException) {
        throw AppError.VadLoad("VAD 모델 파일을 읽을 수 없다 — ${File(dir, name).path} (${e.message})", e)
    }

    /**
     * 목적: 정리(close·destroy) 한 가지를 하고, 실패하면 Logcat 에 남긴다(v3 는 runCatching 으로 조용히 삼켰다).
     * 입력: what - 무엇을 정리하는지(로그용), block - 정리할 일
     * 출력: 실패하면 경고 로그 한 줄(정리 실패가 결과를 바꾸지는 않으므로 예외로 올리지 않는다)
     * 리턴: 없음
     */
    fun release(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w("GoogleStt", "VAD 정리 실패($what): ${e.message}")
        }
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
