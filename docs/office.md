# Office 文档（docx）

日期：2026-09-28。Issue #6 演进：**纯 JS 文档模型 + 渲染**，无 Java `host.office`。

## 脚本库

| 文件 | 作用 |
|---|---|
| `assets/office/docx.js` | 内存 `Document` + `renderDocx` + `markdownToDocx` + grep/样式 |
| `assets/office/docx-raw.js` | 解压/打包 `.docx` + `validateDocx`（原始 OOXML 逃生舱） |

宿主：`engine.setScriptFolder` 指向含 `docx.js` 的目录（或把该文件拷入 Agent1 catalog）。

## API 摘要

见 **[office-js-api.md](office-js-api.md)**。Agent1 接线见 **[AGENT1_DOCX_INTEGRATION.md](AGENT1_DOCX_INTEGRATION.md)**。

## 测试

```bash
./scripts/test-jni.sh       # OfficeTest（模型 + grep/样式 + raw/validate）
./scripts/test.sh android   # docxJsMarkdownToDocx + docxJsGrepValidate
./scripts/test-office-strict.sh   # 可选 xmllint
```

xlsx / pptx / PDF：未实现；后续可同级增加 `xlsx.js`、`pptx.js` 或宿主导出 PDF。
