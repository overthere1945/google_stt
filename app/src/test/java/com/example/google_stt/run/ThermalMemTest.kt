/*
 * 파일명: run/ThermalMemTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-RUN-08] 발열 대기: SEVERE 이상이면 30초 간격으로 다시 보고, 식으면 진행, 20분이 지나면 그대로 진행(로그). 취소되면 대기 표시가 꺼진다.
 * - 메모리 기록: 시작하자마자 한 줄, 그 뒤 30초마다. 열 순서는 v3 와 같다. 쓰기가 실패해도 계속 돈다(경고는 한 번).
 *
 * add-hyungchul-20261106-2235 — [T6-3]
 */
package com.example.google_stt.run

import com.example.google_stt.data.device.MemoryInfo               // 메모리 값
import com.example.google_stt.domain.AppError                      // 쓰기 실패
import kotlinx.coroutines.ExperimentalCoroutinesApi                // 가상 시간
import kotlinx.coroutines.cancelAndJoin                            // 취소
import kotlinx.coroutines.launch                                   // 코루틴 시작
import kotlinx.coroutines.test.advanceTimeBy                       // 가상 시간 보내기
import kotlinx.coroutines.test.currentTime                         // 가상 시각
import kotlinx.coroutines.test.runCurrent                          // 밀린 일 돌리기
import kotlinx.coroutines.test.runTest                             // 코루틴 시험
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Test                                              // JUnit 4 시험 표시

/** 목적: ThermalGate·MemLogger 시험. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThermalMemTest {

    /**
     * 목적: [UT-RUN-08] 발열 대기의 네 가지 경우.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun thermalGate_waitsUntilCoolOrLimit() = runTest {
        val logs = ArrayList<String>(); val flags = ArrayList<Boolean>()
        var level = 2                                                                  // MODERATE
        val gate = ThermalGate({ level }, { logs += it })
        assertEquals(0L, gate.awaitCool { flags += it }); assertEquals(0L, currentTime); assertTrue(logs.isEmpty() && flags.isEmpty())   // 뜨겁지 않으면 바로
        var looks = 0
        val cooling = ThermalGate({ if (++looks > 5) 2 else 3 }, { logs += it })       // 몇 번 뒤에 식는다
        val waited = cooling.awaitCool { flags += it }
        assertEquals(waited, currentTime); assertTrue(waited in 30_000L..120_000L); assertEquals(0L, waited % 30_000L)   // 30초 간격
        assertEquals(listOf(true, false), flags); assertTrue(logs.last().contains("내려갔다"))
        level = 4; logs.clear()                                                        // CRITICAL 이 계속된다
        val t0 = currentTime
        assertEquals(20 * 60_000L, gate.awaitCool()); assertEquals(20 * 60_000L, currentTime - t0)   // 20분 뒤 그대로 진행
        assertTrue(logs.last().startsWith("E-RUN-03"))
        flags.clear()
        val job = launch { gate.awaitCool { flags += it } }
        advanceTimeBy(45_000); runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(listOf(true, false), flags)          // 취소돼도 대기 표시는 꺼진다
    }

    /**
     * 목적: 메모리 기록 — 줄 형식, 간격, 쓰기 실패에도 계속.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun memLogger_writesEveryInterval() = runTest {
        val lines = ArrayList<String>(); val logs = ArrayList<String>()
        var failing = false
        val logger = MemLogger(
            memory = { MemoryInfo(5973, 11089, 564, false) }, pssMb = { 134.7 }, thermal = { "LIGHT" },
            progress = { Triple(2, 33, listOf("kr/whisper-base/SILERO", "en/vosk-small-en/NOVAD,x")) }, now = { "2026-11-06 22:50:00" },
            append = { if (failing) throw AppError.OutputUnavailable("권한 없음") else lines += it }, log = { logs += it },
        )
        assertEquals(10, MemLogger.HEADER.split(",").size)
        assertEquals("2026-11-06 22:50:00,5973,11089,564,false,134,LIGHT,2,33,kr/whisper-base/SILERO | en/vosk-small-en/NOVAD x", logger.line())
        assertEquals(10, logger.line().split(",").size)                                // 작업 이름의 쉼표가 칸을 늘리지 않는다
        val job = launch { logger.run() }
        runCurrent(); assertEquals(1, lines.size)                                      // 시작하자마자 한 줄
        advanceTimeBy(65_000); runCurrent(); assertEquals(3, lines.size)               // 30초, 60초
        failing = true
        advanceTimeBy(90_000); runCurrent()
        assertEquals(3, lines.size); assertEquals(1, logs.size)                        // 실패해도 죽지 않고, 경고는 한 번
        failing = false
        advanceTimeBy(30_000); runCurrent(); assertEquals(4, lines.size)               // 다시 쓰인다
        job.cancelAndJoin()
    }
}
