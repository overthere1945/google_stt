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
        assertEquals(setOf("zipformer-ko", "zipformer-ko-stream", "vosk-small-ko"), koOnly)   // change-hyungchul-20261107-0008 : [T8-2] v3 PlanCheck.KO_ONLY 의 값                                      // zipformer-ko, zipformer-ko-stream, vosk-small-ko
        assertEquals(setOf("vosk-small-en", "zipformer-en", "parakeet-tdt-0.6b-v2", "moonshine-tiny-en", "moonshine-base-en"), enOnly)   // change-hyungchul-20261107-0008 : [T8-2] v3 PlanCheck.EN_ONLY 의 값                                      // vosk-small-en, zipformer-en, parakeet, moonshine 2
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

    // change-hyungchul-20261107-0008 : [T8-2] "옮긴 값이 v3 SherpaCatalog 와 같다" 시험(values_matchV3SherpaCatalog)은 v3 정의를 지우면서 뺐다.
    //   그 시험은 WP2~WP7 동안 통과했고, 지금 값은 CatalogAssetsTest·CatalogExportTest(tools/asset_catalog.json 사본)가 지킨다.
}
