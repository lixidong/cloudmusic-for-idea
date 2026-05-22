package org.example.musicplugin.audio

import org.example.musicplugin.api.model.Song

enum class LoopMode { LIST, SINGLE, SHUFFLE }

class PlaybackQueue {

    @Volatile var loopMode: LoopMode = LoopMode.LIST

    private val lock = Any()
    private val songs = mutableListOf<Song>()
    private var cursor: Int = -1

    fun replaceAll(newSongs: List<Song>, startIndex: Int = 0) = synchronized(lock) {
        songs.clear()
        songs.addAll(newSongs)
        cursor = if (newSongs.isEmpty()) -1 else startIndex.coerceIn(0, newSongs.lastIndex)
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
            LoopMode.SHUFFLE -> if (songs.size == 1) 0 else {
                var r = (Math.random() * songs.size).toInt()
                if (r == cursor) r = (r + 1) % songs.size
                r
            }
        }
        songs[cursor]
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
