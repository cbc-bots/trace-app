package com.trace.app

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** 流水线编排 + 云端 LLM + 组件市场。对应桌面版 pipeline.py / adapters_oss.py。 */
class TraceEngine(private val ctx: Context, val db: TraceDb) {

    var apiKey: String = ""
    var baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    var llmModel: String = "qwen3.8-max"
    var asrEndpoint: String = ""
    var imaClientId: String = ""
    var imaApiKey: String = ""
    var imaKbId: String = ""
    var imaKbName: String = ""
    var activeAgent: String = "base"

    companion object {
        private const val TAG = "TraceEngine"
        private fun nid(p: String) = p + "_" + UUID.randomUUID().toString().substring(0, 12)
        private fun now() = TraceDb.now()
    }

    // ---------- 配置持久化 ----------
    /** 输入框里粘进来的 key 经常带脏字符：首尾空格、换行（复制时连带）、
     *  零宽空格、左右引号、甚至整句 "Bearer sk-xxx"。这些都会让服务端判定
     *  "Incorrect API key"，但用户看起来「明明填了」。统一在入口洗一遍。 */
    private fun sanitizeKey(raw: String): String {
        var s = raw
        s = s.replace("\r", "").replace("\n", "").replace("\t", "")
        s = s.replace("\uFEFF", "").replace("\u200B", "")
        s = s.replace("\u200C", "").replace("\u200D", "").replace("\u00A0", "")
        s = s.filter { it.code in 0x21..0x7E }   // key 只用 ASCII 可见字符
        if (s.startsWith("Bearer", ignoreCase = true)) s = s.substring(6)
        s = s.trim().trim('"').trim('\'').trim()
        return s
    }

    /** 给界面看的脱敏片段，便于确认「存的是哪一把 key」。 */
    fun keyHint(): String {
        val k = apiKey
        if (k.isBlank()) return ""
        val tail = if (k.length > 8) k.takeLast(4) else ""
        return "${k.take(3)}…$tail · ${k.length} 位"
    }

    fun loadConfig() {
        val p = ctx.getSharedPreferences("trace", Context.MODE_PRIVATE)
        apiKey = sanitizeKey(p.getString("api_key", "") ?: "")
        baseUrl = (p.getString("base_url", baseUrl) ?: baseUrl).trim().trimEnd('/')
        llmModel = (p.getString("llm_model", llmModel) ?: llmModel).trim()
        asrEndpoint = p.getString("asr_endpoint", "") ?: ""
        imaClientId = p.getString("ima_client_id", "") ?: ""
        imaApiKey = p.getString("ima_api_key", "") ?: ""
        imaKbId = p.getString("ima_kb_id", "") ?: ""
        imaKbName = p.getString("ima_kb_name", "") ?: ""
        activeAgent = p.getString("active_agent", activeAgent) ?: activeAgent
    }

    fun saveConfig(key: String? = null, endpoint: String? = null, agent: String? = null,
                   base: String? = null, model: String? = null) {
        if (key != null) apiKey = sanitizeKey(key)
        if (endpoint != null) asrEndpoint = endpoint
        if (agent != null) activeAgent = agent
        if (base != null && base.isNotBlank()) baseUrl = base.trim().trimEnd('/')
        if (model != null && model.isNotBlank()) llmModel = model.trim()
        ctx.getSharedPreferences("trace", Context.MODE_PRIVATE).edit()
            .putString("api_key", apiKey).putString("base_url", baseUrl)
            .putString("llm_model", llmModel).putString("asr_endpoint", asrEndpoint)
            .putString("ima_client_id", imaClientId).putString("ima_api_key", imaApiKey)
            .putString("ima_kb_id", imaKbId).putString("ima_kb_name", imaKbName)
            .putString("active_agent", activeAgent).apply()
    }

    /** 当前启用的人格预设（骑砍式模组里的 prompt.md） */
    fun persona(): Pair<String, String> {
        val comps = db.listComponents("agent")
        for (i in 0 until comps.length()) {
            val c = comps.getJSONObject(i)
            if (c.optString("id") == activeAgent) {
                val m = c.optJSONObject("manifest") ?: JSONObject()
                return m.optString("prompt_md", "") to m.optString("name", "助手")
            }
        }
        return "" to "基础助手"
    }

    // ---------- 适配器状态 ----------
    fun hasSherpaAar(): Boolean = try {
        // 两个类都要在：AAR 提供 native 封装，SherpaAsr 是我们条件编译的真实实现
        Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer")
        Class.forName("com.trace.app.SherpaAsr")
        true
    } catch (e: Throwable) { false }

    fun hasOnnxAssets(): Boolean {
        return try {
            val files = ctx.assets.list("models/asr-zh-14M")?.toSet()
            if (files == null) {
                false
            } else {
                // 四个文件缺一不可；只查 tokens.txt 会漏掉打包时丢 onnx 的情况
                listOf("tokens.txt", "encoder-epoch-99-avg-1.int8.onnx",
                    "decoder-epoch-99-avg-1.int8.onnx",
                    "joiner-epoch-99-avg-1.int8.onnx").all { it in files }
            }
        } catch (e: Exception) { false }
    }

    fun adapters(): JSONObject = JSONObject()
        .put("vad", "builtin")
        .put("asr", when {
            hasSherpaAar() && hasOnnxAssets() -> "sherpa-onnx:zh-14M"
            asrEndpoint.isNotBlank() -> "cloud:$asrEndpoint"
            else -> "builtin:text-channel"
        })
        .put("speaker", if (apiKey.isBlank()) "builtin" else "qwen:speaker-split")
        .put("extractor", if (apiKey.isBlank()) "builtin" else "qwen:extract")
        .put("ranker", "builtin:LIKE")
        .put("agent", if (apiKey.isBlank()) "builtin" else "qwen:agent")
        .put("polish", if (apiKey.isBlank()) "builtin:rules" else "qwen:polish")
        .put("analysis", if (apiKey.isBlank()) "builtin:rules" else "qwen:analysis")
        .put("ima", if (imaConfigured()) "ima:openapi" else "builtin")

    // ---------- 云端 LLM（HttpURLConnection，零第三方依赖） ----------
    /** 复用的连接工厂：**不要每次 connect 完就 disconnect**。
     *  HttpURLConnection 默认走 keep-alive，同一条 TLS 连接可以连续用；
     *  每轮新建 + 断开会重握手，国内到 dashscope 约 100–400ms，对话里就是纯浪费。 */
    private fun openConn(): HttpURLConnection = (URL("$baseUrl/chat/completions").openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Authorization", "Bearer $apiKey")
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Connection", "keep-alive")
        connectTimeout = 10000
        doOutput = true
    }

    /** 最近一次 LLM 失败的结构化原因，给 /config/test 和错误提示用。 */
    @Volatile
    var lastLlmError: String = ""
        private set
    @Volatile
    var lastLlmCode: Int = 0
        private set

    private fun llm(body: JSONObject, timeoutMs: Int = 45000): JSONObject? {
        if (apiKey.isBlank()) { lastLlmError = "未配置 API Key"; lastLlmCode = 0; return null }
        var conn: HttpURLConnection? = null
        return try {
            conn = openConn()
            conn.readTimeout = timeoutMs
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            lastLlmCode = code
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                lastLlmError = humanizeApiError(text, code)
                null
            } else {
                lastLlmError = ""
                JSONObject(text)
            }
        } catch (e: Exception) {
            lastLlmError = "请求没发出去：" + (e.message ?: e.javaClass.simpleName)
            try { conn?.disconnect() } catch (_: Exception) { }
            null
        }
    }

    /** 把阿里云/OpenAI 兼容层的原始报错翻成人话。界面直接显示这段，
     *  不再把整段 JSON 甩给用户（原来用户看到的是一大坨英文 + 重复两遍）。 */
    fun humanizeApiError(raw: String, code: Int = 0): String {
        val t = raw.lowercase()
        val model = llmModel
        return when {
            t.contains("incorrect api key") || t.contains("invalidapikey")
                || t.contains("invalid_api_key") || t.contains("api-key-error") ->
                "API Key 不被接受。请到阿里云百炼控制台重新复制一次（整串 sk- 开头，别漏字符），" +
                "并确认这把 key 与当前 Base URL 属于同一个站点（中国内地站 / 国际站不通用）。"
            t.contains("model not exist") || t.contains("model_not_found")
                || t.contains("does not exist") ->
                "模型「$model」不可用。到设置里把模型改成 qwen-plus 或 qwen-max 再试。"
            t.contains("arrearage") || t.contains("overdue") || t.contains("insufficient") ->
                "账户欠费或额度已用尽，请到百炼控制台充值。"
            t.contains("access denied") || t.contains("forbidden") || code == 403 ->
                "这把 Key 没有调用该模型的权限，请在控制台给对应的 API-KEY 授权。"
            code == 429 || t.contains("throttling") || t.contains("rate limit") ->
                "请求太快被限流了，等几秒再问。"
            t.contains("timeout") || t.contains("timed out") ->
                "请求超时。录音很长或网络慢时会这样，重试或换个短一点的问题。"
            code == 401 -> "鉴权失败（401）。Key 可能已失效或被删除，重新生成一把。"
            code == 404 -> "接口地址不对（404）。检查设置里的 Base URL，默认应是 https://dashscope.aliyuncs.com/compatible-mode/v1"
            t.contains("connect") || t.contains("unable to resolve") || t.contains("network") ->
                "连不上服务器，检查网络或代理。"
            raw.isBlank() -> "服务端没有返回内容（HTTP $code）"
            else -> raw.take(160)
        }
    }

    /** 用一次真实请求验证 key（1 token，约 1–2 秒）。设置页点保存时调用。 */
    fun verifyKey(): JSONObject {
        if (apiKey.isBlank()) return JSONObject().put("ok", false).put("error", "还没填 Key")
        val body = JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("max_tokens", 1)
            .put("messages", JSONArray().put(
                JSONObject().put("role", "user").put("content", "hi")))
        val r = llm(body, 20000)
        return if (r != null)
            JSONObject().put("ok", true).put("model", llmModel).put("key", keyHint())
        else
            JSONObject().put("ok", false).put("error", lastLlmError)
                .put("code", lastLlmCode).put("model", llmModel).put("key", keyHint())
    }

    private fun llmContent(body: JSONObject): String? = try {
        llm(body)?.getJSONArray("choices")?.getJSONObject(0)
            ?.getJSONObject("message")?.getString("content")
    } catch (e: Exception) { null }

    /** 条目切分的输出可能有一两千字（几十条 note），45 秒默认超时不够用 */
    private fun llmContent(body: JSONObject, timeoutMs: Int): String? = try {
        llm(body, timeoutMs)?.getJSONArray("choices")?.getJSONObject(0)
            ?.getJSONObject("message")?.getString("content")
    } catch (e: Exception) { null }

    // ---------- 分类：离线词表内核（Classifier）+ 模型结果对齐 ----------
    // 枚举只在这里定义一次，omniSchema / llmExtract 的 enum 由它生成，避免三处漂移。
    private val TYPE_IDS = linkedMapOf(
        "decision" to "决定", "action" to "行动", "outcome" to "结果", "emotion" to "情绪",
        "problem" to "问题", "plan" to "计划", "insight" to "觉察", "fact" to "见闻")
    private val DOMAIN_IDS = linkedMapOf(
        "life" to "生活日常", "work" to "工作学习", "relation" to "关系社交",
        "health" to "身心状态", "money" to "财务消费", "idea" to "想法觉察")

    private fun typeIdOf(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val low = v.lowercase()
        if (TYPE_IDS.containsKey(low)) return low
        return TYPE_IDS.entries.firstOrNull { it.value == v }?.key
    }

    private fun domainIdOf(raw: String): String? {
        val v = raw.trim()
        if (v.isEmpty()) return null
        val low = v.lowercase()
        if (DOMAIN_IDS.containsKey(low)) return low
        return DOMAIN_IDS.entries.firstOrNull { it.value == v }?.key
    }

    private fun shareOf(scores: JSONObject, top: String): Double {
        if (top.isBlank()) return 0.0
        var total = 0.0
        for (k in scores.keys()) total += scores.optDouble(k, 0.0)
        if (total <= 0.0) return 0.0
        return Math.round(scores.optDouble(top, 0.0) / total * 100) / 100.0
    }

    /**
     * 事件分类与情绪标注的唯一入口。
     * 规则先算一遍兜底；模型给了合法枚举就采用模型的结论 —— 但两者写进同一份结构，
     * 前端不用判断「这条是规则还是模型」，只看 cls.type.source 决定是否显示「规则」角标。
     * 没有任何信号时 type 落到 fact（见闻），不硬猜。
     */
    private fun buildCls(quote: String, modelType: String, modelDomain: String): JSONObject {
        var r = JSONObject()
        try {
            Classifier.ensureLoaded(ctx)
            r = Classifier.classify(ctx, quote)
        } catch (e: Exception) {
            Log.w(TAG, "classifier unavailable: ${e.message}")
        }
        val ev = r.optJSONObject("event") ?: JSONObject()
        val emo = r.optJSONObject("emotion") ?: JSONObject()
        val tScores = ev.optJSONObject("type_scores") ?: JSONObject()
        val dScores = ev.optJSONObject("domain_scores") ?: JSONObject()
        val mType = typeIdOf(modelType)
        val mDom = domainIdOf(modelDomain)
        val rType = typeIdOf(ev.optString("type")) ?: "fact"
        val rDom = domainIdOf(ev.optString("domain"))
        val typeId = mType ?: rType
        val domId = mDom ?: rDom

        val primary = emo.optJSONObject("primary")
        val emotion = JSONObject()
        if (primary != null) {
            val evid = JSONArray()
            val hits = emo.optJSONArray("hits") ?: JSONArray()
            for (i in 0 until minOf(hits.length(), 4))
                evid.put(hits.getJSONObject(i).optString("word"))
            emotion.put("l1", primary.optString("l1"))
                .put("l1_name", primary.optString("l1_name"))
                .put("l2", primary.optString("l2"))
                .put("l2_name", primary.optString("l2_name"))
                .put("word", primary.optString("word"))
                .put("intensity", primary.optInt("intensity"))
                .put("polarity", primary.optInt("polarity"))
                .put("negated", primary.optBoolean("negated"))
                .put("evidence", evid)
                .put("adapter", "builtin:lexicon")
        } else {
            emotion.put("l1", JSONObject.NULL).put("l2", JSONObject.NULL)
                .put("intensity", JSONObject.NULL).put("adapter", "builtin:lexicon")
        }
        val keywords = r.optJSONArray("tags") ?: JSONArray()
        val sentiment = emo.optDouble("sentiment", 0.0)
        return JSONObject()
            .put("domain", if (domId == null) JSONObject.NULL else JSONObject()
                .put("id", domId).put("name", DOMAIN_IDS[domId] ?: domId)
                .put("confidence", if (mDom != null) 1.0 else shareOf(dScores, ev.optString("domain")))
                .put("source", if (mDom != null) "qwen:model" else "builtin:lexicon"))
            .put("type", JSONObject().put("id", typeId).put("name", TYPE_IDS[typeId] ?: typeId)
                .put("confidence", if (mType != null) 1.0 else shareOf(tScores, ev.optString("type")))
                .put("source", if (mType != null) "qwen:model" else "builtin:lexicon"))
            .put("emotion", emotion)
            .put("tense", ev.optString("tense", "present"))
            .put("modality", ev.optString("modality", "actual"))
            .put("keywords", keywords)
            .put("sentiment", sentiment)
            .put("taxonomy_version", r.optString("lexicon_version", ""))
            .put("source", "builtin:lexicon")
            .put("corrected", false)
    }

    /** cls 快照 → event_tags 行。前端筛选/统计都查这张表，不再解析 JSON。 */
    private fun clsTags(cls: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        fun tag(kind: String, value: String, name: String, conf: Double, src: String) {
            if (value.isBlank()) return
            out.add(JSONObject().put("kind", kind).put("value", value)
                .put("name", name.ifBlank { value }).put("confidence", conf).put("source", src))
        }
        val dom = cls.optJSONObject("domain")
        if (dom != null) tag("domain", dom.optString("id"), dom.optString("name"),
            dom.optDouble("confidence", 1.0), dom.optString("source"))
        val typ = cls.optJSONObject("type")
        if (typ != null) tag("type", typ.optString("id"), typ.optString("name"),
            typ.optDouble("confidence", 1.0), typ.optString("source"))
        val emo = cls.optJSONObject("emotion")
        if (emo != null && !emo.isNull("l1")) {
            tag("emotion_l1", emo.optString("l1"), emo.optString("l1_name"), 1.0, "builtin:lexicon")
            tag("emotion_l2", emo.optString("l2"), emo.optString("l2_name"), 1.0, "builtin:lexicon")
        }
        tag("tense", cls.optString("tense"), "", 1.0, "builtin:lexicon")
        tag("modality", cls.optString("modality"), "", 1.0, "builtin:lexicon")
        val kws = cls.optJSONArray("keywords") ?: JSONArray()
        for (i in 0 until minOf(kws.length(), 6))
            tag("keyword", kws.optString(i), "", 0.6, "builtin:lexicon")
        return out
    }

    /** 把 ISO 时间串解析成 Calendar；解析不了就退回当前时刻。 */
    private fun calOf(iso: String?): java.util.Calendar {
        val cal = java.util.Calendar.getInstance()
        if (iso.isNullOrBlank()) return cal
        for (pat in listOf("yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            try {
                val f = java.text.SimpleDateFormat(pat, java.util.Locale.CHINA)
                f.isLenient = false
                cal.time = f.parse(iso.substring(0, minOf(iso.length, pat.length))) ?: continue
                return cal
            } catch (_: Exception) { }
        }
        return cal
    }

    /** 「2026-11-07 17:36（周六）」——给模型当时间锚点，也给界面显示。 */
    /** recAt + offsetMs，给分段转写算「这一段开始于几点」 */
    private fun isoPlus(baseIso: String, offsetMs: Long): String = try {
        java.time.OffsetDateTime.parse(baseIso)
            .plusNanos(offsetMs * 1_000_000L)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX"))
    } catch (e: Exception) { baseIso }

    fun humanTime(iso: String? = null): String {
        val c = calOf(iso ?: now())
        val wd = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
        return "%04d-%02d-%02d %02d:%02d（%s）".format(
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
            c.get(java.util.Calendar.DAY_OF_MONTH), c.get(java.util.Calendar.HOUR_OF_DAY),
            c.get(java.util.Calendar.MINUTE),
            wd[c.get(java.util.Calendar.DAY_OF_WEEK) - 1])
    }

    private fun ymd(c: java.util.Calendar): String = "%04d-%02d-%02d".format(
        c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
        c.get(java.util.Calendar.DAY_OF_MONTH))

    /** 「下午三点」「晚上 8 点」「十点半」→ 24 小时制小时；没提到具体时刻返回 null。 */
    private fun parseHour(t: String): Int? {
        val m = Regex("(凌晨|早上|早晨|上午|中午|下午|傍晚|晚上|夜里|半夜)?\\s*" +
                "(?<![第])([0-9]{1,2}|[零一二两三四五六七八九十]{1,2})\\s*[点時时]")
            .find(t) ?: return null
        var h = cnToInt(m.groupValues[2]) ?: return null
        if (h !in 0..23) return null
        when (m.groupValues[1]) {
            "下午", "傍晚", "晚上", "夜里" -> if (h in 1..11) h += 12
            "中午" -> if (h in 1..10) h += 12
            "凌晨", "半夜" -> if (h == 12) h = 0
        }
        return h
    }

    /** 「十一点」「两点」「三」这类中文数字转 int；认不出返回 null。 */
    private fun cnToInt(s: String): Int? {
        if (s.isEmpty()) return null
        if (s.all { it.isDigit() }) return s.toIntOrNull()
        val d = mapOf('零' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
            '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        if (s == "十") return 10
        if (s.length == 2 && s[0] == '十') return 10 + (d[s[1]] ?: return null)
        if (s.length == 2 && s[1] == '十') return (d[s[0]] ?: return null) * 10
        if (s.length == 1) return d[s[0]]
        return null
    }

    /**
     * 从一句话里取事件时间。
     * 先认显式日期，再认「今天/昨天/前天」这类相对说法（基准是录音时刻，不是现在），
     * 最后才认「下午三点」这种只有时刻的说法 —— 用录音当日补日期。
     * 全都认不出返回 null，由调用方用「录制当日」兜底并标记 time_source=recorded。
     */
    private fun parseDate(t: String, base: String? = null): String? {
        val bcal = calOf(base)
        var day: String? = null
        val m1 = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})").find(t)
        if (m1 != null) day = "%04d-%02d-%02d".format(
            m1.groupValues[1].toInt(), m1.groupValues[2].toInt(), m1.groupValues[3].toInt())
        if (day == null) {
            val rel = when {
                t.contains("大前天") -> -3
                t.contains("前天") -> -2
                t.contains("昨天") || t.contains("昨日") || t.contains("昨晚") -> -1
                t.contains("今天") || t.contains("今日") || t.contains("今早") ||
                    t.contains("今儿") || t.contains("今晚") -> 0
                else -> null
            }
            if (rel != null) {
                val c = bcal.clone() as java.util.Calendar
                c.add(java.util.Calendar.DAY_OF_MONTH, rel)
                day = ymd(c)
            }
        }
        if (day == null) {
            val m2 = Regex("(?<!\\d)(\\d{1,2})[-/.月](\\d{1,2})(?!\\d)").find(t)
            if (m2 != null) day = "%04d-%02d-%02d".format(
                bcal.get(java.util.Calendar.YEAR),
                m2.groupValues[1].toInt(), m2.groupValues[2].toInt())
        }
        val hour = parseHour(t)
        if (day == null) day = if (hour != null) ymd(bcal) else return null
        return if (hour == null) "${day}T00:00:00+08:00"
        else "%sT%02d:00:00+08:00".format(day, hour)
    }

    /** 把模型返回的 event_time 统一成 ISO；认不出返回 null 交给兜底。 */
    private fun normalizeEventTime(raw: String, base: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.equals("null", true) || s == "未知" || s == "未定") return null
        val m = Regex("(20\\d{2})[-/.年](\\d{1,2})[-/.月](\\d{1,2})").find(s)
        if (m != null) {
            val day = "%04d-%02d-%02d".format(
                m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            val hm = Regex("(\\d{1,2})[:：](\\d{1,2})").find(s)
            val h = hm?.groupValues?.get(1)?.toIntOrNull()
            return if (h != null && h in 0..23)
                "%sT%02d:%02d:00+08:00".format(day, h, hm.groupValues[2].toIntOrNull() ?: 0)
            else "${day}T00:00:00+08:00"
        }
        return parseDate(s, base)
    }

    /**
     * 卡片 extensions：只放「视图分组要用的粗粒度槽位」。
     * quadrant 由 domain 粗映射（Human 3.0 的精确象限由成长分析给出，不在单卡上假装算准）；
     * topic 取命中的最相关关键词。精确结论一律在 cls 快照里，不在 extensions 里重复。
     */
    private fun cardExt(cls: JSONObject, mode: String): JSONObject {
        val ext = JSONObject()
        val domId = cls.optJSONObject("domain")?.optString("id").orEmpty()
        val kw = cls.optJSONArray("keywords")?.optString(0).orEmpty()
        if (mode == "study") ext.put("topic", kw.ifBlank { "未分类" })
        if (mode == "growth") ext.put("quadrant", when (domId) {
            "work", "money" -> "Vocation"
            "health" -> "Body"
            "relation" -> "Spirit"
            else -> "Mind"
        })
        return ext
    }

    // ---------- 流水线 ----------
    data class Input(
        val source: String, val mode: String, val text: String? = null,
        val audioBytes: ByteArray? = null, val filename: String? = null,
        /** 已在本机的音频文件（原生选择器给的路径）。走这条就不经过 base64，
         *  35 分钟录音也不会在 JS 侧 / 桥接层撑爆堆。 */
        val audioFile: File? = null,
        val captureSession: String = "live", val isDemoSample: Boolean = false,
        /** 录制开始时刻（ISO）。整条流水线的「当日」基准都用它，而不是跑完流水线的时刻。 */
        val startedAt: String? = null,
        /** 进度回调（阶段名, 0-100）。长录音转写要几分钟，前端靠它显示进展。 */
        val progress: ((String, Int) -> Unit)? = null,
        /** 跳过段级 AI 分析。常驻录音积压时用它降级：先把转写和卡片落库，
         *  分析交给会话结束后按天补一次 —— 卡不会少，只是晚一点。 */
        val skipAnalysis: Boolean = false)

    fun run(input: Input): JSONObject {
        val src = nid("src"); val stages = JSONArray(); val adaptersUsed = JSONObject()
        // 这一次收录的时间锚点：优先用录音真正开始的时刻，没有就用现在。
        // 后面的事件兜底日期、提示词里的「现在几点」全用它，保证同一段录音口径一致。
        val recAt = input.startedAt?.takeIf { it.isNotBlank() } ?: now()
        val recDay = recAt.take(10)
        val recLabel = humanTime(recAt)
        // 文件名用：20261107-173605
        val stamp = recAt.take(19).replace("-", "").replace(":", "").replace("T", "-")
        fun stage(name: String, adapter: String, detail: JSONObject) {
            stages.put(JSONObject().put("stage", name).put("adapter", adapter).put("detail", detail))
            db.logStage(src, input.mode, name, adapter, detail)
            adaptersUsed.put(name, adapter)
            // pct 传 -1：只换阶段名、不动百分比。转写之后的「条目切分 / AI 分析」没有
            // 可数的进度，但任务条上的字应该跟着走，否则会一直停在「转写完成 7/7 段」。
            input.progress?.invoke(name, -1)
        }

        var fileRef = "text://$src"
        val progress = input.progress
        var chunks: List<AudioChunker.Chunk> = emptyList()
        var splitInfo: AudioChunker.Result? = null
        var stored: File? = null
        val chunkDir = File(ctx.cacheDir, "chunks/$src")
        if (input.audioFile != null || input.audioBytes != null) {
            val dir = File(ctx.filesDir, "l1"); dir.mkdirs()
            // 文件名本身带录制时刻：20261107-173605_rec_xxx.wav
            // 好处是拷出去、分享出去、脱离数据库也一眼知道这是什么时候录的
            val rawName = (input.filename ?: "$src.bin").substringAfterLast('/')
            var f = File(dir, if (rawName.startsWith(stamp)) rawName else "${stamp}_$rawName")
            // 同一秒传了同名文件就再加一层随机 id，绝不覆盖已有存证
            if (f.exists()) f = File(dir, "${stamp}_${src}_$rawName")
            var wrote = 0L
            try {
                val from = input.audioFile
                if (from != null && from.isFile && from.canonicalPath != f.canonicalPath) {
                    from.copyTo(f, overwrite = true)      // 流式复制，不把整段音频读进堆
                    wrote = f.length()
                } else if (from == null && input.audioBytes != null) {
                    f.writeBytes(input.audioBytes); wrote = input.audioBytes.size.toLong()
                } else if (from != null) wrote = f.length()
            } catch (e: Exception) {
                Log.w(TAG, "音频落盘失败: ${e.message}")
            }
            stored = f
            fileRef = "l1/" + f.name
            progress?.invoke("解码与切分", 4)
            // 边解码边切段：全程只驻留一个 20ms 窗，录音多长都不会把堆撑爆。
            // 切点优先落在静音处，尽量不把一句话切成两半。
            val sp = AudioChunker.split(f, chunkDir) { doneN, totN ->
                // AAC 转码是长录音最耗时的一步（串行时 35 分钟录音要 7~12 分钟，期间进度
                // 一动不动停在 6%，看起来就像卡死）。现在并行跑，每完成一段就报一次。
                progress?.invoke("转码 $doneN/$totN 段（并行）",
                    4 + 8 * doneN / totN.coerceAtLeast(1))
            }
            splitInfo = sp
            chunks = sp.chunks
            db.putL1(fileRef, if (input.captureSession == "rehearsal") null else recAt,
                input.captureSession, if (input.captureSession == "rehearsal") "none" else "file_meta",
                JSONObject().put("bytes", wrote).put("decoded", chunks.isNotEmpty())
                    .put("recorded_at", recAt).put("recorded_label", recLabel)
                    .put("origin_name", rawName)
                    .put("duration_sec", sp.durationMs / 1000).put("chunks", chunks.size))
            stage("收录", "inbox", JSONObject().put("file", fileRef)
                .put("bytes", wrote).put("decoded", chunks.isNotEmpty())
                .put("duration_sec", sp.durationMs / 1000)
                .put("recorded_at", recAt).put("recorded_label", recLabel))
            // 单段录音不额外刷一条「切分」阶段，免得日志里到处是 1 段
            if (chunks.size > 1) stage("智能切分", "builtin:stream-split", JSONObject()
                .put("duration_sec", sp.durationMs / 1000)
                .put("chunks", chunks.size)
                .put("chunk_sec", JSONArray(chunks.map { it.durMs / 1000 }))
                .put("chunk_speech_sec", JSONArray(chunks.map { it.speechMs / 1000 }))
                .put("policy", "超过 ${AudioChunker.LONG_LIMIT_MS / 60000} 分钟才切段（等长均分，切点取静音处），每段不超过 ${AudioChunker.HARD_MAX_MS / 60000} 分钟；≤${AudioChunker.LONG_LIMIT_MS / 60000} 分钟整段不切")
                .put("memory", "流式解码：峰值只驻留一个 20ms 窗，与录音长度无关")
                .put("parallel", "转码 2 路 + 转写 3 路并发，结果按段号顺序归并"))
        } else {
            stage("收录", "text-channel", JSONObject().put("file", fileRef)
                .put("recorded_at", recAt).put("recorded_label", recLabel))
        }

        // VAD 由切分器顺带算好（每段内部相对时刻），这里只做汇总，不再整段解码
        val segs = chunks.flatMap { it.vad }
        stage("VAD 切段", "builtin", JSONObject().put("segments", segs.size)
            .put("note", "无效片段丢弃、不落盘"))

        // ASR：按段转写。段内优先「音频直投多模态大模型」，失败回退本地离线 sherpa，再回退云 ASR。
        // 分段不只是为了内存：模型一次只吃一段（≤18 分钟），转写漏字和抽卡跑偏都明显更少。
        val l2 = JSONArray(); var rawEvents = JSONArray()
        var asrAdapter = "builtin:text-channel"; var isMock = true
        var asrStatus = "not_applicable"; var asrFailure = ""
        var omniUsed = 0; var sherpaUsed = 0; var cloudUsed = 0; var silentChunks = 0
        var usedOmni = false
        val summaries = ArrayList<String>()
        if (stored != null) {
            asrStatus = "empty_or_failed"
            val total = chunks.size.coerceAtLeast(1)
            /** 单段转写的产物。并发跑完再按段号归并，不在这里动 l2/summaries。 */
            class SegOut(val text: String, val evs: JSONArray?, val summary: String, val via: String)
            val doneCnt = java.util.concurrent.atomic.AtomicInteger(0)
            // 本地 sherpa 会吃满 CPU，并发只会互相拖慢（还更容易 OOM）→ 全局只放 1 路；
            // 云端 omni 是纯 IO 等待，3 路并发才真正省时间。
            val sherpaGate = java.util.concurrent.Semaphore(1)
            val pool = java.util.concurrent.Executors.newFixedThreadPool(3) { r ->
                Thread(r, "trace-asr").apply { isDaemon = true }
            }
            val futures = chunks.map { c ->
                pool.submit(java.util.concurrent.Callable<SegOut> {
                    // 只有「整段两三分钟都没检测到任何语音」才跳过 —— 阈值定高了会静悄悄吞掉小声说话，
                    // 那比多花一次调用糟得多
                    if (c.speechMs <= 0L && c.durMs >= 120_000L) {
                        return@Callable SegOut("", null, "", "silent")
                    }
                    val label = humanTime(isoPlus(recAt, c.startMs))
                    var text = ""; var evs: JSONArray? = null
                    var sm = ""; var via = "none"
                    if (apiKey.isNotBlank()) {
                        var v = omniProcess(c.audio, c.mime, label)
                        // AAC 转码在个别机型上会出坏文件，退回原始 WAV 再试一次（大但一定能读）
                        if (v == null && c.m4a != null) v = omniProcess(c.wav, "audio/wav", label)
                        if (v != null) {
                            text = v.optString("transcript").trim()
                            evs = v.optJSONArray("events")
                            sm = v.optString("summary").trim()
                            if (text.isNotBlank()) via = "omni"
                        }
                    }
                    if (text.isBlank() && hasSherpaAar() && hasOnnxAssets()) {
                        // 单段 ≤6 分钟 → PCM ≤12MB，本地 ASR 这条兜底路径也不会 OOM
                        sherpaGate.acquire()
                        try {
                            val pcm = AudioUtil.readWav16k(c.wav)
                            val t = if (pcm != null) sherpaLong(pcm) else null
                            if (!t.isNullOrBlank()) { text = t.trim(); via = "sherpa" }
                        } finally { sherpaGate.release() }
                    }
                    if (text.isBlank() && asrEndpoint.isNotBlank()) {
                        val t = cloudTranscribe(c.audio)
                        if (!t.isNullOrBlank()) { text = t.trim(); via = "cloud" }
                    }
                    // 进度按「已完成段数」走，不按段号：并发下完成顺序本来就乱，
                    // 按段号报会出现 3/7 之后跳回 2/7 的鬼畜现象。
                    val d = doneCnt.incrementAndGet()
                    progress?.invoke(
                        when (via) {
                            "omni" -> "转写完成 $d/$total 段"
                            "sherpa" -> "本地转写 $d/$total 段"
                            "cloud" -> "云端转写 $d/$total 段"
                            else -> "处理 $d/$total 段"
                        }, 10 + 60 * d / total)
                    SegOut(text, evs, sm, via)
                })
            }
            // 单段抛异常不能让整条流水线崩：当成「这段没转出来」，其余段照常合并
            val outs = futures.map { fu ->
                try { fu.get() } catch (e: Exception) {
                    Log.w(TAG, "分段转写异常: ${e.message}")
                    SegOut("", null, "", "none")
                }
            }
            pool.shutdown()

            // 按段号顺序归并 —— 时间轴、summaries、chunk_summary 全都依赖这个顺序，
            // 绝不能按完成顺序拼，否则知识库里的句子会前后颠倒。
            chunks.forEachIndexed { ci, c ->
                val o = outs[ci]
                when (o.via) {
                    "silent" -> silentChunks++
                    "omni" -> { omniUsed++; usedOmni = true }
                    "sherpa" -> sherpaUsed++
                    "cloud" -> cloudUsed++
                }
                if (o.summary.isNotBlank())
                    summaries.add(if (total > 1) "[第 ${c.index + 1} 段] ${o.summary}" else o.summary)
                if (o.text.isBlank()) return@forEachIndexed
                val text = o.text
                // 段内按句切，时间在段内等比铺开：模型不给逐句时间戳，
                // 按字符比例折算比全部写 0 诚实得多（详情页能跳回对应位置）
                val sents = text.split(Regex("(?<=[。！？!?])|[\\n；;]+"))
                    .map { it.trim() }.filter { it.isNotEmpty() }
                val each = if (sents.isEmpty()) 0L else c.durMs / sents.size
                sents.forEachIndexed { i, line ->
                    l2.put(JSONObject().put("start_ms", c.startMs + i * each)
                        .put("end_ms", c.startMs + (i + 1) * each)
                        .put("quote", line).put("speaker", "self").put("chunk", c.index))
                }
                val evs = o.evs ?: return@forEachIndexed
                for (i in 0 until evs.length()) {
                    val e = evs.optJSONObject(i) ?: continue
                    val q = e.optString("quote")
                    // 事件在段内的位置：按引文在转写里的字符偏移折算
                    val off = if (q.isBlank() || text.isEmpty()) 0L
                        else text.indexOf(q).coerceAtLeast(0).toLong() * c.durMs / text.length
                    e.put("start_ms", c.startMs + off).put("end_ms", c.startMs + off + 1000L)
                        .put("speaker", e.optString("speaker", "self"))
                        .put("chunk", c.index)
                        .put("chunk_summary", summaries.lastOrNull() ?: "")
                    rawEvents.put(e)
                }
            }
            asrAdapter = when {
                omniUsed > 0 && sherpaUsed == 0 && cloudUsed == 0 -> "qwen3.8-omni-flash:audio-understanding"
                omniUsed > 0 -> "qwen3.8-omni-flash + 本地兜底"
                sherpaUsed > 0 -> "sherpa-onnx:zh-14M (本地兜底)"
                cloudUsed > 0 -> "cloud-asr"
                else -> "builtin:none"
            }
            if (omniUsed + sherpaUsed + cloudUsed > 0) {
                isMock = false
                asrStatus = if (omniUsed == 0) "ok_local" else "ok"
            } else if (!hasSherpaAar() || !hasOnnxAssets()) {
                asrStatus = "unavailable"
            }
            if (asrStatus != "ok" && asrStatus != "ok_local") {
                val base = splitInfo?.error?.takeIf { it.isNotBlank() }
                asrFailure = when {
                    base != null -> base
                    chunks.isEmpty() -> "没有解出任何音频内容（文件可能损坏或不是音频）"
                    silentChunks == chunks.size -> "整段录音都是静音，没有可转写的内容"
                    else -> "在线 omni 与本地 ASR 均未产出文本（详见 logcat TAG=TraceEngine）；aar=${hasSherpaAar()}, 模型=${hasOnnxAssets()}"
                }
                Log.w(TAG, asrFailure)
            }
        }
        if (l2.length() == 0 && !input.text.isNullOrBlank()) {
            // 文本直录通道：显式标 is_mock，绝不伪造转写
            input.text.split(Regex("[。！？!?\\n；;]+")).map { it.trim() }.filter { it.isNotEmpty() }
                .forEachIndexed { i, line ->
                    l2.put(JSONObject().put("start_ms", i * 1000).put("end_ms", (i + 1) * 1000)
                        .put("quote", line).put("speaker", "self").put("is_mock", true))
                }
        }
        val rawTranscript = StringBuilder()
        for (i in 0 until l2.length())
            rawTranscript.append(l2.getJSONObject(i).optString("quote")).append('\n')

        // 顺文本：L2 原文只读不动，另产出展示/分析用的润色文本
        val rawText = rawTranscript.toString().trim()
        val (polished, polishAdapter, correction) = when {
            usedOmni && rawText.isNotEmpty() ->
                // 大模型已在同一次调用里完成断句纠错，不再重复调 LLM
                Triple(rawText, "omni (已断句纠错)", correctionRules(rawText))
            rawText.isNotEmpty() -> polish(rawText)
            else -> Triple("", "builtin", JSONObject())
        }
        val corrSents = correction.optJSONArray("sentences") ?: JSONArray()
        stage("语音转写", asrAdapter, JSONObject().put("l2_segments", l2.length())
            .put("is_mock", isMock).put("asr_status", asrStatus).put("asr_failure", asrFailure)
            .put("polish_adapter", polishAdapter)
            .put("polished_preview", polished.take(160))
            .put("correction_sentences", corrSents.length())
            .put("policy", correction.optString("policy", "L2 原文只读；矫正句另存")))
        if (l2.length() > 0) db.putL2(fileRef, l2)

        // 说话人归属（other 全保留）
        var spkAdapter = "builtin"
        if (apiKey.isNotBlank() && l2.length() > 0) {
            val labels = llmSpeakerLabels(l2)
            if (labels != null && labels.size == l2.length()) {
                for (i in 0 until l2.length()) l2.getJSONObject(i).put("speaker", labels[i])
                spkAdapter = "qwen:per-line-speaker"
            }
        }
        if (spkAdapter == "builtin") db.putL2(fileRef, l2)
        stage("说话人归属", spkAdapter, JSONObject().put("policy", "other 全保留，不丢弃"))

        // 条目化
        // 抽取改吃矫正后的句级文本；L2 原文仍只读，事件卡里另存 evidence_raw 供回溯
        val transcript = if (polished.isNotBlank()) polished else rawTranscript.toString()
        val totalMs = splitInfo?.durationMs ?: 0L
        var extAdapter = "builtin"
        /* 质检三道，任何一道不过就重做语义切分：
           ① 一条都没有；
           ② 只有一条而全文不短 —— 那基本是「这段在讲 XX」的摘要，不是记录；
           ③ 单条平均超过 200 字 / 引文加起来覆盖不到全文 45% —— 只挑了几段，不是切分。 */
        val qLens = (0 until rawEvents.length()).map {
            rawEvents.optJSONObject(it)?.optString("quote")?.length ?: 0
        }
        val avgQ = if (qLens.isEmpty()) 0 else qLens.sum() / qLens.size
        val cover = if (transcript.isEmpty()) 1.0 else qLens.sum().toDouble() / transcript.length
        val tooCoarse = rawEvents.length() == 0 ||
            (rawEvents.length() <= 1 && transcript.length > 100) ||
            avgQ > 200 ||
            (transcript.length > 300 && cover < 0.45)
        var resplit = false
        if (!tooCoarse) {
            // 分段转写时每段已顺带抽卡（含该段的 quote / 段内偏移），不再重复调 LLM
            extAdapter = if (usedOmni) "omni 一次调用（转写+概括+抽卡，按段）" else "qwen:extract"
        } else if (transcript.isNotBlank()) {
            resplit = true
            val notes = if (apiKey.isNotBlank()) splitNotes(transcript, input.mode, recLabel) else null
            if (notes != null && notes.length() > 0) {
                rawEvents = attachOffsets(notes, transcript, totalMs)
                extAdapter = "qwen:split-notes"
            } else {
                // 没 key / 模型失败：本地按句切，也绝不让整段变成一条
                rawEvents = attachOffsets(ruleNotes(transcript), transcript, totalMs)
                extAdapter = "builtin:rule-split"
            }
        }
        stage("条目切分", extAdapter, JSONObject().put("notes", rawEvents.length())
            .put("mode_id", input.mode).put("chars", transcript.length)
            .put("avg_quote_chars", avgQ).put("coverage", Math.round(cover * 100) / 100.0)
            .put("resplit", resplit)
            .put("policy", "一条只讲一件事；一段录音可切出多条、可分属不同类别；quote 必须连续原文"))

        // 成卡
        val saved = JSONArray()
        val rawByCorrected = HashMap<String, String>()
        for (i in 0 until corrSents.length()) {
            val s = corrSents.getJSONObject(i)
            val k = s.optString("corrected")
            if (k.isNotBlank()) rawByCorrected[k] = s.optString("raw", k)
        }
        var spokenCnt = 0
        var ruleCnt = 0
        for (i in 0 until rawEvents.length()) {
            val r = rawEvents.getJSONObject(i)
            val quote = r.optString("quote").ifBlank { r.optString("summary") }
            if (quote.isBlank()) continue
            // 模型在 omni/extract 里已经按同一套枚举给过 type/domain，这里只做校验与合并，
            // 不合法的值一律回落到词表内核，不把模型的自由文本写进枚举字段。
            val cls = buildCls(quote, r.optString("type"), r.optString("domain"))
            if (cls.getJSONObject("type").optString("source") != "qwen:model") ruleCnt++
            val extJson = cardExt(cls, input.mode)
            extJson.put("evidence_raw", rawByCorrected[quote] ?: quote)
            // 时间三级兜底，顺序不能反：
            // ① 模型给的（它已拿到「现在是几点」，能把今天/昨天换算成日期）
            // ② 规则从原文里认（模型降级时也照样有日期）
            // ③ 都没有 → 用录制当日，标 recorded，界面标「估」——不假装是用户说的
            val modelTime = r.optString("event_time", "").trim()
                .takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?.let { normalizeEventTime(it, recAt) }
            val ruleTime = if (modelTime == null) parseDate(quote, recAt) else null
            var evTime = modelTime ?: ruleTime
            var timeSrc = "spoken"
            if (evTime == null) {
                /* 同一次录音切出的多条，不该全都挤在同一个 00:00 —— 星图上会看起来像重复。
                   没口述时间就按它在录音里的位置推一个时刻，仍然标 recorded（界面显示「估」），
                   用户一眼能看出「这几条是同一段录音里的先后顺序」。 */
                val off = r.optInt("start_ms").toLong()
                evTime = if (off > 0) isoPlus(recAt, off) else "${recDay}T00:00:00+08:00"
                timeSrc = "recorded"
            } else spokenCnt++
            extJson.put("time_source", timeSrc)
            // 一段录音切成 N 条：序号与总数写进卡片，详情页据此显示「第 3 条 / 共 12 条」
            extJson.put("note_seq", i + 1).put("note_total", rawEvents.length())
            extJson.put("source_file", fileRef)
            val e = JSONObject()
                .put("id", nid("evt")).put("mode_id", input.mode)
                .put("event_time", evTime).put("time_source", timeSrc)
                .put("recorded_at", if (input.captureSession == "rehearsal") JSONObject.NULL else recAt)
                .put("quote_ref", JSONObject().put("file", fileRef)
                    .put("start_ms", r.optInt("start_ms"))
                    .put("end_ms", r.optInt("end_ms"))
                    .put("chunk", r.optInt("chunk"))
                    .put("quote", quote))
                .put("speaker", r.optString("speaker", "self"))
                .put("source", "user").put("confidence", r.optDouble("confidence", 0.9))
                .put("confirmed", true).put("capture_session", input.captureSession)
                .put("is_mock", isMock).put("is_demo_sample", input.isDemoSample)
                /* summary 是这一条的概括（星图条目卡的标题），不是整段录音的概括。
                   原来最后会落到 summaries.firstOrNull()，那段录音里每条都显示同一句，等于没条目化。 */
                .put("summary", r.optString("summary").ifBlank {
                    r.optString("chunk_summary").takeIf { it.isNotBlank() } ?: quote.take(36) })
                .put("quote", quote)
                .put("type", cls.getJSONObject("type").optString("name", "见闻"))
                .put("cls", cls)
                .put("extensions", extJson)
                .put("l3a_version", 1).put("corrected", false).put("created_at", now())
            db.putEvent(e)
            db.replaceTags(e.getString("id"), clsTags(cls))
            saved.put(e)
        }
        stage("分类标注", "builtin:lexicon", JSONObject()
            .put("events", saved.length()).put("model_typed", saved.length() - ruleCnt)
            .put("rule_typed", ruleCnt)
            .put("taxonomy_version", Classifier.lexiconVersion))
        stage("成卡入库", "TraceDb", JSONObject().put("saved", saved.length())
            .put("recorded_at", recAt).put("recorded_label", recLabel)
            .put("time_spoken", spokenCnt).put("time_estimated", saved.length() - spokenCnt))
        // 摘要缓存：收录完立刻重建一次，之后所有对话都只读这一行，不再扫全表
        val digestLen = if (saved.length() > 0) buildDigest(input.mode).length else 0
        stage("知识库排序整理", "builtin:LIKE", JSONObject().put("count", saved.length())
            .put("digest_chars", digestLen))

        // 8. AI 分析：本次收录 + 模式历史跨时间对比，自动产出，无需用户另问
        //    积压降级时跳过：形状保持不变（前端不用判空），只多一个 deferred 标记，
        //    等 /analysis/day 补上当天汇总。
        val analysis = if (input.skipAnalysis)
            JSONObject().put("mode_id", input.mode).put("granularity", "segment")
                .put("deferred", true).put("adapter", "deferred:backlog")
                .put("is_mock", true).put("event_count", saved.length())
                .put("created_at", now())
        else analyze(input.mode, src, rawTranscript.toString(), polished, saved)
        stage("AI 分析", analysis.optString("adapter"), JSONObject()
            .put("analysis_id", analysis.optString("id"))
            .put("is_mock", analysis.optBoolean("is_mock"))
            .put("sections", JSONObject()
                .put("key_points", analysis.optJSONArray("key_points")?.length() ?: 0)
                .put("patterns", analysis.optJSONArray("patterns")?.length() ?: 0)
                .put("suggestions", analysis.optJSONArray("suggestions")?.length() ?: 0)))
        adaptersUsed.put("analysis", analysis.optString("adapter"))

        // 切出来的临时段只服务于本次转写，原始录音已完整存证，不长期占双份空间
        if (chunks.isNotEmpty()) runCatching { chunkDir.deleteRecursively() }
        return JSONObject().put("ok", true).put("source_id", src).put("file", fileRef)
            .put("duration_sec", (splitInfo?.durationMs ?: 0) / 1000).put("chunks", chunks.size)
            .put("mode_id", input.mode).put("stages", stages)
            .put("adapters", adaptersUsed).put("events", saved)
            .put("analysis", analysis).put("polished_transcript", polished)
            .put("raw_transcript", rawTranscript.toString()).put("correction", correction)
            .put("asr_status", asrStatus).put("asr_failure", asrFailure)
            .put("event_summary", summaries.joinToString(" "))
            .put("stats", db.stats(input.mode))
    }

    // ---------- LLM 三段能力 ----------
    // 抽取 schema 与 omniSchema 共用同一套枚举；少了 domain 会退化成「只剩情绪标签」的旧行为。
    private val eventSchema = JSONObject("""{"type":"object","properties":{"events":{"type":"array","items":{"type":"object","properties":{"event_time":{"type":["string","null"]},"type":{"type":"string","enum":[${TYPE_IDS.keys.joinToString(",") { "\"" + it + "\"" }}]},"domain":{"type":"string","enum":[${DOMAIN_IDS.keys.joinToString(",") { "\"" + it + "\"" }}]},"quote":{"type":"string"},"summary":{"type":"string"},"speaker":{"type":"string"},"keywords":{"type":"array","items":{"type":"string"}}},"required":["type","domain","quote","summary","event_time","speaker","keywords"]}}},"required":["events"]}""")

    // ---------- 条目化（note split） ----------
    // 星图的最小单位必须是「一件事」，不是「一段录音」。
    // 原来只靠 omni 顺带抽的 events：模型经常只回一条「这段在讲 XX」，
    // 那条其实是摘要，点开就是整段全文。所以单开一道切分，并且不信模型的粗结果。
    private val noteSchema = JSONObject("""{"type":"object","properties":{"notes":{"type":"array","items":{"type":"object","properties":{"quote":{"type":"string"},"summary":{"type":"string"},"type":{"type":"string","enum":[${TYPE_IDS.keys.joinToString(",") { "\"" + it + "\"" }}]},"domain":{"type":"string","enum":[${DOMAIN_IDS.keys.joinToString(",") { "\"" + it + "\"" }}]},"event_time":{"type":["string","null"]},"speaker":{"type":"string","enum":["self","other","mixed"]},"keywords":{"type":"array","items":{"type":"string"}}},"required":["quote","summary","type","domain","event_time","speaker","keywords"]}}},"required":["notes"]}""")

    /** 句级切分：把一整段转写切成一条条独立记录。
     *  与 llmExtract 的区别是「必须覆盖全篇 + 一条只讲一件事 + quote 连续原文」，
     *  而不是让模型自由挑几条它觉得重要的事件。 */
    private fun splitNotes(transcript: String, mode: String, nowLabel: String): JSONArray? {
        val text = transcript.trim()
        if (text.isEmpty()) return null
        // 太短的一句话没必要花一次调用，直接按标点切
        if (text.length <= 40) return ruleNotes(text)
        if (text.length > 5000) {
            val out = JSONArray()
            for (b in splitBlocks(text, 5000)) {
                val part = splitNotes(b, mode, nowLabel) ?: continue
                for (i in 0 until part.length()) part.optJSONObject(i)?.let { out.put(it) }
            }
            return if (out.length() > 0) out else null
        }
        val sys = "你是履迹的记录整理器。输入是一段口述的转写全文（已补过标点，按行分段）。" +
            "把它切成一条条独立记录（note），**一条只讲一件事**。模式=$mode。这段录音开始于 $nowLabel（时区 +08:00）。" +
            "硬性规则：" +
            "1) 同一段录音里往往混着不同主题（工作/情绪/人际/开销/身体/念头），必须分别归类，" +
            "不要因为整体印象就把所有条目归成同一个 domain。" +
            "2) quote 必须是从原文**连续复制**的句子，可以 1–3 句，长度 8–120 字；" +
            "不许概括、不许改写、不许把不相邻的句子拼在一起。" +
            "3) **必须覆盖全部原文**：每一句都要落到某一条里；只有纯寒暄、纯语气词可以丢掉，" +
            "丢掉的总量不得超过全文的 15%。宁可多切几条，也不要漏掉半段。" +
            "4) 一句话里前后是两件事（例如「今天开会挨批了，晚上想吃点好的」）必须拆成两条。" +
            "5) summary 用 12–24 个字说清这一条讲了什么，写成人话，不要标签串。" +
            "6) type 只能取 " + TYPE_IDS.entries.joinToString("/") { "${it.key}=${it.value}" } +
            "；domain 只能取 " + DOMAIN_IDS.entries.joinToString("/") { "${it.key}=${it.value}" } + "。" +
            "7) event_time：口述里有日期就换算成 yyyy-MM-dd（「今天/昨天/上周三」都以该录音时间为基准），" +
            "有具体时刻写成 yyyy-MM-ddTHH:mm；完全没提就填 null，不要编。" +
            "8) speaker：self=本人自述，other=他人发言，mixed=混说或不清。" +
            "9) keywords 给 1–3 个可检索的原词。按 JSON Schema 输出。"
        val body = JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content", "请以 JSON 输出：\n$text")))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "notes")
                    .put("strict", true).put("schema", noteSchema)))
        val c = llmContent(body, 90000) ?: return null
        return try {
            val arr = JSONObject(c).optJSONArray("notes")
            if (arr == null || arr.length() == 0) null else arr
        } catch (e: Exception) { null }
    }

    /** 没有模型时的兜底切分：一句一条，长句按逗号再切，过短的相邻句合并。
     *  存在的意义是「一段录音 = 多条记录」这条不依赖网络也成立，
     *  而不是退化成整段一条、点开又是一整篇。 */
    private fun ruleNotes(text: String): JSONArray {
        val sents = ArrayList<String>()
        for (line in text.split('\n')) {
            val sb = StringBuilder()
            for (ch in line) {
                sb.append(ch)
                if (ch == '。' || ch == '！' || ch == '？' || ch == '；' ||
                    ch == '!' || ch == '?' || ch == ';' || ch == '.') {
                    sb.toString().trim().takeIf { it.isNotEmpty() }?.let { sents.add(it) }
                    sb.setLength(0)
                }
            }
            sb.toString().trim().takeIf { it.isNotEmpty() }?.let { sents.add(it) }
        }
        // 长句二次切：优先逗号，实在不行硬切
        val pieces = ArrayList<String>()
        for (s in sents) {
            if (s.length <= 120) { pieces.add(s); continue }
            val sb = StringBuilder()
            for (p in s.split('，', ',', '、', '：', ':')) {
                if (p.isBlank()) continue
                if (sb.length + p.length > 120 && sb.isNotEmpty()) {
                    pieces.add(sb.toString().trim()); sb.setLength(0)
                }
                sb.append(p).append('，')
                while (sb.length > 130) {
                    pieces.add(sb.substring(0, 120)); sb.delete(0, 120)
                }
            }
            sb.toString().trim().trimEnd('，').takeIf { it.isNotBlank() }?.let { pieces.add(it) }
        }
        // 过短的相邻句合并（「嗯。」「对。」这种不该各占一个星点），但不超过 120 字
        val merged = ArrayList<String>()
        for (p in pieces) {
            val last = merged.lastOrNull()
            if (last != null && (last.length < 18 || p.length < 18) && last.length + p.length <= 120) {
                merged[merged.size - 1] = last + p
            } else merged.add(p)
        }
        val out = JSONArray()
        for (p in merged) {
            if (p.isBlank()) continue
            out.put(JSONObject().put("quote", p)
                .put("summary", p.take(40))
                .put("type", JSONObject.NULL)
                .put("domain", JSONObject.NULL)
                .put("event_time", JSONObject.NULL)
                .put("speaker", "self")
                .put("keywords", JSONArray())
                .put("note_via", "rule"))
        }
        return out
    }

    /** 重切后的事件没有段内偏移，用「引文在全文里的字符位置」按总时长折算。
     *  比全部写 0 诚实：详情页的原声按钮至少能跳到大概位置。 */
    private fun attachOffsets(evs: JSONArray, text: String, totalMs: Long): JSONArray {
        if (totalMs <= 0 || text.isEmpty() || evs.length() == 0) return evs
        val out = JSONArray()
        for (i in 0 until evs.length()) {
            val e = evs.optJSONObject(i) ?: continue
            val q = e.optString("quote")
            val idx = if (q.isBlank()) -1 else text.indexOf(q)
            val s = if (idx >= 0) totalMs * idx / text.length else totalMs * i / evs.length()
            val t = if (idx >= 0) totalMs * (idx + q.length.coerceAtLeast(1)) / text.length
            else totalMs * (i + 1) / evs.length()
            if (!e.has("start_ms") || e.optInt("start_ms") == 0)
                e.put("start_ms", s).put("end_ms", maxOf(t, s + 800))
            out.put(e)
        }
        return out
    }

    private fun llmExtract(transcript: String, mode: String, nowLabel: String): JSONArray? {
        // 长转写分块抽取：一次塞 1.5 万字，模型只能吐出前面几条事件
        if (transcript.length > 6000) {
            val out = JSONArray()
            for (b in splitBlocks(transcript, 6000)) {
                val part = llmExtract(b, mode, nowLabel) ?: continue
                for (i in 0 until part.length()) part.optJSONObject(i)?.let { out.put(it) }
            }
            return if (out.length() > 0) out else null
        }
        val body = JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "把转写切成事件，按 JSON 返回。模式=$mode。这段录音开始于 $nowLabel（时区 +08:00）。"
                            + "event_time 规则：口述里有日期就换算成 yyyy-MM-dd（「今天/昨天/前天/上周三」都以该录音时间为基准），"
                            + "有具体时刻写成 yyyy-MM-ddTHH:mm，完全没提时间就填 null（系统会按录制当日补），不要自己编。"
                            + "type 只能取 " + TYPE_IDS.entries.joinToString("/") { "${it.key}=${it.value}" }
                            + "；domain 只能取 " + DOMAIN_IDS.entries.joinToString("/") { "${it.key}=${it.value}" }
                            + "。keywords 给 1–3 个可检索的原词。"))
                .put(JSONObject().put("role", "user").put("content", "请以 JSON 输出：\n$transcript")))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "events").put("strict", true).put("schema", eventSchema)))
        val c = llmContent(body) ?: return null
        return try { JSONObject(c).optJSONArray("events") } catch (e: Exception) { null }
    }

    /** 逐句交给千问判定说话人（用户拍板的方案）：每句单独调用，带前后各一句上下文。 */
    /** 说话人归属：一次批量调用。
     *  原来是**逐句**调一次模型 —— 长录音切段后转写有几百句，等于几百次串行请求，
     *  既慢又贵。现在把带编号的整段文本一次发过去，要一个等长数组回来。 */
    private fun llmSpeakerLabels(l2: JSONArray): List<String>? {
        val n = l2.length()
        // 超过 120 句时一次调用也塞不下，直接按单人自述处理，不假装能分清
        if (n == 0 || n > 120) return null
        val schema = JSONObject("""{"type":"object","properties":{"labels":{"type":"array","items":{"type":"string","enum":["self","other","mixed"]}}},"required":["labels"]}""")
        val ctx = StringBuilder()
        for (i in 0 until n) {
            val q = l2.getJSONObject(i).optString("quote").replace(Regex("\\s+"), " ")
            ctx.append(i + 1).append(". ").append(q.take(120)).append('\n')
        }
        val body = JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "判断每一句是谁说的：self=本人自述，other=他人发言，mixed=混说或不清。" +
                    "只依据文本语义线索（我/她说/老师讲/对方问）与前后文，不要臆测。" +
                    "返回 labels 数组，长度必须等于行数、顺序一一对应。"))
                .put(JSONObject().put("role", "user").put("content", ctx.toString())))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "speaker_labels")
                    .put("strict", true).put("schema", schema)))
        val c = llmContent(body) ?: return null
        val arr = try { JSONObject(c).optJSONArray("labels") } catch (e: Exception) { null } ?: return null
        if (arr.length() != n) return null
        val out = ArrayList<String>(n)
        for (i in 0 until n) {
            val lab = arr.optString(i, "")
            out.add(if (lab == "self" || lab == "other" || lab == "mixed") lab else "self")
        }
        return out
    }

    // ---------- 全库上下文（P0-3/4）：摘要缓存 + 两段式检索 ----------
    private val chatConns = java.util.concurrent.ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelled = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun cancelChat(reqId: String) {
        cancelled.add(reqId)
        try { chatConns[reqId]?.disconnect() } catch (_: Exception) { }
    }

    /** 收录后重建一次，对话时只读一行。原来每次对话都要把最多 5000 行读成 JSONObject。 */
    fun buildDigest(mode: String, total: Int = db.countEvents(mode)): String {
        val evs = db.listEventsBrief(mode, 5000)
        val sb = StringBuilder()
        var n = 0
        for (i in 0 until evs.length()) {
            val e = evs.getJSONObject(i)
            val d = e.optString("event_time").take(10)
            val s = e.optString("summary").ifBlank { e.optString("quote") }
                .replace(Regex("\\s+"), " ").take(40)
            if (s.isBlank()) continue
            // 没口述日期、按录音当天补的，标「估」——模型据此知道这不是用户说的
            val est = if (e.optString("time_source") == "recorded") "（估）" else ""
            val line = "· " + (if (d.isBlank()) "时间未定" else d + est) + " " + e.optString("type") + " " + s
            // 6k 字符 ≈ 4k token；超了折叠更早的记录，但「已读全库」的语义不变
            if (sb.length + line.length > 6000) {
                sb.append("· …（更早的 ").append(maxOf(total - n, 0)).append(" 条已折叠，按需检索）")
                break
            }
            sb.append(line).append('\n'); n++
        }
        val text = sb.toString().trim()
        db.putDigest(mode, text, total)
        return text
    }

    /** 全库摘要（覆盖所有记录）+ 与本次问题最相关的原话（检索命中）。 */
    private fun memoryContext(mode: String, query: String): Triple<String, Int, Int> {
        val total = db.countEvents(mode)
        val cached = db.getDigest(mode)
        var digest = cached?.optString("digest", "") ?: ""
        if (digest.isBlank() || cached?.optInt("count", -1) != total) digest = buildDigest(mode, total)
        val hits = if (query.isBlank()) JSONArray() else db.search(mode, query, 20)
        val sb = StringBuilder()
        sb.append("【全库摘要 · 共 ").append(total).append(" 条，按时间升序】\n").append(digest)
        val rel = StringBuilder()
        for (i in 0 until hits.length()) {
            val e = hits.getJSONObject(i)
            rel.append("[").append(e.optString("event_time").take(10)).append("] ")
                .append(e.optString("quote")).append('\n')
        }
        if (rel.isNotEmpty()) sb.append("\n\n【与本次问题最相关的原话】\n").append(rel)
        return Triple(sb.toString(), total, hits.length())
    }

    private fun chatBody(personaName: String, personaPrompt: String, mode: String,
                         total: Int, ctx: String, query: String): JSONObject {
        val sys = (if (personaPrompt.isNotBlank()) personaPrompt + "\n\n" else "") +
            "现在是 ${humanTime()}（时区 +08:00）。" +
            "你是$personaName，当前模式 $mode，你已读取该模式全部 $total 条记录。" +
            "摘要里带「估」的日期是录音当天推断的（用户没口述日期），按「当天」理解即可，不要当成用户说的。" +
            "像通用 AI 助手一样自然回答；记录只在相关时才用，不要写成检索报告、" +
            "不要逐条复述、不要每句话都提知识库。可自由给建议、提醒、计划、评估，不受输出限制。"
        return JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("max_tokens", 1200)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", sys))
                .put(JSONObject().put("role", "user").put("content",
                    "【我的个人记忆 · 共 $total 条，仅在相关时参考】\n$ctx\n\n【我的问题】$query")))
    }

    fun chat(mode: String, query: String): JSONObject {
        // 同步版：APK 里已改走 chatStream，这个保留给 /chat 非流式调用与兜底
        val (ctx, total, hitCount) = memoryContext(mode, query)
        val out: JSONObject
        if (apiKey.isBlank()) {
            val ans = if (total == 0) "当前 $mode 模式的知识库里还没有任何记录。"
            else "关于「$query」，当前全库共 $total 条记录：\n$ctx"
            out = JSONObject().put("answer", ans).put("agent", "内置助手")
                .put("mode_id", mode).put("evidence_ids", JSONArray()).put("is_mock", true)
                .put("adapter", "builtin")
        } else {
            val (pp, pn) = persona()
            val ans = llmContent(chatBody(pn, pp, mode, total, ctx, query))
            out = if (ans == null) {
                JSONObject().put("answer", "模型调用失败，已降级。").put("is_mock", true)
                    .put("degraded", true).put("adapter", "builtin")
            } else JSONObject().put("answer", ans).put("agent", "基础助手").put("mode_id", mode)
                .put("evidence_ids", JSONArray()).put("is_mock", false).put("adapter", "qwen:agent")
        }
        out.put("context_count", hitCount).put("library_total", total).put("full_library", true)
        return out
    }

    /** 流式对话：边生成边回调，首字 0.5–1.5s。失败自动退回同步一次，保证还能出答案。 */
    fun chatStream(mode: String, query: String, reqId: String, onDelta: (String) -> Unit): JSONObject {
        val (ctx, total, hitCount) = memoryContext(mode, query)
        if (apiKey.isBlank()) return chat(mode, query)
        val (pp, pn) = persona()
        val body = chatBody(pn, pp, mode, total, ctx, query).put("stream", true)
        val acc = StringBuilder()
        var ok = false
        var err = ""
        var wasCancelled = false
        var conn: HttpURLConnection? = null
        try {
            conn = openConn()
            conn.readTimeout = 45000
            conn.setRequestProperty("Accept", "text/event-stream")
            chatConns[reqId] = conn
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val raw = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                err = humanizeApiError(raw, code)
            } else {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { br ->
                    while (!cancelled.contains(reqId)) {
                        val line = br.readLine() ?: break
                        if (!line.startsWith("data:")) continue
                        val d = line.substring(5).trim()
                        if (d == "[DONE]") break
                        val piece = try {
                            JSONObject(d).optJSONArray("choices")?.optJSONObject(0)
                                ?.optJSONObject("delta")?.optString("content", "") ?: ""
                        } catch (e: Exception) { "" }
                        if (piece.isNotEmpty()) { acc.append(piece); onDelta(piece) }
                    }
                }
                ok = true
            }
        } catch (e: Throwable) {
            err = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            chatConns.remove(reqId)
            wasCancelled = cancelled.remove(reqId)
            try { conn?.disconnect() } catch (_: Exception) { }
            if (wasCancelled && acc.isEmpty()) err = "已停止生成"
        }
        if (wasCancelled) {
            // 用户主动停止：不再兜底重发一次，否则「停止」会变成「再问一遍」
            return JSONObject().put("ok", acc.isNotEmpty()).put("answer", acc.toString())
                .put("adapter", "qwen:agent（已停止）").put("is_mock", false).put("stopped", true)
                .put("context_count", hitCount).put("library_total", total)
        }
        if (!ok || acc.isEmpty()) {
            // 流式失败（网关不支持 / 断网 / 中途掐断）→ 同步再试一次，别让用户看到空白
            body.remove("stream")
            val ans = llmContent(body)
            if (!ans.isNullOrBlank()) {
                onDelta(ans)
                return JSONObject().put("ok", true).put("answer", ans).put("agent", pn)
                    .put("mode_id", mode).put("adapter", "qwen:agent（非流式兜底）")
                    .put("is_mock", false).put("context_count", hitCount)
                    .put("library_total", total).put("full_library", true)
                    .put("stream_error", err.take(160))
            }
            return JSONObject().put("ok", false)
                .put("error", if (err.isNotBlank()) err else lastLlmError.ifBlank { "模型未返回内容" })
                .put("answer", "").put("is_mock", true).put("degraded", true)
                .put("adapter", "builtin").put("context_count", hitCount).put("library_total", total)
        }
        return JSONObject().put("ok", true).put("answer", acc.toString()).put("agent", pn)
            .put("mode_id", mode).put("adapter", "qwen:agent（流式）").put("is_mock", false)
            .put("context_count", hitCount).put("library_total", total)
            .put("full_library", true).put("stream", true)
    }

    // ---------- 分析：四种模式各一套 schema，日汇总单独一套 ----------
    // 共用字段保持稳定（summary/key_points/patterns/suggestions/reminders），模式专属字段各自定义。
    // 前端可以只认共用字段，也可以按 mode 渲染专有块 —— 结构不会因为加模式而变。
    private val A_COMMON = """"summary":{"type":"string"},
        "key_points":{"type":"array","items":{"type":"string"}},
        "patterns":{"type":"array","items":{"type":"string"}},
        "suggestions":{"type":"array","items":{"type":"string"}},
        "reminders":{"type":"array","items":{"type":"string"}}"""

    private val A_EXTRA = mapOf(
        "life" to """"emotion_trajectory":{"type":"array","items":{"type":"object","properties":{
            "time":{"type":"string"},"emotion":{"type":"string"},"intensity":{"type":"number"},
            "trigger":{"type":"string"}},"required":["time","emotion","intensity","trigger"]}},
          "recurring_theme":{"type":"array","items":{"type":"string"}},
          "say_do_gap":{"type":"array","items":{"type":"string"}},
          "body_signals":{"type":"array","items":{"type":"string"}}""",
        "study" to """"topics":{"type":"array","items":{"type":"object","properties":{
            "topic":{"type":"string"},"mastery":{"type":"number"},"evidence":{"type":"string"}},
            "required":["topic","mastery","evidence"]}},
          "weak_points":{"type":"array","items":{"type":"string"}},
          "drills":{"type":"array","items":{"type":"object","properties":{
            "task":{"type":"string"},"why":{"type":"string"},"minutes":{"type":"number"}},
            "required":["task","why","minutes"]}},
          "next_focus":{"type":"string"}""",
        "relation" to """"people":{"type":"array","items":{"type":"object","properties":{
            "person":{"type":"string"},"signal":{"type":"string"},
            "unspoken":{"type":"string"},"next_action":{"type":"string"}},
            "required":["person","signal","unspoken","next_action"]}},
          "interaction_patterns":{"type":"array","items":{"type":"string"}},
          "boundaries":{"type":"array","items":{"type":"string"}}""",
        "growth" to """"quadrants":{"type":"array","items":{"type":"object","properties":{
            "quadrant":{"type":"string","enum":["Mind","Body","Spirit","Vocation"]},
            "stage":{"type":"string"},"evidence":{"type":"string"}},
            "required":["quadrant","stage","evidence"]}},
          "false_transformation":{"type":"array","items":{"type":"string"}},
          "leverage":{"type":"array","items":{"type":"string"}},
          "identity_shift":{"type":"string"}"""
    )
    private val A_EXTRA_KEYS = mapOf(
        "life" to listOf("emotion_trajectory", "recurring_theme", "say_do_gap", "body_signals"),
        "study" to listOf("topics", "weak_points", "drills", "next_focus"),
        "relation" to listOf("people", "interaction_patterns", "boundaries"),
        "growth" to listOf("quadrants", "false_transformation", "leverage", "identity_shift"))

    // 流水线跑在 4 个线程的池子里，两段录音可能同时进来 —— 缓存必须并发安全
    private val schemaCache = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()

    /** 每个模式一套严格 schema。strict 要求所有 properties 都出现在 required，这里自动拼。 */
    private fun analysisSchema(mode: String): JSONObject = schemaCache.getOrPut(mode) {
        val m = if (A_EXTRA.containsKey(mode)) mode else "life"
        val req = (listOf("summary", "key_points", "patterns", "suggestions", "reminders")
                + (A_EXTRA_KEYS[m] ?: emptyList()))
            .joinToString(",") { "\"$it\"" }
        JSONObject("""{"type":"object","properties":{$A_COMMON,${A_EXTRA[m]}},"required":[$req]}""")
    }

    /** 日汇总：把一天切成一条时间线，再给一句「明天只做一件事」。 */
    private val daySchema = JSONObject("""{"type":"object","properties":{
        "summary":{"type":"string"},
        "timeline":{"type":"array","items":{"type":"object","properties":{
            "time":{"type":"string"},"what":{"type":"string"},
            "emotion":{"type":"string"},"domain":{"type":"string"}},
            "required":["time","what","emotion","domain"]}},
        "highlights":{"type":"array","items":{"type":"string"}},
        "emotion_arc":{"type":"string"},
        "vs_last_time":{"type":"array","items":{"type":"string"}},
        "open_loops":{"type":"array","items":{"type":"string"}},
        "one_thing_tomorrow":{"type":"string"}},
        "required":["summary","timeline","highlights","emotion_arc","vs_last_time","open_loops","one_thing_tomorrow"]}""")

    private fun polishRules(raw: String): String {
        val t = raw.replace(Regex("[，,]{2,}"), "，")
            .replace(Regex("(嗯|呃|啊|那个|就是说|然后呢)(?=[，。？！\\s]|$)"), "")
        return t.split(Regex("(?<=[。！？!?])|[\\n；;]+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("") { if (it.last() in "。！？!?，,") it else "$it。" }
    }

    // ---------- 句级转写矫正（L2'：原文只读、矫正句另存、禁止增删事实） ----------
    private val correctionSchema = JSONObject("""{"type":"object","properties":{"sentences":{"type":"array","items":{"type":"object","properties":{"raw":{"type":"string"},"corrected":{"type":"string"},"edits":{"type":"array","items":{"type":"string"}},"confidence":{"type":"number"},"needs_review":{"type":"boolean"}},"required":["raw","corrected","edits","confidence","needs_review"]}}},"required":["sentences"]}""")

    private fun correctionRules(raw: String): JSONObject {
        // 零改动：无 key 时只做结构化分句，绝不改写原文
        val arr = JSONArray()
        for (p in raw.split(Regex("(?<=[。！？!?])|[\\n；;]+"))
            .map { it.trim() }.filter { it.isNotEmpty() }) {
            arr.put(JSONObject().put("raw", p).put("corrected", p)
                .put("edits", JSONArray()).put("confidence", 0.6)
                .put("needs_review", false))
        }
        return JSONObject().put("sentences", arr).put("full_text", raw)
            .put("adapter", "builtin:rules").put("is_mock", true)
            .put("policy", "原文只读；L2 不回写；矫正句另存；禁止增删事实")
    }

    /** 按句子边界把长文本切成 ≤max 字的块。
     *  为什么需要：转写和矫正都是一次请求把全文塞给模型，
     *  35 分钟的转写 1.5 万字，输出会被 max_tokens 截断成半截 JSON，整个流程白跑。 */
    private fun splitBlocks(text: String, max: Int): List<String> {
        if (text.length <= max) return listOf(text)
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (sent in text.split(Regex("(?<=[。！？!?])|[\\n；;]+"))) {
            if (sent.isEmpty()) continue
            if (sb.length + sent.length > max && sb.isNotEmpty()) {
                out.add(sb.toString()); sb.setLength(0)
            }
            sb.append(sent)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return if (out.isEmpty()) listOf(text) else out
    }

    private fun correctTranscript(raw: String): JSONObject {
        if (raw.isBlank()) return JSONObject().put("sentences", JSONArray())
            .put("full_text", "").put("adapter", "builtin").put("is_mock", true)
        if (apiKey.isBlank()) return correctionRules(raw)
        // 长转写分块矫正：单次请求超过 6000 字，输出会被截断
        if (raw.length > 6000) {
            val sents = JSONArray(); val sb = StringBuilder()
            var mock = false; val ads = LinkedHashSet<String>()
            for (b in splitBlocks(raw, 6000)) {
                val r = correctTranscript(b)       // 块内不会超过阈值，递归一层即止
                r.optJSONArray("sentences")?.let { a ->
                    for (i in 0 until a.length()) a.optJSONObject(i)?.let { sents.put(it) }
                }
                sb.append(r.optString("full_text", b))
                mock = mock || r.optBoolean("is_mock", false)
                ads.add(r.optString("adapter", "builtin"))
            }
            return JSONObject().put("sentences", sents).put("full_text", sb.toString())
                .put("adapter", ads.joinToString("+") + "（分块）").put("is_mock", mock)
                .put("policy", "原文只读；L2 不回写；矫正句另存；禁止增删事实")
        }
        val body = JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "你是语音转写的句级矫正器。输入是 ASR 原文（可能没有断句、有同音错字、夹带口语赘词）。请按语义切句，逐句输出 JSON。" +
                    "硬性规则：1) raw 必须逐字照抄该句原文，一个字都不许改。" +
                    "2) corrected 只许补标点、去纯语气赘词（嗯/呃/啊/那个/就是说/然后呢）、去连续重复、修明显同音错字。" +
                    "3) 严禁改写语义、严禁添加原文没有的人名/地点/数字/事实、严禁翻译、严禁把不确定写成笃定。" +
                    "4) 拿不准就不要改，把 confidence 调低并置 needs_review=true。" +
                    "5) edits 用短语列出改了什么；没改就空数组。6) 不要合并或凭空拆出新句。"))
                .put(JSONObject().put("role", "user").put("content", raw)))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "correction")
                    .put("strict", true).put("schema", correctionSchema)))
        val c = llmContent(body)
        if (!c.isNullOrBlank()) try {
            val arr = JSONObject(c).optJSONArray("sentences")
            if (arr != null && arr.length() > 0) {
                val clean = JSONArray()
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    val r = s.optString("raw", "")
                    val k = s.optString("corrected", "")
                    if (r.isEmpty() && k.isEmpty()) continue
                    val conf = s.optDouble("confidence", 0.8).coerceIn(0.0, 1.0)
                    val edits = JSONArray()
                    val ea = s.optJSONArray("edits")
                    if (ea != null) for (j in 0 until minOf(ea.length(), 6)) edits.put(ea.optString(j))
                    clean.put(JSONObject().put("raw", if (r.isEmpty()) k else r)
                        .put("corrected", if (k.isEmpty()) r else k)
                        .put("edits", edits).put("confidence", conf)
                        .put("needs_review", s.optBoolean("needs_review", false) || conf < 0.7))
                }
                if (clean.length() > 0) {
                    val full = StringBuilder()
                    for (i in 0 until clean.length()) {
                        if (i > 0) full.append('\n')
                        full.append(clean.getJSONObject(i).optString("corrected"))
                    }
                    return JSONObject().put("sentences", clean)
                        .put("full_text", full.toString())
                        .put("adapter", "qwen3.8-max:correction").put("is_mock", false)
                        .put("policy", "原文只读；L2 不回写；矫正句另存；禁止增删事实")
                }
            }
        } catch (ignored: Exception) { }
        return correctionRules(raw)
    }

    private fun polish(raw: String): Triple<String, String, JSONObject> {
        val d = correctTranscript(raw)
        return Triple(d.optString("full_text", raw), d.optString("adapter", "builtin"), d)
    }

    private fun modePrompt(mode: String) = when (mode) {
        "study" -> "你在分析一次上课/家教/请教的录音。关注知识点、掌握程度、薄弱环节、下一步该练什么。" +
                " topics[].mastery 用 0–100 表示这次录音里能看出的掌握程度（说不清就给 50，别装确定）；" +
                " drills 要能在 30 分钟内做完。"
        "relation" -> "你在分析一段关系里的对话。关注互动模式、双方表达、被忽略的信号、值得提醒的事。" +
                " people[].person 只写称呼（如「我妈」「张老师」），没提到具体人时用「对方」；" +
                " unspoken 写你从语气/回避里读到的、对方没说出口的部分，并说明依据。"
        "growth" -> "你用 Human 3.0 式视角分析成长记录：四象限（Mind/Body/Spirit/Vocation）、所处层级、" +
                "False Transformation（词汇变了但日子没变）。" +
                " false_transformation 必须给出「说了什么 vs 实际做了什么」的具体对照，没有就返回空字符串说明。"
        else -> "你在复盘一个人的真实经历记录。关注情绪轨迹、反复出现的主题、说过的话与做过的事之间的差距。" +
                " emotion_trajectory 按时间顺序给，intensity 用 1–9；trigger 写触发了什么，不要写「情绪波动」这种空话。"
    }

    /** 把事件卡渲染成「日期 · 类型/情绪 · 原话」，给模型当跨时间对比材料。 */
    private fun renderEvents(arr: JSONArray, limit: Int, newestFirst: Boolean = true,
                             skip: Set<String> = emptySet()): String {
        val sb = StringBuilder()
        var n = 0
        val idx = if (newestFirst) (arr.length() - 1 downTo 0) else (0 until arr.length())
        for (i in idx) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("id") in skip) continue
            val cls = e.optJSONObject("cls")
            val t = cls?.optJSONObject("type")?.optString("name").orEmpty()
            val emo = cls?.optJSONObject("emotion")?.optString("l2_name").orEmpty()
            val tag = listOf(t, emo).filter { it.isNotBlank() }.joinToString("/")
            sb.append("[").append(e.optString("event_time").take(10))
            if (tag.isNotBlank()) sb.append(' ').append(tag)
            sb.append("] ").append(e.optString("quote").take(140)).append('\n')
            if (++n >= limit) break
        }
        return sb.toString()
    }

    /**
     * 规则版分析：不联网也要给出「真的算出来的东西」。
     * 情绪轨迹、类型分布、主题频次都从 cls 快照聚合，不是占位文案；
     * 只有建议类内容才明说「配 key 后由模型生成」。
     */
    private fun analysisRules(mode: String, events: JSONArray, history: JSONArray): JSONObject {
        val kp = JSONArray()
        val domCount = LinkedHashMap<String, Int>()
        val typeCount = LinkedHashMap<String, Int>()
        val emoCount = LinkedHashMap<String, Int>()
        val traj = JSONArray()
        val all = history
        for (i in 0 until all.length()) {
            val e = all.optJSONObject(i) ?: continue
            val cls = e.optJSONObject("cls") ?: continue
            val d = cls.optJSONObject("domain")?.optString("name").orEmpty()
            if (d.isNotBlank()) domCount[d] = (domCount[d] ?: 0) + 1
            val t = cls.optJSONObject("type")?.optString("name").orEmpty()
            if (t.isNotBlank()) typeCount[t] = (typeCount[t] ?: 0) + 1
            val em = cls.optJSONObject("emotion") ?: continue
            val l2 = em.optString("l2_name")
            if (l2.isNotBlank()) {
                emoCount[l2] = (emoCount[l2] ?: 0) + 1
                if (traj.length() < 12 && i < events.length()) {
                    traj.put(JSONObject().put("time", e.optString("event_time").take(16).replace("T", " "))
                        .put("emotion", l2)
                        .put("intensity", em.optInt("intensity", 5))
                        .put("trigger", e.optString("quote").take(24)))
                }
            }
        }
        for (i in 0 until minOf(events.length(), 5))
            kp.put("记录到：" + events.getJSONObject(i).optString("quote").take(40))
        val pats = JSONArray()
        fun top(m: Map<String, Int>) = m.entries.maxByOrNull { it.value }
        top(typeCount)?.let { pats.put("累计标记最多的类型是「${it.key}」，共 ${it.value} 条。") }
        top(domCount)?.let { pats.put("涉及的领域里「${it.key}」出现 ${it.value} 次，是这段时间的主线。") }
        top(emoCount)?.let { pats.put("情绪词命中最多的是「${it.key}」，共 ${it.value} 次。") }
        if (pats.length() == 0) pats.put("该模式累计 ${history.length()} 条记录，本次新增 ${events.length()} 条。")
        val out = JSONObject().put("summary", "本次收录 ${events.length()} 张事件卡，规则版分析给出了可算的分布与轨迹。")
            .put("key_points", kp).put("patterns", pats)
            .put("suggestions", JSONArray().put("配置大模型 key 后，这里会给出针对性的行动建议。"))
            .put("reminders", JSONArray().put("当前为规则版分析：分布与轨迹来自本地词表，不是模型推断。"))
        when (mode) {
            "life" -> {
                val themes = JSONArray()
                domCount.entries.sortedByDescending { it.value }.take(3).forEach { themes.put(it.key) }
                out.put("emotion_trajectory", traj).put("recurring_theme", themes)
                    .put("say_do_gap", JSONArray()).put("body_signals", JSONArray())
            }
            "study" -> out.put("topics", JSONArray())
                .put("weak_points", JSONArray())
                .put("drills", JSONArray())
                .put("next_focus", "先补最近一次卡住的那一步。")
            "relation" -> out.put("people", JSONArray())
                .put("interaction_patterns", JSONArray())
                .put("boundaries", JSONArray())
            "growth" -> out.put("quadrants", JSONArray())
                .put("false_transformation", JSONArray())
                .put("leverage", JSONArray())
                .put("identity_shift", "记录还不够，先积累两周再谈身份变化。")
        }
        return out
    }

    private fun analyze(mode: String, sourceId: String, transcript: String,
                        polishedText: String, events: JSONArray): JSONObject {
        val savedIds = HashSet<String>()
        for (i in 0 until events.length()) savedIds.add(events.getJSONObject(i).optString("id"))
        // 历史只看「最近」的：旧实现按 event_time 升序取前 30 条，等于永远在分析最早的记录，
        // 用了越久、越看不到最近的变化。
        val history = db.recentEvents(mode, 60)
        val evidence = JSONArray()
        for (i in 0 until minOf(events.length(), 20)) evidence.put(events.getJSONObject(i).optString("id"))
        val periodKey = (events.optJSONObject(0)?.optString("event_time").orEmpty().take(10))
            .ifBlank { TraceDb.now().take(10) }
        val base = JSONObject().put("id", nid("ana")).put("mode_id", mode)
            .put("granularity", "segment").put("period_key", periodKey)
            .put("source_id", sourceId).put("transcript_polished", polishedText)
            .put("evidence_ids", evidence).put("event_count", events.length())
            .put("history_count", history.length()).put("created_at", now())
        fun merge(extra: JSONObject, mock: Boolean, adapter: String): JSONObject {
            for (k in extra.keys()) base.put(k, extra.get(k))
            base.put("is_mock", mock).put("adapter", adapter)
            db.putAnalysis(base)
            return base
        }
        if (apiKey.isBlank()) return merge(analysisRules(mode, events, history), true, "builtin:rules")
        val hist = renderEvents(history, 40, newestFirst = true, skip = savedIds)
        val evText = StringBuilder()
        for (i in 0 until minOf(events.length(), 20)) {
            val e = events.getJSONObject(i)
            val cls = e.optJSONObject("cls")
            evText.append("- [").append(e.optString("event_time").take(16).replace("T", " "))
                .append("] ").append(cls?.optJSONObject("type")?.optString("name").orEmpty())
                .append(cls?.optJSONObject("emotion")?.optString("l2_name")?.takeIf { it.isNotBlank() }
                    ?.let { "/$it" }.orEmpty())
                .append(" ").append(e.optString("quote")).append('\n')
        }
        val body = JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    (if (persona().first.isNotBlank()) persona().first + "\n\n" else "")
                            + "现在是 ${humanTime()}。录音发生在 ${humanTime(events.optJSONObject(0)?.optString("recorded_at"))}（时区 +08:00）。"
                            + modePrompt(mode) + " 自由分析、自由生成，可以主动给建议、提醒、计划和评估。"
                            + " patterns 要跨时间看重复与变化；若历史里有前后矛盾请指出。"
                            + " 每个数组至少给 2 条具体内容，不要给空数组、不要写套话；确实没有就写「这次没有」。"
                            + " 只依据给定记录，不要编造事实。按 JSON Schema 输出。"))
                .put(JSONObject().put("role", "user").put("content",
                    "本次转写（已顺文本）：\n$polishedText\n\n本次事件卡：\n$evText\n\n最近的历史记录（新的在前，用于跨时间对比）：\n$hist")))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "analysis")
                    .put("strict", true).put("schema", analysisSchema(mode))))
        val c = llmContent(body)
        if (!c.isNullOrBlank()) try {
            return merge(JSONObject(c), false, "qwen3.8-max:analysis")
        } catch (ignored: Exception) { }
        return merge(analysisRules(mode, events, history), true, "builtin:rules")
    }

    /**
     * 日汇总分析：把一天的所有卡片合成一条时间线 + 一句明天只做一件事。
     * 按 period_key（yyyy-MM-dd）缓存，除非 force 或当天卡片数变了才重算 —— 打开日视图不会每次都烧一次调用。
     */
    fun analyzeDay(mode: String, day: String, force: Boolean = false): JSONObject {
        val key = day.ifBlank { TraceDb.now().take(10) }
        val events = db.eventsOfDay(mode, key)
        if (events.length() == 0)
            return JSONObject().put("ok", false).put("error", "这一天（$key）还没有记录")
        val cached = db.findAnalysis(mode, "day", key)
        if (!force && cached != null && cached.optInt("event_count") == events.length())
            return JSONObject().put("ok", true).put("cached", true).put("analysis", cached)

        val invIds = HashSet<String>()
        for (i in 0 until events.length()) invIds.add(events.getJSONObject(i).optString("id"))
        val history = db.recentEvents(mode, 80)
        val base = JSONObject().put("id", nid("ana")).put("mode_id", mode)
            .put("granularity", "day").put("period_key", key)
            .put("source_id", "day:$key").put("event_count", events.length())
            .put("evidence_ids", JSONArray().also { a ->
                for (i in 0 until minOf(events.length(), 40)) a.put(events.getJSONObject(i).optString("id")) })
            .put("created_at", now())
        fun merge(extra: JSONObject, mock: Boolean, adapter: String): JSONObject {
            for (k in extra.keys()) base.put(k, extra.get(k))
            base.put("is_mock", mock).put("adapter", adapter)
            db.putAnalysis(base)
            return JSONObject().put("ok", true).put("cached", false).put("analysis", base)
        }
        val lines = renderEvents(events, 60, newestFirst = false)
        if (apiKey.isBlank()) {
            val r = JSONObject().put("summary", "$key 共 ${events.length()} 条记录。")
                .put("timeline", JSONArray().also { a ->
                    for (i in 0 until minOf(events.length(), 40)) {
                        val e = events.getJSONObject(i)
                        a.put(JSONObject().put("time", e.optString("event_time").take(16).replace("T", " "))
                            .put("what", e.optString("summary").ifBlank { e.optString("quote").take(40) })
                            .put("emotion", e.optJSONObject("cls")?.optJSONObject("emotion")?.optString("l2_name").orEmpty())
                            .put("domain", e.optJSONObject("cls")?.optJSONObject("domain")?.optString("name").orEmpty()))
                    } })
                .put("highlights", JSONArray())
                .put("emotion_arc", "")
                .put("vs_last_time", JSONArray())
                .put("open_loops", JSONArray())
                .put("one_thing_tomorrow", "配置大模型 key 后这里会给出一件事。")
            return merge(r, true, "builtin:rules")
        }
        val body = JSONObject().put("model", llmModel).put("enable_thinking", false)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    (if (persona().first.isNotBlank()) persona().first + "\n\n" else "")
                            + "你要做「$key 这一天」的日复盘（模式=$mode），时区 +08:00。"
                            + modePrompt(mode)
                            + " timeline 按时间顺序，一条记录一行，time 只写 HH:mm；"
                            + " highlights 挑 2–4 件真正重要的事；open_loops 是今天没落地、悬着的事；"
                            + " one_thing_tomorrow 只给一件，要具体到能立刻开始做。"
                            + " 只依据给定记录，不要编造。按 JSON Schema 输出。"))
                .put(JSONObject().put("role", "user").put("content",
                    "这一天的事件卡（按时间顺序）：\n$lines\n\n更早的历史（用于 vs_last_time 对比）：\n"
                            + renderEvents(history, 30, true, invIds))))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "day_review")
                    .put("strict", true).put("schema", daySchema)))
        val c = llmContent(body)
        if (!c.isNullOrBlank()) try {
            return merge(JSONObject(c), false, "qwen3.8-max:day")
        } catch (ignored: Exception) { }
        return JSONObject().put("ok", false).put("error", "模型返回无法解析")
    }

    // ---------- 分类：单条重算 / 批量重算 / 人工修正 ----------
    /** 重算一条事件的分类与标签，不动其它字段。 */
    fun reclassify(eventId: String): JSONObject? {
        val e = db.getEvent(eventId) ?: return null
        val quote = e.optString("quote").ifBlank { e.optString("summary") }
        val old = e.optJSONObject("cls")
        // 已经被人工改过的，重算时保留人工结论，只用规则补空缺
        val cls = buildCls(quote, old?.optJSONObject("type")?.optString("id").orEmpty(),
            old?.optJSONObject("domain")?.optString("id").orEmpty())
        if (old?.optBoolean("corrected") == true) {
            cls.put("corrected", true)
            old.optJSONObject("type")?.let { if (it.optString("source") == "user") cls.put("type", it) }
            old.optJSONObject("domain")?.let { if (it.optString("source") == "user") cls.put("domain", it) }
        }
        db.setEventCls(eventId, cls)
        db.replaceTags(eventId, clsTags(cls))
        db.writableDatabase.execSQL("UPDATE events SET type=? WHERE id=?",
            arrayOf(cls.getJSONObject("type").optString("name", "见闻"), eventId))
        return db.getEvent(eventId)
    }

    /** 批量重算没有分类的老数据。limit 控制单次规模，前端可循环调用。 */
    fun rebuildClassify(mode: String?, limit: Int = 200): JSONObject {
        val list = db.eventsWithoutCls(mode, limit)
        var n = 0
        for (i in 0 until list.length()) {
            try { if (reclassify(list.getJSONObject(i).optString("id")) != null) n++ } catch (_: Exception) { }
        }
        val remain = db.eventsWithoutCls(mode, 1).length()
        return JSONObject().put("done", n).put("remaining", remain)
            .put("taxonomy_version", Classifier.lexiconVersion)
    }

    /** 历史重切：把某段录音已经生成的「一张大卡」退回成转写原文，重新切成细条目。
     *  为什么需要：旧版本一段录音只出一条卡，点开就是整段全文；不重切的话，
     *  用户已经录进去的东西永远停在那个粒度上，改代码也救不回来。 */
    fun resegment(fileRef: String): JSONObject {
        if (fileRef.isBlank()) return JSONObject().put("ok", false).put("error", "需要 file")
        val l2 = db.getL2(fileRef)
        if (l2.length() == 0)
            return JSONObject().put("ok", false).put("error", "这段录音没有转写原文，无法重切")
        val sb = StringBuilder()
        var totalMs = 0L
        for (i in 0 until l2.length()) {
            val s = l2.getJSONObject(i)
            val q = s.optString("quote").trim()
            if (q.isNotBlank()) sb.append(q).append('\n')
            totalMs = maxOf(totalMs, s.optLong("end_ms"))
        }
        val text = sb.toString().trim()
        if (text.isBlank()) return JSONObject().put("ok", false).put("error", "转写为空")

        val old = db.eventsByFile(fileRef)
        var mode = "life"
        var recAt: String? = null
        val del = ArrayList<String>()
        for (i in 0 until old.length()) {
            val e = old.getJSONObject(i)
            // 用户亲手改过的卡片不动，避免把人做过的修正覆盖掉
            if (e.optBoolean("corrected"))
                return JSONObject().put("ok", false)
                    .put("error", "这段录音里有你手动改过的条目，跳过不覆盖")
            if (i == 0) mode = e.optString("mode_id", mode)
            if (recAt == null) recAt = e.optString("recorded_at").takeIf { it.isNotBlank() }
            del.add(e.getString("id"))
        }
        val base = recAt ?: now()
        val notes = (if (apiKey.isNotBlank()) splitNotes(text, mode, humanTime(base)) else null)
            ?: ruleNotes(text)
        val arr = attachOffsets(notes, text, totalMs)

        db.deleteEvents(del)
        val saved = JSONArray()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            val quote = r.optString("quote").ifBlank { r.optString("summary") }
            if (quote.isBlank()) continue
            val cls = buildCls(quote, r.optString("type"), r.optString("domain"))
            val ext = cardExt(cls, mode)
            ext.put("note_seq", i + 1).put("note_total", arr.length())
            ext.put("source_file", fileRef).put("resegmented", true)
            val modelTime = r.optString("event_time", "").trim()
                .takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?.let { normalizeEventTime(it, base) }
            var evTime = modelTime ?: parseDate(quote, base)
            var ts = "spoken"
            if (evTime == null) {
                val off = r.optInt("start_ms").toLong()
                evTime = if (off > 0) isoPlus(base, off) else base.take(10) + "T00:00:00+08:00"
                ts = "recorded"
            }
            ext.put("time_source", ts)
            val e = JSONObject()
                .put("id", nid("evt")).put("mode_id", mode)
                .put("event_time", evTime).put("time_source", ts)
                .put("recorded_at", if (recAt != null) recAt else JSONObject.NULL)
                .put("quote_ref", JSONObject().put("file", fileRef)
                    .put("start_ms", r.optInt("start_ms"))
                    .put("end_ms", r.optInt("end_ms"))
                    .put("quote", quote))
                .put("speaker", r.optString("speaker", "self"))
                .put("source", "user").put("confidence", 0.9)
                .put("confirmed", true).put("capture_session", "app")
                .put("is_mock", apiKey.isBlank()).put("is_demo_sample", false)
                .put("summary", r.optString("summary").ifBlank { quote.take(36) })
                .put("quote", quote)
                .put("type", cls.getJSONObject("type").optString("name", "见闻"))
                .put("cls", cls).put("extensions", ext)
                .put("l3a_version", 1).put("corrected", false).put("created_at", now())
            db.putEvent(e)
            db.replaceTags(e.getString("id"), clsTags(cls))
            saved.put(e)
        }
        if (saved.length() > 0) buildDigest(mode)
        return JSONObject().put("ok", true).put("file", fileRef)
            .put("before", del.size).put("after", saved.length())
            .put("adapter", if (apiKey.isNotBlank()) "qwen:split-notes" else "builtin:rule-split")
            .put("events", saved)
    }

    /** 批量重切：把所有只有「一整段一条」的录音都过一遍。
     *  判断标准很朴素 —— 这段录音的条目数少于 2，就值得重切。 */
    fun resegmentAll(limit: Int = 40): JSONObject {
        val files = db.l1List()
        var done = 0; var before = 0; var after = 0
        val failed = JSONArray()
        for (i in 0 until files.length()) {
            if (done >= limit) break
            val f = files.getJSONObject(i).optString("file")
            if (f.isBlank()) continue
            val evs = db.eventsByFile(f)
            if (evs.length() >= 2) { continue }      // 已经切过了
            if (db.getL2(f).length() == 0) continue
            try {
                val r = resegment(f)
                if (r.optBoolean("ok")) {
                    done++
                    before += r.optInt("before")
                    after += r.optInt("after")
                } else failed.put(JSONObject().put("file", f).put("error", r.optString("error")))
            } catch (e: Exception) {
                failed.put(JSONObject().put("file", f).put("error", e.message ?: "重切失败"))
            }
        }
        return JSONObject().put("ok", true).put("files", done)
            .put("before", before).put("after", after).put("errors", failed)
    }

    /** 人工改分类：写 cls 快照 + 标签，标 source=user / corrected=true，之后重算不再覆盖。 */
    fun correctClass(eventId: String, domainId: String?, typeId: String?,
                     emotionL2: String?, keywords: List<String>): JSONObject? {
        val e = db.getEvent(eventId) ?: return null
        val cls = e.optJSONObject("cls") ?: buildCls(e.optString("quote"), "", "")
        if (!domainId.isNullOrBlank()) {
            val id = domainIdOf(domainId) ?: domainId
            cls.put("domain", JSONObject().put("id", id)
                .put("name", DOMAIN_IDS[id] ?: Classifier.displayName(ctx, "domains", id))
                .put("confidence", 1.0).put("source", "user"))
        }
        if (!typeId.isNullOrBlank()) {
            val id = typeIdOf(typeId) ?: typeId
            cls.put("type", JSONObject().put("id", id)
                .put("name", TYPE_IDS[id] ?: Classifier.displayName(ctx, "types", id))
                .put("confidence", 1.0).put("source", "user"))
        }
        if (!emotionL2.isNullOrBlank()) {
            val (l1n, l2n) = Classifier.emotionNames(ctx, emotionL2)
            cls.put("emotion", JSONObject().put("l1", l1n.ifBlank { "" })
                .put("l1_name", l1n).put("l2", emotionL2).put("l2_name", l2n)
                .put("intensity", cls.optJSONObject("emotion")?.optInt("intensity", 5) ?: 5)
                .put("polarity", cls.optJSONObject("emotion")?.optInt("polarity", 0) ?: 0)
                .put("evidence", JSONArray().put("人工标注"))
                .put("adapter", "user"))
        }
        if (keywords.isNotEmpty()) {
            val a = JSONArray(); keywords.take(8).forEach { a.put(it) }
            cls.put("keywords", a)
        }
        cls.put("corrected", true)
        db.setEventCls(eventId, cls)
        db.replaceTags(eventId, clsTags(cls) + keywords.take(6).map {
            JSONObject().put("kind", "keyword").put("value", it).put("name", it)
                .put("confidence", 1.0).put("source", "user")
        })
        db.writableDatabase.execSQL("UPDATE events SET corrected=1, type=? WHERE id=?",
            arrayOf(cls.getJSONObject("type").optString("name", "见闻"), eventId))
        return db.getEvent(eventId)
    }

    // ---------- 可选 ASR 引擎 ----------
    /** 真实离线转写：反射实例化条件编译的 SherpaAsr（AAR 就位时才存在）。
     *  用反射是为了让主源码集不硬依赖 AAR —— 缺 AAR 时工程仍能编译并自动降级。 */
    private var sherpaEngine: Any? = null
    private fun sherpaTranscribe(pcm: AudioUtil.Pcm): String? {
        return try {
            if (!hasSherpaAar()) {
                Log.w(TAG, "offline ASR unavailable: AAR or SherpaAsr class missing")
                null
            } else {
                if (sherpaEngine == null) {
                    val cls = Class.forName("com.trace.app.SherpaAsr")
                    sherpaEngine = cls.getConstructor(Context::class.java).newInstance(ctx)
                    Log.i(TAG, "offline ASR engine created: 24MB zh-14M transducer")
                }
                // MediaCodec 解码结果是 s16 ShortArray，sherpa-onnx 要 FloatArray [-1,1]
                val floats = FloatArray(pcm.samples.size) { pcm.samples[it] / 32768.0f }
                val m = sherpaEngine!!.javaClass.getMethod(
                    "transcribe", FloatArray::class.java, Int::class.javaPrimitiveType)
                val text = m.invoke(sherpaEngine, floats, pcm.rate) as? String
                val out = text?.takeIf { it.isNotBlank() }
                if (out == null) Log.w(TAG, "offline ASR returned empty text (samples=${pcm.samples.size}, rate=${pcm.rate})")
                else Log.i(TAG, "offline ASR ok: ${out.length} chars")
                out
            }
        } catch (e: Throwable) {
            // 现场排障关键：区分「识别为空」与「引擎崩溃 / 模型加载失败」
            Log.e(TAG, "offline ASR failed: ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    // ---------- 音频直投多模态大模型：一次调用完成转写+断句纠错+一句话概括+事件卡 ----------
    // type / domain 都是封闭枚举（严格 schema 要求所有 properties 都在 required 里），
    // 这样模型不会自由发挥出「工作」「职场」这种同义异名，下游统计永远能对齐。
    private val omniSchema = JSONObject("""{"type":"object","properties":{
      "transcript":{"type":"string"},"summary":{"type":"string"},
      "speaker":{"type":"string","enum":["self","other","mixed"]},
      "keywords":{"type":"array","items":{"type":"string"}},
      "events":{"type":"array","items":{"type":"object","properties":{
        "type":{"type":"string","enum":[${TYPE_IDS.keys.joinToString(",") { "\"" + it + "\"" }}]},
        "domain":{"type":"string","enum":[${DOMAIN_IDS.keys.joinToString(",") { "\"" + it + "\"" }}]},
        "quote":{"type":"string"},"summary":{"type":"string"},
        "event_time":{"type":["string","null"]},"speaker":{"type":"string"},
        "keywords":{"type":"array","items":{"type":"string"}}},
        "required":["type","domain","quote","summary","event_time","speaker","keywords"]}}},
      "required":["transcript","summary","speaker","keywords","events"]}""")

    /** 本地离线 ASR 分片跑：14M 模型一次吃 12 分钟会同时撑出 46MB 的 FloatArray，
     *  按 5 分钟切片串行跑，峰值直接降到 1/3。 */
    private fun sherpaLong(pcm: AudioUtil.Pcm, maxSec: Int = 300): String? {
        val step = pcm.rate * maxSec
        if (pcm.samples.size <= step) return sherpaTranscribe(pcm)
        val sb = StringBuilder()
        var i = 0
        while (i < pcm.samples.size) {
            val end = minOf(i + step, pcm.samples.size)
            val t = sherpaTranscribe(AudioUtil.Pcm(pcm.samples.copyOfRange(i, end), pcm.rate))
            if (!t.isNullOrBlank()) sb.append(t.trim()).append('\n')
            i = end
        }
        return sb.toString().trim().ifBlank { null }
    }

    private fun guessAudioMime(name: String): String {
        val e = name.substringAfterLast('.', "").lowercase()
        return when (e) {
            "mp3" -> "audio/mpeg"; "m4a", "mp4" -> "audio/mp4"
            "aac" -> "audio/aac"; "ogg" -> "audio/ogg"; "webm" -> "audio/webm"
            "amr" -> "audio/amr"; "flac" -> "audio/flac"
            else -> "audio/wav"
        }
    }

    /** 流式 POST：请求体分三段写（JSON 前缀 / base64 音频 / JSON 后缀）。
     *  35 分钟录音的 base64 是 40MB 字符串，先 encodeToString 再放进 JSONObject
     *  等于在堆里同时躺三份 —— 这一步是给手机留活路。 */
    /** 每个上传线程最后一次失败的原因。postAudioJson 是同步调用，用 ThreadLocal
     *  并发跑多段时互不干扰。作用只有一个：区分「读超时」和「连接被重置」——
     *  超时说明音频太大模型吃不完，同一份再传一遍纯属白等，直接降级才划算。 */
    private val lastAudioErr = ThreadLocal<String>()

    private fun postAudioJson(url: String, prefix: String, audio: File, suffix: String,
                              timeoutMs: Int): JSONObject? {
        if (apiKey.isBlank()) return null
        lastAudioErr.remove()
        val head = prefix.toByteArray(Charsets.UTF_8)
        val tail = suffix.toByteArray(Charsets.UTF_8)
        val b64Len = 4L * ((audio.length() + 2) / 3)
        var conn: HttpURLConnection? = null
        return try {
            conn = if (url == baseUrl + "/chat/completions") openConn()
            else (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 15000; doOutput = true
            }
            conn.readTimeout = timeoutMs
            conn.setFixedLengthStreamingMode(head.size.toLong() + b64Len + tail.size)
            conn.outputStream.use { out ->
                out.write(head)
                java.io.FileInputStream(audio).use { fis ->
                    // 12288 是 3 的倍数：每块单独编码不带 padding，首尾相接仍是合法 base64
                    val buf = ByteArray(12288)
                    while (true) {
                        var n = 0
                        while (n < buf.size) {
                            val r = fis.read(buf, n, buf.size - n)
                            if (r < 0) break
                            n += r
                        }
                        if (n <= 0) break
                        out.write(android.util.Base64.encodeToString(
                            buf, 0, n, android.util.Base64.NO_WRAP).toByteArray(Charsets.US_ASCII))
                        if (n < buf.size) break
                    }
                }
                out.write(tail)
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                lastAudioErr.set("http $code")
                Log.w(TAG, "音频上传 HTTP $code: ${text.take(200)}")
                null
            } else JSONObject(text)
        } catch (e: Exception) {
            lastAudioErr.set(e.message ?: e.javaClass.simpleName)
            Log.w(TAG, "音频上传失败: ${e.message}")
            try { conn?.disconnect() } catch (_: Exception) { }
            null
        }
    }

    private fun omniProcess(audio: File, mime: String, nowLabel: String): JSONObject? {
        if (apiKey.isBlank()) return null
        if (!audio.isFile || audio.length() <= 0) return null
        val prompt = ("这段录音开始于 $nowLabel（时区 +08:00）。请仔细听这段中文录音。要求："
                + "1) transcript 逐字转写，补标点、按语义断句、"
                + "修明显同音错字、去纯语气赘词，严禁添加原文没有的内容；"
                + "2) summary 用一句话概括这段录音里发生的事（事件概括，不要写成要点列表）；"
                /* events 是星图的节点，不是「这段录音的目录」。
                   不这样写死，模型最常见的输出是 1 条覆盖全篇 —— 点开就是整段全文。 */
                + "3) events 把这段录音切成一条条独立记录：**一条只讲一件事**，"
                + "一段录音里混着不同主题（工作/情绪/人际/开销/身体/念头）必须分别给出不同的 domain，"
                + "一句话里前后是两件事必须拆成两条；"
                + "quote 必须是转写里**连续**的 1–3 句、8–120 字，不许概括、不许拼接不相邻的句子；"
                + "**必须覆盖全部转写内容**，每句话都要落到某一条里，宁可多切也不要只抽前面几条；"
                + "4) events[].event_time：说话人提到「今天/昨天/前天/上周」要按上面的录音时间换算成 "
                + "yyyy-MM-dd；提到具体时刻（如下午三点）写成 yyyy-MM-ddTHH:mm；"
                + "完全没提时间就填 null（会由系统按录制当日补，不要自己猜）；"
                + "5) speaker：self/other/mixed；"
                + "6) events[].type 只能取 " + TYPE_IDS.entries.joinToString("/") { "${it.key}=${it.value}" }
                + "；events[].domain 只能取 " + DOMAIN_IDS.entries.joinToString("/") { "${it.key}=${it.value}" }
                + "。拿不准就用 fact / life，不要自创枚举值；"
                + "7) events[].keywords 给 1–3 个可用于检索的原词（不是泛化词）。按 JSON Schema 输出。")
        val body = JSONObject()
            .put("model", "qwen3.8-omni-flash")
            .put("enable_thinking", false)
            .put("messages", JSONArray().put(JSONObject().put("role", "user")
                .put("content", JSONArray()
                    .put(JSONObject().put("type", "input_audio")
                        .put("input_audio", JSONObject().put("data", "data:$mime;base64,@@AUDIO@@")))
                    .put(JSONObject().put("type", "text").put("text", prompt)))))
            .put("response_format", JSONObject().put("type", "json_schema")
                .put("json_schema", JSONObject().put("name", "asr_result")
                    .put("strict", true).put("schema", omniSchema)))
        val json = body.toString()
        val mark = "@@AUDIO@@"
        val k = json.indexOf(mark)
        if (k < 0) return null
        val prefix = json.substring(0, k)
        val suffix = json.substring(k + mark.length)
        // 大请求体偶发连接重置 → 值得重试一次。
        // 但「读超时」是另一回事：说明这份音频模型在规定时间内吃不完，原样再传一遍
        // 只会把同样的 180 秒再等一次（3 段就是白扔 18 分钟）。超时直接降级本地 ASR。
        for (attempt in 0 until 2) {
            val r = postAudioJson(baseUrl + "/chat/completions", prefix, audio, suffix, 180000)
            if (r == null) {
                val why = lastAudioErr.get() ?: ""
                val timeout = why.contains("timed out", true) || why.contains("timeout", true)
                if (timeout) {
                    Log.w(TAG, "omni 读超时（音频过长模型吃不完），不重试直接降级: $why")
                    break
                }
                continue
            }
            val c = try {
                r.getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
            } catch (e: Exception) { continue }
            for (cand in listOf(c, c.trim().removePrefix("[").removeSuffix("]"))) {
                try {
                    val v = JSONObject(cand)
                    if (v.optString("transcript").isNotBlank()) {
                        Log.i(TAG, "omni one-call ASR ok: ${v.optString("transcript").length} chars")
                        return v
                    }
                } catch (ignored: Exception) { }
            }
        }
        Log.w(TAG, "omni one-call ASR failed after retry, falling back to local ASR")
        return null
    }

    private fun cloudTranscribe(audio: File): String? {
        if (asrEndpoint.isBlank() || apiKey.isBlank()) return null
        if (!audio.isFile || audio.length() <= 0) return null
        val body = JSONObject().put("model", "paraformer-v2")
            .put("input", JSONObject().put("file_url",
                "data:${guessAudioMime(audio.name)};base64,@@AUDIO@@"))
        val json = body.toString()
        val k = json.indexOf("@@AUDIO@@")
        if (k < 0) return null
        val r = postAudioJson(asrEndpoint, json.substring(0, k), audio,
            json.substring(k + "@@AUDIO@@".length), 120000) ?: return null
        return r.optString("text", "").ifBlank { r.optString("result", "") }
    }

    // ---------- 骑砍式模组文件夹（assets/modules） ----------
    fun scanModules(): JSONObject {
        val installed = JSONArray(); val errors = JSONArray()
        val okIds = mutableListOf<String>()
        try {
            val folders = ctx.assets.list("modules") ?: emptyArray()
            for (name in folders) {
                try {
                    val txt = ctx.assets.open("modules/$name/manifest.json")
                        .bufferedReader().use { it.readText() }
                    val m = JSONObject(txt)
                    if (!m.has("id")) m.put("id", name)
                    if (!m.has("name")) m.put("name", name)
                    if (!m.has("version")) m.put("version", "1.0.0")
                    if (!m.has("minCore")) m.put("minCore", "1.0")
                    if (!m.has("repo")) m.put("repo", "folder://modules/$name")
                    m.put("source", "folder")
                    for (f in listOf("prompt.md", "schema.json", "view.json", "README.md")) {
                        try {
                            val t = ctx.assets.open("modules/$name/$f").bufferedReader().use { it.readText() }
                            m.put(f.replace(".", "_"), t.take(20000))
                        } catch (ignored: Exception) { }
                    }
                    if (!m.has("type") || m.getString("type") !in listOf("classifier", "agent", "view"))
                        throw IllegalArgumentException("type 必须是 classifier/agent/view")
                    db.installComponent(m, true)
                    okIds.add(m.getString("id"))
                    installed.put(JSONObject().put("id", m.getString("id"))
                        .put("type", m.getString("type")).put("name", m.getString("name")))
                } catch (e: Exception) {
                    errors.put(JSONObject().put("folder", name).put("error", e.message))
                }
            }
        } catch (e: Exception) { errors.put(JSONObject().put("error", e.message)) }
        val removed = JSONArray()
        val comps = db.listComponents(null)
        for (i in 0 until comps.length()) {
            val c = comps.getJSONObject(i)
            if (c.optJSONObject("manifest")?.optString("source") == "folder" && c.getString("id") !in okIds) {
                db.uninstallComponent(c.getString("id")); removed.put(c.getString("id"))
            }
        }
        return JSONObject().put("root", "assets/modules").put("installed", installed)
            .put("removed", removed).put("errors", errors)
    }

    // ================= ima 拓展（腾讯 ima OpenAPI） =================
    // 官方入口：ima App →「我」→「Claw 配置」→ 复制 Client ID / API Key。
    // ima 只提供「新建笔记 / 追加 / 挂进知识库」，没有改和删，所以这里只做单向增量。

    fun imaConfigured(): Boolean = imaClientId.isNotBlank() && imaApiKey.isNotBlank()

    private fun imaMask(s: String): String =
        if (s.length <= 6) "****" else s.substring(0, 4) + "****" + s.takeLast(2)

    private fun imaCall(path: String, body: JSONObject, timeoutMs: Int = 30000): JSONObject {
        if (!imaConfigured())
            return JSONObject().put("ok", false).put("error", "还没填 ima 的 Client ID / API Key")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("https://ima.qq.com$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("ima-openapi-clientid", imaClientId.trim())
                setRequestProperty("ima-openapi-apikey", imaApiKey.trim())
                setRequestProperty("Connection", "keep-alive")
                connectTimeout = 8000
                readTimeout = timeoutMs
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                JSONObject().put("ok", false)
                    .put("error", "HTTP $code " + text.take(160))
            } else {
                val o = JSONObject(text)
                // 文档里两种外层都见过：{"retcode":0,"errmsg":...} 和 {"code":0,"msg":...}
                val rc = if (o.has("retcode")) o.optInt("retcode") else o.optInt("code", 0)
                if (rc != 0) {
                    val msg = o.optString("errmsg").ifBlank { o.optString("msg") }
                    JSONObject().put("ok", false).put("retcode", rc)
                        .put("error", msg.ifBlank { "retcode=$rc" })
                } else JSONObject().put("ok", true).put("data", o.opt("data") ?: JSONObject())
            }
        } catch (e: Exception) {
            try { conn?.disconnect() } catch (_: Exception) { }
            JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** 防御式解析：官方各版本文档里知识库列表的 key 名不完全一致，递归找带 name+id 的对象。 */
    private fun collectKbs(node: Any?, out: JSONArray, seen: MutableSet<String>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) collectKbs(node.opt(i), out, seen)
            is JSONObject -> {
                val name = node.optString("name").ifBlank { node.optString("title") }
                val id = node.optString("knowledge_base_id").ifBlank {
                    node.optString("kb_id").ifBlank { node.optString("id") }
                }
                if (name.isNotBlank() && id.isNotBlank() && seen.add(id)) {
                    out.put(JSONObject().put("id", id).put("name", name))
                } else {
                    for (k in node.keys()) collectKbs(node.opt(k), out, seen)
                }
            }
        }
    }

    private fun pickId(data: Any?): String {
        when (data) {
            is JSONObject -> {
                for (k in listOf("doc_id", "docId", "note_id", "content_id", "id")) {
                    val v = data.optString(k)
                    if (v.isNotBlank()) return v
                }
                for (k in data.keys()) {
                    val v = pickId(data.opt(k))
                    if (v.isNotBlank()) return v
                }
            }
            is JSONArray -> for (i in 0 until data.length()) {
                val v = pickId(data.opt(i))
                if (v.isNotBlank()) return v
            }
        }
        return ""
    }

    /** 列知识库（同时当作连通性测试：能列出来说明 Client ID / Key 是好的）。 */
    fun imaKbs(): JSONObject {
        val r = imaCall("/openapi/wiki/v1/search_knowledge_base",
            JSONObject().put("query", "").put("cursor", "").put("limit", 50))
        if (!r.optBoolean("ok")) return r
        val arr = JSONArray()
        collectKbs(r.opt("data"), arr, HashSet())
        return JSONObject().put("ok", true).put("kbs", arr).put("count", arr.length())
    }

    /** 连接 = 先验证再落盘。验证失败不覆盖已有凭证，避免手抖把好的覆盖成坏的。 */
    fun imaConnect(cid: String, key: String, kbId: String, kbName: String): JSONObject {
        val oc = imaClientId; val ok0 = imaApiKey
        if (cid.isNotBlank()) imaClientId = cid.trim()
        if (key.isNotBlank()) imaApiKey = key.trim()
        val r = imaKbs()
        if (!r.optBoolean("ok")) {
            imaClientId = oc; imaApiKey = ok0
            return JSONObject().put("ok", false).put("error", r.optString("error", "连接失败"))
        }
        imaKbId = kbId.trim()
        imaKbName = kbName.trim()
        saveConfig()
        return JSONObject().put("ok", true).put("kbs", r.opt("kbs"))
            .put("client_id", imaMask(imaClientId)).put("kb_id", imaKbId).put("kb_name", imaKbName)
    }

    fun imaDisconnect(): JSONObject {
        imaClientId = ""; imaApiKey = ""; imaKbId = ""; imaKbName = ""
        saveConfig()
        return JSONObject().put("ok", true).put("cleared", db.imaClear())
    }

    fun imaState(): JSONObject {
        val st = db.imaStats()
        val pending = imaBuckets(null, "all").values.sumOf { it.size }
        return JSONObject()
            .put("ok", true)
            .put("configured", imaConfigured())
            .put("client_id", if (imaClientId.isBlank()) "" else imaMask(imaClientId))
            .put("api_key", if (imaApiKey.isBlank()) "" else "已保存")
            .put("kb_id", imaKbId).put("kb_name", imaKbName)
            .put("synced", st.optInt("synced")).put("days", st.optInt("days"))
            .put("last_at", st.optString("last_at")).put("pending", pending)
            .put("adapter", if (imaConfigured()) "ima:openapi" else "builtin")
            .put("day_hint", "每天一篇笔记，同日再次同步用追加，不改旧内容")
    }

    // ---- 待同步分桶：按天聚合，一天一个请求，避免几百条记录变几百次 API ----
    private fun dayStr(offset: Int): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DAY_OF_MONTH, offset)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(cal.time)
    }

    private fun hashOf(e: JSONObject): String = Integer.toHexString(
        (e.optString("summary") + "|" + e.optString("quote") + "|" + e.optString("type")).hashCode())

    private fun imaBuckets(mode: String?, scope: String): LinkedHashMap<String, MutableList<JSONObject>> {
        val evs = db.listEventsBrief(mode, 5000)
        val synced = db.imaSyncedHashes()
        val from = when (scope) {
            "today" -> dayStr(0)
            "7d" -> dayStr(-7)
            else -> ""
        }
        val map = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until evs.length()) {
            val e = evs.getJSONObject(i)
            val day = e.optString("event_time").take(10).ifBlank { "undated" }
            if (from.isNotEmpty() && day < from) continue
            if (synced[e.optString("id")] == hashOf(e)) continue
            map.getOrPut(day) { mutableListOf() }.add(e)
        }
        // 早的在前，追加顺序和时间线一致
        return LinkedHashMap(map.toSortedMap())
    }

    private fun imaBlock(list: List<JSONObject>): String {
        val sb = StringBuilder()
        for (e in list) {
            val t = e.optString("event_time")
            val hm = if (t.length >= 16) t.substring(11, 16) else ""
            val sm = e.optString("summary").ifBlank { e.optString("quote") }
            val q = e.optString("quote")
            sb.append("- ")
            if (hm.isNotEmpty()) sb.append(hm).append(" · ")
            sb.append(e.optString("type", "事")).append("｜").append(sm).append('\n')
            if (q.isNotBlank() && q != sm) sb.append("  > ").append(q.replace("\n", " ")).append('\n')
        }
        return sb.toString()
    }

    /** 一键同步：新建 or 追加 → 挂进知识库 → 记幂等。失败的天保留，下次自动重试。 */
    fun imaSync(mode: String?, scope: String): JSONObject {
        if (!imaConfigured())
            return JSONObject().put("ok", false).put("error", "还没连接 ima，先去 ima 页填 Client ID / API Key")
        val buckets = imaBuckets(mode, scope)
        if (buckets.isEmpty())
            return JSONObject().put("ok", true).put("created", 0).put("appended", 0)
                .put("events", 0).put("message", "没有待同步的新记录")
        var created = 0; var appended = 0; var events = 0
        val fails = JSONArray(); val warns = JSONArray()
        for ((day, list) in buckets) {
            val block = imaBlock(list)
            var docId = db.imaDayDoc(day)
            if (docId.isBlank()) {
                val r = imaCall("/openapi/note/v1/import_doc", JSONObject()
                    .put("content_format", 1)
                    .put("content", "# 履迹 · $day\n\n共 ${list.size} 条记录\n\n$block"))
                if (!r.optBoolean("ok")) {
                    fails.put(JSONObject().put("day", day).put("error", r.optString("error"))); continue
                }
                docId = pickId(r.opt("data"))
                if (docId.isBlank()) {
                    fails.put(JSONObject().put("day", day).put("error", "建笔记成功但没拿到 doc_id")); continue
                }
                if (imaKbId.isNotBlank()) {
                    val ak = imaCall("/openapi/wiki/v1/add_knowledge", JSONObject()
                        .put("media_type", 11)
                        .put("title", "履迹 · $day")
                        .put("knowledge_base_id", imaKbId)
                        .put("note_info", JSONObject().put("content_id", docId)))
                    if (!ak.optBoolean("ok")) warns.put(JSONObject().put("day", day)
                        .put("warn", "笔记已建，但挂到知识库失败：" + ak.optString("error")))
                }
                db.imaPutDay(day, docId, imaKbId)
                created++
            } else {
                val r = imaCall("/openapi/note/v1/append_doc", JSONObject()
                    .put("doc_id", docId).put("content_format", 1)
                    .put("content", "\n$block"))
                if (!r.optBoolean("ok")) {
                    fails.put(JSONObject().put("day", day).put("error", r.optString("error"))); continue
                }
                appended++
            }
            for (e in list) db.imaMarkSynced(e.optString("id"), day, docId, hashOf(e))
            events += list.size
        }
        return JSONObject().put("ok", fails.length() == 0)
            .put("created", created).put("appended", appended).put("events", events)
            .put("kb", imaKbName.ifBlank { if (imaKbId.isBlank()) "（未选知识库，只写进笔记）" else imaKbId })
            .put("failed", fails).put("warn", warns)
            .put("pending", imaBuckets(null, "all").values.sumOf { it.size })
            .apply { if (fails.length() > 0) put("error", "有 ${fails.length()} 天没同步成功，可再点一次重试") }
    }

    /** 系统分享兜底：不需要凭证，把当天/最近内容丢给 ima App 手动入库。 */
    fun imaShareText(mode: String?, scope: String): String {
        val evs = db.listEventsBrief(mode, 5000)
        val from = when (scope) { "today" -> dayStr(0); "7d" -> dayStr(-7); else -> "" }
        val byDay = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until evs.length()) {
            val e = evs.getJSONObject(i)
            val day = e.optString("event_time").take(10).ifBlank { "未标日期" }
            if (from.isNotEmpty() && day < from) continue
            byDay.getOrPut(day) { mutableListOf() }.add(e)
        }
        val sb = StringBuilder("# 履迹导出 · ").append(TraceDb.now().take(10)).append("\n\n")
        if (byDay.isEmpty()) sb.append("（该范围内没有记录）\n")
        for ((day, list) in byDay.toSortedMap()) {
            sb.append("## ").append(day).append('\n').append(imaBlock(list)).append('\n')
        }
        sb.append("\n> 由「履迹」导出，单向文本，不含音频。")
        return sb.toString()
    }

    fun imaExport(text: String, title: String): JSONObject {
        if (!imaConfigured())
            return JSONObject().put("ok", false).put("error", "ima 凭证未配置（可选拓展，不影响主链路）")
        val body = JSONObject().put("content_format", 1)
            .put("content", "# $title\n\n$text")
        val r = imaCall("/openapi/note/v1/import_doc", body)
        if (!r.optBoolean("ok")) return r
        val docId = pickId(r.opt("data"))
        var kbWarn = ""
        if (imaKbId.isNotBlank()) {
            val ak = imaCall("/openapi/wiki/v1/add_knowledge", JSONObject()
                .put("media_type", 11).put("title", title)
                .put("knowledge_base_id", imaKbId)
                .put("note_info", JSONObject().put("content_id", docId)))
            if (!ak.optBoolean("ok")) kbWarn = ak.optString("error")
        }
        return JSONObject().put("ok", true).put("doc_id", docId).put("title", title)
            .put("note", "已写入 ima 笔记").put("kb_warn", kbWarn)
    }
}
