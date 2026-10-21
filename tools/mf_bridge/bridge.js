#!/usr/bin/env node
/*
 * QingTing × MusicFree 桥接器
 *
 * 把 MusicFree 格式的插件（CommonJS，`module.exports = { platform, search,
 * getMediaSource, getTopLists, getLyric, ... }`）包装成「轻听」的在线音源协议
 * （docs/ONLINE_SOURCE_PROTOCOL.md 六个 GET 接口），这样 App 那边不用改一行代码
 * 就能直接填 `http://<host>:18091` 当音源。
 *
 * 为什么要桥接：
 *   - MusicFree 插件是 JS 模块，跑在宿主注入的环境里；「轻听」只认 HTTP。
 *   - 两者的能力其实是同构的：
 *       /search       <-> search(query, page, 'music')
 *       /playlists    <-> getTopLists()
 *       /playlist     <-> getTopListDetail(item)
 *       /url          <-> getMediaSource(item, quality)
 *       /lyric        <-> getLyric(item)
 *
 * 两个协议之间对不上的地方，以及这里的处理：
 *   1. 时长：MusicFree 用秒，「轻听」用毫秒 —— 转一下。
 *   2. 音质：MusicFree 是 low/standard/high/super，「轻听」是
 *      standard/high/lossless —— 做一次映射，拿不到就降级重试。
 *   3. **请求头**：这一条最要命。MusicFree 的 getMediaSource 除了 url 还会返回
 *      headers（bilibili 就必须要 Referer + Host，否则 403），但「轻听」的协议
 *      只传一个裸 URL，播放器没法带自定义头。所以凡是插件要求 headers 的，
 *      这里改成返回本服务的 /stream 代理地址，由代理带上头去拉，并原样透传
 *      Range，保证拖动进度条还是流畅的。
 *
 * 用法：
 *   npm install
 *   把插件 js 丢进 plugins/ 目录（任意 MusicFree 插件）
 *   node bridge.js [端口]
 *   然后在 App「我的 -> 在线音源」里填 http://<这台机器的IP>:18091
 */

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORT = parseInt(process.argv[2] || process.env.PORT || '18091', 10);
// 部署到 VPS 想给外网用就保持 0.0.0.0；只想本机/模拟器测就设 BIND=127.0.0.1
const BIND = process.env.BIND || '0.0.0.0';
const PLUGIN_DIR = path.join(__dirname, 'plugins');
const IDMAP_FILE = path.join(__dirname, 'idmap.json');
const IDMAP_MAX = 5000;

// 「轻听」的三档 -> MusicFree 的四档
const QUALITY_UP = { standard: 'standard', high: 'high', lossless: 'super' };
// 拿不到目标档位时往下退
const QUALITY_FALLBACK = { super: ['super', 'high', 'standard'], high: ['high', 'standard'], standard: ['standard'] };

// 走系统代理（仅当环境变量存在时）。
// 插件内部都是 `require('axios')`，拿的是同一个模块实例，所以设 defaults 就能全局生效。
// 部署在能直连的机器上时不设这些变量即可，不会有额外开销。
const PROXY_URL =
  process.env.HTTPS_PROXY || process.env.https_proxy || process.env.HTTP_PROXY || process.env.http_proxy;
if (PROXY_URL) {
  try {
    const axios = require('axios');
    const { HttpsProxyAgent } = require('https-proxy-agent');
    const agent = new HttpsProxyAgent(PROXY_URL);
    axios.defaults.httpsAgent = agent;
    axios.defaults.httpAgent = agent;
    axios.defaults.proxy = false;
    console.log(`[proxy] 走代理 ${PROXY_URL}`);
  } catch (e) {
    console.warn('[warn] 代理初始化失败，将直连:', e.message);
  }
}

// ------------------------------------------------------------------ 插件加载

/** platformKey（文件名） -> { key, siteName, mod } */
const plugins = new Map();

function loadPlugins() {
  if (!fs.existsSync(PLUGIN_DIR)) {
    fs.mkdirSync(PLUGIN_DIR, { recursive: true });
    return;
  }
  for (const file of fs.readdirSync(PLUGIN_DIR)) {
    if (!file.endsWith('.js')) continue;
    const key = file.replace(/\.js$/, '');
    if (plugins.has(key)) continue;
    try {
      const loaded = require(path.join(PLUGIN_DIR, file));
      const mod = loaded && loaded.default && !loaded.search ? loaded.default : loaded;
      if (!mod || typeof mod.search !== 'function') {
        console.warn(`[skip] ${file}: 没有 search，跳过`);
        continue;
      }
      plugins.set(key, {
        key,
        siteName: String(mod.platform || key),
        mod,
      });
      console.log(`[ok] 载入插件 ${key} (${mod.platform || key}) v${mod.version || '?'}`);
    } catch (e) {
      console.warn(`[skip] ${file}: 载入失败 ${e && e.message}`);
    }
  }
}

// ------------------------------------------------------------------ id 映射

/**
 * MusicFree 的 musicItem 字段很多（bilibili 要 bvid/cid/aid，某些平台要 songmid），
 * 塞进 URL 会又长又丑，所以这里只发一个短 id，完整对象存内存 + 落盘。
 * 代价是重启后老歌单里的 id 会失效 —— 所以落盘，重启能接上。
 */
let idMap = {};
let idMapDirty = false;

function loadIdMap() {
  try {
    idMap = JSON.parse(fs.readFileSync(IDMAP_FILE, 'utf8'));
  } catch {
    idMap = {};
  }
}

function saveIdMap() {
  if (!idMapDirty) return;
  idMapDirty = false;
  try {
    const keys = Object.keys(idMap);
    if (keys.length > IDMAP_MAX) {
      const sorted = keys.sort((a, b) => (idMap[a].ts || 0) - (idMap[b].ts || 0));
      for (const k of sorted.slice(0, keys.length - IDMAP_MAX)) delete idMap[k];
    }
    fs.writeFileSync(IDMAP_FILE, JSON.stringify(idMap));
  } catch (e) {
    console.warn('[warn] idmap 落盘失败', e.message);
  }
}
setInterval(saveIdMap, 5000);

/** 把任意对象存进映射表，返回短 id。<platformKey>.<12位hex> */
function remember(platformKey, payload, type) {
  const raw = JSON.stringify(payload);
  const hash = crypto.createHash('sha1').update(platformKey + '|' + type + '|' + raw).digest('hex').slice(0, 12);
  const id = `${platformKey}.${hash}`;
  if (!idMap[id]) {
    idMap[id] = { p: platformKey, t: type, o: payload, ts: Date.now() };
    idMapDirty = true;
  }
  return id;
}

function recall(id) {
  return idMap[id] || null;
}

// ------------------------------------------------------------------ 工具

function sendJson(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Access-Control-Allow-Origin': '*',
  });
  res.end(body);
}

function fail(res, message) {
  sendJson(res, 200, { error: String(message) });
}

function withTimeout(promise, ms, label) {
  return Promise.race([
    promise,
    new Promise((_, reject) => setTimeout(() => reject(new Error(`${label} 超时`)), ms)),
  ]);
}

/** artist 有时候是数组，有时候是字符串 */
function joinArtist(a) {
  if (!a) return '未知歌手';
  if (Array.isArray(a)) return a.filter(Boolean).join('、') || '未知歌手';
  return String(a);
}

/** MusicFree 的时长是秒，轻听要毫秒 */
function toMs(d) {
  const n = Number(d);
  if (!isFinite(n) || n <= 0) return 0;
  return Math.round(n * 1000);
}

/** 把一个 musicItem 转成「轻听」的 song */
function toSong(platformKey, item) {
  const id = remember(platformKey, item, 'music');
  return {
    id,
    name: String(item.title || item.name || '未知歌曲'),
    artist: joinArtist(item.artist),
    album: String(item.album || ''),
    duration: toMs(item.duration),
    cover: item.artwork || '',
    // 具体能不能拿到要等 /url 才知道，这里给个乐观值，拿不到会降级
    qualities: ['standard', 'high', 'lossless'],
  };
}

function pluginOf(entry) {
  return entry ? plugins.get(entry.p) : null;
}

// ------------------------------------------------------------------ 各接口

/** 把插件抛出来的技术错误翻译成人话，毕竟这些字会直接显示在 App 里 */
function humanize(e, what) {
  const msg = (e && e.message) || String(e);
  if (/reading 'list'|reading 'data'|Cannot read propert/i.test(msg)) {
    // 插件拿到了非预期结构 —— 基本都是站点风控/接口改版
    return `${what}暂时取不到（源站可能风控或改版了），换一个试试`;
  }
  if (/timeout|超时/i.test(msg)) return `${what}超时了`;
  if (/403|412|认证|风控/i.test(msg)) return `${what}被源站拦截了`;
  return msg;
}

async function doSearch(query) {
  const keyword = query.get('keyword') || '';
  const page = Math.max(1, parseInt(query.get('page') || '1', 10) || 1);
  const limit = Math.max(1, parseInt(query.get('limit') || '30', 10) || 30);
  if (!keyword.trim()) return { songs: [], total: 0 };

  // 并发问所有插件，谁先回谁先算，单个插件挂了不影响其他的
  const tasks = [...plugins.values()].map(async (p) => {
    const r = await withTimeout(p.mod.search(keyword, page, 'music'), 15000, p.key);
    const data = (r && r.data) || [];
    return data
      .filter((it) => it && (it.title || it.name))
      .map((it) => toSong(p.key, it));
  });

  const settled = await Promise.allSettled(tasks);
  const songs = [];
  const errors = [];
  settled.forEach((s, i) => {
    if (s.status === 'fulfilled') songs.push(...s.value);
    else errors.push(`${[...plugins.keys()][i]}: ${s.reason && s.reason.message}`);
  });

  if (!songs.length && errors.length) {
    return { error: '所有音源都失败了：' + errors.join('；') };
  }
  return { songs: songs.slice(0, limit), total: songs.length };
}

async function doPlaylists() {
  const tasks = [...plugins.values()]
    .filter((p) => typeof p.mod.getTopLists === 'function')
    .map(async (p) => {
      const groups = await withTimeout(p.mod.getTopLists(), 15000, p.key);
      const out = [];
      for (const g of groups || []) {
        for (const sheet of (g && g.data) || []) {
          if (!sheet || !sheet.title) continue;
          out.push({
            id: remember(p.key, sheet, 'sheet'),
            name: g.title ? `${g.title} · ${sheet.title}` : sheet.title,
            cover: sheet.coverImg || '',
            count: sheet.musicList ? sheet.musicList.length : 0,
            desc: sheet.description || '',
          });
        }
      }
      return out;
    });

  const settled = await Promise.allSettled(tasks);
  const playlists = [];
  settled.forEach((s) => {
    if (s.status === 'fulfilled') playlists.push(...s.value);
  });
  return { playlists };
}

async function doPlaylist(query) {
  const id = query.get('id') || '';
  const entry = recall(id);
  if (!entry) return { error: '歌单不存在或桥接器重启过，请刷新列表' };
  const p = pluginOf(entry);
  if (!p || typeof p.mod.getTopListDetail !== 'function') return { error: '这个插件不支持看歌单详情' };

  try {
    const detail = await withTimeout(p.mod.getTopListDetail(entry.o), 20000, p.key);
    const list = (detail && detail.musicList) || [];
    return {
      name: (detail && detail.title) || (entry.o && entry.o.title) || '歌单',
      songs: list.filter((it) => it && (it.title || it.name)).map((it) => toSong(p.key, it)),
    };
  } catch (e) {
    return { error: humanize(e, '这个歌单') };
  }
}

async function doLyric(query) {
  const id = query.get('id') || '';
  const entry = recall(id);
  if (!entry) return {};
  const p = pluginOf(entry);
  if (!p || typeof p.mod.getLyric !== 'function') return {};
  const r = await withTimeout(p.mod.getLyric(entry.o), 15000, p.key);
  if (!r) return {};
  const lyric = r.rawLrc || r.lrc || '';
  return lyric ? { lyric } : {};
}

// getMediaSource 的结果缓存：拖动进度会打很多个 Range 请求，
// 每次 Range 都去重新解析链接既慢又容易被平台限流。
const srcCache = new Map(); // `${id}|${quality}` -> { url, headers, exp }
const SRC_TTL = 20 * 60 * 1000;

async function resolveSource(entry, quality) {
  const key = `${entry.p}|${JSON.stringify(entry.o).length}|${quality}`;
  const hit = srcCache.get(key);
  if (hit && Date.now() < hit.exp - SRC_TTL / 2) return hit;

  const p = pluginOf(entry);
  if (!p || typeof p.mod.getMediaSource !== 'function') {
    throw new Error('这个插件不支持解析播放地址');
  }

  const wanted = QUALITY_FALLBACK[QUALITY_UP[quality] || 'high'] || ['high'];
  let lastErr = null;
  for (const q of wanted) {
    try {
      const r = await withTimeout(p.mod.getMediaSource(entry.o, q), 20000, p.key);
      if (r && r.url) {
        const item = { url: r.url, headers: r.headers || {}, exp: Date.now() + SRC_TTL, quality: q };
        srcCache.set(key, item);
        return item;
      }
      lastErr = new Error('插件没返回可播放的地址');
    } catch (e) {
      lastErr = e;
    }
  }
  throw lastErr || new Error('解析失败');
}

async function doUrl(req, query) {
  const id = query.get('id') || '';
  const quality = query.get('quality') || 'high';
  const entry = recall(id);
  if (!entry) return { error: '歌曲不存在或桥接器重启过，请重新搜索' };

  const src = await resolveSource(entry, quality);

  // 插件不要求特殊请求头 -> 直接把直链给 App，少一跳
  const hk = Object.keys(src.headers || {});
  if (!hk.length) {
    return { url: src.url, quality: quality, expiresIn: Math.floor(SRC_TTL / 1000) };
  }

  // 要求 headers（bilibili 必须带 Referer）-> 返回本服务的代理地址，
  // 由 /stream 带上头去拉。App 那边看到的仍然是一个普通的音频 URL。
  const host = req.headers.host || `127.0.0.1:${PORT}`;
  const proxy = `http://${host}/stream?id=${encodeURIComponent(id)}&quality=${encodeURIComponent(quality)}`;
  return { url: proxy, quality: quality, expiresIn: Math.floor(SRC_TTL / 2000) };
}

/**
 * B 站的 dash 音频上游给的是 application/octet-stream，
 * 播放器只能靠嗅探文件头猜格式，个别机型会猜错直接不出声。
 * 这里按扩展名/上游类型补一个明确的音频类型。
 */
function audioContentType(upstreamType, pathname) {
  const t = String(upstreamType || '').toLowerCase();
  if (t.startsWith('audio/')) return upstreamType;
  const p = String(pathname || '').toLowerCase();
  if (p.endsWith('.mp3')) return 'audio/mpeg';
  if (p.endsWith('.flac')) return 'audio/flac';
  if (p.endsWith('.wav')) return 'audio/wav';
  if (p.endsWith('.ogg') || p.endsWith('.opus')) return 'audio/ogg';
  if (p.endsWith('.webm')) return 'audio/webm';
  // m4s / dash / 未知：一律按 mp4 容器给，ExoPlayer 的 fMP4 解析器能接
  return 'audio/mp4';
}

/** 音频流代理：带上插件要求的头去拉，Range 原样透传 */function doStream(req, res, query) {
  const id = query.get('id') || '';
  const quality = query.get('quality') || 'high';
  const entry = recall(id);
  if (!entry) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('歌曲不存在');
    return;
  }

  resolveSource(entry, quality)
    .then((src) => {
      const target = new URL(src.url);
      const headers = Object.assign({}, src.headers);
      // 这两个头交给底层自己算，原样带过去反而会出错
      delete headers['host'];
      delete headers['Host'];
      delete headers['content-length'];
      delete headers['Content-Length'];
      delete headers['connection'];
      delete headers['accept-encoding'];
      if (req.headers.range) headers['range'] = req.headers.range;

      const mod = target.protocol === 'https:' ? https : http;
      const upstream = mod.request(
        {
          protocol: target.protocol,
          hostname: target.hostname,
          port: target.port || (target.protocol === 'https:' ? 443 : 80),
          path: target.pathname + target.search,
          method: 'GET',
          headers,
        },
        (up) => {
          const out = {
            'Content-Type': audioContentType(up.headers['content-type'], target.pathname),
            'Accept-Ranges': up.headers['accept-ranges'] || 'bytes',
            'Access-Control-Allow-Origin': '*',
          };
          if (up.headers['content-length']) out['Content-Length'] = up.headers['content-length'];
          if (up.headers['content-range']) out['Content-Range'] = up.headers['content-range'];
          res.writeHead(up.statusCode || 200, out);
          up.pipe(res);
        }
      );
      upstream.on('error', (e) => {
        console.warn('[stream] 上游出错', e.message);
        if (!res.headersSent) res.writeHead(502);
        res.end();
      });
      // 客户端拖动进度/退出时把上游也断掉，不然连接会堆积
      res.on('close', () => upstream.destroy());
      upstream.end();
    })
    .catch((e) => {
      console.warn('[stream] 解析失败', e && e.message);
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
      }
      res.end(String((e && e.message) || '解析失败'));
    });
}

// ------------------------------------------------------------------ 路由

const server = http.createServer((req, res) => {
  let u;
  try {
    u = new URL(req.url, `http://${req.headers.host || '127.0.0.1'}`);
  } catch {
    return fail(res, '请求地址不合法');
  }
  const q = u.searchParams;
  const p = u.pathname.replace(/\/+$/, '') || '/';

  const run = async () => {
    switch (p) {
      case '/':
        return sendJson(res, 200, {
          ok: true,
          app: 'qingting-mf-bridge',
          version: '1.0.0',
          protocol: 'qingting-online-v1',
          plugins: [...plugins.values()].map((x) => ({ key: x.key, name: x.siteName })),
        });
      case '/search':
        return sendJson(res, 200, await doSearch(q));
      case '/playlists':
        return sendJson(res, 200, await doPlaylists());
      case '/playlist':
        return sendJson(res, 200, await doPlaylist(q));
      case '/url':
        return sendJson(res, 200, await doUrl(req, q));
      case '/lyric':
        return sendJson(res, 200, await doLyric(q));
      case '/stream':
        return doStream(req, res, q);
      case '/pic':
        return sendJson(res, 200, { error: '封面请用 search 返回的 cover 字段' });
      default:
        return sendJson(res, 404, { error: `没有这个接口: ${p}` });
    }
  };

  run().catch((e) => {
    console.warn(`[err] ${p}`, e && e.message);
    if (!res.headersSent) fail(res, (e && e.message) || '桥接器出错');
  });
});

// ------------------------------------------------------------------ 启动

loadPlugins();
loadIdMap();

if (!plugins.size) {
  console.warn('[warn] plugins/ 目录下没有可用插件，先把 MusicFree 插件的 index.js 丢进去');
}

server.listen(PORT, BIND, () => {
  console.log(`[ready] 轻听音源桥接器 http://${BIND}:${PORT}`);
  console.log(`[ready] 已加载插件: ${[...plugins.keys()].join(', ') || '(无)'}`);
});
