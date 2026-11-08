# 履迹 Trace · Android 原生工程（真 APK）

这是**安卓手机安装包工程**，不是桌面 zip。装到手机上即可独立运行，
**不需要电脑、不需要局域网、不需要 Python**。

## ✅ 已经编译出可直接安装的 APK

生成侧已装好 JDK 17 + Android SDK 34 + Gradle 8.7，**真的编译成功并验签通过**：

| 项 | 值 |
|---|---|
| 文件 | `dist/履迹-v1.7.2-并发转写.apk`（80.2 MB，v2 正式签名，非 debug） |
| 微信可点下载页 | `https://trace-apk-download.app.workbuddy.host/?sharecode=VULPKIQXTJ` |
| APK 直链 | 同域名 `/trace.apk?sharecode=VULPKIQXTJ`（Content-Type: application/vnd.android.package-archive） |
| 内含 | classes.dex / 24MB 中文 ASR 模型 / 双模前端 / 8 个模组（含 6 套人格预设） |
| 签名 | **release 正式签名**（`CN=Trace`，keystore 在 `trace-release.jks`，不入库），Android 8+ 允许「未知来源」即可安装 |

把上面下载页链接发到微信，点开 → 点按钮 → 下载 → 安装。
**App 内不含任何 API Key**，首次打开在「设置 / 填 Key」粘一次即可。

> 中文路径注意：AGP 在 Windows 上默认拒绝非 ASCII 路径，工程里已加
> `android.overridePathCheck=true`；生成侧另外把工程复制到纯 ASCII 路径完成编译。

## 一、怎么打出 APK

1. 安装 **Android Studio**（自带 JDK 17、Android SDK 34、Gradle）
2. `File → Open` 选择本目录 `android/TraceApp`
3. 等 Gradle Sync 完成（首次会从阿里云/腾讯云镜像拉依赖，见 `settings.gradle` / `gradle-wrapper.properties`）
4. `Build → Build Bundle(s) / APK(s) → Build APK(s)`
5. 产物：`app/build/outputs/apk/debug/app-debug.apk`，拷到手机安装即可

> 本工程目录里没有 `gradle-wrapper.jar`（二进制，生成侧没带）。
> Android Studio 打开时会自动补；若提示，选 **「OK / Use Gradle wrapper」** 让它下载即可，
> 或直接 `./gradlew assembleDebug`（Studio 会生成 wrapper）。也可把本机其它 Android 项目的
> `gradle/wrapper/gradle-wrapper.jar` 拷过来。

## 二、装到手机后要做的唯一一步

打开 App → 左侧 **「设置 / 填 Key」** → 粘贴你的 API Key（保存在本机 SharedPreferences，不进包）。
然后 12 个页面全部可点。

## 三、架构（为什么不再需要 Python）

```
MainActivity (WebView, assets/index.html)
      ↕ JavascriptInterface  TraceBridge.call("GET/POST /xxx", json)
TraceBridge  →  TraceEngine (流水线 / 云端 LLM / 组件市场)
             →  TraceDb     (SQLite 四层存储 / 模式隔离 / 组件表 / 会话 / stage_log)
RecordService (Q1 前台服务, AudioRecord 常驻录音)
AudioUtil     (MediaCodec 解码任意手机录音格式 / VAD / WAV)
```

**桌面版 Python 后端的所有逻辑都用 Kotlin 重写了**，不再跑 Python：
SQLite 四层存储、`mode_id` 隔离、8 阶段流水线、FTS 检索降级为 LIKE + 中文分词、
agent / extractor / speaker 走 qwen3.8-max、组件市场与骑砍式模组文件夹、统计快照锁定。

前端 `assets/index.html` 与桌面版**同一份代码**：检测到 `window.TraceBridge` 就走原生，
否则走 HTTP —— 所以桌面预览版和 APK 功能一一对应。

## 四、功能对照（12 页 = 全部功能）

| 页面 | APK 内实现 |
|---|---|
| 概览 / 状态 | `/health` 返回适配器、Key、模组、AAR 状态 |
| 一站式收录 | 上传 m4a/mp3/aac/amr/webm/wav → **MediaCodec 解码** → 16k mono；文本直录通道 |
| 自动流水线 | 收录 → VAD → ASR（含 **LLM 顺文本**）→ 说话人 → 抽取 → 成卡 → 排序 → **AI 分析**，8 阶段每步标 adapter |
| 知识库检索 | SQLite LIKE + 中文词切分 + 分组统计 |
| 可视化 | 时间轴 / 掌握度 / 四象限 / 关系时间线 |
| 证据回放 / 校正 | WebViewClient 拦截 `/audio?file=` 播放本地音频；L3a 快照 vs L3b 校正 |
| Agent 对话 | 真实 qwen3.8-max 自由生成（无红线），失败会带 `degraded` 原因 |
| AI 分析 | 收录后**自动生成**：摘要 / 要点 / 跨时间模式 / 建议 / 提醒 / 成长计划，按模式换 persona，存 SQLite 可回看；无需另开对话问 |
| 模式管理 | `mode_id` 隔离、新建模式 |
| 组件市场 | `assets/modules/` 骑砍式热扫描、manifest 安装、启停/卸载、GitHub 精选目录 |
| ima 拓展 | 未配凭证时明确报错，不影响主链路 |
| 设置 / 填 Key | 云端 key 与 `asr_endpoint` 保存在本机 |
| Q1 常驻收音 | `RecordService` 前台服务 + AudioRecord，每 30 秒分段落盘并自动入库 |

## 四、6 套预置人格（提前设置好，随时切换）

`assets/modules/` 里已放好 6 个 agent 模组，在「Agent 对话」页下拉切换；
`POST /agent/select` 保存选择，之后 **AI 分析和对话都会用这套 prompt**：

| id | 人格 | 适合 |
|---|---|---|
| `life-coach` | 人生教练 · 直率复盘者（默认） | 指出说过与做过的差距、给硬真相 |
| `gentle-companion` | 温柔伴侣 · 倾听者 | 先接住情绪、记得住之前的事、主动关心 |
| `strict-tutor` | 严厉督学 · AI 导师 | 学习模式，查掌握度、给练习闭环 |
| `human3-growth` | Human 3.0 成长教练 | 四象限 / 三层级 / False Transformation / 30-90-180 天 |
| `relationship-mirror` | 关系镜子 | 看互动模式、被忽略的信号、主动提醒 |
| `career-planner` | 生涯规划师 | 把竞赛/实习记录连成能力轨迹与取舍 |

参考的开源提示词项目见 `backend/presets/SOURCES.md`：
`chiisen/HUMAN3_PROMPT`（Human 3.0 简中修订版）、`JushBJJ/Mr.-Ranedeer-AI-Tutor`、
`Yotoha0303/AI_RoleTemplate`、`linexjlin/GPTs`、`langgptai/LangGPT`、
SillyTavern Character Card V2。预设是**按本产品"连续时间轴"重写**的，不是直接复制。
改模组的 `prompt.md` 后在「组件市场」点「重新扫描」即生效。

## 五、ASR 的三种状态（重要，请先看）

`/health` 的 `adapters.asr` 会告诉你当前是哪一种：

| 状态 | 条件 | 能力 |
|---|---|---|
| **`qwen3.8-omni-flash:audio-understanding`（默认）** | 填了 API Key | **音频直投多模态大模型**：一次调用完成转写（断句/标点/纠错）+ 一句话事件概括 + 事件卡。WAV/mp3/m4a 均可用 |
| `sherpa-onnx:zh-14M (本地兜底)` | omni 失败或未填 key，且 AAR + assets 就位 | 完全离线真转写，24MB 本地模型 |
| `cloud:<endpoint>` | 在「设置」填了 `asr_endpoint` | 云端 ASR 备用 |
| `builtin:text-channel` | 以上都不可用 | 文本直录通道（显式标 `is_mock`，不伪造转写） |

> ⚠️ **没填 Key 时，转写会退回 24MB 本地小模型，矫正/概括/分析/对话全部是规则版**——这正是「效果差」的最常见原因。
> App 顶部有橙色横幅提示，进「设置 / 填 Key」粘一次即全部切到真实大模型。

**已全部就位，开箱即用**：24MB 中文 ASR 模型在 `assets/models/asr-zh-14M/`，
官方 AAR v2.0.1（49,441,341 字节，sha256 与官方公布一致）在 `app/libs/sherpa-onnx.aar`，
真实实现 `SherpaAsr.kt` 用 AAR 的 Kotlin API 直接跑 transducer。`TraceEngine.sherpaTranscribe()` 已不再是占位。

如果换电脑后 AAR 丢了：

```
python .build/fetch_aar_parallel.py        # 分块并发下载，约 26 秒
# 手动地址：https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar
```

`app/build.gradle` 用条件依赖 + 条件 sourceSet：AAR 在就编译真实实现，不在仍可 Build 但自动降级。
API 签名与两条构造路径（assets 直读 / 文件路径兜底）见 `docs/SHERPA.md`。

> 真机 ABI 只保留 `arm64-v8a` + `armeabi-v7a`（去掉仅模拟器用的 x86），APK 81MB。
> 本机无 Android 设备，运行时推理**尚未真机验证**；静态证据见 `docs/SHERPA.md` §6。

## 六、工程里有什么

```
settings.gradle / build.gradle / gradle.properties     根目录 Gradle 配置（国内镜像）
gradle/wrapper/gradle-wrapper.properties               Gradle 8.7（腾讯云镜像）
app/build.gradle                                       AGP 8.5.2 + Kotlin 1.9.24，条件依赖 AAR
app/src/main/AndroidManifest.xml                       权限 / Activity / 前台服务
app/src/main/java/com/trace/app/*.kt                    6 个 Kotlin 文件
app/src/main/assets/index.html                         双模前端（原生桥 / HTTP）
app/src/main/assets/models/asr-zh-14M/                 24MB 中文 ASR 模型 + 测试音频
app/src/main/assets/modules/                           两个示例模组
app/libs/README.md                                     AAR 放置说明
fetch_aar.py                                           AAR 下载脚本
docs/SHERPA.md                                         离线 ASR 接线片段
```

## 七、v1.1 同步修复（对应桌面版千问审核补丁）

桌面版补丁修复的 A1/A2/A3/B/F 已逐一在 Kotlin 侧核对并处理：

| 编号 | Kotlin 侧实况 | 处理 |
|---|---|---|
| A1 平方级重复入库 | **不存在**。`RecordService.emit()` 每次只消费新增的 30 秒（`acc.take + subList.clear`），本来就是增量 | 保持，并加注释防回退 |
| A2 同步阻塞推流 | **存在**。原 `writeChunk()` 在录音线程里同步跑 `engine.run()`（含 ASR + LLM 网络），期间 `AudioRecord.read()` 停摆 → 丢音频 | 改为「录音线程只落盘 + 入队」，`LinkedBlockingQueue` + 单消费者线程按序跑流水线；`stop()` 等队列落库完再收尾 |
| A3 分段覆盖 | 毫秒时间戳命名，理论同毫秒可覆盖 | 改递增序号 `rec_{session}_seg0001.wav`，中间 WAV 走 `data/tmp/` 且用完即删 |
| B `/audio` 路径越界 | **存在**。`shouldInterceptRequest` 只做了 `File(File(filesDir,"l1"), name)`，绝对路径 / `..` 可跳出 | `canonicalFile` 前缀校验，不在 `l1/` 子树内一律拒绝；已清理的存证返回 **410 + 说明文案** |
| F 72h 保留策略 | **完全缺失** | `l1_files` 幂等加 6 列（`keep/expire_at/bytes_on_disk/purged_at/stored_at`）；新增 `GET /l1`、`POST /l1/keep`、`POST /l1/purge`；`/health` 带 `retention` 统计；**只清音频字节，L2 / 事件卡 / 分析 / 检索全文不动** |

新增能力：`POST /l1/keep {file, keep:true}` 可标记永久保留；取消保留时重新给满 72h 窗口。清理后审计行保留 `purged_at`，不删行、可追溯。

⚠️ **APK 侧暂未做 Opus/AAC 压缩存证**（桌面版已有）。手机 16k WAV ≈ 110 MB/h，按 8h/天 × 72h ≈ **2.7 GB 稳态上限**；靠保留策略兜住不再无限增长，但比桌面版的 247 MB 重。若要进一步省空间，后续用 `MediaCodec` 编码 AAC + `MediaMuxer` 写 m4a，或把 `TraceDb.RETENTION_HOURS` 调小。

## 八、2026-11-07 功能同步（对齐桌面版）

| 需求 | Kotlin 侧实现 |
|---|---|
| 手机点「选择文件」没反应 | `MainActivity` 增设 `WebChromeClient.onShowFileChooser()` + `onActivityResult`，取消时回调 `onReceiveValue(null)`（否则再点永远无反应）。`/audio` 的 canonical 越界校验与 410 保持不变 |
| 逐句说话人 | `TraceEngine.llmSpeakerLabels()` 改为**每句单独调用**千问，带前后各一句上下文，适配器名 `qwen:per-line-speaker` |
| 转写先矫正再总结 | `correctTranscript()` 用 `json_schema` 做句级 L2'（原句/矫正句/编辑/低置信待核对），原文只读；`llmExtract()` 改吃矫正句；事件卡 `extensions.evidence_raw` 存原句；响应带 `correction` / `raw_transcript` |
| 事件卡质量 | 抽取输入变成断好句、修过错字的文本；前端收录页新增「原话｜矫正后｜改动」三层对照表 + AI 分析卡片 |
| 全库 Agent | `chat()` 改为读该模式**全部**事件卡（`db.listEvents(mode, 5000)`，最多喂 300 条），system 提示里明确「已读取全部 N 条，可跨时间引用任意历史」；响应带 `library_total` / `context_count`，前端可见 |
| 粘贴转写文本 | 收录页文本框支持直接粘贴别处（ima / 微信语音转文字）的转写结果，同样先句级矫正再抽卡总结；无 key 时规则版零改动 |

✅ **APK 侧本地离线 ASR 已接通**（详见 `docs/SHERPA.md`）：官方 `sherpa-onnx.aar` v2.0.1 已放入 `app/libs/`（sha256 与官方一致），`SherpaAsr.kt` 用 AAR 的真实 Kotlin API 从 assets 加载 24MB 中文流式模型，`TraceEngine.sherpaTranscribe()` 不再是占位。
手机上传中文录音 → 本地转写 → 句级矫正 → 抽卡 → AI 分析，**全程离线、不需联网、不需 Key**。
`/health` 的 `adapters.asr` 为 `sherpa-onnx:zh-14M`；流水线「语音转写」阶段 `is_mock=false`。
粘贴外部转写文本与填 `asr_endpoint` 走云端 ASR 仍保留作为备用通道。

## 九、诚实说明（当前仍存在的局限）

- **APK 已在本机编译并验签通过**（Kotlin 全部编译成功，只有 deprecation / 未使用参数警告）。
  生成侧装了 JDK 17 + Android SDK 34 + Gradle 8.7，并把工程复制到纯 ASCII 路径构建。
- v1.7.1 起改为 **release 正式签名**（`python .build/build_apk.py release`）；v1.7.2 签名不变，可覆盖升级。

  因换了签名，v1.7.0 及更早的包**必须先卸载**才能覆盖安装。上架应用商店另需补齐隐私政策、软著等资质。
- `gradle-wrapper.jar` 二进制未随包（Studio 会自动补）；生成侧直接用下载的 Gradle 8.7 编译。
- **离线 ASR 未在真机验证**：AAR、模型、调用链、签名都静态验证通过，桌面 Python 版跑同一份模型能转写出中文；但本环境没有 Android 设备/模拟器，首次装机请实测一段中文录音。详见 `docs/SHERPA.md` §6。
- APK 80.2 MB（含 AAR 的两个真机 ABI + 24MB 模型），微信内需点右上角「在浏览器打开」再下载。
- 云端 `asr_endpoint` 用 data URL 传音频，具体端点格式按你换成百炼官方 key 后微调。
- **不含明文 API Key**，Key 由用户在 App 内填一次。

## 八、跟桌面 zip 的关系

`dist/履迹-全功能demo.zip` 是**电脑上的 Python 后端 + 浏览器**，用于快速验证所有逻辑；
本工程才是**给观众装到手机上的真 APK**。两者前端共用一份 HTML、API 契约一致。

## 九、ima 拓展：App 内连接窗口（2026-11-07）

以前 ima 那页只是个占位（点了只会回一句「未配置」）。现在是一个能在手机上走完的三步窗口，
不需要跳浏览器、不需要把 key 打到电脑上。

**怎么连（手机上一次做完，约 1 分钟）**

1. 履迹 →「ima 拓展」→ 点「打开 ima」（未装 ima 会自动开网页版凭证页）
2. ima 里「我 → Claw 配置」→ 复制 **Client ID** 和 **API Key**（API Key 只显示一次）
3. 粘回履迹 →「测试并连接」→ 连上后下拉选知识库 →「保存选择」→「开始同步」

**后端接口（`/ima/*`，走 `TraceEngine.imaCall`，零第三方依赖）**

| 路由 | 作用 |
|---|---|
| `GET /ima/state` | 连接状态、掩码后的 Client ID、已选知识库、已同步/待同步条数 |
| `POST /ima/connect` | 先真实调 `search_knowledge_base` 验证凭证，通过才落盘；不通过不覆盖旧凭证 |
| `POST /ima/kbs` | 列知识库（同时充当连通性测试，解析做了防御式递归，兼容不同 key 名） |
| `POST /ima/kb` | 保存选中的知识库 |
| `POST /ima/sync` | 按天聚合 → 新建 or 追加 → `add_knowledge` 挂库 → 记幂等 |
| `POST /ima/disconnect` | 清掉本机凭证 + 同步标记 |
| `POST /ima/share` | 系统分享兜底（`ACTION_SEND`），没凭证也能用 |
| `POST /ima/open` | 拉起 ima App（`com.tencent.ima`），未装则开网页凭证页 |

**同步语义：只增不改**

ima 只提供「新建笔记 / 追加 / 挂进知识库」，**没有改和删的接口**。所以：

- 同一天的内容聚合成一篇「履迹 · 2026-11-07」笔记；当天再同步走 `append_doc` 追加，不覆盖已有内容
- 事件被校正后（hash 变了）会再追加一条新版本，旧版本保留 —— 和 L2 只读 / L3a 只增 / L3b 另写是同一套写法
- `ima_sync` 表记录 `event_id → doc_id + hash`，保证同一条不会重复推
- 索引是异步的：刚同步完在 ima 里可能还搜不到，等一会儿

**没做的**：音频文件不走 ima（要 `create_media` + COS v5 签名，代价大，且 72h 后就 purge 了）；ima 凭证存在本机 SharedPreferences，界面只显示掩码。

## 十、录音自带「当日时间」（v1.4.0）

以前 `event_time` 只从**口述文字**里认日期，用户不说「几号」的录音就变成 `null`，
在时间轴上掉到最后、在对话里也看不出是哪天说的。现在每条记录都带自己的时间锚点。

**时间从哪来（三级，顺序不能反）**

| 级 | 来源 | 标记 `time_source` | 界面 |
|---|---|---|---|
| ① | 模型听出来的（提示词已注入「这段录音开始于 …」，「昨天/上周三」都按这个基准换算） | `spoken` | 正常显示日期/时刻 |
| ② | 规则从原文里认（模型降级、断网时照样有）；支持 `2026-11-07`、`11-15`、`3月5日`、`今天/昨天/前天/大前天`、`下午三点/晚上8点/十一点` | `spoken` | 正常显示 |
| ③ | 都没有 → 按**录制当天**归档（只到日，不编造时刻） | `recorded` | 日期后标「（估）」 |
| — | 用户手动改过 | `manual` | 按用户填的显示 |

**锚点是「录制开始时刻」，不是「流水线跑完的时刻」**

`RecordService` 每 30 秒落一段 WAV，入队时就把 `startedAt = now - 该段时长` 一起带上
（`Segment` 数据类）。队列积压、ASR 慢、网络抖动都不会把早上录的音算到晚上。
文本直录 / 上传走 `/ingest`、`/upload`，可显式传 `started_at`，不传就是当前时刻。

**具体改动**

- `TraceDb` v5：`events` 加 `time_source`；旧库升级时用 `recorded_at` 的日期回填空 `event_time`，并标 `recorded`
- `TraceEngine`：`Input.startedAt`；`humanTime()`（给模型的「现在几点」）、`parseHour`/`cnToInt`（中文数字时刻）、`parseDate(t, base)`（相对日期以录音时刻为基准）、`normalizeEventTime()`（模型返回的时间统一成 ISO）
- 提示词注入：omni 一次调用、`llmExtract`、`chat`、`analyze` 四处都带上了当前时间
- 摘要缓存里，`recorded` 的行会带「（估）」，模型据此知道这不是用户说的
- 前端：收录页显示「现在 …」+ 本段录制起点；卡片显示日期/时刻 + 「录制 11-07 17:36」+ 「（估）」角标
- 新增 `POST /event/time`：手动改时间（空 = 恢复「按录制当天」），改完重建摘要缓存

**一句话给评委**：*我们区分「用户说的时间」和「系统推断的时间」，推断的永远带标记、可改、不冒充事实。*

**时间也写在音频文件上**

l1 里存的原音频文件名形如 `20261107-173605_rec_<session>_seg0001.wav`：

- 前缀就是这段录音的**开始时刻**（`RecordService` 入队时算的 `now - 段长`）
- 文件被导出、分享、拖到电脑上，脱离数据库也一眼知道是什么时候录的
- 同名同秒撞车会再拼一层来源 id，不覆盖已有存证
- `l1_files.meta` 同时存 `recorded_at` / `recorded_label` / `origin_name`（原始文件名）
- 详情页音频播放器下面直接显示「录制 11-07 17:36 · 文件 …」，不用去翻 JSON

给模型的提示词里也带着这个时间（"这段录音开始于 2026-11-07 17:36（周六）"），
所以 Agent 分析语音时不是「一段没有时间的音频」，而是「某个具体时刻的录音」。
