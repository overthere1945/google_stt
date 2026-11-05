/*
 * 파일명: domain/catalog/CatalogTest.kt (app/src/test)
 * 목적 및 기능:
 * - 모델 카탈로그 규칙을 확인한다.
 *     · UT-CAT-01 결과 이름 규칙(엔진 이름에 '_' 없음, 모델 이름 3종)
 *     · UT-CAT-02 언어 전용 집합이 v3 PlanCheck.KO_ONLY / EN_ONLY 와 같음
 *     · UT-CAT-03 조각 상한(Whisper 29, Qwen3 30, Omni 39, Zipformer 15, SenseVoice 30, Parakeet 30, Moonshine 25)
 * - 옮긴 값이 v3 정의(stt/SherpaModels.kt)와 같은지 대조한다. 이 대조는 v3 코드를 지우는 T8-2 에서 함께 지운다.
 *
 * add-hyungchul-20261106-0040 — [T2-2]
 */
package com.example.google_stt.domain.catalog

import com.example.google_stt.plan.PlanCheck        // v3 언어 전용 집합(대조용, T8-2 에서 삭제)
import com.example.google_stt.stt.SherpaCatalog     // v3 모델 정의(대조용, T8-2 에서 삭제)
import com.example.google_stt.stt.SherpaModelSpec   // v3 모델 자료형(대조용)
import org.junit.Assert.assertEquals                // 값 비교
import org.junit.Assert.assertNull                  // null 확인
import org.junit.Assert.assertTrue                  // 조건 확인
import org.junit.Test                               // JUnit 4 시험 표시

/** 목적: 모델 카탈로그 시험. */
class CatalogTest {

    /** 엔진 이름 → 모델(시험에서 자주 찾는다) */
    private val byEngine = Catalog.models.associateBy { it.engine }

    /**
     * 목적: [UT-CAT-01] 모델 19개, 엔진 이름은 겹치지 않고 '_' 가 없으며, 결과 모델 이름은 3종뿐이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun resultNames_followEvaluatorRule() {
        assertEquals(19, Catalog.models.size)                                        // sherpa 16 + Vosk 2 + ML Kit 1
        assertEquals(19, byEngine.size)                                              // 엔진 이름 중복 없음
        assertTrue(Catalog.models.none { '_' in it.engine })                         // '_' 금지(PC 평가기가 '_' 로 나눈다)
        assertEquals(setOf("sherpa-int8", "vosk", "basic"), Catalog.models.map { it.model }.toSet())
        assertEquals("google-mlkit", Catalog.models.last().engine)                   // ML Kit 은 목록 끝
        assertNull(byEngine.getValue("google-mlkit").assetId)                        // ML Kit 은 내려받을 자산이 없다
        assertTrue(Catalog.models.filter { it.runtime != Runtime.MLKIT }.all { it.assetId != null && it.files.isNotEmpty() })
    }

    /**
     * 목적: [UT-CAT-02] 언어 전용 모델 집합이 v3 PlanCheck 와 같고, 한국어 13개 / 영어 15개(ML Kit 제외)다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun languageOnlySets_matchV3() {
        val koOnly = Catalog.models.filter { it.langSupport == LangSupport.KO_ONLY }.map { it.engine }.toSet()
        val enOnly = Catalog.models.filter { it.langSupport == LangSupport.EN_ONLY }.map { it.engine }.toSet()
        assertEquals(PlanCheck.KO_ONLY, koOnly)                                      // zipformer-ko, zipformer-ko-stream, vosk-small-ko
        assertEquals(PlanCheck.EN_ONLY, enOnly)                                      // vosk-small-en, zipformer-en, parakeet, moonshine 2
        assertEquals(13, Catalog.modelsFor(Language.KO).count { it.runtime != Runtime.MLKIT })   // v3 견본의 kr 13모델
        assertEquals(15, Catalog.modelsFor(Language.EN).count { it.runtime != Runtime.MLKIT })   // v3 견본의 en 15모델
        assertTrue(Catalog.modelsFor(Language.EN).none { it.engine == "zipformer-ko" })          // FR-02-03
    }

    /**
     * 목적: [UT-CAT-03] 조각 상한이 계열별로 정한 값이고, 스트리밍·Vosk·ML Kit 은 상한이 없다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun maxChunkSec_perFamily() {
        val want = mapOf(
            ModelFamily.WHISPER to 29, ModelFamily.QWEN3ASR to 30, ModelFamily.OMNILINGUAL to 39, ModelFamily.ZIPFORMER to 15,
            ModelFamily.SENSEVOICE to 30, ModelFamily.PARAKEET to 30, ModelFamily.MOONSHINE to 25,
        )
        for (m in Catalog.models) {
            if (m.isOffline) assertEquals(m.engine, want.getValue(m.family), m.maxChunkSec)   // 오프라인은 계열 값
            else assertNull(m.engine, m.maxChunkSec)                                          // 그 밖은 상한 없음
        }
        assertEquals(13, Catalog.models.count { it.isOffline })                      // 오프라인 13개(sherpa 16 - 스트리밍 3)
        assertEquals(5, Catalog.models.count { it.feedSelectable })                  // 스트리밍 3 + Vosk 2
    }

    /**
     * 목적: 상업 조건부 모델은 3개(SenseVoice, Zipformer KO 2종)이고 화면 이름에 표시가 있다(FR-02-04).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun conditionalModels_areMarked() {
        val cond = Catalog.models.filter { it.commercial == Commercial.CONDITIONAL }
        assertEquals(setOf("zipformer-ko", "zipformer-ko-stream", "sensevoice-small"), cond.map { it.engine }.toSet())
        assertTrue(cond.all { it.displayName.endsWith("[상업 조건부]") })              // 이름 끝 표시
    }

    /**
     * 목적: 옮긴 값이 v3 SherpaCatalog 와 같다(패키지 이름·파일·조각 상한·라이선스·상업 판정·화면 이름).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun values_matchV3SherpaCatalog() {
        val v3: Map<String, SherpaModelSpec> = mapOf(
            "whisper-tiny" to SherpaCatalog.WHISPER_TINY, "whisper-base" to SherpaCatalog.WHISPER_BASE,
            "whisper-small" to SherpaCatalog.WHISPER_SMALL, "whisper-medium" to SherpaCatalog.WHISPER_MEDIUM,
            "whisper-turbo" to SherpaCatalog.WHISPER_TURBO, "qwen3asr-0.6b" to SherpaCatalog.QWEN3_ASR_06B,
            "omnilingual-300m" to SherpaCatalog.OMNILINGUAL_300M, "nemotron3.5-320ms" to SherpaCatalog.NEMOTRON_320MS,
            "nemotron3.5-560ms" to SherpaCatalog.NEMOTRON_560MS, "zipformer-ko" to SherpaCatalog.ZIPFORMER_KO,
            "zipformer-ko-stream" to SherpaCatalog.ZIPFORMER_KO_STREAMING, "sensevoice-small" to SherpaCatalog.SENSE_VOICE_SMALL,
            "zipformer-en" to SherpaCatalog.ZIPFORMER_EN, "parakeet-tdt-0.6b-v2" to SherpaCatalog.PARAKEET_TDT_06B_V2,
            "moonshine-tiny-en" to SherpaCatalog.MOONSHINE_TINY_EN, "moonshine-base-en" to SherpaCatalog.MOONSHINE_BASE_EN,
            "vosk-small-ko" to SherpaCatalog.VOSK_SMALL_KO, "vosk-small-en" to SherpaCatalog.VOSK_SMALL_EN,
        )
        assertEquals(v3.keys, Catalog.models.filter { it.runtime != Runtime.MLKIT }.map { it.engine }.toSet())   // 18개 모두 대응
        for ((engine, old) in v3) {
            val m = byEngine.getValue(engine)                                        // 새 정의
            assertEquals(engine, old.dirName, m.assetId)                             // 패키지 이름 = 자산 ID
            assertEquals(engine, old.files, m.files)                                 // 역할 → 파일
            assertEquals(engine, old.maxChunkSec, m.maxChunkSec?.toDouble())         // 조각 상한
            assertEquals(engine, old.license, m.license)                             // 라이선스
            assertEquals(engine, old.commercial, m.commercial.value)                 // 상업 판정
            assertEquals(engine, old.displayName, m.displayName)                     // 화면 이름
            assertEquals(engine, old.family.streaming, !m.isOffline)                 // 스트리밍 여부(Vosk 포함)
        }
    }
}
