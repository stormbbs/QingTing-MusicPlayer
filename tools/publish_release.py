#!/usr/bin/env python3
"""把打好的 APK 发到 GitHub Releases（api.github.com 通、github.com 不通时也能用）。

用法：
    python tools/publish_release.py v1.9 app/build/outputs/apk/debug/app-debug.apk

会做三件事：建 release（不存在时）、上传 APK、把 latest.json 里的
url / size / sha256 填成真实值。
"""
import hashlib
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

REPO = "stormbbs/QingTing-MusicPlayer"
API = "https://api.github.com"


def git(*args):
    return subprocess.run(["git", *args], capture_output=True, check=True).stdout.decode().strip()


def token():
    # 与 push_via_api.py 保持一致：优先一次性环境变量，其次本机凭据管理器。
    import os
    for name in ("GITHUB_TOKEN", "GH_TOKEN", "GITHUB_PAT"):
        value = os.environ.get(name, "").strip()
        if value:
            return value
    try:
        cred = subprocess.run(
            ["git", "credential", "fill"],
            input=b"protocol=https\nhost=github.com\n\n",
            capture_output=True,
            timeout=15,
        ).stdout.decode("utf-8", "replace")
    except subprocess.TimeoutExpired:
        cred = ""
    for line in cred.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    raise SystemExit("没有找到 GitHub 凭据：请设置环境变量 GITHUB_TOKEN，或先在 git 里登录一次")


def call(method, url, tok, body=None, raw=None, content_type=None):
    data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + tok)
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", content_type or "application/json")
    try:
        with urllib.request.urlopen(req, timeout=900) as resp:
            return json.loads(resp.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode()[:400]
        if e.code == 404:
            return {"_status": 404, "_detail": detail}
        raise SystemExit(f"{method} {url} -> {e.code}: {detail}")


def main():
    tag = sys.argv[1] if len(sys.argv) > 1 else "v1.9"
    apk = sys.argv[2] if len(sys.argv) > 2 else "app/build/outputs/apk/debug/app-debug.apk"
    manifest_path = "latest.json"
    manifest = json.load(open(manifest_path, encoding="utf-8"))
    version = manifest.get("versionName", tag)
    asset_name = f"qingting-{version}.apk"

    raw = open(apk, "rb").read()
    digest = hashlib.sha256(raw).hexdigest()
    print(f"APK {apk}  {len(raw)/1024/1024:.1f}MB  sha256={digest[:16]}…")

    tok = token()
    rel = call("GET", f"{API}/repos/{REPO}/releases/tags/{tag}", tok)
    if rel.get("_status") == 404:
        rel = call(
            "POST",
            f"{API}/repos/{REPO}/releases",
            tok,
            {
                "tag_name": tag,
                "target_commitish": "main",
                "name": f"轻听 {version}",
                "body": manifest.get("notes", "").replace("\\n", "\n"),
                "draft": False,
                "prerelease": False,
            },
        )
        print(f"已创建 release {tag}（id {rel['id']}）")
    else:
        print(f"release {tag} 已存在（id {rel['id']}）")

    assets = {a["name"]: a for a in rel.get("assets", [])}
    if asset_name in assets:
        call("DELETE", f"{API}/repos/{REPO}/releases/assets/{assets[asset_name]['id']}", tok)
        print("删掉同名旧附件")

    up = call(
        "POST",
        f"https://uploads.github.com/repos/{REPO}/releases/{rel['id']}/assets?name={asset_name}",
        tok,
        raw=raw,
        content_type="application/vnd.android.package-archive",
    )
    print(f"已上传 {asset_name}  ({up.get('size', 0)/1024/1024:.1f}MB)")

    url = up.get("browser_download_url") or f"https://github.com/{REPO}/releases/download/{tag}/{asset_name}"
    # 自建更新源用相对地址（qingting-latest.apk）。私有仓库的 Release 直链要登录才能下，
    # 写进清单会让手机端应用内升级 404，所以只有清单本来就是绝对地址时才替换。
    old_url = manifest.get("url", "")
    if old_url.startswith("http"):
        manifest["url"] = url
    manifest["size"] = len(raw)
    manifest["sha256"] = digest
    json.dump(manifest, open(manifest_path, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    open(manifest_path, "a", encoding="utf-8").write("\n")
    print("Release 附件：", url)
    print("latest.json 的 url 保持：", manifest["url"])


if __name__ == "__main__":
    main()
