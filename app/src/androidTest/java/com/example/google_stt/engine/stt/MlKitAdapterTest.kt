/*
 * 파일명: engine/stt/MlKitAdapterTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-ENG-05] ML Kit 어댑터로 음원 앞부분(10초 이하 조각 2개)을 전사한다: 상태 OK, 글자 수 > 0.
 *   ML Kit 결과는 결정적이지 않아 기준 전사문과 같은지는 보지 않는다.
 * - 세션 = warm-up 1 + 조각 2. 실시간 공급이라 조각 길이만큼(약 20초) 걸린다. 세션 한도(약 35)를 아끼려고 짧게, 하나만 둔다.
 * - 기기의 ML Kit(시스템 인식 서비스)이 준비되지 않았으면 실패한다 — 그때는 로그의 checkStatus 값을 본다.
 *   언어 모델은 시스템이 언어별로 받는다. 기본은 한국어다(이 기기에 영어 모델이 없다 — 평소 묶음에서 실제로 검증되게). 영어는 인자를 준다:  -e lang en
 *   건너뛰는 것은 "언어 모델을 받지 못함·쓸 수 없음" 일 때뿐이다. 그 밖의 적재 실패(권한·파이프 등)는 시험 실패다.
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
     * 목적: [IT-ENG-05] ML Kit(기본 한국어, 인자 lang=en 이면 영어) — warm-up 뒤 조각마다 세션을 열어 전사한다.
     * 입력: 없음 / 출력: Logcat 에 시간·전사문 앞부분 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun transcribesWithWarmUp() = runBlocking<Unit> {
        val spec = Catalog.model("google-mlkit")!!
        val lang = if (InstrumentationRegistry.getArguments().getString("lang") == "en") Language.EN else Language.KO   // change-hyungchul-20261106-2216 : 기본을 한국어로(독립 검토 — 영어 기본이면 이 기기에서 검증 0건)
        // ML Kit 은 파일을 넣을 때도 마이크 권한을 요구한다(없으면 ERROR_TYPE_INSUFFICIENT_PERMISSION). 앱 화면의 권한 요청은 T7 에서 만든다
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(EngineTestSupport.context.packageName, android.Manifest.permission.RECORD_AUDIO)
        val pcm = EngineTestSupport.pcm(lang)
        val chunks = EngineTestSupport.chunks(pcm, 10).take(2)                          // 10초 이하 조각 2개(여러 조각을 합치는 길까지 본다)
        val audioSec = chunks.sumOf { it.sec }
        val warnings = ArrayList<String>()
        val adapter = SttAdapterFactory.create(spec) { warnings += it } as MlKitAdapter
        adapter.use {
            val info = try {
                adapter.load(null, lang)                                               // 1초 무음 warm-up
            } catch (e: AppError.ModelLoad) {
                val noModel = e.message!!.contains("다운로드") || e.message!!.contains("사용 불가")   // 언어 모델이 기기에 없을 때의 문구
                assumeTrue("ML Kit ${lang.code} 언어 모델이 없어 건너뜀(시스템이 받는다): ${e.message}", !noModel)   // 기기 준비 문제만 건너뛴다
                throw e                                                                // 그 밖의 적재 실패는 시험 실패
            }
            assertEquals(MlKitAdapter.RUNTIME, info.engineVersion); assertEquals(1, adapter.sessionCount)
            val out = adapter.transcribe(chunks, FeedChoice.NONE, lang)         // 공급 속도를 뭐라고 주든 실시간으로 넣는다
            Log.i("GoogleStt", "IT-ENG-05 ML Kit ${lang.code}: warm-up=${info.loadSec}s 음원 ${audioSec}s 조각 ${chunks.size}개 feed=${out.feedSec}s " +
                "tail=${out.tailMs}ms first=${out.firstResultMs}ms 문장 ${out.utterances}개 글자 ${out.text.length} note='${out.note}' 경고 $warnings")
            Log.i("GoogleStt", "IT-ENG-05 전사문 앞부분: ${out.text.take(120)}")
            assertEquals(SttStatus.OK, out.status); assertTrue(out.text.isNotEmpty())
            assertTrue(out.feedMeasured); assertEquals(audioSec, out.feedSec!!, 1.5)   // 실시간 공급(조각마다 파이프 마무리 시간이 조금 붙는다)
            assertEquals(1 + chunks.size, adapter.sessionCount); assertFalse(adapter.pastBudget)
        }
    }
}
