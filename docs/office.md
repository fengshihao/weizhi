# Office 文档（docx / pptx）

日期：2026-10-07。Issue #6 演进：**纯 JS 文档模型 + 渲染**，无 Java `host.office`。

## 脚本库

| 文件 | 作用 |
|---|---|
| `assets/office/docx.js` | 内存 `Document` + `renderDocx` + `markdownToDocx` + grep/样式 |
| `assets/office/docx-build.js` | 链式 Builder / JSON 块 DSL（写新文档） |
| `assets/office/docx-raw.js` | 解压/打包 `.docx` + `validateDocx`（原始 OOXML 逃生舱） |
| `assets/office/pptx.js` | 内存幻灯片 + `renderPptx`（版式、主题、形状与背景） |
| `assets/office/pptx-build.js` | 链式 Builder（按页追加版式） |

宿主：`engine.setScriptFolder` 指向含这些脚本的目录（或把文件拷入 Agent1 catalog）。内置 Skill：`android/app/src/main/assets/agent_skills/pptx/SKILL.md`。

## API 摘要

Word 给人读：**[office-js-api.md](office-js-api.md)**。幻灯片：**[pptx-js-api.md](pptx-js-api.md)**。Agent 检索：**[api-cards.jsonl](api-cards.jsonl)**。Agent1 接 Word 见 **[AGENT1_DOCX_INTEGRATION.md](AGENT1_DOCX_INTEGRATION.md)**；接 PPT 同样把 `pptx.js` 放进 catalog，用 `runJs` 调 `renderPptx`。

## 测试

```bash
./scripts/test-jni.sh       # OfficeTest（docx + pptx 版式/形状）
./scripts/test.sh android   # docxJsMarkdownToDocx + docxJsGrepValidate
./scripts/test-office-strict.sh   # 可选 xmllint
```

xlsx / PDF：未实现。pptx 见 `pptx.js`。
