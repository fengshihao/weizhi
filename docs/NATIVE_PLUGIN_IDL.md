# Native 插件：IDL / 近原生绑定（实施中）

本文落实「SO 定制到底」方案：AI 友好 JS API、接近原生性能、`Buffer` 传二进制、C→JS 回调。  
与 [HOST_ABI.md](HOST_ABI.md) 目录/验签/生命周期配合；**调用面**不再以 JSON RPC 为主。

状态（2026-09-24）：设计锁定并开始落地；`enableNativeMock` 仍保留作无 SO 冒烟。

---

## 1. 目标

| 目标 | 做法 |
| --- | --- |
| AI 放心用 | IDL 生成文档与包装；写错 → `bad argument: ...`，不进 UB |
| 近原生 | ensure 时缓存 `dlsym`；同步调用直跳 C；无 JSON 热路径 |
| 二进制 | JS `Buffer` ↔ `WeizhiBuf`（同步可借阅零拷贝） |
| C→JS | `weizhi_cb_*` 入完成队列，仅在 JS 线程执行 |

## 2. IDL（唯一契约）

路径约定：`plugins/<name>/<name>.idl`

```text
plugin echo_math
version 1.0.0
min_host_abi 1

fn add(a: i32, b: i32) -> i32
fn echo_bytes(data: bytes) -> bytes
fn count_with_cb(n: i32, on_i: cb(i: i32)) -> i32
```

### 2.1 第一期类型子集

| IDL | JS | C |
| --- | --- | --- |
| `i32` | number（截断为 int32） | `int32_t` |
| `i64` | number（注意精度）或后期 BigInt | `int64_t` |
| `f64` | number | `double` |
| `bytes` | `Buffer` | `WeizhiBuf { data, len }` |
| `cb(...)` | function | `uint32_t cb_id`（宿主/引擎分配） |
| `void` | `undefined` | `void` |

### 2.2 工具

`scripts/weizhi-bindgen.py`：

- 读 `.idl` → 写 `weizhi_<name>_api.h`（导出声明）
- → 写 `manifest.json`（typed exports）
- → 写 `AGENT_SNIPPET.md`（给提示词）

SO 作者只实现生成头文件里的符号；**不解析 JSON**。

## 3. 插件 C SDK

头文件：[include/weizhi_plugin.h](../include/weizhi_plugin.h)

- `WeizhiBuf` / `weizhi_buf_alloc` / `weizhi_buf_free`（经 host 表或 libc malloc 约定）
- `weizhi_plugin_init(WeizhiPluginHost *)`（可选）：注册回调入口
- `weizhi_cb_invoke_i32(host, cb_id, v0)` 等（第一期先做 `i32` 单参回调）

符号命名：`weizhi_<plugin>_<fn>`，例如 `weizhi_echo_math_add`。

## 4. 引擎加载与调用

1. `weizhi_set_plugin_dir(engine, dir)` + 内置 loader（或宿主 `ensure` 自己 dlopen 后注册 typed 表）。
2. `host.ensureNative("echo_math")` → 读 `dir/echo_math/manifest.json` → `dlopen` → 缓存符号 → 按类型挂 JS 方法。
3. JS `p.add(20,22)` → 校验 → 调 `int32_t(*)(int32_t,int32_t)`。
4. JS `p.echo_bytes(buf)` → `WeizhiBuf` 借阅 → 返回新 `Buffer`。

**Mock**：`enableNativeMock()` 仍走旧 JSON complete，便于无 .so 的 CI。

## 5. C→JS 回调

```text
SO: weizhi_cb_invoke_i32(cb_id, i)
  → 入队 {cb_id, args}
  → JS 线程 drain → 调用注册的 JS Function
```

第一期：回调参数仅 `i32`（可多个固定 arity 的 helper）。  
复杂回调后续用 IDL 生成 `weizhi_cb_invoke_<sig>`。

## 6. 目录布局（实现）

```text
plugins/echo_math/
  echo_math.idl
  echo_math.c
  manifest.json          # bindgen 生成或手写保底
  CMakeLists.txt         # 产出 libecho_math.so / .dylib
```

Android：编进 `jniLibs` 或测试时拷到 app files，loader 从 `plugin_dir` 加载。

## 7. 待你决策（晚上对齐）

下列项已按**推荐默认**开工；若要改，改文档默认并告知即可。

| ID | 议题 | 推荐默认 | 备选 |
| --- | --- | --- | --- |
| D1 | 通用 libffi vs 按 IDL 生成专用 stub | **按签名生成/手写固定 stub 表**（少 ROM、快） | 链 libffi |
| D2 | 同步调用时 `bytes` 零拷贝借阅 | **允许借阅**；SO 不得在返回后持有指针 | 一律拷贝（更安全更慢） |
| D3 | 回调默认同轮 flush 还是下一 tick | **同轮 flush**（detect 类 API 好写） | 一律异步排队 |
| D4 | 插件崩溃隔离 | **同进程**（第一期）；靠验签 | 独立进程（工期大） |
| D5 | `i64` 在 JS 的表示 | **number**（大整数精度告警写进文档） | 强制 BigInt |
| D6 | Android 插件存放 | **app 私有 `plugin_dir`** + 可从 assets 解压 | 仅 `jniLibs` 预置 |
| D7 | 是否保留 JSON `native_call` | **保留给 mock/调试**；正式插件走 typed | 删除 JSON 路径 |
| D8 | OpenCV | **不进本阶段**；契约按 IDL 预留 `bytes` | 立刻嵌 opencv 官方包 |

有异议的项请直接改推荐列或批注。

## 8. 验收清单（本迭代）

- [x] 文档 + 待决表（本文 §7）
- [x] `weizhi_plugin.h` + `echo_math` 真 SO（桌面 dylib / Android so）
- [x] 桌面：`ensureNative` + `add` / `echo_bytes` / `count_with_cb` 单测
- [x] Android：编进 assets、instrumented 用例已写（需连真机跑 `./scripts/test.sh android`）
- [x] C→JS 回调（`cb(i: i32)` + 同轮 flush）
- [x] Agent 提示词补充 typed 插件用法

### JS 用法（typed）

```js
const p = await host.ensureNative("echo_math");
p.add(20, 22);                           // 42
p.echo_bytes(Buffer.from("hi"));         // Buffer
p.count_with_cb(3, (i) => console.log(i));
```

宿主：`weizhi_enable_plugin_loader(engine, dir)` / Java `enableNativePlugins(dir)`，  
目录形如 `dir/echo_math/{manifest.json,libecho_math.so}`。