# Office 文档（docx / pptx）归属

日期：2026-10-07。

Word / PPT 是宿主侧脚本资产，**不在 Weizhi 引擎仓库里**。真源在 [Agent1](https://github.com/fengshihao/agent1)：

| 内容 | Agent1 路径 |
|---|---|
| `docx.js` / `docx-build.js` / `docx-raw.js` / `pptx.js` / `pptx-build.js` | `agent_core/.../catalog/scripts/`，Android 同步副本 `android_agent/.../assets/office/` |
| 调用卡 `docx.*` / `pptx.*` | `agent-home/capabilities/office-api-cards.jsonl` |
| 给人读的 API | `doc/集成/OFFICE_DOCX_API.md`、`doc/集成/OFFICE_PPTX_API.md` |
| 幻灯片 Skill | `agent-home/skills/pptx/SKILL.md` |
| 回归 | `DocxOfficeIntegrationTest`、`PptxOfficeIntegrationTest` |

只用 `:weizhi` + `:caps`、不接 Agent1 的集成方，不会随 AAR 得到这些脚本。需要时从 Agent1 拷贝上述 catalog（或自行维护一份与引擎 `import` 约定对齐的脚本包），再用 `setScriptFolder` 指向该目录。

## Weizhi 继续保证的引擎能力

Office 脚本依赖这些，并应保持稳定：

- `import zip from "zip"`、`fs` / `fs.promises`、文本编解码
- `setFsRoot`（工作区）与 `setScriptFolder`（catalog 单层叶子 `import "leaf.js"`）
- caps 的 `files.zipExtract` / `files.zipCreate`（可选）

引擎门禁只做最小冒烟：空模块加载 + zip 往返（`CatalogSmokeTest`）。完整 OOXML 回归在 Agent1。

引擎侧没有 Java `host.office`。xlsx / PDF 未实现。
