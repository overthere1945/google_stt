/*
 * 파일명: ui/setup/SetupScreen.kt
 * 목적 및 기능:
 * - 설정 탭 화면(4단계 4-2): 폴더 → 엔진·모델 → VAD → 고급(접힘) → 조합 요약 → [조합 CSV 저장] [받고 실행].
 * - 상태는 SetupViewModel 이 들고, 이 파일은 그리기와 누름 전달만 한다. 실행 버튼 뒤의 대화창 흐름은 부르는 쪽(onStart)이 맡는다.
 *
 * add-hyungchul-20261106-2326 — [T7-2]
 */
package com.example.google_stt.ui.setup

import androidx.activity.compose.rememberLauncherForActivityResult   // 폴더 선택기 띄우기
import androidx.activity.result.contract.ActivityResultContracts      // 폴더 선택 약속
import androidx.compose.foundation.clickable                          // 접힘 제목 누르기
import androidx.compose.foundation.layout.Arrangement                 // 간격
import androidx.compose.foundation.layout.Column                      // 세로 배치
import androidx.compose.foundation.layout.ExperimentalLayoutApi       // FlowRow
import androidx.compose.foundation.layout.FlowRow                     // 줄바꿈되는 가로 배치
import androidx.compose.foundation.layout.Row                         // 가로 배치
import androidx.compose.foundation.layout.fillMaxWidth                // 가로 채우기
import androidx.compose.foundation.layout.padding                     // 여백
import androidx.compose.foundation.rememberScrollState                // 스크롤 위치
import androidx.compose.foundation.verticalScroll                     // 세로 스크롤
import androidx.compose.material3.AssistChip                          // 묶음 버튼 칩
import androidx.compose.material3.Button                              // 실행 버튼
import androidx.compose.material3.FilterChip                          // 하나 고르는 칩
import androidx.compose.material3.MaterialTheme                       // 글꼴·색
import androidx.compose.material3.OutlinedButton                      // 보조 버튼
import androidx.compose.material3.Switch                              // 켜고 끄기
import androidx.compose.material3.Text                                // 글자
import androidx.compose.runtime.Composable                            // 컴포저블 표시
import androidx.compose.runtime.getValue                              // 상태 읽기
import androidx.compose.runtime.mutableStateOf                        // 상태
import androidx.compose.runtime.saveable.rememberSaveable             // 회전에도 남는 상태
import androidx.compose.runtime.setValue                              // 상태 쓰기
import androidx.compose.ui.Alignment                                  // 정렬
import androidx.compose.ui.Modifier                                   // 꾸밈
import androidx.compose.ui.unit.dp                                    // 길이 단위
import androidx.lifecycle.compose.collectAsStateWithLifecycle         // 상태 흐름 읽기
import com.example.google_stt.domain.catalog.Catalog                  // 모델 목록
import com.example.google_stt.domain.catalog.Commercial               // 상업 조건부
import com.example.google_stt.domain.catalog.LangSupport              // 언어 전용
import com.example.google_stt.domain.catalog.Language                 // 언어 이름
import com.example.google_stt.domain.catalog.Runtime                  // 엔진 종류
import com.example.google_stt.domain.condition.FeedChoice             // 공급 속도
import com.example.google_stt.domain.condition.RunPurpose             // 실행 목적
import com.example.google_stt.domain.epd.EpdMode                      // EPD 방식
import com.example.google_stt.domain.lang.LangSource                  // 언어를 정한 방법
import com.example.google_stt.domain.vad.SegMode                      // 구간 처리
import com.example.google_stt.domain.vad.VadEngine                    // VAD 엔진
import com.example.google_stt.ui.common.ChipGroup                     // 칩 묶음
import com.example.google_stt.ui.common.FolderRow                     // 폴더 한 줄
import com.example.google_stt.ui.common.SectionCard                   // 제목 있는 카드
import java.util.Locale                                               // 숫자 표기 고정

/**
 * 목적: 설정 탭을 그린다.
 * 입력: vm - 설정 탭 ViewModel, onStart - [받고 실행] 을 눌렀을 때(대화창 흐름을 시작한다)
 * 출력: 화면 / 리턴: 없음
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(vm: SetupViewModel, onStart: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pickInput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.setInput(uri) }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) vm.setOutput(uri) }
    var advanced by rememberSaveable { mutableStateOf(false) }                       // 고급 옵션을 펼쳤는지

    Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── 폴더 ──
        SectionCard("폴더") {
            FolderRow("입력", s.inputPath ?: "(고르지 않음)", langText(s)) { pickInput.launch(null) }
            FolderRow("출력", s.outputPath ?: "(고르지 않음)", "") { pickOutput.launch(null) }
        }
        // ── 엔진·모델 ──
        SectionCard("엔진·모델 (${s.models.size}개 고름)") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((label, pick) in MODEL_GROUPS) AssistChip(onClick = { vm.setModels(Catalog.models.filter(pick).map { it.engine }.toSet()) }, label = { Text(label) })
                AssistChip(onClick = { vm.setModels(emptySet()) }, label = { Text("모두 해제") })
            }
            ChipGroup(Catalog.models.map { it.engine to it.engine + if (it.commercial == Commercial.CONDITIONAL) " [상업 조건부]" else "" }, s.models, vm::toggleModel)
        }
        // ── VAD ──
        SectionCard("VAD (${s.vads.size}개 고름)") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { vm.setVads(SetupViewModel.VAD_SETTINGS.keys) }, label = { Text("전체") })
                for (e in VadEngine.values()) {
                    val names = SetupViewModel.VAD_SETTINGS.filterValues { it.engine == e }.keys
                    AssistChip(onClick = { vm.setVads(names) }, label = { Text(if (e == VadEngine.NONE) "NOVAD" else "${e.token} ${names.size}") })
                }
            }
            ChipGroup(SetupViewModel.VAD_SETTINGS.keys.map { it to it }, s.vads, vm::toggleVad)
        }
        // ── 고급(접힘) ──
        SectionCard(if (advanced) "▾ 고급" else "▸ 고급 (구간 처리·공급 속도·노이즈 저감·EPD·실행 목적)", Modifier.clickable { advanced = !advanced }) {
            if (advanced) {
                Choice("구간 처리", SegMode.values().map { it to if (it == SegMode.ENGINE_MAX) "엔진별 최대 길이" else "10초 통일" }, s.seg, vm::setSeg)
                Choice("공급 속도(스트리밍·Vosk)", FeedChoice.values().map { it to if (it == FeedChoice.NONE) "사용 안 함(최대 속도)" else it.name }, s.feed, vm::setFeed)
                Choice("노이즈 저감", listOf<Pair<String?, String>>(null to "사용 안 함") + SetupViewModel.NS_CHOICES, s.ns, vm::setNs)
                Choice("EPD(ML Kit 61초 결함 회피)", listOf(EpdMode.OFF to "사용 안 함", EpdMode.SPLIT_SESSION to "세션 나누기", EpdMode.LONG_SILENCE to "긴 무음 넣기"), s.epd, vm::setEpd)
                Choice("실행 목적", listOf(RunPurpose.TIMING to "시간 측정(동시 1)", RunPurpose.ACCURACY to "정확도만(동시 1~3)"), s.purpose, vm::setPurpose)
                if (s.purpose == RunPurpose.ACCURACY) Choice("동시 작업 수", (1..RunPurpose.ACCURACY.maxWorkers).map { it to "$it" }, s.workers, vm::setWorkers)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("모델 받기는 Wi-Fi 에서만", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = s.wifiOnly, onCheckedChange = vm::setWifiOnly)
                }
            }
        }
        // ── 조합 요약 ──
        SectionCard("조합 요약") {
            val m = s.summary
            Text("${m.jobs}개 작업" + if (m.excluded > 0) " (제외 ${m.excluded}: 언어 전용 모델)" else "")
            Text("필요한 모델 ${m.assetsNeeded}개 · 준비됨 ${m.assetsReady} · 받을 것 ${m.assetsNeeded - m.assetsReady} (${gb(m.missingBytes)}, 여유 ${gb(m.freeBytes)})")
            Text("예상 " + (m.etaSec?.let(::durationText) ?: "알 수 없음") + if (m.etaUnknown > 0) " (실측이 없는 작업 ${m.etaUnknown}개는 빠짐)" else "")
            if (s.busy) Text("폴더를 읽는 중…", color = MaterialTheme.colorScheme.primary)
            if (s.message.isNotEmpty()) Text(s.message, color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                OutlinedButton(onClick = { vm.savePlanCsv() }) { Text("조합 CSV 저장") }
                Button(onClick = onStart, enabled = !s.busy) { Text(if (m.assetsNeeded > m.assetsReady) "받고 실행" else "실행") }
            }
        }
    }
}

/** 모델 묶음 버튼: 이름 → 고르는 조건 */
private val MODEL_GROUPS: List<Pair<String, (com.example.google_stt.domain.catalog.ModelSpec) -> Boolean>> = listOf(
    "전체" to { _ -> true },
    "오프라인" to { m -> m.runtime == Runtime.SHERPA_OFFLINE },
    "스트리밍" to { m -> m.runtime == Runtime.SHERPA_STREAMING || m.runtime == Runtime.VOSK },
    "상업 조건부" to { m -> m.commercial == Commercial.CONDITIONAL },
    "영어 전용" to { m -> m.langSupport == LangSupport.EN_ONLY },
)

/**
 * 목적: 하나만 고르는 줄(이름표 + 칩들).
 * 입력: label - 이름표, options - (값, 표시 글자) 목록, selected - 지금 값, onSelect - 골랐을 때
 * 출력: 화면 / 리턴: 없음
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choice(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((value, text) in options) FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
        }
    }
}

/**
 * 목적: 입력 폴더의 언어 판정을 한 줄로 만든다.
 * 입력: s - 화면 상태 / 출력: 없음 / 리턴: String - 예 "kr → 한국어(자동) 24개 · en → 영어(자동) 30개"
 */
private fun langText(s: SetupUiState): String {
    val plan = s.langPlan ?: return ""
    val parts = plan.folders.map { f ->
        val lang = if (f.lang == Language.KO) "한국어" else "영어"
        val how = if (f.source == LangSource.REMEMBERED || f.source == LangSource.ASKED) "기억" else "자동"
        "${f.name.ifEmpty { "(이 폴더)" }} → $lang($how) ${s.counts[f.name] ?: 0}개"
    } + plan.unknown.map { "${it.name.ifEmpty { "(이 폴더)" }} → 언어를 골라야 함" } + plan.skipped.map { "${it.name} → 건너뜀" }
    return parts.joinToString(" · ").ifEmpty { "음원 폴더를 찾지 못했다" }
}

/** 목적: 바이트를 GB 글자로. 입력: bytes / 출력: 없음 / 리턴: String - 예 "3.1 GB" */
private fun gb(bytes: Long): String = String.format(Locale.US, "%.1f GB", bytes / 1024.0 / 1024.0 / 1024.0)

/** 목적: 초를 "N일 N시간 N분" 글자로. 입력: sec / 출력: 없음 / 리턴: String */
fun durationText(sec: Double): String {
    val total = sec.toLong().coerceAtLeast(0L)
    val d = total / 86_400; val h = total % 86_400 / 3_600; val m = total % 3_600 / 60
    return when {
        d > 0 -> "${d}일 ${h}시간"
        h > 0 -> "${h}시간 ${m}분"
        m > 0 -> "${m}분 ${total % 60}초"
        else -> "${total}초"
    }
}
