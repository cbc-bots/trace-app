"""组件市场（《我的世界》模组式）：GitHub 仓库 = 一个组件。

三类：classifier（排序分类）/ agent / view（可视化）。
安装：粘贴 GitHub 仓库 URL 或从内置精选列表选 → 拉 manifest.json → 校验 → 落库 → 启用。
无网络时可直接 POST 本地 manifest JSON 安装。
"""
from __future__ import annotations

import json
import os
import urllib.request
from typing import Any

from core import REQUIRED_MANIFEST

# 骑砍式模组文件夹：把模组目录丢进来即可，无需重启（可调 /components/scan）
MODULES_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "modules")
OPTIONAL_FILES = ("prompt.md", "schema.json", "view.json", "README.md")

BUILTIN_COMPONENTS = [
    {"id": "life-emotion", "type": "classifier", "name": "人生记录 · 情绪与事件分类",
     "version": "1.0.0", "repo": "local://builtin/life-emotion", "minCore": "1.0",
     "modes": ["life"], "entry": "builtin", "description": "受控词表归一 + 事件类型枚举"},
    {"id": "study-topic", "type": "classifier", "name": "学习 · 知识点归类",
     "version": "1.0.0", "repo": "local://builtin/study-topic", "minCore": "1.0",
     "modes": ["study"], "entry": "builtin", "description": "按知识点与掌握度归类"},
    {"id": "relation-event", "type": "classifier", "name": "关系 · 互动归类",
     "version": "1.0.0", "repo": "local://builtin/relation-event", "minCore": "1.0",
     "modes": ["relation"], "entry": "builtin", "description": "按人物与互动类型归类"},
    {"id": "growth-quadrant", "type": "classifier", "name": "成长 · 四象限归类",
     "version": "1.0.0", "repo": "local://builtin/growth-quadrant", "minCore": "1.0",
     "modes": ["growth"], "entry": "builtin", "description": "Human 3.0 四象限与层级"},
    {"id": "base", "type": "agent", "name": "基础助手（自由生成）",
     "version": "1.0.0", "repo": "local://builtin/base", "minCore": "1.0",
     "entry": "builtin", "description": "直连知识库，自由回答自由生成，无输出闸门"},
    {"id": "timeline", "type": "view", "name": "时间轴",
     "version": "1.0.0", "repo": "local://builtin/timeline", "minCore": "1.0",
     "entry": "builtin", "description": "按 event_time 分组的人生履历"},
    {"id": "mastery", "type": "view", "name": "知识点掌握度",
     "version": "1.0.0", "repo": "local://builtin/mastery", "minCore": "1.0",
     "entry": "builtin", "description": "学习模式视图"},
    {"id": "relation-timeline", "type": "view", "name": "关系时间线",
     "version": "1.0.0", "repo": "local://builtin/relation-timeline", "minCore": "1.0",
     "entry": "builtin", "description": "按人物分组的关系视图"},
    {"id": "quadrant", "type": "view", "name": "四象限成长图",
     "version": "1.0.0", "repo": "local://builtin/quadrant", "minCore": "1.0",
     "entry": "builtin", "description": "Human 3.0 式象限视图"},
]

MARKET_CATALOG = [
    {"id": "oss-sherpa-asr", "type": "classifier", "repo": "https://github.com/k2-fsa/sherpa-onnx",
     "name": "sherpa-onnx 离线语音流水线", "version": "1.0.0", "minCore": "1.0",
     "description": "ASR + VAD + 说话人，纯 CPU 离线"},
    {"id": "oss-funasr", "type": "classifier", "repo": "https://github.com/modelscope/FunASR",
     "name": "FunASR 中文全流水线", "version": "1.0.0", "minCore": "1.0",
     "description": "转写 + 标点 + VAD + 说话人分离"},
    {"id": "oss-llamaindex-rag", "type": "agent", "repo": "https://github.com/run-llama/llama_index",
     "name": "LlamaIndex RAG 对话分析", "version": "1.0.0", "minCore": "1.0",
     "description": "索引 + 查询引擎，对话分析"},
    {"id": "oss-ragflow", "type": "agent", "repo": "https://github.com/infiniflow/ragflow",
     "name": "RAGFlow 混合检索与溯源", "version": "1.0.0", "minCore": "1.0",
     "description": "答案绑定原始片段，契合证据引用"},
    {"id": "oss-instructor", "type": "classifier", "repo": "https://github.com/jxnl/instructor",
     "name": "instructor 结构化抽取", "version": "1.0.0", "minCore": "1.0",
     "description": "Pydantic schema 约束 LLM 输出"},
    {"id": "oss-chroma", "type": "classifier", "repo": "https://github.com/chroma-core/chroma",
     "name": "Chroma 向量排序", "version": "1.0.0", "minCore": "1.0",
     "description": "语义检索排序"},
    {"id": "oss-vis-timeline", "type": "view", "repo": "https://github.com/visjs/vis-timeline",
     "name": "vis-timeline 时间轴组件", "version": "1.0.0", "minCore": "1.0",
     "description": "可交互时间轴"},
    {"id": "oss-echarts", "type": "view", "repo": "https://github.com/apache/echarts",
     "name": "ECharts 统计视图", "version": "1.0.0", "minCore": "1.0",
     "description": "四象限 / 掌握度统计图"},
    {"id": "oss-pluggy", "type": "agent", "repo": "https://github.com/pytest-dev/pluggy",
     "name": "pluggy 插件注册内核", "version": "1.0.0", "minCore": "1.0",
     "description": "成熟插件系统，可作模组市场内核"},
]


GH_PROXY = "https://gh-proxy.com/"


def manifest_urls(repo: str) -> list[str]:
    """生成 manifest 候选地址：raw 直连 + gh-proxy 镜像 + main/master 两分支。

    沙箱里 raw.githubusercontent.com 会被 SSL 拦，gh-proxy.com 镜像可用；
    用户自己的机器上直连通常更快，所以直连优先。
    """
    repo = repo.rstrip("/")
    if repo.startswith("local://"):
        return []
    if "github.com" not in repo:
        return [f"{repo}/manifest.json"]
    path = repo.replace("https://github.com/", "").replace("http://github.com/", "")
    out = []
    for branch in ("main", "master"):
        raw = f"https://raw.githubusercontent.com/{path}/{branch}/manifest.json"
        out.append(raw)
        out.append(f"{GH_PROXY}{raw}")
    return out


def scan_modules(store, modules_dir: str | None = None) -> dict:
    """《骑马与砍杀》Modules 式：扫描目录，每个含 manifest.json 的子文件夹自动注册。

    - 放进来 = 安装并启用；删掉文件夹再扫描 = 自动卸载
    - manifest 可只写 `{"type":"agent","name":"..."}`，id/repo/version/minCore 自动补
    - 同目录的 prompt.md / schema.json / view.json / README.md 会被读进 manifest，
      供 agent / classifier / view 组件直接使用（声明式，无需执行第三方代码）
    """
    root = modules_dir or MODULES_DIR
    os.makedirs(root, exist_ok=True)
    result = {"root": root, "installed": [], "removed": [], "errors": []}
    ok_ids: list[str] = []

    for name in sorted(os.listdir(root)):
        path = os.path.join(root, name)
        if not os.path.isdir(path) or name.startswith((".", "_")):
            continue
        mf = os.path.join(path, "manifest.json")
        if not os.path.isfile(mf):
            continue
        try:
            with open(mf, "r", encoding="utf-8") as f:
                m = json.load(f)
        except Exception as e:
            result["errors"].append({"folder": name, "error": f"manifest.json 解析失败: {e}"})
            continue
        m.setdefault("id", name)
        m.setdefault("name", name)
        m.setdefault("version", "1.0.0")
        m.setdefault("minCore", "1.0")
        m.setdefault("repo", f"folder://modules/{name}")
        m["source"] = "folder"
        m["folder"] = os.path.relpath(path, os.path.dirname(root)).replace("\\", "/")
        for extra in OPTIONAL_FILES:
            fp = os.path.join(path, extra)
            if os.path.isfile(fp):
                with open(fp, "r", encoding="utf-8") as f:
                    m[extra.replace(".", "_")] = f.read()[:20000]
        try:
            store.install_component(m, enabled=True)
            ok_ids.append(m["id"])
            result["installed"].append({"id": m["id"], "type": m["type"], "name": m["name"]})
        except Exception as e:
            result["errors"].append({"folder": name, "error": str(e)})

    # 文件夹被删掉 → 同步卸载（只处理 folder 来源，不碰 GitHub 安装的组件）
    for c in store.list_components():
        if c["manifest"].get("source") == "folder" and c["id"] not in ok_ids:
            store.uninstall_component(c["id"])
            result["removed"].append(c["id"])
    return result


class ComponentMarket:
    def __init__(self, store, timeout: int = 25):
        self.store = store
        self.timeout = timeout

    def seed_builtins(self) -> int:
        n = 0
        for m in BUILTIN_COMPONENTS:
            self.store.install_component(m, enabled=True)
            n += 1
        return n

    def catalog(self) -> list[dict]:
        installed = {c["id"] for c in self.store.list_components()}
        return [{**m, "installed": m["id"] in installed} for m in MARKET_CATALOG]

    def validate(self, manifest: dict) -> list[str]:
        errs = [f"缺少 {k}" for k in REQUIRED_MANIFEST if not manifest.get(k)]
        if manifest.get("type") and manifest["type"] not in ("classifier", "agent", "view"):
            errs.append("type 必须是 classifier / agent / view")
        return errs

    def _get(self, url: str) -> str:
        req = urllib.request.Request(url, headers={"User-Agent": "trace-component-market/1.0"})
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            return r.read().decode("utf-8")

    def _retry(self, urls: list[str], rounds: int = 3) -> tuple[str | None, list[str]]:
        """沙箱代理会间歇性 SSL EOF / 握手超时，多候选 × 多轮重试。"""
        errs: list[str] = []
        for _ in range(rounds):
            for url in urls:
                try:
                    return self._get(url), errs
                except Exception as e:
                    errs.append(f"{type(e).__name__}")
        return None, errs

    def fetch(self, repo: str) -> dict:
        if repo.startswith("local://"):
            raise ValueError("本地组件请直接提交 manifest JSON")
        urls = manifest_urls(repo)
        text, errs = self._retry(urls, rounds=2)
        if text is None:
            raise ValueError(f"无法拉取 {repo}/manifest.json（{len(urls)} 个候选 × 2 轮）：{errs[:4]}")
        return json.loads(text)

    def fetch_file(self, raw_url: str) -> str:
        """从 GitHub 拉任意源码文件（用于把官方示例挪进 vendor/ 参考）。"""
        urls = [raw_url, f"{GH_PROXY}{raw_url}"]
        text, errs = self._retry(urls, rounds=3)
        if text is None:
            raise ValueError(f"无法拉取 {raw_url}（2 个候选 × 3 轮）：{errs[:4]}")
        return text

    def install(self, *, manifest: dict | None = None, repo: str | None = None,
                enabled: bool = False) -> dict:
        if manifest is None and repo:
            manifest = self.fetch(repo)
        if not manifest:
            raise ValueError("需要 manifest 或 repo")
        if repo and not manifest.get("repo"):
            manifest["repo"] = repo
        errs = self.validate(manifest)
        if errs:
            raise ValueError("; ".join(errs))
        return self.store.install_component(manifest, enabled=enabled)
