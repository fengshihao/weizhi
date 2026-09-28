# 脚本模块怎么加载（给 AI / Agent 提示用）

Weizhi **没有 npm**。只有两类加载方式，**不要混用第三种**。

---

## 规则（只记两条）

| 你要加载什么 | 唯一推荐写法 | 不要用什么 |
|---|---|---|
| **引擎内置**（`fs`、`path`、`buffer`、`process`、`zlib`、`zip`） | `require("fs")` 等 | 不要 `require("./xxx.js")`；不要写 `import fs from "fs"` 与 `require("fs")` 混在同一段脚本里挑着用（选一种风格整段统一） |
| **脚本目录里的自建库**（宿主 `setScriptFolder` 下的叶子 `.js`） | `import … from "./名字.js"` 或顶层 `await import("./名字.js")` | **不要用 `loadScript`**（遗留 API，与 `import` 重复，易和内置 `require` 混成三种写法） |

**路径规则（两类共用）**：自建库只能是 **叶子文件名**，例如 `./weizhi-docx.js`。不允许 `./a/b.js`、`..`、绝对路径。

**两个根目录（勿混）**：

- **`setFsRoot`**：工作区，用户产物、`fs.readFileSync('out/x.docx')`。
- **`setScriptFolder`**：只读脚本库，**只有**上面的 `./leaf.js` 从这里解析。

---

## 决策树（复制进 Agent 提示）

```
要用的名字是 fs / path / buffer / process / zlib / zip 之一？
  → 是：require("该名")
  → 否：必须是 ./某库.js → import … from "./某库.js"
```

整段 `runJs` 若含 `import` / `export`，引擎按 **ES 模块** 执行；可用 `export default` 作为本轮返回值。

---

## `loadScript` 是什么？（集成方知晓，AI 不用）

`loadScript("leaf.js")` 与 `import "./leaf.js"` **读同一文件**，但前者按 **全局脚本** 执行（历史行为）。  
**新脚本、新库、Agent1 catalog 一律只用 `import './…'`**，避免 AI 在 `loadScript` / `import` / `require` 之间混用。

集成方旧代码若仍调用 `loadScript`，可继续工作；文档与 [AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md) 不再向 AI 推荐。

---

## 示例

```javascript
import WeizhiDocx from "./weizhi-docx.js";

const fs = require("fs");
const zip = require("zip");

host.office.docx.fromMarkdown({ inputPath: "a.md", outputPath: "out/a.docx" });
const doc = WeizhiDocx.open("out/a.docx");
doc.setTitle("标题");
doc.save("out/a-final.docx");

export default { path: "out/a-final.docx" };
```

---

## 相关规格

- [DECISIONS.md](DECISIONS.md) — 脚本库命名、16MB 上限
- [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) — `setScriptFolder` / `setFsRoot`
