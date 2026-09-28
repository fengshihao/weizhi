# docx.js — 文档模型 + 渲染

路径：`assets/office/docx.js`。QuickJS **ES module**，无 npm。

## 加载

```javascript
import { Document, renderDocx, markdownToDocx, documentFromMarkdown } from "./docx.js";
```

需 `setScriptFolder` + caps（`files.mkdir` / `zipCreate`）与 `setFsRoot` 工作区对齐。

---

## 从零创建（推荐）

```javascript
import { Document, renderDocx } from "./docx.js";

const doc = Document.create({
  title: "报告",
  defaultStyle: { font: "宋体", sizePt: 12, lineSpacing: 1.5 },
});
doc.addHeading("摘要", 1);
doc.addParagraph("正文段落。");
doc.addBullet("要点一");

const result = renderDocx(doc, "out/report.docx");
// { ok: true, path: "out/report.docx", bytes: number }
```

### Document 方法

| 方法 | 说明 |
|---|---|
| `Document.create(options?)` | `title`、`defaultStyle` |
| `addHeading(text, level?)` | 1–9 |
| `addParagraph(text, style?)` | 段落；`style` 可覆盖默认字体/字号/行距等 |
| `addBullet(text, style?)` | 项目符号段 |
| `setDefaultStyle({ font, sizePt, bold, italic, lineSpacing })` | 后续块默认样式 |

---

## Markdown → Word

```javascript
import { markdownToDocx } from "./docx.js";

markdownToDocx({
  inputPath: "notes/report.md",   // 与 markdown 二选一
  outputPath: "out/report.docx",
  title: "可选封面标题",
  defaultStyle: { font: "宋体", sizePt: 12 },
});
```

或先建模型再渲染：

```javascript
import { documentFromMarkdown, renderDocx } from "./docx.js";

const doc = documentFromMarkdown("# Hi\n\n- a\n", { title: "Doc" });
renderDocx(doc, "out/hi.docx");
```

Markdown 子集：`# 标题`、`-` / `*` 列表、普通行 → 段落。

---

## 架构说明

```
Document（内存 blocks）
    → renderDocx → 写 OOXML 目录树 → zip → .docx
```

**没有** `open()` 改已有 docx 的补丁路径；**没有** PDF 渲染（以后由 Agent1 / LibreOffice 等负责）。

---

## 测试

`tests/java/OfficeTest`；Android `CapsInstrumentedTest.docxJsMarkdownToDocx`。
