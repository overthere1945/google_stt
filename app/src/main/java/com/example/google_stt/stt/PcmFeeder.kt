/*
 * 파일명: stt/PcmFeeder.kt
 * 목적 및 기능:
 * - 16 kHz/mono/PCM16 바이트를 파이프(ParcelFileDescriptor)에 일정 속도로 흘려보내는 공급기.
 * - ML Kit / Android 플랫폼 인식기에 "파일을 마이크처럼" 넣을 때 쓴다.
 *
 * change-hyungchul-20261009-1514
 *   MainActivity.kt 안의 private 중첩 클래스를 내용 변경 없이 별도 파일로 옮겼다.
 */
package com.example.google_stt.stt

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CompletableDeferred
import java.io.IOException

// ─────────────────────────── PCM 공급기 ───────────────────────────
/**
 * 목적: PCM 을 파이프에 일정 속도로 흘려보낸다. 공급이 끝나면 write 쪽이 닫혀 EOF → 세션 종료.
 * 비고: 코루틴 대신 전용 데몬 스레드를 쓴다. 인식기가 먼저 죽어 파이프가 막히면
 *       블로킹 write 는 코루틴 취소로 풀리지 않아 배치 전체가 멈추기 때문이다.
 * change(add)-hyungchul-20260820: 공급 시작/종료 시각을 남겨 tail latency 계산에 쓴다.
 */
internal class PcmFeeder(
    private val writeSide: ParcelFileDescriptor,
    private val pcm: ByteArray,
    private val chunkBytes: Int,
    private val chunkDelayMs: Long,
    private val onFed: (Double) -> Unit,
) {
    @Volatile
    private var aborted = false

    @Volatile
    private var started = false

    @Volatile
    var startedAtNs: Long = 0L
        private set

    @Volatile
    var finishedAtNs: Long = 0L
        private set

    val finished = CompletableDeferred<Unit>()

    private val thread = Thread({
        try {
            feed()
        } finally {
            finishedAtNs = System.nanoTime()
            onFed(1.0)
            finished.complete(Unit)
        }
    }, "pcm-feeder").apply { isDaemon = true }

    fun start() {
        started = true
        startedAtNs = System.nanoTime()
        thread.start()
    }

    /** 시작 전에 중단되면 write 쪽 FD 가 새므로 여기서 직접 닫는다. */
    fun abort() {
        aborted = true
        if (started) {
            thread.interrupt()
        } else {
            runCatching { writeSide.close() }
            finished.complete(Unit)
        }
    }

    private fun feed() {
        ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { os ->
            var offset = 0
            while (offset < pcm.size) {
                if (aborted) return
                val end = minOf(offset + chunkBytes, pcm.size)
                try {
                    os.write(pcm, offset, end - offset)
                    os.flush()
                } catch (_: IOException) {
                    // change-hyungchul-20260828-1000: 예외 객체를 쓰지 않으므로 _ 로 둔다.
                    return // 인식기가 먼저 닫힘(EPIPE)
                }
                offset = end
                if (pcm.isNotEmpty()) onFed(offset.toDouble() / pcm.size)
                if (chunkDelayMs > 0) {
                    try {
                        Thread.sleep(chunkDelayMs)
                    } catch (_: InterruptedException) {
                        // change-hyungchul-20260828-1000: 예외 객체를 쓰지 않으므로 _ 로 둔다.
                        return
                    }
                }
            }
        }
    }
}
