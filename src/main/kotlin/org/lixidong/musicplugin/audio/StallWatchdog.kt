package org.lixidong.musicplugin.audio

/**
 * Detects when playback stops making progress (e.g. the HTTP source has silently
 * hung — socket alive but no bytes arriving) and fires [onStall] so the engine
 * can recover by aborting the current track.
 *
 * Why this is needed: JDK's `HttpClient` returns an `InputStream` whose `read()`
 * has no per-call timeout. If NetEase's CDN stops feeding bytes without closing
 * the socket, the playback thread parks inside `pcm.read()` forever — `pause`
 * / `resume` only toggle the audio line, they can't unstick a thread blocked
 * on I/O. The watchdog observes write progress on the side and severs the
 * stream when nothing has moved for [timeoutMs] ms.
 */
internal class StallWatchdog(
    private val timeoutMs: Long,
    private val onStall: (reason: String) -> Unit,
) {

    @Volatile private var lastProgressMs: Long = System.currentTimeMillis()
    @Volatile private var paused: Boolean = false
    @Volatile private var disposed: Boolean = false

    private val thread = Thread({ runLoop() }, "NeteaseMusic-Watchdog").apply {
        isDaemon = true
    }

    fun start() { thread.start() }

    fun progress() { lastProgressMs = System.currentTimeMillis() }

    fun pause() { paused = true }

    fun resume() {
        // Don't trust the pre-pause timestamp — the user may have been paused for hours.
        lastProgressMs = System.currentTimeMillis()
        paused = false
    }

    fun dispose() {
        disposed = true
        thread.interrupt()
    }

    private fun runLoop() {
        while (!disposed) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }
            if (disposed) return
            if (paused) continue
            val elapsed = System.currentTimeMillis() - lastProgressMs
            if (elapsed > timeoutMs) {
                if (!disposed) onStall("no playback progress for ${elapsed}ms")
                return
            }
        }
    }
}
