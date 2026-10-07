---
name: pptx
description: 用户要做演示文稿、幻灯片、PPT 或 pptx 时使用。用 run_js 调用 pptx.js 的 renderPptx，由版式和主题绘制形状与背景，不要手写 OOXML。
---

# 生成 PPT

用 `run_js` 调用脚本库，不要自己拼 `slide.xml`，不要改 EMU 坐标。

```javascript
import { renderPptx } from "pptx.js";

export default renderPptx({
  title: "季度复盘",
  theme: "briefing",
  slides: [
    { layout: "title", title: "季度复盘", subtitle: "2026 Q3" },
    { layout: "bullets", title: "结论", items: ["收入 +12%", "留存持平"] },
    { layout: "stat", title: "指标", items: [{ value: "12%", label: "收入" }] },
    { layout: "steps", title: "流程", items: ["发现", "修复", "上线"] }
  ]
}, "out/q3.pptx");
```

链式写法用 `pptx-build.js` 的 `buildPptx`。

## 主题

`briefing`（浅底、左侧色条）或 `dark`（深底渐变、顶部色条）。装饰形状、页脚、圆角卡片由渲染器绘制。

背景可按页覆盖：`"dark"`、`"light"`、`"accent"`，或 `{ image: "workspace/相对路径.png" }`，或 `{ color: "accent" }`。颜色只用主题色名：`bg`、`surface`、`accent`、`accent2`、`muted`。

## 版式

| layout | 内容 |
|---|---|
| `title` | `title`、`subtitle` |
| `section` | 章节页。`briefing` 下默认强调色铺底 |
| `bullets` | `items` 字符串数组，每条一个圆点 |
| `twoColumn` | `left`、`right` 数组，可选 `leftTitle`、`rightTitle` |
| `image` | `image` 为 png/jpeg/gif 路径，可选 `text`、`items`、`side: "left"` |
| `table` | `rows`，第一行是表头 |
| `stat` | `items`: `{ value, label }` |
| `steps` | 短语数组，画成 chevron |
| `callout` | `text` 一句结论，左侧色条 |
| `cards` | `items`: `{ title, text }` |
| `shapes` | 网格形状，见下 |

## 网格形状

只在 `layout: "shapes"` 使用。12 列 × 6 行，写 `col`、`row`、`colSpan`、`rowSpan`（从 0 起）。一页最多 6 个。

`preset`：`rect`、`roundRect`、`ellipse`、`chevron`、`rightArrow`。`fill` 用主题色名。

```javascript
{ layout: "shapes", title: "示意", shapes: [
  { preset: "ellipse", col: 0, row: 1, colSpan: 4, rowSpan: 3, fill: "accent", text: "现在" },
  { preset: "rightArrow", col: 4, row: 2, colSpan: 4, rowSpan: 2, fill: "accent2", text: "然后" },
  { preset: "roundRect", col: 8, row: 1, colSpan: 4, rowSpan: 3, fill: "surface", text: "目标" }
]}
```

## 内容约束

- 一页一个观点。标题短，要点不超过 6 条。
- 普通叙述用 `bullets` / `twoColumn` / `cards`。数字用 `stat`，流程用 `steps`，对比或示意图用 `shapes`。
- 需要打开文件时，宿主用 `android.intent.start({ action: "view", path })`。
