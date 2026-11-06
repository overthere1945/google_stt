/*
 * 파일명: data/saf/OutputLayoutTest.kt (app/src/test)
 * 목적 및 기능:
 * - 출력 폴더의 이름·경로 규칙을 확인한다(D2, D28).
 *     · UT-PATH-01 결과 경로 <출력>/kr/<엔진>_<모델>_<조건>/<상대 폴더>/<이름>.txt
 *     · UT-PATH-02 실패 파일 <이름>.ERROR.txt 와 그 내용(서버 save_failure 형식)
 *     · UT-PATH-03 작업 중 폴더 "_<이름>", 묵은 폴더 "_stale_<이름>_<yyyyMMdd_HHmmss>"
 *
 * add-hyungchul-20261106-1636 — [T3-2]
 */
package com.example.google_stt.data.saf

import com.example.google_stt.domain.catalog.Catalog          // 모델 찾기
import com.example.google_stt.domain.catalog.Language         // 평가 언어
import com.example.google_stt.domain.condition.RunOptions     // 실행 옵션
import com.example.google_stt.domain.csv.DeviceCsv            // CSV 이름 꼬리
import com.example.google_stt.domain.csv.RunMeta              // CSV 이름 꼬리
import com.example.google_stt.domain.csv.UnifiedCsv           // CSV 이름 꼬리
import com.example.google_stt.domain.lang.LangFolder          // 언어 폴더
import com.example.google_stt.domain.lang.LangSource          // 언어를 정한 방법
import com.example.google_stt.domain.plan.Job                 // 작업
import com.example.google_stt.domain.vad.VadEngine            // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog     // 프리셋 목록
import com.example.google_stt.domain.vad.VadSetting           // VAD 설정
import org.junit.Assert.assertEquals                          // 값 비교
import org.junit.Test                                         // JUnit 4 시험 표시

/** 목적: 출력 경로 규칙 시험. */
class OutputLayoutTest {

    private val preset = VadPresetCatalog.presets(VadEngine.PYANNOTE).first { it.name == "PYANNOTE_S500" }
    private val vad = VadSetting(VadEngine.PYANNOTE, preset, preset.values)                // PYANNOTE_S500
    private val kr = LangFolder("kr", "root/kr", Language.KO, LangSource.AUTO_SUBFOLDER)   // 한국어 폴더
    private val job = Job(kr, Catalog.model("qwen3asr-0.6b")!!, vad, RunOptions())         // 4단계 7-1 예시와 같은 작업

    /**
     * 목적: [UT-PATH-01] 전사 TXT 위치와 CSV 3종 이름이 4단계 7-1 의 예시와 같다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun resultPaths_matchDesign() {
        assertEquals(listOf("kr", "qwen3asr-0.6b_sherpa-int8_PYANNOTE_S500", "Customer_Service_KR"),
            OutputLayout.textDirSegments(job, listOf("Customer_Service_KR"), working = false))
        assertEquals("01.txt", OutputLayout.textName("01"))
        assertEquals("qwen3asr-0.6b_PYANNOTE_S500_result.csv", OutputLayout.csvName(job, UnifiedCsv.FILE_SUFFIX))
        assertEquals("qwen3asr-0.6b_PYANNOTE_S500_device.csv", OutputLayout.csvName(job, DeviceCsv.FILE_SUFFIX))
        assertEquals("qwen3asr-0.6b_PYANNOTE_S500_run_meta.csv", OutputLayout.csvName(job, RunMeta.FILE_SUFFIX))
        assertEquals(listOf("kr"), OutputLayout.langSegments(job))
        assertEquals("Friends/a", OutputLayout.relDirText(listOf("Friends", "a")))
        assertEquals("", OutputLayout.relDirText(emptyList()))                  // 최상위면 빈 글
    }

    /**
     * 목적: [UT-PATH-01] 입력 폴더 자체가 한 언어면 언어 폴더 단계가 없다(FR-01-03).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rootLanguageFolder_hasNoLangSegment() {
        val root = LangFolder("", "primary:Documents/input_sound/kr", Language.KO, LangSource.AUTO_ROOT)
        val j = job.copy(lang = root)
        assertEquals(emptyList<String>(), OutputLayout.langSegments(j))
        assertEquals(listOf("_qwen3asr-0.6b_sherpa-int8_PYANNOTE_S500", "vLog_KR"),
            OutputLayout.textDirSegments(j, listOf("vLog_KR"), working = true))
    }

    /**
     * 목적: [UT-PATH-02] 실패하면 <이름>.ERROR.txt 를 쓰고, 내용의 첫 줄은 "[ERROR] <원인 한 줄>" 이다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun errorFile_nameAndContent() {
        assertEquals("01.ERROR.txt", OutputLayout.errorName("01"))
        assertEquals("[ERROR] ERR_DECODE: 헤더 오류 다음 줄\n", OutputLayout.errorText("ERR_DECODE: 헤더 오류\n다음 줄"))   // 원인은 한 줄로
        assertEquals("[ERROR] ERR_TIMEOUT\n[부분 결과(참고용, 평가 제외)] 안녕하세요 반갑\n",
            OutputLayout.errorText("ERR_TIMEOUT", "안녕하세요\n반갑"))
        assertEquals("[ERROR] ERR_STT\n", OutputLayout.errorText("ERR_STT", "  \n"))   // 빈 부분 결과는 적지 않는다
    }

    /**
     * 목적: [UT-PATH-03] 작업 중 폴더는 "_" 접두어, 묵은 폴더는 "_stale_<이름>_<시각>".
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun workingAndStaleDirNames() {
        assertEquals("_qwen3asr-0.6b_sherpa-int8_PYANNOTE_S500", OutputLayout.resultDirName(job, working = true))
        assertEquals("qwen3asr-0.6b_sherpa-int8_PYANNOTE_S500", OutputLayout.resultDirName(job, working = false))
        assertEquals("_stale_qwen3asr-0.6b_sherpa-int8_PYANNOTE_S500_20261106_163600", OutputLayout.staleDirName(job, "20261106_163600"))
        assertEquals("_plan_progress.csv", OutputLayout.PROGRESS_FILE)
    }

    /**
     * 목적: 파일 이름을 이름과 확장자(점 포함 소문자)로 나눈다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun splitName_lowercasesExtension() {
        assertEquals("01" to ".wav", OutputLayout.splitName("01.WAV"))
        assertEquals("a.b" to ".mp3", OutputLayout.splitName("a.b.mp3"))
        assertEquals("README" to "", OutputLayout.splitName("README"))
        assertEquals(".hidden" to "", OutputLayout.splitName(".hidden"))        // 점으로 시작하는 이름은 확장자가 아니다
    }
}
