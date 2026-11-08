"""可选升级：下载 SenseVoice 中文 ASR 大模型（比 24MB 的 zh-14M 准得多）。

模型来自 hf-mirror（国内可达），放进 backend/models/sense-voice/，
填好 config.json 的 sherpa.sense_voice_* 后，ASR 自动切到 SenseVoice。

运行： python backend/fetch_big_asr.py
体积约 230MB，国内镜像通常几分钟。不下载也不影响现有 24MB 模型正常工作。
"""
from __future__ import annotations

import os
import sys
import time
import urllib.request

REPO = "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17"
BASE = f"https://hf-mirror.com/{REPO}/resolve/main/"
FILES = ["model.int8.onnx", "tokens.txt"]
HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "models", "sense-voice")


def fetch(name: str, attempts: int = 4) -> bool:
    dst = os.path.join(OUT, name)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    if os.path.isfile(dst) and os.path.getsize(dst) > 1024:
        print(f"SKIP  {name} ({os.path.getsize(dst)} bytes)", flush=True)
        return True
    for i in range(attempts):
        try:
            t = time.time()
            req = urllib.request.Request(BASE + name, headers={"User-Agent": "trace/1.0"})
            with urllib.request.urlopen(req, timeout=180) as r:
                with open(dst, "wb") as f:
                    while True:
                        b = r.read(1 << 20)
                        if not b:
                            break
                        f.write(b)
            print(f"OK    {name} {os.path.getsize(dst)} bytes in {time.time()-t:.1f}s", flush=True)
            return True
        except Exception as e:
            print(f"RETRY {name} #{i+1} {type(e).__name__}: {str(e)[:70]}", flush=True)
            try:
                os.remove(dst)
            except OSError:
                pass
            time.sleep(1)
    return False


def main():
    os.makedirs(OUT, exist_ok=True)
    ok = sum(1 for f in FILES if fetch(f))
    print(f"DONE {ok}/{len(FILES)} in {OUT}", flush=True)
    if ok == len(FILES):
        print("接下来在 backend/config.json 填：")
        print('  "sense_voice_model": "models/sense-voice/model.int8.onnx",')
        print('  "sense_voice_tokens": "models/sense-voice/tokens.txt"')
        print("重启后 /health 的 adapters.asr 会变成 sherpa-onnx:sense-voice")
    return 0 if ok == len(FILES) else 1


if __name__ == "__main__":
    sys.exit(main())
