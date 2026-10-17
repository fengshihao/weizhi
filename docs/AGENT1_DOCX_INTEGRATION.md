# Agent1 与 Office 脚本

日期：2026-10-07。

docx / pptx **脚本、调用卡、Skill 和回归都在 Agent1**，不在本仓库。本页只保留引擎契约，避免两处各写一份 API。

给人读的 API：Agent1 `doc/集成/OFFICE_DOCX_API.md`、`doc/集成/OFFICE_PPTX_API.md`。  
调用卡：Agent1 `office-api-cards.jsonl`。  
脚本：Agent1 `catalog/scripts/` 与 `android_agent/.../assets/office/`。

## 引擎契约（Weizhi 保证）

- [ ] `setFsRoot` 与宿主 workspace 一致
- [ ] `setPackageRoot` 指向宿主 weizhi-home（官方 Package，`import … from "weizhi:docx"`）
- [ ] `import zip from "zip"` 与 `fs` 可用（Office 脚本打 zip / 改 XML 靠这些）
- [ ] 不注册 Java `host.office`

未安装 `weizhi:docx`（`packages-index.json` 无该 name）时，`import` 会失败。这是宿主漏装包，不是引擎缺模块。

## 只依赖 AAR、不接 Agent1

`:weizhi` 与 `:caps` **不附带** docx/pptx。需要 Office 时自行安装 Agent1 打包的 package（或等价脚本包，经 `PackageInstaller` 安装、`setPackageRoot` 指向 weizhi-home），版本跟你依赖的引擎 API 对齐，不要打进核心 AAR。
