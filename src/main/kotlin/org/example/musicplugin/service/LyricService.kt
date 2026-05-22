package org.example.musicplugin.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.example.musicplugin.api.NeteaseApiClient
import org.example.musicplugin.api.model.LyricLine

internal object LyricParser {
    private val timeRegex = Regex("""\[(\d+):(\d+)(?:[.:](\d+))?]""")

    fun parse(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val matches = timeRegex.findAll(raw).toList()
            if (matches.isEmpty()) continue
            val text = raw.substring(matches.last().range.last + 1).trim()
            if (text.isEmpty()) continue
            for (m in matches) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val frac = m.groupValues[3].let {
                    if (it.isEmpty()) 0L else it.padEnd(3, '0').substring(0, 3).toLong()
                }
                out += LyricLine(min * 60_000L + sec * 1000L + frac, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}

@Service(Service.Level.APP)
internal class LyricService(private val cs: CoroutineScope) {

    private val api = NeteaseApiClient.getInstance()
    private val cache = object : LinkedHashMap<Long, List<LyricLine>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, List<LyricLine>>?): Boolean = size > 16
    }
    private val cacheLock = Any()

    @Volatile private var loadedSongId: Long = -1
    @Volatile private var currentLines: List<LyricLine> = emptyList()

    fun loadFor(songId: Long) {
        synchronized(cacheLock) {
            val cached = cache[songId]
            if (cached != null) {
                loadedSongId = songId
                currentLines = cached
                return
            }
        }
        cs.launch(Dispatchers.IO) {
            val parsed = try {
                LyricParser.parse(api.fetchLyric(songId))
            } catch (_: Throwable) {
                emptyList()
            }
            synchronized(cacheLock) { cache[songId] = parsed }
            loadedSongId = songId
            currentLines = parsed
        }
    }

    fun currentLineFor(positionMs: Long): String? {
        val list = currentLines
        if (list.isEmpty()) return null
        var lo = 0
        var hi = list.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].timeMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        if (found < 0) return null
        return list[found].text.ifBlank { null }
    }

    fun clear() {
        loadedSongId = -1
        currentLines = emptyList()
    }

    companion object {
        fun getInstance(): LyricService = service()
    }
}
