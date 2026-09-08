#!/usr/bin/env python3
"""R2 presign uploader — version-agnostic (stdlib only, NO network here).

Replaces the per-version _r2putNNN.py copies. Reads APP_VERSION_NAME /
APP_VERSION_CODE from android/app/build.gradle.kts (single source of truth),
locates the APK under android/app/build*/outputs/apk/debug, builds
version.json + presigned PUT URLs (SigV4 query auth). The shell does the
actual transfer with curl -T.

Usage:
  python tools/_r2put.py                       # notes from latest commit
  python tools/_r2put.py --notes "0.9.7: ..."  # explicit release notes
  python tools/_r2put.py --notes-file FILE

Writes: _r2urls.txt (line1=apk_url, line2=json_url), android/_r2_version.json
"""
import argparse
import hashlib
import hmac
import json
import os
import re
import subprocess
import time
import urllib.parse
from datetime import datetime, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GRADLE_KTS = os.path.join(REPO, "android", "app", "build.gradle.kts")
PUBLIC_BASE = "https://app.tybtool.top"


def read_version():
    text = open(GRADLE_KTS, encoding="utf-8").read()
    name = re.search(r'APP_VERSION_NAME\s*=\s*"([^"]+)"', text)
    code = re.search(r"APP_VERSION_CODE\s*=\s*(\d+)", text)
    if not name or not code:
        raise SystemExit(f"cannot parse APP_VERSION_* from {GRADLE_KTS}")
    return name.group(1), int(code.group(1))


def find_apk(version: str) -> str:
    apk_name = f"PhotographerCamera-{version}.apk"
    # 1.0.0 起正式发布走 release 包（R8 minified + debug key 签名，可原地升级）；
    # release 目录优先，找不到再回退 debug 目录（历史流程兼容）。
    roots = [
        os.path.join(REPO, "android", "app", "build2", "outputs", "apk", "release"),
        os.path.join(REPO, "android", "app", "build", "outputs", "apk", "release"),
        os.path.join(REPO, "android", "app", "build2", "outputs", "apk", "debug"),
        os.path.join(REPO, "android", "app", "build", "outputs", "apk", "debug"),
    ]
    for root in roots:
        path = os.path.join(root, apk_name)
        if os.path.isfile(path):
            return path
    raise SystemExit(f"APK not found: {apk_name} (searched build2/build release+debug dirs)")


def default_notes(version: str) -> str:
    try:
        msg = subprocess.run(
            ["git", "log", "-1", "--format=%s"],
            cwd=REPO, capture_output=True, text=True, timeout=10,
        ).stdout.strip()
    except Exception:
        msg = ""
    return f"{version}: {msg}" if msg else f"{version}: bugfix update"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--notes", help="release notes for version.json")
    ap.add_argument("--notes-file", help="file containing release notes")
    args = ap.parse_args()

    version, code = read_version()
    apk_path = find_apk(version)
    apk_size = os.path.getsize(apk_path)

    if args.notes_file:
        notes = open(args.notes_file, encoding="utf-8").read().strip()
    elif args.notes:
        notes = args.notes.strip()
    else:
        notes = default_notes(version)

    version_payload = {
        "versionName": version,
        "versionCode": code,
        "url": f"{PUBLIC_BASE}/PhotographerCamera-{version}.apk",
        "size": apk_size,
        "ts": int(time.time() * 1000),
        "notes": notes,
    }
    json_out = os.path.join(REPO, "android", "_r2_version.json")
    with open(json_out, "w", encoding="utf-8") as f:
        json.dump(version_payload, f, ensure_ascii=False)

    with open(os.path.expanduser("~/.workbuddy/r2_credentials.json"), encoding="utf-8") as f:
        creds = json.load(f)
    endpoint = creds["endpoint"].rstrip("/")
    ak, sk, bucket = creds["access_key"], creds["secret_key"], creds["bucket"]
    host = urllib.parse.urlparse(endpoint).netloc

    def hmac_sha256(key: bytes, msg: str) -> bytes:
        return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()

    def presign_put(key: str, expires: int = 3600) -> str:
        region, service = "auto", "s3"
        now = datetime.now(timezone.utc)
        amzdate = now.strftime("%Y%m%dT%H%M%SZ")
        datestamp = now.strftime("%Y%m%d")
        scope = f"{datestamp}/{region}/{service}/aws4_request"
        q = {
            "X-Amz-Algorithm": "AWS4-HMAC-SHA256",
            "X-Amz-Credential": f"{ak}/{scope}",
            "X-Amz-Date": amzdate,
            "X-Amz-Expires": str(expires),
            "X-Amz-SignedHeaders": "host",
        }
        canon_query = "&".join(
            f"{k}={urllib.parse.quote(v, safe='~')}" for k, v in sorted(q.items())
        )
        canon_uri = f"/{bucket}/{urllib.parse.quote(key, safe='/~')}"
        canonical_request = (
            "PUT\n" + canon_uri + "\n" + canon_query + "\n"
            + f"host:{host}\n" + "\n"
            + "host\n" + "UNSIGNED-PAYLOAD"
        )
        string_to_sign = (
            "AWS4-HMAC-SHA256\n" + amzdate + "\n" + scope + "\n"
            + hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()
        )
        k_date = hmac_sha256(("AWS4" + sk).encode("utf-8"), datestamp)
        k_region = hmac_sha256(k_date, region)
        k_service = hmac_sha256(k_region, service)
        k_signing = hmac_sha256(k_service, "aws4_request")
        signature = hmac.new(k_signing, string_to_sign.encode("utf-8"), hashlib.sha256).hexdigest()
        return f"{endpoint}/{bucket}/{key}?{canon_query}&X-Amz-Signature={signature}"

    urls = [
        presign_put(f"PhotographerCamera-{version}.apk"),
        presign_put("version.json"),
    ]
    out = os.path.join(REPO, "_r2urls.txt")
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(urls) + "\n")

    print(f"version={version} code={code}")
    print(f"apk={apk_path} size={apk_size}")
    print(f"version_payload -> {json_out}")
    print(f"urls -> {out}")


if __name__ == "__main__":
    main()
