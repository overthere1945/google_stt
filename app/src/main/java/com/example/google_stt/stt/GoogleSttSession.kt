/*
 * 파일명: stt/GoogleSttSession.kt
 * 목적 및 기능:
 * - Google 계열 STT(ML Kit Basic / Android 플랫폼 on-device) 호출을 한곳에서 관리한다.
 *     · transcribeOne()            : PCM 1조각을 엔진에 맞는 경로로 전사(세션 수 집계)
 *     · transcribeWithQuotaRetry() : SODA 할당량에 막히면 쉬었다가 1회 재시도
 *     · transcribeChunks()         : 여러 조각을 차례로 전사해 하나의 결과로 합침
 *     · warmUpEngine()             : 배치 시작 전 1초 무음으로 엔진 응답 확인
 * - sttSessionCount 로 배치 중 연 세션 수를 센다(SODA 세션 한도 약 35 대응).
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 의 위 4개 함수와 sttSessionCount 를 내용 변경 없이 옮겼다.
 */
package com.example.google_stt.stt

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale

/**
 * 목적: Google 계열 STT 세션 관리자.
 * 입력(생성자): mlKit — ML Kit 전사기, platform — 플랫폼 on-device 전사기, log — 화면 로그 함수
 */
class GoogleSttSession(
    private val mlKit: MlKitTranscriber,
    private val platform: PlatformOnDeviceTranscriber,
    private val log: (String) -> Unit,
) {

    // add-hyungchul-20260915-2200
    /**
     * 배치를 시작한 뒤 지금까지 연 STT 세션 수(사전 점검 warm-up 포함).
     *
     * ★ 이 값을 세는 이유 (2026-09-15 원인 확정)
     *   AICore 에는 공개 문서에 없는 "앱당 추론 할당량" 이 있다.
     *   실측에서 D20(실시간 ×5) 실행과 REALTIME(실시간 ×1) 실행이
     *   경과 시간은 256초 대 1240초로 5배 차이가 나는데도
     *   똑같이 "35세션 성공 → 36번째 세션부터 먹통" 으로 죽었다.
     *   즉 공급 속도도 경과 시간도 아니고 "세션 수" 가 한도다.
     *   그래서 세션 수를 세어 로그와 CSV 에 남기고, 한도에 닿기 전에 미리 경고한다.
     */
    var sttSessionCount = 0

    // ──────────── add-hyungchul-20260914-2350 : 조각 전사 / 사전 점검 ────────────
    /**
     * 목적: PCM 1조각을 엔진에 맞는 경로로 전사한다(기존 호출부를 한 군데로 모은 것뿐이다).
     * 입력: pcm, modelConfig, locale, feed, onFed
     * 출력: 없음
     * 리턴: SttOutcome
     * 예외: ML Kit 모드 설정이 없으면 IOException
     */
    suspend fun transcribeOne(
        pcm: ByteArray,
        modelConfig: ModelConfig,
        locale: Locale,
        feed: FeedConfig,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        // add-hyungchul-20260915-2200 : 세션을 하나 여는 시점에 센다(실패해도 할당량은 소모된다).
        sttSessionCount++
        if (sttSessionCount == QUOTA_WARN_SESSIONS) {
            log(
                "[quota] 세션 ${sttSessionCount}개째다. SODA 세션 한도(실측 약 " +
                    "${QUOTA_BUDGET_SESSIONS}세션)이 가까워졌다. 곧 응답이 멈출 수 있다.",
            )
        }
        return when (modelConfig.engine) {
            SttEngine.ML_KIT -> {
                val mode = modelConfig.mlKitMode
                    ?: throw IOException("ML Kit 모드 설정이 없습니다.")
                mlKit.transcribeMlKit(pcm, locale, mode, feed, onFed)
            }

            SttEngine.ANDROID_PLATFORM_ON_DEVICE ->
                platform.transcribeAndroidOnDevice(pcm, locale, feed, onFed)
        }
    }

    /**
     * add-hyungchul-20260915-2200
     * 목적: 할당량으로 막힌 경우 잠깐 쉬었다가 다시 시도한다.
     * 입력: chunks, modelConfig, locale, feed, onFed, relName(로그용)
     * 출력: 없음
     * 리턴: SttOutcome
     * 예외: 재시도까지 실패하면 마지막 예외를 그대로 던진다.
     *
     * 배경: AICore 할당량은 "굴러가는 창(rolling window)" 이라 시간이 지나면 다시 채워진다는
     *       보고가 있다(googlesamples/mlkit issue #1070, 약 2.5분 뒤 회복).
     *       그래서 타임아웃 한 번에 파일을 포기하지 말고, 한 번은 쉬었다가 다시 해 본다.
     *       배치 전체를 버리는 것보다 몇 분 기다리는 쪽이 훨씬 싸다.
     */
    suspend fun transcribeWithQuotaRetry(
        chunks: List<ByteArray>,
        modelConfig: ModelConfig,
        locale: Locale,
        feed: FeedConfig,
        relName: String,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        // change-hyungchul-20261009-1626 : 조각이 여러 개(EPD A안 / 10초 통일)면 재시도를 조각 단위로 한다(검토 지적 E).
        //   예전처럼 파일 전체를 처음부터 다시 돌리면 이미 끝난 조각의 세션을 또 써서 SODA 한도에 빨리 닿는다.
        if (chunks.size > 1) return transcribeChunks(chunks, modelConfig, locale, feed, onFed, relName)
        var attempt = 0                                          // 재시도 횟수
        while (true) {
            try {
                return transcribeChunks(chunks, modelConfig, locale, feed, onFed)
            } catch (e: TimeoutCancellationException) {
                attempt++
                // change-hyungchul-20260916-1000
                //   한도를 이미 넘긴 뒤라면 쉬어도 소용이 없다(실측: 180초 휴식 + 재시도 전부 실패,
                //   파일당 8.1분을 헛되이 썼다). 그때는 바로 던져서 상위에서 배치를 끝내게 한다.
                if (attempt > QUOTA_RETRY_MAX || sttSessionCount > QUOTA_BUDGET_SESSIONS) throw e
                log(
                    "[quota] $relName 이 응답하지 않았다(세션 ${sttSessionCount}개째). " +
                        "AICore 할당량으로 보고 ${QUOTA_COOLDOWN_MS / 1000}초 쉬었다가 " +
                        "다시 시도한다 ($attempt/$QUOTA_RETRY_MAX).",
                )
                delay(QUOTA_COOLDOWN_MS)                          // 할당량이 다시 채워질 시간을 준다
            }
        }
    }

    /**
     * 목적: Speech EPD 로 나눈 조각들을 차례로 전사하고 하나의 결과로 합친다.
     * 입력: chunks — 조각 목록(1개면 예전과 동일한 단일 세션),
     *       modelConfig, locale, feed, onFed(파일 전체 기준 진행률 0.0~1.0)
     * 출력: 없음
     * 리턴: 합쳐진 SttOutcome
     *
     * 합치는 규칙:
     *   text          — 조각 전사문을 공백 하나로 이어 붙인다(빈 조각은 건너뛴다).
     *   readyMs/first — 첫 조각 값(체감 응답성은 첫 조각이 대표한다).
     *   feed/tail/wall— 전 조각의 합(파일 1건을 처리하는 데 실제로 든 시간).
     *   segments      — 전 조각의 합.
     *   engineNote    — 조각 번호를 붙여 모두 남긴다.
     * 비고: 조각 하나라도 예외가 나면 그대로 위로 던진다. 반쪽짜리 전사문을
     *       정상처럼 기록하면 WER 이 조용히 오염되기 때문이다.
     */
    suspend fun transcribeChunks(
        chunks: List<ByteArray>,
        modelConfig: ModelConfig,
        locale: Locale,
        feed: FeedConfig,
        onFed: (Double) -> Unit,
        relName: String = "",                                    // change-hyungchul-20261009-1626 : 조각 단위 재시도 로그용(빈 문자열이면 재시도 없음)
    ): SttOutcome {
        // 조각이 1개면 예전 경로를 그대로 탄다(진행률 계산도 건드리지 않는다).
        if (chunks.size <= 1) {
            return transcribeOne(chunks.firstOrNull() ?: ByteArray(0), modelConfig, locale, feed, onFed)
        }

        val totalBytes = chunks.sumOf { it.size }.coerceAtLeast(1)   // 진행률 분모
        var doneBytes = 0                                            // 여기까지 끝난 바이트
        val texts = ArrayList<String>(chunks.size)
        val notes = ArrayList<String>()

        var readyMs = -1L
        var firstResultMs = -1L
        var feedMs = 0L
        var tailMs = 0L
        var wallMs = 0L
        var segments = 0
        var mlkitStatus = ""
        var checkStatusMs = -1L
        var downloadMs = -1L

        for ((i, chunk) in chunks.withIndex()) {
            val base = doneBytes                                     // 이 조각이 시작될 때의 누적 바이트
            val weight = chunk.size.toDouble()
            // change-hyungchul-20261009-1626 : relName 이 있으면 이 조각만 할당량 재시도한다(QUOTA_RETRY_MAX 회, 한도 초과 뒤에는 즉시 포기)
            val one = transcribeChunkWithRetry(chunk, modelConfig, locale, feed, relName, i, chunks.size) { fed ->
                // 조각 내부 진행률(0~1)을 파일 전체 진행률로 환산한다.
                onFed((base + fed * weight) / totalBytes)
            }

            if (one.text.isNotBlank()) texts.add(one.text.trim())
            if (i == 0) {                                            // 첫 조각만 대표값으로 쓴다
                readyMs = one.readyMs
                firstResultMs = one.firstResultMs
                mlkitStatus = one.mlkitStatus
                checkStatusMs = one.checkStatusMs
                downloadMs = one.downloadMs
            }
            if (one.feedMs >= 0) feedMs += one.feedMs
            if (one.tailMs >= 0) tailMs += one.tailMs
            if (one.sttWallMs >= 0) wallMs += one.sttWallMs
            segments += one.segments
            if (one.engineNote.isNotEmpty()) notes.add("[조각${i + 1}] ${one.engineNote}")

            doneBytes += chunk.size
        }

        return SttOutcome(
            text = texts.joinToString(" "),
            readyMs = readyMs,
            firstResultMs = firstResultMs,
            feedMs = feedMs,
            tailMs = tailMs,
            sttWallMs = wallMs,
            segments = segments,
            mlkitStatus = mlkitStatus,
            checkStatusMs = checkStatusMs,
            downloadMs = downloadMs,
            engineNote = notes.joinToString(" | "),
        )
    }

    /**
     * change-hyungchul-20261009-1626
     * 목적: 조각 1개를 전사하고, 할당량으로 막히면(시간 초과) 쉬었다가 그 조각만 다시 시도한다(검토 지적 E).
     * 입력: chunk, modelConfig, locale, feed — 전사 조건
     *       relName — 로그용 파일 이름(빈 문자열이면 재시도하지 않는다), index/count — 조각 번호/전체 수
     *       onFed — 조각 내부 진행률(0~1)
     * 리턴: SttOutcome
     * 예외: 재시도까지 실패하거나 세션 한도를 이미 넘겼으면 TimeoutCancellationException 을 그대로 던진다
     */
    private suspend fun transcribeChunkWithRetry(
        chunk: ByteArray,
        modelConfig: ModelConfig,
        locale: Locale,
        feed: FeedConfig,
        relName: String,
        index: Int,
        count: Int,
        onFed: (Double) -> Unit,
    ): SttOutcome {
        var attempt = 0                                          // 재시도 횟수
        while (true) {
            try {
                return transcribeOne(chunk, modelConfig, locale, feed, onFed)
            } catch (e: TimeoutCancellationException) {
                attempt++
                if (relName.isEmpty() || attempt > QUOTA_RETRY_MAX || sttSessionCount > QUOTA_BUDGET_SESSIONS) throw e
                log(
                    "[quota] $relName 조각${index + 1}/$count 이 응답하지 않았다(세션 ${sttSessionCount}개째). " +
                        "${QUOTA_COOLDOWN_MS / 1000}초 쉬었다가 이 조각만 다시 시도한다 ($attempt/$QUOTA_RETRY_MAX).",
                )
                delay(QUOTA_COOLDOWN_MS)                          // 할당량이 다시 채워질 시간을 준다
            }
        }
    }

    /**
     * 목적: 배치를 시작하기 전에 엔진이 살아 있는지 1초짜리 무음으로 시험해 본다.
     * 입력: modelConfig, locale
     * 출력: 없음 (로그를 남긴다)
     * 리턴: "OK" / "TIMEOUT" / "SKIP"
     *
     * 배경(실측):
     *   2026-09-14 실행에서 customer/01,02,03 이 연속으로 248초씩 타임아웃했다.
     *   같은 PCM 이 전날에는 12.4초에 끝났으므로 음원이나 설정 문제가 아니라
     *   ML Kit 인식 서비스가 먹통이 된 상태였다.
     *   그대로 두면 24개 × 248초 = 약 99분을 통째로 날린다.
     *   그래서 배치 시작 전에 짧은 입력으로 응답 여부만 먼저 본다.
     *
     * 비고: 무음이므로 NO_SPEECH_DETECTED 나 빈 결과가 나오는 것이 정상이다.
     *       "응답이 왔는가" 만 보면 되므로 예외가 나도 살아 있는 것으로 친다.
     *       응답 자체가 없을 때(=타임아웃) 만 먹통으로 판정한다.
     */
    suspend fun warmUpEngine(modelConfig: ModelConfig, locale: Locale): String {
        val silence = ByteArray(BYTES_PER_SEC * WARMUP_SECONDS)      // 1초 무음 (0x00 = 무음)
        // change-hyungchul-20260915-1400 : 사전 점검도 실시간 속도로 넣는다.
        //   공식 문서가 요구하는 속도가 실시간이므로, 점검부터 규격대로 해야 의미가 있다.
        val warmFeed = FeedConfig("WARMUP", WARMUP_FEED_DELAY_MS)
        val outcome = withTimeoutOrNull(WARMUP_TIMEOUT_MS) {
            runCatching { transcribeOne(silence, modelConfig, locale, warmFeed) {} }
        } ?: return "TIMEOUT"                                        // 아예 응답이 없다

        // change-hyungchul-20260915-1400
        //   무음이므로 NO_SPEECH 같은 예외가 나오는 것은 "엔진이 살아서 대답했다" 는 뜻이다.
        //   반대로 우리가 건 시간 제한(checkStatus 무응답 등)에 걸린 예외라면 먹통이다.
        val e = outcome.exceptionOrNull() ?: return "OK"
        val msg = e.message ?: ""
        return if (e is TimeoutCancellationException ||
            msg.contains("응답하지 않았다") ||
            msg.contains("무응답")
        ) {
            "TIMEOUT"
        } else {
            "OK"
        }
    }
}
