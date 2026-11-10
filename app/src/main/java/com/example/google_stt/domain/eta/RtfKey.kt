/*
 * 파일명: domain/eta/RtfKey.kt
 * 목적 및 기능:
 * - 예상 소요 시간에 쓰는 실측 RTF(= 작업 소요 시간 / 음원 길이) 표와 그 조회 규칙을 둔다(D11, FR-08-02, 3단계 8-2).
 * - 조회 우선순위:
 *     ① 같은 기기·같은 (엔진, VAD 묶음, 동시 작업 수) 실측 → MEASURED
 *     ② 같은 기기·같은 엔진의 다른 조건 실측 → MEASURED_MODEL
 *     ③ 같은 기기의 빠른 측정(엔진별 짧은 파일 1개) → CALIBRATION
 *     ④ 다른 기기의 값 → OTHER_DEVICE_REF(참고로만 보이고 합계에 넣지 않는다 — SoC·발열 특성이 달라 섞으면 양쪽 다 틀린다)
 *     ⑤ 없음 → NONE("미측정" — 근거 없는 추정치를 만들지 않는다)
 * - 실측값은 키마다 최근 20개만 두고 중앙값을 쓴다(튀는 값 1~2개에 흔들리지 않게).
 *
 * add-hyungchul-20261106-0056 — [T2-9]
 */
package com.example.google_stt.domain.eta

/** 목적: 추정에 쓴 RTF 의 출처. */
enum class RtfSource {
    MEASURED,           // 같은 기기·같은 조건 실측
    MEASURED_MODEL,     // 같은 기기·같은 엔진의 다른 조건 실측
    CALIBRATION,        // 같은 기기의 빠른 측정
    FEED_RATE,          // 공급 속도로 정해지는 값(측정이 필요 없다 — 실시간 공급이면 음원 길이만큼 걸린다)
    OTHER_DEVICE_REF,   // 다른 기기 값(참고 표시만)
    NONE;               // 미측정

    /** 합계에 넣어도 되는 출처인지(다른 기기 값과 미측정은 넣지 않는다) */
    val usable: Boolean get() = this == MEASURED || this == MEASURED_MODEL || this == CALIBRATION || this == FEED_RATE
}

/**
 * 목적: 실측 RTF 를 구분하는 키.
 * 필드: deviceKey - 기기 식별("<기기 모델>/<SoC>"), engine - 결과 엔진 이름, vadGroup - VAD 엔진 토큰(NOVAD / SILERO …),
 *       workers - 측정할 때의 동시 작업 수(동시 실행의 감속이 값에 들어 있다)
 */
data class RtfKey(
    val deviceKey: String,   // 기기
    val engine: String,      // 엔진
    val vadGroup: String,    // VAD 묶음
    val workers: Int,        // 동시 작업 수
)

/**
 * 목적: 조회 결과.
 * 필드: rtf - RTF 값, source - 출처
 */
data class RtfValue(
    val rtf: Double,         // RTF
    val source: RtfSource,   // 출처
)

/** 목적: 실측값 목록 다루기(최근 20개, 중앙값). */
object RtfSamples {

    /** 키마다 두는 실측값 최대 개수 */
    const val MAX = 20

    /**
     * 목적: 실측값 하나를 더한다(20개를 넘으면 가장 오래된 것을 버린다).
     * 입력: samples - 지금까지의 값(오래된 것이 앞), value - 새 값
     * 출력: 없음(입력 목록은 바뀌지 않는다)
     * 리턴: List<Double> - 새 목록. 값이 0 이하이거나 숫자가 아니면 그대로 돌려준다
     */
    fun add(samples: List<Double>, value: Double): List<Double> =
        if (value <= 0.0 || value.isNaN() || value.isInfinite()) samples else (samples + value).takeLast(MAX)

    /**
     * 목적: 중앙값을 구한다.
     * 입력: samples - 값 목록 / 출력: 없음 / 리턴: Double? - 비어 있으면 null. 짝수 개면 가운데 두 값의 평균
     */
    fun median(samples: List<Double>): Double? {
        if (samples.isEmpty()) return null
        val s = samples.sorted()                                              // 크기순
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0   // 홀수: 가운데, 짝수: 두 값 평균
    }
}

/**
 * 목적: RTF 표(실측 + 빠른 측정)와 조회.
 * 필드: measured - 키 → 최근 실측값들, calibration - (기기, 엔진) → 빠른 측정 RTF
 */
class RtfTable(
    val measured: Map<RtfKey, List<Double>> = emptyMap(),                     // 실측
    val calibration: Map<Pair<String, String>, Double> = emptyMap(),          // 빠른 측정
) {
    /**
     * 목적: 우선순위 규칙대로 RTF 를 찾는다.
     * 입력: key - 찾는 조건(기기·엔진·VAD 묶음·동시 작업 수)
     * 출력: 없음
     * 리턴: RtfValue? - 어떤 값도 없으면 null(= NONE)
     */
    fun lookup(key: RtfKey): RtfValue? {
        RtfSamples.median(measured[key] ?: emptyList())?.let { return RtfValue(it, RtfSource.MEASURED) }             // ① 같은 조건
        val sameModel = measured.filterKeys { it.deviceKey == key.deviceKey && it.engine == key.engine }.values.flatten()
        RtfSamples.median(sameModel)?.let { return RtfValue(it, RtfSource.MEASURED_MODEL) }                          // ② 같은 엔진
        calibration[key.deviceKey to key.engine]?.let { return RtfValue(it, RtfSource.CALIBRATION) }                 // ③ 빠른 측정
        val other = measured.filterKeys { it.deviceKey != key.deviceKey && it.engine == key.engine }.values.flatten() +
            calibration.filterKeys { it.first != key.deviceKey && it.second == key.engine }.values
        RtfSamples.median(other)?.let { return RtfValue(it, RtfSource.OTHER_DEVICE_REF) }                            // ④ 다른 기기
        return null                                                                                                  // ⑤ 없음
    }

    /**
     * 목적: 실측값 하나를 더한 새 표를 만든다(작업이 끝날 때마다 배운다 — FR-08-04).
     * 입력: key - 조건, rtf - 새 실측 RTF / 출력: 없음(이 표는 바뀌지 않는다) / 리턴: RtfTable
     */
    fun plus(key: RtfKey, rtf: Double): RtfTable =
        RtfTable(measured + (key to RtfSamples.add(measured[key] ?: emptyList(), rtf)), calibration)
}
