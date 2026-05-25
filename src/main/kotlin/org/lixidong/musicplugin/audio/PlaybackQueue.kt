package org.lixidong.musicplugin.audio

import org.lixidong.musicplugin.api.model.Song
import java.util.Random

enum class LoopMode { LIST, SINGLE, SHUFFLE }

class PlaybackQueue {

    @Volatile var loopMode: LoopMode = LoopMode.LIST

    private val lock = Any()
    private val songs = mutableListOf<Song>()
    private var cursor: Int = -1
    private val random = Random()

    /**
     * Track recently-played indices for SHUFFLE so we don't bounce between the
     * same few songs. Capped at half the queue size — once the window fills up
     * we drop the oldest entry, which means a queue of N gets a guaranteed
     * fresh pick from at least ceil(N/2) candidates.
     */
    private val recentShuffled = ArrayDeque<Int>()

    fun replaceAll(newSongs: List<Song>, startIndex: Int = 0) = synchronized(lock) {
        songs.clear()
        songs.addAll(newSongs)
        cursor = if (newSongs.isEmpty()) -1 else startIndex.coerceIn(0, newSongs.lastIndex)
        recentShuffled.clear()
    }

    fun appendAll(more: List<Song>) = synchronized(lock) {
        if (more.isNotEmpty()) songs.addAll(more)
    }

    fun current(): Song? = synchronized(lock) {
        if (cursor in songs.indices) songs[cursor] else null
    }

    fun moveTo(songId: Long): Song? = synchronized(lock) {
        val idx = songs.indexOfFirst { it.id == songId }
        if (idx < 0) return@synchronized null
        cursor = idx
        songs[cursor]
    }

    fun next(): Song? = synchronized(lock) {
        if (songs.isEmpty()) return@synchronized null
        cursor = when (loopMode) {
            LoopMode.LIST -> if (cursor < 0) 0 else (cursor + 1) % songs.size
            LoopMode.SINGLE -> if (cursor < 0) 0 else cursor
            LoopMode.SHUFFLE -> pickShuffleIndex()
        }
        if (loopMode == LoopMode.SHUFFLE) rememberShuffled(cursor)
        songs[cursor]
    }

    private fun pickShuffleIndex(): Int {
        val n = songs.size
        if (n == 1) return 0
        val avoid = HashSet<Int>(recentShuffled).also { if (cursor >= 0) it.add(cursor) }
        // If the avoid set covers the whole queue, we have to repeat — pick anything but current.
        if (avoid.size >= n) {
            var r = random.nextInt(n)
            if (r == cursor) r = (r + 1) % n
            return r
        }
        // Rejection sampling: cheap and gives a uniform pick over the remaining candidates.
        var r: Int
        do { r = random.nextInt(n) } while (r in avoid)
        return r
    }

    private fun rememberShuffled(idx: Int) {
        val cap = (songs.size / 2).coerceAtLeast(1)
        recentShuffled.addLast(idx)
        while (recentShuffled.size > cap) recentShuffled.removeFirst()
    }

    fun previous(): Song? = synchronized(lock) {
        if (songs.isEmpty()) return@synchronized null
        cursor = if (cursor <= 0) songs.lastIndex else cursor - 1
        songs[cursor]
    }

    fun snapshot(): List<Song> = synchronized(lock) { songs.toList() }
    fun currentIndex(): Int = synchronized(lock) { cursor }
    fun size(): Int = synchronized(lock) { songs.size }
}
