/*
 * 파일명: data/saf/SafTree.kt
 * 목적 및 기능:
 * - SAF(Storage Access Framework) 폴더 트리에 대한 읽기·쓰기 도구. v3 io/ResultStore.kt 의 ensureDir / writeBytes 를 대신한다.
 * - 자식 목록 캐시: 폴더마다 자식 목록을 한 번만 읽어 이름 → 문서 맵으로 기억한다
 *   (v3 는 파일마다 findFile 로 폴더 전체를 다시 조회했다 — 1단계 위험 10).
 * - 원자적 쓰기(4단계 7-2): ① "<이름>.tmp" 에 전부 쓴다 ② 옛 "<이름>" 을 지운다 ③ tmp 를 "<이름>" 으로 이름을 바꾼다
 *   ④ 복구: "<이름>" 이 없고 "<이름>.tmp" 만 있으면 ③ 을 다시 한다(recoverTmp).
 *   → 쓰는 도중 앱이 죽어도 온전한 옛 파일이나 온전한 임시 파일 중 하나는 남는다(v3 는 지운 뒤 새로 써서 CSV 가 사라질 수 있었다 — 위험 7).
 *   한계: 처음 만드는 파일을 ① 도중에 죽으면 덜 쓰인 tmp 만 남고, 복구가 그것을 "<이름>" 으로 올린다.
 *         그런 파일은 중단된 작업의 산출물이라 이어 하기 때 다시 만들어진다(D30).
 * - 이름 바꾸기가 안 되는 제공자면 "지우고 새로 쓰기" 로 대신하고 경고를 남긴다(E-OUT-03).
 * - 쓰기 실패는 AppError.OutputUnavailable(E-OUT-01) / OutputNoSpace(E-OUT-02)로 알린다.
 * - 여러 작업이 같은 언어 폴더에 동시에 쓸 수 있으므로 메서드를 @Synchronized 로 묶었다.
 *
 * add-hyungchul-20261106-1633 — [T3-1]
 */
package com.example.google_stt.data.saf

import android.content.ContentResolver                    // 문서 열기
import android.net.Uri                                    // 캐시 키(폴더 URI)
import androidx.documentfile.provider.DocumentFile        // SAF 문서
import com.example.google_stt.domain.AppError             // E-OUT-01 / E-OUT-02
import java.io.IOException                                // 쓰기 오류

/**
 * 목적: SAF 폴더 트리 도구.
 * 입력(생성자): resolver - 문서를 여는 ContentResolver, root - 트리의 뿌리 폴더,
 *               warn - 경고 문장을 받는 함수(E-OUT-03 등. 기본은 아무것도 하지 않음 — 호출하는 쪽이 로그에 연결한다)
 */
class SafTree(
    private val resolver: ContentResolver,
    val root: DocumentFile,
    private val warn: (String) -> Unit = {},
) {
    companion object {
        /** 임시 파일 꼬리 */
        const val TMP_SUFFIX = ".tmp"

        /**
         * 만들 때 쓰는 MIME — 확장자 대응표(MimeTypeMap)에 없는 값을 써서 제공자가 이름에 확장자를 덧붙이지 않게 한다(이름은 우리가 정한다).
         * 실측(S24 Ultra, Android 15): "application/octet-stream" 은 파일 기반 DocumentFile 에서 ".bin" 이 붙어
         * "x.csv.tmp.bin" 이 되고, 그러면 tmp 복구 규칙이 그 파일을 찾지 못한다.
         */
        const val MIME = "application/x-google-stt"
    }

    /** 폴더 URI → (자식 이름 → 문서). 한 번 읽은 폴더는 다시 조회하지 않는다 */
    private val cache = HashMap<Uri, MutableMap<String, DocumentFile>>()

    /**
     * 목적: 폴더의 자식 맵을 얻는다(처음이면 한 번 읽어 기억한다).
     * 입력: dir - 폴더 / 출력: 캐시에 저장 / 리턴: MutableMap<String, DocumentFile> - 이름 → 문서
     */
    private fun kids(dir: DocumentFile): MutableMap<String, DocumentFile> =
        cache.getOrPut(dir.uri) {
            val map = LinkedHashMap<String, DocumentFile>()                 // 읽은 순서 유지
            for (child in dir.listFiles()) child.name?.let { map[it] = child }   // 이름이 없는 문서는 건너뛴다
            map
        }

    /**
     * 목적: 폴더의 자식 목록을 돌려준다.
     * 입력: dir - 폴더 / 출력: 없음 / 리턴: Map<String, DocumentFile> - 이름 → 문서(읽은 시점의 사본)
     */
    @Synchronized
    fun children(dir: DocumentFile): Map<String, DocumentFile> = LinkedHashMap(kids(dir))

    /**
     * 목적: 폴더에서 이름으로 문서를 찾는다.
     * 입력: dir - 폴더, name - 이름 / 출력: 없음 / 리턴: DocumentFile? - 없으면 null
     */
    @Synchronized
    fun find(dir: DocumentFile, name: String): DocumentFile? = kids(dir)[name]

    /**
     * 목적: 하위 폴더를 얻는다(없으면 만든다).
     * 입력: parent - 부모 폴더, name - 폴더 이름
     * 출력: 폴더가 만들어질 수 있다
     * 리턴: DocumentFile - 하위 폴더
     * 예외: AppError.OutputUnavailable - 만들 수 없을 때
     */
    @Synchronized
    fun ensureDir(parent: DocumentFile, name: String): DocumentFile {
        val map = kids(parent)
        map[name]?.takeIf { it.isDirectory }?.let { return it }             // 이미 있다
        val made = guarded("폴더 '$name' 만들기") { parent.createDirectory(name) }
            ?: throw AppError.OutputUnavailable("폴더 '$name' 을 만들 수 없다")
        map[name] = made                                                    // 캐시에 더한다
        return made
    }

    /**
     * 목적: 뿌리에서부터 여러 단계의 폴더를 얻는다(없으면 만든다).
     * 입력: segments - 폴더 이름들(빈 이름은 건너뛴다) / 출력: 폴더가 만들어질 수 있다 / 리턴: DocumentFile - 마지막 폴더
     */
    fun ensureDirs(segments: List<String>): DocumentFile {
        var cur = root
        for (seg in segments) if (seg.isNotEmpty()) cur = ensureDir(cur, seg)   // 한 단계씩 내려간다
        return cur
    }

    /**
     * 목적: 파일을 원자적으로 쓴다(임시 파일 → 옛 파일 지우기 → 이름 바꾸기).
     * 입력: dir - 폴더, name - 파일 이름, bytes - 내용
     * 출력: 파일이 만들어지거나 바뀐다. 끝나면 "<이름>.tmp" 는 남지 않는다
     * 리턴: 없음
     * 예외: AppError.OutputUnavailable(E-OUT-01), AppError.OutputNoSpace(E-OUT-02)
     */
    @Synchronized
    fun writeAtomic(dir: DocumentFile, name: String, bytes: ByteArray) {
        val map = kids(dir)
        val tmpName = name + TMP_SUFFIX
        map.remove(tmpName)?.delete()                                       // 예전 실행이 남긴 tmp 는 지운다
        val tmp = create(dir, tmpName)                                      // ① 임시 파일
        write(tmp, bytes, tmpName)
        map.remove(name)?.delete()                                          // ② 옛 파일 지우기
        val renamed = guarded("'$tmpName' 이름 바꾸기") { tmp.renameTo(name) } == true && tmp.name == name   // ③ 이름 바꾸기
        if (renamed) {
            map[name] = tmp                                                 // tmp 문서가 이제 최종 파일이다
            return
        }
        warn("E-OUT-03: '$tmpName' → '$name' 이름 바꾸기 실패 — 지우고 새로 쓴다")   // 제공자가 이름 바꾸기를 지원하지 않음
        if (tmp.exists()) tmp.delete()                                      // 남은 tmp 정리
        val direct = create(dir, name)                                      // 대신: 바로 쓴다(이 구간에서 죽으면 파일이 덜 쓰일 수 있다)
        write(direct, bytes, name)
        map[name] = direct
    }

    /**
     * 목적: 파일 내용을 읽는다.
     * 입력: dir - 폴더, name - 파일 이름 / 출력: 없음 / 리턴: ByteArray? - 파일이 없으면 null
     * 예외: AppError.OutputUnavailable - 있는데 읽을 수 없을 때
     */
    @Synchronized
    fun readBytes(dir: DocumentFile, name: String): ByteArray? {
        val doc = kids(dir)[name]?.takeIf { it.isFile } ?: return null       // 없는 파일
        return guarded("'$name' 읽기") { resolver.openInputStream(doc.uri)?.use { it.readBytes() } }
            ?: throw AppError.OutputUnavailable("'$name' 을 열 수 없다")
    }

    /**
     * 목적: 문서를 지운다(폴더면 안의 것까지).
     * 입력: dir - 폴더, name - 이름 / 출력: 문서가 지워진다 / 리턴: Boolean - 지웠으면 true(없었으면 false)
     */
    @Synchronized
    fun delete(dir: DocumentFile, name: String): Boolean {
        val doc = kids(dir).remove(name) ?: return false                    // 없으면 할 일이 없다
        cache.remove(doc.uri)                                               // 폴더였다면 그 캐시도 버린다
        return guarded("'$name' 지우기") { doc.delete() } == true
    }

    /**
     * 목적: 문서 이름을 바꾼다(작업 중 폴더 "_<이름>" → "<이름>", 묵은 폴더 → "_stale_…").
     * 입력: dir - 폴더, from - 지금 이름, to - 새 이름
     * 출력: 이름이 바뀐다
     * 리턴: Boolean - 바꿨으면 true. from 이 없거나 to 가 이미 있거나 제공자가 다른 이름을 붙였으면 false
     */
    @Synchronized
    fun rename(dir: DocumentFile, from: String, to: String): Boolean {
        val map = kids(dir)
        val doc = map[from] ?: return false                                 // 바꿀 대상이 없다
        if (map.containsKey(to)) return false                               // 같은 이름이 이미 있다
        val oldUri = doc.uri                                                // 이름을 바꾸면 URI 가 달라질 수 있다
        val ok = guarded("'$from' → '$to' 이름 바꾸기") { doc.renameTo(to) } == true
        map.remove(from)
        cache.remove(oldUri)                                                // 옛 URI 로 기억한 자식 목록은 버린다
        val actual = doc.name ?: return false                               // 바뀐 뒤 실제 이름
        map[actual] = doc
        return ok && actual == to                                           // "(1)" 같은 다른 이름이 붙었으면 실패로 본다
    }

    /**
     * 목적: 남은 임시 파일을 정리한다(실행 시작 때 부른다 — E-OUT-04).
     *       "<이름>" 이 없고 "<이름>.tmp" 만 있으면 이름을 바꿔 살리고, 둘 다 있으면 tmp(덜 쓰였을 수 있음)를 지운다.
     * 입력: dir - 폴더 / 출력: tmp 가 사라진다 / 리턴: List<String> - 살려 낸 파일 이름들
     */
    @Synchronized
    fun recoverTmp(dir: DocumentFile): List<String> {
        val recovered = ArrayList<String>()
        for (tmpName in kids(dir).keys.filter { it.endsWith(TMP_SUFFIX) }) {   // 순회 중 맵이 바뀌므로 이름 목록을 먼저 뜬다
            val name = tmpName.removeSuffix(TMP_SUFFIX)
            if (kids(dir).containsKey(name)) delete(dir, tmpName)           // 옛 파일이 온전히 있다 → tmp 는 버린다
            else if (rename(dir, tmpName, name)) recovered += name          // tmp 만 있다 → 살린다
        }
        return recovered
    }

    /**
     * 목적: 폴더의 캐시를 버린다(앱 밖에서 폴더 내용이 바뀌었을 수 있을 때).
     * 입력: dir - 폴더 / 출력: 캐시에서 빠진다 / 리턴: 없음
     */
    @Synchronized
    fun forget(dir: DocumentFile) {
        cache.remove(dir.uri)
    }

    /**
     * 목적: 새 파일 문서를 만든다.
     * 입력: dir - 폴더, name - 파일 이름 / 출력: 빈 파일이 생긴다 / 리턴: DocumentFile
     * 예외: AppError.OutputUnavailable - 만들 수 없을 때
     */
    private fun create(dir: DocumentFile, name: String): DocumentFile =
        guarded("'$name' 만들기") { dir.createFile(MIME, name) } ?: throw AppError.OutputUnavailable("'$name' 을 만들 수 없다")

    /**
     * 목적: 문서에 내용을 쓴다(있던 내용은 버린다).
     * 입력: doc - 문서, bytes - 내용, name - 오류 문구용 이름 / 출력: 내용이 쓰인다 / 리턴: 없음
     * 예외: AppError.OutputUnavailable, AppError.OutputNoSpace
     */
    private fun write(doc: DocumentFile, bytes: ByteArray, name: String) {
        guarded("'$name' 쓰기") {
            resolver.openOutputStream(doc.uri, "wt")?.use { it.write(bytes) }   // "wt" = 쓰기 + 내용 비우기
                ?: throw AppError.OutputUnavailable("'$name' 을 열 수 없다")
        }
    }

    /**
     * 목적: SAF 호출의 예외를 앱 오류로 바꾼다.
     * 입력: what - 무엇을 하다 났는지(오류 문구용), block - 실행할 일 / 출력: 없음 / 리턴: T - block 의 결과
     * 예외: AppError.OutputNoSpace - 저장공간 부족(ENOSPC), AppError.OutputUnavailable - 권한 없음·파일 없음·그 밖의 입출력 오류
     */
    private fun <T> guarded(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: AppError) {
            throw e                                                         // 이미 분류된 오류는 그대로
        } catch (e: SecurityException) {
            throw AppError.OutputUnavailable("$what: 권한이 없다", e)        // 폴더 권한이 사라짐
        } catch (e: IOException) {                                          // FileNotFoundException 포함
            if (e.message?.contains("ENOSPC") == true) throw AppError.OutputNoSpace(what, e)
            throw AppError.OutputUnavailable("$what: ${e.message}", e)
        } catch (e: IllegalArgumentException) {                             // 제공자가 문서를 찾지 못하면 이 예외를 던진다
            throw AppError.OutputUnavailable("$what: ${e.message}", e)
        }
}
