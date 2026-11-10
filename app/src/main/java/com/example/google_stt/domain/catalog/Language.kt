/*
 * 파일명: domain/catalog/Language.kt
 * 목적 및 기능:
 * - 평가 언어(한국어 / 영어)와 모델의 언어 지원 범위를 정의한다. Android 를 쓰지 않는 순수 Kotlin 이다.
 * - 언어 전용 모델 판정은 ModelSpec.langSupport 한 곳에서만 한다(v3 의 PlanCheck.KO_ONLY / EN_ONLY 중복 제거 — FR-02-01).
 *
 * add-hyungchul-20261106-0033 — [T2-1]
 */
package com.example.google_stt.domain.catalog

/**
 * 목적: 평가 언어.
 * 필드: code - 짧은 언어 코드(서버 INPUT_LANG_WORDS 의 키와 같다), reqLang - CSV req_lang 열에 쓰는 값
 */
enum class Language(val code: String, val reqLang: String) {
    KO("ko", "ko-KR"),   // 한국어
    EN("en", "en-US"),   // 영어
}

/** 목적: 모델이 지원하는 언어 범위. */
enum class LangSupport {
    KO_ONLY,   // 한국어 전용(영어 폴더 조합에서 자동 제외)
    EN_ONLY,   // 영어 전용(한국어 폴더 조합에서 자동 제외)
    MULTI;     // 한국어·영어 모두

    /**
     * 목적: 이 지원 범위가 주어진 언어를 다루는지 알려 준다.
     * 입력: lang - 평가 언어
     * 출력: 없음
     * 리턴: Boolean - 다루면 true
     */
    fun supports(lang: Language): Boolean = when (this) {
        KO_ONLY -> lang == Language.KO   // 한국어 전용은 한국어만
        EN_ONLY -> lang == Language.EN   // 영어 전용은 영어만
        MULTI -> true                    // 다국어는 모두
    }
}
