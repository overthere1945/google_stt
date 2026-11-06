/*
 * 파일명: data/prefs/SettingsRepository.kt
 * 목적 및 기능:
 * - 화면 설정을 DataStore(Preferences)에 저장하고 되살린다(FR-01-06, 4단계 7-4). v3 plan/PlanState.kt(SharedPreferences)를 대신한다.
 *     input_tree_uri / output_tree_uri / selected_models / selected_vads / custom_vad / seg / feed / purpose / workers / wifi_only
 * - 저장소 파일 3개(settings, lang_memory, rtf)를 여는 공용 함수 PrefsStores.open 도 여기 둔다.
 *   파일이 깨졌으면 기본값으로 바꿔 계속 동작한다(E-PREF-01).
 * - 같은 파일을 여는 DataStore 는 프로세스에 하나만 있어야 한다 → 앱에서는 AppContainer 가 한 번만 만든다(T6-7).
 *
 * add-hyungchul-20261106-1645 — [T3-4]
 */
package com.example.google_stt.data.prefs

import android.content.Context                                              // 저장 위치
import androidx.datastore.core.DataStore                                    // 저장소
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler        // 깨진 파일 처리
import androidx.datastore.preferences.core.MutablePreferences               // 고치는 중인 값 묶음
import androidx.datastore.preferences.core.PreferenceDataStoreFactory       // 저장소 만들기
import androidx.datastore.preferences.core.Preferences                      // 키-값 묶음
import androidx.datastore.preferences.core.booleanPreferencesKey            // Boolean 키
import androidx.datastore.preferences.core.edit                             // 값 바꾸기
import androidx.datastore.preferences.core.emptyPreferences                 // 빈 값
import androidx.datastore.preferences.core.intPreferencesKey                // Int 키
import androidx.datastore.preferences.core.stringPreferencesKey             // String 키
import androidx.datastore.preferences.core.stringSetPreferencesKey          // Set<String> 키
import androidx.datastore.preferences.preferencesDataStoreFile              // 표준 저장 파일 위치
import com.example.google_stt.domain.condition.FeedChoice                   // 공급 속도
import com.example.google_stt.domain.condition.RunPurpose                   // 실행 목적
import com.example.google_stt.domain.vad.SegMode                            // 구간 처리
import java.io.File                                                         // 저장 파일
import kotlinx.coroutines.CoroutineScope                                    // 저장소가 쓰는 범위
import kotlinx.coroutines.Dispatchers                                       // 입출력 스레드
import kotlinx.coroutines.SupervisorJob                                     // 독립 작업
import kotlinx.coroutines.flow.Flow                                         // 값 흐름
import kotlinx.coroutines.flow.map                                          // 변환

/** 목적: Preferences 저장소 열기(세 저장소가 같이 쓴다). */
object PrefsStores {

    const val SETTINGS = "settings"         // 화면 설정
    const val LANG_MEMORY = "lang_memory"   // 폴더 언어 기억
    const val RTF = "rtf"                   // 실측 RTF

    /**
     * 목적: 파일 하나에 대한 저장소를 연다.
     * 입력: file - 저장 파일(".preferences_pb" 로 끝나야 한다), scope - 저장소가 쓸 코루틴 범위(취소하면 파일을 놓는다),
     *       onCorrupt - 파일이 깨져 기본값으로 바꿨을 때 불리는 함수(로그용)
     * 출력: 없음
     * 리턴: DataStore<Preferences>
     */
    fun open(
        file: File,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        onCorrupt: (Throwable) -> Unit = {},
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { e -> onCorrupt(e); emptyPreferences() },   // E-PREF-01: 기본값으로
        scope = scope,
        produceFile = { file },
    )

    /**
     * 목적: 앱의 표준 위치에 있는 저장 파일을 준다.
     * 입력: context - 앱 Context, name - 저장소 이름(SETTINGS / LANG_MEMORY / RTF) / 출력: 없음 / 리턴: File
     */
    fun file(context: Context, name: String): File = context.preferencesDataStoreFile(name)
}

/**
 * 목적: 화면 설정 값.
 * 필드: inputTreeUri / outputTreeUri - 고른 폴더의 트리 URI 글자(없으면 null),
 *       selectedModels - 고른 결과 엔진 이름(null = 아직 고른 적 없음 → 화면이 기본 선택을 쓴다),
 *       selectedVads - 고른 VAD 프리셋 이름(null = 아직 고른 적 없음), customVad - CUSTOM VAD 입력값(화면이 정한 형식의 글자),
 *       seg - 구간 처리, feed - 공급 속도, purpose - 실행 목적, workers - 동시 작업 수, wifiOnly - 다운로드를 Wi-Fi 에서만
 */
data class Settings(
    val inputTreeUri: String? = null,                // 입력 폴더
    val outputTreeUri: String? = null,               // 출력 폴더
    val selectedModels: Set<String>? = null,         // 고른 모델
    val selectedVads: Set<String>? = null,           // 고른 VAD
    val customVad: String = "",                      // CUSTOM VAD 값
    val seg: SegMode = SegMode.ENGINE_MAX,           // 구간 처리(기본: 엔진별 최대 길이)
    val feed: FeedChoice = FeedChoice.NONE,          // 공급 속도(기본: 사용 안 함)
    val purpose: RunPurpose = RunPurpose.TIMING,     // 실행 목적(기본: 시간 측정)
    val workers: Int = 1,                            // 동시 작업 수(기본 1 — D17)
    val wifiOnly: Boolean = true,                    // Wi-Fi 전용(기본 켬 — FR-05-08)
)

/**
 * 목적: 화면 설정 저장소.
 * 입력(생성자): store - settings 저장소
 */
class SettingsRepository(private val store: DataStore<Preferences>) {

    private object Keys {
        val INPUT = stringPreferencesKey("input_tree_uri")
        val OUTPUT = stringPreferencesKey("output_tree_uri")
        val MODELS = stringSetPreferencesKey("selected_models")
        val VADS = stringSetPreferencesKey("selected_vads")
        val CUSTOM_VAD = stringPreferencesKey("custom_vad")
        val SEG = stringPreferencesKey("seg")
        val FEED = stringPreferencesKey("feed")
        val PURPOSE = stringPreferencesKey("purpose")
        val WORKERS = intPreferencesKey("workers")
        val WIFI_ONLY = booleanPreferencesKey("wifi_only")
    }

    /** 설정 값의 흐름(저장될 때마다 새 값이 나온다) */
    val settings: Flow<Settings> = store.data.map { read(it) }

    /**
     * 목적: 저장된 값 묶음을 설정 값으로 바꾼다(없거나 모르는 글자는 기본값).
     * 입력: p - 저장된 값 묶음 / 출력: 없음 / 리턴: Settings
     */
    private fun read(p: Preferences): Settings {
        val d = Settings()                                                        // 기본값
        val purpose = RunPurpose.values().firstOrNull { it.name == p[Keys.PURPOSE] } ?: d.purpose
        return Settings(
            inputTreeUri = p[Keys.INPUT],
            outputTreeUri = p[Keys.OUTPUT],
            selectedModels = p[Keys.MODELS],
            selectedVads = p[Keys.VADS],
            customVad = p[Keys.CUSTOM_VAD] ?: d.customVad,
            seg = SegMode.values().firstOrNull { it.name == p[Keys.SEG] } ?: d.seg,   // 모르는 글자면 기본값
            feed = FeedChoice.values().firstOrNull { it.name == p[Keys.FEED] } ?: d.feed,
            purpose = purpose,
            workers = (p[Keys.WORKERS] ?: d.workers).coerceIn(1, purpose.maxWorkers),  // 실행 목적의 상한을 넘지 않게
            wifiOnly = p[Keys.WIFI_ONLY] ?: d.wifiOnly,
        )
    }

    /**
     * 목적: 설정을 바꿔 저장한다.
     * 입력: transform - 지금 값 → 새 값
     * 출력: 저장소 파일이 바뀐다
     * 리턴: 없음
     */
    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val s = transform(read(p))                                            // 지금 값에서 새 값을 만든다
            setOrRemove(p, Keys.INPUT, s.inputTreeUri)                            // null 이면 키를 지운다
            setOrRemove(p, Keys.OUTPUT, s.outputTreeUri)
            setOrRemove(p, Keys.MODELS, s.selectedModels)
            setOrRemove(p, Keys.VADS, s.selectedVads)
            p[Keys.CUSTOM_VAD] = s.customVad
            p[Keys.SEG] = s.seg.name
            p[Keys.FEED] = s.feed.name
            p[Keys.PURPOSE] = s.purpose.name
            p[Keys.WORKERS] = s.workers.coerceIn(1, s.purpose.maxWorkers)
            p[Keys.WIFI_ONLY] = s.wifiOnly
        }
    }

    /**
     * 목적: 값이 있으면 저장하고 null 이면 키를 지운다.
     * 입력: p - 고치는 중인 값 묶음, key - 키, value - 값 / 출력: p 가 바뀐다 / 리턴: 없음
     */
    private fun <T> setOrRemove(p: MutablePreferences, key: Preferences.Key<T>, value: T?) {
        if (value == null) p.remove(key) else p[key] = value
    }
}
