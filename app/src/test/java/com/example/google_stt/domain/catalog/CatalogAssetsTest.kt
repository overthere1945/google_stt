/*
 * 파일명: domain/catalog/CatalogAssetsTest.kt (app/src/test)
 * 목적 및 기능:
 * - 자산 표(Catalog.assets)가 모델 정의와 어긋나지 않는지 확인한다(모델이 쓰는 파일을 자산이 빠짐없이 내려받는지).
 * - 주소·경로 규칙과 VAD 엔진 → 자산 ID 대응을 확인한다.
 *
 * add-hyungchul-20261106-0037 — [T2-2a]
 */
package com.example.google_stt.domain.catalog

import com.example.google_stt.domain.vad.VadEngine     // VAD 엔진 → 자산 ID
import org.junit.Assert.assertEquals                   // 값 비교
import org.junit.Assert.assertFalse                    // 거짓 확인
import org.junit.Assert.assertNull                     // null 확인
import org.junit.Assert.assertTrue                     // 조건 확인
import org.junit.Test                                  // JUnit 4 시험 표시

/** 목적: 자산 표 시험. */
class CatalogAssetsTest {

    /**
     * 목적: 자산은 22개(STT 18 + VAD 4)이고, 자산이 있는 모든 모델의 파일을 그 자산이 내려받는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun assets_coverEveryModelFile() {
        assertEquals(22, Catalog.assets.size)                                               // 전체 자산 수
        assertEquals(18, Catalog.assets.values.count { it.kind == AssetKind.STT_MODEL })    // sherpa 16 + Vosk 2
        assertEquals(4, Catalog.assets.values.count { it.kind == AssetKind.VAD_MODEL })     // Silero, pyannote, FSMN, FireRed
        for (m in Catalog.models.filter { it.assetId != null }) {
            val asset = Catalog.assets.getValue(m.assetId!!)                                // 모델의 자산
            val targets = asset.files.map { it.target }                                     // 저장되는 경로들
            for (f in m.files.values) {                                                     // 모델이 쓰는 파일(또는 폴더)
                assertTrue("${m.engine}: $f 를 받지 않는다", targets.any { it == f || it.startsWith("$f/") })
            }
            assertEquals(m.engine, m.license, asset.license)                                // 라이선스 표기 일치
        }
    }

    /**
     * 목적: 주소·경로 규칙 — sherpa 는 "<ID>.tar.bz2", Vosk 는 "<ID>.zip", 압축 안 경로는 "<ID>/…", 위험한 경로(..)가 없다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun urlsAndPaths_followRules() {
        for (a in Catalog.assets.values) {
            assertTrue(a.id, a.url.startsWith("https://"))                                  // HTTPS 만
            assertTrue(a.id, a.archiveBytes != null && a.archiveBytes!! > 0)                // 받을 크기를 안다
            assertEquals(a.id, a.files.size, a.files.map { it.target }.toSet().size)        // 저장 경로 중복 없음
            assertTrue(a.id, a.files.none { ".." in it.pathInArchive || ".." in it.target || it.target.startsWith("/") })
            assertFalse(a.id, a.verified)                                                   // 해시는 매니페스트를 합쳐야 생긴다
            if (a.kind == AssetKind.STT_MODEL) {
                val ext = if (a.archive == ArchiveType.ZIP) ".zip" else ".tar.bz2"          // Vosk 는 zip
                assertTrue(a.id, a.url.endsWith("/${a.id}$ext"))                            // 압축 파일 이름 = 자산 ID
                assertTrue(a.id, a.files.all { it.pathInArchive == "${a.id}/${it.target}" }) // 압축 안 경로 규칙
            }
        }
        assertEquals(14, Catalog.assets.getValue("vosk-model-small-ko-0.22").files.size)    // 공통 13 + graph/phones.txt
        assertEquals(13, Catalog.assets.getValue("vosk-model-small-en-us-0.15").files.size) // 공통 13
    }

    /**
     * 목적: VAD 엔진 → 자산 ID 대응. TEN 과 NOVAD 는 내려받을 자산이 없다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun vadAssetIds_exist() {
        for (e in listOf(VadEngine.SILERO, VadEngine.PYANNOTE, VadEngine.FSMN, VadEngine.FIRERED)) {
            assertTrue(e.name, Catalog.assets.containsKey(Catalog.vadAssetId(e)))           // 자산 표에 있다
        }
        assertNull(Catalog.vadAssetId(VadEngine.TEN))                                       // APK 에 든 네이티브 라이브러리
        assertNull(Catalog.vadAssetId(VadEngine.NONE))                                      // VAD 를 쓰지 않음
    }
}
