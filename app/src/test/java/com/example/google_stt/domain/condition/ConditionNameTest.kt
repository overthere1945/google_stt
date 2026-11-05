/*
 * 파일명: domain/condition/ConditionNameTest.kt (app/src/test)
 * 목적 및 기능:
 * - 조건 이름 규칙을 확인한다.
 *     · UT-COND-01 v3 와 같은 이름(NS / VAD / SEG10 / EPD 조합, CUSTOM)
 *     · UT-COND-02 Feed 토큰(D26): 스트리밍·Vosk 에서 고른 경우만
 * - 런타임에 따라 적용되지 않는 옵션이 걸러지는지(effectiveFor) 확인한다.
 *
 * add-hyungchul-20261106-0042 — [T2-4]
 */
package com.example.google_stt.domain.condition

import com.example.google_stt.domain.catalog.Runtime          // 런타임
import com.example.google_stt.domain.epd.EpdConfig            // EPD 파라미터
import com.example.google_stt.domain.epd.EpdMode              // EPD 방식
import com.example.google_stt.domain.vad.SegMode              // 구간 처리
import com.example.google_stt.domain.vad.VadEngine            // VAD 엔진
import com.example.google_stt.domain.vad.VadKeys              // VAD 설정 키
import com.example.google_stt.domain.vad.VadPresetCatalog     // 프리셋 목록
import com.example.google_stt.domain.vad.VadSetting           // VAD 설정
import org.junit.Assert.assertEquals                          // 값 비교
import org.junit.Assert.assertNull                            // null 확인
import org.junit.Test                                         // JUnit 4 시험 표시

/** 목적: 조건 이름 시험. */
class ConditionNameTest {

    private val novad = VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())   // VAD 사용 안 함

    /**
     * 목적: 이름으로 고정 프리셋 설정을 만든다.
     * 입력: engine - 엔진, name - 프리셋 이름 / 출력: 없음 / 리턴: VadSetting
     */
    private fun preset(engine: VadEngine, name: String): VadSetting {
        val p = VadPresetCatalog.presets(engine).first { it.name == name }   // 이름이 같은 프리셋
        return VadSetting(engine, p, p.values)
    }

    private val silero = preset(VadEngine.SILERO, "SILERO")                  // 기준선 프리셋
    private val epdA = EpdChoice(EpdConfig(EpdMode.SPLIT_SESSION))           // A안, 목표 45 / 상한 55
    private val epdB = EpdChoice(EpdConfig(EpdMode.LONG_SILENCE))            // B안, 긴 무음 1000 ms

    /**
     * 목적: [UT-COND-01] 기본 옵션이면 조건 이름은 VAD 토큰뿐이다(NS·EPD·Feed 토큰 없음 — FR-03-04).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun defaults_giveVadTokenOnly() {
        for (rt in Runtime.values()) {
            assertEquals("NOVAD", ConditionName.build(novad, RunOptions(), rt))
            assertEquals("SILERO", ConditionName.build(silero, RunOptions(), rt))
        }
        assertEquals("PYANNOTE_S500_SEG10",
            ConditionName.build(preset(VadEngine.PYANNOTE, "PYANNOTE_S500"), RunOptions(seg = SegMode.SEG10), Runtime.SHERPA_OFFLINE))
    }

    /**
     * 목적: [UT-COND-01] NS·EPD 를 켜면 v3 와 같은 조각이 같은 순서로 붙는다(대문자). CUSTOM 값 조각은 소문자 그대로다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun nsAndEpd_matchV3Format() {
        val ns = NsChoice("ns_gtcrn_lim12")                                   // v3 outputFolder() 값
        assertEquals("NS_GTCRN_LIM12_SILERO", ConditionName.build(silero, RunOptions(ns = ns), Runtime.SHERPA_OFFLINE))
        assertEquals("SILERO_EPD_SPLIT_T45_M55", ConditionName.build(silero, RunOptions(epd = epdA), Runtime.MLKIT))
        assertEquals("NS_GTCRN_LIM12_SILERO_EPD_GAP_T45_M55_S1000",
            ConditionName.build(silero, RunOptions(ns = ns, epd = epdB), Runtime.MLKIT))
        val p = VadPresetCatalog.customStart(VadEngine.SILERO)                // Silero Custom 시작값
        val custom = VadSetting(VadEngine.SILERO, p,
            p.values + mapOf(VadKeys.THRESHOLD to 0.40, VadKeys.MIN_SILENCE_MS to 300.0, VadKeys.PAD_MS to 100.0))
        assertEquals("NS_GTCRN_LIM12_SILERO_CUSTOM_t40_sp250_si300_pad100_SEG10",
            ConditionName.build(custom, RunOptions(seg = SegMode.SEG10, ns = ns), Runtime.VOSK))
    }

    /**
     * 목적: EPD 는 ML Kit 이면서 "엔진별 최대 길이" 일 때만 이름에 들어간다(v3 규칙).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun epd_onlyForMlKitEngineMax() {
        assertEquals("SILERO", ConditionName.build(silero, RunOptions(epd = epdA), Runtime.SHERPA_OFFLINE))      // sherpa 는 EPD 안 씀
        assertEquals("SILERO", ConditionName.build(silero, RunOptions(epd = epdA), Runtime.VOSK))
        assertEquals("SILERO_SEG10", ConditionName.build(silero, RunOptions(seg = SegMode.SEG10, epd = epdA), Runtime.MLKIT))   // 10초 통일
        assertNull(EpdChoice.OFF.folder)                                      // 사용 안 함
        assertNull(EpdChoice(EpdConfig(EpdMode.OFF)).folder)                  // mode OFF 도 사용 안 함
    }

    /**
     * 목적: [UT-COND-02] Feed 토큰은 스트리밍·Vosk 에서 공급 속도를 고른 경우에만 붙는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun feedToken_onlyForStreamingAndVosk() {
        val d50 = RunOptions(feed = FeedChoice.D50)
        assertEquals("SILERO_FEED_D50", ConditionName.build(silero, d50, Runtime.SHERPA_STREAMING))
        assertEquals("SILERO_FEED_D50", ConditionName.build(silero, d50, Runtime.VOSK))
        assertEquals("SILERO_FEED_REALTIME", ConditionName.build(silero, RunOptions(feed = FeedChoice.REALTIME), Runtime.VOSK))
        assertEquals("SILERO", ConditionName.build(silero, RunOptions(feed = FeedChoice.NONE), Runtime.SHERPA_STREAMING))   // 사용 안 함
        assertEquals("SILERO", ConditionName.build(silero, d50, Runtime.SHERPA_OFFLINE))                                    // 해당 없음
        assertEquals("SILERO", ConditionName.build(silero, RunOptions(feed = FeedChoice.REALTIME), Runtime.MLKIT))          // 고정값
        assertEquals("SILERO_SEG10_FEED_D10",
            ConditionName.build(silero, RunOptions(seg = SegMode.SEG10, feed = FeedChoice.D10), Runtime.SHERPA_STREAMING))   // 순서: SEG10 뒤
    }

    /**
     * 목적: effectiveFor — 오프라인은 공급 속도 없음, ML Kit 은 REALTIME 고정, 스트리밍·Vosk 는 고른 값.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun effectiveOptions_perRuntime() {
        val o = RunOptions(feed = FeedChoice.D20, epd = epdA)
        assertEquals(FeedChoice.NONE, o.effectiveFor(Runtime.SHERPA_OFFLINE).feed)
        assertEquals(FeedChoice.REALTIME, o.effectiveFor(Runtime.MLKIT).feed)
        assertEquals(FeedChoice.REALTIME, RunOptions().effectiveFor(Runtime.MLKIT).feed)        // 고르지 않아도 REALTIME
        assertEquals(FeedChoice.D20, o.effectiveFor(Runtime.SHERPA_STREAMING).feed)
        assertEquals(FeedChoice.D20, o.effectiveFor(Runtime.VOSK).feed)
        assertEquals(EpdChoice.OFF, o.effectiveFor(Runtime.VOSK).epd)                            // EPD 는 ML Kit 만
        assertEquals(epdA, o.effectiveFor(Runtime.MLKIT).epd)
        assertEquals(listOf(null, 100L, 50L, 30L, 20L, 10L), FeedChoice.values().map { it.delayMs })   // v3 SttCatalog.feeds 와 같은 간격
    }
}
