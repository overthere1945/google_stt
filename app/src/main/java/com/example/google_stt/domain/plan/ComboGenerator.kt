/*
 * 파일명: domain/plan/ComboGenerator.kt
 * 목적 및 기능:
 * - 언어 폴더 × 모델 × VAD 조건 × 실행 옵션의 모든 조합을 작업 목록으로 만든다(FR-04-01). v3 의 목록 CSV 를 대신한다.
 * - 규칙:
 *     · 언어 전용 모델은 맞지 않는 언어 폴더에서 자동으로 빼고 제외 내역에 남긴다(FR-02-03)
 *     · 런타임에 뜻이 없는 옵션은 걸러 낸 뒤(RunOptions.effectiveFor) 같은 결과 폴더가 되는 조합은 하나만 남긴다
 *       (예: 오프라인 모델에 공급 속도 2개를 골라도 작업은 1개, ML Kit 은 항상 REALTIME)
 *     · 순서(FR-04-03): 언어 폴더 순 → 오프라인 → 스트리밍·Vosk → ML Kit → 같은 모델끼리 이어서(모델 적재 횟수 최소) → VAD 순 → 옵션 순
 *
 * add-hyungchul-20261106-0044 — [T2-5]
 */
package com.example.google_stt.domain.plan

import com.example.google_stt.domain.catalog.LangSupport   // 언어 전용 판정
import com.example.google_stt.domain.catalog.ModelSpec     // 모델 정의
import com.example.google_stt.domain.catalog.Runtime       // 실행 순서 묶음
import com.example.google_stt.domain.condition.RunOptions  // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder       // 언어 폴더
import com.example.google_stt.domain.lang.LangPlan         // 언어 계획
import com.example.google_stt.domain.vad.VadSetting        // VAD 설정

/**
 * 목적: 제외된 조합 묶음(화면 "제외 내역" 용).
 * 필드: lang - 언어 폴더, model - 모델, count - 빠진 조합 수, reason - 사유
 */
data class Excluded(
    val lang: LangFolder,    // 언어 폴더
    val model: ModelSpec,    // 모델
    val count: Int,          // 빠진 조합 수
    val reason: String,      // 사유
)

/**
 * 목적: 조합 생성 결과.
 * 필드: jobs - 실행 순서대로의 작업, excluded - 자동 제외 내역
 */
data class ComboResult(
    val jobs: List<Job>,             // 작업 목록
    val excluded: List<Excluded>,    // 제외 내역
)

/** 목적: 조합 생성기. */
object ComboGenerator {

    /**
     * 목적: 조합을 만든다.
     * 입력: plan - 언어 계획(folders 만 쓴다. 모르는 폴더·건너뛰는 폴더는 작업을 만들지 않는다),
     *       models - 고른 모델, vads - 고른 VAD 설정(NOVAD 포함), opts - 고른 실행 옵션 조합
     * 출력: 없음
     * 리턴: ComboResult - 중복 없는 작업 목록(순서 규칙 적용)과 제외 내역
     */
    fun generate(plan: LangPlan, models: List<ModelSpec>, vads: List<VadSetting>, opts: List<RunOptions>): ComboResult {
        val jobs = LinkedHashMap<String, Job>()                                 // key → 작업(처음 나온 것만 남긴다)
        val excluded = ArrayList<Excluded>()                                    // 제외 내역
        val ordered = models.withIndex().sortedWith(compareBy({ order(it.value.runtime) }, { it.index })).map { it.value }   // 런타임 묶음 → 고른 순서
        for (folder in plan.folders) {
            val lang = folder.lang ?: continue                                  // 언어가 없는 폴더는 건너뛴다(계획이 넣지 않지만 방어)
            for (m in ordered) {
                if (!m.langSupport.supports(lang)) {                            // 언어 전용 모델이 다른 언어 폴더를 만남
                    val distinct = opts.map { it.effectiveFor(m.runtime) }.distinct().size   // 빠지는 옵션 가짓수
                    excluded += Excluded(folder, m, vads.distinct().size * distinct, reason(m.langSupport))
                    continue
                }
                for (vad in vads) {
                    for (opt in opts) {
                        val job = Job(folder, m, vad, opt.effectiveFor(m.runtime))   // 적용되는 옵션만 담는다
                        jobs.putIfAbsent(job.key, job)                               // 같은 결과 폴더는 한 번만
                    }
                }
            }
        }
        return ComboResult(jobs.values.toList(), excluded)
    }

    /**
     * 목적: 런타임의 실행 순서 묶음 번호를 준다.
     * 입력: runtime - 런타임 / 출력: 없음 / 리턴: Int - 0 오프라인, 1 스트리밍·Vosk, 2 ML Kit
     */
    private fun order(runtime: Runtime): Int = when (runtime) {
        Runtime.SHERPA_OFFLINE -> 0                        // 오프라인 먼저
        Runtime.SHERPA_STREAMING, Runtime.VOSK -> 1        // 스트리밍·Vosk
        Runtime.MLKIT -> 2                                 // ML Kit 마지막
    }

    /**
     * 목적: 언어 전용 제외 사유 문구를 만든다.
     * 입력: support - 모델의 언어 범위 / 출력: 없음 / 리턴: String - 사유
     */
    private fun reason(support: LangSupport): String = when (support) {
        LangSupport.KO_ONLY -> "한국어 전용 모델"
        LangSupport.EN_ONLY -> "영어 전용 모델"
        LangSupport.MULTI -> ""                            // 다국어는 제외되지 않는다
    }
}
