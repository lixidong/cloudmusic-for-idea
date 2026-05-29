package org.lixidong.musicplugin.audio

/**
 * Detects when playback stops making progress (e.g. the HTTP source has silently
 * hung — socket alive but no bytes arriving) and fires [onStall] so the engine
 * can recover.
 *
 * 支持分级恢复: 首次检测到卡顿后, 每 [recoveryGraceMs] 再次触发,
 * 让 RecoveryController 根据连续触发次数执行不同级别的恢复策略.
 *
 * Why this is needed: JDK's `HttpClient` returns an `InputStream` whose `read()`
 * has no per-call timeout. If NetEase's CDN stops feeding bytes without closing
 * the socket, the playback thread parks inside `pcm.read()` forever.
 */
internal class StallWatchdog(
    private val timeoutMs: Long,
    private val recoveryGraceMs: Long = 5_000L,
    private val onStall: () -> Unit,
) {

    @Volatile private var lastProgressMs: Long = System.currentTimeMillis()
    @Volatile private var paused: Boolean = false
    @Volatile private var disposed: Boolean = false
    @Volatile private var reported: Boolean = false
    @Volatile private var lastStallMs: Long = 0L

    private val thread = Thread({ runLoop() }, "NeteaseMusic-Watchdog").apply {
        isDaemon = true
    }

    fun start() { thread.start() }

    fun progress() {
        lastProgressMs = System.currentTimeMillis()
        reported = false
    }

    fun pause() { paused = true }

    fun resume() {
        lastProgressMs = System.currentTimeMillis()
        reported = false
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
            if (!reported && elapsed > timeoutMs) {
                reported = true
                lastStallMs = System.currentTimeMillis()
                onStall()
            } else if (reported && elapsed - lastStallMs > recoveryGraceMs) {
                lastStallMs = System.currentTimeMillis()
                onStall()
            }
        }
    }
}
