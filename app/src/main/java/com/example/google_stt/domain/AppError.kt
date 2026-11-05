/*
 * 파일명: domain/AppError.kt
 * 목적 및 기능:
 * - 앱이 분류해서 다루는 오류를 한 곳에 둔다(6단계 6-2 오류 처리 표의 코드 E-…).
 *   오류마다 코드와 "다시 시도해도 되는지" 를 가진다. 잡은 쪽은 코드와 메시지를 로그에 남긴다(조용히 삼키지 않는다).
 * - 취소(CancellationException)는 오류가 아니므로 여기에 넣지 않는다.
 * - 오류 종류는 그 오류를 처음 쓰는 Task 에서 하나씩 더한다(지금은 E-AS-09).
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
}
