/*
 * 파일명: run/RunState.kt
 * 목적 및 기능:
 * - 실행 1회의 상태(4단계 5-4). 화면(실행 탭)·알림이 이 값 하나를 보고 그린다.
 *     IDLE → PREPARING → RUNNING ⇄ WAITING(발열 / 자산) → STOPPING → FINISHED | STOPPED | ERROR
 *
 * add-hyungchul-20261106-2242 — [T6-6]
 */
package com.example.google_stt.run

/** 목적: 실행 단계. */
enum class RunPhase {
    IDLE,        // 실행한 적 없음
    PREPARING,   // 시작 요청을 받고 준비 중(서비스 시작, 기록 읽기)
    RUNNING,     // 작업을 돌리는 중
    WAITING,     // 기다리는 중 — 이유는 RunProgress.waiting(THERMAL / ASSET)
    STOPPING,    // 중지 요청을 받고 마무리 중
    FINISHED,    // 모든 작업을 마쳤다(실패한 작업이 있어도 끝까지 갔으면 FINISHED)
    STOPPED,     // 사용자 중지로 끝났다(남은 작업이 있다 — 이어 하기 대상)
    ERROR;       // 실행을 계속할 수 없어 멈췄다(출력 폴더 오류 등 — message 에 사유)

    /** 실행이 진행 중인지(이 동안에는 새 실행을 시작하지 않는다) */
    val active: Boolean get() = this == PREPARING || this == RUNNING || this == WAITING || this == STOPPING
}

/** 목적: 중지 방식. */
enum class StopMode {
    NOW,             // 지금 중지 — 돌던 작업은 INTERRUPTED(이어 하기 때 처음부터)
    AFTER_CURRENT,   // 이 작업 끝나고 — 돌던 작업은 끝까지 가고 새 작업을 시작하지 않는다
}

/**
 * 목적: 실행 상태.
 * 필드: phase - 단계, progress - 작업 진행(전체·끝난 수·돌고 있는 작업·기다리는 이유), message - 사유(오류·중지 안내, 없으면 빈 글),
 *       stopAfterCurrent - "이 작업 끝나고 중지" 가 걸려 있는지
 */
data class RunState(
    val phase: RunPhase = RunPhase.IDLE,
    val progress: RunProgress = RunProgress(),
    val message: String = "",
    val stopAfterCurrent: Boolean = false,
)
