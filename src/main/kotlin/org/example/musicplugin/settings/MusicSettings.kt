package org.example.musicplugin.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "NeteaseMusicSettings", storages = [Storage("netease-music.xml")])
internal class MusicSettings : PersistentStateComponent<MusicSettings.Data> {

    data class Data(
        var volumePercent: Int = 60,
        var loopMode: String = "LIST",
        var lastPlaylistId: Long = 0L,
        var enableStatusBar: Boolean = true,
        var statusBarTextWidth: Int = 220,
        var username: String = ""
    )

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        XmlSerializerUtil.copyBean(state, data)
    }

    companion object {
        fun getInstance(): MusicSettings = service()
    }
}
