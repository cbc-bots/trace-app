"""全链路冒烟测试：启动真实 HTTP 服务，用 urllib 打 API 逐项断言。

运行： python backend/smoke_test.py
覆盖：收录 / VAD / ASR 通道 / 说话人归属 / 抽取分类 / 排序检索 / 时间轴 /
      Agent 对话 / L3b 校正 / 统计快照锁定 / 模式隔离 / 组件市场 / 常驻录音 / ima 降级。
"""
from __future__ import annotations

import base64
import io
import json
import math
import os
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import wave

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 8766
BASE = f"http://127.0.0.1:{PORT}"
PASS, FAIL = [], []


def call(method: str, path: str, body: dict | None = None) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=10) as r:
        return json.loads(r.read().decode())


def check(name: str, cond: bool, detail: str = ""):
    (PASS if cond else FAIL).append(name)
    print(("  [OK]   " if cond else "  [FAIL] ") + name + (f" — {detail}" if detail else ""))


def make_wav(seconds: float = 1.6, rate: int = 16000) -> bytes:
    """造一段 0.8s 正弦 + 0.8s 静音的 WAV，用于验证 VAD 切段。"""
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(rate)
        n = int(rate * seconds)
        frames = []
        for i in range(n):
            v = 0.45 * math.sin(2 * math.pi * 440 * i / rate) if i < n // 2 else 0.0
            frames.append(struct.pack("<h", int(v * 32767)))
        w.writeframes(b"".join(frames))
    return buf.getvalue()


def main():
    # 用独立测试配置 + 独立数据库，绝不碰正在运行的生产库
    with open(os.path.join(HERE, "config.json"), "r", encoding="utf-8") as f:
        cfg = json.load(f)
    cfg["db_file"] = "trace_test.db"
    cfg["port"] = PORT
    # 回归测试强制内置实现，保证断言确定性；真实 key 链路另由 live_test.py 验证
    cfg["adapters"] = {k: "builtin" for k in cfg.get("adapters", {})}
    test_cfg = os.path.join(HERE, "config.test.json")
    with open(test_cfg, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    db = os.path.join(HERE, cfg.get("data_dir", "data"), "trace_test.db")
    if os.path.isfile(db):
        import sqlite3
        con = sqlite3.connect(db)
        for t in ("events", "l1_files", "l2_segments", "l3a_snapshots",
                  "l3b_corrections", "components", "modes", "sessions", "stage_log"):
            try:
                con.execute(f"DELETE FROM {t}")
            except sqlite3.OperationalError:
                pass
        con.commit()
        con.close()
    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "run.py"), "--port", str(PORT), "--config", test_cfg],
        cwd=HERE, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(40):
            try:
                call("GET", "/health")
                break
            except Exception:
                time.sleep(0.15)
        else:
            print("服务未能启动")
            return 1

        print("\n== 1. 基础设施 ==")
        h = call("GET", "/health")
        check("服务健康", h.get("ok") is True)
        check("Q1 = 程序常驻收音", h.get("q1_capture") == "always_on", str(h.get("q1_capture")))
        check("外部适配器自动降级", h["adapters"]["asr"].startswith("builtin"),
              str(h["adapters"]))
        modes = call("GET", "/modes")["modes"]
        check("4 个模式就绪", len(modes) >= 4, ", ".join(m["mode_id"] for m in modes))
        comps = call("GET", "/components")["components"]
        check("内置组件注册并启用", len(comps) >= 9 and all(c["enabled"] for c in comps),
              f"{len(comps)} 个")
        cat = call("GET", "/catalog")["catalog"]
        check("GitHub 组件精选目录", len(cat) >= 8, f"{len(cat)} 个")
        fm = h.get("folder_modules", [])
        check("模组文件夹自动注册（骑砍式）", len(fm) >= 2, ", ".join(fm))
        scan = call("POST", "/components/scan", {})
        check("热重扫可用（放进去不用重启）", scan.get("ok") is True
              and len(scan["result"]["installed"]) >= 2, f"{len(scan['result']['installed'])} 个")
        folder_comps = [c for c in call("GET", "/components")["components"]
                        if c["manifest"].get("source") == "folder"]
        check("W11 可见文件夹来源组件", len(folder_comps) >= 2
              and any("prompt_md" in c["manifest"] for c in folder_comps))
        ag = call("GET", "/agents")
        ids = [a["id"] for a in ag.get("agents", [])]
        check("预置人格模组 >= 6 套", len(ids) >= 6, ", ".join(ids))
        check("默认人格已设置且有效", ag.get("active_agent") in ids, str(ag.get("active_agent")))
        check("人格带 prompt.md", any(a["manifest"].get("prompt_md") for a in ag.get("agents", [])))

        print("\n== 2. 一站式收录 + 自动分析整理排序 ==")
        text = "\n".join([
            "9-12 报名那天，想试试看",
            "10-3 组队会，怕拖后腿",
            "10-4 倾诉，觉得自己好像什么都做不好",
            "11-2 跑通了！",
            "11-15 拿奖那天，本以为会更高兴",
            "11-16 跟人聊完，那阵子是真的累",
            "11-17 倾诉，下学期还继续吗",
            "12-5 复习，按部就班吧",
            "12-18 复盘，大概就是这样了",
        ])
        r = call("POST", "/ingest", {"source": "text", "mode_id": "life", "text": text,
                                     "capture_session": "rehearsal", "is_demo_sample": True})
        check("文本收录成功", r.get("ok") is True)
        stages = [s["stage"] for s in r["stages"]]
        check("流水线 8 阶段全跑通", len(stages) == 8, " → ".join(stages))
        check("含独立 AI 分析阶段", any(s["stage"] == "AI 分析" for s in r["stages"]))
        check("生成 9 张事件卡", len(r["events"]) == 9, str(len(r["events"])))
        dates = [e["event_time"] for e in r["events"] if e["event_time"]]
        check("event_time 从口述解析", len(dates) == 9, f"{len(dates)}/9")
        check("按 event_time 排序写入", dates == sorted(dates))
        emo = [e["extensions"]["emotion"]["tag"] for e in r["events"]]
        check("受控词表归一（分类排序）", "落差" in emo and "释然" in emo, str(emo))
        check("speaker 全保留字段存在", all("speaker" in e for e in r["events"]))
        check("证据引用带原文", all(e["quote_ref"]["quote"] for e in r["events"]))
        src = r["source_id"]
        sr = call("GET", "/stages?source=" + urllib.parse.quote(src))["stages"]
        check("stage_log 可供 W5 可视化", len(sr) == 8)
        ana = r.get("analysis") or {}
        check("收录自动产出 AI 分析", bool(ana.get("id")) and "suggestions" in ana,
              str(ana.get("adapter")))
        check("分析已入库且可列出", any(a["id"] == ana.get("id")
              for a in call("GET", "/analyses?mode_id=life")["analyses"]))

        print("\n== 3. 知识库可视化与检索 ==")
        evs = call("GET", "/events?mode_id=life")["events"]
        check("事件列表按事件日排序", len(evs) == 9 and evs[0]["event_time"] <= evs[-1]["event_time"])
        tl = call("GET", "/timeline?mode_id=life&view=timeline")["data"]
        check("时间轴分组输出", tl["total"] == 9 and len(tl["groups"]) >= 4,
              f"{len(tl['groups'])} 组")
        q = call("GET", "/search?mode_id=life&q=" + urllib.parse.quote("本以为会更高兴"))["events"]
        check("全文检索命中原话", any("本以为" in e["quote"] for e in q), f"{len(q)} 条")
        l2 = call("GET", "/l2?file=" + urllib.parse.quote(r["file"]))["segments"]
        check("L2 转写只读层（按句切段）", len(l2) >= 9 and all(s["readonly"] == 1 for s in l2),
              f"{len(l2)} 段")

        print("\n== 4. Agent 自由对话（无红线） ==")
        chat = call("POST", "/chat", {"mode_id": "life", "query": "倾诉几次，我该不该继续"})
        check("自由回答生成", len(chat["answer"]) > 20)
        check("回答附知识库证据", len(chat["evidence_ids"]) > 0,
              f"{len(chat['evidence_ids'])} 条")
        check("无输出闸门标记", "redline" not in chat and "blocked" not in chat)
        check("适配器来源标注", "adapter" in chat, chat.get("adapter"))

        print("\n== 5. 校正与统计快照锁定 ==")
        target = next(e for e in evs if "本以为" in e["quote"])
        corr = call("POST", "/correct", {"event_id": target["id"],
                                         "patch": {"summary": "我自己的词：释然"}})
        check("L3b 校正生效", corr["event"]["corrected"] is True)
        check("保留 L3a 只读快照", "l3a_snapshot" in corr["event"])
        before = call("GET", "/stats?mode_id=life")["stats"]["rehearsal_count"]
        call("POST", "/ingest", {"source": "text", "mode_id": "life",
                                 "text": "11-15 现场真说一句，拿奖那天其实没那么开心",
                                 "capture_session": "live"})
        after = call("GET", "/stats?mode_id=life")["stats"]["rehearsal_count"]
        check("现场新增卡不回写统计", before == after == 9, f"{before} → {after}")

        print("\n== 6. 模式隔离 ==")
        call("POST", "/ingest", {"source": "text", "mode_id": "study",
                                 "text": "11-20 上课，老师讲了函数，没听懂", "capture_session": "live"})
        life = call("GET", "/events?mode_id=life")["events"]
        study = call("GET", "/events?mode_id=study")["events"]
        check("study 模式独立入库", len(study) == 1)
        check("life 模式不受污染", all("没听懂" not in e["quote"] for e in life))
        check("study 扩展字段生效", "topic" in study[0]["extensions"])

        print("\n== 7. 组件市场（模组式） ==")
        m = {"id": "my-custom-view", "type": "view", "name": "我的自定义视图",
             "version": "1.2.0", "repo": "https://github.com/demo/my-view", "minCore": "1.0"}
        ins = call("POST", "/components/install", {"manifest": m, "enabled": True})
        check("安装自定义组件", ins.get("ok") is True)
        check("组件出现在注册表", any(c["id"] == m["id"] for c in call("GET", "/components")["components"]))
        try:
            bad = call("POST", "/components/install", {"manifest": {"id": "bad", "type": "view"}})
            check("manifest 校验拒绝残缺字段", bad.get("ok") is False)
        except urllib.error.HTTPError as e:
            check("manifest 校验拒绝残缺字段", e.code == 400, f"HTTP {e.code}")
        call("POST", "/components/toggle", {"id": m["id"], "enabled": False})
        check("停用组件", any(c["id"] == m["id"] and not c["enabled"]
                             for c in call("GET", "/components")["components"]))
        check("卸载组件", call("POST", "/components/uninstall", {"id": m["id"]})["ok"] is True)

        print("\n== 8. Q1 程序常驻收音 + 音频上传 ==")
        s = call("POST", "/record/start", {"mode_id": "life"})["session"]
        check("常驻录音会话启动", s["status"] == "recording")
        wav = make_wav()
        pcm = b"".join(struct.pack("<h", int(0.3 * math.sin(2 * math.pi * 300 * i / 16000) * 32767))
                       for i in range(1600))
        push = call("POST", "/record/push", {"session_id": s["id"],
                                             "pcm_b64": base64.b64encode(pcm).decode()})
        check("PCM 分块推入", push["chunks"] >= 1)
        stop = call("POST", "/record/stop", {"session_id": s["id"]})
        check("停止并触发入库", stop.get("ingested") is True and stop["session"]["status"] == "stopped")
        STORE_RETENTION = call("GET", "/health").get("retention", {})
        up = call("POST", "/upload", {"mode_id": "life", "filename": "smoke.wav",
                                      "audio_b64": base64.b64encode(wav).decode(),
                                      "text": "11-21 上传的一段录音，想试试看"})
        check("录音文件上传", up.get("ok") is True)
        STORE_RETENTION = up.get("retention", {})
        vad = next((x for x in up["stages"] if x["stage"] == "VAD 切段"), {})
        check("VAD 切出语音段", vad.get("detail", {}).get("segments", 0) >= 1,
              str(vad.get("detail", {})))
        check("音频存证文件可回放", os.path.isfile(os.path.join(HERE, "data", up["file"].replace("/", os.sep))),
              up["file"])
        check("存证已压缩（非 WAV 长期保留）", up.get("evidence", {}).get("codec") in ("opus", "aac", "mp3"),
              str(up.get("evidence", {})))
        check("临时消费 WAV 已清理", not os.path.isfile(os.path.join(HERE, "data", "l1", "smoke.16k.wav")))
        check("保留窗口已登记（72h）", (STORE_RETENTION.get("retention_hours") == 72
                                    and STORE_RETENTION.get("audio_files", 0) >= 1),
              str(STORE_RETENTION))
        keep = call("POST", "/l1/keep", {"file": up["file"], "keep": True})
        check("用户可标记永久保留", keep.get("ok") is True and keep.get("keep") is True
              and keep.get("expire_at") is None)
        purge = call("POST", "/l1/purge", {})
        check("到期清理不影响 keep 的文件", purge.get("ok") is True
              and up["file"] not in (purge.get("purged") or []),
              f"purged={purge.get('count')}")

        print("\n== 9. 可选拓展降级 ==")
        try:
            call("POST", "/ima/export", {"text": "x", "title": "t"})
            check("ima 未配置时明确报错", False)
        except urllib.error.HTTPError as e:
            check("ima 未配置时明确报错且不影响主链路", e.code == 400)
        check("主链路仍健康", call("GET", "/health")["ok"] is True)

        print(f"\n===== 结果：{len(PASS)} 通过 / {len(FAIL)} 失败 =====")
        if FAIL:
            print("失败项：" + ", ".join(FAIL))
        return 0 if not FAIL else 1
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=5)
        except subprocess.TimeoutExpired:
            proc.kill()


if __name__ == "__main__":
    sys.exit(main())
