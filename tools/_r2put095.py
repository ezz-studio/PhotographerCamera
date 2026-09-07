#!/usr/bin/env python3
"""R2 presign uploader for 0.9.5 (stdlib only, NO network in this script).

Derived from _r2put094.py. Builds version.json + presigned PUT URLs
(SigV4 query auth), then the shell does the actual transfer with curl -T.

Usage: python _r2put095.py  ->  writes _r2urls.txt (2 lines: apk_url, json_url)
                                writes android/_r2_version.json payload
"""
import hashlib
import hmac
import json
import os
import time
import urllib.parse
from datetime import datetime, timezone

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

with open(os.path.expanduser("~/.workbuddy/r2_credentials.json"), encoding="utf-8") as f:
    creds = json.load(f)

endpoint = creds["endpoint"].rstrip("/")
ak, sk, bucket = creds["access_key"], creds["secret_key"], creds["bucket"]
host = urllib.parse.urlparse(endpoint).netloc

APK_PATH = os.path.join(REPO, "android", "app", "build2", "outputs", "apk", "debug", "PhotographerCamera-0.9.5.apk")
apk_size = os.path.getsize(APK_PATH)

version_payload = {
    "versionName": "0.9.5",
    "versionCode": 35,
    "url": "https://app.tybtool.top/PhotographerCamera-0.9.5.apk",
    "size": apk_size,
    "ts": int(time.time() * 1000),
    "notes": ("0.9.5: fix JPEG MAX / RAW MAX capture failures with upstream fallback "
              "(fixed-focus snapshot continues multi-frame capture instead of dropping "
              "the shot, isCapturing state reset) and fix in-app update installing a "
              "stale APK (versioned download filename, old download tasks canceled, "
              "size verified against version.json, leftover APKs purged after install)"),
}
json_out = os.path.join(REPO, "android", "_r2_version.json")
with open(json_out, "w", encoding="utf-8") as f:
    json.dump(version_payload, f, ensure_ascii=False)


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
    presign_put("PhotographerCamera-0.9.5.apk"),
    presign_put("version.json"),
]
out = os.path.join(REPO, "_r2urls.txt")
with open(out, "w", encoding="utf-8") as f:
    f.write("\n".join(urls) + "\n")

print(f"apk_size={apk_size}")
print(f"version_payload -> {json_out}")
print(f"urls -> {out}")
