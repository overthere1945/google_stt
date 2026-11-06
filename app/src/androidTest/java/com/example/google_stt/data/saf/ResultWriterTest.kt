/*
 * 파일명: data/saf/ResultWriterTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - [IT-FILE-03] ResultWriter 가 쓰는 파일을 확인한다:
 *     · result.csv 는 BOM 1회·74열, device.csv 와 같은 행 수, 10파일마다 다시 쓰기
 *     · TXT 는 전사문 그대로(빈 결과는 0바이트), 실패는 <이름>.ERROR.txt(D28), 끝나면 결과 폴더의 "_" 가 떨어진다
 *     · run_meta.csv, _summary.csv
 * - 앱 전용 폴더(DocumentFile.fromFile)에서 돈다. 실제 SAF 제공자에서의 쓰기 규칙은 SafTreeTest 가 확인한다.
 *
 * add-hyungchul-20261106-1643 — [T3-3]
 */
package com.example.google_stt.data.saf

import androidx.documentfile.provider.DocumentFile                 // SAF 문서
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context 얻기
import com.example.google_stt.domain.catalog.Catalog               // 모델 찾기
import com.example.google_stt.domain.catalog.Language              // 평가 언어
import com.example.google_stt.domain.condition.RunOptions          // 실행 옵션
import com.example.google_stt.domain.csv.AudioInfo                 // 음원 정보
import com.example.google_stt.domain.csv.DeviceSample              // 기기 계측
import com.example.google_stt.domain.csv.FileRecord                // 파일 처리 결과
import com.example.google_stt.domain.csv.RunMetaInput              // 실행 조건 입력
import com.example.google_stt.domain.csv.SttOutcome                // 전사 결과
import com.example.google_stt.domain.csv.SttStatus                 // 상태
import com.example.google_stt.domain.csv.Timings                   // 단계별 시간
import com.example.google_stt.domain.lang.LangFolder               // 언어 폴더
import com.example.google_stt.domain.lang.LangSource               // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                      // 작업
import com.example.google_stt.domain.vad.VadEngine                 // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog          // 프리셋 목록
import com.example.google_stt.domain.vad.VadSetting                // VAD 설정
import java.io.File                                                // 앱 전용 폴더
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: ResultWriter 계측 시험. */
@RunWith(AndroidJUnit4::class)
class ResultWriterTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext   // 시험 대상 앱의 Context
    private val kr = LangFolder("kr", "root/kr", Language.KO, LangSource.AUTO_SUBFOLDER)
    private val novad = VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())
    private val job = Job(kr, Catalog.model("whisper-base")!!, novad, RunOptions())    // kr/whisper-base_sherpa-int8_NOVAD

    /**
     * 목적: 시험용 파일 기록을 만든다.
     * 입력: n - 파일 번호, status - 상태, text - 전사문 / 출력: 없음 / 리턴: FileRecord
     */
    private fun record(n: Int, status: SttStatus = SttStatus.OK, text: String = "전사문 $n") = FileRecord(
        runId = "20261106_164300", startedAt = "2026-11-06T16:43:00+09:00", job = job,
        relDir = "Customer_Service_KR", file = "%02d.wav".format(n), audio = AudioInfo(".wav", 1000, 10.0, 16_000, 1),
        timings = Timings(0.01, 0.0, 0.0, 1.0, 1.1), stt = SttOutcome(text, 1, status, tailMs = 1000), vad = null,
        device = DeviceSample("SM-S928N SM8650", "SM-S928N/SM8650"), engineVersion = "sherpa-onnx 1.13.8", workers = 1,
        modelLoadSec = if (n == 1) 0.5 else 0.0, chunks = 1,
    )

    /**
     * 목적: 파일을 글자로 읽는다(없으면 null).
     * 입력: f - 파일 / 출력: 없음 / 리턴: String?
     */
    private fun text(f: File): String? = if (f.isFile) f.readText(Charsets.UTF_8) else null

    /**
     * 목적: [IT-FILE-03] 작업 1개를 끝까지 쓰고 파일 구성을 확인한다.
     * 입력: 없음 / 출력: 앱 캐시에 시험 폴더가 만들어졌다 지워진다 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun jobWriter_writesTextCsvMetaAndRenamesFolder() {
        val out = File(context.cacheDir, "result_writer_it").apply { deleteRecursively(); mkdirs() }
        try {
            val tree = SafTree(context.contentResolver, DocumentFile.fromFile(out))
            val writer = ResultWriter(tree)
            val w = writer.open(job)
            val work = File(out, "kr/_whisper-base_sherpa-int8_NOVAD")          // 작업 중 결과 폴더
            val csv = File(out, "kr/whisper-base_NOVAD_result.csv")             // 언어 폴더 바로 아래
            val dev = File(out, "kr/whisper-base_NOVAD_device.csv")
            assertTrue(work.isDirectory)

            // 전사문·무음·실패 저장
            val rel = listOf("Customer_Service_KR")
            w.saveText(rel, "01", "안녕하세요. 예약 가능할까요?")
            assertEquals("안녕하세요. 예약 가능할까요?", text(File(work, "Customer_Service_KR/01.txt")))   // 전사문만, 줄바꿈을 붙이지 않는다
            w.saveText(rel, "02", "")
            assertEquals(0L, File(work, "Customer_Service_KR/02.txt").length())  // 무음 = 0바이트
            w.saveText(rel, "03", "옛 결과")
            w.saveError(rel, "03", "ERR_STT: 엔진 오류\n둘째 줄", "부분")
            assertFalse(File(work, "Customer_Service_KR/03.txt").exists())       // 실패하면 TXT 를 남기지 않는다
            assertEquals("[ERROR] ERR_STT: 엔진 오류 둘째 줄\n[부분 결과(참고용, 평가 제외)] 부분\n",
                text(File(work, "Customer_Service_KR/03.ERROR.txt")))
            w.saveText(rel, "03", "다시 성공")                                    // 다시 성공하면 실패 기록을 지운다
            assertFalse(File(work, "Customer_Service_KR/03.ERROR.txt").exists())
            assertEquals("다시 성공", text(File(work, "Customer_Service_KR/03.txt")))

            // CSV: 10파일마다 다시 쓰기
            for (n in 1..9) w.add(record(n))
            assertFalse("9개까지는 CSV 를 쓰지 않는다", csv.exists())
            w.add(record(10))
            assertEquals(11, text(csv)!!.removePrefix("\uFEFF").split("\r\n").count { it.isNotEmpty() })   // 머리줄 + 10행
            for (n in 11..19) w.add(record(n, if (n == 15) SttStatus.ERROR else SttStatus.OK, if (n == 15) "" else "쉼표, \"따옴표\" $n"))
            assertEquals(11, text(csv)!!.removePrefix("\uFEFF").split("\r\n").count { it.isNotEmpty() })   // 아직 10행 그대로
            w.add(record(20)); w.add(record(21))
            assertEquals(21, text(csv)!!.removePrefix("\uFEFF").split("\r\n").count { it.isNotEmpty() })   // 20행으로 다시 씀
            assertEquals(21, w.count)

            // run_meta 와 마무리
            w.writeRunMeta(RunMetaInput(job, "2026-11-06T16:43:00+09:00", "4.0.0-dev", 21, "sherpa-onnx 1.13.8", 0.5, 4, "/models/x", 1, "timing", "download", "SM-S928N/SM8650"))
            assertTrue(w.finish())
            assertFalse(work.exists())                                           // "_" 가 떨어졌다
            assertTrue(File(out, "kr/whisper-base_sherpa-int8_NOVAD/Customer_Service_KR/01.txt").isFile)

            val body = text(csv)!!
            assertEquals(1, body.count { it == '\uFEFF' })                       // BOM 은 한 번
            assertTrue(body.startsWith("\uFEFFrun_id,started_at,algo,model,option,"))
            val lines = body.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
            assertEquals(22, lines.size)                                         // 머리줄 + 21행
            assertEquals(74, lines[0].split(",").size)                           // 머리줄 74열
            assertTrue(lines[1].startsWith("20261106_164300,2026-11-06T16:43:00+09:00,whisper-base,sherpa-int8,NOVAD,embedded,"))
            assertTrue(lines[15].contains(",ERROR,"))                            // 15번째 파일은 실패
            assertTrue(lines[12].endsWith(",\"쉼표, \"\"따옴표\"\" 12\""))        // 쉼표·따옴표가 든 전사문
            val devLines = text(dev)!!.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
            assertEquals(22, devLines.size)                                      // result.csv 와 같은 행 수
            assertEquals(31, devLines[0].split(",").size)
            val meta = text(File(out, "kr/whisper-base_NOVAD_run_meta.csv"))!!
            assertTrue(meta.startsWith("\uFEFFkey,value\r\nrun_started_at,2026-11-06T16:43:00+09:00\r\n"))
            assertTrue(meta.contains("\r\nresult_dir,whisper-base_sherpa-int8_NOVAD\r\n"))
            assertTrue(File(out, "kr").list()!!.none { it.endsWith(".tmp") })    // 임시 파일이 남지 않는다

            // 요약
            writer.writeSummary(listOf(SummaryRow(job.key, "DONE", 21, 20, 1, 25.2, 210.0), SummaryRow("kr/x", "FAILED", 0, 0, 0, 0.5, 0.0)))
            assertEquals("\uFEFFkey,status,files,ok,fail,elapsed_sec,audio_sec,rtf\r\n" +
                "kr/whisper-base_sherpa-int8_NOVAD,DONE,21,20,1,25.200,210.000,0.1200\r\nkr/x,FAILED,0,0,0,0.500,0.000,\r\n",
                text(File(out, "_summary.csv")))

            // 같은 이름의 완료 폴더가 이미 있으면 이름을 못 바꾼다("_" 폴더로 남는다 — 비켜 두기는 ResumeManager 의 일)
            val again = writer.open(job)
            again.saveText(rel, "01", "두 번째 실행")
            assertFalse(again.finish())
            assertTrue(work.isDirectory)
        } finally {
            out.deleteRecursively()
        }
    }

    /**
     * 목적: 입력 폴더 자체가 한 언어면 언어 폴더 없이 출력 폴더 바로 아래에 쓴다(FR-01-03).
     * 입력: 없음 / 출력: 시험 폴더가 만들어졌다 지워진다 / 리턴: 없음
     */
    @Test
    fun rootLanguageFolder_writesDirectlyUnderOutput() {
        val out = File(context.cacheDir, "result_writer_root_it").apply { deleteRecursively(); mkdirs() }
        try {
            val rootJob = job.copy(lang = LangFolder("", "primary:Documents/x/kr", Language.KO, LangSource.AUTO_ROOT))
            val w = ResultWriter(SafTree(context.contentResolver, DocumentFile.fromFile(out))).open(rootJob)
            w.saveText(emptyList(), "a", "가")
            w.add(record(1).copy(job = rootJob, relDir = ""))
            assertTrue(w.finish())
            assertEquals("가", text(File(out, "whisper-base_sherpa-int8_NOVAD/a.txt")))
            assertTrue(File(out, "whisper-base_NOVAD_result.csv").isFile)
        } finally {
            out.deleteRecursively()
        }
    }
}
