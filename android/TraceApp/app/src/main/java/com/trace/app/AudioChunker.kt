package com.trace.app

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 长录音切分器：**边解码边写盘**，全程只驻留一个 20ms 窗（640 字节）。
 *
 * 为什么需要它 —— 26 分钟以上的录音，整段解码成 16k 单声道 PCM 就是 67MB，
 * ByteArrayOutputStream 再复制一次，256MB 的 Dalvik/ART 堆直接
 * `Failed to allocate a 67108880 byte allocation`。
 *
 * 顺带解决另外两件事：
 *  1. 大模型一次吃 35 分钟音频，转写会漏、抽卡会糊；按 ≤18 分钟切段后每段单独转写再合并。
 *  2. 切点优先落在静音处，尽量不把一句话切成两半。
 *
 * 输出统一是 16k mono WAV（无损、通用），再尝试转一份 AAC m4a 用于上传（体积 1/8）。
 */
object AudioChunker {

    private const val TAG = "AudioChunker"
    const val RATE = AudioUtil.RATE
    private const val WIN = 320                       // 20ms @ 16k
    /** 20ms 窗平均幅度低于此值算安静。RecordService 的「有没有人说话」判定也用它，
     *  两处共用同一个阈值，免得录音侧判成静音、切分侧又判成有声。 */
    const val QUIET = 260
    /** 只有**确定超过这个长度**才切段。≤8 分钟的录音原样一段，不折腾。
     *  为什么要降到 8 分钟：段长直接决定单次 omni 请求的耗时，段越长越容易撞
     *  HTTP 读超时（180s）。8 分钟音频的转写约 90~120 秒，还在安全区内。 */
    const val LONG_LIMIT_MS = 8 * 60 * 1000L
    /** 长录音的期望段长。按它算段数再均分，避免出现 1 分钟的碎尾。
     *  5 分钟音频 → omni 约 60~90 秒返回，留出 2 倍余量再撞不超时。
     *  段数变多不是坏事：多段并发跑，墙钟时间反而更短，单段失败也只丢 5 分钟。 */
    const val LONG_TARGET_MS = 5 * 60 * 1000L
    const val HARD_MAX_MS = 6 * 60 * 1000L            // 绝对上限，等不到静音点也不能超它
    private const val MIN_TAIL_MS = 15_000L           // 尾巴太短就和上一段合并
    /** AAC 编码器的并发实例数。系统对同时打开的编码器数量有上限（常见 2~4），
     *  开太多会 configure 失败；失败会自动回落到 WAV，不影响转写正确性。 */
    private const val ENCODE_PARALLEL = 2

    data class Chunk(
        val index: Int,
        val startMs: Long,
        val endMs: Long,
        val wav: File,
        val m4a: File?,
        val speechMs: Long,
        /** 语音段（相对本段起点，毫秒）[start, end] */
        val vad: List<LongArray>
    ) {
        val audio: File get() = m4a ?: wav
        val mime: String get() = if (m4a != null) "audio/mp4" else "audio/wav"
        val durMs: Long get() = (endMs - startMs).coerceAtLeast(0)
    }

    data class Result(
        val chunks: List<Chunk>,
        val durationMs: Long,
        val srcRate: Int,
        val channels: Int,
        val error: String? = null
    )

    /**
     * 把 src 切成若干段（每段 < 20 分钟）。outDir 里的旧文件会先清空。
     * 解码失败时返回空列表 + error，调用方按「无法转写」处理，不要假装成功。
     */
    fun split(src: File, outDir: File,
              onEncode: ((done: Int, total: Int) -> Unit)? = null): Result {
        try {
            outDir.mkdirs()
            outDir.listFiles()?.forEach { runCatching { it.delete() } }
        } catch (e: Exception) {
            Log.w(TAG, "准备切分目录失败: ${e.message}")
        }
        // 先看总时长：≤20 分钟就整段走，切段只服务于「大模型吃不下」的长录音
        val total = probeDurationMs(src)
        val long = total <= 0L || total > LONG_LIMIT_MS
        var target = Long.MAX_VALUE
        var soft = HARD_MAX_MS
        if (long) {
            val n = if (total > 0) ((total + LONG_TARGET_MS - 1) / LONG_TARGET_MS).coerceAtLeast(1) else 1
            target = if (total > 0) (total / n).coerceAtMost(HARD_MAX_MS - 1000) else LONG_TARGET_MS
            soft = (target + 60_000L).coerceAtMost(HARD_MAX_MS)
            Log.i(TAG, "长录音 ${total / 1000}s → $n 段，每段约 ${target / 1000}s")
        }
        val w = Writer(outDir, target, soft)
        var info: AudioUtil.StreamInfo? = null
        var err: String? = null
        try {
            info = AudioUtil.decodeStream(src.absolutePath) { w.feed(it) }
            if (info == null) err = "解码失败：系统解码器与 WAV 解析都没能读出音频"
        } catch (e: Exception) {
            err = "解码异常：${e.message}"
            Log.w(TAG, "split 失败: ${e.message}")
        }
        val merged = w.finish()
        if (merged.isEmpty()) {
            return Result(emptyList(), 0, info?.rate ?: 0, info?.channels ?: 0,
                err ?: "没有解出任何音频内容")
        }
        // 转 AAC：上传体积从 19MB/10 分钟降到 2.4MB；失败就继续用 WAV。
        // 并发 2 路 —— 编码是纯 CPU 活，串行时 35 分钟录音要 7~12 分钟，
        // 这才是长录音「卡在 6% 不动」的真正原因，必须并行掉。
        val pool = java.util.concurrent.Executors.newFixedThreadPool(ENCODE_PARALLEL) { r ->
            Thread(r, "trace-aac").apply { isDaemon = true }
        }
        val encDone = java.util.concurrent.atomic.AtomicInteger(0)
        val futures = merged.mapIndexed { i, c ->
            pool.submit(java.util.concurrent.Callable<Chunk> {
                val m4a = File(outDir, c.wav.nameWithoutExtension + ".m4a")
                val ok = AudioUtil.wavToM4a(c.wav, m4a)
                onEncode?.invoke(encDone.incrementAndGet(), merged.size)
                c.copy(index = i, m4a = if (ok && m4a.length() > 1024) m4a else null)
            })
        }
        // 单段编码失败不能让整条流水线崩：回落成 WAV 继续，只是上传大一点
        val final = futures.mapIndexed { i, fu ->
            try { fu.get() } catch (e: Exception) {
                Log.w(TAG, "第 ${i + 1} 段转 AAC 失败，改用 WAV: ${e.message}")
                merged[i].copy(index = i, m4a = null)
            }
        }
        pool.shutdown()
        return Result(final, final.last().endMs, info?.rate ?: 0, info?.channels ?: 0, err)
    }

    /** 容器里自带的时长（µs→ms）。拿不到返回 0，调用方按「不确定」处理（保守切段）。 */
    private fun probeDurationMs(src: File): Long {
        try {
            val ex = android.media.MediaExtractor()
            try {
                ex.setDataSource(src.absolutePath)
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    if ((f.getString(android.media.MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                        if (f.containsKey(android.media.MediaFormat.KEY_DURATION)) {
                            val us = f.getLong(android.media.MediaFormat.KEY_DURATION)
                            if (us > 0) return us / 1000
                        }
                        break
                    }
                }
            } finally { runCatching { ex.release() } }
        } catch (e: Exception) { }
        // WAV：data 长度 / 每秒字节数
        try {
            RandomAccessFile(src, "r").use { r ->
                val head = ByteArray(minOf(src.length(), 8192L).toInt())
                r.readFully(head)
                var pos = 12; var byteRate = 0; var dataLen = -1L
                while (pos + 8 <= head.size) {
                    val tag = String(head, pos, 4, Charsets.US_ASCII)
                    val len = ByteBuffer.wrap(head, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                    if (tag == "fmt " && pos + 24 <= head.size)
                        byteRate = ByteBuffer.wrap(head, pos + 16, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (tag == "data") { dataLen = len; break }
                    pos += (8 + len + (if (len % 2 == 1L) 1 else 0)).toInt()
                }
                if (byteRate > 0 && dataLen > 0) return dataLen * 1000 / byteRate
            }
        } catch (e: Exception) { }
        return 0
    }

    /** 流式写入器：一窗一窗写，遇到该切的位置就收尾并开下一段。 */
    private class Writer(private val dir: File, private val targetMs: Long, private val softMs: Long) {

        private var raf: RandomAccessFile? = null
        private var curFile: File? = null
        private var curBytes = 0L            // 本段已写入的 data 字节
        private var curStartMs = 0L
        private var globalMs = 0L
        private var carry = ShortArray(0)
        private var energies = ArrayList<Int>()
        private var scanning = false         // 已过目标时长，正在等一个安静的落点
        private var seq = 0
        private val out = ArrayList<Chunk>()
        private val win = ByteArray(WIN * 2)

        fun feed(block: ShortArray) {
            if (block.isEmpty()) return
            val data: ShortArray =
                if (carry.isEmpty()) block
                else ShortArray(carry.size + block.size).also {
                    carry.copyInto(it); block.copyInto(it, carry.size)
                }
            var i = 0
            while (i + WIN <= data.size) {
                writeWindow(data, i)
                i += WIN
            }
            carry = if (i >= data.size) ShortArray(0) else data.copyOfRange(i, data.size)
        }

        private fun writeWindow(data: ShortArray, off: Int) {
            if (raf == null) open()
            var sum = 0L
            for (k in 0 until WIN) {
                val v = data[off + k].toInt()
                sum += abs(v)
                val s = v
                win[k * 2] = (s and 0xFF).toByte()
                win[k * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            raf!!.write(win)
            curBytes += WIN * 2
            val e = (sum / WIN).toInt()
            energies.add(e)
            globalMs += 20
            val curMs = curBytes / 2 * 1000L / RATE
            if (!scanning && curMs >= targetMs) scanning = true
            if ((scanning && e < QUIET) || curMs >= softMs || curMs >= HARD_MAX_MS) closeChunk()
        }

        private fun open() {
            val f = File(dir, "part_%02d.wav".format(seq + 1))
            val r = RandomAccessFile(f, "rw")
            r.setLength(0)
            r.write(header(0))     // 先占 44 字节，收尾时回填长度
            raf = r; curFile = f; curBytes = 0; energies = ArrayList(); scanning = false
        }

        private fun closeChunk() {
            val r = raf ?: return
            val f = curFile ?: return
            val bytes = curBytes
            try {
                r.seek(0)
                r.write(header(bytes))
                r.close()
            } catch (e: Exception) {
                Log.w(TAG, "收尾 WAV 头失败: ${e.message}")
                runCatching { r.close() }
            }
            raf = null; curFile = null
            if (bytes <= 0) { runCatching { f.delete() }; return }
            val durMs = bytes / 2 * 1000L / RATE
            val (vad, speech) = vadOf(energies)
            out.add(Chunk(out.size, curStartMs, curStartMs + durMs, f, null, speech, vad))
            curStartMs += durMs
            curBytes = 0; energies = ArrayList(); scanning = false; seq++
        }

        /** 收尾：关文件 → 尾巴太短则并入上一段 → 去掉空段 */
        fun finish(): List<Chunk> {
            closeChunk()
            if (carry.isNotEmpty()) carry = ShortArray(0)
            mergeTail(out)
            return out.filter { it.endMs > it.startMs }
        }

        private fun mergeTail(list: MutableList<Chunk>) {
            while (list.size >= 2 && list.last().durMs < MIN_TAIL_MS) {
                val last = list.last()
                val prev = list[list.size - 2]
                try {
                    // 尾巴 < 15 秒 = < 480KB，整读安全
                    val all = last.wav.readBytes()
                    if (all.size <= 44) { last.wav.delete(); list.removeAt(list.size - 1); continue }
                    RandomAccessFile(prev.wav, "rw").use { r ->
                        r.seek(r.length())
                        r.write(all, 44, all.size - 44)
                        val total = r.length() - 44
                        r.seek(0)
                        r.write(header(total))
                    }
                    last.wav.delete()
                    val shift = prev.durMs
                    list[list.size - 2] = prev.copy(
                        endMs = last.endMs,
                        speechMs = prev.speechMs + last.speechMs,
                        vad = prev.vad + last.vad.map { longArrayOf(it[0] + shift, it[1] + shift) })
                    list.removeAt(list.size - 1)
                } catch (e: Exception) {
                    Log.w(TAG, "合并尾巴失败: ${e.message}")
                    break
                }
            }
        }

        private fun header(dataBytes: Long): ByteArray {
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()); h.putInt((36 + dataBytes).toInt()); h.put("WAVE".toByteArray())
            h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
            h.putInt(RATE); h.putInt(RATE * 2); h.putShort(2); h.putShort(16)
            h.put("data".toByteArray()); h.putInt(dataBytes.toInt())
            return h.array()
        }

        /** 从 20ms 窗能量推语音段：合并 <300ms 的空隙，丢掉 <400ms 的碎片 */
        private fun vadOf(e: List<Int>): Pair<List<LongArray>, Long> {
            val raw = ArrayList<LongArray>()
            var start = -1
            for (k in e.indices) {
                val on = e[k] > QUIET
                if (on && start < 0) start = k
                else if (!on && start >= 0) {
                    if (k * 20L - start * 20L >= 400) raw.add(longArrayOf(start * 20L, k * 20L))
                    start = -1
                }
            }
            if (start >= 0) raw.add(longArrayOf(start * 20L, e.size * 20L))
            val merged = ArrayList<LongArray>()
            for (s in raw) {
                val last = merged.lastOrNull()
                if (last != null && s[0] - last[1] < 300) last[1] = s[1] else merged.add(s.copyOf())
            }
            var speech = 0L
            for (s in merged) speech += (s[1] - s[0])
            return merged to speech
        }
    }
}
