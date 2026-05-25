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
    private var volumeStep: Int = settings.state.volumeStep
    private var cacheLimitMb: Int = settings.state.cacheLimitMb

    private var dialogPanel: com.intellij.openapi.ui.DialogPanel? = null

    override fun getDisplayName(): String = "NetEase Music"

    override fun createComponent(): JComponent {
        enableStatusBar = settings.state.enableStatusBar
        statusBarWidth = settings.state.statusBarTextWidth
        volumeStep = settings.state.volumeStep
        cacheLimitMb = settings.state.cacheLimitMb

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

            group("Playback") {
                row("音量步进 (%)") {
                    intTextField(1..50).bindIntText(this@MusicConfigurable::volumeStep)
                }
            }

            group("Cache") {
                row("本地缓存上限 (MB)") {
                    intTextField(0..4096).bindIntText(this@MusicConfigurable::cacheLimitMb)
                        .comment("0 表示禁用本地缓存。修改后下次播放生效。")
                }
                row {
                    button("清空缓存") {
                        org.lixidong.musicplugin.audio.MediaCache.getInstance().clear()
                    }
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
        settings.state.volumeStep = volumeStep.coerceIn(1, 50)
        settings.state.cacheLimitMb = cacheLimitMb.coerceIn(0, 4096)
        org.lixidong.musicplugin.audio.MediaCache.getInstance().updateLimit(settings.state.cacheLimitMb)
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
        volumeStep = settings.state.volumeStep
        cacheLimitMb = settings.state.cacheLimitMb
        dialogPanel?.reset()
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
