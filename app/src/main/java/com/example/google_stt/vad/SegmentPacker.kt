/*
 * 파일명: vad/SegmentPacker.kt
 * 목적 및 기능:
 * - "구간 처리" 선택에 따라 STT 에 넣을 조각(PCM 목록)을 만든다.
 *     · ENGINE_MAX (엔진별 최대 길이) : 엔진이 한 번에 받을 수 있는 길이까지 조각을 키운다.
 *         Google 엔진(ML Kit / 플랫폼)은 기존 동작 그대로 — 말 구간을 이어 붙인 PCM 1개를 세션 1개로 넣는다
 *         (61초 결함은 기존 Speech EPD 가 처리한다). 그래서 이 모드에서는 이 파일을 쓰지 않는다.
 *     · SEG10 (모든 엔진 10초 통일) : 말 구간을 순서대로 담되 한 조각이 10초를 넘지 않게 한다.
 *         - 10초보다 긴 말 구간은 상한의 50~100 % 범위에서 30 ms 창 에너지가 가장 작은 곳에서 자른다
 *           (PC "STT 통합 실행기" stt_common.split_long_segments(samples=) 와 같은 규칙).
 *         - 짧은 말 구간들은 사이 무음(join)을 넣어 10초까지 한 조각으로 묶는다(세션 수를 줄인다).
 *         - VAD 를 쓰지 않으면 파일 전체를 하나의 말 구간으로 보고 같은 규칙으로 자른다.
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import com.example.google_stt.VadSegment
import java.io.ByteArrayOutputStream

/** 구간 처리 방식 */
enum class SegMode(
    val displayName: String,                  // 스피너 표시 이름
    val conditionSuffix: String,              // 조건 이름 꼬리(없으면 빈 문자열)
    val maxSec: Double?,                      // 조각 상한(초). null = 엔진별 최대 길이
) {
    ENGINE_MAX("엔진별 최대 길이 (Google = 기존: 이어 붙여 1세션 + EPD)", "", null),
    SEG10("모든 엔진 10초 통일 (말 구간을 10초 이하 조각으로)", "SEG10", 10.0),
}

/** 조각 만들기 결과 */
class PackedChunks(
    val chunks: List<ByteArray>,              // STT 에 넣을 PCM 조각들(순서대로)
    val splitCount: Int,                      // 상한 때문에 말 구간을 자른 횟수
    val joinSilenceSamples: Int = 0,          // change-hyungchul-20261009-1626 : 조각 안에 끼워 넣은 사이 무음 샘플 합(CSV vad_join_silence_sec)
)

/** 조각 만들기 */
object SegmentPacker {

    private const val SAMPLE_RATE = 16_000
    private const val ENERGY_WIN = 480                // 30 ms 에너지 창

    /**
     * 목적: 말 구간을 상한 이하 조각으로 묶는다.
     * 입력: pcm — 원본(노이즈 저감까지 끝난) PCM16 LE
     *       segments — VAD 말 구간(패딩 후, 원본 좌표). null 이면 파일 전체를 한 구간으로 본다
     *       maxSec — 조각 상한(초), joinMs — 같은 조각 안의 구간 사이 무음(ms)
     * 리턴: PackedChunks
     */
    fun pack(pcm: ByteArray, segments: List<VadSegment>?, maxSec: Double, joinMs: Int): PackedChunks {
        val total = pcm.size / 2
        val maxSamples = (maxSec * SAMPLE_RATE).toInt()                    // 상한(샘플)
        val gap = (SAMPLE_RATE.toLong() * joinMs / 1000L).toInt().coerceAtLeast(0)   // 사이 무음(샘플)
        val src = segments ?: listOf(VadSegment(0, total))                 // VAD 없음 = 파일 전체
        var splits = 0
        var joined = 0                                                     // change-hyungchul-20261009-1626 : 끼워 넣은 무음 샘플 합

        // 1) 상한보다 긴 구간을 조용한 지점에서 자른다
        val pieces = ArrayList<VadSegment>()
        for (s in src) {
            var start = s.startSample
            while (s.endSample - start > maxSamples) {
                val cut = quietestCut(pcm, start + maxSamples / 2, start + maxSamples)   // 50~100 % 범위
                pieces.add(VadSegment(start, cut))
                start = cut
                splits++
            }
            if (s.endSample > start) pieces.add(VadSegment(start, s.endSample))
        }

        // 2) 짧은 구간들을 사이 무음과 함께 상한까지 묶는다
        val chunks = ArrayList<ByteArray>()
        var cur = ByteArrayOutputStream()
        var curSamples = 0
        for (p in pieces) {
            val len = p.endSample - p.startSample
            val need = if (curSamples == 0) len else curSamples + gap + len    // 넣었을 때 길이
            if (curSamples > 0 && need > maxSamples) {                       // 넘치면 지금 조각을 닫는다
                chunks.add(cur.toByteArray())
                cur = ByteArrayOutputStream()
                curSamples = 0
            }
            if (curSamples > 0 && gap > 0) {                                 // 같은 조각 안의 두 번째 구간부터 무음
                cur.write(ByteArray(gap * 2))
                curSamples += gap
                joined += gap
            }
            cur.write(pcm, p.startSample * 2, len * 2)
            curSamples += len
        }
        if (curSamples > 0) chunks.add(cur.toByteArray())
        return PackedChunks(chunks, splits, joined)
    }

    /**
     * 목적: [from, to) 범위에서 30 ms 창 에너지가 가장 작은 위치(창 가운데)를 찾는다.
     * 입력: pcm, from, to — 샘플 좌표
     * 리턴: 자를 샘플 위치(from 초과 to 이하)
     */
    private fun quietestCut(pcm: ByteArray, from: Int, to: Int): Int {
        val total = pcm.size / 2
        val end = minOf(to, total)
        if (end - from <= ENERGY_WIN) return end                           // 범위가 창보다 짧으면 상한에서 자른다
        var best = end
        var bestE = Double.MAX_VALUE
        var pos = from
        while (pos + ENERGY_WIN <= end) {                                  // 창을 반씩 겹쳐 민다(15 ms 간격)
            var e = 0.0
            for (i in pos until pos + ENERGY_WIN) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt()
                val v = ((hi shl 8) or lo).toShort().toDouble()
                e += v * v
            }
            if (e < bestE) { bestE = e; best = pos + ENERGY_WIN / 2 }
            pos += ENERGY_WIN / 2
        }
        return best.coerceIn(from + 1, end)
    }
}
