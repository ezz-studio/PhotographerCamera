#!/usr/bin/env python3
"""R2 presigned uploader for the Studio deploy bundle (stdlib only).

把本地文件上传到 <bucket>/studio/<key>：先用 SigV4 预签名 PUT（S3 query
auth）做 PUT，再生成该对象的预签名 GET URL（默认 7 天有效）打印到 stdout，
供目标服务器下载。无任何第三方依赖，签名逻辑与 tools/_r2put.py 一致。

关键点（踩坑）：curl PUT 必须带 -H "Expect:"，否则 100-continue 下只传
几 MB 就假成功（与 APK 上传同坑）。

Usage:
  python r2_upload.py --file path/to/bundle.tar.gz --key studio/foo.tar.gz
stdout: 预签名 GET URL（供脚本捕获）；stderr: 进度/sha256
"""
import argparse
import hashlib
import hmac
import json
import os
import subprocess
import sys
import urllib.parse
from datetime import datetime, timezone

CREDS_PATH = os.path.expanduser("~/.workbuddy/r2_credentials.json")
PUT_EXPIRES = 3600
GET_EXPIRES = 7 * 24 * 3600  # 7 天，足够服务器端部署时拉取


def load_creds():
    with open(CRED_PATH := CREDS_PATH, encoding="utf-8") as f:
        c = json.load(f)
    return c["endpoint"].rstrip("/"), c["access_key"], c["secret_key"], c["bucket"]


def _hmac(key: bytes, msg: str) -> bytes:
    return hmac.new(key, msg.encode("utf-8"), hashlib.sha256).digest()


def presign(endpoint, ak, sk, bucket, method, key, expires):
    """返回 method 对应对象的预签名 URL（S3 query auth）。"""
    host = urllib.parse.urlparse(endpoint).netloc
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
        method + "\n" + canon_uri + "\n" + canon_query + "\n"
        + f"host:{host}\n" + "\n"
        + "host\n" + "UNSIGNED-PAYLOAD"
    )
    string_to_sign = (
        "AWS4-HMAC-SHA256\n" + amzdate + "\n" + scope + "\n"
        + hashlib.sha256(canonical_request.encode("utf-8")).hexdigest()
    )
    k = _hmac(("AWS4" + sk).encode(), datestamp)
    k = _hmac(k, region)
    k = _hmac(k, service)
    k = _hmac(k, "aws4_request")
    signature = hmac.new(k, string_to_sign.encode(), hashlib.sha256).hexdigest()
    return f"{endpoint}/{bucket}/{key}?{canon_query}&X-Amz-Signature={signature}"


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True, help="本地待上传文件")
    ap.add_argument("--key", required=True, help="R2 对象键，例如 studio/foo.tar.gz")
    ap.add_argument("--get-expires", type=int, default=GET_EXPIRES)
    args = ap.parse_args()

    if not os.path.isfile(args.file):
        raise SystemExit(f"file not found: {args.file}")
    endpoint, ak, sk, bucket = load_creds()

    put_url = presign(endpoint, ak, sk, bucket, "PUT", args.key, PUT_EXPIRES)
    print(f"==> PUT {args.key} -> {endpoint}/{bucket}/{args.key}", file=sys.stderr)
    # 不加 -H "Expect:" 会在 100-continue 下只传几 MB 假成功
    subprocess.run(["curl", "-sS", "-T", args.file, "-H", "Expect:", put_url], check=True)

    get_url = presign(endpoint, ak, sk, bucket, "GET", args.key, args.get_expires)
    sha = sha256_file(args.file)
    print(f"sha256={sha}", file=sys.stderr)
    # 仅把 GET URL 打到 stdout，方便被 deploy.sh $(...) 捕获
    print(get_url)


if __name__ == "__main__":
    main()
