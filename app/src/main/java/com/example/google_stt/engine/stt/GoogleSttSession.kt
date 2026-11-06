/*
 * 파일명: engine/stt/GoogleSttSession.kt
 * 목적 및 기능:
 * - ML Kit(시스템의 SODA 인식 서비스) 세션을 한곳에서 관리한다.
 *     · transcribeOne()    : PCM 1조각 = 세션 1개(세션 수 집계, 한도가 가까우면 경고)
 *     · transcribeChunks() : 여러 조각을 차례로 전사해 하나의 결과로 합친다. 응답이 없으면 쉬었다가 그 조각만 1회 다시
 *     · warmUp()           : 작업 시작 전 1초 무음으로 엔진이 대답하는지 본다
 * - v3 의 stt/GoogleSttSession.kt 를 옮긴 것이다. 규칙(세션 한도 약 35, 28 에서 경고, 180초 쉬고 1회 재시도, 한도를 넘긴 뒤에는
 *   바로 포기, warm-up 30초)은 그대로이고 바뀐 점은:
 *     · 세션 1개를 돌리는 일은 함수로 받는다(MlKitAdapter 가 준다) — v3 의 ModelConfig·Locale·FeedConfig 에 기대지 않는다
 *     · 파일 전체 재시도와 조각 재시도를 조각 재시도 하나로 합쳤다(조각이 1개면 둘은 같은 동작이다)
 *     · 시간 초과를 잡은 뒤에는 바깥 작업이 취소됐는지 확인한다(취소를 삼키지 않게)
 *
 * ★ 세션 수를 세는 이유(2026-09-15 원인 확정): AICore 에는 공개 문서에 없는 "앱당 추론 할당량" 이 있다.
 *   공급 속도·경과 시간과 무관하게 "35세션 성공 → 36번째부터 먹통" 으로 죽었다. 즉 한도는 세션 수다.
 *
 * add-hyungchul-20261106-2146 — [T5-5]
 */
package com.example.google_stt.engine.stt

import kotlinx.coroutines.CancellationException            // 취소
import kotlinx.coroutines.TimeoutCancellationException     // 시간 초과(응답 없음)
import kotlinx.coroutines.currentCoroutineContext          // 바깥 작업의 취소 확인
import kotlinx.coroutines.delay                            // 할당량 회복 기다리기
import kotlinx.coroutines.ensureActive                     // 취소 확인
import kotlinx.coroutines.withTimeout                      // warm-up 시간 제한

/**
 * 목적: 세션 1개(또는 합친 여러 세션)의 결과.
 * 필드: text - 전사문, firstResultMs - 세션 시작 → 첫 응답(-1 = 없음), feedMs - 공급 시간, tailMs - 공급 끝 → 최종 결과,
 *       finals - 확정 문장 수, note - 엔진이 남긴 말(NO_SPEECH_DETECTED 등 — 실패가 아닐 때만)
 */
internal class SessionResult(
    val text: String,           // 전사문
    val firstResultMs: Long,    // 첫 응답
    val feedMs: Long,           // 공급 시간
    val tailMs: Long,           // 꼬리 지연
    val finals: Int,            // 확정 문장 수
    val note: String = "",      // 엔진 메모
)

/**
 * 목적: ML Kit 세션 관리자.
 * 입력(생성자): one - 세션 1개를 돌리는 함수 (PCM, 100 ms 조각 사이 간격 ms) → 결과. 응답이 없으면 TimeoutCancellationException,
 *               log - 경고 한 줄을 받는 함수
 */
internal class GoogleSttSession(
    private val one: suspend (ByteArray, Long) -> SessionResult,
    private val log: (String) -> Unit,
) {
    /** 적재(warm-up 포함) 뒤 지금까지 연 세션 수 */
    var sessionCount = 0
        private set

    /** 세션 한도를 넘겼는지 — 넘긴 뒤의 무응답은 쉬어도 돌아오지 않는다(실측: 180초 휴식 + 재시도 전부 실패) */
    val pastBudget: Boolean get() = sessionCount > QUOTA_BUDGET_SESSIONS

    /** 목적: 세션 수를 0 으로 돌린다(작업을 시작할 때). 입력: 없음 / 출력: 집계 초기화 / 리턴: 없음 */
    fun reset() {
        sessionCount = 0
    }

    /**
     * 목적: PCM 1조각을 세션 1개로 전사한다.
     * 입력: pcm - 16 kHz / mono / PCM16 LE, delayMs - 공급 간격
     * 출력: 세션 수가 1 늘어난다(실패해도 할당량은 소모된다)
     * 리턴: SessionResult
     */
    suspend fun transcribeOne(pcm: ByteArray, delayMs: Long): SessionResult {
        sessionCount++                                           // 세션을 여는 시점에 센다
        if (sessionCount == QUOTA_WARN_SESSIONS) {
            log("[quota] 세션 ${sessionCount}개째다. SODA 세션 한도(실측 약 ${QUOTA_BUDGET_SESSIONS}세션)가 가까워졌다. 곧 응답이 멈출 수 있다.")
        }
        return one(pcm, delayMs)
    }

    /**
     * 목적: 조각들을 차례로 전사하고 하나의 결과로 합친다.
     * 입력: chunks - 조각 목록(1개면 세션 1개), delayMs - 공급 간격
     * 출력: 없음
     * 리턴: 합쳐진 SessionResult
     *
     * 합치는 규칙:
     *   text       - 조각 전사문을 공백 하나로 이어 붙인다(빈 조각은 건너뛴다)
     *   first      - 첫 조각 값(체감 응답성은 첫 조각이 대표한다)
     *   feed/tail  - 전 조각의 합(파일 1건을 처리하는 데 실제로 든 시간)
     *   finals     - 전 조각의 합
     *   note       - 조각 번호를 붙여 모두 남긴다(조각이 1개면 그대로)
     * 비고: 조각 하나라도 예외가 나면 그대로 위로 던진다. 반쪽짜리 전사문을 정상처럼 기록하면 WER 이 조용히 오염된다.
     */
    suspend fun transcribeChunks(chunks: List<ByteArray>, delayMs: Long): SessionResult {
        if (chunks.size <= 1) return transcribeChunkWithRetry(chunks.firstOrNull() ?: ByteArray(0), delayMs, 0, 1)
        val texts = ArrayList<String>(chunks.size)
        val notes = ArrayList<String>()
        var firstResultMs = -1L
        var feedMs = 0L
        var tailMs = 0L
        var finals = 0
        for ((i, chunk) in chunks.withIndex()) {
            val r = transcribeChunkWithRetry(chunk, delayMs, i, chunks.size)
            if (r.text.isNotBlank()) texts.add(r.text.trim())
            if (i == 0) firstResultMs = r.firstResultMs                // 첫 조각만 대표값으로 쓴다
            if (r.feedMs >= 0) feedMs += r.feedMs
            if (r.tailMs >= 0) tailMs += r.tailMs
            finals += r.finals
            if (r.note.isNotEmpty()) notes.add("[조각${i + 1}] ${r.note}")
        }
        return SessionResult(texts.joinToString(" "), firstResultMs, feedMs, tailMs, finals, notes.joinToString(" | "))
    }

    /**
     * 목적: 조각 1개를 전사하고, 응답이 없으면(시간 초과) 쉬었다가 그 조각만 다시 시도한다.
     * 입력: chunk - 조각, delayMs - 공급 간격, index / count - 조각 번호 / 전체 수(로그용)
     * 출력: 없음
     * 리턴: SessionResult
     * 예외: 재시도까지 실패하거나 세션 한도를 이미 넘겼으면 TimeoutCancellationException 을 그대로 던진다
     *
     * 배경: AICore 할당량은 "굴러가는 창" 이라 시간이 지나면 다시 채워진다는 보고가 있다(googlesamples/mlkit issue #1070, 약 2.5분 뒤 회복).
     */
    private suspend fun transcribeChunkWithRetry(chunk: ByteArray, delayMs: Long, index: Int, count: Int): SessionResult {
        var attempt = 0                                          // 재시도 횟수
        while (true) {
            try {
                return transcribeOne(chunk, delayMs)
            } catch (e: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()         // 바깥 작업이 취소된 것이면 여기서 취소로 끝낸다
                attempt++
                if (attempt > QUOTA_RETRY_MAX || pastBudget) throw e
                log(
                    "[quota] 조각 ${index + 1}/$count 이 응답하지 않았다(세션 ${sessionCount}개째). " +
                        "${QUOTA_COOLDOWN_MS / 1000}초 쉬었다가 이 조각만 다시 시도한다 ($attempt/$QUOTA_RETRY_MAX).",
                )
                delay(QUOTA_COOLDOWN_MS)                          // 할당량이 다시 채워질 시간을 준다
            }
        }
    }

    /**
     * 목적: 작업을 시작하기 전에 엔진이 살아 있는지 1초짜리 무음으로 시험해 본다.
     * 입력: 없음
     * 출력: 세션 1개를 쓴다
     * 리턴: Throwable? - 정상으로 대답했으면 null. 아니면 원인 예외 — isEngineDead(예외) 가 true 면 먹통(응답 없음),
     *       false 면 엔진은 대답했지만 쓸 수 없는 상태(모델 받기 실패, 권한 없음 등)
     *
     * 배경(실측 2026-09-14): 엔진이 먹통이면 파일마다 시간 초과까지 기다리다 끝난다(24개 × 248초 ≈ 99분).
     * 비고: 무음의 "말이 없다"(NO_SPEECH) 판정은 세션 함수가 빈 결과로 바꿔 주므로 예외가 아니다.
     *       v3 는 먹통만 가렸다. v4 는 그 밖의 오류도 돌려줘 작업을 시작하기 전에 알린다(파일마다 같은 오류가 반복되지 않게).
     */
    suspend fun warmUp(): Throwable? {
        val silence = ByteArray(BYTES_PER_SEC * WARMUP_SECONDS)      // 1초 무음(0x00)
        return try {
            withTimeout(WARMUP_TIMEOUT_MS) { transcribeOne(silence, WARMUP_FEED_DELAY_MS) }   // 점검도 실시간 속도로 넣는다
            null
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()                 // 바깥 작업의 취소는 그대로 올린다
            e                                                        // 아예 응답이 없다(isEngineDead = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e                                                        // checkStatus 무응답이면 먹통, 그 밖은 "대답했지만 쓸 수 없음"
        }
    }
}
