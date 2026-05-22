package org.example.musicplugin.auth

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.ide.passwordSafe.PasswordSafe

internal object CookieStore {
    private const val SERVICE = "NeteaseMusicPlugin"
    private const val KEY = "cookies"
    private val attrs = CredentialAttributes(SERVICE, KEY)
    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, String>>() {}.type

    fun load(): Map<String, String> {
        val raw = PasswordSafe.instance.getPassword(attrs) ?: return emptyMap()
        return try {
            gson.fromJson<Map<String, String>>(raw, mapType)?.filterValues { it.isNotEmpty() } ?: emptyMap()
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun save(cookies: Map<String, String>) {
        if (cookies.isEmpty()) {
            clear()
        } else {
            PasswordSafe.instance.setPassword(attrs, gson.toJson(cookies))
        }
    }

    fun clear() {
        PasswordSafe.instance.setPassword(attrs, null)
    }
}
