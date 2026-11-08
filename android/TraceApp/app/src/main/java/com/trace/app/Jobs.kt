package com.trace.app

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * 长任务登记处。
 *
 * 为什么需要：35 分钟录音的整条流水线（解码→切段→逐段转写→抽卡→分析）要几分钟，
 * 桥接层如果不立刻返回，前端只能干等，用户会以为卡死。
 * 现在 /upload 立刻返回 job_id，前端轮询 /job 拿阶段名和百分比。
 */
object Jobs {

    class Job(val id: String) {
        @Volatile var stage: String = "排队中"
        @Volatile var pct: Int = 0
        @Volatile var done: Boolean = false
        @Volatile var error: String? = null
        @Volatile var result: JSONObject? = null
        val startedAt: Long = System.currentTimeMillis()
    }

    private val seq = AtomicInteger(0)
    private val map = ConcurrentHashMap<String, Job>()

    /** 同一时刻只跑一条音频流水线：两条并发会把 SQLite 写锁和 CPU 一起拖死 */
    private val pipelineLock = ReentrantLock()

    fun create(): Job {
        val j = Job("job-" + System.currentTimeMillis().toString(36) + "-" + seq.incrementAndGet())
        map[j.id] = j
        // 只留最近 20 条，别把内存当日志用
        if (map.size > 20) map.keys.take(map.size - 20).forEach { map.remove(it) }
        return j
    }

    fun run(j: Job, block: () -> JSONObject) {
        val t = Thread {
            j.stage = "等待前一条任务"
            pipelineLock.lock()
            try {
                j.stage = "开始"
                val r = block()
                j.result = r
                if (!r.optBoolean("ok", true) && r.optString("error").isNotBlank())
                    j.error = r.optString("error")
                j.pct = 100
                j.stage = if (j.error != null) "失败" else "完成"
            } catch (e: Exception) {
                j.error = e.message ?: e.javaClass.simpleName
                j.stage = "失败"
                Log.w("Jobs", "任务 ${j.id} 失败: ${e.message}")
            } finally {
                j.done = true
                pipelineLock.unlock()
            }
        }
        t.isDaemon = true
        t.start()
    }

    fun get(id: String): Job? = map[id]
}

/**
 * 原生选择器挑出来的文件登记表。
 * 走这条路，音频**完全不经过 JS / base64**，35 分钟录音也不会在桥接层撑爆堆。
 */
object PickedFiles {
    private val map = ConcurrentHashMap<String, File>()
    private val seq = AtomicInteger(0)

    fun put(f: File): String {
        val k = "pk" + System.currentTimeMillis().toString(36) + seq.incrementAndGet()
        map[k] = f
        // 顺手清掉一小时前挑的临时文件
        val cut = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1)
        f.parentFile?.listFiles()?.forEach { if (it.lastModified() < cut) runCatching { it.delete() } }
        if (map.size > 12) map.keys.take(map.size - 12).forEach { map.remove(it) }
        return k
    }

    fun get(k: String): File? = map[k]?.takeIf { it.isFile }
}
