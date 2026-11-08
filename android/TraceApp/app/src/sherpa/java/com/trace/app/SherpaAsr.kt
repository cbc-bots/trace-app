package com.trace.app

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * sherpa-onnx 官方 Android AAR 的真实转写封装（不再是反射占位）。
 *
 * 模型：24MB 流式 zipformer transducer 中文 int8 三件套，已放在
 * `app/src/main/assets/models/asr-zh-14M/`，完全离线、不联网、不需要 key。
 *
 * 本文件位于 `src/sherpa/java`，只有当 `app/libs/sherpa-onnx.aar` 存在时
 * 才会被 build.gradle 的 sourceSets 纳入编译；主源码集通过反射实例化本类，
 * 因此 AAR 临时缺失时工程仍能 Build（自动降级到云端 / 文本通道）。
 *
 * 两条构造路径（由 AAR 的字节码确认）：
 *  - newFromAsset：传 AssetManager，config 里填 assets 相对路径（主路径）
 *  - newFromFile ：AssetManager 传 null，config 里填绝对文件路径（兜底）
 */
class SherpaAsr(private val ctx: Context) {

    private val recognizer: OnlineRecognizer by lazy { build() }

    private fun config(prefix: String): OnlineRecognizerConfig {
        val feat = FeatureConfig()
        feat.sampleRate = 16000
        feat.featureDim = 80

        val tr = OnlineTransducerModelConfig()
        tr.encoder = prefix + "encoder-epoch-99-avg-1.int8.onnx"
        tr.decoder = prefix + "decoder-epoch-99-avg-1.int8.onnx"
        tr.joiner = prefix + "joiner-epoch-99-avg-1.int8.onnx"

        val model = OnlineModelConfig()
        model.transducer = tr
        model.tokens = prefix + "tokens.txt"
        model.numThreads = 2
        model.provider = "cpu"
        model.debug = false
        model.modelType = ""

        val cfg = OnlineRecognizerConfig()
        cfg.featConfig = feat
        cfg.modelConfig = model
        cfg.endpointConfig = EndpointConfig()
        cfg.enableEndpoint = true
        cfg.decodingMethod = "greedy_search"
        cfg.maxActivePaths = 4
        return cfg
    }

    /** 兜底：把 assets 里的模型拷到内部存储，返回绝对路径前缀。 */
    private fun copyModels(): String {
        val dst = File(ctx.filesDir, "asr-zh-14M")
        dst.mkdirs()
        for (n in MODEL_FILES) {
            val f = File(dst, n)
            if (f.isFile && f.length() > 0) continue
            ctx.assets.open(ASSET_DIR + n).use { input ->
                f.outputStream().use { input.copyTo(it) }
            }
        }
        return dst.absolutePath + File.separator
    }

    private fun build(): OnlineRecognizer {
        return try {
            val rec = OnlineRecognizer(ctx.assets, config(ASSET_DIR))
            Log.i("SherpaAsr", "newFromAsset OK (models read directly from assets)")
            rec
        } catch (e: Throwable) {
            // assets 直读在某些机型/ABI 上可能失败，退回文件路径方式
            Log.w("SherpaAsr", "newFromAsset failed, fallback to newFromFile", e)
            OnlineRecognizer(null, config(copyModels()))
        }
    }

    /** 16k mono PCM → 中文文本。补尾部静音触发 endpoint，再 drain 解码队列。 */
    fun transcribe(samples: FloatArray, sampleRate: Int): String {
        val st = recognizer.createStream("")
        try {
            st.acceptWaveform(samples, sampleRate)
            st.acceptWaveform(FloatArray((sampleRate * 0.6).toInt()), sampleRate)
            st.inputFinished()
            while (recognizer.isReady(st)) {
                recognizer.decode(st)
            }
            val text = recognizer.getResult(st).text.trim()
            if (text.isEmpty()) Log.w("SherpaAsr", "recognizer returned empty text after drain")
            return text
        } finally {
            st.release()
        }
    }

    companion object {
        private const val ASSET_DIR = "models/asr-zh-14M/"
        private val MODEL_FILES = listOf(
            "tokens.txt",
            "encoder-epoch-99-avg-1.int8.onnx",
            "decoder-epoch-99-avg-1.int8.onnx",
            "joiner-epoch-99-avg-1.int8.onnx")
    }
}
