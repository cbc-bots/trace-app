"""内置适配器：零第三方依赖、离线可跑的真实实现（规则版，不伪造模型输出）。

每个技能都有可运行实现；结果明确标注 is_mock，便于上层切换到 GitHub 现成模型。
"""
from __future__ import annotations

import math
import os
import re
import shutil
import struct
import subprocess
import wave
from typing import Any

from core import make_event, now_iso, new_id

DATE_PATTERNS = [
    (re.compile(r"(20\d{2})[-/.年](\d{1,2})[-/.月](\d{1,2})"), lambda m: (int(m[1]), int(m[2]), int(m[3]))),
    (re.compile(r"(?<!\d)(\d{1,2})-(\d{1,2})(?!\d)"), lambda m: (2026, int(m[1]), int(m[2]))),
    (re.compile(r"(\d{1,2})月(\d{1,2})日"), lambda m: (2026, int(m[1]), int(m[2]))),
]

TYPE_RULES = [
    (("决定", "要不要", "还继续吗", "选择", "打算"), "决定"),
    (("结果", "拿奖", "获奖", "跑通", "完成", "通过", "二等奖", "发布"), "结果"),
    (("倾诉", "跟人聊", "聊完", "说给我", "谈心"), "情绪"),
    (("上课", "家教", "老师", "同学", "组队", "开会", "面试"), "人"),
]

EMOTION_WORDS = {
    "期待": ("想试试", "期待", "想看"),
    "焦虑": ("怕", "焦虑", "紧张", "担心"),
    "自我怀疑": ("什么都做不好", "不行吧", "怀疑自己"),
    "兴奋": ("跑通了", "太好了", "兴奋"),
    "落差": ("没那么开心", "本以为会更高兴", "落差"),
    "疲惫": ("真的累", "累", "撑不住"),
    "犹豫": ("还继续吗", "要不要", "犹豫"),
    "平静": ("按部就班", "还行", "平静"),
    "释然": ("大概就是这样", "释然", "放下了"),
}

INTENSIFIERS = ("真的", "特别", "非常", "很", "有点")


def parse_event_date(text: str) -> str | None:
    """从用户口述里抽 event_time（事件日），不拿录音时间冒充。"""
    for pat, fn in DATE_PATTERNS:
        m = pat.search(text)
        if m:
            y, mo, d = fn(m)
            try:
                return f"{y:04d}-{mo:02d}-{d:02d}T00:00:00+08:00"
            except (ValueError, TypeError):
                continue
    return None


def classify(text: str, mode_id: str) -> dict:
    """排序分类：按受控词表 + 规则，返回 extensions[mode_id]。"""
    ext: dict[str, Any] = {}
    typ = "事"
    for keys, t in TYPE_RULES:
        if any(k in text for k in keys):
            typ = t
            break
    if mode_id == "life":
        tag, intensity, hit = None, None, None
        for t, words in EMOTION_WORDS.items():
            for w in words:
                if w in text:
                    tag, hit = t, w
                    break
            if tag:
                break
        for i, w in enumerate(INTENSIFIERS):
            if w in text:
                intensity = 3 - min(i, 2)
                break
        ext["emotion"] = {"tag": tag, "intensity": intensity, "source": "user",
                          "matched_phrase": hit, "confirmed": True}
    elif mode_id == "study":
        ext["topic"] = next((k for k in ("数学", "英语", "物理", "编程", "专业课") if k in text), "未分类")
        ext["mastery"] = "待评估" if "不懂" in text or "没听" in text else "已理解"
    elif mode_id == "relation":
        ext["person"] = "对方" if any(k in text for k in ("对象", "她", "他", "约饭", "闲聊")) else "自己"
        ext["interaction"] = "约会" if "约饭" in text else "闲聊"
    elif mode_id == "growth":
        ext["quadrant"] = next((q for q in ("Mind", "Body", "Spirit", "Vocation")
                                if q.lower() in text.lower()), "Mind")
        ext["level"] = "3.0" if "整合" in text else "2.0"
    return {"type": typ, "extensions": ext}


# ---------- VAD ----------
def vad_segments(wav_path: str, min_speech_ms: int = 800, silence_db: float = -32) -> list[dict]:
    """能量型 VAD：读标准 WAV（wave 模块），按静音切段。零依赖。"""
    try:
        with wave.open(wav_path, "rb") as w:
            rate, width, ch = w.getframerate(), w.getsampwidth(), w.getnchannels()
            raw = w.readframes(w.getnframes())
    except (wave.Error, EOFError, FileNotFoundError):
        return []
    if not raw or width not in (1, 2, 4):
        return []
    fmt = {1: "b", 2: "h", 4: "i"}[width]
    try:
        samples = struct.unpack(f"<{len(raw)//width}{fmt}", raw)
    except struct.error:
        return []
    if ch > 1:
        samples = samples[::ch]
    win = max(1, int(rate * 0.02))
    threshold = (10 ** (silence_db / 20)) * (2 ** (8 * width - 1))
    energies = []
    for i in range(0, len(samples), win):
        block = samples[i:i + win]
        if not block:
            continue
        energies.append(sum(abs(v) for v in block) / len(block))
    if not energies:
        return []
    active = [e > threshold for e in energies]
    segs, start = [], None
    for i, a in enumerate(active):
        if a and start is None:
            start = i
        elif not a and start is not None:
            s_ms, e_ms = start * win * 1000 // rate, i * win * 1000 // rate
            if e_ms - s_ms >= min_speech_ms:
                segs.append({"start_ms": s_ms, "end_ms": e_ms})
            start = None
    if start is not None:
        s_ms = start * win * 1000 // rate
        e_ms = len(energies) * win * 1000 // rate
        if e_ms - s_ms >= min_speech_ms:
            segs.append({"start_ms": s_ms, "end_ms": e_ms})
    return segs


# ---------- speaker ----------
def speaker_of(segment: dict, default: str = "self") -> str:
    """other 语音全保留：speaker 只用于归属与展示，不作为丢弃依据。"""
    s = segment.get("speaker")
    return s if s in ("self", "other", "mixed") else default


# ---------- extractor ----------
def extract_events(text: str, mode_id: str, *, file: str = "text://inline",
                   recorded_at: str | None = None, capture_session: str = "live",
                   is_mock: bool = True) -> list[dict]:
    """把 L2 转写切成事件卡。规则版：按句切 + 日期/类型/词表归一。"""
    parts = [p.strip() for p in re.split(r"[。！？!?\n；;]+", text) if p.strip()]
    events = []
    for p in parts:
        c = classify(p, mode_id)
        events.append(make_event(
            mode_id=mode_id,
            event_time=parse_event_date(p),
            recorded_at=recorded_at,
            quote_ref={"file": file, "start_ms": 0, "end_ms": 0, "quote": p},
            speaker=speaker_of({"speaker": "self"}),
            source="user", confidence=0.9, confirmed=True,
            capture_session=capture_session, is_mock=is_mock,
            summary=p[:60], quote=p, type=c["type"], extensions=c["extensions"],
        ))
    return events


# ---------- ranker ----------
def rank(events: list[dict], query: str) -> list[dict]:
    """关键词相关度排序（FTS5 不可用时由 Store 调用）。"""
    if not query.strip():
        return sorted(events, key=lambda e: (e.get("event_time") is None, e.get("event_time") or ""))
    terms = [t for t in re.split(r"\s+", query.strip()) if t]
    scored = []
    for e in events:
        blob = (e.get("quote", "") + " " + e.get("summary", "") + " " + e.get("type", ""))
        score = sum(blob.count(t) * 2 + (1 if t in blob.lower() else 0) for t in terms)
        if score:
            scored.append((score, e))
    scored.sort(key=lambda x: (-x[0], x[1].get("event_time") or ""))
    return [e for _, e in scored]


# ---------- agent ----------
def agent_reply(query: str, evidence: list[dict], persona: dict | None = None,
                mode_id: str = "life") -> dict:
    """自由生成（规则版）：基于检索证据组织回答，不限制输出内容。"""
    persona = persona or {"name": "基础助手", "style": "自由回答"}
    if not evidence:
        answer = f"当前 {mode_id} 模式的知识库里没有找到与「{query}」相关的内容。"
    else:
        lines = []
        for e in evidence[:8]:
            d = (e.get("event_time") or "时间未定")[:10]
            lines.append(f"[{d}] {e.get('quote', '')}")
        answer = f"关于「{query}」，我在 {len(evidence)} 条记录里找到这些：\n" + "\n".join(lines)
        answer += "\n\n（以上为记录原文，可以自由继续追问、让我给建议、做计划或展开分析。）"
    return {
        "answer": answer,
        "agent": persona.get("name", "基础助手"),
        "mode_id": mode_id,
        "evidence_ids": [e.get("id") for e in evidence[:8]],
        "is_mock": True,
        "generated_at": now_iso(),
    }


# ---------- ASR ----------
def asr_from_text(text: str, file: str = "text://inline") -> list[dict]:
    """文本直录通道：无音频模型时显式以 L2 原文入库，不伪造转写结果。

    按句切段，这样说话人归属能逐句标注、事件卡能逐句继承证据戳。
    """
    parts = [p.strip() for p in re.split(r"[。！？!?\n；;]+", text) if p.strip()]
    if not parts:
        return [{"start_ms": 0, "end_ms": 1, "quote": text, "speaker": "self", "is_mock": True}]
    out, cursor = [], 0
    for p in parts:
        dur = max(1, len(p) * 80)
        out.append({"start_ms": cursor, "end_ms": cursor + dur,
                    "quote": p, "speaker": "self", "is_mock": True})
        cursor += dur + 200
    return out


def find_ffmpeg() -> str | None:
    """优先安装包自带的 bin/，其次系统 PATH，最后 imageio-ffmpeg 自带二进制。"""
    here = os.path.dirname(os.path.abspath(__file__))
    for cand in (os.path.join(here, "bin", "ffmpeg.exe"),
                 os.path.join(here, "..", "bin", "ffmpeg.exe"),
                 os.path.join(here, "bin", "ffmpeg")):
        if os.path.isfile(cand):
            return os.path.abspath(cand)
    p = shutil.which("ffmpeg")
    if p:
        return p
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def transcode_to_wav(src: str, cfg: dict) -> str:
    """把手机录音（m4a/mp3/aac/amr/任意采样率）统一转成 16k mono PCM WAV。

    原始文件保持不变（供证据回放），返回的是供 VAD / ASR 消费的转码文件。
    已经是 16k mono s16 WAV 时直接复用，避免无谓拷贝。
    """
    try:
        with wave.open(src, "rb") as w:
            if (w.getnchannels() == 1 and w.getframerate() == 16000
                    and w.getsampwidth() == 2):
                return src
    except Exception:
        pass
    exe = find_ffmpeg()
    if not exe:
        return src
    dst = src + ".16k.wav"
    try:
        subprocess.run(
            [exe, "-y", "-loglevel", "error", "-i", src,
             "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", dst],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=180)
    except Exception:
        return src
    return dst if os.path.isfile(dst) and os.path.getsize(dst) > 44 else src


# ---------- 存证格式：三种音频职责分离（72 小时保留策略的前提） ----------
#   inbox/{fname}      用户原始上传件        → 转码后即删（可配）
#   l1/{stem}.16k.wav  ASR/VAD 消费格式      → 流水线结束即删（可配）
#   l1/{stem}.ogg      长期存证（Opus 24k）   → 保留 72h，用户可 keep 永久保留
# 实测（包内 ffmpeg 9.0.2，10 分钟真实中文语音折算）：
#   WAV 16k mono s16 = 109.9 MB/h ；Opus 24k = 10.3 MB/h（10.7x）；AAC 32k = 14.1 MB/h。
EVIDENCE_CODECS = {
    "opus": {"ext": ".ogg", "args": ["-c:a", "libopus", "-b:a", "{br}"], "mb_per_hour": 10.3},
    "aac":  {"ext": ".m4a", "args": ["-c:a", "aac", "-b:a", "{br}"],      "mb_per_hour": 14.1},
    "mp3":  {"ext": ".mp3", "args": ["-c:a", "libmp3lame", "-b:a", "{br}"], "mb_per_hour": 13.7},
}
DEFAULT_BITRATE = {"opus": "24k", "aac": "32k", "mp3": "32k"}


def to_consumer_wav(src: str, dst: str, cfg: dict) -> bool:
    """把任意来源音频转成 16k mono s16 WAV（ASR/VAD 消费格式），总是写到指定 dst。

    与 transcode_to_wav 的区别：调用方因此能确定地知道哪个文件是「临时消费件」、
    跑完可以安全删除，不会把用户原件误当中间产物删掉。
    """
    if os.path.abspath(src) == os.path.abspath(dst):
        return True
    try:
        with wave.open(src, "rb") as w:
            conform = (w.getnchannels() == 1 and w.getframerate() == 16000
                       and w.getsampwidth() == 2)
    except Exception:
        conform = False
    if conform:
        try:
            shutil.copyfile(src, dst)
            return os.path.isfile(dst) and os.path.getsize(dst) > 44
        except OSError:
            return False
    exe = find_ffmpeg()
    if not exe:
        return False
    try:
        subprocess.run([exe, "-y", "-loglevel", "error", "-i", src,
                        "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", dst],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)
    except Exception:
        return False
    return os.path.isfile(dst) and os.path.getsize(dst) > 44


def compress_evidence(src_wav: str, dst_stem: str, cfg: dict) -> dict | None:
    """把消费用 WAV 压成长期存证件。失败返回 ok=False，调用方必须退回保留 WAV。

    存证件与 WAV 时间轴一致，所以 quote_ref 的 start_ms/end_ms 不需要重算，
    W8「点卡片跳音频时间点」照常工作 —— 这是「压缩而不是删除」的关键。
    """
    ret = cfg.get("retention", {}) or {}
    codec = str(ret.get("evidence_codec") or "opus").lower()
    if codec not in EVIDENCE_CODECS:
        return {"ok": False, "codec": codec, "error": f"未知 evidence_codec: {codec}"}
    exe = find_ffmpeg()
    if not exe:
        return {"ok": False, "codec": codec, "error": "未找到 ffmpeg"}
    spec = EVIDENCE_CODECS[codec]
    br = str(ret.get("evidence_bitrate") or DEFAULT_BITRATE[codec])
    dst = dst_stem + spec["ext"]
    args = [a.replace("{br}", br) for a in spec["args"]]
    src_bytes = os.path.getsize(src_wav) if os.path.isfile(src_wav) else 0
    try:
        subprocess.run([exe, "-y", "-loglevel", "error", "-i", src_wav,
                        *args, "-ar", "16000", "-ac", "1", dst],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=900)
    except Exception as e:
        return {"ok": False, "codec": codec, "error": f"{type(e).__name__}: {str(e)[:120]}"}
    if not os.path.isfile(dst) or os.path.getsize(dst) < 512:
        return {"ok": False, "codec": codec, "error": "压缩产物无效或过小"}
    after = os.path.getsize(dst)
    return {"ok": True, "codec": codec, "bitrate": br, "ext": spec["ext"], "path": dst,
            "bytes": after, "bytes_before": src_bytes,
            "ratio": round(src_bytes / after, 1) if after else 0,
            "mb_per_hour": spec["mb_per_hour"]}


def wav_from_pcm_chunks(chunks: list[bytes], out_path: str, cfg: dict) -> str:
    """长时间常驻录音：把分段 PCM 写成标准 WAV，供 VAD/ASR 消费。"""
    r = cfg.get("recorder", {})
    with wave.open(out_path, "wb") as w:
        w.setnchannels(r.get("channels", 1))
        w.setsampwidth(r.get("sample_width", 2))
        w.setframerate(r.get("sample_rate", 16000))
        for c in chunks:
            w.writeframes(c)
    return out_path
