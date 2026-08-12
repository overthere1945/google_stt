package com.example.google_stt

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 어떤 오디오 파일(mp3 / m4a / aac / wav / ogg 등)이든
 * ML Kit GenAI Speech Recognition 이 요구하는 형식으로 변환합니다.
 *
 *   - Raw, 헤더 없는 16-bit PCM
 *   - Mono(1채널)
 *   - 16 kHz
 *
 * 변환 단계:
 *   1) MediaExtractor 로 오디오 트랙을 찾고,
 *   2) audio/raw(WAV 등)면 그대로 PCM 을 읽고, 아니면 MediaCodec 으로 디코딩,
 *   3) 여러 채널이면 모노로 다운믹스,
 *   4) 원본 샘플레이트를 16 kHz 로 리샘플링(선형보간).
 */
object AudioDecoder {

    private const val TARGET_RATE = 16000
    private const val TIMEOUT_US = 10_000L

    /** 오디오 파일 URI 를 16kHz/mono/16-bit PCM 바이트 배열로 변환한다. */
    fun decodeTo16kMonoPcm(context: Context, uri: Uri): ByteArray {
        val extractor = MediaExtractor()
        context.contentResolver.openFileDescriptor(uri, "r").use { pfd ->
            requireNotNull(pfd) { "파일을 열 수 없습니다: $uri" }
            extractor.setDataSource(pfd.fileDescriptor)
        }
        try {
            val trackIndex = selectAudioTrack(extractor)
            require(trackIndex >= 0) { "오디오 트랙을 찾을 수 없습니다." }
            extractor.selectTrack(trackIndex)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val srcRate =
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else TARGET_RATE
            val channels =
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1

            val interleaved: ByteArray =
                if (mime == "audio/raw") readRawPcm(extractor)
                else decodeWithCodec(extractor, format, mime)

            val mono = downmixToMono(interleaved, channels)
            return resampleTo16k(mono, srcRate)
        } finally {
            extractor.release()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** audio/raw(예: 16-bit PCM WAV)는 코덱 없이 그대로 읽는다. */
    private fun readRawPcm(extractor: MediaExtractor): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(1 shl 16)
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val chunk = ByteArray(size)
            buffer.position(0)
            buffer.get(chunk, 0, size)
            out.write(chunk)
            extractor.advance()
            buffer.clear()
        }
        return out.toByteArray()
    }

    /** mp3 / m4a / aac / ogg 등은 MediaCodec 으로 PCM 디코딩한다. */
    private fun decodeWithCodec(
        extractor: MediaExtractor,
        format: MediaFormat,
        mime: String
    ): ByteArray {
        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

        try {
            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEOS = true
                            } else {
                                codec.queueInputBuffer(
                                    inIndex, 0, sampleSize, extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                        if (info.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIndex)
                            if (outBuf != null) {
                                val chunk = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.get(chunk, 0, info.size)
                                out.write(chunk)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            pcmEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }

        val bytes = out.toByteArray()
        return if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) floatToS16(bytes) else bytes
    }

    /** 32-bit float PCM 을 16-bit PCM 으로 변환한다(일부 기기의 디코더 출력 대비). */
    private fun floatToS16(floatBytes: ByteArray): ByteArray {
        val floatCount = floatBytes.size / 4
        val fb = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = ByteArray(floatCount * 2)
        val sb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until floatCount) {
            var v = fb.get(i)
            if (v > 1f) v = 1f
            if (v < -1f) v = -1f
            sb.put(i, (v * 32767f).toInt().toShort())
        }
        return out
    }

    /** 인터리브된 16-bit PCM 을 모노로 평균 다운믹스한다. */
    private fun downmixToMono(interleavedS16: ByteArray, channels: Int): ByteArray {
        if (channels <= 1) return interleavedS16
        val totalSamples = interleavedS16.size / 2
        val frames = totalSamples / channels
        val src = ShortArray(totalSamples)
        ByteBuffer.wrap(interleavedS16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var acc = 0
            val base = f * channels
            for (c in 0 until channels) acc += src[base + c].toInt()
            mono[f] = (acc / channels).toShort()
        }
        val out = ByteArray(frames * 2)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(mono)
        return out
    }

    /** 모노 16-bit PCM 을 원본 샘플레이트에서 16 kHz 로 선형보간 리샘플링한다. */
    private fun resampleTo16k(monoS16: ByteArray, srcRate: Int): ByteArray {
        if (srcRate == TARGET_RATE || monoS16.isEmpty()) return monoS16
        val srcSamples = monoS16.size / 2
        if (srcSamples == 0) return monoS16
        val src = ShortArray(srcSamples)
        ByteBuffer.wrap(monoS16).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(src)

        val ratio = TARGET_RATE.toDouble() / srcRate
        val dstSamples = (srcSamples * ratio).toInt().coerceAtLeast(1)
        val dst = ShortArray(dstSamples)
        for (i in 0 until dstSamples) {
            val srcPos = i / ratio
            val idx = srcPos.toInt()
            val frac = srcPos - idx
            val s0 = src[idx.coerceIn(0, srcSamples - 1)].toInt()
            val s1 = src[(idx + 1).coerceIn(0, srcSamples - 1)].toInt()
            dst[i] = (s0 + (s1 - s0) * frac).toInt().coerceIn(-32768, 32767).toShort()
        }
        val out = ByteArray(dstSamples * 2)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(dst)
        return out
    }
}
