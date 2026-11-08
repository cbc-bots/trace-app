"""下载 sherpa-onnx 官方 Android AAR 到 app/libs/sherpa-onnx.aar。

运行： python fetch_aar.py
放好后重新 Gradle Sync，APK 里的本地离线 ASR 即启用（还需按 docs/SHERPA.md 补一段接线）。
"""
from __future__ import annotations

import os
import sys
import time
import urllib.request

VERSION = "2.0.1"
NAME = f"sherpa-onnx-{VERSION}.aar"
URLS = [
    f"https://gh-proxy.com/https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/{NAME}",
    f"https://github.com/k2-fsa/sherpa-onnx/releases/download/v{VERSION}/{NAME}",
]
HERE = os.path.dirname(os.path.abspath(__file__))
DST = os.path.join(HERE, "app", "libs", "sherpa-onnx.aar")


def main():
    os.makedirs(os.path.dirname(DST), exist_ok=True)
    if os.path.isfile(DST) and os.path.getsize(DST) > 40_000_000:
        print("已存在:", DST, os.path.getsize(DST), "bytes")
        return 0
    for url in URLS:
        for attempt in range(3):
            try:
                t = time.time()
                req = urllib.request.Request(url, headers={"User-Agent": "trace/1.0"})
                with urllib.request.urlopen(req, timeout=180) as r:
                    data = r.read()
                with open(DST, "wb") as f:
                    f.write(data)
                print(f"OK {DST} {len(data)} bytes in {time.time()-t:.1f}s")
                return 0
            except Exception as e:
                print(f"重试 {url[:60]} #{attempt+1}: {type(e).__name__} {str(e)[:80]}", flush=True)
                time.sleep(1)
    print("下载失败：请手动下载后重命名为 app/libs/sherpa-onnx.aar")
    print("地址:", URLS[1])
    return 1


if __name__ == "__main__":
    sys.exit(main())
