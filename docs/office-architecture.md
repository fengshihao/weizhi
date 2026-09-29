# Office 架构（当前）

日期：2026-09-28。

## 现状

- **docx（高级）**：`assets/office/docx.js` — 内存 **Document** → **renderDocx** → OOXML zip；grep/样式。  
- **docx（原始）**：`assets/office/docx-raw.js` — **unpackDocx** / **packDocx** + **validateDocx**（良构 XML + 包结构；非 XSD）。  
- **Markdown→Word**：`markdownToDocx` / `documentFromMarkdown` + `renderDocx`。  
- **Java `host.office`**：已移除（无 AAR 内 OOXML 生成）。  
- **xlsx / pptx / PDF**：未实现；可后续增加 `xlsx.js`、`pptx.js` 或宿主导出 PDF。

## Agent1 集成

1. `setScriptFolder` 提供 `docx.js`（catalog 同步）。  
2. `setFsRoot` 与 caps workspace 一致。  
3. `execute_script` 内 `import { markdownToDocx } from "./docx.js"`。  
4. 打开 docx：App 层 `FileProvider` + `ACTION_VIEW`（不在 Weizhi）。

## 演进

- 表格、图片、页眉页脚：扩展 **Document** 块类型 + renderer。  
- **PptxDeck** 模型 + `renderPptx`：与 docx 平行，仍走 JS catalog。
