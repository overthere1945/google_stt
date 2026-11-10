/*
 * 파일명: domain/lang/LanguageResolver.kt
 * 목적 및 기능:
 * - 입력 폴더 이름으로 음원 언어(한국어 / 영어)를 정하고, 어느 폴더를 어느 언어로 돌릴지 계획(LangPlan)을 만든다.
 *   규칙과 낱말은 서버 stt_all_gui_server(INPUT_LANG_WORDS, _lang_folder_entries)·v3 io/AudioInput.kt 와 같다(FR-01-02, FR-01-03).
 *     (1) 바로 아래 하위 폴더 중 이름으로 언어가 정해지는 것이 있으면 → 그 하위 폴더들을 언어 폴더로 돌린다(결과도 폴더별로 나눈다)
 *     (2) 없고 입력 폴더 경로로 정해지면(예: …/input_sound/kr) → 입력 폴더 하나를 그 언어로(결과 폴더를 나누지 않는다)
 *     (3) 그 밖 → 모르는 폴더. 실행 전에 사용자에게 묻고 문서 ID 로 기억한다(D9, FR-01-04)
 * - v3 와 달라진 점: 모르는 폴더를 건너뛰지 않고, 기억한 값(한국어 / 영어 / 건너뛰기)이 있으면 그 값을 쓴다.
 * - Android 를 쓰지 않는 순수 Kotlin 이다. 폴더 목록과 기억 값은 호출하는 쪽이 넘긴다.
 *
 * add-hyungchul-20261106-0040 — [T2-3]
 */
package com.example.google_stt.domain.lang

import com.example.google_stt.domain.catalog.Language   // 평가 언어
import java.util.Locale                                 // 대소문자 무시 비교

/** 목적: 폴더의 언어를 어떻게 정했는지. */
enum class LangSource {
    AUTO_SUBFOLDER,   // 하위 폴더 이름으로 정함
    AUTO_ROOT,        // 입력 폴더 경로로 정함
    REMEMBERED,       // 예전에 사용자가 고른 값을 기억해 둠
    ASKED,            // 이번 실행에서 사용자가 고름(화면이 붙인다)
}

/**
 * 목적: 언어 폴더 1개(돌릴 단위).
 * 필드: name - 입력 폴더 바로 아래 하위 폴더 이름("" = 입력 폴더 자체 — 결과 폴더를 나누지 않는다),
 *       docId - SAF 문서 ID(기억 값의 키), lang - 언어(모르면 null), source - 정한 방법
 */
data class LangFolder(
    val name: String,          // 하위 폴더 이름
    val docId: String,         // 문서 ID
    val lang: Language?,       // 언어
    val source: LangSource,    // 정한 방법
)

/**
 * 목적: 언어 계획.
 * 필드: folders - 차례로 돌릴 언어 폴더(lang 이 정해진 것만),
 *       unknown - 언어를 몰라 실행 전에 물어야 하는 폴더,
 *       skipped - 기억한 값이 "건너뛰기" 인 폴더(돌리지 않고 묻지도 않는다. 화면 표시용),
 *       splitByLang - 결과를 언어 폴더별로 나누는지(false = 입력 폴더 자체가 한 언어)
 */
data class LangPlan(
    val folders: List<LangFolder>,     // 돌릴 폴더
    val unknown: List<LangFolder>,     // 물어야 할 폴더
    val skipped: List<LangFolder>,     // 건너뛰기로 기억한 폴더
    val splitByLang: Boolean,          // 결과 폴더를 나누는지
)

/** 목적: 폴더 이름 → 언어 판정과 언어 계획 만들기. */
object LanguageResolver {

    /** 폴더 이름으로 언어를 짐작하는 낱말(소문자) — 서버 INPUT_LANG_WORDS 와 같은 값 */
    val WORDS: Map<Language, List<String>> = linkedMapOf(
        Language.KO to listOf("kr", "ko", "kor", "korea", "korean", "한국어"),   // 한국어로 보는 이름
        Language.EN to listOf("en", "eng", "english", "영어"),                  // 영어로 보는 이름
    )

    /**
     * 목적: 폴더 이름 하나로 언어를 정한다(이름이 낱말과 같거나 "_<낱말>" 로 끝나면 그 언어).
     * 입력: folderName - 폴더 이름(대소문자 무시)
     * 출력: 없음
     * 리턴: Language? - 한 언어로만 판정될 때. 판정이 안 되면 null
     */
    fun resolve(folderName: String): Language? = resolvePath(folderName)   // 이름 하나도 마디 하나인 경로로 본다

    /**
     * 목적: 경로의 마디들로 언어를 정한다(입력 폴더 경로 판정용 — 서버 input_language_hint 와 같은 규칙).
     * 입력: path - 경로 또는 SAF 문서 ID('/', '\', ':' 로 마디를 나눈다. 예 "primary:Documents/input_sound/kr")
     * 출력: 없음
     * 리턴: Language? - 한 언어만 보일 때. 아무 언어도 안 보이거나 두 언어가 다 보이면 null
     */
    fun resolvePath(path: String): Language? {
        val found = LinkedHashSet<Language>()                                       // 보인 언어들
        for (seg in path.split('/', '\\', ':')) {                                   // 마디마다
            val low = seg.trim().lowercase(Locale.ROOT)                             // 대소문자 무시
            if (low.isEmpty()) continue                                             // 빈 마디는 건너뛴다
            for ((lang, words) in WORDS) {                                          // 언어마다 낱말 비교
                if (low in words || words.any { low.endsWith("_$it") }) found += lang   // 같거나 '_낱말' 로 끝나면
            }
        }
        return if (found.size == 1) found.first() else null                         // 한 언어만 보일 때만
    }

    /**
     * 목적: 어느 폴더를 어느 언어로 돌릴지 정한다.
     * 입력: rootPath - 입력 폴더의 SAF 문서 ID(경로 판정과 기억 값의 키로 쓴다),
     *       children - 바로 아래 하위 폴더 (이름, 문서 ID) 목록,
     *       memory - 기억 값: 문서 ID → 언어(null 값 = "건너뛰기" 로 기억함)
     * 출력: 없음
     * 리턴: LangPlan - 하위 폴더는 이름순(서버 sorted() 와 같은 순서), 숨김 폴더(.으로 시작)는 뺀다
     */
    fun plan(rootPath: String, children: List<Pair<String, String>>, memory: Map<String, Language?>): LangPlan {
        val subs = children.filter { !it.first.startsWith(".") }.sortedBy { it.first }   // 숨김 폴더 제외, 이름순
        val byName = subs.map { it to resolve(it.first) }                                // 하위 폴더 이름마다 언어 짐작
        if (byName.any { it.second != null }) {                                          // (1) 이름으로 정해지는 하위 폴더가 있다
            return splitPlan(byName, memory)
        }
        val rootLang = resolvePath(rootPath)                                             // (2) 입력 폴더 경로로 짐작
        if (rootLang != null) {
            return LangPlan(listOf(LangFolder("", rootPath, rootLang, LangSource.AUTO_ROOT)), emptyList(), emptyList(), false)
        }
        if (subs.isEmpty()) {                                                            // (3-a) 하위 폴더가 없다 → 입력 폴더 자체
            val root = LangFolder("", rootPath, memory[rootPath], LangSource.AUTO_ROOT)
            return when {
                rootPath !in memory -> LangPlan(emptyList(), listOf(root), emptyList(), false)                       // 물어야 한다
                root.lang == null -> LangPlan(emptyList(), emptyList(), listOf(root.copy(source = LangSource.REMEMBERED)), false)   // 건너뛰기
                else -> LangPlan(listOf(root.copy(source = LangSource.REMEMBERED)), emptyList(), emptyList(), false)  // 기억한 언어
            }
        }
        return splitPlan(byName, memory)                                                 // (3-b) 하위 폴더 모두 이름으로는 모름
    }

    /**
     * 목적: 하위 폴더별 계획을 만든다(이름으로 정해진 것, 기억한 것, 건너뛰기로 기억한 것, 모르는 것으로 나눈다).
     * 입력: byName - (이름, 문서 ID) 와 이름으로 짐작한 언어, memory - 기억 값
     * 출력: 없음
     * 리턴: LangPlan(splitByLang = true)
     */
    private fun splitPlan(byName: List<Pair<Pair<String, String>, Language?>>, memory: Map<String, Language?>): LangPlan {
        val folders = ArrayList<LangFolder>()                                            // 돌릴 폴더
        val unknown = ArrayList<LangFolder>()                                            // 물어야 할 폴더
        val skipped = ArrayList<LangFolder>()                                            // 건너뛰기로 기억한 폴더
        for ((child, hint) in byName) {
            val (name, docId) = child
            when {
                hint != null -> folders += LangFolder(name, docId, hint, LangSource.AUTO_SUBFOLDER)           // 이름으로 정함
                docId !in memory -> unknown += LangFolder(name, docId, null, LangSource.AUTO_SUBFOLDER)       // 모름 → 묻는다
                memory[docId] == null -> skipped += LangFolder(name, docId, null, LangSource.REMEMBERED)      // 건너뛰기로 기억
                else -> folders += LangFolder(name, docId, memory[docId], LangSource.REMEMBERED)              // 기억한 언어
            }
        }
        return LangPlan(folders, unknown, skipped, true)
    }
}
