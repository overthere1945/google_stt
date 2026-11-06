/*
 * 파일명: data/prefs/PrefsRepositoriesTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-PREF-01] DataStore 저장소 3종이 값을 저장하고, 저장소를 닫았다 다시 열어도(앱을 다시 켠 것과 같다) 같은 값을 돌려주는지 확인한다.
 *     · SettingsRepository: 기본값, 저장·복원, 동시 작업 수 상한
 *     · LangMemoryRepository: 한국어 / 영어 / 건너뛰기 기억, 지우기
 *     · RtfRepository: 실측 최근 20개, 빠른 측정, RtfTable 로 읽기
 * - [E-PREF-01] 저장 파일이 깨졌으면 기본값으로 바뀌어 계속 동작한다.
 *
 * add-hyungchul-20261106-1645 — [T3-4]
 */
package com.example.google_stt.data.prefs

import androidx.datastore.core.DataStore                           // 저장소
import androidx.datastore.preferences.core.Preferences             // 키-값 묶음
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context 얻기
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.condition.FeedChoice          // 공급 속도
import com.example.google_stt.domain.condition.RunPurpose          // 실행 목적
import com.example.google_stt.domain.eta.RtfKey                    // 실측 키
import com.example.google_stt.domain.eta.RtfSource                 // 출처
import com.example.google_stt.domain.vad.SegMode                   // 구간 처리
import java.io.File                                                // 저장 파일
import kotlinx.coroutines.CoroutineScope                           // 저장소 범위
import kotlinx.coroutines.Dispatchers                              // 입출력 스레드
import kotlinx.coroutines.Job                                      // 범위의 작업
import kotlinx.coroutines.flow.first                               // 흐름의 첫 값
import kotlinx.coroutines.runBlocking                              // 시험에서 suspend 함수 부르기
import org.junit.After                                             // 시험 뒤 정리
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertNull                                 // null 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Before                                            // 시험 전 준비
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: 저장소 3종 계측 시험. */
@RunWith(AndroidJUnit4::class)
class PrefsRepositoriesTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext   // 시험 대상 앱의 Context
    private val dir = File(context.cacheDir, "prefs_it")                               // 시험용 저장 폴더(앱의 실제 설정을 건드리지 않는다)
    private val jobs = ArrayList<Job>()                                                // 연 저장소들의 범위(닫을 때 쓴다)

    /** 목적: 시험 전에 저장 폴더를 비운다. 입력: 없음 / 출력: 폴더가 비워진다 / 리턴: 없음 */
    @Before
    fun setUp() {
        dir.deleteRecursively(); dir.mkdirs()
    }

    /** 목적: 시험 뒤에 저장소를 닫고 폴더를 지운다. 입력: 없음 / 출력: 폴더가 지워진다 / 리턴: 없음 */
    @After
    fun tearDown() {
        closeAll(); dir.deleteRecursively()
    }

    /**
     * 목적: 저장소를 연다.
     * 입력: name - 저장소 이름, onCorrupt - 깨진 파일을 만났을 때 불리는 함수 / 출력: 없음 / 리턴: DataStore<Preferences>
     */
    private fun open(name: String, onCorrupt: (Throwable) -> Unit = {}): DataStore<Preferences> {
        val job = Job().also { jobs += it }                                            // 이 저장소의 범위
        return PrefsStores.open(File(dir, "$name.preferences_pb"), CoroutineScope(Dispatchers.IO + job), onCorrupt)
    }

    /**
     * 목적: 연 저장소를 모두 닫는다(앱이 꺼진 것과 같다 — 같은 파일을 다시 열 수 있게 된다).
     * 입력: 없음 / 출력: 저장소 범위가 끝난다 / 리턴: 없음
     */
    private fun closeAll() = runBlocking {
        for (j in jobs) { j.cancel(); j.join() }                                       // 범위를 끝내고 끝날 때까지 기다린다
        jobs.clear()
    }

    /**
     * 목적: [IT-PREF-01] 설정 — 처음에는 기본값, 저장하면 다시 열어도 같은 값, 동시 작업 수는 실행 목적의 상한을 넘지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun settings_saveAndRestore() = runBlocking {
        var repo = SettingsRepository(open(PrefsStores.SETTINGS))
        assertEquals(Settings(), repo.settings.first())                                // 기본값(동시 1, 시간 측정, Wi-Fi 전용 …)
        assertNull(repo.settings.first().selectedModels)                               // 아직 고른 적 없음
        val want = Settings(
            inputTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Finput_sound%2Fscarlett",
            outputTreeUri = "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2Foutput",
            selectedModels = setOf("whisper-base", "zipformer-ko"), selectedVads = setOf("NOVAD", "SILERO"),
            customVad = "SILERO:threshold=0.4", seg = SegMode.SEG10, feed = FeedChoice.D50,
            purpose = RunPurpose.ACCURACY, workers = 3, wifiOnly = false,
        )
        repo.update { want }
        assertEquals(want, repo.settings.first())
        closeAll()                                                                     // 앱을 껐다 켠 것처럼
        repo = SettingsRepository(open(PrefsStores.SETTINGS))
        assertEquals(want, repo.settings.first())                                      // 다시 열어도 같은 값
        repo.update { it.copy(purpose = RunPurpose.TIMING, selectedModels = null, inputTreeUri = null) }   // 일부만 바꾼다
        val s = repo.settings.first()
        assertEquals(1, s.workers)                                                     // 시간 측정은 동시 1 고정
        assertNull(s.selectedModels); assertNull(s.inputTreeUri)                       // null 로 되돌린 값
        assertEquals(want.outputTreeUri, s.outputTreeUri)                              // 나머지는 그대로
        assertEquals(setOf("NOVAD", "SILERO"), s.selectedVads)
        repo.update { it.copy(purpose = RunPurpose.ACCURACY, workers = 9) }
        assertEquals(3, repo.settings.first().workers)                                 // 상한 3
    }

    /**
     * 목적: [IT-PREF-01] 언어 기억 — 한국어·영어·건너뛰기를 기억하고 다시 열어도 같다. 하나 지우기·모두 지우기.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun langMemory_rememberSkipForgetClear() = runBlocking {
        var repo = LangMemoryRepository(open(PrefsStores.LANG_MEMORY))
        assertTrue(repo.memory.first().isEmpty())
        val misc = "primary:Documents/input_sound/x/misc"; val noise = "primary:Documents/input_sound/x/noise: 한글"; val etc = "primary:a/b"
        repo.remember(misc, Language.KO); repo.remember(noise, null); repo.remember(etc, Language.EN)
        closeAll()
        repo = LangMemoryRepository(open(PrefsStores.LANG_MEMORY))
        val m = repo.memory.first()
        assertEquals(Language.KO, m[misc]); assertEquals(Language.EN, m[etc])
        assertTrue(noise in m); assertNull(m[noise])                                   // 건너뛰기 = 키는 있고 값은 null
        repo.forget(etc)
        assertEquals(setOf(misc, noise), repo.memory.first().keys)
        repo.clear()
        assertTrue(repo.memory.first().isEmpty())
    }

    /**
     * 목적: [IT-PREF-01] 실측 RTF — 키마다 최근 20개만 남고, 빠른 측정과 함께 RtfTable 로 읽힌다. 다시 열어도 같다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rtf_keepsLast20AndCalibration() = runBlocking {
        var repo = RtfRepository(open(PrefsStores.RTF))
        val s24 = "SM-S928N/SM8650"
        val key = RtfKey(s24, "whisper-base", "SILERO", 1)
        for (i in 1..21) repo.addMeasured(key, i / 100.0)                              // 0.01 … 0.21
        repo.addMeasured(RtfKey("SM-S938N/SM8750", "qwen3asr-0.6b", "NOVAD", 2), 0.2)  // 다른 기기 값
        repo.setCalibration(s24, "sensevoice-small", 0.5); repo.setCalibration(s24, "sensevoice-small", 0.04)   // 뒤 값이 남는다
        closeAll()
        repo = RtfRepository(open(PrefsStores.RTF))
        val t = repo.table.first()
        assertEquals((2..21).map { it / 100.0 }, t.measured.getValue(key))             // 가장 오래된 0.01 이 빠졌다
        assertEquals(0.115, t.lookup(key)!!.rtf, 1e-9)                                 // 20개의 중앙값
        assertEquals(RtfSource.MEASURED, t.lookup(key)!!.source)
        assertEquals(0.04, t.lookup(RtfKey(s24, "sensevoice-small", "NOVAD", 1))!!.rtf, 1e-9)
        assertEquals(RtfSource.CALIBRATION, t.lookup(RtfKey(s24, "sensevoice-small", "NOVAD", 1))!!.source)
        assertEquals(RtfSource.OTHER_DEVICE_REF, t.lookup(RtfKey(s24, "qwen3asr-0.6b", "NOVAD", 1))!!.source)   // 다른 기기 값은 참고로만
        assertNull(t.lookup(RtfKey(s24, "zipformer-ko", "NOVAD", 1)))
    }

    /**
     * 목적: [E-PREF-01] 저장 파일이 깨졌으면 기본값으로 바뀌고(알림 함수가 불린다) 그 뒤로 정상 동작한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun corruptFile_fallsBackToDefaults() = runBlocking {
        File(dir, "${PrefsStores.SETTINGS}.preferences_pb").writeBytes(ByteArray(64) { 0xFF.toByte() })   // 읽을 수 없는 내용
        val errors = ArrayList<Throwable>()
        val repo = SettingsRepository(open(PrefsStores.SETTINGS) { errors += it })
        assertEquals(Settings(), repo.settings.first())                                // 기본값으로 동작
        assertEquals(1, errors.size)                                                   // 깨졌다는 알림 1회(로그·화면 안내용)
        repo.update { it.copy(workers = 1, wifiOnly = false) }
        assertEquals(false, repo.settings.first().wifiOnly)                            // 다시 저장할 수 있다
    }
}
