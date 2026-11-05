/*
 * 파일명: domain/csv/FileRecord.kt
 * 목적 및 기능:
 * - 음원 파일 1개를 처리한 결과(FileRecord)와 그 부품 자료형을 둔다(4단계 2-4).
 *   result.csv(74열)와 device.csv(보조)가 모두 이 한 자료에서 값을 계산한다 → 두 CSV 가 run_id + file 로 맞물린다.
 * - 단위: 시간은 초(Double), 엔진이 주는 지연은 ms(Long). 값이 없으면 null(빈 칸이 된다).
 * - SttOutcome 은 엔진 어댑터(T5-1)가 돌려주는 전사 결과다. v3 의 stt.SttOutcome 을 대신한다.
 *
 * add-hyungchul-20261106-0049 — [T2-7]
 */
package com.example.google_stt.domain.csv

import com.example.google_stt.domain.plan.Job   // 이 파일이 속한 작업(엔진·모델·조건·언어)

/** 목적: 파일 처리 상태(서버 normalize_status 의 3종 — 대문자 이름이 그대로 CSV 값이다). */
enum class SttStatus {
    OK,         // 전사문이 있다
    NO_MATCH,   // 오류 없이 끝났지만 전사문이 비었다(무음 등) — 0바이트 TXT
    ERROR,      // 실패 — <이름>.ERROR.txt 를 남긴다(D28)
}

/**
 * 목적: 전사 결과와 지연 계측.
 * 필드: text - 전사문, utterances - 발화(세그먼트) 수, status - 상태,
 *       firstResultMs - 시작 → 첫 응답(ms), tailMs - 공급 끝 → 최종 결과(ms, 오프라인은 추론 시간),
 *       feedSec - 오디오 공급에 걸린 시간(초, 오프라인은 null), feedMeasured - 공급 구간을 실제로 쟀는지,
 *       detectedLang - 엔진이 알려 준 언어(없으면 null)
 */
data class SttOutcome(
    val text: String,                    // 전사문
    val utterances: Int,                 // 발화 수
    val status: SttStatus,               // 상태
    val firstResultMs: Long? = null,     // 첫 응답 지연
    val tailMs: Long? = null,            // 꼬리 지연
    val feedSec: Double? = null,         // 공급 시간
    val feedMeasured: Boolean = false,   // 공급 구간 측정 여부
    val detectedLang: String? = null,    // 감지 언어
)

/**
 * 목적: 원본 음원 정보.
 * 필드: ext - 확장자(점 포함 소문자, 예 ".wav"), bytes - 파일 크기, sec - 길이(초, 모르면 0),
 *       sampleRate - 원본 표본화 주파수(Hz), channels - 원본 채널 수
 */
data class AudioInfo(
    val ext: String,        // 확장자
    val bytes: Long,        // 파일 크기
    val sec: Double,        // 길이(초)
    val sampleRate: Int,    // 표본화 주파수
    val channels: Int,      // 채널 수
)

/**
 * 목적: 단계별 소요 시간(초).
 * 필드: decodeSec - 디코딩·리샘플, nsSec - 노이즈 저감, vadSec - VAD, transcribeSec - STT 벽시계, elapsedSec - 파일 전체
 */
data class Timings(
    val decodeSec: Double,       // 디코딩
    val nsSec: Double,           // 노이즈 저감(안 쓰면 0)
    val vadSec: Double,          // VAD(안 쓰면 0)
    val transcribeSec: Double?,  // STT 벽시계(엔진을 부르지 못했으면 null)
    val elapsedSec: Double,      // 파일 전체
) {
    /** 전처리 합 = 디코딩 + 노이즈 저감 + VAD (CSV preprocess_sec) */
    val preprocessSec: Double get() = decodeSec + nsSec + vadSec
}

/**
 * 목적: VAD 결과 요약(VAD 를 쓰지 않았으면 FileRecord.vad 가 null).
 * 필드: segments - 말 구간 수, keptSec - VAD 뒤 엔진에 들어간 길이(초)
 */
data class VadSummary(
    val segments: Int,     // 말 구간 수
    val keptSec: Double,   // 남은 길이(초)
)

/**
 * 목적: 파일 1개를 처리하는 동안의 기기 계측(data/device/DeviceProbe 가 채운다 — T3-5).
 * 필드: deviceName - "<기기 모델> <SoC>", hostId - "<기기 모델>/<SoC>",
 *       battPct* - 배터리 %, battUahDelta - 충전량 변화(µAh), battTempC* - 배터리 온도(℃), charging - AC / USB / WIRELESS / NONE,
 *       thermal* - 발열 상태 이름(NONE … SHUTDOWN), thermalHeadroom - 발열 여유, pssPeakMb - 앱 PSS 최댓값(MB),
 *       cpuTimeSec - 프로세스 CPU 시간(초), cpuPercentAvg - 평균 CPU 사용률(%), netRxDelta / netTxDelta - 앱 수신·송신 바이트 변화
 */
data class DeviceSample(
    val deviceName: String,               // 기기·SoC 이름
    val hostId: String,                   // 기기 식별
    val battPctStart: Int? = null,        // 시작 배터리 %
    val battPctEnd: Int? = null,          // 끝 배터리 %
    val battUahDelta: Long? = null,       // 충전량 변화
    val battTempCStart: Double? = null,   // 시작 배터리 온도
    val battTempCEnd: Double? = null,     // 끝 배터리 온도
    val charging: String = "",            // 충전 종류
    val thermalStart: String = "",        // 시작 발열 상태
    val thermalEnd: String = "",          // 끝 발열 상태
    val thermalHeadroom: Double? = null,  // 발열 여유
    val pssPeakMb: Double? = null,        // PSS 최댓값
    val cpuTimeSec: Double? = null,       // CPU 시간
    val cpuPercentAvg: Double? = null,    // 평균 CPU 사용률
    val netRxDelta: Long? = null,         // 수신 바이트 변화(추론 중 0 이어야 한다 — FR-10-02)
    val netTxDelta: Long? = null,         // 송신 바이트 변화
)

/**
 * 목적: 파일 1개 처리 결과 — result.csv·device.csv 의 공통 원천.
 * 필드: runId - 실행 ID(yyyyMMdd_HHmmss), startedAt - 이 파일을 시작한 시각(ISO8601), job - 작업,
 *       relDir - 언어 폴더 아래 상대 폴더, file - 원본 파일 이름(확장자 포함), audio / timings / stt / vad / device - 부품,
 *       engineVersion - 런타임 판 글자, workers - 동시 작업 수, modelLoadSec - 모델 적재 시간(작업의 첫 파일만 값, 나머지 0),
 *       chunks - STT 에 넣은 조각 수, chunkMaxSec - 가장 긴 조각(초), attempts - 시도 횟수, warmupRow - 워밍업 행 여부,
 *       purpose - 실행 목적(시간 측정 / 정확도), downloadOverlap - 다운로드와 겹쳐 돌았는지, note - 부가 설명(오류 코드 등)
 */
data class FileRecord(
    val runId: String,                    // 실행 ID
    val startedAt: String,                // 시작 시각
    val job: Job,                         // 작업
    val relDir: String,                   // 상대 폴더
    val file: String,                     // 파일 이름
    val audio: AudioInfo,                 // 음원 정보
    val timings: Timings,                 // 단계별 시간
    val stt: SttOutcome,                  // 전사 결과
    val vad: VadSummary?,                 // VAD 요약
    val device: DeviceSample,             // 기기 계측
    val engineVersion: String,            // 런타임 판
    val workers: Int,                     // 동시 작업 수
    val modelLoadSec: Double?,            // 모델 적재 시간
    val chunks: Int,                      // 조각 수
    val chunkMaxSec: Double? = null,      // 가장 긴 조각
    val attempts: Int = 1,                // 시도 횟수
    val warmupRow: Boolean = false,       // 워밍업 행
    val purpose: String = "",             // 실행 목적
    val downloadOverlap: Boolean = false, // 다운로드 겹침
    val note: String = "",                // 부가 설명
)
