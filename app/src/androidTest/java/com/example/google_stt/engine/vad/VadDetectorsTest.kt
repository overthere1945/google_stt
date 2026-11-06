/*
 * 파일명: engine/vad/VadDetectorsTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-VAD-01] 새 VAD 실행기 5종(Silero·pyannote·FSMN·FireRed·TEN)이 v3 와 같은 구간을 내는지 확인한다.
 *     · v3 구현(com.example.google_stt.vad.*, APK assets 의 모델)과 새 구현(자산 폴더의 모델)을 같은 음원에 돌려
 *       구간 목록·이어 붙인 PCM·모델 해시가 완전히 같은지 본다(옛 코드는 T8-2 에서 지우므로 이 비교는 그때까지만 가능하다).
 *     · v3 기준 CSV(docs/baseline_v3) 값과도 맞춘다: SILERO 구간 21개·말 69.952초, PYANNOTE_S500 구간 10개·말 75.853초
 *     · VAD 없음(NONE)은 null, 모델 폴더가 비었으면 E-VAD-01
 *
 * add-hyungchul-20261106-2200 — [T5-6]
 */
package com.example.google_stt.engine.vad

import android.util.Log                                            // 측정값 기록
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import com.example.google_stt.domain.AppError                      // 오류 코드
import com.example.google_stt.domain.catalog.Language              // 시험 음원 언어
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋 목록
import com.example.google_stt.domain.vad.VadResult                 // VAD 결과
import com.example.google_stt.domain.vad.VadSetting                // 실행 설정
import com.example.google_stt.engine.EngineTestSupport             // 준비 도우미
import java.io.File                                                // 빈 폴더
import org.junit.Assert.assertArrayEquals                          // 바이트 비교
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertNull                                 // null 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assert.fail                                       // 예외가 안 났을 때 실패
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: VAD 실행기 계측 시험. */
@RunWith(AndroidJUnit4::class)
class VadDetectorsTest {

    private val pcm by lazy { EngineTestSupport.pcm(Language.KO) }                     // 한국어 시험 음원(약 84초)

    /**
     * 목적: 프리셋 하나로 새 구현과 v3 구현을 돌려 결과가 같은지 본다.
     * 입력: engine - VAD 엔진, presetName - 프리셋 이름 / 출력: Logcat 에 구간 수·시간 / 리턴: VadResult - 새 구현의 결과
     */
    private fun sameAsV3(engine: VadEngine, presetName: String): VadResult {
        val preset = VadPresetCatalog.presets(engine).first { it.name == presetName }
        val setting = VadSetting(engine, preset, preset.values)
        val fresh = VadDetectorFactory.create(engine) { EngineTestSupport.assetDir(it) }!!
        val old = com.example.google_stt.vad.VadDetectorFactory.create(EngineTestSupport.context, engine)
        try {
            val a = VadRunner.process(fresh, pcm, setting)
            val b = com.example.google_stt.vad.VadRunner.process(old, pcm, setting)
            Log.i("GoogleStt", "IT-VAD-01 $presetName: 구간 ${a.segments.size}개 말 ${a.detectedSpeechSec}s 출력 ${a.outputSec}s 처리 ${a.processMs}ms(v3 ${b.processMs}ms) ${fresh.label}")
            assertTrue("$presetName: 구간이 하나도 없다", a.segments.isNotEmpty())
            assertEquals("$presetName 구간 목록이 v3 와 다르다", b.segments, a.segments)
            assertArrayEquals("$presetName 이어 붙인 PCM 이 v3 와 다르다", b.pcm, a.pcm)
            assertEquals(b.detectedSpeechSamples, a.detectedSpeechSamples); assertEquals(b.joinSilenceSamples, a.joinSilenceSamples)
            assertEquals(b.seams, a.seams)
            assertEquals(old.label, fresh.label); assertEquals(old.runtimeVersion, fresh.runtimeVersion); assertEquals(engine, fresh.engine)
            assertEquals("$presetName: 자산 폴더의 모델이 v3 APK 에 든 모델과 다르다", old.modelSha256, fresh.modelSha256)
            return a
        } finally {
            fresh.close(); old.close()
        }
    }

    /** 목적: [IT-VAD-01] Silero — v3 와 같고 기준 CSV 값과도 같다. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun silero_matchesV3AndBaseline() {
        val r = sameAsV3(VadEngine.SILERO, "SILERO")
        assertEquals(21, r.segments.size); assertEquals(69.952, r.detectedSpeechSec, 0.0005)   // docs/baseline_v3 whisper-base_SILERO kr 01
    }

    /** 목적: [IT-VAD-01] pyannote — v3 와 같고 기준 CSV 값과도 같다. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun pyannote_matchesV3AndBaseline() {
        val r = sameAsV3(VadEngine.PYANNOTE, "PYANNOTE_S500")
        assertEquals(10, r.segments.size); assertEquals(75.853, r.detectedSpeechSec, 0.0005)   // docs/baseline_v3 whisper-base_PYANNOTE_S500 kr 01
    }

    /** 목적: [IT-VAD-01] FSMN·FireRed·TEN — 기준 CSV 가 없어 v3 구현과만 비교한다. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun fsmnFireRedTen_matchV3() {
        sameAsV3(VadEngine.FSMN, "FSMN_DEFAULT"); sameAsV3(VadEngine.FIRERED, "FIRERED_DEFAULT"); sameAsV3(VadEngine.TEN, "TEN_SHERPA")
    }

    /**
     * 목적: VAD 없음은 null, 모델 파일이 없으면 E-VAD-01(어느 파일인지 알려 준다).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun none_andMissingModel() {
        assertNull(VadDetectorFactory.create(VadEngine.NONE) { error("부르면 안 된다") })
        val empty = File(EngineTestSupport.context.cacheDir, "no_vad").apply { deleteRecursively(); mkdirs() }
        for (engine in listOf(VadEngine.SILERO, VadEngine.PYANNOTE, VadEngine.FSMN, VadEngine.FIRERED)) {
            try { VadDetectorFactory.create(engine) { empty }; fail("$engine: E-VAD-01 이 나야 한다") } catch (e: AppError.VadLoad) {
                assertEquals("E-VAD-01", e.code); assertTrue(e.message!!.contains(empty.path))
            }
        }
    }
}
