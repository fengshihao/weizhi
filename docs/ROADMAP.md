# Weizhi roadmap（安卓编程智能体）

日期：2026-10-17。与 [DECISIONS.md](DECISIONS.md)、[AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md) 配套。  
本文是**方向与优先级**，不是当前实现清单；未勾选的项默认未做。

## 产品目标

把 Weizhi + caps + Agent 集成成**安卓上的编程智能体**：能在沙箱里改文件、跑脚本，并把写过的能力沉淀成可复用技能；必要时能恢复工作区或某份文档的历史版本。

分层不变：

| 层 | 职责 |
|---|---|
| **引擎** | `runJs`、内置模块、`fs` / 模块加载、限额 |
| **caps** | 端上通用表面（`files.*`、zip、后续 vcs 等） |
| **Agent App / `@Tool`** | 工具环、技能归档策略、用户授权目录、UI 确认 |

## 路径与沙箱语义（已对齐）

### 工作区（可读写）

- 宿主 `setFsRoot` / caps 工作区：AI **可读可写**。
- 脚本里引擎 `fs` / workspace `import`：相对或绝对路径均可，归一化后须在本根下；Caps `files.*` 仍多用相对路径。`process.cwd` 锚定工作区。

### 只读根（保护，不是「技能库」）

用途：保护 **Agent 系统文件** 以及 **用户授予、但不属于工作区** 的目录，避免被 AI 写坏。

| 来源 | 读写 |
|---|---|
| Agent 系统内部重要目录（由 App 挂进来） | 只读 |
| 用户授予的目录（非工作区） | **先只读**；以后可考虑写时弹窗确认（caps/宿主策略，不进引擎默认写） |

实现落点（待做）：引擎 `fs` + caps 支持额外只读根列表；写操作落在只读根 → 明确错误。与参考 Agent `Sandbox` 双根同思路，但动机是**保护**，不是「外存当资料库」 alone。

### 技能库（App 决定，须可写）

- **位置由 Agent App 决定**（通常是应用私有目录），告知引擎/caps 即可（例如挂成工作区子树，或 App 自行约定的技能根目录）。
- 必须让 AI **可写**，否则无法自行创建 Skill。
- 技能如何命名、何时归档、如何索引：**App / Agent 策略**；引擎只负责能 `import './….js'` 跑起来（见 [MODULE_LOADING.md](MODULE_LOADING.md)）。

```
工作区 (R/W)          ← 日常读写、产出
只读根 (R)            ← 系统保护区 + 用户授权非工作区
技能区 (R/W, App 定)  ← 可与工作区重叠或为其子目录；需可写才能沉淀 Skill
```

## 脚本复用（方向）

- 引擎：稳定相对模块加载（按需扩展多级路径）；技能以普通文件存在磁盘上。
- **官方 JS 库**：已归并为 **Weizhi Package**（内置 + Registry 可下载、统一 manifest，`import … from "weizhi:<name>"`）；旧 catalog / `setScriptFolder` 已移除。见 [WEIZHI_PACKAGES.md](WEIZHI_PACKAGES.md)（P1/P3 已实现）。
- Agent：总结本轮成果 → 写入技能目录 → 后续 `import` / `runJs` 薄入口调用。
- **不**在引擎里做「自动记忆」；复用 = 文件系统 + 模块系统。

## 版本恢复库（后置，先定推荐）

目标：恢复工作区或某文档的历史版本；**不要求真 Git 兼容**。约束：**ROM 极小（约几百 KB 量级）**。

### 推荐（最优默认）

**应用内「快照式」版本库（自研，Java）**，不要嵌入完整 Git / JGit：

- 内容寻址：文件按 hash 存 blob；提交 = 树快照 + message + 时间。
- 先做线性历史：`checkpoint` / `list` / `restore`（整树或单路径）；分支以后再说。
- 存储落在 App 私有目录（可与工作区分开），由 caps 或独立小库提供 API；Agent 薄包成工具。
- 预期包体：纯 Java、无网络依赖时 **远小于 1MB**（目标几百 KB 量级代码+最小运行态）。

### 明确不优先

| 方案 | 原因 |
|---|---|
| 完整 Git / JGit | 体积与复杂度超标 |
| libgit2 进 NDK | 体积与维护成本高 |
| 引擎内 C 实现 VCS | 分层错误；版本策略属宿主/App |

收费与产品形态以后再议；技术默认按上面快照库推进。

## 近期优先级（当前焦点）

1. **Agent 集成**（主路径，进行中）：引擎侧入口已稳定（`runJs` / caps / 沙箱 / `weizhi:` Package）；`run_js` 等模型工具与文件工具环属宿主（Agent1 `java-agent-core`），不在本仓库。接入步骤见 [INTEGRATION_FOR_AI.md](INTEGRATION_FOR_AI.md)。
2. 双根只读、技能目录约定、多级 import、快照 VCS：**写入路线图，不阻塞集成**；集成稳定后再按需开做。
3. **视频剪辑能力**（2026-10-19 立项，随 `feat/video-editing` 合入主干）：见下节。

## 视频剪辑能力（已立项）

- **状态**：P0–P3c 已落地（IDL v2、`weizhi_video` remux / `weizhi_transcode` 重编码插件、
  GL 滤镜与缩放、LUT 与 fade 转场、`weizhi:video` JS 编排包 `video@0.3.0`），
  真机 29/29、桌面含 ASan 全绿。架构与阶段记录：[video-architecture.md](video-architecture.md)。
- **边界不变**：heavy 代码全部在独立签名插件 SO（`libweizhivideo.so` ≈60KB、
  `libweizhitranscode.so` ≈133KB，预算 ≤512KB）；核心 `libweizhijni.so` 的 1.5MB strip 门禁不动；
  安卓数据面走 NDK 系统媒体栈（`libmediandk`），不自研 FFmpeg。
- **后续（不阻塞主线）**：多滤镜链（解除「一 job 一滤镜」守卫）、P4 预览协作面
  （宿主 Surface，需与 Agent1 对齐跨仓协议）。ffmpeg 插件**暂不做**——安卓系统
  编解码器已覆盖主线需求；仅当出现系统栈覆盖不了的容器/编码诉求再评估。

## 迭代建议（集成之后）

| 顺序 | 项 | 说明 |
|---|---|---|
| A | 只读根 API | 保护系统区 + 用户授权只读目录 |
| B | 技能目录约定 + import | App 指定可写技能根；引擎按路径加载 |
| C | 快照版本库 | 工作区/文档恢复；ROM 极小 |
| D | 写授权弹窗 | 只读根将来「可写但需确认」 |

## 本阶段不做

- 官方站点 / 大门户、引擎内嵌完整 Git、把 Agent 行号编辑/grep 塞进 C、整库 OpenCV / 重 Wasm 回主干。
