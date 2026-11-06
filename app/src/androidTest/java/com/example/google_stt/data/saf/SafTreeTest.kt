/*
 * 파일명: data/saf/SafTreeTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - SafTree 의 쓰기 규칙을 확인한다.
 *     · IT-FILE-01 원자적 쓰기(새 파일·덮어쓰기): 내용 일치, ".tmp" 가 남지 않음
 *     · IT-FILE-02 ".tmp" 복구: "<이름>" 없음 + ".tmp" 있음 → 이름 바꾸기
 * - 같은 시험을 두 가지 폴더에서 돌린다.
 *     · 앱 전용 폴더(DocumentFile.fromFile) — 항상 돈다
 *     · 실제 SAF 폴더 — 앱이 쓰기 권한을 보존한 폴더가 있을 때만 돈다(없으면 건너뜀).
 *       화면에서 출력 폴더를 한 번 고르면 권한이 보존된다. 6단계 문서의 수동 시험 MT-SAF-01(실제 제공자의 이름 바꾸기)을 대신한다.
 *
 * add-hyungchul-20261106-1633 — [T3-1]
 */
package com.example.google_stt.data.saf

import androidx.documentfile.provider.DocumentFile                 // SAF 문서
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context 얻기
import com.example.google_stt.domain.AppError                      // 오류 코드  // add-hyungchul-20261106-1712
import java.io.File                                                // 앱 전용 폴더
import org.junit.Assert.assertArrayEquals                          // 바이트 비교
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertNull                                 // null 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assume.assumeTrue                                 // 조건이 안 되면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: SafTree 계측 시험. */
@RunWith(AndroidJUnit4::class)
class SafTreeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext   // 시험 대상 앱의 Context

    /**
     * 목적: 앱 전용 폴더에 빈 시험 폴더를 만든다.
     * 입력: name - 폴더 이름 / 출력: 폴더가 새로 만들어진다 / 리턴: DocumentFile
     */
    private fun fileRoot(name: String): DocumentFile {
        val dir = File(context.cacheDir, name)                             // 앱 캐시 아래
        dir.deleteRecursively()                                            // 지난 시험의 흔적 지우기
        dir.mkdirs()
        return DocumentFile.fromFile(dir)
    }

    /**
     * 목적: 앱이 쓰기 권한을 보존한 실제 SAF 폴더를 찾는다.
     * 입력: 없음 / 출력: 없음 / 리턴: DocumentFile? - 없으면 null
     */
    private fun persistedSafRoot(): DocumentFile? =
        context.contentResolver.persistedUriPermissions.firstOrNull { it.isWritePermission }
            ?.let { DocumentFile.fromTreeUri(context, it.uri) }?.takeIf { it.canWrite() }

    /**
     * 목적: 주어진 뿌리 폴더에서 쓰기·덮어쓰기·읽기·이름 바꾸기·지우기·tmp 복구를 차례로 확인한다(두 종류 폴더가 같이 쓴다).
     * 입력: root - 시험할 뿌리 폴더 / 출력: 시험 폴더가 만들어졌다 지워진다 / 리턴: 없음(다르면 시험 실패)
     */
    private fun exercise(root: DocumentFile) {
        val warnings = ArrayList<String>()                                 // E-OUT-03 경고 모으기
        val tree = SafTree(context.contentResolver, root) { warnings += it }
        val base = "_saftree_test_" + System.currentTimeMillis()           // 다른 파일과 겹치지 않는 시험 폴더
        try {
            val dir = tree.ensureDirs(listOf(base, "kr", "", "_whisper-base_sherpa-int8_SILERO", "Customer_Service_KR"))
            assertTrue(dir.isDirectory)
            assertEquals("Customer_Service_KR", dir.name)
            assertEquals(dir.uri, tree.ensureDirs(listOf(base, "kr", "_whisper-base_sherpa-int8_SILERO", "Customer_Service_KR")).uri)   // 다시 불러도 같은 폴더

            // [IT-FILE-01] 새 파일
            val first = "안녕하세요. 첫 번째".toByteArray(Charsets.UTF_8)
            tree.writeAtomic(dir, "01.txt", first)
            assertArrayEquals(first, tree.readBytes(dir, "01.txt"))
            assertEquals(setOf("01.txt"), tree.children(dir).keys)          // ".tmp" 가 남지 않는다

            // [IT-FILE-01] 덮어쓰기(더 짧은 내용 — 옛 내용의 꼬리가 남으면 안 된다)
            val second = "둘".toByteArray(Charsets.UTF_8)
            tree.writeAtomic(dir, "01.txt", second)
            assertArrayEquals(second, tree.readBytes(dir, "01.txt"))
            tree.writeAtomic(dir, "02.txt", ByteArray(0))                   // 0바이트 파일(무음 결과)
            assertEquals(0, tree.readBytes(dir, "02.txt")!!.size)
            assertEquals(setOf("01.txt", "02.txt"), tree.children(dir).keys)
            assertNull(tree.readBytes(dir, "없는파일.txt"))

            // 캐시가 아닌 실제 폴더 상태 확인(새 SafTree 로 다시 읽는다)
            val fresh = SafTree(context.contentResolver, root)
            val again = fresh.ensureDirs(listOf(base, "kr", "_whisper-base_sherpa-int8_SILERO", "Customer_Service_KR"))
            assertEquals(setOf("01.txt", "02.txt"), fresh.children(again).keys)
            assertArrayEquals(second, fresh.readBytes(again, "01.txt"))

            // [IT-FILE-02] tmp 복구: "<이름>" 없음 + ".tmp" 있음 → 살린다 / 둘 다 있음 → tmp 를 버린다
            val csvDir = tree.ensureDirs(listOf(base, "kr"))
            val payload = "run_id,file\r\n1,a\r\n".toByteArray(Charsets.UTF_8)
            leaveTmp(csvDir, "a_result.csv.tmp", payload)                   // tmp 만 남긴 상황
            tree.writeAtomic(csvDir, "b_result.csv", "old".toByteArray())
            leaveTmp(csvDir, "b_result.csv.tmp", "half".toByteArray())      // 옛 파일 + 덜 쓰인 tmp
            tree.forget(csvDir)                                             // 앱이 다시 시작한 것처럼 캐시를 버린다
            assertEquals(listOf("a_result.csv"), tree.recoverTmp(csvDir))
            assertArrayEquals(payload, tree.readBytes(csvDir, "a_result.csv"))
            assertArrayEquals("old".toByteArray(), tree.readBytes(csvDir, "b_result.csv"))   // 옛 파일이 그대로
            assertTrue(tree.children(csvDir).keys.none { it.endsWith(".tmp") })

            // add-hyungchul-20261106-1650 : [T3-5a] 덧붙이기(로그 파일용) — 없으면 만들고, 있으면 끝에 잇는다
            tree.append(csvDir, "_log.txt", "첫 줄\n".toByteArray(Charsets.UTF_8))
            tree.append(csvDir, "_log.txt", "둘째 줄\n".toByteArray(Charsets.UTF_8))
            assertEquals("첫 줄\n둘째 줄\n", String(tree.readBytes(csvDir, "_log.txt")!!, Charsets.UTF_8))
            assertEquals("첫 줄\n둘째 줄\n".toByteArray(Charsets.UTF_8).size.toLong(), tree.size(csvDir, "_log.txt"))
            assertEquals(0L, tree.size(csvDir, "없는파일"))
            assertTrue(tree.delete(csvDir, "_log.txt"))

            // 이름 바꾸기: 작업 중 폴더 "_<이름>" → "<이름>", 그리고 같은 이름이 있으면 실패
            assertTrue(tree.rename(csvDir, "_whisper-base_sherpa-int8_SILERO", "whisper-base_sherpa-int8_SILERO"))
            assertFalse(tree.children(csvDir).containsKey("_whisper-base_sherpa-int8_SILERO"))
            val done = tree.find(csvDir, "whisper-base_sherpa-int8_SILERO")!!
            val scen = tree.ensureDir(done, "Customer_Service_KR")          // 바뀐 폴더 아래도 계속 쓸 수 있다
            assertArrayEquals(second, tree.readBytes(scen, "01.txt"))
            assertFalse(tree.rename(csvDir, "a_result.csv", "b_result.csv"))   // 이미 있는 이름
            assertFalse(tree.rename(csvDir, "없는것", "x"))

            // 지우기(폴더는 안의 것까지)
            assertTrue(tree.delete(csvDir, "whisper-base_sherpa-int8_SILERO"))
            assertFalse(tree.delete(csvDir, "whisper-base_sherpa-int8_SILERO"))   // 이미 없다
            assertEquals(setOf("a_result.csv", "b_result.csv"), tree.children(csvDir).keys)

            // add-hyungchul-20261106-1712 : 지운 폴더를 같은 이름으로 다시 만들면 비어 있어야 한다(옛 하위 목록이 캐시에 남으면 안 된다 — 독립 검토 지적 4)
            val reborn = tree.ensureDir(csvDir, "whisper-base_sherpa-int8_SILERO")
            assertTrue("다시 만든 폴더에 옛 목록이 보인다: ${tree.children(reborn).keys}", tree.children(reborn).isEmpty())
            assertNull(tree.readBytes(tree.ensureDir(reborn, "Customer_Service_KR"), "01.txt"))   // 한 단계 더 아래도 비어 있다
            assertTrue(tree.delete(csvDir, "whisper-base_sherpa-int8_SILERO"))

            // add-hyungchul-20261106-1712 : 캐시가 모르는 사이 같은 이름의 폴더가 생겼을 때 — "이름 (1)" 같은 다른 폴더를 조용히 만들어 두지 않는다(지적 3)
            csvDir.createDirectory("dup")!!                                 // SafTree 를 거치지 않고 만든다
            try {
                assertEquals("dup", tree.ensureDir(csvDir, "dup").name)     // 제공자가 있던 폴더를 돌려주면 그대로 쓴다(파일 기반)
            } catch (e: AppError.OutputUnavailable) {
                assertEquals("E-OUT-01", e.code)                            // 다른 이름으로 만들어졌으면 지우고 멈춘다(실제 SAF)
            }
            assertEquals(listOf("dup"), csvDir.listFiles().mapNotNull { it.name }.filter { it.startsWith("dup") })   // 실제 폴더에 "dup" 하나뿐
            csvDir.findFile("dup")!!.delete()
            assertEquals("이름 바꾸기 대체 경로를 탔다(E-OUT-03): $warnings", 0, warnings.size)   // 이 제공자는 이름 바꾸기를 지원해야 한다
        } finally {
            SafTree(context.contentResolver, root).delete(root, base)       // 시험 폴더 정리
        }
    }

    /**
     * 목적: SafTree 를 거치지 않고 임시 파일만 남긴다(쓰는 도중 앱이 죽은 상황을 만든다).
     * 입력: dir - 폴더, name - tmp 파일 이름, bytes - 내용 / 출력: tmp 파일이 생긴다 / 리턴: 없음
     */
    private fun leaveTmp(dir: DocumentFile, name: String, bytes: ByteArray) {
        val doc = dir.createFile(SafTree.MIME, name)!!                     // SafTree 와 같은 MIME
        assertEquals(name, doc.name)                                       // 제공자가 이름을 바꾸지 않는다
        context.contentResolver.openOutputStream(doc.uri, "wt")!!.use { it.write(bytes) }
    }

    /**
     * add-hyungchul-20261106-1712
     * 목적: 이름 바꾸기도, 대체 쓰기도 실패했을 때 다 쓰인 임시 파일이 남는지 확인한다(독립 검토 지적 1 —
     *       대체 경로가 tmp 를 먼저 지우면 옛 파일·tmp·새 파일이 모두 없는 상태로 끝난다).
     *       같은 이름의 "비어 있지 않은 폴더" 가 있으면 파일 기반 폴더에서는 이름 바꾸기와 쓰기가 모두 실패한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun appPrivateFolder_failedFallbackKeepsTmp() {
        val root = fileRoot("saftree_fallback")
        val warnings = ArrayList<String>()
        val tree = SafTree(context.contentResolver, root) { warnings += it }
        tree.children(root)                                                // 빈 폴더 상태를 캐시에 올린다
        root.createDirectory("01.txt")!!.createFile(SafTree.MIME, "x")!!   // 캐시가 모르는, 같은 이름의 비어 있지 않은 폴더
        val bytes = "다 쓰인 내용".toByteArray(Charsets.UTF_8)
        try {
            tree.writeAtomic(root, "01.txt", bytes)
            org.junit.Assert.fail("E-OUT-01 이 나야 한다")
        } catch (e: AppError.OutputUnavailable) {
            assertEquals("E-OUT-01", e.code)
        }
        val left = root.findFile("01.txt.tmp")                             // 실제 폴더에서 다시 찾는다
        assertTrue("다 쓰인 tmp 가 남아야 다음 실행이 살릴 수 있다", left != null && left.isFile)
        assertArrayEquals(bytes, context.contentResolver.openInputStream(left!!.uri)!!.use { it.readBytes() })
        assertEquals(0, warnings.size)                                     // 성공하지 못했으므로 "새로 썼다" 경고는 없다
    }

    /**
     * 목적: [IT-FILE-01][IT-FILE-02] 앱 전용 폴더에서 쓰기 규칙을 확인한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun appPrivateFolder_atomicWriteAndRecovery() {
        exercise(fileRoot("saftree_it"))
    }

    /**
     * 목적: [MT-SAF-01 대체] 실제 SAF 제공자(공용 Documents 등)에서 같은 규칙을 확인한다 — 특히 이름 바꾸기 지원 여부.
     * 입력: 없음(앱이 보존한 쓰기 권한 폴더를 쓴다. 없으면 건너뜀) / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun realSafFolder_atomicWriteAndRecovery() {
        val root = persistedSafRoot()
        assumeTrue("앱이 쓰기 권한을 보존한 SAF 폴더가 없어 건너뜀(화면에서 출력 폴더를 한 번 고르면 된다)", root != null)
        exercise(root!!)
    }
}
