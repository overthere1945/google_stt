/*
 * 파일명: engine/audio/Pcm16.kt
 * 목적 및 기능:
 * - 16 kHz / mono / PCM16 LE 바이트를 엔진이 받는 형태로 바꾼다(float: sherpa-onnx·ONNX VAD, short: TEN VAD).
 * - v3 의 vad/VadPipeline.kt 안 VadAudio.toFloat / toShort 를 식 그대로 옮겼다(결과가 달라지면 기준 TXT 와 어긋난다).
 *
 * add-hyungchul-20261106-2132 — [T5-1]
 */
package com.example.google_stt.engine.audio

/** 목적: PCM16 LE 변환. */
object Pcm16 {

    /** 표본화 주파수 16 kHz */
    const val SAMPLE_RATE = 16_000

    /**
     * 목적: PCM16 LE 바이트를 [-1, 1) float 로 바꾼다.
     * 입력: pcm - PCM16 LE / 출력: 없음 / 리턴: FloatArray(샘플 수 = pcm.size / 2)
     */
    fun toFloat(pcm: ByteArray): FloatArray {
        val out = FloatArray(pcm.size / 2)
        var bi = 0
        for (i in out.indices) {
            val lo = pcm[bi].toInt() and 0xFF                             // little-endian: 낮은 바이트가 먼저
            val hi = pcm[bi + 1].toInt()                                  // 부호 확장을 위해 마스킹하지 않는다
            out[i] = ((hi shl 8) or lo).toShort().toInt() / 32768.0f
            bi += 2
        }
        return out
    }

    /**
     * 목적: PCM16 LE 바이트를 ShortArray 로 바꾼다(TEN VAD 입력용).
     * 입력: pcm - PCM16 LE / 출력: 없음 / 리턴: ShortArray
     */
    fun toShort(pcm: ByteArray): ShortArray {
        val out = ShortArray(pcm.size / 2)
        var bi = 0
        for (i in out.indices) {
            val lo = pcm[bi].toInt() and 0xFF                             // 낮은 바이트
            val hi = pcm[bi + 1].toInt()                                  // 높은 바이트(부호 포함)
            out[i] = ((hi shl 8) or lo).toShort()
            bi += 2
        }
        return out
    }
}
