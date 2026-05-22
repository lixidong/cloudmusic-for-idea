package org.example.musicplugin.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.messages.MessageBusConnection
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import org.example.musicplugin.service.LyricService
import org.example.musicplugin.service.MusicPlayerService
import org.example.musicplugin.service.PlaybackState
import org.example.musicplugin.service.PlaybackStateListener
import org.example.musicplugin.service.PlaybackTopics
import org.example.musicplugin.ui.icons.MusicIcons
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JProgressBar
import javax.swing.JSlider
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

internal class PlayerView : JBPanel<JBPanel<*>>(), PlaybackStateListener {

    private val albumLabel = JBLabel().apply {
        preferredSize = Dimension(JBUI.scale(180), JBUI.scale(180))
        horizontalAlignment = SwingConstants.CENTER
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val titleLabel = JBLabel(" ").apply {
        font = JBFont.label().asBold().biggerOn(2f)
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val artistLabel = JBLabel(" ").apply {
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val lyricLabel = JBLabel(" ").apply {
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val progressBar = JProgressBar(0, 1000)
    private val timeLabel = JBLabel("00:00 / 00:00")
    private val prevButton = JButton(MusicIcons.Prev)
    private val playPauseButton = JButton(MusicIcons.Play)
    private val nextButton = JButton(MusicIcons.Next)
    private val volumeSlider = JSlider(0, 100, 60).apply {
        preferredSize = Dimension(JBUI.scale(120), preferredSize.height)
    }

    private val playlistPanel = PlaylistPanel().apply {
        preferredSize = Dimension(JBUI.scale(240), JBUI.scale(0))
    }

    private var connection: MessageBusConnection? = null
    private var currentAlbumUrl: String? = null
    private var ignoreSliderChange = false

    init {
        layout = BorderLayout()
        border = JBUI.Borders.empty(8)
        add(playlistPanel, BorderLayout.WEST)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildControls(), BorderLayout.SOUTH)

        prevButton.addActionListener { MusicPlayerService.getInstance().previous() }
        playPauseButton.addActionListener { MusicPlayerService.getInstance().togglePlayPause() }
        nextButton.addActionListener { MusicPlayerService.getInstance().next() }
        volumeSlider.addChangeListener {
            if (ignoreSliderChange) return@addChangeListener
            MusicPlayerService.getInstance().setVolume(volumeSlider.value)
        }
    }

    private fun buildCenter(): JComponent {
        val panel = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 16)
        }
        panel.add(albumLabel)
        panel.add(spacer(8))
        panel.add(titleLabel)
        panel.add(spacer(4))
        panel.add(artistLabel)
        panel.add(spacer(8))
        panel.add(lyricLabel)
        return panel
    }

    private fun buildControls(): JComponent {
        val outer = JBPanel<JBPanel<*>>().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(4, 12)
        }
        val progressRow = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0))
        progressRow.add(progressBar, BorderLayout.CENTER)
        progressRow.add(timeLabel, BorderLayout.EAST)

        val buttons = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.CENTER, JBUI.scale(8), 0)).apply {
            add(prevButton)
            add(playPauseButton)
            add(nextButton)
            add(JBLabel(MusicIcons.Volume))
            add(volumeSlider)
        }

        outer.add(progressRow)
        outer.add(spacer(4))
        outer.add(buttons)
        return outer
    }

    private fun spacer(h: Int): JComponent =
        JBPanel<JBPanel<*>>().apply {
            preferredSize = Dimension(1, JBUI.scale(h))
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(h))
            isOpaque = false
        }

    override fun addNotify() {
        super.addNotify()
        connection = ApplicationManager.getApplication().messageBus.connect().also {
            it.subscribe(PlaybackTopics.PLAYBACK_STATE_CHANGED, this)
        }
        applyState(MusicPlayerService.getInstance().currentState())
    }

    override fun removeNotify() {
        super.removeNotify()
        connection?.disconnect()
        connection = null
    }

    override fun onStateChanged(state: PlaybackState) {
        if (SwingUtilities.isEventDispatchThread()) applyState(state)
        else ApplicationManager.getApplication().invokeLater { applyState(state) }
    }

    private fun applyState(state: PlaybackState) {
        val song = state.currentSong
        titleLabel.text = song?.name ?: " "
        artistLabel.text = song?.artistNames ?: " "
        if (currentAlbumUrl != song?.albumPicUrl) {
            currentAlbumUrl = song?.albumPicUrl
            albumLabel.icon = null
            AlbumArtCache.get(song?.albumPicUrl, JBUI.scale(180)) { icon ->
                if (currentAlbumUrl == song?.albumPicUrl) albumLabel.icon = icon
            }
        }
        progressBar.value =
            if (state.durationMs > 0) ((state.positionMs.coerceAtLeast(0) * 1000L) / state.durationMs).toInt().coerceIn(0, 1000)
            else 0
        timeLabel.text = "${fmt(state.positionMs)} / ${fmt(state.durationMs)}"
        playPauseButton.icon = if (state.isPlaying) MusicIcons.Pause else MusicIcons.Play
        if (volumeSlider.value != state.volumePercent) {
            ignoreSliderChange = true
            try { volumeSlider.value = state.volumePercent } finally { ignoreSliderChange = false }
        }
        lyricLabel.text = LyricService.getInstance().currentLineFor(state.positionMs) ?: " "
    }

    private fun fmt(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
