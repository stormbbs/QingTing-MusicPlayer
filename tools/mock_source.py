"""QingTing 在线音源协议 v1 —— 参考实现 / 自测服务端。

只用 Python 标准库，无第三方依赖。把任意一个音频目录当成一个曲库，
提供协议里的全套接口，用于：
  1) 给想写自己音源的人当样例；
  2) 给 App 做端到端自测（模拟器用 adb reverse 就能访问到本机端口）。

用法：
    python mock_source.py [端口] [音频目录]
    python mock_source.py 18080 ./music

可选的环境变量：
    SRC_USER / SRC_PASS   开启 Basic 鉴权（不设就不鉴权）
"""
import email.utils
import json
import os
import re
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

AUDIO_EXT = {'.mp3', '.wav', '.flac', '.m4a', '.aac', '.ogg', '.opus', '.wma', '.mka'}

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18080
ROOT = os.path.abspath(sys.argv[2]) if len(sys.argv) > 2 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), 'music')
USER = os.environ.get('SRC_USER', '')
PASS = os.environ.get('SRC_PASS', '')

# 音质档位：mock 源手里只有一份文件，所以三档都返回同一个地址，
# 只在返回的 quality 字段里如实说明实际给了哪一档。
QUALITIES = ['standard', 'high', 'lossless']


# ----------------------------------------------------------------- 曲库

def _read_duration(path):
    """尽量读出时长（毫秒）。读不出来就给 0，App 播到时会自己算。"""
    try:
        from mutagen import File as MutagenFile  # 可选依赖，没有就算了
        info = MutagenFile(path).info
        return int(getattr(info, 'length', 0) * 1000)
    except Exception:
        return 0


def build_catalog():
    """扫描目录，按文件名排序建索引。id 用相对路径的 URL-safe 编码。"""
    items = []
    for name in sorted(os.listdir(ROOT)):
        path = os.path.join(ROOT, name)
        if not os.path.isfile(path) or os.path.splitext(name)[1].lower() not in AUDIO_EXT:
            continue
        base = os.path.splitext(name)[0]
        # id 用原始文件名。查询串里的中文会被 parse_qs 解码回来，
        # 如果这里存的是编码后的形式，/url?id=... 就永远对不上。
        items.append({
            'id': name,
            'name': base,
            'artist': '未知艺术家',
            'album': '本地测试曲库',
            'duration': _read_duration(path),
            'cover': '',
            'qualities': QUALITIES,
            '_path': path,
        })
    return items


CATALOG = build_catalog()
BY_ID = {s['id']: s for s in CATALOG}

# 两个歌单：一个装全部，一个装前两首，够验证「点歌单播全部」和「歌单详情」。
PLAYLISTS = [
    {'id': 'all', 'name': '全部测试曲目', 'count': len(CATALOG), 'desc': '曲库里的每一首'},
    {'id': 'top2', 'name': '精选两首', 'count': min(2, len(CATALOG)), 'desc': '只放两首，测分页'},
]


def playlist_songs(pid):
    if pid == 'all':
        return CATALOG
    if pid == 'top2':
        return CATALOG[:2]
    return []


def public(song):
    """去掉内部字段后再返回给客户端。"""
    return {k: v for k, v in song.items() if not k.startswith('_')}


# ----------------------------------------------------------------- 服务器

class Handler(BaseHTTPRequestHandler):
    server_version = 'QingTingMockSource/1.0'
    protocol_version = 'HTTP/1.1'

    # ---- 工具 ----

    def log_message(self, fmt, *args):  # 保持安静，免得刷屏
        pass

    def _auth_ok(self):
        if not USER and not PASS:
            return True
        header = self.headers.get('Authorization', '')
        if not header.startswith('Basic '):
            return False
        import base64
        try:
            raw = base64.b64decode(header[6:]).decode('utf-8')
        except Exception:
            return False
        user, _, pwd = raw.partition(':')
        return user == USER and pwd == PASS

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _error(self, text, code=404):
        self._json({'error': text}, code)

    def _query(self):
        parsed = urllib.parse.urlparse(self.path)
        return urllib.parse.parse_qs(parsed.query), parsed.path.rstrip('/')

    def _first(self, q, key, default=''):
        v = q.get(key)
        return v[0] if v else default

    # ---- 路由 ----

    def do_GET(self):
        if not self._auth_ok():
            self.send_response(401)
            self.send_header('WWW-Authenticate', 'Basic realm="mock-source"')
            self.send_header('Content-Length', '0')
            self.end_headers()
            return
        q, path = self._query()
        if path == '/search':
            return self._search(q)
        if path == '/playlists':
            return self._json({'playlists': PLAYLISTS})
        if path == '/playlist':
            return self._playlist(q)
        if path == '/url':
            return self._url(q)
        if path == '/lyric':
            return self._lyric(q)
        if path == '/file':
            return self._file(q)
        if path == '/pic':
            return self._error('no cover', 404)
        if path in ('', '/'):
            return self._json({'ok': True, 'name': 'QingTing mock source',
                               'protocol': 'v1', 'songs': len(CATALOG)})
        self._error('unknown endpoint: %s' % path)

    def do_HEAD(self):
        self.do_GET()

    # ---- 各接口 ----

    def _search(self, q):
        keyword = self._first(q, 'keyword').strip().lower()
        limit = int(self._first(q, 'limit', '30') or 30)
        page = int(self._first(q, 'page', '1') or 1)
        hits = [s for s in CATALOG
                if not keyword
                or keyword in s['name'].lower()
                or keyword in s['artist'].lower()
                or keyword in s['album'].lower()]
        start = (page - 1) * limit
        return self._json({
            'total': len(hits),
            'songs': [public(s) for s in hits[start:start + limit]],
        })

    def _playlist(self, q):
        pid = self._first(q, 'id')
        songs = playlist_songs(pid)
        if pid not in ('all', 'top2'):
            return self._error('歌单不存在: %s' % pid)
        return self._json({'name': next(p['name'] for p in PLAYLISTS if p['id'] == pid),
                           'songs': [public(s) for s in songs]})

    def _url(self, q):
        sid = self._first(q, 'id')
        quality = self._first(q, 'quality', 'high')
        song = BY_ID.get(sid)
        if song is None:
            return self._error('歌曲不存在')
        if quality not in QUALITIES:
            quality = 'high'
        # 直链指回本服务的 /file；带个 expiresIn 方便验证「直链时效」逻辑。
        return self._json({
            'url': 'http://%s/file?id=%s&quality=%s'
                   % (self.headers.get('Host'), urllib.parse.quote(sid), quality),
            'quality': quality,
            'expiresIn': 600,
            'size': os.path.getsize(song['_path']) if os.path.exists(song['_path']) else 0,
        })

    def _lyric(self, q):
        song = BY_ID.get(self._first(q, 'id'))
        if song is None:
            return self._error('歌曲不存在')
        # 造一段简单 LRC，够验证滚动歌词跟着走
        return self._json({'lyric': '[00:00.00] %s\n[00:01.00]第一行\n[00:03.00]第二行\n'
                                    % song['name']})

    def _file(self, q):
        """实际音频流，支持 Range —— 不然进度条拖不动。"""
        song = BY_ID.get(self._first(q, 'id'))
        if song is None:
            return self._error('歌曲不存在')
        path = song['_path']
        if not os.path.exists(path):
            return self._error('文件没了')
        size = os.path.getsize(path)
        rng = self.headers.get('Range')
        start, end = 0, size - 1
        if rng and rng.startswith('bytes='):
            m = re.match(r'bytes=(\d*)-(\d*)', rng)
            if m:
                start = int(m.group(1)) if m.group(1) else 0
                end = int(m.group(2)) if m.group(2) else size - 1
        end = min(end, size - 1)
        length = end - start + 1
        self.send_response(206 if rng else 200)
        self.send_header('Content-Type', 'audio/mpeg')
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', str(length))
        if rng:
            self.send_header('Content-Range', 'bytes %d-%d/%d' % (start, end, size))
        self.send_header('Last-Modified', email.utils.formatdate(os.path.getmtime(path), usegmt=True))
        self.end_headers()
        with open(path, 'rb') as f:
            f.seek(start)
            left = length
            while left > 0:
                chunk = f.read(min(65536, left))
                if not chunk:
                    break
                self.wfile.write(chunk)
                left -= len(chunk)


def main():
    if not os.path.isdir(ROOT):
        print('音频目录不存在: %s' % ROOT)
        return 1
    print('mock source: http://0.0.0.0:%d  <- %s  (%d 首)' % (PORT, ROOT, len(CATALOG)))
    print('接口: /search /playlists /playlist /url /lyric /file')
    ThreadingHTTPServer(('0.0.0.0', PORT), Handler).serve_forever()
    return 0


if __name__ == '__main__':
    sys.exit(main())
