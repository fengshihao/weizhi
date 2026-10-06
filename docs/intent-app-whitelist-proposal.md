# android.intent.start：`app` 目标白名单（讨论稿）

> 状态：**未实现**。当前 `intent.start` 仍禁止脚本传任意 `package` / `component`。若采纳本表，建议新增 `action: "app"` + **`target`**（下表「目标 ID」），由引擎映射到固定包名与 URI/Activity，脚本不得自行指定包名。

## 设计原则（供讨论）

| 原则 | 说明 |
|---|---|
| 只开「国民级」App 的**固定入口** | 扫码、搜店、导航、打开首页等；不搞任意 Activity 跳转 |
| 参数白名单 | 只允许表格里列出的占位符（如 `{lat}`、`{keyword}`），禁止透传 extras map |
| 必须用户可见 | 一律 `startActivity`；无静默操作 |
| 未安装要可读失败 | 错误含 `unsupported: intent.app` 或「未安装 xxx」 |
| Manifest `<queries>` | 每增加一个包名，宿主 Manifest 需声明，否则 Android 11+ 看不到 App |

## 候选白名单

| 目标 ID | App | 包名 | 打开方式 | 脚本参数（提议） | Agent 典型场景 | 风险 / 备注 | 建议 |
|---|---|---|---|---|---|---|---|
| `wechat.home` | 微信 | `com.tencent.mm` | `weixin://` | 无 | 「打开微信」 | 低；仅唤起 App | ✅ 首批 |
| `wechat.scan` | 微信扫一扫 | `com.tencent.mm` | `weixin://scanqrcode` 或官方扫码 URI | 无 | 「扫二维码」 | 中；进入扫码页 | ✅ 首批（需真机验 URI） |
| `wechat.pay` | 微信支付 | `com.tencent.mm` | 商户 URL / 受限 deep link | `url`（https 白名单域） | 付款 | **高**；易钓鱼 | ❌ 暂不 |
| `alipay.home` | 支付宝 | `com.eg.android.AlipayGphone` | `alipays://platformapi/startapp?appId=20000001` 等 | 无 | 打开支付宝 | 低 | ⚠️ 讨论 |
| `alipay.scan` | 支付宝扫一扫 | 同上 | `saId=10000007` 类固定入口 | 无 | 扫码 | 中 | ⚠️ 讨论 |
| `amap.route` | 高德地图 | `com.autonavi.minimap` | `androidamap://route?sourceApplication=weizhi&dlat={lat}&dlon={lon}&dname={name}&dev=0&t=0` | `lat`,`lon`,`name` | 导航到目的地 | 中；暴露位置意图 | ✅ 首批 |
| `amap.search` | 高德搜 POI | 同上 | `androidamap://keywordNavi?keyword={keyword}` 或 `poi` 模板 | `keyword` | 「附近咖啡」 | 低 | ✅ 首批 |
| `baidu.map.marker` | 百度地图 | `com.baidu.BaiduMap` | `baidumap://map/marker?location={lat},{lon}&title={name}` | `lat`,`lon`,`name` | 地图打点 | 中 | ⚠️ 讨论 |
| `baidu.map.direction` | 百度地图导航 | 同上 | `baidumap://map/direction?destination=latlng:{lat},{lon}` | `lat`,`lon` | 导航 | 中 | ⚠️ 讨论 |
| `tencent.map.route` | 腾讯地图 | `com.tencent.map` | `qqmap://map/routeplan?type=drive&tocoord={lat},{lon}&to={name}` | `lat`,`lon`,`name` | 导航 | 中 | ⚠️ 二批 |
| `dianping.shop` | 大众点评 | `com.dianping.v1` | `dianping://shopinfo?id={shopId}` | `shopId` | 打开商户页 | 低；需合法 shopId | ✅ 首批 |
| `dianping.search` | 大众点评搜索 | 同上 | `dianping://search?keyword={keyword}` | `keyword` | 搜「火锅」 | 低 | ✅ 首批 |
| `meituan.search` | 美团 | `com.sankuai.meituan` | `imeituan://www.meituan.com/search?q={keyword}` | `keyword` | 本地生活搜索 | 低 | ⚠️ 二批 |
| `taobao.search` | 淘宝 | `com.taobao.taobao` | `taobao://s.taobao.com?q={keyword}` | `keyword` | 网购搜索 | 低；外链合规 | ⚠️ 二批 |
| `jd.search` | 京东 | `com.jingdong.app.mall` | `openApp.jdMobile://virtual?params=...` | `keyword` | 网购 | JSON 参数复杂 | ❌ 二批再议 |
| `douyin.home` | 抖音 | `com.ss.android.ugc.aweme` | `snssdk1128://` | 无 | 打开抖音 | 低 | ⚠️ 讨论 |
| `bilibili.video` | B 站 | `tv.danmaku.bili` | `bilibili://video/{bvid}` | `bvid` | 打开指定视频 | 低 | ⚠️ 二批 |
| `dingtalk.home` | 钉钉 | `com.alibaba.android.rimet` | `dingtalk://dingtalkclient/page/link` | 无 | 打开钉钉 | 低 | ⚠️ 二批 |
| `wework.home` | 企业微信 | `com.tencent.wework` | `wxwork://` | 无 | 打开企业微信 | 低 | ⚠️ 二批 |

## 与现有 `view` + `data` 的分工

| 能力 | 用法 | 说明 |
|---|---|---|
| 通用 https / tel / mailto / geo | `action: "view", data: "https://..."` | **已实现**；不绑包名，由系统选择处理器 |
| 指定国民 App 固定页 | `action: "app", target: "amap.route", lat: …` | **本表**；仅白名单 target |

## 实现形态（若通过讨论）

```javascript
// 提议 API（尚未上线）
android.intent.start({
  action: "app",
  target: "dianping.search",
  keyword: "附近火锅",
});
```

- 仍禁止 `component` / `package` / `extras` / `flags`。
- `target` 未知 → `unsupported: intent.app`。
- 可选返回 `{ ok: true, target, package }` 便于 Agent 自纠。

## 待你拍板的问题

1. **首批 scope**：是否只做 `wechat.home` + `wechat.scan` + `amap.route` + `amap.search` + `dianping.shop` + `dianping.search`？
2. **支付类**（微信/支付宝付款链接）是否永久禁止？
3. **坐标来源**：是否要求 Agent 先用 caps/脚本算好 `lat/lon`，不允许传原始 GPS 后台采集（隐私）？
4. **未安装**：失败文案用中文「未安装高德地图」还是英文关键词 + 中文（与 DECISIONS 混排）？
5. **`<queries>`**：是否接受 Demo `:app` Manifest 随白名单变长（集成方复制同表）？

确认后另开 PR：新增 `IntentAppTargets.java` + 仪器化测试（真机有 App 则 launch，无 App 则断言「未安装」）。
