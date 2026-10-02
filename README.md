<p align="center">
  <img src="assets/icon.png" alt="Weizhi" width="96" height="96" />
</p>

<h1 align="center">之谓 · Weizhi</h1>

<p align="center">
  <strong>在设备里，让 AI 安全地写脚本、用工具、沉淀技能。</strong><br />
  <em>On-device JS for agents — sandboxed scripts, caps, and a tool loop.</em>
</p>

<p align="center">
  Embedded <a href="https://bellard.org/quickjs/">QuickJS</a> (C + JNI) for <strong>AI coding agents</strong> on Android and desktop hosts.<br />
  Ship as AAR: engine, optional productivity <code>caps</code>, and <code>:agent-tools</code> (grep, bash, <code>run_js</code>, Skills, MCP, WebView).
</p>

<p align="center">
  <a href="docs/INTEGRATION_FOR_AI.md"><img alt="Integrate" src="https://img.shields.io/badge/Integrate-AI%20guide-2A4A6B?style=for-the-badge&labelColor=0F1C2E" /></a>
  <a href="docs/ai/START.md"><img alt="AI contribute" src="https://img.shields.io/badge/AI-One--line%20setup-E5A84B?style=for-the-badge&labelColor=0F1C2E" /></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/License-Apache--2.0-7EB89A?style=for-the-badge&labelColor=0F1C2E" /></a>
  <a href=".github/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/badge/CI-GitHub%20Actions-3D5A80?style=for-the-badge&labelColor=0F1C2E" /></a>
</p>

<p align="center">
  <a href="#why-weizhi">愿景</a> ·
  <a href="#quick-start">快速开始</a> ·
  <a href="#contribute-with-ai">AI 贡献</a> ·
  <a href="#documentation">文档</a> ·
  <a href="#repository-layout">仓库结构</a>
</p>

<p align="center">
  <a href="#vision-en">Vision (EN)</a> ·
  <a href="#quick-start-en">Quick start (EN)</a> ·
  <a href="#contribute-with-ai-en">Contribute with AI (EN)</a>
</p>

---

## Why Weizhi · 愿景

| | 中文 | English |
| --- | --- | --- |
| **单一脚本入口** | 宿主只调 `WeizhiEngine.runJs`；返回值是 JSON 文本，失败抛带关键词的英文错误，方便 LLM 自纠 | One programming entry: `runJs` → JSON text; errors use stable English tokens for agents |
| **沙箱与分层** | 引擎管 `fs` / 限额 / 内置模块；**grep、行号编辑**在 Java `:agent-tools`，不污染 C 核心 | Engine = sandbox + limits; agent ergonomics stay in `:agent-tools` |
| **端上生产力** | `caps` 暴露 `android.files.*`、zip、分享、提醒等；脚本与 Agent 工具可薄包同一工作区 | `caps` bridge OS surfaces; workspace paths must align |
| **可扩展** | IDL + 签名原生插件（`host.ensureNative`）；内置 docx / Markdown→Word 脚本库 | Native plugins via IDL; office helpers in `assets/office` |
| **面向 AI 集成** | 集成教程、沙盒系统提示、MCP 示例；README 可复制**一句话**让其他智能体自行克隆与跑门禁 | Copy-paste prompts in [`docs/ai/START.md`](docs/ai/START.md) for any coding agent |

与兄弟项目 [**Molan（墨览）**](https://github.com/fengshihao/molan) 互补：Molan 专注纸面 Markdown 阅读与编辑；Weizhi 专注 **Agent 在端上执行代码与工具环**。

### 典型场景

- Android **编程智能体**：工作区内改文件 → `run_js` 跑 QuickJS → `$tools.grep` / bash → 把流程写成 Skill 再 `import`
- **预编译 Maven**：CI 打出 `weizhi-android-maven`，宿主 App 无需本地编 NDK
- **桌面 / CI**：C API + JNI 冒烟，与 Android 语义一致

---

## Quick start · 快速开始

```bash
git clone https://github.com/fengshihao/weizhi.git
cd weizhi
./scripts/fetch-deps.sh
WEIZHI_SKIP_ASAN=1 ./scripts/test.sh    # 桌面门禁（与 CI 相同）
```

### Android AAR

```bash
./scripts/build-android.sh arm64-v8a
cd android && ./gradlew :weizhi:assembleRelease :caps:assembleRelease :agent-tools:assembleRelease
./scripts/publish-android-maven.sh arm64-v8a   # 可选：本地 Maven 给宿主 import
```

### 真机仪器化测试

```bash
./scripts/test.sh android
```

| 命令 | 用途 |
| --- | --- |
| `WEIZHI_SKIP_ASAN=1 ./scripts/test.sh` | **PR 必跑**（C 单测 + JNI；CI 同款） |
| `./scripts/test.sh` | 完整桌面门禁（含 ASan/UBSan） |
| `./scripts/test.sh android` | 设备上 JNI / caps / agent-tools 测试 |
| `./scripts/test-office-strict.sh` | docx / office 资产严格校验 |

---

## Contribute with AI · 用 AI 贡献本仓库

1. 打开 [`docs/ai/START.md`](docs/ai/START.md)，复制「准备环境」提示到 Cursor / Claude / 其他编码智能体。
2. 说明**一件事**要改什么；要求智能体按 [`AGENTS.md`](AGENTS.md) 与 [`docs/ai/CHECKLIST.md`](docs/ai/CHECKLIST.md) 执行。
3. **合并前必须** `WEIZHI_SKIP_ASAN=1 ./scripts/test.sh` 通过；新行为**必须有自动化测试**。

**一句话（中文）**

```text
帮我准备开源项目 Weizhi（之谓，https://github.com/fengshihao/weizhi）的贡献环境：请你自己克隆仓库、读 AGENTS.md 和 docs/ai/START.md，缺依赖时运行 ./scripts/fetch-deps.sh，需要时用 WEIZHI_SKIP_ASAN=1 ./scripts/test.sh 验证。准备好后告诉我，我再说想贡献什么。
```

机器契约：[`AGENTS.md`](AGENTS.md) · [`.cursor/rules/weizhi-repo.mdc`](.cursor/rules/weizhi-repo.mdc)

---

## Integrate Weizhi in your agent · 在自有 Agent 里集成

给**集成方**智能体的一句话（不改本仓库）：

```text
在我的 Android Agent 里集成 Weizhi：读 https://github.com/fengshihao/weizhi/blob/master/docs/INTEGRATION_FOR_AI.md，依赖 :weizhi（+ 可选 :caps、:agent-tools），workspace 与 setFsRoot 对齐，用 run_js 调 WeizhiEngine.runJs，系统提示粘贴 docs/AGENT_SANDBOX_PROMPT.md。
```

最小 Java 片段见 [`docs/INTEGRATION_FOR_AI.md`](docs/INTEGRATION_FOR_AI.md) §1。

---

## Documentation · 文档

| 文档 | 用途 |
| --- | --- |
| **[docs/INTEGRATION_FOR_AI.md](docs/INTEGRATION_FOR_AI.md)** | **集成方 / AI 接入（引擎 + caps，从这里开始）** |
| **[docs/AGENT_TOOLS_INTEGRATION.md](docs/AGENT_TOOLS_INTEGRATION.md)** | Agent 工具环（grep / bash / run_js / MCP / WebView / Skill） |
| [docs/AGENT_SANDBOX_PROMPT.md](docs/AGENT_SANDBOX_PROMPT.md) | 复制进 Agent 系统提示的脚本契约 |
| [docs/ai/START.md](docs/ai/START.md) | **一句话让 AI 准备贡献环境** |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 已锁定设计与限额 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 产品方向与优先级 |
| [docs/HOST_ABI.md](docs/HOST_ABI.md) / [docs/NATIVE_PLUGIN_IDL.md](docs/NATIVE_PLUGIN_IDL.md) | 宿主 ABI / 原生插件 |
| [docs/office.md](docs/office.md) | docx.js（Markdown→Word） |

---

## Repository layout · 仓库结构

```text
src/ include/ jni/ java/     # QuickJS 引擎 + JNI + WeizhiEngine
android/weizhi|caps|agent-tools*   # AAR 模块
plugins/                   # 示例 IDL 原生插件
tests/                     # C + 桌面 Java 测试
scripts/                   # build.sh, test.sh, bindgen
docs/                      # 集成与 AI 契约
assets/                    # 品牌图标、office 脚本
```

---

## Vision (EN)

Weizhi is a **small, strict JS runtime** for on-device AI agents:

- **Run untrusted logic** in a bounded sandbox (`fs`, memory, stack, I/O caps).
- **Compose with the host**: Android caps, optional tool loop, MCP, WebView automation.
- **Stay integrator-friendly**: one `runJs` contract, documented error tokens, Maven/AAR delivery.

Roadmap themes (skills on disk, read-only roots, snapshot history) live in [docs/ROADMAP.md](docs/ROADMAP.md); unlisted items are not promises.

---

## Quick start (EN)

```bash
git clone https://github.com/fengshihao/weizhi.git
cd weizhi && ./scripts/fetch-deps.sh
WEIZHI_SKIP_ASAN=1 ./scripts/test.sh
```

Android: `./scripts/build-android.sh arm64-v8a` then Gradle `assembleRelease` on `:weizhi`, `:caps`, `:agent-tools` as needed.

**Before every PR:** `WEIZHI_SKIP_ASAN=1 ./scripts/test.sh` plus tests for new behavior. See [AGENTS.md](AGENTS.md).

---

## Contribute with AI (EN)

Copy from [docs/ai/START.md](docs/ai/START.md):

```text
Set up a contribution environment for Weizhi (https://github.com/fengshihao/weizhi): clone the repo, read AGENTS.md and docs/ai/START.md, run ./scripts/fetch-deps.sh if needed, and WEIZHI_SKIP_ASAN=1 ./scripts/test.sh when validating. Tell me when ready; I will describe the change next.
```

---

## License

Apache-2.0 — see [LICENSE](LICENSE). QuickJS remains upstream MIT; do not fork or patch `third_party/quickjs` in this tree.
