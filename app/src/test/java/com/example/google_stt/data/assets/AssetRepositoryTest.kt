/*
 * 파일명: data/assets/AssetRepositoryTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-ASSET-05] 자산 상태 전이를 PC(JVM)에서 확인한다. 네트워크 대신 가짜 받기 함수를 쓴다.
 *     · MISSING → DOWNLOADING → VERIFYING(압축 파일) → EXTRACTING → VERIFYING → READY
 *     · 해시 불일치 → 지우고 1회 다시 → FAILED(HASH) / 깨진 압축 파일 → 다시 받아 READY
 *     · TLS 오류는 다시 받지 않고 .part 를 남긴다. 한 자산이 실패해도 나머지는 받는다
 *     · 작은 것부터 받기, 공간 부족이면 시작하지 않음(E-AS-08), 압축 없는 자산, 취소, 다 받은 .part 재사용, 삭제
 *
 * add-hyungchul-20261106-1714 — [T4-5]
 */
package com.example.google_stt.data.assets

import com.example.google_stt.domain.AppError                      // 오류 코드
import com.example.google_stt.domain.catalog.ArchiveType           // 압축 형식
import com.example.google_stt.domain.catalog.AssetFile             // 자산 안 파일
import com.example.google_stt.domain.catalog.AssetKind             // 자산 종류
import com.example.google_stt.domain.catalog.AssetSpec             // 자산 정의
import com.example.google_stt.domain.catalog.Catalog               // 실제 자산 정의(fsmn-vad)
import java.io.ByteArrayOutputStream                               // zip 을 메모리에 만든다
import java.io.File                                                // 임시 폴더
import java.util.zip.ZipEntry                                      // zip 항목
import java.util.zip.ZipOutputStream                               // zip 만들기
import kotlinx.coroutines.ExperimentalCoroutinesApi                // 시험용 디스패처
import kotlinx.coroutines.awaitCancellation                        // 취소될 때까지 멈춤
import kotlinx.coroutines.cancelAndJoin                            // 취소하고 기다리기
import kotlinx.coroutines.flow.toList                              // 상태 모으기
import kotlinx.coroutines.launch                                   // 코루틴 시작
import kotlinx.coroutines.test.StandardTestDispatcher              // 시험 스케줄러를 따르는 디스패처
import kotlinx.coroutines.test.TestScope                           // 시험 범위
import kotlinx.coroutines.test.UnconfinedTestDispatcher            // 값이 바뀌는 즉시 받는 수집기용
import kotlinx.coroutines.test.runCurrent                          // 밀린 일 돌리기
import kotlinx.coroutines.test.runTest                             // 코루틴 시험
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Rule                                              // 시험 규칙
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.rules.TemporaryFolder                             // 시험마다 새 임시 폴더

/** 목적: AssetRepository 시험. */
@OptIn(ExperimentalCoroutinesApi::class)
class AssetRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()                                    // 시험이 끝나면 지워지는 폴더

    private val content = mapOf("pkg/model.onnx" to "모델 내용", "pkg/tokens.txt" to "토큰 내용", "pkg/README.md" to "필요 없는 파일")
    private val zipBytes = zip(content)                            // 정상 압축 파일
    private val calls = ArrayList<String>()                        // 가짜 받기 함수가 불린 주소들

    /** 목적: 메모리에 zip 을 만든다. 입력: items - 경로 → 내용 / 출력: 없음 / 리턴: ByteArray */
    private fun zip(items: Map<String, String>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> for ((n, c) in items) { z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
        return bos.toByteArray()
    }

    /**
     * 목적: 시험용 zip 자산 정의를 만든다(파일 2개만 남긴다).
     * 입력: id - 자산 ID, bytes - 카탈로그에 적을 압축 크기, hashes - 해시를 넣을지, archiveHash - 압축 파일 해시
     * 출력: 없음 / 리턴: AssetSpec
     */
    private fun zipSpec(id: String = "pkg", bytes: Long? = zipBytes.size.toLong(), hashes: Boolean = true, archiveHash: String? = Sha256.of(zipBytes)) =
        AssetSpec(
            id = id, kind = AssetKind.STT_MODEL, url = "https://example.invalid/$id.zip", archive = ArchiveType.ZIP,
            archiveBytes = bytes, archiveSha256 = archiveHash, license = "test",
            files = listOf("model.onnx", "tokens.txt").map { AssetFile("pkg/$it", it, if (hashes) Sha256.of(content.getValue("pkg/$it").toByteArray()) else null) },
        )

    /**
     * 목적: 가짜 받기 함수를 만든다 — 부를 때마다 payloads 의 다음 내용을 part 에 쓰고(마지막 내용은 반복) 진행을 두 번 알린다.
     * 입력: payloads - 차례로 줄 내용 / 출력: calls 에 주소가 쌓인다 / 리턴: 받기 함수
     */
    private fun serving(vararg payloads: ByteArray): suspend (String, File, (Long, Long?) -> Unit) -> Long = { url, part, onProgress ->
        val body = payloads[minOf(calls.size, payloads.size - 1)]
        calls += url
        part.parentFile!!.mkdirs()
        onProgress(0L, body.size.toLong())
        part.writeBytes(body)
        onProgress(body.size.toLong(), body.size.toLong())
        body.size.toLong()
    }

    /**
     * 목적: 저장소를 만든다(파일 일은 시험 스케줄러에서 돌려 상태 변화를 빠짐없이 본다).
     * 입력: scope - 시험 범위, specs - 자산 정의들, fetch - 받기 함수, free - 여유 공간 / 출력: 없음 / 리턴: (AssetRepository, AssetStore)
     */
    private fun repo(
        scope: TestScope, specs: List<AssetSpec>, fetch: suspend (String, File, (Long, Long?) -> Unit) -> Long, free: Long = Long.MAX_VALUE,
    ): Pair<AssetRepository, AssetStore> {
        val store = AssetStore(File(tmp.root, "models"))
        return AssetRepository(store, specs.associateBy { it.id }, fetch, StandardTestDispatcher(scope.testScheduler), { free }) to store
    }

    /**
     * 목적: 자산 하나의 상태 변화를 모은다(같은 종류가 이어지면 하나로).
     * 입력: r - 저장소, id - 자산 ID(수신 객체 = 시험 범위) / 출력: 없음 / 리턴: () -> List<String> - 지금까지 본 상태 이름들
     */
    private fun TestScope.watch(r: AssetRepository, id: String): () -> List<String> {
        val seen = ArrayList<AssetState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.state(id).toList(seen) }   // 값이 바뀌는 자리에서 바로 받는다
        return { seen.map { it::class.simpleName!! }.fold(ArrayList<String>()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc } }
    }

    /**
     * 목적: [UT-ASSET-05] 정상 흐름 — 상태 순서, 풀린 파일, 준비 표시, 압축 파일 정리.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun ensureReady_walksStatesToReady() = runTest {
        val (r, store) = repo(this, listOf(zipSpec()), serving(zipBytes))
        val seen = watch(r, "pkg")
        assertEquals(zipBytes.size * 2L + AssetStore.MARGIN_BYTES, r.requiredBytes(listOf("pkg")))
        assertTrue(r.ensureReady(listOf("pkg")).isEmpty())
        assertEquals(listOf("Missing", "Downloading", "Verifying", "Extracting", "Verifying", "Ready"), seen())
        assertEquals(AssetState.Ready(verified = true), r.state("pkg").value)
        assertEquals("모델 내용", store.file("pkg", "model.onnx").readText())
        assertFalse(store.file("pkg", "README.md").exists())                             // 필요 없는 파일은 풀지 않는다
        assertFalse(store.partFile("pkg").exists())                                      // 압축 파일은 지운다
        assertEquals("download", store.readyInfo("pkg")!!.source)
        assertTrue(r.ensureReady(listOf("pkg")).isEmpty()); assertEquals(1, calls.size)  // 이미 있으면 다시 받지 않는다
        assertEquals(0L, r.requiredBytes(listOf("pkg")))
        assertTrue(r.delete("pkg")); assertEquals(AssetState.Missing, r.state("pkg").value)   // 삭제하면 MISSING
        assertFalse(store.dir("pkg").exists())
    }

    /**
     * 목적: [UT-ASSET-05] 해시 불일치 → 지우고 1회 다시 받기 → FAILED(HASH). 깨진 압축 파일은 다시 받아 READY.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun damaged_retriesOnceThenFails() = runTest {
        val bad = zipSpec().let { s -> s.copy(files = s.files.map { it.copy(sha256 = "0".repeat(64)) }) }   // 풀린 파일의 기대 해시가 틀린 정의
        val (r, store) = repo(this, listOf(bad, zipSpec(id = "pkg2", archiveHash = null)), serving(zipBytes))
        val failed = r.ensureReady(listOf("pkg"))
        assertEquals(FailReason.HASH, failed.getValue("pkg").reason); assertEquals("E-AS-06", failed.getValue("pkg").error.code)
        assertEquals(2, calls.size)                                                      // 처음 1번 + 다시 1번
        assertEquals(failed.getValue("pkg"), r.state("pkg").value)
        assertFalse(store.dir("pkg").exists()); assertFalse(store.partFile("pkg").exists())   // 손상된 것은 남기지 않는다
        calls.clear()
        val (r2, _) = repo(this, listOf(zipSpec(id = "pkg2", archiveHash = null)), serving("zip 이 아닌 내용".toByteArray(), zipBytes))
        val seen = watch(r2, "pkg2")
        assertTrue(r2.ensureReady(listOf("pkg2")).isEmpty())                             // 첫 번째는 해제 실패(E-AS-07) → 다시 받아 성공
        assertEquals(2, calls.size)
        assertEquals(listOf("Missing", "Downloading", "Extracting", "Downloading", "Extracting", "Verifying", "Ready"), seen())
        val (r3, _) = repo(this, listOf(zipSpec(id = "pkg3", archiveHash = "f".repeat(64))), serving(zipBytes))
        assertEquals(FailReason.HASH, r3.ensureReady(listOf("pkg3")).getValue("pkg3").reason)   // 압축 파일 해시가 다르면 풀지 않는다
    }

    /**
     * 목적: TLS 오류는 다시 받지 않고 받던 파일을 남긴다. 한 자산이 실패해도 나머지는 받는다. 받는 순서는 작은 것부터.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun networkFailure_keepsPartAndContinues() = runTest {
        val ok = serving(zipBytes)
        val fetch: suspend (String, File, (Long, Long?) -> Unit) -> Long = { url, part, p ->
            if (url.contains("big")) {
                calls += url
                part.parentFile!!.mkdirs(); part.writeBytes(ByteArray(10))               // 조금 받다가
                throw AppError.AssetTls("PKIX path building failed")                    // 인증서 오류
            } else ok(url, part, p)
        }
        val specs = listOf(zipSpec(id = "big", bytes = 9_000L), zipSpec(id = "small", bytes = 100L, archiveHash = null), zipSpec(id = "mid", bytes = null, archiveHash = null))
        val (r, store) = repo(this, specs, fetch)
        assertEquals(listOf("small", "big", "mid"), r.missing(listOf("big", "mid", "small", "big")).map { it.id })   // 크기를 모르면 맨 뒤
        val failed = r.ensureReady(listOf("big", "mid", "small"))
        assertEquals(setOf("big"), failed.keys); assertEquals(FailReason.TLS, failed.getValue("big").reason)
        assertEquals(listOf("small", "big", "mid"), calls.map { it.substringAfterLast('/').removeSuffix(".zip") })   // 받은 순서, 다시 시도 없음
        assertEquals(10L, store.partFile("big").length())                                // 받던 파일은 남는다(이어받기)
        assertTrue(r.state("small").value is AssetState.Ready); assertTrue(r.state("mid").value is AssetState.Ready)
        r.refresh(); assertEquals(AssetState.Missing, r.state("big").value)              // 다시 읽으면 실패 표시가 지워진다
    }

    /**
     * 목적: [E-AS-08] 공간이 모자라면 받기를 시작하지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun noSpace_doesNotStart() = runTest {
        val (r, _) = repo(this, listOf(zipSpec()), serving(zipBytes), free = AssetStore.MARGIN_BYTES)   // 여유 1 GB 뿐
        val f = r.ensureReady(listOf("pkg")).getValue("pkg")
        assertEquals(FailReason.SPACE, f.reason)
        val err = f.error as AppError.AssetNoSpace
        assertEquals(zipBytes.size * 2L + AssetStore.MARGIN_BYTES, err.requiredBytes); assertEquals(AssetStore.MARGIN_BYTES, err.freeBytes)
        assertEquals(0, calls.size)
    }

    /**
     * 목적: 압축이 없는 자산은 파일마다 "주소/경로" 로 받아 저장 이름으로 놓는다(해시가 없으면 "미검증" 으로 준비).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun plainFiles_fetchedOneByOne() = runTest {
        val fsmn = Catalog.assets.getValue("fsmn-vad")                                   // model_quant.onnx → fsmn_vad_quant.onnx, am.mvn → fsmn_vad_am.mvn
        val (r, store) = repo(this, listOf(fsmn), serving("가".toByteArray(), "나다".toByteArray()))
        val seen = watch(r, fsmn.id)
        assertTrue(r.ensureReady(listOf(fsmn.id)).isEmpty())
        assertEquals(listOf("${fsmn.url}/model_quant.onnx", "${fsmn.url}/am.mvn"), calls)
        assertEquals("가", store.file(fsmn.id, "fsmn_vad_quant.onnx").readText()); assertEquals("나다", store.file(fsmn.id, "fsmn_vad_am.mvn").readText())
        assertEquals(AssetState.Ready(verified = false), r.state(fsmn.id).value)
        assertEquals(listOf("Missing", "Downloading", "Verifying", "Ready"), seen())
        assertTrue(File(store.root, "downloads").listFiles()!!.isEmpty())                // 임시 파일이 남지 않는다
    }

    /**
     * 목적: 취소되면 상태가 MISSING 으로 돌아가고 받던 파일은 남는다. 끝까지 받아 둔 압축 파일은 다시 받지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun cancel_thenResumeFromCompletePart() = runTest {
        val hang: suspend (String, File, (Long, Long?) -> Unit) -> Long = { url, part, p ->
            calls += url; part.parentFile!!.mkdirs(); part.writeBytes(zipBytes); p(5L, null); awaitCancellation()   // 다 받고 멈춘 상황
        }
        val (r, store) = repo(this, listOf(zipSpec()), hang)
        val job = launch { r.ensureReady(listOf("pkg")) }
        runCurrent()
        assertEquals(AssetState.Downloading(5L, zipBytes.size.toLong()), r.state("pkg").value)   // 전체 크기는 카탈로그 값을 쓴다
        job.cancelAndJoin()
        assertEquals(AssetState.Missing, r.state("pkg").value)
        assertTrue(store.partFile("pkg").isFile)
        calls.clear()
        val (r2, _) = repo(this, listOf(zipSpec()), serving(ByteArray(0)))               // 불리면 빈 파일을 줘서 실패하게 될 받기 함수
        assertTrue(r2.ensureReady(listOf("pkg")).isEmpty())                              // 크기가 맞는 .part 를 그대로 쓴다
        assertEquals(0, calls.size)
    }
}
