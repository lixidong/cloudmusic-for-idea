package org.lixidong.musicplugin.service

/**
 * 播放阶段的显式状态机，替代 boolean `isPlaying`。
 *
 * 状态转移：
 *   IDLE → RESOLVING_URL → BUFFERING → PLAYING ⇄ PAUSED
 *                                    ↓
 *                              RECOVERING → PLAYING / FAILED → IDLE
 *                                    ↓
 *                              COMPLETED → (next) → RESOLVING_URL
 */
enum class PlaybackPhase {
    /** 无歌曲或已停止 */
    IDLE,

    /** 正在获取歌曲 URL */
    RESOLVING_URL,

    /** 预缓冲或恢复中缓冲 */
    BUFFERING,

    /** 正常播放 */
    PLAYING,

    /** 暂停 */
    PAUSED,

    /** 卡顿恢复中 */
    RECOVERING,

    /** 播放完成（等待切歌） */
    COMPLETED,

    /** 播放失败（不可恢复） */
    FAILED,
}
