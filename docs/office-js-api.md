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

Markdown（常用子集）：

| 元素 | 语法 |
|---|---|
| 标题 | `#` … `######` |
| 列表 | `-` / `*` |
| Task | `- [ ]` / `- [x]` |
| 链接 / 强调 | `[text](url)`、`**bold**`、`*italic*`、`` `code` `` |
| 表格 | GFM `\| col \|` + `\| --- \|` |
| 引用 | `> line` |
| 代码块 | ` ``` ` 围栏 |
| 图片 | `![](workspace/相对路径.png)`（读 workspace 文件写入 docx） |

### 读取 → 观察（grep）→ 修改 → 保存

与代码库 **read / grep / edit / save** 类似：先把 docx 当成「带块序号的纯文本视图」观察，再按 `blockIndex` 或全文替换修改。

```javascript
import { readDocx } from "./docx.js";

const doc = readDocx("out/rich.docx");   // 或 Document.load(...)

// 观察：整篇纯文本、逐块索引视图、只筛标题/正文
doc.plainText();
doc.textView();                          // [{ blockIndex, type, line, text, preview }, ...]
doc.headings();                          // 等价 filter({ type: "heading" })
doc.filter({ type: "paragraph" });

// grep：在块正文中搜索（默认不区分大小写）
const { matches } = doc.grep("ABCD");
// matches[i]: { blockIndex, line, match, snippet, start, end, text }

// 编辑：改单块、或全文替换
doc.replaceInBlock(matches[0].blockIndex, "ABCD", "EFGH");
doc.replaceAll("old phrase", "new phrase", { type: "paragraph" });
doc.setBlockText(3, "整段重写为这一句。");  // 会重新解析 ** / 链接 等 inline

doc.save("out/updated.docx");
const md = doc.toMarkdown();             // 有损往返，复杂版式会简化
```

| 方法 | 说明 |
|---|---|
| `plainText()` | 所有块正文拼接（块之间 `\n`） |
| `textView({ type? })` | Agent 友好行列表，`[index] type: 正文` |
| `listBlocks` / `filter` / `headings` | 按 `type` 过滤块（`heading` / `paragraph` / `bullet` / `task` / …） |
| `getBlock(index)` | 单块摘要 |
| `grep(pattern, { type?, regex?, caseInsensitive?, maxResults? })` | 返回 `matches` + `truncated` |
| `replaceInBlock(index, search, replace, { replaceFirst?, regex? })` | 块内替换（默认全部；复杂 inline 会被收成新 plain） |
| `replaceAll(search, replace, { type?, … })` | 跨块替换 |
| `setBlockText(index, text)` | 整段设为 Markdown 风格 plain |

**读取限制**：以 `word/document.xml` 为主；嵌入图片读回为占位；复杂 Run/样式/页眉页脚可能丢失。表格 grep 命中的是「行内 tab 分隔」的 plain；`setBlockText` 不支持 table/image 块。

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
