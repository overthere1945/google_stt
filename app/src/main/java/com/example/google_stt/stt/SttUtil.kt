/*
 * 파일명: stt/SttUtil.kt
 * 목적 및 기능:
 * - STT 배치에서 공통으로 쓰는 작은 계산/판정 함수를 모은다.
 *     · ms()             : 두 nanoTime 사이를 ms 로 환산(미측정 -1)
 *     · isEngineDead()   : 엔진 먹통(시간 초과·checkStatus 무응답) 판정
 *     · isNoSpeechError(): ML Kit "말이 없다" 오류 판정
 *     · expectedFeedMs() : 현재 공급 속도에서의 이론 공급시간
 *     · describe()       : 예외를 한 줄 문자열로
 *     · sttTimeoutSec()  : 파일 1건의 STT 타임아웃 계산
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 private 함수들을 내용 변경 없이 옮겼다(private → 패키지 공개).
 */
package com.example.google_stt.stt

import kotlinx.coroutines.TimeoutCancellationException

/** 두 nanoTime 사이를 ms 로. 끝값이 0(미측정)이면 -1 을 돌려 CSV 에서 구분되게 한다. */
fun ms(fromNs: Long, toNs: Long): Long =
    if (fromNs <= 0L || toNs <= 0L || toNs < fromNs) -1L else (toNs - fromNs) / 1_000_000

/**
 * add-hyungchul-20260916-1000
 * 목적: "엔진이 죽어서 난 실패" 인지 판정한다.
 * 입력: e — 잡은 예외
 * 출력: 없음
 * 리턴: 엔진이 죽은 것으로 보이면 true
 *
 * ★ 이 판정이 필요한 이유 (2026-09-16 실측)
 *   엔진이 한도를 넘으면 증상이 두 단계로 진행된다.
 *     1단계 — startRecognition 이 아무것도 내보내지 않는다 → TimeoutCancellationException
 *     2단계 — checkStatus() 조차 대답하지 않는다            → IOException("...응답하지 않았다")
 *   기존 코드는 1단계(TimeoutCancellationException)만 세었다.
 *   그래서 2단계로 넘어가는 순간 연속 카운터가 0으로 초기화되어 배치 중단이 걸리지 않았고,
 *   죽은 엔진에 대고 남은 파일을 끝까지 다 시도했다(실측: 5건을 헛돌렸다).
 */
fun isEngineDead(e: Throwable): Boolean {
    if (e is TimeoutCancellationException) return true          // 1단계
    val m = e.message ?: return false
    return m.contains("응답하지 않았다") || m.contains("무응답")  // 2단계
}

// change-hyungchul-20261009-1514 : 원본에서 isEngineDead 위에 떨어져 있던 설명을 제자리로 옮겼다.
/**
 * add-hyungchul-20260914-1500
 * 목적: ML Kit 오류 메시지가 "말이 없다" 판정인지 가려낸다.
 * 입력: message — 엔진이 준 오류 문자열
 * 출력: 없음
 * 리턴: 음성 미검출 판정이면 true
 * 비고: 실측된 문자열 예:
 *   "Speech recognition engine is closed due to internal error: ERROR_TYPE_NO_SPEECH_DETECTED"
 */
fun isNoSpeechError(message: String): Boolean =
    message.contains("NO_SPEECH_DETECTED", ignoreCase = true) ||
            message.contains("ERROR_TYPE_NO_SPEECH", ignoreCase = true)

/**
 * add-hyungchul-20260914-1500
 * 목적: 이 PCM 을 현재 공급 속도로 흘려보낼 때 걸려야 하는 "이론상" 시간을 계산한다.
 * 입력: pcmBytes — STT 에 넣는 PCM 바이트 수, chunkDelayMs — chunk 사이 지연(ms)
 * 출력: 없음
 * 리턴: 이론 공급시간(ms). 지연 0(MAX)이거나 빈 PCM 이면 -1.
 * 비고: 실제 feed_ms 가 이 값보다 크게 늘어났다면 엔진이 파이프를 안 읽어
 *       write 가 막힌 것이다. 그 경우 stt_wall_ms 와 rtf 는 엔진 속도가 아니라
 *       "엔진이 멈춰 있던 시간" 을 재게 되므로 그대로 쓰면 안 된다.
 */
fun expectedFeedMs(pcmBytes: Int, chunkDelayMs: Long): Long {
    if (chunkDelayMs <= 0L || pcmBytes <= 0) return -1
    val chunks = (pcmBytes + CHUNK_BYTES - 1) / CHUNK_BYTES   // 올림 나눗셈
    return chunks * chunkDelayMs
}

fun describe(e: Throwable): String {
    val m = e.message
    return if (m.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $m"
}

/**
 * add-hyungchul-20260914-2130
 * 목적: 이 파일 1건에 적용할 STT 타임아웃(초)을 계산한다.
 * 입력: pcmBytes — 실제로 STT 에 들어가는 PCM 바이트 수
 *       feed     — 공급 속도 설정(chunk 당 지연)
 * 출력: 없음
 * 리턴: 타임아웃(초)
 *
 * 배경(실측 근거 — 기존 3회 실행, 정상 완료 112행을 전수 집계했다):
 *   기존 식은 (오디오초 × 2 + 180) 이라 90초 음원에서 360초를 기다렸다.
 *   실제로 엔진이 먹통이 된 3건(vLog/08 ×2, customer/06)에서 이 시간을 전부 날렸다.
 *
 *   [1] 공급 EOF → 최종 결과(tail_ms) 는 112행 전부에서 최대 287 ms 였다.
 *       즉 기존 식의 고정 +180초는 관측 최악값의 628배로, 순전히 군더더기였다.
 *   [2] 반대로 stt_wall_ms ≒ feed_ms 였다(둘의 차이 최대 287 ms).
 *       전체 소요시간은 "엔진이 파이프를 얼마나 빨리 읽어 가느냐" 가 지배한다.
 *   [3] 정상 완료한 행 중 가장 오래 걸린 경우는
 *       STT 입력 60.8초 → 101.7초(= 입력 길이의 1.67배) 였다.
 *
 *   그래서 고정분은 45초(=[1] 의 150배 여유), 비례분은 2.5배([3] 의 1.5배 여유)로 잡는다.
 *   이 값이면 관측된 모든 정상 행이 1.94배 이상 여유를 두고 통과하면서,
 *   먹통일 때는 기존보다 90초 이상 일찍 끊는다.
 *   ※ 만약 정상인데 타임아웃으로 끊기는 행이 생기면 STT_TIMEOUT_FACTOR 부터 올릴 것.
 *
 * 주의: 공급 지연이 0(MAX 모드) 이면 이론 공급시간을 0 으로 보고 오디오 길이만 쓴다.
 */
fun sttTimeoutSec(pcmBytes: Int, feed: FeedConfig): Long {
    val audioSec = pcmBytes.toDouble() / BYTES_PER_SEC           // 실제 STT 입력 길이(초)
    val expectedMs = expectedFeedMs(pcmBytes, feed.delayMs)      // 이론 공급시간(ms), 잴 수 없으면 -1
    val feedSec = if (expectedMs > 0) expectedMs / 1000.0 else 0.0
    val base = maxOf(audioSec, feedSec)                          // 둘 중 큰 쪽을 기준으로 삼는다
    val sec = STT_TIMEOUT_FIXED_SEC + base * STT_TIMEOUT_FACTOR  // 고정 여유 + 기준 × 배율
    return sec.toLong().coerceAtLeast(STT_TIMEOUT_MIN_SEC)       // 아주 짧은 음원도 최소치는 보장
}
