package org.lixidong.musicplugin.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import org.lixidong.musicplugin.api.NeteaseApiClient
import org.lixidong.musicplugin.api.model.AlbumSummary
import org.lixidong.musicplugin.api.model.ArtistSummary
import org.lixidong.musicplugin.api.model.PlaylistSummary
import org.lixidong.musicplugin.api.model.Song
import org.lixidong.musicplugin.auth.LoginStateListener
import org.lixidong.musicplugin.auth.LoginTopics
import org.lixidong.musicplugin.auth.NeteaseAuthService
import org.lixidong.musicplugin.service.MusicPlayerService
import org.lixidong.musicplugin.service.RecentPlayStore
import org.lixidong.musicplugin.settings.MusicSettings
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JList
import javax.swing.ListSelectionModel

internal class PlaylistPanel : JBPanel<JBPanel<*>>() {

    /** Items rendered in the upper (sidebar) list. */
    private sealed class SidebarItem {
        object RecentPlay : SidebarItem()
        object Liked : SidebarItem()
        object DailyRecommend : SidebarItem()
        object PersonalFm : SidebarItem()
        data class UserPlaylist(val summary: PlaylistSummary) : SidebarItem()

        fun display(): String = when (this) {
            RecentPlay -> "★ 最近播放"
            Liked -> "♥ 我喜欢的音乐"
            DailyRecommend -> "✪ 每日推荐"
            PersonalFm -> "📻 私人 FM"
            is UserPlaylist -> "${summary.name} (${summary.trackCount})"
        }
    }

    /** Items rendered in the lower (content) list. */
    private sealed class ContentRow {
        data class SongRow(val song: Song) : ContentRow()
        data class PlaylistRow(val playlist: PlaylistSummary) : ContentRow()
        data class ArtistRow(val artist: ArtistSummary) : ContentRow()
        data class AlbumRow(val album: AlbumSummary) : ContentRow()

        fun display(): String = when (this) {
            is SongRow -> song.display
            is PlaylistRow -> "[歌单] ${playlist.name} (${playlist.trackCount})"
            is ArtistRow -> "[歌手] ${artist.name}"
            is AlbumRow -> "[专辑] ${album.name}${if (album.artistName.isNotBlank()) " — ${album.artistName}" else ""}"
        }
    }

    private val sidebarModel = DefaultListModel<SidebarItem>()
    private val contentModel = DefaultListModel<ContentRow>()

    private val sidebarList = JBList(sidebarModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val text = (value as? SidebarItem)?.display() ?: value?.toString() ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }

    private val contentList = JBList(contentModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int,
                isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val text = (value as? ContentRow)?.display() ?: value?.toString() ?: ""
                return super.getListCellRendererComponent(list, text, index, isSelected, cellHasFocus)
            }
        }
    }

    private val searchField = JBTextField(16)
    private val searchTypeBox = JComboBox(arrayOf("歌曲", "歌单", "歌手", "专辑"))
    private val searchButton = JButton("搜索")

    private var loginConn: com.intellij.util.messages.MessageBusConnection? = null

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(4)

        val topBar = JBPanel<JBPanel<*>>().apply {
            layout = FlowLayout(FlowLayout.LEFT, 4, 2)
            add(searchField)
            add(searchTypeBox)
            add(searchButton)
        }
        add(topBar, BorderLayout.NORTH)

        val split = JBSplitter(true, 0.35f).apply {
            firstComponent = JBScrollPane(sidebarList)
            secondComponent = JBScrollPane(contentList)
        }
        add(split, BorderLayout.CENTER)

        searchButton.addActionListener { triggerSearch() }
        searchField.addActionListener { triggerSearch() }

        sidebarList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                when (val item = sidebarList.selectedValue) {
                    is SidebarItem.RecentPlay -> showRecentPlay()
                    is SidebarItem.Liked -> showLiked()
                    is SidebarItem.DailyRecommend -> showDailyRecommend()
                    is SidebarItem.PersonalFm -> startPersonalFm()
                    is SidebarItem.UserPlaylist -> loadPlaylistDetail(item.summary.id)
                    null -> Unit
                }
            }
        }

        contentList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) handleContentDoubleClick()
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
        else seedSidebarWithVirtualEntriesOnly()
    }

    override fun removeNotify() {
        super.removeNotify()
        loginConn?.disconnect()
        loginConn = null
    }

    private fun refresh() {
        seedSidebarWithVirtualEntriesOnly()
        val profile = NeteaseAuthService.getInstance().currentProfile ?: return
        Thread({
            try {
                val list = NeteaseApiClient.getInstance().fetchUserPlaylists(profile.userId)
                ApplicationManager.getApplication().invokeLater {
                    // keep the leading virtual entries; append user playlists
                    val virtualCount = sidebarModel.size()
                    list.forEach { sidebarModel.addElement(SidebarItem.UserPlaylist(it)) }
                    val rememberedId = MusicSettings.getInstance().state.lastPlaylistId
                    val rememberedIdx = (0 until sidebarModel.size()).firstOrNull { idx ->
                        val item = sidebarModel.getElementAt(idx)
                        item is SidebarItem.UserPlaylist && item.summary.id == rememberedId
                    }
                    val pick = rememberedIdx ?: virtualCount
                    if (pick in 0 until sidebarModel.size()) {
                        sidebarList.selectedIndex = pick
                    }
                }
            } catch (_: Throwable) {
                // ignore — user will see only virtual entries
            }
        }, "NeteaseMusic-Playlists").apply { isDaemon = true }.start()
    }

    private fun seedSidebarWithVirtualEntriesOnly() {
        sidebarModel.clear()
        sidebarModel.addElement(SidebarItem.RecentPlay)
        sidebarModel.addElement(SidebarItem.Liked)
        sidebarModel.addElement(SidebarItem.DailyRecommend)
        sidebarModel.addElement(SidebarItem.PersonalFm)
    }

    private fun loadPlaylistDetail(playlistId: Long) {
        MusicSettings.getInstance().state.lastPlaylistId = playlistId
        Thread({
            try {
                val songs = NeteaseApiClient.getInstance().fetchPlaylistDetail(playlistId)
                ApplicationManager.getApplication().invokeLater { showSongs(songs) }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-PlaylistDetail").apply { isDaemon = true }.start()
    }

    private fun showRecentPlay() {
        showSongs(RecentPlayStore.getInstance().snapshot())
    }

    private fun showLiked() {
        val uid = NeteaseAuthService.getInstance().currentProfile?.userId ?: return
        Thread({
            try {
                val ids = NeteaseApiClient.getInstance().fetchLikedSongIds(uid)
                val songs = NeteaseApiClient.getInstance().fetchSongDetails(ids)
                ApplicationManager.getApplication().invokeLater { showSongs(songs) }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-Liked").apply { isDaemon = true }.start()
    }

    private fun showDailyRecommend() {
        Thread({
            try {
                val songs = NeteaseApiClient.getInstance().fetchDailyRecommendSongs()
                ApplicationManager.getApplication().invokeLater { showSongs(songs) }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-DailyRecommend").apply { isDaemon = true }.start()
    }

    private fun startPersonalFm() {
        Thread({
            try {
                val songs = NeteaseApiClient.getInstance().fetchPersonalFmSongs()
                ApplicationManager.getApplication().invokeLater {
                    showSongs(songs)
                    if (songs.isNotEmpty()) {
                        MusicPlayerService.getInstance().loadPersonalFm(songs)
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-PersonalFM").apply { isDaemon = true }.start()
    }

    private fun showSongs(songs: List<Song>) {
        contentModel.clear()
        songs.forEach { contentModel.addElement(ContentRow.SongRow(it)) }
    }

    private fun triggerSearch() {
        val keyword = searchField.text?.trim().orEmpty()
        if (keyword.isEmpty()) return
        val type = when (searchTypeBox.selectedIndex) {
            0 -> 1
            1 -> 1000
            2 -> 100
            3 -> 10
            else -> 1
        }
        Thread({
            try {
                val results = NeteaseApiClient.getInstance().search(keyword, type)
                ApplicationManager.getApplication().invokeLater {
                    contentModel.clear()
                    when (type) {
                        1 -> results.songs.forEach { contentModel.addElement(ContentRow.SongRow(it)) }
                        1000 -> results.playlists.forEach { contentModel.addElement(ContentRow.PlaylistRow(it)) }
                        100 -> results.artists.forEach { contentModel.addElement(ContentRow.ArtistRow(it)) }
                        10 -> results.albums.forEach { contentModel.addElement(ContentRow.AlbumRow(it)) }
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-Search").apply { isDaemon = true }.start()
    }

    private fun handleContentDoubleClick() {
        val idx = contentList.selectedIndex
        if (idx < 0) return
        val row = contentModel.getElementAt(idx) ?: return
        when (row) {
            is ContentRow.SongRow -> {
                val songs = (0 until contentModel.size()).mapNotNull {
                    (contentModel.getElementAt(it) as? ContentRow.SongRow)?.song
                }
                val songIdx = songs.indexOfFirst { it.id == row.song.id }.coerceAtLeast(0)
                MusicPlayerService.getInstance().loadPlaylist(songs, songIdx, autoPlay = true, playlistId = 0L)
            }
            is ContentRow.PlaylistRow -> loadPlaylistAndPlay(row.playlist.id)
            is ContentRow.ArtistRow -> loadArtistAndPlay(row.artist.id)
            is ContentRow.AlbumRow -> loadAlbumAndPlay(row.album.id)
        }
    }

    private fun loadPlaylistAndPlay(playlistId: Long) {
        Thread({
            try {
                val songs = NeteaseApiClient.getInstance().fetchPlaylistDetail(playlistId)
                ApplicationManager.getApplication().invokeLater {
                    showSongs(songs)
                    if (songs.isNotEmpty()) {
                        MusicPlayerService.getInstance().loadPlaylist(songs, 0, autoPlay = true, playlistId = playlistId)
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-PlaylistExpand").apply { isDaemon = true }.start()
    }

    private fun loadArtistAndPlay(artistId: Long) {
        Thread({
            try {
                val songs = NeteaseApiClient.getInstance().fetchArtistTopSongs(artistId)
                ApplicationManager.getApplication().invokeLater {
                    showSongs(songs)
                    if (songs.isNotEmpty()) {
                        MusicPlayerService.getInstance().loadPlaylist(songs, 0, autoPlay = true, playlistId = 0L)
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-ArtistExpand").apply { isDaemon = true }.start()
    }

    private fun loadAlbumAndPlay(albumId: Long) {
        Thread({
            try {
                val songs = NeteaseApiClient.getInstance().fetchAlbumSongs(albumId)
                ApplicationManager.getApplication().invokeLater {
                    showSongs(songs)
                    if (songs.isNotEmpty()) {
                        MusicPlayerService.getInstance().loadPlaylist(songs, 0, autoPlay = true, playlistId = 0L)
                    }
                }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-AlbumExpand").apply { isDaemon = true }.start()
    }

    private fun clearAll() {
        seedSidebarWithVirtualEntriesOnly()
        contentModel.clear()
    }
}
