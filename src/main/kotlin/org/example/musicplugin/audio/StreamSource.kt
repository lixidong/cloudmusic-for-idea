package org.example.musicplugin.audio

import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream

/**
 * Streams an MP3 URL and exposes a PCM AudioInputStream.
 *
 * Notes on classloading: IntelliJ plugins live in a separate classloader, and `AudioSystem`
 * resolves SPI providers via `ServiceLoader` against the thread context classloader, which
 * is usually the IDE's main loader — so it cannot find mp3spi shipped inside our plugin jar.
 * We bypass `AudioSystem` and instantiate `MpegAudioFileReader` / `MpegFormatConversionProvider`
 * directly.
 */
internal object StreamSource {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val reader = MpegAudioFileReader()
    private val converter = MpegFormatConversionProvider()

    data class Opened(val raw: InputStream, val pcm: AudioInputStream, val targetFormat: AudioFormat)

    fun open(url: String): Opened {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() / 100 != 2) {
            try { resp.body().close() } catch (_: Throwable) {}
            throw IllegalStateException("HTTP ${resp.statusCode()} for $url")
        }
        val buffered = BufferedInputStream(resp.body(), 64 * 1024)
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
