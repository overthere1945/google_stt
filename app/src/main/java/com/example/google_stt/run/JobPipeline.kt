/*
 * 파일명: run/JobPipeline.kt
 * 목적 및 기능:
 * - 작업 1개(언어 폴더 × 모델 × 조건)를 끝까지 돌린다(4단계 5-2).
 *     결과 폴더 "_<이름>" 준비 → 모델·VAD·노이즈 저감 적재(1회) → run_meta → 파일마다 PipelineSteps.runFile → TXT / ERROR.txt 저장,
 *     기록 누적(10개마다 CSV) → 모델 해제 → CSV 마지막 쓰기, "_<이름>" → "<이름>"
 * - 판정(v3 BatchRunner·6단계 6-2)
 *     · 적재 실패 → 작업 FAILED(ERR_MODEL_LOAD / ERR_VAD_LOAD / ERR_NS_LOAD). ML Kit 먹통이면 ERR_MLKIT_STUCK
 *     · 파일 실패 → 그 파일만 ERROR(.ERROR.txt), 다음 파일로 간다
 *     · 엔진 먹통(isEngineDead)이 연속 2건이거나, 엔진이 한도를 넘긴 뒤 1건이면 → 남은 파일을 포기하고 작업 FAILED
 *       (ERR_ENGINE_DEAD, ML Kit 이면 ERR_MLKIT_STUCK). 결과 폴더는 "_" 그대로 둔다(이어 하기 때 다시 돈다)
 *     · 취소 → 지금까지의 CSV 를 쓰고 취소를 그대로 올린다(부르는 쪽이 INTERRUPTED 로 적는다)
 *     · 출력 폴더 오류(E-OUT-01·02)는 잡지 않는다 → 실행 전체를 멈춰야 하므로 그대로 올린다
 * - 엔진·VAD·저장·기기 계측을 만드는 일은 PipelineDeps 의 함수로 받는다 → PC(JVM)에서 가짜로 판정 규칙을 시험한다.
 *
 * add-hyungchul-20261106-2231 — [T6-2]
 */
package com.example.google_stt.run

import com.example.google_stt.data.saf.JobSink                       // 결과 받는 곳
import com.example.google_stt.domain.AppError                        // 오류 분류
import com.example.google_stt.domain.catalog.ModelSpec               // 모델 정의
import com.example.google_stt.domain.catalog.Runtime                 // 런타임(스레드 수·ML Kit 판정)
import com.example.google_stt.domain.condition.RunPurpose            // 실행 목적
import com.example.google_stt.domain.csv.RunMetaInput                // run_meta 입력
import com.example.google_stt.domain.csv.SttStatus                   // 상태
import com.example.google_stt.domain.plan.Job                        // 작업
import com.example.google_stt.domain.plan.PlanProgress               // 작업 상태 이름
import com.example.google_stt.domain.vad.VadEngine                   // VAD 엔진
import com.example.google_stt.engine.stt.CONSECUTIVE_TIMEOUT_ABORT   // 연속 먹통 기준(2)
import com.example.google_stt.engine.stt.LoadInfo                    // 적재 결과
import com.example.google_stt.engine.stt.SherpaOfflineAdapter        // 스레드 수
import com.example.google_stt.engine.stt.SttEngineAdapter            // STT 엔진
import com.example.google_stt.engine.stt.VoskAdapter                 // 스레드 수
import com.example.google_stt.engine.stt.describe                    // 예외를 한 줄로
import com.example.google_stt.engine.stt.isEngineDead                // 먹통 판정
import com.example.google_stt.engine.vad.VadDetector                 // VAD 실행기
import java.io.File                                                  // 모델 폴더
import kotlinx.coroutines.CancellationException                      // 취소
import kotlinx.coroutines.CoroutineDispatcher                        // 스레드 지정
import kotlinx.coroutines.Dispatchers                                // 기본 스레드
import kotlinx.coroutines.NonCancellable                             // 마무리 쓰기
import kotlinx.coroutines.withContext                                // 스레드 바꾸기

/** 목적: 노이즈 저감기. 필드: run - PCM 을 처리하는 함수, close - 해제 */
class Denoiser(val run: (ByteArray) -> ByteArray, val close: () -> Unit)

/**
 * 목적: run_meta 에 넣을, 작업 밖에서 오는 값.
 * 필드: appVersion - 앱 판, deviceKey - 기기 식별, env - 기기·런타임 값(DeviceProbe.envMeta), assetSha256 - 자산 ID → 해시 요약,
 *       downloadSource - 자산을 구한 방법(download / import / mixed)
 */
class RunMetaExtras(
    val appVersion: String,
    val deviceKey: String,
    val env: Map<String, String>,
    val assetSha256: Map<String, String>,
    val downloadSource: String,
)

/**
 * 목적: 파이프라인이 바깥에서 받는 것들(앱에서는 AppContainer 가 채운다).
 * 필드: adapterFor - 모델의 STT 어댑터를 만든다, modelDir - 모델의 자산 폴더(ML Kit 은 null), vadFor - VAD 실행기를 만든다(NONE 은 null),
 *       denoiserFor - 노이즈 저감기를 만든다(조건 이름의 ns 폴더 조각으로), openSink - 작업의 결과 받는 곳을 연다,
 *       meter - 기기 계측, meta - run_meta 용 값, now - 지금 시각 글자, nanoTime - 시계, compute / io - 계산·파일 스레드, log - 로그
 */
class PipelineDeps(
    val adapterFor: (ModelSpec) -> SttEngineAdapter,
    val modelDir: (ModelSpec) -> File?,
    val vadFor: (VadEngine) -> VadDetector?,
    val denoiserFor: (String) -> Denoiser,
    val openSink: (Job) -> JobSink,
    val meter: DeviceMeter,
    val meta: (Job) -> RunMetaExtras,
    val now: () -> String,
    val nanoTime: () -> Long = System::nanoTime,
    val compute: CoroutineDispatcher = Dispatchers.Default,
    val io: CoroutineDispatcher = Dispatchers.IO,
    val log: (String) -> Unit = {},
)

/**
 * 목적: 실행 1회 동안 모든 작업에 같은 값.
 * 필드: runId - 실행 ID, startedAt - 실행 시작 시각, workers - 동시 작업 수, purpose - 실행 목적, downloadOverlap - 지금 다운로드가 돌고 있는지
 */
class RunParams(
    val runId: String,
    val startedAt: String,
    val workers: Int,
    val purpose: RunPurpose,
    val downloadOverlap: () -> Boolean = { false },
)

/**
 * 목적: 작업 1개의 결과.
 * 필드: job - 작업, status - PlanProgress 의 상태 이름(DONE / FAILED), files / ok / fail - 처리한 파일 수·성공(무음 포함)·실패,
 *       elapsedSec - 작업 소요(적재 포함), audioSec - 처리한 음원 길이 합, note - 사유, mlKitStuck - ML Kit 이 먹통이라 남은 ML Kit 작업을 건너뛰어야 하는지(D29)
 */
class JobResult(
    val job: Job,
    val status: String,
    val files: Int,
    val ok: Int,
    val fail: Int,
    val elapsedSec: Double,
    val audioSec: Double,
    val note: String = "",
    val mlKitStuck: Boolean = false,
)

/**
 * 목적: 작업 실행기.
 * 입력(생성자): deps - 바깥에서 받는 것들
 */
class JobPipeline(private val deps: PipelineDeps) {

    /**
     * 목적: 작업 1개를 돌린다.
     * 입력: job - 작업, sources - 그 언어 폴더의 음원들(처리 순서), run - 실행 1회의 값
     * 출력: 결과 폴더·TXT·CSV·run_meta 가 쓰인다
     * 리턴: JobResult(DONE / FAILED)
     * 예외: 취소되면 CancellationException(지금까지의 CSV 는 쓴다), 출력 폴더를 쓸 수 없으면 AppError.OutputUnavailable / OutputNoSpace
     */
    suspend fun execute(job: Job, sources: List<AudioSource>, run: RunParams): JobResult {
        val t0 = deps.nanoTime()
        val sink = withContext(deps.io) { deps.openSink(job) }                     // 작업 중 결과 폴더 "_<이름>"
        val adapter = deps.adapterFor(job.model)
        var vad: VadDetector? = null
        var denoiser: Denoiser? = null
        var ok = 0; var fail = 0; var audioSec = 0.0
        // 목적: 결과를 만든다. 입력: status - 상태, note - 사유, stuck - ML Kit 먹통 여부 / 리턴: JobResult
        fun result(status: String, note: String = "", stuck: Boolean = false) =
            JobResult(job, status, ok + fail, ok, fail, (deps.nanoTime() - t0) / 1e9, audioSec, note, stuck)
        try {
            // ── 적재(작업마다 1회) ──
            val load: LoadInfo
            var what = "MODEL"                                                     // 지금 적재 중인 것(오류 표기에 쓴다)
            try {
                load = adapter.load(deps.modelDir(job.model), job.language)
                what = "VAD"
                vad = withContext(deps.compute) { deps.vadFor(job.vad.engine) }
                what = "NS"
                denoiser = job.opt.ns.folder?.let { folder -> withContext(deps.compute) { deps.denoiserFor(folder) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = if (e is AppError.MlKitStuck) "ERR_MLKIT_STUCK" else "ERR_${what}_LOAD"   // ERR_MODEL_LOAD / ERR_VAD_LOAD / ERR_NS_LOAD
                deps.log("[${job.label}] 적재 실패: ${describe(e)}")
                return result(PlanProgress.FAILED, "$code:${e.message}", stuck = e is AppError.MlKitStuck)
            } catch (e: OutOfMemoryError) {  // change-hyungchul-20261109-1016 : 9단계 통합 검토 9 — 적재 중 메모리 부족·네이티브 라이브러리 오류는 이 작업만 실패시킨다(앱이 죽지 않게)
                deps.log("[${job.label}] 적재 실패(메모리 부족): ${e.message}")
                return result(PlanProgress.FAILED, "ERR_${what}_LOAD:OutOfMemoryError")
            } catch (e: LinkageError) {
                deps.log("[${job.label}] 적재 실패(라이브러리): ${e.javaClass.simpleName}: ${e.message}")
                return result(PlanProgress.FAILED, "ERR_${what}_LOAD:${e.javaClass.simpleName}")
            }
            val ctx = JobContext(
                job = job, runId = run.runId, adapter = adapter, load = load, vad = vad, denoise = denoiser?.run, meter = deps.meter,
                workers = run.workers, purpose = run.purpose, now = deps.now, nanoTime = deps.nanoTime, compute = deps.compute,
                downloadOverlap = run.downloadOverlap, log = deps.log,
            )
            withContext(deps.io) { sink.writeRunMeta(runMeta(job, sources.size, load, run)) }   // 중단돼도 조건 기록은 남게 먼저 쓴다
            deps.log("[${job.label}] 시작: 파일 ${sources.size}개, 적재 ${"%.2f".format(load.loadSec)}초 (${load.engineVersion})")

            // ── 파일마다 ──
            var consecutiveDead = 0                                                // 연속 먹통 수
            for ((i, src) in sources.withIndex()) {
                val r = PipelineSteps.runFile(ctx, src, first = i == 0)
                val err = r.error
                withContext(deps.io) {
                    if (err == null) sink.saveText(src.relDir, src.baseName, r.record.stt.text)   // 성공·무음(0바이트)
                    else sink.saveError(src.relDir, src.baseName, describe(err))                  // <이름>.ERROR.txt (D28)
                    sink.add(r.record)                                             // 10개마다 CSV 를 다시 쓴다
                }
                audioSec += r.record.audio.sec
                if (r.record.stt.status == SttStatus.ERROR) fail++ else ok++
                deps.log("[${job.label}] ${i + 1}/${sources.size} ${src.baseName}: ${r.record.stt.status}" +
                    (if (err != null) " — ${describe(err)}" else " (${r.record.stt.text.length}자, ${"%.1f".format(r.record.timings.elapsedSec)}초)"))
                if (err != null && isEngineDead(err)) consecutiveDead++ else consecutiveDead = 0
                if (err != null && isEngineDead(err) && (adapter.pastBudget || consecutiveDead >= CONSECUTIVE_TIMEOUT_ABORT)) {
                    val mlKit = job.model.runtime == Runtime.MLKIT
                    deps.log("[${job.label}] 엔진이 응답하지 않는다(연속 ${consecutiveDead}건). 남은 ${sources.size - i - 1}개를 포기한다")
                    withContext(deps.io) { sink.flush() }                          // 여기까지의 결과는 남긴다
                    return result(PlanProgress.FAILED, if (mlKit) "ERR_MLKIT_STUCK" else "ERR_ENGINE_DEAD", stuck = mlKit)
                }
            }
            val renamed = withContext(NonCancellable + deps.io) { sink.finish() }  // CSV 마지막 쓰기 + "_" 떼기   // change-hyungchul-20261106-2304 : WP6 독립 검토 8 — 이름을 바꾼 직후 취소돼도 DONE 으로 끝나게
            if (!renamed) deps.log("[${job.label}] 결과 폴더 이름을 바꾸지 못했다(같은 이름이 이미 있다) — \"_\" 폴더로 남는다")
            return result(PlanProgress.DONE, if (renamed) "" else "WARN_RENAME")
        } catch (e: CancellationException) {
            withContext(NonCancellable + deps.io) {                                // 마무리 쓰기는 취소 중에도 한다
                try { sink.flush() } catch (w: AppError) { deps.log("[${job.label}] 중지 중 CSV 쓰기 실패: ${w.message}") }   // 취소를 덮어쓰지 않는다
            }
            throw e
        } finally {
            adapter.close()                                                        // 모델 해제(취소·실패에도)
            vad?.close()
            denoiser?.close?.invoke()
        }
    }

    /**
     * 목적: run_meta 입력을 만든다.
     * 입력: job - 작업, fileCount - 음원 수, load - 적재 결과, run - 실행 값 / 출력: 없음 / 리턴: RunMetaInput
     */
    private fun runMeta(job: Job, fileCount: Int, load: LoadInfo, run: RunParams): RunMetaInput {
        val x = deps.meta(job)
        val threads = when (job.model.runtime) {
            Runtime.SHERPA_OFFLINE, Runtime.SHERPA_STREAMING -> SherpaOfflineAdapter.NUM_THREADS
            Runtime.VOSK -> VoskAdapter.NUM_THREADS
            Runtime.MLKIT -> null                                                  // 시스템 서비스가 돌린다(알 수 없다)
        }
        return RunMetaInput(
            job = job, runStartedAt = run.startedAt, appVersion = x.appVersion, fileCount = fileCount,
            engineVersion = load.engineVersion, modelLoadSec = load.loadSec, numThreads = threads,
            modelDir = deps.modelDir(job.model)?.path ?: "", parallelJobs = run.workers, runPurpose = run.purpose.name.lowercase(),
            downloadSource = x.downloadSource, deviceKey = x.deviceKey, assetSha256 = x.assetSha256, env = x.env,
        )
    }
}
