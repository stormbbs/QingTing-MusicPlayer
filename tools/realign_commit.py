#!/usr/bin/env python3
"""把远端刚创建的提交对象在本地重建出来，并把本地分支对齐过去。

API 建的提交和本地提交内容一致，但提交对象的字节（消息末尾换行等）可能不同，
导致 sha 不一致。这里按远端提交的元数据重建原始提交对象，写入本地对象库，
再把本地分支指到它，避免以后 push 出现历史分叉。
"""
import json
import subprocess
import sys
import urllib.request

REPO = "stormbbs/QingTing-MusicPlayer"
API = "https://api.github.com"


def git(*args, binary=False):
    out = subprocess.run(["git", *args], capture_output=True, check=True).stdout
    return out if binary else out.decode("utf-8", "replace").strip()


def token():
    cred = subprocess.run(
        ["git", "credential", "fill"],
        input=b"protocol=https\nhost=github.com\n\n",
        capture_output=True,
    ).stdout.decode("utf-8", "replace")
    for line in cred.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    raise SystemExit("没有找到 GitHub 凭据")


def get(path, tok):
    req = urllib.request.Request(API + path)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/vnd.github+json")
    with urllib.request.urlopen(req, timeout=60) as resp:
        return json.loads(resp.read().decode())


def ident(who, when, force_tz=None):
    # 2026-10-21T14:20:00+08:00 -> "1761000000 +0800"
    import datetime
    dt = datetime.datetime.fromisoformat(when.replace("Z", "+00:00"))
    epoch = int(dt.timestamp())
    if force_tz:
        return f"{who['name']} <{who['email']}> {epoch} {force_tz}"
    off = dt.utcoffset() or datetime.timedelta(0)
    minutes = int(off.total_seconds() // 60)
    sign = "+" if minutes >= 0 else "-"
    minutes = abs(minutes)
    tz = f"{sign}{minutes // 60:02d}{minutes % 60:02d}"
    return f"{who['name']} <{who['email']}> {epoch} {tz}"


def main():
    target = sys.argv[1]
    tok = token()
    c = get(f"/repos/{REPO}/git/commits/{target}", tok)
    local_tree = git("rev-parse", "999f90a^{tree}")
    print("本地 999f90a 的 tree:", local_tree)
    print("远端提交的 tree   :", c["tree"]["sha"])
    if local_tree != c["tree"]["sha"]:
        raise SystemExit("tree 不一致，内容有差异，请人工检查")

    print("author 时间:", c["author"]["date"])
    print("committer 时间:", c["committer"]["date"])

    msg = c["message"]
    if not msg.endswith("\n"):
        msg += "\n"

    def build(a_tz, c_tz, tail_newline=True):
        header = [f"tree {c['tree']['sha']}"]
        for p in c["parents"]:
            header.append(f"parent {p['sha']}")
        header.append("author " + ident(c["author"], c["author"]["date"], a_tz))
        header.append("committer " + ident(c["committer"], c["committer"]["date"], c_tz))
        body = msg if tail_newline else msg.rstrip("\n")
        return ("\n".join(header) + "\n\n" + body).encode()

    sha = None
    for a_tz in (None, "+0800", "+0000"):
        for c_tz in (None, "+0800", "+0000"):
            for tail in (True, False):
                raw = build(a_tz, c_tz, tail)
                got = subprocess.run(
                    ["git", "hash-object", "-t", "commit", "--stdin"],
                    input=raw, capture_output=True,
                ).stdout.decode().strip()
                if got == target:
                    sha = subprocess.run(
                        ["git", "hash-object", "-t", "commit", "--stdin", "-w"],
                        input=raw, capture_output=True,
                    ).stdout.decode().strip()
                    print(f"匹配：author_tz={a_tz} committer_tz={c_tz} 尾换行={tail}")
                    break
            if sha:
                break
        if sha:
            break

    if not sha:
        raise SystemExit("重建失败（换行/时区形式都对不上），未改动本地分支")
    git("update-ref", "refs/heads/main", target, "999f90a")
    print("本地 main 已对齐到", target[:7])
    print(git("log", "--oneline", "-3"))


if __name__ == "__main__":
    main()
