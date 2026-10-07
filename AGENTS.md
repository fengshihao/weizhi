# AGENTS.md — Weizhi 机器契约

> 本文件优先于聊天里的口头约定。人类一句话开工入口：[`docs/ai/START.md`](docs/ai/START.md)。

## 项目是什么

**微智 Weizhi** 是基于 Bellard QuickJS 的**嵌入式 JavaScript 引擎**（C + JNI）：Release 核心 SO strip 后约 **1.1&nbsp;MB**，沙箱 + `runJs` 单一入口；可选 **caps**、**`:agent-tools`**、IDL 原生插件与脚本库，按需扩展。

与 **[Agent 1](https://github.com/fengshihao/agent1)** 分仓：本仓库是引擎与 AAR；Agent 1 是参考宿主 App（工具环、UI、LLM 编排）。集成约定见 [docs/INTEGRATION_FOR_AI.md](docs/INTEGRATION_FOR_AI.md)。

## 分层（改代码前对齐）

| 层 | 路径 / 模块 | 职责 |
|----|-------------|------|
| 引擎 | `src/`、`include/`、`jni/`、`java/com/weizhi/` | `runJs`、限额、`fs`、内置模块、插件加载 |
| Caps | `android/caps/` | `globalThis.android` 等端能力 |
| Agent 工具环 | `android/agent-tools*` | LLM `@Tool`，**不要**塞进 C 引擎 |
| 原生插件 | `plugins/*` + IDL | 签名 SO；用 `scripts/weizhi-bindgen.py` |
| 文档 | `docs/**` | 集成真源见 `INTEGRATION_FOR_AI.md` |

## 默认允许改的路径

| 路径 | 用途 |
|------|------|
| `src/`、`include/`、`jni/`、`java/` | 引擎与 JNI |
| `android/**` | AAR 模块与仪器化测试 |
| `plugins/**` | 示例原生插件 |
| `tests/**` | C / Java 桌面测试 |
| `scripts/**`、`.github/**`、`docs/**`、根文档 | 构建、CI、说明 |
| `assets/**` | 品牌资源（如 `icon.svg`） |

**禁止**（除非维护者明确要求）：修改 `third_party/quickjs` 上游树。

## 开工步骤（强制）

1. 读本文件 + [`docs/ai/START.md`](docs/ai/START.md) + [`docs/ai/CHECKLIST.md`](docs/ai/CHECKLIST.md)
2. **一个 PR 一件事**
3. 改完必须跑门禁（见下）；失败不得 push
4. 按 [`docs/ai/PR_PLAYBOOK.md`](docs/ai/PR_PLAYBOOK.md) 开 PR

## 检查命令（与 CI 对齐）

```bash
./scripts/fetch-deps.sh          # 首次或缺 third_party 时
./scripts/test.sh                # 桌面：C 单测 + JNI 冒烟 + ASan（本地可 WEIZHI_SKIP_ASAN=1）
WEIZHI_SKIP_ASAN=1 ./scripts/test.sh   # 与 GitHub Actions desktop job 相同
./scripts/test.sh android        # 真机 / 模拟器 instrumented（改 JNI/Android 时）
# docx/pptx 脚本不在本仓库；Office 回归在 Agent1
```

| 改动范围 | 最低要求 |
|----------|----------|
| 任意 C/引擎逻辑 | `./scripts/test.sh`（或 `WEIZHI_SKIP_ASAN=1` 至少与 CI 一致） |
| Java / JNI / Android 模块 | 上式 + `./scripts/test.sh android`（有设备时） |
| 仅文档 / 图标 / README | 无强制编译；勿破坏现有链接 |
| 新功能 / 修 bug | **必须**附带或更新自动化测试（`tests/` 或 `android/.../androidTest/`） |

CI 定义： [`.github/workflows/ci.yml`](.github/workflows/ci.yml)。

## 测试用例规则

- **新行为必须有测试**：引擎行为 → `tests/test_engine.c` 或 JNI；Android 工具 → `*InstrumentedTest.java`；Java 纯逻辑 → `tests/java/`。
- **不要**删除或弱化现有门禁来「让 PR 变绿」；应修根因或更新断言并说明语义变化。
- 失败时错误信息应含项目约定英文关键词（见 [`docs/DECISIONS.md`](docs/DECISIONS.md)），便于 Agent 自纠。

## Commit / PR 约定

- Commit：中文或英文均可，说明**为什么**；一行主题，必要时正文补充
- PR 标题：简短祈使句或 `fix/docs/android: …`
- PR 正文必须含：**摘要**、**对用户/集成方可见变化**、**Test plan**（贴实际跑过的命令）
- 若由 AI 辅助，勾选并写明读过 `AGENTS.md` / `CHECKLIST.md`

## 集成方与引擎边界

- Agent **只有一个编程入口**：`WeizhiEngine.runJs`；grep / 行号 edit 属于 **`:agent-tools`**，不进 C。
- 规格冲突时以 `include/weizhi.h`、`java/com/weizhi/WeizhiEngine.java` 与测试为准。
- 第三方接 Agent： [`docs/INTEGRATION_FOR_AI.md`](docs/INTEGRATION_FOR_AI.md)、[`docs/AGENT_TOOLS_INTEGRATION.md`](docs/AGENT_TOOLS_INTEGRATION.md)。

## 安全

- 禁止提交密钥、token、私钥、未脱敏的 `.env`
- 不要在 issue 中公开可利用的 0day 细节；走私有渠道或维护者约定

## 语言

- 仓库文档默认**中文**，README 与关键页 **中英并存**
- 用户可见错误与 DECISIONS 关键词保持**英文**（供 LLM 解析）
