/*
 * 파일명: data/assets/ArchiveExtractor.kt
 * 목적 및 기능:
 * - 내려받은 압축 파일(tar.bz2 / zip)에서 자산 정의에 적힌 파일만 꺼낸다(FR-05-03, 3단계 3-3·3-5).
 *   공식 패키지에는 쓰지 않는 fp32 파일도 들어 있어 전부 풀면 저장공간을 낭비한다 → AssetSpec.files 의 항목만 남긴다.
 * - 안전: 압축 안 경로에 ".." 마디나 절대 경로가 있으면 압축 파일 전체를 거부한다(E-AS-07 — 폴더 밖에 쓰는 공격 차단).
 * - 꺼낸 파일은 "<이름>.part" 로 쓴 뒤 이름을 바꾼다(풀다 죽어도 반쪽 파일이 정식 이름으로 남지 않는다).
 * - 필요한 파일이 압축 안에 없거나 압축 파일이 깨졌으면 E-AS-07 로 알린다. 취소(CancellationException)는 그대로 올린다.
 * - tar.bz2 는 Apache Commons Compress 1.28.0, zip 은 java.util.zip 을 쓴다. Android 를 쓰지 않아 PC(JVM)에서 시험한다.
 * - bzip2 는 한 스레드로 풀려 큰 파일(whisper-medium 1.9 GB)은 수 분 걸릴 수 있다 → 부르는 쪽이 입출력 스레드에서 돌린다.
 *
 * add-hyungchul-20261106-1700 — [T4-3]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.AppError                                             // E-AS-07
import com.example.google_stt.domain.catalog.ArchiveType                                  // 압축 형식
import com.example.google_stt.domain.catalog.AssetFile                                    // 꺼낼 파일
import java.io.BufferedInputStream                                                        // 읽기 버퍼
import java.io.File                                                                       // 파일
import java.io.IOException                                                                // 깨진 압축 파일
import java.io.InputStream                                                                // 항목 내용
import java.util.zip.ZipInputStream                                                       // zip 읽기
import kotlinx.coroutines.currentCoroutineContext                                         // 취소 확인
import kotlinx.coroutines.ensureActive                                                    // 취소 확인
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream                    // tar 읽기
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream           // bzip2 풀기

/** 목적: 압축 파일에서 필요한 파일만 꺼내기. */
object ArchiveExtractor {

    private const val BUFFER = 64 * 1024   // 복사 단위 64 KB

    /**
     * 목적: 압축 파일에서 files 에 적힌 항목만 destDir 에 꺼낸다.
     * 입력: archive - 압축 파일, type - 압축 형식(TAR_BZ2 / ZIP), files - 꺼낼 파일(압축 안 경로 → 저장 경로),
     *       destDir - 자산 폴더, onBytes - 꺼낸 바이트 수를 받는 함수(진행 표시용, 조각마다 불린다)
     * 출력: destDir 아래에 파일들이 쓰인다
     * 리턴: 없음
     * 예외: AppError.AssetExtract(E-AS-07) - 위험한 경로, 필요한 파일 없음, 깨진 압축 파일. 취소되면 CancellationException
     */
    suspend fun extract(archive: File, type: ArchiveType, files: List<AssetFile>, destDir: File, onBytes: (Long) -> Unit = {}) {
        require(type != ArchiveType.NONE) { "압축이 없는 자산은 풀 것이 없다" }
        val wanted = files.associateBy { normalize(it.pathInArchive) }                    // 압축 안 경로 → 꺼낼 파일
        val done = HashSet<String>()                                                      // 꺼낸 경로
        try {
            BufferedInputStream(archive.inputStream(), BUFFER).use { raw ->
                if (type == ArchiveType.ZIP) {
                    ZipInputStream(raw).use { zip ->
                        while (true) {
                            val e = zip.nextEntry ?: break                                // 다음 항목(없으면 끝)
                            handle(e.name, e.isDirectory, zip, wanted, done, destDir, onBytes)
                        }
                    }
                } else {
                    TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                        while (true) {
                            val e = tar.nextEntry ?: break
                            handle(e.name, e.isDirectory, tar, wanted, done, destDir, onBytes)
                        }
                    }
                }
            }
        } catch (e: IOException) {
            throw AppError.AssetExtract("압축 파일을 읽을 수 없다(${archive.name}): ${e.message}", e)   // 깨진 파일·쓰기 실패
        }
        val missing = wanted.keys - done
        if (missing.isNotEmpty()) throw AppError.AssetExtract("압축 안에 필요한 파일이 없다: ${missing.sorted().joinToString(", ")}")
    }

    /**
     * 목적: 압축 항목 하나를 처리한다 — 경로를 검사하고, 필요한 파일이면 꺼낸다.
     * 입력: rawName - 항목 이름, isDir - 폴더 항목인지, input - 항목 내용을 읽을 스트림, wanted - 꺼낼 파일 표,
     *       done - 꺼낸 경로 모음, destDir - 자산 폴더, onBytes - 진행 알림
     * 출력: 파일이 쓰일 수 있고 done 에 더해진다
     * 리턴: 없음
     */
    private suspend fun handle(
        rawName: String, isDir: Boolean, input: InputStream, wanted: Map<String, AssetFile>,
        done: MutableSet<String>, destDir: File, onBytes: (Long) -> Unit,
    ) {
        currentCoroutineContext().ensureActive()                                          // 항목마다 취소 확인
        val name = normalize(rawName)
        if (isUnsafe(name)) throw AppError.AssetExtract("위험한 경로가 든 압축 파일이다: '$rawName'")
        val spec = wanted[name] ?: return                                                 // 필요 없는 항목은 건너뛴다(읽지 않는다)
        if (isDir) return
        if (isUnsafe(spec.target)) throw AppError.AssetExtract("저장 경로가 위험하다: '${spec.target}'")
        val out = File(destDir, spec.target)
        out.parentFile?.mkdirs()                                                          // 하위 폴더(tokenizer/ 등)
        val part = File(out.path + ".part")
        part.outputStream().use { os ->
            val buf = ByteArray(BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive()                                  // 조각마다 취소 확인
                val n = input.read(buf)
                if (n < 0) break
                os.write(buf, 0, n)
                onBytes(n.toLong())
            }
        }
        if (out.exists()) out.delete()                                                    // 옛 파일 지우기
        if (!part.renameTo(out)) throw AppError.AssetExtract("파일 이름을 바꿀 수 없다: ${out.path}")
        done += name
    }

    /**
     * 목적: 압축 안 경로를 비교하기 좋게 다듬는다.
     * 입력: path - 항목 이름 / 출력: 없음 / 리턴: String - 역슬래시를 슬래시로, 앞의 "./" 를 뗀 경로
     */
    private fun normalize(path: String): String {
        var p = path.replace('\\', '/')
        while (p.startsWith("./")) p = p.substring(2)
        return p
    }

    /**
     * 목적: 폴더 밖을 가리킬 수 있는 경로인지 본다.
     * 입력: path - 다듬은 경로 / 출력: 없음 / 리턴: Boolean - ".." 마디가 있거나 절대 경로(/…, C:…)면 true
     */
    private fun isUnsafe(path: String): Boolean =
        path.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(path) || path.split('/').any { it == ".." }
}
