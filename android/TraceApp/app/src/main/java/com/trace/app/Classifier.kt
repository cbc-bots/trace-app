package com.trace.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 离线分类内核：不联网也能给每段录音定情绪、事件类型与属性。
 *
 * 设计取舍
 *  - 词表放在 assets/lexicon/，与代码解耦：换词表只换文件（商用需替换 DUTIR）
 *  - 打包成 Int 存在 HashMap 里，27k 词约 1.5MB 常驻，冷启动解析约 150ms，可后台预热
 *  - 输出结构与 LLM 返回的字段同名（l1/l2/intensity/polarity），模型结果与规则结果可直接合并
 *  - 所有推断都带 source 标记（builtin:lexicon），不冒充模型结论
 *
 * 权重逻辑与 tools/eval_classifier.py 完全一致（20/20 用例通过），改动请两边同步。
 */
object Classifier {

    private const val TAG = "Classifier"

    // 小类码 → (大类, 序位)
    private val L2_TO_L1 = linkedMapOf(
        "PA" to "joy", "PE" to "joy",
        "PD" to "good", "PH" to "good", "PG" to "good", "PB" to "good", "PK" to "good",
        "NA" to "anger",
        "NB" to "sad", "NJ" to "sad", "NH" to "sad", "PF" to "sad",
        "NI" to "fear", "NC" to "fear", "NG" to "fear",
        "NE" to "disgust", "ND" to "disgust", "NN" to "disgust",
        "NK" to "disgust", "NL" to "disgust",
        "PC" to "surprise"
    )
    private val L2_LIST = L2_TO_L1.keys.toList()

    // 大类 → 该大类的默认极性（DUTIR 原表极性列大量为空，只能由大类推导）
    private val L1_POLARITY = mapOf(
        "joy" to 1, "good" to 1, "anger" to 2, "sad" to 2,
        "fear" to 2, "disgust" to 2, "surprise" to 0
    )

    private val POS_CODE = mapOf("n" to 0, "v" to 1, "a" to 2, "d" to 3, "i" to 4, "o" to 5)
    private val POS_WEIGHT = doubleArrayOf(0.6, 1.0, 1.0, 0.9, 0.75, 0.8)

    private const val MAX_WORD = 6

    // 单字否定词若属于更长词，就不是否定
    private val NEG_GUARD = mapOf(
        "别" to listOf("特别", "分别", "差别", "区别", "别人", "告别", "性别", "个别",
            "离别", "级别", "识别", "辨别", "类别", "派别", "别墅"),
        "无" to listOf("无比", "无论", "无数", "无限", "无疑", "无意", "无法"),
        "非" to listOf("非常", "非洲", "是非", "除非", "非要", "非得"),
        "未" to listOf("未来", "未必", "未曾"),
        "不" to listOf("不过", "不久", "不少", "不错", "不但", "不禁", "不得不", "不管",
            "不论", "不断", "不仅", "不免", "不由得", "不一会"),
        "没" to listOf("没有"),
        "莫" to listOf("莫名", "莫过于")
    )

    // 情绪词之后的程度表达
    private val POST_DEGREE = mapOf(
        "死了" to 9, "极了" to 9, "坏了" to 9, "爆了" to 9, "到爆" to 9,
        "得很" to 8, "得慌" to 8, "不已" to 8, "不行" to 8, "要命" to 8,
        "透顶" to 9, "得要命" to 9
    )
    private val POST_ADJ = "大重多高深久强狠凶"

    // 时态 / 模态
    private val TENSE_FUTURE = listOf("明天", "后天", "下周", "下个月", "明年", "将来",
        "以后", "接下来", "准备", "打算", "将要", "快要", "马上", "即将", "要交", "要去")
    private val TENSE_PAST = listOf("昨天", "前天", "上周", "上个月", "去年", "刚才",
        "之前", "已经", "当时", "那天", "刚刚", "完了", "过了")
    private val MODALITY_INTENDED = listOf("打算", "准备", "想要", "希望", "计划", "争取", "考虑")
    private val MODALITY_HYPO = listOf("如果", "要是", "假如", "万一", "假设", "若能", "的话")

    // ---------- 资源 ----------
    private class Entry(val l2: String, val intensity: Int, val polarity: Int, val posCode: Int)

    private var loaded = false
    private val loading = AtomicBoolean(false)
    private var emo = HashMap<String, Entry>(32768)
    private var exclusions = HashSet<String>(64)
    private val degree = HashMap<String, Double>(64)
    private var negation = HashSet<String>(48)
    private val domainPatterns = HashMap<String, MutableList<String>>(8)
    private val typePatterns = HashMap<String, MutableList<String>>(10)
    private var maxNegLen = 1

    var lexiconVersion: String = "unknown"
        private set

    private fun isReady() = loaded

    /** 后台预热：Application.onCreate 里调一次，避免第一段录音卡在解析上。 */
    fun warmUp(ctx: Context) {
        if (loaded || !loading.compareAndSet(false, true)) return
        Thread {
            try {
                load(ctx.applicationContext)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "lexicon load failed: ${e.message}")
            } finally {
                loading.set(false)
            }
        }.start()
    }

    fun ensureLoaded(ctx: Context) {
        if (!loaded) load(ctx.applicationContext)
    }

    @Synchronized
    private fun load(ctx: Context) {
        if (loaded) return
        val t0 = System.currentTimeMillis()
        readLines(ctx, "lexicon/emotion_lexicon.tsv") { line ->
            val p = line.split('\t')
            if (p.size >= 6) {
                val l2 = p[1]
                if (L2_TO_L1.containsKey(l2)) {
                    val posCode = POS_CODE[p[4]] ?: 5
                    val pol = p[3].toIntOrNull() ?: (L1_POLARITY[L2_TO_L1[l2]] ?: 0)
                    emo[p[0]] = Entry(l2, p[2].toIntOrNull() ?: 5, pol, posCode)
                }
            }
        }
        readLines(ctx, "lexicon/word_exclusions.tsv") { exclusions.add(it) }
        readLines(ctx, "lexicon/degree_words.tsv") { line ->
            val p = line.split('\t')
            if (p.size >= 2) p[1].toDoubleOrNull()?.let { degree[p[0]] = it }
        }
        readLines(ctx, "lexicon/negation.txt") { negation.add(it) }
        maxNegLen = negation.maxOfOrNull { it.length } ?: 1
        readLines(ctx, "lexicon/event_patterns.tsv") { line ->
            val p = line.split('\t')
            if (p.size >= 3) {
                val bucket = if (p[0] == "domain") domainPatterns else typePatterns
                bucket.getOrPut(p[1]) { ArrayList() }.add(p[2])
            }
        }
        // 长词优先，保证「不舒服」先于「舒服」命中
        for (m in listOf(domainPatterns, typePatterns))
            for (k in m.keys) m[k]!!.sortByDescending { it.length }
        try {
            ctx.assets.open("lexicon/taxonomy.json").bufferedReader().use {
                lexiconVersion = JSONObject(it.readText()).optString("version", "unknown")
            }
        } catch (_: Exception) { }
        loaded = true
        android.util.Log.i(TAG, "lexicon ready: ${emo.size} words, ${System.currentTimeMillis() - t0} ms")
    }

    private inline fun readLines(ctx: Context, path: String, crossinline onLine: (String) -> Unit) {
        try {
            ctx.assets.open(path).bufferedReader().use { br ->
                br.forEachLine { raw ->
                    val s = raw.trim()
                    if (s.isNotEmpty() && !s.startsWith("#")) onLine(s)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "read $path failed: ${e.message}")
        }
    }

    // ---------- 匹配 ----------
    private data class Hit(
        val start: Int, val word: String, val len: Int,
        val l2: String, val l1: String,
        val intensity: Int, val polarity: Int, val negated: Boolean
    )

    private fun inSpans(j: Int, spans: List<IntArray>): Boolean {
        for (s in spans) if (j >= s[0] && j < s[1]) return true
        return false
    }

    /** 最长匹配扫描，命中即跳过该词长度。 */
    private fun scan(text: String): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        val n = text.length
        while (i < n) {
            var picked: IntArray? = null
            for (ln in minOf(MAX_WORD, n - i) downTo 1) {
                val w = text.substring(i, i + ln)
                if (!emo.containsKey(w)) continue
                if (ln == 1) {
                    var guard = false
                    for (off in intArrayOf(-2, -1, 0, 1)) {
                        val s = i + off
                        if (s >= 0 && s + 2 <= n && exclusions.contains(text.substring(s, s + 2))) {
                            guard = true; break
                        }
                    }
                    if (guard) break
                }
                picked = intArrayOf(i, ln)
                break
            }
            if (picked != null) {
                out.add(picked)
                i += picked[1]
            } else i++
        }
        return out
    }

    /** 向前找最近的表项，跳过已被更长词占用的位置。返回 (值, 距离)。 */
    private fun nearDegree(text: String, i: Int, spans: List<IntArray>): Double? {
        val start = maxOf(0, i - 4)
        for (dist in 1..(i - start)) {
            val j = i - dist
            if (inSpans(j, spans)) continue
            for (ln in minOf(3, i - j) downTo 1) {
                val w = text.substring(j, j + ln)
                degree[w]?.let { return it }
            }
        }
        return null
    }

    private fun isNegated(text: String, i: Int, spans: List<IntArray>): Boolean {
        val start = maxOf(0, i - 4)
        for (dist in 1..(i - start)) {
            val j = i - dist
            if (inSpans(j, spans)) continue
            for (ln in minOf(maxNegLen, i - j) downTo 1) {
                val w = text.substring(j, j + ln)
                if (!negation.contains(w)) continue
                if (w.length == 1) {
                    val guard = NEG_GUARD[w]
                    var hit = false
                    if (guard != null) {
                        for (st in maxOf(0, j - 3)..j) {
                            for (gl in 2..4) {
                                if (st + gl <= text.length && guard.contains(text.substring(st, st + gl))) {
                                    hit = true; break
                                }
                            }
                            if (hit) break
                        }
                    }
                    if (hit) continue      // 这个位置不算否定，继续往前找
                }
                return true
            }
        }
        return false
    }

    /** 情绪词之后的程度：「累死了」「压力好大」。 */
    private fun postDegree(text: String, end: Int): Int? {
        var best: Int? = null
        for ((w, v) in POST_DEGREE) {
            if (text.startsWith(w, end)) best = maxOf(best ?: 0, v)
        }
        if (best == null && end + 1 < text.length) {
            val c = text[end]
            val d = text[end + 1]
            if ((c == '好' || c == '很' || c == '太' || c == '超') && POST_ADJ.indexOf(d) >= 0)
                best = 8
            else if (c == '得' && POST_ADJ.indexOf(d) >= 0) best = 7
        }
        return best
    }

    // ---------- 对外：情绪 ----------
    fun analyzeEmotion(ctx: Context, text: String): JSONObject {
        ensureLoaded(ctx)
        val out = JSONObject()
        if (text.isBlank()) {
            return out.put("primary", JSONObject.NULL).put("secondary", JSONArray())
                .put("sentiment", 0.0).put("hits", JSONArray()).put("adapter", "builtin:lexicon")
        }
        val scans = scan(text)
        val spans = scans.map { intArrayOf(it[0], it[0] + it[1]) }
        val hits = ArrayList<Hit>()
        for (sc in scans) {
            val s = sc[0]; val ln = sc[1]
            val w = text.substring(s, s + ln)
            val e = emo[w] ?: continue
            val deg = nearDegree(text, s, spans)
            val neg = isNegated(text, s, spans)
            val post = postDegree(text, s + ln)
            var eff = if (post != null) maxOf(e.intensity, post) else e.intensity
            if (neg) {
                // 被否定的情绪通常弱化（「不太开心」轻于「难过」），此时不再吃程度加成
                eff = maxOf(3, Math.round(eff * 0.5).toInt())
            } else if (deg != null) {
                eff = minOf(9, Math.round(eff * deg).toInt())
            }
            var pol = e.polarity
            if (neg && pol != 0) pol = 3 - pol
            hits.add(Hit(s, w, ln, e.l2, L2_TO_L1[e.l2] ?: "others", eff, pol, neg))
        }
        if (hits.isEmpty()) {
            return out.put("primary", JSONObject.NULL).put("secondary", JSONArray())
                .put("sentiment", 0.0).put("hits", JSONArray()).put("adapter", "builtin:lexicon")
        }
        // 按有效强度（含词性权重）排序
        val scored = hits.map { h -> h to h.intensity * POS_WEIGHT[emo[h.word]?.posCode ?: 5] }
            .sortedByDescending { it.second }
        val top = scored[0].first
        val primaryInt = scored.filter { it.first.l2 == top.l2 }.maxOf { it.first.intensity }
        val secondary = JSONArray()
        var n = 0
        for ((h, _) in scored.drop(1)) {
            if (h.l2 == top.l2 || n >= 3) continue
            secondary.put(JSONObject().put("word", h.word).put("l1", h.l1)
                .put("l2", h.l2).put("intensity", h.intensity)
                .put("polarity", h.polarity).put("negated", h.negated))
            n++
        }
        val hitArr = JSONArray()
        for ((h, _) in scored)
            hitArr.put(JSONObject().put("word", h.word).put("l1", h.l1).put("l2", h.l2)
                .put("intensity", h.intensity).put("polarity", h.polarity)
                .put("negated", h.negated).put("pos", h.start))
        var posSum = 0.0; var negSum = 0.0
        for ((h, sc) in scored) {
            if (h.polarity == 1) posSum += sc else if (h.polarity == 2) negSum += sc
        }
        val total = posSum + negSum
        val sentiment = if (total == 0.0) 0.0 else Math.round((posSum - negSum) / total * 100) / 100.0
        return out
            .put("primary", JSONObject()
                .put("word", top.word).put("l1", top.l1).put("l2", top.l2)
                .put("intensity", primaryInt).put("polarity", top.polarity)
                .put("negated", top.negated))
            .put("secondary", secondary)
            .put("sentiment", sentiment)
            .put("hits", hitArr)
            .put("adapter", "builtin:lexicon")
    }

    // ---------- 对外：事件类型 ----------
    fun classifyEvent(ctx: Context, text: String): JSONObject {
        ensureLoaded(ctx)
        val dom = scorePatterns(text, domainPatterns)
        val typ = scorePatterns(text, typePatterns)
        return JSONObject()
            .put("domain", dom.optString("top", "").ifBlank { JSONObject.NULL })
            .put("domain_scores", dom.optJSONObject("scores") ?: JSONObject())
            .put("type", typ.optString("top", "").ifBlank { JSONObject.NULL })
            .put("type_scores", typ.optJSONObject("scores") ?: JSONObject())
            .put("tense", detectTense(text))
            .put("modality", detectModality(text))
            .put("adapter", "builtin:lexicon")
    }

    private fun scorePatterns(text: String, table: Map<String, List<String>>): JSONObject {
        val scores = JSONObject()
        var best: String? = null
        var bestScore = 0
        for ((id, words) in table) {
            var s = 0
            for (w in words) if (text.contains(w)) s += w.length
            if (s > 0) {
                scores.put(id, s)
                if (s > bestScore) { bestScore = s; best = id }
            }
        }
        return JSONObject().put("top", best ?: "").put("scores", scores)
    }

    private fun detectTense(text: String): String = when {
        TENSE_FUTURE.any { text.contains(it) } -> "future"
        TENSE_PAST.any { text.contains(it) } -> "past"
        else -> "present"
    }

    private fun detectModality(text: String): String = when {
        MODALITY_HYPO.any { text.contains(it) } -> "hypothetical"
        MODALITY_INTENDED.any { text.contains(it) } -> "intended"
        else -> "actual"
    }

    // ---------- 对外：合并 ----------
    /**
     * 一次性给出分类结论。前端 / LLM 都能用同一份字段结构。
     * tags 取命中到的 domain/type 触发词，作为最朴素的检索标签。
     */
    fun classify(ctx: Context, text: String): JSONObject {
        ensureLoaded(ctx)
        val emoObj = analyzeEmotion(ctx, text)
        val evObj = classifyEvent(ctx, text)
        val tags = JSONArray()
        val seen = HashSet<String>()
        val topDomain = evObj.optString("domain")
        val topType = evObj.optString("type")
        for (w in domainPatterns[topDomain] ?: emptyList()) if (text.contains(w) && seen.add(w)) tags.put(w)
        for (w in typePatterns[topType] ?: emptyList()) if (text.contains(w) && seen.add(w)) tags.put(w)
        val primary = emoObj.optJSONObject("primary")
        if (primary != null) {
            val (l1n, l2n) = emotionNames(ctx, primary.optString("l2"))
            primary.put("l1_name", l1n).put("l2_name", l2n)
            val w = primary.optString("word")
            if (w.isNotBlank() && seen.add(w)) tags.put(w)
        }
        val tId = evObj.optString("type"); val dId = evObj.optString("domain")
        evObj.put("type_name", if (tId.isBlank()) "" else displayName(ctx, "types", tId))
            .put("domain_name", if (dId.isBlank()) "" else displayName(ctx, "domains", dId))
        return JSONObject()
            .put("ok", true)
            .put("text_len", text.length)
            .put("emotion", emoObj)
            .put("event", evObj)
            .put("tags", tags)
            .put("lexicon_version", lexiconVersion)
    }

    // ---------- 给前端的枚举定义 ----------
    private var taxo: JSONObject? = null

    @Synchronized
    fun taxonomy(ctx: Context): JSONObject {
        taxo?.let { return it }
        return try {
            val o = ctx.assets.open("lexicon/taxonomy.json").bufferedReader().use {
                JSONObject(it.readText())
            }
            taxo = o
            o
        } catch (e: Exception) {
            JSONObject().put("error", "taxonomy.json 读取失败: ${e.message}")
        }
    }

    /** id → 中文名。词表可换，名字只认 taxonomy.json 一处。 */
    fun displayName(ctx: Context, group: String, id: String): String {
        val arr = taxonomy(ctx).optJSONArray(group) ?: return id
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o.optString("name", id)
        }
        if (group == "types") return when (id) {
            "decision" -> "决定"; "action" -> "行动"; "outcome" -> "结果"; "emotion" -> "情绪"
            "problem" -> "问题"; "plan" -> "计划"; "insight" -> "觉察"; else -> "见闻"
        }
        return id
    }

    /** L2 小类码（PA/NA…）→ (大类中文名, 小类中文名)。 */
    fun emotionNames(ctx: Context, l2: String?): Pair<String, String> {
        if (l2.isNullOrBlank()) return "" to ""
        val lvs = taxonomy(ctx).optJSONObject("emotions") ?: return "" to ""
        val l1 = L2_TO_L1[l2] ?: ""
        var l1Name = ""; var l2Name = ""
        val l1Arr = lvs.optJSONArray("level1") ?: JSONArray()
        for (i in 0 until l1Arr.length()) {
            val o = l1Arr.optJSONObject(i) ?: continue
            if (o.optString("id") == l1) l1Name = o.optString("name", l1)
        }
        val l2Arr = lvs.optJSONArray("level2") ?: JSONArray()
        for (i in 0 until l2Arr.length()) {
            val o = l2Arr.optJSONObject(i) ?: continue
            if (o.optString("id") == l2) l2Name = o.optString("name", l2)
        }
        return l1Name to l2Name
    }

    /** 词典规模，给 /taxonomy 与健康检查用。 */
    fun stats(): JSONObject = JSONObject()
        .put("emotion_words", emo.size)
        .put("degree_words", degree.size)
        .put("negation_words", negation.size)
        .put("domains", domainPatterns.size)
        .put("types", typePatterns.size)
        .put("loaded", loaded)
}
