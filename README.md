# 轻听 · QingTing Music Player

一个用 **Kotlin + Jetpack Compose + Media3(ExoPlayer)** 写的本地音乐播放器。

它最初只是想解决一件小事：手机里存的歌用系统播放器翻着太累，就想打开 App 直接听，还能顺手挑挑播放模式。后来一步步加成了现在这样：本地扫歌、WebDAV 网络音源、滚动歌词、封面取色主题、离线缓存、睡眠定时、断点续听、一键收藏。

- 包名：`com.tom.musicplayer`
- 当前版本：`versionCode 10` / `versionName 1.9-1021`
- 支持 Android 8.0（API 26）及以上

## 功能清单

### 播放
| 能力 | 说明 |
| --- | --- |
| 本地曲库 | 用系统文件选择器授权一个文件夹，自动扫描 mp3 / wav / flac；二次启动走磁盘缓存 + 增量扫描，冷启动不会整库重解标签 |
| 播放模式 | 顺序 / 倒序 / 随机 / 单曲循环，菜单选完入口文案会跟着变 |
| 前台服务 | `MediaSessionService` + `START_STICKY`，熄屏、切后台继续播 |
| 通知栏控制 | 上一首 / 播放暂停 / 下一首 / 收藏 / 下载，带专辑封面 |
| 断点续听 | 每首歌独立记进度，下次进来接着播 |
| 睡眠定时 | 可选时长，到点自动停并淡停音频焦点 |

### 音源
- **WebDAV**：`PROPFIND`(Depth:1) 拉取目录树 + Basic 鉴权拦截器；多服务器可分别配置、分别开关。
- **边播边缓存**：`SimpleCache` 512MB LRU，网上的歌听过一次之后离线也能放。

### 在线音乐

App 自己**不提供**任何歌曲，它做的事和 LX Music 一样：把你选的标识交给音源，拿回链接就播。所以「能听什么」完全取决于你在「在线音源」里配了什么服务。

| 能力 | 说明 |
| --- | --- |
| 音源管理 | 增删改、开关、连通性测试；支持粘贴 JSON 批量导入、一键导出分享 |
| 搜索播放 | 多个音源并行搜，谁先回谁先显示；单个源挂了不影响其他源 |
| 榜单 / 歌单 | 音源返回的榜单列表，点进去看详情、播放全部 |
| 粘贴导入 | 把歌单链接或 ID 粘进去直接打开，会自动从 URL 里抠出 `id` |
| 自建歌单 | 本地歌单只存「音源 + 歌曲 ID」，不存链接，换音质换网络都不会失效 |
| 三档音质 | 标准 / 高品 / 无损，同时作用于播放和下载；音源给不出你要的档位就退到最接近的一档 |
| 下载 | 两个落点可切：公共目录 `内部存储/Music/轻听`（卸载不丢，可自由复制）或你授权过的音乐文件夹（下载完立刻进本地曲库） |

浏览进度会被记住：切到「我的音乐」或「我的」再回来，还是刚才那个标签、那次搜索、那个榜单，连列表滚到哪儿都在。只有自己清空搜索或按「返回」才真的退出当前视图；在榜单详情里按系统返回键会退回榜单列表，而不是退出 App。

协议本身是公开的、和平台无关的六接口约定，见 [`docs/ONLINE_SOURCE_PROTOCOL.md`](docs/ONLINE_SOURCE_PROTOCOL.md)；仓库里附了一份纯标准库写的参考实现 [`tools/mock_source.py`](tools/mock_source.py)，拿任意一个音频目录当曲库就能跑，既能当写音源的样例，也能给 App 做自测：

```bash
python tools/mock_source.py 18080 ./我的音乐
# 模拟器里访问本机：adb reverse tcp:18080 tcp:18080，音源地址填 http://127.0.0.1:18080
```

### 接现成的插件生态：`tools/mf_bridge`

自己写音源太麻烦，仓库里带了一个桥接器，把 **MusicFree 插件**直接变成「轻听」能用的音源：

```bash
cd tools/mf_bridge && npm install
# 把插件 js 丢进 plugins/，然后：
BIND=127.0.0.1 node bridge.js 18092
# adb reverse tcp:18092 tcp:18092，App 里填 http://127.0.0.1:18092
```

App 侧一行代码都不用改——两边的能力本来是同构的（`search` / `getTopLists` / `getTopListDetail` /
`getMediaSource` / `getLyric` 正好对上六个接口）。细节和限制见 [`tools/mf_bridge/README.md`](tools/mf_bridge/README.md)。

实测过的链路（bilibili 插件，端到端跑在模拟器上）：搜到 8 条 → 点第一首 → 播放状态进入
PLAYING → 进度从 3.0s 走到 9.8s → 榜单列表出 7 条 → 无崩溃。

### 内置音源：酷我 / 网易云（开箱即用）

App 里直接内置了两个国内平台的引擎，装好就能搜，不用再架任何服务：

| 源 | 搜索 | 榜单 | 歌词 | 封面 | 播放 |
| --- | --- | --- | --- | --- | --- |
| 酷我音乐 | ✅ | ✅ 30+ 个榜 | ✅ | ✅ | ✅ |
| 网易云音乐 | ✅ | ✅ 4 个官方榜 | ✅ | ✅ | ✅ |

**播放需要一把密钥**（解析直链用）。两种填法：

1. **App 里填一次**：「我的 → 在线音源 → 某个源 → 设置 → 音源密钥」，存在本地，之后一直用。
2. **构建时注入**（省得手动填）：在 `local.properties` 里加一行

```properties
LINGLAN_KEY=CERU_KEY-你的密钥
```

`local.properties` 已被 `.gitignore` 忽略，密钥不会进仓库；别人 clone 下来是空的，得自己填。
没填密钥时搜索、榜单、歌词都正常，只是点播放会提示缺密钥。

音源管理里可以随时启停单个源、改名、换密钥、删除（想恢复从「+ → 内置平台」加回来）。
也可以继续用「+ → 自定义协议音源」接入任何第三方服务——内置源和自定义源是并列的，
可以同时启用，搜索结果按来源交错合并。

> 实现说明：内置引擎只负责**元数据**（搜索 / 榜单 / 歌词 / 封面），播放直链统一交给
> `LinglanResolver`。这和 LX Music 的分工一致——LX 的内置源本身不含 `musicUrl`，
> 直链一向由外部脚本提供，所以单把内置源「提取」出来是能搜不能播的。

### 可选：`tools/lx_bridge`（给其它客户端用）

把同一套能力做成独立 HTTP 服务的 Node 版本，零依赖。App 已经内置了引擎，正常用不到它；
但如果你想让别的客户端、或者在 PC 上跑一份，可以用：

```bash
cd tools/lx_bridge
cp .env.example .env      # 填 LINGLAN_KEY
node bridge.js 18093
```

细节和踩坑记录见 [`tools/lx_bridge/README.md`](tools/lx_bridge/README.md)。

> 顺带澄清一个常见误解：**LX Music 的 `.js` 音源脚本不能直接填进「轻听」**。LX 脚本只实现
> `musicUrl` 一个动作（拿 ID 换直链），搜索和歌单是 LX 客户端内置的，脚本里没有。
> 这类源只能配合 LX 自己用；要接到「轻听」，得用有 `search` 的 MusicFree 插件格式。

两个实现上值得一提的取舍：

- **直链不落库**。直链通常带签名、会过期，所以列表里存的是 `qingting://online/<源ID>/<歌曲ID>` 这种占位 URI，真正要播的时候才去解析。缓存的 key 也必须是这个占位 URI——用解析后的签名 URL 当 key 的话每次都是 miss。
- **换音质要主动清缓存**。切换档位后已解析的直链全部作废，否则用户明明选了无损，播的还是上一次的高品。

### 界面
- **滚动歌词**：LRCLIB 在线搜索，三步降级匹配（专辑+时长 → 专辑 → 歌名+歌手）；也支持手动指定曲目校对。播放页自动跟随，用户手动滑动时暂停跟随，停手几秒后恢复。
- **封面取色**：用 Palette 从专辑封面抽主色，驱动整个播放页的 Material 配色，每首歌都有自己的颜色。
- **搜索 / 收藏**：按歌名、歌手过滤；收藏写 `favorites.json`，列表页实时显示「点心 N」。

### 点心 / 下载
播放页标题栏右上角、以及通知栏面板，都有「点心」和「下载」两颗按钮，中间隔一个位置，拇指都够得着：

- **点心**（就是收藏，图标是心形）：点一下收下当前正在播的那一首，不需要回列表里找。两边共用同一份 `StateFlow<Set<String>>`，任一侧点击后另一侧图标、列表计数都会同步；切歌时图标自动换成新曲目的状态。
- **下载**：有些歌是听了一会儿才决定要留下的，点一下直接下到当前设置的下载目录。按钮有三态——空心箭头（能下）→ 进度环（正在下，带百分比）→ 勾（已下载）。本地 / WebDAV 的歌本来就在手上，这一个按钮不出现。

通知栏那一路是独立的：命令从 `MediaSession` 回来，下载任务自带后台作用域，所以锁屏点、车机上点、点完直接退回桌面，都能下完。

### 我点心的歌曲（自动歌单）
点过心的歌会自动进「我的歌单 → 我点心的歌曲」，不用先建歌单再往里塞。入口有三个：「我的」页的「我点心的歌曲」、「我的歌单」列表固定的第一条，以及曲库顶部的「点心」筛选。

- 在线的、已经下载的、本地文件夹里的，点一下心都收进同一份歌单。
- 点心的动作到处都有：播放页、通知栏/锁屏、曲库列表、在线搜索结果、我的下载。哪里顺手点哪里。
- 这份歌单不是副本：它就是收藏本身。在里面取消心形，歌立刻从歌单消失，反之亦然。
- 收藏只记「身份 + 展示信息」，不记播放直链（直链有时效）。所以点开时会先解析一遍：下过的优先用本地文件，本地文件被挪走了会去曲库里按文件名再找一次，实在找不到才标成「文件已不在原处」，不会点了没反应。
- 同一首歌的「在线版」和「下载版」算一首：按 `音源:歌曲` 判定，不会出现两条重复，取消一条也不会留下另一条。
- 曲库扫描出来的下载文件不带音源信息，所以会按文件名反查下载记录（`DownloadStore.identityOf`）把身份认回来 —— 在「我的音乐」里点心和「在线」里点心是同一个心，两边图标同步亮。升级上来的老记录在启动时自动改写并合并。
- 曲库的「点心」筛选仍然只显示这个文件夹里的歌（它筛的是本地列表），如果还有在线或别处的点心歌，顶上会给一条「还有 N 首点心歌」的入口，点一下进完整歌单。

## 编译

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

要求 JDK 17、`compileSdk 35`。直接 Android Studio 打开本目录即可（它会自己配 SDK 路径，不需要 `local.properties`）。

> 如果在国内网络环境下 Gradle 拉不动依赖，给它显式挂代理：
> `GRADLE_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=<端口> -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=<端口>"`

## 应用内升级

侧载分发没有应用商店，但也不该让人每次卸载重装 —— 卸载会把下载记录、收藏、歌单、音源配置一起清掉。所以做了应用内升级：**下载新版本 APK → 交给系统安装器覆盖安装**，数据全保留。

- 入口：`我的 → 关于与更新`。可以手动检查、开关「启动时自动检查」、改更新源、或者直接从文件管理器选一个本地 APK 安装。
- 检查方式：读一份 JSON 清单，`versionCode` 比当前大才算有新版本。

```json
{
  "versionCode": 8,
  "versionName": "1.7-1021",
  "url": "https://example.com/qingting-1.7-1021.apk",
  "mirrorUrl": "",
  "size": 21133779,
  "sha256": "……",
  "notes": "更新说明，\n 可以换行",
  "mandatory": false
}
```

- 默认清单地址是仓库根目录的 `latest.json`（先试 jsDelivr，再试 GitHub raw）。想放自己服务器，在「更新源」里填完整 URL 即可。
- `size` / `sha256` 填了就校验，下坏了会明确报错而不是装上去崩。

发布一个新版本：

```bash
python tools/publish_update.py --build                  # 打包 + 生成 dist/latest.json
python tools/publish_update.py --build --url https://你的地址/qingting-1.7-1021.apk
python tools/publish_update.py --build --serve          # 同一 Wi-Fi 下手机直接用，连外网都不用
```

把 `dist/latest.json` 里的内容同步到仓库根目录的 `latest.json`，再把 `dist/qingting-*.apk` 放到清单 `url` 指向的位置（GitHub Release 附件 / 自己的服务器都行）。

> 覆盖安装要求签名一致。一直用同一台机器、同一个 `debug.keystore` 打包就没问题；换了签名只能卸载重装一次。

## 目录结构

```
app/src/main/java/com/tom/musicplayer/
├── player/
│   ├── PlaybackService.kt     MediaSessionService：后台播放、通知栏、自定义命令回调
│   ├── NotificationActions.kt 通知栏自定义按钮（收藏 / 下载）的统一构建处
│   ├── PlayerController.kt    全局播放状态中枢（StateFlow 下发：进度 / 队列 / 模式 / 收藏 / 主色）
│   ├── PlayerDataSources.kt   本地 ContentResolver + OkHttp + SimpleCache 数据源装配
│   └── MediaCommands.kt       通知栏自定义命令（收藏 / 下载）
├── data/
│   ├── MusicScanner.kt        增量扫描
│   ├── FavoritesStore.kt      点心（收藏）：身份 + 展示信息，直接当歌单用
│   ├── LibraryCache.kt        曲库缓存
│   ├── Prefs.kt / ResumeStore.kt / SourceStore.kt
│   ├── lyrics/                LRC 解析 + LRCLIB 搜索
│   ├── update/                应用内升级：清单解析、APK 下载校验、交给系统安装器
│   ├── online/                在线音乐：音源、协议客户端、下载器、自建歌单
│   │   └── builtin/           内置源引擎（酷我 / 网易云）+ 共用直链解析
│   └── webdav/                WebDAV 客户端与目录扫描
├── ui/
│   ├── AppRoot.kt             页面路由 + 播放器连接
│   ├── LibraryScreen.kt       列表 / 搜索 / 收藏视图
│   ├── PlayerScreen.kt        播放页
│   ├── OnlineScreen.kt        在线：搜索 / 榜单 / 导入
│   ├── OnlineSourceScreen.kt  在线音源管理
│   ├── DownloadsScreen.kt     我的下载
│   ├── PlaylistsScreen.kt     自建歌单
│   ├── MeScreen.kt            我的
│   ├── SourcesScreen.kt       WebDAV 音源管理
│   ├── components/            封面、歌词组件
│   └── theme/                 动态取色主题
└── model/                     数据类
docs/                          协议文档
tools/mock_source.py           参考音源实现（自测 / 写音源的样例）
tools/mf_bridge/               MusicFree 插件 -> 轻听音源 的桥接器
tools/lx_bridge/               LX 内置源元数据 + 直链 -> 轻听音源 的桥接器
```

## 两个值得记一笔的坑

修这两个问题时花的工夫比写代码还多，记下来免得以后重犯。

**1. 播放几分钟就自动退出。**
根因在 `PlayerController.attach()`：它在恢复逻辑跑完之前就先 `pushPlayingState()`，把磁盘上「被杀时正在播放」的标记改写了。后台久了进程被系统回收，`MediaSessionService` 以 `START_STICKY` 重启（`intent == null`），恢复逻辑读到的是 `false`，于是不续播 —— 表现就是「放着放着没了」。
解法是加 `restoring` 门闩：置位必须早于任何 `pushPlayingState()`，且 `pushPlayingState()` 在 `restoring` 期间直接返回；同时重构 `restoreIfNeeded()`，保证哪怕 UI 已经提前灌好队列，也照样评估要不要续播。

**2. 封面取色对某些歌无效。**
`refreshArtwork()` 里有一句「已有 artworkUri 就跳过」的提前 return，凡是自带内嵌封面的歌都会被它拦下，Palette 根本没跑。把取色和「补 artworkUri」解耦之后就好了。

**3. 搜到歌点下去，第一次按返回没反应。**
不是返回键坏了，是**软键盘还开着**：第一次返回被输入法吃掉（关键盘），第二次才轮到 `BackHandler`。同一时间播放页已经压在键盘上弹出来了。这个在自动化测试里表现得特别明显——脚本按一次返回去点「榜单歌单」，永远点不到。
同一件事还有一个更基础的前提：搜索框必须显式接 `KeyboardActions(onSearch = ...)`，否则键盘上的放大镜按了毫无反应，而这个页面又没有别的搜索入口，用户只会认为搜索坏了。

收键盘本身比想象中麻烦。下面三招**都不管用**（都实测过，键盘照样弹回来）：

- `focusManager.clearFocus()`
- `clearFocus(force = true)`
- 显式 `SoftwareKeyboardController.hide()`，甚至延后一帧再补一刀

原因是输入法在 `onEditorAction` 返回后会把输入会话重新接上，只要焦点还在输入框上，怎么 hide 都会被它抢回去。真正管用的是**把焦点交出去**：给页面根节点挂一个 `FocusRequester` + `focusable()`，需要收键盘时 `requestFocus()`，输入框一失焦输入法自己就退了。見 `OnlineScreen.dismissKeyboard()`。

## 已知事项

- 歌词搜索依赖公网的 LRCLIB，无网时只展示本地已有歌词。
- 仓库里不含任何密钥、APK 或签名文件，WebDAV 的账号密码保存在设备本地（`shared_prefs`），不进版本控制。
