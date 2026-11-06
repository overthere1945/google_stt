/*
 * 파일명: engine/stt/SherpaOfflineAdapterTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-ENG-01] 새 오프라인 어댑터의 전사문이 v3 기준(docs/baseline_v3, VAD 없음)과 **바이트까지 같은지** 확인한다.
 *   기준이 있는 오프라인 엔진을 모두 본다: whisper-base, qwen3asr-0.6b, sensevoice-small(kr·en), zipformer-ko(kr).
 *   계열마다 설정 분기가 달라(언어 옵션, 토큰으로 글자 만들기 등) 한 엔진만으로는 이식을 확인할 수 없다.
 * - 적재 실패(E-EN-01)와 취소도 확인한다.
 *
 * add-hyungchul-20261106-2136 — [T5-2]
 */
package com.example.google_stt.engine.stt

import android.util.Log                                            // 측정값 기록
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import com.example.google_stt.domain.AppError                      // 오류 코드
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.condition.FeedChoice          // 공급 속도
import com.example.google_stt.domain.csv.SttStatus                 // 상태
import com.example.google_stt.engine.EngineTestSupport             // 준비 도우미
import java.io.File                                                // 빈 폴더
import kotlinx.coroutines.CancellationException                    // 취소
import kotlinx.coroutines.Dispatchers                              // 시험 코루틴 스레드
import kotlinx.coroutines.async                                    // 취소할 작업
import kotlinx.coroutines.delay                                    // 잠깐 기다리기
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import org.junit.Assert.assertArrayEquals                          // 바이트 비교
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertNull                                 // null 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assert.fail                                       // 예외가 안 났을 때 실패
import org.junit.Assume.assumeTrue                                 // 준비물이 없으면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: SherpaOfflineAdapter 계측 시험. */
@RunWith(AndroidJUnit4::class)
class SherpaOfflineAdapterTest {

    /**
     * 목적: 엔진 하나를 한 언어로 돌려 v3 기준 전사문과 바이트 비교한다.
     * 입력: engine - 결과 엔진 이름, lang - 언어 / 출력: Logcat 에 시간 / 리턴: 없음(다르면 시험 실패, 준비물이 없으면 건너뜀)
     */
    private fun matchesBaseline(engine: String, lang: Language) = runBlocking<Unit> {
        val spec = Catalog.model(engine)!!
        val want = EngineTestSupport.expected(lang, "${spec.engine}_${spec.model}_NOVAD")
        assumeTrue("$engine ${lang.code} 기준 전사문이 없어 건너뜀", want != null)
        val chunks = EngineTestSupport.chunks(EngineTestSupport.pcm(lang), spec.maxChunkSec)
        SttAdapterFactory.create(spec).use { adapter ->
            val info = adapter.load(EngineTestSupport.modelDir(spec), lang)
            val out = adapter.transcribe(chunks, FeedChoice.NONE, lang)
            Log.i("GoogleStt", "IT-ENG-01 $engine ${lang.code}: load=${info.loadSec}s 조각 ${chunks.size}개 tail=${out.tailMs}ms 글자 ${out.text.length}")
            assertArrayEquals("$engine ${lang.code} 전사문이 v3 기준과 다르다:\n${out.text}", want, out.text.toByteArray(Charsets.UTF_8))
            assertEquals(SttStatus.OK, out.status); assertEquals(chunks.size, out.utterances)
            assertFalse(out.feedMeasured); assertNull(out.feedSec)                     // 오프라인은 공급 구간이 없다
            assertTrue(out.tailMs!! > 0); assertTrue(out.firstResultMs!! in 1..out.tailMs!!)
            assertEquals(SherpaOfflineAdapter.RUNTIME, info.engineVersion); assertTrue(info.loadSec > 0)
        }
    }

    /** 목적: [IT-ENG-01] Whisper base(언어 지정) 한국어·영어. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun whisperBase_matchesBaseline() { matchesBaseline("whisper-base", Language.KO); matchesBaseline("whisper-base", Language.EN) }

    /** 목적: [IT-ENG-01] Zipformer 한국어(토큰으로 글자 만들기). 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun zipformerKo_matchesBaseline() = matchesBaseline("zipformer-ko", Language.KO)

    /** 목적: [IT-ENG-01] SenseVoice Small(언어·ITN) 한국어·영어. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun senseVoice_matchesBaseline() { matchesBaseline("sensevoice-small", Language.KO); matchesBaseline("sensevoice-small", Language.EN) }

    /** 목적: [IT-ENG-01] Qwen3-ASR 0.6B(스트림 옵션 language) 한국어·영어. 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun qwen3Asr_matchesBaseline() { matchesBaseline("qwen3asr-0.6b", Language.KO); matchesBaseline("qwen3asr-0.6b", Language.EN) }

    /**
     * 목적: [E-EN-01] 모델 파일이 없으면 네이티브를 부르기 전에 ModelLoad 로 멈춘다. 취소하면 조각 사이에서 멈춘다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun loadFailure_andCancel() = runBlocking<Unit> {
        val spec = Catalog.model("whisper-base")!!
        val empty = File(EngineTestSupport.context.cacheDir, "no_model").apply { deleteRecursively(); mkdirs() }
        SttAdapterFactory.create(spec).use { adapter ->
            try { adapter.load(empty, Language.KO); fail("E-EN-01 이 나야 한다") } catch (e: AppError.ModelLoad) {
                assertEquals("E-EN-01", e.code); assertTrue(e.message!!.contains("base-encoder.int8.onnx"))
            }
            try { adapter.load(null, Language.KO); fail("E-EN-01 이 나야 한다") } catch (e: AppError.ModelLoad) { assertEquals("E-EN-01", e.code) }
            adapter.load(EngineTestSupport.modelDir(spec), Language.KO)
            val pcm = EngineTestSupport.pcm(Language.KO)
            val many = List(20) { EngineTestSupport.chunks(pcm, spec.maxChunkSec) }.flatten()   // 끝까지 돌면 오래 걸리는 양
            val job = async(Dispatchers.Default) { adapter.transcribe(many, FeedChoice.NONE, Language.KO) }
            delay(300)
            val t0 = System.nanoTime()
            job.cancel()
            try { job.await(); fail("취소돼야 한다") } catch (e: CancellationException) { /* 기대한 결과 */ }
            val sec = (System.nanoTime() - t0) / 1e9
            Log.i("GoogleStt", "오프라인 취소: ${sec}초 뒤 멈춤(조각 ${many.size}개 중)")
            assertTrue("취소 뒤 ${sec}초 걸렸다(조각 하나의 디코딩 시간 안에 멈춰야 한다)", sec < 10.0)
        }
    }
}
