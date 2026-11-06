/*
 * 파일명: data/prefs/LangMemoryRepository.kt
 * 목적 및 기능:
 * - 이름으로 언어를 알 수 없는 폴더에 대해 사용자가 고른 값(한국어 / 영어 / 건너뛰기)을 기억한다(D9, FR-01-04).
 *   키는 폴더의 SAF 문서 ID 다("lang:<문서 ID>" → "ko" / "en" / "skip"). 한 번 고르면 다음 실행에 묻지 않는다.
 * - 읽은 값은 LanguageResolver.plan 의 memory 인자로 그대로 넘긴다(값이 null 이면 "건너뛰기").
 *
 * add-hyungchul-20261106-1645 — [T3-4]
 */
package com.example.google_stt.data.prefs

import androidx.datastore.core.DataStore                           // 저장소
import androidx.datastore.preferences.core.Preferences             // 키-값 묶음
import androidx.datastore.preferences.core.edit                    // 값 바꾸기
import androidx.datastore.preferences.core.stringPreferencesKey    // String 키
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import kotlinx.coroutines.flow.Flow                                // 값 흐름
import kotlinx.coroutines.flow.map                                 // 변환

/**
 * 목적: 폴더 언어 기억 저장소.
 * 입력(생성자): store - lang_memory 저장소
 */
class LangMemoryRepository(private val store: DataStore<Preferences>) {

    private companion object {
        const val PREFIX = "lang:"   // 키 앞부분
        const val SKIP = "skip"      // "건너뛰기" 로 기억한 값
    }

    /** 기억 값의 흐름: 문서 ID → 언어(null = 건너뛰기) */
    val memory: Flow<Map<String, Language?>> = store.data.map { p ->
        val out = LinkedHashMap<String, Language?>()
        for ((key, value) in p.asMap()) {
            if (!key.name.startsWith(PREFIX)) continue                            // 다른 키는 무시
            val docId = key.name.removePrefix(PREFIX)
            when (value) {
                SKIP -> out[docId] = null                                          // 건너뛰기
                else -> Language.values().firstOrNull { it.code == value }?.let { out[docId] = it }   // 모르는 값은 기억하지 않은 것으로 본다
            }
        }
        out
    }

    /**
     * 목적: 폴더의 언어를 기억한다.
     * 입력: docId - 폴더의 SAF 문서 ID, lang - 언어(null = 건너뛰기)
     * 출력: 저장소 파일이 바뀐다
     * 리턴: 없음
     */
    suspend fun remember(docId: String, lang: Language?) {
        store.edit { it[stringPreferencesKey(PREFIX + docId)] = lang?.code ?: SKIP }
    }

    /**
     * 목적: 폴더 하나의 기억을 지운다(다음 실행에 다시 묻는다).
     * 입력: docId - 폴더의 SAF 문서 ID / 출력: 저장소 파일이 바뀐다 / 리턴: 없음
     */
    suspend fun forget(docId: String) {
        store.edit { it.remove(stringPreferencesKey(PREFIX + docId)) }
    }

    /**
     * 목적: 기억을 모두 지운다("기억 지우기").
     * 입력: 없음 / 출력: 저장소 파일이 바뀐다 / 리턴: 없음
     */
    suspend fun clear() {
        store.edit { p -> p.asMap().keys.filter { it.name.startsWith(PREFIX) }.forEach { p.remove(it) } }
    }
}
