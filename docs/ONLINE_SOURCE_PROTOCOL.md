# QingTing 在线音源协议 v1

「轻听」本身**不具备**获取音频的能力。它做的一件事是：把歌曲标识交给**音源**，音源返回一个可播放的链接，App 拿来播。至于这个链接是不是正确的音频，App 无法校验。

这是参考 [LX Music](https://github.com/lyswhut/lx-music-mobile) 的做法，也是它能长期活着的根本原因——**播放器和数据来源解耦**。任何符合本协议的 HTTP 服务都可以成为一个音源，在 App 的「音源管理」里填个地址（或导入一份 JSON）就能用，不需要改 App 代码。

---

## 0. 约定

- 音源 = 一个 HTTP(S) 根地址（`baseUrl`），例如 `https://music.example.com/api`。下文所有路径相对它。
- 请求方法：**全部 GET**。
- 响应 `Content-Type: application/json; charset=utf-8`。
- 成功：HTTP 200 + 约定结构。
- 失败：HTTP 非 2xx，或 200 但 body 里带 `error` 字段。App 会把 `error` 文本直接提示给用户，所以请写人话。
- 时长单位**毫秒**。文本一律 UTF-8。
- 歌曲 `id` 只需在**本音源内**唯一，字符串类型（不要假设是数字）。

## 1. 搜索

```
GET /search?keyword=稻香&page=1&limit=30&quality=high
```

```json
{
  "total": 128,
  "songs": [
    {
      "id": "29780763",
      "name": "稻香",
      "artist": "周杰伦",
      "album": "魔杰座",
      "duration": 223000,
      "cover": "https://example.com/pic/29780763.jpg",
      "qualities": ["standard", "high", "lossless"]
    }
  ]
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `songs[].id` | ✅ | 音源内唯一标识 |
| `songs[].name` | ✅ | 歌名 |
| `songs[].artist` | ✅ | 歌手，多人用 `、` 或 `/` 连接 |
| `songs[].album` | ✅ | 专辑名，没有就填空串 |
| `songs[].duration` | ✅ | 毫秒；未知填 `0`（App 播到时自己会算） |
| `songs[].cover` | ➖ | 封面图 URL；不给的话 App 显示占位图 |
| `songs[].qualities` | ➖ | 这首歌实际可用的音质档位 |
| `total` | ➖ | 总数，用于分页；不给就不分页 |

`keyword` 为空时可以不返回或返回空数组。

## 2. 榜单 / 推荐歌单列表

```
GET /playlists
```

```json
{
  "playlists": [
    { "id": "3778678", "name": "热歌榜", "cover": "https://...", "count": 200, "desc": "每天更新" }
  ]
}
```

`id` / `name` 必填，其余可选。`count` 是曲目数，`desc` 是一句描述。

## 3. 歌单详情

```
GET /playlist?id=3778678&page=1&limit=100
```

```json
{
  "name": "热歌榜",
  "songs": [ /* 与 /search 的 songs[] 结构完全一致 */ ]
}
```

## 4. 解析播放直链（核心接口）

```
GET /url?id=29780763&quality=high
```

```json
{
  "url": "https://cdn.example.com/xxx.mp3",
  "quality": "high",
  "expiresIn": 1200,
  "size": 8388608
}
```

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `url` | ✅ | 可直接 HTTP GET 的音频地址。**支持 Range 请求**更好（拖动进度才流畅） |
| `quality` | ➖ | 实际返回的音质（请求的档位拿不到时，返回你给的那一档） |
| `expiresIn` | ➖ | 直链有效秒数。App 会据此决定要不要重新解析，不给就按 10 分钟算 |
| `size` | ➖ | 字节数，用于下载前预估 |

**无版权 / 已下架**时这样返回，App 会提示并自动跳下一首：

```json
{ "error": "暂无版权" }
```

## 5. 歌词（可选）

```
GET /lyric?id=29780763
```

```json
{ "lyric": "[00:00.00] 稻香 - 周杰伦\n[00:12.35]对这个世界如果你有太多的抱怨\n" }
```

内容是标准 LRC 文本（`[mm:ss.xx]`，支持 `\[mm:ss\]` 简写）。没有歌词返回 `{"error":"not found"}` 即可，App 会回落到 LRCLIB 在线搜索。

## 6. 封面（可选）

```
GET /pic?id=29780763
```

直接返回图片二进制，或者 `{"cover": "https://..."}`。
一般不需要实现——第 1、3 个接口已经带 `cover` 了。

---

## 音质档位

| 值 | 含义 | 典型码率 |
| --- | --- | --- |
| `standard` | 标准 | 128 kbps |
| `high` | 高品 | 320 kbps |
| `lossless` | 无损 | FLAC |

App 在设置里选一档，播放和下载都带着这个参数去请求。源不支持该档位时，返回你能给的最接近的一档，并在 `quality` 里如实说明，不要报错。

## 鉴权

音源配置里两项可选，App 按此加请求头：

| 配置 | 请求头 |
| --- | --- |
| `token` | `Authorization: Bearer <token>` |
| `user` + `password` | `Authorization: Basic base64(user:password)` |

两项都填时以 `token` 为准。都不填就不带任何鉴权头。

---

## 音源配置格式

App「音源管理」里可以手动填，也可以导入下面这种 JSON（单个对象、数组、`{"sources":[...]}` 三种形式都认，也可以是一个返回它的 URL）：

```json
{
  "name": "我的音源",
  "baseUrl": "https://music.example.com/api",
  "token": "",
  "user": "",
  "password": "",
  "enabled": true
}
```

`baseUrl` 必填，其余可选。导入时同名覆盖、不同名追加。

---

## 参考实现

仓库 `tools/mock_source.py` 是一个可直接跑的协议样例（Python 标准库，无依赖），既可以用来理解协议，也用来给 App 做端到端自测：

```bash
python tools/mock_source.py 18080 ./music
```

它会把 `./music` 目录下的音频文件当成一个曲库，提供搜索、两个歌单、直链、歌词全套接口。
