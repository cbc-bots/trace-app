package com.trace.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** 音频工具：任意手机录音格式解码为 16k mono PCM、WAV 读写、能量型 VAD。
 *  用 Android 系统 MediaCodec，不需要 ffmpeg / 第三方库。
 *
 *  v1.6 起新增**流式**通道（decodeStream）：35 分钟录音整段解码成 PCM 是 67MB，
 *  再经 ByteArrayOutputStream.toByteArray() 复制一次就是两次 67MB —— 256MB 堆直接 OOM。
 *  流式接口逐块回调，内存占用与录音长度无关。 */
object AudioUtil {

    private const val TAG = "AudioUtil"
    const val RATE = 16000

    data class Pcm(val samples: ShortArray, val rate: Int)

    /** 源文件的基本参数，流式解码时顺带返回给调用方做记录 */
    data class StreamInfo(val rate: Int, val channels: Int)

    // ---------------------------------------------------------------- 流式（推荐）

    /** 逐块把任意格式解码成 16k mono s16 并回调。回调里的 ShortArray 是复用的临时块，用完即弃。
     *  返回源采样率/声道（仅用于日志与统计），失败返回 null。 */
    fun decodeStream(path: String, onBlock: (ShortArray) -> Unit): StreamInfo? {
        return try {
            decodeWithCodec(path, onBlock)
        } catch (e: Exception) {
            Log.w(TAG, "decodeStream 失败，回退 WAV 解析：${e.message}")
            try { wavStream(path, onBlock) } catch (e2: Exception) { null }
        }
    }

    private fun decodeWithCodec(path: String, onBlock: (ShortArray) -> Unit): StreamInfo? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            var idx = -1; var rate = RATE; var ch = 1; var mime = ""
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    idx = i; mime = m
                    rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE, RATE)
                    ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                    break
                }
            }
            // WAV 在部分 ROM 上没有 audio track，直接走自解析
            if (idx < 0) { ex.release(); return wavStream(path, onBlock) }

            ex.selectTrack(idx)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(ex.getTrackFormat(idx), null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val rs = MonoResampler(rate, RATE)
            val chn = ch.coerceAtLeast(1)
            var inEos = false; var outEos = false
            while (!outEos) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(ib, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    ob.order(ByteOrder.LITTLE_ENDIAN)
                    val sb = ob.asShortBuffer()
                    val cnt = sb.remaining() / chn
                    if (cnt > 0) {
                        val mono = FloatArray(cnt)
                        for (k in 0 until cnt) {
                            var acc = 0f
                            for (c in 0 until chn) acc += sb.get().toFloat()
                            mono[k] = acc / chn
                        }
                        val out = rs.process(mono)
                        if (out.isNotEmpty()) onBlock(out)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                }
            }
            codec.stop(); codec.release()
            val tail = rs.flush()
            if (tail.isNotEmpty()) onBlock(tail)
            return StreamInfo(rate, ch)
        } finally {
            try { ex.release() } catch (ignored: Exception) { }
        }
    }

    /** WAV 自解析流式读取（16/8 位、任意声道、任意采样率） */
    private fun wavStream(path: String, onBlock: (ShortArray) -> Unit): StreamInfo? {
        val f = File(path)
        if (f.length() < 48) return null
        RandomAccessFile(f, "r").use { raf ->
            val head = ByteArray(minOf(f.length(), 8192L).toInt())
            raf.readFully(head)
            var pos = 12
            var rate = RATE; var ch = 1; var bits = 16
            var dataOff = -1L; var dataLen = 0L
            while (pos + 8 <= head.size) {
                val tag = String(head, pos, 4, Charsets.US_ASCII)
                val len = ByteBuffer.wrap(head, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                if (tag == "fmt " && pos + 24 <= head.size) {
                    val v = ByteBuffer.wrap(head, pos + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
                    ch = v.getShort(2).toInt(); rate = v.getInt(4); bits = v.getShort(14).toInt()
                }
                if (tag == "data") { dataOff = (pos + 8).toLong(); dataLen = len; break }
                pos += (8 + len + (if (len % 2 == 1L) 1 else 0)).toInt()
            }
            if (dataOff < 0 || (bits != 16 && bits != 8)) return null
            if (ch <= 0) ch = 1
            raf.seek(dataOff)
            var left = if (dataLen > 0) minOf(dataLen, raf.length() - dataOff) else raf.length() - dataOff
            val rs = MonoResampler(rate, RATE)
            val buf = ByteArray(64 * 1024)
            val frame = bits / 8 * ch
            while (left > 0) {
                var want = minOf(buf.size.toLong(), left).toInt()
                want -= want % frame
                if (want <= 0) break
                val n = raf.read(buf, 0, want)
                if (n <= 0) break
                left -= n
                val cnt = n / frame
                if (cnt <= 0) continue
                val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
                val mono = FloatArray(cnt)
                for (k in 0 until cnt) {
                    var acc = 0f
                    for (c in 0 until ch) acc += if (bits == 16) bb.short.toFloat() else (bb.get().toInt() shl 8).toFloat()
                    mono[k] = acc / ch
                }
                val out = rs.process(mono)
                if (out.isNotEmpty()) onBlock(out)
            }
            return StreamInfo(rate, ch)
        }
    }

    /** 跨 buffer 连续的线性重采样：把 from 采样率单声道浮点流变成 16k 的 s16 块。
     *  关键是 t（下一个输出点在「上一块末尾 + 本块」坐标系里的位置）要跨块保留，
     *  否则每块边界都会插值错位，噪声会一路累积成嗡声。 */
    private class MonoResampler(private val from: Int, private val to: Int) {
        private val step = if (to <= 0) 1.0 else from.toDouble() / to
        private var t = 1.0
        private var last = 0f
        private var started = false

        fun process(x: FloatArray): ShortArray {
            if (x.isEmpty()) return ShortArray(0)
            if (!started) { started = true; last = x[0]; t = 1.0 }
            val n = x.size
            var pos = t
            // 最多 n/step + 2 个输出点
            val cap = ((n / step) + 2).toInt().coerceAtLeast(2)
            val out = ShortArray(cap)
            var o = 0
            while (o < cap) {
                val i0 = pos.toInt()
                if (i0 > n - 1) break
                val frac = (pos - i0).toFloat()
                val a = if (i0 == 0) last else x[i0 - 1]
                val b = x[i0]
                val v = a + (b - a) * frac
                out[o++] = (v.toInt()).coerceIn(-32768, 32767).toShort()
                pos += step
            }
            t = pos - n
            last = x[n - 1]
            return if (o == out.size) out else out.copyOf(o)
        }

        fun flush(): ShortArray = ShortArray(0)
    }

    // ---------------------------------------------------------------- 整段（旧接口，短音频用）

    /** MediaExtractor + MediaCodec 解码 m4a/mp3/aac/amr/webm/wav → 16k mono s16。
     *  **注意**：整段驻留内存，只适合短音频；长录音请用 decodeStream。 */
    fun decodeTo16kMono(path: String): Pcm? {
        return try {
            val ex = MediaExtractor()
            ex.setDataSource(path)
            var audioIdx = -1
            var mime = ""
            var srcRate = 16000
            var channels = 1
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val m = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (m.startsWith("audio/")) {
                    audioIdx = i; mime = m
                    srcRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE, 16000)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 1)
                    break
                }
            }
            if (audioIdx < 0) { ex.release(); return decodeWav(path) }

            ex.selectTrack(audioIdx)
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(ex.getTrackFormat(audioIdx), null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            val buf = ByteArrayOutputStream()
            var sawInputEos = false
            var sawOutputEos = false
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inBuf: ByteBuffer = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(inBuf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    outBuf.position(info.offset); outBuf.limit(info.offset + info.size)
                    val b = ByteArray(info.size); outBuf.get(b)
                    buf.write(b)
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
            codec.stop(); codec.release(); ex.release()
            val raw = buf.toByteArray()
            // 交错 s16 → 单声道
            val mono = ShortArray(raw.size / 2 / channels.coerceAtLeast(1))
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            var j = 0
            while (bb.remaining() >= 2 * channels && j < mono.size) {
                var acc = 0
                for (c in 0 until channels) acc += bb.short.toInt()
                mono[j++] = (acc / channels).toShort()
            }
            Pcm(resample(mono, srcRate, 16000), 16000)
        } catch (e: Exception) {
            decodeWav(path)
        }
    }

    private fun decodeWav(path: String): Pcm? = try {
        val f = File(path)
        val b = f.readBytes()
        if (b.size < 44) null
        else {
            var pos = 12
            var rate = 16000; var ch = 1; var bits = 16
            var dataOff = -1; var dataLen = 0
            while (pos + 8 <= b.size) {
                val tag = String(b, pos, 4)
                val len = ByteBuffer.wrap(b, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                if (tag == "fmt " && pos + 16 <= b.size) {
                    val v = ByteBuffer.wrap(b, pos + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
                    ch = v.getShort(2).toInt(); rate = v.getInt(4); bits = v.getShort(14).toInt()
                }
                if (tag == "data") { dataOff = pos + 8; dataLen = len; break }
                pos += 8 + len + (if (len % 2 == 1) 1 else 0)
            }
            if (dataOff < 0 || bits != 16) null
            else {
                val n = minOf(dataLen, b.size - dataOff) / 2 / ch
                val mono = ShortArray(n)
                val v = ByteBuffer.wrap(b, dataOff, n * 2 * ch).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) {
                    var acc = 0
                    for (c in 0 until ch) acc += v.short.toInt()
                    mono[i] = (acc / ch).toShort()
                }
                Pcm(resample(mono, rate, 16000), 16000)
            }
        }
    } catch (e: Exception) { null }

    /** 只读我们自己的 16k mono WAV：直接一次读进 ShortArray，不做中间 ByteArray 复制。
     *  给本地离线 ASR 用（它必须拿到整段 PCM），所以调用方要保证文件本身是切好的短块。 */
    fun readWav16k(file: File): Pcm? {
        return try {
            if (file.length() < 48) return null
            RandomAccessFile(file, "r").use { raf ->
                val head = ByteArray(1024)
                raf.readFully(head)
                var pos = 12; var rate = 16000; var ch = 1; var bits = 16
                var dataOff = -1L; var dataLen = 0L
                while (pos + 8 <= head.size) {
                    val tag = String(head, pos, 4, Charsets.US_ASCII)
                    val len = ByteBuffer.wrap(head, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
                    if (tag == "fmt " && pos + 24 <= head.size) {
                        val v = ByteBuffer.wrap(head, pos + 8, 16).order(ByteOrder.LITTLE_ENDIAN)
                        ch = v.getShort(2).toInt(); rate = v.getInt(4); bits = v.getShort(14).toInt()
                    }
                    if (tag == "data") { dataOff = (pos + 8).toLong(); dataLen = len; break }
                    pos += (8 + len + (if (len % 2 == 1L) 1 else 0)).toInt()
                }
                if (dataOff < 0 || bits != 16) return decodeTo16kMono(file.absolutePath)
                if (rate != RATE || ch != 1) return decodeTo16kMono(file.absolutePath)
                val n = (minOf(dataLen, raf.length() - dataOff) / 2).toInt()
                val out = ShortArray(n)
                raf.seek(dataOff)
                val buf = ByteArray(64 * 1024)
                var off = 0
                while (off < n * 2) {
                    val want = minOf(buf.size, n * 2 - off)
                    val r = raf.read(buf, 0, want)
                    if (r <= 0) break
                    val bb = ByteBuffer.wrap(buf, 0, r).order(ByteOrder.LITTLE_ENDIAN)
                    var k = 0
                    while (k + 2 <= r) { out[off / 2 + k / 2] = bb.short; k += 2 }
                    off += r
                }
                Pcm(out, RATE)
            }
        } catch (e: Exception) { null }
    }

    private fun resample(src: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || src.isEmpty()) return src
        val ratio = from.toDouble() / to
        val out = ShortArray((src.size / ratio).toInt().coerceAtLeast(1))
        for (i in out.indices) {
            val p = i * ratio
            val a = p.toInt().coerceIn(0, src.size - 1)
            val b = (a + 1).coerceIn(0, src.size - 1)
            val f = p - a
            out[i] = (src[a] * (1 - f) + src[b] * f).toInt().toShort()
        }
        return out
    }

    /** 能量型 VAD：返回语音段（毫秒） */
    fun vadSegments(pcm: ShortArray, rate: Int, threshold: Int = 800, minMs: Int = 800): List<Pair<Int, Int>> {
        val win = (rate * 0.02).toInt().coerceAtLeast(1)
        val active = ArrayList<Boolean>()
        var i = 0
        while (i < pcm.size) {
            var sum = 0L; var n = 0
            val end = minOf(i + win, pcm.size)
            while (i < end) { sum += abs(pcm[i].toInt()); n++; i++ }
            active.add(n > 0 && sum / n > threshold)
        }
        val segs = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (k in active.indices) {
            if (active[k] && start < 0) start = k
            else if (!active[k] && start >= 0) {
                val s = start * win * 1000L / rate
                val e = k * win * 1000L / rate
                if (e - s >= minMs) segs.add(s.toInt() to e.toInt())
                start = -1
            }
        }
        if (start >= 0) {
            val s = start * win * 1000L / rate
            val e = active.size * win * 1000L / rate
            if (e - s >= minMs) segs.add(s.toInt() to e.toInt())
        }
        return segs
    }

    /** 把 s16 PCM 写成标准 WAV */
    fun writeWav(file: File, pcm: ShortArray, rate: Int) {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in pcm) data.putShort(s)
        val bytes = data.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + bytes.size); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(bytes.size)
        file.outputStream().use { it.write(h.array()); it.write(bytes) }
    }

    /** 标准 44 字节 WAV 头。dataLen 必须和实际数据长度一致，否则解码器会多读/少读。 */
    fun wavHeader(dataLen: Int, rate: Int): ByteArray {
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()); h.putInt(36 + dataLen); h.put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()); h.putInt(16); h.putShort(1); h.putShort(1)
        h.putInt(rate); h.putInt(rate * 2); h.putShort(2); h.putShort(16)
        h.put("data".toByteArray()); h.putInt(dataLen)
        return h.array()
    }

    /** 按文件实际长度回填 WAV 头，返回可播放时长（毫秒）。
     *  用途：录音进程被系统杀掉时，头里写的还是 0 —— 文件其实是好的，只是「没报户口」。
     *  重启后扫一遍 tmp/ 回填，就能把这段录音救回来，不用重新录。 */
    fun wavFixHeader(file: File, rate: Int = RATE): Long {
        if (!file.exists()) return 0
        val len = file.length()
        if (len <= 44) return 0
        val dataLen = (len - 44).toInt()
        RandomAccessFile(file, "rw").use { r ->
            r.seek(0); r.write(wavHeader(dataLen, rate))
        }
        return dataLen.toLong() * 1000 / (rate * 2)
    }

    /**
     * 边录边写的 WAV：头先占位，数据一路 append，每隔几秒回填一次长度。
     *
     * 为什么不是「录满一段再 writeWav」——常驻录音一段 10 分钟，
     * 攒在内存里等落盘一旦被杀就全丢；rolling 写盘后最多丢 2 秒。
     */
    class WavStreamWriter(private val file: File, private val rate: Int = RATE) {
        private val raf = RandomAccessFile(file, "rw")
        private val buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN)
        private var dataLen = 0L
        private var lastFix = 0L

        init {
            raf.setLength(0)
            raf.write(ByteArray(44))
        }

        /** 追加样本；返回本段累计毫秒 */
        fun append(pcm: ShortArray, n: Int): Long {
            buf.clear()
            for (i in 0 until n) {
                if (buf.remaining() < 2) { raf.write(buf.array(), 0, buf.position()); buf.clear() }
                buf.putShort(pcm[i])
            }
            if (buf.position() > 0) raf.write(buf.array(), 0, buf.position())
            dataLen += n * 2L
            val nowMs = System.currentTimeMillis()
            if (nowMs - lastFix >= 2000) { fix(); lastFix = nowMs }
            return dataLen * 1000 / (rate * 2)
        }

        private fun fix() {
            val pos = raf.filePointer
            raf.seek(0)
            raf.write(wavHeader(dataLen.toInt(), rate))
            raf.seek(pos)
        }

        /** 收尾：回填头、flush、关闭。返回最终时长毫秒。 */
        fun close(): Long {
            return try {
                fix()
                raf.fd.sync()
                dataLen * 1000 / (rate * 2)
            } catch (e: Exception) {
                dataLen * 1000 / (rate * 2)
            } finally {
                runCatching { raf.close() }
            }
        }
    }

    /** WAV(16k mono) → AAC-LC m4a。体积约为 WAV 的 1/8（32kbps ≈ 每分钟 240KB），
     *  上传给大模型省流量也省内存。失败返回 false，调用方继续用 WAV。 */
    fun wavToM4a(wav: File, out: File, bitrate: Int = 32000): Boolean {
        var codec: MediaCodec? = null
        var mux: MediaMuxer? = null
        return try {
            val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1)
            fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            if (out.exists()) out.delete()
            mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var track = -1
            var muxing = false
            val binfo = MediaCodec.BufferInfo()
            var pts = 0L
            var inEos = false; var outEos = false
            val fis = FileInputStream(wav)
            fis.channel.position(44)   // 跳过 RIFF 头（我们自己写的，固定 44 字节）
            val pcm = ByteArray(8192)
            while (!outEos) {
                if (!inEos) {
                    val ii = codec.dequeueInputBuffer(10000)
                    if (ii >= 0) {
                        val ib = codec.getInputBuffer(ii)!!
                        ib.clear()
                        var n = fis.read(pcm)
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inEos = true
                        } else {
                            if (n % 2 == 1) n -= 1      // 16bit 帧边界
                            ib.put(pcm, 0, n)
                            codec.queueInputBuffer(ii, 0, n, pts, 0)
                            pts += n / 2L * 1_000_000L / RATE
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(binfo, 10000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.outputFormat)
                    mux.start(); muxing = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (binfo.size > 0 && (binfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxing) {
                        ob.position(binfo.offset); ob.limit(binfo.offset + binfo.size)
                        mux.writeSampleData(track, ob, binfo)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (binfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outEos = true
                }
            }
            fis.close()
            if (muxing) mux.stop()
            true
        } catch (e: Exception) {
            Log.w(TAG, "wavToM4a 失败（继续用 WAV）：${e.message}")
            try { if (out.exists()) out.delete() } catch (ignored: Exception) { }
            false
        } finally {
            try { codec?.stop(); codec?.release() } catch (ignored: Exception) { }
            try { mux?.release() } catch (ignored: Exception) { }
        }
    }
}
