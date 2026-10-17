# 视频剪辑能力：调研与架构提案（feat/video-editing）

日期：2026-10-17。状态：**调研完成，未实现**。本文是 `feat/video-editing` 长期分支的第一份产出，
后续设计/实现以本文为基线演进。

结论先行：**视频剪辑可以进 Weizhi 生态，但 heavy 代码必须以签名插件 SO 形态存在，JS 只做编排；
核心引擎 `libweizhijni.so` 保持 ≤1.5MB strip 门禁不动。** 用户提出的 Media/GPU/Filter/Compositor
分层方向正确，但落地方式需要按本仓库既有架构（Host ABI + IDL 插件 + Weizhi Package）重塑，
其中 GPU 直通 API、跨 runJs 的常驻事件循环、Surface 预览三项与当前引擎模型冲突，需要降级或后置。

---

## 1. 现状勘察（事实，带出处）

### 1.1 引擎执行模型：一次 runJs 一个作业

- 唯一入口 `weizhi_run_js_ex`（`src/engine.c:452`）。QuickJS 严格单线程：状态机
  `ST_IDLE/ST_RUNNING/ST_CLOSED`，同线程重入报 `again`，跨线程报 `busy`。
- **内嵌事件循环** `weizhi_await_value`（`src/engine.c:800`）：drain microtask → 到期 timer →
  应用完成队列 → drain 插件回调队列；无线程通过 `pthread_cond_signal` 唤醒（`weizhi_wake`，
  `src/engine.c:592`）。**事件循环只在 runJs 进行中存在**；`DECISIONS.md` 明确无跨 runJs 的
  常驻事件循环、无 `setInterval`。
- 异步宿主操作约定（`docs/HOST_ABI.md` §3）：宿主拿 `request_id`，任意线程稍后调
  `weizhi_complete / weizhi_complete_fetch / weizhi_complete_native`（`include/weizhi.h:99-111`），
  引擎在 JS 线程 resolve Promise。
- 关键容量（`src/engine_internal.h:15-26`）：`pending[64]`（在途异步上限 64）、timers 64、
  插件回调槽 64、单插件导出 32 个、单函数参数 8 个。
- 超时：默认 10 分钟，负数不限时（`include/weizhi.h:115`）；等待 I/O 计入超时；
  `weizhi_cancel` 可从其他线程中止（错误含 `cancelled`）。

**对视频的含义**：整段导出（几秒到几分钟）在**单次 runJs 内**完成是自然形态；
跨多次 runJs 的交互式预览（常驻回调、Surface 渲染）**不匹配**当前模型。

### 1.2 原生插件系统：heavy 能力的唯一合法通道

- IDL（`docs/NATIVE_PLUGIN_IDL.md`）：`plugins/<name>/<name>.idl` →
  `scripts/weizhi-bindgen.py` 生成 `manifest.json` + C 头。已落地并真机验证。
- 类型子集 v1：`i32 / i64 / f64 / bytes / cb(i:i32) / void`。锁定决策 D1–D8：
  手写 stub 不链 libffi；**同步 bytes 借阅零拷贝**（SO 返回后不得持指针）；
  回调**同轮 flush**、仅单 `i32` 参数；同进程靠验签隔离。
- 加载：`host.ensureNative(name)` → typed loader `dlopen` + manifest 解析
  （`src/plugin_loader.c:360` `load_one`）；生命周期 refcount + 30s idle TTL `dlclose`
  （`docs/HOST_ABI.md` §5）。插件禁止 `JNI_OnLoad`、不碰 QuickJS（`include/weizhi_plugin.h`）。
- 插件 SO **独立于核心引擎 SO**：目录下发、验签、按需加载——
  这正是「大体积视频库不进 APK、不进核心 SO」的通道。

**对视频的含义**：FFmpeg（数 MB～数十 MB）或 MediaCodec 封装应以 `weizhi_video` 插件 SO 形态存在；
但 IDL v1 的类型面（无句柄、无异步函数、cb 仅 i32、导出≤32）**不足以表达视频管线**，见 §3。

### 1.3 Caps：同步 JSON 控制面，非数据面

- Java 桥 `WeizhiEngine.setHostCall` 安装**唯一同步入口 `__caps(args)`**（`java/com/weizhi/WeizhiEngine.java:203`），
  JS prelude 定义 `globalThis.android` 等（`java/com/weizhi/platform/PlatformScripts.java`）。
- caps 调用是 **JS 线程上的同步 JSON 往返**；桌面 `mac`/`linux` 同形 stub，缺能力报 `unsupported`。
- 历史教训：`android.media.resize` 曾进 caps，后在 87c03b4 移除（图片缩放归 Agent1）；
  7a946d3 曾把图片缩放做成插件。**端上媒体处理的既定方向是「宿主/插件，不进 caps 核心」**。

### 1.4 Java 宿主与线程

- 异步 I/O 走 `ExecutorService` 有界池（默认 16 线程，`WeizhiEngine.java:81-103`），
  完成后从池线程调 `nativeComplete*` 回投 C 队列——**永不在池线程进 QuickJS**。
- `Cleaner` 兜底回收 native 句柄（`WeizhiEngine.java:44`）。

### 1.5 体积与 CI 门禁

- `scripts/size-check.sh`：`libweizhijni.so` strip 后 **≤1.5MB**（基线 ~1.1MB）。
  这是「视频代码绝不进核心 SO」的硬约束。
- CI（`.github/workflows/ci.yml`）目前仅 desktop job（`WEIZHI_SKIP_ASAN=1 ./scripts/test.sh`）；
  android instrumented（`android/app/src/androidTest/`）本地真机按需跑。
- 测试布局：C 测试 `tests/test_engine.c`；纯 Java `tests/java/`；真机 `*InstrumentedTest.java`。
  新行为必须有测试（`AGENTS.md`）。

---

## 2. 对用户提案的逐项评估

用户提案（Media 源/解码/GPU/Filter/Compositor/Encoder/Muxer/Audio/Event/Resource 分层）
的**原则层**（原子化、句柄化、异步化、零拷贝、可组合）与本仓库哲学完全一致；
**API 层**需要按落地通道重映射：

| 提案模块 | 评估 | 落地方式 |
| --- | --- | --- |
| `Media.open/getTracks` 元数据 | ✅ 直接可行 | 探测属轻操作：插件同步 fn 返回 JSON 字符串即可 |
| `Decoder.decode/seek` 逐帧 | ⚠️ 需改形态 | 见 §3.1：句柄 + 同步 step，或 IDL v2 异步 |
| `FrameBuffer` 跨 JS/GPU | ⚠️ 收窄 | 帧**不进 JS**：decode→filter→encode 在插件内闭环；JS 只拿 i64 句柄与元数据 |
| `GPU.createShader/dispatch` 直通 | ❌ 不进 JS 表面 | GPU 细节封装在插件内；Shader 作为**字符串参数**下发（`filter shader="..."`），由插件编译 |
| `Filter.custom` | ✅ 方向正确 | 对应「JS 下发 GLSL/MSL + uniforms」，插件内 GPU 执行 |
| `Compositor/Timeline` 时间线 | ✅ JS 层实现 | 纯 JS（`weizhi:video` 包）建模，编译成插件可执行的图描述 |
| `Encoder/Muxer` | ✅ 插件 fn | MediaCodec/MediaMuxer 或 FFmpeg，全在插件 SO 内 |
| `Audio resample/mix` | ✅ 插件 fn | 同上；PCM 段如需进 JS 调试，走 `bytes`（小数据拷贝可接受） |
| `timeline.on("progress")` 事件 | ⚠️ 单作业内可行 | runJs 内 Promise/cb 即可；**跨 runJs 常驻事件不可行**（§1.1） |
| 预览到 Surface | ❌ 后置 | 引擎无 UI/Surface 概念；归宿主 App（Agent1）或未来 caps 扩展，见 §5 |
| `job.pause/resume` | ⚠️ 降级 | cancel 已有（`weizhi_cancel`）；pause/resume 后置 |

核心修正：**「大数据走 Buffer 句柄」在 Weizhi 语境下收窄为「帧数据根本不出插件」**。
JS 编排的是**管线图**（哪些片段、什么滤镜、什么参数），不是帧本身；唯一例外是缩略图/
封面等小产物，按 `bytes` 拷贝出（受 `fs_io_bytes` 级别约束即可）。

### 2.1 安卓系统 API 是主线，不是自研 FFmpeg（2026-10-17 与维护者对齐）

视频处理**不写软解/软编算法**，全部调用安卓系统媒体栈，两条可达路径：

| 路径 | 说明 | 结论 |
| --- | --- | --- |
| **A. NDK 原生媒体 API（`libmediandk`）** | `AMediaExtractor`（解复用）、`AMediaCodec`（硬解/硬编，与 Java `MediaCodec` 是同一套底层硬件编解码器）、`AMediaMuxer`（复用）、`AImageReader`（GPU 帧出口，API 24+）。C 插件直接链接调用，无需 JNI | **主线**。完全符合插件规则（纯 C、禁 `JNI_OnLoad`），可验签目录下发、零第三方体积；minSdk 26 > API 21/24 门槛，全量可用 |
| B. Java 层 `MediaCodec`（放 `:caps` 或宿主） | 能用 Surface 模式等全部 Java 面 | 否决为数据面：caps 桥是同步 JSON（§1.3），长导出会阻塞 JS 线程且需新造异步通道；Java 代码打进 AAR，更新必须发版，违背「不更新 APK 下发新能力」 |

因此「纯 C」在本方案里只出现在两处，都不是自研编解码：

1. **插件胶水层**：IDL 插件 ABI 本身是 C（`include/weizhi_plugin.h`），这层是薄封装，
   内部转调 `libmediandk`——相当于给系统媒体栈套一层可验签下发的 C 门面；
2. **P0 概念验证插件**：纯 C 生成确定性合成视频流，仅作桌面 CI 测试替身
   （桌面没有 MediaCodec，用它验证 IDL v2 通道），不是产品形态。

GPU 滤镜同样走系统 API：EGL 离屏 + GLSL（`libEGL`/`libGLESv3`，全版本可用）为主线；
AGSL `RuntimeShader` 需 API 33+，只作高版本可选加速。

---

## 3. 差距清单（Gap analysis）

### 3.1 IDL v1 表达力不足（主要差距）

视频管线以「会话 + 句柄 + 步进/提交」为自然形态，v1 缺：

1. **句柄返回**：`open_session(...) -> i64`。v1 的 `i64` 返回其实可用（D5 精度损失在
   会话 id 场景可接受），但需要约定「句柄即 i64」的编码规范与非法句柄错误关键词。
2. **多参数回调**：进度需要 `(stage, percent)` 乃至 JSON 字符串；
   v1 只有 `cb(i: i32)`（`weizhi_plugin.h:35`）。需要 `cb(json: str)` 或 `cb(f64, i32)`。
3. **异步导出**：整段导出若做成同步阻塞调用（JS 线程阻塞在插件内几分钟），
   cancel 依赖 `weizhi_cancel` 的 interrupt handler——interrupt 只在 JS 执行中触发，
   **阻塞在 C 里时 interrupt 不生效**。这是同步长调用的真实风险，
   需要插件内部定期检查取消（宿主置位、插件轮询）或引入 IDL v2 异步 fn。
4. **导出数量**：单插件 ≤32 导出。视频 API 面更大——按「多逻辑插件」
   （`weizhi_video_demux` / `weizhi_video_enc`…）拆分，或提高上限（倾向前者，不动引擎）。
5. **无字符串参数类型**：管线图/Shader/路径都是字符串；v1 只能借道 `bytes`。
   建议 v2 增加 `str`（或约定 bytes 即 UTF-8，成本最低）。

### 3.2 引擎侧需要的小改动（若走 IDL v2）

- `weizhi-plugin.h` 增加 `cb_invoke_json` / `cb_invoke_f64_i32`；
  `engine_internal.h` 的 `WeizhiCbEvent` 扩展载荷（现在是 `{cb_id, v0}` 定长，
  `src/engine_internal.h:57-61`）。
- 取消传播：给 `WeizhiPluginHost` 加 `cancel_poll(engine) -> i32`，
  长任务插件自查（不引入跨线程 QuickJS 访问，仅读原子量——引擎已有 `cancel_requested`）。
- 预计引擎侧增量在几 KB 量级，满足 §1.5 门禁。

### 3.3 无需改引擎的部分（明确边界）

- 帧零拷贝：decode→filter→encode 全在插件进程内（同进程、同 SO 或同插件族），
  引擎与 JNI 不感知帧——`pending[]` 64 槽、32MB JS 堆都不构成瓶颈。
- fs：输入输出文件路径经沙箱解析后传**绝对路径**给插件（插件自行 fopen），
  或宿主把媒体文件放在 fs_root 下由 JS 传相对路径 + 宿主侧解析。**需决策**（§6-Q3）。

---

## 4. 推荐架构（三层）

```
┌────────────────────────────────────────────────────┐
│ L3  weizhi:video JS 包（可下发、可热更）             │
│     Timeline / Clip / Filter / Transition DSL       │
│     纯 JS 建模 + 参数校验，编译成图 JSON             │
├────────────────────────────────────────────────────┤
│ L2  weizhi_video* 签名插件 SO（目录下发，验签）      │
│     Android NDK 系统媒体栈（§2.1 路径 A）：          │
│       AMediaExtractor / AMediaCodec / AMediaMuxer   │
│       （硬编硬解，零第三方体积）；ffmpeg 后置可选    │
│     GPU 滤镜: EGL 离屏 + GLSL；AGSL(33+) 可选       │
├────────────────────────────────────────────────────┤
│ L1  引擎（现有 + IDL v2 小增量）                     │
│     ensureNative / typed stub / cb 队列 / cancel     │
└────────────────────────────────────────────────────┘
```

- **新滤镜 = JS 下发**（Shader 字符串 + uniforms 参数），满足「不更新 APK 上新能力」目标；
- **新容器/编码器 = 目录上架新插件 SO**，走 HOST_ABI §4 零发版通道；
- **JS 导出脚本形态**：单次 runJs 内 `await video.export(timeline, opts)`，
  Promise 内部由插件 cb 报进度（同轮 flush，D3 语义正好匹配）。

### 分阶段路线（建议）

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 | IDL v2（str、句柄规范、cb 扩展、cancel_poll）+ `echo_video` 概念验证插件（纯 C 合成流，仅桌面 CI 测试替身，见 §2.1） | 桌面 test.sh 绿；真机加载成功 |
| P1 | Android 硬件管线（NDK `libmediandk`）：`AMediaExtractor`→`AMediaMuxer` 直通转封装 + 剪辑（掐头去尾/拼接，无重编码） | 真机 instrumented：输出 mp4 时长/轨道断言 |
| P2 | 重编码导出：`AMediaCodec` 解码→（CPU/EGL 变换）→`AMediaCodec` 编码→`AMediaMuxer`；进度 cb + cancel | 真机黄金样本 hash 断言 |
| P3 | `weizhi:video` JS 包：Timeline/Filter DSL；GPU 滤镜（AGSL/EGL 离屏）；LUT/转场 | JS 用例测试 + 采样帧像素断言 |
| P4 | 预览协作面（宿主 Surface，与 Agent1 对齐接口）；ffmpeg 可选插件 | 跨仓协议文档 |

P1 的「无重编码剪辑（remux）」是性价比最高的第一刀：纯 MediaExtractor/MediaMuxer，
不碰 codec，代码量小，真机即可验证端到端通道。

---

## 5. 明确不做 / 后置

- **GPU compute 直通 JS**（`gpu.dispatch` 形态）：分层违规（引擎不应感知 GL/Vulkan 上下文），
  GPU 只存在于插件实现内部。
- **跨 runJs 常驻预览流**：需要常驻事件循环，引擎模型不支持；预览归宿主 App。
- **引擎内 FFmpeg**：体积门禁（§1.5）直接否决；只能做独立插件 SO。
- **Audio DSP 全家桶**：P3 后按需；PCM 处理先走 `bytes` 拷贝（音频数据量小得多）。

## 6. 待拍板的开放问题

- **Q1** IDL v2 是否引入显式 `handle` 类型（还是 i64 约定 + 文档规范）？
  倾向：i64 约定，零引擎改动的一部分。
- **Q2** 长导出的取消语义：插件轮询 `cancel_poll`（简单）vs 异步 fn + complete（完整但动引擎）？
  倾向：P0 先轮询，真机验证体验后再定。
- **Q3** 媒体文件路径授权：插件直接吃沙箱绝对路径，还是复用 fs_root 相对路径语义？
  涉及安全边界（插件不受引擎沙箱约束），需要在 P0 定。
- **Q4** 插件拆分粒度：`weizhi_video` 单插件（需提高导出上限）vs 按功能拆多插件？
  倾向按功能拆，不动 32 上限。
- **Q5** 与 ROADMAP 的关系：视频不在当前路线图（近期焦点是 Agent 集成）；
  本特性按长期分支推进，合入主干前需在 `docs/ROADMAP.md` 立项。

## 7. 测试策略（对齐 AGENTS.md）

- 每阶段必须有自动化测试：P0 → `tests/test_engine.c` + `tests/java/`；
  P1+ 真机 → `android/app/src/androidTest/java/com/weizhi/Video*InstrumentedTest.java`。
- 概念验证插件用**纯 C 合成视频流**（生成确定性帧序列），桌面 CI 可全量跑，
  不依赖媒体文件与 MediaCodec——与 `echo_math` 插件的测试形态一致。
- 真机断言优先用**确定性可校验量**：输出时长、轨道数、采样帧的像素 hash、文件大小区间，
  避免脆弱的视觉比对。
