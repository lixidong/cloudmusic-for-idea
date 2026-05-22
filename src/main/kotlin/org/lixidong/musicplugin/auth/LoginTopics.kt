package org.lixidong.musicplugin.auth

import com.intellij.util.messages.Topic
import org.lixidong.musicplugin.api.model.UserProfile

fun interface LoginStateListener {
    fun onLoginStateChanged(loggedIn: Boolean, profile: UserProfile?)
}

object LoginTopics {
    @JvmField
    val LOGIN_STATE_CHANGED: Topic<LoginStateListener> =
        Topic.create("NeteaseMusic.LoginStateChanged", LoginStateListener::class.java)
}
