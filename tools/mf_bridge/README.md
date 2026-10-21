# 轻听 × MusicFree 桥接器

把 **MusicFree 格式的插件**包装成**「轻听」的在线音源协议**，让 App 不用改一行代码就能用上
整个 MusicFree 插件生态。

```
轻听 App  ──HTTP(六接口)──▶  bridge.js  ──require()──▶  MusicFree 插件  ──▶  各站点
```

---

## 为什么需要它

「轻听」的音源是一份 HTTP 协议（见 [`docs/ONLINE_SOURCE_PROTOCOL.md`](../../docs/ONLINE_SOURCE_PROTOCOL.md)），
MusicFree 插件是 JS 模块，两者本来对不上。但两边**能力是同构的**：

| 轻听协议 | MusicFree 插件 |
| --- | --- |
| `GET /search` | `search(query, page, 'music')` |
| `GET /playlists` | `getTopLists()` |
| `GET /playlist` | `getTopListDetail(item)` |
| `GET /url` | `getMediaSource(item, quality)` |
| `GET /lyric` | `getLyric(item)` |
| 封面 | 曲目自带的 `artwork` 字段 |

桥接器做的就是把这张表翻译一遍。所以「有哪些插件」决定了「能听什么」，桥接器本身不碰任何站点。

顺带一提，**LX Music 的脚本格式做不到这件事**：LX 脚本只实现 `musicUrl` 一个动作
（给 ID 换直链），搜索和歌单是 LX 客户端自带的，脚本里根本没有。所以拿 `.js` 的 LX 音源
填进「轻听」是搜不到东西的——那类源只能配合 LX 自己用。

---

## 快速开始

```bash
cd tools/mf_bridge
npm install

# 把 MusicFree 插件丢进 plugins/（文件名随意，导出里要有 search 就行）
# 例如 plugins/bilibili.js

# 本机测试（模拟器/本机访问）
BIND=127.0.0.1 node bridge.js 18092

# 给局域网 / VPS 用（默认就是 0.0.0.0）
node bridge.js 18092
```

然后在「轻听 → 我的 → 在线音源」里填地址：

- 真机同局域网：`http://<电脑的局域网IP>:18092`
- 模拟器（推荐用 adb reverse，绕开网络差异）：

  ```bash
  adb reverse tcp:18092 tcp:18092
  # App 里填 http://127.0.0.1:18092
  ```

启动日志会打印加载了哪些插件：

```
[ok] 载入插件 bilibili (bilibili) v0.2.3
[ok] 载入插件 youtube (Youtube) v0.0.1
[ready] 轻听音源桥接器 http://127.0.0.1:18092
```

`GET /` 可以看到当前装了哪些插件：

```json
{"ok":true,"app":"qingting-mf-bridge","protocol":"qingting-online-v1",
 "plugins":[{"key":"bilibili","name":"bilibili"},{"key":"youtube","name":"Youtube"}]}
```

---

## 插件从哪来

- **官方仓库** [maotoumao/MusicFreePlugins](https://github.com/maotoumao/MusicFreePlugins) —
  `dist/<平台>/index.js` 就是编译好的插件。注意官方版已经移除了国内音乐平台，
  剩下 bilibili / youtube / webdav / navidrome / audiomack / suno / udio 这些
  （其中 webdav、navidrome 正好可以接你自己的音乐库）。
- **插件模板** [maotoumao/MusicFreePluginTemplate](https://github.com/maotoumao/MusicFreePluginTemplate) —
  想自己写一个的话从这开始，接口定义看 `types/plugin.d.ts`。
- 社区里还有不少第三方插件合集，**质量、可用性、合法性都自己判断**，本仓库不附带也不背书。

> 提醒一句：第三方插件大多是抓取平台公开接口，随时可能因为风控或改版失效，
> 也可能触及平台条款。自己用、别分发。

---

## 三个协议对不上的地方，以及怎么处理的

**1. 时长单位。** MusicFree 用秒，轻听用毫秒，转一下。

**2. 音质档位。** MusicFree 是 `low/standard/high/super`，轻听是 `standard/high/lossless`。
做了映射，并且**拿不到目标档位会往下退**（无损 → 高品 → 标准），
而不是直接报错——很多站点对某些歌就是没有无损。

**3. 请求头（这条最要命）。** `getMediaSource` 除了 URL 还会返回 `headers`，
bilibili 就必须要带 `Referer` + 正确的 `Host`，否则直接 403。
可「轻听」的协议只传一个裸 URL，播放器没法带自定义头。

处理办法：凡是插件要求 headers 的，`/url` 返回的不是原始直链，而是本服务的
`/stream` 代理地址；代理带上插件给的头去拉，并把 `Range` 原样透传，
所以拖动进度条照常流畅。插件不需要 headers 时就直接给直链，少一跳。

```
GET /url?id=bilibili.7bd9889020a1&quality=high
{"url":"http://127.0.0.1:18092/stream?id=bilibili.7bd9889020a1&quality=high",
 "quality":"high","expiresIn":600}
```

代理地址是按请求的 `Host` 头拼的，所以无论你用 `127.0.0.1`、`10.0.2.2`
还是局域网 IP 访问，返回的地址都是 App 能访问到的那个。

---

## 内部实现要点

- **id 只是短哈希。** MusicFree 的曲目对象字段很多（bilibili 要 `bvid`/`cid`/`aid`，
  某些平台要 `songmid`），塞进 URL 又长又丑。所以对外只发 `<平台>.<12位hash>`，
  完整对象存在 `idmap.json` 里（内存 + 落盘，重启能接上，超过 5000 条按时间淘汰）。
  自定义歌单里存的也是这个 id，换音质、换网络都不会失效。
- **多插件并发搜。** 搜索同时打所有插件，谁先回谁先显示，单个插件超时/报错不影响其他的；
  全挂了才会返回 `error`。
- **直链缓存。** `getMediaSource` 的结果缓存 20 分钟，并且用「过半就重新解析」的策略——
  拖动进度会打很多个 Range 请求，每次都重新解析既慢又容易被限流。
- **错误说人话。** 插件抛出来的 `Cannot read properties of undefined` 这种，
  会翻译成「这个歌单暂时取不到（源站可能风控或改版了）」。这些字会直接显示在 App 里。

---

## 已知限制

- **歌单详情的可用性取决于站点。** B 站的「每周必看」现在返回 `code:-352`（风控），
  「入站必刷」还能取到。这是插件/站点那边的事，桥接器只能如实报错。
- **bilibili 搜出来的是视频，不是单曲。** 很多是 1~4 小时的合集，当"电台"听可以，
  当单曲听体验一般。
- **代理会占桥接器的带宽。** 需要 headers 的源都走代理，部署在带宽小的 VPS 上注意。
- **不安全。** 默认没有鉴权，绑 `0.0.0.0` 时会暴露给整个网络。
  要放公网请自己在前面套一层带鉴权的反代（或者用 `BIND=127.0.0.1` 只给本机）。

---

## 环境变量

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `PORT` | `18091` | 也可以直接 `node bridge.js <端口>` |
| `BIND` | `0.0.0.0` | 只给本机/模拟器用设 `127.0.0.1` |
| `HTTPS_PROXY` / `HTTP_PROXY` | 无 | 设了就走代理，插件内部请求也会走 |
