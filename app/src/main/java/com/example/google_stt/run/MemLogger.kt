/*
 * 파일명: run/MemLogger.kt
 * 목적 및 기능:
 * - 실행하는 동안 30초마다 휴대폰 남은 메모리·앱 PSS·발열·진행 상황을 한 줄씩 남긴다(_plan_memlog.csv, v3 Q46).
 *   앱이 메모리 부족으로 꺼졌을 때 직전 상태를 보려는 기록이다(꺼진 이유 자체는 ResumeManager 가 _plan_exits.csv 에 남긴다).
 * - 열은 v3 와 같다: time,avail_mb,total_mb,threshold_mb,low_memory,app_pss_mb,thermal,finished,total,running
 * - 값 읽기·줄 쓰기는 함수로 받는다 → PC(JVM)에서 가상 시간으로 시험한다. 쓰기가 실패해도 실행은 멈추지 않는다(경고만 남긴다).
 *
 * add-hyungchul-20261106-2235 — [T6-3]
 */
package com.example.google_stt.run

import com.example.google_stt.data.device.MemoryInfo   // 메모리 값
import com.example.google_stt.domain.AppError          // 쓰기 실패
import kotlinx.coroutines.delay                        // 다음 기록까지 기다리기

/**
 * 목적: 메모리 기록기.
 * 입력(생성자): memory - 휴대폰 메모리, pssMb - 앱 PSS(MB), thermal - 발열 상태 이름, progress - (끝난 작업 수, 전체 작업 수, 돌고 있는 작업 이름들),
 *               now - 지금 시각 글자("yyyy-MM-dd HH:mm:ss"), append - 한 줄을 파일 끝에 덧붙이는 함수, log - 경고, intervalMs - 간격
 */
class MemLogger(
    private val memory: () -> MemoryInfo,
    private val pssMb: () -> Double?,
    private val thermal: () -> String,
    private val progress: () -> Triple<Int, Int, List<String>>,
    private val now: () -> String,
    private val append: (String) -> Unit,
    private val log: (String) -> Unit = {},
    private val intervalMs: Long = 30_000L,
) {
    companion object {
        /** 머리줄(v3 와 같다) — 파일이 비어 있을 때 부르는 쪽이 먼저 쓴다 */
        const val HEADER = "time,avail_mb,total_mb,threshold_mb,low_memory,app_pss_mb,thermal,finished,total,running"
    }

    /**
     * 목적: 지금 값으로 한 줄을 만든다.
     * 입력: 없음 / 출력: 없음 / 리턴: String - 줄바꿈 없는 CSV 한 줄(작업 이름의 쉼표는 공백으로 바꾼다)
     */
    fun line(): String {
        val m = memory()
        val (finished, total, running) = progress()
        return listOf(
            now(), m.availMb, m.totalMb, m.thresholdMb, m.lowMemory, pssMb()?.toLong() ?: "", thermal(), finished, total,
            running.joinToString(" | ").replace(',', ' '),
        ).joinToString(",")
    }

    /**
     * 목적: 취소될 때까지 간격마다 한 줄씩 남긴다(시작하자마자 한 줄, 그 뒤 30초마다).
     * 입력: 없음 / 출력: 파일에 줄이 쌓인다 / 리턴: 없음(취소로만 끝난다)
     */
    suspend fun run() {
        var warned = false                                                 // 쓰기 실패 경고는 한 번만
        while (true) {
            try {
                append(line())
            } catch (e: AppError) {
                if (!warned) log("메모리 기록을 쓰지 못했다(${e.message}) — 실행은 계속한다")
                warned = true
            }
            delay(intervalMs)
        }
    }
}
