package org.example.musicplugin.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBPanel
import com.intellij.util.messages.MessageBusConnection
import org.example.musicplugin.auth.LoginStateListener
import org.example.musicplugin.auth.LoginTopics
import org.example.musicplugin.auth.NeteaseAuthService
import java.awt.CardLayout

internal class MusicToolWindowPanel : JBPanel<JBPanel<*>>() {

    private val cards = CardLayout()
    private val loginView = LoginView()
    private val playerView = PlayerView()
    private var connection: MessageBusConnection? = null

    init {
        layout = cards
        add(loginView, KEY_LOGIN)
        add(playerView, KEY_PLAYER)
        showCorrect(NeteaseAuthService.getInstance().isLoggedIn())
    }

    override fun addNotify() {
        super.addNotify()
        connection = ApplicationManager.getApplication().messageBus.connect().also {
            it.subscribe(LoginTopics.LOGIN_STATE_CHANGED, LoginStateListener { loggedIn, _ ->
                showCorrect(loggedIn)
            })
        }
        showCorrect(NeteaseAuthService.getInstance().isLoggedIn())
    }

    override fun removeNotify() {
        super.removeNotify()
        connection?.disconnect()
        connection = null
    }

    private fun showCorrect(loggedIn: Boolean) {
        cards.show(this, if (loggedIn) KEY_PLAYER else KEY_LOGIN)
    }

    companion object {
        private const val KEY_LOGIN = "login"
        private const val KEY_PLAYER = "player"
    }
}
