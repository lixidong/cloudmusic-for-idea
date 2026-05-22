package org.example.musicplugin.ui.toolwindow

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import org.example.musicplugin.MusicBundle
import org.example.musicplugin.auth.NeteaseAuthService
import org.example.musicplugin.auth.QrLoginSession
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.BoxLayout
import javax.swing.ImageIcon
import javax.swing.JButton

internal class QrLoginPanel : JBPanel<JBPanel<*>>() {

    private val qrLabel = JBLabel().apply {
        preferredSize = Dimension(JBUI.scale(200), JBUI.scale(200))
        horizontalAlignment = JBLabel.CENTER
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val statusLabel = JBLabel(MusicBundle.message("login.qr.waiting")).apply {
        alignmentX = Component.CENTER_ALIGNMENT
    }
    private val refreshButton = JButton(MusicBundle.message("login.qr.refresh")).apply {
        alignmentX = Component.CENTER_ALIGNMENT
    }

    private var session: QrLoginSession? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(20)
        add(qrLabel)
        add(JBUI.Borders.emptyTop(8).let { JBLabel(" ") })
        add(statusLabel)
        add(JBLabel(" "))
        add(refreshButton)
        refreshButton.addActionListener { startSession() }
    }

    override fun addNotify() {
        super.addNotify()
        if (session == null) startSession()
    }

    override fun removeNotify() {
        super.removeNotify()
        session?.cancel()
        session = null
    }

    private fun startSession() {
        session?.cancel()
        qrLabel.icon = null
        statusLabel.text = MusicBundle.message("login.qr.waiting")
        session = NeteaseAuthService.getInstance().startQrLogin { state ->
            when (state) {
                is QrLoginSession.State.Ready -> qrLabel.icon = ImageIcon(state.qrImage)
                QrLoginSession.State.Waiting -> statusLabel.text = MusicBundle.message("login.qr.waiting")
                QrLoginSession.State.Scanned -> statusLabel.text = MusicBundle.message("login.qr.scanned")
                QrLoginSession.State.Expired -> statusLabel.text = MusicBundle.message("login.qr.expired")
                is QrLoginSession.State.Error -> statusLabel.text = MusicBundle.message("error.qrFailed", state.message)
                is QrLoginSession.State.Success -> {} // root view will switch
            }
        }
    }
}
