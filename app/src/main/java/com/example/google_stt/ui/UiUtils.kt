/*
 * 파일명: ui/UiUtils.kt
 * 목적 및 기능:
 * - 여러 화면 컨트롤러(VadUi / EpdUi / NoiseUi)가 함께 쓰는 View 도우미 함수.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 setGroupEnabled 를 내용 변경 없이 옮겼다.
 */
package com.example.google_stt.ui

import android.view.ViewGroup

/**
 * 목적: VAD 옵션 컨테이너 안의 모든 자식 View 의 enabled 상태를 한 번에 바꾼다.
 * 입력: group — 대상 ViewGroup, enabled — 활성화 여부
 * 출력: 없음
 * 리턴: 없음
 */
fun setGroupEnabled(group: ViewGroup, enabled: Boolean) {
    group.isEnabled = enabled
    for (i in 0 until group.childCount) {
        val child = group.getChildAt(i)
        child.isEnabled = enabled
        if (child is ViewGroup) setGroupEnabled(child, enabled)      // 중첩 레이아웃까지 재귀 적용
    }
}

