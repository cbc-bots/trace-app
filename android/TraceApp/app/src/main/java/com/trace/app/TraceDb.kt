package com.trace.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 本地知识库：四层存储 + 模式隔离 + 组件注册表 + 会话 / 流水线日志。
 *  对应桌面版 core.py，全部用 Android 内置 SQLite，无第三方依赖。 */
class TraceDb(private val appCtx: Context) : SQLiteOpenHelper(appCtx, "trace.db", null, 6) {

    companion object {
        const val RETENTION_HOURS = 72
        private val FMT = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", java.util.Locale.CHINA)
        private fun id(p: String) = p + "_" + UUID.randomUUID().toString().substring(0, 12)
        fun now() = FMT.format(java.util.Date())
        fun expireAfter(hours: Int = RETENTION_HOURS): String {
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.HOUR_OF_DAY, hours)
            return FMT.format(cal.time)
        }
    }

    /** WAL：读写不互相阻塞。常驻录音边写、对话边读时不再排队等锁。 */
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        try { setWriteAheadLoggingEnabled(true) } catch (_: Throwable) { }
    }

    override fun onCreate(d: SQLiteDatabase) {
        // time_source：event_time 的来源。spoken=口述里说了日期，recorded=没口述、用录制当日兜底，
        // unknown=既没口述也没有录制时间（仅历史脏数据）。界面据此标「估」，不把推断当事实。
        d.execSQL("""CREATE TABLE IF NOT EXISTS events(
            id TEXT PRIMARY KEY, mode_id TEXT, event_time TEXT, recorded_at TEXT,
            quote_ref TEXT, speaker TEXT, source TEXT, confidence REAL,
            confirmed INTEGER, capture_session TEXT, is_mock INTEGER,
            is_demo_sample INTEGER, summary TEXT, quote TEXT, type TEXT,
            extensions TEXT, l3a_version INTEGER, corrected INTEGER, created_at TEXT,
            time_source TEXT, cls TEXT, sentiment REAL)""")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_ev_mode ON events(mode_id)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_ev_time ON events(event_time)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_ev_mode_time ON events(mode_id, event_time)")
        // 记忆摘要缓存：对话只读这一行，不再每次扫全表拼 prompt
        d.execSQL("""CREATE TABLE IF NOT EXISTS memory_digest(
            mode_id TEXT PRIMARY KEY, digest TEXT, count INTEGER, updated_at TEXT)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS l1_files(
            file TEXT PRIMARY KEY, recorded_at TEXT, capture_session TEXT,
            time_source TEXT, is_demo_sample INTEGER, meta TEXT,
            keep INTEGER DEFAULT 0, expire_at TEXT, bytes_on_disk INTEGER DEFAULT 0,
            purged_at TEXT, stored_at TEXT)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS l2_segments(
            id TEXT PRIMARY KEY, file TEXT, start_ms INTEGER, end_ms INTEGER,
            quote TEXT, speaker TEXT)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS l3a_snapshots(
            id TEXT PRIMARY KEY, event_id TEXT, version INTEGER, payload TEXT, created_at TEXT)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS l3b_corrections(
            id TEXT PRIMARY KEY, event_id TEXT, patch TEXT, created_at TEXT)""")
        d.execSQL("CREATE TABLE IF NOT EXISTS modes(mode_id TEXT PRIMARY KEY, name TEXT, config TEXT)")
        d.execSQL("""CREATE TABLE IF NOT EXISTS components(
            id TEXT PRIMARY KEY, type TEXT, name TEXT, version TEXT, repo TEXT,
            manifest TEXT, enabled INTEGER, installed_at TEXT)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS sessions(
            id TEXT PRIMARY KEY, mode_id TEXT, capture_session TEXT, started_at TEXT,
            stopped_at TEXT, status TEXT, chunks INTEGER, bytes INTEGER)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS stage_log(
            id TEXT PRIMARY KEY, source TEXT, mode_id TEXT, stage TEXT,
            adapter TEXT, detail TEXT, created_at TEXT)""")
        d.execSQL("""CREATE TABLE IF NOT EXISTS analyses(
            id TEXT PRIMARY KEY, mode_id TEXT, source_id TEXT,
            payload TEXT, created_at TEXT,
            granularity TEXT, period_key TEXT)""")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_ana_mode ON analyses(mode_id)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_ana_period ON analyses(mode_id, granularity, period_key)")
        // 分类标签：一行一个标签，支持按 domain/type/情绪/话题任意维度聚合与筛选。
        // events.cls 里另存一份「主分类快照」，列表页直接读，不用 join。
        d.execSQL("""CREATE TABLE IF NOT EXISTS event_tags(
            event_id TEXT, kind TEXT, value TEXT, name TEXT,
            confidence REAL, source TEXT, created_at TEXT)""")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_tag_event ON event_tags(event_id)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_tag_kind ON event_tags(kind, value)")
        // ima 同步幂等表：一条事件只推一次，内容变了（hash 变）才重推。
        // ima 没有改/删接口，所以同步语义只能是「只增不改」——和四层存储同构。
        imaTables(d)
        seedModes(d)
    }

    private fun imaTables(d: SQLiteDatabase) {
        d.execSQL("""CREATE TABLE IF NOT EXISTS ima_sync(
            event_id TEXT PRIMARY KEY, day TEXT, doc_id TEXT, hash TEXT, synced_at TEXT)""")
        d.execSQL("CREATE TABLE IF NOT EXISTS ima_day(day TEXT PRIMARY KEY, doc_id TEXT, kb_id TEXT, created_at TEXT)")
        d.execSQL("CREATE INDEX IF NOT EXISTS idx_ima_sync_day ON ima_sync(day)")
    }

    override fun onUpgrade(d: SQLiteDatabase, o: Int, n: Int) {
        // v1.1 / F：幂等加列。旧库直接升级，不丢数据。
        if (o < 2) {
            val cols = mutableSetOf<String>()
            val c = d.rawQuery("PRAGMA table_info(l1_files)", null)
            c.use { while (it.moveToNext()) cols.add(it.getString(it.getColumnIndexOrThrow("name"))) }
            val add = listOf(
                "keep" to "INTEGER DEFAULT 0",
                "expire_at" to "TEXT",
                "bytes_on_disk" to "INTEGER DEFAULT 0",
                "purged_at" to "TEXT",
                "stored_at" to "TEXT")
            for ((k, typ) in add) if (k !in cols) d.execSQL("ALTER TABLE l1_files ADD COLUMN $k $typ")
            // 老行补一个到期时间，避免永远不被清理
            d.execSQL("UPDATE l1_files SET expire_at=? WHERE expire_at IS NULL",
                arrayOf(expireAfter()))
        }
        if (o < 3) {
            d.execSQL("""CREATE TABLE IF NOT EXISTS memory_digest(
                mode_id TEXT PRIMARY KEY, digest TEXT, count INTEGER, updated_at TEXT)""")
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_ev_mode_time ON events(mode_id, event_time)")
        }
        if (o < 4) imaTables(d)
        if (o < 5) {
            // v5：每条记录都带「录制当日」时间。老行先补列，再用 recorded_at 的日期回填
            // 那些 event_time 为空的卡（只补到日，不编造具体时刻），并标成 recorded。
            val cols = mutableSetOf<String>()
            d.rawQuery("PRAGMA table_info(events)", null).use { c ->
                while (c.moveToNext()) cols.add(c.getString(c.getColumnIndexOrThrow("name")))
            }
            if ("time_source" !in cols) d.execSQL("ALTER TABLE events ADD COLUMN time_source TEXT")
            d.execSQL("""UPDATE events SET event_time = substr(recorded_at,1,10) || 'T00:00:00+08:00',
                         time_source='recorded'
                         WHERE (event_time IS NULL OR event_time='')
                           AND recorded_at IS NOT NULL AND recorded_at<>''""")
            d.execSQL("UPDATE events SET time_source='spoken' WHERE time_source IS NULL AND event_time IS NOT NULL AND event_time<>''")
            d.execSQL("UPDATE events SET time_source='unknown' WHERE time_source IS NULL")
        }
        if (o < 6) {
            // v6：把分类结果变成一等公民。
            // events.cls 存主分类快照（列表页零 join），event_tags 存展开标签（筛选/统计）。
            // 老数据不在这里硬填分类——留给「重算分类」按需跑，避免升级时卡住启动。
            val cols = mutableSetOf<String>()
            d.rawQuery("PRAGMA table_info(events)", null).use { c ->
                while (c.moveToNext()) cols.add(c.getString(c.getColumnIndexOrThrow("name")))
            }
            if ("cls" !in cols) d.execSQL("ALTER TABLE events ADD COLUMN cls TEXT")
            // sentiment 单独成列，不让查询依赖 json_extract：JSON1 扩展在低版本 SQLite 上不一定编译进来
            if ("sentiment" !in cols) d.execSQL("ALTER TABLE events ADD COLUMN sentiment REAL")
            d.execSQL("""CREATE TABLE IF NOT EXISTS event_tags(
                event_id TEXT, kind TEXT, value TEXT, name TEXT,
                confidence REAL, source TEXT, created_at TEXT)""")
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_tag_event ON event_tags(event_id)")
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_tag_kind ON event_tags(kind, value)")
            val acols = mutableSetOf<String>()
            d.rawQuery("PRAGMA table_info(analyses)", null).use { c ->
                while (c.moveToNext()) acols.add(c.getString(c.getColumnIndexOrThrow("name")))
            }
            if ("granularity" !in acols) d.execSQL("ALTER TABLE analyses ADD COLUMN granularity TEXT")
            if ("period_key" !in acols) d.execSQL("ALTER TABLE analyses ADD COLUMN period_key TEXT")
            d.execSQL("CREATE INDEX IF NOT EXISTS idx_ana_period ON analyses(mode_id, granularity, period_key)")
        }
    }

    private fun seedModes(d: SQLiteDatabase) {
        val modes = listOf(
            "life" to "人生记录", "study" to "学习",
            "relation" to "关系", "growth" to "成长")
        for ((mid, name) in modes) {
            val c = JSONObject().put("classifier", "builtin").put("agent", "base")
                .put("view", if (mid == "growth") "quadrant" else "timeline")
            d.execSQL("INSERT OR IGNORE INTO modes VALUES(?,?,?)", arrayOf(mid, name, c.toString()))
        }
    }

    private fun rowToEvent(c: android.database.Cursor): JSONObject {
        fun s(k: String) = c.getString(c.getColumnIndexOrThrow(k)) ?: ""
        return JSONObject()
            .put("id", s("id")).put("mode_id", s("mode_id"))
            .put("event_time", if (c.isNull(c.getColumnIndexOrThrow("event_time"))) JSONObject.NULL else s("event_time"))
            .put("recorded_at", if (c.isNull(c.getColumnIndexOrThrow("recorded_at"))) JSONObject.NULL else s("recorded_at"))
            .put("quote_ref", JSONObject(s("quote_ref").ifEmpty { "{}" }))
            .put("speaker", s("speaker")).put("source", s("source"))
            .put("confidence", c.getDouble(c.getColumnIndexOrThrow("confidence")))
            .put("confirmed", c.getInt(c.getColumnIndexOrThrow("confirmed")) == 1)
            .put("capture_session", s("capture_session"))
            .put("is_mock", c.getInt(c.getColumnIndexOrThrow("is_mock")) == 1)
            .put("is_demo_sample", c.getInt(c.getColumnIndexOrThrow("is_demo_sample")) == 1)
            .put("summary", s("summary")).put("quote", s("quote")).put("type", s("type"))
            .put("extensions", JSONObject(s("extensions").ifEmpty { "{}" }))
            .put("l3a_version", c.getInt(c.getColumnIndexOrThrow("l3a_version")))
            .put("corrected", c.getInt(c.getColumnIndexOrThrow("corrected")) == 1)
            .put("created_at", s("created_at"))
            .put("time_source", s("time_source").ifEmpty { if (s("event_time").isBlank()) "unknown" else "spoken" })
            .put("cls", s("cls").ifEmpty { "" }.let {
                if (it.isBlank()) JSONObject.NULL else try { JSONObject(it) } catch (e: Exception) { JSONObject.NULL }
            })
    }

    fun putEvent(e: JSONObject): JSONObject {
        val d = writableDatabase
        // 用 ContentValues 而不是 22 个问号：列名与取值成对出现，加字段不会悄悄错位
        val cv = android.content.ContentValues()
        cv.put("id", e.optString("id", id("evt")))
        cv.put("mode_id", e.optString("mode_id", "life"))
        if (e.isNull("event_time")) cv.putNull("event_time") else cv.put("event_time", e.optString("event_time"))
        if (e.isNull("recorded_at")) cv.putNull("recorded_at") else cv.put("recorded_at", e.optString("recorded_at"))
        cv.put("quote_ref", e.optJSONObject("quote_ref")?.toString() ?: "{}")
        cv.put("speaker", e.optString("speaker", "self"))
        cv.put("source", e.optString("source", "user"))
        cv.put("confidence", e.optDouble("confidence", 1.0))
        cv.put("confirmed", if (e.optBoolean("confirmed", true)) 1 else 0)
        cv.put("capture_session", e.optString("capture_session", "live"))
        cv.put("is_mock", if (e.optBoolean("is_mock")) 1 else 0)
        cv.put("is_demo_sample", if (e.optBoolean("is_demo_sample")) 1 else 0)
        cv.put("summary", e.optString("summary"))
        cv.put("quote", e.optString("quote"))
        cv.put("type", e.optString("type", "fact"))
        cv.put("extensions", e.optJSONObject("extensions")?.toString() ?: "{}")
        cv.put("l3a_version", e.optInt("l3a_version", 1))
        cv.put("corrected", if (e.optBoolean("corrected")) 1 else 0)
        cv.put("created_at", e.optString("created_at", now()))
        cv.put("time_source", e.optString("time_source",
            if (e.isNull("event_time")) "unknown" else "spoken"))
        val cls = e.optJSONObject("cls")
        if (cls == null) cv.putNull("cls") else cv.put("cls", cls.toString())
        val senti = cls?.optDouble("sentiment", Double.NaN) ?: Double.NaN
        if (senti.isNaN()) cv.putNull("sentiment") else cv.put("sentiment", senti)
        d.insertWithOnConflict("events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        d.execSQL("INSERT OR REPLACE INTO l3a_snapshots VALUES(?,?,?,?,?)",
            arrayOf(id("snap"), e.optString("id"), e.optInt("l3a_version", 1), e.toString(), now()))
        return e
    }

    fun getEvent(eid: String): JSONObject? {
        val c = readableDatabase.rawQuery("SELECT * FROM events WHERE id=?", arrayOf(eid))
        c.use { return if (it.moveToFirst()) rowToEvent(it) else null }
    }

    fun listEvents(mode: String?, limit: Int = 500): JSONArray {
        val sql = StringBuilder("SELECT * FROM events WHERE 1=1")
        val args = mutableListOf<String>()
        if (mode != null) { sql.append(" AND mode_id=?"); args.add(mode) }
        sql.append(" ORDER BY (event_time IS NULL), event_time ASC, created_at ASC LIMIT ?")
        args.add(limit.toString())
        val out = JSONArray()
        val c = readableDatabase.rawQuery(sql.toString(), args.toTypedArray())
        c.use { while (it.moveToNext()) out.put(rowToEvent(it)) }
        return out
    }

    /** 只取摘要需要的列：不搬 extensions / quote_ref 这类大字段，几千行也很快。 */
    fun listEventsBrief(mode: String?, limit: Int = 5000): JSONArray {
        val sql = StringBuilder(
            "SELECT id,mode_id,event_time,summary,quote,type,speaker,time_source,recorded_at FROM events WHERE 1=1")
        val args = mutableListOf<String>()
        if (mode != null) { sql.append(" AND mode_id=?"); args.add(mode) }
        sql.append(" ORDER BY (event_time IS NULL), event_time ASC, created_at ASC LIMIT ?")
        args.add(limit.toString())
        val out = JSONArray()
        val c = readableDatabase.rawQuery(sql.toString(), args.toTypedArray())
        c.use {
            while (it.moveToNext()) out.put(JSONObject()
                .put("id", it.getString(0) ?: "").put("mode_id", it.getString(1) ?: "")
                .put("event_time", it.getString(2) ?: "").put("summary", it.getString(3) ?: "")
                .put("quote", it.getString(4) ?: "").put("type", it.getString(5) ?: "事")
                .put("speaker", it.getString(6) ?: "self")
                .put("time_source", it.getString(7) ?: "")
                .put("recorded_at", it.getString(8) ?: ""))
        }
        return out
    }

    /** 星图专用的瘦身版：只带「画点 + 画卡片 + 详情页」真正要用的列。
     *
     *  和 rowToEvent 的差别：
     *  - 不带 is_mock / l3a_version / created_at 这些前端不读的字段；
     *  - **quote_ref 里挖掉 quote**——那一份和 quote 列完全重复，
     *    1500 条时等于白搬一半字节过去让 WebView 解析。
     *  file / start_ms / end_ms 留着，详情页的原声播放要用。 */
    fun listEventsSlim(mode: String?, limit: Int = 1500): JSONArray {
        val sql = StringBuilder(
            "SELECT id,mode_id,event_time,recorded_at,summary,quote,type,speaker,confidence," +
                "cls,time_source,extensions,quote_ref,corrected FROM events WHERE 1=1")
        val args = mutableListOf<String>()
        if (mode != null) { sql.append(" AND mode_id=?"); args.add(mode) }
        sql.append(" ORDER BY (event_time IS NULL), event_time ASC, created_at ASC LIMIT ?")
        args.add(limit.toString())
        val out = JSONArray()
        val c = readableDatabase.rawQuery(sql.toString(), args.toTypedArray())
        c.use { cur ->
            while (cur.moveToNext()) {
                val obj = JSONObject()
                    .put("id", cur.getString(0) ?: "")
                    .put("mode_id", cur.getString(1) ?: "")
                    .put("event_time", cur.getString(2) ?: "")
                    .put("recorded_at", cur.getString(3) ?: "")
                    .put("summary", cur.getString(4) ?: "")
                    .put("quote", cur.getString(5) ?: "")
                    .put("type", cur.getString(6) ?: "")
                    .put("speaker", cur.getString(7) ?: "self")
                    .put("confidence", cur.getDouble(8))
                    .put("time_source", cur.getString(10) ?: "")
                    .put("corrected", cur.getInt(13) == 1)
                val cls = cur.getString(9) ?: ""
                obj.put("cls", if (cls.isBlank()) JSONObject.NULL
                    else try { JSONObject(cls) } catch (e: Exception) { JSONObject.NULL })
                val ext = cur.getString(11) ?: ""
                obj.put("extensions", if (ext.isBlank()) JSONObject() else
                    try { JSONObject(ext) } catch (e: Exception) { JSONObject() })
                val ref = cur.getString(12) ?: ""
                val rf = if (ref.isBlank()) JSONObject() else
                    try { JSONObject(ref).apply { remove("quote") } } catch (e: Exception) { JSONObject() }
                obj.put("quote_ref", rf)
                out.put(obj)
            }
        }
        return out
    }

    fun countEvents(mode: String): Int {
        val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE mode_id=?", arrayOf(mode))
        c.use { return if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** 最近 N 条（新的在前）。分析用的「历史」必须取最近，取最早等于永远看不到变化。 */
    fun recentEvents(mode: String?, limit: Int = 60): JSONArray {
        val sql = StringBuilder("SELECT * FROM events WHERE 1=1")
        val args = mutableListOf<String>()
        if (mode != null) { sql.append(" AND mode_id=?"); args.add(mode) }
        sql.append(" ORDER BY COALESCE(event_time, recorded_at) DESC, created_at DESC LIMIT ?")
        args.add(limit.toString())
        val out = JSONArray()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.put(rowToEvent(c))
        }
        return out
    }

    /** 某一天的全部事件（时间正序），日汇总分析用。day = yyyy-MM-dd。 */
    fun eventsOfDay(mode: String, day: String): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            """SELECT * FROM events WHERE mode_id=? AND substr(event_time,1,10)=?
               ORDER BY event_time ASC, created_at ASC""",
            arrayOf(mode, day)).use { c ->
            while (c.moveToNext()) out.put(rowToEvent(c))
        }
        return out
    }

    fun getDigest(mode: String): JSONObject? {
        val c = readableDatabase.rawQuery(
            "SELECT digest,count,updated_at FROM memory_digest WHERE mode_id=?", arrayOf(mode))
        c.use {
            return if (it.moveToFirst()) JSONObject()
                .put("digest", it.getString(0) ?: "")
                .put("count", it.getInt(1))
                .put("updated_at", it.getString(2) ?: "")
            else null
        }
    }

    fun putDigest(mode: String, digest: String, count: Int) {
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO memory_digest VALUES(?,?,?,?)",
            arrayOf(mode, digest, count, now()))
    }

    // ---------- ima 同步状态（只记录「这条已经推过去了」，不改事件本身） ----------
    fun imaSyncedHashes(): HashMap<String, String> {
        val m = HashMap<String, String>()
        val c = readableDatabase.rawQuery("SELECT event_id, hash FROM ima_sync", null)
        c.use { while (it.moveToNext()) m[it.getString(0) ?: ""] = it.getString(1) ?: "" }
        return m
    }

    fun imaDayDoc(day: String): String =
        readableDatabase.rawQuery("SELECT doc_id FROM ima_day WHERE day=?", arrayOf(day)).use {
            if (it.moveToFirst()) it.getString(0) ?: "" else ""
        }

    fun imaPutDay(day: String, docId: String, kbId: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO ima_day VALUES(?,?,?,?)",
            arrayOf(day, docId, kbId, now()))
    }

    fun imaMarkSynced(eventId: String, day: String, docId: String, hash: String) {
        if (eventId.isBlank()) return
        writableDatabase.execSQL("INSERT OR REPLACE INTO ima_sync VALUES(?,?,?,?,?)",
            arrayOf(eventId, day, docId, hash, now()))
    }

    fun imaClear(): Int {
        val n = readableDatabase.rawQuery("SELECT COUNT(*) FROM ima_sync", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
        writableDatabase.execSQL("DELETE FROM ima_sync")
        writableDatabase.execSQL("DELETE FROM ima_day")
        return n
    }

    fun imaStats(): JSONObject = JSONObject()
        .put("synced", readableDatabase.rawQuery("SELECT COUNT(*) FROM ima_sync", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0 })
        .put("days", readableDatabase.rawQuery("SELECT COUNT(*) FROM ima_day", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0 })
        .put("last_at", readableDatabase.rawQuery(
            "SELECT MAX(synced_at) FROM ima_sync", null).use {
            if (it.moveToFirst()) it.getString(0) ?: "" else "" })

    fun search(mode: String?, q: String, limit: Int = 20): JSONArray {
        if (q.isBlank()) return listEvents(mode, limit)
        // Android SQLite 版本不一，用 LIKE + 中文词切分兜底（对应桌面版 FTS5 降级路径）
        val terms = Regex("[\\u4e00-\\u9fff]{2,}|[A-Za-z0-9]{2,}")
            .findAll(q).map { it.value }.take(6).toList().ifEmpty { listOf(q) }
        val found = LinkedHashMap<String, JSONObject>()
        for (t in terms) {
            var sql = "SELECT * FROM events WHERE (quote LIKE ? OR summary LIKE ? OR type LIKE ?)"
            val args = mutableListOf("%$t%", "%$t%", "%$t%")
            if (mode != null) { sql += " AND mode_id=?"; args.add(mode) }
            sql += " ORDER BY (event_time IS NULL), event_time ASC LIMIT ?"
            args.add(limit.toString())
            val c = readableDatabase.rawQuery(sql, args.toTypedArray())
            c.use { while (it.moveToNext()) { val e = rowToEvent(it); found.putIfAbsent(e.getString("id"), e) } }
        }
        val out = JSONArray()
        found.values.take(limit).forEach { out.put(it) }
        return out
    }

    fun timeline(mode: String, view: String): JSONObject {
        val evs = listEvents(mode)
        val groups = LinkedHashMap<String, JSONArray>()
        for (i in 0 until evs.length()) {
            val e = evs.getJSONObject(i)
            val ext = e.optJSONObject("extensions") ?: JSONObject()
            val key = when (view) {
                // 象限视图：单卡只做粗映射（精确象限由成长分析给），映射成中文，避免四栏变一栏
                "quadrant" -> when (ext.optString("quadrant")) {
                    "Mind" -> "心智"; "Body" -> "身体"; "Spirit" -> "精神"
                    "Vocation" -> "事业"; else -> "未归类"
                }
                "mastery" -> ext.optString("topic").ifEmpty { "未分类" }
                // 关系视图：没有人物实体时按「我 / 对方」分栏，总比全部堆在「未分类」里强
                "relation-timeline" -> ext.optString("person")
                    .ifEmpty { if (e.optString("speaker") == "self") "我" else "对方" }
                // 领域视图：直接看分类结果，和统计口径一致
                "domain" -> e.optJSONObject("cls")?.optJSONObject("domain")?.optString("name").orEmpty()
                    .ifEmpty { "未分类" }
                "type" -> e.optJSONObject("cls")?.optJSONObject("type")?.optString("name").orEmpty()
                    .ifEmpty { "未分类" }
                else -> e.optString("event_time").take(7).ifEmpty { "时间未定" }
            }
            groups.getOrPut(key) { JSONArray() }.put(e)
        }
        val arr = JSONArray()
        for ((k, v) in groups) arr.put(JSONObject().put("key", k).put("count", v.length()).put("events", v))
        return JSONObject().put("mode_id", mode).put("view", view)
            .put("total", evs.length()).put("groups", arr)
    }

    /**
     * 统计快照。分布全部来自 event_tags（与筛选同源，口径只有一处），
     * 前端画图直接取 by_domain / by_type / by_emotion_l1。
     */
    fun stats(mode: String, days: Int = 0): JSONObject {
        val per = JSONObject()
        val c = readableDatabase.rawQuery(
            "SELECT capture_session, COUNT(*) n FROM events WHERE mode_id=? AND confirmed=1 GROUP BY capture_session",
            arrayOf(mode))
        c.use { while (it.moveToNext()) per.put(it.getString(0), it.getInt(1)) }
        var total = 0; var classified = 0
        var first = ""; var last = ""
        readableDatabase.rawQuery(
            "SELECT COUNT(*), SUM(CASE WHEN cls IS NOT NULL AND cls<>'' THEN 1 ELSE 0 END), " +
                "MIN(event_time), MAX(event_time) FROM events WHERE mode_id=?", arrayOf(mode)).use {
            if (it.moveToFirst()) {
                total = it.getInt(0); classified = it.getInt(1)
                first = it.getString(2) ?: ""; last = it.getString(3) ?: ""
            }
        }
        val today = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM events WHERE mode_id=? AND substr(event_time,1,10)=?",
            arrayOf(mode, now().take(10))).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return JSONObject().put("mode_id", mode)
            .put("locked_scope", "rehearsal AND confirmed=true")
            .put("rehearsal_count", per.optInt("rehearsal"))
            .put("live_count", per.optInt("live")).put("per_session", per)
            .put("total", total).put("classified", classified)
            .put("unclassified", maxOf(0, total - classified))
            .put("today", today).put("first_at", first).put("last_at", last)
            .put("by_domain", statsBy(mode, "domain", days))
            .put("by_type", statsBy(mode, "type", days))
            .put("by_emotion_l1", statsBy(mode, "emotion_l1", days))
            .put("by_emotion_l2", statsBy(mode, "emotion_l2", days))
            .put("top_keywords", statsBy(mode, "keyword", days))
            .put("sentiment_trend", sentimentTrend(mode, if (days > 0) days else 14))
    }

    /** 手动改事件时间：空 → 退回「无时间」；非空按用户给的，标 manual（不再算「估」）。 */
    fun setEventTime(eid: String, whenRaw: String?): JSONObject? {
        val iso = normalizeIso(whenRaw)
        writableDatabase.execSQL("UPDATE events SET event_time=?, time_source=? WHERE id=?",
            arrayOf(iso, if (iso == null) "unknown" else "manual", eid))
        return getEvent(eid)
    }

    private fun normalizeIso(raw: String?): String? {
        val s = (raw ?: "").trim()
        if (s.isEmpty()) return null
        val m = Regex("(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2}))?").find(s) ?: return null
        val y = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val d = m.groupValues[3].toInt()
        val h = m.groupValues[4].toIntOrNull() ?: 0
        val mi = m.groupValues[5].toIntOrNull() ?: 0
        if (mo !in 1..12 || d !in 1..31 || h !in 0..23 || mi !in 0..59) return null
        return "%04d-%02d-%02dT%02d:%02d:00+08:00".format(y, mo, d, h, mi)
    }

    fun correct(eid: String, patch: JSONObject): JSONObject? {
        val e = getEvent(eid) ?: return null
        val snap = JSONObject().put("summary", e.optString("summary"))
            .put("quote", e.optString("quote")).put("type", e.optString("type"))
        writableDatabase.execSQL("INSERT INTO l3b_corrections VALUES(?,?,?,?)",
            arrayOf(id("corr"), eid, patch.toString(), now()))
        writableDatabase.execSQL("UPDATE events SET summary=?, corrected=1, l3a_version=l3a_version+1 WHERE id=?",
            arrayOf(patch.optString("summary", e.optString("summary")), eid))
        return getEvent(eid)?.put("l3a_snapshot", snap)
    }

    fun putL1(file: String, recordedAt: String?, session: String, timeSource: String, meta: JSONObject) {
        val f = java.io.File(appCtx.filesDir, file)
        val bytes = if (f.isFile) f.length() else meta.optLong("bytes", 0)
        val d = writableDatabase
        val ex = d.rawQuery("SELECT keep FROM l1_files WHERE file=?", arrayOf(file))
        val has = ex.use { it.moveToFirst() }
        if (has) {
            // 已存在：不覆盖 keep / expire_at / purged_at（用户标记永久保留后不能被重置）
            val cv = android.content.ContentValues().apply {
                put("recorded_at", recordedAt)
                put("capture_session", session)
                put("time_source", timeSource)
                put("meta", meta.toString())
                put("bytes_on_disk", bytes)
            }
            d.update("l1_files", cv, "file=?", arrayOf(file))
        } else {
            val cv = android.content.ContentValues().apply {
                put("file", file)
                put("recorded_at", recordedAt)
                put("capture_session", session)
                put("time_source", timeSource)
                put("is_demo_sample", 0)
                put("meta", meta.toString())
                put("keep", 0)
                put("expire_at", expireAfter())
                put("bytes_on_disk", bytes)
                put("stored_at", now())
            }
            d.insert("l1_files", null, cv)
        }
    }

    // ---------- v1.1 / F：72 小时音频存证保留策略（只清音频字节，文本层永不动） ----------
    fun l1List(pendingOnly: Boolean = false): JSONArray {
        val sql = "SELECT * FROM l1_files WHERE 1=1" +
            (if (pendingOnly) " AND purged_at IS NULL" else "") + " ORDER BY stored_at DESC"
        val out = JSONArray()
        val c = readableDatabase.rawQuery(sql, null)
        c.use {
            while (it.moveToNext()) {
                fun s(k: String) = it.getString(it.getColumnIndexOrThrow(k)) ?: ""
                out.put(JSONObject()
                    .put("file", s("file"))
                    .put("keep", it.getInt(it.getColumnIndexOrThrow("keep")) == 1)
                    .put("expire_at", s("expire_at"))
                    .put("bytes_on_disk", it.getLong(it.getColumnIndexOrThrow("bytes_on_disk")))
                    .put("purged_at", s("purged_at"))
                    .put("stored_at", s("stored_at")))
            }
        }
        return out
    }

    fun l1SetKeep(file: String, keep: Boolean): Boolean {
        val cv = android.content.ContentValues().apply {
            put("keep", if (keep) 1 else 0)
            if (!keep) put("expire_at", expireAfter())   // 取消保留时重新给满一个 72h 窗口
        }
        return writableDatabase.update("l1_files", cv, "file=?", arrayOf(file)) > 0
    }

    fun l1IsPurged(file: String): Boolean {
        val c = readableDatabase.rawQuery("SELECT purged_at FROM l1_files WHERE file=?", arrayOf(file))
        c.use { return it.moveToFirst() && !it.getString(0).isNullOrEmpty() }
    }

    fun l1PurgeExpired(): JSONObject {
        val ts = now()
        val c = readableDatabase.rawQuery(
            "SELECT file, bytes_on_disk FROM l1_files " +
                "WHERE keep=0 AND purged_at IS NULL AND expire_at IS NOT NULL AND expire_at<=?",
            arrayOf(ts))
        val targets = ArrayList<Pair<String, Long>>()
        c.use { while (it.moveToNext()) targets.add(it.getString(0) to it.getLong(1)) }
        var freed = 0L
        val purged = JSONArray()
        val base = java.io.File(appCtx.filesDir, "l1").canonicalFile
        for ((rel, bytes) in targets) {
            val f = java.io.File(base, rel.removePrefix("l1/")).canonicalFile
            if (f != base && !f.path.startsWith(base.path + java.io.File.separator)) continue
            if (f.isFile) {
                freed += bytes
                try { f.delete() } catch (ignored: Exception) { }
            }
            val cv = android.content.ContentValues().apply { put("purged_at", ts) }
            writableDatabase.update("l1_files", cv, "file=?", arrayOf(rel))
            purged.put(rel)
        }
        return JSONObject().put("count", purged.length())
            .put("freed_bytes", freed).put("purged", purged)
    }

    /** 容量水位清理：超过 maxBytes 时按「最久远优先」回收音频字节。
     *
     *  为什么必须有这一条：72 小时策略只管时间，不管体积。常驻录音写的是未压缩 WAV
     *  （16k 单声道 ≈ **115 MB/小时**），连着录两天就能把 128G 的手机塞满，而用户根本
     *  不会主动去点「清理」。这里只删音频字节，事件卡、转写原文、分析结果一律不动。
     *
     *  @param maxBytes 水位线，默认 1.5 GB；回收后留 10% 余量，避免刚清完又立刻触发。
     */
    fun l1PurgeToCap(maxBytes: Long = 1_500_000_000L): JSONObject {
        val live = readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(bytes_on_disk),0) FROM l1_files WHERE purged_at IS NULL", null)
        var bytes = 0L
        live.use { if (it.moveToFirst()) bytes = it.getLong(0) }
        if (bytes <= maxBytes) return JSONObject().put("count", 0).put("freed_bytes", 0L)
        val target = (maxBytes * 0.9).toLong()
        val c = readableDatabase.rawQuery(
            "SELECT file, bytes_on_disk FROM l1_files " +
                "WHERE keep=0 AND purged_at IS NULL ORDER BY stored_at ASC", null)
        val targets = ArrayList<Pair<String, Long>>()
        c.use { while (it.moveToNext()) targets.add(it.getString(0) to it.getLong(1)) }
        val ts = now()
        var freed = 0L
        var n = 0
        val base = java.io.File(appCtx.filesDir, "l1").canonicalFile
        for ((rel, size) in targets) {
            if (bytes - freed <= target) break
            val f = java.io.File(base, rel.removePrefix("l1/")).canonicalFile
            // 与 l1PurgeExpired 同一套 canonical 前缀校验，防止越出 l1/ 子树
            if (f != base && !f.path.startsWith(base.path + java.io.File.separator)) continue
            if (f.isFile && f.delete()) freed += size
            else if (!f.isFile) freed += size   // 文件已被手动删掉，账也要销
            writableDatabase.update("l1_files",
                android.content.ContentValues().apply { put("purged_at", ts) }, "file=?", arrayOf(rel))
            n++
        }
        return JSONObject().put("count", n).put("freed_bytes", freed).put("cap_bytes", maxBytes)
    }

    fun l1Stats(): JSONObject {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(*), COALESCE(SUM(bytes_on_disk),0), " +
                "SUM(CASE WHEN keep=1 THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN purged_at IS NOT NULL THEN 1 ELSE 0 END), " +
                "SUM(CASE WHEN purged_at IS NULL AND expire_at<=? THEN 1 ELSE 0 END) FROM l1_files",
            arrayOf(now()))
        c.use {
            if (it.moveToFirst()) return JSONObject()
                .put("retention_hours", RETENTION_HOURS)
                .put("audio_files", it.getInt(0))
                .put("audio_bytes", it.getLong(1))
                .put("kept_files", it.getInt(2))
                .put("purged_files", it.getInt(3))
                .put("expired_pending", it.getInt(4))
        }
        return JSONObject().put("retention_hours", RETENTION_HOURS)
    }

    fun putL2(file: String, segs: JSONArray) {
        val d = writableDatabase
        d.delete("l2_segments", "file=?", arrayOf(file))
        for (i in 0 until segs.length()) {
            val s = segs.getJSONObject(i)
            d.execSQL("INSERT INTO l2_segments VALUES(?,?,?,?,?,?)",
                arrayOf(id("seg"), file, s.optInt("start_ms"), s.optInt("end_ms"),
                    s.optString("quote"), s.optString("speaker", "self")))
        }
    }

    fun getL2(file: String): JSONArray {
        val out = JSONArray()
        val c = readableDatabase.rawQuery("SELECT * FROM l2_segments WHERE file=? ORDER BY start_ms", arrayOf(file))
        c.use {
            while (it.moveToNext()) out.put(JSONObject()
                .put("file", it.getString(1)).put("start_ms", it.getInt(2))
                .put("end_ms", it.getInt(3)).put("quote", it.getString(4))
                .put("speaker", it.getString(5)).put("readonly", 1))
        }
        return out
    }

    /** 同一段录音切出来的全部条目。fileRef 形如 l1/xxx.wav，存在 quote_ref JSON 里。 */
    fun eventsByFile(fileRef: String): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM events WHERE quote_ref LIKE ? ORDER BY created_at",
            arrayOf("%$fileRef%")).use { c ->
            while (c.moveToNext()) out.put(rowToEvent(c))
        }
        return out
    }

    /** 重切前清掉旧条目，顺手清标签。事务包起来，中途失败不会留下半套数据。 */
    fun deleteEvents(ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val d = writableDatabase
        var n = 0
        d.beginTransaction()
        try {
            for (eid in ids) {
                d.delete("events", "id=?", arrayOf(eid))
                d.delete("event_tags", "event_id=?", arrayOf(eid))
                n++
            }
            d.setTransactionSuccessful()
        } finally {
            try { d.endTransaction() } catch (_: Exception) { }
        }
        return n
    }

    fun listModes(): JSONArray {
        val out = JSONArray()
        val c = readableDatabase.rawQuery("SELECT * FROM modes ORDER BY mode_id", null)
        c.use {
            while (it.moveToNext()) out.put(JSONObject().put("mode_id", it.getString(0))
                .put("name", it.getString(1)).put("config", JSONObject(it.getString(2))))
        }
        return out
    }

    fun setMode(mid: String, name: String, config: JSONObject) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO modes VALUES(?,?,?)",
            arrayOf(mid, name, config.toString()))
    }

    fun installComponent(m: JSONObject, enabled: Boolean): JSONObject {
        val d = writableDatabase
        d.execSQL("INSERT OR REPLACE INTO components VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(m.optString("id"), m.optString("type"), m.optString("name"),
                m.optString("version", "1.0.0"), m.optString("repo", "folder://modules/" + m.optString("id")),
                m.toString(), if (enabled) 1 else 0, now()))
        return JSONObject().put("id", m.optString("id")).put("enabled", enabled).put("manifest", m)
    }

    fun listComponents(type: String?): JSONArray {
        val out = JSONArray()
        val c = if (type == null) readableDatabase.rawQuery("SELECT * FROM components ORDER BY installed_at", null)
        else readableDatabase.rawQuery("SELECT * FROM components WHERE type=? ORDER BY installed_at", arrayOf(type))
        c.use {
            while (it.moveToNext()) out.put(JSONObject()
                .put("id", it.getString(0)).put("type", it.getString(1)).put("name", it.getString(2))
                .put("version", it.getString(3)).put("repo", it.getString(4))
                .put("manifest", JSONObject(it.getString(5))).put("enabled", it.getInt(6) == 1))
        }
        return out
    }

    fun toggleComponent(cid: String, enabled: Boolean) {
        writableDatabase.execSQL("UPDATE components SET enabled=? WHERE id=?",
            arrayOf(if (enabled) 1 else 0, cid))
    }

    fun uninstallComponent(cid: String): Boolean {
        return writableDatabase.delete("components", "id=?", arrayOf(cid)) > 0
    }

    fun startSession(mode: String): JSONObject {
        val s = JSONObject().put("id", id("rec")).put("mode_id", mode)
            .put("capture_session", "live").put("started_at", now())
            .put("status", "recording").put("chunks", 0).put("bytes", 0)
        writableDatabase.execSQL("INSERT INTO sessions VALUES(?,?,?,?,?,?,?,?)",
            arrayOf(s.getString("id"), mode, "live", s.getString("started_at"), null, "recording", 0, 0))
        return s
    }

    fun pushChunk(sid: String, n: Int) {
        writableDatabase.execSQL("UPDATE sessions SET chunks=chunks+1, bytes=bytes+? WHERE id=?",
            arrayOf(n, sid))
    }

    fun stopSession(sid: String): JSONObject? {
        writableDatabase.execSQL("UPDATE sessions SET stopped_at=?, status='stopped' WHERE id=?",
            arrayOf(now(), sid))
        val c = readableDatabase.rawQuery("SELECT * FROM sessions WHERE id=?", arrayOf(sid))
        c.use { return if (it.moveToFirst()) JSONObject().put("id", it.getString(0))
            .put("status", it.getString(5)).put("chunks", it.getInt(6)) else null }
    }

    fun logStage(src: String, mode: String, stage: String, adapter: String, detail: JSONObject) {
        writableDatabase.execSQL("INSERT INTO stage_log VALUES(?,?,?,?,?,?,?)",
            arrayOf(id("stg"), src, mode, stage, adapter, detail.toString(), now()))
    }

    fun putAnalysis(a: JSONObject) {
        // 显式列名：v6 给 analyses 加了 granularity/period_key，隐式 VALUES 会列数不匹配
        writableDatabase.execSQL("""INSERT OR REPLACE INTO analyses(
            id,mode_id,source_id,payload,created_at,granularity,period_key)
            VALUES(?,?,?,?,?,?,?)""",
            arrayOf(a.optString("id"), a.optString("mode_id"), a.optString("source_id"),
                a.toString(), a.optString("created_at", now()),
                a.optString("granularity", "segment"), a.optString("period_key", "")))
    }

    fun listAnalyses(mode: String?, limit: Int = 50): JSONArray {
        val out = JSONArray()
        val c = if (mode == null)
            readableDatabase.rawQuery("SELECT payload FROM analyses ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString()))
        else readableDatabase.rawQuery(
            "SELECT payload FROM analyses WHERE mode_id=? ORDER BY created_at DESC LIMIT ?",
            arrayOf(mode, limit.toString()))
        c.use { while (it.moveToNext()) out.put(JSONObject(it.getString(0))) }
        return out
    }

    /** 按粒度取分析：segment（单段，实时）/ day（当日汇总）/ week（周期洞察）。 */
    fun listAnalysesBy(mode: String, granularity: String, limit: Int = 20): JSONArray {
        val out = JSONArray()
        val c = readableDatabase.rawQuery(
            """SELECT payload FROM analyses WHERE mode_id=? AND granularity=?
               ORDER BY period_key DESC, created_at DESC LIMIT ?""",
            arrayOf(mode, granularity, limit.toString()))
        c.use { while (it.moveToNext()) out.put(JSONObject(it.getString(0))) }
        return out
    }

    fun findAnalysis(mode: String, granularity: String, periodKey: String): JSONObject? {
        val c = readableDatabase.rawQuery(
            """SELECT payload FROM analyses WHERE mode_id=? AND granularity=? AND period_key=?
               ORDER BY created_at DESC LIMIT 1""",
            arrayOf(mode, granularity, periodKey))
        c.use { return if (it.moveToFirst()) JSONObject(it.getString(0)) else null }
    }

    // ---------- 分类标签 ----------

    /** 整表替换某事件的标签：先删后插，避免重算后残留旧标签。 */
    fun replaceTags(eventId: String, tags: List<JSONObject>) {
        val d = writableDatabase
        d.beginTransaction()
        try {
            d.execSQL("DELETE FROM event_tags WHERE event_id=?", arrayOf(eventId))
            for (t in tags) {
                d.execSQL("""INSERT INTO event_tags(event_id,kind,value,name,confidence,source,created_at)
                    VALUES(?,?,?,?,?,?,?)""",
                    arrayOf(eventId, t.optString("kind"), t.optString("value"),
                        t.optString("name"), t.optDouble("confidence", 1.0),
                        t.optString("source", "builtin:lexicon"), now()))
            }
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
    }

    fun getTags(eventId: String): JSONArray {
        val out = JSONArray()
        readableDatabase.rawQuery(
            "SELECT kind,value,name,confidence,source FROM event_tags WHERE event_id=? ORDER BY kind",
            arrayOf(eventId)).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().put("kind", c.getString(0)).put("value", c.getString(1))
                    .put("name", c.getString(2)).put("confidence", c.getDouble(3))
                    .put("source", c.getString(4)))
            }
        }
        return out
    }

    /**
     * 按维度聚合。kind ∈ domain / type / emotion_l1 / emotion_l2 / topic / person / keyword
     * sinceDays<=0 表示不限时间。结果按 count 降序，供前端画分布图。
     */
    fun statsBy(mode: String?, kind: String, sinceDays: Int = 0): JSONArray {
        val sql = StringBuilder(
            """SELECT t.value, t.name, COUNT(*) n FROM event_tags t
               JOIN events e ON e.id = t.event_id
               WHERE t.kind = ?""")
        val args = mutableListOf<String>(kind)
        if (mode != null) { sql.append(" AND e.mode_id=?"); args.add(mode) }
        if (sinceDays > 0) {
            sql.append(" AND e.event_time >= ?")
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -sinceDays)
            args.add(FMT.format(cal.time).take(10) + "T00:00:00+08:00")
        }
        sql.append(" GROUP BY t.value ORDER BY n DESC")
        val out = JSONArray()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().put("value", c.getString(0))
                    .put("name", c.getString(1) ?: c.getString(0)).put("count", c.getInt(2)))
            }
        }
        return out
    }

    /** 情绪强度按天取均值，用于「这周情绪走势」。 */
    fun sentimentTrend(mode: String?, days: Int = 14): JSONArray {
        val sql = StringBuilder(
            """SELECT substr(e.event_time,1,10) d,
                      AVG(e.sentiment) s,
                      COUNT(*) n
               FROM events e
               WHERE e.sentiment IS NOT NULL AND e.event_time IS NOT NULL AND e.event_time<>''""")
        val args = mutableListOf<String>()
        if (mode != null) { sql.append(" AND e.mode_id=?"); args.add(mode) }
        if (days > 0) {
            sql.append(" AND e.event_time >= ?")
            val cal = java.util.Calendar.getInstance()
            cal.add(java.util.Calendar.DAY_OF_YEAR, -days)
            args.add(FMT.format(cal.time).take(10))
        }
        sql.append(" GROUP BY d ORDER BY d ASC")
        val out = JSONArray()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.put(JSONObject().put("date", c.getString(0))
                    .put("sentiment", Math.round(c.getDouble(1) * 100) / 100.0)
                    .put("count", c.getInt(2)))
            }
        }
        return out
    }

    /** 只更新分类快照（含摘要用的 sentiment 列），不碰其它列。人工纠正与重算都走这里。 */
    fun setEventCls(eventId: String, cls: JSONObject) {
        val senti = cls.optDouble("sentiment", Double.NaN)
        if (senti.isNaN()) writableDatabase.execSQL("UPDATE events SET cls=? WHERE id=?",
            arrayOf(cls.toString(), eventId))
        else writableDatabase.execSQL("UPDATE events SET cls=?, sentiment=? WHERE id=?",
            arrayOf(cls.toString(), senti, eventId))
    }

    /** 没有分类结果的事件，供「重算分类」扫描。 */
    fun eventsWithoutCls(mode: String?, limit: Int = 500): JSONArray {
        val sql = StringBuilder("SELECT * FROM events WHERE (cls IS NULL OR cls='')")
        val args = mutableListOf<String>()
        if (mode != null) { sql.append(" AND mode_id=?"); args.add(mode) }
        sql.append(" ORDER BY created_at ASC LIMIT ?")
        args.add(limit.toString())
        val out = JSONArray()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.put(rowToEvent(c))
        }
        return out
    }

    fun getStages(src: String): JSONArray {
        val out = JSONArray()
        val c = readableDatabase.rawQuery("SELECT * FROM stage_log WHERE source=? ORDER BY created_at", arrayOf(src))
        c.use {
            while (it.moveToNext()) out.put(JSONObject().put("stage", it.getString(3))
                .put("adapter", it.getString(4)).put("detail", JSONObject(it.getString(5))))
        }
        return out
    }
}
