# -*- coding: utf-8 -*-
"""72 小时保留策略端到端验证。

用法:  python tools/verify_retention.py [trace-demo 根目录]
默认根目录 = 本脚本的上一级。

覆盖：压缩存证 -> 临时件清理 -> 可回放 -> keep 免清理 -> 取消 keep 后到期清理
      -> /audio 返回 410 -> 文本层（事件卡 / L2 / 分析 / 检索）完好性。
自包含：测试音频用包内 backend/models/asr-zh-14M/test_wavs/0.wav，无需外部素材。
"""
import base64, io, json, os, sys, threading, time
import urllib.request, urllib.error, urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(sys.argv[1]) if len(sys.argv) > 1 else os.path.dirname(HERE)
PORT = int(os.environ.get("TRACE_PORT", "8823"))
sys.path.insert(0, os.path.join(ROOT, "backend"))
import run as R  # noqa: E402

R.init(os.path.join(ROOT, "backend", "config.json"))
srv = R.ThreadingHTTPServer(("127.0.0.1", PORT), R.Handler)
threading.Thread(target=srv.serve_forever, daemon=True).start()
time.sleep(0.4)
BASE = "http://127.0.0.1:%d" % PORT
fails = []


def call(m, p, b=None):
    data = json.dumps(b).encode() if b is not None else None
    req = urllib.request.Request(BASE + p, data=data,
                                 headers={"Content-Type": "application/json"}, method=m)
    try:
        r = urllib.request.urlopen(req, timeout=180)
        return r.getcode(), json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def raw(p):
    try:
        r = urllib.request.urlopen(BASE + p, timeout=60)
        return r.getcode(), len(r.read()), r.headers.get("Content-Type")
    except urllib.error.HTTPError as e:
        return e.code, 0, json.loads(e.read().decode() or "{}").get("error", "")


def ck(name, cond, detail=""):
    print("  [%s] %s%s" % ("OK " if cond else "FAIL", name, (" -- " + str(detail)) if detail else ""))
    if not cond:
        fails.append(name)


# ---- 前置检查：保留策略（F 项）是否已经落地 ----
_cols = {r["name"] for r in R.STORE.conn.execute("PRAGMA table_info(l1_files)").fetchall()}
_need = {"keep", "expire_at", "purged_at", "evidence_codec", "bytes_on_disk"}
if not _need <= _cols:
    print("\n===== 无法验证：l1_files 缺少保留策略字段 %s =====" % sorted(_need - _cols))
    print("说明这份代码还没有打 v1.1 的 F 项补丁（72 小时保留策略 + 压缩存证）。")
    print("先覆盖 patch-v1.1/backend/{core.py,pipeline.py,adapters_builtin.py,run.py,config.json}。")
    srv.shutdown()
    sys.exit(2)

src = os.path.join(ROOT, "backend", "models", "asr-zh-14M", "test_wavs", "0.wav")
if not os.path.isfile(src):
    raise SystemExit("找不到测试音频: " + src)
import tempfile, wave
with wave.open(src, "rb") as _w:
    _params, _frames = _w.getparams(), _w.readframes(_w.getnframes())
    _secs = _w.getnframes() / float(_w.getframerate())
# 用 wave 正确拼接数据帧并重写头部 —— 裸拼字节会产生多个 RIFF 头，是无效 WAV
loops = max(1, int(60 / max(0.5, _secs)))
_tmp = os.path.join(tempfile.gettempdir(), "trace_reten_in.wav")
with wave.open(_tmp, "wb") as _o:
    _o.setparams(_params)
    _o.writeframes(_frames * loops)
rawbytes = io.open(_tmp, "rb").read()
data_dir = os.path.join(ROOT, "backend", "data")
print("输入：约 %.0f 秒中文语音 WAV = %.2f MB" % (loops * _secs, len(rawbytes) / 1048576))

c, up = call("POST", "/upload", {"mode_id": "life", "filename": "reten_test.wav",
                                 "audio_b64": base64.b64encode(rawbytes).decode(),
                                 "text": "2026-11-01 开会讨论了下周的评审，我真的很怕失败。"})
print("\n== 1. 收录 ==")
ck("上传成功", up.get("ok") is True, up.get("error"))
ev = up.get("evidence", {}) or {}
cl = up.get("cleanup", {}) or {}
evp = os.path.join(data_dir, up["file"].replace("/", os.sep))
ck("存证件已生成且为压缩格式", ev.get("ok") is True and ev.get("codec") in ("opus", "aac", "mp3"), ev)
ck("存证文件真实存在", os.path.isfile(evp), up["file"])
if ev.get("bytes") and ev.get("bytes_before"):
    print("      压缩比 %.1fx  (%.2f MB -> %.2f MB)"
          % (ev["ratio"], ev["bytes_before"] / 1048576, ev["bytes"] / 1048576))
    ck("压缩比 >= 5x", ev["ratio"] >= 5, ev["ratio"])
ck("临时消费 WAV 已删", cl.get("wav_deleted") is True)
ck("原始上传件已删", cl.get("original_deleted") is True)
ck("inbox 无残留", not os.listdir(os.path.join(data_dir, "inbox")))
ck("l1 目录无 .wav 残留",
   not [f for f in os.listdir(os.path.join(data_dir, "l1")) if f.endswith(".wav")],
   os.listdir(os.path.join(data_dir, "l1")))

print("\n== 2. 存证可回放 ==")
code, n, ctype = raw("/audio?file=" + urllib.parse.quote(up["file"]))
ck("GET /audio 返回 200", code == 200, code)
ck("Content-Type 是音频", str(ctype).startswith("audio/"), ctype)

evs0 = len(R.STORE.list_events("life"))
l20 = len(R.STORE.get_l2(up["file"]))
ana0 = len(call("GET", "/analyses?mode_id=life")[1].get("analyses") or [])

print("\n== 3. keep=True -> 到期清理应放过 ==")
call("POST", "/l1/keep", {"file": up["file"], "keep": True})
R.STORE.conn.execute("UPDATE l1_files SET expire_at=? WHERE file=?",
                     ("2000-01-01T00:00:00+08:00", up["file"]))
R.STORE.conn.commit()
c3, pu = call("POST", "/l1/purge", {})
ck("purge 未清理 keep 的文件", pu.get("count") == 0 and os.path.isfile(evp), pu.get("count"))

print("\n== 4. keep=False -> 到期清理 ==")
call("POST", "/l1/keep", {"file": up["file"], "keep": False})
R.STORE.conn.execute("UPDATE l1_files SET expire_at=? WHERE file=?",
                     ("2000-01-01T00:00:00+08:00", up["file"]))
R.STORE.conn.commit()
c4, pu4 = call("POST", "/l1/purge", {})
ck("purge 清理了 1 个文件", pu4.get("count") == 1, pu4.get("count"))
ck("音频字节已删除", not os.path.isfile(evp))
meta = R.STORE.get_l1(up["file"])
ck("审计行保留且有 purged_at", meta is not None and bool(meta.get("purged_at")),
   (meta or {}).get("purged_at"))
code, _, msg = raw("/audio?file=" + urllib.parse.quote(up["file"]))
ck("/audio 返回 410（不是 404）", code == 410, code)
ck("410 带明确说明文案", "保留策略" in str(msg), msg)

print("\n== 5. 文本层完好性（最关键） ==")
ck("事件卡数量不变", len(R.STORE.list_events("life")) == evs0, "%d -> %d" % (evs0, len(R.STORE.list_events("life"))))
ck("L2 转写原文不变", len(R.STORE.get_l2(up["file"])) == l20, "%d -> %d" % (l20, len(R.STORE.get_l2(up["file"]))))
ck("AI 分析仍在", len(call("GET", "/analyses?mode_id=life")[1].get("analyses") or []) == ana0)
c6, q = call("GET", "/search?q=" + urllib.parse.quote("怕失败"))
ck("全文检索仍命中原话", len(q.get("events") or []) >= 1, len(q.get("events") or []))
c7, h = call("GET", "/health")
rt = h.get("retention", {})
ck("/health.retention 有 purged 计数", rt.get("purged_files", 0) >= 1, rt)

print("\n== 6. 越界防护未回退 ==")
for bad in ("../config.json", "..%5Cconfig.json",
            urllib.parse.quote(os.path.abspath(os.path.join(ROOT, "backend", "config.json")).replace("\\", "/")),
            urllib.parse.quote("C:/Windows/win.ini")):
    code, _, _ = raw("/audio?file=" + bad)
    ck("越界请求被拒 (%s)" % bad[:34], code in (404, 410), code)

srv.shutdown()
print("\n===== %s =====" % ("全部通过" if not fails else "失败 %d 项: %s" % (len(fails), fails)))
sys.exit(1 if fails else 0)
