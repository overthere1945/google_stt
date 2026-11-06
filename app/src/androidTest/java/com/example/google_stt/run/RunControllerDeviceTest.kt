/*
 * 파일명: run/RunControllerDeviceTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [M3] 실행 전체(컨트롤러 → 포그라운드 서비스 → 스케줄러 → 파이프라인)를 실제 엔진으로 돌린다: 엔진 4종 × VAD 2종 × 한국어 01 = 작업 8개.
 *     · 오프라인(whisper-base)·스트리밍(nemotron3.5-560ms)의 TXT 가 v3 기준과 바이트까지 같다(VAD 없음 / SILERO 둘 다)
 *     · Vosk·ML Kit 은 오류 없이 글자가 나온다(결정적이지 않아 비교하지 않는다). ML Kit 은 61초 결함을 피하려고 EPD(세션 분할)를 켠다
 *     · 출력 폴더의 기록: _plan.csv, _plan_progress.csv(모두 DONE), _summary.csv(8줄), _plan_memlog.csv, _app_log.txt, 작업마다 CSV 3종
 *     · 실행 도중 화면을 꺼도 계속 돈다(포그라운드 서비스 + wake lock). 실측 RTF 가 저장된다
 *     · 같은 요청으로 다시 시작하면 모두 건너뛰고 바로 끝난다(이어 하기)
 * - 5분쯤 걸리고 ML Kit 세션을 쓰므로 인자를 줬을 때만 돈다:
 *     adb shell am instrument -w -e class com.example.google_stt.run.RunControllerDeviceTest -e m3 1 com.example.google_stt.test/androidx.test.runner.AndroidJUnitRunner
 * - 출력은 앱이 권한을 보존한 실제 SAF 폴더 아래 "m3_<시각>" 에 쓴다(없으면 앱 캐시 폴더).
 *
 * add-hyungchul-20261106-2244 — [T6-6]
 */
package com.example.google_stt.run

import android.Manifest                                            // 권한 이름
import android.net.Uri                                             // 음원 위치
import android.os.ParcelFileDescriptor                             // 셸 명령 출력
import android.os.PowerManager                                     // 화면 켜짐 여부
import android.util.Log                                            // 측정값 기록
import androidx.documentfile.provider.DocumentFile                 // 출력 폴더
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 인자·시험 APK 자산
import com.example.google_stt.data.saf.AudioItem                   // 음원 항목
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.catalog.Runtime               // 엔진 종류
import com.example.google_stt.domain.condition.EpdChoice           // EPD 선택
import com.example.google_stt.domain.condition.RunOptions          // 실행 옵션
import com.example.google_stt.domain.condition.RunPurpose          // 실행 목적
import com.example.google_stt.domain.epd.EpdConfig                 // EPD 설정
import com.example.google_stt.domain.epd.EpdMode                   // EPD 방식
import com.example.google_stt.domain.eta.RtfKey                    // 실측 RTF 키
import com.example.google_stt.domain.lang.LangFolder               // 언어 폴더
import com.example.google_stt.domain.lang.LangSource               // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                      // 작업
import com.example.google_stt.domain.plan.PlanProgress             // 진행 기록 형식
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋
import com.example.google_stt.domain.vad.VadSetting                // VAD 설정
import com.example.google_stt.engine.EngineTestSupport             // 준비 도우미
import java.io.File                                                // 캐시 파일
import kotlinx.coroutines.delay                                    // 기다리기
import kotlinx.coroutines.flow.first                               // 저장된 값 읽기
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import kotlinx.coroutines.withTimeout                              // 시간 제한
import org.junit.Assert.assertArrayEquals                          // 바이트 비교
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assume.assumeTrue                                 // 조건이 안 되면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: 실행 전체의 실기기 시험(M3). */
@RunWith(AndroidJUnit4::class)
class RunControllerDeviceTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = EngineTestSupport.context
    private val c = EngineTestSupport.container

    /** 목적: 셸 명령을 돌린다. 입력: cmd - 명령 / 출력: 명령의 효과 / 리턴: 없음 */
    private fun shell(cmd: String) {
        ParcelFileDescriptor.AutoCloseInputStream(inst.uiAutomation.executeShellCommand(cmd)).use { it.readBytes() }
    }

    /** 목적: 출력 폴더 아래의 파일을 읽는다. 입력: root - 폴더, path - "/" 로 나눈 상대 경로 / 출력: 없음 / 리턴: ByteArray? - 없으면 null */
    private fun read(root: DocumentFile, path: String): ByteArray? {
        var cur = root
        for (seg in path.split('/')) cur = cur.findFile(seg) ?: return null
        return context.contentResolver.openInputStream(cur.uri)?.use { it.readBytes() }
    }

    /** 목적: 실행이 끝날 때까지 기다린다. 입력: onTick - 0.5초마다 불리는 함수 / 출력: 없음 / 리턴: RunState - 끝 상태 */
    private suspend fun awaitEnd(onTick: (RunState) -> Unit = {}): RunState = withTimeout(20 * 60_000L) {
        while (c.controller.state.value.phase.active) { onTick(c.controller.state.value); delay(500) }
        c.controller.state.value
    }

    /**
     * 목적: [M3] 엔진 4종 × VAD 2종 종단 실행과 이어 하기.
     * 입력: 없음 / 출력: 출력 폴더에 결과 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun fourEnginesTwoVads_endToEnd_thenResume() = runBlocking<Unit> {
        assumeTrue("5분쯤 걸리고 ML Kit 세션을 써서 '-e m3 1' 을 줬을 때만 돈다", InstrumentationRegistry.getArguments().getString("m3") == "1")
        inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)   // 알림(화면의 요청은 T7)
        inst.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)         // ML Kit 이 요구한다
        // 작업 8개: 엔진 4종 × (VAD 없음, SILERO)
        val lang = LangFolder("kr", "id", Language.KO, LangSource.AUTO_SUBFOLDER)
        val silero = VadPresetCatalog.presets(VadEngine.SILERO).first { it.name == "SILERO" }
        val vads = listOf(VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap()), VadSetting(VadEngine.SILERO, silero, silero.values))
        val jobs = listOf("whisper-base", "nemotron3.5-560ms", "vosk-small-ko", "google-mlkit").flatMap { engine ->
            val spec = Catalog.model(engine)!!
            val opt = if (spec.runtime == Runtime.MLKIT) RunOptions(epd = EpdChoice(EpdConfig(EpdMode.SPLIT_SESSION))) else RunOptions()   // ML Kit 만 EPD
            vads.map { Job(lang, spec, it, opt.effectiveFor(spec.runtime)) }
        }
        assertEquals(8, jobs.map { it.key }.toSet().size)
        jobs.flatMap { it.assetIds }.distinct().forEach { EngineTestSupport.assetDir(it) }   // 자산은 미리 준비(받기는 M2 에서 확인했다)
        // 음원과 출력 폴더
        val wav = File(context.cacheDir, "m3_01.wav").apply { writeBytes(inst.context.assets.open("audio/kr/Customer_Service_KR/01.wav").use { it.readBytes() }) }
        val item = AudioItem(Uri.fromFile(wav), listOf("Customer_Service_KR"), "01.wav", "01", ".wav", wav.length())
        val name = "m3_" + System.currentTimeMillis()
        val saf = context.contentResolver.persistedUriPermissions.firstOrNull { it.isWritePermission }?.let { DocumentFile.fromTreeUri(context, it.uri) }?.takeIf { it.canWrite() }
        val out = saf?.createDirectory(name) ?: DocumentFile.fromFile(File(context.cacheDir, name).apply { mkdirs() })
        Log.i("GoogleStt", "M3 출력 폴더: ${out.uri} (실제 SAF=${saf != null})")
        val request = RunRequest(out, jobs, mapOf("kr" to listOf(item)), workers = 1, purpose = RunPurpose.TIMING, planName = "m3")

        // ── 실행 ──
        val pm = context.getSystemService(PowerManager::class.java)
        var slept = false; var offFrom = -1; var offTo = -1                            // 화면이 꺼진 동안 끝난 작업 수(처음·마지막)
        val phases = LinkedHashSet<RunPhase>()
        shell("input keyevent KEYCODE_WAKEUP")
        val t0 = System.nanoTime()
        try {
            assertTrue(c.controller.start(request))
            assertTrue("실행 중에는 새로 시작하지 않는다", !c.controller.start(request))
            val end = awaitEnd { s ->
                phases += s.phase
                if (!slept && s.progress.finished >= 1) { shell("input keyevent KEYCODE_SLEEP"); slept = true }   // 첫 작업이 끝나면 화면을 끈다
                else if (slept && !pm.isInteractive) { if (offFrom < 0) offFrom = s.progress.finished; offTo = s.progress.finished }
            }
            val sec = (System.nanoTime() - t0) / 1e9
            Log.i("GoogleStt", "M3 실행: ${end.phase} ${end.progress} ${sec}초, 단계 $phases, 화면 꺼진 동안 $offFrom → $offTo, 사유 '${end.message}'")
            assertEquals("끝 상태: ${end.message}", RunPhase.FINISHED, end.phase)
            assertEquals(RunProgress(total = 8, done = 8), end.progress)
            assertTrue(RunPhase.RUNNING in phases)
            assertTrue("화면이 꺼진 동안 작업이 이어져야 한다($offFrom → $offTo)", slept && offTo > offFrom && offFrom >= 0)
        } finally {
            shell("input keyevent KEYCODE_WAKEUP")
        }

        // ── 결과 ──
        for (job in jobs) {
            val txt = read(out, "kr/${job.resultDirName}/Customer_Service_KR/01.txt")
            assertTrue("${job.key}: TXT 가 없다", txt != null)
            val want = EngineTestSupport.expected(Language.KO, job.resultDirName)
            when (job.model.runtime) {
                Runtime.SHERPA_OFFLINE, Runtime.SHERPA_STREAMING -> assertArrayEquals("${job.key} 전사문이 v3 기준과 다르다", want, txt)   // 결정적인 엔진
                else -> assertTrue("${job.key}: 전사문이 비었다", txt!!.isNotEmpty())                                                     // Vosk·ML Kit
            }
            for (suffix in listOf("_result.csv", "_device.csv", "_run_meta.csv")) assertTrue("${job.csvBaseName}$suffix 가 없다", (read(out, "kr/${job.csvBaseName}$suffix")?.size ?: 0) > 0)
            Log.i("GoogleStt", "M3 결과 ${job.key}: ${txt!!.toString(Charsets.UTF_8).length}자")
        }
        val progress = PlanProgress.parse(String(read(out, "_plan_progress.csv")!!, Charsets.UTF_8))
        assertEquals(jobs.map { it.key }.toSet(), progress.doneKeys)                   // 8개 모두 DONE
        val summary = String(read(out, "_summary.csv")!!, Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
        assertEquals(9, summary.size)                                                  // 머리줄 + 8
        for (f in listOf("_plan.csv", "_plan_memlog.csv", "_app_log.txt")) assertTrue("$f 가 없다", (read(out, f)?.size ?: 0) > 0)
        assertTrue(out.findFile("kr")!!.listFiles().none { it.name!!.startsWith("_") })   // 작업 중 폴더가 남지 않았다
        val table = c.rtf.table.first()
        val key = RtfKey(c.probe.deviceKey, "whisper-base", VadEngine.SILERO.token, 1)
        assertTrue("실측 RTF 가 저장돼야 한다", table.lookup(key) != null)
        Log.i("GoogleStt", "M3 실측 RTF whisper-base/SILERO: ${table.lookup(key)}")

        // ── 이어 하기: 같은 요청을 다시 시작하면 모두 건너뛴다 ──
        val t1 = System.nanoTime()
        assertTrue(c.controller.start(request))
        val again = awaitEnd()
        val sec2 = (System.nanoTime() - t1) / 1e9
        Log.i("GoogleStt", "M3 이어 하기: ${again.phase} ${again.progress} ${sec2}초")
        assertEquals(RunPhase.FINISHED, again.phase); assertEquals(RunProgress(total = 8, done = 8), again.progress)
        assertTrue("다시 돌리지 않고 바로 끝나야 한다(${sec2}초)", sec2 < 15.0)
    }
}
