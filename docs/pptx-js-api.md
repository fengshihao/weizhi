# pptx.js — 幻灯片模型 + 渲染

路径：`assets/office/pptx.js`。QuickJS **ES module**，无 npm。给人读；Agent 检索见 [`api-cards.jsonl`](api-cards.jsonl) 的 `pptx.render` / `pptx.build`。

形状、背景和坐标由渲染器绘制。调用方选择版式、主题和主题色名。

## 加载

```javascript
import { renderPptx, createDeck } from "pptx.js";
import { buildPptx } from "pptx-build.js";
```

需 `setScriptFolder` + caps（`files.mkdir` / `zipCreate`）与 `setFsRoot` 工作区对齐。

## 直接渲染

```javascript
const result = renderPptx({
  title: "季度复盘",
  theme: "briefing",
  slides: [
    { layout: "title", title: "季度复盘", subtitle: "2026 Q3" },
    { layout: "bullets", title: "结论", items: ["收入 +12%", "留存持平"] },
    { layout: "steps", title: "流程", items: ["发现", "修复", "上线"] },
  ],
}, "out/q3.pptx");
// { ok: true, path, bytes, slides }
```

`theme`：`briefing`（浅底、左侧色条）或 `dark`（深底渐变、顶部色条）。省略时为 `briefing`。

按页 `background`：

| 值 | 效果 |
|---|---|
| 省略 | 主题默认底 |
| `"dark"` / `"light"` / `"accent"` | 深色渐变、浅底、强调色铺底 |
| `{ image: "notes/cover.png" }` | 铺满图片，上盖半透明遮罩。png / jpeg / gif |
| `{ color: "accent" }` | 主题色名：`bg`、`bg2`、`accent`、`accent2`、`surface`、`text`、`muted`、`onAccent` |

`section` 在 `briefing` 下未指定背景时铺强调色。

## 版式

| layout | 字段 |
|---|---|
| `title` | `title`、`subtitle` |
| `section` | `title`、`subtitle` |
| `bullets` | `title`、`items`（字符串，每条一个圆点） |
| `twoColumn` | `title`、`left`、`right`，可选 `leftTitle`、`rightTitle` |
| `image` | `title`、`image`，可选 `text`、`items`、`side: "left"`（默认图在右） |
| `table` | `title`、`rows`（第一行表头，最多 12 行） |
| `stat` | `title`、`items`: `{ value, label }` 或字符串 |
| `steps` | `title`、`items` 画成 chevron |
| `callout` | `title`、`text`（左侧色条） |
| `cards` | `title`、`items`: `{ title, text }` |
| `shapes` | `title`、`shapes`（见下） |

别名：`two-column`、`bullet`、`blank`（等于 `shapes`）。

要点、步骤、统计、卡片、双栏每一侧最多 8 条。整份最多 40 页。

## 网格形状

仅 `layout: "shapes"`。内容区 12 列 × 6 行，原点在左上。`col` / `row` 从 0 起，加上 `colSpan` / `rowSpan` 不可越界。一页最多 6 个。

`preset`：`rect`、`roundRect`、`ellipse`、`chevron`、`rightArrow`。`fill` 为主题色名，默认 `surface`。`text` 写在形状里。

```javascript
{
  layout: "shapes",
  title: "示意",
  shapes: [
    { preset: "ellipse", col: 0, row: 1, colSpan: 4, rowSpan: 3, fill: "accent", text: "现在" },
    { preset: "rightArrow", col: 4, row: 2, colSpan: 4, rowSpan: 2, fill: "accent2", text: "然后" },
  ],
}
```

## Builder

```javascript
import { buildPptx } from "pptx-build.js";

buildPptx({ title: "演示", theme: "dark" }, (b) => {
  b.title("演示", "副标题")
    .bullets("结论", ["一点", "二点"])
    .steps("流程", ["发现", "修复", "上线"]);
}, "out/deck.pptx");
```

方法：`title`、`section`、`bullets`、`twoColumn`、`image`、`table`、`stat`、`steps`、`callout`、`cards`、`shapes`、`slide` / `slides`。第三个参数可传 `{ background }`。

## 错误

失败信息含 `bad argument: renderPptx:`，例如 `unknown theme`、`unknown layout`、`unknown fill`、`unknown preset`、`too many shapes`、`shape out of grid`、`slides required`、`image required`、`image type`。

## 测试

`./scripts/test-jni.sh` 里的 `OfficeTest` 覆盖版式、主题、图片背景、网格形状和上述错误。`./scripts/test-office-strict.sh` 对生成的 `deck.pptx` 做 XML 良构检查（需 `xmllint`）。
