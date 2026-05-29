package org.lixidong.musicplugin.audio

import com.intellij.openapi.diagnostic.Logger
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 下载音频流到本地文件，支持：
 * - 读取超时（防止 CDN 静默挂起导致线程永远阻塞）
 * - 断点续传（从 partial 文件继续下载）
 * - 下载完成后自动标记为完整缓存
 *
 * 下载完成后的文件由 AudioEngine.playFromFile() 播放，
 * 实现下载层与播放层的完全解耦。
 */
internal class StreamDownloader(
    private val readTimeoutMs: Long = TimeoutInputStream.DEFAULT_READ_TIMEOUT_MS
) {

    private val log = Logger.getInstance(StreamDownloader::class.java)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    data class DownloadResult(
        val file: Path,
        val totalBytes: Long,
        val fromCache: Boolean
    )

    /**
     * 下载音频到本地文件。
     *
     * @param songId 歌曲 ID，用于缓存键
     * @param url 音频 URL
     * @param onProgress 下载进度回调 (已下载字节数)，可能从非主线程调用
     * @return 下载结果，包含本地文件路径和总字节数
     * @throws IOException 下载失败（网络超时、HTTP 错误等）
     */
    fun download(songId: Long, url: String, onProgress: ((Long) -> Unit)? = null): DownloadResult {
        val cache = MediaCache.getInstance()

        // 1. 检查完整缓存
        cache.getCompleteFile(songId)?.let { file ->
            log.info("streamDownloader: cache hit for songId=$songId")
            return DownloadResult(file, Files.size(file), fromCache = true)
        }

        // 2. 检查 partial 文件，支持断点续传
        val partialFile = cache.getOrCreatePartialFile(songId)
        val existingBytes = if (Files.exists(partialFile)) Files.size(partialFile) else 0L
        val totalBytes = cache.getTotalBytes(songId)

        // 3. 发起 HTTP 请求
        val requestBuilder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET()

        if (existingBytes > 0L) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val response = http.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())

        if (response.statusCode() / 100 != 2 && response.statusCode() != 206) {
            response.body().close()
            throw IOException("HTTP ${response.statusCode()} for url=$url")
        }

        val isResume = response.statusCode() == 206
        val contentLength = response.headers().firstValue("Content-Length")
            .map { it.toLongOrNull() ?: 0L }
            .orElse(0L)

        // 计算总字节数
        val actualTotalBytes = when {
            isResume -> existingBytes + contentLength
            contentLength > 0 -> contentLength
            totalBytes > 0 -> totalBytes
            else -> 0L
        }

        if (actualTotalBytes > 0L) {
            cache.setTotalBytes(songId, actualTotalBytes)
        }

        // 4. 包装输入流，添加读取超时
        val timeoutStream = TimeoutInputStream(response.body(), readTimeoutMs)

        // 5. 下载到文件
        val outputStream = if (isResume) {
            cache.openPartialForAppend(songId)
                ?: throw IOException("Failed to open partial file for append")
        } else {
            BufferedOutputStream(Files.newOutputStream(partialFile), 64 * 1024)
        }

        var downloadedBytes = existingBytes
        var lastProgressTime = System.currentTimeMillis()

        try {
            val buffer = ByteArray(8192)
            while (true) {
                val read = timeoutStream.read(buffer)
                if (read == -1) break

                outputStream.write(buffer, 0, read)
                downloadedBytes += read

                // 节流进度回调（最多 100ms 一次）
                val now = System.currentTimeMillis()
                if (onProgress != null && now - lastProgressTime >= 100) {
                    onProgress(downloadedBytes)
                    lastProgressTime = now
                }
            }

            // 最后一次进度回调
            onProgress?.invoke(downloadedBytes)

        } catch (e: IOException) {
            log.warn("streamDownloader: download failed for songId=$songId", e)
            throw e
        } finally {
            timeoutStream.close()
            outputStream.close()
        }

        // 6. 标记完成
        cache.markComplete(songId)
        log.info("streamDownloader: download complete for songId=$songId, bytes=$downloadedBytes")

        return DownloadResult(partialFile, downloadedBytes, fromCache = false)
    }
}
