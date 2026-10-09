/*
 * 파일명: ui/EpdUi.kt
 * 목적 및 기능:
 * - 화면의 Speech EPD 영역(방식 스피너 / 목표·최대 길이 / 긴 무음 입력칸)을 초기화한다.
 * - START 를 누른 순간의 EPD 설정을 검증해 EpdSelection snapshot 으로 만든다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 EPD UI 멤버 변수와 setupEpdUi / applyEpdEnabled / readEpdSelection /
 *   customEpdFolder 를 내용 변경 없이 옮겼다(fillEpdConfig 는 batch/BatchRunner.kt 로 옮겼다).
 */
package com.example.google_stt.ui

import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import com.example.google_stt.EpdConfig
import com.example.google_stt.EpdMode
import com.example.google_stt.R
import java.util.Locale

/**
 * 목적: Speech EPD 화면 컨트롤러.
 * 입력(생성자): activity — View 를 찾고 Adapter 를 만들 Activity, log — 화면 로그 함수
 */
class EpdUi(
    private val activity: AppCompatActivity,
    private val log: (String) -> Unit,
) {

    // add-hyungchul-20260914-2350 : Speech EPD UI
    private val epdModeSpinner: Spinner = activity.findViewById(R.id.epdModeSpinner)
    private val epdOptionsGroup: LinearLayout = activity.findViewById(R.id.epdOptionsGroup)
    private val epdTargetEdit: EditText = activity.findViewById(R.id.epdTargetEdit)
    private val epdMaxEdit: EditText = activity.findViewById(R.id.epdMaxEdit)
    private val epdSilenceEdit: EditText = activity.findViewById(R.id.epdSilenceEdit)

    // ──────────── add-hyungchul-20260914-2350 : Speech EPD UI ────────────
    /**
     * 목적: Speech EPD(무음 지점 분할) 스피너와 옵션 입력창을 초기화한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     * 동작: 기본값은 OFF 다(기존 baseline 과 100% 동일하게 시작한다).
     *       OFF 를 고르면 옵션 입력을 비활성화한다.
     *       B안(긴 무음)이 아닐 때는 "긴 무음(ms)" 칸을 비활성화한다(값을 바꿔도 결과가 안 바뀌므로).
     */
    fun setupEpdUi() {
        val modes = EpdMode.entries                                      // 표시 순서 = enum 선언 순서
        epdModeSpinner.adapter = ArrayAdapter(
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { it.displayName },
        )
        epdModeSpinner.setSelection(modes.indexOf(EpdMode.OFF))          // 기본은 OFF
        applyEpdEnabled(EpdMode.OFF)

        epdModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                applyEpdEnabled(modes[position.coerceIn(0, modes.lastIndex)])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * 목적: 고른 EPD 방식에 맞춰 입력창 활성/비활성을 정리하고 안내를 남긴다.
     * 입력: mode — 고른 방식
     * 출력: 없음 (View 상태를 바꾸고 로그를 남긴다)
     * 리턴: 없음
     */
    private fun applyEpdEnabled(mode: EpdMode) {
        setGroupEnabled(epdOptionsGroup, mode != EpdMode.OFF)
        // 긴 무음(ms) 은 B안에서만 의미가 있다.
        epdSilenceEdit.isEnabled = (mode == EpdMode.LONG_SILENCE)
        when (mode) {
            EpdMode.OFF -> Unit
            EpdMode.SPLIT_SESSION -> log(
                "[epd] A안 — 조각마다 STT 세션을 따로 돌린다. 61초 결함을 확실히 피하지만 " +
                    "세션 수가 늘어 엔진 먹통 노출도 함께 늘어난다.",
            )
            EpdMode.LONG_SILENCE -> log(
                "[epd] B안 — 세션은 1개이고 절단 지점의 무음만 길게 늘린다. 문맥은 유지되지만 " +
                    "ML Kit 가 그 무음에서 반드시 끊어준다는 보장은 없다.",
            )
        }
    }

    /**
     * 목적: START 를 누른 순간의 EPD 설정을 읽어 검증하고 snapshot 으로 만든다.
     * 입력: 없음 (입력창 값을 읽는다)
     * 출력: 없음
     * 리턴: EpdSelection
     * 예외: 값이 비었거나 범위를 벗어나면 IllegalArgumentException
     */
    fun readEpdSelection(): EpdSelection {
        val modes = EpdMode.entries
        val mode = modes[epdModeSpinner.selectedItemPosition.coerceIn(0, modes.lastIndex)]
        if (mode == EpdMode.OFF) {
            return EpdSelection(EpdConfig(EpdMode.OFF), null)            // 폴더명을 바꾸지 않는다
        }

        val target = epdTargetEdit.text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("EPD 목표 길이 값을 확인하세요.")
        val max = epdMaxEdit.text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("EPD 최대 길이 값을 확인하세요.")
        val silence = epdSilenceEdit.text.toString().trim().toIntOrNull()
            ?: throw IllegalArgumentException("EPD 긴 무음 값을 확인하세요.")

        val config = EpdConfig(                                          // EpdConfig 의 init 이 범위를 재검증한다
            mode = mode,
            targetSec = target,
            maxSec = max,
            longSilenceMs = silence,
        )
        return EpdSelection(config, customEpdFolder(config))
    }

    /**
     * 목적: EPD 설정이 다른 실행끼리 결과가 덮어써지지 않도록 설정값을 폴더명에 녹인다.
     * 입력: config — EPD 파라미터
     * 출력: 없음
     * 리턴: "epd_split_t45_m55" / "epd_gap_t45_m55_s1000" 형태
     */
    private fun customEpdFolder(config: EpdConfig): String {
        val t = String.format(Locale.US, "%.0f", config.targetSec)       // 45.0 → "45"
        val m = String.format(Locale.US, "%.0f", config.maxSec)
        val base = "epd_${config.mode.folderName}_t${t}_m$m"
        // 긴 무음은 B안에서만 결과를 바꾸므로 B안일 때만 폴더명에 넣는다.
        return if (config.mode == EpdMode.LONG_SILENCE) base + "_s${config.longSilenceMs}" else base
    }
}
