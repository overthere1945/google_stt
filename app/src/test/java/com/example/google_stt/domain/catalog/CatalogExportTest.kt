/*
 * 파일명: domain/catalog/CatalogExportTest.kt (app/src/test)
 * 목적 및 기능:
 * - PC 스크립트 tools/fetch_assets.py 가 읽는 자산 목록(tools/asset_catalog.json)이 Catalog(Kotlin)와 같은지 확인한다.
 *   자산 정의는 Catalog 한 곳에만 쓰고, JSON 은 그 사본이다. 다르면 새 사본을 app/build/asset_catalog.json 에 써 주고 실패한다
 *   → 그 파일을 tools/asset_catalog.json 으로 복사하면 된다(사본을 손으로 고치지 않는다).
 *
 * add-hyungchul-20261106-1717 — [T4-6]
 */
package com.example.google_stt.domain.catalog

import java.io.File                           // 사본 파일
import org.junit.Assert.assertEquals          // 값 비교
import org.junit.Test                         // JUnit 4 시험 표시

/** 목적: 자산 목록 사본 시험. */
class CatalogExportTest {

    /**
     * 목적: Catalog.assets 를 스크립트가 읽을 JSON 글로 만든다(자산마다 한 줄, 순서 = Catalog 순서).
     * 입력: 없음 / 출력: 없음 / 리턴: String - JSON 글(줄 끝 LF)
     */
    private fun export(): String {
        val sb = StringBuilder("{\n")
        sb.append("  \"note\": \"domain/catalog/Catalog.kt 의 사본(CatalogExportTest 가 만든다). 직접 고치지 않는다.\",\n")
        sb.append("  \"assets\": [\n")
        val lines = Catalog.assets.values.map { a ->
            val files = a.files.joinToString(", ") { "[\"${it.pathInArchive}\", \"${it.target}\"]" }   // [원본 안 경로, 저장 경로]
            "    {\"id\": \"${a.id}\", \"kind\": \"${a.kind}\", \"archive\": \"${a.archive}\", \"archiveBytes\": ${a.archiveBytes}, " +
                "\"url\": \"${a.url}\", \"files\": [$files]}"
        }
        sb.append(lines.joinToString(",\n")).append("\n  ]\n}\n")
        return sb.toString()
    }

    /**
     * 목적: tools/asset_catalog.json 이 Catalog 와 같은지 확인한다.
     * 입력: 없음 / 출력: 다르면 app/build/asset_catalog.json 에 새 사본을 쓴다 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun toolsCatalogJson_matchesKotlinCatalog() {
        val expected = export()
        val file = File("../tools/asset_catalog.json")                                  // app/ 기준 상대 경로
        val actual = if (file.isFile) file.readText(Charsets.UTF_8) else ""
        if (actual != expected) File("build/asset_catalog.json").apply { parentFile?.mkdirs(); writeText(expected, Charsets.UTF_8) }   // 새 사본
        assertEquals("tools/asset_catalog.json 이 Catalog 와 다르다 — app/build/asset_catalog.json 을 tools/ 로 복사한다", expected, actual)
    }
}
