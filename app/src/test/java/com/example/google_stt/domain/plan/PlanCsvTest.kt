/*
 * 파일명: domain/plan/PlanCsvTest.kt (app/src/test)
 * 목적 및 기능:
 * - 조합 목록 CSV 를 확인한다(FR-04-04).
 *     · UT-PLAN-01 v3 견본 CSV(docs/stt_plan_samples/plan_kr_en_app_REALTIME_VAD22.csv)를 읽어 조합 생성기와 같은 616 조합
 *     · UT-PLAN-02 내보내기 → 불러오기 왕복, 해석 오류(E-CSV-01)
 * - 읽기 결과가 v3 PlanCsv.parse 와 같은지 대조한다(T8-2 에서 v3 코드와 함께 지운다).
 *
 * add-hyungchul-20261106-0046 — [T2-6]
 */
package com.example.google_stt.domain.plan

import com.example.google_stt.domain.catalog.Catalog          // 모델 목록
import com.example.google_stt.domain.catalog.Language         // 평가 언어
import com.example.google_stt.domain.catalog.Runtime          // 런타임
import com.example.google_stt.domain.condition.FeedChoice     // 공급 속도
import com.example.google_stt.domain.condition.RunOptions     // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder          // 언어 폴더
import com.example.google_stt.domain.lang.LangPlan            // 언어 계획
import com.example.google_stt.domain.lang.LangSource          // 언어를 정한 방법
import com.example.google_stt.domain.vad.SegMode              // 구간 처리
import com.example.google_stt.domain.vad.VadEngine            // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog     // 프리셋 목록
import com.example.google_stt.domain.vad.VadSetting           // VAD 설정
import java.io.File                                           // 견본 CSV 읽기
import org.junit.Assert.assertEquals                          // 값 비교
import org.junit.Assert.assertTrue                            // 조건 확인
import org.junit.Test                                         // JUnit 4 시험 표시

/** 목적: 목록 CSV 시험. */
class PlanCsvTest {

    private val kr = LangFolder("kr", "root/kr", Language.KO, LangSource.AUTO_SUBFOLDER)   // 한국어 폴더
    private val en = LangFolder("en", "root/en", Language.EN, LangSource.AUTO_SUBFOLDER)   // 영어 폴더
    private val krEn = LangPlan(listOf(kr, en), emptyList(), emptyList(), true)            // kr·en 계획

    /** v3 견본 CSV 내용(Gradle 단위 시험의 작업 폴더는 모듈 폴더 app/ 이다) */
    private val sample: String = File("../docs/stt_plan_samples/plan_kr_en_app_REALTIME_VAD22.csv").readText(Charsets.UTF_8)

    /**
     * 목적: [UT-PLAN-01] v3 견본 CSV 를 읽으면 오류 없이 616 조합이고, 조합 생성기가 만든 것과 결과 폴더(키)가 같다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음(다르면 시험 실패)
     */
    @Test
    fun v3Sample_gives616SameAsGenerator() {
        val parsed = PlanCsv.parse(sample)
        assertEquals(emptyList<String>(), parsed.errors)                      // 형식 오류 없음
        assertEquals(616, parsed.rows.size)                                   // 견본 주석의 작업 수
        assertEquals(0, parsed.duplicates)
        val loaded = PlanCsv.toJobs(parsed.rows, krEn)
        assertEquals(emptyList<String>(), loaded.errors)                      // 해석 오류 없음
        assertEquals(616, loaded.jobs.size)
        val vad22 = listOf(VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())) +
            VadEngine.values().filter { it != VadEngine.NONE }.flatMap { e ->
                VadPresetCatalog.presets(e).filter { !it.isCustom }.map { VadSetting(e, it, it.values) }
            }
        val generated = ComboGenerator.generate(krEn, Catalog.models.filter { it.runtime != Runtime.MLKIT }, vad22,
            listOf(RunOptions(feed = FeedChoice.REALTIME)))                   // 견본은 공급 속도 REALTIME
        assertEquals(generated.jobs.map { it.key }.toSet(), loaded.jobs.map { it.key }.toSet())   // 같은 결과 폴더 집합
    }

    /**
     * 목적: 읽은 줄이 v3 PlanCsv.parse 와 같다(같은 순서, 같은 값).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun parse_matchesV3() {
        val old = com.example.google_stt.plan.PlanCsv.parse(sample)           // v3 읽기(대조용)
        assertEquals(old.jobs.map { it.csvLine }, PlanCsv.parse(sample).rows.map { it.csvLine })
        assertEquals(old.errors, PlanCsv.parse(sample).errors)
    }

    /**
     * 목적: [UT-PLAN-02] 작업 → CSV 글 → 작업으로 돌아와도 같다(고정 프리셋, 공급 속도·구간 처리 포함).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun exportThenImport_roundTrips() {
        val p = VadPresetCatalog.presets(VadEngine.PYANNOTE).first { it.name == "PYANNOTE_S500" }
        val vads = listOf(VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap()), VadSetting(VadEngine.PYANNOTE, p, p.values))
        val models = listOf("whisper-base", "nemotron3.5-560ms", "vosk-small-ko", "google-mlkit").map { Catalog.model(it)!! }
        val opts = listOf(RunOptions(), RunOptions(seg = SegMode.SEG10, feed = FeedChoice.D50))
        val jobs = ComboGenerator.generate(krEn, models, vads, opts).jobs
        val text = PlanCsv.format(PlanCsv.fromJobs(jobs), "왕복 시험")
        assertTrue(text.startsWith("\uFEFF# 왕복 시험\nlang,model,feed,vad,seg\n"))   // BOM + 주석 + 머리줄
        val back = PlanCsv.toJobs(PlanCsv.parse(text).rows, krEn)
        assertEquals(emptyList<String>(), back.errors)
        assertEquals(jobs, back.jobs)                                         // 순서와 내용이 같다
    }

    /**
     * 목적: 해석 오류 — 모르는 모델·공급 속도·VAD·seg·언어 폴더, 언어 전용 모델, CUSTOM VAD 는 오류로 알리고 작업을 만들지 않는다(E-CSV-01).
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun badRows_areReportedNotLoaded() {
        val text = "lang,model,feed,vad,seg\n" +
            "kr,whisper-base,REALTIME,SILERO,MAX\n" +                         // 3행은 정상(머리줄이 1행… 아래에서 줄 번호 확인)
            "kr,no-such-model,REALTIME,SILERO,MAX\n" +
            "en,zipformer-ko,REALTIME,SILERO,MAX\n" +
            "kr,whisper-base,FAST,SILERO,MAX\n" +
            "kr,whisper-base,REALTIME,SILERO_CUSTOM_t40_sp250_si300_pad100,MAX\n" +
            "kr,whisper-base,REALTIME,SILERO,SEG5\n" +
            "jp,whisper-base,REALTIME,SILERO,MAX\n"
        val r = PlanCsv.toJobs(PlanCsv.parse(text).rows, krEn)
        assertEquals(listOf("kr/whisper-base_sherpa-int8_SILERO"), r.jobs.map { it.key })   // 정상 줄만
        assertEquals(6, r.errors.size)                                        // 틀린 줄마다 오류 하나
        assertTrue(r.errors[0].startsWith("3행:") && "no-such-model" in r.errors[0])
        assertTrue("언어 전용" in r.errors[1])
        assertTrue("FAST" in r.errors[2])
        assertTrue("CUSTOM" in r.errors[3])
        assertTrue("SEG5" in r.errors[4])
        assertTrue("jp" in r.errors[5])
        assertTrue(PlanCsv.parse("model,lang\nkr,x\n").errors.single().contains("머리줄"))   // 머리줄이 틀리면 멈춘다
        assertEquals(1, PlanCsv.parse("lang,model,feed,vad,seg\nkr,a,REALTIME,NOVAD\n").errors.size)   // 칸 4개
    }

    /**
     * 목적: 입력 폴더 자체가 한 언어(언어 폴더 이름 "")이면 lang 칸 kr 은 그 폴더로 해석되고, 내보낼 때도 kr 로 쓴다.
     * 입력: 없음 / 출력: 없음 / 리턴: 없음
     */
    @Test
    fun rootLanguageFolder_usesLanguageWord() {
        val root = LangPlan(listOf(LangFolder("", "primary:Documents/x/kr", Language.KO, LangSource.AUTO_ROOT)), emptyList(), emptyList(), false)
        val r = PlanCsv.toJobs(PlanCsv.parse("lang,model,feed,vad,seg\nkr,whisper-base,NONE,NOVAD,SEG10\n").rows, root)
        assertEquals(listOf("whisper-base_sherpa-int8_NOVAD_SEG10"), r.jobs.map { it.key })   // 언어 폴더 없이 바로
        assertEquals("kr,whisper-base,NONE,NOVAD,SEG10", PlanCsv.fromJobs(r.jobs).single().csvLine)
        assertEquals(1, PlanCsv.toJobs(PlanCsv.parse("lang,model,feed,vad,seg\nen,whisper-base,NONE,NOVAD,MAX\n").rows, root).errors.size)
    }
}
