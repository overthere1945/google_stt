/*
 * 파일명: run/RunServiceDeviceTest.kt (app/src/androidTest — 계측 시험, S24 Ultra, 외부망 필요)
 * 목적 및 기능:
 * - 받기 모드 서비스를 실제 기기·실제 망에서 확인한다(M2 검증, MT-DL-01·02 를 대신한다).
 *     · 앱이 silero-vad 와 whisper-base(약 198 MB)를 받아 풀고 SHA256 을 확인해 준비 상태로 만든다
 *     · 받는 중에 화면을 꺼도 받기·해제가 이어진다(포그라운드 서비스 + wake lock)
 *     · [중지] 는 5초 안에 멈추고, 받던 파일은 남아 다음에 이어받는다
 * - 큰 파일을 받으므로 평소 시험 묶음에서는 건너뛴다. 돌릴 때만 인자를 준다:
 *     adb shell am instrument -w -e class com.example.google_stt.run.RunServiceDeviceTest -e download 1 \
 *         com.example.google_stt.test/androidx.test.runner.AndroidJUnitRunner
 *
 * add-hyungchul-20261106-1723 — [T4-7]
 */
package com.example.google_stt.run

import android.Manifest                                            // 알림 권한 이름
import android.app.ActivityManager                                 // 실행 중인 서비스 조회
import android.app.NotificationManager                             // 떠 있는 알림 조회
import android.net.ConnectivityManager                             // 요금이 드는 망인지
import android.os.ParcelFileDescriptor                             // 셸 명령 출력 읽기
import android.os.PowerManager                                     // 화면 켜짐 여부
import android.os.SystemClock                                      // 걸린 시간
import android.util.Log                                            // 측정값 기록
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context·인자
import com.example.google_stt.SttApp                               // 객체 묶음 얻기
import com.example.google_stt.data.assets.AssetState               // 자산 상태
import kotlinx.coroutines.Dispatchers                              // 값이 바뀌는 자리에서 받기
import kotlinx.coroutines.async                                    // 첫 상태 기다리기
import kotlinx.coroutines.delay                                    // 대기
import kotlinx.coroutines.flow.first                               // 조건에 맞는 첫 값
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import kotlinx.coroutines.withTimeout                              // 시간 제한
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assume.assumeFalse                                // 조건이 안 되면 건너뛰기
import org.junit.Assume.assumeTrue                                 // 조건이 안 되면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: 받기 모드 서비스 실기기 시험. */
@RunWith(AndroidJUnit4::class)
class RunServiceDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext                                           // 시험 대상 앱의 Context
    private val container = (context.applicationContext as SttApp).container          // 서비스와 같은 객체 묶음
    private val wanted = InstrumentationRegistry.getArguments().getString("download") == "1"   // 인자를 줬을 때만 돈다
    private val mb = 1024L * 1024L

    /** 목적: 셸 명령을 돌리고 끝날 때까지 기다린다. 입력: cmd - 명령 / 출력: 명령의 효과 / 리턴: 없음 */
    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    /** 목적: 실행 중인 RunService 를 찾는다. 입력: 없음 / 출력: 없음 / 리턴: RunningServiceInfo? - 없으면 null */
    @Suppress("DEPRECATION")   // 자기 앱의 서비스 조회는 계속 지원된다
    private fun running(): ActivityManager.RunningServiceInfo? =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100).firstOrNull { it.service.className == RunService::class.java.name }

    /** 목적: 시험 전제를 확인한다(인자, 요금이 들지 않는 망). 입력: 없음 / 출력: 없음 / 리턴: 없음(안 맞으면 건너뜀) */
    private fun precondition() {
        assumeTrue("큰 파일을 받는 시험이라 '-e download 1' 을 줬을 때만 돈다", wanted)
        assumeFalse("데이터 요금이 드는 망이라 건너뜀(Wi-Fi 필요)", context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered)
        inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)   // 알림이 보이게(화면의 허용 요청은 T7)
    }

    /**
     * 목적: [M2] 받기 → 해제 → SHA256 확인 → 준비. 받는 중 화면을 꺼도 이어진다.
     * 입력: 없음 / 출력: 앱 전용 저장소에 silero-vad·whisper-base 가 준비된다 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun download_continuesWithScreenOff() = runBlocking {
        precondition()
        val ids = listOf("silero-vad", "sherpa-onnx-whisper-base")
        ids.forEach { container.assets.delete(it) }                                    // 처음부터 받게
        val pm = context.getSystemService(PowerManager::class.java)
        val nm = context.getSystemService(NotificationManager::class.java)
        var slept = false; var offFrom = -1L; var offTo = -1L                          // 화면을 껐는지, 꺼진 동안의 받은 크기(처음·마지막)
        var sawForeground = false; var sawNotification = false
        val offStates = LinkedHashSet<String>()                                        // 화면이 꺼진 동안 본 상태
        val t0 = SystemClock.elapsedRealtime()
        shell("input keyevent KEYCODE_WAKEUP")
        try {
            RunService.download(context, ids)
            withTimeout(15 * 60_000L) {
                while (true) {
                    val states = ids.map { container.assets.state(it).value }
                    if (states.all { it is AssetState.Ready || it is AssetState.Failed }) break
                    val big = states[1]
                    if (!pm.isInteractive) offStates += big::class.simpleName!!
                    if (big is AssetState.Downloading) {
                        sawForeground = sawForeground || running()?.foreground == true
                        sawNotification = sawNotification || nm.activeNotifications.any { it.id == RunService.NOTIFICATION_ID }
                        if (!slept && big.received > 5 * mb) { shell("input keyevent KEYCODE_SLEEP"); slept = true }   // 5 MB 받은 뒤 화면 끄기
                        else if (slept && !pm.isInteractive) { if (offFrom < 0) offFrom = big.received; offTo = big.received }
                    }
                    delay(200)
                }
            }
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")                                     // 화면을 다시 켠다
        }
        val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        Log.i("GoogleStt", "M2 받기 시험: ${sec}초, 화면 꺼진 동안 ${offFrom / mb} → ${offTo / mb} MB, 꺼진 동안 상태 $offStates, 최종 ${ids.map { container.assets.state(it).value }}")
        for (id in ids) {
            assertEquals("$id 상태", AssetState.Ready(verified = true), container.assets.state(id).value)   // 매니페스트 해시와 일치
            val info = container.assetStore.readyInfo(id)!!
            assertEquals("download", info.source); assertTrue(info.verified)
            assertTrue(container.assetStore.isReady(container.assetSpecs.getValue(id)))
        }
        assertTrue(container.assetStore.file(ids[1], "base-encoder.int8.onnx").length() > 10 * mb)
        assertTrue("받는 동안 포그라운드 서비스여야 한다", sawForeground)
        assertTrue("받는 동안 알림이 떠 있어야 한다", sawNotification)
        assertTrue("화면을 끄지 못했다", slept)
        assertTrue("화면이 꺼진 동안 받기가 이어져야 한다($offFrom → $offTo B)", offTo > offFrom && offFrom >= 0)
        withTimeout(5_000L) { while (running() != null) delay(100) }                   // 다 받으면 서비스가 스스로 끝난다
    }

    /**
     * 목적: [중지] 는 5초 안에 멈추고 받던 파일을 남긴다. 다시 시작하면 그 자리부터 이어받는다.
     * 입력: 없음 / 출력: 없음(끝나면 받던 파일을 지운다) / 리턴: 없음
     */
    @Test
    fun stop_cancelsQuicklyAndResumes() = runBlocking {
        precondition()
        val id = "sherpa-onnx-whisper-small"                                           // 약 610 MB — 받는 도중에 멈추기 좋은 크기
        val state = container.assets.state(id)
        container.assets.delete(id)
        try {
            RunService.download(context, listOf(id))
            withTimeout(120_000L) { while (((state.value as? AssetState.Downloading)?.received ?: 0L) < 3 * mb) delay(100) }
            val t0 = SystemClock.elapsedRealtime()
            RunService.stop(context)
            withTimeout(10_000L) { while (state.value != AssetState.Missing || running() != null) delay(20) }
            val took = SystemClock.elapsedRealtime() - t0
            val kept = container.assetStore.partFile(id).length()
            Log.i("GoogleStt", "M2 중지 시험: ${took} ms 만에 멈춤, 남은 파일 ${kept / mb} MB")
            assertTrue("중지에 ${took} ms 걸렸다(5초 안이어야 한다)", took < 5_000L)
            assertTrue("받던 파일이 남아야 한다", kept >= 3 * mb)
            val first = async(Dispatchers.Unconfined) { state.first { it is AssetState.Downloading } as AssetState.Downloading }   // 다시 시작한 뒤 첫 진행 상태
            RunService.download(context, listOf(id))
            val resumed = withTimeout(60_000L) { first.await() }
            Log.i("GoogleStt", "M2 이어받기: 남은 파일 $kept B, 다시 시작한 위치 ${resumed.received} B")
            assertEquals("남은 파일의 끝에서 이어받아야 한다", kept, resumed.received)
        } finally {
            RunService.stop(context)
            withTimeout(10_000L) { while (running() != null) delay(50) }
            container.assets.delete(id)                                                // 시험이 남긴 파일 정리
        }
    }
}
