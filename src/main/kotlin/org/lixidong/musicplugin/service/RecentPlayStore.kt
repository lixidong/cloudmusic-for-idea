package org.lixidong.musicplugin.service

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.XmlSerializerUtil
import org.lixidong.musicplugin.api.model.Song

/**
 * Persists the recently-played songs as a bounded ring buffer.
 * Most-recent first. Persisted via PersistentStateComponent so it survives
 * IDE restarts.
 */
@Service(Service.Level.APP)
@State(name = "NeteaseMusicRecentPlays", storages = [Storage("netease-music-recent.xml")])
internal class RecentPlayStore : PersistentStateComponent<RecentPlayStore.Data> {

    class Data {
        var entries: MutableList<RecentEntry> = mutableListOf()
    }

    class RecentEntry {
        var id: Long = 0
        var name: String = ""
        var artistNames: String = ""
        var albumName: String = ""
        var albumPicUrl: String? = null
        var durationMs: Long = 0
        var playedAtMs: Long = 0
    }

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        XmlSerializerUtil.copyBean(state, data)
    }

    fun record(song: Song) {
        val now = System.currentTimeMillis()
        synchronized(data) {
            data.entries.removeAll { it.id == song.id }
            data.entries.add(0, RecentEntry().also {
                it.id = song.id
                it.name = song.name
                it.artistNames = song.artistNames
                it.albumName = song.albumName
                it.albumPicUrl = song.albumPicUrl
                it.durationMs = song.durationMs
                it.playedAtMs = now
            })
            while (data.entries.size > MAX_ENTRIES) data.entries.removeAt(data.entries.lastIndex)
        }
    }

    fun snapshot(): List<Song> = synchronized(data) {
        data.entries.map {
            Song(it.id, it.name, it.artistNames, it.albumName, it.albumPicUrl, it.durationMs)
        }
    }

    fun clear() = synchronized(data) { data.entries.clear() }

    companion object {
        private const val MAX_ENTRIES = 200
        fun getInstance(): RecentPlayStore = service()
    }
}
