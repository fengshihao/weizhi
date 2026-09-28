# 脚本模块怎么加载（给 AI / Agent 提示用）

Weizhi **没有 npm**。**给 AI 写脚本：一律用 `import`**（一种语法，最不容易混用）。

---

## AI 规则（一条）

| 加载什么 | AI 怎么写 |
|---|---|
| **引擎内置**（`fs`、`path`、`buffer`、`process`、`zlib`、`zip`） | `import fs from "fs"`、`import zip from "zip"` 等 |
| **脚本目录自建库**（`setScriptFolder` 下叶子 `.js`） | `import … from "./名字.js"` 或 `await import("./名字.js")` |

整段 `runJs` 含 `import` / `export` 时按 **ES 模块** 执行；用 `export default` 作为本轮返回值。

**没有 `loadScript`**（已移除）。

**路径**：自建库只能是 `./leaf.js`，不能 `./a/b.js`、`..`、绝对路径。

**两个根（勿混）**：

- **`setFsRoot`** — 工作区（用户文件、`fs`）
- **`setScriptFolder`** — 脚本库（只解析 `./leaf.js`）

---

## `require()`（保留，但不教 AI 用）

引擎仍支持 **`require("fs")`** 等（与 Node 子集一致），供宿主集成、旧脚本、部分 catalog 库内部使用。

**Agent 系统提示里不要出现 `require`**，避免与 `import` 混写。若 AI 误写 `require`，宿主可提示改写成 `import … from "fs"`。

---

## 决策树（贴进 Agent 提示）

```
import 内置模块名（fs / path / buffer / process / zlib / zip）→ from "模块名"
否则 → import … from "./某库.js"
```

---

## 示例

```javascript
import fs from "fs";
import zip from "zip";
import { markdownToDocx, Document, renderDocx } from "./docx.js";

markdownToDocx({ inputPath: "a.md", outputPath: "out/a.docx" });
const doc = Document.create({ title: "标题" });
doc.addParagraph("正文");
export default renderDocx(doc, "out/b.docx");
```

---

## 相关规格

- [DECISIONS.md](DECISIONS.md)
- [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md)
