# Python 运行时实验（experiment/micropython 分支）

> 状态：**桌面 PoC 已跑通**，未合入主干。分支 `experiment/micropython`。
> 决策见 [DECISIONS.md](DECISIONS.md)「Python experiment」条目。

## 目标与结论

验证 weizhi 引擎以**独立可选 SO** 形态支持 Python 的可行性。PoC 三问的答案：

| 问题 | 答案 |
|------|------|
| strip 后体积多大 | **308 KB**（mac arm64 `libweizhipy.dylib`，`strip -x` 后 314,824 字节；MINIMUM ROM 配置）。核心 `libweizhijni.so` strip 后约 1.1 MB，Python 约为其 28% |
| 限额/超时语义能否对齐 runJs | **能**。14 个桌面测试全绿：timeout / cancelled / memory / stack 四个英文关键词、busy、close-in-run、run 后复用、跨 run 状态隔离 |
| 嵌入工程摩擦在哪 | 两点，均已解决（见下「踩坑记录」）；MicroPython 官方 embed port 质量良好 |

## 已拍板的决策

1. **选型 MicroPython**（v1.29.0，pin 在 `scripts/fetch-deps.sh`）：MIT、固定 GC arena 天然限额、官方 embed 库、Android 移植先例多。备选 pocketpy / CPython(wasm) 均因兼容性或体积放弃。
2. **独立可选 SO，不碰核心**：`libweizhipy.so/dylib` 只依赖 libc/libSystem，不链 `weizhi`/`quickjs`（`otool -L` 验证），导出符号仅 6 个 `weizhi_py_*`。吸取 `archive/wamr-packs` 教训——重能力不进引擎核心，实验失败归档成本为零。
3. **桌面 PoC 范围**：执行 + 内存/栈限额 + 超时 + cancel + console→log 事件。**不含** VFS / import 沙箱 / host functions 桥 / fetch / Android。

## 架构与语义对照

```
宿主 → weizhi_py_open(limits)          # limits: heap_bytes / stack_bytes, open 后不可变
      → weizhi_py_set_log(fn, ud)      # console 事件流
      → weizhi_py_run(py, src, ms)     # 每次运行重建 VM（init→exec→deinit），跨 run 零状态
      → weizhi_py_cancel(py)           # 任意线程；VM hook 轮询
```

| runJs 语义（src/engine.c） | weizhi_py 实现（src/py/py_engine.c） |
|---|---|
| `JS_SetMemoryLimit` 32MB | 固定 GC arena 传入 `mp_embed_init` → 超限 `MemoryError` → `"script exceeded the memory limit"` |
| `JS_SetMaxStackSize` 256KB | `mp_stack_set_limit`（需显式开 `MICROPY_STACK_CHECK`）→ `"script call stack exceeded the limit"` |
| interrupt handler 查 deadline | `MICROPY_VM_HOOK_LOOP`（每轮 VM 分支）→ `mp_sched_keyboard_interrupt()` → `KeyboardInterrupt` → timeout/cancel 文案 |
| cancel atomic flag | 同一 hook 检查 → `"script cancelled"` |
| console.log → log 事件 | `mp_hal_stdout_tx_strn_cooked` 按行缓冲 → `{"v":1,"seq":N,"event":"console","data":{"level":"log","message":...}}`（未设 log 回调则丢弃） |
| output_text = 返回值 JSON | **空串**。Python exec 顶层无值；print 走 log 事件。结果值语义是后续阶段头号议题 |
| 错误关键词英文 | 异常格式化：error = traceback 末行（`ZeroDivisionError: ...`），error_location = 末个 `File ...` 行 |

**与 QuickJS 引擎的已知差异**（文档级承诺）：

- **进程级单实例**：MicroPython VM 状态（`MP_STATE_*`）是进程全局，所有 `weizhi_py_run`（跨引擎实例）用一把全局锁串行化。QuickJS 支持多引擎并行。
- VM hook 不覆盖编译期与 C 扩展内长循环（QuickJS interrupt 同样不覆盖编译，限制对齐）。
- MicroPython 是 Python 3 子集（无 `dataclasses` 等；本配置已开 float/double、f-string）。

## 构建与测试

```bash
./scripts/fetch-deps.sh            # 含 third_party/micropython（v1.29.0，pin）
./scripts/build-python-embed.sh    # 生成 build/micropython_embed（qstr 预生成；--force 重建）
./scripts/test.sh                  # 门禁；embed 源集存在时自动编 weizhipy + 跑 weizhi_py_tests
# 缺 third_party/micropython 时 CMake 输出 skip，主干构建不受影响
```

产物：`build/libweizhipy.dylib|so`、`build/weizhi_py_tests`（14 用例，风格同 `tests/test_engine.c`）。

关键文件：`include/weizhi_py.h`（公共 API）、`src/py/py_engine.c`（引擎）、`src/py/mpconfigport.h`（裁剪 + VM hook）、`src/py/py_mphalport.c`（stdout→log）、`scripts/build-python-embed.sh`、`tests/test_py.c`。

## 踩坑记录（后续恢复实验时先读）

1. **qstr 生成配置必须与编译配置完全一致**。embed 源集的 `qstrdefs.generated.h` 由 make 扫描源码生成，预处理时生效的 `mpconfigport.h` 决定哪些 `MP_QSTR_*` 进表。最初生成用 examples 默认配置（float 关），编译开 float → `MP_QSTR_float` 未定义连锁报「incomplete type」。
   **解法**：`build-python-embed.sh` 把 `src/py/mpconfigport.h` 复制进 scratch 目录 `build/py-embed-work/`，从那里 `make`（embed.mk 的 `-I.` 让我们的配置优先生效）——生成与编译同源。改配置后必须重新生成（脚本用 stamp 自动判断）。
2. **`MICROPY_VM_HOOK_LOOP` 必须自带分号**：vm.c 里裸语句展开。
3. **MINIMUM ROM 级别默认关 `MICROPY_STACK_CHECK`**（mpconfig.h 注释原话 "Not checking means segfault on overflow"）：不开就是无限递归真·栈溢出 SIGSEGV。同理要显式开 `MICROPY_KBD_EXCEPTION`（timeout/cancel 依赖）。
4. `mp_embed_exec_str` 的异常只打印不返回，拿不到 ok/error——自研 exec（`nlr_push` + `mp_parse_compile_execute` 同款四步），异常收集到 vstr 再拆行。MemoryError/KeyboardInterrupt 走零分配固定文案（OOM 后格式化 traceback 可能再 raise）。

## 后续阶段路线（未承诺）

- **P1 结果值语义**：output_text 不为空的方案（如末条表达式求值 / 显式 result 协议）——agent 场景的关键缺口。
- **P2 VFS + import 沙箱**：`open()`/import 重定向到 weizhi VFS 回调，与 JS 引擎同款 workspace 语义。
- **P3 host functions 桥**：`weizhi.host.call(name, json)` 复用已注册宿主函数（caps 全量复用，宿主零改动）。
- **P4 Android `:weizhi-python` AAR**：仿 `:weizhi-video`（jniLibs 注入 + `useLegacyPackaging`），NDK 交叉编译 MicroPython。
- **P5**（可选）：IDL bindgen 生成 Python 绑定；`weizhi:` 包系统接入。
