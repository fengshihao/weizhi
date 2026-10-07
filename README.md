<p align="center">
  <img src="assets/icon.png" alt="Weizhi" width="96" height="96" />
</p>

<h1 align="center">微智 · Weizhi</h1>

<p align="center">
  <strong>小巧的端上 JS 引擎，给 Agent 一颗可扩展的「脚本心脏」。</strong><br />
  <em>A ~1.1&nbsp;MB core you can extend — on-device JS for agents.</em>
</p>

<p align="center">
  Embedded <a href="https://bellard.org/quickjs/">QuickJS</a> (C + JNI): Release <code>libweizhijni.so</code> 经 strip 后约 <strong>1.1&nbsp;MB</strong>，只保留沙箱与 <code>runJs</code> 核心；<br />
  需要时再叠 <code>:caps</code>、IDL 原生插件或脚本库。模型可见的工具由宿主实现，本仓库不提供。
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
| **沙箱与分层** | 引擎管 `fs` / 限额 / 内置模块。给模型的 grep、bash、行号编辑属于**宿主**（Agent1 在 `java-agent-core`），不进 C 核心 | Engine = sandbox + limits. Model-facing grep/bash/edit stay in the host, not in C |
| **端上生产力** | `caps` 暴露 `android.files.*`、zip、分享、提醒等；脚本与 Agent 工具可薄包同一工作区 | `caps` bridge OS surfaces; workspace paths must align |
| **小巧核心** | Release 引擎 SO 约 **1.1&nbsp;MB**（strip 后）；限额内跑沙箱脚本，不把 Node 级运行时塞进 APK | Stripped core ~1.1&nbsp;MB; bounded sandbox, not a full Node runtime |
| **随心扩展** | 可选 AAR：<code>caps</code>；脚本内 <code>mcp</code>；IDL 签名 SO；<code>setScriptFolder</code> 脚本库。模型工具不在本仓库 | Optional caps, in-script mcp, IDL plugins. Model tools stay in the host |
| **面向 AI 集成** | 集成教程、沙盒系统提示；README 可复制**一句话**让其他智能体自行克隆与跑门禁 | Copy-paste prompts in [`docs/ai/START.md`](docs/ai/START.md) for any coding agent |

### 与 [Agent 1](https://github.com/fengshihao/agent1) 的关系

**微智是 Agent 1 的脚本与沙箱引擎层，Agent 1 是面向用户的安卓编程智能体应用**——二者分仓维护，职责清晰：

| | **微智（本仓库）** | **Agent 1** |
| --- | --- | --- |
| 定位 | 可复用的 QuickJS 引擎 + 可选 caps / 工具环 AAR | 产品级 Agent App：对话、授权、UI、编排 LLM |
| 编程入口 | `WeizhiEngine.runJs`（唯一脚本 API） | `execute_script` 把脚本交给微智执行 |
| 文件 / grep / bash | 引擎只提供沙箱 `fs` 和脚本内 `zip` | 模型工具全部在 Agent1，不从本仓库引入 |
| 交付 | Maven / AAR（CI 产出 `weizhi-android-maven`） | `import-weizhi-prebuilt.sh` 拉预编译包联编，见 [INTEGRATION_FOR_AI.md](docs/INTEGRATION_FOR_AI.md) |
| Word / 幻灯片 | 提供 `zip`、`fs`、`setScriptFolder`，让宿主脚本能打 OOXML | 自带 `docx.js` / `pptx.js`、调用卡、Skill 与回归。见 [office.md](docs/office.md) |

集成 Agent 1 时：**workspace 路径**必须与 `setFsRoot` / `AndroidCaps` 对齐；Java 与 `libweizhijni.so` **必须同一次发布**，勿只替换 SO。其他 Agent 产品也可只依赖微智引擎，不必 fork Agent 1。

### 典型场景

- **[Agent 1](https://github.com/fengshihao/agent1)**：对话编排和模型工具在 Agent1 → `execute_script` → 微智沙箱跑脚本
- **仅要引擎**：只打 `:weizhi` AAR，工具环自己写
- **预编译 Maven**：CI 打出 `weizhi-android-maven`，宿主无需本地编 NDK
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
cd android && ./gradlew :weizhi:assembleRelease :caps:assembleRelease
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
| `./scripts/test.sh android` | 设备上 JNI / caps 测试 |
| `./scripts/test-office-strict.sh` | 已废弃：Office 回归在 Agent1 |

---

## Contribute with AI · 用 AI 贡献本仓库

1. 打开 [`docs/ai/START.md`](docs/ai/START.md)，复制「准备环境」提示到 Cursor / Claude / 其他编码智能体。
2. 说明**一件事**要改什么；要求智能体按 [`AGENTS.md`](AGENTS.md) 与 [`docs/ai/CHECKLIST.md`](docs/ai/CHECKLIST.md) 执行。
3. **合并前必须** `WEIZHI_SKIP_ASAN=1 ./scripts/test.sh` 通过；新行为**必须有自动化测试**。

**一句话（中文）**

```text
帮我准备开源项目微智 Weizhi（https://github.com/fengshihao/weizhi）的贡献环境：请你自己克隆仓库、读 AGENTS.md 和 docs/ai/START.md，缺依赖时运行 ./scripts/fetch-deps.sh，需要时用 WEIZHI_SKIP_ASAN=1 ./scripts/test.sh 验证。准备好后告诉我，我再说想贡献什么。
```

机器契约：[`AGENTS.md`](AGENTS.md) · [`.cursor/rules/weizhi-repo.mdc`](.cursor/rules/weizhi-repo.mdc)

---

## Integrate Weizhi in your agent · 在自有 Agent 里集成

给**集成方**智能体的一句话（不改本仓库）：

```text
在我的 Android Agent 里集成 Weizhi：读 https://github.com/fengshihao/weizhi/blob/master/docs/INTEGRATION_FOR_AI.md，依赖 :weizhi（+ 可选 :caps）。模型工具由宿主自己提供。workspace 与 setFsRoot 对齐，用 WeizhiEngine.runJs 跑脚本，系统提示粘贴 docs/AGENT_SANDBOX_PROMPT.md。
```

最小 Java 片段见 [`docs/INTEGRATION_FOR_AI.md`](docs/INTEGRATION_FOR_AI.md) §1。

---

## Documentation · 文档

| 文档 | 用途 |
| --- | --- |
| **[docs/INTEGRATION_FOR_AI.md](docs/INTEGRATION_FOR_AI.md)** | **集成方 / AI 接入（引擎 + caps，从这里开始）** |
| **[docs/AGENT_TOOLS_INTEGRATION.md](docs/AGENT_TOOLS_INTEGRATION.md)** | 说明模型工具不在本仓库 |
| [docs/AGENT_SANDBOX_PROMPT.md](docs/AGENT_SANDBOX_PROMPT.md) | 复制进 Agent 系统提示的脚本契约 |
| [docs/ai/START.md](docs/ai/START.md) | **一句话让 AI 准备贡献环境** |
| [docs/DECISIONS.md](docs/DECISIONS.md) | 已锁定设计与限额 |
| [docs/ROADMAP.md](docs/ROADMAP.md) | 产品方向与优先级 |
| [docs/HOST_ABI.md](docs/HOST_ABI.md) / [docs/NATIVE_PLUGIN_IDL.md](docs/NATIVE_PLUGIN_IDL.md) | 宿主 ABI / 原生插件 |
| [docs/office.md](docs/office.md) | docx/pptx 归 Agent1；本仓库只保证引擎契约 |

---

## Repository layout · 仓库结构

```text
src/ include/ jni/ java/     # QuickJS 引擎 + JNI + WeizhiEngine
android/weizhi|caps             # AAR 模块
plugins/                   # 示例 IDL 原生插件
tests/                     # C + 桌面 Java 测试
scripts/                   # build.sh, test.sh, bindgen
docs/                      # 集成与 AI 契约
assets/                    # 品牌图标。Office 脚本在 Agent1
```

---

## Vision (EN)

**Weizhi (微智)** is a **compact, strict JS runtime** for on-device AI agents:

- **~1.1&nbsp;MB stripped core** (`libweizhijni.so`) — sandbox, limits, single `runJs` entry.
- **Extend on demand**: optional `caps`, in-script `mcp`, IDL native plugins, script folders. Model tools are not in this repo.
- **[Agent 1](https://github.com/fengshihao/agent1)** is the reference host app; this repo is the engine layer it depends on (separate repos, Maven prebuilts).
- **Integrator-friendly**: documented error tokens, AAR/Maven delivery — see [INTEGRATION_FOR_AI.md](docs/INTEGRATION_FOR_AI.md).

Roadmap themes live in [docs/ROADMAP.md](docs/ROADMAP.md); unlisted items are not promises.

---

## Quick start (EN)

```bash
git clone https://github.com/fengshihao/weizhi.git
cd weizhi && ./scripts/fetch-deps.sh
WEIZHI_SKIP_ASAN=1 ./scripts/test.sh
```

Android: `./scripts/build-android.sh arm64-v8a` then Gradle `assembleRelease` on `:weizhi` and `:caps`.

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
