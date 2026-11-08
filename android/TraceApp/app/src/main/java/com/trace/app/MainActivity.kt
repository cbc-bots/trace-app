package com.trace.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream

/** 全局运行时，供前台录音服务复用同一份 db / engine。 */
object TraceRuntime {
    lateinit var db: TraceDb
    lateinit var engine: TraceEngine
    var bridge: TraceBridge? = null

    /** 原生音频选择器入口。由 TraceBridge 的 /pick 路由在 UI 线程调用。
     *  为什么要它：`<input type=file>` 选完文件后只能靠 JS 的 FileReader 读成 base64，
     *  35 分钟录音就是 40MB 字符串 + 解码副本，堆直接炸。走原生路径则流式拷进缓存，全程不进 JS。 */
    var pickHandler: (((File?, String?) -> Unit) -> Unit)? = null

    fun ready() = ::db.isInitialized && ::engine.isInitialized
}

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val reqCode = 1001

    /** <input type="file"> 的文件选择回调；未结束时再次点击必须先置空，否则 WebView 会吞掉后续点击。 */
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val REQ_FILE = 1002
        private const val REQ_PICK = 1003
    }

    /** 原生选择器的回调（/pick 路由阻塞等它返回） */
    private var pendingPick: ((File?, String?) -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!TraceRuntime.ready()) {
            TraceRuntime.db = TraceDb(applicationContext)
            TraceRuntime.engine = TraceEngine(applicationContext, TraceRuntime.db)
            TraceRuntime.engine.loadConfig()
            // 词表后台预热：27k 词解析约 100–200ms，放到启动时就做，第一段录音不必等
            Classifier.warmUp(applicationContext)
            seedBuiltins()
            TraceRuntime.engine.scanModules()
        }
        // 上次录音若是被系统杀掉的，tmp/ 里会留着已经落盘的段。录音服务本身不再
        // STICKY 自启（见 RecordService.onStartCommand），所以必须在这里给用户一个交代，
        // 否则他只会看到「昨晚录的三小时不见了」。真正的补跑在下次开录时由 recoverPending 完成。
        val pendingSegments = File(filesDir, "tmp").listFiles { f ->
            f.isFile && f.name.startsWith("rec_") && f.name.endsWith(".wav")
        }?.size ?: 0
        if (pendingSegments > 0) {
            android.widget.Toast.makeText(this,
                "上次录音异常中断，$pendingSegments 段已落盘；开始录音时会自动补跑",
                android.widget.Toast.LENGTH_LONG).show()
        }
        val bridge = TraceBridge(applicationContext, TraceRuntime.engine, TraceRuntime.db)
        TraceRuntime.bridge = bridge
        TraceRuntime.pickHandler = { cb -> startNativePick(cb) }

        // v2.1.5：原生两层都直接涂深色，WebView 不再设透明。
        // 部分 ROM 的硬件加速 WebView 会把透明背景合成为白色，导致底下的
        // FrameLayout 根本露不出来。现在即使 HTML 的某个背景图层被 GPU 丢弃，
        // WebView 自身和更底层的原生容器也都只会补深色，不会补白色。
        val rootView = android.widget.FrameLayout(this)
        rootView.setBackgroundColor(0xFF05080E.toInt())
        web = WebView(this)
        web.setBackgroundColor(0xFF05080E.toInt())
        rootView.addView(web, android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(rootView)
        // 异步桥需要在 UI 线程回调 JS；不 attach 时 callAsync 会静默丢弃结果
        bridge.attach(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            defaultTextEncodingName = "UTF-8"
            // 覆盖安装后 assets/index.html 会被 WebView 缓存，导致「装了新版还是旧界面」。
            // 页面全在本地，禁缓存没有代价。
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        web.clearCache(true)
        // 关掉 WebView 自己的「强制暗色」。
        // 主题是 DayNight，系统在暗色模式下会给 WebView 下发算法反色指令，
        // 后果是整个深色页面被翻译成浅色 —— 看上去就是整屏发白。
        // 用反射调用，避免为这一个开关引入 androidx.webkit 依赖。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                web.settings.javaClass
                    .getMethod("setForceDark", Int::class.javaPrimitiveType)
                    .invoke(web.settings, 0)   // WebSettings.FORCE_DARK_OFF
            } catch (_: Throwable) {
            }
        }
        web.addJavascriptInterface(bridge, "TraceBridge")
        web.webViewClient = object : WebViewClient() {
            /**
             * 渲染进程被系统回收（显存/内存吃紧，或 GPU 合成崩掉）时，WebView 的默认
             * 表现就是整页变白，而且不会自愈 —— 用户看到的就是「聊着聊着白屏」。
             * 返回 true 表示由我们接管：把这个已经废掉的 WebView 拆掉，重建 Activity，
             * 页面重新加载后即恢复。记录都在 SQLite 里，不丢数据。
             */
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                try {
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    view?.destroy()
                } catch (_: Throwable) {
                }
                if (!isFinishing && !isDestroyed) {
                    window.decorView.post { try { recreate() } catch (_: Throwable) { } }
                }
                return true
            }

            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                if (req.url.path == "/audio") {
                    val name = req.url.getQueryParameter("file") ?: return null
                    // v1.1 / B：canonical 前缀校验。File(base, 绝对路径) 会直接跳出 base，
                    // 只挡 ".." 是不够的 —— 必须解析后确认仍在 l1/ 子树内。
                    val base = File(filesDir, "l1").canonicalFile
                    val target = File(base, name).canonicalFile
                    if (target != base && !target.path.startsWith(base.path + File.separator)) {
                        return null
                    }
                    if (!target.isFile) {
                        // 已按 72h 策略清理：返回 410 + 说明，而不是含糊的 404
                        if (TraceRuntime.db.l1IsPurged("l1/" + target.name)) {
                            val body = "音频存证已按 72 小时保留策略清理；转写原文、事件卡与分析结果仍完整保留"
                            val resp = WebResourceResponse("text/plain", "UTF-8",
                                ByteArrayInputStream(body.toByteArray(Charsets.UTF_8)))
                            resp.setStatusCodeAndReasonPhrase(410, "Gone")
                            return resp
                        }
                        return null
                    }
                    val mime = when (target.extension.lowercase()) {
                        "wav" -> "audio/wav"; "mp3" -> "audio/mpeg"
                        "m4a", "aac" -> "audio/mp4"; "ogg" -> "audio/ogg"
                        else -> "application/octet-stream"
                    }
                    return WebResourceResponse(mime, "UTF-8", FileInputStream(target))
                }
                return super.shouldInterceptRequest(view, req)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            @Suppress("DEPRECATION")
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams
            ): Boolean {
                // 上一次选择尚未结束又触发时，先通知 WebView 取消，避免后续点击永远无响应。
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback

                val intent = try {
                    // API 21+：createIntent() 会带上前端 accept 的类型；minSdk 26 可直接使用。
                    fileChooserParams.createIntent().apply {
                        action = Intent.ACTION_GET_CONTENT
                        addCategory(Intent.CATEGORY_OPENABLE)
                        // accept 里既有 audio/* 也有 .wav/.m4a/.mp3 等扩展名，
                        // 统一放宽到音频通配并补充常见 MIME，避免被限死到单一类型。
                        type = "audio/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                            "audio/*", "audio/wav", "audio/x-wav", "audio/mpeg",
                            "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg",
                            "application/octet-stream"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    }
                } catch (e: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "audio/*"
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                    }
                }

                return try {
                    startActivityForResult(intent, REQ_FILE)
                    true
                } catch (e: Exception) {
                    // 部分 ROM 可能没有能处理该 Intent 的 Activity，必须回调 null 释放 WebView。
                    filePathCallback.onReceiveValue(null)
                    fileChooserCallback = null
                    true
                }
            }
        }
        web.loadUrl("file:///android_asset/index.html")
        requestPerms()
    }

    /**
     * 原生挑音频：ACTION_OPEN_DOCUMENT → 流式拷进 cacheDir/pick → 回调文件路径。
     * 拷完之后音频就是一个普通 File，主流程按流式解码处理，跟文件多大都没关系。
     */
    private fun startNativePick(cb: (File?, String?) -> Unit) {
        pendingPick?.invoke(null, null)   // 上一次没结束的先放掉，避免永远等下去
        pendingPick = cb
        val primary = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "audio/*", "audio/wav", "audio/x-wav", "audio/mpeg",
                "audio/mp4", "audio/x-m4a", "audio/aac", "audio/ogg", "audio/amr"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        }
        try {
            startActivityForResult(primary, REQ_PICK)
        } catch (e: Exception) {
            try {
                startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "audio/*"
                }, REQ_PICK)
            } catch (e2: Exception) {
                pendingPick = null
                cb(null, null)
            }
        }
    }

    /** 把 content:// 流式复制到缓存目录，返回文件与展示名（DISPLAY_NAME 拿不到就用兜底名） */
    private fun copyPicked(uri: Uri, done: (File?, String) -> Unit) {
        Thread {
            var name = "audio"
            try {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getString(0)
                        if (!n.isNullOrBlank()) name = n
                    }
                }
            } catch (ignored: Exception) { }
            var f: File? = null
            try {
                val dir = File(cacheDir, "pick").apply { mkdirs() }
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
                val out = File(dir, "${System.currentTimeMillis()}_$safe")
                contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { o -> input.copyTo(o, 64 * 1024) }
                }
                if (out.length() > 0) f = out else out.delete()
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "复制所选音频失败: ${e.message}")
            }
            runOnUiThread { done(f, name) }
        }.start()
    }

    private fun seedBuiltins() {
        val db = TraceRuntime.db
        val builtins = listOf(
            Triple("life-emotion", "classifier", "人生记录 · 情绪与事件分类"),
            Triple("study-topic", "classifier", "学习 · 知识点归类"),
            Triple("growth-quadrant", "classifier", "成长 · 四象限归类"),
            Triple("timeline", "view", "时间轴"),
            Triple("mastery", "view", "知识点掌握度"),
            Triple("quadrant", "view", "四象限成长图"))
        for ((id, type, name) in builtins) {
            val m = JSONObject().put("id", id).put("type", type).put("name", name)
                .put("version", "1.0.0").put("minCore", "1.0")
                .put("repo", "local://builtin/$id").put("source", "builtin")
            db.installComponent(m, true)
        }
        // 人格预设：manifest.prompt_md 会被 TraceEngine.persona() 取走，
        // 作为对话的 system prompt。切换人格 = 换这一句。
        val personas = listOf(
            listOf("base", "基础助手", "回答直接、具体",
                "你是履迹的助手。回答要短，优先引用记录里的原话；不确定就说不确定，不要编。"),
            listOf("listener", "倾听者", "先接住情绪，再说话",
                "你是履迹的倾听者。先接住对方的情绪，用一两句话复述你听到的重点，再往下说。" +
                "不评判、不急着给建议，除非对方主动问。"),
            listOf("coach", "行动派", "指出模式，给能做的动作",
                "你是履迹的行动教练。直接指出反复出现的模式，以及它正在付出的代价。" +
                "每次只给一到两个今天就能做的动作，不讲大道理，不铺垫。"),
            listOf("analyst", "观察者", "只归纳事实，不做评价",
                "你是履迹的观察者。只做事实归纳：时间、频次、变化趋势。" +
                "明确区分「记录里写了什么」和「你的推测」，不做情绪评价。"))
        for (a in personas) {
            val m = JSONObject().put("id", a[0]).put("type", "agent").put("name", a[1])
                .put("description", a[2]).put("prompt_md", a[3])
                .put("version", "1.0.0").put("minCore", "1.0")
                .put("repo", "local://builtin/${a[0]}").put("source", "builtin")
            db.installComponent(m, true)
        }
    }

    private fun requestPerms() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty())
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), reqCode)
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (code == reqCode) web.reload()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_PICK) {
            val cb = pendingPick
            pendingPick = null
            val uri = if (resultCode == RESULT_OK) data?.data else null
            if (uri == null || cb == null) { cb?.invoke(null, null); return }
            copyPicked(uri) { f, n -> cb(f, n) }
            return
        }
        if (requestCode == REQ_FILE) {
            val callback = fileChooserCallback
            fileChooserCallback = null
            val uris = if (resultCode == RESULT_OK) extractUris(data) else null
            // 用户取消或 resultCode != OK 时 uris 为 null，回调 null 才能结束上一次选择。
            callback?.onReceiveValue(uris)
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** 从返回的 Intent 中取出单个 Uri，或 ClipData 里的多个 Uri。 */
    private fun extractUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            val uris = mutableListOf<Uri>()
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri
                if (uri != null) uris.add(uri)
            }
            if (uris.isNotEmpty()) return uris.toTypedArray()
        }
        val single = data.data
        return if (single != null) arrayOf(single) else null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
