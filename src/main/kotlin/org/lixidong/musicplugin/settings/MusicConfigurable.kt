package org.lixidong.musicplugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import org.lixidong.musicplugin.MusicBundle
import org.lixidong.musicplugin.auth.NeteaseAuthService
import org.lixidong.musicplugin.ui.statusbar.MusicStatusBarWidgetFactory
import javax.swing.JComponent

internal class MusicConfigurable : Configurable {

    private val settings = MusicSettings.getInstance()
    private val auth = NeteaseAuthService.getInstance()

    private var enableStatusBar: Boolean = settings.state.enableStatusBar
    private var statusBarWidth: Int = settings.state.statusBarTextWidth

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getDisplayName(): String = "NetEase Music"

    override fun createComponent(): JComponent {
        enableStatusBar = settings.state.enableStatusBar
        statusBarWidth = settings.state.statusBarTextWidth

        val panel = panel {
            group("Account") {
                row {
                    val profile = auth.currentProfile
                    if (profile != null) {
                        label(MusicBundle.message("settings.account.loggedIn", profile.nickname))
                        button(MusicBundle.message("settings.logout")) { auth.logout() }
                    } else {
                        label(MusicBundle.message("settings.account.notLoggedIn"))
                    }
                }
            }

            group("StatusBar") {
                row {
                    checkBox(MusicBundle.message("settings.showStatusBar"))
                        .bindSelected(this@MusicConfigurable::enableStatusBar)
                }
                row(MusicBundle.message("settings.scrollWidth")) {
                    intTextField(120..400).bindIntText(this@MusicConfigurable::statusBarWidth)
                }
            }

            row {
                button(MusicBundle.message("settings.clearCache")) { auth.logout() }
            }
        }
        dialogPanel = panel
        return panel
    }

    override fun isModified(): Boolean = dialogPanel?.isModified() ?: false

    override fun apply() {
        dialogPanel?.apply()
        settings.state.enableStatusBar = enableStatusBar
        settings.state.statusBarTextWidth = statusBarWidth
        ProjectManager.getInstance().openProjects.forEach { project ->
            try {
                project.getService(StatusBarWidgetsManager::class.java)
                    .updateWidget(MusicStatusBarWidgetFactory::class.java)
            } catch (_: Throwable) {
                // best-effort; widget will reflect changes on next restart
            }
        }
    }

    override fun reset() {
        enableStatusBar = settings.state.enableStatusBar
        statusBarWidth = settings.state.statusBarTextWidth
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
