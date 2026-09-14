"""Upload the APK + version.json to R2 using the presigned URLs in _r2urls.txt.

Fallback for environments where curl is not on PATH. Reads the two URLs
produced by tools/_r2put.py (line 1 = APK, last line = version.json) and PUTs
the files. version.json is uploaded LAST because it is the release switch.
"""
import http.client
import json
import os
import ssl
import sys
import urllib.parse

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def put(url: str, path: str) -> int:
    parsed = urllib.parse.urlparse(url)
    ctx = ssl.create_default_context()
    conn = http.client.HTTPSConnection(parsed.netloc, context=ctx, timeout=600)
    size = os.path.getsize(path)
    with open(path, "rb") as fh:
        conn.putrequest("PUT", parsed.path + "?" + parsed.query)
        conn.putheader("Content-Length", str(size))
        conn.putheader("Expect", "")
        conn.endheaders()
        remaining = size
        while True:
            chunk = fh.read(4 << 20)
            if not chunk:
                break
            conn.send(chunk)
            remaining -= len(chunk)
    resp = conn.getresponse()
    body = resp.read(64)
    print("%s -> HTTP %s (%d bytes sent)" % (os.path.basename(path), resp.status, size))
    if resp.status >= 300:
        print(body)
    return resp.status


def main() -> int:
    urls = [l.strip() for l in open(os.path.join(ROOT, "_r2urls.txt"), encoding="utf-8") if l.strip()]
    meta = json.load(open(os.path.join(ROOT, "android", "_r2_version.json"), encoding="utf-8"))
    apk = os.path.join(
        ROOT, "android", "app", "build2", "outputs", "apk", "debug",
        "PhotographerCamera-%s.apk" % meta["versionName"],
    )
    if not os.path.exists(apk):
        print("APK not found: %s" % apk)
        return 1
    ok = put(urls[0], apk)
    if ok >= 300:
        return 1
    ok = put(urls[-1], os.path.join(ROOT, "android", "_r2_version.json"))
    return 0 if ok < 300 else 1


if __name__ == "__main__":
    sys.exit(main())
