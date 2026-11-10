/*
 * 파일명: data/assets/AssetStoreTest.kt (app/src/test)
 * 목적 및 기능:
 * - 자산 저장소의 규칙을 PC(JVM)에서 확인한다.
 *     · UT-ASSET-01 SHA256("abc" 의 알려진 값), 큰 입력도 같은 값
 *     · UT-ASSET-03 필요 용량 = 압축 크기 × 2 + 1 GB
 *     · 준비 판정(.ready + 모든 파일), 준비 표시 쓰기·읽기, 삭제
 *
 * add-hyungchul-20261106-1656 — [T4-1]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.AppError            // 오류 코드
import com.example.google_stt.domain.catalog.Catalog     // 자산 정의
import java.io.File                                      // 임시 폴더
import org.junit.Assert.assertEquals                     // 값 비교
import org.junit.Assert.assertFalse                      // 거짓 확인
import org.junit.Assert.assertNull                       // null 확인
import org.junit.Assert.assertTrue                       // 조건 확인
import org.junit.Rule                                    // 시험 규칙
import org.junit.Test                                    // JUnit 4 시험 표시
import org.junit.rules.TemporaryFolder                   // 시험마다 새 임시 폴더

/** 목적: AssetStore·Sha256 시험. */
class AssetStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()                          // 시험이 끝나면 지워지는 폴더

    private val silero = Catalog.assets.getValue("silero-vad")                       // 파일 1개, 압축 없음
    private val fsmn = Catalog.assets.getValue("fsmn-vad")                           // 파일 2개, 압축 없음
    private val whisperBase = Catalog.assets.getValue("sherpa-onnx-whisper-base")    // tar.bz2 207,557,382 B

    /**
     * 목적: [UT-ASSET-01] SHA256 — "abc" 의 알려진 값, 빈 입력, 버퍼보다 큰 입력.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun sha256_knownValues() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.of("abc".toByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256.of(ByteArray(0)))
        val big = ByteArray(200_000) { (it % 251).toByte() }                         // 64 KB 버퍼 3번 넘게
        val f = tmp.newFile("big.bin").apply { writeBytes(big) }
        assertEquals(Sha256.of(big), Sha256.of(f))                                   // 파일로 읽어도 같은 값
        assertEquals(64, Sha256.of(f).length)
    }

    /**
     * 목적: [UT-ASSET-03] 필요 용량 — 압축 자산은 크기 × 2, 압축이 없는 자산은 × 1, 거기에 여유 1 GB.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun requiredBytes_archiveTwicePlusMargin() {
        val gb = 1024L * 1024L * 1024L
        assertEquals(207_557_382L * 2 + gb, AssetStore.requiredBytes(listOf(whisperBase)))
        assertEquals(1_289_603L + gb, AssetStore.requiredBytes(listOf(silero)))       // 압축이 없으면 한 번만
        assertEquals(207_557_382L * 2 + 1_289_603L + 514_784L + gb, AssetStore.requiredBytes(listOf(whisperBase, silero, fsmn)))   // 여유는 한 번만 더한다
        assertEquals(0L, AssetStore.requiredBytes(emptyList()))                      // 받을 것이 없으면 0
    }

    /**
     * 목적: 준비 판정 — 파일이 모두 있고 .ready 가 있어야 준비됨. 하나라도 빠지면 아니다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun ready_needsMarkerAndAllFiles() {
        val store = AssetStore(File(tmp.root, "models"))
        assertFalse(store.isReady(fsmn))
        assertNull(store.readyInfo(fsmn.id))
        assertTrue(store.freeBytes() > 0)                                            // 뿌리가 아직 없어도 읽힌다
        store.dir(fsmn.id).mkdirs()
        store.file(fsmn.id, "fsmn_vad_quant.onnx").writeBytes("모델".toByteArray())
        store.file(fsmn.id, "fsmn_vad_am.mvn").writeBytes("mvn".toByteArray())
        assertFalse("표시가 없으면 준비되지 않은 것", store.isReady(fsmn))            // 받다 만 자산을 쓰지 않는다
        val info = ReadyInfo("download", true, mapOf(
            "fsmn_vad_quant.onnx" to Sha256.of("모델".toByteArray()), "fsmn_vad_am.mvn" to Sha256.of("mvn".toByteArray())))
        store.markReady(fsmn, info)
        assertTrue(store.isReady(fsmn))
        assertEquals(info, store.readyInfo(fsmn.id))                                 // 쓴 그대로 읽힌다
        assertEquals(64, info.summary.length)
        assertEquals(info.summary, store.readyInfo(fsmn.id)!!.summary)
        assertFalse(File(store.dir(fsmn.id), ".ready.tmp").exists())                 // 임시 표시가 남지 않는다
        store.file(fsmn.id, "fsmn_vad_am.mvn").delete()
        assertFalse("파일이 빠지면 준비되지 않은 것", store.isReady(fsmn))
    }

    /**
     * 목적: 대표 해시는 파일 순서와 무관하고, 파일 해시가 하나라도 다르면 달라진다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun summary_isOrderIndependent() {
        val a = ReadyInfo("download", true, linkedMapOf("b.onnx" to "2".repeat(64), "a.onnx" to "1".repeat(64)))
        val b = ReadyInfo("import", false, linkedMapOf("a.onnx" to "1".repeat(64), "b.onnx" to "2".repeat(64)))
        assertEquals(a.summary, b.summary)                                           // 순서·출처와 무관
        assertFalse(a.summary == a.copy(fileHashes = mapOf("a.onnx" to "1".repeat(64), "b.onnx" to "3".repeat(64))).summary)
    }

    /**
     * 목적: 삭제 — 자산 폴더와 받다 만 파일(.part)을 함께 지운다. 다른 자산은 그대로다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun delete_removesDirAndPartFiles() {
        val store = AssetStore(File(tmp.root, "models"))
        store.dir(silero.id).mkdirs(); store.file(silero.id, "silero_vad_16k_op15.onnx").writeBytes(ByteArray(3))
        store.partFile(silero.id).apply { parentFile!!.mkdirs(); writeBytes(ByteArray(5)) }
        store.partFile("${silero.id}~0").writeBytes(ByteArray(5))                    // 파일별 part
        store.partFile(fsmn.id).writeBytes(ByteArray(7))                             // 다른 자산의 part
        assertEquals(File(tmp.root, "models/downloads/silero-vad.part"), store.partFile(silero.id))
        assertTrue(store.delete(silero.id))
        assertFalse(store.dir(silero.id).exists())
        assertFalse(store.partFile(silero.id).exists()); assertFalse(store.partFile("${silero.id}~0").exists())
        assertTrue(store.partFile(fsmn.id).exists())                                 // 다른 자산은 건드리지 않는다
        assertFalse(store.delete(silero.id))                                         // 지울 것이 없다
    }

    /**
     * add-hyungchul-20261106-1705 : [T4-4]
     * 목적: verify — 해시가 맞으면 준비 표시를 쓰고(검증됨), 기대 해시가 없으면 "미검증" 으로 준비, 다르면 E-AS-06, 파일이 없으면 E-AS-07.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun verify_checksHashesAndMarksReady() {
        val store = AssetStore(File(tmp.root, "models"))
        store.dir(fsmn.id).mkdirs()
        store.file(fsmn.id, "fsmn_vad_quant.onnx").writeBytes("모델".toByteArray())
        try { store.verify(fsmn, "download"); org.junit.Assert.fail("E-AS-07 이 나야 한다") } catch (e: AppError.AssetExtract) { assertEquals("E-AS-07", e.code) }
        store.file(fsmn.id, "fsmn_vad_am.mvn").writeBytes("mvn".toByteArray())
        val unverified = store.verify(fsmn, "import")                                // 기대 해시가 없는 정의
        assertFalse(unverified.verified); assertEquals("import", unverified.source)
        assertTrue(store.isReady(fsmn))                                              // 미검증이어도 쓸 수는 있다(run_meta 에 기록)
        val good = mapOf("fsmn_vad_quant.onnx" to Sha256.of("모델".toByteArray()), "fsmn_vad_am.mvn" to Sha256.of("mvn".toByteArray()))
        val ok = store.verify(fsmn.withHashes(null, good), "download")
        assertTrue(ok.verified); assertEquals(good, ok.fileHashes)
        store.file(fsmn.id, ".ready").delete()
        try {
            store.verify(fsmn.withHashes(null, good + ("fsmn_vad_am.mvn" to "0".repeat(64))), "download")
            org.junit.Assert.fail("E-AS-06 이 나야 한다")
        } catch (e: AppError.AssetHashMismatch) {
            assertEquals("E-AS-06", e.code)
            assertTrue(e.message!!.contains("fsmn_vad_am.mvn"))                      // 어느 파일인지, 기대·실제 해시를 알려 준다
        }
        assertFalse("해시가 다르면 준비 표시를 쓰지 않는다", store.isReady(fsmn))
    }
}
