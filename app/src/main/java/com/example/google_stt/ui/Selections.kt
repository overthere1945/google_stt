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
import com.example.google_stt.vad.SegMode      // change-hyungchul-20261009-1530
import com.example.google_stt.vad.VadSetting   // change-hyungchul-20261009-1530

// add-hyungchul-20260825-1430
/**
 * 목적: START 를 누른 "그 순간"의 VAD 설정을 불변 snapshot 으로 고정한다.
 *       배치가 도는 중에 사용자가 UI 를 만져도 실행 조건이 바뀌지 않게 하기 위함이다.
 * change-hyungchul-20261009-1530 : 필드를 VAD 엔진 선택 구조로 바꿨다.
 *   예전: enabled / profile(VadProfile) / config(VadConfig) / outputFolder("vad_<profile>")
 *   지금: setting(엔진·프리셋·값) / segMode(구간 처리) — 조건 이름은 conditionToken 으로 만든다.
 */
data class VadSelection(
    val setting: VadSetting,                  // VAD 엔진·프리셋·실제 값
    val segMode: SegMode,                     // 구간 처리(엔진별 최대 길이 / 10초 통일)
) {
    /** VAD 를 쓰는지 */
    val enabled: Boolean get() = setting.enabled

    /** 조건 이름 중 VAD 부분(NOVAD / SILERO_SAFE / …) */
    val conditionToken: String get() = setting.conditionToken()
}

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
