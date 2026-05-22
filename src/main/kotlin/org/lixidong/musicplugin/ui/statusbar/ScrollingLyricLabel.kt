package org.lixidong.musicplugin.ui.statusbar

import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.Timer
import javax.swing.UIManager

/**
 * Status-bar text area that:
 *  - displays one short line (song title or current lyric)
 *  - auto-scrolls (marquee) horizontally when the text doesn't fit
 *  - stops scrolling when the source is paused or text shrinks below the width
 */
internal class ScrollingLyricLabel(initialWidth: Int = 220) : JComponent() {

    private var text: String = ""
    private var scrollPos: Int = 0
    private var isPlaying: Boolean = false

    private val timer = Timer(40) {
        scrollPos += 1
        val tw = textWidth()
        if (tw > 0 && scrollPos > tw + GAP) scrollPos = -width
        repaint()
    }.apply { initialDelay = 0; isRepeats = true }

    init {
        font = UIManager.getFont("Label.font") ?: font
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(initialWidth), JBUI.scale(18))
        minimumSize = Dimension(JBUI.scale(60), JBUI.scale(16))
        border = JBUI.Borders.empty(0, 4)
    }

    fun setFixedWidth(widthPx: Int) {
        val w = JBUI.scale(widthPx.coerceIn(80, 600))
        preferredSize = Dimension(w, preferredSize.height)
        recompute()
        revalidate()
        repaint()
    }

    fun update(newText: String, playing: Boolean) {
        val safe = newText.replace("\n", " ").trim()
        val textChanged = safe != text
        text = safe
        isPlaying = playing
        if (textChanged) scrollPos = 0
        recompute()
        repaint()
    }

    private fun textWidth(): Int {
        if (text.isEmpty()) return 0
        val fm = getFontMetrics(font) ?: return 0
        return fm.stringWidth(text)
    }

    private fun recompute() {
        val available = width - JBUI.scale(PADDING * 2)
        val needsScroll = isPlaying && text.isNotEmpty() && textWidth() > available
        if (needsScroll) {
            if (!timer.isRunning) timer.start()
        } else {
            timer.stop()
            scrollPos = 0
        }
    }

    override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        super.setBounds(x, y, width, height)
        recompute()
    }

    override fun paintComponent(g: Graphics) {
        if (text.isEmpty()) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.font = font
            g2.color = foreground ?: UIManager.getColor("Label.foreground")
            g2.clipRect(0, 0, width, height)
            val fm = g2.fontMetrics
            val baseline = (height + fm.ascent - fm.descent) / 2 - 1
            val padding = JBUI.scale(PADDING)
            if (timer.isRunning) {
                val tw = fm.stringWidth(text)
                val drawX = padding - scrollPos
                g2.drawString(text, drawX, baseline)
                g2.drawString(text, drawX + tw + JBUI.scale(GAP), baseline)
            } else {
                g2.drawString(text, padding, baseline)
            }
        } finally {
            g2.dispose()
        }
    }

    override fun removeNotify() {
        super.removeNotify()
        timer.stop()
    }

    companion object {
        private const val PADDING = 4
        private const val GAP = 40
    }
}
