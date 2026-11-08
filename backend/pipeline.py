"""流水线编排 + 长时间常驻录音会话（Q1 已定：程序常驻收音）。

链路：inbox/ → VAD 切段 → ASR → speaker 归属（other 全保留）→ 抽取 → 分类排序 → 成卡 → 存储
每一步都记 stage_log，供 W5 流水线窗口可视化；任一外部适配器不可用自动降级 builtin。
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import threading
import wave
from typing import Any

import adapters_builtin as builtin
from adapters_oss import resolve, resolve_chain
from analysis import AnalysisEngine
from core import now_iso, new_id


class IngestPipeline:
    def __init__(self, store, cfg, data_dir: str):
        self.store = store
        self.cfg = cfg
        self.data_dir = data_dir
        self.inbox = os.path.join(data_dir, "inbox")
        self.l1 = os.path.join(data_dir, "l1")
        os.makedirs(self.inbox, exist_ok=True)
        os.makedirs(self.l1, exist_ok=True)

    def run(self, *, source: str, mode_id: str = "life", text: str | None = None,
            audio_b64: str | None = None, filename: str | None = None,
            capture_session: str = "live", recorded_at: str | None = None,
            time_source: str = "file_meta", is_demo_sample: bool = False) -> dict:
        """source: 'text' | 'file' | 'upload' | 'recorder'"""
        sid = new_id("src")
        stages: list[dict] = []
        adapter_used: dict[str, str] = {}

        def stage(name: str, adapter: str, detail: dict):
            stages.append({"stage": name, "adapter": adapter, "detail": detail})
            self.store.log_stage(sid, mode_id, name, adapter, detail)

        # ---- 1. inbox/ → 消费格式(临时 WAV) → 存证格式(压缩件) ----
        # 三种格式职责分离，这是「72 小时保留策略」能成立的前提：
        #   inbox/{fname}       用户原始上传件        → 转码后即删（可配）
        #   l1/{stem}.16k.wav   ASR/VAD 消费格式      → 流水线结束即删（可配）
        #   l1/{stem}.ogg       长期存证 Opus 24k      → 保留 72h，用户可 keep 永久保留
        # quote_ref.file 指向存证件；存证与 WAV 时间轴一致，start_ms/end_ms 不需重算。
        file_ref = f"text://{sid}"
        wav_path = None
        evidence: dict = {"ok": False, "codec": "none"}
        upload_original: str | None = None
        if source in ("upload", "file", "recorder"):
            fname = filename or f"{sid}.wav"
            stem = os.path.splitext(os.path.basename(fname))[0] or sid
            inbox_path = os.path.join(self.inbox, fname)
            if audio_b64:
                with open(inbox_path, "wb") as f:
                    f.write(base64.b64decode(audio_b64))
            elif source == "file" and os.path.isfile(text or ""):
                inbox_path = text
            if not os.path.isfile(inbox_path):
                return {"ok": False, "error": f"找不到音频文件: {inbox_path}", "stages": stages}
            with open(inbox_path, "rb") as f:
                raw = f.read()
            sha = hashlib.sha256(raw).hexdigest()
            if os.path.abspath(inbox_path).startswith(os.path.abspath(self.inbox)):
                upload_original = inbox_path

            # 消费格式：显式落到 l1/{stem}.16k.wav，这样收尾时能确定地删掉它
            tmp_wav = os.path.join(self.l1, f"{stem}.16k.wav")
            wav_path = tmp_wav if builtin.to_consumer_wav(inbox_path, tmp_wav, self.cfg) else inbox_path

            # 存证格式：实测 109.9 → 10.3 MB/小时（10.7x）；失败则退回保留 WAV，绝不丢证据
            ev = builtin.compress_evidence(wav_path, os.path.join(self.l1, stem), self.cfg)
            if ev and ev.get("ok"):
                evidence = ev
                ref_abs = ev["path"]
            else:
                evidence = {"ok": False, "codec": "wav",
                            "reason": (ev or {}).get("error", "未知原因")}
                ref_abs = wav_path
            file_ref = os.path.relpath(ref_abs, self.data_dir).replace("\\", "/")
            hours = float(self.cfg.get("retention", {}).get("hours", 72))
            self.store.put_l1(file_ref, recorded_at, capture_session, time_source,
                              is_demo_sample, sha,
                              {"size": len(raw), "filename": fname, "evidence": evidence,
                               "ffmpeg": builtin.find_ffmpeg()},
                              keep=False, expire_hours=hours,
                              evidence_codec=evidence.get("codec", ""),
                              bytes_on_disk=os.path.getsize(ref_abs) if os.path.isfile(ref_abs) else 0)
            stage("收录", "inbox", {"file": file_ref, "bytes": len(raw), "sha256": sha[:12],
                                     "evidence_codec": evidence.get("codec"),
                                     "evidence_bytes": evidence.get("bytes"),
                                     "ratio": evidence.get("ratio"),
                                     "retention_hours": hours,
                                     "note": evidence.get("reason")
                                             or "存证已压缩；消费用 WAV 与原始上传件将在流水线结束时删除"})
        else:
            stage("收录", "text-channel", {"file": file_ref, "note": "文本直录，L2 原文直接入库"})

        # ---- 2. VAD 切段 ----
        vad_inst, vad_name = resolve(self.cfg, "vad")
        segments: list[dict] = []
        if wav_path:
            if vad_inst:
                try:
                    segments = vad_inst.segments(wav_path)
                except Exception:
                    segments = []
            if not segments:
                segments = builtin.vad_segments(wav_path)
                vad_name = "builtin" if not vad_inst else f"{vad_name}->builtin"
        stage("VAD 切段", vad_name, {"segments": len(segments),
                                     "note": "无效片段丢弃、不落盘" if segments else "非 WAV 或未检出语音段"})
        adapter_used["vad"] = vad_name

        # ---- 3. ASR：适配器链式降级（在线大模型 → 本地大模型 → 本地小模型） ----
        asr_inst, asr_name = resolve(self.cfg, "asr")
        l2: list[dict] = []
        tried: list[str] = []
        if source == "text" or (text and not wav_path):
            l2 = builtin.asr_from_text(text or "", file_ref)
            asr_name = "builtin:text-channel"
        elif wav_path:
            for inst, nm in resolve_chain(self.cfg, "asr"):
                try:
                    got = inst.transcribe(wav_path)
                except Exception as e:
                    got = []
                    tried.append(f"{nm}:{type(e).__name__}")
                if got:
                    l2 = got
                    asr_name = nm
                    break
                tried.append(nm)
            if not l2:
                # 全链路失败才落文本通道；绝不伪造转写结果
                l2 = builtin.asr_from_text(text or "", file_ref) if text else []
                asr_name = "builtin:no-model"
            # 降级过程记进「语音转写」阶段的 detail，不新增阶段（保持 8 阶段叙事）
        if segments and l2:
            for i, s in enumerate(l2):
                if i < len(segments):
                    s["start_ms"] = segments[i].get("start_ms", s.get("start_ms", 0))
                    s["end_ms"] = segments[i].get("end_ms", s.get("end_ms", 0))
        raw_transcript = "\n".join(s.get("quote", "") for s in l2)
        analyzer = AnalysisEngine(self.cfg, self.store)
        # 句级转写矫正（并入「语音转写」阶段，不新增阶段、不改 8 阶段叙事）：
        # L2 原文只读不动；矫正句另存 L2'；原文与矫正句逐句并存可回溯。
        polished, polish_adapter, correction = "", "builtin", {}
        if raw_transcript.strip():
            # 录音通道与「粘贴别处转写文本」的文本通道都走句级矫正；
            # 无 key 时规则版零改动，绝不改写用户原文。
            polished, polish_adapter, correction = analyzer.polish(raw_transcript)
        else:
            polished, polish_adapter, correction = "", "builtin", {}
        sent = correction.get("sentences", []) if correction else []
        stage("语音转写", asr_name, {
            "l2_segments": len(l2),
            "is_mock": any(s.get("is_mock") for s in l2),
            "polish_adapter": polish_adapter,
            "polished_preview": polished[:160],
            "correction_sentences": len(sent),
            "needs_review": sum(1 for x in sent if x.get("needs_review")),
            "policy": correction.get("policy", "L2 原文只读；矫正句另存"),
            "asr_tried": tried,
            "omni_one_call": any(isinstance(s.get("omni"), dict) for s in l2),
        })
        adapter_used["asr"] = asr_name
        adapter_used["polish"] = polish_adapter
        if l2:
            self.store.put_l2(file_ref, l2)

        # ---- 4. speaker 归属（other 全保留） ----
        spk_inst, spk_name = resolve(self.cfg, "speaker")
        policy = "other 全保留，不丢弃"
        if spk_inst and wav_path and hasattr(spk_inst, "assign"):
            # 本地声纹模型：按音频 embedding 判定
            for s in l2:
                try:
                    s["speaker"] = spk_inst.assign(wav_path)
                except Exception:
                    s["speaker"] = builtin.speaker_of(s)
        elif spk_inst and l2 and hasattr(spk_inst, "assign_text"):
            # 在线 LLM 推断：零模型下载，结果属 inferred
            try:
                labels = spk_inst.assign_text([s.get("quote", "") for s in l2])
                for s, lab in zip(l2, labels):
                    s["speaker"] = builtin.speaker_of({"speaker": lab})
                policy = "other 全保留；在线 LLM 语义推断（inferred，非声纹）"
            except Exception:
                for s in l2:
                    s["speaker"] = builtin.speaker_of(s)
                spk_name = f"{spk_name}->builtin"
        else:
            for s in l2:
                s["speaker"] = builtin.speaker_of(s)
            spk_name = "builtin" if not spk_inst else spk_name
        counts = {k: sum(1 for s in l2 if s.get("speaker") == k) for k in ("self", "other", "mixed")}
        stage("说话人归属", spk_name, {"counts": counts, "policy": policy})
        adapter_used["speaker"] = spk_name

        # ---- 5. 抽取 + 分类（注册表驱动） ----
        ext_inst, ext_name = resolve(self.cfg, "extractor")
        comp = self.store.resolve_component("classifier", mode_id)
        # 抽取改吃「矫正后的句级文本」：错字与断句先修好，事件卡才不会跟着错。
        # L2 原文仍只读不动，事件卡里另存 evidence_raw 供回溯。
        transcript = polished or "\n".join(s.get("quote", "") for s in l2)
        raw_events: list[dict] = []
        # 音频直投 omni 时，转写 + 断句纠错 + 事件概括 + 事件卡已在**一次调用**里完成，
        # 不再对同一段文本做第二次 LLM 抽取（省钱、省延迟，且前后语义一致）。
        omni = next((s.get("omni") for s in l2 if isinstance(s.get("omni"), dict)), None)
        if omni and omni.get("events"):
            raw_events = list(omni["events"])
            ext_name = f"{asr_name} (一次调用含转写+概括+抽卡)"
        elif ext_inst and transcript:
            try:
                raw_events = ext_inst.extract(transcript, mode_id)
            except Exception:
                raw_events = []
        if not raw_events and transcript:
            raw_events = builtin.extract_events(
                transcript, mode_id, file=file_ref, recorded_at=recorded_at,
                capture_session=capture_session, is_mock=True)
            ext_name = "builtin" if not ext_inst else f"{ext_name}->builtin"
        # 用户要的「事件概括一句话」：omni 直接给，优先用作卡片摘要
        if omni and omni.get("summary"):
            for r in raw_events:
                if not r.get("summary") or r.get("summary") == r.get("quote"):
                    r["summary"] = omni["summary"]
        stage("事件抽取", f"{ext_name} / classifier={comp['id'] if comp else 'builtin'}",
              {"events": len(raw_events), "mode_id": mode_id})
        adapter_used["extractor"] = ext_name

        # ---- 6. 成卡入库 ----
        raw_by_corrected = {x["corrected"]: x["raw"] for x in sent if x.get("corrected")}
        saved = []
        for i, r in enumerate(raw_events):
            c = builtin.classify(r.get("quote", ""), mode_id)
            # 事件卡保留「矫正句 → 原文」映射，W8 可一眼对照 AI 有没有改掉原意
            _q = r.get("quote", "")
            _ext = dict(r.get("extensions") or c["extensions"])
            _ext["evidence_raw"] = raw_by_corrected.get(_q, _q)
            ev = {
                "id": r.get("id") or new_id("evt"),
                "mode_id": mode_id,
                "event_time": r.get("event_time"),
                "recorded_at": recorded_at,
                "quote_ref": {
                    "file": file_ref,
                    "start_ms": (l2[i].get("start_ms", 0) if i < len(l2) else r.get("start_ms", 0)),
                    "end_ms": (l2[i].get("end_ms", 0) if i < len(l2) else r.get("end_ms", 0)),
                    "quote": r.get("quote", ""),
                },
                "speaker": r.get("speaker") or (l2[i].get("speaker", "self") if i < len(l2) else "self"),
                "source": r.get("source", "user"),
                "confidence": float(r.get("confidence", 0.9)),
                "confirmed": True,
                "capture_session": capture_session,
                "is_mock": bool(r.get("is_mock", asr_name.startswith("builtin"))),
                "is_demo_sample": is_demo_sample,
                "summary": r.get("summary") or r.get("quote", "")[:60],
                "quote": r.get("quote", ""),
                "type": r.get("type") or c["type"],
                "extensions": _ext,
                "l3a_version": 1,
                "corrected": False,
                "created_at": now_iso(),
            }
            self.store.put_event(ev)
            saved.append(ev)
        stage("成卡入库", "core.Store", {"saved": len(saved), "fts5": self.store.has_fts})

        # ---- 记忆摘要缓存：收录后重建一次，之后每轮对话只读这一行 ----
        # 不新增阶段（仍是 8 阶段），只挂在这一步的 detail 里，避免动 smoke_test 的断言。
        digest_chars = 0
        if saved:
            try:
                digest_chars = len(self.store.build_digest(mode_id))
            except Exception as e:
                print(f"[履迹] 摘要重建失败：{type(e).__name__}: {e}")

        # ---- 7. 排序整理（ranker 可插拔） ----
        rk_inst, rk_name = resolve(self.cfg, "ranker")
        if rk_inst:
            try:
                saved = rk_inst.rank("", saved)
            except Exception:
                rk_name = f"{rk_name}->builtin"
        stage("知识库排序整理", rk_name, {"count": len(saved), "digest_chars": digest_chars})
        adapter_used["ranker"] = rk_name

        # ---- 8. AI 分析：本次收录 + 该模式历史跨时间对比，自动产出，不需用户另问 ----
        history = self.store.list_events(mode_id)
        analysis = analyzer.analyze(
            mode_id=mode_id, source_id=sid, transcript=raw_transcript,
            polished=polished or raw_transcript, events=saved, history=history)
        stage("AI 分析", analysis["adapter"], {
            "analysis_id": analysis["id"],
            "is_mock": analysis["is_mock"],
            "sections": {k: len(analysis.get(k, [])) for k in
                         ("key_points", "patterns", "suggestions", "reminders", "growth_plan")},
        })
        adapter_used["analysis"] = analysis["adapter"]

        # ---- 收尾：删临时消费件与原始上传件（存证件与文本层一律不动） ----
        ret = self.cfg.get("retention", {}) or {}
        cleanup = {"wav_deleted": False, "original_deleted": False}
        if evidence.get("ok") and wav_path and os.path.isfile(wav_path):
            # 只有真的压出了存证件、且它不是同一个文件时，才允许删 WAV
            if (ret.get("delete_wav_after_archive", True)
                    and os.path.abspath(wav_path) != os.path.abspath(evidence["path"])):
                try:
                    os.remove(wav_path)
                    cleanup["wav_deleted"] = True
                except OSError:
                    pass
        if upload_original and ret.get("delete_original_upload", True):
            if os.path.abspath(upload_original) != os.path.abspath(os.path.join(self.data_dir, file_ref)):
                try:
                    os.remove(upload_original)
                    cleanup["original_deleted"] = True
                except OSError:
                    pass

        return {"ok": True, "source_id": sid, "file": file_ref, "mode_id": mode_id,
                "stages": stages, "adapters": adapter_used, "events": saved,
                "analysis": analysis, "polished_transcript": polished or raw_transcript,
                "raw_transcript": raw_transcript, "correction": correction,
                "asr_status": "ok" if (l2 and not any(s.get("is_mock") for s in l2))
                              else ("text_channel" if l2 else "empty"),
                "asr_failure": "" if l2 else "未产出转写文本",
                "event_summary": (omni or {}).get("summary", ""),
                "evidence": {k: v for k, v in evidence.items() if k != "path"},
                "cleanup": cleanup,
                "retention": self.store.retention_stats(),
                "stats": self.store.stats(mode_id)}


class AlwaysOnRecorder:
    """Q1 已定：程序常驻收音。分段 PCM 落盘 + 会话状态机。

    真机由 APK 前台服务推 PCM 块到 POST /record/push；本机无麦克风时用注入块验证链路。

    v1.1 三处修正：
      1. 增量消费 —— 每次 flush 原子地取走并清空「尚未处理」的 PCM 块。
         旧版 _flush 只读不清，每 3 块就把整段会话历史重跑一遍，
         重复量随会话时长平方增长（实测 27s 会话被处理成 81s）。
      2. 异步落库 —— flush 在后台线程跑完整流水线，/record/push 立即返回。
         否则接上真实 ASR + qwen 抽取后，每次推流都要阻塞数秒，前端切片积压。
      3. 分段独立编号 —— {sid}_seg0001.wav、seg0002.wav……不再互相覆盖，
         因此 L2 层不会互相 DELETE，quote_ref 的文件与时间戳不会串台。
    """

    def __init__(self, store, cfg, pipeline: IngestPipeline, data_dir: str):
        self.store = store
        self.cfg = cfg
        self.pipeline = pipeline
        self.data_dir = data_dir
        self._chunks: dict[str, list[bytes]] = {}
        self._seq: dict[str, int] = {}
        self._threads: dict[str, list[threading.Thread]] = {}
        self._timer: threading.Timer | None = None
        self._lock = threading.Lock()

    @property
    def flush_every(self) -> int:
        """多少块 PCM 触发一次落库；可被 config.recorder.flush_every_chunks 显式覆盖。"""
        r = self.cfg.get("recorder", {})
        if r.get("flush_every_chunks"):
            return max(1, int(r["flush_every_chunks"]))
        return max(1, int(r.get("chunk_seconds", 30)) // 10)

    def start(self, mode_id: str) -> dict:
        s = self.store.start_session(mode_id)
        with self._lock:
            self._chunks[s["id"]] = []
            self._seq[s["id"]] = 0
            self._threads[s["id"]] = []
        self.store.log_stage(s["id"], mode_id, "常驻录音启动", "always_on",
                             {"chunk_seconds": self.cfg["recorder"].get("chunk_seconds", 30),
                              "flush_every_chunks": self.flush_every})
        return s

    def push(self, sid: str, pcm_b64: str) -> dict:
        raw = base64.b64decode(pcm_b64)
        due = False
        with self._lock:
            buf = self._chunks.setdefault(sid, [])
            buf.append(raw)
            if len(buf) >= self.flush_every:
                due = True
        s = self.store.push_chunk(sid, len(raw))
        seg = self._flush(sid, background=True) if due else None
        return {"session_id": sid, "chunks": s["chunks"] if s else 0,
                "bytes": s["bytes"] if s else 0,
                "flushed": bool(due), "flush": seg}

    def _take(self, sid: str):
        """原子取走并清空待处理缓冲 —— 修掉重复入库的关键。返回 (chunks, seq)。"""
        with self._lock:
            buf = self._chunks.get(sid)
            if not buf:
                return [], 0
            taken = list(buf)
            buf.clear()
            self._seq[sid] = self._seq.get(sid, 0) + 1
            return taken, self._seq[sid]

    def _flush(self, sid: str, background: bool = False):
        chunks, seq = self._take(sid)
        if not chunks:
            return {"skipped": "empty"}
        if background:
            th = threading.Thread(target=self._flush_sync, args=(sid, chunks, seq),
                                  name=f"trace-flush-{seq}", daemon=True)
            with self._lock:
                self._threads.setdefault(sid, []).append(th)
            th.start()
            return {"segment": seq, "async": True}
        return self._flush_sync(sid, chunks, seq)

    def _flush_sync(self, sid: str, chunks: list[bytes], seq: int) -> dict:
        s = self.store.get_session(sid) or {}
        mode_id = s.get("mode_id", "life")
        name = f"{sid}_seg{seq:04d}.wav"
        # 中间 WAV 走 data/tmp/，不写 l1/ —— l1/ 现在只放长期存证件（.ogg/.m4a），
        # 否则常驻录音会在 l1/ 里留下第三个副本，谁都不删，72h 策略管不到它。
        tmp_dir = os.path.join(self.data_dir, "tmp")
        os.makedirs(tmp_dir, exist_ok=True)
        path = os.path.join(tmp_dir, name)
        builtin.wav_from_pcm_chunks(chunks, path, self.cfg)
        wav_bytes = os.path.getsize(path) if os.path.isfile(path) else 0
        # 以音频本体入库，不把文件路径当转写文本（避免伪造 ASR 结果）
        with open(path, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()
        try:
            r = self.pipeline.run(source="upload", filename=name, audio_b64=b64,
                                  mode_id=mode_id, capture_session="live",
                                  time_source="session")
        finally:
            try:
                os.remove(path)      # 流水线已自行生成存证件，这个中间 WAV 不再需要
            except OSError:
                pass
        return {"segment": seq, "ok": bool(r.get("ok")), "wav_bytes": wav_bytes,
                "events": len(r.get("events") or []),
                "evidence": (r.get("evidence") or {}).get("codec")}

    def _join(self, sid: str, timeout: float = 300.0) -> int:
        with self._lock:
            ts = list(self._threads.get(sid, []))
        for th in ts:
            th.join(timeout)
        return len(ts)

    def stop(self, sid: str) -> dict:
        self._flush(sid)              # 收尾：不足一批的残余块也要落库
        waited = self._join(sid)      # 等后台 flush 全部结束，避免会话已停还在写库
        with self._lock:
            segments = self._seq.get(sid, 0)
            self._chunks.pop(sid, None)
            self._seq.pop(sid, None)
            self._threads.pop(sid, None)
        s = self.store.stop_session(sid)
        return {"session": s, "ingested": True, "segments": segments, "flushes_joined": waited}
