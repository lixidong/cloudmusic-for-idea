package org.lixidong.musicplugin.audio

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 预缓冲管理器：先下载文件前 N 字节，然后边下载边播放。
 *
 * 预缓冲流程：
 * 1. 下载前 [prebufferBytes] 字节到 .partial 文件
 * 2. 创建 BlockingFileInputStream（可以从正在下载的文件中读取）
 * 3. 返回 InputStream 给 AudioEngine 开始播放
 * 4. 后台线程继续下载剩余数据
 *
 * BlockingFileInputStream 在读取到当前文件末尾时会阻塞，等待下载线程写入更多数据。
 */
internal class PrebufferManager(
    private val prebufferBytes: Int = 512 * 1024,
    private val prebufferTimeoutMs: Long = 15_000L
) {
    private val log = Logger.getInstance(PrebufferManager::class.java)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    data class PrebufferResult(
        val stream: InputStream,
        val totalBytes: Long,
        val partialFile: Path
    )

    /**
     * 预缓冲：下载前 N 字节，然后返回 InputStream。
     *
     * @param songId 歌曲 ID
     * @param url 音频 URL
     * @param onProgress 下载进度回调
     * @return PrebufferResult 包含 InputStream 和总字节数
     * @throws IOException 下载失败或超时
     */
    fun prebuffer(
        songId: Long,
        url: String,
        onProgress: ((Long) -> Unit)? = null
    ): PrebufferResult {
        val cache = MediaCache.getInstance()

        // 1. 检查完整缓存
        cache.getCompleteFile(songId)?.let { file ->
            log.info("prebuffer: cache hit for songId=$songId")
            val stream = java.nio.file.Files.newInputStream(file)
            val totalBytes = java.nio.file.Files.size(file)
            return PrebufferResult(stream, totalBytes, file)
        }

        // 2. 获取 partial 文件路径
        val partialFile = cache.getOrCreatePartialFile(songId)
        val existingBytes = cache.getPartialFile(songId)?.let { java.nio.file.Files.size(it) } ?: 0L

        // 3. 发起 HTTP 请求（支持断点续传）
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

        val totalBytes = if (isResume) existingBytes + contentLength else contentLength
        if (totalBytes > 0L) {
            cache.setTotalBytes(songId, totalBytes)
        }

        // 4. 打开输出流（追加或新建）
        val outputStream = if (isResume) {
            cache.openPartialForAppend(songId)
                ?: throw IOException("Failed to open partial file for append")
        } else {
            java.io.BufferedOutputStream(java.nio.file.Files.newOutputStream(partialFile), 64 * 1024)
        }

        // 5. 创建同步机制
        val prebufferLatch = CountDownLatch(1)
        val downloadComplete = AtomicBoolean(false)
        val downloadThread = AtomicBoolean(true)
        val inputStream = response.body()
        val buffer = ByteArray(8192)
        var downloadedBytes = existingBytes
        var lastProgressTime = System.currentTimeMillis()

        // 6. 启动下载线程
        val thread = Thread({
            try {
                while (downloadThread.get()) {
                    val read = try {
                        inputStream.read(buffer)
                    } catch (e: IOException) {
                        log.warn("prebuffer: download failed", e)
                        break
                    }

                    if (read == -1) {
                        // 下载完成
                        downloadComplete.set(true)
                        downloadThread.set(false)
                        prebufferLatch.countDown()
                        break
                    }

                    outputStream.write(buffer, 0, read)
                    downloadedBytes += read

                    // 进度回调（节流）
                    val now = System.currentTimeMillis()
                    if (onProgress != null && now - lastProgressTime >= 100) {
                        onProgress(downloadedBytes)
                        lastProgressTime = now
                    }

                    // 检查是否达到预缓冲阈值
                    if (downloadedBytes - existingBytes >= prebufferBytes && prebufferLatch.count > 0) {
                        log.info("prebuffer: threshold reached, downloaded=${downloadedBytes - existingBytes} bytes")
                        prebufferLatch.countDown()
                    }
                }
            } finally {
                runCatching { outputStream.close() }
                runCatching { inputStream.close() }
                if (downloadComplete.get()) {
                    cache.markComplete(songId)
                    log.info("prebuffer: download complete for songId=$songId, bytes=$downloadedBytes")
                }
            }
        }, "NeteaseMusic-Prebuffer-$songId").apply {
            isDaemon = true
            start()
        }

        // 7. 等待预缓冲完成（或超时）
        val prebufferSuccess = prebufferLatch.await(prebufferTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!prebufferSuccess) {
            downloadThread.set(false)
            thread.interrupt()
            outputStream.close()
            inputStream.close()
            throw IOException("Prebuffer timeout after ${prebufferTimeoutMs}ms")
        }

        // 8. 创建 BlockingFileInputStream
        val blockingStream = BlockingFileInputStream(partialFile)

        // 注册下载完成回调
        thread.join(0) // 不阻塞，只是检查线程状态
        if (downloadComplete.get()) {
            blockingStream.notifyComplete()
        } else {
            // 启动一个监控线程，等待下载完成后通知
            Thread({
                thread.join()
                blockingStream.notifyComplete()
            }, "NeteaseMusic-Prebuffer-Monitor-$songId").apply {
                isDaemon = true
                start()
            }
        }

        return PrebufferResult(blockingStream, totalBytes, partialFile)
    }
}
