/*
 * 파일명: run/ThermalGate.kt
 * 목적 및 기능:
 * - 작업을 시작하기 전에 기기가 뜨거우면 식을 때까지 기다린다(FR-06-07, v3 PlanRunner 의 발열 대기 규칙).
 *     발열 상태가 SEVERE 이상이면 30초마다 다시 보고, 최대 20분 기다린다. 20분이 지나면 그대로 진행한다(E-RUN-03) —
 *     무한정 서 있는 것보다 결과에 발열 상태를 남기고 가는 편이 낫다(device.csv 의 thermal 열).
 * - 발열 값은 함수로 받는다(앱에서는 DeviceProbe.thermalLevel) → PC(JVM)에서 가상 시간으로 시험한다.
 *
 * add-hyungchul-20261106-2235 — [T6-3]
 */
package com.example.google_stt.run

import kotlinx.coroutines.delay   // 다시 볼 때까지 기다리기

/**
 * 목적: 발열 대기.
 * 입력(생성자): level - 지금 발열 단계(PowerManager.currentThermalStatus: 0 NONE … 3 SEVERE … 6 SHUTDOWN),
 *               log - 로그 한 줄, pollMs - 다시 보는 간격, maxWaitMs - 가장 길게 기다리는 시간
 */
class ThermalGate(
    private val level: () -> Int,
    private val log: (String) -> Unit = {},
    private val pollMs: Long = 30_000L,
    private val maxWaitMs: Long = 20 * 60_000L,
) {
    companion object {
        /** 기다리기 시작하는 단계 — PowerManager.THERMAL_STATUS_SEVERE 와 같은 값(3) */
        const val SEVERE = 3
    }

    /**
     * 목적: 식을 때까지(SEVERE 미만) 기다린다.
     * 입력: onWaiting - 기다리기 시작하면 true, 끝나면 false 를 받는 함수(화면의 "발열 대기" 표시용)
     * 출력: 로그
     * 리턴: Long - 기다린 시간(ms). 뜨겁지 않았으면 0
     * 예외: 기다리는 중에 취소되면 CancellationException(onWaiting(false) 는 불린다)
     */
    suspend fun awaitCool(onWaiting: (Boolean) -> Unit = {}): Long {
        if (level() < SEVERE) return 0L                                    // 뜨겁지 않다
        log("발열 단계 ${level()} — SEVERE 미만이 될 때까지 기다린다(최대 ${maxWaitMs / 60_000}분)")
        var waited = 0L
        onWaiting(true)
        try {
            while (level() >= SEVERE && waited < maxWaitMs) {
                delay(pollMs)                                              // 30초 뒤 다시 본다
                waited += pollMs
            }
        } finally {
            onWaiting(false)
        }
        if (level() >= SEVERE) log("E-RUN-03: ${waited / 60_000}분을 기다려도 식지 않았다(단계 ${level()}) — 그대로 진행한다")
        else log("발열이 내려갔다(${waited / 1000}초 대기) — 진행한다")
        return waited
    }
}
