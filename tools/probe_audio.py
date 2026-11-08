# -*- coding: utf-8 -*-
"""/audio 越界防护验证。

用法:  python tools/probe_audio.py <trace-demo 根目录> [标签] [端口]

覆盖：合法回放仍可用；../ 穿越、URL 编码穿越、绝对路径（DATA_DIR 外）、
      系统文件 全部被拒。对应改进方案里的 B 项。
自包含：l1 里没有音频时会先用包内测试音频播种一个存证件。
"""
import base64, json, os, sys, threading, time
import urllib.request, urllib.error, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(HERE)
LABEL = sys.argv[2] if len(sys.argv) > 2 else "PROBE"
PORT = int(sys.argv[3]) if len(sys.argv) > 3 else 8802
sys.path.insert(0, os.path.join(ROOT, "backend"))
import run as R  # noqa: E402

R.init(os.path.join(ROOT, "backend", "config.json"))
srv = R.ThreadingHTTPServer(("127.0.0.1", PORT), R.Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(0.4)
BASE = "http://127.0.0.1:%d" % PORT
AUDIO_EXT = (".ogg", ".m4a", ".mp3", ".wav", ".aac", ".webm")
fails = []


def code_of(path):
    try:
        r = urllib.request.urlopen(BASE + path, timeout=30)
        return r.getcode(), len(r.read())
    except urllib.error.HTTPError as e:
        return e.code, 0


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        urllib.request.urlopen(req, timeout=180).read()
        return True
    except Exception as e:
        print("  (seed 失败: %s)" % e)
        return False


data_dir = os.path.join(ROOT, "backend", "data")
l1 = os.path.join(data_dir, "l1")
os.makedirs(l1, exist_ok=True)

# l1 里没有音频存证时先播种一个，否则「合法回放」这项会被跳过 = 没验证
existing = [f for f in os.listdir(l1) if f.lower().endswith(AUDIO_EXT)]
if not existing:
    seed = os.path.join(ROOT, "backend", "models", "asr-zh-14M", "test_wavs", "0.wav")
    if os.path.isfile(seed):
        post("/upload", {"mode_id": "life", "filename": "probe_seed.wav",
                         "audio_b64": base64.b64encode(open(seed, "rb").read()).decode(),
                         "text": "probe 用的一段测试音频"})
    existing = [f for f in os.listdir(l1) if f.lower().endswith(AUDIO_EXT)]

secret = os.path.abspath(os.path.join(ROOT, "backend", "config.json")).replace("\\", "/")
tests = [
    ("合法回放 /audio?file=l1/<真存证件>",
     "/audio?file=" + urllib.parse.quote("l1/" + existing[0]) if existing else None, (200,)),
    ("目录穿越 ../config.json", "/audio?file=../config.json", (404, 410)),
    ("URL 编码穿越 ..%5Cconfig.json", "/audio?file=..%5Cconfig.json", (404, 410)),
    ("绝对路径（DATA_DIR 外）", "/audio?file=" + urllib.parse.quote(secret), (404, 410)),
    ("绝对路径 C:/Windows/win.ini", "/audio?file=" + urllib.parse.quote("C:/Windows/win.ini"), (404, 410)),
]
print("=== %s ===" % LABEL)
for name, path, want in tests:
    if path is None:
        print("  %-34s -> 跳过（l1 无音频存证）" % name)
        fails.append(name)
        continue
    c, n = code_of(path)
    ok = c in want
    print("  %-34s -> HTTP %-4s %-10s %s" % (name, c, ("%dB" % n) if n else "",
                                              "" if ok else "<== 期望 %s" % (want,)))
    if not ok:
        fails.append(name)
srv.shutdown()
print("\n===== %s =====" % ("全部通过" if not fails else "失败 %d 项: %s" % (len(fails), fails)))
sys.exit(1 if fails else 0)
