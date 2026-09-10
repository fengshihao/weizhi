package com.weizhi.agent.web;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.Base64;
import java.util.TreeMap;

/**
 * SR15 P1:webview_exec 的 native ↔ bridge.js 编解码协议(纯 JVM 可单测)。
 *
 * <p>下发:{@link #encodeTask} 组任务 JSON(taskId/code/inputB64/wasmB64),Gson 序列化保证
 * code 内的引号/换行等被安全转义,{@link #buildInvokeJs} 包成 evaluateJavascript 表达式。
 *
 * <p>回传:小结果(≤{@link #INLINE_LIMIT})bridge 直接 onResult(payloadJson);大结果整体
 * UTF-8→base64 按 {@link #CHUNK_B64_CHARS} 分块 onChunk(seq,total),收尾 onResult(chunked 标记);
 * native 侧 {@link ChunkAssembler} 按序重组(缺块/重复/超限抛带原因异常),再还原 payloadJson。
 */
public final class BridgeCodec {

    /** 超过此长度(字符)的结果走分块回传(SR15 §2:64KB)。 */
    public static final int INLINE_LIMIT = 64 * 1024;
    /** 单块 base64 字符数(48KB,须与 bridge.js 常量一致)。 */
    public static final int CHUNK_B64_CHARS = 48 * 1024;

    private BridgeCodec() {
    }

    /** 任务下发 JSON:{"taskId":..,"code":..,"inputB64":..,"wasmB64":..}(可空字段省略)。 */
    public static String encodeTask(Gson gson, String taskId, WebViewTask task) {
        StringBuilder sb = new StringBuilder(64 + task.code.length());
        sb.append("{\"taskId\":").append(gson.toJson(taskId));
        sb.append(",\"code\":").append(gson.toJson(task.code));
        if (task.inputB64 != null) {
            sb.append(",\"inputB64\":").append(gson.toJson(task.inputB64));
        }
        if (task.wasmB64 != null) {
            sb.append(",\"wasmB64\":").append(gson.toJson(task.wasmB64));
        }
        sb.append("}");
        return sb.toString();
    }

    /** evaluateJavascript 下发表达式。 */
    public static String buildInvokeJs(String taskJson) {
        return "window.__agentRun(" + taskJson + ");";
    }

    /**
     * 大结果分块重组器:单任务实例,bridge 的 onChunk 回调线程与等待线程并发访问,方法级同步。
     */
    public static final class ChunkAssembler {

        private final TreeMap<Integer, String> chunks = new TreeMap<>();
        private int total = -1;
        private long b64Len;

        /** 喂入一块;seq/total 非法、重复块、累计超输出上限时抛 IOException(带原因)。 */
        public synchronized void feed(int seq, int total, String b64) throws IOException {
            if (seq < 0 || total <= 0 || seq >= total) {
                throw new IOException("chunk 元数据非法: seq=" + seq + " total=" + total);
            }
            if (this.total != -1 && this.total != total) {
                throw new IOException("chunk total 不一致: " + this.total + " vs " + total);
            }
            this.total = total;
            if (chunks.put(seq, b64) != null) {
                throw new IOException("chunk 重复: seq=" + seq);
            }
            b64Len += b64 == null ? 0 : b64.length();
            // base64 长度 ≈ 原始 4/3,提前在 b64 层拦截超限(免 decode 后才发现 100MB+)
            if (b64Len > (WebViewTask.MAX_OUTPUT_BYTES / 3 + 4) * 4) {
                throw new IOException("结果超过上限 " + WebViewTask.MAX_OUTPUT_BYTES + " 字节");
            }
        }

        public synchronized boolean isComplete() {
            return total > 0 && chunks.size() == total;
        }

        /** 全部块到齐后重组:join → base64 decode → 字节数终检。未到齐/超限抛 IOException。 */
        public synchronized byte[] assemble() throws IOException {
            if (!isComplete()) {
                throw new IOException("chunk 未到齐: " + chunks.size() + "/" + total);
            }
            StringBuilder sb = new StringBuilder((int) b64Len);
            for (int i = 0; i < total; i++) {
                sb.append(chunks.get(i));
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(sb.toString());
            } catch (IllegalArgumentException e) {
                throw new IOException("chunk base64 非法: " + e.getMessage());
            }
            if (bytes.length > WebViewTask.MAX_OUTPUT_BYTES) {
                throw new IOException("结果超过上限 " + WebViewTask.MAX_OUTPUT_BYTES + " 字节: "
                        + bytes.length);
            }
            return bytes;
        }
    }
}
