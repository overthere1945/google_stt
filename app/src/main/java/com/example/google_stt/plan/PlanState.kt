/*
 * 파일명: plan/PlanState.kt
 * 목적 및 기능 (v2.6.0, 사용자 결정 Q44~Q49):
 * - 앱을 껐다 켜도 이어 갈 수 있게 남기는 값(SharedPreferences): 고른 입력·출력 폴더, 동시 작업 수, "목록 실행 중" 표시, 마지막으로 본 종료 기록 시각.
 * - 지난번 앱이 왜 꺼졌는지 읽기: ActivityManager.getHistoricalProcessExitReasons (ApplicationExitInfo, Android 11 이상 — 이 앱 minSdk 31).
 *   메모리 부족(REASON_LOW_MEMORY)·네이티브 충돌(REASON_CRASH_NATIVE)·신호(REASON_SIGNALED) 등과 꺼질 때 PSS/RSS 를 돌려준다.
 * - 폴더 주소는 이미 takePersistableUriPermission 으로 권한을 유지하고 있으므로, 권한이 아직 남아 있을 때만 되살린다.
 *
 * add-hyungchul-20261012-0310 — v2.6.0 목록 실행 이어 하기 보강
 */
package com.example.google_stt.plan

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 목적: 지난 프로세스 종료 기록 1건(화면·CSV 에 쓰기 좋은 형태).
 * 필드: timestamp — 종료 시각(ms), reason — 이유 코드, reasonName — 이유 이름, status — 종료 코드/신호 번호,
 *       pssMb / rssMb — 꺼질 때 메모리(MB), description — 시스템 설명, lowMemoryReportSupported — 이 기기가 메모리 부족 종료를 LOW_MEMORY 로 보고하는지
 */
data class PlanExit(
    val timestamp: Long,                    // 종료 시각(ms)
    val reason: Int,                        // ApplicationExitInfo.REASON_*
    val reasonName: String,                 // LOW_MEMORY 등
    val status: Int,                        // 종료 코드 또는 신호 번호
    val pssMb: Long,                        // 꺼질 때 PSS(MB)
    val rssMb: Long,                        // 꺼질 때 RSS(MB)
    val description: String,                // 시스템 설명(없으면 빈 문자열)
    val lowMemoryReportSupported: Boolean,  // isLowMemoryKillReportSupported()
) {
    /**
     * 메모리 부족으로 꺼졌는지.
     * 검토 지적 7: 메모리 부족 종료를 LOW_MEMORY 로 보고하지 않는 기기(isLowMemoryKillReportSupported = false)는
     * 같은 종료를 SIGNALED + 신호 9(SIGKILL)로 남길 수 있어 그 경우도 메모리 부족으로 본다(사용자 강제 종료는 USER_REQUESTED 로 따로 남는다).
     */
    val isLowMemory: Boolean
        get() = reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
            (!lowMemoryReportSupported && reason == ApplicationExitInfo.REASON_SIGNALED && status == 9)

    /** 사람이 읽는 한 줄: "2026-10-12 10:31:05 LOW_MEMORY (PSS 2310 MB / RSS 2875 MB)" */
    val summary: String
        get() = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))} $reasonName " +
            "(PSS $pssMb MB / RSS $rssMb MB${if (description.isNotEmpty()) ", $description" else ""})"
}

/** 목적: 이어 하기용 저장 값과 종료 기록 읽기 */
object PlanState {
    private const val PREFS = "google_stt_plan"          // SharedPreferences 이름
    const val KEY_IN = "in_uri"                          // 입력 폴더 트리 URI
    const val KEY_OUT = "out_uri"                        // 출력 폴더 트리 URI
    private const val KEY_WORKERS = "workers"            // 동시 작업 수
    private const val KEY_RUNNING = "plan_running"       // 목록 실행 중이었는지(꺼지면 true 로 남는다)
    private const val KEY_LAST_EXIT = "last_exit_ts"     // 마지막으로 처리한 종료 기록 시각
    private const val KEY_PLAN_IN = "plan_in_uri"        // 마지막 목록 실행의 입력 폴더(검토 지적 2b — 실행 중 폴더를 바꿔도 이어 하기는 이 폴더로)
    private const val KEY_PLAN_OUT = "plan_out_uri"      // 마지막 목록 실행의 출력 폴더

    /**
     * 이 프로세스에서 이어 하기 점검을 이미 했는지(검토 지적 1).
     * 메모리 부족으로 꺼진 뒤 최근 앱에서 다시 열면 Activity 가 저장 상태(savedInstanceState)와 함께 만들어지므로
     * "savedInstanceState == null" 로는 놓친다 → 프로세스가 새로 시작됐는지(이 값이 false)로 판단한다. 회전 등은 같은 프로세스라 다시 하지 않는다.
     */
    var checkedThisProcess = false

    /** 목적: SharedPreferences 를 연다. 입력: context / 리턴: SharedPreferences */
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 목적: 고른 폴더를 저장한다.
     * 입력: context, key — KEY_IN / KEY_OUT, uri — 고른 폴더 / 리턴: 없음
     */
    fun saveFolder(context: Context, key: String, uri: Uri) {
        prefs(context).edit().putString(key, uri.toString()).apply()
    }

    /**
     * 목적: 저장한 폴더를 되살린다 — 권한이 아직 남아 있을 때만.
     * 입력: context, key — KEY_IN / KEY_OUT, needWrite — 쓰기 권한도 필요한지(출력 폴더)
     * 리턴: 폴더 URI 또는 null(저장한 값이 없거나 권한이 없어졌음)
     */
    fun restoreFolder(context: Context, key: String, needWrite: Boolean): Uri? {
        val s = prefs(context).getString(key, null) ?: return null
        val uri = Uri.parse(s)
        val ok = context.contentResolver.persistedUriPermissions.any { p ->
            p.uri == uri && p.isReadPermission && (!needWrite || p.isWritePermission)   // 유지 중인 권한 목록에서 찾는다
        }
        return if (ok) uri else null
    }

    /** 목적: 저장한 동시 작업 수(없으면 기본 3). 입력: context / 리턴: 1..PLAN_WORKERS_MAX */
    fun workers(context: Context): Int =
        prefs(context).getInt(KEY_WORKERS, PLAN_WORKERS).coerceIn(1, PLAN_WORKERS_MAX)

    /** 목적: 동시 작업 수를 저장한다. 입력: context, n / 리턴: 없음 */
    fun saveWorkers(context: Context, n: Int) {
        prefs(context).edit().putInt(KEY_WORKERS, n.coerceIn(1, PLAN_WORKERS_MAX)).apply()
    }

    /**
     * 목적: "목록 실행 중" 표시를 남기거나 지운다(꺼지면 true 가 남아 다음 실행 때 '실행 도중 꺼졌다' 를 안다).
     * 입력: context, running / 리턴: 없음
     * 비고: commit() — 바로 디스크에 써야 직후에 앱이 죽어도 남는다.
     */
    fun setRunning(context: Context, running: Boolean) {
        prefs(context).edit().putBoolean(KEY_RUNNING, running).commit()
    }

    /**
     * 목적: 목록을 시작할 때 그 목록의 입력·출력 폴더를 따로 남긴다(검토 지적 2b).
     * 입력: context, inUri, outUri / 리턴: 없음
     */
    fun savePlanFolders(context: Context, inUri: Uri, outUri: Uri) {
        prefs(context).edit().putString(KEY_PLAN_IN, inUri.toString()).putString(KEY_PLAN_OUT, outUri.toString()).commit()
    }

    /**
     * 목적: 마지막 목록 실행의 폴더를 되살린다(권한이 남아 있을 때만).
     * 입력: context / 리턴: Pair(입력, 출력) — 없으면 각각 null
     */
    fun planFolders(context: Context): Pair<Uri?, Uri?> =
        Pair(restoreFolder(context, KEY_PLAN_IN, needWrite = false), restoreFolder(context, KEY_PLAN_OUT, needWrite = true))

    /** 목적: 지난번 목록 실행 도중 앱이 꺼졌는지. 입력: context / 리턴: true 면 실행 중 표시가 남아 있다 */
    fun wasRunning(context: Context): Boolean = prefs(context).getBoolean(KEY_RUNNING, false)

    /**
     * 목적: 아직 처리하지 않은 종료 기록들을 최근 것부터 읽는다(같은 기록을 두 번 보고하지 않게 가장 최근 시각을 저장).
     * 입력: context / 리턴: PlanExit 목록(새 기록 없음·읽기 실패면 빈 목록)
     * 비고: 검토 지적 2a — 1건만 읽으면 메모리 부족 종료 뒤의 다른 종료(사용자 강제 종료 등)에 가려지므로 최대 10건을 본다.
     */
    fun newExits(context: Context): List<PlanExit> = runCatching {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val last = prefs(context).getLong(KEY_LAST_EXIT, 0L)
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).filter { it.timestamp > last }   // 최근 것부터
        if (infos.isEmpty()) return emptyList()
        prefs(context).edit().putLong(KEY_LAST_EXIT, infos.maxOf { it.timestamp }).apply()
        val lowMemReport = ActivityManager.isLowMemoryKillReportSupported()
        infos.map { info ->
            PlanExit(
                timestamp = info.timestamp,
                reason = info.reason,
                reasonName = reasonName(info.reason),
                status = info.status,
                pssMb = info.pss / 1024,                                               // KB → MB
                rssMb = info.rss / 1024,
                description = info.description.orEmpty(),
                lowMemoryReportSupported = lowMemReport,
            )
        }
    }.getOrDefault(emptyList())

    /**
     * 목적: 종료 이유 코드 → 이름.
     * 입력: reason — ApplicationExitInfo.REASON_* / 리턴: LOW_MEMORY 등(모르는 값은 REASON_<숫자>)
     */
    fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"                 // 메모리 부족으로 시스템이 종료
        ApplicationExitInfo.REASON_CRASH -> "CRASH"                           // Java/Kotlin 예외로 종료
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"             // 네이티브(sherpa·onnxruntime 등) 충돌
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"                     // 신호로 종료(제조사 메모리 관리가 이렇게 보고하기도 한다)
        ApplicationExitInfo.REASON_ANR -> "ANR"                               // 응답 없음
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"                   // 앱 스스로 종료
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"         // 사용자가 강제 종료·앱 정보에서 중지
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"   // 권한이 바뀌어 종료
        ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"       // 앱 업데이트(Android Studio 다시 설치)
        ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        else -> "REASON_$reason"
    }
}
