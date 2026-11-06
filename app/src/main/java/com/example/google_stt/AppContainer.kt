/*
 * 파일명: AppContainer.kt
 * 목적 및 기능:
 * - 앱에서 하나씩만 있어야 하는 객체를 만들어 들고 있는다(수동 주입 — Hilt 를 쓰지 않는다, 4단계 Q10).
 *   지금은 자산 받기(T4-7)에 필요한 것만: 로그, 설정 저장소, 자산 저장 위치, 자산 상태 저장소.
 * - add-hyungchul-20261106-2233 : [T6-2] 실행 파이프라인의 연결(pipelineDeps·audioSource) — 엔진·VAD·저장·기기 계측을 실제 구현으로 잇는다.
 * - 자산 정의 = Catalog.assets + assets/asset_manifest.json 의 해시. 매니페스트가 깨졌으면 해시 없이("미검증") 동작한다(E-AS-09).
 *
 * add-hyungchul-20261106-1721 — [T4-7]
 */
package com.example.google_stt

import android.content.Context                                     // 앱 Context
import com.example.google_stt.data.saf.AudioItem                   // 음원 항목  // add-hyungchul-20261106-2233 : [T6-2] 아래 import 들
import com.example.google_stt.data.saf.ResultWriter                // 결과 쓰기
import com.example.google_stt.data.saf.SafTree                     // 출력 폴더
import com.example.google_stt.data.device.DeviceProbe              // 기기 계측
import com.example.google_stt.engine.audio.AudioDecoder            // 디코딩
import com.example.google_stt.engine.denoise.NoiseAlgorithm        // 노이즈 저감 알고리즘
import com.example.google_stt.engine.denoise.NoiseReduceConfig     // 노이즈 저감 설정
import com.example.google_stt.engine.denoise.NoiseReducerFactory   // 노이즈 저감기 만들기
import com.example.google_stt.engine.stt.SttAdapterFactory         // STT 어댑터 만들기
import com.example.google_stt.engine.vad.VadDetectorFactory        // VAD 실행기 만들기
import com.example.google_stt.run.AudioSource                      // 파이프라인의 음원
import com.example.google_stt.run.Decoded                          // 디코딩 결과
import com.example.google_stt.run.Denoiser                         // 노이즈 저감기
import com.example.google_stt.run.DeviceMeter                      // 기기 계측 약속
import com.example.google_stt.run.PipelineDeps                     // 파이프라인이 받는 것
import com.example.google_stt.run.RunMetaExtras                    // run_meta 용 값
import java.text.SimpleDateFormat                                  // 시각 글자
import java.util.Date                                              // 지금 시각
import java.util.Locale                                            // 숫자·날짜 표기 고정
import kotlinx.coroutines.Dispatchers                              // 입출력 스레드
import kotlinx.coroutines.withContext                              // 스레드 바꾸기
import com.example.google_stt.data.assets.AssetDownloader          // 내려받기 도구
import com.example.google_stt.data.assets.AssetRepository          // 자산 상태 저장소
import com.example.google_stt.data.assets.AssetStore               // 자산 저장 위치
import com.example.google_stt.data.device.AppLog                   // 앱 로그
import com.example.google_stt.data.prefs.LangMemoryRepository      // 폴더 언어 기억  // add-hyungchul-20261106-2326 : [T7-2]
import com.example.google_stt.data.prefs.PrefsStores               // 설정 파일 열기
import com.example.google_stt.data.prefs.RtfRepository             // 실측 RTF  // add-hyungchul-20261106-2243 : [T6-7]
import com.example.google_stt.run.RunController                    // 실행 컨트롤러  // add-hyungchul-20261106-2243 : [T6-7]
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

    /** add-hyungchul-20261106-2326 : [T7-2] 폴더 언어 기억(물어서 정한 언어·건너뛰기) */
    val langMemory: LangMemoryRepository by lazy { LangMemoryRepository(PrefsStores.open(PrefsStores.file(context, PrefsStores.LANG_MEMORY))) }

    /** add-hyungchul-20261106-2243 : [T6-7] 실측 RTF 저장소(예상 소요 시간 계산에 쓴다) */
    val rtf: RtfRepository by lazy { RtfRepository(PrefsStores.open(PrefsStores.file(context, PrefsStores.RTF), onCorrupt = { log.w("E-PREF-01: RTF 기록이 깨져 비웠다 — ${it.message}") })) }

    /** add-hyungchul-20261106-2243 : [T6-7] 실행 컨트롤러(앱 전역 1개 — 화면과 서비스가 같은 것을 쓴다) */
    val controller: RunController by lazy { RunController(context, this) }

    /** add-hyungchul-20261106-2233 : [T6-2] 기기 계측(배터리·발열·메모리·네트워크) */
    val probe: DeviceProbe by lazy { DeviceProbe(context) { log.w(it) } }

    /** add-hyungchul-20261106-2233 : [T6-2] 앱 판(run_meta app_version) */
    val appVersion: String by lazy { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }

    /**
     * add-hyungchul-20261106-2233 : [T6-2]
     * 목적: 지금 시각을 ISO 8601 글자로 준다(CSV started_at, run_meta run_started_at — v3 와 같은 형식).
     * 입력: 없음 / 출력: 없음 / 리턴: String - 예 2026-11-06T22:40:01.123+09:00
     */
    fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    /**
     * add-hyungchul-20261106-2233 : [T6-2]
     * 목적: 실행 파이프라인에 실제 구현을 이어 준다.
     * 입력: tree - 출력 폴더
     * 출력: 없음
     * 리턴: PipelineDeps
     */
    fun pipelineDeps(tree: SafTree): PipelineDeps {
        val writer = ResultWriter(tree)                                              // 이 출력 폴더의 결과 쓰기
        return PipelineDeps(
            adapterFor = { SttAdapterFactory.create(it) { line -> log.w(line) } },
            modelDir = { it.assetId?.let(assetStore::dir) },                         // ML Kit 은 자산이 없다
            vadFor = { VadDetectorFactory.create(it, assetStore::dir) },
            denoiserFor = ::denoiser,
            openSink = writer::open,
            meter = DeviceMeter { val start = probe.begin(); { probe.end(start) } },
            meta = { job ->
                val infos = job.assetIds.associateWith { assetStore.readyInfo(it) }  // 자산 ID → 준비 표시
                RunMetaExtras(
                    appVersion = appVersion, deviceKey = probe.deviceKey, env = probe.envMeta(),
                    assetSha256 = infos.mapValues { (_, i) -> if (i != null && i.verified) i.summary else "unverified" },
                    downloadSource = infos.values.mapNotNull { it?.source }.distinct().let { if (it.size == 1) it[0] else if (it.isEmpty()) "none" else "mixed" },
                )
            },
            now = ::isoNow,
            log = log::i,
        )
    }

    /**
     * add-hyungchul-20261106-2233 : [T6-2]
     * 목적: 음원 항목을 파이프라인의 음원으로 바꾼다(디코딩은 입출력 스레드에서 한다).
     * 입력: item - 훑어 찾은 음원 / 출력: 없음 / 리턴: AudioSource
     */
    fun audioSource(item: AudioItem): AudioSource = AudioSource(item.relDir, item.baseName, item.ext, item.bytes) {
        val d = withContext(Dispatchers.IO) { AudioDecoder.decodeDetailed(context, item.uri) }
        Decoded(d.pcm, d.srcRate, d.srcChannels)
    }

    /**
     * add-hyungchul-20261106-2233 : [T6-2]
     * 목적: 조건 이름의 노이즈 저감 조각(예 "ns_gtcrn_lim12")으로 저감기를 만든다. 알고리즘별 기본 설정만 다룬다.
     * 입력: folder - 조각 / 출력: 모델이 메모리에 올라간다 / 리턴: Denoiser
     * 예외: IllegalArgumentException - 아는 설정이 아닐 때(작업은 ERR_NS_LOAD 로 끝난다)
     */
    private fun denoiser(folder: String): Denoiser {
        val config = NoiseAlgorithm.values().filter { it.runnable }.map { NoiseReduceConfig.defaults(it) }.firstOrNull { it.outputFolder() == folder }
            ?: throw IllegalArgumentException("모르는 노이즈 저감 설정: $folder")
        val reducer = NoiseReducerFactory.create(context, config)
        return Denoiser({ pcm -> reducer.process(pcm, config).pcm }, reducer::close)
    }
}
