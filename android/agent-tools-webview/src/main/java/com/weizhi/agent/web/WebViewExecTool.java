package com.weizhi.agent.web;

import com.google.gson.Gson;
import com.weizhi.agent.sandbox.WorkspaceSandbox;
import com.weizhi.agent.tool.Tool;
import com.weizhi.agent.tool.ToolParam;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * SR15 P1:webview_exec——无头 WebView(Chromium 内核)执行 JS,承载 QuickJS 跑不了的
 * wasm / DOM / canvas 类重任务(PDF 生成等)。
 *
 * <p>流程:native 侧校验+下载 wasm+读 input(base64)→ {@link WebViewRuntime} 串行下发 →
 * 结果预览/console 回执。不超过 {@link #AUTO_SPILL_BYTES} 的结果放进回执;
 * 超过则自动写入工作区 {@code tmp/webview-<时间>-<序号>.txt}
 * (与 zip 默认解压、docx 解包同一临时目录),回执只带回相对路径。
 * 文件是返回值的 UTF-8 文本。JSON null 不写文件。调用方不指定输出路径。
 */
public class WebViewExecTool {

    private static final Gson GSON = new Gson();
    /** wasm 下载专用 client(50MB 级,读超时放宽;静态复用连接池)。 */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .callTimeout(180, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .build();
    /** 回执里结果预览长度(字符,SR15 §3.1:前 1KB)。仅自动落盘时截断。 */
    private static final int PREVIEW_CHARS = 1024;
    /**
     * 超过此字节数自动落盘。与 bridge 内联上限一致(64KB),调用方不传路径。
     * 临时文件在工作区 {@code tmp/} 下,和 zip 默认解压目录、docx 的 {@code tmp/docx-*} 同一约定。
     */
    static final int AUTO_SPILL_BYTES = BridgeCodec.INLINE_LIMIT;
    private static final AtomicInteger SPILL_SEQ = new AtomicInteger();

    private final WebViewRuntime runtime;
    private final WorkspaceSandbox sandbox;

    public WebViewExecTool(WebViewRuntime runtime, WorkspaceSandbox sandbox) {
        this.runtime = runtime;
        this.sandbox = sandbox;
    }

    @Tool(name = "webview_exec",
            description = "在无头 WebView(Chromium 内核,支持 WebAssembly/DOM/canvas)中执行一段 JavaScript,"
                    + "适合 PDF 生成、格式转换、wasm 重计算等 run_js(QuickJS)跑不了的任务;"
                    + "普通计算请直接用 run_js。code 顶层 return 返回结果;"
                    + "可用全局:input(input_path 文件的 Uint8Array,未提供则 null)、"
                    + "loadWasm()——返回 wasm_url 模块的 WebAssembly.Module Promise、console.log(随回执返回)。"
                    + "不超过 64KB 的结果完整放在回执 resultPreview。"
                    + "超过 64KB 时自动写入工作区临时文件 tmp/webview-<时间>-<序号>.txt,"
                    + "回执给出 outputPath 与 outputBytes,resultPreview 只含前 1KB;不要指定输出路径。"
                    + "该文件是返回值的 UTF-8 文本,不是按扩展名生成的二进制;"
                    + "若 return Base64,文件内容就是这段 Base64。"
                    + "返回 null 或 undefined 不写文件。"
                    + "成功回执含 resultType(null、string、number、boolean、object、array);"
                    + "字符串 \"null\" 与 JSON null 靠 resultType 区分。"
                    + "任务超时(timeout_ms,默认 60000,上限 600000)后页面被强杀重置。",
            readOnly = false, concurrencySafe = false)
    public String webviewExec(
            @ToolParam(name = "code", description = "要执行的 JavaScript 代码(顶层 return 返回结果;异步任务返回 Promise)")
                    String code,
            @ToolParam(name = "wasm_url", required = false,
                    description = "WebAssembly 模块 URL(http/https,≤50MB),脚本内 loadWasm() 取用")
                    String wasmUrl,
            @ToolParam(name = "input_path", required = false,
                    description = "输入文件路径(工作区内,≤20MB),脚本内以 Uint8Array 全局变量 input 取用")
                    String inputPath,
            @ToolParam(name = "timeout_ms", required = false,
                    description = "任务超时毫秒数(默认 60000,上限 600000)")
                    String timeoutMs) {
        // —— 入参校验(硬性要求 3:错误带原因供自纠正)——
        String verr = WebViewTask.validate(code, wasmUrl);
        if (verr != null) {
            return errJson(verr);
        }
        long timeout = WebViewTask.DEFAULT_TIMEOUT_MS;
        if (timeoutMs != null && !timeoutMs.trim().isEmpty()) {
            try {
                timeout = Long.parseLong(timeoutMs.trim());
            } catch (NumberFormatException e) {
                return errJson("timeout_ms 必须是毫秒整数(收到: " + timeoutMs + ")。");
            }
        }
        timeout = WebViewTask.clampTimeout(timeout);
        boolean needSandbox = inputPath != null && !inputPath.trim().isEmpty();
        if (needSandbox && sandbox == null) {
            return errJson("宿主未配置 workspace 沙箱,input_path 不可用。");
        }

        // —— wasm 下载(native 侧,页面零外联;SR15 §4.1)——
        String wasmB64 = null;
        if (wasmUrl != null && !wasmUrl.trim().isEmpty()) {
            byte[] wasm = downloadWasm(wasmUrl.trim());
            if (wasm == null) {
                return errJson("wasm_url 下载失败: " + wasmUrl + "(详见日志);请确认 URL 可达与大小 ≤50MB。");
            }
            wasmB64 = Base64.getEncoder().encodeToString(wasm);
        }

        // —— input 读取(WorkspaceSandbox 校验)——
        String inputB64 = null;
        if (inputPath != null && !inputPath.trim().isEmpty()) {
            byte[] bytes = readInput(inputPath.trim());
            if (bytes == null) {
                return errJson("input_path 读取失败: " + inputPath + "(详见日志)。");
            }
            inputB64 = Base64.getEncoder().encodeToString(bytes);
        }

        WebViewTask task = new WebViewTask(code, inputB64, wasmB64, timeout);
        WebViewRuntime.ExecOutcome o = runtime.execute(task, null);

        if (!o.ok) {
            return errJson(o.error);
        }
        return renderOk(o);
    }

    /** 组回执。超过 64KB 的非 null 结果自动写入 tmp/,小结果完整留在 resultPreview。 */
    String renderOk(WebViewRuntime.ExecOutcome o) {
        WebViewResult parsed = WebViewResult.parse(o.payloadJson);
        if (parsed.parseError != null) {
            WebLog.w("webview_exec payload parse failed: " + parsed.parseError);
            return errJson("结果解析失败: " + parsed.parseError);
        }
        boolean spill = parsed.spillUtf8 != null && parsed.spillUtf8.length > AUTO_SPILL_BYTES;
        if (spill && sandbox == null) {
            return errJson("结果 " + parsed.spillUtf8.length
                    + " 字节超过 64KB,但宿主未配置 workspace 沙箱,无法写入临时文件。");
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("elapsedMs", o.elapsedMs);
        if (parsed.unserializable) {
            m.put("note", "结果不可 JSON 序列化,已降级为字符串形态");
        }
        if (spill) {
            String rel;
            try {
                rel = allocateSpillRel();
                Path p = sandbox.resolveWrite(rel);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                Files.write(p, parsed.spillUtf8);
                m.put("outputPath", rel);
                m.put("outputBytes", parsed.spillUtf8.length);
                WebLog.i("webview_exec spill " + parsed.spillUtf8.length + " bytes -> " + rel);
            } catch (IOException | SecurityException e) {
                WebLog.w("webview_exec output write failed: " + e.getMessage());
                return errJson("结果超过 64KB,但写入临时文件失败: " + e.getMessage()
                        + "。结果预览: " + preview(parsed.text));
            }
        }
        m.put("resultType", parsed.resultType);
        m.put("resultPreview", spill ? preview(parsed.text) : parsed.text);
        if (!o.console.isEmpty()) {
            m.put("console", o.console);
        }
        return GSON.toJson(m);
    }

    /** 工作区相对路径 tmp/webview-时间-序号.txt。已存在则换序号。 */
    private String allocateSpillRel() throws IOException {
        for (int n = 0; n < 8; n++) {
            String rel = "tmp/webview-" + System.currentTimeMillis()
                    + "-" + SPILL_SEQ.incrementAndGet() + ".txt";
            if (!Files.exists(sandbox.resolveWrite(rel))) {
                return rel;
            }
        }
        throw new IOException("无法分配 tmp/webview-*.txt");
    }

    private static String preview(String s) {
        return s.length() <= PREVIEW_CHARS ? s : s.substring(0, PREVIEW_CHARS)
                + "...(截断,共 " + s.length() + " 字符)";
    }

    private static String errJson(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", msg);
        return GSON.toJson(m);
    }

    /** 读 input 文件(WorkspaceSandbox 校验 + 尺寸上限);失败记日志返回 null。 */
    private byte[] readInput(String inputPath) {
        Path p;
        try {
            p = sandbox.resolveRead(inputPath);
        } catch (SecurityException e) {
            WebLog.w(                    "webview_exec input_path escapes sandbox: " + inputPath + ": " + e.getMessage());
            return null;
        }
        if (!Files.isRegularFile(p)) {
            WebLog.w("webview_exec input not found: " + inputPath);
            return null;
        }
        try {
            long size = Files.size(p);
            if (size > WebViewTask.MAX_INPUT_BYTES) {
                WebLog.w(                        "webview_exec input too large: " + inputPath + " " + size + " bytes");
                return null;
            }
            return Files.readAllBytes(p);
        } catch (IOException e) {
            WebLog.w(                    "webview_exec input read failed: " + inputPath + ": " + e.getMessage());
            return null;
        }
    }

    /** 下载 wasm(流式累计限长 50MB);失败记日志返回 null。 */
    private static byte[] downloadWasm(String url) {
        Request req;
        try {
            req = new Request.Builder().url(url).build();
        } catch (IllegalArgumentException e) {
            WebLog.w("webview_exec wasm url invalid: " + url);
            return null;
        }
        try (Response resp = HTTP.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                WebLog.w(                        "webview_exec wasm download http " + resp.code() + ": " + url);
                return null;
            }
            ResponseBody body = resp.body();
            if (body == null) {
                WebLog.w("webview_exec wasm empty body: " + url);
                return null;
            }
            if (body.contentLength() > WebViewTask.MAX_WASM_BYTES) {
                WebLog.w(                        "webview_exec wasm too large: " + url + " " + body.contentLength() + " bytes");
                return null;
            }
            try (InputStream in = body.byteStream()) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                long total = 0L;
                int n;
                while ((n = in.read(b)) > 0) {
                    total += n;
                    if (total > WebViewTask.MAX_WASM_BYTES) {
                        WebLog.w(                                "webview_exec wasm exceeds limit: " + url + " >50MB");
                        return null;
                    }
                    buf.write(b, 0, n);
                }
                return buf.toByteArray();
            }
        } catch (IOException e) {
            WebLog.w(                    "webview_exec wasm download failed: " + url + ": " + e.getMessage());
            return null;
        }
    }
}
