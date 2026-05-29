package org.lixidong.musicplugin.audio

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock

/**
 * 阻塞式文件输入流：支持从正在下载的文件中读取数据。
 *
 * 当读取到文件末尾时，不会立即返回 EOF，而是等待文件增长（下载继续）。
 * 由 PrebufferManager 在写入新数据后调用 [notifyMoreData] 唤醒等待的读取线程。
 * 下载完成后由 PrebufferManager 调用 [notifyComplete] 标记结束。
 *
 * 用于 Phase 5 预缓冲：先下载前 N 字节，然后边下载边播放。
 */
internal class BlockingFileInputStream(
    private val filePath: Path,
    private val readTimeoutMs: Long = 30_000L
) : InputStream() {

    private val log = Logger.getInstance(BlockingFileInputStream::class.java)
    private val channel: FileChannel = FileChannel.open(filePath, StandardOpenOption.READ)
    private val lock = ReentrantLock()
    private val condition: Condition = lock.newCondition()
    private var position: Long = 0L
    private var closed: Boolean = false
    private var complete: Boolean = false
    private val singleByteBuffer = ByteBuffer.allocate(1)

    /**
     * 通知有新数据可用（由 PrebufferManager 调用）
     */
    fun notifyMoreData() {
        lock.lock()
        try {
            condition.signalAll()
        } finally {
            lock.unlock()
        }
    }

    /**
     * 通知下载完成（由 PrebufferManager 调用）
     */
    fun notifyComplete() {
        lock.lock()
        try {
            complete = true
            condition.signalAll()
        } finally {
            lock.unlock()
        }
    }

    override fun read(): Int {
        singleByteBuffer.clear()
        val n = read(singleByteBuffer)
        if (n <= 0) return -1
        singleByteBuffer.flip()
        return singleByteBuffer.get().toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        val buf = ByteBuffer.wrap(b, off, len)
        return read(buf)
    }

    private fun read(buf: ByteBuffer): Int {
        lock.lock()
        try {
            while (!closed) {
                val n = channel.read(buf, position)
                if (n > 0) {
                    position += n
                    return n
                }

                // 到达当前文件末尾
                if (complete || closed) {
                    return -1
                }

                // 等待新数据
                val startTime = System.currentTimeMillis()
                try {
                    val signaled = condition.await(readTimeoutMs, TimeUnit.MILLISECONDS)
                    if (!signaled && !complete && !closed) {
                        throw IOException("Read timeout after ${readTimeoutMs}ms, no new data available")
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Read interrupted")
                }
            }
            return -1
        } finally {
            lock.unlock()
        }
    }

    override fun available(): Int {
        lock.lock()
        try {
            val fileSize = channel.size()
            return (fileSize - position).coerceAtLeast(0L).toInt()
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        lock.lock()
        try {
            closed = true
            condition.signalAll()
            runCatching { channel.close() }
        } finally {
            lock.unlock()
        }
    }

    fun getPosition(): Long = position
}
