"""打包「履迹 Trace 全功能 Demo」安装包。

生成一个可解压到任意位置、双击 start.bat 就能跑的 zip。
包含：后端代码 + 简陋前端 + 24MB 中文 ASR 模型 + ffmpeg 二进制 + 两个示例模组。
不包含：明文 API Key（前端「设置」页填一次）。

运行： python backend/build_demo.py
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DIST = os.path.join(ROOT, "dist")
PKG = os.path.join(DIST, "trace-demo")
ZIP = os.path.join(DIST, "履迹-全功能demo.zip")

PY_FILES = ["run.py", "core.py", "pipeline.py", "adapters_builtin.py",
            "adapters_oss.py", "components.py", "analysis.py",
            "smoke_test.py", "live_test.py", "fetch_asr_model.py",
            "fetch_big_asr.py", "gen_presets.py"]
DOC_FILES = ["README.md", "requirements-optional.txt"]
MODEL_FILES = ["tokens.txt", "encoder-epoch-99-avg-1.int8.onnx",
               "decoder-epoch-99-avg-1.int8.onnx", "joiner-epoch-99-avg-1.int8.onnx",
               os.path.join("test_wavs", "0.wav")]

START_BAT = '@echo off\r\nsetlocal EnableExtensions EnableDelayedExpansion\r\nchcp 65001 >nul\r\ncd /d "%~dp0"\r\necho.\r\necho  ============================================\r\necho   履迹 Trace - 全功能 Demo\r\necho  ============================================\r\necho.\r\n\r\nrem ---- 解释器探测 ----------------------------------------------------\r\nrem PATH 里的 python 常常是 Microsoft Store 占位符（0 字节 reparse point），\r\nrem 双击只会弹出应用商店；这里逐个候选真跑一次 -c 验证，取第一个可用的。\r\nrem 也可先 set TRACE_PY=C:\\path\\to\\python.exe 手动指定。\r\nset "PY="\r\nif defined TRACE_PY set "PY=%TRACE_PY%"\r\nif not defined PY (\r\n  for %%C in ("python" py "%USERPROFILE%\\anaconda3\\python.exe" "%USERPROFILE%\\miniconda3\\python.exe" "%LOCALAPPDATA%\\Programs\\Python\\Python314\\python.exe" "%LOCALAPPDATA%\\Programs\\Python\\Python313\\python.exe" "%LOCALAPPDATA%\\Programs\\Python\\Python312\\python.exe" "%LOCALAPPDATA%\\Programs\\Python\\Python311\\python.exe" "%LOCALAPPDATA%\\Programs\\Python\\Python310\\python.exe" "C:\\Python313\\python.exe" "C:\\Python312\\python.exe") do (\r\n    if not defined PY (\r\n      %%C -c "import sys;sys.exit(0 if sys.version_info>=(3,10) else 1)" >nul 2>&1\r\n      if not errorlevel 1 set "PY=%%C"\r\n    )\r\n  )\r\n)\r\nif not defined PY (\r\n  echo  [错误] 没找到可用的 Python 3.10+\r\n  echo.\r\n  echo  任选一种解决：\r\n  echo    1^) 安装 Python 3.10+ 并勾选 Add to PATH\r\n  echo    2^) 先执行  set TRACE_PY=C:\\路径\\python.exe  再双击本脚本\r\n  echo.\r\n  pause\r\n  exit /b 1\r\n)\r\necho  使用解释器: !PY!\r\n!PY! -c "import sys;print(\'  Python 版本: \'+sys.version.split()[0])"\r\necho.\r\necho  浏览器会自动打开 http://127.0.0.1:8765/\r\necho  首次使用请在页面「设置 / 填 Key」里粘贴 API Key\r\necho.\r\n!PY! -c "import sherpa_onnx" >nul 2>&1\r\nif errorlevel 1 (\r\n  echo  [提示] 缺少 Python 依赖，正在自动安装...\r\n  call install.bat nopause\r\n)\r\nstart "" /min cmd /c "timeout /t 2 >nul & start http://127.0.0.1:8765/"\r\n!PY! backend\\run.py\r\npause\r\n'

INSTALL_BAT = '@echo off\r\nsetlocal EnableExtensions EnableDelayedExpansion\r\nchcp 65001 >nul\r\ncd /d "%~dp0"\r\nif not defined PY set "PY=python"\r\nif defined TRACE_PY set "PY=%TRACE_PY%"\r\necho 正在安装 Python 依赖（国内镜像）...\r\necho 解释器: !PY!\r\n!PY! -m pip install -i https://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com ^\r\n  numpy soundfile sherpa-onnx rank_bm25 pluggy instructor openai pydantic imageio-ffmpeg\r\necho 完成。\r\nif /i not "%~1"=="nopause" pause\r\n'

START_SH = '#!/usr/bin/env bash\nset -e\ncd "$(dirname "$0")"\nPY="${TRACE_PY:-}"\nif [ -z "$PY" ]; then\n  for c in python3 python; do\n    if command -v "$c" >/dev/null 2>&1 && "$c" -c \'import sys;sys.exit(0 if sys.version_info>=(3,10) else 1)\' 2>/dev/null; then\n      PY="$c"; break\n    fi\n  done\nfi\nif [ -z "$PY" ]; then echo "[错误] 没找到 Python 3.10+，请先安装或 export TRACE_PY=/path/to/python3"; exit 1; fi\necho "使用解释器: $PY ($("$PY" -V 2>&1))"\n"$PY" -c "import sherpa_onnx" 2>/dev/null || {\n  echo "安装依赖..."; "$PY" -m pip install -r backend/requirements-optional.txt; }\n(sleep 2; (xdg-open http://127.0.0.1:8765/ 2>/dev/null || open http://127.0.0.1:8765/ 2>/dev/null)) &\n"$PY" backend/run.py\n'

PKG_README = """# 履迹 Trace · 全功能 Demo 安装包

## 三步跑起来

1. 装 **Python 3.10+**（Windows 记得勾选 Add to PATH）
2. 双击 **`start.bat`**（Mac/Linux 用 `start.sh`）—— 会自动装依赖、起服务、开浏览器
   - 若依赖安装失败，手动双击 `install.bat`
3. 浏览器打开 `http://127.0.0.1:8765/`，在左侧 **「设置 / 填 Key」** 粘贴你的 API Key

> **包里不含明文 Key**，Key 只保存在你本机的 `backend/config.json`。
> 建议用比赛专用 key + 额度上限 + 赛后吊销；别把这个包传到公开仓库。

## 包里有什么（全部可用，不依赖外网）

| 内容 | 说明 |
|---|---|
| `backend/*.py` | 零第三方依赖也能起服务；装上 pip 包后自动切真实实现 |
| `backend/demo/index.html` | 简陋前端，覆盖全部 12 个功能页 |
| `backend/models/asr-zh-14M/` | **24MB 中文离线 ASR 模型**（sherpa-onnx int8 三件套 + 测试音频） |
| `bin/ffmpeg.exe` | **ffmpeg 二进制**，手机 m4a/mp3/aac/amr/webm 都能转 16k mono |
| `backend/modules/` | 骑砍式模组文件夹，两个示例已放好 |

## 左侧 12 个页面 = 全部现有功能

| 页面 | 能体验什么 |
|---|---|
| 概览 / 状态 | `/health`、适配器来源、Key/ffmpeg/模组状态、全局模式切换 |
| 一站式收录 | 上传任意格式录音（真 ASR）、文本直录、**Q1 程序常驻收音**会话 |
| 自动流水线 | 收录→VAD→ASR→说话人→抽取→成卡→排序 8 阶段，每步标 adapter |
| 知识库检索 | FTS5 + 中文分词 + rank_bm25、统计快照锁定 |
| 可视化 | 时间轴 / 掌握度 / 四象限 / 关系时间线 四种 view |
| 证据回放 / 校正 | 原话、音频定位播放、双时间、L3a 快照 vs L3b 校正 |
| Agent 对话 | 真实 qwen3.8-max 自由生成，可给建议/计划/评估（无红线） |
| 模式管理 | `mode_id` 数据隔离、新建模式 |
| 组件市场 | 模组文件夹热扫描、manifest 安装、启停/卸载、GitHub 精选目录 |
| AI 分析 | 收录后自动生成摘要/要点/跨时间模式/建议/提醒/成长计划，按模式换 persona |
| Agent 对话 | 6 套人格预设（人生教练/温柔伴侣/严厉督学/Human 3.0 成长教练/关系镜子/生涯规划师）随时切换，同时作用于 AI 分析与对话 |

> 转写默认用 24MB 的 sherpa-onnx zh-14M（包内已带）；生成侧另有 239MB SenseVoice 大模型，
> 因体积太大未打进本包，需要时运行 `python backend/fetch_big_asr.py` 下载即可自动切换。
| ima 拓展 | 可选单向导出，未配凭证时明确报错且不影响主链路 |
| 设置 / 填 Key | 填 key、看配置（隐藏密钥）、看依赖状态 |

## 验证命令

```
python backend/smoke_test.py   # 45/45 确定性回归（不需要网络与 key）
python backend/live_test.py    # 17/17 真实链路（需要 key）
```

## 常见问题

- **端口被占用**：改 `backend/config.json` 的 `port`。
- **上传 mp3 没结果**：确认 `/health` 里 `ffmpeg` 有值；包内 `bin/ffmpeg.exe` 会自动被找到。
- **Agent 回答是模板**：说明 key 没填或调用失败，页面会显示「已降级」及原因。
- **想换云端 ASR**：填百炼官方 key 后，在 `config.json` 加 `dashscope.asr_endpoint`。
"""


def main():
    if os.path.isdir(PKG):
        shutil.rmtree(PKG)
    os.makedirs(os.path.join(PKG, "backend", "demo"), exist_ok=True)
    os.makedirs(os.path.join(PKG, "backend", "models", "asr-zh-14M", "test_wavs"), exist_ok=True)
    os.makedirs(os.path.join(PKG, "bin"), exist_ok=True)

    for f in PY_FILES + DOC_FILES:
        src = os.path.join(HERE, f)
        if os.path.isfile(src):
            shutil.copy2(src, os.path.join(PKG, "backend", f))
    shutil.copy2(os.path.join(HERE, "demo", "index.html"),
                 os.path.join(PKG, "backend", "demo", "index.html"))

    # config.json：清空 key，保留相对模型路径
    cfg = json.load(open(os.path.join(HERE, "config.json"), encoding="utf-8"))
    cfg["dashscope"]["api_key"] = ""
    cfg["dashscope"]["note"] = "在前端「设置 / 填 Key」里粘贴；本包不含明文凭证"
    # 包内只带 24MB 小模型，清掉大模型路径避免启动检测误报
    cfg["sherpa"]["sense_voice_model"] = ""
    cfg["sherpa"]["sense_voice_tokens"] = ""
    presets = os.path.join(HERE, "presets")
    if os.path.isdir(presets):
        shutil.copytree(presets, os.path.join(PKG, "backend", "presets"), dirs_exist_ok=True)
    json.dump(cfg, open(os.path.join(PKG, "backend", "config.json"), "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)

    for f in MODEL_FILES:
        src = os.path.join(HERE, "models", "asr-zh-14M", f)
        dst = os.path.join(PKG, "backend", "models", "asr-zh-14M", f)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        if os.path.isfile(src):
            shutil.copy2(src, dst)

    mod_src = os.path.join(HERE, "modules")
    if os.path.isdir(mod_src):
        shutil.copytree(mod_src, os.path.join(PKG, "backend", "modules"),
                        ignore=shutil.ignore_patterns("__pycache__"))
    # v1.1 压测/验收工具随包交付，现场可复跑
    tools_src = os.path.join(ROOT, "tools")
    if os.path.isdir(tools_src):
        shutil.copytree(tools_src, os.path.join(PKG, "tools"),
                        ignore=shutil.ignore_patterns("__pycache__"), dirs_exist_ok=True)

    # ffmpeg 二进制（imageio-ffmpeg 自带）
    try:
        import imageio_ffmpeg
        exe = imageio_ffmpeg.get_ffmpeg_exe()
        shutil.copy2(exe, os.path.join(PKG, "bin", "ffmpeg.exe"))
        print("ffmpeg packed:", os.path.getsize(os.path.join(PKG, "bin", "ffmpeg.exe")))
    except Exception as e:
        print("WARN ffmpeg not packed:", e)

    with open(os.path.join(PKG, "start.bat"), "w", encoding="utf-8", newline="") as f:
        f.write(START_BAT)
    with open(os.path.join(PKG, "install.bat"), "w", encoding="utf-8", newline="") as f:
        f.write(INSTALL_BAT)
    with open(os.path.join(PKG, "start.sh"), "w", encoding="utf-8", newline="") as f:
        f.write(START_SH)
    with open(os.path.join(PKG, "README.md"), "w", encoding="utf-8") as f:
        f.write(PKG_README)

    with zipfile.ZipFile(ZIP, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for base, dirs, files in os.walk(PKG):
            dirs[:] = [d for d in dirs if d != "__pycache__"]
            for fn in files:
                full = os.path.join(base, fn)
                z.write(full, os.path.relpath(full, DIST))
    size = os.path.getsize(ZIP)
    print("PACKAGE:", ZIP, round(size / 1024 / 1024, 1), "MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
