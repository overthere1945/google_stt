// 파일명: app/src/main/java/com/example/google_stt/SpeechEpd.kt
// 목적 및 기능:
//   add-hyungchul-20260914-2350
//   Google on-device(ML Kit GenAI) 는 한 세션에 넣는 오디오가 약 61초를 넘고
//   엔진이 내부적으로 segment 를 1개로 처리해 버리면 전사문의 "앞부분" 을 통째로 버린다.
//   (실측: 61.32초 정상 / 67.06초 잘림, 86~89초에서는 590자 나올 것이 143자만 나왔다)
//
//   이 파일은 그 결함을 피하기 위해, 회사에서 쓰던 Speech EPD 개념을 그대로 옮긴 것이다.
//     - 원래 방식: 10초 고정 프레임으로 자르되, 프레임 경계가 발화 도중(유음구간)이면
//                  32 ms 단위로 "역방향 탐색" 해서 무음 지점까지 경계를 당겨 자른다.
//     - 여기 방식: 고정 길이를 45초(목표) / 55초(상한) 로 바꾸고,
//                  1순위로 Silero VAD 가 이미 찾아 놓은 구간 사이 무음(seam) 에서 자른다.
//                  seam 이 상한 안에 하나도 없을 때만 32 ms 역방향 탐색으로 내려간다.
//
//   자른 다음 무엇을 하느냐가 A안 / B안으로 갈린다.
//     A안(SPLIT_SESSION) : 조각마다 STT 세션을 따로 돌리고 전사문을 이어 붙인다.
//                          조각이 항상 55초 이하이므로 61초 결함을 확실히 피한다.
//     B안(LONG_SILENCE)  : 세션은 1개로 두고, 절단 지점의 무음만 길게(기본 1000 ms) 늘린다.
//                          문맥이 끊기지 않지만, ML Kit 가 그 무음에서 정말 끊어줄지는 보장이 없다.
//
// 비고: 이 파일은 안드로이드 API 를 쓰지 않는다(순수 Kotlin). 그래서 단위 검증이 쉽다.

package com.example.google_stt

import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 목적: 앱 UI(스피너) 에 노출할 Speech EPD 동작 방식.
 * 비고: folderName 은 결과 폴더명 조각으로 쓴다(설정이 다르면 결과가 덮어써지면 안 되므로).
 */
enum class EpdMode(
    val displayName: String,   // 스피너에 보여줄 이름
    val folderName: String,    // 결과 폴더명 조각
) {
    /** 분할하지 않는다. 지금까지의 동작과 100% 동일하다(기존 baseline 보존용). */
    OFF("OFF (분할 안 함)", "off"),

    /** A안: 조각마다 STT 세션을 따로 돌리고 전사문을 이어 붙인다. */
    SPLIT_SESSION("A: 세션 분할 (조각마다 STT)", "split"),

    /** B안: 세션은 1개, 절단 지점의 무음만 길게 늘린다. */
    LONG_SILENCE("B: 긴 무음 삽입 (세션 1개)", "gap"),
}

/**
 * 목적: Speech EPD 파라미터 묶음.
 * 필드:
 *   mode          — OFF / A안 / B안
 *   targetSec     — 조각 하나의 "목표" 길이(초). 이 값에 가장 가까운 무음에서 자른다.
 *   maxSec        — 조각 하나의 "절대 상한"(초). 여기를 넘기면 안 된다(61초 결함 경계보다 작아야 한다).
 *   longSilenceMs — B안에서 절단 지점에 넣을 무음 길이(ms).
 *   searchBackSec — seam 이 없을 때 32 ms 역방향 탐색을 할 최대 구간(초).
 */
data class EpdConfig(
    val mode: EpdMode,
    val targetSec: Double = 45.0,
    val maxSec: Double = 55.0,
    val longSilenceMs: Int = 1_000,
    val searchBackSec: Double = 5.0,
) {
    init {
        // 잘못된 값으로 배치가 다 돌면 결과 전체를 버려야 하므로 여기서 막는다.
        require(targetSec > 0.0) { "EPD 목표 길이는 0보다 커야 합니다." }
        require(maxSec > 0.0) { "EPD 최대 길이는 0보다 커야 합니다." }
        require(targetSec <= maxSec) { "EPD 목표 길이는 최대 길이보다 클 수 없습니다." }
        require(maxSec <= 60.0) {
            "EPD 최대 길이는 60초 이하여야 합니다(Google on-device 결함 경계가 약 61초입니다)."
        }
        require(longSilenceMs in 0..5_000) { "긴 무음은 0 ~ 5000 ms 여야 합니다." }
        require(searchBackSec > 0.0) { "역방향 탐색 구간은 0보다 커야 합니다." }
    }

    /** 로그/run_meta 에 남길 한 줄 요약. */
    fun summary(): String = when (mode) {
        EpdMode.OFF -> "OFF"
        EpdMode.SPLIT_SESSION -> String.format(
            Locale.US,
            "A(세션 분할) target=%.1fs, max=%.1fs, 역방향탐색=%.1fs",
            targetSec, maxSec, searchBackSec,
        )
        EpdMode.LONG_SILENCE -> String.format(
            Locale.US,
            "B(긴 무음) target=%.1fs, max=%.1fs, 무음=%dms, 역방향탐색=%.1fs",
            targetSec, maxSec, longSilenceMs, searchBackSec,
        )
    }
}

/**
 * 목적: 분할 계획 1건의 결과를 나른다.
 * 필드:
 *   cuts          — 절단 지점(출력 PCM 기준 sample 좌표). 비어 있으면 분할하지 않는다는 뜻.
 *   seamCuts      — 그중 VAD 무음(seam) 에서 자른 횟수 (= 안전한 절단)
 *   searchedCuts  — 그중 32 ms 역방향 탐색으로 자른 횟수 (= 말 도중일 수 있는 절단)
 *   planMs        — 계획을 세우는 데 걸린 시간(ms)
 */
class EpdPlan(
    val cuts: List<Int>,
    val seamCuts: Int,
    val searchedCuts: Int,
    val planMs: Long,
) {
    /** 조각 개수. 절단이 N개면 조각은 N+1개다. */
    val chunkCount: Int get() = cuts.size + 1

    /**
     * 목적: 절단 지점을 "초" 문자열로 만든다(CSV 기록용).
     * 입력: 없음 / 출력: 없음
     * 리턴: "45.2|88.7" 형태. 절단이 없으면 빈 문자열.
     */
    fun cutPointsText(): String =
        cuts.joinToString("|") { String.format(Locale.US, "%.2f", it / SpeechEpd.SAMPLE_RATE_D) }
}

/**
 * 목적: Speech EPD 본체. 상태가 없으므로 object 로 둔다.
 */
object SpeechEpd {

    /** 이 앱의 STT 입력은 항상 16 kHz mono PCM16 이다. */
    const val SAMPLE_RATE = 16_000
    const val SAMPLE_RATE_D = 16_000.0

    /**
     * 역방향 탐색 단위(sample). 512 sample = 32 ms @ 16 kHz.
     * 회사에서 쓰던 "32 ms 역방향 탐색" 과 같은 단위이며,
     * Silero VAD 의 window 크기(512)와도 같아서 두 결과를 나란히 보기 좋다.
     */
    const val FRAME_SAMPLES = 512

    /**
     * 목적: 어디를 자를지 계획을 세운다. PCM 을 실제로 건드리지는 않는다.
     * 입력: pcm    — VAD 를 통과한 STT 입력 PCM16 LE
     *       seams  — VAD 가 알려준 안전한 절단 후보(출력 PCM 기준 sample 좌표)
     *       config — EPD 설정
     * 출력: 없음
     * 리턴: EpdPlan
     *
     * 동작:
     *   0) 먼저 "몇 조각으로 나눌지" 를 정하고, 조각을 고르게 나눈다.
     *        조각 수 n = max(올림(전체/상한), 반올림(전체/목표))  (최소 1)
     *        조각 길이 = 전체 / n
     *      change-hyungchul-20260915-0030
     *      이렇게 하지 않고 그냥 "목표(45초)" 에서 자르면 60초 음원이 45초 + 15초 로 갈라진다.
     *      15초짜리 조각은 세션만 하나 더 쓰고 문맥은 문맥대로 끊어서 손해만 크다.
     *      고르게 나누면 같은 60초가 30초 + 30초가 되어 양쪽 다 문맥이 충분하다.
     *   1) 남은 길이가 상한 이하면 더 자를 필요가 없으므로 끝낸다.
     *   2) [현재위치+최소, 현재위치+상한] 안에 있는 seam 중 "이상적인 지점에 가장 가까운" 것을 고른다.
     *      → 이 지점은 VAD 가 무음이라고 판단한 곳이므로 말이 잘리지 않는다.
     *   3) 그런 seam 이 하나도 없으면(예: VAD 가 구간을 1개로만 잡은 경우)
     *      상한 지점에서 32 ms 단위로 뒤로 훑어 RMS 가 가장 작은 frame 을 찾아 거기서 자른다.
     *
     * 비고: 최소 길이는 조각 길이의 절반으로 둔다. 너무 짧은 조각이 생기면
     *       조각 수만 늘고 문맥이 과하게 끊기기 때문이다.
     */
    fun plan(pcm: ByteArray, seams: List<Int>, config: EpdConfig): EpdPlan {
        val startedNs = System.nanoTime()                       // 계획 수립 시간 계측 시작

        val totalSamples = pcm.size / 2                         // PCM16 은 sample 당 2 byte
        val maxSamples = (config.maxSec * SAMPLE_RATE).toInt()  // 조각 상한(sample)
        val targetSamples = (config.targetSec * SAMPLE_RATE).toInt()
        val searchBackSamples = (config.searchBackSec * SAMPLE_RATE).toInt()

        val cuts = ArrayList<Int>()
        var seamCuts = 0
        var searchedCuts = 0

        // OFF 이거나 애초에 상한 이하면 자를 것이 없다.
        if (config.mode == EpdMode.OFF || totalSamples <= maxSamples) {
            return EpdPlan(emptyList(), 0, 0, (System.nanoTime() - startedNs) / 1_000_000)
        }

        // change-hyungchul-20260915-0030 : 조각을 고르게 나누기 위해 조각 수를 먼저 정한다.
        //   byMax    — 상한을 지키려면 최소한 이만큼은 나눠야 한다(올림).
        //   byTarget — 목표 길이에 가깝게 하려면 이 정도가 좋다(반올림, 최소 1).
        val byMax = (totalSamples + maxSamples - 1) / maxSamples
        val byTarget = kotlin.math.max(1, Math.round(totalSamples.toDouble() / targetSamples).toInt())
        val pieces = kotlin.math.max(byMax, byTarget)
        val idealSamples = totalSamples / pieces                // 조각 하나의 이상적인 길이
        val minSamples = idealSamples / 2                       // 너무 짧은 조각을 막는 하한

        var start = 0                                           // 현재 조각의 시작 좌표
        var guard = 0                                           // 무한 루프 방어(조각 수 상한)
        val guardMax = totalSamples / kotlin.math.max(minSamples, 1) + 4

        while (totalSamples - start > maxSamples && guard++ < guardMax) {
            val hardEnd = start + maxSamples                    // 여기를 넘으면 안 된다
            // 이상적인 절단 지점. 상한을 넘지 않도록 눌러 둔다.
            val ideal = kotlin.math.min(start + idealSamples, hardEnd)

            // (1순위) 상한 안에 있는 seam 중 목표에 가장 가까운 것
            var cut = -1
            var best = Int.MAX_VALUE
            for (s in seams) {
                if (s <= start + minSamples) continue           // 너무 짧은 조각이 된다
                if (s > hardEnd) break                          // seams 는 오름차순이므로 더 볼 필요 없다
                val d = abs(s - ideal)
                if (d < best) { best = d; cut = s }
            }

            if (cut > 0) {
                seamCuts++                                      // VAD 무음에서 잘랐다(안전)
            } else {
                // (2순위) seam 이 없다 → 상한 지점에서 32 ms 단위로 역방향 탐색
                cut = backwardSilenceSearch(
                    pcm = pcm,
                    hardEnd = hardEnd,
                    backSamples = searchBackSamples,
                    lowerBound = start + minSamples,
                )
                searchedCuts++
            }

            // 방어: 어떤 이유로든 앞으로 못 나가면 상한에서 강제로 끊는다(무한 루프 방지).
            if (cut <= start) cut = hardEnd

            cuts.add(cut)
            start = cut
        }

        return EpdPlan(cuts, seamCuts, searchedCuts, (System.nanoTime() - startedNs) / 1_000_000)
    }

    /**
     * 목적: hardEnd 에서 뒤(앞쪽)로 32 ms 씩 훑어 가장 조용한 frame 을 찾는다.
     * 입력: pcm         — 대상 PCM16 LE
     *       hardEnd     — 이 좌표를 넘어설 수 없다(sample)
     *       backSamples — 뒤로 훑을 최대 거리(sample)
     *       lowerBound  — 이 좌표보다 앞으로는 가지 않는다(sample)
     * 출력: 없음
     * 리턴: 자를 좌표(sample). 후보가 없으면 hardEnd 를 그대로 돌려준다.
     *
     * 비고: 회사 방식과 동일하게 32 ms 단위로 본다.
     *       VAD 를 통과한 뒤라 진짜 완전한 무음은 거의 없다.
     *       그래서 "무음을 찾는다" 가 아니라 "가장 조용한 지점을 찾는다" 가 정확한 표현이다.
     *       frame 의 한가운데를 절단점으로 삼아 앞뒤로 16 ms 씩 여유를 남긴다.
     */
    fun backwardSilenceSearch(
        pcm: ByteArray,
        hardEnd: Int,
        backSamples: Int,
        lowerBound: Int,
    ): Int {
        val totalSamples = pcm.size / 2
        val end = hardEnd.coerceIn(0, totalSamples)             // 탐색 시작(뒤쪽 끝)
        val floor = lowerBound.coerceIn(0, end)                 // 탐색 한계(앞쪽 끝)
        val from = kotlin.math.max(floor, end - backSamples)    // 실제로 훑기 시작할 좌표
        if (end - from < FRAME_SAMPLES) return end              // 훑을 구간이 없다

        var bestPos = end
        var bestRms = Double.MAX_VALUE

        // end 쪽에서 from 쪽으로 32 ms 씩 이동하며 RMS 를 잰다.
        var p = end - FRAME_SAMPLES
        while (p >= from) {
            val rms = frameRms(pcm, p, FRAME_SAMPLES)
            // "<=" 가 아니라 "<" 를 쓴다. 값이 같다면 뒤쪽(=원래 경계에 가까운 쪽)을 유지해
            // 조각이 불필요하게 짧아지는 것을 막는다.
            if (rms < bestRms) {
                bestRms = rms
                bestPos = p + FRAME_SAMPLES / 2                 // frame 한가운데에서 자른다
            }
            p -= FRAME_SAMPLES
        }
        return bestPos.coerceIn(floor, end)
    }

    /**
     * 목적: PCM16 LE 의 한 frame RMS 를 구한다.
     * 입력: pcm — PCM16 LE, from — 시작 sample, count — sample 수
     * 출력: 없음
     * 리턴: RMS (0.0 ~ 32768.0)
     */
    private fun frameRms(pcm: ByteArray, from: Int, count: Int): Double {
        var acc = 0.0
        var i = from * 2                                        // sample → byte 좌표
        val last = ((from + count) * 2).coerceAtMost(pcm.size)
        var n = 0
        while (i + 1 < last) {
            // little-endian 16bit signed 복원
            val v = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
            acc += v.toDouble() * v.toDouble()
            i += 2
            n++
        }
        return if (n == 0) Double.MAX_VALUE else sqrt(acc / n)
    }

    /**
     * 목적: A안 — 계획대로 PCM 을 조각으로 나눈다.
     * 입력: pcm — STT 입력 PCM16 LE, plan — plan() 결과
     * 출력: 없음
     * 리턴: 조각 목록. 분할이 없으면 원본 1개를 그대로 담아 돌려준다.
     * 비고: 조각은 원본의 부분 복사본이다. 어떤 sample 도 버리거나 더하지 않는다.
     */
    fun split(pcm: ByteArray, plan: EpdPlan): List<ByteArray> {
        if (plan.cuts.isEmpty()) return listOf(pcm)              // 자를 곳이 없으면 통째로
        val out = ArrayList<ByteArray>(plan.chunkCount)
        var startByte = 0
        for (cut in plan.cuts) {
            val endByte = (cut * 2).coerceIn(startByte, pcm.size)
            if (endByte > startByte) out.add(pcm.copyOfRange(startByte, endByte))
            startByte = endByte
        }
        if (startByte < pcm.size) out.add(pcm.copyOfRange(startByte, pcm.size))  // 마지막 조각
        return if (out.isEmpty()) listOf(pcm) else out
    }

    /**
     * 목적: B안 — 세션은 1개로 두고, 절단 지점에 긴 무음만 끼워 넣는다.
     * 입력: pcm — STT 입력 PCM16 LE, plan — plan() 결과, longSilenceMs — 넣을 무음 길이(ms)
     * 출력: 없음
     * 리턴: InsertedPcm(무음이 들어간 PCM, 추가된 무음 sample 합)
     * 비고: 이 방식은 STT 입력 길이를 늘린다. 그래서 RTF 계산이 흔들리지 않도록
     *       추가된 무음 길이를 CSV 에 따로 남긴다.
     */
    fun insertLongSilence(pcm: ByteArray, plan: EpdPlan, longSilenceMs: Int): InsertedPcm {
        if (plan.cuts.isEmpty() || longSilenceMs <= 0) return InsertedPcm(pcm, 0)

        val gapSamples = (SAMPLE_RATE.toLong() * longSilenceMs / 1_000L).toInt()
        val gapBytes = gapSamples * 2
        val gap = ByteArray(gapBytes)                            // ByteArray 는 0(무음) 으로 초기화된다

        val out = ByteArrayOutputStream(pcm.size + gapBytes * plan.cuts.size)
        var startByte = 0
        var inserted = 0
        for (cut in plan.cuts) {
            val endByte = (cut * 2).coerceIn(startByte, pcm.size)
            if (endByte > startByte) out.write(pcm, startByte, endByte - startByte)
            out.write(gap, 0, gapBytes)                          // 절단 지점에 긴 무음
            inserted++
            startByte = endByte
        }
        if (startByte < pcm.size) out.write(pcm, startByte, pcm.size - startByte)
        return InsertedPcm(out.toByteArray(), gapSamples * inserted)
    }

    /** insertLongSilence() 의 반환 묶음. */
    class InsertedPcm(
        val pcm: ByteArray,          // 무음이 들어간 최종 STT 입력
        val addedSamples: Int,       // 추가된 무음 sample 합
    ) {
        val addedSec: Double get() = addedSamples / SAMPLE_RATE_D
    }
}
