package org.lixidong.musicplugin.auth

import org.lixidong.musicplugin.api.NeteaseApiClient
import org.lixidong.musicplugin.api.model.UserProfile

internal class SmsLoginHandler(private val api: NeteaseApiClient) {
    fun sendCode(phone: String) = api.sendSmsCaptcha(phone)
    fun login(phone: String, code: String): UserProfile = api.loginBySms(phone, code)
}
