package org.example.musicplugin.api

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import org.example.musicplugin.api.model.*
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class NeteaseApiException(message: String, val code: Int = -1) : RuntimeException(message)

/**
 * Blocking HTTP client for music.163.com weapi endpoints.
 * Callers should invoke from Dispatchers.IO or a background thread.
 */
internal class NeteaseApiClient private constructor() {

    companion object {
        @Volatile private var instance: NeteaseApiClient? = null
        fun getInstance(): NeteaseApiClient =
            instance ?: synchronized(this) {
                instance ?: NeteaseApiClient().also { instance = it }
            }

        fun md5Hex(text: String): String {
            val md = MessageDigest.getInstance("MD5")
            return md.digest(text.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }

    private val log = Logger.getInstance(NeteaseApiClient::class.java)
    private val http: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()
    private val gson = Gson()
    private val cookies = ConcurrentHashMap<String, String>()

    init {
        // Pretend to be the official PC desktop client to bypass web risk control (code 8821)
        cookies["os"] = "pc"
        cookies["appver"] = "8.20.20"
        cookies["osver"] = "Microsoft-Windows-10-Professional-build-19045-64bit"
        cookies["channel"] = "netease"
    }

    fun setCookies(map: Map<String, String>) {
        cookies.clear()
        for ((k, v) in map) if (v.isNotEmpty()) cookies[k] = v
    }

    fun clearCookies() = cookies.clear()
    fun snapshotCookies(): Map<String, String> = cookies.toMap()
    fun musicU(): String? = cookies["MUSIC_U"]
    fun isLoggedIn(): Boolean = !cookies["MUSIC_U"].isNullOrEmpty()

    private fun weapi(path: String, payload: Map<String, Any?>): String {
        val bodyMap = payload.toMutableMap()
        val csrf = cookies["__csrf"] ?: ""
        bodyMap.putIfAbsent("csrf_token", csrf)

        val json = gson.toJson(bodyMap)
        val enc = NeteaseCrypto.encrypt(json)
        val form = "params=${URLEncoder.encode(enc.params, StandardCharsets.UTF_8)}" +
            "&encSecKey=${URLEncoder.encode(enc.encSecKey, StandardCharsets.UTF_8)}"

        val url = NeteaseEndpoints.BASE + path + if (csrf.isNotEmpty()) "?csrf_token=$csrf" else ""

        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("User-Agent", NeteaseEndpoints.USER_AGENT)
            .header("Referer", NeteaseEndpoints.REFERER)
            .header("Origin", NeteaseEndpoints.REFERER)

        val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)

        val req = builder.POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        absorbCookies(resp)
        if (resp.statusCode() / 100 != 2) {
            throw NeteaseApiException("HTTP ${resp.statusCode()}", resp.statusCode())
        }
        return resp.body()
    }

    private fun absorbCookies(resp: HttpResponse<*>) {
        for (line in resp.headers().allValues("set-cookie")) {
            absorbCookieLine(line)
        }
    }

    private fun absorbCookieLine(line: String) {
        val firstPart = line.substringBefore(';')
        val eq = firstPart.indexOf('=')
        if (eq <= 0) return
        val k = firstPart.substring(0, eq).trim()
        val v = firstPart.substring(eq + 1).trim()
        if (v.isEmpty() || v.equals("EXPIRED", ignoreCase = true) || v.equals("deleted", ignoreCase = true)) {
            cookies.remove(k)
        } else {
            cookies[k] = v
        }
    }

    /** Some endpoints embed `cookie: "MUSIC_U=xxx; __csrf=yyy; ..."` inside JSON body. */
    private fun absorbCookieString(raw: String?) {
        if (raw.isNullOrBlank()) return
        for (chunk in raw.split(';', ',')) {
            val s = chunk.trim()
            if (s.isEmpty()) continue
            val eq = s.indexOf('=')
            if (eq <= 0) continue
            val k = s.substring(0, eq).trim()
            val v = s.substring(eq + 1).trim()
            if (k in setOf("MUSIC_U", "MUSIC_A", "__csrf", "__remember_me", "NMTID")) {
                if (v.isNotEmpty() && v != "EXPIRED") cookies[k] = v
            }
        }
    }

    // ---------- Auth ----------

    fun fetchQrUnikey(): String {
        val body = weapi(NeteaseEndpoints.LOGIN_QR_KEY, mapOf("type" to 1))
        log.info("fetchQrUnikey resp: $body")
        val parsed = gson.fromJson(body, QrUnikeyResp::class.java)
        if (parsed.code != 200 || parsed.unikey.isNullOrEmpty()) {
            throw NeteaseApiException("拿不到二维码 key (code=${parsed.code}, body=${body.take(200)})", parsed.code)
        }
        return parsed.unikey
    }

    /** Returns the raw check response. code: 800 expired, 801 waiting, 802 scanned, 803 success. */
    fun checkQrLogin(unikey: String): QrCheckResp {
        val body = weapi(NeteaseEndpoints.LOGIN_QR_CHECK, mapOf("key" to unikey, "type" to 1))
        log.info("checkQrLogin resp: $body")
        val parsed = gson.fromJson(body, QrCheckResp::class.java)
        if (parsed.code == 803) absorbCookieString(parsed.cookie)
        return parsed
    }

    fun loginByPassword(phone: String, plainPassword: String): UserProfile {
        val md5 = md5Hex(plainPassword)
        val body = weapi(
            NeteaseEndpoints.LOGIN_CELLPHONE,
            mapOf(
                "phone" to phone,
                "countrycode" to "86",
                "password" to md5,
                "rememberLogin" to "true"
            )
        )
        val parsed = gson.fromJson(body, CellphoneLoginResp::class.java)
        if (parsed.code != 200) {
            throw NeteaseApiException(parsed.message ?: parsed.msg ?: "登录失败", parsed.code)
        }
        absorbCookieString(parsed.cookie)
        return parsed.profile.toDomain()
    }

    fun sendSmsCaptcha(phone: String) {
        val body = weapi(
            NeteaseEndpoints.SMS_SEND,
            mapOf("cellphone" to phone, "ctcode" to "86")
        )
        val parsed = gson.fromJson(body, SmsSentResp::class.java)
        if (parsed.code != 200) {
            throw NeteaseApiException(parsed.message ?: parsed.msg ?: "发送失败", parsed.code)
        }
    }

    fun loginBySms(phone: String, captcha: String): UserProfile {
        val body = weapi(
            NeteaseEndpoints.LOGIN_CELLPHONE,
            mapOf(
                "phone" to phone,
                "countrycode" to "86",
                "captcha" to captcha,
                "rememberLogin" to "true"
            )
        )
        val parsed = gson.fromJson(body, CellphoneLoginResp::class.java)
        if (parsed.code != 200) {
            throw NeteaseApiException(parsed.message ?: parsed.msg ?: "登录失败", parsed.code)
        }
        absorbCookieString(parsed.cookie)
        return parsed.profile.toDomain()
    }

    fun fetchUserInfo(): UserProfile? {
        val body = weapi(NeteaseEndpoints.USER_INFO, emptyMap())
        val parsed = gson.fromJson(body, UserInfoResp::class.java)
        if (parsed.code != 200) return null
        return parsed.profile?.toDomain()
    }

    // ---------- Playlists & songs ----------

    fun fetchUserPlaylists(uid: Long, limit: Int = 100): List<PlaylistSummary> {
        val body = weapi(
            NeteaseEndpoints.USER_PLAYLIST,
            mapOf("uid" to uid, "limit" to limit, "offset" to 0)
        )
        val parsed = gson.fromJson(body, UserPlaylistResp::class.java)
        if (parsed.code != 200) throw NeteaseApiException("拉取歌单失败", parsed.code)
        return parsed.playlist.orEmpty().map {
            PlaylistSummary(it.id, it.name ?: "", it.coverImgUrl, it.trackCount ?: 0)
        }
    }

    fun fetchPlaylistDetail(playlistId: Long): List<Song> {
        val body = weapi(
            NeteaseEndpoints.PLAYLIST_DETAIL,
            mapOf("id" to playlistId, "n" to 100000, "s" to 8)
        )
        val parsed = gson.fromJson(body, PlaylistDetailResp::class.java)
        if (parsed.code != 200) throw NeteaseApiException("拉取歌单详情失败", parsed.code)
        return parsed.playlist?.tracks.orEmpty().map { it.toDomain() }
    }

    /** Returns the directly playable MP3 url, or null if not playable for the current user. */
    fun fetchSongUrl(songId: Long): String? {
        val body = weapi(
            NeteaseEndpoints.SONG_URL,
            mapOf("ids" to "[$songId]", "level" to "standard", "encodeType" to "mp3")
        )
        log.info("fetchSongUrl resp: $body")
        val parsed = gson.fromJson(body, SongUrlResp::class.java)
        if (parsed.code != 200) return null
        return parsed.data?.firstOrNull()?.url
    }

    /** Returns the raw LRC text, or empty string if there's none. */
    fun fetchLyric(songId: Long): String {
        val body = weapi(
            NeteaseEndpoints.SONG_LYRIC,
            mapOf("id" to songId, "lv" to -1, "kv" to -1, "tv" to -1)
        )
        val parsed = gson.fromJson(body, LyricResp::class.java)
        if (parsed.code != 200) return ""
        return parsed.lrc?.lyric ?: ""
    }

    fun fetchLikedSongIds(uid: Long): Set<Long> {
        val body = weapi(NeteaseEndpoints.SONG_LIKED_LIST, mapOf("uid" to uid))
        val parsed = gson.fromJson(body, LikedListResp::class.java)
        if (parsed.code != 200) return emptySet()
        return parsed.ids.orEmpty().toSet()
    }

    fun likeSong(songId: Long, like: Boolean): Boolean {
        val body = weapi(
            NeteaseEndpoints.SONG_LIKE,
            mapOf(
                "trackId" to songId,
                "like" to like.toString(),
                "time" to 25,
                "userid" to 0
            )
        )
        log.info("likeSong($songId, $like) resp: $body")
        val parsed = gson.fromJson(body, SimpleCodeResp::class.java)
        return parsed.code == 200
    }

    fun trashSong(songId: Long): Boolean {
        val body = weapi(
            NeteaseEndpoints.FM_TRASH,
            mapOf("songId" to songId, "alg" to "RT", "time" to 25)
        )
        log.info("trashSong($songId) resp: $body")
        val parsed = gson.fromJson(body, SimpleCodeResp::class.java)
        return parsed.code == 200
    }

    fun fetchHeartModeSongs(seedSongId: Long, playlistId: Long, count: Int = 20): List<Song> {
        val body = weapi(
            NeteaseEndpoints.HEART_MODE,
            mapOf(
                "songId" to seedSongId,
                "type" to "fromPlayOne",
                "playlistId" to playlistId,
                "startMusicId" to seedSongId,
                "count" to count
            )
        )
        log.info("fetchHeartModeSongs resp len=${body.length}")
        val parsed = gson.fromJson(body, HeartModeResp::class.java)
        if (parsed.code != 200) return emptyList()
        return parsed.data.orEmpty().mapNotNull { it.songInfo?.toDomain() }
    }
}

private fun ProfileInfo?.toDomain(): UserProfile {
    val safeId = this?.userId ?: 0L
    val safeName = this?.nickname ?: ""
    return UserProfile(safeId, safeName, this?.avatarUrl)
}

private fun TrackDto.toDomain(): Song {
    val artistList = artists.orEmpty().mapNotNull { it.name }.joinToString(" / ")
    return Song(
        id = id,
        name = name ?: "",
        artistNames = artistList,
        albumName = album?.name ?: "",
        albumPicUrl = album?.picUrl,
        durationMs = durationMs ?: 0L
    )
}
