package org.example.musicplugin.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import org.example.musicplugin.api.NeteaseApiClient
import org.example.musicplugin.api.model.PlaylistSummary
import org.example.musicplugin.api.model.Song
import org.example.musicplugin.auth.LoginStateListener
import org.example.musicplugin.auth.LoginTopics
import org.example.musicplugin.auth.NeteaseAuthService
import org.example.musicplugin.service.MusicPlayerService
import org.example.musicplugin.settings.MusicSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JList
import javax.swing.ListSelectionModel

internal class PlaylistPanel : JBPanel<JBPanel<*>>() {

    private val playlistsModel = DefaultListModel<PlaylistSummary>()
    private val songsModel = DefaultListModel<Song>()

    private val playlistsList = JBList(playlistsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val pl = value as? PlaylistSummary
                val text = pl?.let { "${it.name} (${it.trackCount})" } ?: value?.toString() ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }

    private val songsList = JBList(songsModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val s = value as? Song
                val text = s?.display ?: value?.toString() ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }

    private var loginConn: com.intellij.util.messages.MessageBusConnection? = null

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(4)
        val split = JBSplitter(true, 0.35f).apply {
            firstComponent = JBScrollPane(playlistsList)
            secondComponent = JBScrollPane(songsList)
        }
        add(split, BorderLayout.CENTER)

        playlistsList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                playlistsList.selectedValue?.let { loadPlaylistDetail(it.id) }
            }
        }

        songsList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val idx = songsList.selectedIndex
                    if (idx >= 0) {
                        val songs = (0 until songsModel.size()).map { songsModel.getElementAt(it) }
                        val pid = playlistsList.selectedValue?.id ?: 0L
                        MusicPlayerService.getInstance().loadPlaylist(songs, idx, autoPlay = true, playlistId = pid)
                    }
                }
            }
        })
    }

    override fun addNotify() {
        super.addNotify()
        loginConn = ApplicationManager.getApplication().messageBus.connect().also {
            it.subscribe(LoginTopics.LOGIN_STATE_CHANGED, LoginStateListener { loggedIn, _ ->
                if (loggedIn) refresh() else clearAll()
            })
        }
        if (NeteaseAuthService.getInstance().isLoggedIn()) refresh()
    }

    override fun removeNotify() {
        super.removeNotify()
        loginConn?.disconnect()
        loginConn = null
    }

    private fun refresh() {
        val profile = NeteaseAuthService.getInstance().currentProfile ?: return
        Thread({
            try {
                val list = NeteaseApiClient.getInstance().fetchUserPlaylists(profile.userId)
                ApplicationManager.getApplication().invokeLater {
                    playlistsModel.clear()
                    list.forEach { playlistsModel.addElement(it) }
                    val rememberedId = MusicSettings.getInstance().state.lastPlaylistId
                    val rememberedIdx = list.indexOfFirst { it.id == rememberedId }
                    val pick = if (rememberedIdx >= 0) rememberedIdx else 0
                    if (pick in 0 until playlistsModel.size()) {
                        playlistsList.selectedIndex = pick
                    }
                }
            } catch (_: Throwable) {
                // ignore — user will see empty list
            }
        }, "NeteaseMusic-Playlists").apply { isDaemon = true }.start()
    }

    private fun loadPlaylistDetail(playlistId: Long) {
        MusicSettings.getInstance().state.lastPlaylistId = playlistId
        Thread({
            try {
                val songs = NeteaseApiClient.getInstance().fetchPlaylistDetail(playlistId)
                ApplicationManager.getApplication().invokeLater {
                    songsModel.clear()
                    songs.forEach { songsModel.addElement(it) }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-PlaylistDetail").apply { isDaemon = true }.start()
    }

    private fun clearAll() {
        playlistsModel.clear()
        songsModel.clear()
    }
}
