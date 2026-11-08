"""GitHub 现成开源项目 / 云端 API 的接入适配器（照官方示例写，懒加载）。

设计：available() 只探测依赖或凭证，绝不 import 失败就崩；不可用时上层自动降级到
adapters_builtin 的规则实现，并在响应里标注 adapter 来源。有网 + 装好依赖 / 填好 key
后无需改业务代码，自动切到真实实现。

对应调研清单见 README.md「GitHub 现成组件」。
"""
from __future__ import annotations

import base64
import json
import os
import time
import urllib.error
import urllib.request
from typing import Any


def _http_json(url: str, payload: dict, headers: dict, timeout: int = 30) -> dict:
    req = urllib.request.Request(
        url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", **headers}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def _http_stream(url: str, payload: dict, headers: dict, timeout: int = 45):
    """逐块读 SSE（data: {...}）。生成器每 yield 一次就是模型吐出的一小段文本。

    urllib 的 response 是行迭代器，服务端 flush 一行这里就拿到一行；
    这正是「首字 0.5–1.5s」的前提——不必等整段回答生成完。
    """
    req = urllib.request.Request(
        url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json", "Accept": "text/event-stream", **headers},
        method="POST")
    resp = urllib.request.urlopen(req, timeout=timeout)
    try:
        for raw in resp:
            line = raw.decode("utf-8", "ignore").strip()
            if not line.startswith("data:"):
                continue
            d = line[5:].strip()
            if d == "[DONE]":
                break
            try:
                yield json.loads(d)
            except json.JSONDecodeError:
                continue
    finally:
        resp.close()


def _has_key(cfg: dict) -> bool:
    return bool(cfg.get("dashscope", {}).get("api_key"))


def _model_path(cfg: dict, key: str) -> str:
    """模型路径支持相对 backend/ 目录，保证安装包解压到任意位置都能跑。"""
    p = cfg.get("sherpa", {}).get(key, "")
    if not p:
        return ""
    if os.path.isabs(p):
        return p
    return os.path.join(os.path.dirname(os.path.abspath(__file__)), p)


def _try_import(name: str):
    try:
        return __import__(name)
    except Exception:
        return None


# ============ 云端 ASR / LLM（urllib 标准库即可真跑，只需 key） ============
class ParaformerASR:
    """阿里云百炼 paraformer-v2 录音文件识别。
    文档: https://help.aliyun.com/zh/model-studio/paraformer-speech-recognition
    计费 0.00008 元/秒，每月 10h 免费，仅对语音段计费。
    """
    name = "aliyun/paraformer-v2"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        # 聚合网关不代理音频端点（实测 404），必须显式配 asr_endpoint 才启用，避免 health 误报
        return _has_key(self.cfg) and bool(self.cfg["dashscope"].get("asr_endpoint"))

    def transcribe(self, file_url: str) -> list[dict]:
        d = self.cfg["dashscope"]
        body = {"model": d.get("asr_model", "paraformer-v2"),
                "input": {"file_url": file_url},
                "parameters": {"language_hints": ["zh"]}}
        url = d.get("asr_endpoint") or f"{d['base_url'].rstrip('/')}/services/audio/asr/transcription"
        r = _http_json(url, body, {"Authorization": f"Bearer {d['api_key']}"},
                       d.get("timeout_sec", 30))
        out = []
        try:
            for s in r["output"]["results"][0]["transcripts"]:
                out.append({"start_ms": int(s.get("begin_time", 0)),
                            "end_ms": int(s.get("end_time", 0)),
                            "quote": s.get("text", ""), "speaker": "self", "is_mock": False})
        except (KeyError, IndexError, TypeError):
            pass
        return out


class QwenExtractor:
    """qwen3.8-max 结构化抽取：官方标注支持 Structured Outputs。
    坑：抽取时关掉 enable_thinking；不要设 max_tokens（会截断 JSON）。
    """
    name = "aliyun/qwen3.8-max:extract"

    SCHEMA = {
        "type": "object",
        "properties": {
            "events": {"type": "array", "items": {
                "type": "object",
                "properties": {
                    "event_time": {"type": ["string", "null"]},
                    "type": {"type": "string"},
                    "quote": {"type": "string"},
                    "summary": {"type": "string"},
                    "speaker": {"type": "string", "enum": ["self", "other", "mixed"]},
                    "confidence": {"type": "number"},
                    "extensions": {"type": "object"},
                },
                "required": ["type", "quote", "speaker"],
            }},
        },
        "required": ["events"],
    }

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _has_key(self.cfg)

    def extract(self, transcript: str, mode_id: str) -> list[dict]:
        d = self.cfg["dashscope"]
        body = {
            "model": d.get("llm_model", "qwen3.8-max"),
            "enable_thinking": False,
            "messages": [
                {"role": "system", "content":
                    f"你是事件抽取器。把转写原文切成事件，按 JSON 返回。模式={mode_id}。"
                    "event_time 取自口述里的事件日期，缺失填 null。不要编造内容。"},
                {"role": "user", "content": f"请以 JSON 输出:\n{transcript}"},
            ],
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "events", "strict": True, "schema": self.SCHEMA}},
        }
        r = _http_json(f"{d['base_url'].rstrip('/')}/chat/completions", body,
                       {"Authorization": f"Bearer {d['api_key']}"}, d.get("timeout_sec", 30))
        try:
            content = r["choices"][0]["message"]["content"]
            return json.loads(content).get("events", [])
        except (KeyError, IndexError, json.JSONDecodeError):
            return []


class QwenAgent:
    """qwen3.8-max 自由对话：无输出闸门、无禁用话题、自由生成。"""
    name = "aliyun/qwen3.8-max:agent"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _has_key(self.cfg)

    def _body(self, query: str, evidence: list[dict], persona: dict | None,
              mode_id: str, context: str | None = None) -> dict:
        d = self.cfg["dashscope"]
        persona = persona or {"name": "基础助手"}
        if context is None:
            # 兼容旧调用：自己把事件卡拼成上下文
            shown = evidence[:300]
            context = "\n".join(f"[{(e.get('event_time') or '时间未定')[:10]}] {e.get('quote','')}"
                                for e in shown)
        return {
            "model": d.get("llm_model", "qwen3.8-max"),
            "enable_thinking": False,
            "max_tokens": 1200,
            "messages": [
                {"role": "system", "content":
                    ((persona.get("prompt", "") + "\n\n") if persona and persona.get("prompt") else "")
                    + f"你是{persona.get('name','助手')}，当前模式 {mode_id}，"
                      f"你已读取该模式全部 {len(evidence)} 条记录。"
                      "像通用 AI 助手一样自然回答；记录只在相关时才用，不要写成检索报告，"
                      "不要逐条复述，不要每句话都提知识库。可自由给建议、提醒、计划、评估。"},
                {"role": "user", "content":
                    f"【我的个人记忆 · 共 {len(evidence)} 条，仅在相关时参考】\n{context}\n\n【我的问题】{query}"},
            ],
        }

    def reply(self, query: str, evidence: list[dict], persona: dict | None = None,
              mode_id: str = "life", context: str | None = None) -> dict:
        d = self.cfg["dashscope"]
        persona = persona or {"name": "基础助手"}
        body = self._body(query, evidence, persona, mode_id, context)
        r = _http_json(f"{d['base_url'].rstrip('/')}/chat/completions", body,
                       {"Authorization": f"Bearer {d['api_key']}"},
                       max(60, int(d.get("timeout_sec", 60))))
        try:
            answer = r["choices"][0]["message"]["content"]
        except (KeyError, IndexError):
            answer = "(模型未返回内容)"
        return {"answer": answer, "agent": persona.get("name"), "mode_id": mode_id,
                "evidence_ids": [e.get("id") for e in evidence[:20]], "is_mock": False}

    def stream(self, query: str, evidence: list[dict], persona: dict | None = None,
               mode_id: str = "life", context: str | None = None):
        """逐段 yield 文本增量。调用方负责包装成 SSE 或推给 JS 桥。"""
        d = self.cfg["dashscope"]
        body = self._body(query, evidence, persona or {"name": "基础助手"}, mode_id, context)
        body["stream"] = True
        for chunk in _http_stream(f"{d['base_url'].rstrip('/')}/chat/completions", body,
                                  {"Authorization": f"Bearer {d['api_key']}"}, timeout=45):
            choices = chunk.get("choices") or []
            if not choices:
                continue
            piece = (choices[0].get("delta") or {}).get("content") or ""
            if piece:
                yield piece


class LLMSpeakerSplit:
    """在线说话人归属（零模型下载，回答「声纹模型能不能直接调网上的」）。

    GitHub 上的 .onnx 是本地权重文件，不是在线 API；这个适配器用 qwen3.8-max
    从转写文本的语义线索（「我说」「她问」「老师讲」「对方说」）推断每句 speaker，
    精度低于声纹 embedding，但完全不用下载 28MB 模型，也不用音频端点。
    适合本产品「事后回述」场景——口述里天然带着谁说的线索。结果标 inferred。
    """
    name = "aliyun/qwen3.8-max:speaker-split"

    LABEL_SCHEMA = {
        "type": "object",
        "properties": {"labels": {"type": "array", "items": {
            "type": "string", "enum": ["self", "other", "mixed"]}}},
        "required": ["labels"],
    }

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _has_key(self.cfg)

    def assign_text(self, lines: list[str]) -> list[str]:
        d = self.cfg["dashscope"]
        numbered = "\n".join(f"{i+1}. {t}" for i, t in enumerate(lines))
        body = {
            "model": d.get("llm_model", "qwen3.8-max"),
            "enable_thinking": False,
            "messages": [
                {"role": "system", "content":
                    "判断每句是谁说的。self=用户本人自述；other=他人说的话或被转述的他人发言；"
                    "mixed=多人混说或无法判断。只依据文本里的语义线索（我/她说/老师讲/对方问等），"
                    "不要臆测。返回与输入行数完全相同的 labels 数组。"},
                {"role": "user", "content": f"请以 JSON 输出每句的说话人标签：\n{numbered}"},
            ],
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "speaker_labels", "strict": True,
                                                "schema": self.LABEL_SCHEMA}},
        }
        r = _http_json(f"{d['base_url'].rstrip('/')}/chat/completions", body,
                       {"Authorization": f"Bearer {d['api_key']}"}, d.get("timeout_sec", 30))
        try:
            labels = json.loads(r["choices"][0]["message"]["content"]).get("labels", [])
        except (KeyError, IndexError, json.JSONDecodeError):
            return []
        out = [x if x in ("self", "other", "mixed") else "mixed" for x in labels]
        if len(out) != len(lines):
            out = (out + ["mixed"] * len(lines))[:len(lines)]
        return out


class QwenPerLineSpeaker:
    """逐句交给千问判定说话人 —— 用户拍板的「建议 2」。

    与 LLMSpeakerSplit 的「一次调用返回等长 labels」不同：这里**每一句单独调用**
    qwen3.8-max，并把前后各一句作为上下文，单句判断更准，单句失败也不拖累其它句。
    比赛环境无 API 额度限制，用线程池并发压住延迟。结果统一属 inferred（非声纹）。
    """
    name = "qwen3.8-max:per-line-speaker"

    LABEL_SCHEMA = {
        "type": "object",
        "properties": {"label": {"type": "string", "enum": ["self", "other", "mixed"]}},
        "required": ["label"],
    }

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _has_key(self.cfg) and bool(self.cfg.get("speaker", {}).get("per_line", True))

    def assign_text(self, lines: list[str]) -> list[str]:
        from concurrent.futures import ThreadPoolExecutor
        d = self.cfg["dashscope"]
        url = f"{d['base_url'].rstrip('/')}/chat/completions"
        headers = {"Authorization": f"Bearer {d['api_key']}"}
        timeout = max(30, int(d.get("timeout_sec", 30)))

        def one(i: int) -> str:
            lo, hi = max(0, i - 1), min(len(lines), i + 2)
            ctx = "\n".join(
                (">> " if j == i else "   ") + f"{j + 1}. {lines[j]}"
                for j in range(lo, hi))
            body = {
                "model": d.get("llm_model", "qwen3.8-max"),
                "enable_thinking": False,
                "messages": [
                    {"role": "system", "content":
                        "判断标有 >> 的那一句是谁说的。self=用户本人自述；"
                        "other=他人说的话或被转述的他人发言；mixed=多人混说或无法判断。"
                        "只依据文本语义线索（我/她说/老师讲/对方问等）与前后文，不要臆测。"
                        "只返回 JSON，label 取 self/other/mixed 之一。"},
                    {"role": "user", "content": ctx},
                ],
                "response_format": {"type": "json_schema",
                                    "json_schema": {"name": "speaker_label", "strict": True,
                                                    "schema": self.LABEL_SCHEMA}},
            }
            try:
                r = _http_json(url, body, headers, timeout)
                lab = json.loads(r["choices"][0]["message"]["content"]).get("label", "")
                return lab if lab in ("self", "other", "mixed") else "mixed"
            except Exception:
                return "mixed"

        if not lines:
            return []
        with ThreadPoolExecutor(max_workers=min(4, len(lines))) as ex:
            return list(ex.map(one, range(len(lines))))


# ============ GitHub 开源项目（需 pip install，见 requirements-optional.txt） ============
class QwenOmniASR:
    """音频直投多模态大模型：一次调用完成「转写 + 断句纠错 + 事件概括 + 事件卡」。

    用户明确要求「让录音直接投入 AI 大模型让它转写，然后整合成一个事件概括的一句话，
    这些工作都接给大模型干」。实测网关 qwen3.8-omni-flash 支持：
        {"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}
    裸 base64 会被当成 URL 而报 400，必须带完整 data URL；mp3/m4a 同样可用。
    配合 json_schema 一次返回 {transcript, summary, keywords, events[]}。
    """
    name = "qwen3.8-omni-flash:audio-understanding"

    OMNI_SCHEMA = {
        "type": "object",
        "properties": {
            "transcript": {"type": "string"},
            "summary": {"type": "string"},
            "speaker": {"type": "string", "enum": ["self", "other", "mixed"]},
            "keywords": {"type": "array", "items": {"type": "string"}},
            "events": {"type": "array", "items": {
                "type": "object",
                "properties": {
                    "type": {"type": "string"},
                    "quote": {"type": "string"},
                    "summary": {"type": "string"},
                    "event_time": {"type": ["string", "null"]},
                    "speaker": {"type": "string"},
                },
                "required": ["type", "quote", "summary"],
            }},
        },
        "required": ["transcript", "summary", "speaker", "keywords", "events"],
    }

    MIME = {".wav": "audio/wav", ".mp3": "audio/mpeg", ".m4a": "audio/mp4",
            ".aac": "audio/aac", ".ogg": "audio/ogg", ".webm": "audio/webm",
            ".amr": "audio/amr", ".flac": "audio/flac"}

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _has_key(self.cfg) and bool(
            self.cfg.get("asr", {}).get("prefer", "omni") != "local")

    def transcribe(self, wav_path: str) -> list[dict]:
        import base64
        d = self.cfg["dashscope"]
        ext = os.path.splitext(wav_path)[1].lower()
        mime = self.MIME.get(ext, "audio/wav")
        with open(wav_path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        data_url = f"data:{mime};base64,{b64}"
        model = self.cfg.get("asr", {}).get("omni_model", "qwen3.8-omni-flash")
        body = {
            "model": model,
            "enable_thinking": False,
            "messages": [{"role": "user", "content": [
                {"type": "input_audio", "input_audio": {"data": data_url}},
                {"type": "text", "text":
                    "请仔细听这段中文录音。要求：\n"
                    "1) transcript：逐字转写，补标点、按语义断句、修明显同音错字、"
                    "去掉纯语气赘词；严禁添加原文没有的内容，严禁改写语义。\n"
                    "2) summary：用**一句话**概括这段录音里发生的事（事件概括，不要写成要点列表）。\n"
                    "3) events：从录音中抽出独立事件，每条给 type/quote/summary/speaker；"
                    "quote 必须来自转写原文。\n"
                    "4) speaker：self=本人自述，other=他人发言，mixed=混说。\n"
                    "按 JSON Schema 输出。"},
            ]}],
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "asr_result", "strict": True,
                                                "schema": self.OMNI_SCHEMA}},
        }
        r = None
        last_err = None
        # 大请求体偶发 ConnectionReset，重试 3 次；全失败则返回空让流水线降级到本地模型
        for attempt in range(3):
            try:
                r = _http_json(f"{d['base_url'].rstrip('/')}/chat/completions", body,
                               {"Authorization": f"Bearer {d['api_key']}"},
                               max(120, int(d.get("timeout_sec", 60))))
                break
            except Exception as e:
                last_err = e
                if attempt < 2:
                    time.sleep(1 + attempt)
        if r is None:
            print(f"[QwenOmniASR] 调用失败，降级到本地 ASR: "
                  f"{type(last_err).__name__ if last_err else 'unknown'}", flush=True)
            return []
        content = r["choices"][0]["message"]["content"]
        parsed = None
        # 实测可能返回 "[{...}]" 或 "{...}"，两种都兼容
        for cand in (content, content.strip().lstrip("[").rstrip("]")):
            try:
                v = json.loads(cand)
                if isinstance(v, list) and v:
                    v = v[0]
                if isinstance(v, dict) and v.get("transcript"):
                    parsed = v
                    break
            except (json.JSONDecodeError, TypeError):
                continue
        if not parsed:
            return []
        text = str(parsed.get("transcript", "")).strip()
        if not text:
            return []
        return [{
            "start_ms": 0, "end_ms": 0,
            "quote": text,
            "speaker": parsed.get("speaker", "self") if parsed.get("speaker") in
                       ("self", "other", "mixed") else "self",
            "is_mock": False,
            "omni": {
                "summary": str(parsed.get("summary", "")).strip(),
                "keywords": [str(x) for x in (parsed.get("keywords") or [])][:10],
                "events": parsed.get("events") or [],
            },
        }]


class SenseVoiceASR:
    """SenseVoice 中文大模型（可选升级，识别质量明显优于 24MB zh-14M）。

    模型由 fetch_big_asr.py 从 hf-mirror 下载，config.json 填
    sherpa.sense_voice_model / sense_voice_tokens 后自动启用（优先级高于流式小模型）。
    """
    name = "sherpa-onnx:sense-voice"

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self._rec = None

    def available(self) -> bool:
        return (_try_import("sherpa_onnx") is not None
                and os.path.isfile(_model_path(self.cfg, "sense_voice_model"))
                and os.path.isfile(_model_path(self.cfg, "sense_voice_tokens")))

    def transcribe(self, wav_path: str) -> list[dict]:
        sp = _try_import("sherpa_onnx")
        import soundfile as sf
        if self._rec is None:
            self._rec = sp.OfflineRecognizer.from_sense_voice(
                model=_model_path(self.cfg, "sense_voice_model"),
                tokens=_model_path(self.cfg, "sense_voice_tokens"),
                num_threads=self.cfg.get("sherpa", {}).get("num_threads", 2),
                use_itn=True)
        samples, rate = sf.read(wav_path, dtype="float32")
        st = self._rec.create_stream()
        st.accept_waveform(rate, samples)
        self._rec.decode_stream(st)
        # sherpa-onnx 2.x 的 OfflineStream.result 是 JSON 字符串，text 在里面
        res = st.result
        text = ""
        if isinstance(res, str):
            try:
                text = json.loads(res).get("text", "")
            except (json.JSONDecodeError, AttributeError):
                text = res
        else:
            text = getattr(res, "text", "") or ""
        text = text.strip()
        if not text:
            return []
        return [{"start_ms": 0, "end_ms": int(len(samples) / rate * 1000),
                 "quote": text, "speaker": "self", "is_mock": False}]


class StreamingZipformerASR:
    """k2-fsa/sherpa-onnx 中文流式 ASR（int8 三件套共 24MB，纯 CPU、离线、无需 key）。

    模型: hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23
    实测 5.6s 中文音频转写成功。这是不依赖任何网关音频端点就能真跑 ASR 的路。
    """
    name = "github:k2-fsa/sherpa-onnx:zh-14M"

    KEYS = ("transducer_tokens", "transducer_encoder", "transducer_decoder", "transducer_joiner")

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self._rec = None

    def available(self) -> bool:
        return (_try_import("sherpa_onnx") is not None
                and all(os.path.isfile(_model_path(self.cfg, k)) for k in self.KEYS))

    def _recognizer(self):
        if self._rec is None:
            sp = _try_import("sherpa_onnx")
            self._rec = sp.OnlineRecognizer.from_transducer(
                tokens=_model_path(self.cfg, "transducer_tokens"),
                encoder=_model_path(self.cfg, "transducer_encoder"),
                decoder=_model_path(self.cfg, "transducer_decoder"),
                joiner=_model_path(self.cfg, "transducer_joiner"),
                num_threads=self.cfg.get("sherpa", {}).get("num_threads", 2),
                sample_rate=16000, feature_dim=80,
                decoding_method="greedy_search", enable_endpoint_detection=True)
        return self._rec

    def transcribe(self, wav_path: str) -> list[dict]:
        import numpy as np
        import soundfile as sf
        rec = self._recognizer()
        samples, rate = sf.read(wav_path, dtype="float32")
        if samples.ndim > 1:
            samples = samples.mean(axis=1)
        st = rec.create_stream()
        st.accept_waveform(rate, samples)
        st.accept_waveform(rate, np.zeros(int(rate * 0.6), dtype=np.float32))
        st.input_finished()
        while rec.is_ready(st):
            rec.decode_stream(st)
        text = rec.get_result(st)
        if isinstance(text, str):
            text = text.strip()
        else:
            text = getattr(text, "text", "").strip()
        if not text:
            return []
        return [{"start_ms": 0, "end_ms": int(len(samples) / rate * 1000),
                 "quote": text, "speaker": "self", "is_mock": False}]


class SherpaOnnxASR:
    """k2-fsa/sherpa-onnx (Apache-2.0) — SenseVoice 离线 ASR + 内置 VAD + 说话人。
    官方: pip install sherpa-onnx ; OfflineRecognizer.from_sense_voice(...)
    """
    name = "github:k2-fsa/sherpa-onnx"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        m = self.cfg.get("sherpa", {}).get("sense_voice_model", "")
        return _try_import("sherpa_onnx") is not None and bool(m) and os.path.isfile(m)

    def transcribe(self, wav_path: str) -> list[dict]:
        sp = _try_import("sherpa_onnx")
        s = self.cfg["sherpa"]
        rec = sp.OfflineRecognizer.from_sense_voice(
            model=s["sense_voice_model"], tokens=s.get("tokens", ""),
            num_threads=s.get("num_threads", 2), use_itn=True)
        stream = rec.create_stream()
        import soundfile as sf
        samples, rate = sf.read(wav_path, dtype="float32")
        stream.accept_waveform(rate, samples)
        rec.decode_stream(stream)
        return [{"start_ms": 0, "end_ms": 0, "quote": rec.get_result(stream).text,
                 "speaker": "self", "is_mock": False}]


class SherpaVAD:
    """sherpa-onnx 内置 silero-vad（或 snakers4/silero-vad）。"""
    name = "github:snakers4/silero-vad"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        # 与 ASR 各适配器一致：相对路径按 backend/ 解析，否则结果随启动 CWD 变化；
        # 0 字节占位文件（仓库里就有 models/silero_vad.onnx）不该被判为可用。
        m = _model_path(self.cfg, "vad_model")
        return (_try_import("sherpa_onnx") is not None and bool(m)
                and os.path.isfile(m) and os.path.getsize(m) > 0)

    def segments(self, wav_path: str) -> list[dict]:
        sp = _try_import("sherpa_onnx")
        s = self.cfg.get("sherpa", {})
        config = sp.VadModelConfig()
        config.silero_vad.model = _model_path(self.cfg, "vad_model")
        config.silero_vad.threshold = s.get("vad_threshold", 0.5)
        config.silero_vad.min_silence_duration = 0.25
        config.sample_rate = 16000
        import soundfile as sf
        samples, rate = sf.read(wav_path, dtype="float32")
        vad = sp.VoiceActivityDetector(config)
        out, window = [], int(rate * 0.1)

        def emit(seg):
            # seg.start 单位是「采样点」不是毫秒：旧版 //1000 把时间戳压小了 16 倍，
            # 且 end_ms 恒为 0 —— 一旦真放 silero_vad.onnx 进来，W8 证据回放的
            # 音频定位跳转会全错，quote_ref 的 start/end 也会失去意义。
            st = int(getattr(seg, "start", 0))
            ns = len(seg.samples)
            r = max(1, int(rate))
            return {"start_ms": st * 1000 // r, "end_ms": (st + ns) * 1000 // r,
                    "samples": ns}

        for i in range(0, len(samples), window):
            vad.accept_waveform(samples[i:i + window])
            while not vad.empty():
                out.append(emit(vad.front))
                vad.pop()
        try:
            vad.finish()          # 冲掉尾部尚未闭合的一段，否则最后几句会丢
        except Exception:
            pass
        while not vad.empty():
            out.append(emit(vad.front))
            vad.pop()
        return out


class SherpaSpeaker:
    """sherpa-onnx 说话人验证：CAM++ 28MB 纯 CPU。
    官方示例 python-api-examples/speaker-identification.py 默认 threshold 0.6；
    本项目建议 0.70~0.72 + 多组情绪基准向量（激动时相似度会从 0.94 掉到 0.73）。
    """
    name = "github:k2-fsa/sherpa-onnx:speaker"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        m = self.cfg.get("sherpa", {}).get("speaker_model", "")
        return _try_import("sherpa_onnx") is not None and bool(m) and os.path.isfile(m)

    def assign(self, wav_path: str) -> str:
        sp = _try_import("sherpa_onnx")
        import numpy as np
        import soundfile as sf
        s = self.cfg["sherpa"]
        ex = sp.SpeakerEmbeddingExtractor(
            model=s["speaker_model"], num_threads=2, provider="cpu")
        samples, rate = sf.read(wav_path, dtype="float32")
        st = ex.create_stream()
        st.accept_waveform(rate, samples)
        st.input_finished()
        emb = np.array(ex.compute(st))
        enrolled = s.get("enrolled", [])
        if not enrolled:
            return "self"
        best = max((float(np.dot(emb, np.array(e)) /
                          (np.linalg.norm(emb) * np.linalg.norm(e) + 1e-9)) for e in enrolled),
                   default=0.0)
        thr = s.get("speaker_threshold", 0.71)
        return "self" if best >= thr else "other"


class FunASRPipeline:
    """modelscope/FunASR (MIT) — 中文全流水线 ASR+VAD+标点+说话人。"""
    name = "github:modelscope/FunASR"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _try_import("funasr") is not None

    def transcribe(self, wav_path: str) -> list[dict]:
        from funasr import AutoModel
        m = AutoModel(model="paraformer-zh", vad_model="fsmn-vad",
                      punc_model="ct-punc", spk_model="cam++")
        r = m.generate(input=wav_path)
        out = []
        for seg in (r[0].get("sentence_info", []) if r else []):
            out.append({"start_ms": int(seg.get("start", 0)), "end_ms": int(seg.get("end", 0)),
                        "quote": seg.get("text", ""),
                        "speaker": "other" if str(seg.get("spk", "0")) != "0" else "self",
                        "is_mock": False})
        return out


class WhisperXDiarization:
    """m-bain/whisperX (BSD-2) — 词级时间戳 + pyannote 说话人分离。
    需 HF_TOKEN 与 gated 模型，48h 现场风险较高，列为可选。
    """
    name = "github:m-bain/whisperX"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _try_import("whisperx") is not None and bool(os.getenv("HF_TOKEN"))

    def transcribe(self, wav_path: str) -> list[dict]:
        wx = _try_import("whisperx")
        audio = wx.load_audio(wav_path)
        model = wx.load_model("large-v2", device="cpu")
        result = model.transcribe(audio)
        dia = wx.DiarizationPipeline(use_auth_token=os.environ["HF_TOKEN"], device="cpu")
        result = wx.assign_word_speakers(dia(audio), result)
        return [{"start_ms": int(s.get("start", 0) * 1000),
                 "end_ms": int(s.get("end", 0) * 1000), "quote": s.get("text", ""),
                 "speaker": "other" if str(s.get("speaker", "SPEAKER_00")).endswith(("01", "1")) else "self",
                 "is_mock": False} for s in result.get("segments", [])]


class InstructorExtractor:
    """jxnl/instructor (MIT) — 用 Pydantic schema 约束 LLM 结构化输出。"""
    name = "github:jxnl/instructor"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _try_import("instructor") is not None and _has_key(self.cfg)

    def extract(self, transcript: str, mode_id: str) -> list[dict]:
        import instructor
        from openai import OpenAI
        from pydantic import BaseModel

        class Event(BaseModel):
            event_time: str | None
            type: str
            quote: str
            speaker: str
            confidence: float

        class Events(BaseModel):
            events: list[Event]

        d = self.cfg["dashscope"]
        c = instructor.from_openai(OpenAI(api_key=d["api_key"], base_url=d["base_url"]))
        r = c.chat.completions.create(model=d.get("llm_model", "qwen3.8-max"),
                                      response_model=Events,
                                      messages=[{"role": "user", "content":
                                                 f"把转写切成事件，模式={mode_id}: {transcript}"}])
        return [e.model_dump() for e in r.events]


class LlamaIndexRAG:
    """run-llama/llama_index — RAG 查询引擎（对话分析）。"""
    name = "github:run-llama/llama_index"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _try_import("llama_index") is not None

    def reply(self, query: str, evidence: list[dict], persona: dict | None = None) -> dict:
        from llama_index.core import Document, VectorStoreIndex
        docs = [Document(text=e.get("quote", "")) for e in evidence]
        if not docs:
            return {"answer": "知识库为空", "is_mock": False}
        engine = VectorStoreIndex.from_documents(docs).as_query_engine()
        return {"answer": str(engine.query(query)), "is_mock": False}


class ChromaRanker:
    """chroma-core/chroma — 向量检索排序。"""
    name = "github:chroma-core/chroma"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _try_import("chromadb") is not None

    def rank(self, query: str, events: list[dict]) -> list[dict]:
        import chromadb
        c = chromadb.Client()
        col = c.get_or_create_collection("events")
        if events:
            col.upsert(ids=[e["id"] for e in events],
                       documents=[e.get("quote", "") for e in events])
            r = col.query(query_texts=[query], n_results=min(20, len(events)))
            order = {i: n for n, i in enumerate(r["ids"][0])}
            return sorted(events, key=lambda e: order.get(e["id"], 999))
        return events


class BM25Ranker:
    """dorianbrown/rank_bm25 — 关键词相关度排序（比 FTS5 更细的打分）。"""
    name = "github:dorianbrown/rank_bm25"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _try_import("rank_bm25") is not None

    def rank(self, query: str, events: list[dict]) -> list[dict]:
        from rank_bm25 import BM25Okapi
        corpus = [list(e.get("quote", "")) for e in events]
        if not corpus:
            return events
        scores = BM25Okapi(corpus).get_scores(list(query))
        return [e for _, e in sorted(zip(scores, events), key=lambda x: -x[0])]


class RAGFlowRAG:
    """infiniflow/ragflow — 企业 RAG 引擎，混合检索+重排序+答案溯源引用。
    特别适合本项目：每条结论绑定原始片段，与 quote_ref 证据引用天然契合。
    """
    name = "github:infiniflow/ragflow"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return bool(self.cfg.get("ragflow", {}).get("api_url"))

    def reply(self, query: str, evidence: list[dict]) -> dict:
        r = self.cfg["ragflow"]
        resp = _http_json(f"{r['api_url'].rstrip('/')}/api/v1/chats/{r.get('chat_id','')}/completions",
                          {"question": query, "stream": False},
                          {"Authorization": f"Bearer {r.get('api_key','')}"})
        return {"answer": resp.get("data", {}).get("answer", ""), "is_mock": False}


class PluggyRegistry:
    """pytest-dev/pluggy — 成熟插件注册系统，可直接作为模组市场内核。"""
    name = "github:pytest-dev/pluggy"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        return _try_import("pluggy") is not None

    def make_manager(self, project: str = "trace"):
        import pluggy
        return pluggy.PluginManager(project)


class ImaExporter:
    """ima 官方 OpenAPI：单向导出 L2/L3a 为 NOTE/MARKDOWN。
    无改/删能力、入库异步，需轮询 MEDIA_PARSE_SUCCESS。凭证绝不放客户端日志。
    """
    name = "ima:openapi"

    def __init__(self, cfg: dict):
        self.cfg = cfg

    def available(self) -> bool:
        i = self.cfg.get("ima", {})
        return bool(i.get("client_id") and i.get("api_key"))

    def export(self, text: str, title: str) -> dict:
        i = self.cfg["ima"]
        body = {"title": title, "content": text, "media_type": "NOTE"}
        r = _http_json(f"{i['base_url'].rstrip('/')}/openapi/note/v1/import_doc", body,
                       {"X-Client-Id": i["client_id"], "X-Api-Key": i["api_key"]})
        return {"ok": True, "raw": r, "note": "入库异步，需轮询解析状态"}


# ---------- 适配器工厂：auto 优先真实实现，不可用则降级 builtin ----------
ADAPTER_MAP = {
    "vad": [SherpaVAD],
    "asr": [QwenOmniASR, SenseVoiceASR, StreamingZipformerASR, ParaformerASR,
            SherpaOnnxASR, FunASRPipeline, WhisperXDiarization],
    "speaker": [SherpaSpeaker, QwenPerLineSpeaker, LLMSpeakerSplit],
    "extractor": [QwenExtractor, InstructorExtractor],
    "ranker": [ChromaRanker, BM25Ranker],
    "agent": [QwenAgent, LlamaIndexRAG, RAGFlowRAG],
    "ima": [ImaExporter],
}


def resolve(cfg: dict, slot: str) -> tuple[Any | None, str]:
    """返回 (适配器实例, 来源名)；全部不可用时返回 (None, 'builtin')。"""
    pref = cfg.get("adapters", {}).get(slot, "auto")
    for cls in ADAPTER_MAP.get(slot, []):
        inst = cls(cfg)
        if pref in ("auto", inst.name) and inst.available():
            return inst, inst.name
    return None, "builtin"


def resolve_chain(cfg: dict, slot: str, skip: set[str] | None = None) -> list[tuple[Any, str]]:
    """按优先级返回**全部**可用适配器，供调用方逐个尝试（前一个失败自动落下一个）。

    用于音频转写这类「在线大模型可能因网络失败」的场景：omni 挂了还能回退到
    SenseVoice / 24MB 本地模型，绝不会出现「有本地模型却返回空转写」。
    """
    pref = cfg.get("adapters", {}).get(slot, "auto")
    skip = skip or set()
    out: list[tuple[Any, str]] = []
    for cls in ADAPTER_MAP.get(slot, []):
        inst = cls(cfg)
        if pref in ("auto", inst.name) and inst.name not in skip and inst.available():
            out.append((inst, inst.name))
    return out
