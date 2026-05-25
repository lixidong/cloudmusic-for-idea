package org.lixidong.musicplugin.api

internal object NeteaseEndpoints {
    const val BASE = "https://music.163.com"

    const val LOGIN_QR_KEY = "/weapi/login/qrcode/unikey"
    const val LOGIN_QR_CHECK = "/weapi/login/qrcode/client/login"
    const val LOGIN_CELLPHONE = "/weapi/login/cellphone"
    const val SMS_SEND = "/weapi/sms/captcha/sent"
    const val USER_INFO = "/weapi/w/nuser/account/get"
    const val USER_PLAYLIST = "/weapi/user/playlist"
    const val PLAYLIST_DETAIL = "/weapi/v6/playlist/detail"
    const val SONG_URL = "/weapi/song/enhance/player/url/v1"
    const val SONG_LYRIC = "/weapi/song/lyric"
    const val SONG_LIKE = "/weapi/song/like"
    const val SONG_LIKED_LIST = "/weapi/song/liked/get"
    const val FM_TRASH = "/weapi/radio/trash/add"
    const val HEART_MODE = "/weapi/playmode/intelligence/list"

    const val SEARCH = "/weapi/cloudsearch/get/web"
    const val PERSONAL_FM = "/weapi/v1/radio/get"
    const val RECOMMEND_SONGS = "/weapi/v3/discovery/recommend/songs"
    const val ARTIST_TOP_SONGS = "/weapi/v1/artist"   // append `/{id}`
    const val ALBUM_DETAIL = "/weapi/v1/album"        // append `/{id}`
    const val SONG_DETAIL = "/weapi/v3/song/detail"

    const val QR_LOGIN_PAGE_PREFIX = "https://music.163.com/login?codekey="

    const val REFERER = "https://music.163.com"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
