# Agent1 集成：Word（docx.js / docx-raw.js）

日期：2026-09-29。来源：Issue #6 演进。给人读的 API 见 [office-js-api.md](office-js-api.md)。Agent 发现面是 [api-cards.jsonl](api-cards.jsonl)（`./sync-weizhi.sh` 之后导入能力索引）；不要把 `office-js-api.md` 或 `docs/system/*.md` 打进 APK。

---

## 1. 目标

让 Agent1 里的 LLM 通过 **`runJs` + ES module** 完成：

| 场景 | 推荐模块 / API |
|---|---|
| **从零写**（新报告、新合同、结构化生成） | **`docx-build.js`**（Builder / `blocks([…])`），或 Markdown → `markdownToDocx` |
| **改已有** docx（找段、改字、改样式、追加） | **`docx.js`**：`readDocx` → `grep` / `textView` / `replace*` / `setBlockStyle` → `save`（**不用 Builder**） |
| OOXML 级补丁（页眉、relationships 等） | `docx-raw.js` + `validateDocx` |

### 创作 vs 修改（给 Agent 的固定分工）

1. **新文档**：用 Builder（或 Markdown）**生成一份新的 `.docx`**，保存到 workspace；用户或后续轮次再在这份文件上改。
2. **已有文档**：始终 **`readDocx(路径)`** 加载，在内存里改 blocks，**`save`** 到原路径或新路径；不要对已有文件走 `buildDocx` 覆盖式「重写整篇」（除非用户明确要求「重做一版」）。
3. **「重做一版」**：当旧文档结构太乱、改起来不如重写时，可以用 Builder **新建** `out/xxx-v2.docx`，再在 v2 上用 read/grep 微调——旧文件保留作对照。

**不要**再依赖已删除的 Java `host.office.*`。行级 `read_file` / `grep` / `edit_file` 仍是 Agent1 `@Tool`；Word 正文检索用 **`Document.grep`** / **`textView`**。

---

## 2. 宿主必做（Agent1）

1. **引擎**：`WeizhiEngine` + `setFsRoot(workspace)`（与 Caps 工作区同一目录）。
2. **Caps**：`AndroidCaps.install` / `DesktopCaps.install`，保证 `files.mkdir`、`files.zipExtract`、`files.zipCreate` 可用（或脚本内 `import zip from "zip"`）。
3. **脚本目录**：`engine.setScriptFolder(<含 docx 的目录>)`，并把 Weizhi 仓库资源拷入 catalog：

   | 文件 | 说明 |
   |---|---|
   | `assets/office/docx.js` | 文档模型 + 渲染 + grep/样式 |
   | `assets/office/docx-build.js` | 可选 **Builder / 块 DSL**（从零写文档） |
   | `assets/office/docx-raw.js` | 解压 / 打包 / `validateDocx` |

   Android：`android/app/src/main/assets/office/` 已与 `assets/office/` 同步；Agent1 可同样放进 `assets` 或下发到 `workspace/scripts/office/`。

4. **系统提示**：把 [office-js-api.md](office-js-api.md) 摘要或链接放进 Agent 工具说明；沙盒契约见 [AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md)（**只用 `import`，不用 `require`**，无 `loadScript`）。

5. **打开 docx 给用户看**：`android.intent.start({ action: "view", path })`。宿主声明 `${applicationId}.fileprovider`（paths 覆盖 workspace），并设 `session.launchIntent = true`。

---

## 3. 推荐工具环（Agent1 `@Tool` 薄封装）

可选注册 4 个工具（内部均 `runJs` 一段 `import` 脚本）：

| 工具名 | 脚本要点 |
|---|---|
| `docx_create`（新） | `buildDocx(...)` 或 `markdownToDocx` → 产出**新**文件 |
| `docx_edit`（改已有） | `readDocx` → `grep` / `replaceAll` / `setBlockStyle` → `save` |
| `docx_inspect` | `textView({ includeStyle: true })` 或 `listBlocks` / `headings` |
| `docx_raw_edit` | `unpackDocx` → 改 XML → `validateDocx` → `packDocx` |

返回值：引擎 JSON；**校验失败**时把 `validateDocx` 的 `errors[]`（含 `file`、`line`、`column`）原样给 LLM。

---

## 4. 示例脚本（可复制进 runJs）

### 4.1 Markdown → docx

```javascript
import { markdownToDocx } from "./docx.js";
export default markdownToDocx({
  inputPath: "notes/brief.md",
  outputPath: "out/brief.docx",
  title: "简报",
  defaultStyle: { font: "宋体", sizePt: 12, lineSpacing: 1.5 },
});
```

### 4.2 read → grep → edit → save

```javascript
import { readDocx } from "./docx.js";
const doc = readDocx("in/contract.docx");
const { matches } = doc.grep("甲方");
if (matches.length === 0) export default { ok: false, reason: "not found" };
doc.replaceInBlock(matches[0].blockIndex, "甲方", "乙方");
export default doc.save("out/contract.docx");
```

### 4.3 原始 OOXML + 校验

```javascript
import { unpackDocx, packDocx, validateDocx } from "./docx-raw.js";
import fs from "fs";

const { dir } = unpackDocx("in/old.docx", { workDir: "tmp/ooxml" });
const p = dir + "/word/document.xml";
fs.writeFileSync(p, fs.readFileSync(p).toString().replace("OLD", "NEW"));
const v1 = validateDocx({ dir });
if (!v1.ok) export default v1;
const packed = packDocx(dir, "out/new.docx");
export default { packed, validate: validateDocx({ path: "out/new.docx" }) };
```

---

## 5. 校验预期（给 Agent 的说明）

`validateDocx` **能**发现：非 zip、缺 OPC 部件、XML 不闭合、缺 `w:body`。  
**不能**保证 Word 一定打开或版式正确（无 XSD / 无 Word 试开）。

桌面可选：`./scripts/test-office-strict.sh`（需先 `./scripts/test-jni.sh`，有 `xmllint` 时更严）。

---

## 6. 自动化测试（Weizhi 仓库）

| 命令 | 覆盖 |
|---|---|
| `./scripts/test-jni.sh` | `tests/java/OfficeTest`：MD→docx、模型渲染、read/save、grep、样式、raw+validate、路径逃逸 |
| `./scripts/test.sh android` | `CapsInstrumentedTest.docxJsMarkdownToDocx`、`docxJsGrepValidate` |
| `./scripts/test-office-strict.sh` | 可选 xmllint（桌面） |

Agent1 CI 建议在集成后增加：拷贝 `assets/office/*.js` → 跑一次 `markdownToDocx` smoke（与 CapsInstrumentedTest 同构）。

---

## 7. 验收清单（Agent1 Issue）

- [ ] `setScriptFolder` 含 `docx.js` + `docx-raw.js`
- [ ] workspace 与 `setFsRoot` 一致
- [ ] 至少 1 个 `@Tool` 或固定 `runJs` 模板能产出 `.docx` 并通过 `validateDocx`
- [ ] Agent 系统提示说明：优先 `docx.js`，失败再 `docx-raw.js` + validate
- [ ] 用户从 App 打开生成的 docx：`android.intent.start({ action: "view", path })` + FileProvider

---

## 8. Android 崩溃 / 无日志排查（与桌面 CLI 不同步时）

桌面 **`./scripts/test-jni.sh` 正常**，只说明「本机 CMake 编出来的 **`libweizhijni.so` + 同仓库 `WeizhiEngine.java`**」匹配。Agent1 若**只换 SO、或 Maven 里 Java/SO 版本不一致**，会在 Android 上直接崩进程，且不一定有 Java 堆栈。

### 8.1 代码上最常见原因（按优先级）

1. **JNI 与 Java 不同步**（自 D4 / `runJs` 文件名栈起）  
   - Java 侧：`nativeRunJs(long, String, int, String)` **4 个参数**。  
   - 必须 **`weizhi` AAR 里的 `WeizhiEngine.class` 与 `jniLibs/*/libweizhijni.so` 同一次发布**（`./scripts/publish-android-maven.sh` 或整包 import），不要只拷 `.so` 进旧工程。  
   - 典型 logcat：`UnsatisfiedLinkError`、`NoSuchMethodError`、`*JNI DETECTED ERROR*`。

2. **ABI 不匹配**  
   - 真机 arm64 需 `jniLibs/arm64-v8a/libweizhijni.so`；x86 模拟器需 `x86_64`。混用会 `dlopen` 失败。

3. **不是 native 崩，而是脚本/集成**（表现为 `RuntimeException`，message 以 `!` 开头被 Java 抛出）  
   - 仍调用已删除的 **`loadScript`** → ReferenceError。  
   - **`setScriptFolder` 未设**就 `import './docx.js'` → 模块找不到。  
   - **`setFsRoot` 与 Caps workspace 不一致** → 写 docx 路径失败。  
   - **`docx.js` 未打进 Agent1 assets/catalog** → import 失败。

4. **资源极限**（较少见，一般会有 JS 错误文案）  
   - 默认 JS 栈 **256KB**、堆 **32MB**；超大脚本或极深递归 → `"stack"` / `"memory"`。docx 库较大，但仍应在默认限额内；若仍 OOM，可在 Agent1 构造 `WeizhiLimits` 加大 `jsHeapBytes` / `jsStackBytes` 试一次。

5. **线程**  
   - 不要在 **主线程** 长时间 `runJs`（部分 UI cap 会抛 `runJs must not be on the main thread`）。应在后台线程跑引擎。

### 8.2 建议抓 log（无 Android Studio 时）

```bash
adb logcat -c
adb logcat -v time '*:E' | rg -i 'weizhi|UnsatisfiedLink|FATAL|signal 11|JNI|QuickJS|AndroidRuntime'
```

复现一次崩溃后，看是否有 **UnsatisfiedLinkError**（集成问题）或 **signal 11 SIGSEGV**（native，多为 SO/ABI 或引擎 bug）。

### 8.3 Weizhi 仓库内对照测试（真机/模拟器）

```bash
./scripts/build-android.sh arm64-v8a
cd android && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.weizhi.WeizhiJniInstrumentedTest
# docx：CapsInstrumentedTest#docxJsMarkdownToDocx / docxJsGrepValidate
```

若上述在设备上绿，而 Agent1 仍崩，问题几乎一定在 **Agent1 打包的 AAR/SO 版本或脚本集成**，不在 docx JS 本身。

---

## 9. 相关文档

- [office-js-api.md](office-js-api.md) — API 表
- [office.md](office.md) — 总览
- [office-architecture.md](office-architecture.md) — 架构
- [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md) — 引擎/Caps 接入
