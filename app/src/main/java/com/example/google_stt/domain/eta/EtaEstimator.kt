/*
 * 파일명: domain/eta/EtaEstimator.kt
 * 목적 및 기능:
 * - 작업 목록의 예상 소요 시간을 계산한다(D11, FR-08-01 ~ FR-08-04).
 *     작업 1개 = 그 언어 폴더의 음원 길이 합 × RTF
 *       · 공급 속도를 쓰는 작업(스트리밍·Vosk 에서 고른 경우, ML Kit): RTF = 간격 ms / 100 — 측정 없이 정해진다
 *       · 그 밖(오프라인, 공급 속도 "사용 안 함"): RTF 표에서 찾는다(RtfTable.lookup 의 우선순위)
 *     전체 = 합계에 넣을 수 있는 작업들의 합 ÷ 동시 작업 수
 * - 값이 없는 작업은 "미측정" 으로 세고 합계에 넣지 않는다. 다른 기기 값은 참고 합계(otherDeviceSec)로 따로 둔다.
 * - 실행 중에는 끝난 작업의 실측을 표에 더해 남은 작업을 다시 추정한다(remaining).
 * - 여기서 쓰는 RTF 는 "작업 소요 시간 / 음원 길이" 다(모델 적재·디코딩·VAD 가 들어 있다). CSV 의 rtf 열(전사 구간만)과 다르다.
 *
 * add-hyungchul-20261106-0056 — [T2-9]
 */
package com.example.google_stt.domain.eta

import com.example.google_stt.domain.plan.Job   // 작업

/**
 * 목적: 추정 결과.
 * 필드: totalSec - 예상 소요(초). 합계에 넣을 작업이 하나도 없으면 null,
 *       perJob - 작업별 예상(초, 값이 없으면 null), sources - Job.key → 출처,
 *       unknownJobs - 합계에 넣지 못한 작업 수(미측정 + 다른 기기 값만 있는 것),
 *       otherDeviceSec - 다른 기기 값으로만 계산되는 작업들의 참고 합(초, 없으면 null)
 */
data class Estimate(
    val totalSec: Double?,                     // 예상 소요
    val perJob: List<Pair<Job, Double?>>,      // 작업별 예상
    val sources: Map<String, RtfSource>,       // 출처
    val unknownJobs: Int,                      // 미측정 작업 수
    val otherDeviceSec: Double?,               // 다른 기기 참고 합
)

/**
 * 목적: 끝난 작업의 실측.
 * 필드: job - 작업, audioSec - 처리한 음원 길이 합(초), elapsedSec - 작업 소요 시간(초)
 */
data class JobTiming(
    val job: Job,             // 작업
    val audioSec: Double,     // 음원 길이 합
    val elapsedSec: Double,   // 소요 시간
)

/**
 * 목적: 예상 소요 시간 계산기.
 * 필드: table - RTF 표, deviceKey - 이 기기의 식별, workers - 동시 작업 수
 */
class EtaEstimator(
    private val table: RtfTable,      // RTF 표
    private val deviceKey: String,    // 이 기기
    private val workers: Int,         // 동시 작업 수
) {
    /**
     * 목적: 작업 목록의 예상 소요 시간을 계산한다.
     * 입력: jobs - 작업 목록, audioSecByLang - 언어 폴더 이름 → 음원 길이 합(초)
     * 출력: 없음
     * 리턴: Estimate
     */
    fun estimate(jobs: List<Job>, audioSecByLang: Map<String, Double>): Estimate = estimate(jobs, audioSecByLang, table)

    /**
     * 목적: 실행 중 남은 시간을 계산한다 — 끝난 작업의 실측을 배운 뒤 남은 작업을 추정한다(FR-08-03).
     * 입력: done - 끝난 작업의 실측, pending - 남은 작업, audioSecByLang - 언어 폴더 이름 → 음원 길이 합(초)
     * 출력: 없음(이 계산기의 표는 바뀌지 않는다)
     * 리턴: Estimate - 남은 작업에 대한 추정
     */
    fun remaining(done: List<JobTiming>, pending: List<Job>, audioSecByLang: Map<String, Double>): Estimate {
        var learned = table                                                   // 끝난 작업을 더해 갈 표
        for (d in done) {
            if (d.audioSec <= 0.0 || d.elapsedSec <= 0.0) continue            // 잴 수 없는 작업은 건너뛴다
            learned = learned.plus(keyOf(d.job), d.elapsedSec / d.audioSec)   // 실측 RTF
        }
        return estimate(pending, audioSecByLang, learned)
    }

    /**
     * 목적: 주어진 표로 추정한다.
     * 입력: jobs - 작업 목록, audioSecByLang - 언어 폴더별 음원 길이, t - RTF 표 / 출력: 없음 / 리턴: Estimate
     */
    private fun estimate(jobs: List<Job>, audioSecByLang: Map<String, Double>, t: RtfTable): Estimate {
        val perJob = ArrayList<Pair<Job, Double?>>()                          // 작업별 예상
        val sources = LinkedHashMap<String, RtfSource>()                      // 출처
        var usableSum = 0.0; var usableCount = 0                              // 합계에 넣는 것
        var otherSum = 0.0; var otherCount = 0                                // 다른 기기 참고
        for (job in jobs) {
            val audioSec = audioSecByLang[job.lang.name] ?: 0.0               // 그 언어 폴더의 음원 길이
            val value = rtfFor(job, t)
            val source = value?.source ?: RtfSource.NONE
            sources[job.key] = source
            if (value != null && source.usable) {
                val sec = audioSec * value.rtf
                perJob += job to sec; usableSum += sec; usableCount++
            } else {
                perJob += job to null                                         // 합계에 넣지 않는다
                if (value != null) { otherSum += audioSec * value.rtf; otherCount++ }   // 다른 기기 값은 참고 합으로
            }
        }
        val div = workers.coerceAtLeast(1)                                    // 동시 작업 수로 나눈다(최소 1)
        return Estimate(
            totalSec = if (usableCount > 0) usableSum / div else null,
            perJob = perJob, sources = sources,
            unknownJobs = jobs.size - usableCount,
            otherDeviceSec = if (otherCount > 0) otherSum / div else null,
        )
    }

    /**
     * 목적: 작업의 RTF 를 정한다.
     * 입력: job - 작업, t - RTF 표
     * 출력: 없음
     * 리턴: RtfValue? - 공급 속도를 쓰면 간격/100(FEED_RATE), 아니면 표에서 찾은 값(없으면 null)
     */
    private fun rtfFor(job: Job, t: RtfTable): RtfValue? {
        job.opt.feed.delayMs?.let { return RtfValue(it / 100.0, RtfSource.FEED_RATE) }   // REALTIME = 1.0, D50 = 0.5 …
        return t.lookup(keyOf(job))
    }

    /**
     * 목적: 작업의 RTF 키를 만든다.
     * 입력: job - 작업 / 출력: 없음 / 리턴: RtfKey(이 기기, 엔진, VAD 엔진 토큰, 동시 작업 수)
     */
    private fun keyOf(job: Job): RtfKey = RtfKey(deviceKey, job.model.engine, job.vad.engine.token, workers)
}
