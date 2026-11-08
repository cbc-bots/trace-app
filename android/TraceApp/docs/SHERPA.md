# 离线 ASR 接线：已实现（不再是占位）

> 状态：**已接通**。`TraceEngine.sherpaTranscribe()` 不再返回 null。
> 上传音频 → `AudioUtil.decodeTo16kMono()` → `SherpaAsr.transcribe()` → 文本 → 句级矫正 → 抽卡 → AI 分析。
> 全程离线、不需联网、不需 API Key。

## 1. 组成

| 件 | 位置 | 说明 |
|---|---|---|
| 官方 AAR | `app/libs/sherpa-onnx.aar` | v2.0.1，49,441,341 字节，官方 sha256 `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7` |
| 模型 | `app/src/main/assets/models/asr-zh-14M/` | 24MB 流式 zipformer transducer 中文 int8 三件套 + `tokens.txt` |
| 真实实现 | `app/src/sherpa/java/com/trace/app/SherpaAsr.kt` | 条件编译：仅 AAR 存在时纳入源集 |
| 调用方 | `app/src/main/java/com/trace/app/TraceEngine.kt` | 反射实例化 `SherpaAsr`，主源码集不硬依赖 AAR |

如果 AAR 丢了，运行 `python .build/fetch_aar_parallel.py`（分块并发下载，约 26 秒；串行下载会因 GitHub release 冷启动极慢）。

## 2. 为什么用「条件源集 + 反射」

`app/build.gradle`：

```groovy
def sherpaAar = file('libs/sherpa-onnx.aar')
if (sherpaAar.exists()) implementation files('libs/sherpa-onnx.aar')
android.sourceSets.main {
    if (file('libs/sherpa-onnx.aar').exists()) java.srcDirs += 'src/sherpa/java'
}
```

- AAR 在 → `SherpaAsr.kt` 参与编译，APK 里出现该类，`hasSherpaAar()` 为真 → 真离线转写。
- AAR 不在 → 主源码集仍能编译，`hasSherpaAar()` 为假 → 自动降级到云端 `asr_endpoint` / 文本通道。
- 这样交付包绝不会因为少一个二进制而编译失败，但**正式交付必须带上 AAR**。

`TraceEngine.hasSherpaAar()` 同时检查 `com.k2fsa.sherpa.onnx.OnlineRecognizer` 和 `com.trace.app.SherpaAsr` 两个类，避免「AAR 在但接线没编译进去」的假阳性。

## 3. 真实 API（用 javap 从 AAR classes.jar 核对，不要按 Python 版 API 猜）

Python 版叫 `decode_stream` / `get_result(stream)`，Android AAR 完全不同：

| Android AAR (Kotlin) | 说明 |
|---|---|
| `OnlineRecognizer(AssetManager?, OnlineRecognizerConfig)` | `assetManager != null` → `newFromAsset`（assets 相对路径）；`null` → `newFromFile`（绝对路径） |
| `createStream(String)` | 传 `""` 即可 |
| `OnlineStream.acceptWaveform(float[], int)` | 输入 `FloatArray [-1,1]`，不是 ShortArray |
| `OnlineStream.inputFinished()` | 尾部收尾 |
| `recognizer.isReady(stream)` / `recognizer.decode(stream)` | 方法名是 `decode`，不是 `decode_stream` |
| `recognizer.getResult(stream).text` | 返回 `OnlineRecognizerResult`，取 `.text` |
| `OnlineStream.release()` | 释放 |

配置类 `FeatureConfig` / `OnlineTransducerModelConfig` / `OnlineModelConfig` / `OnlineRecognizerConfig` / `EndpointConfig` **都有无参构造 + 全套 setter**，所以用「无参构造 + 逐个赋值」最稳，绕开 Kotlin 默认参数在不同版本间的差异。

## 4. 两条构造路径

`SherpaAsr.build()`：

1. **主路径**：`OnlineRecognizer(ctx.assets, config("models/asr-zh-14M/"))`，模型直接从 assets 读，零拷贝。
2. **兜底**：若主路径抛异常，把 4 个模型文件拷到 `filesDir/asr-zh-14M/`，再用 `OnlineRecognizer(null, config(绝对路径))` 走 `newFromFile`。

`transcribe()` 补 0.6 秒静音触发 endpoint，再 `while (isReady) decode` 排空解码队列，与桌面版 Python 实现一致。

## 5. 体积

AAR 自带 4 个 ABI 的 `.so` 且不压缩进 APK，全带会达 150MB。
`defaultConfig.ndk.abiFilters` 只保留 `arm64-v8a` + `armeabi-v7a`（覆盖真机），去掉仅模拟器用的 x86 / x86_64，**APK 81MB**。
若要在模拟器跑，把 `'x86_64'` 加回 `abiFilters` 即可（会变大）。

## 6. 验证边界

已静态证明：APK 内 dex 含 `SherpaAsr` 与 `OnlineRecognizer`、两个真机 ABI 各 4 个 `.so` 齐全、assets 模型 sha256 与工作区一致、v2 签名有效；并用桌面 Python 版跑同一份模型确认可转写出中文。

**未在真机/模拟器上验证运行时推理**（本环境无 Android 设备）。首次装机请上传一段中文录音，确认：

- `/health` 的 `adapters.asr` = `sherpa-onnx:zh-14M`
- 「语音转写」阶段 `is_mock=false`、`l2_segments >= 1`、`asr_status = "ok"`
- 响应 `raw_transcript` 非空，且后续有事件卡与 AI 分析

### 现场排障（QA 复核后补强）

- `android.util.Log` 已接入，TAG `TraceEngine` / `SherpaAsr`：引擎创建成功打印 `offline ASR engine created`；识别为空打印 `returned empty text`；异常打印 `offline ASR failed: <类型>: <消息>` 并带堆栈；`newFromAsset` 失败切换兜底也会打日志。用 `adb logcat -s TraceEngine SherpaAsr` 即可区分「识别为空」与「引擎崩溃」。
- 响应与 stage_log 新增 `asr_status`（`ok` / `empty_or_failed` / `unavailable` / `not_applicable`）与 `asr_failure`；前端收录页在失败时会显示橙色错误条，不会静默无结果。
- `hasOnnxAssets()` 改为校验 4 个模型文件全部存在，不再只看 `tokens.txt`。
- 前端音频固定走 `/upload`（`source="upload"`），不会误走 `/ingest` 的文本分支而跳过转写。

QA 独立核验 **107/107 PASS、路由 NoOne**：dexdump 确认反射签名 `transcribe([FI)Ljava/lang/String;` 与 `(Landroid/content/Context;)V` 逐字匹配，ELF 头确认 8 个 `.so` 的真实 CPU 架构，AAR API 39 项、调用链 28 项全绿。所有结论仍属**静态证明**，运行时出字需真机确认。
