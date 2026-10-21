#!/usr/bin/env python3
"""当 github.com:443 被网络阻断、但 api.github.com 还能通时，用 REST API 推一个提交。

用法：python tools/push_via_api.py [commit]
默认推 HEAD，前提是远端分支正好是这个提交的父提交（快进）。
"""
import base64
import json
import subprocess
import sys
import urllib.request

REPO = "stormbbs/QingTing-MusicPlayer"
BRANCH = "main"
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
    raise SystemExit("没有找到 GitHub 凭据，先在 git 里登录一次")


def call(method, path, body=None, tok=""):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(API + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("X-GitHub-Api-Version", "2022-11-28")
    if data:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} -> {e.code}: {e.read().decode()[:500]}")


def main():
    commit = sys.argv[1] if len(sys.argv) > 1 else git("rev-parse", "HEAD")
    parent = git("rev-parse", commit + "^")
    message = git("log", "-1", "--format=%B", commit).rstrip()
    author = git("log", "-1", "--format=%an", commit)
    author_email = git("log", "-1", "--format=%ae", commit)
    author_date = git("log", "-1", "--format=%aI", commit)
    committer = git("log", "-1", "--format=%cn", commit)
    committer_email = git("log", "-1", "--format=%ce", commit)
    committer_date = git("log", "-1", "--format=%cI", commit)

    tok = token()
    me = call("GET", "/user", tok=tok)
    print(f"登录身份：{me.get('login')}")

    ref = call("GET", f"/repos/{REPO}/git/ref/heads/{BRANCH}", tok=tok)
    remote_sha = ref["object"]["sha"]
    print(f"远端 {BRANCH} 当前：{remote_sha[:7]}  本地待推：{commit[:7]} 父：{parent[:7]}")
    if remote_sha == commit:
        print("远端已经是最新，无需推送")
        return
    if remote_sha != parent:
        raise SystemExit("远端不是这个提交的父提交，不能快进；请先手动同步历史")

    changes = []
    for line in git("diff", "--name-status", parent, commit).splitlines():
        status, path = line.split("\t", 1)
        changes.append((status[0], path))

    entries = []
    for status, path in changes:
        if status == "D":
            mode = git("ls-tree", parent, "--", path).split()[0]
            entries.append({"path": path, "mode": mode, "type": "blob", "sha": None})
            print(f"  删除 {path}")
            continue
        blob_sha = git("rev-parse", f"{commit}:{path}")
        mode = git("ls-tree", commit, "--", path).split()[0]
        raw = git("cat-file", "blob", blob_sha, binary=True)
        size_mb = len(raw) / 1024 / 1024
        if size_mb > 40:
            raise SystemExit(f"{path} 有 {size_mb:.1f}MB，超过 API 单文件上限")
        resp = call(
            "POST",
            f"/repos/{REPO}/git/blobs",
            {"content": base64.b64encode(raw).decode(), "encoding": "base64"},
            tok=tok,
        )
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": resp["sha"]})
        print(f"  {'新增' if status == 'A' else '修改'} {path}  {len(raw)} 字节")

    base_tree = git("rev-parse", parent + "^{tree}")
    tree = call(
        "POST",
        f"/repos/{REPO}/git/trees",
        {"base_tree": base_tree, "tree": entries},
        tok=tok,
    )
    new_commit = call(
        "POST",
        f"/repos/{REPO}/git/commits",
        {
            "message": message,
            "tree": tree["sha"],
            "parents": [remote_sha],
            "author": {"name": author, "email": author_email, "date": author_date},
            "committer": {
                "name": committer,
                "email": committer_email,
                "date": committer_date,
            },
        },
        tok=tok,
    )
    print(f"远端新提交：{new_commit['sha'][:7]}")

    call(
        "PATCH",
        f"/repos/{REPO}/git/refs/heads/{BRANCH}",
        {"sha": new_commit["sha"], "force": False},
        tok=tok,
    )
    print(f"已更新 {BRANCH} -> {new_commit['sha']}")
    print(f"https://github.com/{REPO}/commit/{new_commit['sha']}")


if __name__ == "__main__":
    main()
