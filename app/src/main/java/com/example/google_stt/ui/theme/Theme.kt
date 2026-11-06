/*
 * 파일명: ui/theme/Theme.kt
 * 목적 및 기능:
 * - 앱 화면의 색·글꼴 묶음(Material 3). 기기의 라이트/다크 설정을 따르고, 색은 기기 배경에서 뽑은 동적 색을 쓴다(minSdk 31 이라 항상 지원).
 *
 * add-hyungchul-20261106-2322 — [T7-1]
 */
package com.example.google_stt.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme           // 기기의 다크 설정
import androidx.compose.material3.MaterialTheme                   // 테마
import androidx.compose.material3.dynamicDarkColorScheme          // 동적 색(다크)
import androidx.compose.material3.dynamicLightColorScheme         // 동적 색(라이트)
import androidx.compose.runtime.Composable                        // 컴포저블 표시
import androidx.compose.ui.platform.LocalContext                  // Context

/**
 * 목적: 앱 테마를 입힌다.
 * 입력: content - 테마 안에 그릴 화면 / 출력: 화면 / 리턴: 없음
 */
@Composable
fun SttTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)   // 기기 설정을 따른다
    MaterialTheme(colorScheme = colors, content = content)
}
