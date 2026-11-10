/*
 * 파일명: domain/condition/ConditionName.kt
 * 목적 및 기능:
 * - 결과 폴더·CSV 이름에 들어가는 "조건 이름" 을 만든다. 서버·v3 와 같은 규칙에 Feed 토큰만 더했다(D26).
 *     <조건> = [NS_<ns>_]<VAD 토큰>[_SEG10][_EPD_<epd>][_FEED_<feed>]
 *     예) NOVAD, SILERO_SAFE, NS_GTCRN_SILERO, PYANNOTE_S500_SEG10, SILERO_EPD_SPLIT_T45_M55, SILERO_FEED_D50
 * - Feed 토큰은 스트리밍·Vosk 에서 공급 속도를 고른 경우에만 붙는다. 오프라인(해당 없음)·ML Kit(REALTIME 고정)·
 *   "사용 안 함" 에는 붙지 않는다 → 공급 속도만 다른 결과가 같은 폴더를 덮어쓰지 않는다.
 *
 * add-hyungchul-20261106-0042 — [T2-4]
 */
package com.example.google_stt.domain.condition

import com.example.google_stt.domain.catalog.Runtime   // 런타임별 규칙
import com.example.google_stt.domain.vad.VadSetting    // VAD 설정(조건 토큰)
import java.util.Locale                                // 대문자 변환 규칙 고정

/** 목적: 조건 이름 만들기. */
object ConditionName {

    /**
     * 목적: 조건 이름을 만든다.
     * 입력: vad - VAD 설정(NOVAD 포함), opt - 고른 실행 옵션, runtime - 모델의 런타임
     * 출력: 없음
     * 리턴: String - 조건 이름(런타임에 적용되지 않는 옵션은 이름에 들어가지 않는다)
     */
    fun build(vad: VadSetting, opt: RunOptions, runtime: Runtime): String {
        val eff = opt.effectiveFor(runtime)                                     // 이 런타임에서 실제로 적용되는 옵션
        val parts = ArrayList<String>()                                         // 이름 조각(순서대로)
        eff.ns.folder?.let { parts += it.uppercase(Locale.US) }                 // ns_gtcrn_lim12 → NS_GTCRN_LIM12
        parts += vad.conditionToken()                                           // NOVAD / SILERO_SAFE / SILERO_CUSTOM_t40_…
        if (eff.seg.conditionSuffix.isNotEmpty()) parts += eff.seg.conditionSuffix   // SEG10
        eff.epd.folder?.let { parts += it.uppercase(Locale.US) }                // epd_split_t45_m55 → EPD_SPLIT_T45_M55
        val feedSelectable = runtime == Runtime.SHERPA_STREAMING || runtime == Runtime.VOSK   // 공급 속도를 고를 수 있는 런타임
        if (feedSelectable && eff.feed != FeedChoice.NONE) parts += "FEED_${eff.feed.name}"   // FEED_REALTIME / FEED_D50 …
        return parts.joinToString("_")
    }
}
