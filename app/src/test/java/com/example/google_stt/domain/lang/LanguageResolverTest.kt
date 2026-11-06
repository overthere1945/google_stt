/*
 * 파일명: domain/lang/LanguageResolverTest.kt (app/src/test)
 * 목적 및 기능:
 * - 폴더 이름 → 언어 판정과 언어 계획을 확인한다(FR-01-02 ~ FR-01-04).
 *     · UT-LANG-01 낱말 판정, UT-LANG-02 판정 불가, UT-LANG-03 입력 폴더 자체가 한 언어, UT-LANG-04 기억 값
 * - v3 io/AudioInput.kt 의 판정 함수와 같은 결과인지 대조한다(T8-2 에서 v3 코드와 함께 지운다).
 *
 * add-hyungchul-20261106-0040 — [T2-3]
 */
package com.example.google_stt.domain.lang

import com.example.google_stt.domain.catalog.Language   // 평가 언어
import org.junit.Assert.assertEquals                    // 값 비교
import org.junit.Assert.assertFalse                     // 거짓 확인
import org.junit.Assert.assertNull                      // null 확인
import org.junit.Assert.assertTrue                      // 조건 확인
import org.junit.Test                                   // JUnit 4 시험 표시

/** 목적: 언어 판정·계획 시험. */
class LanguageResolverTest {

    /**
     * 목적: (이름, 문서 ID) 목록을 만든다(문서 ID 는 "root/<이름>" 으로 둔다).
     * 입력: names - 하위 폴더 이름들 / 출력: 없음 / 리턴: List<Pair<String, String>>
     */
    private fun kids(vararg names: String): List<Pair<String, String>> = names.map { it to "root/$it" }

    /**
     * 목적: [UT-LANG-01] 이름이 낱말과 같거나 "_<낱말>" 로 끝나면 그 언어다(대소문자 무시).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun resolve_knownNames() {
        assertEquals(Language.EN, LanguageResolver.resolve("en"))
        assertEquals(Language.KO, LanguageResolver.resolve("kr"))
        assertEquals(Language.KO, LanguageResolver.resolve("Customer_Service_KR"))
        assertEquals(Language.EN, LanguageResolver.resolve("ENGLISH"))
        assertEquals(Language.KO, LanguageResolver.resolve("한국어"))
        assertEquals(Language.EN, LanguageResolver.resolve("test_en"))
    }

    /**
     * 목적: [UT-LANG-02] 낱말이 이름 중간에 섞였거나 뒤에 글자가 붙으면 판정하지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun resolve_unknownNames() {
        for (name in listOf("misc", "kren", "korea2", "", "enx", "my-en")) {
            assertNull(name, LanguageResolver.resolve(name))                 // 판정 불가
        }
        assertNull(LanguageResolver.resolvePath("data/en/kr"))               // 두 언어가 다 보이면 정하지 않는다
        assertEquals(Language.KO, LanguageResolver.resolvePath("primary:Documents/input_sound/kr"))   // SAF 문서 ID
    }

    /**
     * 목적: 판정 결과가 v3 inputLanguageHint 가 내던 값과 같다("ko"/"en"/null).
     *       change-hyungchul-20261107-0008 : [T8-2] v3 함수를 지우면서, 지우기 직전에 그 함수로 뽑은 값을 기대값으로 고정했다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun resolve_matchesV3() {
        val v3 = mapOf(
            "en" to "en", "kr" to "ko", "ko" to "ko", "KOR" to "ko", "korea" to "ko", "Korean" to "ko", "한국어" to "ko", "eng" to "en", "English" to "en",
            "영어" to "en", "a_kr" to "ko", "b_EN" to "en", "misc" to null, "kren" to null, "korea2" to null, "kr_en" to "en", "en_kr" to "ko",
            "primary:Documents/input_sound/scarlett" to null, "primary:Documents/x/kr" to "ko", "a/en/b" to "en",
        )
        for ((n, want) in v3) assertEquals(n, want, LanguageResolver.resolvePath(n)?.code)   // 코드 글자로 비교
    }

    /**
     * 목적: 규칙 (1) — 하위 폴더 이름으로 정해지면 그 폴더들을 이름순으로 돌리고, 모르는 폴더는 물을 목록에 둔다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun plan_subfoldersByName() {
        val p = LanguageResolver.plan("primary:Documents/input_sound/scarlett", kids("kr", "en", "misc", ".hidden"), emptyMap())
        assertTrue(p.splitByLang)                                             // 결과를 언어 폴더별로 나눈다
        assertEquals(listOf("en", "kr"), p.folders.map { it.name })          // 이름순
        assertEquals(listOf(Language.EN, Language.KO), p.folders.map { it.lang })
        assertTrue(p.folders.all { it.source == LangSource.AUTO_SUBFOLDER })
        assertEquals(listOf("misc"), p.unknown.map { it.name })              // 물어야 할 폴더(숨김 폴더는 빠진다)
        assertEquals("root/misc", p.unknown[0].docId)                        // 기억 값의 키
        assertTrue(p.skipped.isEmpty())
    }

    /**
     * 목적: [UT-LANG-03] 규칙 (2) — 하위 폴더는 이름으로 못 정하고 입력 폴더 경로가 kr 이면 전체가 한국어, 결과 폴더를 나누지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun plan_rootDecidesLanguage() {
        val p = LanguageResolver.plan("primary:Documents/input_sound/kr", kids("Customer_Service", "vLog"), emptyMap())
        assertFalse(p.splitByLang)                                           // 나누지 않는다
        assertEquals(1, p.folders.size)
        assertEquals("", p.folders[0].name)                                  // 입력 폴더 자체
        assertEquals(Language.KO, p.folders[0].lang)
        assertEquals(LangSource.AUTO_ROOT, p.folders[0].source)
        assertTrue(p.unknown.isEmpty())
    }

    /**
     * 목적: [UT-LANG-04] 기억 값 — 한국어로 기억한 폴더는 묻지 않고 돌리고, 건너뛰기로 기억한 폴더는 돌리지도 묻지도 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun plan_usesMemory() {
        val kidsList = kids("kr", "misc", "noise")
        val p = LanguageResolver.plan("primary:Documents/in", kidsList, mapOf("root/misc" to Language.KO, "root/noise" to null))
        assertEquals(listOf("kr", "misc"), p.folders.map { it.name })
        assertEquals(LangSource.REMEMBERED, p.folders[1].source)             // 기억한 값으로 표시
        assertEquals(Language.KO, p.folders[1].lang)
        assertTrue(p.unknown.isEmpty())                                      // 다시 묻지 않는다
        assertEquals(listOf("noise"), p.skipped.map { it.name })             // 건너뛰기로 기억
    }

    /**
     * 목적: 규칙 (3) — 아무것도 이름으로 못 정하면 하위 폴더 전부(없으면 입력 폴더 자체)를 물을 목록에 두고, 기억하면 그 값을 쓴다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun plan_allUnknown_thenRemembered() {
        val a = LanguageResolver.plan("primary:Documents/data", kids("b", "a"), emptyMap())
        assertTrue(a.folders.isEmpty())
        assertEquals(listOf("a", "b"), a.unknown.map { it.name })            // 하위 폴더 전부, 이름순
        val b = LanguageResolver.plan("primary:Documents/data", kids("b", "a"), mapOf("root/a" to Language.EN))
        assertEquals(listOf("a"), b.folders.map { it.name })                 // 기억한 것은 돌린다
        assertEquals(listOf("b"), b.unknown.map { it.name })                 // 나머지는 묻는다
        val c = LanguageResolver.plan("primary:Documents/data", emptyList(), emptyMap())
        assertEquals(listOf(""), c.unknown.map { it.name })                  // 하위 폴더가 없으면 입력 폴더 자체
        assertFalse(c.splitByLang)
        val d = LanguageResolver.plan("primary:Documents/data", emptyList(), mapOf("primary:Documents/data" to Language.EN))
        assertEquals(Language.EN, d.folders.single().lang)                   // 입력 폴더 자체를 기억한 언어로
        assertEquals(LangSource.REMEMBERED, d.folders.single().source)
    }
}
