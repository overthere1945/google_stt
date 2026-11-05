/*
 * 파일명: domain/condition/RunOptions.kt
 * 목적 및 기능:
 * - 실행 옵션(구간 처리, 공급 속도, 노이즈 저감, Speech EPD)을 한 묶음으로 둔다(4단계 2-3).
 *   노이즈 저감·EPD·공급 속도는 "사용 안 함" 이 기본이다(D4).
 * - 런타임마다 뜻이 없는 옵션을 걸러 내는 effectiveFor 를 둔다(v3 MainActivity·BatchRunner 의 규칙을 한 곳에):
 *     · 공급 속도: 오프라인 = 없음, ML Kit = REALTIME 고정, 스트리밍·Vosk = 고른 값(FR-03-05)
 *     · EPD: ML Kit 이면서 "엔진별 최대 길이" 일 때만(sherpa·Vosk 와 10초 통일에서는 쓰지 않는다)
 * - 구간 처리 SegMode 는 domain/vad/SegmentPacker.kt 의 것을 그대로 쓴다.
 *
 * add-hyungchul-20261106-0042 — [T2-4]
 */
package com.example.google_stt.domain.condition

import com.example.google_stt.domain.catalog.Runtime   // 런타임별 옵션 거르기
import com.example.google_stt.domain.epd.EpdConfig     // EPD 파라미터
import com.example.google_stt.domain.epd.EpdMode       // EPD 방식(OFF / A안 / B안)
import com.example.google_stt.domain.vad.SegMode       // 구간 처리
import java.util.Locale                                // 숫자 형식 고정

/**
 * 목적: 오디오 공급 속도 선택.
 * 필드: delayMs - 100 ms 분량 조각을 넣는 간격(ms). null = 기다리지 않고 최대 속도로 넣는다
 */
enum class FeedChoice(val delayMs: Long?) {
    NONE(null),       // 사용 안 함(최대 속도) — 스트리밍·Vosk 기본
    REALTIME(100L),   // 실시간 ×1.0 — ML Kit 고정값
    D50(50L),         // 실시간 ×2.0
    D30(30L),         // 실시간 ×3.3
    D20(20L),         // 실시간 ×5.0
    D10(10L),         // 실시간 ×10.0
}

/**
 * 목적: 노이즈 저감 선택. 조건 이름에 들어갈 폴더 조각만 가진다(알고리즘 설정 자체는 engine/denoise 에 있다).
 * 필드: folder - v3 NoiseReduceConfig.outputFolder() 값(예 "ns_gtcrn_lim12"). null = 사용 안 함
 */
data class NsChoice(val folder: String?) {
    companion object {
        val OFF = NsChoice(null)   // 사용 안 함(기본)
    }
}

/**
 * 목적: Speech EPD 선택(ML Kit 61초 결함 회피 분할).
 * 필드: config - EPD 파라미터. null 이거나 mode 가 OFF 면 사용 안 함
 */
data class EpdChoice(val config: EpdConfig?) {

    /** EPD 를 쓰는지 */
    val enabled: Boolean get() = config != null && config.mode != EpdMode.OFF

    /**
     * 조건 이름에 들어갈 폴더 조각 — 설정이 다른 실행끼리 결과가 덮어써지지 않게 값을 넣는다(v3 EpdUi.customEpdFolder 와 같은 규칙).
     * 예 "epd_split_t45_m55" / "epd_gap_t45_m55_s1000". 사용 안 함이면 null
     */
    val folder: String?
        get() {
            val c = config?.takeIf { it.mode != EpdMode.OFF } ?: return null        // 사용 안 함
            val t = String.format(Locale.US, "%.0f", c.targetSec)                   // 45.0 → "45"
            val m = String.format(Locale.US, "%.0f", c.maxSec)                      // 55.0 → "55"
            val base = "epd_${c.mode.folderName}_t${t}_m$m"                         // 방식 + 목표 + 상한
            return if (c.mode == EpdMode.LONG_SILENCE) base + "_s${c.longSilenceMs}" else base   // 긴 무음은 B안에서만 결과를 바꾼다
        }

    companion object {
        val OFF = EpdChoice(null)   // 사용 안 함(기본)
    }
}

/**
 * 목적: 실행 옵션 묶음.
 * 필드: seg - 구간 처리, feed - 공급 속도, ns - 노이즈 저감, epd - Speech EPD
 */
data class RunOptions(
    val seg: SegMode = SegMode.ENGINE_MAX,   // 엔진별 최대 길이(기본)
    val feed: FeedChoice = FeedChoice.NONE,  // 공급 속도 사용 안 함(기본)
    val ns: NsChoice = NsChoice.OFF,         // 노이즈 저감 사용 안 함(기본)
    val epd: EpdChoice = EpdChoice.OFF,      // EPD 사용 안 함(기본)
) {
    /**
     * 목적: 주어진 런타임에서 실제로 적용되는 옵션을 만든다(뜻이 없는 옵션은 기본값으로 바꾼다).
     * 입력: runtime - 모델의 런타임
     * 출력: 없음(이 객체는 바뀌지 않는다)
     * 리턴: RunOptions - 적용되는 옵션. 같은 조합이 여러 번 만들어지지 않게 조합 생성기가 이 값으로 중복을 거른다
     */
    fun effectiveFor(runtime: Runtime): RunOptions = copy(
        feed = when (runtime) {
            Runtime.SHERPA_OFFLINE -> FeedChoice.NONE                     // 오프라인은 공급 속도가 없다
            Runtime.MLKIT -> FeedChoice.REALTIME                          // ML Kit 은 REALTIME 고정
            Runtime.SHERPA_STREAMING, Runtime.VOSK -> feed                // 고른 값 그대로
        },
        epd = if (runtime == Runtime.MLKIT && seg == SegMode.ENGINE_MAX) epd else EpdChoice.OFF,   // EPD 는 ML Kit·엔진별 최대 길이에서만
    )
}
