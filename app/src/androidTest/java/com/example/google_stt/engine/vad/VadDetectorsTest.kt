/*
 * 파일명: engine/vad/VadDetectorsTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-VAD-01] 새 VAD 실행기 5종(Silero·pyannote·FSMN·FireRed·TEN)이 v3 와 같은 구간을 내는지 확인한다.
 *     · (T8-2 뒤) v3 구현과의 직접 비교는 뺐다 — v3 와 구간·이어 붙인 PCM·모델 해시가 같음을 확인한 실행에서 잰 구간 수·말 길이를 기준값으로 쓴다.
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
     * 목적: 프리셋 하나로 VAD 를 돌려 구간 수·말 길이가 기준과 같은지 본다.
     *       change-hyungchul-20261107-0008 : [T8-2] v3 구현(com.example.google_stt.vad.*)을 지우면서 직접 비교를 뺐다. 기준값은 v3 구현과 구간·PCM·모델 해시가
     *       모두 같음을 확인한 실행(2026-11-06, T5-6c)에서 잰 값이고, SILERO·PYANNOTE_S500 은 v3 기준 CSV(docs/baseline_v3)의 값이기도 하다.
     * 입력: engine - VAD 엔진, presetName - 프리셋 이름, segments - 기대 구간 수, speechSec - 기대 말 길이(초)
     * 출력: Logcat 에 구간 수·시간 / 리턴: 없음(다르면 시험 실패)
     */
    private fun matchesGolden(engine: VadEngine, presetName: String, segments: Int, speechSec: Double) {
        val preset = VadPresetCatalog.presets(engine).first { it.name == presetName }
        val setting = VadSetting(engine, preset, preset.values)
        val detector = VadDetectorFactory.create(engine) { EngineTestSupport.assetDir(it) }!!
        try {
            val a: VadResult = VadRunner.process(detector, pcm, setting)
            Log.i("GoogleStt", "IT-VAD-01 $presetName: 구간 ${a.segments.size}개 말 ${a.detectedSpeechSec}s 출력 ${a.outputSec}s 처리 ${a.processMs}ms ${detector.label}")
            assertEquals("$presetName 구간 수", segments, a.segments.size)
            assertEquals("$presetName 말 길이", speechSec, a.detectedSpeechSec, 0.0005)
            assertEquals(engine, detector.engine); assertTrue(a.pcm.isNotEmpty()); assertEquals(a.pcm.size / 2, a.outputSamples)
        } finally {
            detector.close()
        }
    }

    /** 목적: [IT-VAD-01] Silero·pyannote — v3 기준 CSV 값. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun sileroAndPyannote_matchBaseline() {
        matchesGolden(VadEngine.SILERO, "SILERO", 21, 69.952)                          // docs/baseline_v3 whisper-base_SILERO kr 01
        matchesGolden(VadEngine.PYANNOTE, "PYANNOTE_S500", 10, 75.853)                 // docs/baseline_v3 whisper-base_PYANNOTE_S500 kr 01
    }

    /** 목적: [IT-VAD-01] FSMN·FireRed·TEN — v3 구현과 같음을 확인했을 때 잰 값. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun fsmnFireRedTen_matchGolden() {
        matchesGolden(VadEngine.FSMN, "FSMN_DEFAULT", 11, 80.340)
        matchesGolden(VadEngine.FIRERED, "FIRERED_DEFAULT", 23, 74.070)
        matchesGolden(VadEngine.TEN, "TEN_SHERPA", 16, 72.304)
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
