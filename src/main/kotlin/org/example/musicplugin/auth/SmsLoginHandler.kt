package org.example.musicplugin.auth

import org.example.musicplugin.api.NeteaseApiClient
import org.example.musicplugin.api.model.UserProfile

internal class SmsLoginHandler(private val api: NeteaseApiClient) {
    fun sendCode(phone: String) = api.sendSmsCaptcha(phone)
    fun login(phone: String, code: String): UserProfile = api.loginBySms(phone, code)
}
