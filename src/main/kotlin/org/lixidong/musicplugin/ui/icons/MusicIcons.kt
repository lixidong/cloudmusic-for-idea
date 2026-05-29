package org.lixidong.musicplugin.ui.icons

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

internal object MusicIcons {
    @JvmField val Play: Icon = AllIcons.Actions.Execute
    @JvmField val Pause: Icon = AllIcons.Actions.Pause
    @JvmField val Next: Icon = AllIcons.Actions.Forward
    @JvmField val Prev: Icon = AllIcons.Actions.Back
    @JvmField val Volume: Icon = AllIcons.Ide.Notification.NoEvents
    @JvmField val Refresh: Icon = AllIcons.Actions.Refresh
    @JvmField val Login: Icon = AllIcons.General.User
    @JvmField val Loop: Icon = AllIcons.Actions.Refresh
    @JvmField val ToolWindow: Icon = AllIcons.Nodes.PpLib
    @JvmField val List: Icon = AllIcons.Actions.ListFiles
    @JvmField val LikeOff: Icon = IconLoader.getIcon("/icons/heart_outline.svg", MusicIcons::class.java)
    @JvmField val LikeOn: Icon = IconLoader.getIcon("/icons/heart_filled.svg", MusicIcons::class.java)
    @JvmField val Dislike: Icon = IconLoader.getIcon("/icons/heart_broken.svg", MusicIcons::class.java)
    @JvmField val HeartModeOff: Icon = AllIcons.Actions.IntentionBulb
    @JvmField val HeartModeOn: Icon = AllIcons.General.InspectionsOK
    @JvmField val Loading: Icon = AllIcons.Actions.ForceRefresh
}
