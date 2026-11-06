/*
 * 파일명: data/device/DeviceProbeTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-DEV-01] DeviceProbe 가 실기기에서 그럴듯한 값을 읽는지 확인한다:
 *   배터리 0~100 %, 온도 10~60 ℃, deviceKey 가 비지 않음, PSS > 0, 발열 상태·충전 종류가 정해진 글자,
 *   파일 구간(begin → end)의 CPU 시간·네트워크 변화량, run_meta 환경 값.
 *
 * add-hyungchul-20261106-1648 — [T3-5]
 */
package com.example.google_stt.data.device

import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context 얻기
import com.example.google_stt.domain.csv.RunMeta                   // run_meta 키 목록
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertNotNull                              // null 아님 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: DeviceProbe 계측 시험. */
@RunWith(AndroidJUnit4::class)
class DeviceProbeTest {

    private val warnings = ArrayList<String>()                                          // 읽기 실패 알림 모으기
    private val probe = DeviceProbe(InstrumentationRegistry.getInstrumentation().targetContext) { warnings += it }
    private val thermalNames = setOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")

    /**
     * 목적: [IT-DEV-01] 한 번 읽는 값들의 범위.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun singleReadings_areInRange() {
        assertTrue("deviceKey=${probe.deviceKey}", Regex("^[^/ ]+/[^/ ]+$").matches(probe.deviceKey))   // "<모델>/<SoC>"
        assertTrue(probe.deviceName.isNotBlank())
        assertTrue("battery=${probe.batteryPercent()}", probe.batteryPercent() in 0..100)
        val temp = probe.batteryTempC()
        assertTrue("temp=$temp", temp != null && temp in 10.0..60.0)
        assertTrue("uAh=${probe.batteryChargeUah()}", (probe.batteryChargeUah() ?: 0L) > 0L)
        assertTrue("charging=${probe.charging()}", probe.charging() in setOf("AC", "USB", "WIRELESS", "DOCK", "NONE"))
        assertTrue("thermal=${probe.thermalStatus()}", probe.thermalStatus() in thermalNames)
        assertTrue(probe.thermalLevel() in 0..6)
        assertTrue("pss=${probe.pssMb()}", (probe.pssMb() ?: 0.0) > 0.0)
        val mem = probe.memory()
        assertTrue("mem=$mem", mem.totalMb > 4000 && mem.availMb in 1..mem.totalMb && mem.thresholdMb > 0)
        assertTrue(probe.networkType() in setOf("WIFI", "CELLULAR", "ETHERNET", "VPN", "OTHER", "NONE"))
        val headroom = probe.thermalHeadroom()                                          // 지원하지 않거나 너무 자주 부르면 null
        assertTrue("headroom=$headroom", headroom == null || headroom in 0.0..5.0)
        assertEquals("읽기 실패 알림: $warnings", 0, warnings.size)
    }

    /**
     * 목적: [IT-DEV-01] 파일 구간 계측 — CPU 를 쓰면 CPU 시간이 늘고, 네트워크를 쓰지 않으면 변화량이 0 이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun interval_measuresCpuAndNetwork() {
        val start = probe.begin()
        var x = 0.0
        val until = System.nanoTime() + 300_000_000L                                    // 약 0.3초 동안 계산한다
        while (System.nanoTime() < until) x += Math.sqrt(x + 1.0)
        val s = probe.end(start)
        assertTrue(x > 0.0)
        assertEquals(probe.deviceKey, s.hostId); assertEquals(probe.deviceName, s.deviceName)
        assertTrue("cpuTimeSec=${s.cpuTimeSec}", s.cpuTimeSec!! in 0.2..5.0)             // 0.3초 계산 + 시험 실행기 몫
        assertTrue("cpuPercentAvg=${s.cpuPercentAvg}", s.cpuPercentAvg!! in 50.0..800.0) // 코어 1개 = 100 %
        assertEquals("추론 구간 수신 바이트", 0L, s.netRxDelta)                            // 네트워크를 쓰지 않았다(FR-10-02 의 측정 방법 확인)
        assertEquals("추론 구간 송신 바이트", 0L, s.netTxDelta)
        assertTrue(s.battPctStart in 0..100 && s.battPctEnd in 0..100)
        assertNotNull(s.battUahDelta); assertTrue(s.battUahDelta!! >= 0)
        assertTrue(s.thermalStart in thermalNames && s.thermalEnd in thermalNames)
        assertTrue("pssPeakMb=${s.pssPeakMb}", s.pssPeakMb!! > 0.0)
        assertTrue(s.charging.isNotEmpty())
    }

    /**
     * 목적: run_meta 환경 값 — 키가 모두 v3 run_meta 키 목록에 있고, 기기 정보가 비어 있지 않다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun envMeta_usesRunMetaKeys() {
        val env = probe.envMeta()
        assertTrue("run_meta 에 없는 키: ${env.keys - RunMeta.KEYS.toSet()}", RunMeta.KEYS.containsAll(env.keys))
        assertEquals(17, env.size)                                                      // v3 가 기기에서 읽던 17개
        for (k in listOf("manufacturer", "model", "device", "hardware", "soc", "android_release", "sdk_int", "build_fingerprint", "abi", "cpu_cores")) {
            assertTrue("$k 가 비었다", env.getValue(k).isNotBlank())
        }
        assertTrue(env.getValue("soc").contains("/"))                                   // "<제조사>/<모델>" (v3 형식)
        assertTrue(env.getValue("sdk_int").toInt() >= 31)
        assertTrue(env.getValue("recognition_services").isNotBlank())
    }
}
