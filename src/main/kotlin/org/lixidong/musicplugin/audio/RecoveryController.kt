package org.lixidong.musicplugin.audio

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 播放卡住时的分级恢复策略:
 *
 * - Level 1: 仅通知 (短暂网络抖动可能自行恢复, 等待 recoveryGraceMs)
 * - Level 2: 重连当前 URL, seek 到稳定位置回退 2 秒
 * - Level 3: 重新获取 URL (CDN token 可能过期), seek 到稳定位置
 * - Level 4: 放弃, 触发 onRecoveryExhausted (通常切歌)
 *
 * 每次有播放进度 (onProgress) 或切歌 (reset) 时, 恢复等级归零.
 */
internal class RecoveryController(
    private val engine: AudioEngine,
    private val fetchNewUrl: suspend (Long) -> String?,
    private val onRecoveryExhausted: () -> Unit,
) {
    private val log = Logger.getInstance(RecoveryController::class.java)
    private val recoveryLevel = AtomicInteger(0)

    @Volatile
    private var lastStablePositionMs: Long = 0L

    private val cs: CoroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * 播放卡住时调用. 根据连续卡顿次数执行不同级别的恢复策略.
     * 注意: 此方法由 watchdog 线程调用, 不要在此处做耗时操作 (除 Level 3 的 URL 获取外).
     */
    fun onStall() {
        val level = recoveryLevel.incrementAndGet()
        val seekTo = (lastStablePositionMs - 2000).coerceAtLeast(0)
        val songId = engine.currentSongId()

        when (level) {
            1 -> {
                // Level 1: 短暂等待, 可能是临时网络抖动
                log.info("playback stall level 1: waiting for natural recovery")
            }

            2 -> {
                // Level 2: 重连当前 URL (seek 回退 2 秒避免丢字)
                log.info("playback stall level 2: reconnecting current URL, seek to ${seekTo}ms")
                engine.reconnectCurrentUrl(seekTo)
            }

            3 -> {
                // Level 3: 重新获取 URL (CDN token 可能已过期)
                log.info("playback stall level 3: fetching new URL, seek to ${seekTo}ms")
                cs.launch {
                    val url = try {
                        fetchNewUrl(songId)
                    } catch (e: Throwable) {
                        log.warn("fetch new url failed", e)
                        null
                    }
                    if (url != null) {
                        engine.play(url, songId, seekTo)
                    } else {
                        recoveryLevel.set(MAX_RECOVERIES + 1)
                        onRecoveryExhausted()
                    }
                }
            }

            else -> {
                // Level 4+: 所有恢复手段用尽, 切歌
                log.warn("playback stall level $level: all recovery attempts failed, giving up")
                recoveryLevel.set(0)
                lastStablePositionMs = 0
                onRecoveryExhausted()
            }
        }
    }

    /**
     * 播放进度更新时调用. 有进度就重置恢复等级.
     */
    fun onProgress(positionMs: Long) {
        if (positionMs > lastStablePositionMs) {
            lastStablePositionMs = positionMs
            recoveryLevel.set(0)
        }
    }

    /**
     * 切歌时调用. 重置所有状态.
     */
    fun reset() {
        recoveryLevel.set(0)
        lastStablePositionMs = 0
    }

    companion object {
        const val MAX_RECOVERIES = 3
    }
}
