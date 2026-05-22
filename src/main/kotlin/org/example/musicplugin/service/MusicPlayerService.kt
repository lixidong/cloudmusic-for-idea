package org.example.musicplugin.service

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.example.musicplugin.MusicBundle
import org.example.musicplugin.api.NeteaseApiClient
import org.example.musicplugin.api.NeteaseApiException
import org.example.musicplugin.api.model.Song
import org.example.musicplugin.audio.AudioEngine
import org.example.musicplugin.audio.LoopMode
import org.example.musicplugin.audio.PlaybackQueue
import org.example.musicplugin.auth.NeteaseAuthService
import org.example.musicplugin.settings.MusicSettings
import java.util.concurrent.ConcurrentHashMap.newKeySet

@Service(Service.Level.APP)
internal class MusicPlayerService(private val cs: CoroutineScope) : Disposable {

    private val log = Logger.getInstance(MusicPlayerService::class.java)
    private val api = NeteaseApiClient.getInstance()
    private val engine = AudioEngine()
    private val queue = PlaybackQueue()

    @Volatile private var state = PlaybackState()
    private val likedSongIds: MutableSet<Long> = newKeySet()
    @Volatile private var likedLoaded = false

    init {
        val saved = MusicSettings.getInstance().state.volumePercent.coerceIn(0, 100)
        engine.setVolume(saved)
        state = state.copy(volumePercent = saved)
        engine.setListener(EngineListener())
        runCatching {
            queue.loopMode = LoopMode.valueOf(MusicSettings.getInstance().state.loopMode)
        }
    }

    fun currentState(): PlaybackState = state

    fun loadPlaylist(songs: List<Song>, startIndex: Int = 0, autoPlay: Boolean = false, playlistId: Long = 0L) {
        queue.replaceAll(songs, startIndex)
        update(
            state.copy(
                queue = songs,
                currentIndex = queue.currentIndex(),
                currentSong = queue.current(),
                currentPlaylistId = playlistId,
                isHeartMode = false
            )
        )
        if (autoPlay) play(queue.current())
    }

    /** Play the n-th song in the current queue without changing playlistId or heart mode. */
    fun playFromQueueAt(index: Int) {
        val snapshot = queue.snapshot()
        if (index !in snapshot.indices) return
        queue.moveTo(snapshot[index].id)
        play(snapshot[index])
    }

    fun togglePlayPause() {
        val current = state.currentSong
        if (current == null) {
            val fromQueue = queue.current()
            if (fromQueue != null) play(fromQueue) else notifyEmptyQueue()
            return
        }
        if (state.isPlaying) {
            engine.pause()
            update(state.copy(isPlaying = false))
        } else {
            if (engine.isActive()) {
                engine.resume()
                update(state.copy(isPlaying = true))
            } else {
                play(current)
            }
        }
    }

    fun next() {
        val s = queue.next()
        if (s == null) notifyEmptyQueue() else play(s)
    }

    fun previous() {
        val s = queue.previous()
        if (s == null) notifyEmptyQueue() else play(s)
    }

    fun setVolume(percent: Int) {
        val v = percent.coerceIn(0, 100)
        engine.setVolume(v)
        MusicSettings.getInstance().state.volumePercent = v
        update(state.copy(volumePercent = v))
    }

    fun volumeUp() = setVolume(state.volumePercent + 10)
    fun volumeDown() = setVolume(state.volumePercent - 10)

    fun setLoopMode(mode: LoopMode) {
        queue.loopMode = mode
        MusicSettings.getInstance().state.loopMode = mode.name
    }

    fun loopMode(): LoopMode = queue.loopMode

    /** Toggle like state of the currently playing song. Optimistic UI; remote call follows. */
    fun toggleLikeCurrent() {
        val song = state.currentSong ?: run { notifyEmptyQueue(); return }
        val newLiked = !state.isCurrentLiked
        if (newLiked) likedSongIds.add(song.id) else likedSongIds.remove(song.id)
        update(state.copy(isCurrentLiked = newLiked))
        cs.launch(Dispatchers.IO) {
            val ok = try { api.likeSong(song.id, newLiked) } catch (e: Throwable) { log.warn("like failed", e); false }
            if (!ok) {
                // revert on failure
                if (newLiked) likedSongIds.remove(song.id) else likedSongIds.add(song.id)
                ApplicationManager.getApplication().invokeLater {
                    update(state.copy(isCurrentLiked = !newLiked))
                    notify("操作失败：${if (newLiked) "添加到喜欢" else "取消喜欢"}未同步", NotificationType.WARNING)
                }
            }
        }
    }

    /** Mark current song as "不喜欢" — adds to NetEase trash and skips. */
    fun dislikeCurrent() {
        val song = state.currentSong ?: run { notifyEmptyQueue(); return }
        cs.launch(Dispatchers.IO) {
            val ok = try { api.trashSong(song.id) } catch (e: Throwable) { log.warn("trash failed", e); false }
            ApplicationManager.getApplication().invokeLater {
                if (ok) {
                    notify("已加入不喜欢：${song.display}", NotificationType.INFORMATION)
                } else {
                    notify("不喜欢操作失败", NotificationType.WARNING)
                }
                next()
            }
        }
    }

    /** Toggle 心动模式. ON: fetch ~20 recommended songs based on current song. OFF: just clears the flag. */
    fun toggleHeartMode() {
        if (state.isHeartMode) {
            update(state.copy(isHeartMode = false))
            notify("已退出心动模式", NotificationType.INFORMATION)
            return
        }
        val seed = state.currentSong ?: run { notifyEmptyQueue(); return }
        val playlistId = state.currentPlaylistId
        if (playlistId <= 0L) {
            notify("请先从某个歌单里播放一首歌再开启心动模式", NotificationType.WARNING)
            return
        }
        cs.launch(Dispatchers.IO) {
            val songs = try {
                api.fetchHeartModeSongs(seed.id, playlistId, 20)
            } catch (e: Throwable) {
                log.warn("heart mode failed", e); emptyList()
            }
            ApplicationManager.getApplication().invokeLater {
                if (songs.isEmpty()) {
                    notify("拉取心动歌单失败", NotificationType.WARNING)
                    return@invokeLater
                }
                // Put seed first, then recommended. The seed continues playing; queue refreshes.
                val newQueue = (listOf(seed) + songs.filter { it.id != seed.id }).distinctBy { it.id }
                queue.replaceAll(newQueue, 0)
                update(
                    state.copy(
                        queue = queue.snapshot(),
                        currentIndex = queue.currentIndex(),
                        currentSong = seed,
                        currentPlaylistId = playlistId,
                        isHeartMode = true
                    )
                )
                notify("心动模式已开启（共 ${newQueue.size} 首）", NotificationType.INFORMATION)
            }
        }
    }

    /** Pull the latest liked song id set from NetEase. Should be triggered after login. */
    fun refreshLikedSongs() {
        cs.launch(Dispatchers.IO) {
            val uid = NeteaseAuthService.getInstance().currentProfile?.userId ?: return@launch
            val ids = try { api.fetchLikedSongIds(uid) } catch (e: Throwable) { log.warn("liked-list failed", e); emptySet() }
            likedSongIds.clear()
            likedSongIds.addAll(ids)
            likedLoaded = true
            ApplicationManager.getApplication().invokeLater {
                val songId = state.currentSong?.id
                if (songId != null) {
                    update(state.copy(isCurrentLiked = songId in likedSongIds))
                }
            }
        }
    }

    private fun play(song: Song?) {
        if (song == null) return
        if (!likedLoaded) refreshLikedSongs()
        cs.launch(Dispatchers.IO) {
            try {
                val url = api.fetchSongUrl(song.id)
                if (url.isNullOrEmpty()) {
                    notifyUnplayable(song)
                    ApplicationManager.getApplication().invokeLater { next() }
                    return@launch
                }
                ApplicationManager.getApplication().invokeLater {
                    engine.play(url)
                    LyricService.getInstance().loadFor(song.id)
                    update(
                        state.copy(
                            currentSong = song,
                            isPlaying = true,
                            positionMs = 0,
                            durationMs = song.durationMs,
                            currentIndex = queue.currentIndex(),
                            queue = queue.snapshot(),
                            isCurrentLiked = song.id in likedSongIds
                        )
                    )
                }
            } catch (e: NeteaseApiException) {
                log.warn("fetch url failed: ${e.message}", e)
                ApplicationManager.getApplication().invokeLater { next() }
            } catch (e: Throwable) {
                log.warn("play failed", e)
            }
        }
    }

    private fun notify(text: String, type: NotificationType) {
        try {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("CloudMusic.Notifications")
                .createNotification(text, type)
                .notify(null)
        } catch (_: Throwable) {}
    }

    private fun notifyUnplayable(song: Song) =
        notify(MusicBundle.message("notification.songSkipped", song.display), NotificationType.WARNING)

    private fun notifyEmptyQueue() =
        notify("请先打开底部 CloudMusic 工具窗，登录并选择一个歌单", NotificationType.INFORMATION)

    @Synchronized
    private fun update(newState: PlaybackState) {
        state = newState
        ApplicationManager.getApplication().messageBus
            .syncPublisher(PlaybackTopics.PLAYBACK_STATE_CHANGED)
            .onStateChanged(newState)
    }

    private inner class EngineListener : AudioEngine.Listener {
        override fun onPositionUpdate(positionMs: Long) {
            update(state.copy(positionMs = positionMs))
        }

        override fun onCompleted() {
            ApplicationManager.getApplication().invokeLater { next() }
        }

        override fun onError(error: Throwable) {
            log.warn("playback engine error", error)
            update(state.copy(isPlaying = false))
        }
    }

    override fun dispose() {
        engine.dispose()
    }

    companion object {
        fun getInstance(): MusicPlayerService = service()
    }
}
