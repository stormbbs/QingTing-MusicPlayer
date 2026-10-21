#!/usr/bin/env node
/**
 * 轻听 · LX 内置源桥接器 (lx_bridge)
 * ---------------------------------------------------------------------------
 * 把 LX Music 的「内置音乐源」重新实现成一个独立的 HTTP 音源服务，
 * 对外遵守《轻听在线音源协议 v1》，App 端无需改动。
 *
 * 为什么要重新实现而不是直接跑 LX 的源码：
 *   LX Music 的内置源（src/renderer/utils/musicSdk/）只提供元数据
 *   —— 搜索 / 歌单 / 榜单 / 歌词 / 封面；
 *   播放直链 musicUrl 一直是由外部「自定义源脚本」注入的
 *   （LX 里叫 api-source，即 globalThis.lx.on(EVENT_NAMES.request)）。
 *   所以单把内置源抠出来只能搜不能播，必须再配一个直链解析器。
 *
 * 本桥接器的分工：
 *   元数据  -> 直接复刻 LX 内置源的公开接口（酷我）
 *   播放直链 -> 调用聆澜音源服务端 API（你自己的永久密钥）
 *
 * 启动：
 *   LINGLAN_KEY=CERU_KEY-xxxx node bridge.js 18093
 *
 * 环境变量：
 *   PORT         监听端口，默认 18093
 *   BIND         绑定地址，默认 127.0.0.1
 *   LINGLAN_KEY  聆澜音源密钥（必填，直链解析用）
 *   LINGLAN_BASE 聆澜服务端地址，默认 https://source.shiqianjiang.cn/api
 *   TIMEOUT      单次上游请求超时毫秒，默认 12000
 *
 * 协议：见仓库 docs/ONLINE_SOURCE_PROTOCOL.md
 */

'use strict';

const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const { URL } = require('url');

// ---------------------------------------------------------------------------
// 配置
// ---------------------------------------------------------------------------

/** 从同级 .env 补环境变量，省得每次 export（不覆盖已有 env） */
function loadDotEnv() {
  const file = path.join(__dirname, '.env');
  if (!fs.existsSync(file)) return;
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^\s*([A-Z0-9_]+)\s*=\s*(.*)$/.exec(line);
    if (!m) continue;
    let v = m[2].trim();
    if (/^(['"]).*\1$/.test(v)) v = v.slice(1, -1);
    if (!process.env[m[1]]) process.env[m[1]] = v;
  }
}
loadDotEnv();

const PORT = Number(process.env.PORT || 18093);
const BIND = process.env.BIND || '127.0.0.1';
const LINGLAN_BASE = (process.env.LINGLAN_BASE || 'https://source.shiqianjiang.cn/api').replace(/\/+$/, '');
const LINGLAN_KEY = process.env.LINGLAN_KEY || '';
const TIMEOUT = Number(process.env.TIMEOUT || 12000);
const IDLE_TTL = 20 * 60 * 1000; // 直链缓存 20 分钟
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

if (!LINGLAN_KEY) {
  console.warn('[警告] 未配置 LINGLAN_KEY，/url 直链解析会全部失败（搜索/榜单仍可用）');
}

// ---------------------------------------------------------------------------
// 平台能力表
// ---------------------------------------------------------------------------

/**
 * 聆澜密钥实际开放的音质（来自脚本里 MUSIC_QUALITY 声明）：
 *   kw: 128k / 320k / flac
 *   wy: 128k / 320k / flac
 *   mg: 128k / 320k
 * 这里必须如实反映，否则 App 会让用户选一个拿不到的档位。
 */
const PLATFORMS = {
  kw: {
    key: 'kw',
    name: '酷我音乐',
    qualities: ['standard', 'high', 'lossless'],
    // LX 音质档位 -> 聆澜 quality 参数
    upstream: { standard: '128k', high: '320k', lossless: 'flac' },
  },
  wy: {
    key: 'wy',
    name: '网易云音乐',
    qualities: ['standard', 'high', 'lossless'],
    upstream: { standard: '128k', high: '320k', lossless: 'flac' },
  },
  mg: {
    key: 'mg',
    name: '咪咕音乐',
    qualities: ['standard', 'high'],
    upstream: { standard: '128k', high: '320k' },
  },
};

const DEFAULT_PLATFORM = 'kw';

// ---------------------------------------------------------------------------
// 工具
// ---------------------------------------------------------------------------

class UpstreamError extends Error {
  constructor(message, status) {
    super(message);
    this.status = status || 502;
  }
}

async function fetchText(url, options = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), options.timeout || TIMEOUT);
  try {
    const resp = await fetch(url, {
      method: options.method || 'GET',
      headers: Object.assign({ 'User-Agent': UA, Accept: '*/*' }, options.headers || {}),
      body: options.body,
      signal: ctrl.signal,
      redirect: 'follow',
    });
    const text = await resp.text();
    if (!resp.ok) throw new UpstreamError(`上游返回 HTTP ${resp.status}`, resp.status);
    return text;
  } catch (e) {
    if (e.name === 'AbortError') throw new UpstreamError('上游响应超时，请稍后重试', 504);
    if (e instanceof UpstreamError) throw e;
    throw new UpstreamError(`上游请求失败：${e.message}`, 502);
  } finally {
    clearTimeout(timer);
  }
}

async function fetchJson(url, options = {}) {
  const text = await fetchText(url, options);
  try {
    return JSON.parse(text);
  } catch {
    // 酷我部分接口会返回 `var xxx = {...}` 这种非严格 JSON
    const m = /\{[\s\S]*\}/.exec(text);
    if (m) {
      try {
        return JSON.parse(m[0]);
      } catch { /* 落到下面抛错 */ }
    }
    throw new UpstreamError('上游返回了无法解析的数据，接口可能已改版', 502);
  }
}

function sendJson(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj), 'utf8');
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  res.end(body);
}

/** 请求方自己的可达地址（127.0.0.1 / 10.0.2.2 / 局域网 IP 都按请求头来） */
function selfBase(req) {
  return `http://${req.headers.host || `127.0.0.1:${PORT}`}`;
}

/** 把 "kw:440613" 解析成 { platform, songId }；不带前缀按默认平台 */
function parseId(raw) {
  const s = String(raw || '').trim();
  if (!s) return null;
  const i = s.indexOf(':');
  if (i > 0) {
    const p = s.slice(0, i);
    if (PLATFORMS[p]) return { platform: p, songId: s.slice(i + 1) };
  }
  return { platform: DEFAULT_PLATFORM, songId: s };
}

// ---------------------------------------------------------------------------
// 酷我（kw）元数据 —— 复刻 LX src/renderer/utils/musicSdk/kw/
// ---------------------------------------------------------------------------

/**
 * 酷我搜索接口，参数与 LX 的 kw/musicSearch.js 保持一致。
 * 这些是酷我客户端的私有接口，平台改版就会失效，届时需要重新抓参数。
 */
function kwSearchUrl(keyword, page, limit) {
  const q = encodeURIComponent(keyword);
  return (
    'http://search.kuwo.cn/r.s?client=kt' +
    `&all=${q}` +
    `&pn=${Math.max(0, page - 1)}&rn=${limit}` +
    '&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1' +
    '&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012' +
    '&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1'
  );
}

/**
 * 解析 MINFO 里的音质串：
 *   level:ff,bitrate:2000,format:flac,size:24.81Mb;level:p,bitrate:320,format:mp3,...
 * bitrate 4000/2000 -> 无损，320 -> high，128 -> standard
 */
function kwQualities(minfo) {
  const have = new Set();
  const re = /bitrate:(\d+)/g;
  let m;
  while ((m = re.exec(minfo || ''))) {
    const br = m[1];
    if (br === '2000' || br === '4000') have.add('lossless');
    else if (br === '320') have.add('high');
    else if (br === '128') have.add('standard');
  }
  // 按聆澜实际开放档位过滤，并保证至少标准音质
  const cap = PLATFORMS.kw.qualities;
  const out = cap.filter((q) => have.has(q));
  return out.length ? out : ['standard'];
}

/**
 * 封面：搜索结果的 web_albumpic_short 形如 "120/s4s0/93/1794217775.jpg"，
 * 把尺寸段换成 500 就是大图。省掉每首一次封面查询请求。
 */
function kwCoverFromShort(short) {
  if (!short) return '';
  const p = String(short).replace(/^\d+\//, '500/');
  return `http://img1.kwcdn.kuwo.cn/star/albumcover/${p}`;
}

/** 搜索 / 榜单两种返回的字段名完全不同，这里归一到协议结构 */
function kwSongFromSearch(it, base) {
  const songId =
    String(it.MUSICRID || '').replace(/^MUSIC_/, '') || String(it.DC_TARGETID || '');
  const seconds = parseInt(it.DURATION, 10);
  return {
    id: `kw:${songId}`,
    name: it.SONGNAME || it.NAME || '',
    artist: it.ARTIST || it.FARTIST || '',
    album: it.ALBUM || '',
    duration: Number.isFinite(seconds) ? seconds * 1000 : 0,
    cover: kwCoverFromShort(it.web_albumpic_short)
      ? `${base}/pic?id=kw:${encodeURIComponent(songId)}`
      : '',
    qualities: kwQualities(it.MINFO || it.N_MINFO),
  };
}

function kwSongFromBang(it, base) {
  const songId = String(it.id || '');
  // 榜单里的 duration 是试听时长，song_duration 才是完整时长
  const seconds = parseInt(it.song_duration || it.duration, 10);
  return {
    id: `kw:${songId}`,
    name: it.name || '',
    artist: it.artist || '',
    album: it.album || '',
    duration: Number.isFinite(seconds) ? seconds * 1000 : 0,
    // 榜单接口不给封面，但 /pic 按 songId 能查到，走代理补上
    cover: songId ? `${base}/pic?id=kw:${encodeURIComponent(songId)}` : '',
    qualities: PLATFORMS.kw.qualities.slice(),
  };
}

async function kwSearch(keyword, page, limit, base) {
  const data = await fetchJson(kwSearchUrl(keyword, page, limit));
  const list = Array.isArray(data.abslist) ? data.abslist : [];
  const songs = list.map((it) => kwSongFromSearch(it, base)).filter((s) => s.id !== 'kw:');
  const total = parseInt(data.TOTAL, 10);
  return { total: Number.isFinite(total) ? total : songs.length, songs };
}

/** 榜单树：qukudata 的排行榜分类，取二级节点 */
async function kwPlaylists() {
  const data = await fetchJson(
    'http://qukudata.kuwo.cn/q.k?op=query&cont=tree&node=2&pn=0&rn=200&fmt=json&level=2'
  );
  const out = [];
  const walk = (node) => {
    const kids = node.child || [];
    for (const k of kids) {
      if (k.child && k.child.length) {
        walk(k);
      } else if (k.id) {
        out.push({
          id: `bang:${k.id}`,
          name: k.disname || k.name || '未命名榜单',
          cover: k.pic || '',
          desc: k.info && k.info !== '0个列表' ? k.info : '',
        });
      }
    }
  };
  walk(data);
  // 榜单太多（几百个），只给前 40 个，避免 App 列表刷屏
  return out.slice(0, 40);
}

async function kwPlaylistDetail(pid, page, limit, base) {
  const data = await fetchJson(
    'http://kbangserver.kuwo.cn/ksong.s?from=pc&fmt=json' +
      `&pn=${Math.max(0, page - 1)}&rn=${limit}` +
      `&type=bang&data=content&id=${encodeURIComponent(pid)}&show_copyright_off=0&pcmp4=1&isbang=1`
  );
  const list = Array.isArray(data.musiclist) ? data.musiclist : [];
  const songs = list.map((it) => kwSongFromBang(it, base)).filter((s) => s.id !== 'kw:');
  return { name: data.name || '', songs, total: parseInt(data.num, 10) || songs.length };
}

/**
 * 歌词：三个坑都要绕过，否则必定返回 status:301「音乐查询失败」
 *   1) 必须带 httpsStatus=1
 *   2) 必须伪装成移动端 UA 且带 Referer
 *   3) Accept 必须是浏览器式的完整列表，`* / *` 会被判成非浏览器请求
 */
async function kwLyric(songId) {
  const data = await fetchJson(
    `http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${encodeURIComponent(songId)}&httpsStatus=1`,
    {
      headers: {
        'User-Agent':
          'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1',
        Referer: 'http://m.kuwo.cn/',
        Accept:
          'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      },
    }
  );
  const rows = data && data.data && data.data.lrclist;
  if (!Array.isArray(rows) || !rows.length) return null;
  const lines = rows.map((r) => `[${fmtLrcTime(r.time)}]${r.lineLyric || ''}`);
  return lines.join('\n');
}

function fmtLrcTime(sec) {
  const t = Number(sec) || 0;
  const m = Math.floor(t / 60);
  const s = (t % 60).toFixed(2).padStart(5, '0');
  return `${String(m).padStart(2, '0')}:${s}`;
}

// ---------------------------------------------------------------------------
// 直链解析（聆澜音源服务端）
// ---------------------------------------------------------------------------

const urlCache = new Map(); // key -> { url, quality, at }

/**
 * 档位回退链：请求 lossless 拿不到就退 high，再退 standard。
 * 协议要求「拿不到就返回最接近的一档并在 quality 里如实说明」，不能报错。
 */
function fallbackChain(platform, quality) {
  const p = PLATFORMS[platform] || PLATFORMS[DEFAULT_PLATFORM];
  const order = ['lossless', 'high', 'standard'];
  const from = order.indexOf(quality) >= 0 ? order.indexOf(quality) : 1;
  return order.slice(from).filter((q) => p.qualities.includes(q));
}

async function resolveUrl(platform, songId, quality, extra) {
  const cacheKey = `${platform}:${songId}:${quality}`;
  const hit = urlCache.get(cacheKey);
  if (hit && Date.now() - hit.at < IDLE_TTL) {
    return { url: hit.url, quality: hit.actualQuality, cached: true };
  }

  let lastErr = null;
  for (const q of fallbackChain(platform, quality)) {
    const up = PLATFORMS[platform].upstream[q];
    let api =
      `${LINGLAN_BASE}/music/url?source=${encodeURIComponent(platform)}` +
      `&songId=${encodeURIComponent(songId)}&quality=${encodeURIComponent(up)}`;
    // 咪咕这类平台按 songmid 解析不稳，补上歌名歌手能显著提高命中率
    if (extra && extra.name) api += `&name=${encodeURIComponent(extra.name)}`;
    if (extra && extra.singer) api += `&singer=${encodeURIComponent(extra.singer)}`;

    let body;
    try {
      body = await fetchJson(api, {
        headers: { 'X-API-Key': LINGLAN_KEY, 'Content-Type': 'application/json' },
      });
    } catch (e) {
      lastErr = e;
      continue;
    }
    const code = Number(body && body.code);
    if ((code === 0 || code === 200) && body.url) {
      urlCache.set(cacheKey, { url: body.url, actualQuality: q, at: Date.now() });
      return { url: body.url, quality: q, cached: false };
    }
    lastErr = new UpstreamError((body && body.message) || '直链解析失败', 404);
    // 密钥/余额类错误不必再降档重试
    const msg = String((body && body.message) || '');
    if (/密钥|key|额度|过期|封禁|未授权/i.test(msg)) break;
  }
  throw lastErr || new UpstreamError('直链解析失败', 404);
}

// ---------------------------------------------------------------------------
// 上行代理（音频流 / 封面图共用）
// ---------------------------------------------------------------------------

/**
 * 把上游资源原样透传给 App。
 * - Range 请求头必须原样转发，否则拖动进度条会失败
 * - 封面图是 http 域名且证书有问题，App 只访问本机地址规避明文/证书限制
 */
function pipeUpstream(req, res, targetUrl, extraHeaders = {}) {
  const target = new URL(targetUrl);
  const mod = target.protocol === 'https:' ? https : http;
  const headers = Object.assign(
    { 'User-Agent': UA, Accept: '*/*' },
    extraHeaders
  );
  if (req.headers.range) headers.Range = req.headers.range;

  const upReq = mod.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || (target.protocol === 'https:' ? 443 : 80),
      path: target.pathname + target.search,
      method: 'GET',
      headers,
      timeout: TIMEOUT,
    },
    (up) => {
      const status = up.statusCode || 502;
      if (status >= 400) {
        res.writeHead(status, { 'Content-Type': 'text/plain; charset=utf-8' });
        res.end(`上游返回 ${status}`);
        up.resume();
        return;
      }
      const out = {
        'Content-Type': up.headers['content-type'] || 'application/octet-stream',
        'Accept-Ranges': 'bytes',
        'Cache-Control': 'no-store',
      };
      if (up.headers['content-length']) out['Content-Length'] = up.headers['content-length'];
      if (up.headers['content-range']) out['Content-Range'] = up.headers['content-range'];
      res.writeHead(status, out);
      up.pipe(res);
      up.on('error', () => res.destroy());
    }
  );
  upReq.on('timeout', () => upReq.destroy(new UpstreamError('上游超时', 504)));
  upReq.on('error', (e) => {
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(`上游连接失败：${e.message}`);
    } else {
      res.destroy();
    }
  });
  req.on('close', () => upReq.destroy());
  upReq.end();
}

// ---------------------------------------------------------------------------
// 路由
// ---------------------------------------------------------------------------

/**
 * 搜索需要同时支持单平台和聚合。
 * 目前只有酷我实现了元数据接口（wy/mg 的搜索要额外做加密签名），
 * 所以默认只查酷我；显式传 source=wy 才走对应平台。
 */
async function handleSearch(req, res, q, base) {
  const keyword = (q.get('keyword') || '').trim();
  const page = Math.max(1, parseInt(q.get('page') || '1', 10) || 1);
  const limit = Math.min(100, Math.max(1, parseInt(q.get('limit') || '30', 10) || 30));
  if (!keyword) return sendJson(res, 200, { total: 0, songs: [] });

  const platform = q.get('source') || DEFAULT_PLATFORM;
  if (platform !== 'kw') {
    return sendJson(res, 200, {
      total: 0,
      songs: [],
      error: `${PLATFORMS[platform] ? PLATFORMS[platform].name : platform} 的搜索接口尚未实现`,
    });
  }
  const out = await kwSearch(keyword, page, limit, base);
  sendJson(res, 200, out);
}

async function handlePlaylists(req, res) {
  // 协议要求外层是 { playlists: [...] }，返回裸数组 App 会当成空列表
  sendJson(res, 200, { playlists: await kwPlaylists() });
}

async function handlePlaylist(req, res, q, base) {
  const raw = String(q.get('id') || '');
  const page = Math.max(1, parseInt(q.get('page') || '1', 10) || 1);
  const limit = Math.min(200, Math.max(1, parseInt(q.get('limit') || '100', 10) || 100));
  if (raw.startsWith('bang:')) {
    const out = await kwPlaylistDetail(raw.slice(5), page, limit, base);
    return sendJson(res, 200, out);
  }
  // 酷我普通歌单（用户歌单）走 nplserver，字段与榜单不同，暂只回榜单
  throw new UpstreamError(`暂不支持该歌单类型：${raw}`, 404);
}

async function handleUrl(req, res, q) {
  const parsed = parseId(q.get('id'));
  if (!parsed) return sendJson(res, 400, { error: '缺少 id' });
  const quality = q.get('quality') || 'high';
  const extra = { name: q.get('name') || '', singer: q.get('singer') || '' };

  if (!LINGLAN_KEY) return sendJson(res, 200, { error: '桥接器未配置密钥' });

  try {
    const r = await resolveUrl(parsed.platform, parsed.songId, quality, extra);
    // 统一走 /stream 代理：酷我/网易云的 CDN 对 Referer/UA 有要求，
    // 且直链是 http，App 端直连可能被明文流量策略拦掉
    const proxy =
      `${selfBase(req)}/stream?id=${encodeURIComponent(q.get('id'))}` +
      `&quality=${encodeURIComponent(r.quality)}`;
    sendJson(res, 200, {
      url: proxy,
      quality: r.quality,
      expiresIn: Math.floor(IDLE_TTL / 1000),
    });
  } catch (e) {
    sendJson(res, 200, { error: e.message || '暂无版权' });
  }
}

async function handleStream(req, res, q) {
  const parsed = parseId(q.get('id'));
  if (!parsed) {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('缺少 id');
  }
  const quality = q.get('quality') || 'high';
  try {
    const r = await resolveUrl(parsed.platform, parsed.songId, quality, {});
    pipeUpstream(req, res, r.url);
  } catch (e) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end(e.message || '直链解析失败');
  }
}

async function handleLyric(req, res, q) {
  const parsed = parseId(q.get('id'));
  if (!parsed) return sendJson(res, 400, { error: '缺少 id' });
  if (parsed.platform !== 'kw') return sendJson(res, 200, { error: 'not found' });
  const lrc = await kwLyric(parsed.songId);
  if (!lrc) return sendJson(res, 200, { error: 'not found' });
  sendJson(res, 200, { lyric: lrc });
}

async function handlePic(req, res, q) {
  const parsed = parseId(q.get('id'));
  if (!parsed) {
    res.writeHead(400, { 'Content-Type': 'text/plain; charset=utf-8' });
    return res.end('缺少 id');
  }
  // 优先用 artistpicserver（覆盖全，含单曲封面）
  const picApi =
    `http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic` +
    `&pictype=500&size=500&rid=${encodeURIComponent(parsed.songId)}`;
  try {
    const text = (await fetchText(picApi)).trim();
    if (/^https?:\/\//.test(text)) return pipeUpstream(req, res, text);
  } catch { /* 落到 404 */ }
  res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('无封面');
}

const ROUTES = {
  '/search': (req, res, q) => handleSearch(req, res, q, selfBase(req)),
  '/playlists': (req, res) => handlePlaylists(req, res),
  '/playlist': (req, res, q) => handlePlaylist(req, res, q, selfBase(req)),
  '/url': (req, res, q) => handleUrl(req, res, q),
  '/stream': (req, res, q) => handleStream(req, res, q),
  '/lyric': (req, res, q) => handleLyric(req, res, q),
  '/pic': (req, res, q) => handlePic(req, res, q),
};

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const route = ROUTES[url.pathname];

  if (url.pathname === '/' || url.pathname === '/health') {
    return sendJson(res, 200, {
      ok: true,
      service: 'qingting-lx-bridge',
      platforms: Object.keys(PLATFORMS),
      keyConfigured: Boolean(LINGLAN_KEY),
      uptime: Math.floor(process.uptime()),
    });
  }
  if (!route) return sendJson(res, 404, { error: `未知接口 ${url.pathname}` });

  try {
    await route(req, res, url.searchParams);
  } catch (e) {
    if (res.headersSent) return res.destroy();
    const status = e instanceof UpstreamError ? e.status : 500;
    sendJson(res, status, { error: e.message || '内部错误' });
  }
});

server.listen(PORT, BIND, () => {
  console.log(`[轻听·LX桥接器] 已启动 http://${BIND}:${PORT}`);
  console.log(`  平台：${Object.values(PLATFORMS).map((p) => p.name).join(' / ')}`);
  console.log(`  密钥：${LINGLAN_KEY ? '已配置' : '未配置（仅搜索可用）'}`);
  console.log(`  直链来源：${LINGLAN_BASE}`);
});

process.on('SIGTERM', () => server.close(() => process.exit(0)));
process.on('SIGINT', () => server.close(() => process.exit(0)));
