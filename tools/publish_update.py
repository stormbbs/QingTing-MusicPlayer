#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把当前代码打成一个「应用内可升级」的发布包。

产物放在 dist/：
  dist/qingting-<versionName>.apk   安装包（文件名用 ASCII，方便直接当下载地址）
  dist/latest.json                  更新清单，App 检查更新时读的就是它

用法：
  # 只打包并生成清单（URL 用默认的 GitHub Release 地址）
  python tools/publish_update.py --build

  # 打包 + 指定 APK 的实际下载地址（放自己服务器 / 网盘直链都行）
  python tools/publish_update.py --build --url https://example.com/qingting-1.7-1021.apk

  # 打包 + 在本机起一个 HTTP 服务，方便同一个 Wi-Fi 下用手机直接升级
  python tools/publish_update.py --build --serve
  #   → 把打印出来的 http://192.168.x.x:8765/latest.json 填到
  #     App「我的 → 关于与更新 → 更新源」里

说明：
- 升级是新版本覆盖安装，签名必须和手机上已装的版本一致。一直用同一台机器
  （同一个 debug.keystore）打包就没问题；换了签名只能卸载重装一次。
- 清单里的 versionCode 必须比手机上的大，App 才会认为「有新版本」。
"""

import argparse
import hashlib
import http.server
import json
import os
import re
import shutil
import socket
import socketserver
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle"
DIST = ROOT / "dist"
DEFAULT_REPO = "https://github.com/stormbbs/QingTing-MusicPlayer"


def read_version() -> tuple[int, str]:
    text = GRADLE.read_text(encoding="utf-8")
    code = re.search(r"versionCode\s+(\d+)", text)
    name = re.search(r"versionName\s+'([^']+)'", text)
    if not code or not name:
        sys.exit("读不到 versionCode / versionName，检查 app/build.gradle")
    return int(code.group(1)), name.group(1)


def build_apk() -> Path:
    java_home = os.environ.get(
        "JAVA_HOME", r"C:/Users/KWong/.workbuddy/binaries/jdk/jdk17/jdk-17.0.20.1+1"
    )
    sdk = os.environ.get("ANDROID_HOME", r"C:/Users/KWong/AppData/Local/Android/Sdk")
    gradle = os.environ.get(
        "GRADLE_BIN",
        os.path.expanduser("~/.workbuddy/binaries/gradle/gradle-8.11.1/bin/gradle"),
    )
    env = dict(os.environ, JAVA_HOME=java_home, ANDROID_HOME=sdk)
    # Gradle 的 JVM 不走系统代理，依赖解析会卡在 maven/google 上，这里显式传进去
    proxy = os.environ.get("https_proxy") or os.environ.get("http_proxy")
    if proxy:
        host, _, port = proxy.replace("http://", "").partition(":")
        env["GRADLE_OPTS"] = (
            f"-Dhttp.proxyHost={host} -Dhttp.proxyPort={port} "
            f"-Dhttps.proxyHost={host} -Dhttps.proxyPort={port}"
        )
    print("→ 编译 debug APK …")
    subprocess.run(
        [gradle, "assembleDebug", "--no-daemon", "-q"],
        cwd=str(ROOT),
        env=env,
        check=True,
    )
    apk = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
    if not apk.exists():
        sys.exit("编译结束但没找到 app-debug.apk")
    return apk


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--build", action="store_true", help="先跑 gradle 编译")
    parser.add_argument("--url", default="", help="APK 的实际下载地址")
    parser.add_argument("--mirror", default="", help="备用下载地址（可选）")
    parser.add_argument("--notes", default="", help="更新说明，留空则沿用已有 latest.json")
    parser.add_argument("--serve", action="store_true", help="生成后在本机起 HTTP 服务")
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()

    code, name = read_version()
    DIST.mkdir(exist_ok=True)

    apk = build_apk() if args.build else (
        DIST / f"qingting-{name}.apk"
    )
    target = DIST / f"qingting-{name}.apk"
    if args.build:
        shutil.copy2(apk, target)
    if not target.exists():
        sys.exit(f"没有安装包：{target}（先加 --build 编译一次）")

    notes = args.notes
    old = DIST / "latest.json"
    if not notes and old.exists():
        notes = json.loads(old.read_text(encoding="utf-8")).get("notes", "")

    url = args.url or f"{DEFAULT_REPO}/releases/download/v{name.split('-')[0]}/qingting-{name}.apk"
    manifest = {
        "versionCode": code,
        "versionName": name,
        "url": url,
        "mirrorUrl": args.mirror,
        "size": target.stat().st_size,
        "sha256": sha256_of(target),
        "notes": notes,
        "mandatory": False,
    }
    (DIST / "latest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    print(f"✓ 安装包：{target}  ({target.stat().st_size/1024/1024:.1f} MB)")
    print(f"✓ 清单：  {DIST / 'latest.json'}")
    print(f"  versionCode {code} / {name}")
    print(f"  下载地址 {url}")

    if not args.url:
        print("\n注意：上面是默认的 GitHub Release 地址。要么把 APK 传到这个 Release，")
        print("      要么重新跑一次并带上 --url 指向你实际存放的地方。")

    if args.serve:
        os.chdir(DIST)
        handler = http.server.SimpleHTTPRequestHandler
        with socketserver.TCPServer(("0.0.0.0", args.port), handler) as httpd:
            ip = local_ip()
            print(f"\n→ 手机和电脑连同一个 Wi-Fi，然后在 App 里填更新源：")
            print(f"   http://{ip}:{args.port}/latest.json")
            print("   （模拟器用 http://10.0.2.2:%d/latest.json）" % args.port)
            print("   Ctrl+C 结束\n")
            try:
                httpd.serve_forever()
            except KeyboardInterrupt:
                print("已停止")


def local_ip() -> str:
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        return ip
    except OSError:
        return "127.0.0.1"


if __name__ == "__main__":
    main()
