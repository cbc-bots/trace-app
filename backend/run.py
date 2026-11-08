"""履迹后端服务入口：零第三方依赖的 HTTP API（Python 标准库）。

启动：  python backend/run.py
        python backend/run.py --port 9000 --config backend/config.json
验证：  python backend/smoke_test.py

UI 由用户在另一个对话单独调整，本服务只提供 JSON API + 音频文件读取。
"""
from __future__ import annotations

import argparse
import json
import mimetypes
import os
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import adapters_builtin as builtin
from adapters_oss import resolve
from components import MODULES_DIR, ComponentMarket, scan_modules
from core import Store, load_config, now_iso, save_config
from pipeline import AlwaysOnRecorder, IngestPipeline

HERE = os.path.dirname(os.path.abspath(__file__))
CFG_PATH = os.path.join(HERE, "config.json")
CFG: dict = {}
STORE: Store
PIPELINE: IngestPipeline
RECORDER: AlwaysOnRecorder
MARKET: ComponentMarket
DATA_DIR = ""


_RETENTION_TIMER = None


def _start_retention_timer() -> None:
    """启动时清一次，之后按 retention.purge_interval_minutes 周期清理。

    72 小时策略要真的生效，必须有周期任务；靠「下次收录时顺手清」在长时间
    不收录的场景下会让过期音频一直躺在盘上。
    """
    global _RETENTION_TIMER
    try:
        first = STORE.purge_expired()
        if first.get("count"):
            print(f"[履迹后端] 保留策略清理：{first['count']} 个音频存证到期，释放 {first['freed_mb']} MB")
    except Exception as e:
        print(f"[履迹后端] 保留策略清理失败：{type(e).__name__}: {e}")
    mins = float(CFG.get("retention", {}).get("purge_interval_minutes", 60))
    if mins <= 0:
        return

    def tick():
        global _RETENTION_TIMER
        try:
            STORE.purge_expired()
        except Exception:
            pass
        _RETENTION_TIMER = threading.Timer(mins * 60, tick)
        _RETENTION_TIMER.daemon = True
        _RETENTION_TIMER.start()

    _RETENTION_TIMER = threading.Timer(mins * 60, tick)
    _RETENTION_TIMER.daemon = True
    _RETENTION_TIMER.start()


def init(config_path: str) -> None:
    global CFG, STORE, PIPELINE, RECORDER, MARKET, DATA_DIR, CFG_PATH
    CFG_PATH = config_path
    CFG = load_config(config_path)
    DATA_DIR = os.path.join(HERE, CFG.get("data_dir", "data"))
    os.makedirs(DATA_DIR, exist_ok=True)
    STORE = Store(os.path.join(DATA_DIR, CFG.get("db_file", "trace.db")), CFG)
    PIPELINE = IngestPipeline(STORE, CFG, DATA_DIR)
    RECORDER = AlwaysOnRecorder(STORE, CFG, PIPELINE, DATA_DIR)
    MARKET = ComponentMarket(STORE)
    MARKET.seed_builtins()
    _start_retention_timer()
    # 骑砍 Modules 式：启动时自动扫描 backend/modules/ 下的模组文件夹
    scan_modules(STORE, MODULES_DIR)


class Handler(BaseHTTPRequestHandler):
    server_version = "TraceBackend/1.0"

    def log_message(self, fmt, *args):
        pass

    # ---------- helpers ----------
    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET,POST,OPTIONS")

    def _json(self, obj: Any, status: int = 200):
        body = json.dumps(obj, ensure_ascii=False, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _sse_start(self) -> None:
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("X-Accel-Buffering", "no")
        self._cors()
        self.end_headers()

    def _sse_send(self, obj: dict) -> None:
        self.wfile.write(("data: " + json.dumps(obj, ensure_ascii=False, default=str) + "\n\n")
                         .encode("utf-8"))
        self.wfile.flush()

    def _chat_stream(self, inst, name: str, query: str, evidence: list, persona: dict,
                     mode_id: str, ctx: str, total: int, hit_count: int):
        """把模型增量逐条推成 SSE。首字 0.5–1.5s，而不是等整段生成完。"""
        t0 = time.time()
        buf: list[str] = []
        self._sse_start()
        try:
            for piece in inst.stream(query, evidence, persona, mode_id, context=ctx):
                buf.append(piece)
                self._sse_send({"delta": piece})
            answer = "".join(buf)
            if not answer:
                raise RuntimeError("模型未返回内容")
            self._sse_send({"done": True, "ok": True, "answer": answer,
                            "adapter": f"{name}（流式）", "agent": persona.get("name"),
                            "mode_id": mode_id, "is_mock": False,
                            "context_count": hit_count, "library_total": total,
                            "full_library": True, "ms": int((time.time() - t0) * 1000)})
        except Exception as e:
            err = f"{type(e).__name__}: {str(e)[:160]}"
            # 流式中断（网关不支持 / 断网）→ 同步兜底一次，别让用户看到空白
            try:
                out = inst.reply(query, evidence, persona, mode_id, context=ctx)
                if not buf and out.get("answer"):
                    self._sse_send({"delta": out["answer"]})
                self._sse_send({"done": True, "ok": True, "answer": out.get("answer", ""),
                                "adapter": f"{name}（非流式兜底）", "agent": persona.get("name"),
                                "mode_id": mode_id, "is_mock": False, "stream_error": err,
                                "context_count": hit_count, "library_total": total,
                                "full_library": True})
            except Exception as e2:
                self._sse_send({"done": True, "ok": False,
                                "error": f"{err} / 兜底失败 {type(e2).__name__}: {str(e2)[:120]}",
                                "answer": "", "is_mock": True, "adapter": "builtin",
                                "context_count": hit_count, "library_total": total})
        finally:
            self.close_connection = True

    def _err(self, msg: str, status: int = 400):
        self._json({"ok": False, "error": msg}, status)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length", 0) or 0)
        if not n:
            return {}
        try:
            return json.loads(self.rfile.read(n).decode("utf-8"))
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self):
        u = urllib.parse.urlparse(self.path)
        q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        p = u.path.rstrip("/") or "/"
        try:
            return self._get(p, q)
        except Exception as e:
            return self._err(f"{type(e).__name__}: {e}", 500)

    def do_POST(self):
        u = urllib.parse.urlparse(self.path)
        p = u.path.rstrip("/") or "/"
        b = self._body()
        try:
            return self._post(p, b)
        except Exception as e:
            return self._err(f"{type(e).__name__}: {e}", 500)

    # ---------- GET ----------
    def _html(self, text: str, status: int = 200):
        body = text.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _get(self, p: str, q: dict):
        if p in ("/", "/app", "/demo"):
            page = os.path.join(HERE, "demo", "index.html")
            if os.path.isfile(page):
                with open(page, "r", encoding="utf-8") as f:
                    return self._html(f.read())
            return self._html("<h3>demo/index.html 未找到</h3>", 404)
        if p == "/health":
            folders = [c for c in STORE.list_components() if c["manifest"].get("source") == "folder"]
            return self._json({"ok": True, "service": "trace-backend", "time": now_iso(),
                               "q1_capture": CFG.get("q1_capture"), "fts5": STORE.has_fts,
                               "has_key": bool(CFG.get("dashscope", {}).get("api_key")),
                               "ffmpeg": builtin.find_ffmpeg(),
                               "retention": STORE.retention_stats(),
                               "modules_dir": MODULES_DIR,
                               "folder_modules": [f["id"] for f in folders],
                               "adapters": {
                                   **{s: resolve(CFG, s)[1] for s in
                                      ("vad", "asr", "speaker", "extractor", "ranker", "agent", "ima")},
                                   "polish": "qwen3.8-max:polish" if CFG["dashscope"].get("api_key") else "builtin:rules",
                                   "analysis": "qwen3.8-max:analysis" if CFG["dashscope"].get("api_key") else "builtin:rules",
                               }})
        if p == "/config":
            safe = json.loads(json.dumps(CFG))
            for k in ("api_key", "client_id"):
                safe.get("dashscope", {}).pop(k, None)
                safe.get("ima", {}).pop(k, None)
            return self._json({"ok": True, "config": safe, "note": "密钥字段已隐藏"})
        if p == "/modes":
            return self._json({"ok": True, "modes": STORE.list_modes()})
        if p == "/events":
            return self._json({"ok": True,
                               "events": STORE.list_events(q.get("mode_id"), int(q.get("limit", 500)))})
        if p == "/event":
            e = STORE.get_event(q.get("id", ""))
            return self._json({"ok": bool(e), "event": e})
        if p == "/timeline":
            return self._json({"ok": True, "data": STORE.timeline(
                q.get("mode_id", CFG.get("default_mode", "life")), q.get("view", "timeline"))})
        if p == "/search":
            return self._json({"ok": True, "query": q.get("q", ""),
                               "events": STORE.search(q.get("q", ""), q.get("mode_id"))})
        if p == "/stages":
            return self._json({"ok": True, "stages": STORE.get_stages(q.get("source", ""))})
        if p == "/analyses":
            return self._json({"ok": True, "analyses": STORE.list_analyses(q.get("mode_id"))})
        if p == "/analysis":
            a = STORE.get_analysis(q.get("id", ""))
            return self._json({"ok": bool(a), "analysis": a})
        if p == "/stats":
            return self._json({"ok": True, "stats": STORE.stats(
                q.get("mode_id", CFG.get("default_mode", "life")))})
        if p == "/l2":
            return self._json({"ok": True, "segments": STORE.get_l2(q.get("file", ""))})
        if p == "/l1":
            return self._json({"ok": True, "files": STORE.list_l1(q.get("pending") == "1"),
                               "retention": STORE.retention_stats()})
        if p == "/components":
            return self._json({"ok": True, "components": STORE.list_components(q.get("type"))})
        if p == "/agents":
            return self._json({"ok": True, "agents": STORE.list_components("agent"),
                               "active_agent": CFG.get("active_agent", "")})
        if p == "/catalog":
            return self._json({"ok": True, "catalog": MARKET.catalog()})
        if p == "/session":
            return self._json({"ok": True, "session": STORE.get_session(q.get("id", ""))})
        if p == "/audio":
            f = q.get("file", "")
            # 旧版只挡 ".."，绝对路径能直接穿透 DATA_DIR 读走本机任意文件；
            # 配合 Access-Control-Allow-Origin:* 任何网页都能跨域拉走。
            root = os.path.realpath(DATA_DIR)
            path = os.path.realpath(os.path.join(DATA_DIR, f))
            if path != root and not path.startswith(root + os.sep):
                return self._err("音频文件不存在", 404)
            # 先查保留策略状态，再查文件是否存在 —— 顺序反了的话，到期清理后
            # 用户只会看到一句没头没脑的「文件不存在」，而不是「按策略已清理，记录仍在」。
            meta = STORE.get_l1(f.replace("\\", "/"))
            if meta and meta.get("purged_at"):
                return self._json({"ok": False, "purged": True, "purged_at": meta["purged_at"],
                                   "keep": False,
                                   "error": "音频存证已按 72 小时保留策略清理；转写原文、事件卡与分析结果仍完整保留"}, 410)
            if not os.path.isfile(path):
                return self._err("音频文件不存在", 404)
            with open(path, "rb") as fh:
                data = fh.read()
            self.send_response(200)
            self.send_header("Content-Type", mimetypes.guess_type(path)[0] or "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self._cors()
            self.end_headers()
            self.wfile.write(data)
            return
        return self._err(f"未知路由 GET {p}", 404)

    # ---------- POST ----------
    def _post(self, p: str, b: dict):
        if p == "/config":
            # 前端「设置」面板可直接填 key：安装包里不带明文凭证
            if b.get("api_key"):
                CFG.setdefault("dashscope", {})["api_key"] = b["api_key"].strip()
            for k, v in (b.get("patch") or {}).items():
                if k in CFG:
                    CFG[k] = v
            save_config(CFG_PATH, CFG)
            return self._json({"ok": True, "has_key": bool(CFG["dashscope"].get("api_key")),
                               "note": "已写入本地 config.json，重启后仍有效"})
        if p == "/agent/select":
            cid = b.get("id", "")
            c = STORE.get_component(cid) if cid else None
            if not c or c["type"] != "agent":
                return self._err("未找到该 agent 预设")
            CFG["active_agent"] = cid
            save_config(CFG_PATH, CFG)
            return self._json({"ok": True, "active_agent": cid,
                               "name": c["manifest"].get("name", cid)})
        if p == "/modes":
            return self._json({"ok": True, "mode": STORE.set_mode(
                b.get("mode_id", ""), b.get("name", ""), b.get("config", {}))})
        if p == "/ingest":
            r = PIPELINE.run(source=b.get("source", "text"), mode_id=b.get("mode_id", "life"),
                             text=b.get("text"), filename=b.get("filename"),
                             audio_b64=b.get("audio_b64"),
                             capture_session=b.get("capture_session", "live"),
                             recorded_at=b.get("recorded_at"),
                             time_source=b.get("time_source", "file_meta"),
                             is_demo_sample=bool(b.get("is_demo_sample", False)))
            return self._json(r, 200 if r.get("ok") else 400)
        if p == "/upload":
            if not b.get("audio_b64"):
                return self._err("需要 audio_b64（base64 音频）")
            r = PIPELINE.run(source="upload", mode_id=b.get("mode_id", "life"),
                             filename=b.get("filename"), audio_b64=b["audio_b64"],
                             text=b.get("text"), capture_session=b.get("capture_session", "live"),
                             recorded_at=b.get("recorded_at"))
            return self._json(r, 200 if r.get("ok") else 400)
        if p == "/chat":
            mode_id = b.get("mode_id", CFG.get("default_mode", "life"))
            query = b.get("query", "")
            qs = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            want_stream = bool(b.get("stream")) or qs.get("stream", ["0"])[0] == "1"

            # 上下文从「300 条原话」换成「全库摘要 + 命中原话」：
            # 摘要只在收录时算一次（memory_digest 表），这里读一行即可，
            # prompt 从约 12k token 降到 4k 左右，prefill 少 2–4 秒。
            if b.get("scope") == "relevant":
                hits = STORE.search(query, mode_id)
                ctx = "\n".join(f"[{(e.get('event_time') or '时间未定')[:10]}] {e.get('quote','')}"
                                for e in hits)
                evidence = hits
                library_total = STORE.count_events(mode_id)
            else:
                ctx, library_total, _hit = STORE.agent_context(mode_id, query)
                evidence = STORE.list_events(mode_id, limit=300)

            inst, name = resolve(CFG, "agent")
            aid = CFG.get("active_agent", "")
            ac = STORE.get_component(aid) if aid else None
            am = ac["manifest"] if ac else {}
            persona = {"name": am.get("name", "基础助手"),
                       "prompt": am.get("prompt_md", "")}

            if want_stream and inst is not None and hasattr(inst, "stream"):
                return self._chat_stream(inst, name, query, evidence, persona, mode_id,
                                         ctx, library_total, len(evidence))
            if inst:
                try:
                    # 旧版 hasattr(inst,"reply") 恒为真，RAGFlowRAG.reply 只收 2 参必然
                    # TypeError 后静默降级。改成按签名给参，谁装了都能用。
                    import inspect
                    sig = inspect.signature(inst.reply)
                    npos = len([k for k, v in sig.parameters.items()
                                if v.kind in (v.POSITIONAL_ONLY, v.POSITIONAL_OR_KEYWORD)])
                    args = (query, evidence, persona, mode_id)[:max(2, npos)]
                    kw = {"context": ctx} if "context" in sig.parameters else {}
                    out = inst.reply(*args, **kw)
                except Exception as e:
                    out = builtin.agent_reply(query, evidence, persona, mode_id)
                    out["degraded"] = True
                    out["degrade_reason"] = f"{type(e).__name__}: {str(e)[:140]}"
                    name = f"{name}->builtin"
            else:
                out = builtin.agent_reply(query, evidence, persona, mode_id)
            out["adapter"] = name
            out["context_count"] = len(evidence)
            out["library_total"] = library_total
            out["full_library"] = b.get("scope") != "relevant"
            out["ok"] = True
            if want_stream:
                # 无 key / 适配器不支持流式：仍然用 SSE 一次性吐完，
                # 前端只保留一条解析路径，不用判断两种响应格式。
                self._sse_start()
                if out.get("answer"):
                    self._sse_send({"delta": out["answer"]})
                self._sse_send({"done": True, **out})
                self.close_connection = True
                return
            return self._json(out)
        if p == "/correct":
            e = STORE.correct(b.get("event_id", ""), b.get("patch") or {})
            return self._json({"ok": bool(e), "event": e}, 200 if e else 404)
        if p == "/l1/keep":
            f = b.get("file", "")
            r = STORE.set_l1_keep(f, bool(b.get("keep", True)),
                                  CFG.get("retention", {}).get("hours", 72))
            if not r:
                return self._err("未找到该音频存证记录", 404)
            return self._json({"ok": True, "file": f, "keep": r["keep"], "expire_at": r["expire_at"]})
        if p == "/l1/purge":
            return self._json({"ok": True, **STORE.purge_expired(),
                               "retention": STORE.retention_stats()})
        if p == "/components/install":
            try:
                r = MARKET.install(manifest=b.get("manifest"), repo=b.get("repo"),
                                   enabled=bool(b.get("enabled", False)))
                return self._json({"ok": True, "component": r})
            except Exception as e:
                return self._err(str(e))
        if p == "/components/toggle":
            r = STORE.toggle_component(b.get("id", ""), bool(b.get("enabled", True)))
            return self._json({"ok": bool(r), "component": r})
        if p == "/components/uninstall":
            return self._json({"ok": STORE.uninstall_component(b.get("id", ""))})
        if p == "/components/scan":
            # 骑砍式热插拔：把模组文件夹丢进 modules/ 后调这个，不用重启
            return self._json({"ok": True, "result": scan_modules(STORE, MODULES_DIR)})
        if p == "/record/start":
            return self._json({"ok": True, "session": RECORDER.start(b.get("mode_id", "life")),
                               "q1": CFG.get("q1_capture")})
        if p == "/record/push":
            if not b.get("session_id") or not b.get("pcm_b64"):
                return self._err("需要 session_id 与 pcm_b64")
            return self._json({"ok": True, **RECORDER.push(b["session_id"], b["pcm_b64"])})
        if p == "/record/stop":
            return self._json({"ok": True, **RECORDER.stop(b.get("session_id", ""))})
        if p == "/ima/export":
            inst, name = resolve(CFG, "ima")
            if not inst:
                return self._err("ima 凭证未配置（可选拓展，不配置不影响主链路）")
            return self._json({"ok": True, "adapter": name,
                               "result": inst.export(b.get("text", ""), b.get("title", "履迹导出"))})
        return self._err(f"未知路由 POST {p}", 404)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=None)
    ap.add_argument("--port", type=int, default=None)
    ap.add_argument("--config", default=CFG_PATH)
    a = ap.parse_args()
    init(a.config)
    host = a.host or CFG.get("host", "127.0.0.1")
    port = a.port or CFG.get("port", 8765)
    srv = ThreadingHTTPServer((host, port), Handler)
    print(f"[履迹后端] http://{host}:{port}  |  Q1={CFG.get('q1_capture')}  |  FTS5={STORE.has_fts}")
    print("[履迹后端] 零依赖模式：外部模型适配器不可用会自动降级 builtin，并在响应里标注 adapter")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        srv.server_close()
        STORE.close()


if __name__ == "__main__":
    main()
