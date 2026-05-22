package org.lixidong.musicplugin.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import java.awt.Image
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.imageio.ImageIO
import javax.swing.ImageIcon

internal object AlbumArtCache {

    private val cache = object : LinkedHashMap<String, ImageIcon>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, ImageIcon>?): Boolean = size > 16
    }
    private val lock = Any()
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun get(url: String?, size: Int, callback: (ImageIcon?) -> Unit) {
        if (url.isNullOrEmpty()) {
            callback(null); return
        }
        val key = "$url@$size"
        synchronized(lock) {
            cache[key]?.let { callback(it); return }
        }
        Thread({
            try {
                val req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15)).GET().build()
                val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
                if (resp.statusCode() / 100 != 2) return@Thread
                val image = ImageIO.read(ByteArrayInputStream(resp.body())) ?: return@Thread
                val scaled = image.getScaledInstance(size, size, Image.SCALE_SMOOTH)
                val icon = ImageIcon(scaled)
                synchronized(lock) { cache[key] = icon }
                ApplicationManager.getApplication().invokeLater { callback(icon) }
            } catch (_: Throwable) {
                // ignore
            }
        }, "NeteaseMusic-AlbumArt").apply { isDaemon = true }.start()
    }
}
