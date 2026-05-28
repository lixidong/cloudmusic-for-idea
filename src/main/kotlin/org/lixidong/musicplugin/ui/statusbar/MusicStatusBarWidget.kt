package org.lixidong.musicplugin.ui.statusbar

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBar
import com.intellij.ui.InplaceButton
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBUI
import org.lixidong.musicplugin.MusicBundle
import org.lixidong.musicplugin.api.model.Song
import org.lixidong.musicplugin.service.LyricService
import org.lixidong.musicplugin.service.MusicPlayerService
import org.lixidong.musicplugin.service.PlaybackState
import org.lixidong.musicplugin.service.PlaybackStateListener
import org.lixidong.musicplugin.service.PlaybackTopics
import org.lixidong.musicplugin.settings.MusicSettings
import org.lixidong.musicplugin.ui.icons.MusicIcons
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSlider
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

internal class MusicStatusBarWidget(private val project: Project) : CustomStatusBarWidget, PlaybackStateListener {

    private companion object {
        private const val TITLE_GRACE_MS = 2000L
    }

    private val panel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, JBUI.scale(4))
    }

    private val lyricLabel = ScrollingLyricLabel(MusicSettings.getInstance().state.statusBarTextWidth)

    private val prevButton = InplaceButton(MusicBundle.message("action.prev"), MusicIcons.Prev) {
        MusicPlayerService.getInstance().previous()
    }
    private val playPauseButton = InplaceButton(MusicBundle.message("action.playPause"), MusicIcons.Play) {
        MusicPlayerService.getInstance().togglePlayPause()
    }
    private val nextButton = InplaceButton(MusicBundle.message("action.next"), MusicIcons.Next) {
        MusicPlayerService.getInstance().next()
    }
    private val listButton = InplaceButton("当前队列", MusicIcons.List) { showQueuePopup() }
    private val heartModeButton = InplaceButton("心动模式", MusicIcons.HeartModeOff) {
        MusicPlayerService.getInstance().toggleHeartMode()
    }
    private val likeButton = InplaceButton("喜欢", MusicIcons.LikeOff) {
        MusicPlayerService.getInstance().toggleLikeCurrent()
    }
    private val dislikeButton = InplaceButton("不喜欢（加入垃圾桶并跳过）", MusicIcons.Dislike) {
        MusicPlayerService.getInstance().dislikeCurrent()
    }
    private val volumeButton = InplaceButton("音量", MusicIcons.Volume) { showVolumePopup() }

    private var connection: MessageBusConnection? = null

    init {
        val controls = JPanel(GridBagLayout()).apply { isOpaque = false }
        val buttons = listOf(
            prevButton, playPauseButton, nextButton, listButton,
            heartModeButton, likeButton, dislikeButton, volumeButton
        )
        buttons.forEachIndexed { i, btn ->
            controls.add(
                btn,
                GridBagConstraints().apply {
                    gridx = i
                    gridy = 0
                    weighty = 1.0
                    anchor = GridBagConstraints.CENTER
                    insets = Insets(0, if (i == 0) 0 else JBUI.scale(7), 0, 0)
                }
            )
        }
        panel.add(lyricLabel, BorderLayout.CENTER)
        panel.add(controls, BorderLayout.EAST)
        // 8 inline buttons (~22px each) + 7 gaps of 7px + outer paddings + lyric area
        val w = MusicSettings.getInstance().state.statusBarTextWidth + 8 * 22 + 7 * 7 + 32
        panel.preferredSize = Dimension(JBUI.scale(w), JBUI.scale(26))

    }

    override fun ID(): String = "NeteaseMusicWidget"

    override fun getComponent(): JComponent = panel

    override fun install(statusBar: StatusBar) {
        connection = ApplicationManager.getApplication().messageBus.connect().also {
            it.subscribe(PlaybackTopics.PLAYBACK_STATE_CHANGED, this)
        }
        applyState(MusicPlayerService.getInstance().currentState())
    }

    override fun dispose() {
        connection?.disconnect()
        connection = null
    }

    override fun onStateChanged(state: PlaybackState) {
        if (SwingUtilities.isEventDispatchThread()) {
            applyState(state)
        } else {
            ApplicationManager.getApplication().invokeLater { applyState(state) }
        }
    }

    private fun applyState(state: PlaybackState) {
        lyricLabel.update(computeText(state), state.isPlaying)
        playPauseButton.setIcon(if (state.isPlaying) MusicIcons.Pause else MusicIcons.Play)
        likeButton.setIcon(if (state.isCurrentLiked) MusicIcons.LikeOn else MusicIcons.LikeOff)
        heartModeButton.setIcon(if (state.isHeartMode) MusicIcons.HeartModeOn else MusicIcons.HeartModeOff)
        // 更新歌词区域tooltip（只在歌词标签上，不影响按钮区域）
        val song = state.currentSong
        lyricLabel.toolTipText = song?.display ?: ""
    }

    private fun computeText(state: PlaybackState): String {
        val song = state.currentSong ?: return MusicBundle.message("statusbar.idle")
        // Show song title for the first few seconds of playback before switching to lyrics,
        // so the song info is readable instead of flashing past.
        if (state.positionMs < TITLE_GRACE_MS) return song.display
        val line = LyricService.getInstance().currentLineFor(state.positionMs)
        if (!line.isNullOrEmpty()) return line
        return song.display
    }

    private fun showVolumePopup() {
        val player = MusicPlayerService.getInstance()
        val slider = JSlider(0, 100, player.currentState().volumePercent).apply {
            preferredSize = Dimension(JBUI.scale(180), JBUI.scale(40))
            addChangeListener { player.setVolume(value) }
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(slider, slider)
            .setRequestFocus(true)
            .setResizable(false)
            .createPopup()
            .showUnderneathOf(volumeButton)
    }

    private fun showQueuePopup() {
        val player = MusicPlayerService.getInstance()
        val state = player.currentState()
        val queue = state.queue
        if (queue.isEmpty()) {
            JBPopupFactory.getInstance()
                .createMessage("当前队列为空——请先在 CloudMusic 工具窗里挑一个歌单")
                .showUnderneathOf(listButton)
            return
        }
        val model = DefaultListModel<Song>().apply { queue.forEach { addElement(it) } }
        val list = JBList(model).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    l: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val s = value as? Song
                    val playing = state.currentIndex == index
                    val prefix = if (playing) "▶ " else "  "
                    val text = s?.let { prefix + it.display } ?: value?.toString() ?: ""
                    return super.getListCellRendererComponent(l, text, index, isSelected, cellHasFocus)
                }
            }
            selectedIndex = state.currentIndex.coerceAtLeast(0)
            visibleRowCount = 12
        }
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(JBScrollPane(list), list)
            .setRequestFocus(true)
            .setResizable(true)
            .setTitle("当前队列（${queue.size} 首）")
            .setMinSize(Dimension(JBUI.scale(280), JBUI.scale(220)))
            .createPopup()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val idx = list.selectedIndex
                    if (idx >= 0) {
                        MusicPlayerService.getInstance().playFromQueueAt(idx)
                        popup.cancel()
                    }
                }
            }
        })
        popup.showUnderneathOf(listButton)
    }
}
