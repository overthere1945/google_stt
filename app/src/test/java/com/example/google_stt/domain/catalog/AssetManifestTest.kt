/*
 * 파일명: domain/catalog/AssetManifestTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-CAT-04] 매니페스트 합치기: 해시가 있으면 반영, 없으면 null("미검증"), 깨진 JSON 은 E-AS-09.
 * - 앱에 넣는 실제 매니페스트(app/src/main/assets/asset_manifest.json)가 읽히고 카탈로그와 맞는지 확인한다.
 *
 * add-hyungchul-20261106-0037 — [T2-2b]
 */
package com.example.google_stt.domain.catalog

import com.example.google_stt.domain.AppError          // E-AS-09
import java.io.File                                    // 실제 매니페스트 파일 읽기
import org.junit.Assert.assertEquals                   // 값 비교
import org.junit.Assert.assertFalse                    // 거짓 확인
import org.junit.Assert.assertNull                     // null 확인
import org.junit.Assert.assertTrue                     // 조건 확인
import org.junit.Assert.fail                           // 예외가 안 났을 때 실패 처리
import org.junit.Test                                  // JUnit 4 시험 표시

/** 목적: 자산 매니페스트 시험. */
class AssetManifestTest {

    private val hashA = "a".repeat(64)   // 시험용 해시(형식만 맞는 값)
    private val hashB = "b".repeat(64)   // 시험용 해시 2

    /**
     * 목적: [UT-CAT-04] 해시가 있는 자산은 반영되고, 없는 자산·파일은 null 로 남는다. 모르는 ID 는 무시한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun manifest_mergesKnownHashes() {
        val json = """{"version":1,"assets":{
            "silero-vad":{"files":{"silero_vad_16k_op15.onnx":"$hashA"}},
            "fsmn-vad":{"archiveSha256":"$hashB","files":{"fsmn_vad_quant.onnx":"$hashA"}},
            "no-such-asset":{"files":{"x":"$hashA"}}}}"""
        val merged = AssetManifest.merge(Catalog.assets, json)
        assertEquals(Catalog.assets.keys, merged.keys)                                      // 자산 수는 그대로
        assertTrue(merged.getValue("silero-vad").verified)                                  // 파일 1개 모두 해시 있음
        val fsmn = merged.getValue("fsmn-vad")
        assertEquals(hashB, fsmn.archiveSha256)                                             // 압축 파일 해시 반영
        assertEquals(hashA, fsmn.files.first { it.target == "fsmn_vad_quant.onnx" }.sha256) // 있는 파일
        assertNull(fsmn.files.first { it.target == "fsmn_vad_am.mvn" }.sha256)              // 없는 파일은 null
        assertFalse(fsmn.verified)                                                          // 일부만 있으면 미검증
        assertFalse(merged.getValue("firered-vad").verified)                                // 매니페스트에 없는 자산
        assertFalse(Catalog.assets.getValue("silero-vad").verified)                         // 원본 표는 바뀌지 않는다
    }

    /**
     * 목적: [UT-CAT-04] 깨진 JSON, "assets" 없음, 해시 형식 오류는 모두 E-AS-09 예외다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun manifest_brokenInputThrowsEAs09() {
        val broken = listOf(
            "{ this is not json",                                                           // 깨진 JSON
            """{"version":1}""",                                                            // "assets" 없음
            """{"assets":{"silero-vad":{"files":{"silero_vad_16k_op15.onnx":"1234"}}}}""",  // 해시가 64자가 아님
            """{"assets":{"silero-vad":{"archiveSha256":"${"A".repeat(64)}"}}}""",          // 대문자 해시
        )
        for (json in broken) {
            try {
                AssetManifest.parse(json)
                fail("예외가 나야 한다: $json")
            } catch (e: AppError.ManifestCorrupt) {
                assertEquals("E-AS-09", e.code)                                             // 오류 코드
                assertFalse(e.retryable)                                                    // 다시 시도해도 같다
            }
        }
    }

    /**
     * 목적: 앱에 넣는 실제 매니페스트가 읽히고, 적힌 자산 ID·파일이 모두 카탈로그에 있다(오타 방지).
     * 입력: 없음(Gradle 단위 시험의 작업 폴더는 모듈 폴더 app/ 이다) / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun bundledManifest_matchesCatalog() {
        val file = File("src/main/assets/asset_manifest.json")                              // app/ 기준 상대 경로
        assertTrue("매니페스트 파일이 없다: ${file.absolutePath}", file.isFile)
        val entries = AssetManifest.parse(file.readText(Charsets.UTF_8))
        assertTrue(entries.isNotEmpty())                                                    // 비어 있지 않다
        for ((id, entry) in entries) {
            val asset = Catalog.assets[id] ?: throw AssertionError("카탈로그에 없는 자산 ID: $id")
            val targets = asset.files.map { it.target }.toSet()                             // 카탈로그의 저장 경로
            assertTrue("$id: 카탈로그에 없는 파일 ${entry.files.keys - targets}", targets.containsAll(entry.files.keys))
        }
        val merged = AssetManifest.merge(Catalog.assets, file.readText(Charsets.UTF_8))
        assertTrue(merged.getValue("sherpa-onnx-whisper-base").verified)                    // 기준 모델은 해시가 모두 있다
        assertTrue(listOf("silero-vad", "pyannote-seg3", "fsmn-vad", "firered-vad").all { merged.getValue(it).verified })
    }
}
