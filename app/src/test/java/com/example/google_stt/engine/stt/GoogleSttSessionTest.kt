/*
 * 파일명: engine/stt/GoogleSttSessionTest.kt (app/src/test)
 * 목적 및 기능:
 * - ML Kit 세션 관리 규칙을 PC(JVM)에서 가상 시간으로 확인한다(실제 엔진 대신 가짜 세션 함수).
 *     · 응답이 없으면(AppError.SttTimeout) 180초 쉬고 그 조각만 1회 다시, 그래도 안 되면 SttTimeout 을 올린다 —
 *       **취소 예외(CancellationException)가 아니어야 한다**(파이프라인이 "취소됨" 으로 오인한다 — WP5 독립 검토 지적 1)
 *     · 세션 한도(35)를 넘긴 뒤에는 쉬지 않고 바로 포기, 28번째에 경고
 *     · 조각 합치기 규칙, warm-up 의 세 가지 결과(정상 / 먹통 / 대답했지만 쓸 수 없음), 바깥 취소는 그대로 취소
 *     · isEngineDead 가 새 오류(SttTimeout·MlKitStuck)와 v3 문구를 먹통으로 본다
 *
 * add-hyungchul-20261106-2216 — [T5-5]
 */
package com.example.google_stt.engine.stt

import com.example.google_stt.domain.AppError                      // 오류 코드
import java.io.IOException                                         // 엔진 오류
import kotlinx.coroutines.CancellationException                    // 취소
import kotlinx.coroutines.ExperimentalCoroutinesApi                // 가상 시간
import kotlinx.coroutines.awaitCancellation                        // 대답하지 않는 엔진
import kotlinx.coroutines.cancelAndJoin                            // 바깥 취소
import kotlinx.coroutines.launch                                   // 코루틴 시작
import kotlinx.coroutines.test.currentTime                         // 가상 시각
import kotlinx.coroutines.test.runCurrent                          // 밀린 일 돌리기
import kotlinx.coroutines.test.runTest                             // 코루틴 시험
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertNull                                 // null 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assert.fail                                       // 예외가 안 났을 때 실패
import org.junit.Test                                              // JUnit 4 시험 표시

/** 목적: GoogleSttSession 시험. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoogleSttSessionTest {

    private val logs = ArrayList<String>()                         // 경고 문장
    private var calls = 0                                          // 세션 함수가 불린 횟수
    private val ok = SessionResult("가", 10, 100, 5, 1)            // 정상 결과 하나

    /**
     * 목적: 응답 없음 → 180초 쉬고 1회 다시 → 성공. 두 번 다 안 되면 SttTimeout(취소 예외가 아니다)을 올린다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun timeout_retriesOnceAfterCooldown_thenThrowsSttTimeout() = runTest {
        var s = GoogleSttSession({ _, _ -> if (++calls == 1) throw AppError.SttTimeout("무응답") else ok }) { logs += it }
        assertEquals("가", s.transcribeChunks(listOf(ByteArray(4)), 100).text)
        assertEquals(2, calls); assertEquals(2, s.sessionCount)
        assertEquals(QUOTA_COOLDOWN_MS, currentTime); assertEquals(1, logs.size)       // 180초 쉬었다
        calls = 0
        s = GoogleSttSession({ _, _ -> calls++; throw AppError.SttTimeout("무응답") }) { logs += it }
        try {
            s.transcribeChunks(listOf(ByteArray(4)), 100); fail("SttTimeout 이 나야 한다")
        } catch (e: AppError.SttTimeout) {
            assertEquals("E-EN-03", e.code); assertTrue(isEngineDead(e))
            assertFalse("취소 예외로 보이면 파이프라인이 파일 ERROR 로 적지 못한다", (e as Throwable) is CancellationException)
        }
        assertEquals(2, calls)                                                         // 처음 1번 + 다시 1번
    }

    /**
     * 목적: 세션 한도를 넘긴 뒤의 무응답은 쉬지 않고 바로 올린다. 28번째 세션에서 경고한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun pastBudget_givesUpWithoutCooldown() = runTest {
        val s = GoogleSttSession({ _, _ -> if (++calls > QUOTA_BUDGET_SESSIONS) throw AppError.SttTimeout("무응답") else ok }) { logs += it }
        repeat(QUOTA_BUDGET_SESSIONS) { s.transcribeOne(ByteArray(4), 100) }           // 35개까지는 된다
        assertEquals(1, logs.size); assertTrue(logs[0].contains("${QUOTA_WARN_SESSIONS}개째")); assertFalse(s.pastBudget)
        try { s.transcribeChunks(listOf(ByteArray(4)), 100); fail("SttTimeout 이 나야 한다") } catch (e: AppError.SttTimeout) { /* 기대한 결과 */ }
        assertTrue(s.pastBudget); assertEquals(0L, currentTime)                        // 쉬지 않았다(쉬어도 소용없다 — v3 실측)
        assertEquals(QUOTA_BUDGET_SESSIONS + 1, calls)                                 // 다시 시도하지 않았다
        s.reset(); assertEquals(0, s.sessionCount); assertFalse(s.pastBudget)
    }

    /**
     * 목적: 조각 합치기 — 전사문은 공백으로 잇고(빈 것 제외), 첫 응답은 첫 조각, 공급·꼬리·문장 수는 합, 메모는 조각 번호를 붙인다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun chunks_mergeRules() = runTest {
        val parts = listOf(SessionResult(" 하나 ", 30, 1000, 20, 2), SessionResult("", -1, 500, 10, 0, "NO_SPEECH_DETECTED"), SessionResult("셋", 40, 700, -1, 1))
        val s = GoogleSttSession({ _, _ -> parts[calls++] }) { logs += it }
        val r = s.transcribeChunks(List(3) { ByteArray(4) }, 100)
        assertEquals("하나 셋", r.text); assertEquals(30L, r.firstResultMs)
        assertEquals(2200L, r.feedMs); assertEquals(30L, r.tailMs); assertEquals(3, r.finals)   // 못 잰 값(-1)은 합에 넣지 않는다
        assertEquals("[조각2] NO_SPEECH_DETECTED", r.note); assertEquals(3, s.sessionCount)
        calls = 0
        assertEquals("NO_SPEECH_DETECTED", GoogleSttSession({ _, _ -> parts[1] }) {}.transcribeChunks(listOf(ByteArray(4)), 100).note)   // 조각 1개면 그대로
    }

    /**
     * 목적: warm-up — 정상이면 null, 30초 안에 대답이 없으면 SttTimeout(먹통), 오류로 대답하면 그 오류(먹통 아님). 바깥 취소는 취소로 끝난다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun warmUp_threeOutcomes_andOuterCancel() = runTest {
        assertNull(GoogleSttSession({ pcm, delay -> assertEquals(BYTES_PER_SEC, pcm.size); assertEquals(WARMUP_FEED_DELAY_MS, delay); ok }) {}.warmUp())
        val stuck = GoogleSttSession({ _, _ -> awaitCancellation() }) {}.warmUp()        // 대답하지 않는 엔진
        assertTrue(stuck is AppError.SttTimeout); assertTrue(isEngineDead(stuck!!)); assertEquals(WARMUP_TIMEOUT_MS, currentTime)
        val broken = GoogleSttSession({ _, _ -> throw IOException("모델 다운로드 실패: not completed yet") }) {}.warmUp()
        assertTrue(broken is IOException); assertFalse(isEngineDead(broken!!))          // 대답은 했다 → 먹통이 아니라 적재 실패
        val s = GoogleSttSession({ _, _ -> calls++; awaitCancellation() }) { logs += it }
        val job = launch { s.transcribeChunks(listOf(ByteArray(4)), 100) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(1, calls); assertTrue(logs.isEmpty())  // 바깥 취소는 다시 시도하지 않고 취소로 끝난다
    }

    /**
     * 목적: 먹통 판정 — v4 의 오류 종류와 v3 의 문구를 모두 먹통으로 보고, 그 밖은 아니다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun isEngineDead_recognizesNewErrorsAndOldMessages() {
        assertTrue(isEngineDead(AppError.SttTimeout("x"))); assertTrue(isEngineDead(AppError.MlKitStuck("x")))
        assertTrue(isEngineDead(IOException("checkStatus() 가 15초 안에 응답하지 않았다.")))
        assertFalse(isEngineDead(AppError.ModelLoad("x"))); assertFalse(isEngineDead(IOException("모델 사용 불가: UNAVAILABLE")))
    }
}
