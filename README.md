# Weizhi

Bellard QuickJS 嵌入式引擎（C + JNI），面向 **Agent 跑 JS 脚本**。Android 以 AAR 交付；可选 caps 提供 `android` / `mac` / `linux` 生产力表面。

## 文档入口

| 文档 | 用途 |
|---|---|
| **[docs/INTEGRATION_FOR_AI.md](docs/INTEGRATION_FOR_AI.md)** | **集成方 / AI 接入教程（引擎 + caps，从这里开始）** |
| **[docs/AGENT_TOOLS_INTEGRATION.md](docs/AGENT_TOOLS_INTEGRATION.md)** | **Agent 工具环**（grep/bash/run_js/MCP/WebView/Skill） |
| [docs/AGENT_SANDBOX_PROMPT.md](docs/AGENT_SANDBOX_PROMPT.md) | 复制进 Agent 系统提示的脚本契约 |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 已锁定设计与限额 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 产品方向与优先级 |
| [docs/AGENT_TOOLS_PLAN.md](docs/AGENT_TOOLS_PLAN.md) | Agent 工具环（bash/skill/MCP/WebView）分阶段计划 |
| [docs/HOST_ABI.md](docs/HOST_ABI.md) | 宿主 / 原生插件 ABI |
| [docs/NATIVE_PLUGIN_IDL.md](docs/NATIVE_PLUGIN_IDL.md) | 签名 SO / IDL |
| [docs/QUICKJS_LIB_COMPAT.md](docs/QUICKJS_LIB_COMPAT.md) | 第三方 JS 库适配 |
| [docs/office.md](docs/office.md) | **host.office**（docx / xlsx / pptx MVP） |
| [docs/office-architecture.md](docs/office-architecture.md) | Office 分层：Java 内核 vs JS/catalog 编排 |

## 快速构建

```bash
./scripts/build-android.sh arm64-v8a
cd android && ./gradlew :weizhi:assembleRelease :caps:assembleRelease :agent-tools:assembleRelease
./scripts/publish-android-maven.sh arm64-v8a   # Agent1 → import-weizhi-prebuilt.sh
./scripts/test.sh          # 桌面
./scripts/test.sh android  # 真机 instrumented
```

License: Apache-2.0（QuickJS 保持上游 MIT，勿改 `third_party/quickjs`）。
