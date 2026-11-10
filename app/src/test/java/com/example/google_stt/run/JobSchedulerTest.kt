/*
 * 파일명: run/JobSchedulerTest.kt (app/src/test)
 * 목적 및 기능:
 * - 스케줄러 규칙을 PC(JVM)에서 가짜 파이프라인·가상 시간으로 확인한다.
 *     · [UT-RUN-05] 슬롯이 3이어도 오프라인 동시 1, ML Kit 동시 1. 빈 슬롯은 다른 종류의 작업으로 채운다
 *     · [UT-RUN-06] "이 작업 끝나고 중지" — 새 작업을 시작하지 않고 돌던 작업은 DONE
 *     · [UT-RUN-07] "지금 중지" — 취소하면 바로 끝나고 돌던 작업은 INTERRUPTED 로 적힌다
 *     · [UT-RUN-09] 끝난 작업은 다시 돌리지 않는다
 *     · [UT-RUN-10] 자산을 받는 동안(슬롯 1) 다른 작업이 시작되지 않는다. 받지 못하면 그 작업만 FAILED
 *     · [UT-RUN-11] ML Kit 먹통 뒤 남은 ML Kit 작업은 SKIPPED, 다른 작업은 계속
 *     · 발열 대기 표시, 출력 폴더 오류는 실행 전체를 멈춘다
 *
 * add-hyungchul-20261106-2240 — [T6-5]
 */
package com.example.google_stt.run

import com.example.google_stt.domain.AppError                      // 출력 폴더 오류
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.catalog.Runtime               // 엔진 종류
import com.example.google_stt.domain.condition.RunOptions          // 실행 옵션
import com.example.google_stt.domain.condition.RunPurpose          // 실행 목적
import com.example.google_stt.domain.lang.LangFolder               // 언어 폴더
import com.example.google_stt.domain.lang.LangSource               // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                      // 작업
import com.example.google_stt.domain.plan.PlanProgress             // 상태 이름
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋
import com.example.google_stt.domain.vad.VadSetting                // VAD 설정
import kotlinx.coroutines.CompletableDeferred                      // 받기가 끝나는 때를 시험이 정한다
import kotlinx.coroutines.ExperimentalCoroutinesApi                // 가상 시간
import kotlinx.coroutines.cancelAndJoin                            // 지금 중지
import kotlinx.coroutines.delay                                    // 가짜 소요 시간
import kotlinx.coroutines.launch                                   // 코루틴 시작
import kotlinx.coroutines.test.StandardTestDispatcher              // 시험 스케줄러
import kotlinx.coroutines.test.TestScope                           // 시험 범위
import kotlinx.coroutines.test.advanceTimeBy                       // 가상 시간 보내기
import kotlinx.coroutines.test.currentTime                         // 가상 시각
import kotlinx.coroutines.test.runCurrent                          // 밀린 일 돌리기
import kotlinx.coroutines.test.runTest                             // 코루틴 시험
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assert.fail                                       // 예외가 안 났을 때 실패
import org.junit.Test                                              // JUnit 4 시험 표시

/** 목적: JobScheduler 시험. */
@OptIn(ExperimentalCoroutinesApi::class)
class JobSchedulerTest {

    /** 가짜 저장소(파일은 맵, 폴더는 집합) */
    private class FakeStore : ResumeStore {
        val files = HashMap<String, String>(); val dirs = HashSet<String>()
        override fun read(name: String): String? = files[name]
        override fun write(name: String, text: String) { files[name] = text }
        override fun exists(lang: List<String>, dir: String) = (lang + dir).joinToString("/") in dirs
        override fun delete(lang: List<String>, dir: String) = dirs.remove((lang + dir).joinToString("/"))
        override fun rename(lang: List<String>, from: String, to: String) = false
    }

    private val store = FakeStore()
    private val started = ArrayList<String>()                      // 실행이 시작된 작업(엔진 이름, 순서대로)
    private val runningNow = HashSet<Job>()                        // 지금 실행 중인 작업
    private var maxOffline = 0; private var maxMlKit = 0; private var maxAll = 0   // 동시에 돈 최대 수
    private val progress = ArrayList<RunProgress>()                // 알려진 진행 상황들
    private val logs = ArrayList<String>()
    private var thermalLevel = 0                                   // 발열 단계
    private var assets: suspend (Collection<String>) -> Map<String, String> = { emptyMap() }   // 자산 준비
    private var outcome: suspend (Job) -> JobResult = { j -> delay(10_000); JobResult(j, PlanProgress.DONE, 2, 2, 0, 10.0, 60.0) }   // 작업 하나 = 10초

    /** 목적: 시험용 작업을 만든다. 입력: engine - 결과 엔진 이름, lang - 언어 폴더 이름 / 출력: 없음 / 리턴: Job */
    private fun job(engine: String, lang: String = "kr"): Job {
        val spec = Catalog.model(engine)!!
        return Job(LangFolder(lang, "id", if (lang == "kr") Language.KO else Language.EN, LangSource.AUTO_SUBFOLDER), spec,
            VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap()), RunOptions().effectiveFor(spec.runtime))
    }

    /** 목적: 가짜로 채운 스케줄러를 만든다. 입력: 없음 / 출력: 없음 / 리턴: JobScheduler */
    private fun TestScope.scheduler(): JobScheduler {
        val resume = ResumeManager(store, { "t${testScheduler.currentTime}" }, { "stamp" }) { logs += it }
        return JobScheduler(SchedulerDeps(
            execute = { j, _, _ ->
                started += j.model.engine; runningNow += j
                maxAll = maxOf(maxAll, runningNow.size)
                maxOffline = maxOf(maxOffline, runningNow.count { it.model.runtime == Runtime.SHERPA_OFFLINE })
                maxMlKit = maxOf(maxMlKit, runningNow.count { it.model.runtime == Runtime.MLKIT })
                try { outcome(j) } finally { runningNow -= j }
            },
            ensureAssets = { assets(it) }, resume = resume, thermal = ThermalGate({ thermalLevel }), onProgress = { progress += it },
            log = { logs += it }, control = StandardTestDispatcher(testScheduler),
        ))
    }

    /** 목적: 실행 계획을 만든다. 입력: jobs - 작업들, workers - 동시 수 / 출력: 없음 / 리턴: RunPlan */
    private fun plan(jobs: List<Job>, workers: Int) =
        RunPlan(jobs, { emptyList() }, RunParams("run1", "start", workers, if (workers == 1) RunPurpose.TIMING else RunPurpose.ACCURACY), "plan.csv")

    /** 목적: 진행 기록에서 작업의 상태를 읽는다. 입력: j - 작업 / 출력: 없음 / 리턴: String? */
    private fun status(j: Job): String? = store.files["_plan_progress.csv"]?.let { PlanProgress.parse(it).rows[j.key]?.status }

    /**
     * 목적: [UT-RUN-05] 슬롯 3 — 오프라인 동시 1, ML Kit 동시 1, 빈 슬롯은 스트리밍·Vosk 로 채운다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun slots_limitOfflineAndMlKitToOne() = runTest {
        val jobs = listOf(job("whisper-base"), job("zipformer-ko"), job("google-mlkit"), job("google-mlkit", "en"), job("nemotron3.5-560ms"), job("vosk-small-ko"))
        val results = scheduler().run(plan(jobs, 3))
        assertEquals(6, results.size); assertTrue(results.all { it.status == PlanProgress.DONE })
        assertEquals(1, maxOffline); assertEquals(1, maxMlKit); assertEquals(3, maxAll)
        assertEquals(listOf("whisper-base", "google-mlkit", "nemotron3.5-560ms"), started.take(3))   // 막힌 종류를 건너뛰고 빈 슬롯을 채운다
        assertEquals(20_000L, currentTime)                                             // 6개를 3슬롯으로 — 두 차례
        assertEquals(RunProgress(total = 6, done = 6), progress.last()); assertEquals(3, progress.maxOf { it.running.size })
        val one = scheduler(); started.clear()
        assertTrue(one.run(plan(jobs, 1)).isEmpty()); assertTrue(started.isEmpty())    // [UT-RUN-09] 끝난 작업은 다시 돌리지 않는다
        assertEquals(RunProgress(total = 6, done = 6), progress.last())
    }

    /**
     * 목적: [UT-RUN-06][UT-RUN-07] 중지 2종.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun stopAfterCurrent_andStopNow() = runTest {
        val jobs = listOf(job("whisper-base"), job("zipformer-ko"), job("vosk-small-ko"))
        val s = scheduler()
        var results: List<JobResult>? = null
        val soft = launch { results = s.run(plan(jobs, 1)) }
        advanceTimeBy(3_000); runCurrent()
        s.stopAfterCurrent()                                                           // 첫 작업을 도는 중에 요청
        soft.join()
        assertEquals(listOf("whisper-base"), started); assertEquals(1, results!!.size); assertEquals(PlanProgress.DONE, status(jobs[0]))
        assertEquals(10_000L, currentTime)                                             // 돌던 작업은 끝까지 갔다
        val t0 = currentTime
        val hard = launch { scheduler().run(plan(jobs, 1)) }                           // 이어 하기: 2번째부터
        advanceTimeBy(4_000); runCurrent()
        assertEquals(listOf("whisper-base", "zipformer-ko"), started)
        hard.cancelAndJoin()                                                           // 지금 중지
        assertTrue(hard.isCancelled); assertEquals(4_000L, currentTime - t0)           // 기다리지 않고 바로 끝난다
        assertEquals(PlanProgress.INTERRUPTED, status(jobs[1])); assertEquals(null, status(jobs[2]))   // 돌던 것만 INTERRUPTED, 시작 안 한 것은 기록 없음
    }

    /**
     * 목적: [UT-RUN-10] 자산을 받는 동안 다른 작업이 시작되지 않는다. 받지 못하면 그 작업만 FAILED 이고 다음으로 간다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun assets_blockStartAndFailOnlyThatJob() = runTest {
        val gate = CompletableDeferred<Map<String, String>>()
        assets = { ids -> if ("sherpa-onnx-whisper-base" in ids) gate.await() else emptyMap() }
        val jobs = listOf(job("whisper-base"), job("vosk-small-ko"))
        var results: List<JobResult>? = null
        val run = launch { results = scheduler().run(plan(jobs, 1)) }
        advanceTimeBy(60_000); runCurrent()
        assertTrue("받는 동안 어떤 작업도 시작되면 안 된다: $started", started.isEmpty())
        assertEquals("ASSET", progress.last().waiting); assertEquals(1, progress.last().running.size)
        gate.complete(mapOf("sherpa-onnx-whisper-base" to "E-AS-01 TLS"))                // 받지 못했다
        run.join()
        assertEquals(listOf("vosk-small-ko"), started)                                 // 다음 작업은 돈다
        assertEquals(listOf(PlanProgress.FAILED, PlanProgress.DONE), results!!.map { it.status })
        assertTrue(results!![0].note.startsWith("ERR_ASSET:sherpa-onnx-whisper-base E-AS-01")); assertEquals(PlanProgress.FAILED, status(jobs[0]))
        assertEquals(RunProgress(total = 2, done = 1, failed = 1), progress.last())
    }

    /**
     * 목적: [UT-RUN-11] ML Kit 이 먹통이 되면 남은 ML Kit 작업만 건너뛴다. 발열 대기 표시. 출력 폴더 오류는 실행 전체를 멈춘다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun mlKitStuck_thermal_andFatalOutputError() = runTest {
        val jobs = listOf(job("google-mlkit"), job("whisper-base"), job("google-mlkit", "en"), job("vosk-small-ko"))
        outcome = { j -> delay(1_000); if (j.model.runtime == Runtime.MLKIT) JobResult(j, PlanProgress.FAILED, 2, 0, 2, 1.0, 0.0, "ERR_MLKIT_STUCK", mlKitStuck = true) else JobResult(j, PlanProgress.DONE, 1, 1, 0, 1.0, 5.0) }
        thermalLevel = 3                                                               // 처음에는 뜨겁다
        val run = launch { scheduler().run(plan(jobs, 1)) }
        advanceTimeBy(45_000); runCurrent()
        assertEquals("THERMAL", progress.last().waiting); assertTrue(started.isEmpty())
        thermalLevel = 0
        run.join()
        assertEquals(listOf("google-mlkit", "whisper-base", "vosk-small-ko"), started)   // 두 번째 ML Kit 은 시작하지 않는다
        assertEquals(listOf(PlanProgress.FAILED, PlanProgress.DONE, PlanProgress.SKIPPED, PlanProgress.DONE), jobs.map { status(it) })
        assertEquals(RunProgress(total = 4, done = 2, failed = 1, skipped = 1), progress.last())
        outcome = { throw AppError.OutputUnavailable("권한이 사라졌다") }
        try { scheduler().run(plan(jobs, 1).let { RunPlan(it.jobs, it.sources, it.params, restart = true) }); fail("E-OUT-01 이 올라와야 한다") }
        catch (e: AppError.OutputUnavailable) { assertEquals("E-OUT-01", e.code) }
        assertEquals(4, started.size)                                                  // 첫 작업에서 멈췄다
    }

    /**
     * 목적: [WP6 독립 검토 1·2] 작업 하나에서 예상하지 못한 예외나 (실행은 취소되지 않았는데) 취소가 나도 그 작업만 실패하고 실행은 계속된다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun unexpectedFailureOrStrayCancel_failsOnlyThatJob() = runTest {
        val jobs = listOf(job("whisper-base"), job("zipformer-ko"), job("vosk-small-ko"))
        outcome = { j ->
            delay(1_000)
            when (j.model.engine) {
                "whisper-base" -> throw IllegalStateException("뜻밖의 오류")
                "zipformer-ko" -> throw kotlinx.coroutines.CancellationException("안에서 난 취소")
                else -> JobResult(j, PlanProgress.DONE, 1, 1, 0, 1.0, 5.0)
            }
        }
        val results = scheduler().run(plan(jobs, 1))                                   // 멈추지 않고 끝나야 한다
        assertEquals(listOf(PlanProgress.FAILED, PlanProgress.FAILED, PlanProgress.DONE), results.map { it.status })
        assertTrue(results[0].note.startsWith("ERR_INTERNAL:IllegalStateException")); assertEquals("ERR_INTERNAL_CANCEL", results[1].note)
        assertEquals(listOf(PlanProgress.FAILED, PlanProgress.FAILED, PlanProgress.DONE), jobs.map { status(it) })
        assertEquals(RunProgress(total = 3, done = 1, failed = 2), progress.last())
    }
}
