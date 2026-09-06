#!/usr/bin/env python3
"""R2 发布工具 — 把 APK + version.json 上传到 Cloudflare R2 app-update 桶。

用法（构建完成后在仓库根执行）:
  python tools/r2_upload.py [apk_path] [--notes "..."]

- APK 默认取 android/app/build/outputs/apk/debug/ 下最新的 PhotographerCamera-*.apk
- versionName/versionCode 从 APK 文件名 + android/app/build.gradle.kts 解析
- 凭据从 ~/.workbuddy/r2_credentials.json 读取（不入库）:
    {"endpoint": "https://<account>.r2.cloudflarestorage.com",
     "access_key": "...", "secret_key": "...", "bucket": "app-update"}
- 公共域名 https://app.tybtool.top 即桶的自定义域，上传即发布。
"""
import json
import os
import re
import sys
import time

import boto3
import botocore

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APK_DIRS = [
    os.path.join(REPO, "android", "app", "build2", "outputs", "apk", "debug"),
    os.path.join(REPO, "android", "app", "build", "outputs", "apk", "debug"),
]
GRADLE = os.path.join(REPO, "android", "app", "build.gradle.kts")


def load_creds():
    p = os.path.expanduser("~/.workbuddy/r2_credentials.json")
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def find_apk():
    if len(sys.argv) > 1 and sys.argv[1].endswith(".apk"):
        return sys.argv[1]
    cands = [os.path.join(d, f) for d in APK_DIRS if os.path.isdir(d) for f in os.listdir(d) if f.endswith(".apk")]
    return max(cands, key=os.path.getmtime)


def version_from_gradle():
    s = open(GRADLE, encoding="utf-8").read()
    name = re.search(r'APP_VERSION_NAME = "([^"]+)"', s).group(1)
    code = re.search(r"APP_VERSION_CODE = (\d+)", s).group(1)
    return name, int(code)


def main():
    creds = load_creds()
    apk = find_apk()
    vname, vcode = version_from_gradle()
    size = os.path.getsize(apk)
    key = os.path.basename(apk)
    notes = ""
    if "--notes" in sys.argv:
        notes = sys.argv[sys.argv.index("--notes") + 1]

    s3 = boto3.client(
        "s3",
        endpoint_url=creds["endpoint"],
        aws_access_key_id=creds["access_key"],
        aws_secret_access_key=creds["secret_key"],
        region_name="auto",
        config=botocore.config.Config(
            proxies={}, connect_timeout=20, read_timeout=600,
            retries={"max_attempts": 3}),
    )
    print(f"upload {key} ({size/1048576:.1f} MB) ...")
    s3.upload_file(apk, creds["bucket"], key,
                   ExtraArgs={"ContentType": "application/vnd.android.package-archive"})
    ver = {
        "versionName": vname, "versionCode": vcode,
        "url": f"https://app.tybtool.top/{key}",
        "size": size, "ts": int(time.time() * 1000), "notes": notes,
    }
    s3.put_object(Bucket=creds["bucket"], Key="version.json",
                  Body=json.dumps(ver, ensure_ascii=False).encode("utf-8"),
                  ContentType="application/json", CacheControl="no-cache")
    print("published:", ver["url"])
    print("version.json:", json.dumps(ver, ensure_ascii=False))


if __name__ == "__main__":
    main()
