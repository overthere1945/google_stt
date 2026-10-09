/*
 * 파일명: vad/VadModels.kt
 * 목적 및 기능:
 * - 앱에서 고를 수 있는 VAD 엔진 목록(VadEngine), 엔진별 설정 항목 정의(VadParamSpec),
 *   엔진별 프리셋(공식 기본값 + 추천값 + Custom) 목록(VadPresetCatalog)을 한곳에 모은다.
 * - 화면(VadUi)은 이 정의만 보고 스피너와 입력칸을 만들고, 배치(BatchRunner)는 이 값으로 VAD 를 돌린다.
 *
 * 프리셋 이름 규칙(PC "STT 통합 실행기" 조건 이름과 동일하게 맞춘다):
 *   SILERO / SILERO_SAFE / SILERO_FW / SILERO_DEFAULT / SILERO_SHERPA / SILERO_QWEN3 / SILERO_WS / SILERO_USER
 *   PYANNOTE_DEFAULT / PYANNOTE_S500 / PYANNOTE_S2000
 *   FSMN_DEFAULT / FSMN_S500 (+ FSMN_CONFIG)
 *   FIRERED_DEFAULT / FIRERED_S500 / FIRERED_S2000
 *   (앱 신규) WEBRTC_DEFAULT / WEBRTC_LIB / WEBRTC_ASR, TEN_DEFAULT / TEN_SHERPA / TEN_ASR
 *   <엔진>_CUSTOM_<값> — 사용자가 고친 값(값을 이름에 넣어 실행끼리 덮어쓰지 않게 한다)
 *
 * 값의 출처(2026-10-09 공식 소스 확인):
 *   Silero  : silero-vad utils_vad.py get_speech_timestamps() / faster-whisper vad.py VadOptions /
 *             sherpa-onnx silero-vad-model-config.h / Qwen3-ASR-Toolkit audio_tools.py /
 *             whisper_streaming silero_vad_iterator.py — SILERO·SILERO_SAFE·SILERO_USER 는 프로젝트 설정값
 *   pyannote: pyannote-audio pipelines/voice_activity_detection.py (min_duration_on/off 0.0), S500/S2000 은 PC GUI v8.1
 *   FSMN    : FunASR funasr_vad.h(동적 무음 기준) / 모델 config.yaml(800 ms·0.6·60000 ms), S500 은 PC GUI v8.1
 *   FireRed : FireRedVAD fireredvad/vad.py FireRedVadConfig, S500/S2000 은 PC GUI v8.1
 *   WebRTC  : WebRTC VAD 코어 기본 mode 0, android-vad README 권장값(20 ms·VERY_AGGRESSIVE·300/50)
 *   TEN     : ten-vad README(hop 256, threshold 0.5), sherpa-onnx ten-vad-model-config.h(0.5/0.5 s/0.25 s/20 s)
 *
 * add-hyungchul-20261009-1530
 */
package com.example.google_stt.vad

import java.util.Locale
import kotlin.math.roundToInt

/**
 * 목적: 앱에서 고를 수 있는 VAD 엔진.
 * 필드: displayName — 스피너 표시 이름
 *       token       — 조건 이름 앞부분(대문자)
 *       license     — 라이선스 요약(화면 안내 / run_meta 용)
 *       commercial  — 상업 사용 판정(allowed / conditional) — 2026-10-09 조사 기준
 */
enum class VadEngine(
    val displayName: String,                  // 스피너에 보여줄 이름
    val token: String,                        // 조건 이름 앞부분
    val license: String,                      // 라이선스 요약
    val commercial: String,                   // 상업 사용 판정
) {
    NONE("사용 안 함 (기존 baseline)", "NOVAD", "", ""),
    SILERO("Silero VAD v6 (ONNX)", "SILERO", "MIT", "allowed"),
    WEBRTC("Google WebRTC VAD (GMM)", "WEBRTC", "BSD-3 (android-vad: MIT)", "allowed"),
    PYANNOTE("pyannote segmentation-3.0 (ONNX)", "PYANNOTE", "MIT (모델 HF 동의 필요)", "allowed"),
    FSMN("FunASR FSMN-VAD (ONNX)", "FSMN", "Apache-2.0 + 출처·모델명 표기", "allowed"),
    FIRERED("FireRedVAD (ONNX)", "FIRERED", "Apache-2.0", "allowed"),
    TEN("TEN VAD (네이티브)", "TEN", "Apache-2.0 + 추가 조건(Agora 경쟁 금지 등)", "conditional"),
}

/**
 * 목적: VAD 설정 항목 1개의 정의(화면 입력칸 1개).
 * 필드: key   — 내부 키(값 맵의 키)
 *       label — 화면 이름
 *       abbr  — Custom 조건 이름에 넣을 약어
 *       min/max — 허용 범위(포함)
 *       isInt — 정수 입력칸인지
 *       scale100 — 조건 이름에 넣을 때 ×100 정수로 바꿀지(0.40 → 40)
 */
data class VadParamSpec(
    val key: String,                          // 값 맵의 키
    val label: String,                        // 화면 표시 이름
    val abbr: String,                         // Custom 이름 약어
    val min: Double,                          // 최소값(포함)
    val max: Double,                          // 최대값(포함)
    val isInt: Boolean,                       // 정수 입력 여부
    val scale100: Boolean = false,            // 이름에 ×100 정수로 넣을지
) {
    /**
     * 목적: 값 하나를 화면에 보여 줄 문자열로 만든다.
     * 입력: v — 값
     * 리턴: 정수 항목이면 "250", 실수 항목이면 "0.50" 형태
     */
    fun format(v: Double): String =
        if (isInt) v.roundToInt().toString()                              // 정수 항목
        else String.format(Locale.US, "%.2f", v)                           // 실수 항목(소수 둘째 자리)

    /**
     * 목적: Custom 조건 이름에 넣을 짧은 문자열을 만든다.
     * 입력: v — 값
     * 리턴: "t40", "sp250", "moff0p5" 형태(소수점은 p 로 바꾼다)
     */
    fun nameToken(v: Double): String {
        val body = when {
            scale100 -> (v * 100).roundToInt().toString()                  // 0.40 → 40
            isInt -> v.roundToInt().toString()                             // 정수
            v == Math.floor(v) -> v.roundToInt().toString()                // 2.0 → 2
            else -> String.format(Locale.US, "%.3f", v)                    // 0.150 → 0.150
                .trimEnd('0').trimEnd('.').replace('.', 'p')              //       → 0p15
        }
        return abbr + body
    }
}

/**
 * 목적: VAD 프리셋 1개(이름 = 결과 조건 이름).
 * 필드: name        — 조건 이름(예: SILERO_SAFE). Custom 은 "<엔진>_CUSTOM"(실행 시 값이 붙는다)
 *       engine      — 어느 VAD 엔진의 프리셋인지
 *       description — 화면 안내(출처·성격)
 *       values      — 설정값(키 → 값)
 *       isCustom    — 사용자가 값을 고칠 수 있는 항목인지
 */
data class VadPreset(
    val name: String,                         // 조건 이름
    val engine: VadEngine,                    // 소속 엔진
    val description: String,                  // 출처·성격
    val values: Map<String, Double>,          // 설정값
    val isCustom: Boolean = false,            // Custom 여부
) {
    /** 스피너 표시 문자열: "SILERO_SAFE — 프로젝트 ASR Safe" */
    val displayName: String get() = "$name — $description"
}

/**
 * 목적: START 를 누른 순간의 VAD 설정 snapshot(불변).
 * 필드: engine — 엔진, preset — 고른 프리셋, values — 실제로 쓸 값(Custom 이면 화면 입력값)
 */
data class VadSetting(
    val engine: VadEngine,                    // 엔진
    val preset: VadPreset,                    // 고른 프리셋
    val values: Map<String, Double>,          // 실제 값
) {
    /** VAD 를 쓰는지 */
    val enabled: Boolean get() = engine != VadEngine.NONE

    /**
     * 목적: 키에 해당하는 값을 꺼낸다(없으면 예외 — 정의 누락을 실행 전에 드러낸다).
     * 입력: key — 설정 키
     * 리턴: 값
     */
    operator fun get(key: String): Double =
        values[key] ?: throw IllegalStateException("VAD 설정 '$key' 값이 없습니다(${engine.name}).")

    /** 구간 사이 무음(ms) — 모든 엔진 공통 항목 */
    val joinMs: Int get() = values[VadKeys.JOIN_MS]?.roundToInt() ?: 0

    /**
     * 목적: 결과 폴더·CSV 에 쓸 조건 이름 조각을 만든다.
     * 리턴: NOVAD / SILERO_SAFE / SILERO_CUSTOM_t40_sp250_si300_pad100 …
     */
    fun conditionToken(): String {
        if (!enabled) return VadEngine.NONE.token                         // VAD 를 쓰지 않음
        if (!preset.isCustom) return preset.name                          // 고정 프리셋은 이름 그대로
        val defaults = VadPresetCatalog.customStart(engine).values        // Custom 시작값(=생략 기준)
        val parts = VadParamCatalog.specs(engine).mapNotNull { spec ->     // 항목 순서대로 값 조각을 만든다
            val v = values[spec.key] ?: return@mapNotNull null
            val optional = spec.key == VadKeys.JOIN_MS || spec.key == VadKeys.MAX_SPEECH_SEC
            // change-hyungchul-20261009-1626 : FSMN 동적 기준(max_end_silence_time = 0)에서는 speech_noise_thres 를 쓰지 않으므로(0.5 고정)
            //   이름에서도 뺀다 — 결과가 같은데 폴더만 갈리는 것을 막는다(검토 지적 F)
            val unused = engine == VadEngine.FSMN && spec.key == VadKeys.SPEECH_NOISE_THRES &&
                (values[VadKeys.MAX_END_SILENCE_MS] ?: 0.0) <= 0.0
            // 사이 무음·최대 길이는 시작값과 같으면 이름에서 뺀다(Q14 예시와 같은 짧은 이름)
            if (unused || (optional && defaults[spec.key] == v)) null else spec.nameToken(v)
        }
        return "${engine.token}_CUSTOM_" + parts.joinToString("_")
    }

    /** 로그/run_meta/CSV 에 남길 한 줄 요약: "threshold=0.5, min_speech_ms=250, …" */
    fun summary(): String =
        if (!enabled) "OFF"
        else VadParamCatalog.specs(engine).joinToString(", ") { s ->
            "${s.key}=${values[s.key]?.let { s.format(it) } ?: "?"}"
        }
}

/** 설정 키 이름 모음(오타로 인한 실행 오류를 막기 위해 상수로 쓴다) */
object VadKeys {
    const val THRESHOLD = "threshold"                 // 말 판정 임계값
    const val MIN_SPEECH_MS = "min_speech_ms"         // 최소 말 길이(ms)
    const val MIN_SILENCE_MS = "min_silence_ms"       // 최소 무음 길이(ms)
    const val PAD_MS = "pad_ms"                       // 앞뒤 여유(ms)
    const val MAX_SPEECH_SEC = "max_speech_sec"       // 최대 말 길이(초, 0 = 무제한)
    const val JOIN_MS = "join_ms"                     // 구간 사이 무음(ms)
    const val WEBRTC_MODE = "mode"                    // WebRTC 공격성 0~3
    const val FRAME_MS = "frame_ms"                   // WebRTC 프레임 10/20/30 ms
    const val HOP = "hop"                             // TEN hop(샘플)
    const val ONSET = "onset"                         // pyannote 시작 임계값
    const val OFFSET = "offset"                       // pyannote 끝 임계값
    const val MIN_ON_SEC = "min_duration_on"          // pyannote 최소 말 길이(초)
    const val MIN_OFF_SEC = "min_duration_off"        // pyannote 최소 무음 길이(초)
    const val PAD_ONSET_SEC = "pad_onset"             // pyannote 앞 여유(초)
    const val PAD_OFFSET_SEC = "pad_offset"           // pyannote 뒤 여유(초)
    const val MAX_END_SILENCE_MS = "max_end_silence_time"   // FSMN 끝 무음(ms, 0 = 동적 기준)
    const val SPEECH_NOISE_THRES = "speech_noise_thres"     // FSMN 말/잡음 차이 기준
    const val MAX_SINGLE_SEGMENT_MS = "max_single_segment_time" // FSMN 최대 구간(ms)
    const val SMOOTH_WINDOW = "smooth_window_size"    // FireRed 확률 평활 창(프레임)
    const val MIN_SPEECH_FRAME = "min_speech_frame"   // FireRed 최소 말(프레임)
    const val MAX_SPEECH_FRAME = "max_speech_frame"   // FireRed 최대 말(프레임)
    const val MIN_SILENCE_FRAME = "min_silence_frame" // FireRed 최소 무음(프레임)
    const val MERGE_SILENCE_FRAME = "merge_silence_frame"   // FireRed 짧은 무음 합치기(프레임)
    const val EXTEND_SPEECH_FRAME = "extend_speech_frame"   // FireRed 앞뒤 늘리기(프레임)
}

/** 엔진별 설정 항목 정의(화면 입력칸 순서 = 이 목록 순서) */
object VadParamCatalog {
    // 공통 항목 — 모든 엔진이 구간을 이어 붙일 때 쓴다(PC GUI 의 --vad-join-silence-ms)
    private val JOIN = VadParamSpec(VadKeys.JOIN_MS, "구간 사이 무음 (ms, 0 = 삽입 안 함)", "join", 0.0, 5000.0, true)

    // Silero 방식(get_speech_timestamps 상태 기계) 공통 항목 — Silero / WebRTC / TEN 이 같이 쓴다
    private val THR = VadParamSpec(VadKeys.THRESHOLD, "Threshold (0.01 ~ 0.99)", "t", 0.01, 0.99, false, scale100 = true)
    private val MIN_SP = VadParamSpec(VadKeys.MIN_SPEECH_MS, "Min Speech (ms)", "sp", 0.0, 60000.0, true)
    private val MIN_SI = VadParamSpec(VadKeys.MIN_SILENCE_MS, "Min Silence (ms)", "si", 0.0, 60000.0, true)
    private val PAD = VadParamSpec(VadKeys.PAD_MS, "Speech Padding (ms)", "pad", 0.0, 5000.0, true)
    private val MAX_SP = VadParamSpec(VadKeys.MAX_SPEECH_SEC, "Max Speech (sec, 0 = Unlimited)", "max", 0.0, 3600.0, false)

    /**
     * 목적: 엔진의 설정 항목 목록을 돌려준다.
     * 입력: engine — VAD 엔진
     * 리턴: 항목 목록(NONE 이면 빈 목록)
     */
    fun specs(engine: VadEngine): List<VadParamSpec> = when (engine) {
        VadEngine.NONE -> emptyList()
        VadEngine.SILERO -> listOf(THR, MIN_SP, MIN_SI, PAD, MAX_SP, JOIN)
        VadEngine.WEBRTC -> listOf(
            VadParamSpec(VadKeys.WEBRTC_MODE, "Mode (0 NORMAL ~ 3 VERY_AGGRESSIVE)", "m", 0.0, 3.0, true),
            VadParamSpec(VadKeys.FRAME_MS, "Frame (ms: 10 / 20 / 30)", "f", 10.0, 30.0, true),
            MIN_SP, MIN_SI, PAD, MAX_SP, JOIN,
        )
        VadEngine.TEN -> listOf(
            THR,
            VadParamSpec(VadKeys.HOP, "Hop (samples: 160 / 256)", "h", 160.0, 256.0, true),
            MIN_SP, MIN_SI, PAD, MAX_SP, JOIN,
        )
        VadEngine.PYANNOTE -> listOf(
            VadParamSpec(VadKeys.ONSET, "Onset (0.01 ~ 0.99)", "on", 0.01, 0.99, false, scale100 = true),
            VadParamSpec(VadKeys.OFFSET, "Offset (0.01 ~ 0.99)", "off", 0.01, 0.99, false, scale100 = true),
            VadParamSpec(VadKeys.MIN_ON_SEC, "min_duration_on (sec)", "mon", 0.0, 60.0, false),
            VadParamSpec(VadKeys.MIN_OFF_SEC, "min_duration_off (sec)", "moff", 0.0, 60.0, false),
            VadParamSpec(VadKeys.PAD_ONSET_SEC, "pad_onset (sec, 앞 여유)", "pon", 0.0, 5.0, false),
            VadParamSpec(VadKeys.PAD_OFFSET_SEC, "pad_offset (sec, 뒤 여유)", "poff", 0.0, 5.0, false),
            JOIN,
        )
        VadEngine.FSMN -> listOf(
            VadParamSpec(VadKeys.MAX_END_SILENCE_MS, "max_end_silence_time (ms, 0 = 동적 기준)", "mes", 0.0, 10000.0, true),
            VadParamSpec(VadKeys.SPEECH_NOISE_THRES, "speech_noise_thres (동적 기준이면 0.5 고정)", "snt", -1.0, 1.0, false, scale100 = true),
            VadParamSpec(VadKeys.MAX_SINGLE_SEGMENT_MS, "max_single_segment_time (ms)", "mss", 1000.0, 600000.0, true),
            JOIN,
        )
        VadEngine.FIRERED -> listOf(
            VadParamSpec(VadKeys.THRESHOLD, "speech_threshold (0 ~ 1)", "t", 0.0, 1.0, false, scale100 = true),
            VadParamSpec(VadKeys.SMOOTH_WINDOW, "smooth_window_size (frame)", "sw", 1.0, 100.0, true),
            VadParamSpec(VadKeys.MIN_SPEECH_FRAME, "min_speech_frame (10 ms 단위)", "msp", 1.0, 10000.0, true),
            VadParamSpec(VadKeys.MAX_SPEECH_FRAME, "max_speech_frame (10 ms 단위)", "mxs", 10.0, 60000.0, true),
            VadParamSpec(VadKeys.MIN_SILENCE_FRAME, "min_silence_frame (10 ms 단위)", "msi", 0.0, 10000.0, true),
            VadParamSpec(VadKeys.MERGE_SILENCE_FRAME, "merge_silence_frame (10 ms 단위)", "mg", 0.0, 10000.0, true),
            VadParamSpec(VadKeys.EXTEND_SPEECH_FRAME, "extend_speech_frame (10 ms 단위)", "ext", 0.0, 1000.0, true),
            JOIN,
        )
    }

    /**
     * 목적: 값 맵을 정의 범위로 검증한다(잘못된 값으로 배치가 돌면 결과 전체를 버려야 하므로 실행 전에 막는다).
     * 입력: engine, values
     * 리턴: 없음
     * 예외: 범위를 벗어나거나 값이 없으면 IllegalArgumentException
     */
    fun validate(engine: VadEngine, values: Map<String, Double>) {
        for (s in specs(engine)) {
            val v = values[s.key] ?: throw IllegalArgumentException("${s.label} 값이 없습니다.")
            require(!v.isNaN() && v >= s.min && v <= s.max) {           // 범위 검사
                "${s.label} 은(는) ${s.format(s.min)} ~ ${s.format(s.max)} 범위여야 합니다(입력 ${s.format(v)})."
            }
        }
        // 엔진별 추가 규칙
        when (engine) {
            VadEngine.WEBRTC -> {
                val f = values.getValue(VadKeys.FRAME_MS).roundToInt()
                require(f == 10 || f == 20 || f == 30) { "WebRTC Frame 은 10 / 20 / 30 ms 만 됩니다." }
            }
            VadEngine.TEN -> {
                val h = values.getValue(VadKeys.HOP).roundToInt()
                require(h == 160 || h == 256) { "TEN VAD Hop 은 160 / 256 만 권장됩니다(공식 README)." }
            }
            else -> Unit
        }
    }
}

/** 엔진별 프리셋 목록 */
object VadPresetCatalog {
    private const val UNLIMITED = 0.0                 // Max Speech 0 = 무제한(PC GUI 와 같은 표기)

    /**
     * 목적: Silero 방식 값 맵을 만든다(Silero / WebRTC / TEN 공통 키).
     * 입력: thr, sp, si, pad, max(0=무제한), join
     * 리턴: 값 맵
     */
    private fun s(thr: Double, sp: Int, si: Int, pad: Int, max: Double = UNLIMITED, join: Int = 200) = mapOf(
        VadKeys.THRESHOLD to thr,
        VadKeys.MIN_SPEECH_MS to sp.toDouble(),
        VadKeys.MIN_SILENCE_MS to si.toDouble(),
        VadKeys.PAD_MS to pad.toDouble(),
        VadKeys.MAX_SPEECH_SEC to max,
        VadKeys.JOIN_MS to join.toDouble(),
    )

    /** WebRTC 값 맵(threshold 는 이진 판정이라 0.5 로 고정해 둔다) */
    private fun w(mode: Int, frame: Int, sp: Int, si: Int, pad: Int, max: Double = UNLIMITED, join: Int = 200) =
        s(0.5, sp, si, pad, max, join) - VadKeys.THRESHOLD +
            mapOf(VadKeys.WEBRTC_MODE to mode.toDouble(), VadKeys.FRAME_MS to frame.toDouble())

    /** TEN 값 맵 */
    private fun t(thr: Double, hop: Int, sp: Int, si: Int, pad: Int, max: Double = UNLIMITED, join: Int = 200) =
        s(thr, sp, si, pad, max, join) + mapOf(VadKeys.HOP to hop.toDouble())

    /** pyannote 값 맵 */
    private fun p(on: Double, off: Double, minOn: Double, minOff: Double, padOn: Double, padOff: Double, join: Int = 200) = mapOf(
        VadKeys.ONSET to on, VadKeys.OFFSET to off,
        VadKeys.MIN_ON_SEC to minOn, VadKeys.MIN_OFF_SEC to minOff,
        VadKeys.PAD_ONSET_SEC to padOn, VadKeys.PAD_OFFSET_SEC to padOff,
        VadKeys.JOIN_MS to join.toDouble(),
    )

    /** FSMN 값 맵(maxEndSilence 0 = FunASR 동적 무음 기준) */
    private fun f(maxEndSil: Int, thres: Double, maxSeg: Int, join: Int = 200) = mapOf(
        VadKeys.MAX_END_SILENCE_MS to maxEndSil.toDouble(),
        VadKeys.SPEECH_NOISE_THRES to thres,
        VadKeys.MAX_SINGLE_SEGMENT_MS to maxSeg.toDouble(),
        VadKeys.JOIN_MS to join.toDouble(),
    )

    /** FireRed 값 맵 */
    private fun r(thr: Double, sw: Int, msp: Int, mxs: Int, msi: Int, mg: Int, ext: Int, join: Int = 200) = mapOf(
        VadKeys.THRESHOLD to thr,
        VadKeys.SMOOTH_WINDOW to sw.toDouble(),
        VadKeys.MIN_SPEECH_FRAME to msp.toDouble(),
        VadKeys.MAX_SPEECH_FRAME to mxs.toDouble(),
        VadKeys.MIN_SILENCE_FRAME to msi.toDouble(),
        VadKeys.MERGE_SILENCE_FRAME to mg.toDouble(),
        VadKeys.EXTEND_SPEECH_FRAME to ext.toDouble(),
        VadKeys.JOIN_MS to join.toDouble(),
    )

    // ── Silero 8종 (첨부 표와 같은 값, maxSpeech·join 은 PC GUI v6.2 규칙: 무제한·200 ms, SILERO_FW 만 0 ms) ──
    private val SILERO_PRESETS = listOf(
        VadPreset("SILERO", VadEngine.SILERO, "프로젝트 기준선(Balanced) 0.5/250/400/150", s(0.5, 250, 400, 150)),
        VadPreset("SILERO_SAFE", VadEngine.SILERO, "프로젝트 ASR Safe 0.3/150/600/300", s(0.3, 150, 600, 300)),
        VadPreset("SILERO_FW", VadEngine.SILERO, "faster-whisper VadOptions 0.5/0/2000/400, join 0", s(0.5, 0, 2000, 400, join = 0)),
        VadPreset("SILERO_DEFAULT", VadEngine.SILERO, "silero-vad 공식 기본값 0.5/250/100/30", s(0.5, 250, 100, 30)),
        VadPreset("SILERO_SHERPA", VadEngine.SILERO, "sherpa-onnx SileroVadModelConfig 0.5/250/500/0", s(0.5, 250, 500, 0)),
        VadPreset("SILERO_QWEN3", VadEngine.SILERO, "Qwen3-ASR-Toolkit 0.5/1500/500/30", s(0.5, 1500, 500, 30)),
        VadPreset("SILERO_WS", VadEngine.SILERO, "whisper_streaming VADIterator 0.5/0/500/100", s(0.5, 0, 500, 100)),
        VadPreset("SILERO_USER", VadEngine.SILERO, "사용자 정의 0.4/250/300/100", s(0.4, 250, 300, 100)),
        // Custom 시작값은 SILERO(기준선)와 같다(Q8 결정)
        VadPreset("SILERO_CUSTOM", VadEngine.SILERO, "Custom (값 직접 입력)", s(0.5, 250, 400, 150), isCustom = true),
    )

    // ── WebRTC: 코어 기본(mode 0, 30 ms, 후처리 없음) / android-vad README 권장 / 프로젝트 제안 ──
    private val WEBRTC_PRESETS = listOf(
        VadPreset("WEBRTC_DEFAULT", VadEngine.WEBRTC, "WebRTC 코어 기본 mode 0 · 30 ms · 후처리 없음", w(0, 30, 0, 0, 0)),
        VadPreset("WEBRTC_LIB", VadEngine.WEBRTC, "android-vad 권장 mode 3 · 20 ms · speech 50 / silence 300", w(3, 20, 50, 300, 0)),
        VadPreset("WEBRTC_ASR", VadEngine.WEBRTC, "프로젝트 제안 mode 2 · 30 ms · 250/400/150 (공식 근거 없음)", w(2, 30, 250, 400, 150)),
        VadPreset("WEBRTC_CUSTOM", VadEngine.WEBRTC, "Custom (값 직접 입력)", w(0, 30, 0, 0, 0), isCustom = true),
    )

    // ── TEN: README 기본 / sherpa-onnx 기본 / 프로젝트 제안 ──
    private val TEN_PRESETS = listOf(
        VadPreset("TEN_DEFAULT", VadEngine.TEN, "ten-vad 공식 hop 256 · threshold 0.5 · 후처리 없음", t(0.5, 256, 0, 0, 0)),
        VadPreset("TEN_SHERPA", VadEngine.TEN, "sherpa-onnx TenVadModelConfig 0.5 · 250 / 500 · max 20 s", t(0.5, 256, 250, 500, 0, max = 20.0)),
        VadPreset("TEN_ASR", VadEngine.TEN, "프로젝트 제안 0.5 · 250/400/150 (공식 근거 없음)", t(0.5, 256, 250, 400, 150)),
        VadPreset("TEN_CUSTOM", VadEngine.TEN, "Custom (값 직접 입력)", t(0.5, 256, 0, 0, 0), isCustom = true),
    )

    // ── pyannote: 공식 기본 / PC GUI v8.1 추천 2종 ──
    private val PYANNOTE_PRESETS = listOf(
        VadPreset("PYANNOTE_DEFAULT", VadEngine.PYANNOTE, "pyannote 공식 기본 onset/offset 0.5 · on 0 · off 0", p(0.5, 0.5, 0.0, 0.0, 0.0, 0.0)),
        VadPreset("PYANNOTE_S500", VadEngine.PYANNOTE, "PC GUI v8.1 세밀 min_off 0.5 s · 여유 0.15 s", p(0.5, 0.5, 0.0, 0.5, 0.15, 0.15)),
        VadPreset("PYANNOTE_S2000", VadEngine.PYANNOTE, "PC GUI v8.1 길게 min_off 2.0 s · 여유 0.15 s", p(0.5, 0.5, 0.0, 2.0, 0.15, 0.15)),
        VadPreset("PYANNOTE_CUSTOM", VadEngine.PYANNOTE, "Custom (값 직접 입력)", p(0.5, 0.5, 0.0, 0.0, 0.0, 0.0), isCustom = true),
    )

    // ── FSMN: 공식 기본(동적 무음 = PC funasr 1.4.16) / 모델 config 고정값 / PC GUI v8.1 세밀 ──
    private val FSMN_PRESETS = listOf(
        VadPreset("FSMN_DEFAULT", VadEngine.FSMN, "FunASR 공식 기본(동적 끝 무음, thres 0.5) · max 60 s", f(0, 0.5, 60000)),
        VadPreset("FSMN_CONFIG", VadEngine.FSMN, "모델 config.yaml 고정값 800 ms · 0.6 · 60 s", f(800, 0.6, 60000)),
        VadPreset("FSMN_S500", VadEngine.FSMN, "PC GUI v8.1 세밀 max_end_silence 500 ms · 0.6", f(500, 0.6, 60000)),
        VadPreset("FSMN_CUSTOM", VadEngine.FSMN, "Custom (값 직접 입력)", f(0, 0.5, 60000), isCustom = true),
    )

    // ── FireRed: 공식 기본 / PC GUI v8.1 추천 2종 ──
    private val FIRERED_PRESETS = listOf(
        VadPreset("FIRERED_DEFAULT", VadEngine.FIRERED, "FireRedVadConfig 공식 기본 0.4 · 20/2000/20 frame", r(0.4, 5, 20, 2000, 20, 0, 0)),
        VadPreset("FIRERED_S500", VadEngine.FIRERED, "PC GUI v8.1 세밀 min_silence 50 · extend 15", r(0.4, 5, 20, 2000, 50, 0, 15)),
        VadPreset("FIRERED_S2000", VadEngine.FIRERED, "PC GUI v8.1 길게 min_silence 200 · extend 15", r(0.4, 5, 20, 2000, 200, 0, 15)),
        VadPreset("FIRERED_CUSTOM", VadEngine.FIRERED, "Custom (값 직접 입력)", r(0.4, 5, 20, 2000, 20, 0, 0), isCustom = true),
    )

    /** VAD 를 쓰지 않을 때의 자리 표시용 프리셋 */
    val NONE_PRESET = VadPreset("NOVAD", VadEngine.NONE, "VAD 를 쓰지 않음", emptyMap())

    /**
     * 목적: 엔진의 프리셋 목록(표시 순서)을 돌려준다.
     * 입력: engine
     * 리턴: 프리셋 목록(맨 끝이 Custom)
     */
    fun presets(engine: VadEngine): List<VadPreset> = when (engine) {
        VadEngine.NONE -> listOf(NONE_PRESET)
        VadEngine.SILERO -> SILERO_PRESETS
        VadEngine.WEBRTC -> WEBRTC_PRESETS
        VadEngine.TEN -> TEN_PRESETS
        VadEngine.PYANNOTE -> PYANNOTE_PRESETS
        VadEngine.FSMN -> FSMN_PRESETS
        VadEngine.FIRERED -> FIRERED_PRESETS
    }

    /**
     * 목적: 엔진의 Custom 프리셋(시작값)을 돌려준다.
     * 입력: engine
     * 리턴: Custom 프리셋
     */
    fun customStart(engine: VadEngine): VadPreset =
        presets(engine).first { it.isCustom || engine == VadEngine.NONE }
}
