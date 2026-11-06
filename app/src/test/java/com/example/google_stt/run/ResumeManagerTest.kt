/*
 * 파일명: run/ResumeManagerTest.kt (app/src/test)
 * 목적 및 기능:
 * - [UT-RUN-09] 이어 하기 규칙을 PC(JVM)에서 가짜 저장소로 확인한다.
 *     · DONE 은 건너뛰고 FAILED·INTERRUPTED·SKIPPED 는 다시 돈다. "처음부터" 면 모두 다시
 *     · 돌다가 앱이 죽으면 다음에 읽을 때 비정상 종료로 센다. 같은 작업이 연속 2회면 FAILED(ERR_CRASH_LOOP)로 두고 돌리지 않는다.
 *       정상으로 끝나면 횟수는 지워진다
 *     · 시작 전 폴더 정리: "_<이름>" 삭제, "<이름>" → "_stale_<이름>_<시각>"
 *     · 꺼진 이유 기록: 같은 시각의 기록은 다시 적지 않는다
 *
 * add-hyungchul-20261106-2237 — [T6-4]
 */
package com.example.google_stt.run

import com.example.google_stt.data.saf.OutputLayout                // 파일 이름
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.condition.RunOptions          // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder               // 언어 폴더
import com.example.google_stt.domain.lang.LangSource               // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                      // 작업
import com.example.google_stt.domain.plan.PlanProgress             // 진행 기록 형식
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋
import com.example.google_stt.domain.vad.VadSetting                // VAD 설정
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Test                                              // JUnit 4 시험 표시

/** 목적: ResumeManager 시험. */
class ResumeManagerTest {

    /** 가짜 저장소 — 파일은 맵, 폴더는 "언어/이름" 집합 */
    private class FakeStore : ResumeStore {
        val files = HashMap<String, String>(); val dirs = HashSet<String>()
        private fun path(lang: List<String>, dir: String) = (lang + dir).joinToString("/")
        override fun read(name: String): String? = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun exists(lang: List<String>, dir: String) = path(lang, dir) in dirs
        override fun delete(lang: List<String>, dir: String) = dirs.remove(path(lang, dir))
        override fun rename(lang: List<String>, from: String, to: String): Boolean {
            if (!dirs.remove(path(lang, from))) return false
            dirs += path(lang, to); return true
        }
    }

    private val store = FakeStore()
    private val logs = ArrayList<String>()
    private var clock = 0                                                              // 부를 때마다 1초씩 가는 시계

    /** 목적: 같은 저장소를 보는 새 관리자를 만든다(앱을 다시 켠 상황). 입력: 없음 / 출력: 없음 / 리턴: ResumeManager */
    private fun manager() = ResumeManager(store, { "2026-11-06 23:00:%02d".format(clock++) }, { "20261106_230000" }) { logs += it }

    /** 목적: 시험용 작업을 만든다. 입력: engine - 결과 엔진 이름 / 출력: 없음 / 리턴: Job */
    private fun job(engine: String): Job {
        val spec = Catalog.model(engine)!!
        return Job(LangFolder("kr", "id", Language.KO, LangSource.AUTO_SUBFOLDER), spec,
            VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap()), RunOptions().effectiveFor(spec.runtime))
    }

    private val a = job("whisper-base"); private val b = job("zipformer-ko"); private val c = job("vosk-small-ko"); private val d = job("sensevoice-small")

    /**
     * 목적: [UT-RUN-09] 끝난 작업은 건너뛰고 나머지는 다시 돈다. 파일은 v3 형식으로 읽힌다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun plan_skipsDoneAndRerunsOthers() {
        var m = manager(); m.load("plan.csv")
        assertEquals(listOf(a, b, c, d), m.plan(listOf(a, b, c, d)).todo)               // 기록이 없으면 모두
        val t = m.begin(a, 1); m.mark(a, PlanProgress.DONE, 1, t, 24, 0, "")
        m.mark(b, PlanProgress.FAILED, 1, m.begin(b, 1), 3, 2, "ERR_ENGINE_DEAD")
        m.mark(c, PlanProgress.SKIPPED, 0, "", 0, 0, "ERR_MLKIT_STUCK")
        m = manager(); assertTrue(m.load().isEmpty())                                  // 앱을 다시 켰다
        val p = m.plan(listOf(a, b, c, d))
        assertEquals(listOf(b, c, d), p.todo); assertEquals(listOf(a), p.done); assertTrue(p.crashLoop.isEmpty())
        assertEquals(listOf(a, b, c, d), m.plan(listOf(a, b, c, d), restart = true).todo)   // "처음부터"
        val parsed = PlanProgress.parse(store.files.getValue(OutputLayout.PROGRESS_FILE))
        assertEquals(setOf(a.key), parsed.doneKeys); assertEquals(24, parsed.rows.getValue(a.key).ok); assertEquals("ERR_ENGINE_DEAD", parsed.rows.getValue(b.key).note)
        assertEquals("kr/whisper-base_sherpa-int8_NOVAD", a.key)
    }

    /**
     * 목적: [UT-RUN-09] 같은 작업이 연속 2회 비정상 종료하면 다시 돌리지 않는다. 한 번 정상으로 끝나면 횟수가 지워진다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun crashTwice_marksCrashLoop() {
        var m = manager(); m.load()
        m.begin(a, 1)                                                                  // 돌다가 앱이 죽는다(끝 상태를 적지 못함)
        m = manager(); m.load()
        assertEquals("CRASH:1", m.snapshot.getValue(a.key).note); assertEquals(PlanProgress.INTERRUPTED, m.snapshot.getValue(a.key).status)
        assertEquals(listOf(a), m.plan(listOf(a)).todo)                                // 한 번은 다시 돌린다
        m.begin(a, 1)                                                                  // 또 죽는다
        m = manager(); m.load()
        var p = m.plan(listOf(a, b))
        assertEquals(listOf(b), p.todo); assertEquals(listOf(a), p.crashLoop)
        assertEquals(PlanProgress.FAILED, m.snapshot.getValue(a.key).status); assertEquals(ResumeManager.CRASH_LOOP, m.snapshot.getValue(a.key).note)
        m = manager(); m.load()
        assertEquals(listOf(a), m.plan(listOf(a, b)).crashLoop)                        // 다시 켜도 그대로다
        assertEquals(listOf(a, b), m.plan(listOf(a, b), restart = true).todo)          // "처음부터" 는 다시 돌린다
        // 한 번 죽은 뒤 사용자 중지로 끝나면(정상 기록) 횟수는 지워진다
        m.begin(b, 1); m = manager(); m.load()
        m.mark(b, PlanProgress.INTERRUPTED, 1, m.begin(b, 1), 2, 0, "사용자 중지")
        m.begin(b, 1); m = manager(); m.load()
        p = m.plan(listOf(b))
        assertEquals("CRASH:1", m.snapshot.getValue(b.key).note); assertEquals(listOf(b), p.todo)   // 연속 2회가 아니다
    }

    /**
     * 목적: 시작 전 폴더 정리 — 중단된 "_" 폴더는 지우고 끝난 결과는 "_stale_" 로 치운다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun prepareDir_cleansWorkingAndMovesFinal() {
        val m = manager()
        store.dirs += listOf("kr/_whisper-base_sherpa-int8_NOVAD", "kr/whisper-base_sherpa-int8_NOVAD", "kr/zipformer-ko_sherpa-int8_NOVAD")
        m.prepareDir(a)
        assertEquals(setOf("kr/_stale_whisper-base_sherpa-int8_NOVAD_20261106_230000", "kr/zipformer-ko_sherpa-int8_NOVAD"), store.dirs)
        assertEquals(2, logs.size)
        m.prepareDir(c); assertEquals(2, logs.size)                                    // 치울 것이 없으면 아무 일도 없다
    }

    /**
     * 목적: 꺼진 이유 기록 — 새 기록만 더하고, 메모리 부족을 알려 준다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun recordExits_appendsOnlyNew() {
        val m = manager()
        val lmk = ExitRecord("2026-11-06 22:10:00", "LOW_MEMORY", "low, memory", 900, 1500)
        val user = ExitRecord("2026-11-06 21:00:00", "USER_REQUESTED", "stop", 0, 148)
        assertEquals(listOf(user, lmk), m.recordExits(listOf(lmk, user)))               // 시각순으로 적는다
        assertTrue(m.recordExits(listOf(lmk, user)).isEmpty())                         // 같은 기록은 다시 적지 않는다
        val crash = ExitRecord("2026-11-06 22:30:00", "CRASH_NATIVE", "", 0, 0)
        assertEquals(listOf(crash), m.recordExits(listOf(crash, lmk)))
        val text = store.files.getValue(OutputLayout.EXITS_FILE)
        val lines = text.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertEquals(4, lines.size); assertEquals(ResumeManager.EXITS_HEADER, lines[0])
        assertTrue(lines[2].contains("\"low, memory\""))                               // 쉼표가 든 설명은 따옴표로 감싼다
        assertTrue(lmk.lowMemory); assertFalse(user.lowMemory)
    }
}
