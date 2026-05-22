package org.lixidong.musicplugin.auth

import org.lixidong.musicplugin.api.NeteaseApiClient
import org.lixidong.musicplugin.api.model.UserProfile

internal class PasswordLoginHandler(private val api: NeteaseApiClient) {
    fun login(phone: String, password: String): UserProfile = api.loginByPassword(phone, password)
}
