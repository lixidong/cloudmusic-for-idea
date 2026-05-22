package org.example.musicplugin.api.model

data class Song(
    val id: Long,
    val name: String,
    val artistNames: String,
    val albumName: String,
    val albumPicUrl: String?,
    val durationMs: Long
) {
    val display: String get() = if (artistNames.isBlank()) name else "$artistNames - $name"
}

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val coverImgUrl: String?,
    val trackCount: Int
)

data class UserProfile(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String?
)

data class LyricLine(val timeMs: Long, val text: String)
