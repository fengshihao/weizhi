# 视频剪辑能力：调研与架构提案（feat/video-editing）

日期：2026-10-17。状态：**P0–P3c 已落地并合入主干**（2026-10-19 ROADMAP 立项，见 §6-Q5 与 [ROADMAP.md](ROADMAP.md)）。
本文是 `feat/video-editing` 长期分支的第一份产出，后续设计/实现以本文为基线演进。

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
- 插件 SO **独立于核心引擎 SO**。⚠️ 但**交付通道在安卓已断**：targetSdk 29+ / Android 10 起
  linker 对 app 可写目录执行 W^X 管控，「下载到私有目录再 dlopen」（HOST_ABI §4 的零发版通道）
  在安卓不可行；桌面 CLI 不受影响。安卓改走**内置独立 SO**（见 §2.2）。

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

### 2.2 交付：安卓内置独立 SO，ensureNative 保留（2026-10-17 与维护者对齐）

安卓 10+/targetSdk 29+ 的 linker W^X 管控使「运行时下载 SO → 私有目录 dlopen」不可行
（§1.2）；桌面 CLI 不受影响。决策：

1. **内置，但独立 SO**：`libweizhivideo.so` 打进 AAR（建议独立 Gradle 模块，如 `:weizhi-video`），
   随安装落在 `nativeLibraryDir`，loader 按 soname `dlopen`（`System.loadLibrary` 同源路径，
   合法加载）。**不并入 `libweizhijni.so` 核心**：`size-check` 的 1.5MB 门禁只管核心 SO；
   视频 SO 单独立预算（建议 ≤512KB strip）。不需要视频能力的宿主不依赖该模块，零体积。
   正因为 §2.1 选了系统媒体栈，wrapper 估计仅 100–300KB（P1 remux + P2 转码），
   +EGL/GL 滤镜再 +100–200KB——「几百 K」量级内得起；若当初选 FFmpeg 此路不通。
2. **ensureNative 不废弃，语义升级**：JS 侧 `await host.ensureNative("video")` 不变；
   安卓端解析改为「内置插件表（soname + 内嵌 manifest）优先」，下载/验签目录仅桌面保留。
   IDL、typed stub、refcount、L3 JS 包全部复用；将来侧载渠道或政策变化可恢复下发。
3. **「新能力 JS 下发」愿景的边界**：新滤镜/新编排 = JS 下发 Shader 字符串与参数
   （插件接口通用），**仍可热更**；只有新容器格式/新编码器这类 native 变化才需发版。
4. 内置插件的 manifest 来源（P1 已定）：SO 导出 `weizhi_plugin_manifest()` 返回
   manifest JSON（bindgen 生成 `weizhi_<name>_manifest.h`，见 NATIVE_PLUGIN_IDL §2.2）。

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
│ L2  weizhi_video 插件 SO（安卓内置进 AAR，独立于核心 │
│     SO；桌面走目录，见 §2.2）                        │
│     Android NDK 系统媒体栈（§2.1 路径 A）：          │
│       AMediaExtractor / AMediaCodec / AMediaMuxer   │
│       （硬编硬解，零第三方体积）；ffmpeg 后置可选    │
│     GPU 滤镜: EGL 离屏 + GLSL；AGSL(33+) 可选       │
├────────────────────────────────────────────────────┤
│ L1  引擎（现有 + IDL v2 小增量）                     │
│     ensureNative / typed stub / cb 队列 / cancel     │
└────────────────────────────────────────────────────┘
```

- **新滤镜 = JS 下发**（Shader 字符串 + uniforms 参数），**不更新 APK 即可上新**；
- **新容器/编码器**：安卓需随 AAR 发版（§2.2 内置决策的代价）；桌面仍可目录上架插件 SO；
- **JS 导出脚本形态**：单次 runJs 内 `await video.export(timeline, opts)`，
  Promise 内部由插件 cb 报进度（同轮 flush，D3 语义正好匹配）。

### 分阶段路线（建议）

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| P0 ✅（2026-10-17） | IDL v2（str、i64 句柄约定、`cb(s: str)`、cancel_poll，`WEIZHI_HOST_ABI_VERSION`→2）+ `echo_video` 概念验证插件（纯 C 合成流，桌面 CI 测试替身，见 §2.1）。落地含两项顺手修复：引擎 drain 后取消复检（§P0 记录）、`zip_api` read_u32 UB + ASan 门禁环境变量 | 桌面 `./scripts/test.sh` 全绿（含 ASan）；真机 18/18 用例通过（仅主干既有的 `workspaceRelativeImport` 在 Android 16 真机失败，与本特性无关） |
| P1 ✅（2026-10-17） | 内置插件注册（`weizhi_register_bundled_plugin` + SO 内嵌 `weizhi_plugin_manifest`，§2.2 落地）；`:weizhi-video` 模块（独立 AAR，`libweizhivideo.so` **61KB**，远低于 512KB 预算）；`weizhi_video` 插件：NDK `AMediaExtractor`/`AMediaMuxer` 无重编码 remux——`probe`（轨道/时长元数据）+ `open_job/run_job/close_job`（掐头去尾 + 拼接，mime 映射跨 clip 复用 muxer 轨道，进度 cb + 取消自清理） | 桌面全量（含 ASan）绿；真机 `WeizhiVideoInstrumentedTest`：MediaCodec 生成确定性 all-intra 夹具 → JS 端到端 probe/trim(200–800ms)/concat → 输出时长/轨道断言通过 |
| P2 ✅（2026-10-17） | `weizhi_transcode` 重编码导出插件（Q4 拆分的第二个逻辑插件，`libweizhitranscode.so` 82KB）：`AMediaCodec` 解码 → **encoder 输入 Surface 隧道**（帧零拷贝、不经 CPU/YUV 布局）→ `AMediaCodec` 编码 → `AMediaMuxer`；掐头去尾经 `releaseOutputBufferAtTime` 改写 PTS（纳秒），音频独立 extractor 直通不重编码；码率/帧率可配；缩放守卫报 `unsupported: scaling (P3)`；取消自清理删输出。`WeizhiVideo.install` 注册双插件（remux+transcode，共 144KB） | 真机 `WeizhiTranscodeInstrumentedTest` 3/3：剪辑+拼接重编码时长断言、缩放守卫、长任务取消（<15s 退出且无残留文件）；全量 22/22；桌面含 ASan 绿。顺手修复：`weizhi_video` 的 `AMediaFormat` 所有权泄漏（NDK `getTrackFormat`/`getOutputFormat` 需调用方 delete）；`build-android.sh` 平台级别对齐 minSdk 26 |
| P3a ✅（2026-10-17） | `weizhi:video` JS 编排包（`video@0.1.0`，随 `:app` assets 内置、`syncBundled` 安装）：`buildJob(spec)` 把时间线 DSL（clips 剪辑点 / output / bitrate·width·height·fps / filters 预设或自定义 `{shader, uniforms}` / reencode 开关）编译成插件作业并做英文错误校验；`exportVideo(spec)` 端到端驱动 `video`/`transcode` 插件（进度回调透传）。无 video 参数默认走 remux；native 侧对 filters 明确报 `unsupported: filter (GPU path lands in P3b)`（不做静默丢弃）。包源码经 CMake 嵌入生成测试头，单一真源 | 桌面 `test_weizhi_video_package`（编译器输出精确断言 + 校验错误 + 无 native 提示）；真机 `WeizhiVideoPackageInstrumentedTest`：包导入→重编码导出（时长/进度）→ remux 路径→ filters 守卫全过；全量 23/23 |
| P3b ✅（2026-10-17） | GL 滤镜与缩放路径打通：EGL 离屏（ES2 + `EGL_RECORDABLE_ANDROID`）+ `AImageReader`(PRIVATE, `GPU_SAMPLED_IMAGE`) → OES 纹理 → 用户 shader → 编码器输入表面（`eglPresentationTimeANDROID` 送 PTS）；预设/自定义滤镜与缩放执行 P3a 预留的 filters 契约。两项根因修复见下方「P3b 根因记录」 | 真机 **27/27 全绿**（含 `scaleViaPackage` 320x240→176x144 分辨率断言、`brightnessPixelShift` Y 均值 +17 像素断言）；桌面含 ASan 绿；`GlEncoderProbeInstrumentedTest` 常驻记录设备 GL 编码能力。**遗留**：LUT/转场已随 P3c 落地；多滤镜链仍守卫报错（一 job 一滤镜） |

### P3b 根因记录（2026-10-17，编码器报错已解）

调试手段（该机 logcat 不可读，全程靠 `<output>.log` + 断言消息带日志）：

1. **Java 对照实验**（`GlEncoderProbeInstrumentedTest`，grafika 最小配方，不经过引擎/插件）：
   腿 1 = `createInputSurface` + EGL14 + swap 纯合成帧；腿 2 = 解码→SurfaceTexture(OES)→GL→编码。
   两腿在 320x240 全过 → **设备能力无问题，嫌疑收窄到 C 用法**。
2. **glClear 二分**（临时 `WT_GL_BISECT=clear`，现已移除）：GL 管线保留但不导入 OES 纹理 → 160x120 仍 0 样本即死 → **导入环节无辜，尺寸维度有异**。
3. **尺寸矩阵**（Java 探针按尺寸逐个跑）：160x120 / 160x112（含对齐尺寸）失败，176x144 / 320x240 通过；反转顺序排除进程状态污染 → **该机（Honor MAA-AN00，`media.hncodec` 厂商栈）AVC Surface 编码模式不支持宽 160**：`configure()` 接受，组件在首批缓冲上报错（`AMEDIA_ERROR_UNKNOWN`），帧数随队列深度 2~5 帧浮动。

两项根因与修复（`plugins/weizhi_transcode/weizhi_transcode.c`）：

- **无 fence 的 AImage 归还**：裸 EGLImage 导入路径没有 BufferQueue 的 acquire/release fence，
  `AImage_delete` 归还缓冲后解码器可立即覆写 GPU 仍在采样的内存（SurfaceTexture 免疫，C 路径不免疫）。
  修复：swap 后 `glFinish()` 再归还，顺带去掉 `eglCreateImageKHR` 的 `EGL_IMAGE_PRESERVED_KHR`
  （导入采样一次即弃，无需 preserve；与 SurfaceTexture 用法对齐）。两处一同修复后 320x240 滤镜路径
  （`brightnessPixelShift`）转绿——未做单独隔离归因，fence 竞争是最合理解释。
- **设备编码器尺寸限制**：NDK 无能力查询 API，且 configure 不报错。修复：0 样本（muxer 未启动）的
  编码器失败改报 `encoder failed (no frame encoded at WxH; device codec limit)`（英文关键词可解析），
  有样本后才失败仍报通用 `encoder failed`。测试缩放目标改 176x144（QCIF，该机支持）。

测试语义注记：YUV 亮度断言受 limited-range 往返衰减（`(Y-16)*(1+amount)+16`），默认 amount 0.2 在
luma-50 夹具上只有 +6.8，`brightnessPixelShift` 现用 amount 0.5（期望 +17，阈值 >10）。

**glFinish 与 fence 的决策（2026-10-17）**：swap 后的每帧 `glFinish()` 保留。它正是 AImage 归还竞态
的修复本身，且真机行为已验证；换成 EGL 同步 fence 只省每帧一次 CPU↔GPU 全量同步的开销，导出 30fps
场景下每帧成本在亚毫秒级、总开销可忽略，不值得为这点收益重新触碰刚修稳的归还路径。等真出现
高帧率/4K 导出的性能诉求再做 fence 化。

P3b 改动文件：`plugins/weizhi_transcode/weizhi_transcode.c`、
`android/app/src/androidTest/java/com/weizhi/{WeizhiTranscodeInstrumentedTest,GlEncoderProbeInstrumentedTest}.java`。
构建：`./scripts/build-android.sh arm64-v8a`；单测：`cd android && ./gradlew :app:connectedDebugAndroidTest`。
| P3c ✅（2026-10-17） | LUT 与 fade 转场（补 P3b 遗留）：`filters:[{type:'lut',params:{path,amount}}]`——插件按 fs 沙箱解析 Adobe/IRIDAS `.cube` 3D LUT（2..64 格）并上传为 N×N² 2D 纹理（unit 1；native 绑定 `uLut`/`uLutSize`，JS 预设 GLSL 做跨 slice 三线性插值）；`transitions:{type:'fade',durationMs}`（单对象广播到全部边界；数组须恰为 clips.length-1 项）编译为每 clip `fadeInMs/fadeOutMs`，native 用 GL 常量色混合（`GL_CONSTANT_COLOR`）逐帧衰减——与滤镜 shader 完全正交、可任意组合。语义：fade 只发生在 clip 边界（时间线首尾不淡出）、只影响视频（音频直通不变）；时长越界报 `unsupported: transition longer than clip`。包升至 `video@0.3.0` | 桌面 `test_weizhi_video_package`（lut/转场编译精确断言 + 6 类校验错误）与含 ASan 全量绿；真机 `lutPixelMap`（常量白 LUT→Y≈235、恒等 LUT 不变色）、`fadeTransitionBoundary`（两侧边界帧 Y<40、clip 中段与时间线尾 Y∈[45,75]）通过，全量 **29/29** |
| P4 | 预览协作面（宿主 Surface，与 Agent1 对齐接口）。~~ffmpeg 可选插件~~（2026-10-19 划掉：安卓系统媒体栈已覆盖主线，仅桌面 CLI 按需再评估） | 跨仓协议文档 |

P1 的「无重编码剪辑（remux）」是性价比最高的第一刀：纯 MediaExtractor/MediaMuxer，
不碰 codec，代码量小，真机即可验证端到端通道。

---

## 5. 明确不做 / 后置

- **GPU compute 直通 JS**（`gpu.dispatch` 形态）：分层违规（引擎不应感知 GL/Vulkan 上下文），
  GPU 只存在于插件实现内部。
- **跨 runJs 常驻预览流**：需要常驻事件循环，引擎模型不支持；预览归宿主 App。
- **引擎内 FFmpeg**：体积门禁（§1.5）直接否决；只能做独立插件 SO。
- **Audio DSP 全家桶**：P3 后按需；PCM 处理先走 `bytes` 拷贝（音频数据量小得多）。

## 6. 已定决策与待拍板的开放问题

已定（正文有细节）：

- **D-交付**（2026-10-17）：安卓内置独立 SO，ensureNative 保留、内置表优先（§2.2）；
  下载目录仅桌面 CLI。
- **D-数据面**（2026-10-17）：安卓 NDK 系统媒体栈（`libmediandk`），不自研 FFmpeg（§2.1）。
- **Q1 已定**：句柄用 **i64 约定**（`0` 恒为非法句柄，插件在返回 JSON 中报 `error`），
  已随 IDL v2 落地（`docs/NATIVE_PLUGIN_IDL.md` §2.1）。
- **Q2 已定**：长任务取消用 **`cancel_poll` 轮询**（真机/桌面验证体验足够；
  异步 fn + complete 通道暂不引入）。落地时发现并修复引擎一处取消漏检：
  `weizhi_await_value` 在 microtask drain 期间发生的取消会被同一 job 的 settle 掩盖
  （`src/engine.c`，现于 drain 后复检；`test_echo_video_cancel` 覆盖）。
- **Q3 已定**（2026-10-17）：**不强制路径形态**——脚本传什么形态都按引擎 `fs` 同语义解析
  （相对 / 绝对 / 带 `.` `..` 均可，归一化后须在 `fs_root` 内，越界 `path escape`）。
  实现为 `WeizhiPluginHost.resolve_path` 宿主服务，插件把脚本路径先过这层再打开；
  `echo_video.probe_media` 已按此工作（相对/绝对/越界/缺失四种用例在桌面与真机覆盖）。
  沙箱外媒体（相册/SAF）由宿主先拷入工作区。见 `docs/NATIVE_PLUGIN_IDL.md` §2.1。

待拍板：

（无。）

- **Q5 已定**（2026-10-19）：ROADMAP 已立项（[ROADMAP.md](ROADMAP.md)「视频剪辑能力」节），
  本特性随 `feat/video-editing` 合入主干；多滤镜链与 P4 预览协作面列为后续、不阻塞主线。

已定（补充）：**Q4**（2026-10-17）：**按功能拆多逻辑插件**，不动单插件 32 导出上限——
P2 起按 demux / decode-encode / mux 等功能面拆分（各自独立 SO 与 manifest，
宿主按需 `registerBundledPlugin`），`weizhi_video`（remux）保持现状不拆。

另：P0/P1 记录中提到的真机 `workspaceRelativeImport` 失败已定位并修复——
非 Android 16 设备问题，而是引擎通用 bug：根级脚本的 `./util.js` 被 QuickJS
默认规范化成裸名 `util.js`，加载器视为非路径报 unsupported（桌面同样复现）。
修复为自定义模块规范化器：路径形态说明符规范化后若丢失路径性则补回 `./`；
裸名语义（仅 built-ins / weizhi:）不变。真机 19/19 全绿（该用例首次全绿）。

## 7. 测试策略（对齐 AGENTS.md）

- 每阶段必须有自动化测试：P0 → `tests/test_engine.c` + `tests/java/`；
  P1+ 真机 → `android/app/src/androidTest/java/com/weizhi/Video*InstrumentedTest.java`。
- 概念验证插件用**纯 C 合成视频流**（生成确定性帧序列），桌面 CI 可全量跑，
  不依赖媒体文件与 MediaCodec——与 `echo_math` 插件的测试形态一致。
- 真机断言优先用**确定性可校验量**：输出时长、轨道数、采样帧的像素 hash、文件大小区间，
  避免脆弱的视觉比对。
