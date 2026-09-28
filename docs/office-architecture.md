# Office 分层架构（Java 内核 + JS 编排）

日期：2026-09-28。与 [office.md](office.md)（当前 MVP API）配套。  
**现状**：专用一键 API（`fromMarkdown` / `fromRows`）已 ship，先给 Agent1 用。  
**方向**：Java 只沉淀**少变、ROM 小**的内核；**读 / 改 / 复杂生成**由 AI + catalog JS 在 workspace 里自由组合。

---

## 1. 两层职责

```
┌─────────────────────────────────────────────────────────┐
│  Agent1 catalog / skills（JS，可热更新、AI 可改）          │
│  md-to-doc.js、patch-docx.js、读正文、自定义版式…          │
└───────────────────────────┬─────────────────────────────┘
                            │ host.office.* / fs / zip / android.files.*
┌───────────────────────────▼─────────────────────────────┐
│  Weizhi 内核（Java + 引擎 fs/zip，AAR 发版频率低）         │
│  沙箱路径、OOXML 打包/解包、空包脚手架、可选结构化读        │
└─────────────────────────────────────────────────────────┘
```

| 层 | 变更频率 | 发版 | 典型内容 |
|---|---|---|---|
| **JS 编排** | 高 | Agent1 catalog / `qjs_module` | Markdown 规则、模板、merge 字段、业务样式 |
| **Java 内核** | 低 | Weizhi AAR | 路径校验、zip 安全、最小 OOXML 壳、稳定错误码 |
| **引擎 built-in** | 极低 | Weizhi `.so` | `fs`、`require("zip")`、`zlib`（已有 path escape） |

原则：**不要把「文档长什么样」写死在 Java**；Java 提供「合法 OOXML 容器 + 安全 IO」，AI 用 JS 决定内容。

---

## 2. 当前 MVP 在架构里的位置

| API | 角色 | 后续 |
|---|---|---|
| `host.office.docx.fromMarkdown` | **默认配方**（内置 MD 子集 + 拼 XML） | 保留为快捷方式；等价逻辑可逐步迁到 catalog JS |
| `host.office.xlsx.fromRows` | **默认配方**（小表） | 同上 |
| `host.office.pptx.fromMarkdown` | **默认配方**（`---` 分页） | 同上 |

MVP 不是最终边界，而是 **Agent1 零脚本即可联调** 的 happy path。与 issue #6 验收一致。

---

## 3. Java 内核（计划中的「基础基础能力」）

目标：**API 面小、行为稳定、单测覆盖、几乎不随业务改**。

### 3.1 通用约定

- 路径：一律 **workspace 相对**；与 `LocalWorkspace` / 引擎 `fs` 相同的 **`path escape`** 语义。
- 返回：与 MVP 相同 `{ ok, path?, bytes?, errorType?, message? }`。
- 临时目录：建议统一 `tmp/office/<sessionId>/…`，由内核创建；JS 只引用相对路径。

### 3.2 建议原语（Phase B，按优先级）

**打包 / 解包（OOXML = ZIP）**

| 原语 | 作用 |
|---|---|
| `host.office.unpack({ file, destDir? })` | 解包到 workspace（等价 caps `files.zipExtract`，Office 语义别名 + 审计 op 名） |
| `host.office.pack({ sourceDir, file, format? })` | 目录 → `.docx`/`.xlsx`/`.pptx`（等价 `files.zipCreate`，可选校验 `[Content_Types].xml` 存在） |

**空包脚手架（一次生成，长期不变）**

| 原语 | 作用 |
|---|---|
| `host.office.scaffold({ format, destDir })` | 写入最小合法空 docx/xlsx/pptx **目录树**（无业务内容），供 JS 改 XML |

**结构化读（可选，减轻 AI 直接扒 XML 的负担）**

| 原语 | 作用 |
|---|---|
| `host.office.docx.readText({ path })` | 从 `word/document.xml` 抽纯文本（段落级，无样式） |
| `host.office.xlsx.readSheet({ path, sheet? })` | 读首 sheet 为 `rows` 二维数组（与 `fromRows` 对称） |

**结构化写（小块补丁，非全文重写）**

| 原语 | 作用 |
|---|---|
| `host.office.docx.appendParagraph({ unpackDir \| path, text, style? })` | 在已有 unpack 树或临时解包后改 `document.xml` 再 pack |
| `host.office.docx.insertImage({ …, imagePath, width?, height? })` | 写 `word/media/*` + relationship（workspace 内 png/jpg） |

**会话式编辑（进阶）**

| 原语 | 作用 |
|---|---|
| `host.office.docx.open({ path })` → `{ sessionId, unpackDir }` | 解包并登记会话；`save({ sessionId, outputPath? })` 再 pack |
| 会话 TTL / 单引擎单 runJs | 与 `runJs` 生命周期一致即可 MVP；跨 run 会话以后由 Agent 用目录路径代替 |

实现上，**unpack/pack/scaffold** 可先 **薄封装现有 `ZipTools` + `OfficeService` 模板**；读文本/读 sheet 是少量 Java XML 解析，仍不必引入 POI。

---

## 4. JS 层（AI 自由发挥）

### 4.1 今天就能做（无需等新 API）

在 caps 已 install、或至少 `setFsRoot` 的前提下：

```javascript
// 读：解包 → 读 XML → 改 → 打回（引擎 zip 或 android.files.zip*）
const zip = require("zip");
zip.extractSync("in/report.docx", "tmp/doc");
const xml = fs.readFileSync("tmp/doc/word/document.xml", "utf8");
// … AI 或脚本改 xml …
fs.writeFileSync("tmp/doc/word/document.xml", xml);
zip.createSync("tmp/doc", "out/report-edited.docx");
```

路径均在 workspace 内；**`..` 会被 fs/zip 拒绝**。与 caps 并行时，推荐 **`android.files.zipExtract` / `zipCreate`**，便于审计与 Agent1 工具对齐。

### 4.2 将来推荐形态（catalog）

| 资源 | kind | 说明 |
|---|---|---|
| `catalog/scripts/office/ooxml-edit.js` | `script` | 薄入口：unpack → 回调 → pack |
| `catalog/modules/office-helpers.js` | `qjs_module` | 常用 XML 片段、关系 ID 递增、Markdown→段落（从 Java MVP 迁出） |
| `catalog/skills/…` | skill | 用户可沉淀的「发票 docx」「周报 pptx」流程 |

AI **改 skill / 改 JS** 即可扩展版式；**不必等 Weizhi 发版**。

### 4.3 与专用 API 的关系

```
快捷路径：host.office.docx.fromMarkdown(...)     ← 产品默认，稳定
灵活路径：import './office-helpers.js' + fs/zip  ← 定制、迭代快
内核路径：host.office.unpack / pack / scaffold   ← 少而稳，可选结构化 read/write
```

三者可混用：例如 `fromMarkdown` 出初稿，再 `unpack` + JS 打补丁 + `pack`。

---

## 5. 读 / 生成 / 编辑 — 推荐流程

| 场景 | 推荐 |
|---|---|
| 聊天里「把这份 md 变成 docx」 | `host.office.docx.fromMarkdown`（MVP） |
| 固定企业模板、复杂样式 | catalog JS + `scaffold` / 模板 docx unpack 改 XML |
| 读 docx 摘要给 LLM | `readText`（Java，Phase B）或 JS 解包读 `document.xml` |
| 改几段话 | JS 改 XML 或 `appendParagraph` |
| 插 workspace 图 | `insertImage`（Java 管 rels/media）或 JS 按 OOXML 规范写 part |
| 表格数据 | `fromRows` 或 CSV + JS；大表注意 QuickJS 堆 |

---

## 6. 沙箱：Java caps vs 引擎 fs（对齐说明）

- **引擎 `fs` / `require("zip")`**：锚在 `setFsRoot`，已有 **path escape**（与 caps 同思路）。
- **caps / `host.office` 走 `__caps`**：与 `android.files.*`、审计、undo 同一宿主面；路线图上的 **只读根** 等策略优先落在 caps。
- **设计选择**：Office **内核原语**走 `host.office` + Java，保证与 caps 策略一致；**纯 JS 编辑**仍可用 `fs`+`zip`，Agent 需保证工作区与 caps 根一致（[INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) 已要求对齐）。

不是「JS 没有权限」，而是 **生产力与策略在 caps 聚合**，Java 内核跟 caps 绑在一起发版更少、行为更统一。

---

## 7. ROM 与发版预期

| 组件 | ROM 影响 |
|---|---|
| Java MVP + 未来原语 | 纯源码 + `java.util.zip`，**无 POI/LibreOffice** |
| catalog JS | 在 Agent1 包或云端 catalog，**不占 Weizhi AAR** |
| 引擎 zip/fs | 已在 `libweizhi` |

**Weizhi 发版**：仅在内核原语、安全 bug、OOXML 壳兼容性变更时。  
**Agent1 发版 / catalog 同步**：业务模板、MD 规则、AI 流程。

---

## 8. 实施顺序（建议）

1. **现在**：沿用 MVP 专用 API（已实现）。
2. **Phase B1**：`unpack` / `pack` / `scaffold`（mostly 别名 + 空包模板），文档 + 测试。
3. **Phase B2**：`readText` / `readSheet`，与 Agent1「读 doc 进上下文」对接。
4. **Phase B3**：`insertImage`、`open`/`save` 会话；catalog 把 `fromMarkdown` 逻辑迁到 JS 示范。

Issue #6 验收完成后，新开 issue 跟踪 Phase B 即可。

---

## 9. Agent1 侧 checklist（集成时）

- [ ] workspace 与 `setFsRoot` / caps 同目录  
- [ ] 优先 `host.office.*` 一键产出；复杂用 catalog JS + fs/zip  
- [ ] 打开 docx：App 层 `FileProvider` + `ACTION_VIEW`（不在 Weizhi）  
- [ ] E2E 格式校验放 bridge（python-docx / ooxml-validator 可选），不在 AAR 内  
