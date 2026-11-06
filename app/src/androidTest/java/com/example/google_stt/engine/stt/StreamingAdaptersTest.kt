/*
 * 파일명: engine/stt/StreamingAdaptersTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - 공급 속도를 쓰는 두 어댑터(sherpa 스트리밍, Vosk)를 확인한다.
 *     · [IT-ENG-02] nemotron3.5-560ms 한국어 REALTIME — 전사문이 v3 기준과 바이트 동일, 공급 시간 ≈ 음원 길이
 *     · [IT-ENG-03] nemotron 공급 속도 NONE(v4 기본) — 오류 없이 끝남, 공급 시간 기록, 소요 < 음원 길이, 전사문은 REALTIME 과 같다
 *     · [IT-ENG-04] vosk-small-ko / en — 오류 없이 끝나고 글자 수 > 0 (Vosk 는 실행마다 결과가 달라 바이트 비교를 하지 않는다 — 사용자 결정 2026-11-06)
 *     · 취소: 공급을 기다리는 중에 취소하면 바로 멈춘다
 *
 * add-hyungchul-20261106-2140 — [T5-3][T5-4]
 */
package com.example.google_stt.engine.stt

import android.util.Log                                            // 측정값 기록
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.condition.FeedChoice          // 공급 속도
import com.example.google_stt.domain.csv.SttStatus                 // 상태
import com.example.google_stt.engine.EngineTestSupport             // 준비 도우미
import kotlinx.coroutines.CancellationException                    // 취소
import kotlinx.coroutines.Dispatchers                              // 시험 코루틴 스레드
import kotlinx.coroutines.async                                    // 취소할 작업
import kotlinx.coroutines.delay                                    // 잠깐 기다리기
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import org.junit.Assert.assertArrayEquals                          // 바이트 비교
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assert.fail                                       // 예외가 안 났을 때 실패
import org.junit.Assume.assumeTrue                                 // 준비물이 없으면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: SherpaStreamingAdapter·VoskAdapter 계측 시험. */
@RunWith(AndroidJUnit4::class)
class StreamingAdaptersTest {

    /**
     * 목적: [IT-ENG-02][IT-ENG-03] Nemotron 스트리밍 — REALTIME 은 v3 기준과 같고, NONE 은 같은 전사문을 더 빨리 낸다. 취소도 본다.
     * 입력: 없음 / 출력: Logcat 에 시간 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun nemotron_realtimeMatchesBaseline_noneIsFaster() = runBlocking<Unit> {
        val spec = Catalog.model("nemotron3.5-560ms")!!
        val want = EngineTestSupport.expected(Language.KO, "${spec.engine}_${spec.model}_NOVAD")
        assumeTrue("nemotron 기준 전사문이 없어 건너뜀", want != null)
        val pcm = EngineTestSupport.pcm(Language.KO)
        val chunks = EngineTestSupport.chunks(pcm, spec.maxChunkSec)                   // 스트리밍은 파일 전체 1개
        val audioSec = pcm.size / BYTES_PER_SEC.toDouble()
        assertEquals(1, chunks.size)
        SttAdapterFactory.create(spec).use { adapter ->
            val info = adapter.load(EngineTestSupport.modelDir(spec), Language.KO)
            assertEquals(SherpaOfflineAdapter.RUNTIME, info.engineVersion)
            val t0 = System.nanoTime()
            val fast = adapter.transcribe(chunks, FeedChoice.NONE, Language.KO)         // [IT-ENG-03]
            val fastWall = (System.nanoTime() - t0) / 1e9
            Log.i("GoogleStt", "IT-ENG-03 nemotron NONE: 음원 ${audioSec}s 소요 ${fastWall}s feed=${fast.feedSec}s tail=${fast.tailMs}ms first=${fast.firstResultMs}ms")
            assertEquals(SttStatus.OK, fast.status); assertTrue(fast.feedMeasured)
            assertTrue("NONE 은 음원 길이보다 빨라야 한다($fastWall / $audioSec)", fastWall < audioSec)
            assertTrue(fast.feedSec!! > 0 && fast.feedSec!! < audioSec)
            assertArrayEquals("NONE 전사문이 v3 기준과 다르다:\n${fast.text}", want, fast.text.toByteArray(Charsets.UTF_8))
            val real = adapter.transcribe(chunks, FeedChoice.REALTIME, Language.KO)     // [IT-ENG-02] 음원 길이만큼 걸린다
            Log.i("GoogleStt", "IT-ENG-02 nemotron REALTIME: feed=${real.feedSec}s tail=${real.tailMs}ms first=${real.firstResultMs}ms")
            assertArrayEquals("REALTIME 전사문이 v3 기준과 다르다:\n${real.text}", want, real.text.toByteArray(Charsets.UTF_8))
            assertEquals(audioSec, real.feedSec!!, audioSec * 0.05)                    // 실시간 공급이면 공급 시간 ≈ 음원 길이
            val job = async(Dispatchers.Default) { adapter.transcribe(chunks, FeedChoice.REALTIME, Language.KO) }
            delay(1_000)
            val c0 = System.nanoTime()
            job.cancel()
            try { job.await(); fail("취소돼야 한다") } catch (e: CancellationException) { /* 기대한 결과 */ }
            val ms = (System.nanoTime() - c0) / 1_000_000
            Log.i("GoogleStt", "스트리밍 취소: ${ms} ms 뒤 멈춤")
            assertTrue("취소에 ${ms} ms 걸렸다", ms < 2_000)
        }
    }

    /**
     * 목적: Vosk 모델 하나를 돌려 오류 없이 글자가 나오는지 본다.
     * 입력: engine - 결과 엔진 이름, lang - 언어, feed - 공급 속도 / 출력: Logcat / 리턴: 없음
     */
    private fun voskRuns(engine: String, lang: Language, feed: FeedChoice) = runBlocking<Unit> {
        val spec = Catalog.model(engine)!!
        val pcm = EngineTestSupport.pcm(lang)
        val audioSec = pcm.size / BYTES_PER_SEC.toDouble()
        SttAdapterFactory.create(spec).use { adapter ->
            val info = adapter.load(EngineTestSupport.modelDir(spec), lang)
            val out = adapter.transcribe(EngineTestSupport.chunks(pcm, spec.maxChunkSec), feed, lang)
            Log.i("GoogleStt", "IT-ENG-04 $engine ${feed.name}: load=${info.loadSec}s 음원 ${audioSec}s feed=${out.feedSec}s tail=${out.tailMs}ms 글자 ${out.text.length}")
            assertEquals(VoskAdapter.RUNTIME, info.engineVersion)
            assertEquals(SttStatus.OK, out.status); assertTrue(out.text.isNotEmpty()); assertEquals(1, out.utterances)
            assertTrue(out.feedMeasured); assertTrue(out.feedSec!! > 0); assertTrue(out.firstResultMs!! > 0)
            feed.delayMs?.let { assertEquals(audioSec * it / 100.0, out.feedSec!!, audioSec * 0.05) }   // 공급 간격을 지켰는지
        }
    }

    /** 목적: [IT-ENG-04] Vosk 한국어(최대 속도)·영어(D10 간격). 입력: 없음 / 출력: 없음 / 리턴: 없음 */
    @Test
    fun vosk_runsWithoutError() { voskRuns("vosk-small-ko", Language.KO, FeedChoice.NONE); voskRuns("vosk-small-en", Language.EN, FeedChoice.D10) }
}
