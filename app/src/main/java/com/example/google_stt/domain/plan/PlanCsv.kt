/*
 * 파일명: domain/plan/PlanCsv.kt
 * 목적 및 기능:
 * - 조합 목록 CSV(출력 폴더의 _plan.csv)를 읽고 쓴다. 형식은 v3 목록 CSV 와 같다(FR-04-04):
 *     머리줄 lang,model,feed,vad,seg / 칸 안 ';' 로 여러 값 → 조합으로 펼침 / '#' 주석 / UTF-8 BOM
 * - parse / format 은 v3 plan/PlanModels.kt 의 PlanCsv 를 그대로 옮겼다(행 자료형 이름만 PlanJob → PlanRow).
 * - toJobs 는 행을 카탈로그·언어 계획으로 해석해 작업(Job)으로 만든다. v3 의 PlanCheck.errors 를 대신한다.
 *   오류가 하나라도 있으면 호출한 쪽은 목록을 불러오지 않는다(E-CSV-01).
 * - v3 와 달라진 점: feed 칸에 NONE(사용 안 함) 을 쓸 수 있다. ML Kit 도 목록에 넣을 수 있다.
 *   CSV 는 5칸뿐이라 노이즈 저감·EPD 는 담지 못한다(toJobs 의 base 옵션에서 가져온다). CUSTOM VAD 는 읽을 수 없다.
 *
 * add-hyungchul-20261106-0046 — [T2-6]
 */
package com.example.google_stt.domain.plan

import com.example.google_stt.domain.catalog.Catalog          // 모델 찾기
import com.example.google_stt.domain.catalog.Language         // 언어 폴더 이름(입력 폴더 자체일 때)
import com.example.google_stt.domain.condition.FeedChoice     // 공급 속도
import com.example.google_stt.domain.condition.RunOptions     // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder          // 언어 폴더
import com.example.google_stt.domain.lang.LangPlan            // 언어 계획
import com.example.google_stt.domain.lang.LanguageResolver    // lang 칸 → 언어
import com.example.google_stt.domain.vad.SegMode              // 구간 처리
import com.example.google_stt.domain.vad.VadEngine            // VAD 엔진
import com.example.google_stt.domain.vad.VadPresetCatalog     // 고정 프리셋 찾기
import com.example.google_stt.domain.vad.VadSetting           // VAD 설정

/**
 * 목적: 펼쳐진 목록 CSV 한 줄(조합 하나).
 * 필드: lang - 언어 폴더 이름(kr / en …), model - 결과 엔진 이름, feed - 공급 속도 이름(NONE / REALTIME …),
 *       vad - VAD 프리셋 이름(NOVAD / SILERO_SAFE …), seg - MAX / SEG10, lineNo - CSV 줄 번호(0 = 프로그램이 만든 줄)
 */
data class PlanRow(
    val lang: String,          // 언어 폴더 이름
    val model: String,         // 결과 엔진 이름
    val feed: String,          // 공급 속도 이름
    val vad: String,           // VAD 프리셋 이름
    val seg: String,           // 구간 처리 토큰
    val lineNo: Int = 0,       // CSV 줄 번호
) {
    /** CSV 한 줄(펼친 형태) — 저장·중복 판정에 쓴다 */
    val csvLine: String get() = "$lang,$model,$feed,$vad,$seg"
}

/**
 * 목적: 목록 CSV 를 읽은 결과.
 * 필드: rows - 펼친 줄(중복 제거, 처음 나온 순서), errors - 줄 번호가 붙은 형식 오류, duplicates - 지운 중복 수
 */
data class PlanParse(
    val rows: List<PlanRow>,   // 펼친 줄
    val errors: List<String>,  // 형식 오류
    val duplicates: Int,       // 지운 중복 수
)

/**
 * 목적: 목록 줄을 작업으로 해석한 결과.
 * 필드: jobs - 작업(CSV 순서, 같은 결과 폴더는 하나만), errors - 해석 오류(하나라도 있으면 불러오지 않는다)
 */
data class PlanJobs(
    val jobs: List<Job>,       // 작업
    val errors: List<String>,  // 해석 오류
)

/** 목적: 목록 CSV 읽기·쓰기·해석. */
object PlanCsv {

    /** 목록 CSV 머리줄(열 순서 고정) */
    const val HEADER = "lang,model,feed,vad,seg"

    /** 구간 처리 토큰: 엔진별 최대 길이 */
    const val SEG_MAX = "MAX"

    /** 구간 처리 토큰: 10초 통일 */
    const val SEG_10 = "SEG10"

    /**
     * 목적: 목록 CSV 글을 읽어 줄로 펼친다.
     * 입력: text - 파일 내용(UTF-8, BOM 있어도 됨)
     * 출력: 없음
     * 리턴: PlanParse(rows, errors, duplicates)
     * 규칙: 빈 줄·'#' 줄은 건너뜀 / 첫 내용 줄은 머리줄(대소문자·공백 무시) /
     *       한 줄 = 칸 5개, 칸 안 ';' 로 여러 값 → lang × model × feed × vad × seg 순서로 펼침
     */
    fun parse(text: String): PlanParse {
        val rows = LinkedHashMap<String, PlanRow>()          // csvLine → 줄(처음 나온 것만 남긴다)
        val errors = mutableListOf<String>()                 // 오류 문장
        var duplicates = 0                                   // 중복 수
        var headerSeen = false                               // 머리줄을 읽었는지
        val lines = text.removePrefix("\uFEFF").split("\n")  // BOM 제거 후 줄로 나눈다
        for ((i, raw) in lines.withIndex()) {
            val lineNo = i + 1                               // 사람이 보는 줄 번호(1부터)
            val line = raw.trim().removeSuffix("\r").trim()  // CRLF·앞뒤 공백 제거
            if (line.isEmpty() || line.startsWith("#")) continue   // 빈 줄·주석
            if (!headerSeen) {                               // 첫 내용 줄 = 머리줄
                if (line.replace(" ", "").lowercase() != HEADER) {
                    errors += "${lineNo}행: 머리줄이 '$HEADER' 가 아니다 — '$line'"
                    return PlanParse(emptyList(), errors, 0) // 머리줄이 틀리면 열 뜻을 알 수 없으므로 멈춘다
                }
                headerSeen = true
                continue
            }
            val cells = line.split(",")                      // 칸 나누기(값 안에 쉼표는 쓰지 않는다)
            if (cells.size != 5) {
                errors += "${lineNo}행: 칸이 5개가 아니다(${cells.size}개) — '$line'"
                continue
            }
            // 칸마다 ';' 로 나누고 공백·빈 값 제거. 열마다 표기를 맞춘다(lang 소문자, feed·vad·seg 대문자, model 그대로)
            val values = cells.map { c -> c.split(";").map { it.trim() }.filter { it.isNotEmpty() } }
            val names = listOf("lang", "model", "feed", "vad", "seg")
            val emptyCol = values.indexOfFirst { it.isEmpty() }
            if (emptyCol >= 0) {
                errors += "${lineNo}행: ${names[emptyCol]} 칸이 비어 있다"
                continue
            }
            for (lang in values[0]) for (model in values[1]) for (feed in values[2]) for (vad in values[3]) for (seg in values[4]) {
                val row = PlanRow(
                    lang = lang.lowercase(),                 // kr / en
                    model = model,                           // 결과 엔진 이름(대소문자 그대로)
                    feed = feed.uppercase(),                 // REALTIME …
                    vad = vad.uppercase(),                   // SILERO_SAFE …
                    seg = seg.uppercase(),                   // MAX / SEG10
                    lineNo = lineNo,
                )
                if (rows.containsKey(row.csvLine)) duplicates++   // 같은 조합이 이미 있으면 센다
                else rows[row.csvLine] = row
            }
        }
        if (!headerSeen) errors += "머리줄('$HEADER')이 없다"
        return PlanParse(rows.values.toList(), errors, duplicates)
    }

    /**
     * 목적: 줄 목록을 CSV 글로 만든다(한 줄 = 조합 1개, 펼친 형태).
     * 입력: rows - 줄 목록, comment - 맨 위 주석 한 줄
     * 출력: 없음
     * 리턴: String - UTF-8 BOM 으로 시작하는 CSV 글(엑셀에서 한글이 깨지지 않게)
     */
    fun format(rows: List<PlanRow>, comment: String): String =
        "\uFEFF# ${comment.replace("\n", " ")}\n$HEADER\n" +          // BOM + 주석 + 머리줄
            rows.joinToString("") { it.csvLine + "\n" }              // 한 줄씩

    /**
     * 목적: 작업 목록을 CSV 줄로 바꾼다(내보내기).
     * 입력: jobs - 작업 목록
     * 출력: 없음
     * 리턴: List<PlanRow> - 작업 순서 그대로. 입력 폴더 자체가 한 언어면 lang 칸은 kr / en 으로 쓴다
     */
    fun fromJobs(jobs: List<Job>): List<PlanRow> = jobs.map { j ->
        PlanRow(
            lang = j.lang.name.ifEmpty { if (j.language == Language.KO) "kr" else "en" },   // 언어 폴더 이름
            model = j.model.engine,                                                         // 결과 엔진 이름
            feed = j.opt.feed.name,                                                         // NONE / REALTIME …
            vad = j.vad.conditionToken(),                                                   // 프리셋 이름
            seg = if (j.opt.seg == SegMode.SEG10) SEG_10 else SEG_MAX,                      // 구간 처리
        )
    }

    /**
     * 목적: CSV 줄을 작업으로 해석한다.
     * 입력: rows - 펼친 줄, plan - 언어 계획(lang 칸을 언어 폴더에 맞춘다), base - 노이즈 저감·EPD 를 가져올 기본 옵션
     * 출력: 없음
     * 리턴: PlanJobs(jobs, errors) - 오류 문장은 같은 줄·같은 이유를 한 번만 담는다
     */
    fun toJobs(rows: List<PlanRow>, plan: LangPlan, base: RunOptions = RunOptions()): PlanJobs {
        val jobs = LinkedHashMap<String, Job>()                          // key → 작업(처음 나온 것만)
        val errors = LinkedHashSet<String>()                             // 순서 유지 + 중복 제거
        for (r in rows) {
            val at = if (r.lineNo > 0) "${r.lineNo}행" else "목록"        // 어디서 온 줄인지
            val before = errors.size                                     // 이 줄에서 오류가 났는지 보려고
            val folder = findFolder(r.lang, plan)
            if (folder == null) errors += "$at: 언어 폴더 '${r.lang}' 가 입력 폴더에 없다"
            val model = Catalog.model(r.model)
            if (model == null) errors += "$at: 모델 '${r.model}' 없음"
            if (folder != null && model != null && !model.langSupport.supports(folder.lang!!)) {
                errors += "$at: '${r.model}' 는 이 언어 폴더('${r.lang}')에 쓸 수 없다(언어 전용 모델)"
            }
            val feed = FeedChoice.values().firstOrNull { it.name == r.feed }
            if (feed == null) errors += "$at: 공급 속도 '${r.feed}' 없음"
            val vad = findVad(r.vad)
            if (vad == null) errors += "$at: VAD '${r.vad}' 없음(고정 프리셋·NOVAD 만, CUSTOM 은 목록에 쓸 수 없다)"
            val seg = when (r.seg) { SEG_MAX -> SegMode.ENGINE_MAX; SEG_10 -> SegMode.SEG10; else -> null }
            if (seg == null) errors += "$at: seg '${r.seg}' 는 $SEG_MAX / $SEG_10 만 된다"
            if (errors.size != before || folder == null || model == null || feed == null || vad == null || seg == null) continue
            val job = Job(folder, model, vad, base.copy(seg = seg, feed = feed).effectiveFor(model.runtime))
            jobs.putIfAbsent(job.key, job)                               // 같은 결과 폴더는 한 번만
        }
        return PlanJobs(jobs.values.toList(), errors.toList())
    }

    /**
     * 목적: lang 칸 값에 맞는 언어 폴더를 찾는다.
     * 입력: lang - CSV 의 lang 칸(소문자), plan - 언어 계획
     * 출력: 없음
     * 리턴: LangFolder? - 이름이 같은 폴더. 없으면, 입력 폴더 자체가 한 언어일 때 그 언어와 같은 뜻의 낱말이면 그 폴더
     */
    private fun findFolder(lang: String, plan: LangPlan): LangFolder? {
        plan.folders.firstOrNull { it.name.lowercase() == lang }?.let { return it }          // 폴더 이름이 같다
        val wanted = LanguageResolver.resolve(lang) ?: return null                           // kr → 한국어
        return plan.folders.firstOrNull { it.name.isEmpty() && it.lang == wanted }           // 입력 폴더 자체가 그 언어
    }

    /**
     * 목적: 프리셋 이름으로 VAD 설정을 찾는다.
     * 입력: name - NOVAD 또는 고정 프리셋 이름(대문자)
     * 출력: 없음
     * 리턴: VadSetting? - 없거나 CUSTOM 이면 null
     */
    private fun findVad(name: String): VadSetting? {
        if (name == VadEngine.NONE.token) return VadSetting(VadEngine.NONE, VadPresetCatalog.NONE_PRESET, emptyMap())   // NOVAD
        for (e in VadEngine.values()) {
            if (e == VadEngine.NONE) continue                                                // NOVAD 는 위에서 처리
            VadPresetCatalog.presets(e).firstOrNull { !it.isCustom && it.name == name }?.let { return VadSetting(e, it, it.values) }
        }
        return null
    }
}
