# WeizhiDocx — JS 语义化 Office 库（给 AI 用）

日期：2026-09-28。单文件、无 npm：`assets/office/weizhi-docx.js`。

**目的**：不让 AI 直接改 `word/document.xml` 字符串；提供接近「文档 API」的用法（改标题、正文字体、行距、段前后距等），内部生成合法 OOXML。

与 [office-architecture.md](office-architecture.md) 的关系：

- **Java `host.office.*`**：一键生成、将来 unpack/pack 内核。
- **`WeizhiDocx`（JS）**：打开已有 docx → 语义编辑 → 再打包；可随 Agent1 catalog 升级，不必动 AAR。

---

## 加载

宿主设置脚本目录（叶子名加载）：

```java
engine.setScriptFolder(new File(appFiles, "weizhi-lib").getAbsolutePath());
// 将 assets/office/weizhi-docx.js 复制到 weizhi-lib/
```

脚本内：

```javascript
loadScript("weizhi-docx.js");
const doc = WeizhiDocx.open("out/report.docx");
```

依赖：`fs`、`require("zip")`；生成初稿仍可用 `host.office.docx.fromMarkdown`。

---

## API 参考

### `WeizhiDocx.open(docxPath, options?)`

| 参数 | 说明 |
|---|---|
| `docxPath` | workspace 相对路径 |
| `options.workDir` | 解包目录（默认 `tmp/weizhi-docx-…`） |

解包 → 解析 `word/document.xml` → 返回 **`DocxDocument`** 实例。

### 读取

| 方法 | 说明 |
|---|---|
| `getTitle()` | 首段 Title/Heading1 样式，或第一段文字 |
| `getPlainText()` | 全文段落用换行拼接（无损样式信息） |

### 写入 / 样式

| 方法 | 说明 |
|---|---|
| `setTitle(text)` | 改标题段（无则插入段首）；使用 `Title` 样式名 |
| `setBodyStyle(opts)` | 正文段落统一字体/字号/行距/段前后距（不动 Title 段） |
| `addParagraph(text, opts?)` | 追加一段 |
| `replaceParagraph(index, text, opts?)` | 按索引改段 |

**`setBodyStyle(opts)` 字段**（均可选）：

| 字段 | 类型 | 含义 |
|---|---|---|
| `font` | string | 字体名，如 `"宋体"`、`"Calibri"` |
| `sizePt` | number | 字号（磅） |
| `bold` / `italic` | boolean | 粗体 / 斜体 |
| `lineSpacing` | number | 行距倍数，如 `1.5` |
| `paragraphSpacingBefore` / `paragraphSpacingAfter` | number | 段前/段后（twips，1/20 磅） |
| `characterSpacingPt` | number | 字符间距（磅） |

**`addParagraph` / `replaceParagraph` 的 opts**：

| 字段 | 说明 |
|---|---|
| `bullet` | 是否加 `•` 前缀 |
| `style` | 段落样式名 |
| `align` | `left` / `center` / `right` / `both` → OOXML `w:jc` |

### `save(outputDocxPath)`

写回 `document.xml` 并 `zip.createSync(workDir, outputDocxPath)` → `{ ok, path, message }`。

---

## 示例（AI 典型流程）

```javascript
loadScript("weizhi-docx.js");

// 1. 引擎一键出稿
host.office.docx.fromMarkdown({
  inputPath: "brief.md",
  outputPath: "out/report.docx",
});

// 2. 语义微调（不碰 XML）
const doc = WeizhiDocx.open("out/report.docx");
doc.setTitle("2026 Q3 总结");
doc.setBodyStyle({
  font: "宋体",
  sizePt: 12,
  lineSpacing: 1.5,
  paragraphSpacingAfter: 120,
});
doc.addParagraph("附录：数据来源见 workspace/data.csv");
doc.save("out/report-final.docx");
```

---

## 与常见 Office JS API 的对照

| 常见能力（如 docx.js） | WeizhiDocx MVP |
|---|---|
| `Document` / `Packer` | `open` / `save` |
| `Paragraph` + `TextRun` + `HeadingLevel` | `addParagraph` / `setTitle` |
| `CharacterSet` / 字体 / 字号 | `setBodyStyle({ font, sizePt })` |
| 行距 / 段间距 | `lineSpacing`, `paragraphSpacingBefore/After` |
| 表格 / 页眉 / 主题 | **未实现** → 用 Java 原语或下版库 |

后续可在同文件或 `weizhi-xlsx.js` 扩展表格、图片（需 `word/media` + rels，建议配合 Java `insertImage` 原语）。

---

## 限制（写进 Agent 提示）

- 解析器针对 **Weizhi 生成或常规单 run 段落**；复杂 docx（多 run、域、修订）可能只保留合并后的文本。
- 样式依赖 `word/document.xml` 内联 `w:rPr`/`w:pPr`，不读写 `styles.xml`（MVP）。
- 大文件注意 QuickJS 堆；超大 docx 宜用 Java `readText`（计划中）。

---

## 测试

桌面 `./scripts/test-jni.sh` 内 `OfficeTest` 含 `WeizhiDocx` 路径：`fromMarkdown` → `setTitle` / `setBodyStyle` → `save` → zip 内 XML 断言。
