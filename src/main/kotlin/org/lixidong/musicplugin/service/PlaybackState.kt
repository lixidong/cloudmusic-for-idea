package org.lixidong.musicplugin.service

import org.lixidong.musicplugin.api.model.Song

data class PlaybackState(
    val currentSong: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volumePercent: Int = 60,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val currentPlaylistId: Long = 0L,
    val isCurrentLiked: Boolean = false,
    val isHeartMode: Boolean = false
)
