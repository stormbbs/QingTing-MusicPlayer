# 轻听 · LX 内置源桥接器

把 LX Music 的「内置音乐源」重新实现成一个独立 HTTP 服务，对外遵守
《轻听在线音源协议 v1》，App 端不用改一行代码。

---

## 先说清楚一件事：LX 的「内置源」到底是什么

很多人以为 LX Music 里藏着一套完整的音源，把它「提取」出来就能用。实际不是。

LX Music（[lyswhut/lx-music-desktop](https://github.com/lyswhut/lx-music-desktop)，Apache-2.0）
的 `src/renderer/utils/musicSdk/` 目录下确实有七个平台（`kw` 酷我、`kg` 酷狗、
`tx` QQ音乐、`wy` 网易云、`mg` 咪咕、`bd` 百度、`xm` 虾米）的实现，但每个平台目录里
只有这些东西：

```
musicSearch.js   搜索
songList.js      歌单
leaderboard.js   榜单
lyric.js         歌词
pic.js           封面
album.js / comment.js / hotSearch.js / tipSearch.js
```

**没有播放直链。** 所有平台的 `getMusicUrl()` 都是这样写的：

```js
getMusicUrl(songInfo, type) {
  return apis('kw').getMusicUrl(songInfo, type)   // 转发给外部注入的解析器
}
```

也就是说，LX 的内置源只是**元数据层**（能搜、能列、能显示歌词和封面），
播放链接一向是由用户导入的「自定义源脚本」提供的 —— 这是 LX 官方刻意
拆开的设计，因为直链解析涉及版权，官方不直接提供。

所以单独把内置源抠出来，结果是：**能搜到歌，点播放没声音。**

## 本项目的拼法

```
元数据（搜索 / 榜单 / 歌单 / 歌词 / 封面）
    └── 复刻 LX 内置源的公开接口（当前实现了酷我 kw）
播放直链（/url）
    └── 调用聆澜音源服务端 API，用你自己的密钥
```

两者用歌曲 ID 对齐，对外就是一份完整的音源。

---

## 启动

```bash
cd tools/lx_bridge

# 方式一：.env（推荐）
cp .env.example .env
# 编辑 .env，填 LINGLAN_KEY=你的密钥
node bridge.js 18093

# 方式二：命令行
LINGLAN_KEY=CERU_KEY-xxxx node bridge.js 18093
```

无第三方依赖，Node 18+ 直接跑。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` | `18093` | 监听端口 |
| `BIND` | `127.0.0.1` | 绑定地址 |
| `LINGLAN_KEY` | 空 | 聆澜音源密钥，`/url` 必需 |
| `LINGLAN_BASE` | `https://source.shiqianjiang.cn/api` | 聆澜服务端 |
| `TIMEOUT` | `12000` | 上游超时（毫秒） |

## 接到 App 上

模拟器：

```bash
adb reverse tcp:18093 tcp:18093
```

然后在「轻听 → 在线 → 音源管理 → 添加音源」里填：

```
服务地址：http://127.0.0.1:18093
名称：可留空
```

点「测试连接」应显示「可用：连接正常」。

真机同局域网时改成 `http://<电脑内网IP>:18093`，并把 `BIND` 设为 `0.0.0.0`
（注意这样会对局域网开放，见下面的风险一节）。

---

## 接口对照

| 轻听协议 | 实现 | 上游 |
| --- | --- | --- |
| `GET /search` | ✅ 酷我 | `search.kuwo.cn/r.s` |
| `GET /playlists` | ✅ 酷我榜单树 | `qukudata.kuwo.cn/q.k` |
| `GET /playlist` | ✅ 榜单详情 | `kbangserver.kuwo.cn/ksong.s` |
| `GET /url` | ✅ 三平台 | 聆澜 `/api/music/url` |
| `GET /lyric` | ✅ 酷我 | `m.kuwo.cn/newh5/singles/songinfoandlrc` |
| `GET /pic` | ✅ 酷我 | `artistpicserver.kuwo.cn/pic.web` |
| `GET /stream` | ✅ 本服务代理 | 透传 Range |

歌曲 id 统一带平台前缀，如 `kw:440613`。`/playlist` 的 id 带 `bang:` 前缀，
如 `bang:16`（酷我热歌榜）。

### 踩过的坑（改接口时注意）

1. **歌词**必须同时满足三条，少一条就返回 `status:301 音乐查询失败`：
   - 带 `httpsStatus=1`
   - 移动端 UA + `Referer: http://m.kuwo.cn/`
   - `Accept` 必须是浏览器式完整列表，`*/*` 会被判成非浏览器请求
2. **封面域名** `img1.kwcdn.kuwo.cn` 只支持 HTTP 且证书异常，所以统一走 `/pic` 代理，
   App 只访问本机地址。
3. **榜单接口**的 `duration` 是试听时长，真实时长在 `song_duration`。
4. **`/playlists`** 必须返回 `{"playlists":[...]}`，返回裸数组 App 会当空列表。
5. **直链**全部走 `/stream` 代理：酷我 CDN 对 Referer/UA 有要求，直链又是 HTTP，
   App 直连会被明文流量策略拦掉；代理同时保证 Range 透传，拖动进度条才流畅。

---

## 音质

聆澜密钥实际开放（来自脚本里的 `MUSIC_QUALITY` 声明）：

| 平台 | 可用档位 | 对应协议档位 |
| --- | --- | --- |
| 酷我 kw | 128k / 320k / flac | standard / high / lossless |
| 网易云 wy | 128k / 320k / flac | standard / high / lossless |
| 咪咕 mg | 128k / 320k | standard / high |

请求高档位拿不到时自动降档，并在响应的 `quality` 里如实说明，不报错。

---

## 局限与风险（务必看）

1. **目前只有酷我的搜索/榜单/歌词实现了。** 网易云、咪咕的直链接口是通的，
   但它们的搜索接口需要额外加密签名（网易云 weapi 要 AES+RSA），还没做。
   所以三平台里只有酷我能完整闭环。
2. **内置源的接口是平台客户端私有接口，说挂就挂。** 这些 URL 是逆向平台官方
   客户端得到的，酷我改版一次就得跟着改一次，LX 上游每次发版也都在修这些。
   这不是「提取」能一劳永逸解决的问题，属于这类方案的固有成本。
   某个接口报「上游返回了无法解析的数据，接口可能已改版」时，就是这个原因。
3. **密钥不要外泄，也不要提交进 Git。** `.env` 和 `bridge.log` 已在 `.gitignore` 里。
   密钥是永久授权，一旦泄露等于给别人用你的额度。
4. **仅供个人自用。** 移植 LX 的 SDK 逻辑本身没问题（Apache-2.0，注意保留版权声明），
   但把这份服务公开部署成多人共享的公共服务，会同时违反 LX 的许可精神和平台条款，
   也可能让你的密钥被封。自己在局域网里用没问题，别开公网。
5. **`BIND=0.0.0.0` 等于对局域网裸奔。** 本服务没有任何鉴权，
   协议支持 `token` 字段但你得自己在前面加一层反代。

---

## 和 mf_bridge 的区别

| | `mf_bridge`（MusicFree 插件） | `lx_bridge`（本项目） |
| --- | --- | --- |
| 数据来源 | MusicFree 社区插件 | LX 内置源接口 + 聆澜直链 |
| 国内平台 | 官方仓库已下架，只剩 B 站/YouTube 等 | 酷我（完整）、网易云/咪咕（仅直链） |
| 单曲搜索 | B 站返回的是合集视频，不是单曲 | 正常的单曲结果 |
| 依赖 | 需要 `npm install` 一堆包 | 零依赖，Node 18+ 直跑 |
| 直链 | 插件自解析 | 你已购的聆澜密钥 |

两者端口不同（18092 / 18093），可以同时开、同时加进 App。
