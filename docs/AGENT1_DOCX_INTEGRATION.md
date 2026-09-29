# Agent1 集成：Word（docx.js / docx-raw.js）

日期：2026-09-29。来源：Issue #6 演进。真源 API 见 [office-js-api.md](office-js-api.md)。

---

## 1. 目标

让 Agent1 里的 LLM 通过 **`runJs` + ES module** 完成：

| 场景 | 推荐模块 |
|---|---|
| Markdown → Word、从零写报告 | `docx.js` |
| 打开已有 docx → 观察 / grep / 改字 / 改样式 → 保存 | `docx.js` |
| 高级 API 不够（页眉、relationships、细粒度 OOXML） | `docx-raw.js` |

**不要**再依赖已删除的 Java `host.office.*`。行级 `read_file` / `grep` / `edit_file` 仍是 Agent1 `@Tool`；文档内容检索用 **`Document.grep`** / **`textView`**。

---

## 2. 宿主必做（Agent1）

1. **引擎**：`WeizhiEngine` + `setFsRoot(workspace)`（与 Caps 工作区同一目录）。
2. **Caps**：`AndroidCaps.install` / `DesktopCaps.install`，保证 `files.mkdir`、`files.zipExtract`、`files.zipCreate` 可用（或脚本内 `import zip from "zip"`）。
3. **脚本目录**：`engine.setScriptFolder(<含 docx 的目录>)`，并把 Weizhi 仓库资源拷入 catalog：

   | 文件 | 说明 |
   |---|---|
   | `assets/office/docx.js` | 文档模型 + 渲染 + grep/样式 |
   | `assets/office/docx-build.js` | 可选 **Builder / 块 DSL**（从零写文档） |
   | `assets/office/docx-raw.js` | 解压 / 打包 / `validateDocx` |

   Android：`android/app/src/main/assets/office/` 已与 `assets/office/` 同步；Agent1 可同样放进 `assets` 或下发到 `workspace/scripts/office/`。

4. **系统提示**：把 [office-js-api.md](office-js-api.md) 摘要或链接放进 Agent 工具说明；沙盒契约见 [AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md)（**只用 `import`，不用 `require`**，无 `loadScript`）。

5. **打开 docx 给用户看**：仍在 App 层 `FileProvider` + `ACTION_VIEW`（Weizhi 引擎不负责）。

---

## 3. 推荐工具环（Agent1 `@Tool` 薄封装）

可选注册 4 个工具（内部均 `runJs` 一段 `import` 脚本）：

| 工具名 | 脚本要点 |
|---|---|
| `docx_markdown_to_word` | `markdownToDocx({ inputPath, outputPath, title?, defaultStyle? })` |
| `docx_read_grep_edit` | `readDocx` → `grep` / `replaceAll` / `setBlockStyle` → `save` |
| `docx_inspect` | `textView({ includeStyle: true })` 或 `listBlocks` / `headings` |
| `docx_raw_edit` | `unpackDocx` → 改路径 → `validateDocx({ dir })` → `packDocx` → 再 `validateDocx({ path })` |

返回值：引擎 JSON；**校验失败**时把 `validateDocx` 的 `errors[]`（含 `file`、`line`、`column`）原样给 LLM。

---

## 4. 示例脚本（可复制进 runJs）

### 4.1 Markdown → docx

```javascript
import { markdownToDocx } from "./docx.js";
export default markdownToDocx({
  inputPath: "notes/brief.md",
  outputPath: "out/brief.docx",
  title: "简报",
  defaultStyle: { font: "宋体", sizePt: 12, lineSpacing: 1.5 },
});
```

### 4.2 read → grep → edit → save

```javascript
import { readDocx } from "./docx.js";
const doc = readDocx("in/contract.docx");
const { matches } = doc.grep("甲方");
if (matches.length === 0) export default { ok: false, reason: "not found" };
doc.replaceInBlock(matches[0].blockIndex, "甲方", "乙方");
export default doc.save("out/contract.docx");
```

### 4.3 原始 OOXML + 校验

```javascript
import { unpackDocx, packDocx, validateDocx } from "./docx-raw.js";
import fs from "fs";

const { dir } = unpackDocx("in/old.docx", { workDir: "tmp/ooxml" });
const p = dir + "/word/document.xml";
fs.writeFileSync(p, fs.readFileSync(p).toString().replace("OLD", "NEW"));
const v1 = validateDocx({ dir });
if (!v1.ok) export default v1;
const packed = packDocx(dir, "out/new.docx");
export default { packed, validate: validateDocx({ path: "out/new.docx" }) };
```

---

## 5. 校验预期（给 Agent 的说明）

`validateDocx` **能**发现：非 zip、缺 OPC 部件、XML 不闭合、缺 `w:body`。  
**不能**保证 Word 一定打开或版式正确（无 XSD / 无 Word 试开）。

桌面可选：`./scripts/test-office-strict.sh`（需先 `./scripts/test-jni.sh`，有 `xmllint` 时更严）。

---

## 6. 自动化测试（Weizhi 仓库）

| 命令 | 覆盖 |
|---|---|
| `./scripts/test-jni.sh` | `tests/java/OfficeTest`：MD→docx、模型渲染、read/save、grep、样式、raw+validate、路径逃逸 |
| `./scripts/test.sh android` | `CapsInstrumentedTest.docxJsMarkdownToDocx`、`docxJsGrepValidate` |
| `./scripts/test-office-strict.sh` | 可选 xmllint（桌面） |

Agent1 CI 建议在集成后增加：拷贝 `assets/office/*.js` → 跑一次 `markdownToDocx` smoke（与 CapsInstrumentedTest 同构）。

---

## 7. 验收清单（Agent1 Issue）

- [ ] `setScriptFolder` 含 `docx.js` + `docx-raw.js`
- [ ] workspace 与 `setFsRoot` 一致
- [ ] 至少 1 个 `@Tool` 或固定 `runJs` 模板能产出 `.docx` 并通过 `validateDocx`
- [ ] Agent 系统提示说明：优先 `docx.js`，失败再 `docx-raw.js` + validate
- [ ] 用户从 App 打开生成的 docx（FileProvider）

---

## 8. 相关文档

- [office-js-api.md](office-js-api.md) — API 表
- [office.md](office.md) — 总览
- [office-architecture.md](office-architecture.md) — 架构
- [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) — 引擎/Caps 接入
