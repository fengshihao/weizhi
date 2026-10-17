# Office 架构

日期：2026-10-07。

## 分层

| 留在 Weizhi | 在 Agent1 |
|---|---|
| QuickJS、`runJs`、限额、沙箱 | `docx.js` / `docx-build.js` / `docx-raw.js`、`pptx.js` / `pptx-build.js` |
| `fs`、`zip`、UTF-8、XML 解析等通用内置 | Office 相关 Skill、`@Tool` 薄封装 |
| `setPackageRoot` + `weizhi:` Package、workspace `import` | 系统提示里的 Office 摘要、`office-api-cards.jsonl` |
| Caps / agent-tools 的机制（不是 Word 业务） | 与 FileProvider / `intent.view` 的宿主接线、Office 回归 |

Office 是 ZIP + XML 的纯 JS。Java `host.office` 已从引擎移除。Agent1 把脚本打成 package 安装，`setPackageRoot` 指向 weizhi-home，`runJs` 里 `import … from "weizhi:docx"`。

## 集成方

1. 安装 Office package（Agent1 自带脚本包，不读本仓库），`setPackageRoot` 指向 weizhi-home。
2. `setFsRoot` 与 caps workspace 一致。
3. `runJs` 内 `import { markdownToDocx } from "weizhi:docx"`（包由宿主安装）。
4. 打开文件：`android.intent.start({ action: "view", path })`（宿主 FileProvider）。

xlsx / PDF：未实现。
