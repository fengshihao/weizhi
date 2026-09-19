# Agent 工具环接入指南（第三方 / 宿主 App）

面向**把 Weizhi 接进自有 Agent App** 的工程师与 AI。引擎与脚本契约仍以 [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) 为准；本文只讲 **`:agent-tools*` Gradle 模块** 怎么依赖、装配、喂给 LLM、验证。

架构背景见 [AGENT_TOOLS_PLAN.md](AGENT_TOOLS_PLAN.md)、边界见 [DECISIONS.md](DECISIONS.md)（grep/bash 不进 C 引擎）。

---

## 1. 模块怎么选

| 模块 | 何时需要 | 产物 |
|------|----------|------|
| `:weizhi` | 必装 | `WeizhiEngine` + `libweizhijni.so` |
| `:caps` | 脚本里要用 `android.*` / 桌面 `mac`/`linux` | `AndroidCaps` 等 |
| `:agent-tools` | LLM **tool-call** 环（读文件、grep、bash、`run_js`、Skill） | AAR `com.weizhi.agent` |
| `:agent-tools-webview` | 需要 **Chromium/wasm/DOM**（PDF、重计算） | `webview_exec` |
| `:agent-tools-mcp` | 需要接 **远端 MCP Server** | `mcp_call_tool` / `mcp_list_servers` |

**分层（不要混）**

- **模型 tool-call** → `AgentToolkit.call(name, input)`（Java 工具环）
- **脚本内编程** → `run_js` → 内部 `WeizhiEngine.runJs`（QuickJS + `fs`/caps）
- **脚本内再调工具** → `await $tools.grep({...})`（白名单桥，见 §5）

---

## 2. Gradle 与构建

### 2.1 同仓库多模块（推荐联调）

在宿主 `settings.gradle` 里 include Weizhi 子工程（或 git submodule 指向本仓库 `android/`），例如：

```gradle
include(":weizhi", ":caps", ":agent-tools")
// 按需：
// include(":agent-tools-webview")
// include(":agent-tools-mcp")
```

宿主 `app/build.gradle`：

```gradle
dependencies {
    implementation(project(":weizhi"))
    implementation(project(":caps"))
    implementation(project(":agent-tools"))
    // implementation(project(":agent-tools-webview"))
    // implementation(project(":agent-tools-mcp"))
}
```

### 2.2 构建 AAR

```bash
./scripts/build-android.sh arm64-v8a
cd android && ./gradlew \
  :weizhi:assembleRelease \
  :caps:assembleRelease \
  :agent-tools:assembleRelease \
  :agent-tools-webview:assembleRelease \
  :agent-tools-mcp:assembleRelease
```

发布或 `implementation(files("…/agent-tools-release.aar"))` 时，**必须**同时带上 `:weizhi` 的 `jniLibs`（与纯引擎集成相同）。

### 2.3 AndroidManifest

| 能力 | 权限 / 说明 |
|------|-------------|
| `run_js` + `enableFetch` | `INTERNET` |
| MCP | `INTERNET`；配置见 §7 |
| `webview_exec` | 无额外权限；WebView 在 App 进程内，注意 **主线程**（模块内已用 `HandlerUiExecutor`） |
| bash / 文件工具 | 仅访问 **workspace**（及工具环配置的只读根），不需存储权限 |

---

## 3. 路径与工作区（必对齐）

以下路径建议 **同一目录**（绝对路径字符串一致）：

1. `WeizhiEngine.setFsRoot` / `WeizhiScriptRunner` 使用的 workspace  
2. `AndroidCaps.Session.workspace`  
3. `AgentToolsBundle.builder(workspace)` 的 `Path`

```java
File workspace = new File(context.getFilesDir(), "agent-ws");
workspace.mkdirs();
Path ws = workspace.toPath();
```

**工具环沙箱**（`WorkspaceSandbox`）与 **引擎 `fs`** 是两套实现，但应对准同一磁盘目录：

- 写操作：仅 workspace 内相对路径  
- **可选** `extraReadRoot`：用户授权只读目录（如 SAF 映射后的路径）；**引擎 `fs` 侧双根尚未实现**（ROADMAP A），只读外部文件可先用工具环 `read_file`/`grep`/`bash` 读绝对路径  

```java
AgentToolsBundle.builder(ws)
    .extraReadRoot(userGrantedReadOnlyPath)  // 可 null
    ...
```

---

## 4. 最小装配（仅 `:agent-tools`）

```java
import com.weizhi.agent.AgentToolsBundle;
import com.weizhi.agent.tool.AgentToolkit;
import com.weizhi.caps.AndroidCaps;

File workspace = ...;
AndroidCaps.Session session = new AndroidCaps.Session(context, workspace);
session.confirmer = msg -> /* UI 或自动 */ true;

AgentToolkit tk = AgentToolsBundle.builder(workspace.toPath())
    .engineConfigure(engine -> {
        try {
            AndroidCaps.install(engine, session);
            // engine.enableFetch();  // 若脚本要 fetch
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    })
    .build();

// 交给你的 LLM 循环：
String grepOut = tk.call("grep", Map.of("pattern", "TODO", "path", "."));
String jsOut = tk.call("run_js", Map.of("code", "JSON.stringify(1+2)", "timeout_ms", 60_000));
```

### 4.1 `AgentToolsBundle.Builder` 常用项

| 方法 | 作用 |
|------|------|
| `engineConfigure(Consumer<WeizhiEngine>)` | 每次 `run_js` 开新引擎时调用（装 caps、fetch、原生插件等） |
| `scriptRunner(WeizhiScriptRunner)` | 完全自定义 runner；默认内部创建 |
| `registerRunJs(false)` | 不注册 `run_js`（仅要文件/bash 工具时） |
| `defaultSkillsDir()` | 技能根 = `workspace/skills`（可写，AI 可落盘 Skill） |
| `compositeSkills(context, "agent_skills")` | assets 内置 + `workspace/skills` 叠加（同名后者覆盖） |
| `skillRepository(SkillRepository)` | 完全自定义仓库 |
| `jsExposed(Set<String>)` | 覆盖 `run_js` 内 `$tools` 白名单；默认 = 已注册工具 − `run_js` |
| `extension(AgentToolsExtension)` | 插件（WebView、MCP 等） |

### 4.2 导出给 LLM 的 schema

```java
List<Map<String, Object>> tools = tk.exportSchemas();
// 每项含 name、description、input_schema — 可原样映射 OpenAI / Anthropic tools API
```

实现类：`com.weizhi.agent.tool.AgentToolkit`（反射 `@Tool` / `@ToolParam`，无独立 LLM SDK 依赖）。

---

## 5. 默认工具一览（`:agent-tools`）

| 工具名 | 用途 | 备注 |
|--------|------|------|
| `read_file` | 带行号读文本 | 二进制拒绝；大文件 offset/limit |
| `write_file` | 覆盖写 | 支持 `encoding: base64` |
| `edit_file` | 精确替换 | `old_string` 唯一或 `replace_all` |
| `grep` | 正则搜内容 | `output_mode`: content / files / count |
| `glob` | 路径 glob | 默认最多 100 条 |
| `zip_extract` / `zip_create` | 工作区内 zip | 与 `com.weizhi.platform.ZipTools` 同源 |
| `bash` | toybox 白名单命令 | 无 shell 管道；写命令仅 workspace |
| `load_skill_through_path` | 读 Skill 文件 | 需配置 `SkillRepository` |
| `run_js` | 跑 QuickJS 片段 | 返回 JSON 文本；每次默认 **新引擎** open→close |

**可选模块追加**

| 工具名 | 模块 |
|--------|------|
| `webview_exec` | `:agent-tools-webview` |
| `mcp_call_tool` / `mcp_list_servers` | `:agent-tools-mcp` |

---

## 6. `run_js` 与 `$tools` 桥

### 6.1 语义

- 宿主对模型暴露工具 **`run_js`**（不是 `execute_script`）。  
- 每次调用默认 **新建** `WeizhiEngine`，跑完即 `close()`，避免全局 `const` 冲突（与 [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) 一致）。  
- 参数：`code`（必填）、`timeout_ms`（可选，默认 60000；`0` = 引擎默认 10 分钟）。

### 6.2 脚本内调工具

装配后自动注入（`ScriptToolsBridge`）：

```javascript
// 在 run_js 的 code 里：
return await $tools.grep({ pattern: "error", path: "." });
```

- 走 `__caps` 的 `op: "agent.tool"`，与 caps 的 `files.*` 共用 HostCall 链（需先 `AndroidCaps.install`，再 chain 桥）。  
- **`run_js` 不在 `$tools` 白名单**，防止递归。  
- 自定义白名单：`Builder.jsExposed(...)` 或 `tk.addJsExposed("webview_exec")`（扩展模块注册后）。

### 6.3 与 caps 同屏

脚本里可同时用：

- `fs` / `require("zip")`（引擎）  
- `android.files.*`（caps，需 `engineConfigure` 里 install）  
- `$tools.*`（Java 工具环）

---

## 7. Skill（内置 + 可写）

### 7.1 目录约定

```
assets/agent_skills/<skillId>/SKILL.md     # 内置只读（可选）
<workspace>/skills/<skillId>/SKILL.md      # 动态可写（AI 创建 Skill）
```

`SKILL.md` 最小 frontmatter：

```yaml
---
name: my-skill
description: 何时触发（给模型看）
---
正文指令…
```

### 7.2 装配

```java
.compositeSkills(context, "agent_skills")  // 推荐
// 或 .defaultSkillsDir()  // 仅 workspace/skills
```

模型工具：`load_skill_through_path(skillId, path)`，`path` 常用 `"SKILL.md"` 或 `references/...`。

Demo 样例：仓库 `android/app/src/main/assets/agent_skills/demo/SKILL.md`。

---

## 8. WebView 模块（可选）

```gradle
implementation(project(":agent-tools-webview"))
```

```java
import com.weizhi.agent.web.WebViewAgentExtension;

.extension(new WebViewAgentExtension(context))
```

- 注册 **`webview_exec`**，并加入 `$tools` 白名单（扩展内 `addJsExposed`）。  
- 适合 wasm/DOM/canvas；普通逻辑优先 **`run_js`**。  
- 单进程单例 WebView 串行队列；超时后页面会重置（见 `WebViewRuntime`）。

---

## 9. MCP 模块（可选）

```gradle
implementation(project(":agent-tools-mcp"))
```

```java
import com.weizhi.agent.mcp.McpAgentExtension;

.extension(new McpAgentExtension(context.getFilesDir().toPath()))
```

### 9.1 配置文件

路径：`<filesDir>/mcp_servers.json`（由 `McpServerStore` 读取）。  
格式见 [examples/mcp_servers.example.json](examples/mcp_servers.example.json)：

```json
{
  "version": 2,
  "servers": [
    {
      "name": "my_server",
      "url": "https://your-host/mcp",
      "enabled": true,
      "description": "给模型看的简短说明"
    }
  ]
}
```

### 9.2 模型用法（目录模式）

- 装配时若有 **enabled** 的 server，注册 `mcp_call_tool` / `mcp_list_servers`，并在 workspace 写入 **`.mcp/tools.jsonl`**（工具目录）。  
- 模型先用 **`grep` / `read_file`** 在 `.mcp/tools.jsonl` 查限定名（`mcp__<server>__<tool>`），再 `mcp_call_tool`。  
- 无 enabled server 时 **不会**注册 MCP 工具（`McpTools.fromStore` 返回 null）。

### 9.3 HTTP 客户端与代理

MCP 模块 **不会**读取 `https_proxy` / `HTTP_PROXY` / `no_proxy` 等环境变量。默认直连（`McpHttpClients.direct()`）。

需走企业代理或自定义 TLS 时，由宿主提供 `McpHttpClientFactory`，在装配时传入：

```java
import com.weizhi.agent.mcp.McpAgentExtension;
import com.weizhi.agent.mcp.McpHttpClients;
import okhttp3.OkHttpClient;

OkHttpClient ok = new OkHttpClient.Builder()
        // .proxy(...) / .proxyAuthenticator(...) 等由宿主自行配置
        .build();

.extension(new McpAgentExtension(
        context.getFilesDir().toPath(),
        McpHttpClients.shared(ok)))
```

也可实现 `McpHttpClientFactory`，按 `McpServerConfig` 为不同 server 返回不同 `OkHttpClient`；或直接 `new McpClient(config, factory)` 做单元测试。

---

## 10. 接 LLM SDK（模式）

Weizhi **不提供** ReAct 循环；宿主自行：

1. `AgentToolkit tk = AgentToolsBundle.builder(...).build();`  
2. 把 `tk.exportSchemas()` 传给模型 API 的 tools 参数。  
3. 收到 tool_call → `String result = tk.call(toolName, argsMap);`  
4. 把 `result` 作为 tool 消息塞回对话。  
5. 对 **`run_js`**：把返回的 JSON 字符串原样给模型；失败时 `tk.call` 返回以 `Error:` 开头的文本（勿吞掉）。

**线程**：`bash` / `run_js` / `webview_exec` 不要在 Android **主线程**调用（Demo 用后台线程）。`run_js` 内 caps 的 `ui.confirm` 仍须能切主线程弹窗。

**取消**：当前 `AgentToolkit.call` 同步阻塞；长任务请在宿主层超时并 `WeizhiEngine.cancel()`（需持有正在 `runJs` 的引擎引用）。默认 `WeizhiScriptRunner` 每段脚本独立引擎，取消需后续 API 扩展或自建 runner。

---

## 11. 验证清单

**单元测试（CI，无需设备）**

```bash
cd android && ./gradlew :agent-tools:testReleaseUnitTest
```

**真机（含 Agent 工具）**

```bash
./scripts/test.sh android
# 内含 :agent-tools:testReleaseUnitTest 与 :app:connectedDebugAndroidTest
# 见 AgentToolsInstrumentedTest（bash、run_js、skill、$tools.grep）
```

**手工冒烟**

| 步骤 | 期望 |
|------|------|
| `tk.call("bash", {command:"pwd"})` | 输出含 workspace 路径片段 |
| `tk.call("run_js", {code:"1+2"})` | `"3"` |
| `compositeSkills` + `load_skill_through_path("demo","SKILL.md")` | 含 Demo 文案（需 assets） |
| `write_file` + `run_js` 内 `$tools.grep` | 命中写入内容 |
| （可选）MCP enabled + `mcp_list_servers` | 非空 server 列表 |

---

## 12. 常见集成问题

| 现象 | 处理 |
|------|------|
| `$tools.xxx is not defined` | 未走 `AgentToolsBundle` 默认装配；或 `xxx` 不在 `jsExposed`；或 `run_js` 未 chain 桥（caps 未 install 也可 chain，但无 android.*） |
| `unsupported: $tools.run_js` | 预期行为；脚本层应直接写 JS，不要递归调 run_js |
| `run_js` 与 caps 路径不一致 | 对齐 workspace 与 `Session.workspace` |
| MCP 工具未出现 | `mcp_servers.json` 无 `enabled: true` 的 server |
| `webview_exec` 无响应 | 是否在主线程调 WebView；看 logcat `WeizhiWebView` |
| bash 命令被拒绝 | 不在白名单或含 `;` `\|` 等 shell 元字符 |
| 工具返回 `Error: unknown tool` | 未注册扩展模块或工具名拼写错误 |

引擎侧错误关键词仍见 [INTEGRATION_FOR_AI.md §7](INTEGRATION_FOR_AI.md#7-错误关键词必须回传原文)。

---

## 13. 参考实现

- 最小 Demo：`android/app` → `MainActivity`（Agent bash / run_js / skill 按钮）  
- 仪器测试：`android/app/src/androidTest/.../AgentToolsInstrumentedTest.java`  

---

## 14. 与 ROADMAP 的边界

- **引擎 `fs` 只读根**、快照版本库、多级 import：尚未在 `WeizhiEngine` 落地；工具环已支持 `extraReadRoot`。  
- 不要把 **ReAct / agent-ui** 当作 Weizhi 必装组件；需要时在宿主 App 自行实现循环。
