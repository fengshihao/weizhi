# 脚本模块怎么加载（给 AI / Agent 提示用）

Weizhi **没有 npm**。**给 AI 写脚本：一律用 `import`**（一种语法，最不容易混用）。

> 官方库：**`setPackageRoot`** + `import … from "weizhi:<name>"`（见 [WEIZHI_PACKAGES.md](WEIZHI_PACKAGES.md)）。**`setScriptFolder` / catalog 已移除。**

---

## AI 规则（一条）

| 加载什么 | AI 怎么写 |
|---|---|
| **引擎内置**（`fs`、`path`、`buffer`、`process`、`zlib`、`zip`） | `import fs from "fs"`、`import zip from "zip"` 等 |
| **官方 Package**（`packages/<name>@<ver>/`，由宿主安装） | `import … from "weizhi:<name>"` |
| **用户 workspace 脚本**（`setFsRoot` 下） | `import … from "./名字.js"` 或带 `/` 的路径 |

整段 `runJs` 含 `import` / `export` 时按 **ES 模块** 执行；用 `export default` 作为本轮返回值。

**没有 `loadScript`**（已移除）。

**两个根（勿混）**：

- **`setFsRoot`** — 工作区（用户文件、`fs`、orchestrator 入口如 `jobs/run.js`）
- **`setPackageRoot`** — Weizhi home（`packages/` + `packages-index.json`）；脚本用 `weizhi:<name>`，**不要**手写磁盘路径

**解析顺序（ES module）**：内置模块名 → **`weizhi:<name>`**（及包内 `weizhi:<name>/sub.js`）→ workspace 路径说明符（含 `/`）。

---

## 路径沙箱（`fs` 与 workspace 内 `import`）

**统一规则**（与 [INTEGRATION_FOR_AI.md §0.1](INTEGRATION_FOR_AI.md#01-路径策略引擎-fs--workspace-import) 一致）：

| 输入 | 行为 |
|---|---|
| workspace **相对**路径（`a.txt`、`jobs/run.js`、`./../x.js` 等） | 相对 `setFsRoot` 拼接后 `realpath` / `normalize` |
| **绝对**路径 | 直接解析；仍必须在 `setFsRoot` 目录树下 |
| **`@weizhi-package:`** 前缀（仅 `getAssetPath` 返回） | 只读官方包内文件；见 WEIZHI_PACKAGES §5.2 |
| 越界（`path escape`）、符号链接逃逸（JNI 异步 `fs`） | 拒绝 |
| 反斜杠 `\` | 拒绝（`invalid path`） |

**`import './util.js'` 相对谁？** QuickJS 相对**当前模块路径**；入口来自 `runJs(..., filename)`（如 `jobs/run.js`）。未传 `filename` 时为 `<eval>`，相对 import 不可靠。

---

## `require()`（保留，但不教 AI 用）

引擎仍支持 **`require("fs")`** 等（与 Node 子集一致），供宿主集成、旧脚本使用。

**Agent 系统提示里不要出现 `require`**，避免与 `import` 混写。

---

## 决策树（贴进 Agent 提示）

```
import 内置模块名（fs / path / buffer / process / zlib / zip）→ from "模块名"
import 官方 Package → from "weizhi:<name>"
否则（workspace）→ from "./名字.js" 或 workspace 内路径（须在 setFsRoot 下）
```

---

## 示例

```javascript
import fs from "fs";
import { ping } from "weizhi:leaf";

fs.writeFileSync("note.txt", ping());
export default fs.readFileSync("note.txt").toString();
```

---

## 相关规格

- [DECISIONS.md](DECISIONS.md)
- [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md)
- [WEIZHI_PACKAGES.md](WEIZHI_PACKAGES.md)
