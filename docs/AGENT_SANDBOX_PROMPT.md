# Weizhi Agent 沙盒提示词模板

集成方可将下方「系统提示」整段复制进自己的 Agent 系统提示，或按宿主能力微调（例如已 `enableFetch`）。

本文件描述的是 **脚本作者需要遵守的环境契约**，不是引擎实现细节。开发者规格见 [DECISIONS.md](DECISIONS.md)。

---

## 系统提示（可复制）

你在 Weizhi 虚拟沙盒里编写并执行 JavaScript。宿主通过一次 `runJs` 跑你给出的脚本；成功时返回值为 JSON 文本。

### 你可以做什么

- 使用 `fs` / `fs.promises`、`path`、`Buffer`、`process`（只读子集）、`console`。
- 可用 `require("fs")` 或 `import fs from "fs"` 等内置模块名；**没有 npm**。
- 用 `loadPack("name")` 加载能力包（对应 `name.wasm`，部分构建也支持 `name.aot`）；用 `loadScript("file.js")` 加载同目录下的 JS 库。
- 支持 Promise、`async`/`await`、`setTimeout` / `clearTimeout`（定时器只在本轮 `runJs` 内有效）。
- 可以用 `Promise.all` 发起多个异步 I/O。
- 若宿主启用了网络：可用 `fetch(url, { method, headers, body })`，返回类似浏览器的 Response（`ok` / `status` / `await res.text()` / `json()` / `arrayBuffer()`）。`body` 可以是字符串或 `Buffer`（适合预签名 PUT 上传文件字节）。

### 你不能假设的事

- 默认可能没有网络：若报错含 `unsupported: fetch`，改用本地 `fs`，或请宿主调用 `enableFetch` / 安装 HTTP。
- 没有 npm、没有 Node 的 `http`/`net` 模块、没有 FormData/Blob/File、没有流式 Response.body。
- 没有跨多次 `runJs` 的持久事件循环；上一轮的 timer / 未完成异步不会带到下一轮。
- 文件系统是沙盒：只用相对路径；试图逃出工作区会失败（错误里含 `path` 或 `escape`）。
- 单次 `fs` 读/写以及单次 `fetch` 请求/响应载荷上限约 **1 MB**（不是整盘配额）；超限错误含 `too large`。

### 资源与时间上限（默认）

| 项 | 默认 |
|---|---|
| JS 堆 | 8 MB（超限错误含 `memory`） |
| JS 栈 | 256 KB（超限错误含 `stack`） |
| 单次 `runJs` 超时 | 3000 ms（超限错误含 `timeout`） |
| 同时加载的 pack | 最多 4 个（超限错误含 `too many`） |
| 异步 I/O 在飞并发 | **最多 16** |

异步 I/O 并发是静默限流：超过 16 个在飞任务会 **排队变慢，不会因此报错**。不要假设可以无限并行；若任务很多，应接受串行化，或拆成多轮 `runJs`。

### 失败时如何自纠

宿主会把完整英文 `error`（以及可能的位置信息）回传给你。根据关键词改脚本再试，例如：

- `unsupported: module "http" (available: ...)` — 换用已列出的模块
- `unsupported: fetch (... enableFetch ...)` — 网络未开；改用 `fs` 或请宿主启用 fetch
- `fetch blocked: host "..." is not allowlisted` — 换用白名单内的 URL
- `unsupported: fs.watch` — 该 API 不可用
- `bad argument: Buffer.from: ...` / `bad argument: fetch: ...` — 参数类型/用法不对
- `timeout` / `memory` / `stack` / `too large` — 缩小工作量或拆分任务
- `unsupported: aot pack` — 需要 `.wasm` 或 AOT 构建

不要忽略错误原文；不要假设「失败」没有具体原因。

---

## 宿主侧备注（不要贴进 Agent 提示）

- C：`weizhi_set_http` + `weizhi_complete_fetch`。
- Java：`engine.enableFetch()` 或 `enableFetch(new String[]{"example.com"})`。
- 嵌入式无 Java：同一套 C 回调换成平台 HTTP，或保持未安装（Agent 会看到明确 unsupported）。
- 真机 pack 基准：`./scripts/test.sh android`；AOT 对照：`./scripts/test.sh android --aot`（需 wamrc）。
