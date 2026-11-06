/*
 * 파일명: ui/common/Components.kt
 * 목적 및 기능:
 * - 세 탭이 같이 쓰는 화면 조각(4단계 4-2~4-4).
 *     · SectionCard : 제목이 있는 카드(폴더·엔진·VAD·요약·상태 묶음)
 *     · FolderRow   : 폴더 한 줄(이름표·경로·설명 + [변경])
 *     · ChipGroup   : 여러 개를 고르는 칩 묶음(엔진·모델, VAD 프리셋)
 *     · LogList     : 최근 로그(새 줄이 오면 맨 아래로 내려간다)
 *
 * add-hyungchul-20261106-2322 — [T7-1]
 */
package com.example.google_stt.ui.common

import androidx.compose.foundation.layout.Arrangement             // 간격
import androidx.compose.foundation.layout.Column                  // 세로 배치
import androidx.compose.foundation.layout.ExperimentalLayoutApi   // FlowRow
import androidx.compose.foundation.layout.FlowRow                 // 줄바꿈되는 가로 배치
import androidx.compose.foundation.layout.Row                     // 가로 배치
import androidx.compose.foundation.layout.fillMaxWidth            // 가로 채우기
import androidx.compose.foundation.layout.padding                 // 여백
import androidx.compose.foundation.lazy.LazyColumn                // 긴 목록
import androidx.compose.foundation.lazy.items                     // 목록 항목
import androidx.compose.foundation.lazy.rememberLazyListState     // 목록 위치
import androidx.compose.material3.Card                            // 카드
import androidx.compose.material3.FilterChip                      // 고르는 칩
import androidx.compose.material3.MaterialTheme                   // 글꼴·색
import androidx.compose.material3.Text                            // 글자
import androidx.compose.material3.TextButton                      // 글자 버튼
import androidx.compose.runtime.Composable                        // 컴포저블 표시
import androidx.compose.runtime.LaunchedEffect                    // 새 줄이 오면 내려가기
import androidx.compose.ui.Alignment                              // 정렬
import androidx.compose.ui.Modifier                               // 꾸밈
import androidx.compose.ui.text.font.FontFamily                   // 고정폭 글꼴
import androidx.compose.ui.text.style.TextOverflow                // 넘치는 글자 처리
import androidx.compose.ui.unit.dp                                // 길이 단위

/**
 * 목적: 제목이 있는 카드.
 * 입력: title - 제목, modifier - 꾸밈, content - 카드 안 내용 / 출력: 화면 / 리턴: 없음
 */
@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

/**
 * 목적: 폴더 한 줄.
 * 입력: label - 이름표("입력" / "출력"), path - 경로(고르지 않았으면 안내 문구), detail - 아래 설명(없으면 빈 글), onChange - [변경] 을 눌렀을 때
 * 출력: 화면 / 리턴: 없음
 */
@Composable
fun FolderRow(label: String, path: String, detail: String, onChange: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$label  $path", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onChange) { Text("변경") }
    }
}

/**
 * 목적: 여러 개를 고르는 칩 묶음.
 * 입력: items - (키, 표시 글자) 목록, selected - 고른 키들, onToggle - 칩을 눌렀을 때(키)
 * 출력: 화면 / 리턴: 없음
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipGroup(items: List<Pair<String, String>>, selected: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for ((key, label) in items) {
            FilterChip(selected = key in selected, onClick = { onToggle(key) }, label = { Text(label, style = MaterialTheme.typography.labelMedium) })
        }
    }
}

/**
 * 목적: 최근 로그 목록(새 줄이 오면 맨 아래로 내려간다).
 * 입력: lines - 로그 줄들(오래된 것부터), modifier - 꾸밈 / 출력: 화면 / 리턴: 없음
 */
@Composable
fun LogList(lines: List<String>, modifier: Modifier = Modifier) {
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.scrollToItem(lines.size - 1) }   // 자동 스크롤
    LazyColumn(modifier = modifier, state = state) {
        items(lines) { line -> Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}
