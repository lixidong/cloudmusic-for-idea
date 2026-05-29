package org.lixidong.musicplugin.audio

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 为 InputStream 的 read() 添加超时支持。
 *
 * JDK 的 HttpClient 返回的 InputStream 没有 per-call timeout。
 * 如果 CDN 停止发送数据但不关闭 socket，read() 会永远阻塞。
 * 这个包装器使用异步执行器来强制执行超时。
 */
internal class TimeoutInputStream(
    private val source: InputStream,
    private val timeoutMs: Long
) : InputStream() {

    private val log = Logger.getInstance(TimeoutInputStream::class.java)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NeteaseMusic-TimeoutRead").apply { isDaemon = true }
    }

    override fun read(): Int {
        val future: Future<Int> = executor.submit<Int> { source.read() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            runCatching { source.close() }
            throw IOException("Read timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            future.cancel(true)
            throw IOException("Read failed", e)
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val future: Future<Int> = executor.submit<Int> { source.read(b, off, len) }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            runCatching { source.close() }
            throw IOException("Read timed out after ${timeoutMs}ms")
        } catch (e: Exception) {
            future.cancel(true)
            throw IOException("Read failed", e)
        }
    }

    override fun available(): Int = source.available()

    override fun close() {
        runCatching { source.close() }
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_READ_TIMEOUT_MS = 30_000L
    }
}
