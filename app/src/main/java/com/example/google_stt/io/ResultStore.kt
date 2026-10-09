/*
 * 파일명: io/ResultStore.kt
 * 목적 및 기능:
 * - 출력 폴더(SAF)에 결과 폴더를 만들고 전사 TXT / result.csv / run_meta.csv 를 쓴다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 ensureRunDir / writeCsv / saveResult / ensureDir / writeBytes 를 내용 변경 없이 옮겼다.
 *   contentResolver 와 algoName("google") 은 생성자로 받는다.
 */
package com.example.google_stt.io

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import com.example.google_stt.SttTelemetry
import java.io.IOException

/**
 * 목적: 결과 저장기.
 * 입력(생성자): contentResolver — 파일 쓰기에 쓸 ContentResolver, algoName — 결과 최상위 폴더 이름("google")
 */
class ResultStore(
    private val contentResolver: ContentResolver,
    private val algoName: String,
) {

    /**
     * add-hyungchul-20260825-1430
     * change-hyungchul-20260826-1100: vadFolder(String?) → variant(List<String>) 로 일반화.
     *   노이즈 저감 폴더가 하나 더 끼어들어야 해서, 앞으로 단계가 늘어나도 시그니처를 안 바꾸도록
     *   "추가 경로 조각 목록"을 그대로 받는다.
     * 목적: 이번 실행의 결과 폴더를 만든다.
     * 입력: outputRoot(출력 트리), modelFolder(base/advanced/android_ondevice),
     *       variant — 비어 있으면 기존 경로를 그대로 쓴다. 예: ["ns_gtcrn_lim12", "vad_asr_safe"]
     * 리턴: output/google/<모드>[/ns_xxx][/vad_yyy] DocumentFile
     */
    fun ensureRunDir(
        outputRoot: DocumentFile,
        modelFolder: String,
        variant: List<String>,
    ): DocumentFile {
        val segments = mutableListOf(algoName, modelFolder)
        segments.addAll(variant)
        return ensureDir(outputRoot, segments)
    }

    // change-hyungchul-20260826-1100: variant 목록을 받는다(빈 목록이면 기존 동작과 동일)
    fun writeCsv(
        outputRoot: DocumentFile,
        modelFolder: String,
        variant: List<String>,
        body: String,
    ) {
        val dir = ensureRunDir(outputRoot, modelFolder, variant)
        writeBytes(
            dir, "result.csv",
            SttTelemetry.BOM + body.toByteArray(Charsets.UTF_8), "text/csv",
        )
    }

    // ─────────────────────────── 출력 저장 ───────────────────────────
    // change-hyungchul-20260826-1100: variant 목록을 받는다(빈 목록이면 기존 경로와 완전히 동일)
    fun saveResult(
        outputRoot: DocumentFile,
        modeFolder: String,
        variant: List<String>,
        item: AudioItem,
        text: String,
    ) {
        val segments = mutableListOf(algoName, modeFolder)
        segments.addAll(variant)
        segments.addAll(item.relPath)
        val dir = ensureDir(outputRoot, segments)
        writeBytes(dir, item.nameNoExt + ".txt", text.toByteArray(Charsets.UTF_8))
    }

    fun ensureDir(root: DocumentFile, segments: List<String>): DocumentFile {
        var cur = root
        for (seg in segments) {
            val existing = cur.findFile(seg)
            cur = if (existing != null && existing.isDirectory) existing
            else cur.createDirectory(seg) ?: throw IOException("폴더 생성 실패: $seg")
        }
        return cur
    }

    fun writeBytes(
        dir: DocumentFile,
        fileName: String,
        bytes: ByteArray,
        mime: String = "text/plain",
    ) {
        dir.findFile(fileName)?.let { if (it.isFile) it.delete() }
        val file = dir.createFile(mime, fileName)
            ?: throw IOException("파일 생성 실패: $fileName")
        contentResolver.openOutputStream(file.uri)?.use { it.write(bytes) }
            ?: throw IOException("파일 쓰기 실패: $fileName")
    }
}
