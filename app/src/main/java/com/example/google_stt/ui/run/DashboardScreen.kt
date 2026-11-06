/*
 * 파일명: ui/run/DashboardScreen.kt
 * 목적 및 기능:
 * - 실행 탭 화면(4단계 4-3): 전체 진행(n/N·막대·남은 시간) · 현재 작업 · 상태 · 기기 값 · 최근 로그 · [중지].
 *
 * add-hyungchul-20261106-2329 — [T7-3]
 */
package com.example.google_stt.ui.run

import androidx.compose.foundation.layout.Arrangement                 // 간격
import androidx.compose.foundation.layout.Column                      // 세로 배치
import androidx.compose.foundation.layout.Row                         // 가로 배치
import androidx.compose.foundation.layout.fillMaxSize                 // 화면 채우기
import androidx.compose.foundation.layout.fillMaxWidth                // 가로 채우기
import androidx.compose.foundation.layout.padding                     // 여백
import androidx.compose.material3.Button                              // 중지 버튼
import androidx.compose.material3.LinearProgressIndicator             // 진행 막대
import androidx.compose.material3.MaterialTheme                       // 글꼴·색
import androidx.compose.material3.Switch                              // 디버그 줄 보기
import androidx.compose.material3.Text                                // 글자
import androidx.compose.runtime.Composable                            // 컴포저블 표시
import androidx.compose.runtime.getValue                              // 상태 읽기
import androidx.compose.ui.Alignment                                  // 정렬
import androidx.compose.ui.Modifier                                   // 꾸밈
import androidx.compose.ui.unit.dp                                    // 길이 단위
import androidx.lifecycle.compose.collectAsStateWithLifecycle         // 상태 흐름 읽기
import com.example.google_stt.run.RunPhase                            // 실행 단계
import com.example.google_stt.run.RunState                            // 실행 상태
import com.example.google_stt.ui.common.LogList                       // 로그 목록
import com.example.google_stt.ui.common.SectionCard                   // 제목 있는 카드
import com.example.google_stt.ui.setup.durationText                   // 시간 표기
import java.util.Locale                                               // 숫자 표기 고정

/**
 * 목적: 실행 탭을 그린다.
 * 입력: vm - 실행 탭 ViewModel, onStop - [중지] 를 눌렀을 때(중지 대화창을 띄운다)
 * 출력: 화면 / 리턴: 없음
 */
@Composable
fun DashboardScreen(vm: DashboardViewModel, onStop: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val p = s.run.progress
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionCard("전체 ${p.finished} / ${p.total}" + if (p.total > 0) String.format(Locale.US, "  %.1f%%", p.finished * 100.0 / p.total) else "") {
            LinearProgressIndicator(progress = { if (p.total > 0) p.finished.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
            Text("경과 ${durationText(s.elapsedSec.toDouble())}" + (s.remainingSec?.let { " · 남은 시간 약 ${durationText(it)}" } ?: ""))
            Text("현재  " + p.running.joinToString(", ").ifEmpty { "-" })
            Text("상태  " + phaseText(s.run) + if (p.failed + p.skipped > 0) "  (실패 ${p.failed} · 건너뜀 ${p.skipped})" else "")
            if (s.run.message.isNotEmpty()) Text(s.run.message, color = MaterialTheme.colorScheme.error)
            Text(String.format(Locale.US, "기기  메모리 여유 %.1f GB · 앱 PSS %.1f GB · 발열 %s · 배터리 %s%s", s.device.availGb, s.device.pssGb, s.device.thermal,
                s.device.battery?.let { "$it%" } ?: "?", if (s.device.charging.isNotEmpty()) " 충전 중" else ""), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("최근 로그", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("디버그 줄", style = MaterialTheme.typography.labelMedium)
            Switch(checked = s.showDebug, onCheckedChange = vm::setShowDebug)
        }
        LogList(s.logs, Modifier.weight(1f).fillMaxWidth())
        Button(onClick = onStop, enabled = s.run.phase.active, modifier = Modifier.align(Alignment.End)) { Text("중지") }
    }
}

/**
 * 목적: 실행 단계를 화면 글자로 바꾼다.
 * 입력: run - 실행 상태 / 출력: 없음 / 리턴: String
 */
private fun phaseText(run: RunState): String = when (run.phase) {
    RunPhase.IDLE -> "대기(실행한 적 없음)"
    RunPhase.PREPARING -> "준비 중"
    RunPhase.RUNNING -> "실행 중" + if (run.stopAfterCurrent) " — 이 작업이 끝나면 멈춘다" else ""
    RunPhase.WAITING -> if (run.progress.waiting == "THERMAL") "발열 대기 — 식으면 이어서 돈다" else "모델 받는 중"
    RunPhase.STOPPING -> "중지하는 중(돌던 조각을 마치는 중일 수 있다)"
    RunPhase.FINISHED -> "끝남"
    RunPhase.STOPPED -> "중지됨"
    RunPhase.ERROR -> "오류로 멈춤"
}
