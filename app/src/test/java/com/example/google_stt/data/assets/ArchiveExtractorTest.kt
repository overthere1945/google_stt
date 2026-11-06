/*
 * 파일명: data/assets/ArchiveExtractorTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-ASSET-02] 압축 해제 규칙을 작은 견본 압축 파일로 확인한다(시험 안에서 tar.bz2·zip 을 만든다):
 *   지정한 파일만 남음, 위험한 경로("../x")는 E-AS-07, 필요한 파일이 없거나 깨진 파일도 E-AS-07, 취소는 그대로 전달.
 *
 * add-hyungchul-20261106-1700 — [T4-3]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.AppError                                             // E-AS-07
import com.example.google_stt.domain.catalog.ArchiveType                                  // 압축 형식
import com.example.google_stt.domain.catalog.AssetFile                                    // 꺼낼 파일
import java.io.File                                                                       // 임시 파일
import java.util.zip.ZipEntry                                                             // zip 항목
import java.util.zip.ZipOutputStream                                                      // zip 만들기
import kotlinx.coroutines.CancellationException                                           // 취소
import kotlinx.coroutines.Job                                                             // 취소된 범위 만들기
import kotlinx.coroutines.runBlocking                                                     // suspend 함수 부르기
import kotlinx.coroutines.withContext                                                     // 범위 바꾸기
import org.apache.commons.compress.archivers.tar.TarArchiveEntry                          // tar 항목
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream                   // tar 만들기
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream          // bzip2 압축
import org.junit.Assert.assertEquals                                                      // 값 비교
import org.junit.Assert.assertTrue                                                        // 조건 확인
import org.junit.Assert.fail                                                              // 예외가 안 났을 때 실패
import org.junit.Rule                                                                     // 시험 규칙
import org.junit.Test                                                                     // JUnit 4 시험 표시
import org.junit.rules.TemporaryFolder                                                    // 임시 폴더

/** 목적: ArchiveExtractor 시험. */
class ArchiveExtractorTest {

    @get:Rule
    val tmp = TemporaryFolder()                                                           // 시험이 끝나면 지워지는 폴더

    /** 공식 패키지처럼 "<패키지>/<파일>" 로 들어 있고, 쓰지 않는 큰 파일도 섞여 있는 견본 내용 */
    private val entries = linkedMapOf(
        "pkg/README.md" to "읽어 보세요",
        "pkg/base-encoder.onnx" to "fp32 인코더(쓰지 않음)".repeat(200),
        "pkg/base-encoder.int8.onnx" to "int8 인코더",
        "pkg/base-tokens.txt" to "a 1\nb 2\n",
        "pkg/tokenizer/vocab.json" to "{\"a\":1}",
    )

    /** 꺼낼 파일 3개(압축 안 경로 → 저장 경로) */
    private val wanted = listOf(
        AssetFile("pkg/base-encoder.int8.onnx", "base-encoder.int8.onnx"),
        AssetFile("pkg/base-tokens.txt", "base-tokens.txt"),
        AssetFile("pkg/tokenizer/vocab.json", "tokenizer/vocab.json"),
    )

    /**
     * 목적: tar.bz2 견본을 만든다.
     * 입력: name - 파일 이름, items - 항목 이름 → 내용 / 출력: 임시 폴더에 파일이 생긴다 / 리턴: File
     */
    private fun tarBz2(name: String, items: Map<String, String>): File {
        val f = tmp.newFile(name)
        TarArchiveOutputStream(BZip2CompressorOutputStream(f.outputStream())).use { tar ->
            for ((n, text) in items) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                val e = TarArchiveEntry(n, true)                                          // true = 이름을 그대로 둔다(시험용 위험 경로도 넣을 수 있게)
                e.size = bytes.size.toLong()
                tar.putArchiveEntry(e); tar.write(bytes); tar.closeArchiveEntry()
            }
        }
        return f
    }

    /**
     * 목적: zip 견본을 만든다.
     * 입력: name - 파일 이름, items - 항목 이름 → 내용 / 출력: 임시 폴더에 파일이 생긴다 / 리턴: File
     */
    private fun zip(name: String, items: Map<String, String>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, text) in items) { z.putNextEntry(ZipEntry(n)); z.write(text.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return f
    }

    /**
     * 목적: 폴더 아래의 파일 상대 경로를 모은다.
     * 입력: dir - 폴더 / 출력: 없음 / 리턴: Set<String>
     */
    private fun tree(dir: File): Set<String> = dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).path.replace('\\', '/') }.toSet()

    /**
     * 목적: block 이 E-AS-07 을 던지는지 확인한다.
     * 입력: block - 실행할 일 / 출력: 없음 / 리턴: AppError.AssetExtract - 던져진 오류(메시지 확인용)
     */
    private fun expectExtractError(block: suspend () -> Unit): AppError.AssetExtract {
        try {
            runBlocking { block() }
        } catch (e: AppError.AssetExtract) {
            assertEquals("E-AS-07", e.code)
            return e
        }
        fail("E-AS-07 이 나야 한다"); throw IllegalStateException()
    }

    /**
     * 목적: [UT-ASSET-02] tar.bz2 — 지정한 파일만 남고(쓰지 않는 fp32·README 는 버린다) 내용이 같다. 임시 파일이 남지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun tarBz2_keepsOnlyWantedFiles() = runBlocking {
        val dest = tmp.newFolder("asset")
        var bytes = 0L
        ArchiveExtractor.extract(tarBz2("a.tar.bz2", entries), ArchiveType.TAR_BZ2, wanted, dest) { bytes += it }
        assertEquals(setOf("base-encoder.int8.onnx", "base-tokens.txt", "tokenizer/vocab.json"), tree(dest))
        assertEquals("int8 인코더", File(dest, "base-encoder.int8.onnx").readText())
        assertEquals("{\"a\":1}", File(dest, "tokenizer/vocab.json").readText())          // 하위 폴더도 만든다
        assertEquals(wanted.sumOf { File(dest, it.target).length() }, bytes)              // 진행 알림 = 꺼낸 바이트 수
    }

    /**
     * 목적: zip 도 같은 규칙이고, "./" 로 시작하는 항목 이름과 이미 있는 파일 덮어쓰기를 처리한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun zip_sameRules_andOverwrite() = runBlocking {
        val dest = tmp.newFolder("asset")
        File(dest, "base-tokens.txt").writeText("옛 내용이 더 길다 ............")
        val items = entries.mapKeys { "./" + it.key }                                     // "./pkg/…" 형태
        ArchiveExtractor.extract(zip("a.zip", items), ArchiveType.ZIP, wanted, dest)
        assertEquals(setOf("base-encoder.int8.onnx", "base-tokens.txt", "tokenizer/vocab.json"), tree(dest))
        assertEquals("a 1\nb 2\n", File(dest, "base-tokens.txt").readText())              // 새 내용으로 바뀐다
    }

    /**
     * 목적: [UT-ASSET-02] 위험한 경로("../x", 절대 경로)가 든 압축 파일은 E-AS-07 로 거부한다(필요 없는 항목이어도).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun unsafePaths_areRejected() {
        val evil = linkedMapOf("pkg/base-tokens.txt" to "ok", "../x" to "밖에 쓰기", "pkg/base-encoder.int8.onnx" to "i")
        val dest = tmp.newFolder("asset")
        val e1 = expectExtractError { ArchiveExtractor.extract(tarBz2("evil.tar.bz2", evil), ArchiveType.TAR_BZ2, wanted, dest) }
        assertTrue(e1.message!!.contains("../x"))
        assertTrue(!File(tmp.root, "x").exists())                                         // 폴더 밖에 아무것도 쓰지 않았다
        expectExtractError { ArchiveExtractor.extract(zip("evil.zip", mapOf("pkg/a/../../y" to "z")), ArchiveType.ZIP, wanted, dest) }
        expectExtractError { ArchiveExtractor.extract(zip("abs.zip", mapOf("/etc/passwd" to "z")), ArchiveType.ZIP, wanted, dest) }
    }

    /**
     * 목적: 필요한 파일이 압축 안에 없거나 압축 파일이 깨졌으면 E-AS-07 이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun missingOrCorrupt_isReported() {
        val dest = tmp.newFolder("asset")
        val partial = entries.filterKeys { it != "pkg/tokenizer/vocab.json" }
        val e = expectExtractError { ArchiveExtractor.extract(tarBz2("partial.tar.bz2", partial), ArchiveType.TAR_BZ2, wanted, dest) }
        assertTrue(e.message!!.contains("pkg/tokenizer/vocab.json"))                      // 무엇이 없는지 알려 준다
        val broken = tmp.newFile("broken.tar.bz2").apply { writeBytes(ByteArray(300) { 7 }) }
        expectExtractError { ArchiveExtractor.extract(broken, ArchiveType.TAR_BZ2, wanted, dest) }
        val good = tarBz2("cut.tar.bz2", entries)
        val cut = tmp.newFile("cut2.tar.bz2").apply { writeBytes(good.readBytes().copyOf(good.length().toInt() / 2)) }   // 받다 만 파일
        expectExtractError { ArchiveExtractor.extract(cut, ArchiveType.TAR_BZ2, wanted, dest) }
    }

    /**
     * 목적: 취소된 상태에서는 CancellationException 이 그대로 올라온다(오류 E-AS-07 로 바뀌지 않는다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun cancellation_isNotSwallowed() = runBlocking {
        val dest = tmp.newFolder("asset")
        val archive = tarBz2("a.tar.bz2", entries)
        val cancelled = Job().apply { cancel() }                                          // 이미 취소된 범위
        try {
            withContext(cancelled) { ArchiveExtractor.extract(archive, ArchiveType.TAR_BZ2, wanted, dest) }
            fail("취소 예외가 나야 한다")
        } catch (e: CancellationException) {
            assertTrue(tree(dest).isEmpty())                                              // 아무것도 꺼내지 않았다
        }
    }
}
