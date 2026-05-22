package org.example.musicplugin.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import org.example.musicplugin.service.MusicPlayerService

class PlayPauseAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        MusicPlayerService.getInstance().togglePlayPause()
    }
}
