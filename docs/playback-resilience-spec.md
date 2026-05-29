# Playback Resilience Overhaul — Spec v1.0

> 目标：将"网络流式播放"重构为"下载缓冲 + 本地播放 + 自动恢复"架构，从根上消除 CDN 静默断流导致的播放卡死问题。

---

## 1. 问题背景

### 1.1 当前架构

```
MusicPlayerService (编排层)
  └─ AudioEngine.play(url, songId)
       └─ runPlayback()  [NeteaseMusic-Playback 线程]
            ├─ MediaCache.openStream(songId, url)  → InputStream (HTTP 或磁盘缓存)
            ├─ StreamSource.open(inputStream)      → AudioInputStream (MP3→PCM, 256KB BufferedInputStream)
            ├─ SourceDataLine (192KB 行缓冲)
            └─ 主循环: pcm.read() → sdl.write()
  └─ StallWatchdog (12s 超时)
       └─ 超时 → stopInternal() → listener.onStalled() → next()
```

### 1.2 已知缺陷

| # | 缺陷 | 根因 | 影响 |
|---|------|------|------|
| 1 | 播放线程永久阻塞 | JDK `InputStream.read()` 无 per-call timeout；CDN 静默断流时 socket 不发 FIN | watchdog 触发后只能切歌，无法恢复 |
| 2 | 卡顿后直接切歌 | `onStalled()` 无条件调用 `next()` | 网络抖动一次就丢歌，用户期望是续播 |
| 3 | 无预缓冲 | 打开流后立刻开始播放 | 网络波动直接表现为卡顿 |
| 4 | 缓存不区分完整/部分 | `MediaCache` 只在全量 EOF 后才保留文件 | 中途断流后缓存被丢弃，重试必须重头下载 |
| 5 | 无 buffering 状态 | `PlaybackState` 只有 `isPlaying` | UI 无法展示"正在缓冲"，用户不知道发生了什么 |
| 6 | HTTP 无 read timeout | `HttpClient.request()` 只有 `Duration.ofSeconds(30)` 的整体超时 | 下载过程中卡住只能等整体超时 |
| 7 | 无分级恢复 | 只有 watchdog 一种恢复手段 | 没有"先尝试重连同一首歌"的中间态 |

---

## 2. 目标与非目标

### 2.1 目标

- CDN 静默断流后自动恢复播放，用户无感知（或仅短暂缓冲提示）
- 播放前预缓冲，减少首帧卡顿
- 完整缓存复用，断流后无需重头下载
- UI 能展示 Buffering / Recovering 状态
- 分级恢复策略：等待 → 重连当前 URL → 重新获取 URL → 切歌
- 每首歌维护失败计数，防止无限循环

### 2.2 非目标

- 不引入新音频格式（保持 MP3 + mp3spi）
- 不改变现有 UI 框架（仍用 IntelliJ 工具窗 + 状态栏）
- 不引入离线下载功能
- 不改变登录/鉴权流程

---

## 3. 架构设计

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────┐
│  UI 层: 工具窗 / 状态栏 (PlaybackState 驱动)        │
├─────────────────────────────────────────────────────┤
│  编排层: MusicPlayerService                          │
│    ├─ PlaybackStateMachine (新增)                   │
│    └─ RecoveryController (新增)                     │
├─────────────────────────────────────────────────────┤
│  下载层: StreamDownloader (新增)                     │
│    ├─ HTTP 下载线程 (支持 read timeout)              │
│    ├─ 磁盘写入 (支持 partial cache)                 │
│    └─ 下载进度回调                                  │
├─────────────────────────────────────────────────────┤
│  播放层: AudioEngine (重构)                          │
│    ├─ 从本地文件读取 (而非 HTTP 流)                  │
│    ├─ MP3 解码 + PCM 写入 SourceDataLine             │
│    └─ 播放进度回调                                  │
├─────────────────────────────────────────────────────┤
│  缓存层: MediaCache (增强)                           │
│    ├─ 完整缓存 (.mp3)                               │
│    ├─ 部分缓存 (.mp3.partial + .meta)               │
│    └─ LRU 驱逐                                      │
└─────────────────────────────────────────────────────┘
```

### 3.2 核心数据流

```
用户点击播放
  │
  ▼
MusicPlayerService.play(song)
  │
  ├─① 检查缓存: MediaCache.hasComplete(songId)?
  │    ├─ YES → 直接用本地文件播放 (跳过下载层)
  │    └─ NO  → 进入下载流程
  │
  ├─② StreamDownloader.start(songId, url)
  │    ├─ 检查 partial cache: MediaCache.hasPartial(songId)?
  │    │    ├─ YES → 从已下载位置续传 (Range header, 若 CDN 不支持则重头)
  │    │    └─ NO  → 全新下载
  │    ├─ 下载线程: 带 read timeout 的 HTTP GET
  │    └─ 写入磁盘: 边下边写 + 进度回调
  │
  ├─③ 预缓冲等待
  │    └─ 等待 downloadedBytes >= PREBUFFER_THRESHOLD (默认 512KB ≈ 4s 音频)
  │       或下载完成 (小文件)
  │
  ├─④ AudioEngine.playFromFile(localPath, startAtMs)
  │    ├─ 从本地文件打开 MP3 流
  │    ├─ 解码 + 播放 (不再依赖网络)
  │    └─ 定期上报 positionMs
  │
  └─⑤ 下载层继续后台下载，直到 EOF
       ├─ EOF → 标记为 complete cache
       └─ 失败 → 保留 partial cache, 记录已下载字节数
```

### 3.3 下载层与播放层的解耦

**关键原则：** 播放层只读本地文件，不直接读网络流。

- `StreamDownloader` 负责把网络数据写到本地 `.partial` 文件
- `AudioEngine` 从本地文件打开 `FileInputStream` 进行解码播放
- 如果播放速度超过下载速度，`AudioEngine` 的读操作会在文件末尾短暂阻塞（因为文件还在增长），此时 watchdog 检测的是"播放进度"而非"下载进度"
- 下载层和播放层通过共享文件路径通信，不需要直接引用

---

## 4. 状态机设计

### 4.1 PlaybackState 扩展

```kotlin
data class PlaybackState(
    val currentSong: Song?,
    val playbackPhase: PlaybackPhase,  // 新增: 替代 boolean isPlaying
    val isBuffering: Boolean,          // 新增
    val positionMs: Long,
    val durationMs: Long,
    val volumePercent: Int = 60,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val currentPlaylistId: Long = 0L,
    val isCurrentLiked: Boolean = false,
    val isHeartMode: Boolean = false,
)

enum class PlaybackPhase {
    IDLE,           // 无歌曲
    RESOLVING_URL,  // 正在获取歌曲 URL
    BUFFERING,      // 预缓冲中
    PLAYING,        // 正常播放
    PAUSED,         // 暂停
    RECOVERING,     // 卡顿恢复中
    COMPLETED,      // 播放完成 (等待切歌)
    FAILED,         // 播放失败 (不可恢复)
}
```

### 4.2 状态转移图

```
                  ┌──────────┐
                  │   IDLE   │
                  └────┬─────┘
                       │ play(song)
                       ▼
              ┌─────────────────┐
              │  RESOLVING_URL  │
              └────────┬────────┘
                       │ URL 获取成功
                       ▼
              ┌─────────────────┐
         ┌────│   BUFFERING     │◄──────────────────┐
         │    └────────┬────────┘                   │
         │             │ 预缓冲完成                  │
         │             ▼                            │
         │    ┌─────────────────┐                   │
         │    │    PLAYING      │──── stall ────► RECOVERING
         │    └───┬──────┬──────┘                   │
         │        │      │ EOF                      │
         │   pause│      ▼                          │
         │        │  COMPLETED ──► next() ──► RESOLVING_URL
         │        ▼
         │    ┌─────────┐
         │    │ PAUSED  │── resume ──► PLAYING
         │    └─────────┘
         │
         │  RECOVERING 分级恢复:
         │    Level 1: 等待 5s (短暂缓冲)
         │    Level 2: 重连当前 URL (seek 到当前位置)
         │    Level 3: 重新获取 URL + seek
         │    Level 4: 放弃 → next()
         │
         │  任意阶段连续失败 MAX_RECOVERIES 次 → FAILED → next()
```

---

## 5. 分级恢复策略

### 5.1 RecoveryController

```kotlin
class RecoveryController(
    private val engine: AudioEngine,
    private val onRecoveryExhausted: () -> Unit,  // 所有恢复手段用尽 → 切歌
) {
    private var recoveryLevel = 0
    private var lastStablePositionMs = 0L

    fun onStall() {
        recoveryLevel++
        val seekTo = (lastStablePositionMs - 2000).coerceAtLeast(0)  // 回退 2 秒避免丢字

        when (recoveryLevel) {
            1 -> {
                // Level 1: 短暂等待 (可能是临时网络抖动)
                // 不做任何操作, 仅更新 UI 为 BUFFERING
                // 5 秒后如果还没恢复, 进入 Level 2
                scheduleRetry(5_000)
            }
            2 -> {
                // Level 2: 重连当前 URL
                // 关闭当前流, 用同一个 URL 重新打开, seek 到稳定位置
                engine.reconnectCurrentUrl(seekTo)
            }
            3 -> {
                // Level 3: 重新获取 URL (CDN token 可能已过期)
                // 调用 api.fetchSongUrl() 获取新 URL, 重新打开
                engine.reconnectWithNewUrl(seekTo)
            }
            else -> {
                // Level 4+: 放弃
                recoveryLevel = 0
                onRecoveryExhausted()
            }
        }
    }

    fun reset() { recoveryLevel = 0 }

    fun recordProgress(positionMs: Long) {
        if (positionMs > lastStablePositionMs) {
            lastStablePositionMs = positionMs
            recoveryLevel = 0  // 有进度就重置恢复等级
        }
    }

    companion object {
        const val MAX_RECOVERIES = 3
    }
}
```

### 5.2 恢复时序

```
t=0s    播放正常, positionMs=60000 (1:00)
t=8s    StallWatchdog 检测到无进度 (最后 progress 在 t=0s 前 8s)
        → 进入 RECOVERING, Level 1
        → UI 显示 "正在缓冲..."

t=13s   Level 1 超时, 仍然无进度
        → Level 2: 关闭当前流, 用同一 URL 重连, seek 到 58000ms
        → AudioEngine.reconnectCurrentUrl(58000)
        → UI 显示 "重新连接中..."

t=18s   Level 2 重连成功, 播放恢复
        → recoveryLevel = 0, 回到 PLAYING
        → UI 恢复正常

        或:

t=18s   Level 2 也失败
        → Level 3: 重新获取 URL
        → api.fetchSongUrl(songId) → 新 URL
        → AudioEngine.reconnectWithNewUrl(58000)

t=23s   Level 3 成功 → 回到 PLAYING
        Level 3 失败 → Level 4 → next() → 通知 "当前歌曲连接异常, 已跳过"
```

### 5.3 StallWatchdog 改造

当前 watchdog 只检测 `sdl.write()` 进度。改为双维度检测：

```kotlin
class StallWatchdog(
    private val playbackTimeoutMs: Long = 15_000,   // 播放进度超时
    private val downloadTimeoutMs: Long = 30_000,   // 下载进度超时
    private val onPlaybackStall: () -> Unit,
    private val onDownloadStall: () -> Unit,
) {
    @Volatile private var lastPlaybackProgressMs = System.currentTimeMillis()
    @Volatile private var lastDownloadProgressMs = System.currentTimeMillis()

    fun playbackProgress() { lastPlaybackProgressMs = System.currentTimeMillis() }
    fun downloadProgress() { lastDownloadProgressMs = System.currentTimeMillis() }

    // 两个独立定时器
    // playbackTimeoutMs 触发 → RecoveryController.onStall()
    // downloadTimeoutMs 触发 → StreamDownloader.restart() (重启下载线程)
}
```

---

## 6. StreamDownloader 设计

### 6.1 核心职责

- 从 HTTP 源下载音频数据到本地磁盘
- 支持 read timeout（通过 `SO_TIMEOUT` 或带超时的 `InputStream` 封装）
- 支持断点续传（若 CDN 支持 Range header）
- 下载进度回调（供 watchdog 和预缓冲判断使用）

### 6.2 HTTP Read Timeout 方案

JDK `HttpClient` 的 `InputStream` 不支持 per-read timeout。方案选择：

| 方案 | 优点 | 缺点 |
|------|------|------|
| A. 换用 OkHttp | 成熟, 支持 read timeout | 引入新依赖 (~1MB) |
| B. 自建 `TimeoutInputStream` 包装器 | 无新依赖 | 需要额外线程 + interrupt 机制 |
| C. 使用 `Socket.setSoTimeout` | 原生 | JDK HttpClient 不暴露底层 Socket |

**推荐方案 B：** 自建 `TimeoutInputStream`，与现有 `TeeInputStream` 模式一致。

```kotlin
/**
 * 包装 InputStream, 为每次 read() 调用设置超时。
 * 实现原理: 在独立线程中执行 read, 主线程用 Future.get(timeout) 等待。
 * 超时后中断读取线程并抛出 SocketTimeoutException。
 *
 * 注意: 中断后底层 socket 可能处于不确定状态,
 * 调用方应视为不可恢复, 关闭流并重新建立连接。
 */
class TimeoutInputStream(
    private val delegate: InputStream,
    private val readTimeoutMs: Long,
) : InputStream() {

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TimeoutInputStream-Reader").apply { isDaemon = true }
    }

    override fun read(): Int {
        val future = executor.submit<Int> { delegate.read() }
        return try {
            future.get(readTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("read timed out after ${readTimeoutMs}ms")
        }
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val future = executor.submit<Int> { delegate.read(b, off, len) }
        return try {
            future.get(readTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw SocketTimeoutException("read timed out after ${readTimeoutMs}ms")
        }
    }

    override fun close() {
        executor.shutdownNow()
        delegate.close()
    }
}
```

### 6.3 下载线程主循环

```kotlin
fun startDownload(songId: Long, url: String, resumeFrom: Long = 0) {
    downloadThread = Thread({
        try {
            val request = buildRequest(url, resumeFrom)  // 含 Range header
            val response = httpClient.send(request, BodyHandlers.ofInputStream())
            val input = TimeoutInputStream(response.body(), READ_TIMEOUT_MS)
            val output = openPartialFile(songId, resumeFrom)

            val buf = ByteArray(64 * 1024)
            var totalBytes = resumeFrom
            while (!stopRequested) {
                val read = input.read(buf)
                if (read < 0) break  // EOF
                output.write(buf, 0, read)
                totalBytes += read
                downloadProgress(totalBytes)  // 回调
            }

            output.close()
            if (read == -1) {
                // 完整下载, 标记为 complete
                mediaCache.markComplete(songId, totalBytes)
            }
        } catch (e: SocketTimeoutException) {
            // read timeout, 保留 partial, 通知 watchdog
            log.warn("download read timeout at ${downloadedBytes} bytes")
        } catch (e: Throwable) {
            log.warn("download failed", e)
        }
    }, "NeteaseMusic-Download").apply { isDaemon = true; start() }
}
```

---

## 7. MediaCache 增强

### 7.1 文件结构

```
cloudmusic-cache/
├── 12345.mp3           # 完整缓存 (可直接播放)
├── 12345.mp3.partial   # 部分缓存 (下载中断后保留)
├── 12345.meta          # 部分缓存元数据 (JSON)
├── 67890.mp3
└── ...
```

### 7.2 .meta 文件格式

```json
{
    "songId": 12345,
    "url": "https://m701.music.126.net/...",
    "downloadedBytes": 1048576,
    "totalBytes": 4194304,
    "timestamp": 1717027200000,
    "complete": false
}
```

### 7.3 新增 API

```kotlin
class MediaCache {
    // 现有
    fun openStream(songId: Long, url: String): InputStream

    // 新增
    fun hasComplete(songId: Long): Boolean
    fun getCompleteFile(songId: Long): Path?
    fun hasPartial(songId: Long): Boolean
    fun getPartialInfo(songId: Long): PartialCacheInfo?
    fun markComplete(songId: Long, totalBytes: Long)
    fun openPartialForAppend(songId: Long): OutputStream
    fun touch(songId: Long)  // 更新 mtime (LRU)
}

data class PartialCacheInfo(
    val songId: Long,
    val downloadedBytes: Long,
    val totalBytes: Long?,  // null if unknown (chunked transfer)
    val timestamp: Long,
)
```

### 7.4 Partial Cache 复用策略

播放时优先使用缓存：

```
1. hasComplete(songId) → 直接用本地文件播放 (零网络开销)
2. hasPartial(songId) → 从已下载位置开始播放, 下载层从 partial offset 续传
3. 无缓存 → 全新下载 + 预缓冲
```

**注意：** partial cache 复用时，播放器从文件开头读取已下载部分（不需要 seek），下载层从 `downloadedBytes` 位置继续写入。由于 MP3 是流式格式，已下载部分可以独立解码播放。

---

## 8. AudioEngine 重构

### 8.1 核心变更

- `play()` 改为从本地文件路径启动，不再从 URL
- 新增 `reconnectCurrentUrl(seekToMs)` 和 `reconnectWithNewUrl(seekToMs)`
- 播放循环中区分"文件尾部等待下载"和"真正的播放卡死"

### 8.2 新增方法

```kotlin
class AudioEngine {
    /**
     * 从本地文件播放。文件可能还在增长 (下载中)。
     * @param filePath 本地 MP3 文件路径
     * @param songId 歌曲 ID
     * @param startAtMs 起始播放位置 (ms)
     * @param expectedTotalBytes 预期总字节数 (-1 表示未知)
     */
    fun playFromFile(filePath: Path, songId: Long, startAtMs: Long = 0, expectedTotalBytes: Long = -1)

    /**
     * 重连当前 URL, seek 到指定位置。
     * 用于 Level 2 恢复: 关闭当前流, 用同一 URL 重新打开。
     */
    fun reconnectCurrentUrl(seekToMs: Long)

    /**
     * 用新 URL 重连, seek 到指定位置。
     * 用于 Level 3 恢复: 需要先获取新 URL, 然后重新打开。
     * @param urlProvider 异步获取新 URL 的回调
     */
    fun reconnectWithNewUrl(seekToMs: Long, urlProvider: () -> String?)
}
```

### 8.3 播放循环改造

```kotlin
private fun runPlaybackFromFile(filePath: Path, seekTargetMs: Long, expectedTotalBytes: Long) {
    val file = filePath.toFile()
    var lastFileSize = file.length()

    while (!stopRequested) {
        if (paused) { Thread.sleep(40); continue }

        val currentFileSize = file.length()

        // 如果读到文件末尾且文件还在增长, 等待下载层写入更多数据
        if (pcmStream.available() == 0 && currentFileSize < expectedTotalBytes) {
            if (currentFileSize > lastFileSize) {
                lastFileSize = currentFileSize
                wd.downloadProgress()  // 文件在增长, 下载没卡
            }
            Thread.sleep(100)  // 等待下载层写入
            continue
        }

        val read = pcmStream.read(buf, pending, buf.size - pending)
        if (read < 0) {
            // EOF: 检查是否真的下载完了
            if (file.length() < expectedTotalBytes) {
                Thread.sleep(100)
                continue
            }
            break  // 真正的 EOF
        }
        // ... 正常写入 sdl (与现有逻辑相同)
    }
}
```

### 8.4 Seek 说明

现有 seek 实现（重开流 + 丢弃 PCM）仍然有效，因为：
- 本地文件的 seek 可以通过 `FileInputStream.skip()` 实现，比 HTTP 重连快得多
- 不再受限于"网易云 token URL 不支持 Range"的问题
- 可以考虑用 `RandomAccessFile` 替代 `FileInputStream` 以获得真正的 seek 能力

---

## 9. 预缓冲策略

### 9.1 阈值定义

```kotlin
companion object {
    /** 预缓冲字节数: 512KB ≈ 4 秒 128kbps MP3 */
    const val PREBUFFER_BYTES = 512 * 1024L

    /** 预缓冲最大等待时间: 15 秒 */
    const val PREBUFFER_TIMEOUT_MS = 15_000L

    /** 播放中低水位线: 当已缓冲 < 256KB 时进入 BUFFERING 状态 */
    const val LOW_WATERMARK_BYTES = 256 * 1024L
}
```

### 9.2 预缓冲流程

```kotlin
private suspend fun prebuffer(songId: Long, url: String): PrebufferResult {
    val startTime = System.currentTimeMillis()
    val downloader = StreamDownloader(songId, url)
    downloader.start()

    while (true) {
        val downloaded = downloader.downloadedBytes()
        val elapsed = System.currentTimeMillis() - startTime

        when {
            downloader.isComplete() -> return PrebufferResult.Ready(downloaded)
            downloaded >= PREBUFFER_BYTES -> return PrebufferResult.Ready(downloaded)
            elapsed > PREBUFFER_TIMEOUT_MS -> return PrebufferResult.Timeout(downloaded)
            else -> {
                delay(200)
                update(state.copy(isBuffering = true, playbackPhase = PlaybackPhase.BUFFERING))
            }
        }
    }
}
```

### 9.3 小文件特殊处理

如果 `Content-Length` 表明文件很小（< 1MB），直接等待下载完成再播放，避免小文件的预缓冲/边下边播复杂度。

---

## 10. MusicPlayerService 改造

### 10.1 play() 重构

```kotlin
private fun play(song: Song?) {
    if (song == null) return
    if (!likedLoaded) refreshLikedSongs()
    RecentPlayStore.getInstance().record(song)

    // 重置恢复控制器
    recoveryController.reset()

    cs.launch(Dispatchers.IO) {
        // Phase 1: 获取 URL (或命中完整缓存)
        update(state.copy(playbackPhase = PlaybackPhase.RESOLVING_URL))

        val cachedFile = mediaCache.getCompleteFile(song.id)
        if (cachedFile != null) {
            // 完整缓存命中: 跳过下载, 直接播放
            launchPlayback(cachedFile, song, 0)
            return@launch
        }

        val url = try {
            api.fetchSongUrl(song.id)
        } catch (e: NeteaseApiException) {
            log.warn("fetch url failed: ${e.message}", e)
            ApplicationManager.getApplication().invokeLater { next() }
            return@launch
        }

        if (url.isNullOrEmpty()) {
            notifyUnplayable(song)
            ApplicationManager.getApplication().invokeLater { next() }
            return@launch
        }

        // Phase 2: 预缓冲
        update(state.copy(playbackPhase = PlaybackPhase.BUFFERING, isBuffering = true))

        val result = prebuffer(song.id, url)
        if (result is PrebufferResult.Timeout && result.downloadedBytes == 0L) {
            // 预缓冲超时且没下载到任何数据 → 不可播放
            notify("当前歌曲无法加载, 已跳过", NotificationType.WARNING)
            ApplicationManager.getApplication().invokeLater { next() }
            return@launch
        }

        // Phase 3: 开始播放
        val localFile = mediaCache.getPlayingFile(song.id)
        launchPlayback(localFile, song, 0)
    }
}
```

### 10.2 EngineListener 改造

```kotlin
private inner class EngineListener : AudioEngine.Listener {
    override fun onPositionUpdate(positionMs: Long) {
        recoveryController.recordProgress(positionMs)
        update(state.copy(positionMs = positionMs, playbackPhase = PlaybackPhase.PLAYING))
    }

    override fun onCompleted() {
        update(state.copy(playbackPhase = PlaybackPhase.COMPLETED))
        ApplicationManager.getApplication().invokeLater { next() }
    }

    override fun onError(error: Throwable) {
        log.warn("playback engine error", error)
        update(state.copy(playbackPhase = PlaybackPhase.FAILED, isPlaying = false))
    }

    override fun onStalled() {
        update(state.copy(playbackPhase = PlaybackPhase.RECOVERING, isBuffering = true))
        recoveryController.onStall()
    }
}
```

### 10.3 RecoveryController 集成

```kotlin
private val recoveryController = RecoveryController(
    engine = engine,
    onRecoveryExhausted = {
        notify("当前歌曲连接异常, 已跳过", NotificationType.WARNING)
        ApplicationManager.getApplication().invokeLater { next() }
    }
)
```

---

## 11. UI 变更

### 11.1 状态栏歌词区域

| PlaybackPhase | 显示内容 |
|---|---|
| IDLE | "CloudMusic" |
| RESOLVING_URL | "正在获取歌曲信息..." |
| BUFFERING | "正在缓冲..." (或 "正在缓冲 (已下载 X%)" 如果知道总大小) |
| PLAYING | 正常歌词滚动 |
| PAUSED | 当前歌词 + 暂停图标 |
| RECOVERING | "重新连接中..." |
| COMPLETED | 短暂显示 "下一首..." |
| FAILED | "播放失败" (1 秒后消失) |

### 11.2 工具窗进度条

- BUFFERING / RECOVERING 状态下进度条显示不确定动画（indeterminate）
- 恢复后回到确定进度显示
- 已有 partial cache 时，进度条显示"已缓存区域"（灰色）和"已播放区域"（蓝色）

### 11.3 PlaybackState 兼容性

`isPlaying` 字段保留用于向后兼容：
```kotlin
val isPlaying: Boolean
    get() = playbackPhase == PlaybackPhase.PLAYING
```

---

## 12. 配置项

在 `MusicSettings` 中新增：

| 配置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `prebufferBytes` | Int | 524288 (512KB) | 预缓冲字节数 |
| `prebufferTimeoutMs` | Long | 15000 | 预缓冲最大等待时间 |
| `readTimeoutMs` | Long | 30000 | HTTP read 超时 |
| `recoveryMaxRetries` | Int | 3 | 每首歌最大恢复次数 |
| `enablePartialCache` | Boolean | true | 是否启用 partial cache |
| `stallPlaybackTimeoutMs` | Long | 15000 | 播放进度超时 (替代原 12000) |
| `stallDownloadTimeoutMs` | Long | 30000 | 下载进度超时 |

---

## 13. 文件变更清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `audio/AudioEngine.kt` | **重构** | playFromFile, reconnectCurrentUrl, reconnectWithNewUrl, 播放循环改造 |
| `audio/StallWatchdog.kt` | **增强** | 双维度检测 (播放 + 下载) |
| `audio/MediaCache.kt` | **增强** | partial cache, hasComplete, getCompleteFile, markComplete |
| `audio/StreamSource.kt` | 小改 | 支持从 File 打开 (而非仅 InputStream) |
| `audio/StreamDownloader.kt` | **新增** | 下载层, HTTP read timeout, 断点续传 |
| `audio/TimeoutInputStream.kt` | **新增** | 带 read timeout 的 InputStream 包装器 |
| `audio/RecoveryController.kt` | **新增** | 分级恢复策略 |
| `service/MusicPlayerService.kt` | **重构** | play() 流程, EngineListener, RecoveryController 集成 |
| `service/PlaybackState.kt` | **增强** | PlaybackPhase 枚举, isBuffering |
| `settings/MusicSettings.kt` | 小改 | 新增配置项 |
| `settings/MusicConfigurable.kt` | 小改 | 新增配置 UI |
| `ui/statusbar/MusicStatusBarWidget.kt` | 小改 | 显示 buffering/recovering 状态 |
| `ui/toolwindow/PlayerPanel.kt` | 小改 | 进度条 indeterminate 动画 |

---

## 14. 实施顺序

按依赖关系和风险从低到高排列：

### Phase 1: 分级恢复 (最小架构变更)
1. 新增 `RecoveryController`
2. 改造 `MusicPlayerService.EngineListener.onStalled()` — 不再直接 next()
3. `AudioEngine` 新增 `reconnectCurrentUrl()` / `reconnectWithNewUrl()`
4. 更新 `StallWatchdog` 超时从 12s → 15s

**验证：** 模拟网络断流，确认 Level 1-4 恢复策略按预期执行。

### Phase 2: 播放状态机
5. 新增 `PlaybackPhase` 枚举
6. 扩展 `PlaybackState` — `playbackPhase`, `isBuffering`
7. 更新所有 `state.copy(isPlaying = ...)` 为 `playbackPhase = ...`
8. UI 适配新状态

**验证：** 各状态下 UI 显示正确。

### Phase 3: Partial Cache
9. `MediaCache` 新增 partial cache 支持
10. `.meta` 文件读写
11. partial cache 复用逻辑

**验证：** 下载中断后 partial 文件保留；重试时从断点续传。

### Phase 4: 下载层解耦
12. 新增 `TimeoutInputStream`
13. 新增 `StreamDownloader`
14. `AudioEngine` 改为 `playFromFile()`
15. `MusicPlayerService.play()` 重构

**验证：** 播放层不再直接依赖网络流；下载超时能正确触发恢复。

### Phase 5: 预缓冲
16. 预缓冲逻辑
17. BUFFERING 状态展示
18. 小文件特殊处理

**验证：** 首次播放有可感知的缓冲等待；缓冲完成后播放流畅。

---

## 15. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| TimeoutInputStream 的线程池泄漏 | 中 | 中 | 使用 try-finally 确保 shutdownNow；限制并发实例数 |
| partial cache 文件损坏 (crash 期间) | 低 | 低 | .meta 中记录 checksum；播放前校验 |
| 播放速度超过下载速度 | 中 | 低 | 播放循环中等待文件增长；watchdog 区分下载等待和真正卡死 |
| CDN 不支持 Range header | 高 | 低 | partial cache 复用时从文件开头播放，下载层重头下载（但已缓存部分可用） |
| mp3spi 解码 partial 文件失败 | 中 | 中 | fallback: 丢弃 partial cache, 全新下载 |
| 恢复循环中的竞态条件 | 中 | 中 | RecoveryController 所有操作通过 engine 的 @Synchronized 保护 |

---

## 16. 测试计划

### 16.1 单元测试

- `RecoveryControllerTest`: 各级恢复策略的状态转移
- `TimeoutInputStreamTest`: 超时触发、正常读取、close 清理
- `MediaCacheTest`: partial cache 写入/读取/标记 complete
- `PlaybackPhaseTest`: 状态转移正确性

### 16.2 集成测试

- **正常播放**: 完整缓存命中 → 直接播放，无网络请求
- **首次播放**: 预缓冲 → 播放 → 下载完成
- **网络断流恢复**: 播放中切断网络 → 自动恢复 → 继续播放
- **多级恢复**: 连续断流 → Level 1 → 2 → 3 → 4 (切歌)
- **partial cache 复用**: 下载中断 → 重试 → 从 partial 续传
- **seek 测试**: 播放中 seek → 本地文件 seek → 无网络延迟

### 16.3 手动测试场景

1. 播放一首歌，在播放过程中拔掉网线，等待自动恢复
2. 播放一首歌，在播放过程中切换 Wi-Fi，验证恢复
3. 播放列表中连续多首歌网络不可用，验证不会无限循环
4. 暂停很久后恢复播放，验证 watchdog 不误触发
5. 拖动进度条 seek，验证本地 seek 的流畅性

---

## 17. 已知限制 (保留)

- seek 仍然通过重开流 + 丢弃 PCM 实现（因为 mp3spi 不支持精确 seek），但改为本地文件后速度大幅提升
- 不支持 FLAC/AAC 等其他格式（保持 MP3 限制）
- 不支持离线播放（仍需网络获取 URL，但完整缓存可离线播放）
