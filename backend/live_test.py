"""真实 key 链路验证：确认 json_schema 抽取、自由 Agent、在线说话人推断真的跑通。

运行： python backend/live_test.py
与 smoke_test.py 的区别：这里不强制 builtin，直接用 config.json 里的真实凭证。
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = 8767
BASE = f"http://127.0.0.1:{PORT}"
PASS, FAIL = [], []


def call(method: str, path: str, body: dict | None = None, timeout: int = 90) -> dict:
    data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def check(name: str, cond: bool, detail: str = ""):
    (PASS if cond else FAIL).append(name)
    print(("  [OK]   " if cond else "  [FAIL] ") + name + (f" — {detail}" if detail else ""))


def main():
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    if not cfg["dashscope"].get("api_key"):
        print("config.json 未填 api_key，跳过真实链路验证")
        return 2
    live_cfg = os.path.join(HERE, "config.live.json")
    live = dict(cfg)
    live["db_file"] = "trace_live.db"
    live["port"] = PORT
    json.dump(live, open(live_cfg, "w", encoding="utf-8"), ensure_ascii=False, indent=2)

    env = dict(os.environ)
    env["PYTHONIOENCODING"] = "utf-8"
    proc = subprocess.Popen(
        [sys.executable, os.path.join(HERE, "run.py"), "--port", str(PORT), "--config", live_cfg],
        cwd=HERE, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    try:
        for _ in range(40):
            try:
                call("GET", "/health", timeout=6)
                break
            except Exception:
                time.sleep(0.15)
        else:
            print("服务未启动")
            return 1

        print("\n== 1. 真实适配器状态 ==")
        h = call("GET", "/health")
        a = h["adapters"]
        print("   ", json.dumps(a, ensure_ascii=False))
        check("extractor 切到真实 LLM", "qwen" in a["extractor"].lower() or "instructor" in a["extractor"], a["extractor"])
        check("agent 切到真实 LLM", "qwen" in a["agent"].lower() or "llama" in a["agent"], a["agent"])
        check("speaker 切到在线推断（零模型下载）",
              ("qwen" in a["speaker"]) or ("speaker-split" in a["speaker"]), a["speaker"])
        check("asr 切到真实模型（omni 直投或本地离线）",
              ("omni" in a["asr"]) or ("sherpa-onnx" in a["asr"]), a["asr"])

        print("\n== 2. json_schema 真抽取 ==")
        text = "\n".join([
            "11-15 拿奖那天，本以为会更高兴",
            "11-16 我跟她说那阵子是真的累，她劝我别想太多",
            "11-17 我自己一直在想下学期还继续吗",
        ])
        r = call("POST", "/ingest", {"source": "text", "mode_id": "life", "text": text,
                                     "capture_session": "rehearsal"})
        check("收录成功", r.get("ok") is True)
        ex = next((s for s in r["stages"] if s["stage"] == "事件抽取"), {})
        check("抽取走真实 LLM", "qwen" in str(ex.get("adapter", "")).lower(), str(ex.get("adapter")))
        check("产出事件卡", len(r.get("events", [])) >= 1, f"{len(r.get('events', []))} 张")
        spk = next((s for s in r["stages"] if s["stage"] == "说话人归属"), {})
        check("在线说话人推断执行",
              ("per-line-speaker" in str(spk.get("adapter", ""))
               or "speaker-split" in str(spk.get("adapter", ""))), str(spk.get("adapter")))
        ana = r.get("analysis") or {}
        check("AI 分析由真实模型生成", ana.get("is_mock") is False, str(ana.get("adapter")))
        check("分析含建议与跨时间模式",
              isinstance(ana.get("suggestions"), list) and isinstance(ana.get("patterns"), list)
              and (len(ana.get("suggestions", [])) + len(ana.get("patterns", []))) > 0,
              f"建议{len(ana.get('suggestions',[]))} 条 / 模式{len(ana.get('patterns',[]))} 条")
        print("    analysis summary:", str(ana.get("summary", ""))[:110])
        print("    polished:", str(r.get("polished_transcript", ""))[:110])
        print("    speaker counts:", spk.get("detail", {}).get("counts"))
        print("    stage adapters:", {s["stage"]: s["adapter"] for s in r["stages"]})

        print("\n== 3. 自由 Agent 真对话（无红线） ==")
        chat = call("POST", "/chat", {"mode_id": "life", "query": "根据记录，给我一些建议：我要不要继续做这个比赛？"})
        ans = chat.get("answer", "")
        print("    answer:", ans[:220].replace("\n", " "))
        if chat.get("degraded"):
            print("    DEGRADED:", chat.get("degrade_reason"), "| adapter:", chat.get("adapter"))
        check("真实 LLM 生成回答", chat.get("is_mock") is False and len(ans) > 20,
              f"is_mock={chat.get('is_mock')}, len={len(ans)}")
        check("自由给建议（红线已撤）", any(k in ans for k in ("建议", "可以", "考虑", "继续", "如果")),
              "允许主动给建议")

        print("\n== 3.5 真实音频端到端（本地 ASR → 真抽取 → 真对话） ==")
        wav = os.path.join(HERE, "models", "asr-zh-14M", "test_wavs", "0.wav")
        if os.path.isfile(wav):
            import base64
            with open(wav, "rb") as f:
                audio = base64.b64encode(f.read()).decode()
            up = call("POST", "/upload", {"mode_id": "life", "filename": "asr_demo.wav",
                                           "audio_b64": audio}, timeout=120)
            asr_stage = next((s for s in up.get("stages", []) if s["stage"] == "语音转写"), {})
            print("    ASR adapter:", asr_stage.get("adapter"), "| detail:", asr_stage.get("detail"))
            check("上传音频走真实 ASR",
              ("omni" in str(asr_stage.get("adapter", "")) or
               "sherpa-onnx" in str(asr_stage.get("adapter", ""))),
                  str(asr_stage.get("adapter")))
            check("转写结果非 mock", asr_stage.get("detail", {}).get("is_mock") is False,
                  str(asr_stage.get("detail")))
            check("真音频产出事件卡", len(up.get("events", [])) >= 1,
                  f"{len(up.get('events', []))} 张")
            if up.get("events"):
                print("    首卡:", up["events"][0].get("quote", "")[:80])
            # 非 WAV 手机录音格式：ffmpeg 转码后仍走真实 ASR
            sys.path.insert(0, HERE)
            from adapters_builtin import find_ffmpeg
            exe = find_ffmpeg()
            if exe:
                mp3 = os.path.join(HERE, "data", "asr_demo.mp3")
                os.makedirs(os.path.dirname(mp3), exist_ok=True)
                subprocess.run([exe, "-y", "-loglevel", "error", "-i", wav,
                                "-ar", "44100", "-ac", "2", "-b:a", "96k", mp3],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
                with open(mp3, "rb") as f:
                    m64 = base64.b64encode(f.read()).decode()
                up2 = call("POST", "/upload", {"mode_id": "life", "filename": "phone.mp3",
                                               "audio_b64": m64}, timeout=120)
                rec = next((s for s in up2.get("stages", []) if s["stage"] == "收录"), {})
                asr2 = next((s for s in up2.get("stages", []) if s["stage"] == "语音转写"), {})
                ev2 = up2.get("evidence") or {}
                check("mp3 转码并压缩存证（v1.1）",
                      bool(ev2.get("codec")) and bool(ev2.get("ratio")),
                      f"codec={ev2.get('codec')} ratio={ev2.get('ratio')} stage={rec.get('detail')}")
                check("非 WAV 仍走真实 ASR",
              ("omni" in str(asr2.get("adapter", "")) or
               "sherpa-onnx" in str(asr2.get("adapter", ""))),
                      str(asr2.get("adapter")))
        else:
            print("    (测试音频不存在，跳过)")

        sel = call("POST", "/agent/select", {"id": "gentle-companion"})
        check("人格预设可切换", sel.get("active_agent") == "gentle-companion", str(sel.get("name")))
        call("POST", "/agent/select", {"id": "life-coach"})

        print("\n== 4. 在线说话人单独实测 ==")
        sys.path.insert(0, HERE)
        from adapters_oss import LLMSpeakerSplit
        sp = LLMSpeakerSplit(cfg)
        labels = sp.assign_text([
            "我说这学期真的有点累",
            "她听完劝我别想太多",
            "老师上课讲了函数，我没听懂",
            "我们俩一起把方案定下来了",
        ])
        print("    labels:", labels)
        check("返回与输入等长的标签", len(labels) == 4)
        check("能区分 self / other", "self" in labels and "other" in labels, str(labels))

        print(f"\n===== 真实链路：{len(PASS)} 通过 / {len(FAIL)} 失败 =====")
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
