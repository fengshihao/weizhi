# Weizhi 接入指南（面向 AI / 集成方）

本文给**正在写接入代码的 AI 或工程师**：按步骤接线 Weizhi，少猜、少读源码。  
规格真源仍以代码与既有文档为准；冲突时以 `include/weizhi.h`、`java/com/weizhi/WeizhiEngine.java`、真机/桌面测试为准。

| 文档 | 何时读 |
|---|---|
| **本文** | 宿主怎么开引擎、设沙箱、跑脚本、装 caps |
| [AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md) | **整段复制进 Agent 系统提示**（脚本作者契约） |
| [DECISIONS.md](DECISIONS.md) | 限额、错误关键词、分层边界 |
| [ROADMAP.md](ROADMAP.md) | 产品方向；近期重点是 Agent 集成 |
| [HOST_ABI.md](HOST_ABI.md) / [NATIVE_PLUGIN_IDL.md](NATIVE_PLUGIN_IDL.md) | 签名原生插件 |
| [QUICKJS_LIB_COMPAT.md](QUICKJS_LIB_COMPAT.md) | 第三方 JS 库能不能塞进脚本 |

---

## 0. 给 AI 的硬约束（先读完再改代码）

1. **Agent 只有一个编程入口**：宿主调用 `WeizhiEngine.runJs(source[, timeoutMs])`，脚本返回值是 **JSON 文本**。
2. **不要**把行号 `read_file` / `edit_file` / `grep` / `glob` 实现进引擎或 Caps；那些是 Agent `@Tool`。
3. **脚本里**用 `fs` / `require("zip")`；**Caps** 用 `android.files.*`（含 `zipExtract`/`zipCreate`）；**Agent 工具环**自己注册 `@Tool`，可薄包 Caps。
4. **Java + JNI**，不要假设 Kotlin API。Android 交付物是 **AAR**（`:weizhi` + 可选 `:caps`）。
5. 当前 **一个可读写工作区**（`setFsRoot`）。额外只读根 / 技能库约定见 ROADMAP，**尚未实现**——集成时不要发明未文档化的双根 API。
6. `setScriptFolder` **只**服务 `loadScript("leaf.js")` / 相对 `import './leaf.js'`（叶子文件名），不是通用只读资料区。
7. 失败时把 **完整** `RuntimeException` message（及 C 侧 error）回传给编排 Agent；错误里含固定英文关键词（见 §7）。

---

## 1. 最小成功路径（复制即用）

### 1.1 构建产物

```bash
# 在 weizhi 仓库根目录
./scripts/build-android.sh arm64-v8a   # 产出 stripped libweizhijni.so → android/weizhi/src/main/jniLibs/
cd android && ./gradlew :weizhi:assembleRelease :caps:assembleRelease
```

- `:weizhi` → 引擎 + `WeizhiEngine` + `libweizhijni.so`（Release 应为 **stripped ~1.1MB**，不是未 strip 的 ~6MB）
- `:caps` → `AndroidCaps`（`globalThis.android`）
- `minSdk`：库侧 26；宿主须 `INTERNET` 若启用 `enableFetch`

把 AAR 以 `project` 依赖或发布到本地 maven 均可；AI 改宿主工程时优先：

```gradle
dependencies {
    implementation(project(":weizhi"))   // 或 files("weizhi-release.aar") + jni
    implementation(project(":caps"))    // 需要 android.* 时
}
```

桌面冒烟（有 JDK）：`./scripts/test-jni.sh`。真机：`./scripts/test.sh android`。

### 1.2 最小 Java 宿主

```java
import com.weizhi.WeizhiEngine;
import java.io.File;

File workspace = new File(context.getFilesDir(), "weizhi-ws");
workspace.mkdirs();

try (WeizhiEngine engine = new WeizhiEngine()) {
    engine.setFsRoot(workspace.getAbsolutePath());
    // 可选：脚本库目录（仅叶子 .js）
    // engine.setScriptFolder(new File(context.getFilesDir(), "weizhi-lib").getAbsolutePath());

    String json = engine.runJs("fs.writeFileSync('a.txt','hi'); fs.readFileSync('a.txt').toString()", 10_000);
    // json == "\"hi\""
}
```

### 1.3 带 Android Caps（推荐 Agent App）

```java
import com.weizhi.WeizhiEngine;
import com.weizhi.caps.AndroidCaps;
import com.weizhi.platform.PlatformHost;

File workspace = new File(context.getFilesDir(), "weizhi-ws");
workspace.mkdirs();

try (WeizhiEngine engine = new WeizhiEngine()) {
    engine.setFsRoot(workspace.getAbsolutePath()); // 引擎 fs 与 caps 工作区建议同一目录

    AndroidCaps.Session session = new AndroidCaps.Session(context, workspace);
    session.confirmer = message -> true; // 或弹系统对话框
    // session.directoryPicker = () -> /* SAF URI or null if cancelled */;
    // session.shareSink = (title, text) -> { ... };

    AndroidCaps.install(engine, session); // 安装 globalThis.android；mac/linux 为 stub

    String out = engine.runJs(
        "android.files.write('note.txt','发票'); android.files.read('note.txt')",
        10_000);
}
```

桌面：`DesktopCaps.install(engine, workspacePath, confirmer)` → `mac` 或 `linux`。

---

## 2. API 速查（宿主必须会的）

| 方法 | 作用 | 注意 |
|---|---|---|
| `new WeizhiEngine()` / `(WeizhiLimits)` | 开引擎 | `close()` / try-with-resources；任务结束宜关掉 |
| `setFsRoot(path)` | 可读写工作区 | 相对路径沙箱；逃逸错误含 `path`/`escape` |
| `setScriptFolder(path)` | `loadScript` / 相对 import 根 | **仅叶子文件名** `[A-Za-z0-9._-]+.js` |
| `runJs(source)` / `runJs(source, timeoutMs)` | 跑脚本 | `timeoutMs==0` → 默认 10 分钟；**负** → 不按墙钟截断；成功返回 JSON 文本；失败 **抛** `RuntimeException` |
| `cancel()` | 另一线程中止 | 错误含 `cancelled` |
| `enableFetch()` / `enableFetch(suffixes)` | 开 `fetch` | 未开则错误提示 `enableFetch`；Android 需 `INTERNET` |
| `setHostCall(HostCall)` | Caps 用的 `__caps` | `AndroidCaps.install` 内部会调 |
| `enableNativePlugins(dir)` | 真 SO 插件目录 | 见 NATIVE_PLUGIN_IDL |
| `enableNativeMock()` | 无 SO 联调 | 仅 mock 目录 |

`WeizhiLimits` 字段为 `0` = 用 C 默认：堆 32MB、栈 256KB、`fsIoBytes` 32MB、`maxAsyncIo` 16。

**线程**：同一引擎 **同一时刻只能一个** `runJs`；JS 只在调用 `runJs` 的线程跑。并发第二次 → 错误含 `busy`/`again`。

---

## 3. 脚本侧能力（宿主应写进 Agent 提示的摘要）

完整版复制 [AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md)。集成方最少保证模型知道：

- 模块：`fs`、`fs.promises`、`path`、`buffer`、`process`、`zlib`、`zip`；无 npm。
- `require("zip").extractSync/createSync`；Caps：`android.files.zipExtract` / `zipCreate`。
- `Buffer` 是 `Uint8Array` 子类；`fetch` 需宿主 `enableFetch`。
- 平台对象三选一：`android` / `mac` / `linux`；调错名字会 `unsupported`。
- Caps 文件 API：`list`/`read`/`write`/`mkdir`/`rename`/`move`/`undo`/`zipExtract`/`zipCreate`；Android 另有 `pickDirectory`、`media.resize`、`share`、`reminders`。

---

## 4. Agent 集成检查清单（给写 Agent1 的 AI）

按顺序做；不要跳步发明 API。

- [ ] 依赖 `:weizhi`（+ 需要时 `:caps`），确认 `jniLibs` 含 **stripped** `libweizhijni.so`
- [ ] App 私有目录创建 **workspace**，`setFsRoot`（与 `AndroidCaps.Session.workspace` 同一路径）
- [ ] （可选）`setScriptFolder` 指向 App 决定的可写技能/库目录（若与 workspace 分离）
- [ ] `AndroidCaps.install`；confirmer / pickDirectory / share 接到真实 UI
- [ ] （可选）`enableFetch(allowlist)` + Manifest `INTERNET`
- [ ] 工具环：`run_js` 工具（或等价）把模型产出的 JS 交给 `WeizhiEngine.runJs`；超时与 `cancel` 接到会话取消
- [ ] 行号读写 / grep / glob：**Agent `@Tool` + 自己的 Sandbox**，不要塞进 Weizhi
- [ ] zip：工具环可薄包 `android.files.zip*` 或继续用 Agent 自有 `ZipTools`；脚本内用 `require("zip")`
- [ ] 每次失败把 **完整英文 error** 回传模型；系统提示贴上 AGENT_SANDBOX_PROMPT
- [ ] 任务结束 `engine.close()`；不要假定跨多次 `runJs` 保留 timer；**注意**同引擎全局词法：`const` 不能重复声明

### 推荐：一轮任务一个引擎

```text
open → setFsRoot → [setScriptFolder] → [AndroidCaps.install] → [enableFetch]
  → loop: runJs / cancel
→ close
```

---

## 5. Caps 表面（宿主装完后脚本可调）

安装后仅有一个活平台对象（例：`android`）：

```javascript
android.ui.confirm(msg)
android.files.list(dir)
android.files.read(path) / write(path, text) / mkdir(dir)
android.files.rename(path, name) / move(path, toDir) / undo()
android.files.zipExtract(file, dest?)  // dest 省略 → tmp/<zip名>/
android.files.zipCreate(sourceDir, file)
android.files.pickDirectory()          // 仅 Android；取消则错误含 cancelled
android.media.resize(path, maxEdge)    // 需 ensureNative("image_resize")
android.share.send({ title, text })
android.reminders.schedule|cancel|fire(...)
android.audit.recent()
```

实现入口：`AndroidCaps.install` / `DesktopCaps.install` → `PlatformHost` + `PlatformScripts.install`。

---

## 6. 常见错误与改法（AI 自纠）

| 现象 | 处理 |
|---|---|
| `UnsatisfiedLinkError: weizhijni` | 未打进 abi 的 `.so`；先 `build-android.sh` 再编 AAR |
| AAR 里 so ~6MB | strip 未生效；见 `scripts/build-android.sh`，应用 `stripped/` |
| `unsupported: fetch` | `enableFetch()` + `INTERNET` |
| `fetch blocked: host` | 放宽 allowlist 或换 URL |
| `workspace not set` / path 类错误 | 忘记 `setFsRoot` 或路径逃逸 |
| `unsupported: mac.*` on Android | 应用 `android.*`，不要抄桌面对象名 |
| `busy` / `again` | 并发/重入 `runJs` |
| `cancelled` | 用户取消或 `cancel()`；**不要**用字符串误伤其它含 cancelled 的文案（引擎已按 flag 区分） |
| `const` 重复声明 | 同引擎多次 `runJs` 共享全局；换名或新引擎 |

---

## 7. 错误关键词（必须回传原文）

模型靠这些自纠（详见 DECISIONS）：

`unsupported` / `bad argument` / `path` / `escape` / `timeout` / `cancelled` / `memory` / `stack` / `too large` / `enableFetch` / `fetch blocked`

---

## 8. 不要做的事

- Fork / 修改 `third_party/quickjs`
- 在引擎里实现 Agent 编程工具（grep、带行号 edit）
- 假设有 npm、`node_modules`、持久事件循环、双根只读 API（未落地前）
- 吞掉 `runJs` 异常只返回 “failed”
- 把未 strip 的 debug `.so` 当 Release 分发

---

## 9. 验证集成是否合格

1. `runJs("1+2")` → `"3"`
2. `setFsRoot` 后读写文件 roundtrip
3. （若装 caps）`android.files.write/read` + `zipCreate/zipExtract`
4. （若开网）`enableFetch` 后 `fetch` 成功；未开则错误含 `enableFetch`
5. 另一线程 `cancel()` 长脚本 → 错误含 `cancelled`
6. 真机：`./scripts/test.sh android` 或宿主自有 instrumented 测试

---

## 10. C / 非 Android 宿主

公开 C API：`include/weizhi.h`（`weizhi_open` / `weizhi_set_fs_root` / `weizhi_run_js` / `weizhi_cancel` / `weizhi_set_http` …）。  
语义与 Java 一致；异步完成必须从宿主回调 `weizhi_complete*`，禁止在线程池里直接调 QuickJS。
