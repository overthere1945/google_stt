/*
 * 파일명: AppContainer.kt
 * 목적 및 기능:
 * - 앱에서 하나씩만 있어야 하는 객체를 만들어 들고 있는다(수동 주입 — Hilt 를 쓰지 않는다, 4단계 Q10).
 *   지금은 자산 받기(T4-7)에 필요한 것만: 로그, 설정 저장소, 자산 저장 위치, 자산 상태 저장소.
 * - 자산 정의 = Catalog.assets + assets/asset_manifest.json 의 해시. 매니페스트가 깨졌으면 해시 없이("미검증") 동작한다(E-AS-09).
 *
 * add-hyungchul-20261106-1721 — [T4-7]
 */
package com.example.google_stt

import android.content.Context                                     // 앱 Context
import com.example.google_stt.data.assets.AssetDownloader          // 내려받기 도구
import com.example.google_stt.data.assets.AssetRepository          // 자산 상태 저장소
import com.example.google_stt.data.assets.AssetStore               // 자산 저장 위치
import com.example.google_stt.data.device.AppLog                   // 앱 로그
import com.example.google_stt.data.prefs.PrefsStores               // 설정 파일 열기
import com.example.google_stt.data.prefs.SettingsRepository        // 화면 설정
import com.example.google_stt.domain.AppError                      // 매니페스트 오류
import com.example.google_stt.domain.catalog.AssetManifest         // 해시 합치기
import com.example.google_stt.domain.catalog.AssetSpec             // 자산 정의
import com.example.google_stt.domain.catalog.Catalog               // 자산 목록
import java.io.File                                                // 저장 폴더
import java.io.IOException                                         // 매니페스트 읽기 실패

/**
 * 목적: 객체 묶음.
 * 입력(생성자): context - Application Context
 */
class AppContainer(private val context: Context) {

    private companion object {
        const val MANIFEST = "asset_manifest.json"   // APK 안의 해시 목록
        const val MODELS_DIR = "models"              // 앱 전용 저장소 안의 자산 폴더
    }

    /** 앱 로그(logcat 태그 GoogleStt + 화면 최근 줄) */
    val log = AppLog()

    /** 화면 설정 저장소 */
    val settings: SettingsRepository by lazy {
        SettingsRepository(PrefsStores.open(PrefsStores.file(context, PrefsStores.SETTINGS), onCorrupt = { log.w("E-PREF-01: 설정 파일이 깨져 기본값으로 바꿨다 — ${it.message}") }))
    }

    /** 자산 저장 위치 — 앱 전용 외부 저장소(없으면 내부 저장소) */
    val assetStore: AssetStore by lazy { AssetStore(context.getExternalFilesDir(MODELS_DIR) ?: File(context.filesDir, MODELS_DIR)) }

    /** 자산 정의(매니페스트 해시가 합쳐진 것) */
    val assetSpecs: Map<String, AssetSpec> by lazy {
        try {
            AssetManifest.merge(Catalog.assets, context.assets.open(MANIFEST).use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: AppError.ManifestCorrupt) {
            log.w("${e.message} — 해시 없이(미검증) 동작한다")                // E-AS-09
            Catalog.assets
        } catch (e: IOException) {
            log.w("E-AS-09: 자산 매니페스트를 열 수 없다(${e.message}) — 해시 없이(미검증) 동작한다")
            Catalog.assets
        }
    }

    /** 자산 상태 저장소(받기는 AssetDownloader 로) */
    val assets: AssetRepository by lazy { AssetRepository(assetStore, assetSpecs, AssetDownloader()::download, log = log::i) }
}
