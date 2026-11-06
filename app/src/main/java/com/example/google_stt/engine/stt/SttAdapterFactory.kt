/*
 * 파일명: engine/stt/SttAdapterFactory.kt
 * 목적 및 기능:
 * - 모델의 런타임에 맞는 STT 어댑터를 만든다(4단계 3-1). 실행 파이프라인은 어댑터의 구현 종류를 알 필요가 없다.
 * - Context 는 받지 않는다 — 모델 위치는 load 의 인자로 주고, ML Kit 클라이언트도 Context 없이 만들어진다.
 *
 * add-hyungchul-20261106-2132 — [T5-1]
 */
package com.example.google_stt.engine.stt

import com.example.google_stt.domain.catalog.ModelSpec   // 모델 정의
import com.example.google_stt.domain.catalog.Runtime     // 런타임 종류

/** 목적: STT 어댑터 만들기. */
object SttAdapterFactory {

    /**
     * 목적: 모델에 맞는 어댑터를 만든다(아직 적재하지 않는다 — load 를 불러야 한다).
     * 입력: spec - 모델 정의, log - 경고 한 줄을 받는 함수(ML Kit 의 세션 한도 경고 등)
     * 출력: 없음
     * 리턴: SttEngineAdapter
     */
    fun create(spec: ModelSpec, log: (String) -> Unit = {}): SttEngineAdapter = when (spec.runtime) {
        Runtime.SHERPA_OFFLINE -> SherpaOfflineAdapter(spec)
        Runtime.SHERPA_STREAMING -> SherpaStreamingAdapter(spec)
        Runtime.VOSK -> VoskAdapter(spec)
        Runtime.MLKIT -> TODO("T5-5 MlKitAdapter")
    }
}
