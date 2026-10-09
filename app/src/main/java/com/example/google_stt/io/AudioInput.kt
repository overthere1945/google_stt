/*
 * 파일명: io/AudioInput.kt
 * 목적 및 기능:
 * - 입력 폴더(SAF DocumentFile)를 재귀로 훑어 오디오 파일 목록(AudioItem)을 만든다.
 * - 로그/CSV 에 쓰는 "하위폴더/파일명" 표시 문자열을 만든다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 audioExtensions / AudioItem / collectAudio / displayRel 을 내용 변경 없이 옮겼다.
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

