# 履迹后端（v1.0 规格 / Q1 = 程序常驻收音）

**零第三方依赖、现在就能跑通全链路**。UI 由另一个对话单独调整，本服务只提供 JSON API + 音频读取。

```
python backend/run.py                 # 默认 http://127.0.0.1:8765
python backend/smoke_test.py          # 全链路冒烟测试（自动起服务、逐项断言）
```

## 0. 联网后状态（2026-11-05 晚，已实测）

| 项 | 状态 |
|---|---|
| 国内 PyPI 镜像 | ✅ 阿里云 / 中科大 / 腾讯云都通；`pypi.org` 直连被 SSL 拦 |
| 已装依赖 | `sherpa-onnx 2.0.1`、`numpy 2.5.4`、`soundfile 0.14`、`rank_bm25 0.2.2`、`pluggy 1.7`、`instructor 1.17`、`openai`、`pydantic` |
| 适配器自动切换 | `ranker` → `github:dorianbrown/rank_bm25` 已生效；`vad` / `asr` / `speaker` 需 onnx 模型文件到位后才切（避免误报） |
| GitHub 通路 | ✅ `codeload.github.com`（仓库 zip）、`gh-proxy.com`（raw 文件镜像）；❌ `raw.githubusercontent.com` 直连被 SSL 拦、git clone 隧道 502 |
| 已挪进 `vendor/` | sherpa-onnx / LlamaIndex / RAGFlow / instructor 四份官方 README |
| 大模型文件 | ⚠️ 经代理下载约 10KB/s，超时；需在你带宽好的电脑下，URL 见 §2 |
| 全链路回归 | ✅ `smoke_test.py` **42 / 42**，装包后仍全过 |

装包命令（国内镜像，已验证）：
```
python -m pip install -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com \
  numpy soundfile rank_bm25 pluggy sherpa-onnx instructor openai pydantic
```

外部能力全部做成**适配器**（`adapters_oss.py` 按官方示例写好真实调用代码、懒加载），
不可用自动降级到 `adapters_builtin.py` 的规则实现，并在 API 响应里标注 `adapter` 来源。
**装上依赖 / 填好 key / 放好模型，无需改业务代码就自动切到真实实现。**

## 0.5 真实 key 已接入（2026-11-05 22:20 实测）

`config.json` 已填 key。`/health` 的 adapters 现状：
`extractor` / `agent` / `speaker` = 真实 qwen3.8-max，`ranker` = 真实 rank_bm25，
**`asr` = 真实本地 sherpa-onnx 中文模型**，`vad` / `ima` = builtin。
生产服务实测 `/chat` 返回 `is_mock=False` 的真实生成文本。

**ASR 是真的能用了**（回答「不能用该怎么办」）：网关没有音频能力，所以改走**本地离线模型**，
不依赖任何在线端点、也不依赖 GitHub release 大文件。已从 `hf-mirror.com` 下好
`sherpa-onnx-streaming-zipformer-zh-14M` 的 int8 三件套（共 24MB）放进 `models/asr-zh-14M/`，
`config.json` 的 `sherpa.transducer_*` 指向它们。实测 5.6 秒中文音频转写为
「对我做了介绍那么我想说的是大家如果对我的研究感兴趣呢」。
下载脚本 `python backend/fetch_asr_model.py`（国内镜像，约 1 分钟）。
`live_test.py` 会真上传一段 WAV 跑完「本地 ASR → qwen 抽取 → 成卡」全链路。

**这个 key 是聚合网关，不是百炼原生**（`/models` 返回 183 个模型，含 MiniMax / DeepSeek / StepFun / GLM）：
- ✅ `chat/completions`：`json_schema` 严格抽取、`json_object`、自由长文本生成全部可用
- ❌ `/services/audio/asr/transcription` → 404；`input_audio` 音频输入 → 400 `format is empty`
- 因此云端 `ParaformerASR` 仍需显式配 `asr_endpoint` 才启用（避免误报）；**真正的 ASR 改由本地 24MB 离线模型承担**，两者互不冲突，`auto` 模式优先用已就位的本地模型

### 声纹模型能不能「直接调网上的」——结论与三条路

1. **GitHub 上的 `.onnx` 是本地权重文件，不是在线 API**。CAM++ 28MB 等要下载下来用 sherpa-onnx 本地推理，不能 HTTP 直接调。这个网关也没有声纹/说话人分离模型。
2. **已实现零下载的在线替代**：`LLMSpeakerSplit`（`aliyun/qwen3.8-max:speaker-split`）——把 L2 转写逐句交给 qwen3.8-max，按语义线索（「我说」「她劝我」「老师讲」）推断 `self / other / mixed`，结果标 `inferred`。实测四句返回 `['self','other','mixed','mixed']`。精度低于声纹 embedding，但完全不用下模型，且契合「事后回述」的口述场景。
3. **要更准**：下 `3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` 填进 `config.json` 的 `sherpa.speaker_model`，`SherpaSpeaker` 会自动优先于 LLM 方案；或换**百炼官方 key** 并填 `asr_endpoint`，paraformer-v2 的 `diarization_enabled` 可一次调用同时出转写 + 说话人标签。

### 两套测试（都要跑）
```
python backend/smoke_test.py   # 42/42 确定性回归（强制 builtin，不依赖网络）
python backend/live_test.py    # 12/12 真实 key 链路（需 api_key）
```

⚠️ `config.json` 现在含**明文 key**：别提交公开仓库、别打包进 APK；比赛用单独 key + 额度上限 + 赛后吊销。

## 0.6 骑砍式模组文件夹（"把模组丢进 Modules 里"）

**有，已经做好了。** 对应 `backend/modules/`，就是《骑马与砍杀》那种体验：

```
backend/modules/
  example-study-agent/        ← 一个文件夹 = 一个模组
    manifest.json             ← 只写 type + name 就行，其余自动补
    prompt.md                 ← 可选：agent persona
    README.md
  example-quadrant-view/
    manifest.json
    view.json                 ← 可选：可视化配置
```

| 动作 | 效果 |
|---|---|
| 把文件夹丢进去 | **启动时自动扫描、注册并启用** |
| 改了里面的文件 | `POST /components/scan` 热重扫，**不用重启** |
| 删掉整个文件夹 | 再扫描一次，自动卸载 |
| 想 GitHub 分发 | 把同样的文件上传到仓库，W11 粘贴 URL 即可 |

manifest 最少只要 `{"type":"agent","name":"我的模组"}`；`id / version / minCore / repo` 自动补齐。
同目录的 `prompt.md` / `schema.json` / `view.json` / `README.md` 会被读进 manifest
（`prompt_md` / `schema_json` / `view_json` / `README_md`），**声明式、不执行第三方代码**。
`/health` 会返回 `modules_dir` 与 `folder_modules` 列表；W11 里能看到来源标记 `source=folder`。

## 0.7 全功能 Demo 与安装包

**本机直接体验**：服务起来后访问 `http://127.0.0.1:8765/`，就是 `demo/index.html` 的简陋前端，
由后端直接托管（零跨域），左侧 12 个页面覆盖全部功能：概览 / 收录 / 流水线 / 知识库 /
可视化 / 证据回放与校正 / Agent 对话 / 模式 / 组件市场 / ima / 设置。
「设置 / 填 Key」页可粘贴 key（`POST /config` 会写进本地 config.json）。

**安装包**（可分发给现场观众）：
```
python backend/build_demo.py   →  dist/履迹-全功能demo.zip（57.3MB）
```
包内 `trace-demo/` 含：后端代码、`demo/index.html`、**24MB 中文 ASR 模型**、
**`bin/ffmpeg.exe`（105MB 原文件，压缩后包 57MB）**、两个示例模组、
`start.bat` / `start.sh` / `install.bat` / `README.md`。
- **不含明文 key**（`config.json` 里 `api_key` 为空），前端填一次即可
- 模型路径已改**相对 backend/**，`_model_path()` 解析，解压到任意位置都能跑
- `find_ffmpeg()` 优先找 `bin/ffmpeg.exe`（包内），再找 PATH，最后 imageio-ffmpeg
- 双击 `start.bat` → 自动装依赖 → 起服务 → 自动开浏览器

**已实测**：解压到独立目录后 `GET /` 200、`/health` 正确识别包内 ffmpeg 与相对模型路径、
mp3（44.1k 立体声）上传 → 转码 → 真 ASR → 成卡全通（不填 key 时抽取/对话自动降级规则版，链路仍完整）、
解压包自带 `smoke_test.py` **56/56**（v1.1 后新增 5 项保留策略断言）。

### GitHub 组件市场通路
`components.py` 的 `manifest_urls()` 会依次尝试：raw 直连 main → gh-proxy 镜像 main → raw 直连 master → gh-proxy 镜像 master，
任一通就能装。`fetch_file()` 同理，已用它把官方 README 拉进 `vendor/`。
用户自己上传组件时，在 GitHub 仓库根目录放一个 `manifest.json`，然后在 W11 粘贴仓库 URL 即可。

---

## 1. 已实现的技能（一切技能先实现）

| 技能 | 内置实现（零依赖可跑） | 真实实现（适配器） |
|---|---|---|
| 一站式收录 | `POST /ingest` 文本通道、`/upload` base64 音频、`/record/*` 常驻录音会话 | APK 前台服务推 PCM |
| VAD 切段 | `wave` + 能量阈值，静音切段、无效段丢弃 | sherpa-onnx 内置 silero-vad |
| 语音转写 | 显式文本通道（标 `is_mock`，**不伪造转写**） | 阿里云 paraformer-v2 / sherpa-onnx SenseVoice / FunASR / whisperX |
| 说话人归属 | `speaker(self/other/mixed)`，**other 全保留** | sherpa-onnx CAM++ 声纹（0.70~0.72 + 多组情绪基准）/ FunASR spk / whisperX+pyannote |
| 自动分析整理排序 | 受控词表归一 + 类型规则 + `event_time` 解析 + SQLite FTS5 / BM25 | qwen3.8-max `json_schema` / instructor / chromadb / rank_bm25 / RAGFlow |
| 知识库可视化数据 | `/timeline` 按 view 分组（时间轴/掌握度/关系/四象限） | ECharts / vis-timeline（前端挂） |
| Agent 对话 | 基于检索证据自由组织回答，**无任何输出闸门** | qwen3.8-max / LlamaIndex / RAGFlow |
| 四层存储 | SQLite：L1 元信息 / L2 只读 / L3a 快照 / L3b 校正 | — |
| 模式隔离 | `mode_id` 命名空间，独立库/检索/视图 | — |
| 组件市场 | manifest 校验、安装、启停、卸载、注册表解析 | pluggy（可选） |
| ima 拓展 | 未配置凭证时明确报错、主链路不受影响 | ima OpenAPI 单向导出 |

### Q1：程序常驻收音
`config.json` 的 `q1_capture = "always_on"` 已写死。链路：
`POST /record/start` → APK 前台服务持续 `POST /record/push` 推 PCM 块 → 分段写 WAV、自动 `_flush` 入库 → `POST /record/stop` 收尾。
真机需要 Android 前台服务 + 常驻通知 + 电池优化白名单（前端/客户端侧实现）；后端状态机与分段入库已跑通。

---

## 2. GitHub 上可直接挪的现成项目（调研结论）

### 音频流水线
| 项目 | 许可 | 用途 | 为什么选 |
|---|---|---|---|
| `k2-fsa/sherpa-onnx` | Apache-2.0 | ASR + VAD + 说话人识别/验证/分离，纯 CPU 离线 | **单引擎覆盖三环节**，Windows/Android/iOS 官方预编译，`python-api-examples/` 近百个可抄示例 |
| `modelscope/FunASR` | MIT | 中文转写+标点+VAD+说话人全流水线 | 中文效果最好，一行 `AutoModel(...)` 出结果 |
| `snakers4/silero-vad` | MIT | VAD | 轻量、sherpa 已内置 |
| `m-bain/whisperX` | BSD-2 | 词级时间戳 + pyannote 分离 | 最准，但需 HF_TOKEN、gated 模型、PyTorch 数 GB，48h 现场风险高，列可选 |
| `QuentinFuxa/WhisperLiveKit` | Apache-2.0 | 实时流式转写+分离+翻译 | 常驻收音场景可参考 |
| `Zackriya-Solutions/meetily` | MIT | 本地会议助手（麦克风+系统声音捕获+实时转写+Ollama 纪要） | 最接近「整体替代」的成品，可直接抄架构 |

### 知识库排序整理 / 对话分析
| 项目 | 许可 | 用途 | 为什么选 |
|---|---|---|---|
| `infiniflow/ragflow` | Apache-2.0 | 混合检索+重排序+**答案溯源引用** | 每条结论绑定原始片段，与本项目 `quote_ref` 证据引用天然契合；有 HTTP API |
| `run-llama/llama_index` | MIT | RAG 索引/查询引擎、结构化抽取 | 对话分析最成熟的 SDK |
| `jxnl/instructor` | MIT | Pydantic schema 约束 LLM 结构化输出 | 事件卡抽取的通用做法 |
| `chroma-core/chroma` | Apache-2.0 | 向量检索排序 | 语义排序 |
| `dorianbrown/rank_bm25` | Apache-2.0 | 关键词相关度排序 | 轻量，配合 FTS5 |
| `pytest-dev/pluggy` | MIT | 插件注册系统 | **直接当模组市场内核** |
| `visjs/vis-timeline` / `apache/echarts` | Apache-2.0 | 时间轴 / 统计可视化 | 前端 view 组件 |

### ONNX 模型（从 sherpa-onnx 官方 README 核实过的确切地址）
大文件请在你带宽好的电脑下载，放进 `backend/models/`，再把绝对路径填进 `config.json` 的 `sherpa.*_model`：

| 能力 | 地址 | 体积量级 |
|---|---|---|
| VAD | `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx` | ~2MB（小，优先下） |
| ASR 中文 | `.../releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2` | 数百 MB |
| ASR 中文（Paraformer） | `.../releases/download/asr-models/sherpa-onnx-paraformer-zh-2024-03-09.tar.bz2` | 数百 MB |
| 声纹 | `.../releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | ~28MB |

沙箱里可用 `https://gh-proxy.com/` 前缀绕开 raw 拦截，但 release 大文件带宽约 10KB/s，实测超时；
国内可用 `hf-mirror.com` / `modelscope.cn`（已探通）找对应权重。模型到位后 `/health` 的 `adapters` 会显示真实适配器名。

### 云端（不需要 pip，填 key 即用标准库 urllib 真跑）
| 能力 | 型号 | 价格 |
|---|---|---|
| ASR | 阿里云百炼 `paraformer-v2` 录音文件识别 | 0.00008 元/秒 ≈ 0.288 元/小时；每月 10h 免费；**静音不计费** |
| LLM | `qwen3.8-max` | 支持 Structured Outputs / 1M 上下文 / Function Calling |
| 坑 | JSON 输出 | 抽取时关 `enable_thinking`；**不要设 `max_tokens`**（会截断 JSON）；先试 `json_schema`，不行退 `json_object` + 本地校验 |

---

## 3. 切到真实实现的三步

1. `config.json` 填 `dashscope.api_key`（你的千问优惠 key）。**建议单独申请比赛专用 key + 额度上限 + 赛后吊销，别用主 key。**
2. 有网时 `pip install -r backend/requirements-optional.txt`（可选，装哪个就用哪个）。
3. 重启服务，`GET /health` 的 `adapters` 会从 `builtin` 变成真实适配器名。无需改任何业务代码。

> 装 ffmpeg 后可把 m4a/mp3/aac/amr 统一转 16k mono WAV；未装时仅支持 WAV 直读（VAD/ASR 仍可跑）。

---

## 4. API 一览

| 方法 | 路径 | 作用 | 对应窗口 |
|---|---|---|---|
| GET | `/health` | 健康 + 适配器来源 + FTS5 状态 | W16 |
| GET/POST | `/config` | 读/改配置（密钥隐藏） | W3 |
| GET/POST | `/modes` | 模式列表 / 新建模式 | W2 |
| POST | `/ingest` | 收录 + 全流水线（text/file/upload/recorder） | W4/W5 |
| POST | `/upload` | base64 音频上传 | W4 |
| POST | `/record/start` `/record/push` `/record/stop` | Q1 常驻录音会话 | W4/W16 |
| GET | `/events` `/event` | 事件卡列表 / 详情 | W6/W8 |
| GET | `/timeline` | 按 view 分组的可视化数据 | W7 |
| GET | `/search` | 知识库检索排序（FTS5 / BM25） | W6 |
| GET | `/stages` `/l2` `/audio` | 流水线日志 / L2 原文 / 音频回放 | W5/W8 |
| GET | `/analyses` `/analysis` | AI 分析列表 / 详情 | W10 |
| POST | `/chat` | Agent 自由对话 | W10 |
| POST | `/correct` | L3b 校正（保留 L3a 快照） | W9 |
| GET | `/stats` | 统计快照（rehearsal 锁定） | W10 |
| GET | `/components` `/catalog` | 已装组件 / GitHub 精选目录 | W11 |
| POST | `/components/install` `/toggle` `/uninstall` | 模组市场 | W11/W13 |
| POST | `/ima/export` | 可选单向导出 | W14 |

统一返回 JSON；`ingest` / `upload` 响应含 `stages`（8 阶段，每步标 `adapter`）、`adapters`、
`events`、`stats`，以及 **`analysis`（收录后自动生成的 AI 分析）** 与 `polished_transcript`（顺文本后的转写）。

## 4.1 AI 分析（收录后自动产出，不必另开对话问）

流水线第 8 阶段。`analysis.py` 用 qwen3.8-max 按 `json_schema` 输出并存入 `analyses` 表：
`summary` / `key_points` / `patterns`（**跨时间对比该模式全部历史**）/ `suggestions` /
`reminders` / `growth_plan`。按 `mode_id` 换 persona（复盘 / 学习 / 关系 / Human 3.0 成长）。
红线已撤，可自由给建议、计划、评估；无 key 时降级规则版并标 `is_mock`。

转写顺文本：`polish()` 给 ASR 原始输出补标点、去赘词、分句、修同音错字，
**L2 原文只读不动**，润色文本另存。实测「对我做了介绍那么我想说的是大家如果对我的研究感兴趣呢」
→「对我做了介绍，那么我想说的是，大家如果对我的研究感兴趣呢？」

新增端点：`GET /analyses?mode_id=`、`GET /analysis?id=`。

## 4.1.1 v1.1 补丁已合并（2026-11-07）

千问审核发现的 5 个缺陷已全部修入工作区，基线 SHA256 核对一致：

| 编号 | 内容 |
|---|---|
| A1/A2/A3 | `AlwaysOnRecorder` 重写：`_take()` 原子取走并清空缓冲、`{sid}_seg{seq:04d}` 独立编号、`_flush(background=True)` 真起后台线程、`stop()` 里 `_join()` 等落库 |
| B | `/audio` 用 `os.path.realpath` 前缀校验，越界（含绝对路径 `C:/Windows/win.ini`）全 404 |
| C | `/chat` 用 `inspect.signature` 按实际签名给参（2/3/4 参适配器全通） |
| D | `SherpaVAD` 时间戳按采样率换算、补 `finish()`、拒绝 0 字节占位模型 |
| E | `start.bat` / `install.bat` / `start.sh` 逐个候选真跑 `-c` 探测解释器，支持 `TRACE_PY` 覆盖 |
| F | **72h 保留策略 + Opus 24k 压缩存证**：`data/inbox`（原上传件）、`data/tmp`（中间 WAV）、`data/l1/*.16k.wav`（消费格式）用完即删，只留 `*.ogg`；`keep` 可永久保留；purge 后 `/audio` 返回 **410** 且事件卡 / L2 / 分析 / 检索全文完好 |

新增端点 `GET /l1`、`POST /l1/keep`、`POST /l1/purge`；`/health` 带 `retention` 统计。
`l1_files` 幂等加 6 列，`Store._migrate()` 旧库直接升级不丢数据。
`tools/` 下 4 个验收脚本随包交付，**注意三个压测工具要分开在干净目录跑**（串行会互相污染目录残留断言）。

> G（信息价值门）/ H（边界触发）/ I（会话 Markdown 导出）仍只有规格、未实现；
> 流水线仍是 **8 阶段**，实现 G 后变 9，届时需同步改 `smoke_test.py` 断言与对外话术。

## 4.1.2 句级转写矫正 + 逐句说话人 + 全库 Agent（2026-11-07）

参考 ima / 通义听悟 / Otter 的「原文／速览／纪要」三层形态（来源清单见 `backend/presets/SOURCES.md` 同类调研），
但**只借形态不借接口**：ima 的录音转写是客户端功能，MCP 不开放转写能力。

| 改动 | 做法 |
|---|---|
| 句级矫正 L2' | `analysis.correct_transcript()` 用 `json_schema` 逐句返回 `{raw, corrected, edits, confidence, needs_review}`。硬性约束：raw 逐字照抄原文、corrected 只许补标点/去纯语气赘词/去连续重复/修明显同音错字、**禁止改写语义与增删事实**、拿不准标 `needs_review`。L2 原文只读不回写 |
| 抽取改吃矫正句 | `pipeline` 把 `polished`（矫正句拼成的文本）喂给 `QwenExtractor`，不再让事件卡从带错字的原文里切。事件卡 `extensions.evidence_raw` 存原句，W8 可对照 AI 有没有改掉原意 |
| 三层对照 UI | 收录页新增「原话（L2 只读）｜矫正后（L2'）｜改动」表格，低置信句标「待核对」；下方接 AI 分析卡片 |
| 文本通道也矫正 | 粘贴别处（ima / 微信语音转文字）的转写结果同样能先矫正再抽卡总结。无 key 时规则版**零改动**，只做结构化分句，绝不改写用户原文 |
| 逐句说话人 | 新增 `QwenPerLineSpeaker`，**每一句单独调用**千问并带前后各一句上下文（用户拍板的「建议 2」），线程池并发；`config.speaker.per_line=false` 可回退批量 labels 版。结果属 inferred，非声纹 |
| 全库 Agent | `/chat` 不再只喂检索命中的 20 条，而是把该模式**全部事件卡**给模型（≤300 条全量；超出时「近期 150 条全量 + 早期命中补充」）。响应带 `library_total` / `context_count` / `full_library`，Agent 对话页可见「已加载全库 N 条」。传 `scope:"relevant"` 可回到检索模式 |

实测：同一段 5.6s 中文音频，原来出 1 张挤成一整段的卡，现在断句后出 2 张卡、首卡「对我做了介绍。」。
`live_test.py` **20/20**、`smoke_test.py` **56/56**。

## 4.1.3 音频直投大模型 + 豆包式通用 Agent（2026-11-07）

**用户诉求**：「让录音直接投入 AI 大模型让它转写，然后整合成一个事件概括的一句话，这些工作都接给大模型来干」「agent 要像豆包那样，只是自带录音知识库」。

### 为什么之前效果差（两个真因）

1. **音频走的是 24MB 端侧小模型**：那是为塞进安装包选的最小可用模型，天然打不过手机厂商的云端 ASR。
2. **没填 API Key 时，矫正 / 抽卡 / 分析 / 对话全部降级成内置规则版**——界面此前没有醒目提示，容易误以为在用大模型。

### 现在的做法

`qwen3.8-omni-flash` 支持音频输入，实测可用格式（裸 base64 会被当 URL 而 400）：

```json
{"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}
```

配 `json_schema` **一次调用**返回 `{transcript, summary, speaker, keywords, events[]}`：
转写（含断句/标点/同音错字修正）、**一句话事件概括**、事件卡，全交给大模型。WAV / mp3 / m4a 均已实测 200。

| 环节 | 实现 |
|---|---|
| `QwenOmniASR` | `ADAPTER_MAP["asr"]` 首位；`config.asr.prefer="local"` 可强制回退本地模型 |
| 链式降级 | `resolve_chain()`：omni 失败（含网络重置，重试 3 次）→ SenseVoice 239MB → 24MB zh-14M → 文本通道，**绝不会出现「有本地模型却返回空转写」** |
| 抽取 | omni 已在同一次调用产出 events，跳过第二次 LLM 抽取；卡片 `summary` 用一句话概括 |
| 前端 | 收录页显示「一句话事件概括」卡片；响应带 `event_summary` / `asr_tried` / `omni_one_call` |
| 通用助手 | 新增 `general-assistant` 预设并设为默认，prompt 明确「像豆包/千问一样自然对话，记录只在相关时才用，不要写成检索报告、不要逐条复述」；全库上下文仍是该模式全部事件卡 |
| Key 状态可见 | 顶部橙色横幅：未配置 Key 时明确告知「转写退回本地小模型、所有 AI 环节为规则版」并给「去填写 Key」按钮；Agent 对话页标注「内置模板（未用大模型）」还是「qwen3.8-max 真实生成」 |

实测对比（同一段 5.6 秒中文音频）：

| 来源 | 输出 |
|---|---|
| 24MB 端侧小模型 | `对我做了介绍那么我想说的是大家如果对我的研究感兴趣呢` |
| omni 一次调用 | `对我做了介绍啊。那么我想说的是呢，大家如果对我的研究感兴趣呢？嗯。` |
| 一句话概括 | `讲话者感谢他人对其进行了介绍，并邀请听众对他的研究表达兴趣。` |

`smoke_test` **56/56**、`live_test` **20/20**。

## 4.1.4 v1.2 对话性能改造（2026-11-07 晚，真机卡顿专项）

**问题**：真机用 Agent 对话时界面卡、反应慢。定位到 5 个原因，**4 个在手机端，1 个在上下文**：

| # | 原因 | 修法 |
|---|---|---|
| 1 | `TraceBridge.call` 是同步 `@JavascriptInterface`，网络往返期间 WebView 的 JS 线程完全冻住 | 新增 `callAsync(id, path, body)`：立即返回，后台线程跑完用 `evaluateJavascript` 回推 `__traceResolve`；前端 `api()` 改 Promise |
| 2 | 无流式，必须等整段生成完 | `/chat?stream=1` SSE 逐块下发；APK 侧 `chatStream` 读流、每 70ms 合并推一次 UI |
| 3 | 每轮把全库 300 条原话重拼 prompt（约 12k token） | 新增 `memory_digest` 表：收录后生成「日期+类型+40字摘要」，对话只读一行，超 6000 字折叠更早记录 |
| 4 | 每轮重新扫表 + 只喂检索结果 | 两段式上下文：全库摘要（覆盖全部）+ 本次问题命中的 20 条原话；`full_library` 语义不变 |
| 5 | 每次新建连接 + `disconnect()`，TLS 重握手 | 复用 keep-alive、启动预热、`Promise.all` 并发拉取、SQLite WAL、`listEventsBrief` 只查必需列 |

**实测**：真实 key 下 `/chat?stream=1` 首个 `data:` 帧约 1 秒内到达，delta 为 2–6 字/帧；
`smoke_test.py` 仍 **56/56**；APK `BUILD SUCCESSFUL`、`apksigner verify` 通过。

> 未能真机验证的部分：WebView 端渲染流畅度与实际体感（本机无 Android 设备）。
> 静态证据：`callAsync` / `chatStream` / `buildDigest` / `memory_digest` 已确认打进 `classes2/3.dex`，`assets/index.html` 为新版。

## 4.2 转写质量：为什么不理想，怎么升级

当前默认 `sherpa-onnx-streaming-zipformer-zh-14M`（int8 仅 24MB）是**能塞进安装包的最小可用中文模型**，
代价是：无标点、口语赘词多、同音字偶错、对演讲类音频不如对话类。三层改进：

1. **已做**：`analysis.py` 的 `polish()` 用 qwen3.8-max 做后处理 —— 补标点、去赘词、分句、修同音错字。
   L2 原文仍只读不动，润色文本另存。实测效果见上。
2. **已启用（2026-11-06）**：`fetch_big_asr.py` 已从 hf-mirror 下好 **SenseVoice int8（239MB）**
   到 `models/sense-voice/`，`config.json` 已填 `sense_voice_model` / `sense_voice_tokens`，
   `SenseVoiceASR` 现为 `ADAPTER_MAP["asr"]` 首选，`/health` 显示 `sherpa-onnx:sense-voice`。
   实测同一段音频：小模型「对我做了介绍那么我想说的是大家如果对我的研究感兴趣呢」
   → SenseVoice「对我做了介绍啊，那么我想说的是呢，大家如果对我的研究感兴趣呢嗯。」（自带标点）
   → LLM 顺文本「对我做了介绍。那么我想说的是，大家如果对我的研究感兴趣。」
   仍完全离线、不用换 key；把 config 里两行清空即可退回 24MB 小模型。
3. **换云端**：用百炼官方 key 填 `dashscope.asr_endpoint` 走 paraformer-v2，中文效果最好且支持说话人分离。

不升级也不影响：24MB 模型 + LLM 顺文本已经能跑通全链路。

## 4.3 6 套预置人格（人生助手 / 伴侣）

`python backend/gen_presets.py` 生成到 `backend/modules/` 与 Android `assets/modules/`
（骑砍式模组，各含 `manifest.json` + `prompt.md`）：
`life-coach`（默认）/ `gentle-companion` / `strict-tutor` / `human3-growth` /
`relationship-mirror` / `career-planner`。

`config.json` 的 `active_agent` 指定当前人格；`GET /agents` 列出，`POST /agent/select` 切换。
**AI 分析（analysis.py）和 Agent 对话（QwenAgent）都会读取该模组的 `prompt.md` 拼进 system prompt**，
改完 `prompt.md` 点「重新扫描」即生效。参考的开源项目见 `backend/presets/SOURCES.md`。

---

## 5. 目录

```
backend/
  run.py                     HTTP 服务 + 全部路由
  config.json                配置（模式、适配器、凭证、录音参数）
  core.py                    数据契约 / 四层存储 / FTS5 / 模式隔离 / 组件注册表
  pipeline.py                流水线编排 + Q1 常驻录音会话
  adapters_builtin.py        零依赖规则实现（每个技能都能跑）
  adapters_oss.py            GitHub 现成项目 / 云 API 的真实接入代码（懒加载）
  components.py              模组市场（manifest 校验、安装、启停、精选目录）
  smoke_test.py              全链路冒烟测试
  requirements-optional.txt  可选依赖 + GitHub 仓库地址
  README.md                  本文件
  data/                      运行后生成：trace.db / inbox / l1
```

## 6. 规格对应

功能与窗口以 `../功能与窗口规格-v1.0.md` 为准：红线全撤、Agent 自由生成、other 全保留、
拓展为模组市场、Q1 常驻收音。本后端不含 UI，全部界面由另一个对话实现，直接连上述 API 即可。
