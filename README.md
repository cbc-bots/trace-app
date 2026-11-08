# 履迹 Trace

> 一个把**语音日记 / 通话录音**自动整理成**可检索知识**的 Android 应用：录音转写 → AI 按主题切片条目化 → 星图可视化，数据全部留在本地。

履迹的核心思路是「先记后理」：你只管说，App 在本地完成语音转写与 AI 分析，把一段可能讲了好几件事的长录音，拆成一条条**只讲一件事**的知识卡片，再用「星图」把不同主题、不同时间的卡片连成一张可回溯的网络。

---

## ✨ 功能特性

- **语音 / 通话录音转写**：支持设备端离线 ASR（sherpa-onnx，无需联网即可转写）与云端 ASR。
- **AI 条目化**：大模型把整段转写切成「一条一主题」的短条目（8–120 字、带原文引用），而非把整段录音全文糊在一起。
- **星图视图**：按 AI 分析出的主题聚类，把卡片在星图中以节点 + 连线呈现，一眼看到知识脉络。
- **对话式检索**：直接问「上周我和谁聊过 AI 选型」，用自然语言在本地知识里找答案。
- **纯本地优先**：录音、转写、数据库（SQLite）全部在设备本地；API Key 仅保存在本机，绝不写入代码或上传。
- **单文件前端**：整个交互界面是一个 `履迹App-星图版.html`（HTML + CSS + JS），通过 WebView 桥与原生层通信。

---

## 🧱 架构

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 前端 | 单文件 HTML / CSS / JS | `履迹App-星图版.html`，打包进 `assets/index.html` |
| 原生桥 | Kotlin + Android WebView | `TraceBridge` / `TraceEngine`，负责录音、ASR、SQLite、Key 清洗 |
| 存储 | SQLite（本地） | 事件、条目、星图关系 |
| 可选后端 | Python（FastAPI 风格） | `backend/`，用于桌面演示或非 Android 环境 |

```
黑客松/
├─ 履迹App-星图版.html        # 单文件前端（主程序）
├─ android/TraceApp/          # Android 工程（Kotlin + WebView）
│  ├─ app/src/main/assets/index.html   # 由上面那份 HTML 复制而来
│  ├─ app/src/main/java/com/trace/app/ # 原生桥与引擎
│  ├─ app/libs/sherpa-onnx.aar         # 离线 ASR（由 fetch_aar.py 下载，不入库）
│  └─ fetch_aar.py                     # 下载官方 sherpa-onnx AAR
├─ backend/                   # 可选 Python 后端（demo / 桌面）
├─ tools/                     # 评测与构建小工具
├─ 功能与窗口规格-*.md         # 产品规格文档
└─ README.md
```

---

## 🚀 快速开始（Android）

### 环境
- Android Studio / 命令行 Gradle
- JDK 17
- Android SDK（API 34 左右）

### 构建步骤
```bash
# 1. 拉取离线转写模型包（49MB，约 1 分钟，仅首次需要）
cd android/TraceApp
python fetch_aar.py

# 2. 构建
./gradlew assembleDebug        # 调试包
# 或 ./gradlew assembleRelease  # 正式包（需要 key.properties，见下）

# 3. 安装
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 前端更新后，需把 `履迹App-星图版.html` 复制到
> `android/TraceApp/app/src/main/assets/index.html`，再重新构建。

### 正式签名（可选）
复制 `android/TraceApp/key.properties.example` 为 `key.properties`，
填入你自己的签名信息。`key.properties` 与 `*.jks` **不会进入版本库**；
不配置时自动回退到 debug 签名，不影响调试与安装。

---

## 🔑 配置大模型 Key

履迹通过兼容 OpenAI 的接口调用大模型（默认 DashScope / 通义千问）。
Key 在 App 内「设置」页粘贴，仅存于本机 SQLite，**不会写进代码、不会上传**。

```jsonc
// backend/config.json（后端可选）
{
  "dashscope": {
    "api_key": "",                       // 留空，用环境变量注入
    "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
    "llm_model": "qwen3.8-max"
  }
}
```
后端建议用环境变量注入：`export DASHSCOPE_API_KEY=sk-xxxx`。

---

## 📦 后端（可选）

`backend/` 提供一套 Python 服务，用于在没有 Android 的环境里跑通转写 / 分析 / 检索链路（桌面 demo、CI 验证）。
```bash
cd backend
pip install -r requirements.txt   # 若无该文件，按 imports 安装：fastapi/uvicorn/sherpa-onnx 等
export DASHSCOPE_API_KEY=sk-xxxx
python core.py                    # 具体入口以 backend/ 内说明为准
```

---

## 📜 开源协议

[MIT](./LICENSE)。可自由使用、修改、分发；商业用途也欢迎，保留版权声明即可。

---

## ⚠️ 说明与免责

- 本仓库**不含**任何签名密钥、API Key 或个人录音数据。
- 离线 ASR 模型包（`.aar`）由 `fetch_aar.py` 从官方源下载，不入库。
- 数据默认全部本地存储；若接入云端 ASR / 大模型，请自行评估隐私与合规。

---

## 🔗 仓库

```
git clone https://github.com/cbc-bots/trace-app.git
```
