# Agent 工具环接入计划

日期：2026-09-25。与 [DECISIONS.md](DECISIONS.md)、[INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) 一致：**引擎只负责 `runJs` + 内置模块**；grep / bash / Skill / MCP / WebView 属于 **Java `@Tool` 工具环**，放在新模块 `:agent-tools`，不进入 C 引擎。

## 1. 参考工程调研


| 能力                        | 参考模块                  | 核心类                                                               | 评价                                                                                                                                                                |
| --------------------------- | ------------------------- | -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 文件 + 搜索                 | `agent-core`              | `Sandbox`, `FileReadTools`, `GrepTool`, `GlobTool`, `FileEditTools`  | **保留**：双根读写分离清晰；与 Weizhi `LocalWorkspace`（caps 用）职责分离，工具环用 `WorkspaceSandbox` 对齐 Agent `Sandbox`                                         |
| Bash                        | `agent-core`              | `BashTool`                                                           | **保留并原样移植**：白名单 + 无 shell 的 `ProcessBuilder` + 路径校验，Android toybox 场景验证过                                                                     |
| run_js（原 execute_script） | `agent-js` + `agent-core` | `JsScriptTool` + QuickJS `ScriptEngine`                              | **改进**：Weizhi 侧工具名 **`run_js`**，实现走 **`WeizhiEngine.runJs`**（`WeizhiRunJsTool`）；`$tools` 桥 Phase 2 经 `HostCall` / prelude 注入，不拉回 agent-script |
| Skill                       | `agent-core` + `agent`    | `FileSystemSkillRepository`, `LoadSkillTool`, `AssetSkillRepository` | **保留模型**；内置 assets Phase 2；动态层默认 `workspace/skills/`（可写，符合 ROADMAP）                                                                             |
| MCP                         | `agent-core`              | `McpTools`, `McpRegistry`, `McpClient` (~1.6k 行) + OkHttp           | **Phase 3 可选模块** `:agent-tools-mcp`：目录式 `tools.jsonl` + `mcp_call_tool` 设计好，但依赖与配置 UI 重，不阻塞 Weizhi 集成                                      |
| WebView                     | `agent-web`               | `WebViewTools`, `WebViewExecTool`                                    | **Phase 2 可选模块** `:agent-tools-webview`：无头 WebView 必须主线程 + Context，单独 AAR，宿主按需依赖                                                              |
| 能力目录 / ReAct            | `agent-core` + `agent-ui` | `AbilitySearchTool`, `ReActAgent`                                    | **不纳入 weizhi 包**：编排与 UI 留在宿主 App；`:agent-tools` 只提供工具注册与 `invoke`                                                                              |

### 与现有 Weizhi 模块关系

```
宿主 App
  ├─ :weizhi      → WeizhiEngine.runJs（脚本沙箱）
  ├─ :caps        → android.files.*（脚本内 caps）
  └─ :agent-tools → @Tool 环（模型 tool-call）+ 可选 webview/mcp 扩展
```

- 工作区路径：**同一目录** 传给 `setFsRoot`、`AndroidCaps.Session.workspace`、`AgentToolsBundle` 的 workspace。
- 只读根：ROADMAP 中的「保护根 + 用户授权只读」→ `WorkspaceSandbox(base, extraReadRoot)`，与参考 Agent SR17 一致；引擎侧 `fs` 只读根 API 仍属 ROADMAP A，**不阻塞**工具环双根。

## 2. 设计取舍（相对参考 Agent 的改进）

1. **更薄的工具运行时**：`AgentToolkit` 只做注册 / JSON Schema / `call(name, input) → String`，不捆绑 `ReActAgent`、消息块、权限 UI（宿主自行对接 LLM SDK）。
2. **脚本入口单一真源**：`run_js` 只走 `WeizhiEngine`，避免维护第二套 QuickJS（agent-script）。
3. **模块化可选能力**：MCP、WebView 用 `AgentToolsExtension` 插件式注册，默认 AAR 不含 WebView/MCP，控制 dex 与权限面。
4. **Zip**：工具环优先复用已有 `com.weizhi.platform.ZipTools`（与 caps 同源算法），减少重复 zip-slip 逻辑。

## 3. 分阶段实施


| 阶段   | 交付                                                                                                                                               | 状态       |
| ------ | -------------------------------------------------------------------------------------------------------------------------------------------------- | ---------- |
| **P1** | Gradle`:agent-tools`；`WorkspaceSandbox`；文件/搜索/zip/bash；Skill + `load_skill_through_path`；`WeizhiRunJsTool`（`run_js`）；`AgentToolsBundle` | **进行中** |
| **P2** | `:agent-tools-webview`（移植 `WebViewTools`）；`AssetSkillRepository`（Android assets）；`run_js` 的 `$tools` 白名单桥                             | 待做       |
| **P3** | `:agent-tools-mcp`（移植 `McpTools` + store）；装配时写 `.mcp/tools.jsonl`                                                                         | 待做       |
| **P4** | 宿主示例（`:app` 演示 tool schema + runJs）；单测（Bash tokenize / Sandbox escape）；与 agent-ui 对齐的 `AgentToolFactory` 薄封装（可选）          | 待做       |

## 4. P1 用法（集成方）

```java
Path workspace = ...; // 与 setFsRoot 相同
WeizhiScriptRunner runner = new WeizhiScriptRunner(workspace, engine -> {
    AndroidCaps.install(engine, session);
    engine.enableFetch();
});

SkillRepository skills = new FileSystemSkillRepository(workspace.resolve("skills"));

AgentToolkit tk = AgentToolsBundle.builder(workspace)
    .extraReadRoot(null) // 或用户授权只读根
    .skillRepository(skills)
    .scriptRunner(runner)
    .build();

String out = tk.call("bash", Map.of("command", "ls -la"));
```

## 5. 风险与验证

- **Bash**：真机 toybox 命令子集与桌面不同 → 沿用参考白名单；instrumented 测 `pwd` / `ls`。
- **run_js**：同引擎多次 `runJs` 的全局 `const` 问题 → 文档要求「每轮任务新引擎」或脚本 IIFE；`WeizhiScriptRunner` 默认每次 `open → run → close`。
- **MCP/WebView**：网络与 JS 执行面扩大 → 独立模块 + 宿主显式依赖。

## 6. 不在本计划内

- 把 grep/read_file 塞进 Weizhi C 引擎（违反 DECISIONS）
- 嵌入完整 ReAct / agent-ui
- 引擎双根只读 API（ROADMAP A，与工具环并行推进）
