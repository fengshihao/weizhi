# QuickJS 库适配调研

日期：2026-09-25。对照的是主干上的 Bellard QuickJS，加上 Weizhi 当前暴露给脚本的宿主表面。结论用于决定第三方 JS 库是打包即用、裁剪后使用，还是改走签名原生 SO。

相关规格：[DECISIONS.md](DECISIONS.md)、[HOST_ABI.md](HOST_ABI.md)、[AGENT_SANDBOX_PROMPT.md](AGENT_SANDBOX_PROMPT.md)。

## 结论

这些库大多不用改 QuickJS 本身，也不该整包塞进引擎。

- 文本和数据工具打成单个脚本就能跑。
- ZIP、PDF、DOCX、表格要裁掉压缩和字体，把 deflate 交给宿主。引擎已提供 `require("zlib")`（`gzipSync` / `gunzipSync` / `deflateSync` / `inflateSync`）和 `require("zip")`（`extractSync` / `createSync`，可直接解压/打包 Office 类 zip）。
- 图像编解码、PDF 渲染、WASM 库走签名原生 SO，和现有 `media.resize` 同一条路。

QuickJS 保持上游，不改引擎源码。没有 `node_modules` 解析；库在引擎外打成一个叶子文件，用 `import './file.js'` 加载（见 [MODULE_LOADING.md](MODULE_LOADING.md)）。

## 引擎实际能跑什么

Bellard QuickJS 语言面接近完整 ES2025：类、`async`、Proxy、BigInt、可选链、类型数组、Promise。明确没有尾调用、`Atomics.waitAsync` 和 ECMA-402 `Intl`。解释执行，没有 JIT。像素循环和 deflate 会比 V8 慢一个数量级以上。

Weizhi 暴露给脚本的表面更窄：

| 已有 | 范围 |
| --- | --- |
| 模块 | 内置 `buffer` / `fs` / `path` / `process` / `zlib` / `zip`。相对 `import './file.js'` 从脚本目录加载叶子文件。`require` / `import` 不解析 npm |
| `fs` | 读、写、存在、删除，以及 `promises` 的读和写 |
| `path` | `join`、`basename`、`dirname`、`extname`、`sep` |
| `Buffer` | `Uint8Array` 子类。`from` 收字符串 / 类型数组 / 类数组；`toString` 有 utf8、hex、base64；可 `buf[i]` |
| `fetch` | body 为字符串、`Buffer`/`Uint8Array`、`Blob`、`FormData`、`URLSearchParams`。Response 有 `headers.get` / `text` / `json` / `arrayBuffer` |
| `mcp` | `mcp.connect({ url, headers })` 后 `listTools` / `callTool` / `close`。走 `fetch`，宿主须先开网络。不缓存、不写工作区目录 |
| 定时器 | 仅本轮 `runJs` 内的 `setTimeout` / `clearTimeout` |
| 限额 | 堆 32MB，栈 256KB，单次 `runJs` 默认 10 分钟（可 `cancel`），单次读写 / `fetch` 载荷 32MB |

没有流、DOM、Worker、Canvas。Wasm 已从主干移除，历史实现在分支 `archive/wamr-packs`。

堆和读写上限是天花板，不是预占。`JS_SetMemoryLimit` 只在分配字符串、对象、字节码、类型数组时计数。空闲引擎不占住 32MB。Bitmap、网络缓冲、原生插件不进这道堆计数。超过上限时本次 `runJs` 以 `memory` 或 `too large` 停下，引擎还可以再跑。

非 ASCII 文本在 QuickJS 里按 UTF-16 存放。一份接近 32MB 的中文文件展开后仍可能先撞上 `memory`。二进制走 `Buffer` 时，字节在引擎外分配，可以顶到读写上限。

## 库怎么处理

### 打包即可

源码不用改。缺的只是包解析，在引擎外打成一个文件。

| 库 | 用途 |
| --- | --- |
| marked、markdown-it | Markdown。markdown-it 要连同 linkify-it、entities 一起打 |
| dayjs、date-fns | 日期。date-fns 只打用到的函数 |
| `papaparse` | CSV。大表仍受堆和默认超时约束 |
| js-yaml | YAML |
| fast-xml-parser | XML。Office 文档拆开后的 XML 层用它，不需要 DOM |
| nanoid、diff、lodash 子集 | 小工具。避开 `crypto.randomUUID`。lodash 不要整包 |

### 要精简

语言能跑，但依赖图、压缩或内存不适合原样放进来。

| 库 | 原因 |
| --- | --- |
| cheerio | 自带 DOM 模拟，可打浏览器包。htmlparser2 宜裁到选择节点和抽文本 |
| turndown | 默认要浏览器 DOM。换成已解析的 HTML 树，或只留规则子集 |
| jszip、pako | 小文本包可以。解释器里做 deflate 慢，图片级压缩会超时。压缩改走宿主 COMPRESS |
| pdf-lib | 有不依赖 Node 的 UMD。去掉 fontkit。生成几页文本可行；解析大 PDF、嵌字体、嵌图会撞堆和超时 |
| mammoth、docx | 本质是 ZIP + XML。浏览器构建可打，但 jszip / bluebird / lodash 应换成宿主解压 + fast-xml-parser |
| xlsx（SheetJS 社区版） | 浏览器构建存在。社区版和 Pro 版许可不同，集成前核对许可证。大表和样式裁掉 |
| upng-js | 仅适合极小 PNG。1000×1000 的 RGBA 裸像素约 4MB，加上对象开销会顶满堆 |

`exceljs` 依赖 `stream`、`archiver`、`unzipper`，不是删几个 import 能解决的，归到下一类。

### 走原生 SO

| 库 | 原因 |
| --- | --- |
| sharp | libvips 绑定。和 `media.resize` 同一条路 |
| jimp、pngjs、jpeg-js | 纯 JS 编解码又慢又吃内存。jpeg-js 有已知内存尖峰。jimp 还要类 Node 的 Buffer |
| pdfjs-dist | 要 Canvas、Worker，可选 WASM |
| opencv.js、@jsquash / squoosh | 产物是 WASM |
| fabric、konva | 绑定浏览器 Canvas 和 DOM |

## 若要多撑住一些库

收益最高的是四件小表面，而不是加宽 `Buffer` 或把堆放到数 GB。

| 表面 | 作用 | 放在哪 |
| --- | --- | --- |
| `TextEncoder` / `TextDecoder` | JS 字符串和 UTF-8 字节互转。docx、XML、带二进制字段的小工具用它生成或读回 `Uint8Array` | 已有。只做 UTF-8 |
| base64（`btoa` / `atob`，或 `Buffer` 的 base64） | 字节和可放进 JSON、data URL 的 ASCII 互转 | 已有。`btoa`/`atob` 是 Latin-1；`Buffer` 另有 `utf8` / `hex` / `base64` |
| `URL` / `URLSearchParams` | 解析和拼装地址。很多库使用 `new URL(...)`，而不是手写字符串。现有 `fetch` 只收一整段 URL | 已有。精简解析，不发起网络请求 |
| `crypto.getRandomValues` | 向类型数组填入密码学安全随机字节。`uuid`、Web 版 `nanoid`、令牌生成会调用 | 已有。熵来自 `/dev/urandom`。另有 `crypto.randomUUID()`。不能用 `Math.random` 代替 |

`fetch` 要各平台接自己的网络栈，所以由宿主安装。编码、base64 和 URL 没有平台差异，写进引擎一次即可。

不要在引擎里做 `node_modules`。不要为了 jimp 放宽默认堆。需要 Wasm 或 Canvas 时用原生插件，不把 WAMR 合回来跑 JS 绑定。
