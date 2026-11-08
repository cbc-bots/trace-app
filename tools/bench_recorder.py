# -*- coding: utf-8 -*-
"""同一套压测分别打在旧版与改版上，输出可比数字。
FakeASR 模拟真实离线 ASR：耗时 = 0.3 x 音频时长，每 3 秒产出一句可辨识文本。"""
import base64, math, os, struct, sys, time, threading, json, urllib.request, urllib.error
ROOT = os.path.abspath(sys.argv[1])
LABEL = sys.argv[2]
sys.path.insert(0, os.path.join(ROOT, "backend"))
import run as R
import adapters_oss as AO

class FakeASR:
    name = "fake:offline-asr"
    def __init__(self, cfg): self.cfg = cfg
    def available(self): return True
    def transcribe(self, wav):
        import wave
        with wave.open(wav, "rb") as w:
            secs = w.getnframes() / float(w.getframerate())
        time.sleep(secs * 0.3)                      # 模拟识别耗时
        n = max(1, int(secs // 3))
        return [{"start_ms": i*3000, "end_ms": (i+1)*3000,
                 "quote": "第%d句[%s] 我真的很怕失败 拿奖那天本以为会更高兴" % (i+1, os.path.basename(wav)),
                 "speaker": "self", "is_mock": False} for i in range(n)]

AO.ADAPTER_MAP["asr"] = [FakeASR]
R.init(os.path.join(ROOT, "backend", "config.json"))

runs, processed = [], [0]
orig = R.PIPELINE.run
def spy(**kw):
    b = kw.get("audio_b64") or ""
    nb = len(base64.b64decode(b)) if b else 0
    processed[0] += nb
    runs.append((kw.get("filename"), nb))
    return orig(**kw)
R.PIPELINE.run = spy

SR = 16000
CHUNK_SEC = 3
pcm = b"".join(struct.pack("<h", int(6000*math.sin(2*math.pi*440*i/SR))) for i in range(SR*CHUNK_SEC))
N_PUSH = 9
sess = R.RECORDER.start("life"); sid = sess["id"]
lat = []
t_all = time.time()
for i in range(N_PUSH):
    t0 = time.time()
    R.RECORDER.push(sid, base64.b64encode(pcm).decode())
    lat.append(time.time() - t0)
stop = R.RECORDER.stop(sid)
wall = time.time() - t_all
time.sleep(0.5)

evs = R.STORE.list_events("life")
quotes = {}
for e in evs: quotes[e["quote"]] = quotes.get(e["quote"], 0) + 1
dups = sum(v-1 for v in quotes.values() if v > 1)
recorded = N_PUSH * CHUNK_SEC
l1 = os.path.join(ROOT, "backend", "data", "l1")
segs = sorted(f for f in os.listdir(l1)) if os.path.isdir(l1) else []

out = {
 "label": LABEL,
 "录音时长(s)": recorded,
 "pipeline调用次数": len(runs),
 "实际处理音频(s)": round(processed[0]/32000.0, 1),
 "放大倍数": round(processed[0]/32000.0/recorded, 1),
 "生成事件卡": len(evs),
 "其中重复卡": dups,
 "去重后应有卡": len(quotes),
 "push最大延迟(s)": round(max(lat), 2),
 "push平均延迟(s)": round(sum(lat)/len(lat), 2),
 "整体墙钟(s)": round(wall, 2),
 "stop返回segments": stop.get("segments", "n/a"),
 "l1分段文件": [f for f in segs if sid in f],
}
print(json.dumps(out, ensure_ascii=False, indent=2))

