package com.weizhi.agent.web;

/**
 * SR15 P1:webview_exec 一次执行的参数快照与静态校验(纯 JVM 可单测)。
 *
 * <p>尺寸/超时上限(安全边界见 SR15 §4):code ≤1MB、wasm ≤50MB、input ≤20MB、输出 ≤100MB;
 * 超限返回带原因错误串供 LLM 自纠正(质量硬性要求 3)。
 */
public final class WebViewTask {

    /** code 上限(字符)。 */
    public static final int MAX_CODE_CHARS = 1 << 20;
    /** input_path 文件上限(字节;base64 后约 27MB,经 evaluateJavascript 单次下发)。 */
    public static final long MAX_INPUT_BYTES = 20L << 20;
    /** wasm 下载上限(字节)。 */
    public static final long MAX_WASM_BYTES = 50L << 20;
    /** 输出重组上限(字节)。 */
    public static final long MAX_OUTPUT_BYTES = 100L << 20;
    /** 任务超时:默认 60s,下限 1s,上限 600s(SR15 §3.1)。 */
    public static final long DEFAULT_TIMEOUT_MS = 60_000L;
    public static final long MIN_TIMEOUT_MS = 1_000L;
    public static final long MAX_TIMEOUT_MS = 600_000L;

    final String code;
    /** input_path 内容 base64(可空)。 */
    final String inputB64;
    /** wasm_url 下载内容 base64(可空)。 */
    final String wasmB64;
    /** 输出落盘 workspace 相对路径(可空)。 */
    final String outputRel;
    final long timeoutMs;

    public WebViewTask(String code, String inputB64, String wasmB64, String outputRel, long timeoutMs) {
        this.code = code;
        this.inputB64 = inputB64;
        this.wasmB64 = wasmB64;
        this.outputRel = outputRel;
        this.timeoutMs = timeoutMs;
    }

    /** 静态校验(native 侧可判的入参):返回带原因错误串,null=通过。 */
    public static String validate(String code, String wasmUrl) {
        if (code == null || code.trim().isEmpty()) {
            return "code 不能为空:请提供要执行的 JavaScript 代码(顶层 return 返回结果)。";
        }
        if (code.length() > MAX_CODE_CHARS) {
            return "code 超过上限(" + code.length() + " > " + MAX_CODE_CHARS
                    + " 字符)。超大脚本请先 write_file 落盘后拆分,或精简逻辑。";
        }
        if (wasmUrl != null && !wasmUrl.trim().isEmpty()) {
            String t = wasmUrl.trim();
            if (!t.startsWith("http://") && !t.startsWith("https://")) {
                return "wasm_url 仅支持 http/https(收到: " + t + ")。";
            }
        }
        return null;
    }

    /** 超时钳制:[MIN, MAX];非法值(<=0)取默认。 */
    public static long clampTimeout(long v) {
        if (v <= 0) {
            return DEFAULT_TIMEOUT_MS;
        }
        return Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, v));
    }
}
