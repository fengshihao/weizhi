# 脚本模块怎么加载（给 AI / Agent 提示用）

Weizhi **没有 npm**。**给 AI 写脚本：一律用 `import`**（一种语法，最不容易混用）。

---

## AI 规则（一条）

| 加载什么 | AI 怎么写 |
|---|---|
| **引擎内置**（`fs`、`path`、`buffer`、`process`、`zlib`、`zip`） | `import fs from "fs"`、`import zip from "zip"` 等 |
| **脚本目录自建库**（`setScriptFolder` 下叶子 `.js`） | `import … from "叶子名.js"`（**不要** `./`），或 `await import(...)` |
| **用户放在 workspace、与当前脚本同目录的文件** | `import … from "./名字.js"` |

整段 `runJs` 含 `import` / `export` 时按 **ES 模块** 执行；用 `export default` 作为本轮返回值。

**没有 `loadScript`**（已移除）。

**两个根（勿混）**：

- **`setFsRoot`** — 工作区（用户文件、`fs`、orchestrator 入口如 `jobs/run.js`）
- **`setScriptFolder`** — catalog 脚本库（`docx.js` 等）

**解析顺序（ES module）**：内置模块名走引擎；裸说明符 `docx` / `docx.js` 在 `setScriptFolder` 根目录查找；带 `/` 或绝对路径的说明符先按 **workspace** 解析（见下节），文件不存在且叶子名合法时**回退**到 `setScriptFolder/<叶子>.js`。

---

## 路径沙箱（`fs` 与 workspace 内 `import`）

**统一规则**（与 [INTEGRATION_FOR_AI.md §0.1](INTEGRATION_FOR_AI.md#01-路径策略引擎-fs--workspace-import) 一致）：

| 输入 | 行为 |
|---|---|
| workspace **相对**路径（`a.txt`、`jobs/run.js`、`./../x.js` 等） | 相对 `setFsRoot` 拼接后 `realpath` / `normalize` |
| **绝对**路径 | 直接解析；仍必须在 `setFsRoot` 目录树下 |
| 越界（`path escape`）、符号链接逃逸（JNI 异步 `fs`） | 拒绝 |
| 反斜杠 `\` | 拒绝（`invalid path`） |

适用于：**`fs` / `fs.promises`**、**`require("zip")` 等工作区内路径**、以及 **从 workspace 加载的 `.js` 模块**（说明符含 `/` 或以 `/` 开头）。

**`import './util.js'` 相对谁？** QuickJS 相对**当前模块路径**；入口模块路径来自 `runJs(source, timeoutMs, filename)` 的 `filename`（如 `jobs/run.js`）。未传 `filename` 时为 `<eval>`，相对 import 不可靠——编排入口应始终传 `filename`。

**Catalog 裸导入**（`import from "docx.js"` / `"docx"`）仍只在 `setScriptFolder` **根目录**找 **单层叶子** `[A-Za-z0-9._-]+.js`，不支持 `catalog/sub/foo.js`。

**集成方变更提示**：旧版会拒绝「绝对路径」或路径中含 `..` 的 `fs` / workspace `import`（`invalid path`）。现版改为**先归一化再校验**；越界仍失败。若你们曾在宿主侧把路径强行改成相对路径，可逐步去掉，交给引擎统一处理。

---

## `require()`（保留，但不教 AI 用）

引擎仍支持 **`require("fs")`** 等（与 Node 子集一致），供宿主集成、旧脚本、部分 catalog 库内部使用。

**Agent 系统提示里不要出现 `require`**，避免与 `import` 混写。若 AI 误写 `require`，宿主可提示改写成 `import … from "fs"`。

---

## 决策树（贴进 Agent 提示）

```
import 内置模块名（fs / path / buffer / process / zlib / zip）→ from "模块名"
import catalog 脚本库（setScriptFolder 下的 leaf.js）→ from "叶子名.js"（不要 ./）
否则（workspace 内用户脚本，常与同目录相对）→ from "./名字.js" 或 workspace 内绝对/相对路径（须在 setFsRoot 下）
```

---

## 示例

```javascript
import fs from "fs";
import zip from "zip";
import { markdownToDocx, Document, renderDocx } from "docx.js";

markdownToDocx({ inputPath: "a.md", outputPath: "out/a.docx" });
const doc = Document.create({ title: "标题" });
doc.addParagraph("正文");
export default renderDocx(doc, "out/b.docx");
```

---

## 相关规格

- [DECISIONS.md](DECISIONS.md)
- [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md)
