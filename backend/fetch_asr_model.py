"""从 hf-mirror 下载 sherpa-onnx 中文流式 ASR 模型（int8，约 24MB）。

国内镜像可用，GitHub release 大文件太慢。下载后把路径填进 config.json 的
sherpa.transducer_* 即可让 ASR 从 builtin 切到真实本地模型。

运行： python backend/fetch_asr_model.py
"""
from __future__ import annotations

import os
import sys
import time
import urllib.request

REPO = "csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23"
BASE = f"https://hf-mirror.com/{REPO}/resolve/main/"
FILES = [
    "encoder-epoch-99-avg-1.int8.onnx",
    "decoder-epoch-99-avg-1.int8.onnx",
    "joiner-epoch-99-avg-1.int8.onnx",
    "tokens.txt",
    "test_wavs/0.wav",
]
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "models", "asr-zh-14M")


def fetch(name: str, attempts: int = 4) -> bool:
    rel = name.replace("/", os.sep)
    dst = os.path.join(OUT, rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.isfile(dst) and os.path.getsize(dst) > 100:
        print(f"SKIP  {name} (already {os.path.getsize(dst)} bytes)", flush=True)
        return True
    url = BASE + name
    for i in range(attempts):
        try:
            t = time.time()
            req = urllib.request.Request(url, headers={"User-Agent": "trace/1.0"})
            with urllib.request.urlopen(req, timeout=90) as r:
                data = r.read()
            with open(dst, "wb") as f:
                f.write(data)
            print(f"OK    {name} {len(data)} bytes in {time.time()-t:.1f}s", flush=True)
            return True
        except Exception as e:
            print(f"RETRY {name} #{i+1} {type(e).__name__}: {str(e)[:80]}", flush=True)
            if os.path.isfile(dst):
                try:
                    os.remove(dst)
                except OSError:
                    pass
            time.sleep(1)
    return False


def main():
    os.makedirs(OUT, exist_ok=True)
    ok = 0
    for f in FILES:
        ok += 1 if fetch(f) else 0
    print(f"DONE {ok}/{len(FILES)} files in {OUT}", flush=True)
    return 0 if ok >= 4 else 1


if __name__ == "__main__":
    sys.exit(main())
