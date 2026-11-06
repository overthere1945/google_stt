/*
 * 파일명: run/PipelineStepsTest.kt (app/src/test)
 * 목적 및 기능:
 * - 파일 1개의 처리 단계를 PC(JVM)에서 가짜 엔진·가짜 VAD·가상 시간으로 확인한다.
 *     · [UT-RUN-01] 단계 순서 decode → ns → vad → (pack) → transcribe, 시간 열이 각 단계 합과 맞는다
 *     · 조각: 상한이 있으면 원본 + VAD 구간으로 다시 묶고, 없으면 VAD 가 이어 붙인 PCM 1개
 *     · VAD 가 말을 찾지 못하면 엔진을 부르지 않고 NO_MATCH + VAD_NO_SPEECH
 *     · 단계 실패는 ERROR 기록(ERR_<단계> / ERR_TIMEOUT), [UT-RUN-02] 취소는 기록 없이 그대로 올라간다
 *     · 공급 막힘·네트워크 변화는 note 에 남는다
 *
 * add-hyungchul-20261106-2228 — [T6-1]
 */
package com.example.google_stt.run

import com.example.google_stt.domain.AppError                      // 시간 초과
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec             // 모델 정의
import com.example.google_stt.domain.condition.FeedChoice          // 공급 속도
import com.example.google_stt.domain.condition.RunOptions          // 실행 옵션
import com.example.google_stt.domain.condition.RunPurpose          // 실행 목적
import com.example.google_stt.domain.csv.DeviceSample              // 기기 계측값
import com.example.google_stt.domain.csv.SttOutcome                // 전사 결과
import com.example.google_stt.domain.csv.SttStatus                 // 상태
import com.example.google_stt.domain.lang.LangFolder               // 언어 폴더
import com.example.google_stt.domain.lang.LangSource               // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                      // 작업
import com.example.google_stt.domain.vad.FrameSegmenter            // 구간 결과
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋
import com.example.google_stt.domain.vad.VadSegment                // 말 구간
import com.example.google_stt.domain.vad.VadSetting                // VAD 설정
import com.example.google_stt.engine.stt.AudioChunk                // STT 조각
import com.example.google_stt.engine.stt.LoadInfo                  // 적재 결과
import com.example.google_stt.engine.stt.SttEngineAdapter          // STT 엔진 약속
import com.example.google_stt.engine.vad.VadDetector               // VAD 실행기 약속
import java.io.File                                                // 모델 폴더(쓰지 않음)
import java.io.IOException                                         // 엔진 오류
import kotlinx.coroutines.CancellationException                    // 취소
import kotlinx.coroutines.ExperimentalCoroutinesApi                // 가상 시간
import kotlinx.coroutines.awaitCancellation                        // 끝나지 않는 엔진
import kotlinx.coroutines.cancelAndJoin                            // 취소
import kotlinx.coroutines.delay                                    // 가짜 소요 시간
import kotlinx.coroutines.launch                                   // 코루틴 시작
import kotlinx.coroutines.test.StandardTestDispatcher              // 시험 스케줄러
import kotlinx.coroutines.test.TestScope                           // 시험 범위
import kotlinx.coroutines.test.runCurrent                          // 밀린 일 돌리기
import kotlinx.coroutines.test.runTest                             // 코루틴 시험
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertNull                                 // null 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Test                                              // JUnit 4 시험 표시

/** 목적: PipelineSteps 시험. */
@OptIn(ExperimentalCoroutinesApi::class)
class PipelineStepsTest {

    private val calls = ArrayList<String>()                        // 단계가 불린 순서
    private var fed: List<Int> = emptyList()                       // 엔진에 들어간 조각 크기들
    private var feedSeen: FeedChoice? = null                       // 엔진이 받은 공급 속도
    private val pcm = ByteArray(32_000 * 40)                       // 40초 분량(16 kHz, 16비트)
    private val speech = listOf(VadSegment(0, 16_000 * 10), VadSegment(16_000 * 20, 16_000 * 35))   // 말 0~10초, 20~35초

    /** 가짜 STT 엔진 — transcribe 본문을 시험마다 바꾼다 */
    private inner class FakeAdapter(override val spec: ModelSpec, val body: suspend () -> SttOutcome) : SttEngineAdapter {
        override suspend fun load(modelDir: File?, lang: Language) = LoadInfo(1.5, "fake 1.0")
        override suspend fun transcribe(chunks: List<AudioChunk>, feed: FeedChoice, lang: Language): SttOutcome {
            calls += "stt"; fed = chunks.map { it.pcm.size }; feedSeen = feed
            return body()
        }
        override fun close() = Unit
    }

    /** 가짜 VAD — 정해 둔 구간을 그대로 준다 */
    private inner class FakeVad(val found: List<VadSegment>) : VadDetector {
        override val engine = VadEngine.SILERO
        override val label = "fake"
        override val runtimeVersion = "0"
        override val modelSha256 = ""
        override fun detect(pcm16Le: ByteArray, setting: VadSetting): FrameSegmenter.Result { calls += "vad"; return FrameSegmenter.Result(found, found) }
        override fun close() = Unit
    }

    /**
     * 목적: 시험용 작업 환경을 만든다.
     * 입력: engine - 결과 엔진 이름, vad - 가짜 VAD(없으면 null), ns - 노이즈 저감을 넣을지, feed - 공급 속도,
     *       device - 기기 계측값, stt - 가짜 엔진의 본문 / 출력: 없음 / 리턴: JobContext
     */
    private fun TestScope.ctx(
        engine: String, vad: List<VadSegment>? = null, ns: Boolean = false, feed: FeedChoice = FeedChoice.NONE,
        device: DeviceSample = DeviceSample("S24", "dev"), stt: suspend () -> SttOutcome = { delay(2_000); SttOutcome("안녕", 1, SttStatus.OK, tailMs = 2_000) },
    ): JobContext {
        val spec = Catalog.model(engine)!!
        val setting = if (vad == null) VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())
        else VadPresetCatalog.presets(VadEngine.SILERO).first().let { VadSetting(VadEngine.SILERO, it, it.values) }
        val job = Job(LangFolder("kr", "id", Language.KO, LangSource.values().first()), spec, setting, RunOptions(feed = feed).effectiveFor(spec.runtime))
        return JobContext(
            job = job, runId = "run1", adapter = FakeAdapter(spec, stt), load = LoadInfo(1.5, "fake 1.0"), vad = vad?.let { FakeVad(it) },
            denoise = if (ns) ({ calls += "ns"; it }) else null, meter = { { device } }, workers = 1, purpose = RunPurpose.TIMING,
            now = { "2026-11-06T22:00:00" }, nanoTime = { testScheduler.currentTime * 1_000_000 }, compute = StandardTestDispatcher(testScheduler),
        )
    }

    /** 목적: 시험용 음원을 만든다(디코딩에 0.1초 걸린다). 입력: fail - 디코딩을 실패시킬지 / 출력: 없음 / 리턴: AudioSource */
    private fun source(fail: Boolean = false) = AudioSource(listOf("Customer", "A"), "01", ".wav", 1234L) {
        calls += "decode"; delay(100)
        if (fail) throw IOException("깨진 파일")
        Decoded(pcm, 48_000, 2)
    }

    /**
     * 목적: [UT-RUN-01] 오프라인 엔진 + VAD + 노이즈 저감 — 단계 순서, 조각 다시 묶기, 시간, 기록 값.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun offlineWithVad_orderChunksAndTimings() = runTest {
        val c = ctx("whisper-base", vad = speech, ns = true)
        val r = PipelineSteps.runFile(c, source(), first = true)
        assertEquals(listOf("decode", "ns", "vad", "stt"), calls)
        assertNull(r.error)
        val rec = r.record
        assertEquals(SttStatus.OK, rec.stt.status); assertEquals("안녕", rec.stt.text); assertEquals("", rec.note)
        assertEquals("Customer/A", rec.relDir); assertEquals("01", rec.file); assertEquals("run1", rec.runId)
        assertEquals(40.0, rec.audio.sec, 1e-9); assertEquals(48_000, rec.audio.sampleRate); assertEquals(2, rec.audio.channels)
        assertEquals(0.1, rec.timings.decodeSec, 1e-9); assertEquals(2.0, rec.timings.transcribeSec!!, 1e-9)
        assertEquals(2.1, rec.timings.elapsedSec, 1e-9)                                // 단계 합과 같다
        assertEquals(rec.timings.decodeSec + rec.timings.nsSec + rec.timings.vadSec, rec.timings.preprocessSec, 1e-12)
        assertEquals(2, rec.vad!!.segments)                                            // VAD 구간 수
        assertTrue("조각은 모델 상한(29초) 이하", fed.all { it <= 32_000 * 29 })
        assertEquals(fed.size, rec.chunks); assertEquals(fed.sum() / 32_000.0, rec.vad!!.keptSec, 1e-9)   // 남은 길이 = 조각 합
        assertTrue("말 구간(25초)에 사이 무음만 더한 길이여야 한다: ${rec.vad!!.keptSec}", rec.vad!!.keptSec in 25.0..26.0)
        assertEquals(fed.max() / 32_000.0, rec.chunkMaxSec!!, 1e-9)
        assertEquals(1.5, rec.modelLoadSec!!, 1e-9); assertEquals("fake 1.0", rec.engineVersion); assertEquals("timing", rec.purpose)
        assertEquals(0.0, PipelineSteps.runFile(c, source(), first = false).record.modelLoadSec!!, 1e-9)   // 첫 파일에만 적는다
    }

    /**
     * 목적: 스트리밍 엔진은 상한이 없어 VAD 가 이어 붙인 PCM 1개를 넣는다. 공급 속도가 엔진에 전달되고, 공급 막힘·네트워크 변화가 note 에 남는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun streaming_singleChunkAndNotes() = runTest {
        val slow = SttOutcome("hello", 1, SttStatus.OK, tailMs = 5, feedSec = 20.0, feedMeasured = true, note = "엔진 메모")   // D10 이면 이론 2.52초
        val c = ctx("nemotron3.5-560ms", vad = speech, feed = FeedChoice.D10, device = DeviceSample("S24", "dev", netRxDelta = 10), stt = { slow })
        val rec = PipelineSteps.runFile(c, source(), first = true).record
        assertEquals(1, fed.size); assertEquals(FeedChoice.D10, feedSeen)
        assertEquals(fed[0] / 32_000.0, rec.vad!!.keptSec, 1e-9)                       // VAD 출력 길이 그대로
        assertEquals(listOf("엔진 메모", PipelineSteps.NOTE_FEED_STALL, PipelineSteps.NOTE_NET_DELTA), rec.note.split(";"))
        calls.clear()
        val plain = PipelineSteps.runFile(ctx("nemotron3.5-560ms"), source(), first = true).record   // VAD 없음
        assertEquals(listOf("decode", "stt"), calls); assertEquals(listOf(pcm.size), fed); assertNull(plain.vad); assertEquals(FeedChoice.NONE, feedSeen)
    }

    /**
     * 목적: [M4 문제 2] Vosk 가 오류 없이 끝났는데 글자가 입력 길이에 비해 너무 적으면 note 에 DECODE_COLLAPSE_SUSPECT 가 붙는다.
     *       글자가 충분하거나 Vosk 가 아닌 엔진이면 붙지 않는다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     * add-hyungchul-20261107-0116
     */
    @Test
    fun vosk_fewCharsForLongInput_markedAsCollapse() = runTest {
        val few = SttOutcome("가".repeat(40), 1, SttStatus.OK, tailMs = 5, feedSec = 3.0, feedMeasured = true)        // 40초 입력에 40자 = 1자/초
        val bad = PipelineSteps.runFile(ctx("vosk-small-ko", stt = { few }), source(), first = true).record
        assertEquals(SttStatus.OK, bad.stt.status); assertEquals(PipelineSteps.NOTE_DECODE_COLLAPSE, bad.note)
        val enough = SttOutcome("가".repeat(280), 1, SttStatus.OK, tailMs = 5, feedSec = 7.0, feedMeasured = true)    // 7자/초
        assertEquals("", PipelineSteps.runFile(ctx("vosk-small-ko", stt = { enough }), source(), first = true).record.note)
        assertEquals("", PipelineSteps.runFile(ctx("nemotron3.5-560ms", stt = { few }), source(), first = true).record.note)   // 다른 엔진은 판정하지 않는다
    }

    /**
     * 목적: [E-VAD-02] VAD 가 말을 찾지 못하면 엔진을 부르지 않는다 — NO_MATCH, note=VAD_NO_SPEECH, 전사 시간 없음.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun vadFindsNothing_noMatchWithoutEngine() = runTest {
        val r = PipelineSteps.runFile(ctx("whisper-base", vad = emptyList()), source(), first = true)
        assertEquals(listOf("decode", "vad"), calls); assertNull(r.error)
        assertEquals(SttStatus.NO_MATCH, r.record.stt.status); assertEquals(PipelineSteps.NOTE_VAD_NO_SPEECH, r.record.note)
        assertNull(r.record.timings.transcribeSec); assertEquals(0, r.record.chunks); assertEquals(0, r.record.vad!!.segments)
    }

    /**
     * 목적: 단계가 실패하면 ERROR 기록으로 돌려준다(예외를 던지지 않는다). 어느 단계인지와 시간 초과를 note 로 가린다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun stepFailure_becomesErrorRecord() = runTest {
        var r = PipelineSteps.runFile(ctx("whisper-base", stt = { throw IOException("엔진 오류") }), source(), first = true)
        assertTrue(r.error is IOException); assertEquals(SttStatus.ERROR, r.record.stt.status); assertEquals("ERR_STT:IOException", r.record.note)
        assertNull(r.record.timings.transcribeSec); assertEquals(40.0, r.record.audio.sec, 1e-9)     // 디코딩까지는 됐다
        r = PipelineSteps.runFile(ctx("google-mlkit", stt = { throw AppError.SttTimeout("무응답") }), source(), first = true)
        assertEquals("ERR_TIMEOUT", r.record.note); assertTrue(r.error is AppError.SttTimeout)
        r = PipelineSteps.runFile(ctx("whisper-base"), source(fail = true), first = true)
        assertEquals("ERR_DECODE:IOException", r.record.note); assertEquals(0.0, r.record.audio.sec, 1e-9); assertFalse("stt" in calls.takeLast(1))
    }

    /**
     * 목적: [UT-RUN-02] 취소는 ERROR 기록으로 바뀌지 않고 그대로 올라간다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun cancel_propagatesWithoutRecord() = runTest {
        var result: FileResult? = null
        val job = launch { result = PipelineSteps.runFile(ctx("whisper-base", stt = { awaitCancellation() }), source(), first = true) }
        testScheduler.advanceTimeBy(500); runCurrent()                                 // 디코딩을 지나 엔진 안에서 멈춰 있다
        assertEquals("stt", calls.last())
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertNull(result)                                // 기록이 만들어지지 않았다
        try { PipelineSteps.runFile(ctx("whisper-base", stt = { throw CancellationException("중지") }), source(), first = true); org.junit.Assert.fail("취소가 올라와야 한다") }
        catch (e: CancellationException) { /* 기대한 결과 */ }
    }
}
