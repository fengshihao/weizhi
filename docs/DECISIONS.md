# 微执：实现过程中的决定

人离开时授权按已对齐的设计继续做。下面是代码里已经钉死、并且由测试验收的规格。

## 产品边界

- 仓库在 `/Users/fengshihao/Work/weizhi`，和 Agent1 并列，不放进 Agent1。
- 协议用 Apache-2.0。QuickJS 仍是上游 MIT，不能改。WAMR 仍是 Apache-2.0 加 LLVM 例外。
- 不依赖、不整仓分叉 quickjs-kt。只在自己的发动机里编译 Bellard QuickJS。
- Agent 只有一个入口：跑一段 JS。能力包在 JS 里用 `loadPack("名字")` 调用。`loadScript("文件.js")` 用来装纯 JS 库。
- 官网、完整 README、墨览那套门户这次不做。
- 外接包装用 **Java + JNI**，不引入 Kotlin。异步 I/O 用 `ExecutorService` 线程池。
- `Buffer` / `path` / `require`·`import` / Promise 循环等内置对象：PC 与 Android **同一套 C 实现**；平台只换宿主接线。

## 内存

一台发动机的默认上限：

| 项目 | 默认 | 含义 |
|---|---|---|
| JS 堆 | 8 MB | 脚本里的字符串、对象都算在里面。超过就停，错误里有「内存」 |
| JS 栈 | 256 KB | 递归太深就停，错误里有「栈」 |
| 同时装载的能力包 | 4 个 | 再装就停，错误里有「太多」 |
| 登记的手机功能 | 32 个 | 超出 `addFunction` 返回失败 |
| 每个包的运算栈 | 64 KB | 包内函数调用用 |
| 每个包的内部堆 | 64 KB | 包自己 `malloc` 用 |
| 每个包声明的线性内存 | 最大 2 MB | 包文件里要求的起步内存更大就拒绝，错误里有「内存」 |
| 单次 fs 读写 | 1 MB | 超过失败，错误里有「太大」 |

这些数可以通过 `WeizhiLimits` 改小，测试就是这么做的。0 表示用默认。

开发构建使用系统内存分配，不用一块预先占死的大池子。关掉发动机后内存还给系统。手机上如果以后要改成固定池，再加测试。

脚本的返回值一律变成文字（JSON）。图片字节由脚本交给宿主函数或 `Buffer`，`runJs` 不再另吐一份字节。

## 线程与异步

- **JS 只在一条线程上跑**（调用 `runJs` 的线程）。禁止多线程同时进入同一台发动机的 QuickJS。
- 同步宿主函数（默认 `addFunction`、`fs.*Sync`）仍在 JS 线程当场跑完。
- **异步宿主**（`fs.promises`、日后 `fetch`）：在线程池执行；完成后把结果放进完成队列并唤醒 `runJs`；**禁止**在池线程直接调 QuickJS。
- 一台发动机同时只能跑一段脚本。
  - 同一条线程再调 `runJs`：失败，错误里有「再次」。
  - 另一条线程再调 `runJs`：失败，错误里有「正忙」。
- 脚本还在跑时 `close` 返回 -1，发动机不拆掉，脚本结束后仍可运行。
- 进程里 WAMR 只能初始化一次。两台发动机同时创建时，用一把锁只保护这次初始化。

## Promise / async / await

- 语法与 `Promise` 可用。
- `runJs` 若得到 Promise，在超时内排空 microtask、到期 `setTimeout`、处理完成队列，直到该 Promise settle。
- fulfilled → 输出兑现值的 JSON；rejected → 失败。
- `setTimeout` / `clearTimeout` 仅在本次 `runJs` 内存活。
- 不做跨多次 `runJs` 的常驻事件循环。

## 时间

- `runJs` 的超时参数为 0 时，默认 3000 毫秒。
- 负数表示不限时。
- 超时后错误里有「时间」，发动机还可以再跑下一段。

## Node 风格内置 API（第一期）

- 主推 `import fs from "fs"`；兼容 `require("fs")`。仅内置名，无 npm。
- 内置：`fs`（含 `fs.promises`）、`path`、`buffer`、`process`（只读子集）、`console`。
- `Buffer` / `path` / 模块表 / `fs` 皮：C 实现。
- 文件系统：宿主沙箱根；相对路径；逃逸失败，错误里有「路径」或「权限」。
- 未实现模块：`require("child_process")` 等失败，错误里有「不支持」。

## 能力包文件

- 文件夹由 `setPackFolder` 指定。JS 只写名字，不写路径。
- `loadPack("add")` 读取 `add.wasm`。
- 名字只允许字母、数字、点、下划线和横线。带斜杠或 `..` 直接失败，错误里有「名字」。
- 若只有 `add.aot`、没有 wasm：当前开发构建失败，错误里有「aot」。手机正式构建再改成只留 AOT，到时这条测试要改。
- 单个能力包文件超过 16 MB 就拒绝。开发构建打开 WAMR 解释器、关掉 AOT 和 SIMD。
- pack 根与工作区（fs）根分开。

## 日志

一行一条 JSON。开发阶段记下脚本正文、宿主函数的参数和返回。字段用英文：`run_id`、`seq`、`event`。事件名包括 `run_js_start`、`host_call`、`load_pack`、`load_script`、`console`。给人看的失败原因用中文。

## 体积

- Android 以 Release + strip 后的 so/可执行为口径。
- 第一期相对增强前 strip 基线，引擎增量目标 ≤ 256KB；`Buffer` 只做 utf8/base64/hex 等子集。

## 依赖怎么放

`third_party/quickjs` 和 `third_party/wamr` 用 `scripts/fetch-deps.sh` 浅克隆，不提交进 git。本机已经克隆过。
