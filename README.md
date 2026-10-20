# 轻听 · QingTing Music Player

一个用 **Kotlin + Jetpack Compose + Media3(ExoPlayer)** 写的本地音乐播放器。

它最初只是想解决一件小事：手机里存的歌用系统播放器翻着太累，就想打开 App 直接听，还能顺手挑挑播放模式。后来一步步加成了现在这样：本地扫歌、WebDAV 网络音源、滚动歌词、封面取色主题、离线缓存、睡眠定时、断点续听、一键收藏。

- 包名：`com.tom.musicplayer`
- 当前版本：`versionCode 3` / `versionName 1.2-1012`
- 支持 Android 8.0（API 26）及以上

## 功能清单

### 播放
| 能力 | 说明 |
| --- | --- |
| 本地曲库 | 用系统文件选择器授权一个文件夹，自动扫描 mp3 / wav / flac；二次启动走磁盘缓存 + 增量扫描，冷启动不会整库重解标签 |
| 播放模式 | 顺序 / 倒序 / 随机 / 单曲循环，菜单选完入口文案会跟着变 |
| 前台服务 | `MediaSessionService` + `START_STICKY`，熄屏、切后台继续播 |
| 通知栏控制 | 上一首 / 播放暂停 / 下一首 / 收藏，带专辑封面 |
| 断点续听 | 每首歌独立记进度，下次进来接着播 |
| 睡眠定时 | 可选时长，到点自动停并淡停音频焦点 |

### 音源
- **WebDAV**：`PROPFIND`(Depth:1) 拉取目录树 + Basic 鉴权拦截器；多服务器可分别配置、分别开关。
- **边播边缓存**：`SimpleCache` 512MB LRU，网上的歌听过一次之后离线也能放。

### 界面
- **滚动歌词**：LRCLIB 在线搜索，三步降级匹配（专辑+时长 → 专辑 → 歌名+歌手）；也支持手动指定曲目校对。播放页自动跟随，用户手动滑动时暂停跟随，停手几秒后恢复。
- **封面取色**：用 Palette 从专辑封面抽主色，驱动整个播放页的 Material 配色，每首歌都有自己的颜色。
- **搜索 / 收藏**：按歌名、歌手过滤；收藏写 `favorites.json`，列表页实时显示「我的喜欢 N」。

### 一键收藏
播放页标题栏右上角和通知栏面板各有一颗心，点一下就收藏当前正在播的那一首，不需要回列表里找。
两边共用同一份 `StateFlow<Set<String>>`，任一侧点击后另一侧图标、列表计数、收藏视图都会同步；切歌时图标自动换成新曲目的状态。

## 编译

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求 JDK 17、`compileSdk 35`。直接 Android Studio 打开本目录即可（它会自己配 SDK 路径，不需要 `local.properties`）。

> 如果在国内网络环境下 Gradle 拉不动依赖，给它显式挂代理：
> `GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<端口> -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<端口>"`

## 目录结构

```
app/src/main/java/com/tom/musicplayer/
├── player/
│   ├── PlaybackService.kt     MediaSessionService：后台播放、通知栏、自定义命令回调
│   ├── PlayerController.kt    全局播放状态中枢（StateFlow 下发：进度 / 队列 / 模式 / 收藏 / 主色）
│   ├── PlayerDataSources.kt   本地 ContentResolver + OkHttp + SimpleCache 数据源装配
│   └── MediaCommands.kt       通知栏自定义命令（收藏）
├── data/
│   ├── MusicScanner.kt        增量扫描
│   ├── FavoritesStore.kt      收藏持久化
│   ├── LibraryCache.kt        曲库缓存
│   ├── Prefs.kt / ResumeStore.kt / SourceStore.kt
│   ├── lyrics/                LRC 解析 + LRCLIB 搜索
│   └── webdav/                WebDAV 客户端与目录扫描
├── ui/
│   ├── AppRoot.kt             页面路由 + 播放器连接
│   ├── LibraryScreen.kt       列表 / 搜索 / 收藏视图
│   ├── PlayerScreen.kt        播放页
│   ├── SourcesScreen.kt       音源管理
│   ├── components/            封面、歌词组件
│   └── theme/                 动态取色主题
└── model/                     数据类
docs/screenshots/              各功能界面截图
```

## 两个值得记一笔的坑

修这两个问题时花的工夫比写代码还多，记下来免得以后重犯。

**1. 播放几分钟就自动退出。**
根因在 `PlayerController.attach()`：它在恢复逻辑跑完之前就先 `pushPlayingState()`，把磁盘上「被杀时正在播放」的标记改写了。后台久了进程被系统回收，`MediaSessionService` 以 `START_STICKY` 重启（`intent == null`），恢复逻辑读到的是 `false`，于是不续播 —— 表现就是「放着放着没了」。
解法是加 `restoring` 门闩：置位必须早于任何 `pushPlayingState()`，且 `pushPlayingState()` 在 `restoring` 期间直接返回；同时重构 `restoreIfNeeded()`，保证哪怕 UI 已经提前灌好队列，也照样评估要不要续播。

**2. 封面取色对某些歌无效。**
`refreshArtwork()` 里有一句「已有 artworkUri 就跳过」的提前 return，凡是自带内嵌封面的歌都会被它拦下，Palette 根本没跑。把取色和「补 artworkUri」解耦之后就好了。

## 已知事项

- 歌词搜索依赖公网的 LRCLIB，无网时只展示本地已有歌词。
- 仓库里不含任何密钥、APK 或签名文件，WebDAV 的账号密码保存在设备本地（`shared_prefs`），不进版本控制。
