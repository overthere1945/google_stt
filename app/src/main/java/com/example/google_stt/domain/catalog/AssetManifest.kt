/*
 * 파일명: domain/catalog/AssetManifest.kt
 * 목적 및 기능:
 * - 자산 해시 목록(assets/asset_manifest.json)을 읽어 AssetSpec 에 합친다(4단계 2-1, 3단계 3-4).
 *   해시를 코드가 아닌 파일에 두므로 PC 스크립트(T4-6)가 해시만 바꿀 수 있다.
 * - 형식: { "version": 1, "assets": { "<자산 ID>": { "archiveSha256": "<64자>", "files": { "<저장 경로>": "<64자>" } } } }
 *   archiveSha256 과 files 는 없어도 된다. 카탈로그에 없는 자산 ID·파일은 무시한다.
 * - 깨진 JSON·잘못된 해시 형식은 AppError.ManifestCorrupt(E-AS-09)로 알린다.
 * - JSON 은 Android 에 들어 있는 org.json 으로 읽는다(JVM 단위 시험에는 같은 API 의 org.json 을 시험 의존성으로 넣었다).
 *
 * add-hyungchul-20261106-0037 — [T2-2b]
 */
package com.example.google_stt.domain.catalog

import com.example.google_stt.domain.AppError   // E-AS-09
import org.json.JSONException                   // JSON 형식 오류
import org.json.JSONObject                      // JSON 객체 읽기

/** 목적: 자산 해시 목록 읽기·합치기. */
object AssetManifest {

    /** 목적: 자산 1개의 해시 묶음. 필드: archiveSha256 - 압축 파일 해시(없으면 null), files - 저장 경로 → 해시 */
    data class Entry(val archiveSha256: String?, val files: Map<String, String>)

    private val SHA256 = Regex("^[0-9a-f]{64}$")   // sha256 16진수 소문자 64자

    /**
     * 목적: 매니페스트 글을 읽는다.
     * 입력: json - asset_manifest.json 내용
     * 출력: 없음
     * 리턴: Map<String, Entry> - 자산 ID → 해시 묶음
     * 예외: AppError.ManifestCorrupt - JSON 이 깨졌거나 "assets" 가 없거나 해시 형식이 틀릴 때
     */
    fun parse(json: String): Map<String, Entry> {
        try {
            val assets = JSONObject(json).getJSONObject("assets")                       // "assets" 객체(없으면 JSONException)
            val out = LinkedHashMap<String, Entry>()                                    // 파일에 적힌 순서 유지
            for (id in assets.keys()) {
                val a = assets.getJSONObject(id)                                        // 자산 1개
                val archive = if (a.has("archiveSha256")) checked(a.getString("archiveSha256"), "$id.archiveSha256") else null
                val files = LinkedHashMap<String, String>()                             // 저장 경로 → 해시
                val f = a.optJSONObject("files")                                        // 없으면 null
                if (f != null) for (path in f.keys()) files[path] = checked(f.getString(path), "$id/$path")
                out[id] = Entry(archive, files)
            }
            return out
        } catch (e: JSONException) {
            throw AppError.ManifestCorrupt(e.message ?: "JSON 형식 오류", e)             // 깨진 JSON
        }
    }

    /**
     * 목적: 매니페스트의 해시를 자산 표에 합친다.
     * 입력: assets - 자산 ID → 정의(Catalog.assets), json - 매니페스트 내용
     * 출력: 없음(입력 맵은 바뀌지 않는다)
     * 리턴: Map<String, AssetSpec> - 해시가 채워진 새 표(매니페스트에 없는 자산은 그대로 "미검증")
     * 예외: AppError.ManifestCorrupt - parse 와 같다
     */
    fun merge(assets: Map<String, AssetSpec>, json: String): Map<String, AssetSpec> {
        val entries = parse(json)                                                       // 자산 ID → 해시 묶음
        return assets.mapValues { (id, spec) ->
            entries[id]?.let { spec.withHashes(it.archiveSha256, it.files) } ?: spec    // 있으면 합치고 없으면 그대로
        }
    }

    /**
     * 목적: 해시 글자 형식을 확인한다.
     * 입력: value - 해시 글자, where - 오류 문구에 넣을 위치
     * 출력: 없음
     * 리턴: String - 확인된 해시
     * 예외: AppError.ManifestCorrupt - 16진수 소문자 64자가 아닐 때
     */
    private fun checked(value: String, where: String): String {
        if (!SHA256.matches(value)) throw AppError.ManifestCorrupt("$where 의 해시 형식이 틀리다")   // 형식 오류
        return value
    }
}
