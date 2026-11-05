/*
 * 파일명: domain/plan/PlanProgressTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-PLAN-03] 진행 기록(_plan_progress.csv) 읽기·쓰기를 확인한다(FR-06-06):
 *   DONE 만 완료, 깨진 줄은 건너뛰고 경고(E-PROG-01), 같은 키는 마지막 줄, 다시 쓰고 읽어도 같다.
 *
 * add-hyungchul-20261106-0046 — [T2-6a]
 */
package com.example.google_stt.domain.plan

import org.junit.Assert.assertEquals   // 값 비교
import org.junit.Assert.assertTrue     // 조건 확인
import org.junit.Test                  // JUnit 4 시험 표시

/** 목적: 진행 기록 시험. */
class PlanProgressTest {

    /**
     * 목적: [UT-PLAN-03] 진행 기록 — DONE 만 완료로 보고, 깨진 줄은 건너뛰며 경고로 알린다. 같은 키는 마지막 줄이 남는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun progress_doneOnly_andBrokenLinesWarn() {
        val text = "﻿# date=20261105\n# plan=x.csv\n" + PlanProgress.HEADER + "\n" +   // v3 파일의 "# date=" 줄은 주석으로 지나간다
            "kr/a,DONE,1,2026-11-06 00:00:00,2026-11-06 00:00:09,1,0,\n" +
            "kr/b,FAILED,1,2026-11-06 00:00:09,2026-11-06 00:00:10,0,1,ERR_MODEL_LOAD\n" +
            "kr/c,INTERRUPTED,1,2026-11-06 00:00:10,,0,0,\n" +
            "kr/d,DONE,1\n" +                                                 // 칸이 모자란 줄
            "kr/e,WHAT,1,a,b,0,0,\n" +                                        // 모르는 상태 값
            "kr/b,DONE,1,2026-11-06 00:01:00,2026-11-06 00:01:05,1,0,다시 성공\n" +
            "kr/f,SKIPPED,0,,,0,0,ERR_MLKIT_STUCK\n"
        val p = PlanProgress.parse(text)
        assertEquals(setOf("kr/a", "kr/b"), p.doneKeys)                       // b 는 마지막 줄(DONE)이 남는다
        assertEquals(listOf("kr/a", "kr/b", "kr/c", "kr/f"), p.rows.keys.toList())
        assertEquals("다시 성공", p.rows.getValue("kr/b").note)
        assertEquals(2, p.warnings.size)                                      // 깨진 줄 2개
        assertTrue(p.warnings[0].startsWith("7행:") && p.warnings[1].startsWith("8행:"))
        val again = PlanProgress.parse(PlanProgress.format("목록, 이름", p.rows.values))   // 다시 쓰고 읽어도 같다
        assertEquals(p.rows, again.rows)
        assertTrue(again.warnings.isEmpty())
        assertTrue(PlanProgress.format("목록, 이름", emptyList()).startsWith("﻿# plan=목록  이름\n"))   // 쉼표는 공백으로
    }
}
