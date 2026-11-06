/*
 * 파일명: data/assets/AssetImporterTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - 폴더에서 자산 가져오기(D13 의 B 방법)를 확인한다: 낱개 파일(하위 폴더 포함) 복사 → SHA256 확인 → 준비 표시.
 *   파일이 빠졌으면 E-AS-07, 해시가 다르면 E-AS-06 이고 반쯤 가져온 것은 남기지 않는다. 원본 폴더는 그대로다.
 * - 원본 폴더는 앱 전용 폴더에 만들어 DocumentFile.fromFile 로 읽는다. 실제 Documents 폴더에서 가져오기는 M2 검증에서 확인한다.
 *
 * add-hyungchul-20261106-1705 — [T4-4]
 */
package com.example.google_stt.data.assets

import androidx.documentfile.provider.DocumentFile                 // SAF 문서
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context 얻기
import com.example.google_stt.domain.AppError                      // 오류 코드
import com.example.google_stt.domain.catalog.Catalog               // 자산 정의
import java.io.File                                                // 시험 폴더
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import org.junit.After                                             // 시험 뒤 정리
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assert.fail                                       // 예외가 안 났을 때 실패
import org.junit.Before                                            // 시험 전 준비
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: AssetImporter 계측 시험. */
@RunWith(AndroidJUnit4::class)
class AssetImporterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext   // 시험 대상 앱의 Context
    private val base = File(context.cacheDir, "import_it")                             // 시험 폴더
    private val src = File(base, "stt_assets")                                         // PC 에서 옮겨 온 폴더 역할
    private val store = AssetStore(File(base, "models"))                               // 앱 전용 저장소 역할
    private val qwen = Catalog.assets.getValue("sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25")   // 하위 폴더(tokenizer/)가 있는 자산
    private val silero = Catalog.assets.getValue("silero-vad")

    /** 목적: 시험 폴더를 비운다. 입력: 없음 / 출력: 폴더가 새로 만들어진다 / 리턴: 없음 */
    @Before
    fun setUp() { base.deleteRecursively(); src.mkdirs() }

    /** 목적: 시험 폴더를 지운다. 입력: 없음 / 출력: 폴더가 지워진다 / 리턴: 없음 */
    @After
    fun tearDown() { base.deleteRecursively() }

    /**
     * 목적: 원본 폴더에 자산의 낱개 파일을 만든다(내용 = "<경로> 내용").
     * 입력: id - 자산 ID, targets - 만들 파일의 상대 경로들 / 출력: 파일이 생긴다 / 리턴: Map<String, String> - 경로 → SHA256
     */
    private fun put(id: String, targets: List<String>): Map<String, String> = targets.associateWith { t ->
        val bytes = "$t 내용".toByteArray(Charsets.UTF_8)
        File(src, "$id/$t").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        Sha256.of(bytes)
    }

    /**
     * 목적: 낱개 파일(하위 폴더 포함)을 가져와 해시를 확인하고 준비 표시를 쓴다. 원본은 그대로 남는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun import_copiesVerifiesAndMarksReady() = runBlocking {
        val hashes = put(qwen.id, qwen.files.map { it.target })                        // tokenizer/merges.txt 등 6개
        put(silero.id, listOf("silero_vad_16k_op15.onnx")); File(src, "notes.txt").writeText("자산이 아닌 파일")
        val importer = AssetImporter(context.contentResolver, store)
        val found = importer.find(DocumentFile.fromFile(src), Catalog.assets.values)
        assertEquals(setOf(qwen.id, silero.id), found.keys)                            // 자산 ID 와 이름이 같은 폴더만
        var copied = 0L
        val info = importer.import(qwen.withHashes(null, hashes), found.getValue(qwen.id)) { copied += it }
        assertTrue(info.verified); assertEquals("import", info.source); assertEquals(hashes, info.fileHashes)
        assertTrue(store.isReady(qwen))
        assertEquals("tokenizer/vocab.json 내용", store.file(qwen.id, "tokenizer/vocab.json").readText())   // 하위 폴더 구조 유지
        assertEquals(qwen.files.sumOf { store.file(qwen.id, it.target).length() }, copied)                  // 진행 알림 = 복사한 바이트
        assertTrue(File(src, "${qwen.id}/encoder.int8.onnx").isFile)                   // 원본은 그대로
        assertTrue(store.dir(qwen.id).walkTopDown().none { it.name.endsWith(".part") }) // 임시 파일이 남지 않는다
        val un = importer.import(silero, found.getValue(silero.id))                    // 기대 해시가 없는 자산
        assertFalse(un.verified); assertTrue(store.isReady(silero))                    // "미검증" 으로 준비
    }

    /**
     * 목적: 파일이 빠졌으면 E-AS-07, 해시가 다르면 E-AS-06 이고, 반쯤 가져온 자산은 남지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun import_missingOrMismatch_cleansUp() = runBlocking {
        val importer = AssetImporter(context.contentResolver, store)
        val hashes = put(qwen.id, qwen.files.map { it.target }.filter { it != "tokenizer/vocab.json" })   // 파일 하나를 빼고 옮김
        val dir = DocumentFile.fromFile(File(src, qwen.id))
        try { importer.import(qwen, dir); fail("E-AS-07 이 나야 한다") } catch (e: AppError.AssetExtract) {
            assertEquals("E-AS-07", e.code); assertTrue(e.message!!.contains("tokenizer/vocab.json"))
        }
        assertFalse(store.dir(qwen.id).exists())                                       // 복사하던 것을 지웠다
        val all = hashes + put(qwen.id, listOf("tokenizer/vocab.json"))
        val wrong = all + ("decoder.int8.onnx" to "0".repeat(64))                      // 매니페스트와 다른 파일
        try { importer.import(qwen.withHashes(null, wrong), dir); fail("E-AS-06 이 나야 한다") } catch (e: AppError.AssetHashMismatch) {
            assertEquals("E-AS-06", e.code); assertTrue(e.message!!.contains("decoder.int8.onnx"))
        }
        assertFalse(store.isReady(qwen)); assertFalse(store.dir(qwen.id).exists())
        assertTrue(importer.import(qwen.withHashes(null, all), dir).verified)          // 제대로 된 폴더는 다시 가져올 수 있다
    }
}
