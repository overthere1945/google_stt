/*
 * 파일명: ui/AppRoot.kt
 * 목적 및 기능:
 * - 앱 화면의 뼈대: 아래쪽 탭 3개(설정·실행·모델)와 그 내용(4단계 4-1). 화면 전환 라이브러리 없이 고른 탭 번호만 기억한다(Q12).
 * - 탭 내용은 밖에서 받는다 → 탭마다 따로 만들어 끼운다.
 *
 * add-hyungchul-20261106-2322 — [T7-1]
 */
package com.example.google_stt.ui

import androidx.compose.foundation.layout.Box                     // 내용 자리
import androidx.compose.foundation.layout.fillMaxSize             // 화면 채우기
import androidx.compose.foundation.layout.padding                 // 여백
import androidx.compose.material3.NavigationBar                   // 아래쪽 탭 줄
import androidx.compose.material3.NavigationBarItem               // 탭 하나
import androidx.compose.material3.Scaffold                        // 화면 틀
import androidx.compose.material3.Text                            // 글자
import androidx.compose.runtime.Composable                        // 컴포저블 표시
import androidx.compose.ui.Modifier                               // 꾸밈

/** 목적: 탭 종류. label - 탭 이름, mark - 아이콘 자리에 두는 글자(아이콘 묶음 의존성을 더하지 않으려고 글자를 쓴다) */
enum class AppTab(val label: String, val mark: String) {
    SETUP("설정", "⚙"),
    RUN("실행", "▶"),
    MODELS("모델", "⬇"),
}

/**
 * 목적: 탭 뼈대를 그린다.
 * 입력: tab - 지금 탭, onTab - 탭을 눌렀을 때, setup / run / models - 탭별 내용
 * 출력: 화면 / 리턴: 없음
 */
@Composable
fun AppRoot(
    tab: AppTab,
    onTab: (AppTab) -> Unit,
    setup: @Composable () -> Unit,
    run: @Composable () -> Unit,
    models: @Composable () -> Unit,
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (t in AppTab.values()) {
                    NavigationBarItem(selected = t == tab, onClick = { onTab(t) }, icon = { Text(t.mark) }, label = { Text(t.label) })
                }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                AppTab.SETUP -> setup()
                AppTab.RUN -> run()
                AppTab.MODELS -> models()
            }
        }
    }
}
