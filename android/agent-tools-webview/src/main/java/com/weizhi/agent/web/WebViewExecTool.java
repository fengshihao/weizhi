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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * SR15 P1:webview_exec——无头 WebView(Chromium 内核)执行 JS,承载 QuickJS 跑不了的
 * wasm / DOM / canvas 类重任务(PDF 生成等)。
 *
 * <p>流程:native 侧校验+下载 wasm+读 input(base64)→ {@link WebViewRuntime} 串行下发 →
 * 结果预览/console 回执;大结果(>64KB)由 bridge 分块回传,按 output_path 经 Sandbox 落盘。
 * output_path 写入返回值的 UTF-8 文本;JSON null 没有可落盘内容,不写文件。
 */
public class WebViewExecTool {

    private static final Gson GSON = new Gson();
    /** wasm 下载专用 client(50MB 级,读超时放宽;静态复用连接池)。 */
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .callTimeout(180, TimeUnit.SECONDS)
            .readTimeout(150, TimeUnit.SECONDS)
            .build();
    /** 回执里结果预览长度(字符,SR15 §3.1:前 1KB)。 */
    private static final int PREVIEW_CHARS = 1024;

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
                    + "output_path 写入的是返回值的 UTF-8 文本,不是按扩展名生成的二进制文件;"
                    + "若 return 的是 Base64,文件内容就是这段 Base64。"
                    + "返回 null 或 undefined 且提供了 output_path 时 ok 为 false,不会把文本 null 写入文件。"
                    + "成功回执含 resultType(null、string、number、boolean、object、array);"
                    + "字符串 \"null\" 与 JSON null 靠 resultType 区分。"
                    + "结果 >64KB 时须提供 output_path 落盘(回执只含路径+预览)。"
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
            @ToolParam(name = "output_path", required = false,
                    description = "结果落盘路径(工作区内相对路径)。写入返回值的 UTF-8 文本,"
                            + "扩展名不表示二进制格式;return Base64 则文件内容就是这段 Base64。"
                            + "返回 null 或 undefined 时拒绝落盘(ok:false),不会写入文本 null。"
                            + "大结果(>64KB)必须提供,回执返回路径+预览")
                    String outputPath,
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
        boolean needSandbox = (inputPath != null && !inputPath.trim().isEmpty())
                || (outputPath != null && !outputPath.trim().isEmpty());
        if (needSandbox && sandbox == null) {
            return errJson("宿主未配置 workspace 沙箱,input_path/output_path 不可用。");
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

        // —— output 预校验(路径非法即拒绝,免任务跑完才发现写不了)——
        String outputRel = outputPath != null ? outputPath.trim() : "";
        if (!outputRel.isEmpty()) {
            try {
                sandbox.resolveWrite(outputRel);
            } catch (SecurityException e) {
                return errJson("output_path 非法: " + e.getMessage());
            }
        }

        WebViewTask task = new WebViewTask(code, inputB64, wasmB64,
                outputRel.isEmpty() ? null : outputRel, timeout);
        WebViewRuntime.ExecOutcome o = runtime.execute(task, null);

        if (!o.ok) {
            return errJson(o.error);
        }
        return renderOk(task, o);
    }

    /** 组回执:结果类型 + 预览(1KB)+ console + 落盘路径。JSON null 且要求落盘时 ok:false,不写文件。 */
    String renderOk(WebViewTask task, WebViewRuntime.ExecOutcome o) {
        WebViewResult parsed = WebViewResult.parse(o.payloadJson);
        if (parsed.parseError != null) {
            WebLog.w("webview_exec payload parse failed: " + parsed.parseError);
            return errJson("结果解析失败: " + parsed.parseError);
        }
        if (task.outputRel != null && parsed.spillUtf8 == null) {
            return errJson("没有可落盘的返回值:脚本返回了 null 或 undefined。"
                    + "output_path 写入的是返回值的 UTF-8 文本,不会把文本 null 写入文件。"
                    + "请在 code 中 return 要保存的内容;若是图片,return Base64 字符串"
                    + "(文件内容就是这段 Base64,不会按扩展名解码成二进制)。");
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("elapsedMs", o.elapsedMs);
        if (parsed.unserializable) {
            m.put("note", "结果不可 JSON 序列化,已降级为字符串形态");
        }
        // 落盘(提供了 output_path 且返回值非 null 才写,不问大小)
        if (task.outputRel != null) {
            try {
                Path p = sandbox.resolveWrite(task.outputRel);
                if (p.getParent() != null) {
                    Files.createDirectories(p.getParent());
                }
                byte[] bytes = parsed.spillUtf8;
                Files.write(p, bytes);
                m.put("outputPath", task.outputRel);
                m.put("outputBytes", bytes.length);
                WebLog.i("webview_exec spill " + bytes.length + " bytes -> " + task.outputRel);
            } catch (IOException | SecurityException e) {
                WebLog.w("webview_exec output write failed: " + task.outputRel + ": " + e.getMessage());
                return errJson("任务执行成功但结果落盘失败(" + task.outputRel + "): "
                        + e.getMessage() + "。结果预览: " + preview(parsed.text));
            }
        } else if (parsed.text.length() > BridgeCodec.INLINE_LIMIT) {
            m.put("hint", "结果 " + parsed.text.length() + " 字符未保存:未提供 output_path,"
                    + "请带 output_path 重跑获取完整结果。");
        }
        m.put("resultType", parsed.resultType);
        m.put("resultPreview", preview(parsed.text));
        if (!o.console.isEmpty()) {
            m.put("console", o.console);
        }
        return GSON.toJson(m);
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
