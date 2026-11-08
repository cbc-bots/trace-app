"""履迹内核：数据契约、四层存储、全文检索排序、模式隔离、组件注册表。

零第三方依赖，仅用 Python 标准库。对应功能与窗口规格 v1.0。
"""
from __future__ import annotations

import json
import os
import re
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any

CAPTURE_SESSIONS = ("rehearsal", "live", "fallback")
COMPONENT_TYPES = ("classifier", "agent", "view")
REQUIRED_MANIFEST = ("id", "type", "name", "version", "repo", "minCore")


def now_iso() -> str:
    return datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def load_config(path: str) -> dict:
    """读配置，并允许用环境变量覆盖凭证。

    配置文件里**不要写明文 key**：一旦随源码或交付包扩散，等于把账号交出去。
    留空后由环境变量注入，本地跑法：
        export DASHSCOPE_API_KEY=sk-xxx
        python backend/run.py
    """
    with open(path, "r", encoding="utf-8") as f:
        cfg = json.load(f)
    env_key = os.environ.get("DASHSCOPE_API_KEY", "").strip()
    if env_key:
        cfg.setdefault("dashscope", {})["api_key"] = env_key
    ima_cid = os.environ.get("IMA_CLIENT_ID", "").strip()
    ima_key = os.environ.get("IMA_API_KEY", "").strip()
    if ima_cid and ima_key:
        ima = cfg.setdefault("ima", {})
        ima["client_id"], ima["api_key"] = ima_cid, ima_key
    return cfg


def save_config(path: str, cfg: dict) -> None:
    with open(path, "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)


def make_event(**kw) -> dict:
    """构造符合 v1.0 契约的 EventCard（内核字段 + extensions[mode_id]）。"""
    return {
        "id": kw.get("id") or new_id("evt"),
        "mode_id": kw.get("mode_id", "life"),
        "event_time": kw.get("event_time"),
        "recorded_at": kw.get("recorded_at"),
        "quote_ref": kw.get("quote_ref", {}),
        "speaker": kw.get("speaker", "self"),
        "source": kw.get("source", "user"),
        "confidence": float(kw.get("confidence", 1.0)),
        "confirmed": bool(kw.get("confirmed", True)),
        "capture_session": kw.get("capture_session", "live"),
        "is_mock": bool(kw.get("is_mock", False)),
        "is_demo_sample": bool(kw.get("is_demo_sample", False)),
        "summary": kw.get("summary", ""),
        "quote": kw.get("quote", ""),
        "type": kw.get("type", "事"),
        "extensions": kw.get("extensions", {}),
        "l3a_version": int(kw.get("l3a_version", 1)),
        "corrected": bool(kw.get("corrected", False)),
        "created_at": kw.get("created_at") or now_iso(),
    }


class Store:
    """四层存储：L1 音频元信息 / L2 转写只读 / L3a 推断快照 / L3b 用户校正。"""

    def __init__(self, db_path: str, cfg: dict):
        self.db_path = db_path
        self.cfg = cfg
        self.lock = threading.RLock()
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        self.conn = sqlite3.connect(db_path, check_same_thread=False)
        self.conn.row_factory = sqlite3.Row
        self.has_fts = False
        self._init_schema()
        self._migrate()
        self._init_modes()

    def _init_schema(self) -> None:
        c = self.conn.cursor()
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS events (
                id TEXT PRIMARY KEY,
                mode_id TEXT NOT NULL,
                event_time TEXT,
                recorded_at TEXT,
                quote_ref TEXT NOT NULL DEFAULT '{}',
                speaker TEXT NOT NULL DEFAULT 'self',
                source TEXT NOT NULL DEFAULT 'user',
                confidence REAL NOT NULL DEFAULT 1.0,
                confirmed INTEGER NOT NULL DEFAULT 1,
                capture_session TEXT NOT NULL DEFAULT 'live',
                is_mock INTEGER NOT NULL DEFAULT 0,
                is_demo_sample INTEGER NOT NULL DEFAULT 0,
                summary TEXT NOT NULL DEFAULT '',
                quote TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT '事',
                extensions TEXT NOT NULL DEFAULT '{}',
                l3a_version INTEGER NOT NULL DEFAULT 1,
                corrected INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_events_mode ON events(mode_id);
            CREATE INDEX IF NOT EXISTS idx_events_time ON events(event_time);
            CREATE INDEX IF NOT EXISTS idx_events_session ON events(capture_session);

            CREATE TABLE IF NOT EXISTS l1_files (
                file TEXT PRIMARY KEY,
                recorded_at TEXT,
                capture_session TEXT,
                time_source TEXT,
                is_demo_sample INTEGER DEFAULT 0,
                sha256 TEXT,
                readonly INTEGER DEFAULT 1,
                meta TEXT NOT NULL DEFAULT '{}',
                keep INTEGER NOT NULL DEFAULT 0,
                expire_at TEXT,
                evidence_codec TEXT NOT NULL DEFAULT '',
                bytes_on_disk INTEGER NOT NULL DEFAULT 0,
                purged_at TEXT,
                stored_at TEXT
            );
            CREATE TABLE IF NOT EXISTS l2_segments (
                id TEXT PRIMARY KEY,
                file TEXT NOT NULL,
                start_ms INTEGER NOT NULL,
                end_ms INTEGER NOT NULL,
                quote TEXT NOT NULL,
                speaker TEXT NOT NULL DEFAULT 'self',
                readonly INTEGER NOT NULL DEFAULT 1
            );
            CREATE INDEX IF NOT EXISTS idx_l2_file ON l2_segments(file);

            CREATE TABLE IF NOT EXISTS l3a_snapshots (
                id TEXT PRIMARY KEY,
                event_id TEXT NOT NULL,
                version INTEGER NOT NULL,
                payload TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS l3b_corrections (
                id TEXT PRIMARY KEY,
                event_id TEXT NOT NULL,
                patch TEXT NOT NULL,
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS modes (
                mode_id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                config TEXT NOT NULL DEFAULT '{}'
            );
            CREATE TABLE IF NOT EXISTS components (
                id TEXT PRIMARY KEY,
                type TEXT NOT NULL,
                name TEXT NOT NULL,
                version TEXT NOT NULL,
                repo TEXT NOT NULL,
                manifest TEXT NOT NULL DEFAULT '{}',
                enabled INTEGER NOT NULL DEFAULT 0,
                installed_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY,
                mode_id TEXT NOT NULL,
                capture_session TEXT NOT NULL,
                started_at TEXT NOT NULL,
                stopped_at TEXT,
                status TEXT NOT NULL DEFAULT 'recording',
                chunks INTEGER NOT NULL DEFAULT 0,
                bytes INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS stage_log (
                id TEXT PRIMARY KEY,
                source TEXT NOT NULL,
                mode_id TEXT NOT NULL,
                stage TEXT NOT NULL,
                adapter TEXT NOT NULL,
                detail TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS analyses (
                id TEXT PRIMARY KEY,
                mode_id TEXT NOT NULL,
                source_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                created_at TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_ana_mode ON analyses(mode_id);

            CREATE TABLE IF NOT EXISTS memory_digest (
                mode_id TEXT PRIMARY KEY,
                digest TEXT NOT NULL DEFAULT '',
                count INTEGER NOT NULL DEFAULT 0,
                updated_at TEXT NOT NULL
            );
            """
        )
        try:
            c.execute("CREATE VIRTUAL TABLE IF NOT EXISTS events_fts USING fts5(id UNINDEXED, mode_id UNINDEXED, quote, summary, type)")
            c.execute("CREATE TRIGGER IF NOT EXISTS events_ai AFTER INSERT ON events BEGIN INSERT INTO events_fts(id,mode_id,quote,summary,type) VALUES(new.id,new.mode_id,new.quote,new.summary,new.type); END")
            c.execute("CREATE TRIGGER IF NOT EXISTS events_ad AFTER DELETE ON events BEGIN DELETE FROM events_fts WHERE id=old.id; END")
            c.execute("CREATE TRIGGER IF NOT EXISTS events_au AFTER UPDATE ON events BEGIN DELETE FROM events_fts WHERE id=old.id; INSERT INTO events_fts(id,mode_id,quote,summary,type) VALUES(new.id,new.mode_id,new.quote,new.summary,new.type); END")
            self.has_fts = True
        except sqlite3.OperationalError:
            self.has_fts = False
        self.conn.commit()

    def _migrate(self) -> None:
        """给已存在的旧库补保留策略字段（幂等）。"""
        c = self.conn.cursor()
        try:
            cols = {r["name"] for r in c.execute("PRAGMA table_info(l1_files)").fetchall()}
        except sqlite3.OperationalError:
            return
        for name, ddl in (("keep", "INTEGER NOT NULL DEFAULT 0"),
                          ("expire_at", "TEXT"),
                          ("evidence_codec", "TEXT NOT NULL DEFAULT ''"),
                          ("bytes_on_disk", "INTEGER NOT NULL DEFAULT 0"),
                          ("purged_at", "TEXT"),
                          ("stored_at", "TEXT")):
            if name not in cols:
                try:
                    c.execute(f"ALTER TABLE l1_files ADD COLUMN {name} {ddl}")
                except sqlite3.OperationalError:
                    pass
        self.conn.commit()

    def _init_modes(self) -> None:
        c = self.conn.cursor()
        for mid, m in self.cfg.get("modes", {}).items():
            c.execute("INSERT OR IGNORE INTO modes(mode_id,name,config) VALUES(?,?,?)",
                      (mid, m.get("name", mid), json.dumps(m, ensure_ascii=False)))
        self.conn.commit()

    # ---------- events ----------
    @staticmethod
    def _row_to_event(row: sqlite3.Row) -> dict:
        d = dict(row)
        for k in ("quote_ref", "extensions"):
            d[k] = json.loads(d.get(k) or "{}")
        for k in ("confirmed", "is_mock", "is_demo_sample", "corrected"):
            d[k] = bool(d[k])
        return d

    def put_event(self, ev: dict) -> dict:
        with self.lock:
            c = self.conn.cursor()
            c.execute(
                """INSERT OR REPLACE INTO events
                (id,mode_id,event_time,recorded_at,quote_ref,speaker,source,confidence,
                 confirmed,capture_session,is_mock,is_demo_sample,summary,quote,type,
                 extensions,l3a_version,corrected,created_at)
                VALUES(:id,:mode_id,:event_time,:recorded_at,:quote_ref,:speaker,:source,:confidence,
                 :confirmed,:capture_session,:is_mock,:is_demo_sample,:summary,:quote,:type,
                 :extensions,:l3a_version,:corrected,:created_at)""",
                {
                    **ev,
                    "quote_ref": json.dumps(ev.get("quote_ref", {}), ensure_ascii=False),
                    "extensions": json.dumps(ev.get("extensions", {}), ensure_ascii=False),
                    "confirmed": int(ev.get("confirmed", True)),
                    "is_mock": int(ev.get("is_mock", False)),
                    "is_demo_sample": int(ev.get("is_demo_sample", False)),
                    "corrected": int(ev.get("corrected", False)),
                },
            )
            c.execute("INSERT OR REPLACE INTO l3a_snapshots(id,event_id,version,payload,created_at) VALUES(?,?,?,?,?)",
                      (new_id("snap"), ev["id"], ev.get("l3a_version", 1),
                       json.dumps(ev, ensure_ascii=False), now_iso()))
            self.conn.commit()
        return ev

    def get_event(self, event_id: str) -> dict | None:
        r = self.conn.execute("SELECT * FROM events WHERE id=?", (event_id,)).fetchone()
        return self._row_to_event(r) if r else None

    def list_events(self, mode_id: str | None = None, limit: int = 500,
                    include_fallback: bool = True) -> list[dict]:
        sql = "SELECT * FROM events WHERE 1=1"
        args: list[Any] = []
        if mode_id:
            sql += " AND mode_id=?"
            args.append(mode_id)
        if not include_fallback:
            sql += " AND capture_session!='fallback'"
        sql += " ORDER BY (event_time IS NULL), event_time ASC, created_at ASC LIMIT ?"
        args.append(limit)
        return [self._row_to_event(r) for r in self.conn.execute(sql, args).fetchall()]

    def search(self, query: str, mode_id: str | None = None, limit: int = 20) -> list[dict]:
        """知识库排序整理：FTS5 相关度 → 中文词条 LIKE 合并 → 无命中给最近记录作上下文。

        中文在 FTS5 unicode61 下整句是一个 token，直接 MATCH 整句常常零命中，
        所以额外按 2 字以上中文词 / 英文数字词切分再匹配，保证 Agent 总能拿到证据。
        """
        if not query.strip():
            return self.list_events(mode_id, limit)
        found: dict[str, dict] = {}
        if self.has_fts:
            expr = " OR ".join(f'"{t}"' for t in re.findall(r"[\u4e00-\u9fff]+|[A-Za-z0-9]+", query)) or query
            sql = """SELECT e.*, bm25(events_fts) AS score FROM events_fts f
                     JOIN events e ON e.id=f.id WHERE events_fts MATCH ?"""
            args: list[Any] = [expr]
            if mode_id:
                sql += " AND e.mode_id=?"
                args.append(mode_id)
            sql += " ORDER BY score LIMIT ?"
            args.append(limit)
            try:
                for r in self.conn.execute(sql, args).fetchall():
                    found[r["id"]] = self._row_to_event(r)
            except sqlite3.OperationalError:
                pass
        terms = [t for t in re.findall(r"[\u4e00-\u9fff]{2,}|[A-Za-z0-9]{2,}", query)]
        terms = terms[:6] or [query]
        for t in terms:
            sql = "SELECT * FROM events WHERE (quote LIKE ? OR summary LIKE ? OR type LIKE ?)"
            args = [f"%{t}%", f"%{t}%", f"%{t}%"]
            if mode_id:
                sql += " AND mode_id=?"
                args.append(mode_id)
            sql += " ORDER BY (event_time IS NULL), event_time ASC LIMIT ?"
            args.append(limit)
            for r in self.conn.execute(sql, args).fetchall():
                d = self._row_to_event(r)
                found.setdefault(d["id"], d)
        if found:
            return sorted(found.values(),
                          key=lambda e: (e.get("event_time") is None, e.get("event_time") or ""))[:limit]
        return self.list_events(mode_id, limit)

    def count_events(self, mode_id: str) -> int:
        r = self.conn.execute("SELECT COUNT(*) FROM events WHERE mode_id=?", (mode_id,)).fetchone()
        return int(r[0]) if r else 0

    def get_digest(self, mode_id: str) -> str:
        r = self.conn.execute("SELECT digest, count FROM memory_digest WHERE mode_id=?",
                              (mode_id,)).fetchone()
        # count 不一致说明收录过但没重建（老库 / 中途崩溃），当作失效
        if not r or int(r["count"]) != self.count_events(mode_id):
            return ""
        return r["digest"]

    def build_digest(self, mode_id: str, budget: int = 6000) -> str:
        """把全库压成「日期 + 类型 + 40 字摘要」的一行一条，供 Agent 每轮直接读。

        原来每轮把 300 条原话拼进 prompt（约 12k token），prefill 就要 2–5 秒；
        压缩后约 4k token，且只在收录时算一次。budget 超了就折叠更早的记录，
        但「已读全库」这个语义不变。
        """
        rows = self.conn.execute(
            "SELECT event_time, type, summary, quote FROM events WHERE mode_id=? "
            "ORDER BY (event_time IS NULL), event_time ASC, created_at ASC LIMIT 5000",
            (mode_id,)).fetchall()
        total = self.count_events(mode_id)
        lines: list[str] = []
        used = 0
        for r in rows:
            s = " ".join((r["summary"] or r["quote"] or "").split())[:40]
            if not s:
                continue
            line = f"· {(r['event_time'] or '时间未定')[:10]} {r['type'] or '事'} {s}"
            if used + len(line) > budget:
                lines.append(f"· …（更早的 {max(total - len(lines), 0)} 条已折叠，按需检索）")
                break
            lines.append(line)
            used += len(line) + 1
        text = "\n".join(lines)
        with self.lock:
            self.conn.execute("INSERT OR REPLACE INTO memory_digest VALUES(?,?,?,?)",
                              (mode_id, text, total, now_iso()))
            self.conn.commit()
        return text

    def agent_context(self, mode_id: str, query: str, limit: int = 20) -> tuple[str, int, int]:
        """两段式上下文：全库摘要（覆盖全部）+ 本次问题命中的原话（给细节）。"""
        total = self.count_events(mode_id)
        digest = self.get_digest(mode_id) or self.build_digest(mode_id)
        hits = self.search(query, mode_id, limit) if query.strip() else []
        ctx = f"【全库摘要 · 共 {total} 条，按时间升序】\n{digest}"
        rel = "\n".join(f"[{(e.get('event_time') or '时间未定')[:10]}] {e.get('quote','')}"
                        for e in hits)
        if rel:
            ctx += f"\n\n【与本次问题最相关的原话】\n{rel}"
        return ctx, total, len(hits)

    def timeline(self, mode_id: str, view: str = "timeline") -> dict:
        evs = self.list_events(mode_id)
        groups: dict[str, list[dict]] = {}
        for e in evs:
            if view == "quadrant":
                key = e.get("extensions", {}).get("quadrant") or "未分类"
            elif view == "mastery":
                key = e.get("extensions", {}).get("topic") or "未分类"
            elif view == "relation-timeline":
                key = e.get("extensions", {}).get("person") or "未分类"
            else:
                key = (e.get("event_time") or "时间未定")[:7]
            groups.setdefault(key, []).append(e)
        return {
            "mode_id": mode_id,
            "view": view,
            "total": len(evs),
            "groups": [{"key": k, "count": len(v), "events": v} for k, v in sorted(groups.items())],
        }

    # ---------- 四层 ----------
    def put_l1(self, file: str, recorded_at: str | None, capture_session: str,
               time_source: str, is_demo_sample: bool = False, sha256: str = "",
               meta: dict | None = None, keep: bool = False, expire_hours: float | None = None,
               evidence_codec: str = "", bytes_on_disk: int = 0) -> None:
        """写入 L1。keep=False 且给了 expire_hours 时，音频字节将在到期后被 purge_expired 清理。

        注意：到期清理只删「音频字节」，L2 转写原文 / L3a 快照 / L3b 校正 /
        事件卡 / 分析结果一律不动 —— 记录本身永久保留，失去的只是「回去听原话」的能力。
        """
        stored = now_iso()
        expire_at = None
        if not keep and expire_hours:
            expire_at = (datetime.now(timezone.utc).astimezone()
                         + timedelta(hours=float(expire_hours))).isoformat(timespec="seconds")
        with self.lock:
            self.conn.execute(
                """INSERT OR REPLACE INTO l1_files
                (file,recorded_at,capture_session,time_source,is_demo_sample,sha256,readonly,meta,
                 keep,expire_at,evidence_codec,bytes_on_disk,purged_at,stored_at)
                VALUES(?,?,?,?,?,?,1,?,?,?,?,?,NULL,?)""",
                (file, recorded_at, capture_session, time_source, int(is_demo_sample), sha256,
                 json.dumps(meta or {}, ensure_ascii=False), int(keep), expire_at,
                 evidence_codec, int(bytes_on_disk), stored),
            )
            self.conn.commit()

    @staticmethod
    def _row_to_l1(r: sqlite3.Row) -> dict:
        d = dict(r)
        d["meta"] = json.loads(d.get("meta") or "{}")
        d["keep"] = bool(d.get("keep"))
        d["purged"] = bool(d.get("purged_at"))
        return d

    def get_l1(self, file: str) -> dict | None:
        r = self.conn.execute("SELECT * FROM l1_files WHERE file=?", (file,)).fetchone()
        return self._row_to_l1(r) if r else None

    def list_l1(self, only_pending: bool = False, limit: int = 500) -> list[dict]:
        sql = "SELECT * FROM l1_files"
        if only_pending:
            sql += " WHERE purged_at IS NULL"
        sql += " ORDER BY stored_at DESC LIMIT ?"
        return [self._row_to_l1(r) for r in self.conn.execute(sql, (limit,)).fetchall()]

    def set_l1_keep(self, file: str, keep: bool, hours: float | None = None) -> dict | None:
        """用户选择保留 / 取消保留。取消保留时重新给一个完整的保留窗口。"""
        h = hours if hours is not None else float(self.cfg.get("retention", {}).get("hours", 72))
        expire_at = None
        if not keep and h:
            expire_at = (datetime.now(timezone.utc).astimezone()
                         + timedelta(hours=h)).isoformat(timespec="seconds")
        with self.lock:
            self.conn.execute("UPDATE l1_files SET keep=?, expire_at=? WHERE file=?",
                              (int(keep), expire_at, file))
            self.conn.commit()
        return self.get_l1(file)

    def _data_root(self) -> str:
        return os.path.dirname(os.path.abspath(self.db_path))

    def purge_expired(self, now: str | None = None) -> dict:
        """执行保留策略：删除到期且未被 keep 的音频字节，保留审计行（purged_at）。"""
        now = now or now_iso()
        root = os.path.realpath(self._data_root())
        purged, freed, errors = [], 0, []
        with self.lock:
            rows = self.conn.execute(
                """SELECT file FROM l1_files
                   WHERE keep=0 AND purged_at IS NULL AND expire_at IS NOT NULL AND expire_at<=?""",
                (now,)).fetchall()
            for r in rows:
                rel = r["file"]
                p = os.path.realpath(os.path.join(root, rel.replace("/", os.sep)))
                if p.startswith(root + os.sep) and os.path.isfile(p):
                    try:
                        freed += os.path.getsize(p)
                        os.remove(p)
                    except OSError as e:
                        errors.append(f"{rel}: {type(e).__name__}")
                        continue
                self.conn.execute(
                    "UPDATE l1_files SET purged_at=?, bytes_on_disk=0 WHERE file=?", (now, rel))
                purged.append(rel)
            self.conn.commit()
        return {"purged": purged, "count": len(purged), "freed_bytes": freed,
                "freed_mb": round(freed / 1048576, 2), "errors": errors}

    def retention_stats(self) -> dict:
        h = self.cfg.get("retention", {}).get("hours", 72)
        q = lambda s, a=(): self.conn.execute(s, a).fetchone()
        live = q("SELECT COUNT(*) n, COALESCE(SUM(bytes_on_disk),0) b FROM l1_files WHERE purged_at IS NULL")
        kept = q("SELECT COUNT(*) n FROM l1_files WHERE keep=1 AND purged_at IS NULL")
        pend = q("""SELECT COUNT(*) n FROM l1_files WHERE keep=0 AND purged_at IS NULL
                    AND expire_at IS NOT NULL AND expire_at<=?""", (now_iso(),))
        done = q("SELECT COUNT(*) n, COALESCE(SUM(bytes_on_disk),0) b FROM l1_files WHERE purged_at IS NOT NULL")
        return {
            "policy": (f"音频存证保留 {h} 小时后自动清理；用户可标记 keep 永久保留；"
                       "L2 转写原文 / L3a / L3b / 事件卡 / 分析结果永不删除"),
            "retention_hours": h,
            "evidence_codec": self.cfg.get("retention", {}).get("evidence_codec", "opus"),
            "audio_files": live["n"], "audio_bytes": live["b"],
            "audio_mb": round((live["b"] or 0) / 1048576, 2),
            "kept_files": kept["n"], "expired_pending": pend["n"],
            "purged_files": done["n"],
        }

    def put_l2(self, file: str, segments: list[dict]) -> int:
        with self.lock:
            c = self.conn.cursor()
            c.execute("DELETE FROM l2_segments WHERE file=?", (file,))
            for s in segments:
                c.execute(
                    "INSERT INTO l2_segments(id,file,start_ms,end_ms,quote,speaker,readonly) VALUES(?,?,?,?,?,?,1)",
                    (new_id("seg"), file, int(s.get("start_ms", 0)), int(s.get("end_ms", 0)),
                     s.get("quote", ""), s.get("speaker", "self")),
                )
            self.conn.commit()
        return len(segments)

    def get_l2(self, file: str) -> list[dict]:
        return [dict(r) for r in self.conn.execute(
            "SELECT * FROM l2_segments WHERE file=? ORDER BY start_ms", (file,)).fetchall()]

    def correct(self, event_id: str, patch: dict) -> dict | None:
        """L3b：保留 L3a 快照只读，生成新版本；校正层只写本地。"""
        ev = self.get_event(event_id)
        if not ev:
            return None
        snap = {k: ev[k] for k in ("summary", "quote", "type", "extensions", "confidence")}
        with self.lock:
            c = self.conn.cursor()
            c.execute("INSERT INTO l3b_corrections(id,event_id,patch,created_at) VALUES(?,?,?,?)",
                      (new_id("corr"), event_id, json.dumps(patch, ensure_ascii=False), now_iso()))
            ext = dict(ev.get("extensions", {}))
            if "extensions" in patch:
                ext.update(patch["extensions"])
            c.execute("""UPDATE events SET summary=?, type=?, extensions=?, corrected=1,
                         l3a_version=l3a_version+1 WHERE id=?""",
                      (patch.get("summary", ev["summary"]), patch.get("type", ev["type"]),
                       json.dumps(ext, ensure_ascii=False), event_id))
            self.conn.commit()
        out = self.get_event(event_id)
        if out:
            out["l3a_snapshot"] = snap
        return out

    # ---------- modes / components ----------
    def list_modes(self) -> list[dict]:
        return [{"mode_id": r["mode_id"], "name": r["name"], "config": json.loads(r["config"])}
                for r in self.conn.execute("SELECT * FROM modes ORDER BY mode_id").fetchall()]

    def set_mode(self, mode_id: str, name: str, config: dict) -> dict:
        with self.lock:
            self.conn.execute("INSERT OR REPLACE INTO modes(mode_id,name,config) VALUES(?,?,?)",
                              (mode_id, name or mode_id, json.dumps(config, ensure_ascii=False)))
            self.conn.commit()
        return {"mode_id": mode_id, "name": name, "config": config}

    def install_component(self, manifest: dict, enabled: bool = False) -> dict:
        missing = [k for k in REQUIRED_MANIFEST if not manifest.get(k)]
        if missing:
            raise ValueError(f"manifest 缺少必填字段: {', '.join(missing)}")
        if manifest["type"] not in COMPONENT_TYPES:
            raise ValueError(f"type 必须是 {COMPONENT_TYPES}")
        if not re.match(r"^\d+\.\d+", str(manifest["version"])):
            raise ValueError("version 必须是语义化版本，如 1.0.0")
        with self.lock:
            self.conn.execute(
                """INSERT OR REPLACE INTO components(id,type,name,version,repo,manifest,enabled,installed_at)
                   VALUES(?,?,?,?,?,?,?,?)""",
                (manifest["id"], manifest["type"], manifest["name"], str(manifest["version"]),
                 manifest["repo"], json.dumps(manifest, ensure_ascii=False), int(enabled), now_iso()),
            )
            self.conn.commit()
        return {"id": manifest["id"], "enabled": enabled, "manifest": manifest}

    def get_component(self, cid: str) -> dict | None:
        r = self.conn.execute("SELECT * FROM components WHERE id=?", (cid,)).fetchone()
        if not r:
            return None
        d = dict(r)
        d["enabled"] = bool(d["enabled"])
        d["manifest"] = json.loads(d["manifest"])
        return d

    def list_components(self, ctype: str | None = None) -> list[dict]:
        sql = "SELECT * FROM components"
        args: list[Any] = []
        if ctype:
            sql += " WHERE type=?"
            args.append(ctype)
        sql += " ORDER BY installed_at"
        out = []
        for r in self.conn.execute(sql, args).fetchall():
            d = dict(r)
            d["enabled"] = bool(d["enabled"])
            d["manifest"] = json.loads(d["manifest"])
            out.append(d)
        return out

    def toggle_component(self, cid: str, enabled: bool) -> dict | None:
        with self.lock:
            self.conn.execute("UPDATE components SET enabled=? WHERE id=?", (int(enabled), cid))
            self.conn.commit()
        r = self.conn.execute("SELECT * FROM components WHERE id=?", (cid,)).fetchone()
        return dict(r) if r else None

    def uninstall_component(self, cid: str) -> bool:
        with self.lock:
            cur = self.conn.execute("DELETE FROM components WHERE id=?", (cid,))
            self.conn.commit()
        return cur.rowcount > 0

    def resolve_component(self, slot: str, mode_id: str) -> dict | None:
        """注册表驱动：优先取该模式启用且声明了 slots 的组件，否则取该类第一个启用的。"""
        mode = next((m for m in self.list_modes() if m["mode_id"] == mode_id), None)
        wanted = (mode or {}).get("config", {}).get(slot)
        comps = self.list_components()
        for c in comps:
            if c["enabled"] and c["id"] == wanted:
                return c
        ctype = {"classifier": "classifier", "agent": "agent", "view": "view"}.get(slot)
        for c in comps:
            if c["enabled"] and c["type"] == ctype:
                return c
        return None

    # ---------- sessions / stage log ----------
    def start_session(self, mode_id: str, capture_session: str = "live") -> dict:
        s = {"id": new_id("rec"), "mode_id": mode_id, "capture_session": capture_session,
             "started_at": now_iso(), "stopped_at": None, "status": "recording", "chunks": 0, "bytes": 0}
        with self.lock:
            self.conn.execute(
                """INSERT INTO sessions(id,mode_id,capture_session,started_at,stopped_at,status,chunks,bytes)
                   VALUES(?,?,?,?,?,?,?,?)""",
                (s["id"], mode_id, capture_session, s["started_at"], None, "recording", 0, 0),
            )
            self.conn.commit()
        return s

    def push_chunk(self, sid: str, nbytes: int) -> dict | None:
        with self.lock:
            self.conn.execute("UPDATE sessions SET chunks=chunks+1, bytes=bytes+? WHERE id=?", (nbytes, sid))
            self.conn.commit()
        r = self.conn.execute("SELECT * FROM sessions WHERE id=?", (sid,)).fetchone()
        return dict(r) if r else None

    def stop_session(self, sid: str) -> dict | None:
        with self.lock:
            self.conn.execute("UPDATE sessions SET stopped_at=?, status='stopped' WHERE id=?", (now_iso(), sid))
            self.conn.commit()
        r = self.conn.execute("SELECT * FROM sessions WHERE id=?", (sid,)).fetchone()
        return dict(r) if r else None

    def get_session(self, sid: str) -> dict | None:
        r = self.conn.execute("SELECT * FROM sessions WHERE id=?", (sid,)).fetchone()
        return dict(r) if r else None

    def log_stage(self, source: str, mode_id: str, stage: str, adapter: str, detail: dict) -> None:
        with self.lock:
            self.conn.execute(
                "INSERT INTO stage_log(id,source,mode_id,stage,adapter,detail,created_at) VALUES(?,?,?,?,?,?,?)",
                (new_id("stg"), source, mode_id, stage, adapter,
                 json.dumps(detail, ensure_ascii=False), now_iso()),
            )
            self.conn.commit()

    def put_analysis(self, a: dict) -> dict:
        with self.lock:
            self.conn.execute(
                "INSERT OR REPLACE INTO analyses(id,mode_id,source_id,payload,created_at) VALUES(?,?,?,?,?)",
                (a["id"], a["mode_id"], a["source_id"],
                 json.dumps(a, ensure_ascii=False), a.get("created_at") or now_iso()))
            self.conn.commit()
        return a

    def list_analyses(self, mode_id: str | None = None, limit: int = 50) -> list[dict]:
        sql = "SELECT * FROM analyses WHERE 1=1"
        args: list[Any] = []
        if mode_id:
            sql += " AND mode_id=?"
            args.append(mode_id)
        sql += " ORDER BY created_at DESC LIMIT ?"
        args.append(limit)
        return [json.loads(r["payload"]) for r in self.conn.execute(sql, args).fetchall()]

    def get_analysis(self, aid: str) -> dict | None:
        r = self.conn.execute("SELECT payload FROM analyses WHERE id=?", (aid,)).fetchone()
        return json.loads(r["payload"]) if r else None

    def get_stages(self, source: str) -> list[dict]:
        out = []
        for r in self.conn.execute("SELECT * FROM stage_log WHERE source=? ORDER BY created_at", (source,)):
            d = dict(r)
            d["detail"] = json.loads(d["detail"])
            out.append(d)
        return out

    def stats(self, mode_id: str) -> dict:
        """统计快照：只基于 rehearsal 且 confirmed=true 的卡，现场新增不回写。"""
        rows = self.conn.execute(
            """SELECT capture_session, COUNT(*) n FROM events
               WHERE mode_id=? AND confirmed=1 GROUP BY capture_session""", (mode_id,)).fetchall()
        per = {r["capture_session"]: r["n"] for r in rows}
        return {"mode_id": mode_id, "locked_scope": "rehearsal AND confirmed=true",
                "rehearsal_count": per.get("rehearsal", 0),
                "live_count": per.get("live", 0), "fallback_count": per.get("fallback", 0),
                "per_session": per}

    def close(self) -> None:
        self.conn.close()
