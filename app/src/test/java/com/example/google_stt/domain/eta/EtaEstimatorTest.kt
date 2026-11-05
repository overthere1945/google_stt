/*
 * 파일명: domain/eta/EtaEstimatorTest.kt (app/src/test)
 * 목적 및 기능:
 * - 예상 소요 시간 계산을 확인한다(D11, FR-08).
 *     · UT-ETA-01 출처 우선순위(같은 조건 실측 > 같은 엔진 실측 > 빠른 측정 > 없음)
 *     · UT-ETA-02 다른 기기 값은 합계에 넣지 않고 참고로만
 *     · UT-ETA-03 미측정 작업 수, 합계는 측정된 작업만
 *     · UT-ETA-04 남은 시간 = 끝난 작업 실측을 배운 뒤 남은 작업 추정
 *     · UT-ETA-05 최근 20개 중앙값(21번째가 가장 오래된 값을 밀어낸다)
 *
 * add-hyungchul-20261106-0056 — [T2-9]
 */
package com.example.google_stt.domain.eta

import com.example.google_stt.domain.catalog.Catalog          // 모델 찾기
import com.example.google_stt.domain.catalog.Language         // 평가 언어
import com.example.google_stt.domain.condition.FeedChoice     // 공급 속도
import com.example.google_stt.domain.condition.RunOptions     // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder          // 언어 폴더
import com.example.google_stt.domain.lang.LangSource          // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                 // 작업
import com.example.google_stt.domain.vad.VadEngine            // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog     // 프리셋 목록
import com.example.google_stt.domain.vad.VadSetting           // VAD 설정
import org.junit.Assert.assertEquals                          // 값 비교
import org.junit.Assert.assertNull                            // null 확인
import org.junit.Test                                         // JUnit 4 시험 표시

/** 목적: 예상 소요 시간 시험. */
class EtaEstimatorTest {

    private val s24 = "SM-S928N/SM8650"                       // 이 기기
    private val s25 = "SM-S938N/SM8750"                       // 다른 기기
    private val kr = LangFolder("kr", "root/kr", Language.KO, LangSource.AUTO_SUBFOLDER)
    private val novad = VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())
    private val sileroPreset = VadPresetCatalog.presets(VadEngine.SILERO).first { it.name == "SILERO" }
    private val silero = VadSetting(VadEngine.SILERO, sileroPreset, sileroPreset.values)
    private val audio = mapOf("kr" to 1000.0)                 // kr 폴더 음원 합 1000초

    /**
     * 목적: 작업을 만든다.
     * 입력: engine - 결과 엔진 이름, vad - VAD 설정, feed - 공급 속도 / 출력: 없음 / 리턴: Job
     */
    private fun job(engine: String, vad: VadSetting = novad, feed: FeedChoice = FeedChoice.NONE) =
        Job(kr, Catalog.model(engine)!!, vad, RunOptions(feed = feed).effectiveFor(Catalog.model(engine)!!.runtime))

    /**
     * 목적: [UT-ETA-01] 출처 우선순위 — 같은 조건 실측 > 같은 엔진 실측 > 빠른 측정 > 없음.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun lookup_sourcePriority() {
        val table = RtfTable(
            measured = mapOf(
                RtfKey(s24, "whisper-base", "SILERO", 1) to listOf(0.10, 0.12, 0.11),   // 같은 조건
                RtfKey(s24, "whisper-base", "NOVAD", 1) to listOf(0.08),                // 같은 엔진, 다른 VAD
                RtfKey(s24, "zipformer-ko", "NOVAD", 2) to listOf(0.03),                // 같은 엔진, 다른 동시 작업 수
            ),
            calibration = mapOf((s24 to "whisper-base") to 0.5, (s24 to "zipformer-ko") to 0.6, (s24 to "sensevoice-small") to 0.04),
        )
        assertEquals(RtfValue(0.11, RtfSource.MEASURED), table.lookup(RtfKey(s24, "whisper-base", "SILERO", 1)))        // 중앙값
        val sameModel = table.lookup(RtfKey(s24, "whisper-base", "PYANNOTE", 1))!!                                      // 같은 엔진의 다른 조건
        assertEquals(RtfSource.MEASURED_MODEL, sameModel.source)
        assertEquals(0.105, sameModel.rtf, 1e-9)                                                                        // 4개 값(0.08, 0.10, 0.11, 0.12)의 중앙값
        assertEquals(RtfValue(0.03, RtfSource.MEASURED_MODEL), table.lookup(RtfKey(s24, "zipformer-ko", "NOVAD", 1)))   // 빠른 측정보다 실측 우선
        assertEquals(RtfValue(0.04, RtfSource.CALIBRATION), table.lookup(RtfKey(s24, "sensevoice-small", "NOVAD", 1)))
        assertNull(table.lookup(RtfKey(s24, "qwen3asr-0.6b", "NOVAD", 1)))                                              // 없음
    }

    /**
     * 목적: [UT-ETA-02] 다른 기기 값은 OTHER_DEVICE_REF 로만 보이고 합계에 들어가지 않는다(참고 합은 따로).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun otherDeviceValues_areReferenceOnly() {
        val table = RtfTable(measured = mapOf(
            RtfKey(s25, "qwen3asr-0.6b", "NOVAD", 1) to listOf(0.2),          // 다른 기기 실측
            RtfKey(s24, "whisper-base", "NOVAD", 1) to listOf(0.1),           // 이 기기 실측
        ))
        assertEquals(RtfValue(0.2, RtfSource.OTHER_DEVICE_REF), table.lookup(RtfKey(s24, "qwen3asr-0.6b", "NOVAD", 1)))
        val e = EtaEstimator(table, s24, 1).estimate(listOf(job("whisper-base"), job("qwen3asr-0.6b")), audio)
        assertEquals(100.0, e.totalSec!!, 1e-9)                               // whisper-base 만: 1000 × 0.1
        assertEquals(1, e.unknownJobs)                                        // qwen3 는 합계에 못 넣는다
        assertEquals(200.0, e.otherDeviceSec!!, 1e-9)                         // 참고: 1000 × 0.2
        assertEquals(RtfSource.OTHER_DEVICE_REF, e.sources["kr/qwen3asr-0.6b_sherpa-int8_NOVAD"])
        assertNull(e.perJob[1].second)
    }

    /**
     * 목적: [UT-ETA-03] 값이 없는 작업은 미측정으로 세고, 합계는 측정된 작업만 더한다. 아무 값도 없으면 합계는 null.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun unknownJobs_areCountedNotGuessed() {
        val table = RtfTable(measured = mapOf(RtfKey(s24, "whisper-base", "NOVAD", 1) to listOf(0.1)))
        val jobs = listOf(job("whisper-base"), job("sensevoice-small"), job("zipformer-ko", silero))
        val e = EtaEstimator(table, s24, 1).estimate(jobs, audio)
        assertEquals(100.0, e.totalSec!!, 1e-9)
        assertEquals(2, e.unknownJobs)
        assertNull(e.otherDeviceSec)
        assertEquals(listOf(RtfSource.MEASURED, RtfSource.NONE, RtfSource.NONE), jobs.map { e.sources[it.key] })
        val none = EtaEstimator(RtfTable(), s24, 1).estimate(jobs, audio)
        assertNull(none.totalSec)                                             // 근거 없는 추정치를 만들지 않는다
        assertEquals(3, none.unknownJobs)
    }

    /**
     * 목적: 공급 속도를 쓰는 작업은 측정 없이 정해진다(REALTIME = 음원 길이, D50 = 절반, ML Kit = REALTIME). 동시 작업 수로 나눈다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun feedRateJobs_needNoMeasurement() {
        val jobs = listOf(
            job("nemotron3.5-560ms", feed = FeedChoice.REALTIME),             // 1000 × 1.0
            job("vosk-small-ko", feed = FeedChoice.D50),                      // 1000 × 0.5
            job("google-mlkit"),                                              // ML Kit 은 REALTIME 고정 → 1000 × 1.0
            job("nemotron3.5-560ms"),                                         // 공급 속도 "사용 안 함" → 실측 필요
        )
        val e = EtaEstimator(RtfTable(), s24, 1).estimate(jobs, audio)
        assertEquals(2500.0, e.totalSec!!, 1e-9)
        assertEquals(1, e.unknownJobs)
        assertEquals(RtfSource.FEED_RATE, e.sources[jobs[0].key])
        assertEquals(RtfSource.NONE, e.sources[jobs[3].key])
        assertEquals(1250.0, EtaEstimator(RtfTable(), s24, 2).estimate(jobs, audio).totalSec!!, 1e-9)   // 동시 2개
        assertNull(EtaEstimator(RtfTable(), s24, 1).estimate(emptyList(), audio).totalSec)              // 작업이 없으면 값 없음
    }

    /**
     * 목적: [UT-ETA-04] 남은 시간 — 끝난 작업의 실측(소요/음원 길이)을 배워 남은 작업을 추정한다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun remaining_learnsFromDoneJobs() {
        val est = EtaEstimator(RtfTable(), s24, 1)
        val pending = listOf(job("whisper-base", silero), job("sensevoice-small"))
        assertNull(est.remaining(emptyList(), pending, audio).totalSec)       // 아직 배운 것이 없다
        val done = listOf(JobTiming(job("whisper-base"), audioSec = 1000.0, elapsedSec = 120.0),   // RTF 0.12
            JobTiming(job("zipformer-ko"), audioSec = 0.0, elapsedSec = 5.0))                      // 음원 길이 0 은 건너뛴다
        val r = est.remaining(done, pending, audio)
        assertEquals(120.0, r.totalSec!!, 1e-9)                               // whisper-base × SILERO 는 같은 엔진 실측으로
        assertEquals(RtfSource.MEASURED_MODEL, r.sources[pending[0].key])
        assertEquals(1, r.unknownJobs)                                        // sensevoice 는 아직 미측정
        assertNull(est.estimate(pending, audio).totalSec)                     // 계산기의 표는 바뀌지 않았다
    }

    /**
     * 목적: [UT-ETA-05] 실측값은 최근 20개만 남고(21번째가 가장 오래된 값을 밀어낸다) 중앙값을 쓴다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun samples_keepLast20_andUseMedian() {
        var s = emptyList<Double>()
        for (i in 1..20) s = RtfSamples.add(s, i.toDouble())                  // 1..20
        assertEquals(20, s.size)
        assertEquals(10.5, RtfSamples.median(s)!!, 1e-9)                      // 짝수 개: 10 과 11 의 평균
        s = RtfSamples.add(s, 100.0)                                          // 21번째
        assertEquals(20, s.size)
        assertEquals(2.0, s.first(), 1e-9)                                    // 가장 오래된 1 이 빠졌다
        assertEquals(11.5, RtfSamples.median(s)!!, 1e-9)                      // 튀는 값 100 에 흔들리지 않는다
        assertEquals(s, RtfSamples.add(s, 0.0))                               // 0 이하·숫자 아님은 넣지 않는다
        assertEquals(s, RtfSamples.add(s, Double.NaN))
        assertEquals(3.0, RtfSamples.median(listOf(5.0, 1.0, 3.0))!!, 1e-9)   // 홀수 개: 가운데 값
        assertNull(RtfSamples.median(emptyList()))
        var t = RtfTable()
        for (i in 1..21) t = t.plus(RtfKey(s24, "whisper-base", "NOVAD", 1), i.toDouble())
        assertEquals(20, t.measured.values.single().size)                     // 표에 더할 때도 20개까지만
    }
}
