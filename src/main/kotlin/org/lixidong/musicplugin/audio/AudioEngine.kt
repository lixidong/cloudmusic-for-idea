package org.lixidong.musicplugin.audio

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

    interface Listener {
        fun onPositionUpdate(positionMs: Long)
        fun onCompleted()
        fun onError(error: Throwable)
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
    @Volatile private var seekStartMs: Long = 0L

    fun setListener(l: Listener?) { listener = l }

    @Synchronized
    fun play(url: String, startAtMs: Long = 0L) {
        stopInternal()
        stopRequested = false
        paused = false
        currentUrl = url
        seekStartMs = startAtMs.coerceAtLeast(0L)
        val t = Thread({ runPlayback(url) }, "NeteaseMusic-Playback")
        t.isDaemon = true
        playbackThread = t
        t.start()
    }

    /**
     * Seek the currently playing song to the given absolute position (ms).
     * Reopens the stream and discards PCM up to the target — no HTTP Range needed,
     * which keeps things compatible with NetEase's tokenised URLs.
     */
    fun seekTo(positionMs: Long) {
        val url = currentUrl ?: return
        play(url, positionMs)
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
        try { line?.stop() } catch (_: Throwable) {}
        try { line?.flush() } catch (_: Throwable) {}
        try { line?.close() } catch (_: Throwable) {}
        try { pcmStream?.close() } catch (_: Throwable) {}
        try { rawStream?.close() } catch (_: Throwable) {}
        line = null
        pcmStream = null
        rawStream = null
        playbackThread?.interrupt()
        playbackThread = null
    }

    fun pause() {
        paused = true
        try { line?.stop() } catch (_: Throwable) {}
    }

    fun resume() {
        paused = false
        try { line?.start() } catch (_: Throwable) {}
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
        } catch (_: Throwable) {}
    }

    private fun runPlayback(url: String) {
        var opened: StreamSource.Opened? = null
        val seekTargetMs = seekStartMs
        try {
            opened = StreamSource.open(url)
            rawStream = opened.raw
            pcmStream = opened.pcm
            sampleRate = opened.targetFormat.sampleRate

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
                }
                if (stopRequested) return
            }

            val sdl = AudioSystem.getSourceDataLine(opened.targetFormat)
            sdl.open(opened.targetFormat, 64 * 1024)
            sdl.start()
            line = sdl
            applyVolume()

            val buf = ByteArray(8 * 1024)
            var lastEmitMs = 0L
            while (!stopRequested) {
                if (paused) {
                    Thread.sleep(40)
                    continue
                }
                val read = opened.pcm.read(buf, 0, buf.size)
                if (read < 0) break
                if (read > 0) {
                    var off = 0
                    while (off < read && !stopRequested) {
                        val wrote = sdl.write(buf, off, read - off)
                        off += wrote
                    }
                    val playedMs = (sdl.framePosition * 1000L) / sampleRate.toLong()
                    val posMs = playedMs + seekTargetMs
                    if (posMs - lastEmitMs >= 250) {
                        lastEmitMs = posMs
                        listener?.onPositionUpdate(posMs)
                    }
                }
            }
            if (!stopRequested) {
                try { sdl.drain() } catch (_: Throwable) {}
                listener?.onCompleted()
            }
        } catch (_: InterruptedException) {
            // expected on cancel
        } catch (e: Throwable) {
            if (!stopRequested) listener?.onError(e)
        } finally {
            try { line?.close() } catch (_: Throwable) {}
            try { opened?.pcm?.close() } catch (_: Throwable) {}
            try { opened?.raw?.close() } catch (_: Throwable) {}
            line = null
            pcmStream = null
            rawStream = null
        }
    }

    fun dispose() = stop()
}
