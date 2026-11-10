/*
 * 파일명: domain/vad/SegmentPackerTest.kt (app/src/test)
 * 목적 및 기능:
 * - SegmentPacker.pack() 의 조각 규칙을 합성 신호로 확인한다(음원 파일을 쓰지 않는다 — D31).
 *     · UT-VAD-01 엔진별 최대 길이: 모든 조각이 모델 상한 이하
 *     · UT-VAD-02 SEG10: 모든 조각이 10초 이하, 짧은 말 구간은 사이 무음과 함께 묶임
 *     · UT-VAD-03 긴 구간 자르기: 상한의 50~100 % 범위에서 가장 조용한 곳에서 자름
 * - 에너지가 고른 신호에서는 quietestCut 이 범위의 첫 창(상한의 50 % + 15 ms)을 고른다는 성질로 조각 수를 미리 계산했다.
 *
 * add-hyungchul-20261105-2354 — [T1-5]
 */
package com.example.google_stt.domain.vad

import org.junit.Assert.assertArrayEquals   // 바이트 배열 비교
import org.junit.Assert.assertEquals        // 값 비교
import org.junit.Assert.assertTrue          // 조건 확인
import org.junit.Test                       // JUnit 4 시험 표시

/** 목적: SegmentPacker 조각 만들기 규칙 시험. */
class SegmentPackerTest {

    private val rate = 16_000                // 표본화 주파수(Hz) — 앱 전체 고정값

    /**
     * 목적: 진폭이 일정한 PCM16 LE 를 만든다(지정한 구간만 무음 0).
     * 입력: totalSamples - 전체 샘플 수, amp - 진폭, silent - 무음으로 둘 샘플 구간 목록
     * 출력: 없음
     * 리턴: ByteArray - PCM16 little-endian(샘플당 2바이트)
     */
    private fun pcm(totalSamples: Int, amp: Int = 1000, silent: List<IntRange> = emptyList()): ByteArray {
        val out = ByteArray(totalSamples * 2)                              // 샘플당 2바이트
        for (i in 0 until totalSamples) {
            val v = if (silent.any { i in it }) 0 else amp                 // 무음 구간이면 0
            out[i * 2] = (v and 0xFF).toByte()                             // 낮은 바이트 먼저(little-endian)
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()                 // 높은 바이트
        }
        return out
    }

    /**
     * 목적: 조각의 샘플 수 목록을 구한다.
     * 입력: p - pack() 결과 / 출력: 없음 / 리턴: List<Int> - 조각별 샘플 수
     */
    private fun sizes(p: PackedChunks): List<Int> = p.chunks.map { it.size / 2 }   // 바이트 → 샘플

    /**
     * 목적: [UT-VAD-01] VAD 없이(구간 null) 긴 음원을 넣으면 모든 조각이 모델 상한 이하이고 샘플이 사라지지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun engineMax_allChunksWithinModelLimit() {
        val total = 100 * rate                                             // 100초 음원
        val audio = pcm(total)                                             // 에너지가 고른 신호
        for (maxSec in listOf(29.0, 30.0, 39.0, 15.0, 25.0)) {             // Whisper / Qwen3·SenseVoice·Parakeet / Omni / Zipformer / Moonshine 상한
            val p = SegmentPacker.pack(audio, null, maxSec, 200)           // 구간 null = 파일 전체를 한 구간으로
            val limit = (maxSec * rate).toInt()                            // 상한(샘플)
            assertTrue("상한 $maxSec 초과 조각", sizes(p).all { it <= limit })
            assertTrue("조각 수가 모자람($maxSec)", p.chunks.size >= Math.ceil(100.0 / maxSec).toInt())
            assertEquals(total + p.joinSilenceSamples, sizes(p).sum())     // 원본 샘플 + 끼워 넣은 무음 = 출력 샘플
        }
    }

    /**
     * 목적: [UT-VAD-01] 상한보다 짧은 음원은 자르지 않고 원본 바이트 그대로 조각 1개가 된다(NFR-07 재현성의 전제).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun engineMax_shortAudioPassesThroughUnchanged() {
        val audio = pcm(20 * rate, silent = listOf(1000..1999))            // 20초(무음 조금 섞음)
        val p = SegmentPacker.pack(audio, null, 29.0, 200)                 // Whisper 상한 29초
        assertEquals(1, p.chunks.size)                                     // 조각 1개
        assertEquals(0, p.splitCount)                                      // 자르지 않음
        assertEquals(0, p.joinSilenceSamples)                              // 무음 삽입 없음
        assertArrayEquals(audio, p.chunks[0])                              // 바이트 동일
    }

    /**
     * 목적: [UT-VAD-02] SEG10 — 에너지가 고른 100초를 10초 상한으로 자르면 조각 19개(자른 횟수 18), 모두 10초 이하.
     * 비고: 첫 창이 뽑히므로 조각 길이 = 5초 + 240샘플 = 80,240. 18조각 뒤 남은 155,680샘플이 마지막 조각.
     *       두 조각을 합치면 160,480 > 160,000 이라 묶이지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun seg10_uniformAudioGives19Chunks() {
        val total = 100 * rate                                             // 100초
        val p = SegmentPacker.pack(pcm(total), null, SegMode.SEG10.maxSec!!, 200)
        assertEquals(19, p.chunks.size)                                    // 조각 수
        assertEquals(18, p.splitCount)                                     // 자른 횟수
        assertTrue(sizes(p).all { it <= 10 * rate })                       // 모두 10초 이하
        assertEquals(List(18) { 80_240 } + 155_680, sizes(p))              // 조각별 길이
        assertEquals(total, sizes(p).sum())                                // 샘플 보존(묶인 조각이 없어 무음 삽입 0)
    }

    /**
     * 목적: [UT-VAD-02] SEG10 — 3초짜리 말 구간 6개는 사이 무음 200 ms 와 함께 3개씩 묶여 조각 2개가 된다.
     * 비고: 3 + 0.2 + 3 + 0.2 + 3 = 9.4초 ≤ 10초, 네 번째를 더하면 12.6초라 새 조각.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun seg10_shortSegmentsAreJoinedWithSilence() {
        val audio = pcm(30 * rate)                                         // 30초
        val segs = (0 until 6).map { VadSegment(it * 5 * rate, it * 5 * rate + 3 * rate) }   // 5초마다 3초씩 말
        val p = SegmentPacker.pack(audio, segs, 10.0, 200)
        val gap = rate * 200 / 1000                                        // 사이 무음 3,200샘플
        assertEquals(2, p.chunks.size)                                     // 조각 2개
        assertEquals(0, p.splitCount)                                      // 자르지 않음
        assertEquals(List(2) { 9 * rate + 2 * gap }, sizes(p))             // 조각마다 말 9초 + 무음 2번
        assertEquals(4 * gap, p.joinSilenceSamples)                        // 끼워 넣은 무음 합
    }

    /**
     * 목적: [UT-VAD-03] 상한보다 긴 구간은 상한의 50~100 % 범위에서 가장 조용한 곳에서 자른다.
     * 비고: 20초 신호, 무음은 2.0~2.5초(범위 밖)와 7.0~7.5초(범위 5~10초 안). 첫 절단은 7.0~7.5초 안이어야 한다.
     *       둘째 절단 범위에는 무음이 없어 첫 창(시작 + 5초 + 240샘플)에서 잘린다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun longSegment_isCutAtQuietestPointInUpperHalf() {
        val total = 20 * rate                                              // 20초
        val audio = pcm(total, silent = listOf(32_000 until 40_000, 112_000 until 120_000))
        val p = SegmentPacker.pack(audio, null, 10.0, 200)
        val s = sizes(p)                                                   // 조각별 샘플 수
        assertEquals(3, s.size)                                            // 조각 3개
        assertEquals(2, p.splitCount)                                      // 두 번 자름
        assertTrue("첫 절단이 7.0~7.5초 무음 안이 아님: ${s[0]}", s[0] in 112_001..120_000)
        assertEquals(80_240, s[1])                                         // 무음 없는 범위 → 첫 창
        assertTrue(s.all { it <= 10 * rate })                              // 모두 10초 이하
        assertEquals(total, s.sum())                                       // 샘플 보존
    }

    /**
     * 목적: 말 구간이 0개(빈 목록)이면 조각도 0개다(E-VAD-02 무음 파일의 전제).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun emptySegments_giveNoChunks() {
        val p = SegmentPacker.pack(pcm(5 * rate), emptyList(), 10.0, 200)  // 구간 목록이 비어 있음
        assertEquals(0, p.chunks.size)                                     // 조각 없음
        assertEquals(0, p.splitCount)                                      // 자른 횟수 0
    }
}
