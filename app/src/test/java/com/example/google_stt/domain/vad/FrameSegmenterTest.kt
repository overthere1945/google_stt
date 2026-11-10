/*
 * 파일명: domain/vad/FrameSegmenterTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-VAD-04] FrameSegmenter.collect() 가 고정 확률 배열에서 Silero get_speech_timestamps() 규칙대로 구간을 만드는지 확인한다.
 * - 기대값은 공식 규칙(임계값 히스테리시스, min speech / min silence, padding, max speech)을 손으로 따라가 구했다.
 *   프레임 1개 = 512샘플(Silero), 16 kHz.
 *
 * add-hyungchul-20261105-2354 — [T1-5]
 */
package com.example.google_stt.domain.vad

import org.junit.Assert.assertEquals   // 값 비교
import org.junit.Test                  // JUnit 4 시험 표시

/** 목적: 프레임 확률 → 말 구간 변환 규칙 시험. */
class FrameSegmenterTest {

    private val win = 512                    // Silero 프레임 길이(샘플)

    /**
     * 목적: 말(0.9)인 프레임 범위만 지정해 확률 배열을 만든다(나머지는 0.0).
     * 입력: frames - 전체 프레임 수, speech - 말 프레임 범위 목록
     * 출력: 없음
     * 리턴: FloatArray - 프레임별 말 확률
     */
    private fun probs(frames: Int, vararg speech: IntRange): FloatArray =
        FloatArray(frames) { i -> if (speech.any { i in it }) 0.9f else 0.0f }   // 말이면 0.9, 아니면 0.0

    /**
     * 목적: 구간 목록을 (시작, 끝) 쌍 목록으로 바꿔 비교하기 쉽게 한다.
     * 입력: list - 구간 목록 / 출력: 없음 / 리턴: List<Pair<Int, Int>>
     */
    private fun pairs(list: List<VadSegment>): List<Pair<Int, Int>> = list.map { it.startSample to it.endSample }

    /**
     * 목적: 긴 말 1개 + min speech 보다 짧은 말 1개 → 짧은 것은 버리고, 앞뒤로 padding 을 붙인다.
     * 비고: 임계 0.5, min speech 250 ms(4,000샘플), min silence 100 ms(1,600샘플), pad 30 ms(480샘플).
     *       프레임 10~39 가 말 → 시작 5,120, 프레임 40 에서 무음 시작(20,480), 프레임 44 에서 무음 2,048 ≥ 1,600 이라 닫힘.
     *       프레임 60~62(1,536샘플)는 4,000 보다 짧아 버린다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun shortSpeechIsDropped_andPaddingApplied() {
        val r = FrameSegmenter.collect(probs(100, 10..39, 60..62), win, 100 * win, 0.5f, 250, 100, 30, null)
        assertEquals(listOf(5_120 to 20_480), pairs(r.raw))                // padding 전
        assertEquals(listOf(4_640 to 20_960), pairs(r.padded))             // 앞뒤 480샘플씩
    }

    /**
     * 목적: 파일 끝까지 말이 이어지면 마지막 구간을 파일 끝에서 닫는다(끝 쪽 padding 은 파일 길이를 넘지 않는다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun speechUntilEnd_isClosedAtAudioLength() {
        val r = FrameSegmenter.collect(probs(20, 5..19), win, 20 * win, 0.5f, 250, 100, 30, null)
        assertEquals(listOf(2_560 to 10_240), pairs(r.raw))                // 프레임 5 시작 ~ 파일 끝
        assertEquals(listOf(2_080 to 10_240), pairs(r.padded))             // 앞만 480샘플, 뒤는 파일 끝
    }

    /**
     * 목적: 두 구간 사이 무음이 padding 2배보다 좁으면 무음을 절반씩 나눠 갖는다(겹치지 않음).
     * 비고: pad 150 ms(2,400샘플), 구간 사이 3,072샘플 < 4,800 → 절반 1,536 씩.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun narrowGap_isSharedBetweenNeighbours() {
        val r = FrameSegmenter.collect(probs(61, 0..19, 26..45), win, 61 * win, 0.5f, 0, 100, 150, null)
        assertEquals(listOf(0 to 10_240, 13_312 to 23_552), pairs(r.raw))      // padding 전 두 구간
        assertEquals(listOf(0 to 11_776, 11_776 to 25_952), pairs(r.padded))   // 사이 무음을 반씩, 마지막은 뒤로 2,400
    }

    /**
     * 목적: max speech 를 넘으면 구간을 강제로 끊는다(무음 후보가 없으면 그 프레임 위치에서).
     * 비고: max 1.0초, pad 0 → 한도 16,000 - 512 = 15,488샘플. 시작에서 31프레임째(15,872)에 끊기고,
     *       끊긴 프레임 다음 프레임에서 새 구간이 시작된다(공식 동작 — 프레임 1개가 비게 된다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun maxSpeech_forcesSplitAtCurrentFrame() {
        val r = FrameSegmenter.collect(probs(100, 0..99), win, 100 * win, 0.5f, 0, 100, 0, 1.0)
        val want = listOf(0 to 15_872, 16_384 to 32_256, 32_768 to 48_640, 49_152 to 51_200)
        assertEquals(want, pairs(r.raw))                                   // padding 전
        assertEquals(want, pairs(r.padded))                                // pad 0 이라 같다
    }

    /**
     * 목적: 말이 전혀 없으면 구간이 없다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun noSpeech_givesNoSegments() {
        val r = FrameSegmenter.collect(probs(50), win, 50 * win, 0.5f, 250, 100, 30, null)
        assertEquals(emptyList<Pair<Int, Int>>(), pairs(r.raw))            // 구간 없음
        assertEquals(emptyList<Pair<Int, Int>>(), pairs(r.padded))         // padding 후에도 없음
    }
}
