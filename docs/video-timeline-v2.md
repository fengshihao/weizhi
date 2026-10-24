# 时间线 schema v2 设计（P5 提案，多轨 + 关键帧）

日期：2026-10-24。状态：**P5a–P5c 已合入**（[#47](https://github.com/fengshihao/weizhi/pull/47)–[#49](https://github.com/fengshihao/weizhi/pull/49)）；P5d/P5e 待做。本文是 [video-architecture.md](video-architecture.md) 的 P5 延伸；v1 = 该文档 P1–P3d 已落地形态。

## 1. 动机

v1 的 JSON 是「单全屏层 + 顺序列表」：clips/slides 排队 + 边界黑场转场 + 单滤镜。
灵活度集中在 shader 字符串（视觉处理可编程）与 JS 编排层（上层任意计算），但**结构维度
天花板明显**：无叠加轨（水印/字幕/画中画）、无空间关键帧（kenBurns/位移/缩放动画）、
无真叠化转场（cross-dissolve）、音频单轨。

目标：把结构表达力补到「Flash 时间轴 / 现代 NLE」级——**多轨叠加 + 逐属性关键帧**，
同时不动三条既有硬边界：帧不出插件、heavy 代码在签名 SO（≤512KB）、安卓系统媒体栈。

## 2. 调研结论（2026-10-24，抄模型不抄代码）

| 参考系 | 模型要点 | 取什么 |
|---|---|---|
| [GES](https://gstreamer.freedesktop.org/documentation/gst-editing-services/index.html) | Timeline→Layers+Tracks→Clips→TrackElements(source/effect/transition)，关键帧走 control binding | **轨/元素分层**与「用户视角 Layer vs 合成 Track」二分 |
| [libopenshot](http://m.openshot.org/files/libopenshot/classopenshot_1_1Timeline.html) | 全 JSON 数据模型：clip JSON + effects + `Keyframe{points[], interp}` | **Keyframe 的 JSON 形态**（我们同为「JSON 控制视频」路线） |
| 剪映/CapCut 草稿（[pyJianYingDraft](https://github.com/GuanYixuan/pyJianYingDraft) 逆向） | materials + tracks + segments + keyframes + animations | **移动端产品的真实结构**：文本/贴纸是一等轨、素材与轨道分离 |

它们的渲染栈（GStreamer / FFmpeg / 剪映 App）都不可移植进我们的约束；v2 只取数据模型。
Remotion（代码即时间轴 + 无头 Chrome）路线已评估否决：渲染栈不可上端、与
「帧不出插件」架构冲突、许可（公司 >3 人付费）；其「代码生成视频」的价值由
我们的 JS 编排层承接。

## 3. schema v2 设计

### 3.1 顶层

```js
await exportVideo({
  width: 1280, height: 720, fps: 30, bitrate: 4_000_000,
  tracks: [ /* 轨道数组，顺序 = 合成顺序（先下后上）*/ ],
  output: 'out.mp4',
})
```

### 3.2 轨道与片段

```js
// 视频轨（可多条；多条 = 叠加合成）
{ type: 'video', clips: [
  { src: 'v.mp4', inMs: 0, outMs: 3000, startMs: 0,
    transform: { /* §3.3，任意属性可关键帧化 */ },
    effects: [ /* 复用 v1 filters 形态：预设名或 {shader, uniforms} */ ] } ] }

// 图片轨（水印/贴纸/画中画）
{ type: 'image', clips: [
  { src: 'logo.png', startMs: 0, durationMs: 10_000,
    transform: { x: 0.8, y: -0.8, scale: 0.2, opacity: 0.9 } } ] }

// 音频轨（可多条，P5d 混音）
{ type: 'audio', clips: [
  { src: 'music.m4a', startMs: 0, gain: [{t: 0, v: 1}, {t: 28_000, v: 0}] } ] }
```

规则：

- `startMs`（轨内起点）+ `inMs/outMs`（源裁剪，video/audio）或 `durationMs`（image/text），
  与 v1 clips 语义一致，只是多了轨内定位。
- **同轨 clip 不许重叠**（报 `bad argument: overlapping clips in track`）；
  **不同轨重叠 = 叠加合成**（这正是叠加的意义）。
- 第一条 video 轨为主轨（决定输出时长基准），其余 video 轨超出主轨范围的部分裁掉。
- **时间轴零点**：主轨首 clip 的 `startMs` 应为 `0`；clip 之间不许留空隙（报 `unsupported: v2 timeline gaps (P5e)`）。首 clip 若 `startMs > 0`，image 主轨在空隙段可能不送帧（勿依赖）。

### 3.3 关键帧模型（OpenShot 式）

任何 `transform` 属性（以及 audio 的 `gain`）可写常量或关键帧数组：

```js
transform: {
  x: 0,                                  // 常量
  scale: [{t: 0, v: 0.2},                // 关键帧（t 相对 clip start，毫秒）
          {t: 2000, v: 0.35, interp: 'ease'}],  // 段内插值：linear | ease（缺省 linear）
}
```

求值语义：t 之前取首值、之后取末值；两关键帧之间按 `interp` 插值；关键帧数组
须按 t 升序（否则 `bad argument: keyframes must be sorted`）。

`transform` 属性集（v2 固定五元）：`x, y`（归一化坐标，±1 为画面边缘中心点）、
`scale`（1 = 原尺寸比例适配）、`rotation`（度）、`opacity`（0–1）。

### 3.4 转场升级：crossfade

v2 转场发生在**同轨相邻 clip 的重叠区**（`transition: {type:'crossfade', durationMs}`
放在 clip 上，表示与前一个 clip 重叠的量），渲染 = 两路纹理同帧按进度混合。
v1 的边界黑场 fade 保留为 `fade`（在无重叠时使用）。语义校验沿用英文错误
（`unsupported: transition longer than clip`）。

### 3.5 文本轨（P5d 落地）

`{ type: 'text', clips: [{text, startMs, durationMs, style: {size, color, weight}, transform}] }`。
**引擎不带字体**（体积与授权），文本位图化归属两个候选：
A. JS 层调 caps 宿主渲染成 PNG 传给 image 轨（零 native 改动，宿主有 Bitmap/Canvas）；
B. 宿主在 `WeizhiVideo.install` 侧预置一张字体图集 SO 资源。
倾向 A（分层正确），随 P5d 拍板实现。

### 3.6 兼容层（向后兼容是硬要求）

v1 的 `clips` / `slides` / `filters` / `transitions` / `audio` 输入**原样可用**：
`buildJob` 内部先把 v1 形态编译成 v2 轨道（clips→单 video 轨 + 边界 fade；
slides→image 主轨；audio→audio 轨），再下发生成 job。已有调用方（含 Agent1 冒烟）
零改动。v2 tracks 能力随 `video@0.6.0`（`:weizhi-video` AAR assets + transcode SO 同步升级）。

## 4. native 执行模型（不动架构）

- **多 pass 绘制**：每输出帧，按轨序自下而上 draw：video 轨帧（解码→OES 纹理，现有路径）、
  image 轨（AImageDecoder→2D 纹理，P3d 路径）、每 clip 的 transform 决定 quad 几何
  （现有 letterbox 动态 VBO 扩展为通用 transform quad）、opacity 走混合。
- **关键帧求值**：纯 C 线性/ease 插值（几十行），每帧算出 quad 参数与 uniforms。
- **主轨仍走 Surface 隧道零拷贝**；叠加轨数量 ≤4（超报 `unsupported: too many tracks`）。
- 体积预估：在 157KB SO 上 +30–60KB，预算 512KB 内。

## 5. 分期与验收（每期一个 PR，桌面 + 真机门禁全绿才合入）

| 期 | 内容 | 验收（自动化断言） |
|---|---|---|
| **P5a** ✅ | v2 编译器（JS）：tracks 校验、关键帧模型、v1→v2 兼容编译 | 桌面精确断言 + 真机 31 回归 |
| **P5b** ✅ | native 图层合成：image 叠加轨 + 主轨 video/image（常量 transform） | 真机水印 ROI、`WeizhiV2TimelineInstrumentedTest` |
| **P5c** ✅ | 关键帧动画 + image 轨 crossfade（native 求值）；video 主轨 transform 常量/关键帧 | 真机 `v2KeyframeAnimation` / `v2CrossfadeBetweenSlides` / `v2MainTrackVideoTransform`；桌面 crossfade 编译断言 |
| **P5d** | 文本轨（按 §3.5 拍板实现）+ 音频多轨（混音 vs 多轨直通的决策随期拍板） | 真机：文本叠加 ROI 断言；音频轨数/时长断言 |
| P5e（可选） | OTIO 子集导入（交换互操作） | 桌面：样例 OTIO→v2 编译断言 |

## 6. 开放问题（随期拍板，记录于本文）

- Q1 文本位图化归属（§3.5 A/B，**P5d 前定**）
- Q2 音频多轨：MediaCodec 无混音器——候选 = 多音轨直通（muxer 多 track）或软混 PCM 后
  重编码 AAC（体积小增量）；P5d 前定
- Q3 关键帧插值是否需要 `hold`（阶梯）与 `bezier`：先不做，`interp` 留扩展位
- Q4 与 OTIO 的对齐深度（P5e 是否值得）
