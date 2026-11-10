/*
 * 파일명: domain/plan/ComboGeneratorTest.kt (app/src/test)
 * 목적 및 기능:
 * - 조합 생성 규칙을 확인한다(FR-04-01, FR-04-03, FR-02-03).
 *     · UT-COMBO-01 조합 수 616(kr 13 + en 15 모델 × VAD 22 — v3 견본과 같은 수)
 *     · UT-COMBO-02 언어 전용 모델 제외 내역, UT-COMBO-03 실행 순서, UT-COMBO-04 중복 없음
 * - 작업의 결과 위치 이름(조건·결과 폴더·키·CSV 이름·필요 자산)을 확인한다.
 *
 * add-hyungchul-20261106-0044 — [T2-5]
 */
package com.example.google_stt.domain.plan

import com.example.google_stt.domain.catalog.Catalog          // 모델 목록
import com.example.google_stt.domain.catalog.Language         // 평가 언어
import com.example.google_stt.domain.catalog.Runtime          // 런타임
import com.example.google_stt.domain.condition.FeedChoice     // 공급 속도
import com.example.google_stt.domain.condition.RunOptions     // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder          // 언어 폴더
import com.example.google_stt.domain.lang.LangPlan            // 언어 계획
import com.example.google_stt.domain.lang.LangSource          // 언어를 정한 방법
import com.example.google_stt.domain.vad.SegMode              // 구간 처리
import com.example.google_stt.domain.vad.VadEngine            // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog     // 프리셋 목록
import com.example.google_stt.domain.vad.VadSetting           // VAD 설정
import org.junit.Assert.assertEquals                          // 값 비교
import org.junit.Assert.assertTrue                            // 조건 확인
import org.junit.Test                                         // JUnit 4 시험 표시

/** 목적: 조합 생성기 시험. */
class ComboGeneratorTest {

    private val kr = LangFolder("kr", "root/kr", Language.KO, LangSource.AUTO_SUBFOLDER)   // 한국어 폴더
    private val en = LangFolder("en", "root/en", Language.EN, LangSource.AUTO_SUBFOLDER)   // 영어 폴더
    private val krEn = LangPlan(listOf(kr, en), emptyList(), emptyList(), true)            // kr → en 순서 계획

    private val novad = VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())   // VAD 사용 안 함

    /** 고정 프리셋 21개 + NOVAD = 조건 22개(v3 견본 "VAD22") */
    private val vad22: List<VadSetting> = listOf(novad) +
        listOf(VadEngine.SILERO, VadEngine.TEN, VadEngine.PYANNOTE, VadEngine.FSMN, VadEngine.FIRERED).flatMap { e ->
            VadPresetCatalog.presets(e).filter { !it.isCustom }.map { VadSetting(e, it, it.values) }
        }

    private val appModels = Catalog.models.filter { it.runtime != Runtime.MLKIT }   // ML Kit 을 뺀 18개

    /**
     * 목적: 이름으로 고정 프리셋 설정을 만든다.
     * 입력: engine - 엔진, name - 프리셋 이름 / 출력: 없음 / 리턴: VadSetting
     */
    private fun preset(engine: VadEngine, name: String): VadSetting {
        val p = VadPresetCatalog.presets(engine).first { it.name == name }   // 이름이 같은 프리셋
        return VadSetting(engine, p, p.values)
    }

    /**
     * 목적: [UT-COMBO-01] kr·en × 앱 모델 18개 × VAD 22 = 616(kr 286 + en 330), 키는 모두 다르다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun count_is616_likeV3Sample() {
        val r = ComboGenerator.generate(krEn, appModels, vad22, listOf(RunOptions(feed = FeedChoice.REALTIME)))
        assertEquals(22, vad22.size)
        assertEquals(616, r.jobs.size)                                        // v3 견본 CSV 의 작업 수
        assertEquals(286, r.jobs.count { it.lang.name == "kr" })              // 13 모델 × 22
        assertEquals(330, r.jobs.count { it.lang.name == "en" })              // 15 모델 × 22
        assertEquals(616, r.jobs.map { it.key }.toSet().size)                 // 결과 폴더가 겹치지 않는다
    }

    /**
     * 목적: [UT-COMBO-02] 언어 전용 모델은 맞지 않는 언어 폴더에서 빠지고 사유와 함께 제외 내역에 남는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun languageOnlyModels_areExcludedWithReason() {
        val r = ComboGenerator.generate(krEn, appModels, vad22, listOf(RunOptions()))
        assertTrue(r.jobs.none { it.lang.name == "en" && it.model.engine == "zipformer-ko" })   // FR-02-03
        assertTrue(r.jobs.none { it.lang.name == "kr" && it.model.engine == "moonshine-tiny-en" })
        assertEquals(8, r.excluded.size)                                      // kr 에서 영어 전용 5 + en 에서 한국어 전용 3
        assertEquals(5, r.excluded.count { it.lang.name == "kr" && it.reason == "영어 전용 모델" })
        assertEquals(3, r.excluded.count { it.lang.name == "en" && it.reason == "한국어 전용 모델" })
        assertTrue(r.excluded.all { it.count == 22 })                         // 모델마다 VAD 22개 조합이 빠졌다
        assertEquals(616 + 8 * 22, 18 * 22 * 2)                               // 만든 것 + 뺀 것 = 전체 곱
    }

    /**
     * 목적: [UT-COMBO-03] 순서 — 언어 폴더 순, 그 안에서 오프라인 → 스트리밍·Vosk → ML Kit, 같은 모델은 이어서.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun order_offlineThenStreamingThenMlKit() {
        val pick = listOf("google-mlkit", "vosk-small-ko", "whisper-base", "nemotron3.5-560ms", "zipformer-ko").map { Catalog.model(it)!! }
        val vads = listOf(novad, preset(VadEngine.SILERO, "SILERO"))
        val r = ComboGenerator.generate(krEn, pick, vads, listOf(RunOptions()))
        val krEngines = r.jobs.filter { it.lang.name == "kr" }.map { it.model.engine }
        assertEquals(   // 오프라인(고른 순서) → 스트리밍·Vosk(고른 순서) → ML Kit, 모델마다 VAD 2개가 이어진다
            listOf("whisper-base", "whisper-base", "zipformer-ko", "zipformer-ko",
                "vosk-small-ko", "vosk-small-ko", "nemotron3.5-560ms", "nemotron3.5-560ms", "google-mlkit", "google-mlkit"),
            krEngines,
        )
        assertEquals(listOf("NOVAD", "SILERO"), r.jobs.take(2).map { it.condition })       // VAD 는 고른 순서
        val firstEn = r.jobs.indexOfFirst { it.lang.name == "en" }
        assertEquals(10, firstEn)                                             // kr 작업이 모두 끝난 뒤 en
        assertTrue(r.jobs.drop(firstEn).all { it.lang.name == "en" })
        assertEquals(listOf("whisper-base", "nemotron3.5-560ms", "google-mlkit"),
            r.jobs.drop(firstEn).map { it.model.engine }.distinct())          // en 에서는 한국어 전용 2개가 빠진다
    }

    /**
     * 목적: [UT-COMBO-04] 같은 VAD 를 두 번 고르거나, 런타임에 뜻이 없는 옵션만 다른 조합은 작업 1개로 합쳐진다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun duplicates_areMerged() {
        val silero = preset(VadEngine.SILERO, "SILERO")
        val pick = listOf("whisper-base", "nemotron3.5-560ms", "google-mlkit").map { Catalog.model(it)!! }
        val opts = listOf(RunOptions(feed = FeedChoice.NONE), RunOptions(feed = FeedChoice.REALTIME))   // 공급 속도 2가지
        val r = ComboGenerator.generate(LangPlan(listOf(kr), emptyList(), emptyList(), true), pick, listOf(silero, silero), opts)
        assertEquals(   // 오프라인 1개, 스트리밍은 공급 속도마다 1개씩, ML Kit 1개(REALTIME 고정)
            listOf("kr/whisper-base_sherpa-int8_SILERO", "kr/nemotron3.5-560ms_sherpa-int8_SILERO",
                "kr/nemotron3.5-560ms_sherpa-int8_SILERO_FEED_REALTIME", "kr/google-mlkit_basic_SILERO"),
            r.jobs.map { it.key },
        )
        assertEquals(FeedChoice.NONE, r.jobs[0].opt.feed)                     // 오프라인 작업의 옵션은 "없음" 으로 정리된다
        assertEquals(FeedChoice.REALTIME, r.jobs[3].opt.feed)                 // ML Kit 은 REALTIME
    }

    /**
     * 목적: 작업의 이름들 — 조건, 결과 폴더, 키, CSV 이름, 필요 자산. 입력 폴더 자체가 한 언어면 키에 언어 폴더가 없다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun jobNames_followResultLayout() {
        val j = Job(kr, Catalog.model("qwen3asr-0.6b")!!, preset(VadEngine.PYANNOTE, "PYANNOTE_S500"), RunOptions(seg = SegMode.SEG10))
        assertEquals("PYANNOTE_S500_SEG10", j.condition)
        assertEquals("qwen3asr-0.6b_sherpa-int8_PYANNOTE_S500_SEG10", j.resultDirName)
        assertEquals("kr/qwen3asr-0.6b_sherpa-int8_PYANNOTE_S500_SEG10", j.key)
        assertEquals("qwen3asr-0.6b_PYANNOTE_S500_SEG10", j.csvBaseName)      // CSV 이름에는 모델 이름이 없다
        assertEquals(listOf("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25", "pyannote-seg3"), j.assetIds)
        assertEquals(Language.KO, j.language)
        val root = LangFolder("", "primary:Documents/input_sound/kr", Language.KO, LangSource.AUTO_ROOT)
        val g = Job(root, Catalog.model("google-mlkit")!!, novad, RunOptions())
        assertEquals("google-mlkit_basic_NOVAD", g.key)                       // 언어 폴더로 나누지 않는다(FR-01-03)
        assertTrue(g.assetIds.isEmpty())                                      // ML Kit·NOVAD 는 받을 자산이 없다
        assertEquals("google-mlkit/NOVAD", g.label)
        val t = Job(kr, Catalog.model("vosk-small-ko")!!, preset(VadEngine.TEN, "TEN_ASR"), RunOptions())
        assertEquals(listOf("vosk-model-small-ko-0.22"), t.assetIds)          // TEN 은 APK 에 들어 있어 자산이 없다
    }

    /**
     * 목적: 모르는 폴더·건너뛰는 폴더는 작업을 만들지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun unknownAndSkippedFolders_makeNoJobs() {
        val misc = LangFolder("misc", "root/misc", null, LangSource.AUTO_SUBFOLDER)
        val plan = LangPlan(listOf(kr), listOf(misc), listOf(misc.copy(name = "noise")), true)
        val r = ComboGenerator.generate(plan, listOf(Catalog.model("whisper-base")!!), listOf(novad), listOf(RunOptions()))
        assertEquals(listOf("kr/whisper-base_sherpa-int8_NOVAD"), r.jobs.map { it.key })
    }
}
