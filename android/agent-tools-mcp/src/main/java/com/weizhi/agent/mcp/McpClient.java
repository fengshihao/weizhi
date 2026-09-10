package com.weizhi.agent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * MCP 客户端（单 server，Streamable HTTP）。实现 MCP v2（2026-07-28）核心：无状态
 * JSON-RPC 2.0 + 按请求能力协商（{@code MCP-Protocol-Version} 头）；对要求会话初始化的
 * 旧版 server 自动补发一次 {@code initialize} 后重试。
 *
 * <p>响应兼容两种形态：JSON body，或 SSE 流（取首个 {@code data:} 行的 JSON-RPC response，
 * 通知/服务端请求忽略并记日志）。</p>
 */
public class McpClient {

    private static final String PROTOCOL_VERSION = "2026-07-28";
    private static final long READ_TIMEOUT_SECONDS = 60;
    private static final MediaType JSON = MediaType.parse("application/json");

    /** 单个远端工具定义（tools/list 结果项）。 */
    public static final class ToolDef {
        public final String name;
        public final String description;
        public final JsonObject inputSchema;
        public final boolean readOnlyHint;

        /** 纯数据载体：public 供装配/测试直接构造（预载工具缓存等）。 */
        public ToolDef(String name, String description, JsonObject inputSchema,
                       boolean readOnlyHint) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.readOnlyHint = readOnlyHint;
        }
    }

    /** 单次远端调用结果（tools/call）。 */
    public static final class CallResult {
        public final boolean isError;
        public final String text;

        CallResult(boolean isError, String text) {
            this.isError = isError;
            this.text = text;
        }
    }

    /** JSON-RPC 调用失败（网络/协议层），message 已含 URL 与 method 供定位与 LLM 自纠正。 */
    public static final class McpException extends Exception {
        McpException(String message) {
            super(message);
        }
    }

    private final McpServerConfig config;
    private final Gson gson = new Gson();
    private final OkHttpClient http;
    private final AtomicLong nextId = new AtomicLong(1);
    /** initialize 是否已对该 server 补发过（旧版有状态 server 需要）。 */
    private volatile boolean initialized;
    /** server 拒绝 2026-07-28 版本头后置位，后续请求不再携带 MCP-Protocol-Version 头。 */
    private volatile boolean versionHeaderRejected;

    public McpClient(McpServerConfig config) {
        this.config = config;
        this.http = buildClient();
    }

    /**
     * 构造 HttpClient。识别 https_proxy/HTTPS_PROXY 环境变量（公司内网代理，支持
     * http://user:pass@host:port 认证，与 WebSearchTool/HttpRequestTool 一致）；Java
     * 不自动读取该环境变量。外网/手机直连场景无此变量即直连；no_proxy 命中 host 直连。
     */
    private static OkHttpClient buildClient() {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        String proxyUrlStr = envFirst("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY");
        if (proxyUrlStr != null && !proxyUrlStr.isEmpty()) {
            try {
                java.net.URL proxyUrl = new java.net.URL(proxyUrlStr);
                final Proxy proxy = new Proxy(Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(proxyUrl.getHost(), proxyUrl.getPort()));
                b.proxySelector(new java.net.ProxySelector() {
                    @Override
                    public java.util.List<Proxy> select(java.net.URI uri) {
                        String h = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
                        return java.util.Collections.singletonList(
                                matchesNoProxy(h) ? Proxy.NO_PROXY : proxy);
                    }

                    @Override
                    public void connectFailed(java.net.URI uri, java.net.SocketAddress sa,
                                              IOException e) {
                        // 连接失败交由 OkHttp 抛出，无需处理
                    }
                });
                String userInfo = proxyUrl.getUserInfo();
                if (userInfo != null) {
                    String[] parts = userInfo.split(":", 2);
                    final String user = urlDecode(parts[0]);
                    final String pass = parts.length > 1 ? urlDecode(parts[1]) : "";
                    b.proxyAuthenticator((route, response) -> response.request().newBuilder()
                            .header("Proxy-Authorization",
                                    okhttp3.Credentials.basic(user, pass))
                            .build());
                }
            } catch (Exception e) {
                // 代理地址非法时退回直连，但记日志（内网用户会表现为全部请求超时，无此日志无从定位）
                McpLog.w(
                        "https_proxy invalid, fallback to direct: " + e.getMessage());
            }
        }
        return b.build();
    }

    /** no_proxy 条目匹配：精确相等或域名后缀（.example.com 同时覆盖子域）。 */
    private static boolean matchesNoProxy(String host) {
        String noProxy = envFirst("no_proxy", "NO_PROXY");
        if (noProxy == null || host.isEmpty()) {
            return false;
        }
        for (String entry : noProxy.split(",")) {
            String e = entry.trim().toLowerCase();
            if (e.isEmpty()) {
                continue;
            }
            if (host.equals(e) || (e.startsWith(".") && host.endsWith(e))
                    || host.endsWith("." + e)) {
                return true;
            }
        }
        return false;
    }

    private static String envFirst(String... names) {
        for (String n : names) {
            String v = System.getenv(n);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    public McpServerConfig config() {
        return config;
    }

    /** tools/list；返回空列表表示 server 未暴露工具（协议允许）。 */
    public synchronized List<ToolDef> listTools() throws McpException {
        JsonObject resp = request("tools/list", null);
        List<ToolDef> tools = new ArrayList<>();
        if (resp.has("result") && resp.getAsJsonObject("result").has("tools")) {
            JsonArray arr = resp.getAsJsonObject("result").getAsJsonArray("tools");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject t = el.getAsJsonObject();
                String name = t.has("name") && t.get("name").isJsonPrimitive()
                        ? t.get("name").getAsString() : null;
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String desc = t.has("description") && t.get("description").isJsonPrimitive()
                        ? t.get("description").getAsString() : "";
                JsonObject schema = t.has("inputSchema") && t.get("inputSchema").isJsonObject()
                        ? t.getAsJsonObject("inputSchema") : null;
                boolean ro = false;
                if (t.has("annotations") && t.get("annotations").isJsonObject()) {
                    JsonObject ann = t.getAsJsonObject("annotations");
                    ro = ann.has("readOnlyHint") && ann.get("readOnlyHint").isJsonPrimitive()
                            && ann.get("readOnlyHint").getAsBoolean();
                }
                tools.add(new ToolDef(name, desc, schema, ro));
            }
        }
        return tools;
    }

    /** tools/call。args 为参数对象（可为 null 表示无参）。 */
    public synchronized CallResult callTool(String toolName, JsonObject args) throws McpException {
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", args == null ? new JsonObject() : args);
        JsonObject resp = request("tools/call", params);
        JsonObject result = resp.has("result") && resp.get("result").isJsonObject()
                ? resp.getAsJsonObject("result") : new JsonObject();
        boolean isError = result.has("isError") && result.get("isError").isJsonPrimitive()
                && result.get("isError").getAsBoolean();
        StringBuilder sb = new StringBuilder();
        if (result.has("content") && result.get("content").isJsonArray()) {
            for (JsonElement el : result.getAsJsonArray("content")) {
                if (el.isJsonObject() && el.getAsJsonObject().has("type")
                        && "text".equals(el.getAsJsonObject().get("type").getAsString())
                        && el.getAsJsonObject().has("text")) {
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(el.getAsJsonObject().get("text").getAsString());
                }
            }
        }
        if (sb.length() == 0) {
            sb.append(result.size() == 0 ? "(empty result)" : gson.toJson(result));
        }
        return new CallResult(isError, sb.toString());
    }

    /**
     * 发一次 JSON-RPC 请求并取回 response。非 2xx 或带 JSON-RPC error 时：若尚未
     * initialize 过则先补发 initialize 再重试一次（旧版有状态 server），仍失败抛 McpException。
     */
    private JsonObject request(String method, JsonObject params) throws McpException {
        JsonObject resp = doRequest(method, params);
        if (resp != null && !isFailure(resp)) {
            return resp;
        }
        if (resp != null && !versionHeaderRejected && isVersionRejected(resp)) {
            versionHeaderRejected = true;
            McpLog.i( "mcp version header rejected, retry without header, server="
                    + config.getName());
            resp = doRequest(method, params);
            if (resp != null && !isFailure(resp)) {
                return resp;
            }
        }
        if (!initialized) {
            initialized = true;
            McpLog.i( "mcp retry with initialize, server=" + config.getName());
            doRequest("initialize", initParams());
            resp = doRequest(method, params);
        }
        if (resp == null) {
            throw new McpException("mcp request failed (no response), url=" + config.getUrl()
                    + ", method=" + method);
        }
        if (isFailure(resp)) {
            String detail = resp.has("error") ? gson.toJson(resp.get("error"))
                    : String.valueOf(resp);
            McpLog.w( "mcp rpc error, url=" + config.getUrl()
                    + ", method=" + method + ", error=" + detail);
            throw new McpException("mcp rpc error, url=" + config.getUrl()
                    + ", method=" + method + ", error=" + detail);
        }
        return resp;
    }

    private static boolean isFailure(JsonObject resp) {
        return resp.has("error");
    }

    /** 旧版 server 对未知版本头的典型拒绝（如 -32000 Unsupported protocol version）。 */
    private static boolean isVersionRejected(JsonObject resp) {
        if (!resp.has("error") || !resp.get("error").isJsonObject()) {
            return false;
        }
        JsonObject err = resp.getAsJsonObject("error");
        String msg = err.has("message") && err.get("message").isJsonPrimitive()
                ? err.get("message").getAsString() : "";
        return msg.contains("Unsupported protocol version")
                || msg.contains("UnsupportedProtocolVersion");
    }

    private JsonObject initParams() {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        // 客户端不提供 sampling/roots/elicitation，只消费 tools
        params.add("capabilities", capabilities);
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "weizhi");
        clientInfo.addProperty("version", "1.0");
        params.add("clientInfo", clientInfo);
        return params;
    }

    /** 发 HTTP POST 并解析响应（JSON 或 SSE 首个 data 帧）。HTTP/网络失败返回 null。 */
    private JsonObject doRequest(String method, JsonObject params) {
        JsonObject rpc = new JsonObject();
        rpc.addProperty("jsonrpc", "2.0");
        rpc.addProperty("id", nextId.getAndIncrement());
        rpc.addProperty("method", method);
        if (params != null) {
            rpc.add("params", params);
        }
        Request.Builder rb = new Request.Builder().url(config.getUrl())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .post(RequestBody.create(gson.toJson(rpc), JSON));
        if (!versionHeaderRejected) {
            rb.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        for (Map.Entry<String, String> h : config.getHeaders().entrySet()) {
            rb.header(h.getKey(), h.getValue());
        }
        try (Response resp = http.newCall(rb.build()).execute()) {
            if (!resp.isSuccessful()) {
                String errBody = "";
                okhttp3.ResponseBody errRespBody = resp.body();
                if (errRespBody != null) {
                    errBody = errRespBody.string();
                    if (errBody.length() > 300) {
                        errBody = errBody.substring(0, 300);
                    }
                }
                McpLog.w( "mcp http " + resp.code() + ", url=" + config.getUrl()
                        + ", method=" + method + ", body=" + errBody);
                // 部分 server 用非 2xx 携带 JSON-RPC error（如 -32000 拒绝版本头）：
                // 解析出 error 对象交 request() 走版本降级/initialize 回退，而非直接当网络失败
                JsonObject errResp = parseJson(errBody, method);
                if (errResp != null && errResp.has("error")) {
                    return errResp;
                }
                return null;
            }
            String ct = resp.header("Content-Type", "");
            if (ct == null) {
                ct = "";
            }
            String body = "";
            okhttp3.ResponseBody respBody = resp.body();
            if (respBody != null) {
                body = respBody.string();
            }
            return ct.contains("text/event-stream") ? parseSse(body, method) : parseJson(body, method);
        } catch (IOException e) {
            McpLog.e( "mcp io error, url=" + config.getUrl()
                    + ", method=" + method, e);
            return null;
        }
    }

    private JsonObject parseJson(String body, String method) {
        try {
            JsonElement el = gson.fromJson(body, JsonElement.class);
            if (el != null && el.isJsonObject()) {
                return el.getAsJsonObject();
            }
        } catch (RuntimeException e) {
            McpLog.w( "mcp bad json body, method=" + method + ": "
                    + e.getMessage());
        }
        return null;
    }

    /** SSE 响应解析：逐行找 {@code data:} 帧，取首个带匹配 id 的 JSON-RPC response。 */
    private JsonObject parseSse(String body, String method) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new java.io.ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String payload = line.substring("data:".length()).trim();
                if (payload.isEmpty() || payload.equals("[DONE]")) {
                    continue;
                }
                JsonElement el = gson.fromJson(payload, JsonElement.class);
                if (el == null || !el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                if (o.has("id") && (o.has("result") || o.has("error"))) {
                    return o;    // JSON-RPC response
                }
                // 通知/服务端请求（无 id 或为 request）：v2 核心不处理，记日志忽略
                McpLog.i( "mcp sse notification ignored, server="
                        + config.getName() + ", method=" + method);
            }
        } catch (IOException | RuntimeException e) {
            McpLog.w( "mcp sse parse failed, method=" + method + ": "
                    + e.getMessage());
        }
        return null;
    }
}
