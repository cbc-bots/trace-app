# AAR 已就位：APK 完全离线真转写

`sherpa-onnx.aar` **已放在本目录**，v2.0.1：

| 项 | 值 |
|---|---|
| 大小 | 49,441,341 字节 |
| sha256 | `a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7`（与官方公布一致） |
| 来源 | `https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar` |
| 内含 | 4 个 ABI 的 `libonnxruntime.so` / `libsherpa-onnx-c-api.so` / `libsherpa-onnx-cxx-api.so` / `libsherpa-onnx-jni.so` |

真实接线已经写好（不再是占位）：
- `app/src/sherpa/java/com/trace/app/SherpaAsr.kt` —— 用 AAR 的 Kotlin API 从 assets 加载模型
- `app/src/main/java/com/trace/app/TraceEngine.kt` —— 反射实例化并调用
- `app/build.gradle` —— 条件依赖 + 条件 sourceSet；AAR 在才编译真实实现

模型（24MB int8 三件套 + tokens.txt）在 `app/src/main/assets/models/asr-zh-14M/`，不需另外下载。
API 签名、两条构造路径、体积裁减说明见 `docs/SHERPA.md`。

**若换电脑后本文件丢失**：在工程根目录运行 `python .build/fetch_aar_parallel.py`
（分块并发下载，约 26 秒；串行下载会被 GitHub release 冷启动拖到十几分钟）。
下载完重新 Build 即可，无需改任何代码。

`abiFilters` 只保留 `arm64-v8a` + `armeabi-v7a`；要在模拟器跑请把 `'x86_64'` 加回 `app/build.gradle`。

**许可证**：sherpa-onnx 为 Apache-2.0；模型请留意各 release 的分发条款。
