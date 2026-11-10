/*
 * 파일명: ui/dialogs/Dialogs.kt
 * 목적 및 기능:
 * - 실행 흐름의 대화창 5종(4단계 4-1·4-4).
 *     · LangDialog            : 이름으로 언어를 알 수 없는 폴더마다 [한국어] [영어] [건너뛰기]
 *     · ResumeDialog          : 지난 실행 요약 + [이어 하기] [처음부터] [닫기]
 *     · PreflightDialog       : 시작 전 점검(필수 항목이 통과해야 [진행])
 *     · DownloadConfirmDialog : 받아야 할 모델 수·크기·여유 공간 확인
 *     · StopDialog            : [지금 중지] [이 작업 끝나고] [취소]
 * - 대화창은 보여 주고 누름을 전하기만 한다. 순서와 판단은 부르는 쪽(MainActivity)이 맡는다.
 *
 * add-hyungchul-20261106-2330 — [T7-5]
 */
package com.example.google_stt.ui.dialogs

import androidx.compose.foundation.layout.Arrangement                 // 간격
import androidx.compose.foundation.layout.Column                      // 세로 배치
import androidx.compose.foundation.layout.ExperimentalLayoutApi       // FlowRow
import androidx.compose.foundation.layout.FlowRow                     // 줄바꿈되는 가로 배치
import androidx.compose.material3.AlertDialog                         // 대화창
import androidx.compose.material3.MaterialTheme                       // 글꼴·색
import androidx.compose.material3.Text                                // 글자
import androidx.compose.material3.TextButton                          // 글자 버튼
import androidx.compose.runtime.Composable                            // 컴포저블 표시
import androidx.compose.ui.unit.dp                                    // 길이 단위
import com.example.google_stt.domain.catalog.Language                 // 언어
import com.example.google_stt.domain.lang.LangFolder                  // 언어 폴더
import com.example.google_stt.ui.setup.PreflightCheck                 // 점검 항목
import com.example.google_stt.ui.setup.ResumeInfo                     // 지난 실행 요약
import java.util.Locale                                               // 숫자 표기 고정

/**
 * 목적: 언어 선택 대화창 — 폴더를 하나씩 묻는다(고르면 기억해 다음부터 묻지 않는다).
 * 입력: folder - 물을 폴더, left - 남은 폴더 수, onChoose - 골랐을 때(null = 건너뛰기), onCancel - 닫기
 * 출력: 화면 / 리턴: 없음
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LangDialog(folder: LangFolder, left: Int, onChoose: (Language?) -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("이 폴더의 언어는?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("\"${folder.name.ifEmpty { "(입력 폴더)" }}\" 폴더의 이름으로는 언어를 알 수 없다. 고른 값은 기억한다(남은 폴더 ${left}개).")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onChoose(Language.KO) }) { Text("한국어") }
                    TextButton(onClick = { onChoose(Language.EN) }) { Text("영어") }
                    TextButton(onClick = { onChoose(null) }) { Text("건너뛰기") }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}

/**
 * 목적: 이어 하기 대화창.
 * 입력: info - 지난 실행 요약, onResume - [이어 하기], onRestart - [처음부터], onClose - [닫기]
 * 출력: 화면 / 리턴: 없음
 */
@Composable
fun ResumeDialog(info: ResumeInfo, onResume: () -> Unit, onRestart: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("지난 실행이 남아 있다") },
        text = {
            Text("이 출력 폴더에 같은 조합의 기록이 있다 — 끝남 ${info.done} · 실패 ${info.failed} · 중단 ${info.interrupted}.\n" +
                "이어 하기: 끝난 것은 건너뛰고 남은 ${info.remaining}개만 돈다.\n처음부터: 앞선 결과 폴더를 _stale_ 로 치우고 모두 다시 돈다.")
        },
        confirmButton = { TextButton(onClick = onResume) { Text("이어 하기") } },
        dismissButton = {
            TextButton(onClick = onRestart) { Text("처음부터") }
            TextButton(onClick = onClose) { Text("닫기") }
        },
    )
}

/**
 * 목적: 시작 전 점검 대화창.
 * 입력: checks - 점검 항목(null 이면 확인하는 중), onRequestPermissions - [권한 요청], onBattery - [배터리 예외 요청],
 *       onProceed - [진행](필수 항목이 모두 통과해야 눌린다), onCancel - [취소]
 * 출력: 화면 / 리턴: 없음
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreflightDialog(checks: List<PreflightCheck>?, onRequestPermissions: () -> Unit, onBattery: () -> Unit, onProceed: () -> Unit, onCancel: () -> Unit) {
    val ready = checks != null && checks.none { it.required && it.ok == false }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("시작 전 점검") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (checks == null) Text("확인하는 중…")
                for (ch in checks.orEmpty()) {
                    val mark = when (ch.ok) { true -> "✔"; false -> if (ch.required) "✘" else "△"; null -> "ℹ" }
                    Text("$mark ${ch.title}" + if (ch.required) " (필수)" else "",
                        color = if (ch.ok == false && ch.required) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(ch.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onRequestPermissions) { Text("권한 요청") }
                    TextButton(onClick = onBattery) { Text("배터리 예외 요청") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onProceed, enabled = ready) { Text("진행") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}

/**
 * 목적: 다운로드 확인 대화창.
 * 입력: count - 받을 자산 수, bytes - 받을 크기, freeBytes - 여유 공간, wifiOnly - Wi-Fi 에서만 받는지, onConfirm - [받고 실행], onCancel - [취소]
 * 출력: 화면 / 리턴: 없음
 */
@Composable
fun DownloadConfirmDialog(count: Int, bytes: Long, freeBytes: Long, wifiOnly: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val gb = { b: Long -> String.format(Locale.US, "%.1f GB", b / 1024.0 / 1024.0 / 1024.0) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("모델을 받고 실행한다") },
        text = {
            Text("받을 모델 ${count}개, ${gb(bytes)} (여유 ${gb(freeBytes)}).\n작업마다 필요한 것을 받은 뒤 그 작업을 돈다" +
                (if (wifiOnly) " — Wi-Fi 에서만 받는다." else " — 모바일 데이터로도 받는다.") + "\n회사망에서 받기가 막히면 모델 탭의 \"PC 에서 가져오기\" 를 쓴다.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("받고 실행") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("취소") } },
    )
}

/**
 * 목적: 중지 대화창.
 * 입력: onNow - [지금 중지], onAfter - [이 작업 끝나고], onCancel - [취소]
 * 출력: 화면 / 리턴: 없음
 */
@Composable
fun StopDialog(onNow: () -> Unit, onAfter: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("실행을 멈출까?") },
        text = {
            Text("지금 중지: 돌던 작업은 중단으로 남고, 이어 하기 때 그 작업을 처음부터 다시 돈다. 큰 오프라인 모델은 돌던 조각을 마친 뒤(수 초~수십 초) 멈춘다.\n" +
                "이 작업 끝나고: 돌던 작업을 마친 뒤 멈춘다.")
        },
        confirmButton = { TextButton(onClick = onNow) { Text("지금 중지") } },
        dismissButton = {
            TextButton(onClick = onAfter) { Text("이 작업 끝나고") }
            TextButton(onClick = onCancel) { Text("취소") }
        },
    )
}
