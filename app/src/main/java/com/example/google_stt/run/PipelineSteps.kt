/*
 * 파일명: run/PipelineSteps.kt
 * 목적 및 기능:
 * - 음원 파일 1개를 처리하는 단계들과 그 순서를 둔다(4단계 5-1·5-2).
 *     decode → [denoise] → [vad] → pack → [epd] → transcribe → record
 *   v3 batch/BatchRunner.kt 의 파일별 처리 규칙을 옮긴 것이다:
 *     · 조각 상한 = min(구간 처리 상한, 모델 상한). 상한이 있으면 "원본 PCM + VAD 구간" 으로 조각을 다시 묶고(SegmentPacker),
 *       없으면(스트리밍·Vosk·ML Kit 의 "엔진별 최대 길이") VAD 가 이어 붙인 PCM 을 통째로 넣는다
 *     · EPD 는 상한이 없을 때만(ML Kit 61초 결함 회피) — 조건 자체는 RunOptions.effectiveFor 가 이미 걸렀다
 *     · VAD 가 말을 찾지 못하면 엔진을 부르지 않고 NO_MATCH(0바이트 TXT, note=VAD_NO_SPEECH — E-VAD-02)
 *     · ML Kit 앞부분 유실 의심·공급 막힘은 상태를 바꾸지 않고 note 에 남긴다(v3 는 화면 로그·별도 상태였다)
 * - 한 단계가 실패하면 그 파일은 ERROR 기록으로 끝난다(예외를 밖으로 던지지 않는다). **취소는 그대로 올린다** — 기록을 만들지 않는다.
 * - Android 에 기대는 일(디코딩·기기 계측·노이즈 저감)과 시계는 함수로 받는다 → PC(JVM)에서 가짜로 단계 순서·시간을 시험한다.
 *
 * add-hyungchul-20261106-2227 — [T6-1]
 */
package com.example.google_stt.run

import com.example.google_stt.data.saf.OutputLayout                  // 상대 폴더 표기
import com.example.google_stt.domain.AppError                        // 시간 초과 판정
import com.example.google_stt.domain.catalog.Runtime                 // ML Kit 판정
import com.example.google_stt.domain.condition.RunPurpose            // 실행 목적
import com.example.google_stt.domain.csv.AudioInfo                   // 음원 정보
import com.example.google_stt.domain.csv.DeviceSample                // 기기 계측값
import com.example.google_stt.domain.csv.FileRecord                  // 파일 기록(CSV 한 줄)
import com.example.google_stt.domain.csv.SttOutcome                  // 전사 결과
import com.example.google_stt.domain.csv.SttStatus                   // 상태
import com.example.google_stt.domain.csv.Timings                     // 단계별 시간
import com.example.google_stt.domain.csv.VadSummary                  // VAD 요약
import com.example.google_stt.domain.epd.EpdMode                     // EPD 방식
import com.example.google_stt.domain.epd.SpeechEpd                   // EPD 계산
import com.example.google_stt.domain.plan.Job                        // 작업
import com.example.google_stt.domain.vad.SegmentPacker               // 조각 묶기
import com.example.google_stt.domain.vad.VadSegment                  // 말 구간
import com.example.google_stt.engine.stt.AudioChunk                  // STT 조각
import com.example.google_stt.engine.stt.BYTES_PER_SEC               // 1초의 바이트 수
import com.example.google_stt.engine.stt.FEED_STALL_WARN_RATIO       // 공급 막힘 기준
import com.example.google_stt.engine.stt.LoadInfo                    // 모델 적재 결과
import com.example.google_stt.engine.stt.MLKIT_SEGMENT_TRUNCATION_SEC    // 61초 결함 경계
import com.example.google_stt.engine.stt.MLKIT_TRUNCATION_CHARS_PER_SEC  // 유실 의심 기준
import com.example.google_stt.engine.stt.SttEngineAdapter            // STT 엔진
import com.example.google_stt.engine.stt.expectedFeedMs              // 이론 공급 시간
import com.example.google_stt.engine.vad.VadDetector                 // VAD 실행기
import com.example.google_stt.engine.vad.VadRunner                   // VAD 실행
import kotlinx.coroutines.CancellationException                      // 취소
import kotlinx.coroutines.CoroutineDispatcher                        // 계산 스레드 지정
import kotlinx.coroutines.Dispatchers                                // 기본 계산 스레드
import kotlinx.coroutines.withContext                                // 스레드 바꾸기

/**
 * 목적: 디코딩 결과.
 * 필드: pcm - 16 kHz / mono / PCM16 LE, sampleRate / channels - 원본의 표본화 주파수·채널 수(모르면 0)
 */
class Decoded(val pcm: ByteArray, val sampleRate: Int, val channels: Int)

/**
 * 목적: 처리할 음원 1개.
 * 필드: relDir - 언어 폴더 아래 상대 폴더 조각, baseName - 확장자를 뺀 이름, ext - 점 포함 확장자, bytes - 파일 크기,
 *       decode - 이 음원을 디코딩하는 함수(앱에서는 AudioDecoder — 위치는 함수가 안다)
 */
class AudioSource(
    val relDir: List<String>,            // 상대 폴더
    val baseName: String,                // 이름
    val ext: String,                     // 확장자
    val bytes: Long,                     // 크기
    val decode: suspend () -> Decoded,   // 디코딩
)

/** 목적: 기기 계측. start 를 부르면 재기 시작하고, 돌려받은 함수를 부르면 그 사이의 변화량을 준다(앱에서는 DeviceProbe.begin / end). */
fun interface DeviceMeter {
    fun start(): () -> DeviceSample
}

/**
 * 목적: 작업 1개 동안 바뀌지 않는 것(파일마다 같이 쓴다).
 * 필드: job - 작업, runId - 실행 ID, adapter - 적재를 마친 STT 엔진, load - 적재 결과, vad - VAD 실행기(안 쓰면 null),
 *       denoise - 노이즈 저감 함수(안 쓰면 null), meter - 기기 계측, workers - 동시 작업 수, purpose - 실행 목적,
 *       now - 지금 시각 글자, nanoTime - 시계(ns), compute - 계산을 돌릴 스레드, downloadOverlap - 지금 다운로드가 겹쳤는지, log - 로그
 */
class JobContext(
    val job: Job,
    val runId: String,
    val adapter: SttEngineAdapter,
    val load: LoadInfo,
    val vad: VadDetector?,
    val denoise: ((ByteArray) -> ByteArray)?,
    val meter: DeviceMeter,
    val workers: Int,
    val purpose: RunPurpose,
    val now: () -> String,
    val nanoTime: () -> Long = System::nanoTime,
    val compute: CoroutineDispatcher = Dispatchers.Default,
    val downloadOverlap: () -> Boolean = { false },
    val log: (String) -> Unit = {},
)

/**
 * 목적: 파일 1개의 처리 결과.
 * 필드: record - CSV 에 들어갈 기록, error - 실패했으면 그 원인(성공·무음이면 null)
 */
class FileResult(val record: FileRecord, val error: Throwable?)

/** 목적: 파일 처리 단계. */
object PipelineSteps {

    const val NOTE_VAD_NO_SPEECH = "VAD_NO_SPEECH"            // VAD 가 말을 찾지 못함(E-VAD-02)
    const val NOTE_TRUNCATED = "TRUNCATED_SUSPECT"            // ML Kit 앞부분 유실 의심(61초 결함)
    const val NOTE_FEED_STALL = "FEED_STALL"                  // 공급이 이론값의 2배 넘게 걸림 — 시간 값을 믿지 말 것
    const val NOTE_NET_DELTA = "WARN_NET_DELTA"               // 처리 중 앱의 네트워크 송수신이 있었다(E-RUN-06)

    /** 단계 사이에 넘기는 값 */
    private class Stage(var pcm: ByteArray) {
        var segments: List<VadSegment>? = null                // VAD 구간(패딩 후). VAD 를 안 쓰면 null
        var seams: List<Int> = emptyList()                    // EPD 절단 후보
        var sttPcm: ByteArray = pcm                           // STT 에 들어갈 PCM(조각을 묶기 전 기준)
        var chunks: List<ByteArray> = emptyList()             // STT 조각
        var cap: Double? = null                               // 조각 상한(초). 없으면 null
        var vad: VadSummary? = null                           // VAD 요약
        var noSpeech = false                                  // VAD 가 말을 찾지 못함
    }

    /**
     * 목적: 파일 1개를 끝까지 처리한다.
     * 입력: ctx - 작업 동안의 것, src - 음원, first - 이 작업의 첫 파일인지(모델 적재 시간을 이 행에만 적는다)
     * 출력: 없음(파일 쓰기는 부르는 쪽이 한다)
     * 리턴: FileResult - 실패해도 ERROR 기록으로 돌려준다
     * 예외: 취소되면 CancellationException(기록을 만들지 않는다)
     */
    suspend fun runFile(ctx: JobContext, src: AudioSource, first: Boolean): FileResult {
        val t0 = ctx.nanoTime()
        val startedAt = ctx.now()
        val stopMeter = ctx.meter.start()                                      // 기기 계측 시작
        var audio = AudioInfo(src.ext, src.bytes, 0.0, 0, 0)                   // 디코딩 전에는 길이를 모른다
        var decodeSec = 0.0; var nsSec = 0.0; var vadSec = 0.0                 // 단계별 시간
        var transcribeSec: Double? = null                                      // 엔진을 부르지 못하면 null
        var stt = SttOutcome("", 0, SttStatus.ERROR)                           // 끝까지 가지 못하면 ERROR
        var stage: Stage? = null
        val notes = ArrayList<String>()
        var error: Throwable? = null
        var step = "DECODE"                                                    // 지금 단계(오류 표기에 쓴다)
        try {
            var t = ctx.nanoTime()
            val decoded = decode(src)
            decodeSec = sec(t, ctx.nanoTime())
            audio = AudioInfo(src.ext, src.bytes, decoded.pcm.size / BYTES_PER_SEC.toDouble(), decoded.sampleRate, decoded.channels)
            val s = Stage(decoded.pcm).also { stage = it }
            if (ctx.denoise != null) {
                step = "NS"; t = ctx.nanoTime()
                denoise(ctx, s)
                nsSec = sec(t, ctx.nanoTime())
            }
            if (ctx.vad != null) {
                step = "VAD"; t = ctx.nanoTime()
                vad(ctx, s)
                vadSec = sec(t, ctx.nanoTime())
            }
            if (s.noSpeech) {
                stt = SttOutcome("", 0, SttStatus.NO_MATCH)                    // 엔진을 부르지 않는다
                notes += NOTE_VAD_NO_SPEECH
            } else {
                step = "PACK"; pack(ctx, s)
                step = "EPD"; epd(ctx, s)
                step = "STT"; t = ctx.nanoTime()
                stt = transcribe(ctx, s)
                transcribeSec = sec(t, ctx.nanoTime())
                notes += judge(ctx, s, stt)
            }
        } catch (e: CancellationException) {
            throw e                                                            // 취소는 ERROR 행으로 바꾸지 않는다
        } catch (e: Exception) {
            error = e
            notes += if (e is AppError.SttTimeout) "ERR_TIMEOUT" else "ERR_$step:${e.javaClass.simpleName}"   // 자세한 원인은 .ERROR.txt 에
        } catch (e: OutOfMemoryError) {                                        // E-EN-06: 파일 단위에서만 잡는다
            error = e
            notes += "ERR_OOM"
        }
        val device = stopMeter()
        if ((device.netRxDelta ?: 0L) > 0L || (device.netTxDelta ?: 0L) > 0L) notes += NOTE_NET_DELTA
        val st = stage
        val timings = Timings(decodeSec, nsSec, vadSec, transcribeSec, sec(t0, ctx.nanoTime()))
        return FileResult(record(ctx, src, first, startedAt, audio, timings, stt, st, device, notes), error)
    }

    /** 목적: 디코딩. 입력: src - 음원 / 출력: 없음 / 리턴: Decoded(실패하면 예외) */
    private suspend fun decode(src: AudioSource): Decoded = src.decode()

    /** 목적: 노이즈 저감. 입력: ctx, s - 단계 값 / 출력: s.pcm·s.sttPcm 이 처리된 PCM 으로 바뀐다 / 리턴: 없음 */
    private suspend fun denoise(ctx: JobContext, s: Stage) {
        s.pcm = withContext(ctx.compute) { ctx.denoise!!(s.pcm) }
        s.sttPcm = s.pcm
    }

    /** 목적: VAD. 입력: ctx, s / 출력: s 에 구간·이어 붙인 PCM·요약이 들어간다. 말이 없으면 s.noSpeech / 리턴: 없음 */
    private suspend fun vad(ctx: JobContext, s: Stage) {
        val r = withContext(ctx.compute) { VadRunner.process(ctx.vad!!, s.pcm, ctx.job.vad) }
        s.segments = r.segments
        s.seams = r.seams
        s.sttPcm = r.pcm                                                       // 말 구간만 이어 붙인 PCM
        s.vad = VadSummary(r.segments.size, r.outputSec)
        s.noSpeech = r.segments.isEmpty() || r.pcm.isEmpty()
    }

    /**
     * 목적: STT 조각을 만든다.
     * 입력: ctx, s / 출력: s.chunks·s.cap, 그리고 상한이 있으면 VAD 요약의 남은 길이가 조각 합으로 바뀐다 / 리턴: 없음
     */
    private suspend fun pack(ctx: JobContext, s: Stage) {
        val seg = ctx.job.opt.seg.maxSec                                       // 구간 처리 상한(엔진별 최대 길이면 null)
        val model = ctx.job.model.maxChunkSec?.toDouble()                      // 모델 상한(스트리밍·Vosk·ML Kit 은 null)
        s.cap = if (seg != null && model != null) minOf(seg, model) else seg ?: model
        val cap = s.cap
        if (cap == null || s.sttPcm.isEmpty()) {
            s.chunks = listOf(s.sttPcm)                                        // 통째로 1개
            return
        }
        val packed = withContext(ctx.compute) { SegmentPacker.pack(s.pcm, s.segments, cap, ctx.job.vad.joinMs) }
        s.chunks = packed.chunks
        val keptSec = packed.chunks.sumOf { it.size } / BYTES_PER_SEC.toDouble()   // 실제 STT 입력 길이(조각 합)
        s.vad = s.vad?.copy(keptSec = keptSec)
    }

    /** 목적: EPD(상한이 없을 때만). 입력: ctx, s / 출력: s.chunks 가 나뉘거나 무음이 들어간 1개로 바뀐다 / 리턴: 없음 */
    private suspend fun epd(ctx: JobContext, s: Stage) {
        val cfg = ctx.job.opt.epd.config
        if (s.cap != null || !ctx.job.opt.epd.enabled || cfg == null || s.sttPcm.isEmpty()) return
        val plan = withContext(ctx.compute) { SpeechEpd.plan(s.sttPcm, s.seams, cfg) }
        when (cfg.mode) {
            EpdMode.SPLIT_SESSION -> s.chunks = SpeechEpd.split(s.sttPcm, plan)    // 조각마다 세션
            EpdMode.LONG_SILENCE -> {
                val ins = SpeechEpd.insertLongSilence(s.sttPcm, plan, cfg.longSilenceMs)
                s.sttPcm = ins.pcm                                             // STT 입력 자체가 길어진다
                s.chunks = listOf(ins.pcm)
                s.vad = s.vad?.copy(keptSec = ins.pcm.size / BYTES_PER_SEC.toDouble())
            }
            EpdMode.OFF -> Unit
        }
        if (plan.searchedCuts > 0) ctx.log("[epd][주의] VAD 무음 후보가 없어 말 도중에서 잘렸을 수 있다(역방향 탐색 ${plan.searchedCuts}회)")
    }

    /** 목적: 전사. 입력: ctx, s / 출력: 없음 / 리턴: SttOutcome(엔진 오류는 예외로) */
    private suspend fun transcribe(ctx: JobContext, s: Stage): SttOutcome =
        ctx.adapter.transcribe(s.chunks.map { AudioChunk(it) }, ctx.job.opt.feed, ctx.job.language)

    /**
     * 목적: 전사 결과에 붙일 주의 표시를 정한다(v3 의 판정 규칙).
     * 입력: ctx, s, stt - 전사 결과 / 출력: 로그 / 리턴: List<String> - note 조각들
     */
    private fun judge(ctx: JobContext, s: Stage, stt: SttOutcome): List<String> {
        val out = ArrayList<String>()
        val inBytes = s.chunks.sumOf { it.size }
        val inSec = inBytes / BYTES_PER_SEC.toDouble()                          // STT 입력 길이
        if (stt.note.isNotEmpty()) out += stt.note                              // 엔진이 남긴 말(NO_SPEECH_DETECTED 등)
        // ML Kit: 61초 넘는 입력을 세션 하나로 넣었는데 글자가 너무 적으면 앞부분이 버려진 것으로 의심한다
        if (ctx.job.model.runtime == Runtime.MLKIT && s.cap == null && stt.status == SttStatus.OK && stt.utterances == 1 &&
            inSec > MLKIT_SEGMENT_TRUNCATION_SEC && stt.text.length / inSec < MLKIT_TRUNCATION_CHARS_PER_SEC
        ) {
            out += NOTE_TRUNCATED
            ctx.log("[warn] 앞부분 유실 의심 — 입력 ${"%.1f".format(inSec)}s 인데 ${stt.text.length}자. 이 행의 WER/CER 은 그대로 쓰지 말 것")
        }
        val delay = ctx.job.opt.feed.delayMs
        val feedSec = stt.feedSec
        if (delay != null && feedSec != null) {
            val expected = expectedFeedMs(inBytes, delay)                       // 이론 공급 시간(ms)
            if (expected > 0 && feedSec * 1000.0 / expected > FEED_STALL_WARN_RATIO) {
                out += NOTE_FEED_STALL
                ctx.log("[warn] 공급이 막혔다 — feed ${feedSec}s(이론 ${expected / 1000.0}s). 이 행의 시간 값은 믿지 말 것")
            }
        }
        return out
    }

    /**
     * 목적: 파일 기록을 만든다.
     * 입력: 단계에서 모은 값들 / 출력: 없음 / 리턴: FileRecord
     */
    private fun record(
        ctx: JobContext, src: AudioSource, first: Boolean, startedAt: String, audio: AudioInfo, timings: Timings,
        stt: SttOutcome, s: Stage?, device: DeviceSample, notes: List<String>,
    ): FileRecord {
        val chunks = s?.chunks.orEmpty().filter { it.isNotEmpty() }
        return FileRecord(
            runId = ctx.runId,
            startedAt = startedAt,
            job = ctx.job,
            relDir = OutputLayout.relDirText(src.relDir),
            file = src.baseName,
            audio = audio,
            timings = timings,
            stt = stt,
            vad = s?.vad,
            device = device,
            engineVersion = ctx.load.engineVersion,
            workers = ctx.workers,
            modelLoadSec = if (first) ctx.load.loadSec else 0.0,                // 작업의 첫 파일에만 적는다
            chunks = chunks.size,
            chunkMaxSec = chunks.maxOfOrNull { it.size / BYTES_PER_SEC.toDouble() },
            purpose = ctx.purpose.name.lowercase(),
            downloadOverlap = ctx.downloadOverlap(),
            note = notes.joinToString(";"),
        )
    }

    /** 목적: 두 시각 사이를 초로 바꾼다. 입력: fromNs, toNs / 출력: 없음 / 리턴: Double */
    private fun sec(fromNs: Long, toNs: Long): Double = (toNs - fromNs) / 1e9
}
