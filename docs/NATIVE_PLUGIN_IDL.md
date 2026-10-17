# Native 插件：IDL / 近原生绑定

本文落实「SO 定制到底」方案：AI 友好 JS API、接近原生性能、`Buffer` 传二进制、C→JS 回调。  
与 [HOST_ABI.md](HOST_ABI.md) 目录/验签/生命周期配合；**调用面**不再以 JSON RPC 为主。

状态（2026-09-24）：**已落地**；桌面 + 真机 `./scripts/test.sh android` 通过。  
IDL v2（`str` / `cb(s: str)` / `cancel_poll`，随 `plugins/echo_video` 通道 PoC）于 2026-10-17 落地，
`WEIZHI_HOST_ABI_VERSION` 升为 **2**。  
§7 决策项已按推荐默认**锁定**（产品确认）。

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

### 2.1 类型子集

v1（HOST_ABI 1）：

| IDL | JS | C |
| --- | --- | --- |
| `i32` | number（截断为 int32） | `int32_t` |
| `i64` | number（大整数有精度损失；见 D5） | `int64_t` |
| `f64` | number | `double` |
| `bytes` | `Buffer` | `WeizhiBuf { data, len }` |
| `cb(...)` | function | `uint32_t cb_id`（宿主/引擎分配） |
| `void` | `undefined` | `void` |

v2 增补（HOST_ABI 2，2026-10-17，随 `echo_video` 落地）：

| IDL | JS | C | 说明 |
| --- | --- | --- | --- |
| `str` | string | `const char *`（参数）/ `char *`（返回） | 返回值为 malloc 的 UTF-8，引擎拷贝后 `free()`；典型用途是 JSON |
| `cb(s: str)` | function（收到解析后的 JSON 对象） | `uint32_t cb_id` + `host->cb_invoke_str(engine, cb_id, json)` | **同调用作用域**：回调仅在该导出调用返回前有效，drain 后引擎自动释放槽位（不占 64 槽预算） |
| `i64` 句柄 | number | `int64_t` | **句柄约定**：会话/资源句柄用 `i64` 传递，`0` 恒为非法句柄；非法句柄由插件在返回 JSON 中报 `error` |

配套宿主服务（`WeizhiPluginHost` 追加字段，向后兼容）：

- `cancel_poll(engine) -> int`：长循环插件**必须**每帧/每批次轮询；返回 1 时应尽快清理并返回。
  引擎保证：取消（`weizhi_cancel`）后 `runJs` 必以含 `cancelled` 的错误收场
  （含同步 C 调用期间取消的场景——`weizhi_await_value` 在 drain 后复检）。
- 旧引擎（HOST_ABI 1）加载 `min_host_abi 2` 的插件会按既有规则拒绝
  （`native incompatible: min_host_abi 2 > host 1`）。

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

1. `weizhi_enable_plugin_loader(engine, dir)` / Java `enableNativePlugins(dir)`。
2. `host.ensureNative("echo_math")` → 读 `dir/echo_math/manifest.json` → `dlopen` → 缓存符号 → 按类型挂 JS 方法。
3. JS `p.add(20,22)` → 校验 → 调 `int32_t(*)(int32_t,int32_t)`。
4. JS `p.echo_bytes(buf)` → `WeizhiBuf` 借阅 → 返回新 `Buffer`。

**Mock**：`enableNativeMock()` 仍走旧 JSON complete，便于无 .so 的 CI（D7）。

## 5. C→JS 回调

```text
SO: weizhi_cb_invoke_i32(cb_id, i)
  → 入队 {cb_id, args}
  → JS 线程 drain → 调用注册的 JS Function
```

第一期：回调参数仅 `i32`；**同轮 flush**（D3）。  
复杂回调后续用 IDL 生成 `weizhi_cb_invoke_<sig>`。

## 6. 目录布局（实现）

```text
plugins/echo_math/
  echo_math.idl
  echo_math.c
  generated/manifest.json
  generated/weizhi_echo_math_api.h
```

Android：构建时拷到 androidTest assets，测试解压到 app 私有 `plugin_dir` 再 `enableNativePlugins`（D6）。

## 7. 已锁定决策（2026-09-24 确认）

| ID | 议题 | 锁定 |
| --- | --- | --- |
| D1 | libffi vs 专用 stub | **按签名手写/生成固定 stub 表**（不链 libffi） |
| D2 | `bytes` 零拷贝 | **同步允许借阅**；SO 返回后不得再持有指针 |
| D3 | 回调时机 | **同轮 flush** |
| D4 | 崩溃隔离 | **同进程**（靠验签）；独立进程以后再说 |
| D5 | `i64` | **JS number**（大整数精度在文档/提示词里说明） |
| D6 | Android 存放 | **app 私有 `plugin_dir`**，可由 assets 解压 |
| D7 | JSON `native_call` | **保留给 mock/调试**；正式插件走 typed |
| D8 | OpenCV | **本阶段不做整库**；用 IDL `bytes` 等契约后续接薄封装 |
| D9 | v2 回调作用域（2026-10-17） | `cb(s: str)` **同调用作用域**，drain 后释放槽位；v1 `cb(i: i32)` 行为不变 |
| D10 | v2 取消语义（2026-10-17） | 长任务用 `cancel_poll` 轮询；引擎在 microtask drain 后复检取消，同步 C 调用期间的取消不再漏检 |

## 8. 验收清单

- [x] 文档 + 决策锁定（本文 §7）
- [x] `weizhi_plugin.h` + `echo_math` 真 SO（桌面 dylib / Android so）
- [x] 桌面：`ensureNative` + `add` / `echo_bytes` / `count_with_cb` 单测
- [x] Android：`./scripts/test.sh android` 真机通过
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
