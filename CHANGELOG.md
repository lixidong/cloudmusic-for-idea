<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# CloudMusic for IDEA — Changelog

## [Unreleased]

## [1.2.1] - 2026-05-25

### Fixed

- 心动模式被错误拦截:从私人 FM / 搜索单曲 / 最近播放等非歌单上下文播放时,即使有歌在播也提示"请先从某个歌单里播放一首歌再开启心动模式"。修复后只要当前有播放歌曲即可开启;`playlistId` 自动兜底到 `lastPlaylistId` 或用户首个歌单(通常即"我喜欢的音乐")

## [1.2.0] - 2026-05-25

### Added

- 搜索：支持歌曲 / 歌单 / 歌手 / 专辑
- 快捷入口：最近播放、我喜欢的音乐、每日推荐、私人 FM
- 本地缓存：MP3 LRU 磁盘缓存（默认 256 MB，可在 Settings 调整）
- 设置：音量步进可配置

### Changed

- 随机播放（SHUFFLE）改进：使用 Random，引入"最近播放集合"避免短期内重复
- 播放缓冲扩容：BufferedInputStream 64KB → 256KB；SourceDataLine 缓冲 64KB → 192KB；MediaCache 写文件加 BufferedOutputStream，明显减少卡顿
- 工具窗歌词：拖动进度条时实时预览对应位置歌词；歌词字间距加宽
- 状态栏歌词字间距进一步加宽
- AudioEngine 静默 catch 改为 log.debug，便于排查问题

### Fixed

- 拖动进度条无效：JSlider 在部分 LookAndFeel 下 drag-release 不触发 change event，改用 mouseReleased 兜底
- sdl.write 偶发 IllegalArgumentException：mp3spi 在缓冲边界可能返回非整 frame 字节数，写入前对齐到 frameSize，残留搬到下次缓冲
- 播放卡住自动恢复：网络流静默断流(socket 不发 FIN)导致 read 永久阻塞时，超过 12 秒无进度自动切下一首并提示用户
- README 已知限制中过期内容（seek、心动模式自动续、SVG 红心图标）

## [1.1.0] - 2026-05-22

### Added

- 进度条支持鼠标拖动 seek
- 心动模式自动续：队列剩 3 首时后台拉下一批 20 首追加到尾部

### Changed

- Like / Dislike 按钮改用自定义 SVG 红心图标（空心 / 红色填充 / 心碎）
- Kotlin 源码包名重构：`org.example.musicplugin` → `org.lixidong.musicplugin`

## [1.0.0] - 2026-05-22

### Added

- 初版发布
- 三种登录方式：扫码 / 手机号+密码 / 手机号+短信验证码
- 底部状态栏控制条：滚动歌名/歌词文案 + 8 个控件按钮（上一首 / 播放 / 下一首 / 队列 / 心动模式 / 喜欢 / 不喜欢 / 音量）
- 底部工具窗完整播放器：歌单浏览 / 封面 / 进度 / 歌词
- MP3 流式播放（mp3spi + JLayer），非播放时零开销
- 心动模式（一次拉 20 首心动列表）
- 喜欢 / 不喜欢按钮，与账号同步
- Cookie 通过 IntelliJ PasswordSafe 持久化
