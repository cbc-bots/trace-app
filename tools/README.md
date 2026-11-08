# tools/ · 交接用压测与验证脚本

改完常驻录音（A1/A2/A3）或保留策略（F）之后必须重跑这三个。
第一个参数都是 trace-demo 根目录（含 backend/ 与 bin/ 的那一层）。

```bash
python tools/bench_recorder.py   <root>  AFTER
python tools/probe_audio.py      <root>  AFTER  8802
python tools/verify_retention.py <root>
```

## 通过标准

| 脚本 | 关键指标 | 通过标准 |
|---|---|---|
| `bench_recorder.py` | 放大倍数 | **== 1.0** |
| | 其中重复卡 | **== 0** |
| | push 最大延迟 | **< 0.1 s** |
| | L1 分段文件 | 每段独立编号，无覆盖 |
| `probe_audio.py` | 合法回放 | 200 |
| | 越界（`../`、绝对路径、`win.ini`） | **全部 404/410** |
| `verify_retention.py` | 全部断言 | 退出码 0 |

`bench_recorder.py` 用 `FakeASR` 注入 `ADAPTER_MAP["asr"]`，模拟真实离线识别
（耗时 = 0.3 x 音频时长，每 3 秒产出一句可辨识文本），因此在没装 sherpa-onnx
的机器上也能测出重复入库与阻塞。换成真实 ASR 后放大倍数结论不变，延迟数字会更大。

`verify_retention.py` 自包含：测试音频用包内 `backend/models/asr-zh-14M/test_wavs/0.wav`
循环拼成约 60 秒，无需外部素材。端口可用环境变量 `TRACE_PORT` 覆盖。

## 改前基线（BEFORE，用于对比）

在**未打补丁**的原版上跑 `bench_recorder.py`，27 秒录音 / 9 次 push 的结果是：

```
pipeline调用次数      4
实际处理音频(s)     81.0     <- 放大 3.0x
生成事件卡          27
其中重复卡          18       <- 应有 9 张
push最大延迟(s)     8.58
整体墙钟(s)        25.78     <- 处理 27s 录音花了 25.78s，追不上实时
l1分段文件          1 个（反复覆盖）
```

外推到 8 小时常驻、30 秒一次 flush（放大倍数 ≈ (M+3)/2，M=960）：
处理量 ≈ 3840 小时，云端 ASR ≈ 1106 元/天，且永远追不上实时。
