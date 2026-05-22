package org.example.musicplugin.ui.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import org.example.musicplugin.MusicBundle
import org.example.musicplugin.settings.MusicSettings

internal class MusicStatusBarWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "NeteaseMusicWidget"

    override fun getDisplayName(): String = MusicBundle.message("toolwindow.name")

    override fun isAvailable(project: Project): Boolean =
        MusicSettings.getInstance().state.enableStatusBar

    override fun createWidget(project: Project): StatusBarWidget = MusicStatusBarWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }
}
