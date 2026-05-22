package org.lixidong.musicplugin.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import org.lixidong.musicplugin.service.MusicPlayerService

class PrevTrackAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        MusicPlayerService.getInstance().previous()
    }
}
