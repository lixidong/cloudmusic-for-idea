package org.lixidong.musicplugin.audio

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import org.lixidong.musicplugin.settings.MusicSettings
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Disk-backed LRU cache for streamed MP3s. Cache key is the NetEase songId —
 * URLs are tokenised and change per request, so they're unsuitable as keys.
 *
 * On a miss we tee the HTTP body into a `.tmp` file; if the consumer reads
 * the stream to EOF the tmp is renamed to `<songId>.mp3`, otherwise it's
 * deleted. `prune` enforces the byte budget by deleting oldest-mtime files
 * until we're back under the limit.
 */
@Service(Service.Level.APP)
internal class MediaCache : Disposable {

    private val log = Logger.getInstance(MediaCache::class.java)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    private val rootDir: Path = PathManager.getSystemDir().resolve("cloudmusic-cache")
    private val pruneInProgress = AtomicBoolean(false)

    init {
        runCatching { Files.createDirectories(rootDir) }
            .onFailure { log.debug("create cache dir failed", it) }
    }

    private fun limitBytes(): Long =
        MusicSettings.getInstance().state.cacheLimitMb.coerceAtLeast(0).toLong() * 1024L * 1024L

    fun openStream(songId: Long, url: String): InputStream {
        val limit = limitBytes()
        if (limit <= 0L || songId <= 0L) return downloadOnly(url)

        val final = rootDir.resolve("$songId.mp3")
        if (Files.exists(final)) {
            runCatching { Files.setLastModifiedTime(final, FileTime.from(Instant.now())) }
            return Files.newInputStream(final)
        }

        val tmp = rootDir.resolve("$songId.mp3.tmp")
        val source = downloadOnly(url)
        val out: OutputStream = try {
            // Buffered so the tee-write doesn't add a syscall per pcm.read(),
            // which on slow filesystems would back-pressure the playback thread.
            BufferedOutputStream(
                Files.newOutputStream(
                    tmp,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                ),
                64 * 1024
            )
        } catch (e: Throwable) {
            log.debug("cache write disabled for $songId", e)
            return source
        }

        return TeeInputStream(source, out) { fullyRead ->
            if (fullyRead) {
                val ok = runCatching {
                    Files.move(tmp, final, StandardCopyOption.REPLACE_EXISTING)
                }.onFailure { log.debug("rename cache file", it) }.isSuccess
                if (ok) prune()
            } else {
                runCatching { Files.deleteIfExists(tmp) }
                    .onFailure { log.debug("rm tmp", it) }
            }
        }
    }

    private fun downloadOnly(url: String): InputStream {
        val req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        if (resp.statusCode() / 100 != 2) {
            runCatching { resp.body().close() }
            throw IllegalStateException("HTTP ${resp.statusCode()} for $url")
        }
        return resp.body()
    }

    fun updateLimit(newMb: Int) {
        if (newMb <= 0) clear() else prune()
    }

    fun clear() {
        runCatching {
            Files.list(rootDir).use { s ->
                s.forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }.onFailure { log.debug("clear cache", it) }
    }

    private data class Entry(val path: Path, val size: Long, val mtime: Long)

    private fun prune() {
        if (!pruneInProgress.compareAndSet(false, true)) return
        try {
            val limit = limitBytes()
            if (limit <= 0L) { clear(); return }
            val entries = runCatching {
                Files.list(rootDir).use { s ->
                    s.filter { it.fileName.toString().endsWith(".mp3") }
                        .map { Entry(it, Files.size(it), Files.getLastModifiedTime(it).toMillis()) }
                        .toList()
                }
            }.getOrElse { return }
            var total = entries.sumOf { it.size }
            if (total <= limit) return
            val sorted = entries.sortedBy { it.mtime }
            for (e in sorted) {
                if (total <= limit) break
                if (runCatching { Files.deleteIfExists(e.path) }.getOrElse { false }) {
                    total -= e.size
                }
            }
        } finally {
            pruneInProgress.set(false)
        }
    }

    override fun dispose() = Unit

    companion object {
        fun getInstance(): MediaCache = service()
    }
}

private class TeeInputStream(
    private val source: InputStream,
    private val out: OutputStream,
    private val onClose: (fullyRead: Boolean) -> Unit,
) : InputStream() {

    private var eof = false
    private var closed = false

    override fun read(): Int {
        val b = source.read()
        if (b < 0) eof = true
        else runCatching { out.write(b) }
        return b
    }

    override fun read(buf: ByteArray, off: Int, len: Int): Int {
        val n = source.read(buf, off, len)
        if (n < 0) eof = true
        else if (n > 0) runCatching { out.write(buf, off, n) }
        return n
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { source.close() }
        runCatching { out.close() }
        runCatching { onClose(eof) }
    }
}
