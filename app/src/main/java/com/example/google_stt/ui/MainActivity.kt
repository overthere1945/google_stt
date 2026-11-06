/*
 * 파일명: ui/MainActivity.kt
 * 목적 및 기능:
 * - v4 화면(Compose)의 Activity. 테마를 입히고 탭 뼈대(AppRoot)에 세 탭을 끼운다.
 * - v3 화면(루트 패키지의 MainActivity, View 기반)은 WP8 까지 그대로 두고, 그때 런처를 이 Activity 로 바꾼다(T8-1).
 *   그 전에는  adb shell am start -n com.example.google_stt/.ui.MainActivity  로 연다.
 *
 * add-hyungchul-20261106-2322 — [T7-1]
 */
package com.example.google_stt.ui

import android.os.Bundle                                          // 저장된 상태
import androidx.activity.ComponentActivity                        // Compose 를 올리는 Activity
import androidx.activity.compose.setContent                       // Compose 화면 넣기
import androidx.activity.enableEdgeToEdge                         // 화면 끝까지 그리기 + 표시줄 색
import androidx.compose.material3.Text                            // 자리 글자
import androidx.compose.runtime.getValue                          // 상태 읽기
import androidx.compose.runtime.mutableStateOf                    // 상태
import androidx.compose.runtime.saveable.rememberSaveable         // 화면 회전에도 남는 상태
import androidx.compose.runtime.setValue                          // 상태 쓰기
import com.example.google_stt.ui.theme.SttTheme                   // 테마

/** 목적: v4 화면 Activity. */
class MainActivity : ComponentActivity() {

    /** 목적: 화면을 올린다. 입력: savedInstanceState - 저장된 상태 / 출력: 화면 / 리턴: 없음 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()                                                         // 상태 표시줄 글자색을 테마(라이트/다크)에 맞춘다
        setContent {
            SttTheme {
                var tab by rememberSaveable { mutableStateOf(AppTab.SETUP) }       // 고른 탭(회전·재생성에도 유지 — FR-09-05)
                AppRoot(
                    tab = tab, onTab = { tab = it },
                    setup = { Text("설정 탭(T7-2)") }, run = { Text("실행 탭(T7-3)") }, models = { Text("모델 탭(T7-4)") },
                )
            }
        }
    }
}
