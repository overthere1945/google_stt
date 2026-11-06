/*
 * 파일명: run/JobPipelineDeviceTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-PIPE-01] 작업 1개를 실제 엔진으로 끝까지 돌린다: whisper-base × SILERO × 한국어 01.
 *     · 전사 TXT 가 v3 기준(docs/baseline_v3)과 바이트까지 같다
 *     · result.csv 가 서버 74열이고 값이 v3 기준 CSV 와 맞는다(조각 3개, VAD 뒤 길이 79.852초)
 *     · device.csv·run_meta.csv 가 생기고, 결과 폴더 이름에서 "_" 가 떨어진다
 * - 출력은 앱 캐시 폴더에 쓴다(실제 SAF 쓰기는 SafTreeTest·ResultWriterTest 가 본다).
 *
 * add-hyungchul-20261106-2233 — [T6-2]
 */
package com.example.google_stt.run

import android.net.Uri                                             // 음원 위치
import android.util.Log                                            // 측정값 기록
import androidx.documentfile.provider.DocumentFile                 // 출력 폴더
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 시험 APK 의 자산
import com.example.google_stt.data.saf.AudioItem                   // 음원 항목
import com.example.google_stt.data.saf.SafTree                     // 출력 폴더 트리
import com.example.google_stt.domain.catalog.Catalog               // 모델 목록
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.condition.RunOptions          // 실행 옵션
import com.example.google_stt.domain.condition.RunPurpose          // 실행 목적
import com.example.google_stt.domain.csv.CsvFormat                 // 한 줄 정리·글자 수 규칙
import com.example.google_stt.domain.csv.UnifiedCsv                // 74열 정의
import com.example.google_stt.domain.lang.LangFolder               // 언어 폴더
import com.example.google_stt.domain.lang.LangSource               // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                      // 작업
import com.example.google_stt.domain.plan.PlanProgress             // 상태 이름
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋
import com.example.google_stt.domain.vad.VadSetting                // VAD 설정
import com.example.google_stt.engine.EngineTestSupport             // 준비 도우미
import java.io.File                                                // 결과 파일
import kotlinx.coroutines.runBlocking                              // suspend 함수 부르기
import org.junit.Assert.assertArrayEquals                          // 바이트 비교
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Assume.assumeTrue                                 // 준비물이 없으면 건너뛰기
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: JobPipeline 실기기 시험. */
@RunWith(AndroidJUnit4::class)
class JobPipelineDeviceTest {

    private val context = EngineTestSupport.context
    private val container = EngineTestSupport.container

    /**
     * 목적: CSV 한 줄을 칸으로 나눈다(큰따옴표로 감싼 칸 안의 쉼표·따옴표를 다룬다).
     * 입력: line - CSV 줄 / 출력: 없음 / 리턴: List<String>
     */
    private fun cells(line: String): List<String> {
        val out = ArrayList<String>(); val sb = StringBuilder(); var quoted = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                quoted && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }   // "" → "
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(ch)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    /**
     * 목적: [IT-PIPE-01] whisper-base × SILERO × kr01 종단 실행.
     * 입력: 없음 / 출력: 앱 캐시에 결과 폴더 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun whisperBase_silero_kr01_endToEnd() = runBlocking<Unit> {
        val spec = Catalog.model("whisper-base")!!
        val preset = VadPresetCatalog.presets(VadEngine.SILERO).first { it.name == "SILERO" }
        val job = Job(LangFolder("kr", "id", Language.KO, LangSource.AUTO_SUBFOLDER), spec, VadSetting(VadEngine.SILERO, preset, preset.values),
            RunOptions().effectiveFor(spec.runtime))
        assertEquals("whisper-base_sherpa-int8_SILERO", job.resultDirName)
        val want = EngineTestSupport.expected(Language.KO, job.resultDirName)
        assumeTrue("기준 전사문이 없어 건너뜀", want != null)
        job.assetIds.forEach { EngineTestSupport.assetDir(it) }                        // 모델·VAD 자산 준비
        // 음원: 시험 APK 의 자산을 캐시 파일로 꺼내 실제 디코더로 읽는다
        val wav = File(context.cacheDir, "pipe_it_01.wav").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("audio/kr/Customer_Service_KR/01.wav").use { it.readBytes() })
        }
        val item = AudioItem(Uri.fromFile(wav), listOf("Customer_Service_KR"), "01.wav", "01", ".wav", wav.length())
        val out = File(context.cacheDir, "pipe_it_out").apply { deleteRecursively(); mkdirs() }
        val tree = SafTree(context.contentResolver, DocumentFile.fromFile(out))
        val result = JobPipeline(container.pipelineDeps(tree)).execute(job, listOf(container.audioSource(item)),
            RunParams("run_it", container.isoNow(), 1, RunPurpose.TIMING))
        Log.i("GoogleStt", "IT-PIPE-01: ${result.status} ok=${result.ok} fail=${result.fail} 소요 ${result.elapsedSec}s 음원 ${result.audioSec}s note='${result.note}'")
        assertEquals(PlanProgress.DONE, result.status); assertEquals(1, result.ok); assertEquals(0, result.fail)

        val lang = File(out, "kr")
        assertTrue("결과 폴더 이름에서 _ 가 떨어져야 한다: ${lang.list()?.toList()}", File(lang, job.resultDirName).isDirectory)
        assertFalse(File(lang, "_" + job.resultDirName).exists())
        assertArrayEquals("전사문이 v3 기준과 다르다", want, File(lang, "${job.resultDirName}/Customer_Service_KR/01.txt").readBytes())

        val csv = File(lang, "whisper-base_SILERO_result.csv").readBytes()
        assertTrue("utf-8-sig", csv[0] == 0xEF.toByte() && csv[1] == 0xBB.toByte() && csv[2] == 0xBF.toByte())
        val lines = String(csv, 3, csv.size - 3, Charsets.UTF_8).split("\r\n").filter { it.isNotEmpty() }
        val header = cells(lines[0]); val row = cells(lines[1])
        assertEquals(2, lines.size); assertEquals(74, header.size); assertEquals(UnifiedCsv.columns.map { it.name }, header); assertEquals(74, row.size)
        val v = header.zip(row).toMap()
        assertEquals("OK", v["status"]); assertEquals("whisper-base", v["algo"]); assertEquals("sherpa-int8", v["model"]); assertEquals("SILERO", v["option"])
        assertEquals("Customer_Service_KR", v["rel_dir"]); assertEquals("01", v["file"]); assertEquals("ko-KR", v["req_lang"])
        assertEquals("3", v["chunks"]); assertEquals("79.852", v["vad_kept_sec"]); assertEquals("84.135", v["audio_sec"])   // v3 기준 CSV 와 같은 값
        val text = String(want!!, Charsets.UTF_8)
        assertEquals(CsvFormat.oneLine(text), v["text"])                               // CSV 의 text 열은 공백을 한 칸으로 정리한 한 줄(TXT 는 원문 그대로)
        assertEquals(CsvFormat.textStats(text).first.toString(), v["chars"])
        assertTrue(v.getValue("transcribe_sec").toDouble() > 0); assertTrue(v.getValue("model_load_sec").toDouble() > 0)
        assertEquals("0", v["feed_measured"]); assertEquals("", v["note"])

        assertTrue(File(lang, "whisper-base_SILERO_device.csv").length() > 0)
        val meta = File(lang, "whisper-base_SILERO_run_meta.csv").readText(Charsets.UTF_8)
        for (k in listOf("file_count", "stt_runtime", "vad_preset", "run_purpose", "device_key", "asset_sha256:sherpa-onnx-whisper-base", "asset_sha256:silero-vad")) {
            assertTrue("run_meta 에 $k 가 없다", meta.contains(k))
        }
        assertFalse("자산 해시가 미검증으로 적혔다", meta.contains("unverified"))
    }
}
