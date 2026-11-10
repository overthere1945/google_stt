/*
 * 파일명: data/device/AppLogTest.kt (app/src/androidTest — 계측 시험, S24 Ultra)
 * 목적 및 기능:
 * - AppLog 가 화면용 최근 목록과 출력 폴더의 _app_log.txt 에 줄을 남기는지 확인한다(FR-10-01):
 *   연결 전에는 파일 없음, 50줄마다 쓰기, "[D] " 표시, 오류 줄에 예외 정보, 크기 한도에서 _app_log.1.txt 로 돌리기(1개만 보관).
 *
 * add-hyungchul-20261106-1650 — [T3-5a]
 */
package com.example.google_stt.data.device

import androidx.documentfile.provider.DocumentFile                 // SAF 문서
import androidx.test.ext.junit.runners.AndroidJUnit4               // 계측 시험 실행기
import androidx.test.platform.app.InstrumentationRegistry          // 앱 Context 얻기
import com.example.google_stt.data.saf.SafTree                     // 출력 폴더 트리
import java.io.File                                                // 앱 전용 폴더
import org.junit.Assert.assertEquals                               // 값 비교
import org.junit.Assert.assertFalse                                // 거짓 확인
import org.junit.Assert.assertTrue                                 // 조건 확인
import org.junit.Test                                              // JUnit 4 시험 표시
import org.junit.runner.RunWith                                    // 실행기 지정

/** 목적: AppLog 계측 시험. */
@RunWith(AndroidJUnit4::class)
class AppLogTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext   // 시험 대상 앱의 Context
    private val stampRe = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ")   // 파일 줄 앞의 시각

    /**
     * 목적: 빈 출력 폴더를 만든다.
     * 입력: name - 폴더 이름 / 출력: 폴더가 새로 만들어진다 / 리턴: File
     */
    private fun outDir(name: String): File = File(context.cacheDir, name).apply { deleteRecursively(); mkdirs() }

    /**
     * 목적: 연결 전에는 화면 목록에만 남고, 연결 뒤에는 flush 때 파일에 쓰인다. 디버그·오류 줄의 형식.
     * 입력: 없음 / 출력: 시험 폴더가 만들어졌다 지워진다 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun lines_goToRecentAndFile() {
        val out = outDir("applog_it")
        try {
            val log = AppLog()
            log.i("연결 전 줄")
            log.flush()
            assertFalse(File(out, "_app_log.txt").exists())                        // 폴더가 없으면 파일에 쓰지 않는다
            assertEquals(listOf("연결 전 줄"), log.recent.value)
            log.attach(SafTree(context.contentResolver, DocumentFile.fromFile(out)))
            log.i("[목록] 시작 kr/whisper-base/SILERO")
            log.d("sherpa load done: 454ms")
            log.w("E-OUT-03: 이름 바꾸기 실패")
            log.e("ERR_STT: 01.wav", IllegalStateException("엔진 오류\n둘째 줄"))
            assertFalse("50줄이 안 되면 아직 쓰지 않는다", File(out, "_app_log.txt").exists())
            log.flush()
            val lines = File(out, "_app_log.txt").readLines(Charsets.UTF_8)
            assertEquals(4, lines.size)                                            // 연결 전 줄은 파일에 없다(이미 버렸다)
            assertTrue(lines.all { stampRe.containsMatchIn(it) })                  // 줄마다 시각
            assertTrue(lines[0].endsWith(" [목록] 시작 kr/whisper-base/SILERO"))
            assertTrue(lines[1].endsWith(" [D] sherpa load done: 454ms"))          // 디버그 표시
            assertTrue(lines[3].endsWith(" ERR_STT: 01.wav — IllegalStateException: 엔진 오류 둘째 줄"))   // 줄바꿈은 공백으로
            assertEquals(5, log.recent.value.size)
            assertEquals("[D] sherpa load done: 454ms", log.recent.value[2])       // 화면 목록에는 시각 없이 같은 문장
            log.flush()
            assertEquals(4, File(out, "_app_log.txt").readLines().size)            // 쓸 것이 없으면 그대로
        } finally {
            out.deleteRecursively()
        }
    }

    /**
     * 목적: 50줄이 모이면 스스로 쓰고, 화면 목록은 최근 200줄만 기억한다. 다시 연결하면 같은 파일에 이어 쓴다.
     * 입력: 없음 / 출력: 시험 폴더가 만들어졌다 지워진다 / 리턴: 없음
     */
    @Test
    fun autoFlushEvery50_andRecentCappedAt200() {
        val out = outDir("applog_flush_it")
        try {
            val log = AppLog()
            log.attach(SafTree(context.contentResolver, DocumentFile.fromFile(out)))
            for (n in 1..49) log.i("줄 $n")
            assertFalse(File(out, "_app_log.txt").exists())
            log.i("줄 50")                                                          // 50번째에 쓴다
            assertEquals(50, File(out, "_app_log.txt").readLines().size)
            for (n in 51..260) log.i("줄 $n")
            assertEquals(250, File(out, "_app_log.txt").readLines().size)          // 50줄 단위로 5번
            assertEquals(AppLog.RECENT_MAX, log.recent.value.size)
            assertEquals("줄 61", log.recent.value.first()); assertEquals("줄 260", log.recent.value.last())
            log.attach(null)                                                       // 떼면서 남은 10줄을 쓴다
            assertEquals(260, File(out, "_app_log.txt").readLines().size)
            val again = AppLog()
            again.attach(SafTree(context.contentResolver, DocumentFile.fromFile(out)))   // 앱을 다시 켠 뒤
            again.i("이어 쓰기"); again.flush()
            assertEquals(261, File(out, "_app_log.txt").readLines().size)          // 같은 파일 끝에 잇는다
        } finally {
            out.deleteRecursively()
        }
    }

    /**
     * 목적: 크기 한도를 넘기 전에 _app_log.1.txt 로 돌리고 새 파일로 시작한다. 앞 로그는 1개만 남는다.
     * 입력: 없음 / 출력: 시험 폴더가 만들어졌다 지워진다 / 리턴: 없음
     */
    @Test
    fun rotation_keepsOneOldFile() {
        val out = outDir("applog_rotate_it")
        try {
            val log = AppLog(maxBytes = 400)                                       // 시험용 작은 한도
            log.attach(SafTree(context.contentResolver, DocumentFile.fromFile(out)))
            val cur = File(out, "_app_log.txt"); val old = File(out, "_app_log.1.txt")
            for (round in 1..4) {                                                  // 한 번에 약 190바이트씩 4번
                for (n in 1..5) log.i("r$round line $n ........")
                log.flush()
            }
            assertTrue(cur.isFile && old.isFile)
            assertTrue("cur=${cur.length()}", cur.length() in 1..400)              // 한도를 넘지 않는다
            assertTrue("old=${old.length()}", old.length() in 1..400)
            assertTrue(cur.readText().contains("r4 line 5"))                       // 가장 새 줄은 지금 파일에
            assertFalse(cur.readText().contains("r1 line 1"))                      // 가장 오래된 줄은 버려졌다
            assertFalse(old.readText().contains("r1 line 1"))
            assertEquals(setOf("_app_log.txt", "_app_log.1.txt"), out.list()!!.toSet())   // 앞 로그는 1개만
        } finally {
            out.deleteRecursively()
        }
    }
}
