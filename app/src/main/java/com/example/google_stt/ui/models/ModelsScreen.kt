/*
 * 파일명: ui/models/ModelsScreen.kt
 * 목적 및 기능:
 * - 모델 탭 화면(4단계 4-4): 자산 목록(상태·크기·라이선스·상업 조건부) + [받기] [삭제], 위쪽에 [필요한 것 모두 받기] [PC 에서 가져오기] [받기 중지].
 *
 * add-hyungchul-20261106-2329 — [T7-4]
 */
package com.example.google_stt.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult   // 폴더 선택기 띄우기
import androidx.activity.result.contract.ActivityResultContracts      // 폴더 선택 약속
import androidx.compose.foundation.layout.Arrangement                 // 간격
import androidx.compose.foundation.layout.Column                      // 세로 배치
import androidx.compose.foundation.layout.ExperimentalLayoutApi       // FlowRow
import androidx.compose.foundation.layout.FlowRow                     // 줄바꿈되는 가로 배치
import androidx.compose.foundation.layout.Row                         // 가로 배치
import androidx.compose.foundation.layout.fillMaxSize                 // 화면 채우기
import androidx.compose.foundation.layout.padding                     // 여백
import androidx.compose.foundation.lazy.LazyColumn                    // 긴 목록
import androidx.compose.foundation.lazy.items                         // 목록 항목
import androidx.compose.material3.HorizontalDivider                   // 구분선
import androidx.compose.material3.MaterialTheme                       // 글꼴·색
import androidx.compose.material3.OutlinedButton                      // 보조 버튼
import androidx.compose.material3.Text                                // 글자
import androidx.compose.material3.TextButton                          // 글자 버튼
import androidx.compose.runtime.Composable                            // 컴포저블 표시
import androidx.compose.runtime.getValue                              // 상태 읽기
import androidx.compose.ui.Alignment                                  // 정렬
import androidx.compose.ui.Modifier                                   // 꾸밈
import androidx.compose.ui.unit.dp                                    // 길이 단위
import androidx.lifecycle.compose.collectAsStateWithLifecycle         // 상태 흐름 읽기
import com.example.google_stt.data.assets.AssetState                  // 자산 상태
import com.example.google_stt.data.assets.FailReason                  // 실패 이유
import com.example.google_stt.domain.catalog.AssetKind                // 자산 종류
import java.util.Locale                                               // 숫자 표기 고정

/**
 * 목적: 모델 탭을 그린다.
 * 입력: vm - 모델 탭 ViewModel, neededIds - 지금 조합에 필요한데 아직 없는 자산 ID 들을 주는 함수(설정 탭의 값)
 * 출력: 화면 / 리턴: 없음
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(vm: ModelsViewModel, neededIds: () -> List<String>) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.importFrom(uri) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("모델·VAD 자산 ${s.assets.size}개 · 준비됨 ${s.assets.count { it.state is AssetState.Ready }} · 여유 ${size(s.freeBytes)}", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.download(neededIds()) }) { Text("필요한 것 모두 받기") }
            OutlinedButton(onClick = { pickFolder.launch(null) }, enabled = !s.busy) { Text("PC 에서 가져오기") }
            OutlinedButton(onClick = { vm.stopDownload() }) { Text("받기 중지") }
        }
        if (s.message.isNotEmpty()) Text(s.message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f)) {
            items(s.assets, key = { it.id }) { a ->
                Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.id + if (a.conditional) " [상업 조건부]" else "", style = MaterialTheme.typography.bodyMedium)
                        Text("${if (a.kind == AssetKind.VAD_MODEL) "VAD" else "STT"} · ${a.bytes?.let(::size) ?: "크기 모름"} · ${a.license} · ${stateText(a.state)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (a.state is AssetState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    when (a.state) {
                        is AssetState.Ready -> TextButton(onClick = { vm.delete(a.id) }) { Text("삭제") }
                        is AssetState.Missing, is AssetState.Failed -> TextButton(onClick = { vm.download(listOf(a.id)) }) { Text("받기") }
                        else -> Unit                                                // 받는 중·푸는 중·확인 중에는 버튼을 두지 않는다
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * 목적: 자산 상태를 화면 글자로 바꾼다(실패는 이유별 안내 — 6단계 6-2).
 * 입력: state - 자산 상태 / 출력: 없음 / 리턴: String
 */
private fun stateText(state: AssetState): String = when (state) {
    is AssetState.Missing -> "없음"
    is AssetState.Downloading -> "받는 중" + (state.percent?.let { " $it%" } ?: "")
    is AssetState.Extracting -> "압축 푸는 중"
    is AssetState.Verifying -> "확인 중"
    is AssetState.Ready -> if (state.verified) "준비됨" else "준비됨(미검증)"
    is AssetState.Failed -> "실패: " + when (state.reason) {
        FailReason.TLS -> "회사망 인증서 검사로 보입니다. 외부망에서 받거나 PC 에서 받아 가져오기"
        FailReason.NETWORK -> "네트워크 오류 — 다시 받기"
        FailReason.HTTP -> "원본 주소가 바뀌었을 수 있습니다"
        FailReason.HASH -> "파일 손상(해시 불일치)"
        FailReason.EXTRACT -> "압축 해제 실패"
        FailReason.SPACE -> "저장공간 부족"
    }
}

/** 목적: 바이트를 MB / GB 글자로. 입력: bytes / 출력: 없음 / 리턴: String */
private fun size(bytes: Long): String =
    if (bytes >= 1024L * 1024 * 1024) String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0) else String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0)
