/*
 * 파일명: engine/stt/SttEngineAdapter.kt
 * 목적 및 기능:
 * - STT 엔진 4종(sherpa-onnx 오프라인·스트리밍, Vosk, ML Kit)이 지키는 하나의 약속(4단계 3-1).
 *   실행 파이프라인은 이 약속대로만 부른다: load(작업마다 1회) → transcribe(파일마다) → close.
 * - v3 의 stt/StreamingSttTranscriber.kt 를 넓힌 것이다. 바뀐 점:
 *     · 모델 폴더를 인자로 받는다(앱 전용 저장소의 자산 폴더 — '모든 파일 접근' 권한이 필요 없다)
 *     · suspend 함수이고 취소에 반응한다(조각·100 ms 단위로 확인, 기다림은 delay)
 *     · 결과는 domain 의 SttOutcome(상태 OK / NO_MATCH 포함). 실패는 예외로 알린다
 * - 조각(AudioChunk)은 PCM16 LE 바이트를 그대로 가진다. 설계의 ShortArray·시작/끝 시각은 쓰지 않는다 —
 *   디코더·VAD·조각 묶기가 모두 ByteArray 로 흐르고, 묶은 조각은 원본 시각을 갖지 않기 때문이다.
 *
 * add-hyungchul-20261106-2132 — [T5-1]
 */
package com.example.google_stt.engine.stt

import com.example.google_stt.domain.AppError                 // E-EN-01
import com.example.google_stt.domain.catalog.Language        // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec       // 모델 정의
import com.example.google_stt.domain.condition.FeedChoice    // 공급 속도
import com.example.google_stt.domain.csv.SttOutcome          // 전사 결과
import com.example.google_stt.domain.csv.SttStatus           // OK / NO_MATCH
import java.io.File                                          // 모델 폴더

/**
 * 목적: STT 에 넣는 조각 1개.
 * 필드: pcm - 16 kHz / mono / PCM16 LE(오프라인 엔진은 모델의 조각 상한 이하)
 */
class AudioChunk(val pcm: ByteArray) {
    /** 조각 길이(초) */
    val sec: Double get() = pcm.size / BYTES_PER_SEC.toDouble()
}

/**
 * 목적: 모델 적재 결과.
 * 필드: loadSec - 적재에 걸린 시간(초, CSV model_load_sec), engineVersion - 런타임 이름·판(CSV engine_version)
 */
data class LoadInfo(val loadSec: Double, val engineVersion: String)

/** 목적: STT 엔진 공통 약속. */
interface SttEngineAdapter : AutoCloseable {

    /** 이 어댑터가 돌리는 모델 */
    val spec: ModelSpec

    /**
     * add-hyungchul-20261106-2229 : [T6-2]
     * 엔진이 쓸 수 있는 양을 다 썼는지(ML Kit 의 세션 한도). true 인 채 먹통이 나면 연속 실패를 더 기다리지 않고 작업을 끝낸다.
     * 한도가 없는 엔진은 항상 false
     */
    val pastBudget: Boolean get() = false

    /**
     * 목적: 모델을 적재한다(작업 시작 때 1회).
     * 입력: modelDir - 자산 폴더(ML Kit 은 시스템이 모델을 관리하므로 null), lang - 평가 언어
     * 출력: 엔진이 메모리에 올라간다
     * 리턴: LoadInfo - 적재 시간과 런타임 판
     * 예외: AppError.ModelLoad(E-EN-01) - 파일이 없거나 엔진을 만들 수 없을 때. 취소되면 CancellationException
     */
    suspend fun load(modelDir: File?, lang: Language): LoadInfo

    /**
     * 목적: 조각들을 차례로 전사해 하나의 결과로 잇는다.
     * 입력: chunks - 조각들(빈 조각은 건너뛴다), feed - 공급 속도(오프라인 엔진은 쓰지 않는다), lang - 평가 언어
     * 출력: 없음
     * 리턴: SttOutcome - 전사문이 비면 status = NO_MATCH(오류 아님, E-EN-08)
     * 예외: 엔진 오류는 그대로 던진다(파이프라인이 파일 ERROR 로 적는다). 취소되면 CancellationException
     */
    suspend fun transcribe(chunks: List<AudioChunk>, feed: FeedChoice, lang: Language): SttOutcome
}

/**
 * 목적: 모델 폴더와 필요한 파일이 있는지 확인한다(없는 파일로 네이티브 엔진을 만들면 앱이 통째로 죽으므로 먼저 본다).
 * 입력: spec - 모델 정의, modelDir - 자산 폴더
 * 출력: 없음
 * 리턴: File - 확인된 모델 폴더
 * 예외: AppError.ModelLoad(E-EN-01) - 폴더가 없거나 파일이 빠졌을 때(빠진 파일과 위치를 알려 준다)
 */
internal fun requireModelDir(spec: ModelSpec, modelDir: File?): File {
    val dir = modelDir ?: throw AppError.ModelLoad("${spec.displayName}: 모델 폴더가 주어지지 않았다")
    val missing = spec.files.values.filter { !File(dir, it).exists() }                 // 빠진 파일(또는 폴더)
    if (missing.isNotEmpty()) {
        throw AppError.ModelLoad("${spec.displayName}: 파일이 없다 — ${missing.joinToString(", ")} (위치 ${dir.absolutePath})")
    }
    return dir
}

/**
 * 목적: 엔진이 잰 값으로 전사 결과를 만든다(세 엔진이 같은 규칙을 쓰게).
 * 입력: text - 이어 붙인 전사문, utterances - 디코딩한 조각 수(또는 확정 문장 수), firstResultMs - 첫 응답(-1 = 없음),
 *       tailMs - 공급 끝 → 최종 결과(-1 = 못 잼), feedMs - 공급에 걸린 시간(null = 공급 구간이 없는 오프라인 엔진)
 * 출력: 없음
 * 리턴: SttOutcome - 전사문이 비면 NO_MATCH, 아니면 OK
 */
internal fun sttOutcome(text: String, utterances: Int, firstResultMs: Long, tailMs: Long, feedMs: Long?): SttOutcome {
    val t = text.trim()
    return SttOutcome(
        text = t,
        utterances = utterances,
        status = if (t.isEmpty()) SttStatus.NO_MATCH else SttStatus.OK,                // 빈 결과는 오류가 아니다(E-EN-08)
        firstResultMs = firstResultMs.takeIf { it >= 0 },
        tailMs = tailMs.takeIf { it >= 0 },
        feedSec = feedMs?.let { it / 1000.0 },
        feedMeasured = feedMs != null,
    )
}
