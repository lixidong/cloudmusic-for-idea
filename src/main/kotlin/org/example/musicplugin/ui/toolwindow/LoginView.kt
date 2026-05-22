package org.example.musicplugin.ui.toolwindow

import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTabbedPane
import org.example.musicplugin.MusicBundle
import java.awt.BorderLayout

internal class LoginView : JBPanel<JBPanel<*>>() {

    init {
        layout = BorderLayout()
        val tabs = JBTabbedPane().apply {
            addTab(MusicBundle.message("login.tab.qr"), QrLoginPanel())
            addTab(MusicBundle.message("login.tab.password"), PasswordLoginPanel())
            addTab(MusicBundle.message("login.tab.sms"), SmsLoginPanel())
        }
        add(tabs, BorderLayout.CENTER)
    }
}
