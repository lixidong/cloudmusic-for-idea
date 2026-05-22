package org.example.musicplugin.auth

import org.example.musicplugin.api.NeteaseApiClient
import org.example.musicplugin.api.model.UserProfile

internal class PasswordLoginHandler(private val api: NeteaseApiClient) {
    fun login(phone: String, password: String): UserProfile = api.loginByPassword(phone, password)
}
