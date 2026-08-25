/*
 * 파일명: SttTelemetry.kt
 * 목적 및 기능:
 * - STT 배치 실행 중 "얼마나 걸렸고 자원을 얼마나 썼는지"를 파일 단위로 수집한다.
 * - 수집 항목:
 *     · 지연(latency)  : 준비/첫응답/공급/꼬리(tail)/전체, RTF
 *     · 실행 위치      : 온디바이스 여부 판정 근거(네트워크 상태, 비행기모드, 트래픽 증가분)
 *     · 자원           : CPU 시간, PSS 메모리, 배터리 소모(µAh), 배터리 온도, 열(thermal) 상태
 *     · 환경           : 기기/OS/앱 버전, 인식 서비스 패키지 목록
 * - 결과를 result.csv(파일별) / run_meta.csv(실행 1회 요약)로 내보낸다.
 *
 * ★ 온디바이스 여부를 "증명"하는 방법에 대한 주의
 *   Android 7(API 24)부터 TrafficStats.getUidRxBytes()는 자기 UID 외에는 UNSUPPORTED(-1)를 돌려준다.
 *   즉 인식 서비스(다른 프로세스)의 통신량을 앱이 직접 측정할 수 없다.
 *   따라서 다음 3종을 함께 기록해 "정황이 아니라 재현 가능한 근거"가 되게 한다.
 *     1) execution : API 계약상 온디바이스인지 (createOnDeviceSpeechRecognizer / ML Kit on-device)
 *     2) net_type / airplane : 실행 당시 네트워크 상태 → 비행기모드에서 OK 가 나오면 그 자체가 증명이다
 *     3) app_rx/tx_delta : 이 앱 UID 통신량 증가분 (0 이면 앱은 네트워크를 쓰지 않았다)
 *        dev_rx/tx_delta : 기기 전체 통신량 증가분 (다른 앱 트래픽이 섞이므로 참고용)
 *
 * 파일 구조상 주의: Row / ResourceSnapshot 은 최상위 클래스로 둔다.
 *   (object 안의 중첩 클래스에서는 바깥 object 멤버를 수식 없이 부를 수 없기 때문)
 */
package com.example.google_stt

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.provider.Settings
import android.speech.RecognitionService
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ─────────────────────────── 파일 1건의 계측 결과 ───────────────────────────
/**
 * 목적: result.csv 한 줄에 들어갈 값을 담는다.
 * 비고: 측정하지 못한 값은 -1(숫자) 또는 빈 문자열로 남겨 "0"과 구분한다.
 */
data class SttRow(
    var path: String = "",
    var file: String = "",
    var status: String = "",
    var engine: String = "",
    var mode: String = "",
    var execution: String = "",
    var lang: String = "",

    var audioSec: Double = -1.0,
    var srcMime: String = "",
    var srcRate: Int = -1,
    var srcCh: Int = -1,
    var srcBytes: Long = -1,

    var decodeMs: Long = -1,
    var feedMode: String = "",
    var feedDelayMs: Long = -1,
    var feedMs: Long = -1,

    var readyMs: Long = -1,
    var firstResultMs: Long = -1,
    var tailMs: Long = -1,
    var sttWallMs: Long = -1,

    var chars: Int = 0,
    var words: Int = 0,
    var segments: Int = 0,

    var mlkitStatus: String = "",
    var checkStatusMs: Long = -1,
    var downloadMs: Long = -1,

    var netType: String = "",
    var airplane: Int = -1,
    var appRxDelta: Long = -1,
    var appTxDelta: Long = -1,
    var devRxDelta: Long = -1,
    var devTxDelta: Long = -1,

    var cpuMsDelta: Long = -1,
    var pssKb: Long = -1,
    var battUahDelta: Long = 0,
    var battPct: Int = -1,
    var battTempC: Double = -1.0,
    var thermal: String = "",

    var startedAt: String = "",
    var error: String = "",
    var text: String = "",
) {
    /** total_ms = 디코딩 + STT 전체 */
    private fun totalMs(): Long =
        if (decodeMs >= 0 && sttWallMs >= 0) decodeMs + sttWallMs else -1

    /** rtf = STT 전체 시간 / 음원 길이. 1.0 미만이면 실시간보다 빠르다. */
    private fun rtf(): Double =
        if (audioSec > 0 && sttWallMs >= 0) sttWallMs / 1000.0 / audioSec else -1.0

    /** tail_rtf = 공급 종료 후 남은 처리시간 / 음원 길이. 공급속도에 영향받지 않는 핵심 지표. */
    private fun tailRtf(): Double =
        if (audioSec > 0 && tailMs >= 0) tailMs / 1000.0 / audioSec else -1.0

    fun toCsvLine(): String = listOf(
        SttTelemetry.q(path), SttTelemetry.q(file), status, engine, mode, execution, lang,
        SttTelemetry.f2(audioSec), SttTelemetry.q(srcMime),
        srcRate.toString(), srcCh.toString(), srcBytes.toString(),
        decodeMs.toString(), feedMode, feedDelayMs.toString(), feedMs.toString(),
        readyMs.toString(), firstResultMs.toString(), tailMs.toString(), sttWallMs.toString(),
        totalMs().toString(),
        SttTelemetry.f3(rtf()), SttTelemetry.f3(tailRtf()),
        chars.toString(), words.toString(), segments.toString(),
        SttTelemetry.q(mlkitStatus), checkStatusMs.toString(), downloadMs.toString(),
        netType, airplane.toString(),
        appRxDelta.toString(), appTxDelta.toString(),
        devRxDelta.toString(), devTxDelta.toString(),
        cpuMsDelta.toString(), pssKb.toString(), battUahDelta.toString(),
        battPct.toString(), SttTelemetry.f1(battTempC), thermal,
        startedAt, SttTelemetry.q(error), SttTelemetry.q(text),
    ).joinToString(",") + "\n"
}

// ─────────────────────────── 자원 스냅샷 ───────────────────────────
/**
 * 목적: 파일 1건 처리 직전/직후의 자원 카운터를 떠서 차이를 계산한다.
 * 사용: val s = SttResourceSnapshot.take(ctx) … 처리 … s.fillDelta(ctx, row)
 */
class SttResourceSnapshot private constructor(
    private val cpuMs: Long,
    private val appRx: Long,
    private val appTx: Long,
    private val devRx: Long,
    private val devTx: Long,
    private val battUah: Long,
) {
    companion object {
        /**
         * 목적: 현재 시점의 CPU/트래픽/배터리 카운터를 읽는다.
         * 입력: context
         * 리턴: SttResourceSnapshot
         */
        fun take(context: Context): SttResourceSnapshot {
            val uid = android.os.Process.myUid()
            return SttResourceSnapshot(
                cpuMs = android.os.Process.getElapsedCpuTime(),
                appRx = zeroIfUnsupported(TrafficStats.getUidRxBytes(uid)),
                appTx = zeroIfUnsupported(TrafficStats.getUidTxBytes(uid)),
                devRx = zeroIfUnsupported(TrafficStats.getTotalRxBytes()),
                devTx = zeroIfUnsupported(TrafficStats.getTotalTxBytes()),
                battUah = SttTelemetry.batteryChargeUah(context),
            )
        }

        /** TrafficStats.UNSUPPORTED(-1)는 0으로 눕혀 차이 계산이 오염되지 않게 한다. */
        private fun zeroIfUnsupported(v: Long): Long = if (v < 0) 0L else v
    }

    /**
     * 목적: 스냅샷 이후 증가분을 계산해 row 에 채운다.
     * 입력: context, row(계측 행)
     * 리턴: 없음(row 를 직접 수정)
     */
    fun fillDelta(context: Context, row: SttRow) {
        val uid = android.os.Process.myUid()
        row.cpuMsDelta = android.os.Process.getElapsedCpuTime() - cpuMs
        row.appRxDelta = delta(TrafficStats.getUidRxBytes(uid), appRx)
        row.appTxDelta = delta(TrafficStats.getUidTxBytes(uid), appTx)
        row.devRxDelta = delta(TrafficStats.getTotalRxBytes(), devRx)
        row.devTxDelta = delta(TrafficStats.getTotalTxBytes(), devTx)

        val nowUah = SttTelemetry.batteryChargeUah(context)
        // 충전 중이면 값이 올라가므로 "소모"만 양수로 기록한다.
        row.battUahDelta =
            if (battUah > 0 && nowUah > 0) (battUah - nowUah).coerceAtLeast(0L) else 0

        row.pssKb = runCatching { Debug.getPss() }.getOrDefault(-1L)
        row.battPct = SttTelemetry.batteryPercent(context)
        row.battTempC = SttTelemetry.batteryTempC(context)
        row.thermal = SttTelemetry.thermalStatus(context)
    }

    private fun delta(now: Long, before: Long): Long =
        if (now < 0) -1L else (now - before).coerceAtLeast(0L)
}

// ─────────────────────────── 계측 유틸 ───────────────────────────
object SttTelemetry {

    /** result.csv 헤더(컬럼 순서). SttRow.toCsvLine() 순서와 반드시 일치해야 한다. */
    val CSV_HEADER: String = listOf(
        // 식별
        "path", "file", "status", "engine", "mode", "execution", "lang",
        // 입력 오디오
        "audio_sec", "src_mime", "src_rate", "src_ch", "src_bytes",
        // 공급 조건(지연 해석에 반드시 필요)
        "decode_ms", "feed_mode", "feed_delay_ms", "feed_ms",
        // ★ 지연
        "ready_ms", "first_result_ms", "tail_ms", "stt_wall_ms", "total_ms",
        "rtf", "tail_rtf",
        // 출력
        "chars", "words", "segments",
        // 모델 상태
        "mlkit_status", "check_status_ms", "download_ms",
        // 실행 위치 판정 근거
        "net_type", "airplane", "app_rx_delta", "app_tx_delta", "dev_rx_delta", "dev_tx_delta",
        // 자원
        "cpu_ms_delta", "pss_kb", "batt_uah_delta", "batt_pct", "batt_temp_c", "thermal",
        // 기타
        "started_at", "error", "text",
    ).joinToString(",") + "\n"

    /**
     * 목적: 배터리 잔량을 µAh 로 읽는다(파일 1건의 에너지 소모 계산용).
     * 입력: context
     * 리턴: µAh. 미지원 기기는 -1.
     */
    fun batteryChargeUah(context: Context): Long {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        val v = runCatching {
            bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrDefault(Long.MIN_VALUE)
        return if (v == Long.MIN_VALUE || v <= 0) -1 else v
    }

    /**
     * 목적: 배터리 잔량(%)을 읽는다.
     * 입력: context / 리턴: 0~100, 실패 시 -1
     */
    fun batteryPercent(context: Context): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return -1
        val v = runCatching {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrDefault(Int.MIN_VALUE)
        return if (v == Int.MIN_VALUE) -1 else v
    }

    /**
     * 목적: 배터리 온도(°C). ACTION_BATTERY_CHANGED 스티키 인텐트가 0.1°C 단위로 준다.
     * 입력: context / 리턴: °C, 실패 시 -1.0
     */
    fun batteryTempC(context: Context): Double = runCatching {
        val i = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val t = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (t == Int.MIN_VALUE) -1.0 else t / 10.0
    }.getOrDefault(-1.0)

    /**
     * 목적: 열(thermal) 상태(API 29+).
     * 비고: 긴 배치에서 MODERATE 이상으로 올라가면 지연이 늘어난다.
     *       뒤쪽 파일의 레이턴시가 나빠지는 이유를 설명해 주는 핵심 컬럼이다.
     */
    fun thermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "NA"
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return "NA"
        return when (runCatching { pm.currentThermalStatus }.getOrDefault(-1)) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    /**
     * 목적: 현재 활성 네트워크 종류. NONE 이면 인터넷이 끊긴 상태다.
     * 필요 권한: ACCESS_NETWORK_STATE
     */
    fun networkType(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "NA"
        val net = cm.activeNetwork ?: return@runCatching "NONE"
        val cap = cm.getNetworkCapabilities(net) ?: return@runCatching "NONE"
        when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
            cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "OTHER"
        }
    }.getOrDefault("NA")

    /** 비행기 모드(1/0). 비행기모드에서 성공하면 온디바이스 실행의 직접 증거가 된다. */
    fun airplaneMode(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
    }.getOrDefault(-1)

    /** 단말에 설치된 RecognitionService 구현 목록(패키지(버전)). 어떤 엔진이 후보인지 남긴다. */
    fun recognitionServices(context: Context): String = runCatching {
        val pm = context.packageManager
        val services = pm.queryIntentServices(
            Intent(RecognitionService.SERVICE_INTERFACE),
            PackageManager.GET_META_DATA,
        )
        if (services.isEmpty()) {
            "none"
        } else {
            services.joinToString(" | ") { ri ->
                val pkg = ri.serviceInfo.packageName
                val ver = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull() ?: "?"
                "$pkg($ver)"
            }
        }
    }.getOrDefault("query failed")

    /** 시스템 기본 음성인식 서비스(설정값). */
    fun defaultVoiceRecognitionService(context: Context): String = runCatching {
        Settings.Secure.getString(context.contentResolver, "voice_recognition_service") ?: "null"
    }.getOrDefault("NA")

    /**
     * 목적: 이 실행이 어떤 기기/조건에서 이뤄졌는지 run_meta.csv 본문으로 만든다.
     *      (여러 기기의 result.csv 를 나중에 합칠 때 반드시 필요하다)
     * 입력: context, extra(실행 조건 key/value)
     * 리턴: "key,value" 두 컬럼짜리 CSV 본문 문자열
     */
    fun runMetaCsv(context: Context, extra: Map<String, String>): String {
        val m = LinkedHashMap<String, String>()
        m["run_started_at"] = isoNow()
        m["app_version"] = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        }.getOrDefault("?")
        m["manufacturer"] = Build.MANUFACTURER
        m["model"] = Build.MODEL
        m["device"] = Build.DEVICE
        m["hardware"] = Build.HARDWARE
        m["soc"] = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "${Build.SOC_MANUFACTURER}/${Build.SOC_MODEL}"
        } else {
            "NA"
        }
        m["android_release"] = Build.VERSION.RELEASE
        m["sdk_int"] = Build.VERSION.SDK_INT.toString()
        m["build_fingerprint"] = Build.FINGERPRINT
        m["abi"] = Build.SUPPORTED_ABIS.joinToString("/")
        m["cpu_cores"] = Runtime.getRuntime().availableProcessors().toString()
        m["net_type_at_start"] = networkType(context)
        m["airplane_at_start"] = airplaneMode(context).toString()
        m["thermal_at_start"] = thermalStatus(context)
        m["battery_pct_at_start"] = batteryPercent(context).toString()
        m["battery_temp_c_at_start"] = f1(batteryTempC(context))
        m["recognition_services"] = recognitionServices(context)
        m["default_voice_recognition_service"] = defaultVoiceRecognitionService(context)
        m.putAll(extra)

        val sb = StringBuilder("key,value\n")
        for ((k, v) in m) sb.append(k).append(',').append(q(v)).append('\n')
        return sb.toString()
    }

    /** ISO-8601 로컬 시각(오프셋 포함) */
    fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    /** 공백 기준 어절 수. 한국어는 어절 수, 영어는 단어 수가 된다. */
    fun wordCount(s: String): Int =
        if (s.isBlank()) 0 else s.trim().split(Regex("\\s+")).size

    /** CSV 필드 escape: 큰따옴표 이중화 + 줄바꿈 제거 */
    fun q(s: String): String =
        "\"" + s.replace("\"", "\"\"").replace("\r", " ").replace("\n", " ") + "\""

    fun f1(v: Double): String = if (v < 0) "" else String.format(Locale.US, "%.1f", v)
    fun f2(v: Double): String = if (v < 0) "" else String.format(Locale.US, "%.2f", v)
    fun f3(v: Double): String = if (v < 0) "" else String.format(Locale.US, "%.3f", v)

    /** utf-8-sig(BOM) — 기존 파이썬 파이프라인 규약과 동일 */
    val BOM: ByteArray = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
}