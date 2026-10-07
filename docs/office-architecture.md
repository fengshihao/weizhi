# Office 架构（当前）

日期：2026-10-07。

## 现状

- **docx（高级）**：`assets/office/docx.js` — 内存 **Document** → **renderDocx** → OOXML zip；grep/样式。  
- **docx（原始）**：`assets/office/docx-raw.js` — **unpackDocx** / **packDocx** + **validateDocx**（良构 XML + 包结构；非 XSD）。  
- **Markdown→Word**：`markdownToDocx` / `documentFromMarkdown` + `renderDocx`。  
- **Java `host.office`**：已移除（无 AAR 内 OOXML 生成）。  
- **pptx**：`assets/office/pptx.js` — **Deck** → **renderPptx** → PresentationML zip。版式自带背景与装饰形状；`shapes` 用 12×6 网格。Skill：`agent_skills/pptx`。
- **xlsx / PDF**：未实现。

## Agent1 集成

1. `setScriptFolder` 提供 `docx.js` / `pptx.js`（catalog 同步）。  
2. `setFsRoot` 与 caps workspace 一致。  
3. `execute_script` 内 `import { markdownToDocx } from "./docx.js"`。  
4. 打开 docx：`android.intent.start({ action: "view", path })`（宿主 FileProvider + `launchIntent`）。

## 演进

- 表格、图片、页眉页脚：扩展 **Document** 块类型 + renderer。
- **pptx** 已与 docx 平行，仍走 JS catalog。不读已有 pptx，不导 PDF。
