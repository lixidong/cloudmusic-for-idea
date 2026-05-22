<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# CloudMusic for IDEA — Changelog

## [Unreleased]

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
