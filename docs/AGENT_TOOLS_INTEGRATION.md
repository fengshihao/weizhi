# 模型工具不在 Weizhi

Weizhi 只提供脚本引擎：`WeizhiEngine.runJs`、沙箱 `fs`、内置模块、脚本内 `mcp.connect`，以及可选 Caps。

给模型调用的 grep、bash、skill、WebView 由宿主自己实现。Agent1 放在 `java-agent-core`，集成方式见 Agent1 的 `doc/集成/WEIZHI.md`。

本仓库不再发布 `:agent-tools` 或 `:agent-tools-webview`。
