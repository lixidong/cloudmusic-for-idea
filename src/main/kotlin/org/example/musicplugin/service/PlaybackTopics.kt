package org.example.musicplugin.service

import com.intellij.util.messages.Topic

fun interface PlaybackStateListener {
    fun onStateChanged(state: PlaybackState)
}

object PlaybackTopics {
    @JvmField
    val PLAYBACK_STATE_CHANGED: Topic<PlaybackStateListener> =
        Topic.create("NeteaseMusic.PlaybackStateChanged", PlaybackStateListener::class.java)
}
