/*
 * 파일명: ui/VadUi.kt
 * 목적 및 기능:
 * - 화면의 VAD 영역(스위치 / Profile 스피너 / 파라미터 입력칸)을 초기화하고 서로 연동한다.
 * - START 를 누른 순간의 VAD 설정을 검증해 VadSelection snapshot 으로 만든다.
 * - Silero 모델 파일(assets) 유무를 확인해 알린다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 VAD UI 멤버 변수와 setupVadUi / applyVadPreset / readVadSelection /
 *   customVadFolder / warnIfVadModelMissing 을 내용 변경 없이 옮겼다.
 *   (setGroupEnabled 는 ui/UiUtils.kt, fillVadConfig 는 batch/BatchRunner.kt 로 옮겼다)
 */
package com.example.google_stt.ui

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.example.google_stt.R
import com.example.google_stt.SileroVadProcessor
import com.example.google_stt.VadConfig
import com.example.google_stt.VadPresets
import com.example.google_stt.VadProfile
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 목적: VAD 화면 컨트롤러.
 * 입력(생성자): activity — View 를 찾고 Adapter 를 만들 Activity, log — 화면 로그 함수, toast — 토스트 함수
 */
class VadUi(
    private val activity: AppCompatActivity,
    private val log: (String) -> Unit,
    private val toast: (String) -> Unit,
) {

    // add-hyungchul-20260825-1430 : Silero VAD UI
    private val vadSwitch: SwitchCompat = activity.findViewById(R.id.vadSwitch)
    private val vadOptionsGroup: LinearLayout = activity.findViewById(R.id.vadOptionsGroup)
    private val vadProfileSpinner: Spinner = activity.findViewById(R.id.vadProfileSpinner)
    private val vadThresholdEdit: EditText = activity.findViewById(R.id.vadThresholdEdit)
    private val vadMinSpeechEdit: EditText = activity.findViewById(R.id.vadMinSpeechEdit)
    private val vadMinSilenceEdit: EditText = activity.findViewById(R.id.vadMinSilenceEdit)
    private val vadSpeechPadEdit: EditText = activity.findViewById(R.id.vadSpeechPadEdit)
    private val vadMaxSpeechEdit: EditText = activity.findViewById(R.id.vadMaxSpeechEdit)

    // add-hyungchul-20260914-2130 : 구간 사이 무음(ms) 입력창 (PC GUI 의 --vad-join-silence-ms)
    private val vadJoinSilenceEdit: EditText = activity.findViewById(R.id.vadJoinSilenceEdit)

    /** preset 을 코드로 채워 넣는 동안 TextWatcher 가 Custom 으로 되돌리지 않게 막는 재진입 가드 */
    private var applyingVadPreset = false

    // ────────────────── add-hyungchul-20260825-1430 : Silero VAD UI ──────────────────
    /**
     * 목적: VAD 스위치 / Profile 스피너 / 파라미터 입력창을 초기화하고 서로 연동시킨다.
     * 입력: 없음 (멤버 View 를 사용)
     * 출력: 없음
     * 리턴: 없음
     * 동작: 시작 상태는 "VAD OFF + Default(PC GUI 기본값) 가 채워진 상태"다.  // change-hyungchul-20260914-2130
     *       값을 직접 고치면 Profile 이 Custom 으로 자동 전환된다.
     */
    fun setupVadUi() {
        // change-hyungchul-20260828-1000: values() 는 호출마다 배열을 새로 만든다. entries 는 불변 List 라 권장값이다.
        val profiles = VadProfile.entries                               // 표시 순서 = enum 선언 순서
        vadProfileSpinner.adapter = ArrayAdapter(                        // Profile 목록을 스피너에 채운다
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            profiles.map { it.displayName },
        )

        applyingVadPreset = true                                         // 초기 세팅 중에는 watcher 를 무시한다
        // change-hyungchul-20260914-2130 : ASR_SAFE → DEFAULT (PC GUI 기본값)
        vadProfileSpinner.setSelection(profiles.indexOf(VadProfile.DEFAULT))
        applyVadPreset(VadProfile.DEFAULT)                               // PC GUI 기본값을 입력창에 채운다
        applyingVadPreset = false

        vadSwitch.isChecked = false                                      // 기본값은 OFF = 기존 baseline
        setGroupEnabled(vadOptionsGroup, false)                          // OFF 면 옵션 입력을 비활성화한다
        vadSwitch.setOnCheckedChangeListener { _, checked ->
            setGroupEnabled(vadOptionsGroup, checked)
            // add-hyungchul-20260825-1620
            // START 를 눌러 preflight 까지 다 돈 뒤에 실패하면 시간이 아깝다. 켜는 즉시 알린다.
            if (checked) warnIfVadModelMissing()
        }

        vadProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingVadPreset) return                            // 코드가 고른 것이면 무시
                val profile = profiles[position.coerceIn(0, profiles.lastIndex)]
                if (profile != VadProfile.CUSTOM) applyVadPreset(profile)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (applyingVadPreset) return                            // preset 적용 중이면 무시
                val position = vadProfileSpinner.selectedItemPosition.coerceIn(0, profiles.lastIndex)
                if (profiles[position] != VadProfile.CUSTOM) {           // 사람이 고친 값이면 Custom 으로 전환
                    applyingVadPreset = true
                    vadProfileSpinner.setSelection(profiles.indexOf(VadProfile.CUSTOM))
                    applyingVadPreset = false
                }
            }
        }

        listOf(
            vadThresholdEdit,
            vadMinSpeechEdit,
            vadMinSilenceEdit,
            vadSpeechPadEdit,
            vadMaxSpeechEdit,
            vadJoinSilenceEdit,                                          // add-hyungchul-20260914-2130
        ).forEach { it.addTextChangedListener(watcher) }
    }

    /**
     * 목적: Profile 을 고르면 그 Profile 의 추천값을 입력창에 채운다.
     * 입력: profile — 채워 넣을 Profile (CUSTOM 이면 아무것도 하지 않는다)
     * 출력: 없음 (입력창 텍스트를 바꾼다)
     * 리턴: 없음
     */
    private fun applyVadPreset(profile: VadProfile) {
        if (profile == VadProfile.CUSTOM) return                          // Custom 은 사용자 값을 유지한다
        val config = VadPresets.config(profile)
        applyingVadPreset = true
        try {
            vadThresholdEdit.setText(String.format(Locale.US, "%.2f", config.threshold))
            vadMinSpeechEdit.setText(config.minSpeechDurationMs.toString())
            vadMinSilenceEdit.setText(config.minSilenceDurationMs.toString())
            vadSpeechPadEdit.setText(config.speechPadMs.toString())
            vadMaxSpeechEdit.setText(                                     // null(Unlimited) 은 UI 에서 0 으로 표기
                config.maxSpeechDurationSec?.let { String.format(Locale.US, "%.1f", it) } ?: "0",
            )
            // add-hyungchul-20260914-2130
            vadJoinSilenceEdit.setText(config.joinSilenceMs.toString())
        } finally {
            applyingVadPreset = false
        }
    }

    /**
     * 목적: START 를 누른 순간의 VAD 설정을 읽어 검증하고 snapshot 으로 만든다.
     * 입력: 없음 (입력창 값을 읽는다)
     * 출력: 없음
     * 리턴: VadSelection
     * 예외: 값이 비었거나 범위를 벗어나면 IllegalArgumentException (호출부에서 토스트로 안내한다)
     * 비고: Max Speech 에 0 을 넣으면 Unlimited(null) 로 해석한다.
     */
    fun readVadSelection(): VadSelection {
        if (!vadSwitch.isChecked) {                                       // OFF: 기존 baseline 경로 유지
            return VadSelection(
                enabled = false,
                profile = VadProfile.DEFAULT,                             // change-hyungchul-20260914-2130
                config = VadPresets.config(VadProfile.DEFAULT),
                outputFolder = null,
            )
        }

        val profiles = VadProfile.entries   // change-hyungchul-20260828-1000
        val profile = profiles[vadProfileSpinner.selectedItemPosition.coerceIn(0, profiles.lastIndex)]

        fun requiredFloat(edit: EditText, name: String): Float =
            edit.text.toString().trim().toFloatOrNull()
                ?: throw IllegalArgumentException("$name 값을 확인하세요.")

        fun requiredInt(edit: EditText, name: String): Int =
            edit.text.toString().trim().toIntOrNull()
                ?: throw IllegalArgumentException("$name 값을 확인하세요.")

        val maxSpeech = vadMaxSpeechEdit.text.toString().trim().toDoubleOrNull()
            ?: throw IllegalArgumentException("Max Speech 값을 확인하세요.")

        val config = VadConfig(                                           // VadConfig 의 init 이 범위를 재검증한다
            threshold = requiredFloat(vadThresholdEdit, "Threshold"),
            minSpeechDurationMs = requiredInt(vadMinSpeechEdit, "Min Speech"),
            minSilenceDurationMs = requiredInt(vadMinSilenceEdit, "Min Silence"),
            speechPadMs = requiredInt(vadSpeechPadEdit, "Speech Padding"),
            maxSpeechDurationSec = if (maxSpeech <= 0.0) null else maxSpeech,
            // add-hyungchul-20260914-2130 : 구간 사이 무음(ms)
            joinSilenceMs = requiredInt(vadJoinSilenceEdit, "구간 사이 무음"),
        )

        val outputFolder = when (profile) {                               // 결과를 profile 별로 분리 저장
            VadProfile.CUSTOM -> customVadFolder(config)
            else -> "vad_" + profile.folderName
        }
        return VadSelection(true, profile, config, outputFolder)
    }

    /**
     * 목적: Custom 값을 바꿔가며 여러 번 돌려도 서로 덮어쓰지 않도록 설정값을 폴더명에 녹인다.
     * 입력: config — Custom 파라미터
     * 출력: 없음
     * 리턴: "vad_custom_t50_sp250_si400_pad150_maxinf_join200" 형태의 폴더명
     * 비고: change-hyungchul-20260914-2130
     *   구간 사이 무음(join) 도 STT 입력을 바꾸므로 폴더명에 포함시켜야
     *   join 값만 다른 실행이 서로 덮어쓰지 않는다.
     */
    private fun customVadFolder(config: VadConfig): String {
        val threshold100 = (config.threshold * 100).roundToInt()          // 0.50 → 50 (폴더명에 소수점을 피한다)
        val maxText = config.maxSpeechDurationSec
            ?.let { String.format(Locale.US, "%.1f", it).replace('.', 'p') }
            ?: "inf"
        return "vad_custom_t${threshold100}_sp${config.minSpeechDurationMs}" +
                "_si${config.minSilenceDurationMs}_pad${config.speechPadMs}_max$maxText" +
                "_join${config.joinSilenceMs}"       // add-hyungchul-20260914-2130
    }

    /**
     * add-hyungchul-20260825-1620
     * 목적: assets 에 Silero 모델 파일이 있는지 확인하고, 없으면 화면 로그와 토스트로 즉시 알린다.
     * 입력: 없음
     * 출력: 없음 (로그/토스트 출력)
     * 리턴: 파일이 있으면 true
     * 비고: 여기서 막지는 않는다. 실제 차단은 SileroVadProcessor 생성 시점에서 예외로 처리한다.
     */
    fun warnIfVadModelMissing(): Boolean {
        val exists = runCatching {
            activity.assets.list("")?.contains(SileroVadProcessor.MODEL_ASSET) == true
        }.getOrDefault(false)
        if (!exists) {
            val msg = "assets/${SileroVadProcessor.MODEL_ASSET} 이 없습니다. " +
                    "tools/download_models.sh 를 실행한 뒤 다시 빌드하세요."
            log("[vad][경고] $msg")
            toast(msg)
        }
        return exists
    }
}
