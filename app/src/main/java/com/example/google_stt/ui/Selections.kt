/*
 * 파일명: ui/Selections.kt
 * 목적 및 기능:
 * - START 를 누른 순간의 VAD / Speech EPD 설정 snapshot 자료형.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 VadSelection / EpdSelection 을 내용 변경 없이 옮겼다.
 */
package com.example.google_stt.ui

import com.example.google_stt.EpdConfig
import com.example.google_stt.EpdMode
import com.example.google_stt.VadConfig
import com.example.google_stt.VadProfile

// add-hyungchul-20260825-1430
/**
 * 목적: START 를 누른 "그 순간"의 VAD 설정을 불변 snapshot 으로 고정한다.
 *       배치가 도는 중에 사용자가 UI 를 만져도 실행 조건이 바뀌지 않게 하기 위함이다.
 * 필드: enabled(ON/OFF), profile(표시용), config(실제 파라미터),
 *       outputFolder — null 이면 VAD OFF 이며 기존 output/google/<모드>/ 경로를 그대로 쓴다.
 */
data class VadSelection(
    val enabled: Boolean,
    val profile: VadProfile,
    val config: VadConfig,
    val outputFolder: String?,
)

// add-hyungchul-20260914-2350
/**
 * 목적: START 를 누른 "그 순간"의 Speech EPD 설정을 불변 snapshot 으로 고정한다.
 * 필드: config — EPD 파라미터,
 *       outputFolder — null 이면 EPD OFF 이며 폴더명을 더 붙이지 않는다.
 */
data class EpdSelection(
    val config: EpdConfig,
    val outputFolder: String?,
) {
    val enabled: Boolean get() = config.mode != EpdMode.OFF
}
