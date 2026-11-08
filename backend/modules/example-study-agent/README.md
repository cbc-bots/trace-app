# 示例模组：家教助教

这就是「骑砍 Modules 文件夹」的最小形态 —— **一个文件夹 + 一个 manifest.json**。

```
backend/modules/
  example-study-agent/          ← 文件夹名随意，丢进来即可
    manifest.json               ← 必填：至少写 type 和 name
    prompt.md                   ← 可选：agent 的 persona / 规则
    README.md                   ← 可选：说明
```

- `id`、`version`、`minCore`、`repo` 不写会自动补齐
- 同目录的 `prompt.md` 会被读进 manifest 的 `prompt_md` 字段，供 agent 组件使用
- 放进来 → 启动时自动扫描注册并启用；改完 → `POST /components/scan` 热重扫，不用重启
- 删掉整个文件夹 → 再扫描会自动卸载
- 想换成 GitHub 分发：把这几个文件传到一个仓库，在 W11 粘贴仓库 URL 即可（走 `manifest.json` 拉取）

可放的可选文件：
| 文件 | 用途 | 读进 manifest 的字段 |
|---|---|---|
| `prompt.md` | agent persona / 输出规则 | `prompt_md` |
| `schema.json` | classifier 扩展字段与归类规则 | `schema_json` |
| `view.json` | view 组件的布局与分组配置 | `view_json` |
| `README.md` | 说明 | `README_md` |
