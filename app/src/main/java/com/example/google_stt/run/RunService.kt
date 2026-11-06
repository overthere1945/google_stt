/*
 * 파일명: run/RunService.kt
 * 목적 및 기능:
 * - 포그라운드 서비스(종류 specialUse). 지금은 "받기 모드" 최소판이다(T4-7): 자산을 받는 동안 알림을 띄우고
 *   부분 wake lock 을 쥐어, 화면이 꺼지거나 앱이 뒤로 가도 받기가 이어지게 한다. 실행(전사) 기능은 T6-6 에서 더한다.
 * - 시작: RunService.download(context, 자산 ID 들) — 화면(앱이 앞에 있을 때)에서 부른다. 중지: RunService.stop(context) 또는 알림의 [중지].
 * - 받는 순서는 작은 것부터. "Wi-Fi 전용" 설정이 켜져 있고 지금 연결이 데이터 요금이 드는 망이면 Wi-Fi 를 기다린다(E-AS-10).
 * - 결과는 AssetRepository 의 상태 흐름과 앱 로그에 남는다. 모두 끝나면 서비스는 스스로 멈춘다.
 * - add-hyungchul-20261106-2243 : [T6-6] "실행 모드" — RunController 가 시작을 요청하면(RunService.run) 이 서비스의 코루틴에서 실행 1회를 돌린다.
 *   알림에 진행(n/N·돌고 있는 작업·기다리는 이유)을 보이고 [지금 중지] [이 작업 후 중지] 를 둔다. wake lock 은 실행 내내 쥔다
 *   (설계는 발열 대기 때 놓으라고 했지만, 놓으면 화면이 꺼진 채 30초 간격 확인이 멈출 수 있어 쥐고 있는다).
 *
 * add-hyungchul-20261106-1721 — [T4-7]
 */
package com.example.google_stt.run

import android.app.Notification                           // 알림
import android.app.NotificationChannel                    // 알림 채널
import android.app.NotificationManager                    // 알림 띄우기
import android.app.PendingIntent                          // 알림 버튼이 보낼 요청
import android.app.Service                                // 서비스
import android.content.Context                            // 시작 요청 보내기
import android.content.Intent                             // 요청
import android.content.pm.ServiceInfo                     // 포그라운드 서비스 종류
import android.net.ConnectivityManager                    // 요금이 드는 망인지
import android.os.IBinder                                 // 바인딩(쓰지 않음)
import android.os.PowerManager                            // wake lock
import com.example.google_stt.AppContainer                // 객체 묶음
import com.example.google_stt.SttApp                      // Application
import com.example.google_stt.data.assets.AssetState      // 자산 상태
import kotlinx.coroutines.CancellationException           // 취소
import kotlinx.coroutines.CoroutineScope                  // 서비스 코루틴 범위
import kotlinx.coroutines.Dispatchers                     // 메인·입출력 스레드
import kotlinx.coroutines.Job                             // 알림 갱신 작업
import kotlinx.coroutines.SupervisorJob                   // 한 작업의 실패가 다른 작업을 끊지 않게
import kotlinx.coroutines.cancel                          // 범위 끝내기
import kotlinx.coroutines.cancelChildren                  // 진행 중인 받기 모두 취소
import kotlinx.coroutines.delay                           // 대기
import kotlinx.coroutines.flow.first                      // 설정 한 번 읽기
import kotlinx.coroutines.launch                          // 코루틴 시작
import kotlinx.coroutines.withContext                     // 스레드 바꾸기

/** 목적: 포그라운드 서비스(받기 모드). */
class RunService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.example.google_stt.action.DOWNLOAD"   // 자산 받기 시작
        const val ACTION_STOP = "com.example.google_stt.action.STOP"           // 지금 중지
        const val ACTION_RUN = "com.example.google_stt.action.RUN"             // add-hyungchul-20261106-2243 : [T6-6] 실행 시작(요청은 RunController 가 들고 있다)
        const val ACTION_STOP_AFTER = "com.example.google_stt.action.STOP_AFTER"   // add-hyungchul-20261106-2243 : [T6-6] 이 작업 끝나고 중지
        const val EXTRA_ASSET_IDS = "asset_ids"                                // 받을 자산 ID 들(ArrayList<String>)
        const val NOTIFICATION_ID = 1                                          // 알림 번호
        private const val CHANNEL = "run"                                      // 알림 채널 ID(4단계 5-5)
        private const val WAKE_TAG = "google_stt:run"                          // wake lock 이름
        private const val WAKE_LIMIT_MS = 6L * 60 * 60 * 1000                  // wake lock 안전 한도 6시간(놓지 못하고 남는 일 방지)
        private const val WAKE_LIMIT_RUN_MS = 72L * 60 * 60 * 1000             // add-hyungchul-20261106-2243 : [T6-6] 실행 모드의 안전 한도 72시간(전 조합 실행은 하루를 넘길 수 있다)
        private const val TICK_MS = 1_000L                                     // 알림 갱신 간격
        private const val WIFI_WAIT_MS = 5_000L                                // Wi-Fi 를 다시 보는 간격

        /**
         * 목적: 자산 받기를 시작한다(앱이 앞에 있을 때 부른다 — 뒤에 있으면 Android 가 포그라운드 서비스 시작을 막는다).
         * 입력: context - Context, ids - 받을 자산 ID 들 / 출력: 서비스가 시작된다 / 리턴: 없음
         */
        fun download(context: Context, ids: Collection<String>) {
            context.startForegroundService(
                Intent(context, RunService::class.java).setAction(ACTION_DOWNLOAD).putStringArrayListExtra(EXTRA_ASSET_IDS, ArrayList(ids)),
            )
        }

        /**
         * add-hyungchul-20261106-2243 : [T6-6]
         * 목적: 실행을 시작한다(RunController.start 가 부른다 — 앱이 앞에 있을 때).
         * 입력: context - Context / 출력: 서비스가 시작된다 / 리턴: 없음
         */
        fun run(context: Context) {
            context.startForegroundService(Intent(context, RunService::class.java).setAction(ACTION_RUN))
        }

        /** 목적: 받기를 지금 멈춘다. 입력: context - Context / 출력: 진행 중인 받기가 취소된다(받던 파일은 남는다) / 리턴: 없음 */
        fun stop(context: Context) {
            context.startService(Intent(context, RunService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)   // 서비스가 사라지면 함께 끝난다
    private var active = 0                                                              // 진행 중인 받기 요청 수(메인 스레드에서만 바꾼다)
    private var ticker: Job? = null                                                     // 알림 갱신 작업
    private var waitingWifi = false                                                     // Wi-Fi 를 기다리는 중인지
    private val container: AppContainer get() = (application as SttApp).container       // 객체 묶음
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply { setReferenceCounted(false) }
    }

    /** 목적: 바인딩은 쓰지 않는다. 입력: intent - 요청 / 출력: 없음 / 리턴: null */
    override fun onBind(intent: Intent?): IBinder? = null

    /** 목적: 알림 채널을 만든다(이미 있으면 그대로다). 입력: 없음 / 출력: 채널 "run" / 리턴: 없음 */
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "실행·받기 진행", NotificationManager.IMPORTANCE_LOW))   // 소리 없음
    }

    /**
     * 목적: 요청을 받아 처리한다.
     * 입력: intent - ACTION_DOWNLOAD(+ EXTRA_ASSET_IDS) 또는 ACTION_STOP, flags·startId - 쓰지 않음
     * 출력: 받기가 시작되거나 취소된다
     * 리턴: START_NOT_STICKY - 시스템이 서비스를 죽였을 때 스스로 다시 시작하지 않는다(받던 파일은 남아 다음에 이어받는다)
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD) {
            startForeground(NOTIFICATION_ID, notification("준비 중", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)   // 시작 요청 뒤 5초 안에 불러야 한다
            startDownload(intent.getStringArrayListExtra(EXTRA_ASSET_IDS).orEmpty())
        } else if (intent?.action == ACTION_RUN) {                                      // add-hyungchul-20261106-2243 : [T6-6] 실행 모드
            startForeground(NOTIFICATION_ID, notification("실행 준비 중", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            startRun()
        } else if (intent?.action == ACTION_STOP_AFTER) {                               // add-hyungchul-20261106-2243 : [T6-6]
            container.controller.stop(StopMode.AFTER_CURRENT)
        } else if (active == 0) {
            stopSelf()                                                                  // 멈출 것이 없다
        } else {
            container.log.i("중지 요청")                                               // change-hyungchul-20261106-2243 : [T6-6] 받기·실행 모두 멈춘다
            scope.coroutineContext.cancelChildren()                                     // 받기·실행 코루틴들이 끝나며 finish() 를 부른다
        }
        return START_NOT_STICKY
    }

    /** 목적: 서비스가 사라질 때 정리한다. 입력: 없음 / 출력: 코루틴 취소, wake lock 해제 / 리턴: 없음 */
    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    /**
     * 목적: 자산 받기 코루틴을 시작한다(요청이 겹치면 저장소의 Mutex 가 차례로 받게 한다).
     * 입력: ids - 받을 자산 ID 들 / 출력: 받기 시작, wake lock 획득, 알림 갱신 시작 / 리턴: 없음
     */
    private fun startDownload(ids: List<String>) {
        active++
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LIMIT_MS)                           // 화면이 꺼져도 CPU 가 돌게
        if (ticker?.isActive != true) ticker = scope.launch { while (true) { show(); delay(TICK_MS) } }
        scope.launch {
            try {
                val c = container
                val known = ids.filter { it in c.assets.states }                        // 모르는 ID 는 뺀다
                if (known.size != ids.size) c.log.w("모르는 자산 ID 를 뺐다: ${ids - known.toSet()}")
                val specs = withContext(Dispatchers.IO) { c.assets.missing(known) }     // 없는 것만, 작은 것부터
                c.log.i("자산 받기 시작: ${specs.size}개 (${specs.joinToString { it.id }})")
                for (spec in specs) {
                    awaitUnmetered()
                    c.assets.ensureReady(listOf(spec.id))                               // 실패는 저장소가 상태·로그에 남긴다
                }
            } catch (e: CancellationException) {
                throw e                                                                 // 중지 요청
            } catch (e: Exception) {
                container.log.e("받기 서비스 오류: ${e.message}", e)                    // 예상하지 못한 오류(받기 자체의 오류는 상태에 남는다)
            } finally {
                if (--active == 0) finish()
            }
        }
    }

    /**
     * add-hyungchul-20261106-2243 : [T6-6]
     * 목적: 실행 1회를 이 서비스의 코루틴에서 돌린다(RunController.execute).
     * 입력: 없음(요청은 RunController 가 들고 있다) / 출력: 실행 시작, wake lock 획득, 알림 갱신 시작 / 리턴: 없음
     */
    private fun startRun() {
        active++
        if (!wakeLock.isHeld) wakeLock.acquire(WAKE_LIMIT_RUN_MS)                       // 화면이 꺼져도 CPU 가 돌게(실행은 길다)
        if (ticker?.isActive != true) ticker = scope.launch { while (true) { show(); delay(TICK_MS) } }
        scope.launch {
            try {
                container.controller.execute()
            } catch (e: CancellationException) {
                throw e                                                                 // 지금 중지
            } catch (e: Exception) {
                container.log.e("실행 서비스 오류: ${e.message}", e)                    // 예상하지 못한 오류(실행 자체의 오류는 상태에 남는다)
            } finally {
                if (--active == 0) finish()
            }
        }
    }

    /**
     * 목적: "Wi-Fi 전용" 설정이 켜져 있고 지금 연결이 데이터 요금이 드는 망이면 기다린다(E-AS-10).
     * 입력: 없음 / 출력: 기다리는 동안 알림에 "Wi-Fi 연결 대기" / 리턴: 없음(취소되면 CancellationException)
     */
    private suspend fun awaitUnmetered() {
        val cm = getSystemService(ConnectivityManager::class.java)
        try {
            while (container.settings.settings.first().wifiOnly && cm.activeNetwork != null && cm.isActiveNetworkMetered) {
                if (!waitingWifi) container.log.w("E-AS-10: Wi-Fi 전용인데 지금 연결은 데이터 요금이 드는 망이다 — Wi-Fi 연결 대기")
                waitingWifi = true
                delay(WIFI_WAIT_MS)
            }
        } finally {
            waitingWifi = false
        }
    }

    /** 목적: 받기를 모두 마쳤을 때 서비스를 끝낸다. 입력: 없음 / 출력: 알림 제거, wake lock 해제, 서비스 종료 / 리턴: 없음 */
    private fun finish() {
        ticker?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        container.log.i("서비스 종료")                                                // change-hyungchul-20261106-2243 : [T6-6] 받기·실행 공용
        container.log.flush()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 목적: 지금 상태로 알림을 갱신한다. 입력: 없음 / 출력: 알림이 바뀐다 / 리턴: 없음 */
    private fun show() {
        val run = container.controller.state.value                                      // add-hyungchul-20261106-2243 : [T6-6] 실행 중이면 실행 진행을 보인다
        if (run.phase.active) {
            val p = run.progress
            val what = when {
                run.phase == RunPhase.STOPPING -> "중지하는 중"
                p.waiting == "THERMAL" -> "발열 대기"
                p.waiting == "ASSET" -> "모델 받는 중"
                else -> p.running.joinToString(", ").ifEmpty { "준비 중" }
            }
            val percent = if (p.total > 0) p.finished * 100 / p.total else null
            notifications.notify(NOTIFICATION_ID, notification("${p.finished}/${p.total} · $what" + if (run.stopAfterCurrent) " (이 작업 후 중지)" else "", percent, running = true))
            return
        }
        val busy = container.assets.states.entries.firstOrNull { (_, s) ->            // 지금 처리 중인 자산
            s.value is AssetState.Downloading || s.value is AssetState.Extracting || s.value is AssetState.Verifying
        }
        val id = busy?.key ?: ""                                                        // 자산 ID(없으면 빈 글자)
        val text: String
        var percent: Int? = null
        when (val s = busy?.value?.value) {
            is AssetState.Downloading -> {
                percent = s.percent
                text = "받는 중 ${percent?.let { "$it% " } ?: ""}$id"
            }
            is AssetState.Extracting -> text = "압축 푸는 중 $id"
            is AssetState.Verifying -> text = "확인 중 $id"
            else -> text = if (waitingWifi) "Wi-Fi 연결 대기" else "준비 중"
        }
        notifications.notify(NOTIFICATION_ID, notification(text, percent))
    }

    /**
     * 목적: 알림을 만든다.
     * 입력: text - 한 줄 내용, percent - 진행률(없으면 막대를 그리지 않는다) / 출력: 없음 / 리턴: Notification
     */
    private fun notification(text: String, percent: Int?, running: Boolean = false): Notification {   // change-hyungchul-20261106-2243 : [T6-6] running - 실행 모드의 알림인지
        val stop = PendingIntent.getService(
            this, 0, Intent(this, RunService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)                         // 시스템 내려받기 아이콘
            .setContentTitle(if (running) "STT 평가 실행" else "모델 받기")
            .setContentText(text)
            .setOngoing(true)                                                           // 밀어서 지우지 못하게
            .setOnlyAlertOnce(true)                                                     // 갱신할 때마다 울리지 않게
            .addAction(Notification.Action.Builder(null, if (running) "지금 중지" else "중지", stop).build())
        if (running) {                                                                  // add-hyungchul-20261106-2243 : [T6-6] 실행 모드에는 "이 작업 후 중지" 도 둔다
            val after = PendingIntent.getService(this, 1, Intent(this, RunService::class.java).setAction(ACTION_STOP_AFTER), PendingIntent.FLAG_IMMUTABLE)
            b.addAction(Notification.Action.Builder(null, "이 작업 후 중지", after).build())
        }
        if (percent != null) b.setProgress(100, percent, false)                         // 진행 막대
        return b.build()
    }
}
