/*
 * 파일명: ui/run/DashboardViewModel.kt
 * 목적 및 기능:
 * - 실행 탭의 상태를 만든다(4단계 4-3·4-5): 실행 상태(RunController) + 최근 로그(AppLog) + 기기 값(메모리·PSS·발열·배터리) + 경과·남은 시간.
 * - 기기 값은 2초마다 읽는다(화면이 떠 있는 동안만). 남은 시간은 이번 실행에서 끝난 작업의 평균 소요로 어림한다
 *   (작업마다 길이가 달라 거친 값이다 — 실측 RTF 로 계산하는 것은 설정 탭의 요약이 한다).
 *
 * add-hyungchul-20261106-2329 — [T7-3]
 */
package com.example.google_stt.ui.run

import android.os.SystemClock                                        // 경과 시간
import androidx.lifecycle.ViewModel                                  // 화면 상태 보관
import androidx.lifecycle.viewModelScope                             // 화면 수명의 코루틴
import com.example.google_stt.AppContainer                           // 객체 묶음
import com.example.google_stt.run.RunState                           // 실행 상태
import com.example.google_stt.run.StopMode                           // 중지 방식
import kotlinx.coroutines.Dispatchers                                // 입출력 스레드
import kotlinx.coroutines.delay                                      // 2초 간격
import kotlinx.coroutines.flow.MutableStateFlow                      // 상태 보관
import kotlinx.coroutines.flow.SharingStarted                        // 화면이 볼 때만 계산
import kotlinx.coroutines.flow.StateFlow                             // 상태 읽기
import kotlinx.coroutines.flow.combine                               // 여러 흐름 합치기
import kotlinx.coroutines.flow.stateIn                               // 상태 흐름으로
import kotlinx.coroutines.withContext                                // 스레드 바꾸기

/**
 * 목적: 기기 값 한 줄.
 * 필드: availGb - 휴대폰 남은 메모리(GB), pssGb - 앱 PSS(GB), thermal - 발열 상태 이름, battery - 배터리 %(모르면 null), charging - 충전 종류(아니면 빈 글)
 */
data class DeviceUi(val availGb: Double = 0.0, val pssGb: Double = 0.0, val thermal: String = "", val battery: Int? = null, val charging: String = "")

/**
 * 목적: 실행 탭의 화면 상태.
 * 필드: run - 실행 상태, logs - 보여 줄 로그 줄들, device - 기기 값, elapsedSec - 이번 실행의 경과(초), remainingSec - 남은 시간 어림(모르면 null),
 *       showDebug - 디버그 줄([D])도 보일지
 */
data class DashboardUiState(
    val run: RunState = RunState(),
    val logs: List<String> = emptyList(),
    val device: DeviceUi = DeviceUi(),
    val elapsedSec: Long = 0,
    val remainingSec: Double? = null,
    val showDebug: Boolean = false,
)

/**
 * 목적: 실행 탭 ViewModel.
 * 입력(생성자): c - 객체 묶음
 */
class DashboardViewModel(private val c: AppContainer) : ViewModel() {

    private companion object {
        const val LOG_LINES = 50          // 화면에 보일 최근 줄 수(4-3)
        const val TICK_MS = 2_000L        // 기기 값을 읽는 간격
    }

    private val device = MutableStateFlow(DeviceUi())
    private val showDebug = MutableStateFlow(false)
    private val clock = MutableStateFlow(0L)                                        // 2초마다 바뀌는 값(경과 시간을 다시 그리게)

    /** 화면 상태 */
    val state: StateFlow<DashboardUiState> = combine(c.controller.state, c.log.recent, device, showDebug, clock) { run, logs, dev, debug, now ->   // change-hyungchul-20261107-0000 : WP7 독립 검토 4·5 — 기준 시각은 실행 상태에서 읽는다(부수 효과 없음)
        val end = if (run.phase.active || run.endedAtMs == 0L) maxOf(now, run.startedAtMs) else run.endedAtMs
        val elapsed = if (run.startedAtMs == 0L) 0L else (end - run.startedAtMs) / 1000
        val doneNow = run.progress.finished - run.finishedAtStart.coerceAtLeast(0)   // 이번 실행에서 끝난 작업 수
        val left = run.progress.total - run.progress.finished
        val remaining = if (run.phase.active && run.finishedAtStart >= 0 && doneNow > 0 && left > 0) elapsed.toDouble() / doneNow * left else null
        val lines = (if (debug) logs else logs.filter { !it.contains("[D]") }).takeLast(LOG_LINES)
        DashboardUiState(run, lines, dev, elapsed, remaining, debug)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    /**
     * 목적: 기기 값을 2초마다 읽는다. 화면이 보이는 동안만 부른다 — 취소될 때까지 돈다.
     * 입력: 없음 / 출력: 상태 / 리턴: 없음
     */
    suspend fun poll() {   // change-hyungchul-20261107-0000 : WP7 독립 검토 7
        while (true) {
            device.value = withContext(Dispatchers.IO) {
                val m = c.probe.memory()
                DeviceUi(m.availMb / 1024.0, (c.probe.pssMb() ?: 0.0) / 1024.0, c.probe.thermalStatus(), c.probe.batteryPercent(), c.probe.charging())
            }
            clock.value = SystemClock.elapsedRealtime()
            delay(TICK_MS)
        }
    }

    /** 목적: 디버그 줄을 보일지 바꾼다. 입력: on / 출력: 상태 / 리턴: 없음 */
    fun setShowDebug(on: Boolean) { showDebug.value = on }

    /** 목적: 실행을 멈춘다. 입력: mode - 지금 / 이 작업 끝나고 / 출력: 컨트롤러에 요청 / 리턴: 없음 */
    fun stop(mode: StopMode) = c.controller.stop(mode)
}
