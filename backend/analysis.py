"""AI 分析引擎：转写顺文本 + 收录后自动生成结构化分析。

补上产品定义里「AI 分析」这一环：之前收录只产出事件卡与标签，没有独立分析。
红线已撤 → 自由生成建议 / 提醒 / 计划 / 评估，不受任何输出约束。
"""
from __future__ import annotations

import json
import urllib.request
from typing import Any

from core import new_id, now_iso

ANALYSIS_SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "key_points": {"type": "array", "items": {"type": "string"}},
        "patterns": {"type": "array", "items": {"type": "string"}},
        "suggestions": {"type": "array", "items": {"type": "string"}},
        "reminders": {"type": "array", "items": {"type": "string"}},
        "growth_plan": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["summary", "key_points", "patterns", "suggestions", "reminders", "growth_plan"],
}

MODE_PROMPTS = {
    "life": "你在复盘一个人的真实经历记录。关注情绪轨迹、反复出现的主题、说过的话与做过的事之间的差距。",
    "study": "你在分析一次上课/家教/请教的录音。关注知识点、掌握程度、薄弱环节、下一步该练什么。",
    "relation": "你在分析一段关系里的对话。关注互动模式、双方表达、被忽略的信号、值得提醒的事。",
    "growth": "你用 Human 3.0 式视角分析成长记录：四象限（Mind/Body/Spirit/Vocation）、所处层级、False Transformation（词汇变了但日子没变）。",
}


def _post(base: str, key: str, body: dict, timeout: int = 60) -> dict | None:
    req = urllib.request.Request(
        base.rstrip("/") + "/chat/completions",
        data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
        headers={"Authorization": "Bearer " + key, "Content-Type": "application/json"},
        method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


class AnalysisEngine:
    def __init__(self, cfg: dict, store):
        self.cfg = cfg
        self.store = store
        self.d = cfg.get("dashscope", {})

    def _slot(self, name: str) -> bool:
        """遵守 adapters 开关：测试可强制 builtin 以免真实网络调用。"""
        pref = self.cfg.get("adapters", {}).get(name, "auto")
        return pref != "builtin" and bool(self.d.get("api_key"))

    @property
    def ready(self) -> bool:
        return self._slot("analysis")

    def persona(self) -> tuple[str, str]:
        """读当前启用的 Agent 预设人格（骑砍式模组里的 prompt.md）。"""
        cid = self.cfg.get("active_agent", "")
        if not cid or self.store is None:
            return "", "基础助手"
        c = self.store.get_component(cid)
        if not c:
            return "", "基础助手"
        m = c.get("manifest", {})
        return (m.get("prompt_md") or ""), m.get("name", "助手")

    def _content(self, body: dict) -> str | None:
        try:
            r = _post(self.d["base_url"], self.d["api_key"], body,
                      max(60, int(self.d.get("timeout_sec", 60))))
            return r["choices"][0]["message"]["content"]
        except Exception:
            return None

    # ---------- 句级转写矫正（L2'：原文只读，矫正句另存） ----------
    CORRECTION_SCHEMA = {
        "type": "object",
        "properties": {
            "sentences": {"type": "array", "items": {
                "type": "object",
                "properties": {
                    "raw": {"type": "string"},
                    "corrected": {"type": "string"},
                    "edits": {"type": "array", "items": {"type": "string"}},
                    "confidence": {"type": "number"},
                    "needs_review": {"type": "boolean"},
                },
                "required": ["raw", "corrected", "edits", "confidence", "needs_review"],
            }},
        },
        "required": ["sentences"],
    }

    @staticmethod
    def _split_raw(raw: str) -> list[str]:
        """按原文已有标点/换行切句，保留每一句的原始字面（不改写）。"""
        import re
        parts = re.split(r"(?<=[。！？!?；;])|[\n]+", raw)
        return [p.strip() for p in parts if p.strip()]

    def _rules_correction(self, raw: str) -> dict:
        # 规则版是「零改动」的结构化包装：无 key 时绝不改写用户原文，
        # 只把原文按句切开、让三层对照 UI 仍可用，避免 builtin 回归被改变。
        sents = self._split_raw(raw) or ([raw] if raw.strip() else [])
        return {
            "sentences": [{"raw": s, "corrected": s, "edits": [],
                           "confidence": 0.6, "needs_review": False} for s in sents],
            "full_text": raw,
            "adapter": "builtin:rules",
            "is_mock": True,
            "policy": "原文只读；L2 不回写；矫正句另存；禁止增删事实",
        }

    def correct_transcript(self, raw: str) -> dict:
        """句级矫正：逐句返回 {raw, corrected, edits, confidence, needs_review}。

        严格约束：raw 必须逐字来自输入（用于回溯原话）；corrected 只允许补标点、
        去纯语气赘词、修明显同音错字；禁止改写语义、禁止添加原文没有的事实、
        禁止翻译、禁止把不确定处改成笃定表述。低置信句标 needs_review 交用户核对。
        """
        if not raw.strip():
            return {"sentences": [], "full_text": "", "adapter": "builtin",
                    "is_mock": True, "policy": "空输入"}
        if not (self._slot("polish") and self.cfg.get("transcript", {}).get("correct", True)):
            return self._rules_correction(raw)
        body = {
            "model": self.d.get("llm_model", "qwen3.8-max"),
            "enable_thinking": False,
            "messages": [
                {"role": "system", "content":
                    "你是语音转写的句级矫正器。输入是 ASR 原文（可能没有断句、有同音错字、"
                    "夹带口语赘词）。请按语义切句，逐句输出 JSON。\n"
                    "硬性规则：\n"
                    "1) raw 必须逐字照抄该句原文，一个字都不许改。\n"
                    "2) corrected 只许做四件事：补标点、去掉纯语气赘词（嗯/呃/啊/那个/就是说/然后呢）、"
                    "去掉连续重复、修明显的同音错字。\n"
                    "3) 严禁改写语义、严禁添加原文没有的人名/地点/数字/事实、严禁翻译、"
                    "严禁把不确定的猜测写成笃定陈述。\n"
                    "4) 拿不准的改动不要做，把 confidence 调低并置 needs_review=true。\n"
                    "5) edits 用短语列出改了什么（如「补句号」「去『嗯』」「期→其」）；没改就空数组。\n"
                    "6) 句数与原文语义分段一致，不要合并、不要拆分出原文没有的新句。"},
                {"role": "user", "content": raw},
            ],
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "correction", "strict": True,
                                                "schema": self.CORRECTION_SCHEMA}},
        }
        c = self._content(body)
        if c:
            try:
                parsed = json.loads(c)
                sents = parsed.get("sentences") or []
                clean = []
                for s in sents:
                    r, k = str(s.get("raw", "")), str(s.get("corrected", ""))
                    if not r and not k:
                        continue
                    conf = s.get("confidence", 0.8)
                    try:
                        conf = max(0.0, min(1.0, float(conf)))
                    except (TypeError, ValueError):
                        conf = 0.8
                    clean.append({
                        "raw": r or k, "corrected": k or r,
                        "edits": [str(x) for x in (s.get("edits") or [])][:6],
                        "confidence": conf,
                        "needs_review": bool(s.get("needs_review", False)) or conf < 0.7,
                    })
                if clean:
                    return {
                        "sentences": clean,
                        "full_text": "\n".join(x["corrected"] for x in clean),
                        "adapter": "qwen3.8-max:correction",
                        "is_mock": False,
                        "policy": "原文只读；L2 不回写；矫正句另存；禁止增删事实",
                    }
            except (json.JSONDecodeError, AttributeError, TypeError):
                pass
        return self._rules_correction(raw)

    def polish(self, raw: str) -> tuple[str, str, dict]:
        """兼容旧调用：返回 (矫正后整段文本, adapter, 句级明细)。"""
        d = self.correct_transcript(raw)
        return d["full_text"], d["adapter"], d

    @staticmethod
    def _polish_rules(raw: str) -> str:
        import re
        t = re.sub(r"[，,]{2,}", "，", raw)
        t = re.sub(r"(嗯|呃|啊|那个|就是说|然后呢)(?=[，。？！\s]|$)", "", t)
        parts = [p.strip() for p in re.split(r"(?<=[。！？!?])|[\n；;]+", t) if p.strip()]
        out = []
        for p in parts:
            if p and p[-1] not in "。！？!?，,":
                p += "。"
            out.append(p)
        return "".join(out)

    # ---------- 收录后 AI 分析 ----------
    def analyze(self, *, mode_id: str, source_id: str, transcript: str,
                polished: str, events: list[dict], history: list[dict]) -> dict:
        evidence_ids = [e.get("id") for e in events[:20]]
        base = {
            "id": new_id("ana"), "mode_id": mode_id, "source_id": source_id,
            "transcript_polished": polished, "evidence_ids": evidence_ids,
            "event_count": len(events), "history_count": len(history),
            "created_at": now_iso(),
        }
        if not self.ready:
            out = {**base, **self._analysis_rules(mode_id, events, history),
                   "is_mock": True, "adapter": "builtin:rules"}
            self.store.put_analysis(out)
            return out
        hist = "\n".join(
            f"[{(h.get('event_time') or '时间未定')[:10]}] {h.get('quote','')}"
            for h in history[:30])
        persona_prompt, _ = self.persona()
        system = (persona_prompt + "\n\n" if persona_prompt else "") + \
                 MODE_PROMPTS.get(mode_id, MODE_PROMPTS["life"]) + " "
        body = {
            "model": self.d.get("llm_model", "qwen3.8-max"),
            "enable_thinking": False,
            "messages": [
                {"role": "system", "content":
                    system +
                    "自由分析、自由生成，可以主动给建议、提醒、计划和评估，没有任何输出限制。"
                    "patterns 要跨时间看重复与变化；若历史里有前后矛盾请指出。"
                    "每个数组至少给 2 条具体内容，不要给空数组、不要写套话。"
                    "只依据给定记录，不要编造事实。按 JSON Schema 输出。"},
                {"role": "user", "content":
                    f"本次转写（已顺文本）：\n{polished or transcript}\n\n"
                    f"本次事件卡：\n" + "\n".join(f"- {e.get('quote','')}" for e in events[:20]) +
                    f"\n\n该模式历史记录（用于跨时间对比）：\n{hist or '（暂无历史）'}"},
            ],
            "response_format": {"type": "json_schema",
                                "json_schema": {"name": "analysis", "strict": True,
                                                "schema": ANALYSIS_SCHEMA}},
        }
        c = self._content(body)
        if c:
            try:
                parsed = json.loads(c)
                out = {**base, **parsed, "is_mock": False, "adapter": "qwen3.8-max:analysis"}
                self.store.put_analysis(out)
                return out
            except (json.JSONDecodeError, TypeError):
                pass
        out = {**base, **self._analysis_rules(mode_id, events, history),
               "is_mock": True, "adapter": "builtin:rules"}
        self.store.put_analysis(out)
        return out

    @staticmethod
    def _analysis_rules(mode_id: str, events: list[dict], history: list[dict]) -> dict:
        lines = [e.get("quote", "") for e in events if e.get("quote")]
        kp = [f"记录到：{x[:40]}" for x in lines[:5]]
        pats = [f"该模式累计 {len(history)} 条历史记录，本次新增 {len(events)} 条。"]
        types: dict[str, int] = {}
        for h in history:
            types[h.get("type", "事")] = types.get(h.get("type", "事"), 0) + 1
        if types:
            top = max(types, key=types.get)
            pats.append(f"出现最多的事件类型是「{top}」（{types[top]} 次）。")
        return {
            "summary": f"本次收录 {len(events)} 张事件卡，共 {sum(len(x) for x in lines)} 字。",
            "key_points": kp or ["暂无要点"],
            "patterns": pats,
            "suggestions": ["配置 qwen3.8-max 后可获得针对性建议。"],
            "reminders": ["当前为规则版分析，未调用大模型。"],
            "growth_plan": ["先持续收录，积累跨时间轨迹后再做成长计划。"],
        }
