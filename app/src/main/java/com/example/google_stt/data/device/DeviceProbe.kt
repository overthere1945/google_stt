/*
 * 파일명: data/device/DeviceProbe.kt
 * 목적 및 기능:
 * - 기기 상태를 읽는다: 배터리(%, 충전량, 온도, 충전 종류), 발열(상태, 여유), 메모리(PSS, 시스템 여유), CPU 시간,
 *   앱의 네트워크 송수신 바이트, 기기 식별(deviceKey). v3 SttTelemetry.kt 의 기기 조회 부분을 옮겼다.
 * - 파일 1개를 처리하는 구간의 변화량을 재서 domain 의 DeviceSample 로 만든다(begin → end).
 *   추론 구간의 네트워크 변화량이 0 이면 온디바이스로 돌았다는 증거다(FR-10-02).
 * - run_meta 의 기기·환경 값(제조사, Android 판, 네트워크 종류 등 v3 키 그대로)을 맵으로 준다(envMeta).
 * - v3 와 달라진 점: 읽기 실패를 runCatching 으로 조용히 삼키지 않는다. 값을 못 읽으면 null 을 돌려주고 warn 으로 알린다.
 *   기기 식별은 "<기기 모델>/<SoC 모델>"(예 SM-S928N/SM8650) — 실측 RTF 를 기기별로 나누는 키다(3단계 8-2).
 *
 * add-hyungchul-20261106-1648 — [T3-5]
 */
package com.example.google_stt.data.device

import android.app.ActivityManager                        // 시스템 메모리 정보
import android.content.Context                            // 시스템 서비스
import android.content.Intent                             // 배터리 상태 인텐트
import android.content.IntentFilter                       // 배터리 상태 필터
import android.content.pm.PackageManager                  // 인식 서비스 조회
import android.net.ConnectivityManager                    // 네트워크 종류
import android.net.NetworkCapabilities                    // 네트워크 종류
import android.net.TrafficStats                           // 앱 송수신 바이트
import android.os.BatteryManager                          // 배터리
import android.os.Build                                   // 기기 정보
import android.os.Debug                                   // PSS
import android.os.PowerManager                            // 발열
import android.os.Process                                 // CPU 시간·UID
import android.os.SystemClock                             // 경과 시간
import android.provider.Settings                          // 비행기 모드·기본 인식 서비스
import android.speech.RecognitionService                  // 인식 서비스 인터페이스 이름
import androidx.core.content.ContextCompat                // 수신기 등록(내보내지 않음)
import com.example.google_stt.domain.csv.DeviceSample     // 파일 구간 계측 결과
import java.util.Locale                                   // 숫자 형식 고정

/**
 * 목적: 시스템 메모리 상태(30초 간격 메모리 기록용 — T6-3).
 * 필드: availMb - 여유(MB), totalMb - 전체(MB), thresholdMb - 이 아래면 시스템이 부족으로 보는 기준(MB), lowMemory - 지금 부족 상태인지
 */
data class MemoryInfo(val availMb: Long, val totalMb: Long, val thresholdMb: Long, val lowMemory: Boolean)

/**
 * 목적: 기기 상태 읽기 도구.
 * 입력(생성자): context - 앱 Context, warn - 값을 읽지 못했을 때 문장을 받는 함수(기본은 아무것도 하지 않음 — 호출하는 쪽이 로그에 연결한다)
 */
class DeviceProbe(private val context: Context, private val warn: (String) -> Unit = {}) {

    /** 기기 식별: "<기기 모델>/<SoC 모델>" — CSV host_id, 실측 RTF 의 키 */
    val deviceKey: String = "${Build.MODEL}/${Build.SOC_MODEL}"

    /** 사람이 읽는 기기 이름: "<기기 모델> <SoC 모델>" — CSV device_name */
    val deviceName: String = "${Build.MODEL} ${Build.SOC_MODEL}"

    /**
     * 목적: 파일 처리 시작 시점의 값(끝에서 변화량을 계산할 기준).
     * 필드: 시작 시각(ms), CPU 시간(ms), 앱 수신·송신 바이트, 배터리 %·충전량·온도, 발열 상태, PSS(MB)
     */
    class Snapshot internal constructor(
        internal val wallMs: Long, internal val cpuMs: Long, internal val rx: Long?, internal val tx: Long?,
        internal val battPct: Int?, internal val battUah: Long?, internal val battTempC: Double?,
        internal val thermal: String, internal val pssMb: Double?,
    )

    /**
     * 목적: 파일 처리를 시작할 때 지금 값을 기억한다.
     * 입력: 없음 / 출력: 없음 / 리턴: Snapshot
     */
    fun begin(): Snapshot {
        // change-hyungchul-20261106-1710 : 기준 시각·CPU 시간은 맨 마지막에 읽는다 — 먼저 읽으면 배터리 조회·PSS 계산에 든 CPU 시간이
        //   파일의 cpu_time_sec 에 섞인다(독립 검토 지적 6. end() 는 반대로 맨 먼저 읽는다)
        val rx = uidBytes(received = true); val tx = uidBytes(received = false)
        val battPct = batteryPercent(); val battUah = batteryChargeUah(); val battTempC = batteryTempC()
        val thermal = thermalStatus(); val pss = pssMb()
        return Snapshot(SystemClock.elapsedRealtime(), Process.getElapsedCpuTime(), rx, tx, battPct, battUah, battTempC, thermal, pss)
    }

    /**
     * 목적: 파일 처리가 끝났을 때 시작 값과의 차이로 계측 결과를 만든다.
     * 입력: start - begin() 이 준 값
     * 출력: 없음
     * 리턴: DeviceSample - CPU 시간·사용률, PSS 최댓값(시작·끝 중 큰 값), 배터리·발열·네트워크 변화
     */
    fun end(start: Snapshot): DeviceSample {
        val wallSec = (SystemClock.elapsedRealtime() - start.wallMs) / 1000.0     // 구간 길이
        val cpuSec = (Process.getElapsedCpuTime() - start.cpuMs) / 1000.0         // 이 프로세스가 쓴 CPU 시간
        val uahNow = batteryChargeUah()
        return DeviceSample(
            deviceName = deviceName, hostId = deviceKey,
            battPctStart = start.battPct, battPctEnd = batteryPercent(),
            battUahDelta = if (start.battUah != null && uahNow != null) (start.battUah - uahNow).coerceAtLeast(0L) else null,   // 소모만 양수(충전 중이면 0)
            battTempCStart = start.battTempC, battTempCEnd = batteryTempC(),
            charging = charging(), thermalStart = start.thermal, thermalEnd = thermalStatus(),
            thermalHeadroom = thermalHeadroom(),
            pssPeakMb = listOfNotNull(start.pssMb, pssMb()).maxOrNull(),
            cpuTimeSec = cpuSec,
            cpuPercentAvg = if (wallSec > 0.0) cpuSec / wallSec * 100.0 else null,   // 100 % = 코어 1개를 다 씀
            netRxDelta = delta(uidBytes(received = true), start.rx),
            netTxDelta = delta(uidBytes(received = false), start.tx),
        )
    }

    /**
     * 목적: 배터리 잔량(%)을 읽는다.
     * 입력: 없음 / 출력: 없음 / 리턴: Int? - 0~100, 못 읽으면 null
     */
    fun batteryPercent(): Int? {
        val v = battery()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        return if (v in 0..100) v else null                                       // 지원하지 않으면 범위 밖 값이 온다
    }

    /**
     * 목적: 배터리 충전량(µAh)을 읽는다(파일 구간의 소모량 계산용).
     * 입력: 없음 / 출력: 없음 / 리턴: Long? - 못 읽거나 지원하지 않으면 null
     */
    fun batteryChargeUah(): Long? {
        val v = battery()?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: return null
        return if (v > 0) v else null
    }

    /**
     * 목적: 배터리 온도(℃)를 읽는다(시스템이 0.1 ℃ 단위로 준다).
     * 입력: 없음 / 출력: 없음 / 리턴: Double? - 못 읽으면 null
     */
    fun batteryTempC(): Double? {
        val t = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: return null
        return if (t == Int.MIN_VALUE) null else t / 10.0
    }

    /**
     * 목적: 충전 종류를 읽는다.
     * 입력: 없음 / 출력: 없음 / 리턴: String - AC / USB / WIRELESS / DOCK / NONE(연결 안 됨), 못 읽으면 빈 글
     */
    fun charging(): String = when (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: return "") {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "DOCK"
        0 -> "NONE"                                                               // 전원에 연결되지 않음
        else -> ""
    }

    /**
     * 목적: 발열 상태 번호를 읽는다(발열 대기 판정용 — SEVERE 이상이면 새 작업을 미룬다).
     * 입력: 없음 / 출력: 없음 / 리턴: Int - PowerManager.THERMAL_STATUS_*(0 NONE … 6 SHUTDOWN)
     */
    fun thermalLevel(): Int = power().currentThermalStatus

    /**
     * 목적: 발열 상태 이름을 읽는다.
     * 입력: 없음 / 출력: 없음 / 리턴: String - NONE / LIGHT / MODERATE / SEVERE / CRITICAL / EMERGENCY / SHUTDOWN
     */
    fun thermalStatus(): String = when (thermalLevel()) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN"
    }

    /**
     * 목적: 발열 여유를 읽는다(10초 뒤 예상. 1.0 이면 SEVERE 문턱).
     * 입력: 없음 / 출력: 없음 / 리턴: Double? - 기기가 지원하지 않거나 너무 자주 부르면(약 1초에 1번보다 자주) null
     */
    fun thermalHeadroom(): Double? {
        val h = power().getThermalHeadroom(10)                                    // 시스템이 값이 없을 때 NaN 을 준다
        return if (h.isNaN()) null else h.toDouble()
    }

    /**
     * 목적: 이 앱의 PSS(MB)를 읽는다.
     * 입력: 없음 / 출력: 없음 / 리턴: Double? - 못 읽으면 null
     */
    fun pssMb(): Double? {
        val kb = Debug.getPss()                                                   // kB 단위
        return if (kb > 0) kb / 1024.0 else null
    }

    /**
     * 목적: 시스템 메모리 상태를 읽는다.
     * 입력: 없음 / 출력: 없음 / 리턴: MemoryInfo
     */
    fun memory(): MemoryInfo {
        val mi = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val mb = 1024L * 1024L
        return MemoryInfo(mi.availMem / mb, mi.totalMem / mb, mi.threshold / mb, mi.lowMemory)
    }

    /**
     * 목적: 지금 활성 네트워크 종류를 읽는다(권한 ACCESS_NETWORK_STATE).
     * 입력: 없음 / 출력: 없음 / 리턴: String - WIFI / CELLULAR / ETHERNET / VPN / OTHER / NONE(연결 없음)
     */
    fun networkType(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val cap = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return "NONE"
        return when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"       // change-hyungchul-20261106-1710 : VPN 을 먼저 본다 — VPN 은 밑단(WIFI 등)도 함께 가지고 있다(지적 10)
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            else -> "OTHER"
        }
    }

    /**
     * 목적: run_meta 에 넣을 기기·환경 값을 준다(키 이름은 v3 run_meta 와 같다 — domain RunMeta.KEYS).
     * 입력: 없음 / 출력: 없음 / 리턴: Map<String, String> - 실행 시작 시점의 값
     */
    fun envMeta(): Map<String, String> = linkedMapOf(
        "manufacturer" to Build.MANUFACTURER, "model" to Build.MODEL, "device" to Build.DEVICE, "hardware" to Build.HARDWARE,
        "soc" to "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}", "android_release" to Build.VERSION.RELEASE,
        "sdk_int" to Build.VERSION.SDK_INT.toString(), "build_fingerprint" to Build.FINGERPRINT,
        "abi" to Build.SUPPORTED_ABIS.joinToString("/"), "cpu_cores" to Runtime.getRuntime().availableProcessors().toString(),
        "net_type_at_start" to networkType(),
        "airplane_at_start" to Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0).toString(),
        "thermal_at_start" to thermalStatus(),
        "battery_pct_at_start" to (batteryPercent()?.toString() ?: ""),
        "battery_temp_c_at_start" to (batteryTempC()?.let { String.format(Locale.US, "%.1f", it) } ?: ""),
        "recognition_services" to recognitionServices(),
        "default_voice_recognition_service" to (Settings.Secure.getString(context.contentResolver, "voice_recognition_service") ?: "null"),
    )

    /**
     * 목적: 설치된 음성 인식 서비스 목록을 읽는다(ML Kit 진단용 — 어떤 엔진이 후보인지 남긴다).
     * 입력: 없음 / 출력: 없음 / 리턴: String - "패키지(판) | …", 없으면 "none"
     */
    private fun recognitionServices(): String {
        val pm = context.packageManager
        val services = pm.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
        if (services.isEmpty()) return "none"
        return services.joinToString(" | ") { ri ->
            val pkg = ri.serviceInfo.packageName
            val ver = try {
                pm.getPackageInfo(pkg, 0).versionName ?: "?"
            } catch (e: PackageManager.NameNotFoundException) {
                warn("인식 서비스 '$pkg' 의 판을 읽지 못했다: ${e.message}")        // 조회 직후 지워진 드문 경우
                "?"
            }
            "$pkg($ver)"
        }
    }

    /** 목적: 배터리 서비스를 얻는다. 리턴: BatteryManager?(없으면 한 번 알리고 null) */
    private fun battery(): BatteryManager? =
        (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager).also { if (it == null) warn("배터리 서비스를 얻지 못했다") }

    /** 목적: 전원 서비스를 얻는다. 리턴: PowerManager */
    private fun power(): PowerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** 목적: 마지막 배터리 상태 알림(시스템이 보관하는 값)을 얻는다. 리턴: Intent?(없으면 null) */
    private fun batteryIntent(): Intent? =
        ContextCompat.registerReceiver(context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)

    /**
     * 목적: 이 앱(UID)이 부팅 뒤 주고받은 바이트 수를 읽는다.
     * 입력: received - true 수신 / false 송신 / 출력: 없음 / 리턴: Long? - 기기가 지원하지 않으면 null
     */
    private fun uidBytes(received: Boolean): Long? {
        val uid = Process.myUid()
        val v = if (received) TrafficStats.getUidRxBytes(uid) else TrafficStats.getUidTxBytes(uid)
        return if (v == TrafficStats.UNSUPPORTED.toLong()) null else v
    }

    /**
     * 목적: 누적 값의 변화량을 구한다.
     * 입력: now - 지금 값, before - 시작 값 / 출력: 없음 / 리턴: Long? - 둘 중 하나라도 없으면 null, 줄어들었으면 0
     */
    private fun delta(now: Long?, before: Long?): Long? =
        if (now == null || before == null) null else (now - before).coerceAtLeast(0L)
}
