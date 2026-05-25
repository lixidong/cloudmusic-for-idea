package org.lixidong.musicplugin.audio

import com.intellij.openapi.diagnostic.Logger
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.FloatControl
import javax.sound.sampled.SourceDataLine
import kotlin.math.log10

/**
 * Streams + decodes + plays an MP3 URL via javax.sound.sampled (with mp3spi SPI).
 * Single playback at a time. Thread-safe public surface.
 */
internal class AudioEngine {

    private val log = Logger.getInstance(AudioEngine::class.java)

    interface Listener {
        fun onPositionUpdate(positionMs: Long)
        fun onCompleted()
        fun onError(error: Throwable)
        /**
         * The current track stopped making progress and was forcibly aborted.
         * Implementations should typically advance to the next track. Default
         * delegates to [onError] for backwards-compat.
         */
        fun onStalled() { onError(java.io.IOException("playback stalled")) }
    }

    @Volatile private var line: SourceDataLine? = null
    @Volatile private var pcmStream: AudioInputStream? = null
    @Volatile private var rawStream: java.io.InputStream? = null
    @Volatile private var playbackThread: Thread? = null
    @Volatile private var paused = false
    @Volatile private var stopRequested = false
    @Volatile private var sampleRate: Float = 44100f
    @Volatile private var listener: Listener? = null
    @Volatile private var volumePercent: Int = 60
    @Volatile private var currentUrl: String? = null
    @Volatile private var currentSongId: Long = 0L
    @Volatile private var seekStartMs: Long = 0L
    @Volatile private var watchdog: StallWatchdog? = null

    fun setListener(l: Listener?) { listener = l }

    @Synchronized
    fun play(url: String, songId: Long = 0L, startAtMs: Long = 0L) {
        stopInternal()
        stopRequested = false
        paused = false
        currentUrl = url
        currentSongId = songId
        seekStartMs = startAtMs.coerceAtLeast(0L)
        val wd = StallWatchdog(STALL_TIMEOUT_MS) { reason ->
            log.warn("playback stalled: $reason — aborting current track")
            stopInternal()
            listener?.onStalled()
        }
        watchdog = wd
        wd.start()
        val t = Thread({ runPlayback(url, songId, wd) }, "NeteaseMusic-Playback")
        t.isDaemon = true
        playbackThread = t
        t.start()
    }

    /**
     * Seek the currently playing song to the given absolute position (ms).
     *
     * Implementation note: we reopen the stream and discard PCM up to the target
     * rather than using HTTP Range. NetEase's tokenised URLs do not support
     * partial content; even forward seeks must restart the download. Don't
     * "optimise" this without testing — Range requests silently return 200
     * with full-body content and the byte offsets won't line up with PCM ms.
     */
    fun seekTo(positionMs: Long) {
        val url = currentUrl ?: return
        play(url, currentSongId, positionMs)
    }

    @Synchronized
    fun stop() {
        currentUrl = null
        seekStartMs = 0L
        stopInternal()
    }

    private fun stopInternal() {
        stopRequested = true
        paused = false
        watchdog?.dispose()
        watchdog = null
        try { line?.stop() } catch (e: Throwable) { log.debug("line.stop", e) }
        try { line?.flush() } catch (e: Throwable) { log.debug("line.flush", e) }
        try { line?.close() } catch (e: Throwable) { log.debug("line.close", e) }
        try { pcmStream?.close() } catch (e: Throwable) { log.debug("pcm.close", e) }
        try { rawStream?.close() } catch (e: Throwable) { log.debug("raw.close", e) }
        line = null
        pcmStream = null
        rawStream = null
        playbackThread?.interrupt()
        playbackThread = null
    }

    fun pause() {
        paused = true
        watchdog?.pause()
        try { line?.stop() } catch (e: Throwable) { log.debug("pause line.stop", e) }
    }

    fun resume() {
        paused = false
        watchdog?.resume()
        try { line?.start() } catch (e: Throwable) { log.debug("resume line.start", e) }
    }

    fun isPaused(): Boolean = paused
    fun isActive(): Boolean = playbackThread?.isAlive == true

    fun setVolume(percent: Int) {
        volumePercent = percent.coerceIn(0, 100)
        applyVolume()
    }

    fun getVolume(): Int = volumePercent

    private fun applyVolume() {
        val ln = line ?: return
        try {
            if (!ln.isControlSupported(FloatControl.Type.MASTER_GAIN)) return
            val ctrl = ln.getControl(FloatControl.Type.MASTER_GAIN) as FloatControl
            val target = if (volumePercent <= 0) ctrl.minimum
                else (20.0 * log10(volumePercent / 100.0)).toFloat()
            ctrl.value = target.coerceIn(ctrl.minimum, ctrl.maximum)
        } catch (e: Throwable) {
            log.debug("applyVolume", e)
        }
    }

    private fun runPlayback(url: String, songId: Long, wd: StallWatchdog) {
        var opened: StreamSource.Opened? = null
        val seekTargetMs = seekStartMs
        try {
            val input = MediaCache.getInstance().openStream(songId, url)
            opened = StreamSource.open(input)
            rawStream = opened.raw
            pcmStream = opened.pcm
            sampleRate = opened.targetFormat.sampleRate
            wd.progress()

            // Discard PCM up to the seek target (if any) BEFORE opening the line.
            // This avoids audible noise from playing the intro we are skipping past.
            if (seekTargetMs > 0) {
                val bytesPerMs = (opened.targetFormat.frameSize * opened.targetFormat.frameRate) / 1000.0
                val targetBytes = (seekTargetMs * bytesPerMs).toLong()
                val skipBuf = ByteArray(16 * 1024)
                var skipped = 0L
                while (skipped < targetBytes && !stopRequested) {
                    val want = minOf(skipBuf.size.toLong(), targetBytes - skipped).toInt()
                    val read = opened.pcm.read(skipBuf, 0, want)
                    if (read < 0) break
                    skipped += read
                    if (read > 0) wd.progress()
                }
                if (stopRequested) return
            }

            val sdl = AudioSystem.getSourceDataLine(opened.targetFormat)
            // 192KB line buffer (was 64KB). At 44.1kHz/16-bit stereo this is ~1.1s
            // of audio in-flight to the device, which buys headroom over the OS
            // mixer's own buffering and shields against EDT / GC jitter.
            sdl.open(opened.targetFormat, 192 * 1024)
            sdl.start()
            line = sdl
            applyVolume()
            wd.progress()

            val frameSize = opened.targetFormat.frameSize.coerceAtLeast(1)
            val buf = ByteArray(8 * 1024)
            var pending = 0  // bytes carried over from previous read that didn't fill a full frame
            var lastEmitMs = 0L
            while (!stopRequested) {
                if (paused) {
                    Thread.sleep(40)
                    continue
                }
                val read = opened.pcm.read(buf, pending, buf.size - pending)
                if (read < 0) {
                    if (pending > 0) {
                        // drop trailing partial frame at EOF; can't write < frameSize
                        pending = 0
                    }
                    break
                }
                val total = pending + read
                // sdl.write requires byte counts that are an exact multiple of frameSize.
                // mp3spi occasionally hands us a non-aligned chunk at buffer boundaries —
                // align here and carry the remainder forward.
                val aligned = (total / frameSize) * frameSize
                if (aligned > 0) {
                    var off = 0
                    while (off < aligned && !stopRequested) {
                        val wrote = sdl.write(buf, off, aligned - off)
                        off += wrote
                        if (wrote > 0) wd.progress()
                    }
                    val playedMs = (sdl.framePosition * 1000L) / sampleRate.toLong()
                    val posMs = playedMs + seekTargetMs
                    if (posMs - lastEmitMs >= 250) {
                        lastEmitMs = posMs
                        listener?.onPositionUpdate(posMs)
                    }
                }
                val leftover = total - aligned
                if (leftover > 0) {
                    System.arraycopy(buf, aligned, buf, 0, leftover)
                }
                pending = leftover
            }
            if (!stopRequested) {
                try { sdl.drain() } catch (e: Throwable) { log.debug("sdl.drain", e) }
                listener?.onCompleted()
            }
        } catch (_: InterruptedException) {
            // expected on cancel
        } catch (e: Throwable) {
            if (!stopRequested) listener?.onError(e)
        } finally {
            try { line?.close() } catch (e: Throwable) { log.debug("finally line.close", e) }
            try { opened?.pcm?.close() } catch (e: Throwable) { log.debug("finally pcm.close", e) }
            try { opened?.raw?.close() } catch (e: Throwable) { log.debug("finally raw.close", e) }
            line = null
            pcmStream = null
            rawStream = null
        }
    }

    fun dispose() = stop()

    companion object {
        private const val STALL_TIMEOUT_MS = 12_000L
    }
}
