package org.lixidong.musicplugin.audio

import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader
import java.io.BufferedInputStream
import java.io.InputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream

/**
 * Decodes an already-opened MP3 InputStream into PCM.
 *
 * Classloading note: IntelliJ plugins live in a separate classloader, and
 * `AudioSystem` resolves SPI providers via `ServiceLoader` against the thread
 * context classloader (usually the IDE's main loader) — so it cannot find
 * mp3spi shipped inside our plugin jar. We bypass `AudioSystem` and instantiate
 * `MpegAudioFileReader` / `MpegFormatConversionProvider` directly.
 *
 * HTTP is handled by [MediaCache] so we can transparently tee downloads into
 * the on-disk cache; this object is therefore source-agnostic.
 */
internal object StreamSource {

    private val reader = MpegAudioFileReader()
    private val converter = MpegFormatConversionProvider()

    data class Opened(val raw: InputStream, val pcm: AudioInputStream, val targetFormat: AudioFormat)

    fun open(input: InputStream): Opened {
        // 256KB buffer (was 64KB): MP3 → PCM expands ~10x for 44.1kHz/16-bit stereo,
        // so 64KB drains in ~700ms of music. With 256KB the playback loop can ride
        // through a 2-3s network hiccup without audible drop-outs.
        val buffered = BufferedInputStream(input, 256 * 1024)
        val mp3 = reader.getAudioInputStream(buffered)
        val base = mp3.format
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            base.sampleRate,
            16,
            base.channels,
            base.channels * 2,
            base.sampleRate,
            false
        )
        val pcm = converter.getAudioInputStream(target, mp3)
        return Opened(buffered, pcm, target)
    }
}
