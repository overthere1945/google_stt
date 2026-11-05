/*
 * 파일명: domain/plan/Job.kt
 * 목적 및 기능:
 * - 작업(Job) 1개 = (언어 폴더, 모델, VAD 조건, 실행 옵션) = 결과 폴더 1개를 정의한다(4단계 2-3).
 * - 결과 위치 규칙(D2): <출력>/<언어 폴더>/<엔진>_<모델>_<조건>/… — 날짜 폴더 없음.
 *   입력 폴더 자체가 한 언어(언어 폴더 이름 "")이면 <출력>/<엔진>_<모델>_<조건>/… 로 바로 둔다(FR-01-03).
 * - key 는 이어 하기 때 작업을 알아보는 값이며 결과 폴더의 상대 경로와 같다.
 *
 * add-hyungchul-20261106-0044 — [T2-5]
 */
package com.example.google_stt.domain.plan

import com.example.google_stt.domain.catalog.Catalog        // VAD 엔진 → 자산 ID
import com.example.google_stt.domain.catalog.Language       // 평가 언어
import com.example.google_stt.domain.catalog.ModelSpec      // 모델 정의
import com.example.google_stt.domain.condition.ConditionName // 조건 이름
import com.example.google_stt.domain.condition.RunOptions   // 실행 옵션
import com.example.google_stt.domain.lang.LangFolder        // 언어 폴더
import com.example.google_stt.domain.vad.VadSetting         // VAD 설정

/**
 * 목적: 작업 1개.
 * 필드: lang - 언어 폴더(lang 이 정해져 있어야 한다), model - 모델, vad - VAD 설정, opt - 실행 옵션(런타임에 적용되는 값)
 */
data class Job(
    val lang: LangFolder,    // 언어 폴더
    val model: ModelSpec,    // 모델
    val vad: VadSetting,     // VAD 설정
    val opt: RunOptions,     // 실행 옵션
) {
    /** 음원 언어 — 언어가 정해지지 않은 폴더로는 작업을 만들지 않는다 */
    val language: Language get() = requireNotNull(lang.lang) { "언어가 정해지지 않은 폴더: '${lang.name}'" }

    /** 조건 이름: [NS_…_]<VAD>[_SEG10][_EPD_…][_FEED_…] */
    val condition: String get() = ConditionName.build(vad, opt, model.runtime)

    /** 결과 폴더 이름: <엔진>_<모델>_<조건> */
    val resultDirName: String get() = "${model.engine}_${model.model}_$condition"

    /** 작업 식별 키 = 출력 폴더 아래 결과 폴더의 상대 경로(이어 하기·진행 기록) */
    val key: String get() = if (lang.name.isEmpty()) resultDirName else "${lang.name}/$resultDirName"

    /** CSV 3종(result / device / run_meta) 파일 이름의 앞부분: <엔진>_<조건> — 모델 이름은 넣지 않는다(서버·v3 와 같은 위치, FR-07-02) */
    val csvBaseName: String get() = "${model.engine}_$condition"

    /** 이 작업에 필요한 자산 ID(모델 + VAD). ML Kit·NOVAD·TEN 처럼 받을 것이 없으면 빠진다 */
    val assetIds: List<String> get() = listOfNotNull(model.assetId, Catalog.vadAssetId(vad.engine))

    /** 로그용 짧은 이름: kr/whisper-base/SILERO_SAFE */
    val label: String get() = listOf(lang.name, model.engine, condition).filter { it.isNotEmpty() }.joinToString("/")
}
