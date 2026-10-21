#!/usr/bin/env python3
"""把 APK + latest.json 打成一个可以直接扔到服务器上的更新目录。

用法：
    python tools/make_update_bundle.py                 # 清单里写相对地址（推荐）
    python tools/make_update_bundle.py https://x.com/q/  # 写死绝对地址

产出 dist/update/：
    latest.json            清单，url 只写文件名 qingting-latest.apk
    qingting-latest.apk    安装包，文件名固定

清单里的地址是相对的，App 会按清单自己的地址拼出来。所以这套文件扔到任何
域名、任何目录都能用，换服务器也不用改清单；发新版覆盖这两个文件就行。
"""
import hashlib
import json
import os
import shutil
import sys
import zipfile

APK = "app/build/outputs/apk/debug/app-debug.apk"
OUT = "dist/update"
FIXED_NAME = "qingting-latest.apk"


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else ""
    if base and not base.endswith("/"):
        base += "/"

    manifest = json.load(open("latest.json", encoding="utf-8"))
    raw = open(APK, "rb").read()

    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    shutil.copy2(APK, os.path.join(OUT, FIXED_NAME))

    manifest["url"] = base + FIXED_NAME
    manifest["mirrorUrl"] = ""
    manifest["size"] = len(raw)
    manifest["sha256"] = hashlib.sha256(raw).hexdigest()
    with open(os.path.join(OUT, "latest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")

    readme = (
        "轻听更新源\n"
        "==========\n\n"
        "把本目录里的三个文件原样上传到服务器的同一个目录，例如 /www/wwwroot/qingting/：\n"
        "  latest.json          版本清单（App 靠它判断有没有新版）\n"
        f"  {FIXED_NAME}    安装包\n"
        "  说明.txt             本文件，可不上传\n\n"
        "上传后，用手机浏览器打开下面这条地址，应该能直接看到 JSON 内容\n"
        "（不要登录、不要跳转、不要 404）：\n"
        "  http://你的域名或IP/那个目录/latest.json\n\n"
        "然后在 App「我的 → 关于与更新 → 更新源」里填这条地址，保存后点「检查更新」。\n\n"
        "清单里的 APK 地址只写了文件名，App 会按清单自己的位置拼出完整地址。\n"
        "所以以后发新版：重新跑一次 tools/make_update_bundle.py，把 latest.json\n"
        f"和 {FIXED_NAME} 覆盖上传即可，地址永远不用再改。\n"
    )
    with open(os.path.join(OUT, "说明.txt"), "w", encoding="utf-8") as f:
        f.write(readme)

    zpath = os.path.join("dist", f"轻听-更新源-{manifest['versionName']}.zip")
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for f in sorted(os.listdir(OUT)):
            z.write(os.path.join(OUT, f), f)

    print(f"更新目录：{os.path.abspath(OUT)}")
    for f in sorted(os.listdir(OUT)):
        print(f"  {f}  {os.path.getsize(os.path.join(OUT, f))/1024/1024:.2f}MB")
    print("清单里写的地址：", manifest["url"], "（相对，按清单位置解析）" if not base else "")
    print("压缩包：", os.path.abspath(zpath))


if __name__ == "__main__":
    main()
