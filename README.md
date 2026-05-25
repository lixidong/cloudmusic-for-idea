# CloudMusic for IDEA

在 IntelliJ IDEA 中收听云音乐的非官方控制器。

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![IntelliJ Platform](https://img.shields.io/badge/IntelliJ-2026.1%2B-blue.svg)](https://www.jetbrains.com/idea/)

---

## 特性

- **底部状态栏控制条**：滚动歌名 / 歌词文案 + 8 个控件（上一首 / 播放 / 下一首 / 队列 / 心动模式 / 喜欢 / 不喜欢 / 音量）
- **底部工具窗播放器**：
  - 登录视图：扫码 / 手机号+密码 / 手机号+短信验证码 三种登录方式
  - 播放视图：歌单浏览 / 封面 / 进度 / 歌词
- **流式 MP3 播放**：非播放时零开销，播放时约 3-5% CPU
- **账号同步**：喜欢 / 不喜欢 / 心动模式 都会同步到账号

## 安装

### 方式 A：从 GitHub Release 安装（推荐）

1. 到 [Releases](https://github.com/lixidong/cloudmusic-for-idea/releases) 下载最新的 `cloudmusic-for-idea-x.y.z.zip`
2. IntelliJ IDEA → `Settings | Plugins` → 齿轮图标 → `Install Plugin from Disk...` → 选刚下载的 zip
3. 重启 IDE

### 方式 B：本地构建

需要 JDK 21+。

```bash
git clone https://github.com/lixidong/cloudmusic-for-idea.git
cd cloudmusic-for-idea
./gradlew buildPlugin
# 产物在 build/distributions/ 下
```

## 使用

1. 打开 IDE 底部的 `CloudMusic` 工具窗
2. 选一种登录方式完成登录
3. 左侧选歌单 → 右下双击歌曲开始播放
4. 之后可以直接用状态栏控制条操作

## 兼容性

- IntelliJ IDEA 2026.1+（since-build 261）
- 其它基于 IntelliJ Platform 的 IDE（PyCharm / WebStorm / 等等）理论上也能用，但未测试

## 已知限制

- 部分需要会员的歌曲无法播放，会自动跳过
- seek 大幅向后跳转时会重新打开音频流（NetEase tokenised URL 不支持 HTTP Range）

## 鸣谢

本插件使用了以下开源项目：

- [JLayer](http://www.javazoom.net/javalayer/javalayer.html) (LGPL) - MP3 解码
- [mp3spi](https://www.javazoom.net/mp3spi/mp3spi.html) (LGPL) - javax.sound SPI for MP3
- [ZXing](https://github.com/zxing/zxing) (Apache 2.0) - 二维码生成
- weapi 加密算法参考 [NeteaseCloudMusicApi](https://github.com/Binaryify/NeteaseCloudMusicApi) (Binaryify)

## 免责声明

本插件仅供学习与个人使用。所调用的第三方网络音乐服务接口与版权均归原始服务商所有，本插件与任何音乐服务商均无商业关联。使用风险由用户自负。

## License

[MIT](LICENSE) © 2026 木乄木
