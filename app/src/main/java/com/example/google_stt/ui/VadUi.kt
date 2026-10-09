/*
 * 파일명: ui/VadUi.kt
 * 목적 및 기능:
 * - 화면의 VAD 영역을 초기화하고 서로 연동한다.
 *     · VAD 엔진 스피너 : 사용 안 함 / Silero / WebRTC / pyannote / FSMN / FireRed / TEN
 *     · 프리셋 스피너   : 고른 엔진의 공식 기본값·추천값 프리셋 + Custom
 *     · 설정값 입력칸   : 고른 엔진의 항목만 동적으로 만든다(값을 고치면 자동으로 Custom 으로 바뀐다)
 *     · 구간 처리 스피너 : 엔진별 최대 길이 / 모든 엔진 10초 통일
 * - START 를 누른 순간의 설정을 검증해 VadSelection snapshot 으로 만든다.
 * - 고른 엔진의 모델 파일(assets) 유무를 바로 확인해 알린다.
 *
 * change-hyungchul-20261009-1530 — VAD 사용 스위치(vadSwitch) + Profile(Default/Safe/Custom) + 고정 입력칸 6개 구조를
 *   "엔진 선택 → 프리셋 선택 → 엔진별 입력칸" 구조로 바꿨다(사용자 요청). 예전 함수 이름(setupVadUi /
 *   readVadSelection / warnIfVadModelMissing)은 호출부가 바뀌지 않도록 유지했다.
 *   (이전 판: change-hyungchul-20261009-1514 에서 MainActivity.kt 로부터 옮긴 파일)
 */
package com.example.google_stt.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.google_stt.R
import com.example.google_stt.VadConfig
import com.example.google_stt.vad.SegMode
import com.example.google_stt.vad.VadDetectorFactory
import com.example.google_stt.vad.VadEngine
import com.example.google_stt.vad.VadParamCatalog
import com.example.google_stt.vad.VadParamSpec
import com.example.google_stt.vad.VadPreset
import com.example.google_stt.vad.VadPresetCatalog
import com.example.google_stt.vad.VadSetting

/**
 * 목적: VAD 화면 컨트롤러.
 * 입력(생성자): activity — View 를 찾고 Adapter 를 만들 Activity, log — 화면 로그 함수, toast — 토스트 함수
 */
class VadUi(
    private val activity: AppCompatActivity,
    private val log: (String) -> Unit,
    private val toast: (String) -> Unit,
) {

    // ── 화면 요소(activity_main.xml) ──
    private val vadEngineSpinner: Spinner = activity.findViewById(R.id.vadEngineSpinner)        // VAD 엔진
    private val vadOptionsGroup: LinearLayout = activity.findViewById(R.id.vadOptionsGroup)     // 엔진을 고르면 보이는 영역
    private val vadProfileSpinner: Spinner = activity.findViewById(R.id.vadProfileSpinner)      // 프리셋
    private val vadParamContainer: LinearLayout = activity.findViewById(R.id.vadParamContainer) // 동적 입력칸 자리
    private val vadInfoText: TextView = activity.findViewById(R.id.vadInfoText)                 // 출처·라이선스 안내
    private val segModeSpinner: Spinner = activity.findViewById(R.id.segModeSpinner)            // 구간 처리

    private val engines = VadEngine.entries                                 // 엔진 표시 순서 = enum 순서
    private val segModes = SegMode.entries                                  // 구간 처리 표시 순서
    private var currentEngine: VadEngine = VadEngine.NONE                   // 지금 고른 엔진
    private var currentPresets: List<VadPreset> = VadPresetCatalog.presets(VadEngine.NONE)   // 지금 엔진의 프리셋
    private val paramEdits = LinkedHashMap<String, EditText>()              // 키 → 입력칸(항목 순서 유지)

    /**
     * 프리셋 값을 코드로 채우는 동안 TextWatcher 가 Custom 으로 되돌리지 않게 막는 재진입 가드.
     * change-hyungchul-20261009-1626 : setText() 의 TextWatcher 는 즉시(동기) 불리므로 이 가드가 효과가 있다. Spinner.setSelection() 의
     *   onItemSelected 는 나중에(비동기) 불려 가드가 이미 풀린 뒤일 수 있으나, 그때는 같은 값을 다시 채울 뿐이라 결과가 같다.
     */
    private var applyingVadPreset = false

    /** 입력칸을 사람이 고치면 프리셋을 Custom 으로 바꾸는 감시기(모든 입력칸이 함께 쓴다) */
    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) {
            if (applyingVadPreset) return                                  // 코드가 채운 값이면 무시
            val pos = vadProfileSpinner.selectedItemPosition.coerceIn(0, currentPresets.lastIndex)
            if (!currentPresets[pos].isCustom) {                           // 사람이 고친 값이면 Custom 으로 전환
                applyingVadPreset = true
                vadProfileSpinner.setSelection(currentPresets.indexOfFirst { it.isCustom })
                applyingVadPreset = false
                showInfo(currentPresets.first { it.isCustom })
            }
        }
    }

    /**
     * 목적: VAD 엔진 / 프리셋 / 구간 처리 스피너를 초기화하고 서로 연동시킨다.
     * 입력: 없음 (멤버 View 를 사용)
     * 출력: 없음
     * 리턴: 없음
     * 동작: 시작 상태는 "VAD 사용 안 함 + 엔진별 최대 길이" = 기존 baseline 과 같다.
     */
    fun setupVadUi() {
        vadEngineSpinner.adapter = ArrayAdapter(                            // 엔진 목록
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            engines.map { it.displayName },
        )
        segModeSpinner.adapter = ArrayAdapter(                              // 구간 처리 목록
            activity,
            android.R.layout.simple_spinner_dropdown_item,
            segModes.map { it.displayName },
        )
        segModeSpinner.setSelection(segModes.indexOf(SegMode.ENGINE_MAX))   // 기본 = 기존 동작

        vadEngineSpinner.setSelection(engines.indexOf(VadEngine.NONE))      // 기본 = VAD 사용 안 함
        applyEngine(VadEngine.NONE)

        vadEngineSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val engine = engines[position.coerceIn(0, engines.lastIndex)]
                if (engine == currentEngine) return                         // 같은 엔진이면 다시 만들지 않는다
                applyEngine(engine)
                if (engine != VadEngine.NONE) warnIfVadModelMissing()       // 모델이 없으면 바로 알린다
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        vadProfileSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (applyingVadPreset) return                               // 코드가 고른 것이면 무시
                val preset = currentPresets[position.coerceIn(0, currentPresets.lastIndex)]
                if (!preset.isCustom) fillValues(preset.values)             // 고정 프리셋은 값을 채운다
                showInfo(preset)                                            // Custom 은 지금 값을 유지
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * 목적: 엔진을 바꾸면 프리셋 목록과 입력칸을 그 엔진에 맞게 다시 만든다.
     * 입력: engine — 고른 엔진
     * 출력: 없음(화면을 바꾼다)
     * 리턴: 없음
     */
    private fun applyEngine(engine: VadEngine) {
        currentEngine = engine
        currentPresets = VadPresetCatalog.presets(engine)
        applyingVadPreset = true
        try {
            vadProfileSpinner.adapter = ArrayAdapter(                       // 이 엔진의 프리셋 목록
                activity,
                android.R.layout.simple_spinner_dropdown_item,
                currentPresets.map { it.displayName },
            )
            vadProfileSpinner.setSelection(0)                               // 첫 프리셋(= 대표값)으로 시작
            buildParamRows(VadParamCatalog.specs(engine))                   // 입력칸 다시 만들기
            fillValues(currentPresets[0].values)
        } finally {
            applyingVadPreset = false
        }
        vadOptionsGroup.visibility = if (engine == VadEngine.NONE) View.GONE else View.VISIBLE
        showInfo(currentPresets[0])
    }

    /**
     * 목적: 엔진의 설정 항목마다 "이름 + 입력칸" 한 줄씩 만든다.
     * 입력: specs — 항목 정의 목록
     * 리턴: 없음
     */
    private fun buildParamRows(specs: List<VadParamSpec>) {
        paramEdits.values.forEach { it.removeTextChangedListener(watcher) } // 예전 입력칸 감시 해제
        paramEdits.clear()
        vadParamContainer.removeAllViews()
        for (spec in specs) {
            val label = TextView(activity).apply { text = spec.label }      // 항목 이름
            val edit = EditText(activity).apply {                           // 값 입력칸
                inputType = if (spec.isInt) {
                    InputType.TYPE_CLASS_NUMBER                             // 정수
                } else {
                    InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                        InputType.TYPE_NUMBER_FLAG_SIGNED                   // 실수(FSMN thres 는 음수 허용)
                }
                maxLines = 1
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            }
            vadParamContainer.addView(label)
            vadParamContainer.addView(edit)
            edit.addTextChangedListener(watcher)
            paramEdits[spec.key] = edit
        }
    }

    /**
     * 목적: 값 맵을 입력칸에 채운다(감시기가 Custom 으로 바꾸지 않게 가드를 건다).
     * 입력: values — 키 → 값
     * 리턴: 없음
     */
    private fun fillValues(values: Map<String, Double>) {
        val specs = VadParamCatalog.specs(currentEngine).associateBy { it.key }
        applyingVadPreset = true
        try {
            for ((key, edit) in paramEdits) {
                val v = values[key] ?: continue
                edit.setText(specs[key]?.format(v) ?: v.toString())
            }
        } finally {
            applyingVadPreset = false
        }
    }

    /**
     * 목적: 프리셋의 출처와 엔진 라이선스를 안내 문구로 보여 준다.
     * 입력: preset
     * 리턴: 없음
     */
    private fun showInfo(preset: VadPreset) {
        val e = preset.engine
        vadInfoText.text = if (e == VadEngine.NONE) "" else
            "출처: ${preset.description}\n라이선스: ${e.license} (상업 사용: ${e.commercial})"
    }

    /**
     * 목적: START 를 누른 순간의 VAD 설정을 읽어 검증하고 snapshot 으로 만든다.
     * 입력: 없음 (스피너·입력칸 값을 읽는다)
     * 출력: 없음
     * 리턴: VadSelection
     * 예외: 값이 비었거나 범위를 벗어나면 IllegalArgumentException (호출부에서 토스트로 안내한다)
     */
    fun readVadSelection(): VadSelection {
        val segMode = segModes[segModeSpinner.selectedItemPosition.coerceIn(0, segModes.lastIndex)]
        if (currentEngine == VadEngine.NONE) {                              // VAD 사용 안 함
            return VadSelection(VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap()), segMode)
        }
        val preset = currentPresets[vadProfileSpinner.selectedItemPosition.coerceIn(0, currentPresets.lastIndex)]
        val values: Map<String, Double> = if (preset.isCustom) {            // Custom 은 입력칸 값을 읽는다
            val labels = VadParamCatalog.specs(currentEngine).associate { it.key to it.label }
            paramEdits.mapValues { (key, edit) ->
                edit.text.toString().trim().toDoubleOrNull()
                    ?: throw IllegalArgumentException("${labels[key] ?: key} 값을 확인하세요.")   // change-hyungchul-20261009-1626 : 화면 이름으로 안내
            }
        } else {
            preset.values                                                   // 고정 프리셋은 정의값 그대로
        }
        VadParamCatalog.validate(currentEngine, values)                     // 범위 검증
        val setting = VadSetting(currentEngine, preset, values)
        if (currentEngine == VadEngine.SILERO || currentEngine == VadEngine.TEN) {
            VadConfig.fromSetting(setting)                                  // Silero 방식 추가 규칙(Max Speech 하한) 검증
        } else if (currentEngine == VadEngine.WEBRTC) {
            VadConfig.fromSetting(setting, thresholdOverride = 0.5f)
        }
        return VadSelection(setting, segMode)
    }

    /**
     * add-hyungchul-20260825-1620 / change-hyungchul-20261009-1530 (엔진별 파일 목록으로 확장)
     * 목적: 고른 엔진의 assets 모델 파일이 있는지 확인하고, 없으면 화면 로그와 토스트로 즉시 알린다.
     * 입력: 없음
     * 출력: 없음 (로그/토스트 출력)
     * 리턴: 파일이 모두 있으면 true
     * 비고: 여기서 막지는 않는다. 실제 차단은 VAD 실행기 생성 시점에서 예외로 처리한다.
     *       TEN VAD 는 assets 가 아니라 jniLibs 의 네이티브 라이브러리라 실행 시점에 확인한다.
     */
    fun warnIfVadModelMissing(): Boolean {
        val have = runCatching { activity.assets.list("")?.toSet() ?: emptySet() }.getOrDefault(emptySet())
        val missing = VadDetectorFactory.requiredAssets(currentEngine).filter { it !in have }
        if (missing.isNotEmpty()) {
            val msg = "assets/${missing.joinToString(", ")} 이 없습니다. " +
                "tools/download_models.sh 를 실행한 뒤 다시 빌드하세요."
            log("[vad][경고] $msg")
            toast(msg)
        }
        return missing.isEmpty()
    }
}
