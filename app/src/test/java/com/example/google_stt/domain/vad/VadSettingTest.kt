/*
 * 파일명: domain/vad/VadSettingTest.kt (app/src/test)
 * 목적 및 기능:
 * - VadSetting.conditionToken() 이 만드는 조건 이름(결과 폴더·CSV 이름의 VAD 부분)을 확인한다(FR-03-02).
 *     · NOVAD, 고정 프리셋은 이름 그대로, CUSTOM 은 "<엔진>_CUSTOM_<약어값…>"
 *     · 사이 무음·최대 길이는 시작값과 같으면 이름에서 빠진다. FSMN 동적 기준에서는 snt 가 빠진다.
 * - 프리셋 목록이 서버와 맞춘 22개(NOVAD + 고정 21)인지, 모든 프리셋 값이 허용 범위 안인지 확인한다(FR-03-01).
 *
 * add-hyungchul-20261105-2354 — [T1-5]
 */
package com.example.google_stt.domain.vad

import org.junit.Assert.assertEquals   // 값 비교
import org.junit.Test                  // JUnit 4 시험 표시

/** 목적: VAD 조건 이름과 프리셋 목록 시험. */
class VadSettingTest {

    private val engines = listOf(VadEngine.SILERO, VadEngine.PYANNOTE, VadEngine.FSMN, VadEngine.FIRERED, VadEngine.TEN)   // VAD 엔진 5종

    /**
     * 목적: 이름으로 고정 프리셋을 찾아 실행 설정(VadSetting)을 만든다.
     * 입력: engine - 엔진, name - 프리셋 이름
     * 출력: 없음
     * 리턴: VadSetting - 프리셋 값 그대로인 설정
     */
    private fun fixed(engine: VadEngine, name: String): VadSetting {
        val preset = VadPresetCatalog.presets(engine).first { it.name == name }   // 이름이 같은 프리셋
        return VadSetting(engine, preset, preset.values)                          // 값은 프리셋 그대로
    }

    /**
     * 목적: 엔진의 Custom 프리셋에서 일부 값만 바꾼 실행 설정을 만든다.
     * 입력: engine - 엔진, change - 바꿀 (키, 값) 목록
     * 출력: 없음
     * 리턴: VadSetting - Custom 설정
     */
    private fun custom(engine: VadEngine, vararg change: Pair<String, Double>): VadSetting {
        val preset = VadPresetCatalog.customStart(engine)                         // Custom 시작값
        return VadSetting(engine, preset, preset.values + change)                 // 일부 값만 덮어쓴다
    }

    /**
     * 목적: VAD 를 쓰지 않으면 NOVAD, 고정 프리셋은 프리셋 이름 그대로다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun fixedPresets_useTheirNames() {
        val none = VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())   // VAD 사용 안 함
        assertEquals("NOVAD", none.conditionToken())
        assertEquals("SILERO", fixed(VadEngine.SILERO, "SILERO").conditionToken())
        assertEquals("SILERO_SAFE", fixed(VadEngine.SILERO, "SILERO_SAFE").conditionToken())
        assertEquals("PYANNOTE_S500", fixed(VadEngine.PYANNOTE, "PYANNOTE_S500").conditionToken())
        assertEquals("FSMN_CONFIG", fixed(VadEngine.FSMN, "FSMN_CONFIG").conditionToken())
        assertEquals("FIRERED_S2000", fixed(VadEngine.FIRERED, "FIRERED_S2000").conditionToken())
        assertEquals("TEN_ASR", fixed(VadEngine.TEN, "TEN_ASR").conditionToken())
    }

    /**
     * 목적: Silero Custom 이름 — 시작값과 같은 사이 무음·최대 길이는 빠지고, 다르면 항목 순서대로 붙는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun sileroCustom_nameTokens() {
        val a = custom(VadEngine.SILERO, VadKeys.THRESHOLD to 0.40, VadKeys.MIN_SILENCE_MS to 300.0, VadKeys.PAD_MS to 100.0)
        assertEquals("SILERO_CUSTOM_t40_sp250_si300_pad100", a.conditionToken())                 // 2단계 FR-03-02 예시
        val b = custom(VadEngine.SILERO, VadKeys.MAX_SPEECH_SEC to 20.0, VadKeys.JOIN_MS to 0.0)
        assertEquals("SILERO_CUSTOM_t50_sp250_si400_pad150_max20_join0", b.conditionToken())     // 시작값과 다르면 붙는다
    }

    /**
     * 목적: pyannote Custom 이름 — 소수점은 p 로 바뀐다(0.5 → 0p5, 0.15 → 0p15).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun pyannoteCustom_decimalPointBecomesP() {
        val s = custom(VadEngine.PYANNOTE, VadKeys.MIN_OFF_SEC to 0.5, VadKeys.PAD_ONSET_SEC to 0.15, VadKeys.PAD_OFFSET_SEC to 0.15)
        assertEquals("PYANNOTE_CUSTOM_on50_off50_mon0_moff0p5_pon0p15_poff0p15", s.conditionToken())
    }

    /**
     * 목적: FSMN Custom 이름 — 동적 기준(끝 무음 0)에서는 쓰이지 않는 snt 가 이름에서 빠진다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun fsmnCustom_dropsUnusedThresholdInDynamicMode() {
        assertEquals("FSMN_CUSTOM_mes0_mss60000", custom(VadEngine.FSMN).conditionToken())       // 동적 기준(시작값)
        val fixedSil = custom(VadEngine.FSMN, VadKeys.MAX_END_SILENCE_MS to 500.0, VadKeys.SPEECH_NOISE_THRES to 0.6)
        assertEquals("FSMN_CUSTOM_mes500_snt60_mss60000", fixedSil.conditionToken())             // 고정 무음이면 snt 포함
    }

    /**
     * 목적: 고정 프리셋은 엔진 5종 합쳐 21개, NOVAD 를 더해 22개이고 이름이 겹치지 않는다(v3 견본 "VAD22" 와 같은 수).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun presetCatalog_has22UniqueConditions() {
        val names = engines.flatMap { e -> VadPresetCatalog.presets(e).filter { !it.isCustom }.map { it.name } }   // 고정 프리셋 이름
        assertEquals(21, names.size)                                              // Silero 9 + pyannote 3 + FSMN 3 + FireRed 3 + TEN 3
        assertEquals(22, (names + VadPresetCatalog.NONE_PRESET.name).toSet().size) // NOVAD 포함 22, 중복 없음
        assertEquals(9, VadPresetCatalog.presets(VadEngine.SILERO).count { !it.isCustom })   // Silero 고정 9종
    }

    /**
     * 목적: 모든 프리셋(Custom 시작값 포함)의 값이 항목 정의 범위 안이다(validate 가 예외를 던지지 않는다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(범위를 벗어나면 IllegalArgumentException 으로 시험 실패)
     */
    @Test
    fun allPresets_passValidation() {
        for (e in engines) {
            for (p in VadPresetCatalog.presets(e)) {
                VadParamCatalog.validate(e, p.values)                             // 범위·필수 값 검사
            }
        }
    }
}
