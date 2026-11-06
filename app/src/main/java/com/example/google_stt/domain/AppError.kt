/*
 * 파일명: domain/AppError.kt
 * 목적 및 기능:
 * - 앱이 분류해서 다루는 오류를 한 곳에 둔다(6단계 6-2 오류 처리 표의 코드 E-…).
 *   오류마다 코드와 "다시 시도해도 되는지" 를 가진다. 잡은 쪽은 코드와 메시지를 로그에 남긴다(조용히 삼키지 않는다).
 * - 취소(CancellationException)는 오류가 아니므로 여기에 넣지 않는다.
 * - 오류 종류는 그 오류를 처음 쓰는 Task 에서 하나씩 더한다.
 *
 * add-hyungchul-20261106-0037 — [T2-2b]
 */
package com.example.google_stt.domain

/**
 * 목적: 분류된 앱 오류의 바탕 클래스.
 * 필드: code - 오류 코드(예 E-AS-09), retryable - 같은 동작을 다시 시도해도 되는지
 */
sealed class AppError(
    val code: String,            // 오류 코드
    val retryable: Boolean,      // 재시도 가능 여부
    detail: String,              // 사람이 읽는 원인
    cause: Throwable? = null,    // 원래 예외(있으면)
) : Exception("$code: $detail", cause) {

    /** E-AS-09 자산 매니페스트(asset_manifest.json)를 읽을 수 없다 → 해시 없이 동작("미검증")하고 경고를 남긴다 */
    class ManifestCorrupt(detail: String, cause: Throwable? = null) :
        AppError("E-AS-09", false, "자산 매니페스트를 읽을 수 없다 — $detail", cause)

    // ── add-hyungchul-20261106-1656 : [T4-1] 자산 내려받기·검증 오류(6단계 6-2 "자산") ──

    /** E-AS-01 TLS 인증서 오류(회사망 HTTPS 검사로 보임) → 다시 시도하지 않고 외부망·PC 가져오기를 안내한다 */
    class AssetTls(detail: String, cause: Throwable? = null) :
        AppError("E-AS-01", false, "보안 연결(TLS) 인증서 오류 — $detail", cause)

    /** E-AS-02 네트워크 끊김·시간 초과(E-AS-04 서버 5xx 포함) → 5·15·45초 뒤 다시 시도, 그래도 안 되면 실패 */
    class AssetNetwork(detail: String, cause: Throwable? = null) :
        AppError("E-AS-02", true, "네트워크 오류 — $detail", cause)

    /** E-AS-03 HTTP 4xx(원본 주소가 바뀌었을 수 있음) → 다시 시도하지 않는다 */
    class AssetHttp(val status: Int, detail: String) :
        AppError("E-AS-03", false, "HTTP $status — $detail")

    /** E-AS-06 받은 파일의 SHA256 이 매니페스트와 다르다 → 지우고 한 번 다시 받는다 */
    class AssetHashMismatch(detail: String) :
        AppError("E-AS-06", true, "파일 해시 불일치 — $detail")

    /** E-AS-07 압축 해제 실패·위험한 경로(..)·필요한 파일 없음 → 압축 파일을 지우고 한 번 다시 받는다 */
    class AssetExtract(detail: String, cause: Throwable? = null) :
        AppError("E-AS-07", true, "압축 해제 실패 — $detail", cause)

    /** E-AS-08 내려받을 저장공간이 부족하다 → 받기를 시작하지 않고 필요·여유 용량을 알린다 */
    class AssetNoSpace(val requiredBytes: Long, val freeBytes: Long) :
        AppError("E-AS-08", false, "저장공간 부족 — 필요 $requiredBytes B, 여유 $freeBytes B")

    /** add-hyungchul-20261106-2132 : [T5-1] E-EN-01 STT 모델을 적재할 수 없다(파일 없음·엔진 생성 실패) → 그 작업을 실패로 끝내고 다음 작업으로 간다 */
    class ModelLoad(detail: String, cause: Throwable? = null) :
        AppError("E-EN-01", false, "모델 적재 실패 — $detail", cause)

    /** add-hyungchul-20261106-2144 : [T5-5] E-EN-05 ML Kit 인식 엔진이 응답하지 않는다(세션 한도·먹통) → 그 작업을 실패로 끝내고 남은 ML Kit 작업은 건너뛴다(D29) */
    class MlKitStuck(detail: String, cause: Throwable? = null) :
        AppError("E-EN-05", false, "ML Kit 엔진이 응답하지 않는다 — $detail", cause)

    /** add-hyungchul-20261106-1633 : [T3-1] E-OUT-01 출력 폴더에 쓸 수 없다(권한이 사라졌거나 폴더·파일을 만들 수 없음) → 실행을 멈추고 폴더를 다시 고르게 한다 */
    class OutputUnavailable(detail: String, cause: Throwable? = null) :
        AppError("E-OUT-01", false, "출력 폴더에 쓸 수 없다 — $detail", cause)

    /** add-hyungchul-20261106-1633 : [T3-1] E-OUT-02 저장공간이 부족하다 → 실행을 멈추고 필요·여유 용량을 알린다 */
    class OutputNoSpace(detail: String, cause: Throwable? = null) :
        AppError("E-OUT-02", false, "저장공간이 부족하다 — $detail", cause)
}
