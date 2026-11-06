/*
 * 파일명: engine/stt/MlKitAdapterTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-ENG-05] ML Kit 어댑터로 영어 음원 1개를 전사한다: 상태 OK, 글자 수 > 0.
 *   ML Kit 결과는 결정적이지 않아 기준 전사문과 같은지는 보지 않는다.
 * - 음원(약 76초)은 61초에서 잘리는 결함을 피하려고 30초 이하 조각으로 나눠 넣는다 → 세션 = warm-up 1 + 조각 수.
 *   실시간 공급이라 음원 길이만큼 걸린다. 세션 한도(약 35)를 아끼려고 시험은 하나만 둔다.
 * - 기기의 ML Kit(시스템 인식 서비스)이 준비되지 않았으면 실패한다 — 그때는 로그의 checkStatus 값을 본다.
 *   언어 모델은 시스템이 언어별로 받는다. 한국어로 돌리려면 인자를 준다:  -e lang ko
 *
 * add-hyungchul-20261106-2146 — [T5-5]
 */
package com.example.google_stt.engine.stt

import android.util.Log                                            // 측정값 기록
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 시험 인자
import com.example.google_stt.domain.AppError                      // 적재 실패
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.condition.FeedChoice          // 공급 속도
import com.example.google_stt.domain.csv.SttStatus                 // 상태
import com.example.google_stt.engine.EngineTestSupport             // 준비 도우미
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assume.assumeTrue                                 // 준비물이 없으면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: MlKitAdapter 계측 시험. */
@RunWith(AndroidJUnit4::class)
class MlKitAdapterTest {

    /**
     * 목적: [IT-ENG-05] ML Kit 1파일(기본 영어, 인자 lang=ko 면 한국어) — warm-up 뒤 조각마다 세션을 열어 전사한다.
     * 입력: 없음 / 출력: Logcat 에 시간·전사문 앞부분 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun transcribesWithWarmUp() = runBlocking<Unit> {
        val spec = Catalog.model("google-mlkit")!!
        val lang = if (InstrumentationRegistry.getArguments().getString("lang") == "ko") Language.KO else Language.EN
        // ML Kit 은 파일을 넣을 때도 마이크 권한을 요구한다(없으면 ERROR_TYPE_INSUFFICIENT_PERMISSION). 앱 화면의 권한 요청은 T7 에서 만든다
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(EngineTestSupport.context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val pcm = EngineTestSupport.pcm(lang)
        val audioSec = pcm.size / BYTES_PER_SEC.toDouble()
        val chunks = EngineTestSupport.chunks(pcm, 30)                                  // 61초 결함 경계보다 짧게
        val warnings = ArrayList<String>()
        val adapter = SttAdapterFactory.create(spec) { warnings += it } as MlKitAdapter
        adapter.use {
            val info = try {
                adapter.load(null, lang)                                               // 1초 무음 warm-up
            } catch (e: AppError.ModelLoad) {
                assumeTrue("ML Kit ${lang.code} 을 쓸 수 없어 건너뜀(언어 모델은 시스템이 받는다): ${e.message}", false)   // 기기 준비 문제 — 실패가 아니다
                throw e
            }
            assertEquals(MlKitAdapter.RUNTIME, info.engineVersion); assertEquals(1, adapter.sessionCount)
            val out = adapter.transcribe(chunks, FeedChoice.NONE, lang)         // 공급 속도를 뭐라고 주든 실시간으로 넣는다
            Log.i("GoogleStt", "IT-ENG-05 ML Kit ${lang.code}: warm-up=${info.loadSec}s 음원 ${audioSec}s 조각 ${chunks.size}개 feed=${out.feedSec}s " +
                "tail=${out.tailMs}ms first=${out.firstResultMs}ms 문장 ${out.utterances}개 글자 ${out.text.length} note='${out.note}' 경고 $warnings")
            Log.i("GoogleStt", "IT-ENG-05 전사문 앞부분: ${out.text.take(120)}")
            assertEquals(SttStatus.OK, out.status); assertTrue(out.text.isNotEmpty())
            assertTrue(out.feedMeasured); assertEquals(audioSec, out.feedSec!!, audioSec * 0.1)   // 실시간 공급
            assertEquals(1 + chunks.size, adapter.sessionCount); assertFalse(adapter.pastBudget)
        }
    }
}
