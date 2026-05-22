package org.lixidong.musicplugin.api.model

import com.google.gson.annotations.SerializedName

internal data class QrUnikeyResp(val code: Int, val unikey: String?)

internal data class QrCheckResp(
    val code: Int,
    val message: String?,
    val cookie: String?,
    val nickname: String?,
    val avatarUrl: String?
)

internal data class CellphoneLoginResp(
    val code: Int,
    val msg: String?,
    val message: String?,
    val account: AccountInfo?,
    val profile: ProfileInfo?,
    val cookie: String?
)

internal data class AccountInfo(val id: Long?, val userName: String?)
internal data class ProfileInfo(val userId: Long?, val nickname: String?, val avatarUrl: String?)

internal data class SmsSentResp(val code: Int, val message: String?, val msg: String?)

internal data class UserInfoResp(val code: Int, val profile: ProfileInfo?, val account: AccountInfo?)

internal data class UserPlaylistResp(val code: Int, val playlist: List<PlaylistDto>?)
internal data class PlaylistDto(
    val id: Long,
    val name: String?,
    val coverImgUrl: String?,
    val trackCount: Int?
)

internal data class PlaylistDetailResp(val code: Int, val playlist: PlaylistDetailDto?)
internal data class PlaylistDetailDto(
    val id: Long,
    val name: String?,
    val coverImgUrl: String?,
    val trackIds: List<TrackIdDto>?,
    val tracks: List<TrackDto>?
)
internal data class TrackIdDto(val id: Long)
internal data class TrackDto(
    val id: Long,
    val name: String?,
    @SerializedName("ar") val artists: List<ArtistDto>?,
    @SerializedName("al") val album: AlbumDto?,
    @SerializedName("dt") val durationMs: Long?
)
internal data class ArtistDto(val id: Long, val name: String?)
internal data class AlbumDto(val id: Long, val name: String?, val picUrl: String?)

internal data class SongUrlResp(val code: Int, val data: List<SongUrlDto>?)
internal data class SongUrlDto(val id: Long, val url: String?, val br: Int?, val type: String?)

internal data class LyricResp(val code: Int, val lrc: LrcDto?, val tlyric: LrcDto?)
internal data class LrcDto(val lyric: String?)

internal data class SimpleCodeResp(val code: Int, val message: String?, val msg: String?)

internal data class LikedListResp(val code: Int, val ids: List<Long>?)

internal data class HeartModeResp(val code: Int, val data: List<HeartSongDto>?)
internal data class HeartSongDto(val songInfo: TrackDto?)
