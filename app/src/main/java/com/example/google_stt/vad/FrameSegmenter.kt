/*
 * 파일명: vad/FrameSegmenter.kt
 * 목적 및 기능:
 * - "프레임별 말 확률" 을 받아 Silero 공식 get_speech_timestamps() 와 같은 규칙으로 말 구간을 만든다.
 *     · threshold / neg_threshold(= max(threshold-0.15, 0.01)) 히스테리시스
 *     · min_speech / min_silence / speech_pad / max_speech(가장 긴 쉼에서 자르기) 규칙
 * - Silero(512 샘플 창), WebRTC(160/320/480 샘플 프레임, 확률 = 0 또는 1), TEN(160/256 hop)이 함께 쓴다.
 *
 * add-hyungchul-20261009-1530
 *   SileroVadProcessor.collectSpeechSegments() 의 본문을 그대로 옮기고, 고정값이던 창 크기(512)만
 *   windowSamples 인자로 바꿨다. Silero 는 windowSamples = 512 로 부르므로 기존 결과와 같다.
 *
 * 공식 근거: https://github.com/snakers4/silero-vad  src/silero_vad/utils_vad.py
 *            get_speech_timestamps() / get_speech_timestamps_from_probs() (v6.2.3, 2026-09-23 확인)
 */
package com.example.google_stt.vad

import com.example.google_stt.VadSegment
import kotlin.math.max
import kotlin.math.min

/**
 * 목적: 프레임 확률 → 말 구간 변환기(Silero 방식).
 */
object FrameSegmenter {

    /** 입력 오디오 샘플레이트(앱 전체가 16 kHz/mono/PCM16 이다) */
    const val SAMPLE_RATE = 16_000

    /** 공식 상수: min_silence_samples_at_max_speech = sampling_rate * 98 / 1000 */
    private const val MIN_SILENCE_AT_MAX_SPEECH_MS = 98

    /**
     * 목적: 상태 기계 결과(패딩 전 / 패딩 후 구간)를 함께 돌려준다.
     * 필드: raw — 패딩 전(계측용), padded — 패딩 후(실제로 잘라 쓸 구간)
     */
    class Result(
        val raw: List<VadSegment>,                            // 패딩 전 구간
        val padded: List<VadSegment>,                         // 패딩 후 구간
    )

    /** 상태 기계를 도는 동안 start/end 를 고쳐 쓰기 위한 가변 구간 */
    private class MutableSegment(var start: Int, var end: Int)

    /**
     * 목적: 공식 get_speech_timestamps() 의 상태 기계를 그대로 옮겨 말 구간을 만든다.
     * 입력: probabilities      — 프레임별 말 확률(0~1)
     *       windowSamples      — 프레임 1개의 샘플 수(Silero 512, WebRTC 160/320/480, TEN 160/256)
     *       audioLengthSamples — 전체 샘플 수
     *       threshold          — 말 판정 임계값(neg_threshold 는 공식대로 max(threshold-0.15, 0.01))
     *       minSpeechMs / minSilenceMs / speechPadMs — 공식과 같은 뜻(ms)
     *       maxSpeechSec       — null 이면 무제한(공식 기본 inf)
     * 출력: 없음
     * 리턴: Result(raw, padded)
     * 비고:
     * - use_max_poss_sil_at_max_speech = true (공식 기본값) 동작만 구현한다.
     * - 공식 Python 과 동일하게 min speech / min silence 비교에 엄격한 '>' 와 '<' 를 쓴다.
     */
    fun collect(
        probabilities: FloatArray,
        windowSamples: Int,
        audioLengthSamples: Int,
        threshold: Float,
        minSpeechMs: Int,
        minSilenceMs: Int,
        speechPadMs: Int,
        maxSpeechSec: Double?,
    ): Result {
        val negThreshold = max(threshold - 0.15f, 0.01f)                  // 공식 기본 neg_threshold
        // ── 공식 식 그대로: ms → sample 환산 ──
        val minSpeechSamples = SAMPLE_RATE * minSpeechMs / 1000.0
        val speechPadSamples = SAMPLE_RATE * speechPadMs / 1000.0
        val minSilenceSamples = SAMPLE_RATE * minSilenceMs / 1000.0
        val minSilenceAtMaxSamples = SAMPLE_RATE * MIN_SILENCE_AT_MAX_SPEECH_MS / 1000.0
        val maxSpeechSamples = maxSpeechSec?.let {
            SAMPLE_RATE * it - windowSamples - 2.0 * speechPadSamples     // 공식: sr*max - window - 2*pad
        } ?: Double.POSITIVE_INFINITY                                     // null = Unlimited

        var triggered = false                                    // 지금 speech 안인지
        var currentSpeech: MutableSegment? = null                // 진행 중인 구간
        var tempEnd = 0                                          // 잠정 무음 시작 위치
        var prevEnd = 0                                          // 직전 분할 후보 위치
        var nextStart = 0                                        // 분할 후 다음 구간 시작 위치
        val possibleEnds = mutableListOf<Pair<Int, Int>>()        // (무음 시작, 무음 길이) 후보들
        val speeches = mutableListOf<MutableSegment>()

        for (i in probabilities.indices) {
            val speechProb = probabilities[i]
            val curSample = windowSamples * i                     // 공식: cur_sample = window * i

            // (1) 무음이 끝나고 speech 가 다시 나오면, 그 무음을 max-speech 분할 후보로 저장한다.
            if (speechProb >= threshold && tempEnd != 0) {
                val silenceDuration = curSample - tempEnd
                if (silenceDuration > minSilenceAtMaxSamples) {
                    possibleEnds.add(tempEnd to silenceDuration)
                }
                tempEnd = 0
                if (nextStart < prevEnd) nextStart = curSample
            }

            // (2) speech 시작
            if (speechProb >= threshold && !triggered) {
                triggered = true
                currentSpeech = MutableSegment(start = curSample, end = 0)
                continue
            }

            // (3) Max Speech 초과 → 구간을 강제로 끊는다. (Unlimited 면 절대 실행되지 않는다)
            val current = currentSpeech
            if (triggered && current != null && (curSample - current.start) > maxSpeechSamples) {
                if (possibleEnds.isNotEmpty()) {
                    // 공식: prev_end, dur = max(possible_ends, key=lambda x: x[1])
                    val best = possibleEnds.maxByOrNull { it.second }!!
                    prevEnd = best.first
                    val duration = best.second
                    current.end = prevEnd                          // 가장 긴 무음 지점에서 끊는다
                    speeches.add(current)

                    nextStart = prevEnd + duration
                    // 공식: if next_start < prev_end -> triggered = False, else 새 구간을 next_start 에서 시작
                    if (nextStart < prevEnd) {
                        triggered = false
                        currentSpeech = null
                    } else {
                        currentSpeech = MutableSegment(start = nextStart, end = 0)
                    }
                    prevEnd = 0
                    nextStart = 0
                    tempEnd = 0
                    possibleEnds.clear()
                } else {
                    // 쓸 만한 무음 후보가 없으면 현재 frame 위치에서 그냥 끊는다.
                    current.end = curSample
                    speeches.add(current)
                    currentSpeech = null
                    prevEnd = 0
                    nextStart = 0
                    tempEnd = 0
                    triggered = false
                    possibleEnds.clear()
                    continue
                }
            }

            // (4) speech 중에 neg_threshold 아래로 떨어지면 잠정 무음이 시작된다.
            if (speechProb < negThreshold && triggered) {
                if (tempEnd == 0) tempEnd = curSample
                // 무음이 min silence 보다 짧으면 아직 구간을 끊지 않는다.
                if (curSample - tempEnd < minSilenceSamples) continue

                val speech = currentSpeech
                if (speech != null) {
                    speech.end = tempEnd
                    // 너무 짧은 speech 는 버린다(공식과 같이 엄격한 '>').
                    if (speech.end - speech.start > minSpeechSamples) speeches.add(speech)
                }
                currentSpeech = null
                prevEnd = 0
                nextStart = 0
                tempEnd = 0
                triggered = false
                possibleEnds.clear()
                continue
            }
        }

        // (5) 파일 끝까지 speech 가 이어졌으면 마지막 구간을 닫는다.
        val lastSpeech = currentSpeech
        if (lastSpeech != null && audioLengthSamples - lastSpeech.start > minSpeechSamples) {
            lastSpeech.end = audioLengthSamples
            speeches.add(lastSpeech)
        }

        // padding 전 구간 — result.csv 의 vad_detected_speech_sec 계산에 쓴다.
        val raw = speeches.mapNotNull { toSegment(it.start, it.end, audioLengthSamples) }

        // (6) 공식 padding 로직을 그대로 적용한다.
        val padded = raw.map { MutableSegment(it.startSample, it.endSample) }.toMutableList()
        val pad = speechPadSamples.toInt()
        for (i in padded.indices) {
            val speech = padded[i]
            if (i == 0) {
                speech.start = max(0, speech.start - pad)          // 첫 구간은 앞으로 pad
            }
            if (i != padded.lastIndex) {
                val next = padded[i + 1]
                val silenceDuration = next.start - speech.end
                if (silenceDuration < 2 * pad) {
                    // 두 구간 사이가 좁으면 무음을 절반씩 나눠 갖는다(겹침 방지).
                    val half = silenceDuration / 2
                    speech.end += half
                    next.start = max(0, next.start - half)
                } else {
                    speech.end = min(audioLengthSamples, speech.end + pad)
                    next.start = max(0, next.start - pad)
                }
            } else {
                speech.end = min(audioLengthSamples, speech.end + pad)  // 마지막 구간은 뒤로 pad
            }
        }

        val paddedImmutable = padded.mapNotNull { toSegment(it.start, it.end, audioLengthSamples) }
        return Result(raw = raw, padded = paddedImmutable)
    }

    /**
     * 목적: 구간 좌표를 오디오 길이 안으로 자르고, 길이가 0 이하이면 버린다.
     * 입력: start, end, length
     * 리턴: VadSegment 또는 null
     */
    fun toSegment(start: Int, end: Int, length: Int): VadSegment? {
        val s = start.coerceIn(0, length)                                  // 시작을 0~길이로 자른다
        val e = end.coerceIn(0, length)                                    // 끝을 0~길이로 자른다
        return if (e > s) VadSegment(s, e) else null                       // 빈 구간은 버린다
    }
}
