/*
 * 파일명: run/JobPipelineTest.kt (app/src/test)
 * 목적 및 기능:
 * - 작업 1개의 판정 규칙을 PC(JVM)에서 가짜 엔진·가짜 저장소로 확인한다.
 *     · [UT-RUN-03] 3개 중 2번째가 실패 → 2번째만 ERROR(.ERROR.txt), 3번째는 정상, 작업은 DONE
 *     · [UT-RUN-04] 엔진 먹통이 연속 2건 → 남은 파일을 포기하고 FAILED(ERR_ENGINE_DEAD). ML Kit 은 ERR_MLKIT_STUCK + 건너뛰기 표시.
 *       한도를 넘긴 엔진은 1건으로 끝낸다. 먹통 사이에 성공이 끼면 계속한다
 *     · 적재 실패 → FAILED(ERR_MODEL_LOAD / ERR_VAD_LOAD / ERR_MLKIT_STUCK), 파일을 돌리지 않는다
 *     · [UT-RUN-02] 취소 → 지금까지의 CSV 를 쓰고 취소가 올라간다. 출력 폴더 오류는 그대로 올라간다. 어느 경우든 엔진을 해제한다
 *
 * add-hyungchul-20261106-2231 — [T6-2]
 */
package com.example.google_stt.run

import com.example.google_stt.data.saf.JobSink                     // 결과 받는 곳
import com.example.google_stt.domain.AppError                      // 오류
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec             // 모델 정의
import com.example.google_stt.domain.condition.FeedChoice          // 공급 속도
import com.example.google_stt.domain.condition.RunOptions          // 실행 옵션
import com.example.google_stt.domain.condition.RunPurpose          // 실행 목적
import com.example.google_stt.domain.csv.DeviceSample              // 기기 계측값
import com.example.google_stt.domain.csv.FileRecord                // 파일 기록
import com.example.google_stt.domain.csv.RunMetaInput              // run_meta 입력
import com.example.google_stt.domain.csv.SttOutcome                // 전사 결과
import com.example.google_stt.domain.csv.SttStatus                 // 상태
import com.example.google_stt.domain.lang.LangFolder               // 언어 폴더
import com.example.google_stt.domain.lang.LangSource               // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                      // 작업
import com.example.google_stt.domain.plan.PlanProgress             // 상태 이름
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋
import com.example.google_stt.domain.vad.VadSetting                // VAD 설정
import com.example.google_stt.engine.stt.AudioChunk                // STT 조각
import com.example.google_stt.engine.stt.LoadInfo                  // 적재 결과
import com.example.google_stt.engine.stt.SttEngineAdapter          // STT 엔진 약속
import java.io.File                                                // 모델 폴더
import java.io.IOException                                         // 엔진 오류
import kotlinx.coroutines.ExperimentalCoroutinesApi                // 가상 시간
import kotlinx.coroutines.awaitCancellation                        // 끝나지 않는 엔진
import kotlinx.coroutines.cancelAndJoin                            // 취소
import kotlinx.coroutines.launch                                   // 코루틴 시작
import kotlinx.coroutines.test.StandardTestDispatcher              // 시험 스케줄러
import kotlinx.coroutines.test.TestScope                           // 시험 범위
import kotlinx.coroutines.test.runCurrent                          // 밀린 일 돌리기
import kotlinx.coroutines.test.runTest                             // 코루틴 시험
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assert.fail                                       // 예외가 안 났을 때 실패
import org.junit.Test                                              // JUnit 4 시험 표시

/** 목적: JobPipeline 시험. */
@OptIn(ExperimentalCoroutinesApi::class)
class JobPipelineTest {

    private val events = ArrayList<String>()                       // 저장소·엔진에 일어난 일(순서대로)
    private var budgetOver = false                                 // 가짜 엔진의 한도 초과 표시
    private var loadError: Exception? = null                       // 적재 때 던질 오류
    private var vadError: Exception? = null                        // VAD 를 만들 때 던질 오류
    private var saveError: AppError? = null                        // 전사문 저장 때 던질 오류
    private var script: List<suspend () -> SttOutcome> = emptyList()   // 파일마다 엔진이 할 일
    private val okText: suspend () -> SttOutcome = { SttOutcome("글", 1, SttStatus.OK) }
    private val timeout: suspend () -> SttOutcome = { throw AppError.SttTimeout("무응답") }

    /** 가짜 결과 받는 곳 — 불린 일을 events 에 적는다 */
    private inner class FakeSink : JobSink {
        val records = ArrayList<FileRecord>()
        override fun saveText(relDir: List<String>, baseName: String, text: String) { saveError?.let { throw it }; events += "text:$baseName" }
        override fun saveError(relDir: List<String>, baseName: String, cause: String, partial: String) { events += "error:$baseName:$cause" }
        override fun add(record: FileRecord) { records += record }
        override fun flush() { events += "flush" }
        override fun writeRunMeta(input: RunMetaInput) { events += "meta:${input.fileCount}:${input.numThreads}:${input.runPurpose}" }
        override fun finish(): Boolean { events += "finish"; return true }
    }

    /** 가짜 STT 엔진 — 파일 순서대로 script 를 실행한다 */
    private inner class FakeAdapter(override val spec: ModelSpec) : SttEngineAdapter {
        var n = 0
        override val pastBudget: Boolean get() = budgetOver
        override suspend fun load(modelDir: File?, lang: Language): LoadInfo { loadError?.let { throw it }; return LoadInfo(0.5, "fake") }
        override suspend fun transcribe(chunks: List<AudioChunk>, feed: FeedChoice, lang: Language): SttOutcome = script[n++]()
        override fun close() { events += "close" }
    }

    private val sink = FakeSink()

    /**
     * 목적: 가짜로 채운 파이프라인으로 작업을 돌린다.
     * 입력: engine - 결과 엔진 이름, files - 음원 수 / 출력: events·sink / 리턴: JobResult
     */
    private suspend fun TestScope.run(engine: String = "whisper-base", files: Int = 3): JobResult {
        val spec = Catalog.model(engine)!!
        val d = StandardTestDispatcher(testScheduler)
        val deps = PipelineDeps(
            adapterFor = { FakeAdapter(it) }, modelDir = { File("/models/x") }, vadFor = { vadError?.let { e -> throw e }; null },
            denoiserFor = { error("쓰지 않는다") }, openSink = { sink }, meter = { { DeviceSample("S24", "dev") } },
            meta = { RunMetaExtras("4.0.0-dev", "dev", emptyMap(), emptyMap(), "download") }, now = { "t" },
            nanoTime = { testScheduler.currentTime * 1_000_000 }, compute = d, io = d,
        )
        val job = Job(LangFolder("kr", "id", Language.KO, LangSource.AUTO_SUBFOLDER), spec,
            VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap()), RunOptions().effectiveFor(spec.runtime))
        val sources = List(files) { i -> AudioSource(emptyList(), "0${i + 1}", ".wav", 10L) { Decoded(ByteArray(32_000), 16_000, 1) } }
        return JobPipeline(deps).execute(job, sources, RunParams("run1", "start", 1, RunPurpose.TIMING))
    }

    /**
     * 목적: [UT-RUN-03] 가운데 파일이 실패해도 그 파일만 ERROR 이고 작업은 끝까지 간다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun fileFailure_continuesAndFinishes() = runTest {
        script = listOf(okText, { throw IOException("엔진 오류") }, okText)
        val r = run()
        assertEquals(PlanProgress.DONE, r.status); assertEquals(3, r.files); assertEquals(2, r.ok); assertEquals(1, r.fail); assertEquals(3.0, r.audioSec, 1e-9)
        assertEquals(listOf("meta:3:4:timing", "text:01", "error:02:IOException: 엔진 오류", "text:03", "finish", "close"), events)
        assertEquals(listOf(SttStatus.OK, SttStatus.ERROR, SttStatus.OK), sink.records.map { it.stt.status })
        assertEquals(listOf(0.5, 0.0, 0.0), sink.records.map { it.modelLoadSec })       // 적재 시간은 첫 파일에만
        assertEquals("ERR_STT:IOException", sink.records[1].note); assertFalse(r.mlKitStuck)
    }

    /**
     * 목적: [UT-RUN-04] 엔진 먹통 — 연속 2건이면 남은 파일을 포기한다. 사이에 성공이 끼면 계속한다. 한도를 넘긴 엔진은 1건으로 끝낸다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun engineDead_abortsJob() = runTest {
        script = listOf(timeout, timeout, okText)
        var r = run()
        assertEquals(PlanProgress.FAILED, r.status); assertEquals("ERR_ENGINE_DEAD", r.note); assertEquals(2, r.files); assertFalse(r.mlKitStuck)
        assertEquals(listOf("meta:3:4:timing", "error:01:SttTimeout: E-EN-03: STT 시간 초과 — 무응답", "error:02:SttTimeout: E-EN-03: STT 시간 초과 — 무응답", "flush", "close"), events)   // finish 는 부르지 않는다
        events.clear()
        script = listOf(timeout, okText, timeout)
        assertEquals(PlanProgress.DONE, run().status)                                  // 연속이 아니면 계속한다
        events.clear()
        budgetOver = true; script = listOf(timeout, okText, okText)
        r = run("google-mlkit")
        assertEquals(PlanProgress.FAILED, r.status); assertEquals("ERR_MLKIT_STUCK", r.note); assertEquals(1, r.files); assertTrue(r.mlKitStuck)
        assertTrue(events[0].startsWith("meta:3:null"))                                // ML Kit 은 스레드 수를 모른다
    }

    /**
     * 목적: 적재 실패 — 파일을 돌리지 않고 FAILED 로 끝낸다. 무엇이 실패했는지 가린다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun loadFailure_failsWithoutFiles() = runTest {
        loadError = AppError.ModelLoad("파일이 없다")
        var r = run()
        assertEquals(PlanProgress.FAILED, r.status); assertTrue(r.note.startsWith("ERR_MODEL_LOAD:")); assertEquals(0, r.files); assertEquals(listOf("close"), events)
        loadError = AppError.MlKitStuck("응답 없음")
        r = run("google-mlkit")
        assertTrue(r.note.startsWith("ERR_MLKIT_STUCK:")); assertTrue(r.mlKitStuck)
        loadError = null; vadError = AppError.VadLoad("모델 없음")
        assertTrue(run().note.startsWith("ERR_VAD_LOAD:"))
    }

    /**
     * 목적: [UT-RUN-02] 취소 — 지금까지의 CSV 를 쓰고 취소가 올라간다. 출력 폴더 오류는 그대로 올라간다. 둘 다 엔진을 해제한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun cancelAndOutputError_propagate() = runTest {
        script = listOf(okText, { awaitCancellation() }, okText)
        var result: JobResult? = null
        val job = launch { result = run() }
        runCurrent()
        assertEquals("text:01", events.last())                                         // 2번째 파일의 엔진 안에서 멈춰 있다
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(null, result)
        assertEquals(listOf("meta:3:4:timing", "text:01", "flush", "close"), events)   // 마무리 쓰기 + 해제, finish 없음
        assertEquals(1, sink.records.size)                                             // 취소된 파일은 기록이 없다
        events.clear()
        script = listOf(okText); saveError = AppError.OutputUnavailable("권한이 사라졌다")
        try { run(files = 1); fail("E-OUT-01 이 올라와야 한다") } catch (e: AppError.OutputUnavailable) { assertEquals("E-OUT-01", e.code) }
        assertEquals("close", events.last())
    }
}
