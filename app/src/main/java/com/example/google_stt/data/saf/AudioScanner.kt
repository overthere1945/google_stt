/*
 * 파일명: data/saf/AudioScanner.kt
 * 목적 및 기능:
 * - 입력 폴더(SAF)에서 음원 파일을 재귀적으로 모은다(FR-01-01). v3 io/AudioInput.kt 의 collectAudio 를 대신한다.
 * - 인정하는 확장자는 v3 와 같다: mp3, wav, m4a, flac, ogg, aac, mp4, wma, opus.
 * - v3 와 달라진 점: 결과를 폴더 경로 → 파일 이름 순으로 정렬한다(제공자가 주는 순서에 기대지 않는다 — 실행 순서가 매번 같다).
 * - 언어 판정(LanguageResolver.plan)에 넘길 하위 폴더 목록과 문서 ID 도 여기서 읽는다.
 *
 * add-hyungchul-20261106-1636 — [T3-2]
 */
package com.example.google_stt.data.saf

import android.net.Uri                                   // 음원 위치
import android.provider.DocumentsContract                // 문서 ID 읽기
import androidx.documentfile.provider.DocumentFile       // SAF 문서

/**
 * 목적: 음원 파일 1개.
 * 필드: uri - 위치, relDir - 훑기 시작한 폴더 아래 상대 폴더 조각, fileName - 파일 이름(확장자 포함),
 *       baseName - 확장자를 뺀 이름, ext - 점 포함 소문자 확장자(".wav"), bytes - 파일 크기
 */
data class AudioItem(
    val uri: Uri,                // 위치
    val relDir: List<String>,    // 상대 폴더
    val fileName: String,        // 파일 이름
    val baseName: String,        // 확장자를 뺀 이름
    val ext: String,             // 확장자
    val bytes: Long,             // 파일 크기
)

/** 목적: 음원 모으기. */
object AudioScanner {

    /** 음원으로 인정하는 확장자(점 포함 소문자) */
    val EXTENSIONS: Set<String> = setOf(".mp3", ".wav", ".m4a", ".flac", ".ogg", ".aac", ".mp4", ".wma", ".opus")

    /**
     * 목적: 폴더 아래의 음원을 모두 모은다(하위 폴더 포함).
     * 입력: dir - 훑을 폴더(언어 폴더 또는 입력 폴더)
     * 출력: 없음
     * 리턴: List<AudioItem> - 상대 폴더 경로 → 파일 이름 순으로 정렬
     */
    fun scan(dir: DocumentFile): List<AudioItem> {
        val out = ArrayList<AudioItem>()
        collect(dir, emptyList(), out)
        return out.sortedWith(compareBy({ it.relDir.joinToString("/") }, { it.fileName }))   // 순서를 고정한다
    }

    /**
     * 목적: 폴더 하나를 훑어 음원을 더하고 하위 폴더로 내려간다.
     * 입력: dir - 폴더, prefix - 여기까지의 상대 폴더 조각, acc - 모으는 목록 / 출력: acc 에 더한다 / 리턴: 없음
     */
    private fun collect(dir: DocumentFile, prefix: List<String>, acc: MutableList<AudioItem>) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue                                // 이름이 없는 문서는 건너뛴다
            if (child.isDirectory) {
                collect(child, prefix + name, acc)                           // 하위 폴더로
            } else if (child.isFile) {
                val (base, ext) = OutputLayout.splitName(name)               // 이름과 확장자
                if (ext in EXTENSIONS) acc.add(AudioItem(child.uri, prefix, name, base, ext, child.length()))
            }
        }
    }

    /**
     * 목적: 바로 아래 하위 폴더의 (이름, 문서 ID) 목록을 읽는다(LanguageResolver.plan 의 children).
     * 입력: root - 입력 폴더 / 출력: 없음 / 리턴: List<Pair<String, String>>
     */
    fun subDirs(root: DocumentFile): List<Pair<String, String>> =
        root.listFiles().filter { it.isDirectory }.mapNotNull { d -> d.name?.let { it to docId(d) } }

    /**
     * 목적: 문서의 식별 글자를 얻는다(언어 기억 값의 키, 경로 판정에도 쓴다).
     * 입력: doc - 문서
     * 출력: 없음
     * 리턴: String - SAF 문서면 문서 ID(예 "primary:Documents/input_sound/kr"), 아니면 URI 글자
     */
    fun docId(doc: DocumentFile): String {
        if (doc.uri.scheme != "content") return doc.uri.path ?: doc.uri.toString()   // 파일 기반 문서는 경로
        return try {
            DocumentsContract.getDocumentId(doc.uri)                         // 트리 문서의 문서 ID
        } catch (e: IllegalArgumentException) {
            doc.uri.toString()                                               // 문서 URI 가 아니면 URI 글자
        }
    }
}
