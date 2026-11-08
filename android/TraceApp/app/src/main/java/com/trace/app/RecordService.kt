package com.trace.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * 常驻收音：前台服务 + AudioRecord 滚动分段写 WAV。
 *
 * ## 分段长度为什么不是「一小时一段」
 *
 * 一小时一段在生产上是三重灾难：
 *  1. **实时性归零** —— 录满一小时才开始转写，用户整个上午看不到任何一张卡；
 *  2. **失败代价太大** —— 一小时里任何一次网络抖动，整段重来，等于一上午白录；
 *  3. **模型吃不下** —— 一小时中文语音约 1.6 万字，抽卡一次输出必被截断，
 *     仍然要在处理侧二次切段，等于白存一个大文件再拆开。
 *
 * 取 **10 分钟目标 / 15 分钟硬上限**：
 *  - 15 分钟 < AudioChunker 的 20 分钟阈值 → **处理侧不再二次切段**，
 *    一次转写就到底，上下文完整，也不会「存一小时的段、转写时又拆成五段」这种重复解码；
 *  - 10 分钟音频 ≈ 2500 字转写，一次抽卡拿得完；出卡延迟控制在 1–2 分钟；
 *  - 切点落在**连续 1.2 秒静音**处，不把一句话切成两半。
 *
 * ## 崩溃安全
 *
 * 边录边写 + 每 2 秒回填 WAV 头，进程被系统杀掉最多丢 2 秒（旧版 30 秒一段丢 30 秒）。
 * 下次启动扫 `filesDir/tmp`，把没入队的 `rec_*.wav` 修好头直接补跑，不用重录。
 *
 * ## 静音门
 *
 * 整段有声时长 < 2 秒（10 分钟里几乎一点声音都没有）就不送转写，直接删。
 * 阈值和 AudioChunker 用的是同一个（20ms 窗平均幅度 260），宁可多送一次也不少送。
 */
class RecordService : Service() {

    companion object {
        const val ACTION_START = "com.trace.app.START"
        const val ACTION_STOP = "com.trace.app.STOP"
        const val EXTRA_MODE = "mode"
        const val EXTRA_SESSION = "session"
        private const val CHANNEL = "trace_record"
        private const val NOTI_ID = 1
        const val RATE = 16000

        /** 目标段长。到这个长度后，等下一个静音口下刀。 */
        const val TARGET_MS = 10 * 60 * 1000L
        /** 硬上限。一直很吵也必须在这里切，保证 < AudioChunker 的 20 分钟阈值。 */
        const val HARD_MS = 15 * 60 * 1000L
        /** 连续静音多久才允许下刀，避免把句子切开。 */
        const val SILENCE_MS = 1_200L
        /** 整段有声少于这个时长 → 判定为无人说话，不送转写。 */
        const val VOICE_MIN_MS = 2_000L
        /** 单次录音会话最长 4 小时，到点自动翻新，避免一个会话无限膨胀。 */
        const val SESSION_MAX_MS = 4 * 3600 * 1000L
        /** 待处理积压超过这个时长，段级分析就跳过，等会话结束按天补一次。 */
        const val BACKLOG_SKIP_ANALYSIS_MS = 15 * 60 * 1000L

        private val running = AtomicBoolean(false)
        fun isRunning() = running.get()

        /** 给 /record/status 用的快照。不走 Binder，静态变量足够。 */
        @Volatile
        var status: JSONObject = JSONObject().put("running", false)
            private set

        private fun publish(patch: JSONObject) {
            val j = runCatching { JSONObject(status.toString()) }.getOrElse { JSONObject() }
            for (k in patch.keys()) runCatching { j.put(k, patch.get(k)) }
            status = j
        }
    }

    private var captureThread: Thread? = null
    private var consumerThread: Thread? = null

    /** 一段音频 + 这一段的真实开始时刻。带时刻入队，流水线跑多久都不影响「当日」归属。 */
    private data class Segment(val file: File, val startedAt: String, val mode: String, val durMs: Long)

    private val queue = LinkedBlockingQueue<Segment>()
    private val seq = AtomicInteger(0)
    private var mode = "life"
    private var sessionId = ""
    private var sessionStartMs = 0L
    @Volatile private var droppedSilent = 0
    @Volatile private var deferredAnalysis = 0
    /** 收尾中：stop() 在后台线程等队列排空，这段时间内不允许再起一条录音链路。 */
    @Volatile private var stopping = false

    private val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.CHINA)
    private val stampFmt = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.CHINA)

    /** SimpleDateFormat 不是线程安全的，而它被录音线程与消费线程同时用 —— 统一加锁，
     *  长度上完全感觉不到（每 10 分钟才几十次），但能避免偶发的乱码时间。 */
    private fun iso(t: Long): String = synchronized(this) { fmt.format(Date(t)) }
    private fun stamp(t: Long): String = synchronized(this) { stampFmt.format(Date(t)) }
    private fun parseMs(s: String): Long? = synchronized(this) {
        try { fmt.parse(s)?.time } catch (e: Exception) { null }
    }
    private fun parseStamp(s: String): Date? = synchronized(this) {
        try { if (s.length >= 15) stampFmt.parse(s) else null } catch (e: Exception) { null }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                mode = intent.getStringExtra(EXTRA_MODE) ?: "life"
                sessionId = intent.getStringExtra(EXTRA_SESSION) ?: ""
                start()
            }
            // 停止要等录音线程收尾 + 队列里最多几十分钟的段跑完（每段都要上云转写），
            // 这段等待**绝不能留在主线程**——旧版就是在这里 join + sleep 最长 120 秒，
            // 用户一按停止就弹「应用无响应」。现在整段扔到后台线程，主线程立刻返回。
            ACTION_STOP -> {
                Thread { runCatching { stop() } }.also {
                    it.name = "trace-stop"
                    it.isDaemon = false
                    it.start()
                }
                return START_NOT_STICKY
            }
            // 系统在进程被杀后尝试重启服务时会给 null intent。录音是麦克风前台服务，
            // 重启后既没有用户上下文、也没有可复用的权限状态，Android 12+ 会直接抛
            // ForegroundServiceDidNotStartInTimeException。这里主动收摊，恢复交给
            // 下次启动时扫 tmp/ 的 recoverPending()，绝不硬起一条半残的录音链路。
            null -> {
                Log.w("RecordService", "收到空 intent，放弃自启；残留段等下次启动时恢复")
                runCatching { stopSelf() }
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    private fun start() {
        if (running.get()) return
        if (stopping) {
            // 上一轮还在收尾：宁可让用户多点一次，也不要两条链路同时往库里写
            publish(JSONObject().put("running", false).put("stopping", true)
                .put("error", "上一轮正在收尾，请等几秒再开始"))
            return
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "常驻录音", NotificationManager.IMPORTANCE_LOW)
            ch.description = "履迹正在后台录音"
            nm.createNotificationChannel(ch)
        }
        val noti = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL).setContentTitle("履迹 · 常驻录音中")
                .setContentText("每 10 分钟整理一段；停止后自动整理成时间轴")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        else Notification.Builder(this).setContentTitle("履迹 · 常驻录音中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        startForeground(NOTI_ID, noti)

        if (!TraceRuntime.ready()) {
            TraceRuntime.db = TraceDb(applicationContext)
            TraceRuntime.engine = TraceEngine(applicationContext, TraceRuntime.db)
            TraceRuntime.engine.loadConfig()
        }
        if (sessionId.isEmpty()) sessionId = TraceRuntime.db.startSession(mode).getString("id")
        sessionStartMs = System.currentTimeMillis()
        running.set(true)
        seq.set(0)
        droppedSilent = 0
        deferredAnalysis = 0
        publish(JSONObject().put("running", true).put("session_id", sessionId)
            .put("mode", mode).put("started_at", iso(sessionStartMs))
            .put("recorded_sec", 0).put("pending_segments", 0).put("backlog_sec", 0)
            .put("segment_seq", 0).put("dropped_silent", 0)
            .put("target_min", TARGET_MS / 60000).put("hard_max_min", HARD_MS / 60000)
            .put("policy", "每段目标 ${TARGET_MS / 60000} 分钟，静音 ≥${SILENCE_MS / 1000} 秒下刀，硬上限 ${HARD_MS / 60000} 分钟"))

        // 消费者：单线程按序跑流水线，不阻塞录音
        consumerThread = Thread {
            while (running.get() || queue.isNotEmpty()) {
                val seg = try {
                    queue.poll(300, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    break
                } ?: continue
                try {
                    // 积压太久说明机器/网络跟不上，就别再每段都跑分析，把分析留给「按天补一次」
                    val endedAt = (parseMs(seg.startedAt) ?: System.currentTimeMillis()) + seg.durMs
                    val skip = System.currentTimeMillis() - endedAt > BACKLOG_SKIP_ANALYSIS_MS
                    if (skip) deferredAnalysis++
                    TraceRuntime.engine.run(TraceEngine.Input(
                        source = "record", mode = seg.mode, audioFile = seg.file,
                        filename = seg.file.name, captureSession = "live",
                        startedAt = seg.startedAt, skipAnalysis = skip))
                } catch (ignored: Exception) {
                    // 单段失败不影响后续段
                } finally {
                    // 中间 WAV 用完即删；l1/ 里由流水线生成的存证件保留
                    try { seg.file.delete() } catch (ignored: Exception) { }
                    // 每段收完顺势清一次过期音频：一次索引查询，开销可忽略，
                    // 但能保证「连着录一整天」不会把存储吃穿
                    runCatching { TraceRuntime.db.l1PurgeExpired() }
                    runCatching { TraceRuntime.db.l1PurgeToCap() }
                    publish(JSONObject().put("pending_segments", queue.size)
                        .put("backlog_sec", backlogSec(queue.peek())))
                }
            }
        }.also {
            it.isDaemon = true
            it.name = "trace-consumer"
            it.start()
        }

        // 上一轮被系统杀掉时，没来得及处理的段还在 tmp/ 里 —— 修头、补跑
        recoverPending()

        // 开录前先按「72 小时 + 1.5GB 水位」清一次历史音频。
        // 旧版只把策略写在文案里，清理函数没有任何调用方，音频会无限堆积
        // （16k 单声道 WAV ≈ 115MB/小时），这里补上真正的执行点。
        Thread {
            runCatching { TraceRuntime.db.l1PurgeExpired() }
            runCatching { TraceRuntime.db.l1PurgeToCap() }
        }.also { it.isDaemon = true; it.name = "trace-l1-purge"; it.start() }

        // 录音线程：只做 AudioRecord.read + 写盘 + 入队
        captureThread = Thread { captureLoop() }.also {
            it.name = "trace-capture"
            it.start()
        }
    }

    /** 录音主循环：滚动写一段 WAV，到点（静音口 / 硬上限）收尾入队，立刻开下一段。 */
    private fun captureLoop() {
        val bufSize = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var rec: AudioRecord? = null
        var writer: AudioUtil.WavStreamWriter? = null
        var segFile: File? = null
        var segStartMs = System.currentTimeMillis()
        var curMs = 0L
        var voicedMs = 0L
        var lastVoiceMs = 0L
        var lastPubMs = 0L
        // 100ms 一个能量窗：既够灵敏，又不会被单个爆音带偏
        val frame = ShortArray(RATE / 10)
        var fn = 0

        fun openSegment() {
            val dir = File(filesDir, "tmp"); dir.mkdirs()
            val s = seq.incrementAndGet().toString().padStart(3, '0')
            segStartMs = System.currentTimeMillis()
            val f = File(dir, "rec_${mode}_${s}_${stamp(segStartMs)}.wav")
            segFile = f
            writer = AudioUtil.WavStreamWriter(f, RATE)
            curMs = 0; voicedMs = 0; lastVoiceMs = 0; fn = 0
        }

        fun closeSegment() {
            val f = segFile ?: return
            val w = writer
            val dur = w?.close() ?: 0L
            writer = null; segFile = null
            if (dur < 1_000L) { runCatching { f.delete() }; return }
            if (voicedMs < VOICE_MIN_MS) {
                // 几乎全程无声：不花转写钱，也不往时间轴塞空卡
                droppedSilent++
                runCatching { f.delete() }
                publish(JSONObject().put("dropped_silent", droppedSilent))
                return
            }
            TraceRuntime.db.pushChunk(sessionId, (dur * RATE / 1000 * 2).toInt())
            queue.offer(Segment(f, iso(segStartMs), mode, dur))
            publish(JSONObject().put("pending_segments", queue.size)
                .put("segment_seq", seq.get())
                .put("backlog_sec", backlogSec(queue.peek())))
            // 会话翻新：单会话最长 4 小时，防止一个 session 里堆几千条卡
            if (System.currentTimeMillis() - sessionStartMs > SESSION_MAX_MS) {
                runCatching {
                    TraceRuntime.db.stopSession(sessionId)
                    sessionId = TraceRuntime.db.startSession(mode).getString("id")
                    sessionStartMs = System.currentTimeMillis()
                    publish(JSONObject().put("session_id", sessionId)
                        .put("started_at", iso(sessionStartMs)))
                }
            }
        }

        try {
            val r = AudioRecord(MediaRecorder.AudioSource.MIC, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                (bufSize * 2).coerceAtLeast(4096))
            rec = r
            r.startRecording()
            openSegment()
            val buf = ShortArray(bufSize / 2)
            while (running.get()) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                curMs = writer?.append(buf, n) ?: curMs
                // 能量：滑窗填满 100ms 就算一次
                var i = 0
                while (i < n) {
                    val take = minOf(n - i, frame.size - fn)
                    System.arraycopy(buf, i, frame, fn, take)
                    fn += take; i += take
                    if (fn == frame.size) {
                        var acc = 0L
                        for (k in 0 until fn) acc += abs(frame[k].toInt())
                        if (acc / fn >= AudioChunker.QUIET) {
                            voicedMs += 100
                            lastVoiceMs = curMs
                        }
                        fn = 0
                    }
                }
                // 状态快照每秒刷一次就够。AudioRecord 每 20–40ms 回来一次，
                // 每次都 JSON 序列化 + 反序列化会把 GC 和电量一起吃掉。
                val nowWall = System.currentTimeMillis()
                if (nowWall - lastPubMs >= 1000) {
                    lastPubMs = nowWall
                    publish(JSONObject().put("recorded_sec", (curMs / 1000).toInt())
                        .put("voiced_sec", (voicedMs / 1000).toInt()))
                }
                val cut = curMs >= HARD_MS ||
                        (curMs >= TARGET_MS && curMs - lastVoiceMs >= SILENCE_MS)
                if (cut) { closeSegment(); openSegment() }
            }
            closeSegment()
        } catch (e: Exception) {
            // 权限被撤 / 麦克风被别的应用占住。旧版在这里完全静默：通知栏照样挂着
            // 「录音中」，用户以为在录，其实一个字节都没进来。现在必须让他看见。
            runCatching { writer?.close() }
            runCatching { segFile?.delete() }
            val msg = when {
                e is SecurityException -> "麦克风权限被拒绝或被系统收回"
                e is IllegalStateException -> "麦克风被其他应用占用"
                else -> "录音失败：" + (e.message ?: e.javaClass.simpleName)
            }
            Log.w("RecordService", msg)
            failAndStop(msg)
        } finally {
            try { rec?.stop(); rec?.release() } catch (ignored: Exception) { }
            publish(JSONObject().put("running", false).put("stopping", stopping))
        }
    }

    /** 录音链路起不来时的统一收摊：状态 + 通知栏 + 结束会话，都能让用户看见。
     *  注意**不能调 stop()**——它会 join 当前线程（就是自己），直接死锁。 */
    private fun failAndStop(msg: String) {
        stopping = true
        runCatching { if (sessionId.isNotEmpty()) TraceRuntime.db.stopSession(sessionId) }
        publish(JSONObject().put("running", false).put("error", msg).put("stopping", true))
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                Notification.Builder(this, CHANNEL) else Notification.Builder(this)
            nm.notify(NOTI_ID, b.setContentTitle("履迹 · 录音已中断")
                .setContentText("$msg，点开应用可重新开始")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(false).build())
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                stopForeground(STOP_FOREGROUND_REMOVE) else stopForeground(true)
        }
        runCatching { stopSelf() }
    }

    /** 上一轮遗留的段：按实际文件长度补好 WAV 头，直接补进队列。
     *  文件名里带模式和开始时刻（rec_life_003_20261107-223355.wav），脱离数据库也能还原。 */
    private fun recoverPending() {
        val dir = File(filesDir, "tmp")
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("rec_") && f.name.endsWith(".wav") }
            ?: return
        var fixed = 0
        for (f in files.sortedBy { it.name }) {
            val durMs = AudioUtil.wavFixHeader(f, RATE)
            if (durMs < 1_500L) { runCatching { f.delete() }; continue }
            val parts = f.name.removeSuffix(".wav").split("_")
            val m = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: mode
            val ts = parts.getOrNull(3) ?: ""
            val startedAt = iso((parseStamp(ts) ?: Date(System.currentTimeMillis() - durMs)).time)
            queue.offer(Segment(f, startedAt, m, durMs))
            fixed++
        }
        if (fixed > 0) {
            publish(JSONObject().put("recovered_segments", fixed)
                .put("pending_segments", queue.size))
        }
    }

    /** 队头那一段「本该什么时候处理完」—— 用它衡量积压，比数个数准。 */
    private fun backlogSec(head: Segment?): Int {
        val h = head ?: return 0
        val startMs = parseMs(h.startedAt) ?: return 0
        val endMs = startMs + h.durMs
        val lag = System.currentTimeMillis() - endMs
        return if (lag > 0) (lag / 1000).toInt() else 0
    }

    private fun stop() {
        stopping = true
        running.set(false)
        // 立刻把「正在收尾」推给界面：用户在等的是反馈，不是转圈
        publish(JSONObject().put("running", false).put("stopping", true)
            .put("pending_segments", queue.size)
            .put("note", "录音已停，正在整理队列里剩下的片段（可继续使用界面）"))
        // 先让录音线程收尾（把不足一段的尾巴落盘入队）
        captureThread?.join(3000)
        // 等队列全部落库，再结束会话；上限 120 秒防止网络调用卡死
        val deadline = System.currentTimeMillis() + 120_000
        while (queue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(100) } catch (ignored: InterruptedException) { break }
        }
        consumerThread?.join(3000)
        captureThread = null
        consumerThread = null
        if (sessionId.isNotEmpty()) TraceRuntime.db.stopSession(sessionId)
        runCatching { TraceRuntime.db.l1PurgeToCap() }
        publish(JSONObject().put("running", false).put("stopping", false)
            .put("pending_segments", queue.size)
            .put("deferred_analysis", deferredAnalysis).put("dropped_silent", droppedSilent))
        stopping = false

        // 段级分析被跳过过 → 结束后按天补一次（幂等，缓存命中就直接返回）
        if (deferredAnalysis > 0) {
            val m = mode
            Thread {
                runCatching {
                    val day = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())
                    TraceRuntime.engine.analyzeDay(m, day, false)
                }
            }.also { it.isDaemon = false; it.name = "trace-day-review"; it.start() }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }
}
