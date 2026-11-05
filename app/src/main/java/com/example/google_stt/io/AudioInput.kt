/*
 * 파일명: io/AudioInput.kt
 * 목적 및 기능:
 * - 입력 폴더(SAF DocumentFile)를 재귀로 훑어 오디오 파일 목록(AudioItem)을 만든다.
 * - 로그/CSV 에 쓰는 "하위폴더/파일명" 표시 문자열을 만든다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 audioExtensions / AudioItem / collectAudio / displayRel 을 내용 변경 없이 옮겼다.
 *
 * add-hyungchul-20261104-1750 — v3.0.0: 입력 폴더 이름으로 언어 자동(kr / en)
 *   · 서버 stt_all_gui_server v8.4.0 의 '폴더 이름으로 자동' 규칙(input_language_hint / _lang_folder_entries)을 그대로 옮겼다.
 *   · inputLanguageHint() : 경로 마디가 낱말과 같거나 '_<낱말>' 로 끝나면 그 언어(예: kr, Customer_Service_KR → ko)
 *   · planLanguageFolders() : 어느 폴더를 어느 언어로 돌릴지 정한다(순수 함수 — Android 없이 시험할 수 있다)
 *   · 서버와 다른 점: 이름으로 언어를 모르는 폴더는 묻지 않고 건너뛴다(로그로 알림).
 */
package com.example.google_stt.io

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/** 입력 폴더에서 오디오로 인정하는 확장자 목록(change-hyungchul-20261009-1514 : MainActivity.audioExtensions 를 옮김) */
val audioExtensions =
    setOf("mp3", "wav", "m4a", "flac", "ogg", "aac", "mp4", "wma", "opus")

// ─────────────────────────── 입력 폴더 탐색 ───────────────────────────
data class AudioItem(
    val uri: Uri,
    val relPath: List<String>,
    val nameNoExt: String,
    val sizeBytes: Long,   // change(add)-hyungchul-20260820: 원본 파일 크기(CSV src_bytes)
)

fun collectAudio(dir: DocumentFile, prefix: List<String>, acc: MutableList<AudioItem>) {
    for (child in dir.listFiles()) {
        if (child.isDirectory) {
            collectAudio(child, prefix + (child.name ?: "unknown"), acc)
        } else if (child.isFile) {
            val name = child.name ?: continue
            val dot = name.lastIndexOf('.')
            val ext = if (dot >= 0) name.substring(dot + 1).lowercase(Locale.US) else ""
            if (ext in audioExtensions) {
                val base = if (dot >= 0) name.substring(0, dot) else name
                acc.add(AudioItem(child.uri, prefix, base, child.length()))
            }
        }
    }
}

fun displayRel(item: AudioItem): String {
    val p = if (item.relPath.isEmpty()) "" else item.relPath.joinToString("/") + "/"
    return p + item.nameNoExt
}


// ─────────────────────────── add-hyungchul-20261104-1750 : 폴더 이름으로 언어 자동 ───────────────────────────

/**
 * 폴더 이름으로 언어를 짐작하는 낱말(서버 stt_all_gui_server.py INPUT_LANG_WORDS 와 같은 값).
 * 키: "ko" / "en", 값: 소문자 낱말 목록
 */
val INPUT_LANG_WORDS: Map<String, Set<String>> = linkedMapOf(
    "ko" to setOf("kr", "ko", "kor", "korea", "korean", "한국어"),   // 한국어로 보는 이름
    "en" to setOf("en", "eng", "english", "영어"),                  // 영어로 보는 이름
)

/**
 * 목적: 경로(또는 폴더 이름)의 마디로 음원 언어를 짐작한다(서버 input_language_hint 와 같은 규칙).
 * 입력: paths — 경로 문자열 목록('/', '\', ':' 로 마디를 나눈다 — ':' 는 SAF 문서 ID "primary:Documents/…" 용)
 * 출력: 없음
 * 리턴: "ko" / "en" — 한 언어만 보일 때. 아무 언어도 안 보이거나 두 언어가 다 보이면 null
 */
fun inputLanguageHint(paths: List<String>): String? {
    val found = LinkedHashSet<String>()                                         // 보인 언어들
    for (path in paths) {                                                       // 경로마다
        for (seg in path.split('/', '\\', ':')) {                              // 마디마다
            val low = seg.trim().lowercase(Locale.ROOT)                         // 대소문자 무시
            if (low.isEmpty()) continue                                         // 빈 마디는 건너뛴다
            for ((lang, words) in INPUT_LANG_WORDS) {                           // 언어마다 낱말 비교
                if (low in words || words.any { low.endsWith("_$it") }) found += lang   // 같거나 '_낱말' 로 끝나면
            }
        }
    }
    return if (found.size == 1) found.first() else null                         // 한 언어만 보일 때만
}

/**
 * 목적: 언어 폴더 하나(돌릴 단위).
 * 필드: name — 입력 폴더 바로 아래 하위 폴더 이름("" = 입력 폴더 자체 — 결과 폴더를 나누지 않는다)
 *       lang — "ko" / "en"
 */
data class LangFolder(val name: String, val lang: String)

/**
 * 목적: 언어 자동 계획(돌릴 언어 폴더 + 이름으로 언어를 모르는 폴더).
 * 필드: groups — 차례로 돌릴 언어 폴더, unknown — 이름으로 언어를 모르는 폴더 이름(건너뜀, "" = 입력 폴더 자체)
 */
data class LangPlan(val groups: List<LangFolder>, val unknown: List<String>)

/**
 * 목적: '폴더 이름으로 자동' 에서 어느 폴더를 어느 언어로 돌릴지 정한다(서버 _lang_folder_entries 와 같은 순서·규칙).
 *   (1) 하위 폴더 중 이름으로 언어가 정해지는 것이 있으면 → 그 하위 폴더들(나머지는 모름 → 건너뜀)
 *   (2) 없고 입력 폴더 경로로 정해지면(예: …/input_sound/kr) → 입력 폴더 하나(결과 폴더를 나누지 않음)
 *   (3) 그 밖 → 모두 모름(하위 폴더가 없으면 입력 폴더 자체를 모름으로)
 * 입력: rootPath — 입력 폴더 경로(SAF 문서 ID 등), subDirs — 바로 아래 하위 폴더 이름 목록
 * 출력: 없음
 * 리턴: LangPlan(하위 폴더는 이름순 — 서버 sorted() 와 같은 순서)
 */
fun planLanguageFolders(rootPath: String, subDirs: List<String>): LangPlan {
    val subs = subDirs.filter { !it.startsWith(".") }.sorted()                  // 숨김 폴더 제외, 이름순
    val hints = subs.map { it to inputLanguageHint(listOf(it)) }               // 하위 폴더 이름마다 언어 짐작
    if (hints.any { it.second != null }) {                                      // (1) 이름으로 정해지는 하위 폴더가 있다
        return LangPlan(
            groups = hints.mapNotNull { (n, l) -> l?.let { LangFolder(n, it) } },
            unknown = hints.filter { it.second == null }.map { it.first },
        )
    }
    val rootHint = inputLanguageHint(listOf(rootPath))                          // 입력 폴더 경로로 짐작
    if (rootHint != null) return LangPlan(listOf(LangFolder("", rootHint)), emptyList())   // (2)
    return LangPlan(emptyList(), if (subs.isEmpty()) listOf("") else subs)     // (3)
}
