/*
 * 파일명: stt/StreamingSttTranscriber.kt
 * 목적 및 기능:
 * - 스트리밍(Feed 간격 공급) 온디바이스 전사기의 공통 약속(인터페이스).
 * - 구현: SherpaStreamingTranscriber(sherpa-onnx OnlineRecognizer — Nemotron·Zipformer KO 스트리밍),
 *         VoskTranscriber(Vosk/Kaldi — vosk-model-small-ko / en-us).
 * - BatchRunner 는 모델 계열(SherpaFamily)에 따라 구현을 고르고, 불러오기·전사·해제는 이 약속대로만 부른다
 *   (같은 공급·계측 흐름을 두 런타임에 그대로 쓰기 위함).
 *
 * add-hyungchul-20261011-0301 — 2단계 2-4: Vosk 추가(v2.4.0)
 */
package com.example.google_stt.stt

import java.util.Locale

/**
 * 목적: 스트리밍 전사기 공통 약속.
 * - loadMs     : 모델을 불러오는 데 걸린 시간(ms). load 전에는 -1
 * - load       : 모델 폴더를 점검하고 모델을 불러온다(배치 시작 때 1회). 파일이 없으면 IOException
 * - transcribe : PCM 조각들을 100 ms 단위로 Feed 간격에 맞춰 넣고 결과를 잇는다 → SttOutcome
 * - close      : 모델을 해제한다(배치 끝, 여러 번 불러도 안전)
 */
interface StreamingSttTranscriber : AutoCloseable {
    /** 모델을 불러오는 데 걸린 시간(ms). load 전에는 -1 */
    val loadMs: Long

    /**
     * 목적: 모델 폴더를 점검하고 모델을 불러온다.
     * 입력: spec — 모델 정의, locale — 인식 언어(로그·언어 옵션용) / 출력: 없음 / 리턴: 없음
     */
    fun load(spec: SherpaModelSpec, locale: Locale)

    /**
     * 목적: 조각들을 Feed 간격으로 넣어 전사한다.
     * 입력: chunks — 16 kHz/mono/PCM16 LE 조각, locale — 인식 언어, feed — 공급 간격, onFed — 진행률(0.0~1.0)
     * 리턴: SttOutcome(text, first_result_ms, feed_ms, tail_ms, stt_wall_ms, segments)
     */
    fun transcribe(chunks: List<ByteArray>, locale: Locale, feed: FeedConfig, onFed: (Double) -> Unit): SttOutcome
}
