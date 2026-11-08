package com.trace.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** WebView JS ↔ 原生桥。把桌面版后端的 20 个 API 原样搬到 APK 内，前端一份代码两处复用。
 *
 * v1.2 起有两套调用方式：
 *  - `call()`      同步返回。**只在必须同步取值时用**——它跑在 WebView 的 JS 线程上，
 *                  网络往返（AI 对话最长 45s）期间整个界面会冻住，不能滚动也不能点。
 *  - `callAsync()` 立即返回，后台线程执行，完成后用 `evaluateJavascript` 回推 Promise。
 *                  前端 `api()` 默认走这条，界面上不会再出现「点了没反应」。
 */
class TraceBridge(private val ctx: Context, val engine: TraceEngine, val db: TraceDb) {

    private val catalog = listOf(
        Triple("classifier", "sherpa-onnx 离线语音流水线", "https://github.com/k2-fsa/sherpa-onnx"),
        Triple("classifier", "FunASR 中文全流水线", "https://github.com/modelscope/FunASR"),
        Triple("agent", "LlamaIndex RAG 对话分析", "https://github.com/run-llama/llama_index"),
        Triple("agent", "RAGFlow 混合检索与溯源", "https://github.com/infiniflow/ragflow"),
        Triple("classifier", "instructor 结构化抽取", "https://github.com/jxnl/instructor"),
        Triple("classifier", "Chroma 向量排序", "https://github.com/chroma-core/chroma"),
        Triple("view", "vis-timeline 时间轴", "https://github.com/visjs/vis-timeline"),
        Triple("view", "ECharts 统计视图", "https://github.com/apache/echarts"),
        Triple("agent", "pluggy 插件注册内核", "https://github.com/pytest-dev/pluggy"))

    // ---------- 异步通道（P0：把网络等待从 JS 线程挪走） ----------
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    private var web: WebView? = null

    fun attach(w: WebView) { web = w }

    private fun describe(e: Throwable) = "${e.javaClass.simpleName}: ${e.message}"

    /** evaluateJavascript 必须在 UI 线程；web.post 顺带避免 WebView 未就绪时的 NPE。 */
    private fun eval(js: String) {
        val w = web ?: return
        w.post { try { w.evaluateJavascript(js, null) } catch (_: Throwable) { } }
    }

    private fun pushResolve(id: String, payload: String) = eval(
        "window.__traceResolve&&window.__traceResolve(${JSONObject.quote(id)},${JSONObject.quote(payload)})")

    private fun pushDelta(id: String, payload: String) = eval(
        "window.__traceDelta&&window.__traceDelta(${JSONObject.quote(id)},${JSONObject.quote(payload)})")

    @JavascriptInterface
    fun callAsync(reqId: String, methodAndPath: String, bodyJson: String) {
        pool.execute {
            val payload = try {
                call(methodAndPath, bodyJson)
            } catch (e: Throwable) {
                JSONObject().put("ok", false).put("error", describe(e)).toString()
            }
            pushResolve(reqId, payload)
        }
    }

    /** 流式对话：delta 攒够 70ms 才往 UI 推一次，避免刷屏把主线程压住。 */
    @JavascriptInterface
    fun chatStream(reqId: String, bodyJson: String) {
        pool.execute {
            val pending = StringBuilder()
            var lastPush = 0L
            try {
                val b = try { JSONObject(bodyJson) } catch (e: Exception) { JSONObject() }
                val meta = engine.chatStream(
                    b.optString("mode_id", "life"), b.optString("query", ""), reqId
                ) { d ->
                    pending.append(d)
                    val t = System.currentTimeMillis()
                    if (t - lastPush >= 70) {
                        lastPush = t
                        val s = pending.toString()
                        pending.setLength(0)
                        if (s.isNotEmpty()) pushDelta(reqId, JSONObject().put("delta", s).toString())
                    }
                }
                if (pending.isNotEmpty()) pushDelta(reqId, JSONObject().put("delta", pending.toString()).toString())
                pushResolve(reqId, meta.toString())
            } catch (e: Throwable) {
                pushResolve(reqId, JSONObject().put("ok", false).put("error", describe(e)).toString())
            }
        }
    }

    @JavascriptInterface
    fun cancel(reqId: String) { engine.cancelChat(reqId) }

    @JavascriptInterface
    fun call(methodAndPath: String, bodyJson: String): String = try {
        val space = methodAndPath.indexOf(' ')
        val method = if (space > 0) methodAndPath.substring(0, space) else "GET"
        val full = if (space > 0) methodAndPath.substring(space + 1) else methodAndPath
        val uri = Uri.parse(full)
        val path = uri.path?.trimEnd('/')?.ifEmpty { "/" } ?: "/"
        val body = try { JSONObject(bodyJson.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }
        val r = if (method == "POST") post(path, body, uri) else get(path, uri)
        r.toString()
    } catch (e: Exception) {
        JSONObject().put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}").toString()
    }

    private fun ok(vararg pairs: Pair<String, Any>): JSONObject {
        val o = JSONObject().put("ok", true)
        for ((k, v) in pairs) o.put(k, v)
        return o
    }

    /** startActivity 必须在主线程；桥的调用都在线程池里，所以统一 post 回 UI。 */
    private fun runOnUi(block: () -> Unit) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try { block() } catch (_: Throwable) { }
        }
    }

    private fun get(path: String, uri: Uri): JSONObject {
        val q = { k: String -> uri.getQueryParameter(k) }
        return when (path) {
            "/health" -> {
                val folders = JSONArray()
                val comps = db.listComponents(null)
                for (i in 0 until comps.length()) {
                    val c = comps.getJSONObject(i)
                    if (c.optJSONObject("manifest")?.optString("source") == "folder") folders.put(c.getString("id"))
                }
                ok("service" to "trace-android", "time" to TraceDb.now(),
                    "q1_capture" to "always_on", "fts5" to false,
                    "has_key" to engine.apiKey.isNotBlank(),
                    "ffmpeg" to "MediaCodec(系统内置)",
                    "sherpa_aar" to engine.hasSherpaAar(),
                    "onnx_assets" to engine.hasOnnxAssets(),
                    "modules_dir" to "assets/modules", "folder_modules" to folders,
                    "retention" to db.l1Stats(),
                    "classifier" to Classifier.stats(),
                    "taxonomy_version" to Classifier.lexiconVersion,
                    "adapters" to engine.adapters())
            }
            "/config" -> ok("config" to JSONObject()
                .put("dashscope", JSONObject().put("api_key", "***").put("base_url", engine.baseUrl)
                    .put("llm_model", engine.llmModel).put("asr_endpoint", engine.asrEndpoint))
                .put("q1_capture", "always_on"),
                "has_key" to engine.apiKey.isNotBlank(),
                "key_hint" to engine.keyHint(),
                "base_url" to engine.baseUrl,
                "llm_model" to engine.llmModel,
                "note" to "密钥字段已隐藏")
            "/modes" -> ok("modes" to db.listModes())
            /* slim=1：星图只要画点/卡片/详情用的列，省掉重复的 quote_ref.quote，
               1500 条时 JSON 体积小一半，WebView 解析也就跟着快一半。 */
            "/events" -> {
                val mode = q("mode_id")
                val lim = q("limit")?.toIntOrNull() ?: 500
                if (q("slim") == "1") ok("events" to db.listEventsSlim(mode, lim))
                else ok("events" to db.listEvents(mode, lim))
            }
            "/event" -> {
                val e = db.getEvent(q("id") ?: "")
                if (e == null) JSONObject().put("ok", false).put("event", JSONObject.NULL)
                else ok("event" to e)
            }
            // 同一段录音切出来的所有条目：详情页点「同段的其他条目」用它，不用再拉全表
            "/source" -> {
                val f = q("file") ?: ""
                val evs = if (f.isBlank()) JSONArray() else db.eventsByFile(f)
                ok("file" to f, "entries" to evs, "count" to evs.length())
            }
            "/timeline" -> ok("data" to db.timeline(q("mode_id") ?: "life", q("view") ?: "timeline"))
            "/search" -> ok("query" to (q("q") ?: ""), "events" to db.search(q("mode_id"), q("q") ?: ""))
            "/stages" -> ok("stages" to db.getStages(q("source") ?: ""))
            // granularity=segment|day；不传就是全粒度混排（旧前端行为不变）
            "/analyses" -> {
                val g = q("granularity")
                val lim = q("limit")?.toIntOrNull() ?: 50
                ok("analyses" to if (g.isNullOrBlank()) db.listAnalyses(q("mode_id"), lim)
                    else db.listAnalysesBy(q("mode_id") ?: "life", g, lim))
            }
            "/l1" -> ok("files" to db.l1List(q("pending") == "1"),
                "retention" to db.l1Stats())
            "/analysis" -> {
                val g = q("granularity"); val pk = q("period_key")
                val hit = if (!g.isNullOrBlank() && !pk.isNullOrBlank())
                    db.findAnalysis(q("mode_id") ?: "life", g, pk)
                else {
                    val all = db.listAnalyses(q("mode_id"))
                    var h: JSONObject? = null
                    for (i in 0 until all.length()) {
                        val a = all.getJSONObject(i)
                        if (a.optString("id") == q("id")) { h = a; break }
                    }
                    h
                }
                ok("analysis" to (hit ?: JSONObject.NULL))
            }
            "/stats" -> ok("stats" to db.stats(q("mode_id") ?: "life",
                q("days")?.toIntOrNull() ?: 0))
            // 长任务进度：done=true 时 result 就是 /upload 原本的完整返回
            "/job" -> {
                val j = Jobs.get(q("id") ?: "")
                if (j == null) JSONObject().put("ok", false).put("error", "没有这个任务")
                else JSONObject().put("ok", true).put("job_id", j.id)
                    .put("stage", j.stage).put("pct", j.pct).put("done", j.done)
                    .put("error", j.error ?: JSONObject.NULL)
                    .put("elapsed_ms", System.currentTimeMillis() - j.startedAt)
                    .put("result", j.result ?: JSONObject.NULL)
            }
            // 分类体系：枚举、颜色、强度刻度，前端据此渲染筛选器与图例，不用硬编码
            // 常驻录音实时状态：已录多久、入队几段、积压多久、丢弃几段静音
            "/record/status" -> ok("status" to RecordService.status,
                "native_running" to RecordService.isRunning())
            "/taxonomy" -> ok("taxonomy" to Classifier.taxonomy(ctx),
                "lexicon_version" to Classifier.lexiconVersion,
                "lexicon" to Classifier.stats())
            "/event/tags" -> ok("event_id" to (q("id") ?: ""),
                "tags" to db.getTags(q("id") ?: ""))
            "/l2" -> ok("segments" to db.getL2(q("file") ?: ""))
            "/components" -> ok("components" to db.listComponents(q("type")))
            "/agents" -> ok("agents" to db.listComponents("agent"),
                "active_agent" to engine.activeAgent)
            "/catalog" -> {
                val arr = JSONArray()
                for ((t, n, r) in catalog) arr.put(JSONObject().put("type", t).put("name", n).put("repo", r)
                    .put("installed", false))
                ok("catalog" to arr)
            }
            "/session" -> ok("session" to JSONObject.NULL)
            "/ima/state" -> engine.imaState()
            else -> JSONObject().put("ok", false).put("error", "未知路由 GET $path")
        }
    }

    private fun post(path: String, b: JSONObject, uri: Uri): JSONObject {
        return when (path) {
            "/config" -> {
                val k = b.optString("api_key", "")
                val e = b.optString("asr_endpoint", "")
                val base = b.optString("base_url", "")
                val mdl = b.optString("llm_model", "")
                engine.saveConfig(
                    if (k.isNotBlank()) k else null,
                    if (e.isNotBlank()) e else null,
                    null,
                    base.ifBlank { null },
                    mdl.ifBlank { null })
                ok("has_key" to engine.apiKey.isNotBlank(),
                    "key_hint" to engine.keyHint(),
                    "base_url" to engine.baseUrl,
                    "llm_model" to engine.llmModel,
                    "note" to "已保存到本机")
            }
            // 保存后立刻用真实请求验一次，把「填了 key」变成「key 被接受」
            "/config/test" -> engine.verifyKey()
            "/l1/keep" -> ok("done" to db.l1SetKeep(
                b.optString("file"), b.optBoolean("keep", true)))
            "/l1/purge" -> ok("result" to db.l1PurgeExpired())
            "/agent/select" -> {
                val cid = b.optString("id", "")
                val comps = db.listComponents("agent")
                var name = ""
                for (i in 0 until comps.length())
                    if (comps.getJSONObject(i).optString("id") == cid)
                        name = comps.getJSONObject(i).optString("name")
                if (name.isEmpty()) return JSONObject().put("ok", false).put("error", "未找到该 agent 预设")
                engine.saveConfig(agent = cid)
                ok("active_agent" to cid, "name" to name)
            }
            "/modes" -> {
                db.setMode(b.optString("mode_id"), b.optString("name"), b.optJSONObject("config") ?: JSONObject())
                ok("mode" to JSONObject().put("mode_id", b.optString("mode_id")))
            }
            "/ingest" -> engine.run(TraceEngine.Input(
                source = b.optString("source", "text"), mode = b.optString("mode_id", "life"),
                text = b.optString("text", null), captureSession = b.optString("capture_session", "live"),
                isDemoSample = b.optBoolean("is_demo_sample"),
                startedAt = b.optString("started_at").ifBlank { b.optString("recorded_at") }.takeIf { it.isNotBlank() }))
            // 音频上传：两种入口，都立刻返回 job_id，前端轮询 /job 看进度
            //   ① pick_handle —— 原生选择器直接给的文件路径，**不经过 base64**（推荐）
            //   ② audio_b64   —— 老的 base64 通道，只适合短片段（浏览器里录的 webm 等）
            "/upload" -> {
                val handle = b.optString("pick_handle", "")
                val picked: File? = if (handle.isNotBlank()) PickedFiles.get(handle) else null
                if (handle.isNotBlank() && picked == null)
                    return JSONObject().put("ok", false).put("error", "所选文件已失效，请重新选择")
                var bytes: ByteArray? = null
                if (picked == null) {
                    val b64 = b.optString("audio_b64", "")
                    if (b64.isBlank()) return JSONObject().put("ok", false)
                        .put("error", "需要 pick_handle 或 audio_b64")
                    bytes = try {
                        android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    } catch (e: Exception) {
                        return JSONObject().put("ok", false).put("error", "base64 解码失败")
                    }
                }
                val name = picked?.name ?: b.optString("filename", "upload.bin")
                val mode = b.optString("mode_id", "life")
                val session = b.optString("capture_session", "live")
                val startedAt = b.optString("started_at").ifBlank { b.optString("recorded_at") }
                    .takeIf { it.isNotBlank() }
                val job = Jobs.create()
                val payload = bytes
                Jobs.run(job) {
                    engine.run(TraceEngine.Input(
                        source = "upload", mode = mode, text = b.optString("text", null),
                        audioBytes = payload, audioFile = picked, filename = name,
                        captureSession = session, startedAt = startedAt,
                        progress = { st, p -> job.stage = st; if (p >= 0) job.pct = p }))
                }
                ok("job_id" to job.id, "filename" to name,
                    "bytes" to ((picked?.length() ?: payload?.size?.toLong()) ?: 0L))
            }
            // 原生文件选择器：APK 里用它挑音频，content:// 由原生侧流式拷进缓存，不经过 JS
            "/pick" -> {
                val handler = TraceRuntime.pickHandler
                if (handler == null) return JSONObject().put("ok", false)
                    .put("error", "当前环境没有原生选择器，请用页面上的文件框")
                val latch = java.util.concurrent.CountDownLatch(1)
                var file: File? = null
                var fname = "audio"
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                main.post { handler { f, n -> file = f; fname = n ?: "audio"; latch.countDown() } }
                if (!latch.await(300, java.util.concurrent.TimeUnit.SECONDS))
                    return JSONObject().put("ok", false).put("error", "选择超时")
                val f = file
                if (f == null) return JSONObject().put("ok", false).put("cancelled", true)
                    .put("error", "没有选择文件")
                ok("handle" to PickedFiles.put(f), "filename" to fname, "bytes" to f.length())
            }
            "/chat" -> engine.chat(b.optString("mode_id", "life"), b.optString("query")).put("ok", true)
            // ---- 分类接口：纯计算不落库，前端可直接预览文本会被分到哪一类 ----
            "/classify" -> {
                val text = b.optString("text")
                if (text.isBlank()) JSONObject().put("ok", false).put("error", "需要 text")
                else Classifier.classify(ctx, text)
            }
            // 人工纠正：写 cls 快照 + 标签，标 source=user，之后重算不再覆盖
            "/event/tag" -> {
                val eid = b.optString("event_id")
                if (eid.isBlank()) return JSONObject().put("ok", false).put("error", "需要 event_id")
                val kws = ArrayList<String>()
                b.optJSONArray("keywords")?.let { a ->
                    for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { kws.add(it) }
                }
                val e = engine.correctClass(eid,
                    b.optString("domain").ifBlank { null },
                    b.optString("type").ifBlank { null },
                    b.optString("emotion").ifBlank { null }, kws)
                if (e != null) engine.buildDigest(e.optString("mode_id", "life"))
                if (e == null) JSONObject().put("ok", false).put("error", "未找到该事件卡")
                else ok("event" to e, "tags" to db.getTags(eid))
            }
            "/event/reclassify" -> {
                val e = engine.reclassify(b.optString("event_id"))
                if (e == null) JSONObject().put("ok", false).put("error", "未找到该事件卡")
                else ok("event" to e, "tags" to db.getTags(b.optString("event_id")))
            }
            // 老数据补分类：一次最多 limit 条，remaining>0 说明还该再调一次
            // 重切历史条目：走后台任务，一段录音几十条 note 要几十秒，不能卡住界面
            "/resegment" -> {
                val f = b.optString("file", "")
                val all = f.isBlank() || f == "all"
                val lim = b.optInt("limit", 40)
                val job = Jobs.create()
                Jobs.run(job) {
                    if (all) engine.resegmentAll(lim) else engine.resegment(f)
                }
                ok("job_id" to job.id, "scope" to if (all) "all" else f)
            }
            "/classify/rebuild" -> ok("result" to engine.rebuildClassify(
                b.optString("mode_id").ifBlank { null }, b.optInt("limit", 200)))
            // 日汇总：按 period_key 缓存，force=true 才重算
            "/analysis/day" -> engine.analyzeDay(
                b.optString("mode_id").ifBlank { "life" },
                b.optString("day"), b.optBoolean("force"))
            // 手动改时间：空 = 退回「按录制当天」；填了就是用户亲口确认的日期/时刻
            "/event/time" -> {
                val e = db.setEventTime(b.optString("event_id"), b.optString("event_time", ""))
                if (e != null) engine.buildDigest(e.optString("mode_id", "life"))
                if (e == null) JSONObject().put("ok", false).put("error", "未找到该事件卡")
                else ok("event" to e)
            }
            "/correct" -> {
                val e = db.correct(b.optString("event_id"), b.optJSONObject("patch") ?: JSONObject())
                // 校正改了 summary → 摘要缓存失效，重建一次，别让对话读到旧版本
                if (e != null) engine.buildDigest(e.optString("mode_id", "life"))
                if (e == null) JSONObject().put("ok", false).put("event", JSONObject.NULL)
                else ok("event" to e)
            }
            "/components/install" -> {
                val m = b.optJSONObject("manifest") ?: return JSONObject().put("ok", false).put("error", "需要 manifest")
                if (!m.has("id") || !m.has("type") || !m.has("name"))
                    return JSONObject().put("ok", false).put("error", "manifest 缺少必填字段")
                if (!m.has("version")) m.put("version", "1.0.0")
                if (!m.has("minCore")) m.put("minCore", "1.0")
                if (!m.has("repo")) m.put("repo", "folder://modules/" + m.getString("id"))
                ok("component" to db.installComponent(m, b.optBoolean("enabled", true)))
            }
            "/components/toggle" -> {
                db.toggleComponent(b.optString("id"), b.optBoolean("enabled", true))
                ok("component" to JSONObject().put("id", b.optString("id")))
            }
            "/components/uninstall" -> ok("done" to db.uninstallComponent(b.optString("id")))
            "/components/scan" -> ok("result" to engine.scanModules())
            "/record/start" -> {
                val mode = b.optString("mode_id", "life")
                val s = db.startSession(mode)
                val i = Intent(ctx, RecordService::class.java)
                    .setAction(RecordService.ACTION_START)
                    .putExtra(RecordService.EXTRA_MODE, mode)
                    .putExtra(RecordService.EXTRA_SESSION, s.getString("id"))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ctx.startForegroundService(i) else ctx.startService(i)
                ok("session" to s, "q1" to "always_on")
            }
            "/record/push" -> {
                val n = b.optString("pcm_b64", "").length
                val sid = b.optString("session_id", "")
                db.pushChunk(sid, n)
                ok("session_id" to sid, "chunks" to 1, "bytes" to n)
            }
            "/record/stop" -> {
                ctx.startService(Intent(ctx, RecordService::class.java).setAction(RecordService.ACTION_STOP))
                ok("session" to (db.stopSession(b.optString("session_id")) ?: JSONObject.NULL),
                    "ingested" to true)
            }
            "/ima/export" -> engine.imaExport(b.optString("text"), b.optString("title", "履迹导出"))
            // ---- ima 连接窗口 ----
            "/ima/connect" -> engine.imaConnect(
                b.optString("client_id"), b.optString("api_key"),
                b.optString("kb_id"), b.optString("kb_name"))
            "/ima/kbs" -> engine.imaKbs()
            "/ima/kb" -> {
                engine.imaKbId = b.optString("kb_id")
                engine.imaKbName = b.optString("kb_name")
                engine.saveConfig()
                ok("kb_id" to engine.imaKbId, "kb_name" to engine.imaKbName)
            }
            "/ima/sync" -> engine.imaSync(
                b.optString("mode_id").ifBlank { null }, b.optString("scope", "all"))
            "/ima/reset" -> ok("cleared" to db.imaClear(), "note" to "已清空同步标记，下次会当新记录重推")
            "/ima/disconnect" -> engine.imaDisconnect()
            "/ima/share" -> {
                val text = engine.imaShareText(
                    b.optString("mode_id").ifBlank { null }, b.optString("scope", "today"))
                runOnUi {
                    try {
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                            .putExtra(Intent.EXTRA_SUBJECT, "履迹导出")
                            .putExtra(Intent.EXTRA_TEXT, text)
                        ctx.startActivity(Intent.createChooser(send, "发送到 ima 知识库")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                ok("chars" to text.length, "note" to "已弹出系统分享面板，选 ima 即可")
            }
            "/ima/open" -> {
                val it = ctx.packageManager.getLaunchIntentForPackage("com.tencent.ima")
                runOnUi {
                    try {
                        if (it != null) {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            ctx.startActivity(it)
                        } else ctx.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://ima.qq.com/agent-interface"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) { }
                }
                ok("installed" to (it != null),
                    "note" to if (it != null) "已打开 ima，去「我 → Claw 配置」复制凭证"
                    else "未装 ima，已打开网页版凭证页")
            }
            else -> JSONObject().put("ok", false).put("error", "未知路由 POST $path")
        }
    }

    /** 供 RecordService 写入音频：文件落在 filesDir/l1 下，WebView 可回放 */
    fun audioFile(name: String): File = File(File(ctx.filesDir, "l1"), name)
}
