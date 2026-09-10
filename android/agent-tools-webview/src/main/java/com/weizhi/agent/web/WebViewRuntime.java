package com.weizhi.agent.web;

import android.content.Context;
import android.content.MutableContextWrapper;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.weizhi.agent.web.UiExecutor;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SR15 P1:无头 WebView 单例运行时——WebView 生命周期 + 任务串行 + JS 桥 + 超时强杀 + 空闲销毁。
 *
 * <p>线程模型:工具 call() 在 ReAct 工作线程,WebView 一切操作经 {@link UiExecutor} 落主线程;
 * NativeBridge 回调在 WebView 的 JavaBridge 线程;{@link #execute} synchronized 保证单实例
 * 串行(SR15 §2),并发超额由 {@link WebViewQueue} 拒绝。
 *
 * <p>页面隔离(SR15 §4.1):base url {@code https://agent.local/},禁 file/外部资源导航
 * (shouldOverrideUrlLoading 一律拦截),输入全由任务 JSON 带入,页面零外联。
 */
public final class WebViewRuntime {

    private static final String BASE_URL = "https://agent.local/";
    /** bootstrap 加载超时(懒建/强杀后重载的兜底)。 */
    private static final long BOOT_TIMEOUT_MS = 10_000L;
    /** 空闲销毁时长(SR15 §2:5 分钟,下次调用懒重建)。 */
    private static final long IDLE_DESTROY_MS = 5 * 60_000L;
    private static final int MAX_CONSOLE_LINES = 200;
    private static final int MAX_CONSOLE_LINE_CHARS = 1024;

    private static volatile WebViewRuntime instance;

    private final Context appContext;
    private final HandlerUiExecutor ui;
    private final Gson gson = new Gson();
    private final WebViewQueue queue = new WebViewQueue();
    private final AtomicInteger taskSeq = new AtomicInteger();
    private final ConcurrentHashMap<String, TaskEntry> pending = new ConcurrentHashMap<>();
    private final BootstrapAssets bootstrap = new BootstrapAssets();
    private final Runnable idleDestroy = new Runnable() {
        @Override
        public void run() {
            destroyOnUiThread();
        }
    };

    // 仅主线程访问(pendingBoot/activeTaskId 为跨线程只读的 volatile)
    private WebView webView;
    private boolean bootstrapReady;
    /** 正在等待 onPageFinished 的引导请求(主线程写;串行执行下同一时刻至多一个)。 */
    private volatile BootWait pendingBoot;
    /** 当前执行中任务(console 归属)。 */
    private volatile String activeTaskId;

    private WebViewRuntime(Context appContext, HandlerUiExecutor ui) {
        this.appContext = appContext;
        this.ui = ui;
    }

    /** 进程级单例(DCL)。 */
    public static WebViewRuntime getInstance(Context appContext, HandlerUiExecutor ui) {
        WebViewRuntime r = instance;
        if (r == null) {
            synchronized (WebViewRuntime.class) {
                r = instance;
                if (r == null) {
                    r = new WebViewRuntime(appContext.getApplicationContext(), ui);
                    instance = r;
                }
            }
        }
        return r;
    }

    /** 一次执行的结果(工具层消费)。 */
    static final class ExecOutcome {
        final boolean ok;
        /** 成功:bridge 回传的 payloadJson(含 result 字段;分块结果已重组还原)。 */
        final String payloadJson;
        /** 失败原因(native 失败 / bridge 错误 / 超时)。 */
        final String error;
        final List<String> console;
        final long elapsedMs;

        ExecOutcome(boolean ok, String payloadJson, String error, List<String> console, long elapsedMs) {
            this.ok = ok;
            this.payloadJson = payloadJson;
            this.error = error;
            this.console = console;
            this.elapsedMs = elapsedMs;
        }
    }

    /**
     * 提交并同步等待一次执行(串行:synchronized;超时强杀页面保实例)。
     * 调用前 input/wasm 已就绪(工具层在工作线程完成下载/读盘)。
     */
    public ExecOutcome execute(WebViewTask task, String replyId) {
        if (!queue.tryAcquire()) {
            return new ExecOutcome(false, null,
                    "webview_exec 队列已满(" + WebViewQueue.QUEUE_LIMIT + " 个任务在途),请稍后重试。",
                    Collections.<String>emptyList(), 0L);
        }
        long start = System.currentTimeMillis();
        String taskId = "wv-" + taskSeq.incrementAndGet();
        TaskEntry e = new TaskEntry();
        pending.put(taskId, e);
        activeTaskId = taskId;
        try {
            ui.cancel(idleDestroy);
            if (!ensureBootstrap(e)) {
                return new ExecOutcome(false, null, e.failReason, e.console(), elapsed(start));
            }
            String js = BridgeCodec.buildInvokeJs(BridgeCodec.encodeTask(gson, taskId, task));
            WebLog.i(                    "webview_exec " + taskId + " submit: code=" + task.code.length()
                            + " chars, timeout=" + task.timeoutMs + "ms");
            ui.execute(new Runnable() {
                @Override
                public void run() {
                    evaluateOnUiThread(taskId, js, e);
                }
            });
            if (!e.latch.await(task.timeoutMs, TimeUnit.MILLISECONDS)) {
                WebLog.w(                        "webview_exec " + taskId + " TIMEOUT after " + task.timeoutMs + "ms, reset page");
                resetOnUiThread();
                return new ExecOutcome(false, null,
                        "任务超时(" + task.timeoutMs + "ms,页面已重置)。若 code 有写副作用,其结果不可信。",
                        e.console(), elapsed(start));
            }
            if (e.failed()) {
                WebLog.w(                        "webview_exec " + taskId + " FAILED: " + e.failReason);
                return new ExecOutcome(false, null, e.failReason, e.console(), elapsed(start));
            }
            String payload = e.payloadJson;
            if (e.chunked) {
                try {
                    payload = new String(e.assembler.assemble(), StandardCharsets.UTF_8);
                } catch (Exception ex) {
                    WebLog.w(                            "webview_exec " + taskId + " chunk assemble failed: " + ex.getMessage());
                    return new ExecOutcome(false, null,
                            "大结果分块重组失败: " + ex.getMessage(), e.console(), elapsed(start));
                }
            }
            WebLog.i(                    "webview_exec " + taskId + " done " + elapsed(start) + "ms"
                            + (e.chunked ? " (chunked)" : ""));
            return new ExecOutcome(true, payload, null, e.console(), elapsed(start));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ExecOutcome(false, null, "执行等待被中断", e.console(), elapsed(start));
        } finally {
            activeTaskId = null;
            pending.remove(taskId);
            queue.release();
            scheduleIdleDestroy();
        }
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    // ---- bootstrap(主线程) ----

    /** 引导等待信号:post 前由等待线程创建,主线程所有结束路径(就绪/加载中由 onPageFinished/失败)必 countDown。 */
    private static final class BootWait {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean ready;
        volatile String failReason;
    }

    /** 等待引导页就绪(懒建/强杀后重载);失败/超时返回 false(原因在 e.failReason)。 */
    private boolean ensureBootstrap(TaskEntry e) {
        final BootWait wait = new BootWait();
        ui.execute(new Runnable() {
            @Override
            public void run() {
                bootstrapOnUiThread(wait);
            }
        });
        try {
            if (!wait.latch.await(BOOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                e.failNative("WebView 引导超时(" + BOOT_TIMEOUT_MS + "ms)");
                return false;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            e.failNative("等待 WebView 引导被中断");
            return false;
        }
        if (wait.failReason != null) {
            e.failNative(wait.failReason);
            return false;
        }
        return wait.ready;
    }

    private void bootstrapOnUiThread(BootWait wait) {
        try {
            if (webView == null) {
                webView = new WebView(new MutableContextWrapper(appContext));
                WebSettings s = webView.getSettings();
                s.setJavaScriptEnabled(true);
                s.setAllowFileAccess(false);
                s.setAllowContentAccess(false);
                // 页面隔离:只允许内联执行,任何导航(含 iframe/重定向)一律拦截
                webView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                        WebLog.w(                                "webview blocked navigation: " + request.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageFinished(WebView view, String url) {
                        // 只认引导页 base url 的完成信号——重置页(about:blank)迟到的 finished 不放行
                        if (url == null || !url.startsWith(BASE_URL)) {
                            return;
                        }
                        bootstrapReady = true;
                        BootWait w = pendingBoot;
                        pendingBoot = null;
                        if (w != null) {
                            w.ready = true;
                            w.latch.countDown();
                        }
                    }
                });
                webView.setWebChromeClient(new WebChromeClient() {
                    @Override
                    public boolean onConsoleMessage(ConsoleMessage m) {
                        appendConsole(m);
                        return true;
                    }
                });
                webView.addJavascriptInterface(new NativeBridge(), "NativeBridge");
            }
            if (bootstrapReady) {
                wait.ready = true;
                wait.latch.countDown();
                return;
            }
            pendingBoot = wait;
            webView.loadDataWithBaseURL(BASE_URL, bootstrap.load(appContext),
                    "text/html", "utf-8", null);
        } catch (Throwable t) {
            WebLog.e("webview bootstrap failed", t);
            pendingBoot = null;
            wait.failReason = "WebView 引导失败: " + t.getMessage();
            wait.latch.countDown();
        }
    }

    private void evaluateOnUiThread(String taskId, String js, TaskEntry e) {
        WebView w = webView;
        if (w == null) {
            e.failNative("WebView 已销毁(空闲回收竞态)");
            return;
        }
        try {
            w.evaluateJavascript(js, null);
        } catch (Throwable t) {
            WebLog.e(                    "webview evaluateJavascript failed: " + taskId, t);
            e.failNative("任务下发失败: " + t.getMessage());
        }
    }

    /** 超时强杀:重置页面(保实例复用,SR15 §2)。 */
    private void resetOnUiThread() {
        ui.execute(new Runnable() {
            @Override
            public void run() {
                bootstrapReady = false;
                pendingBoot = null;
                if (webView != null) {
                    try {
                        webView.loadUrl("about:blank");
                    } catch (Throwable t) {
                        WebLog.w(                                "webview reset failed: " + t.getMessage());
                    }
                }
            }
        });
    }

    private void scheduleIdleDestroy() {
        ui.schedule(idleDestroy, IDLE_DESTROY_MS);
    }

    private void destroyOnUiThread() {
        if (webView != null) {
            try {
                webView.removeJavascriptInterface("NativeBridge");
                webView.destroy();
            } catch (Throwable t) {
                WebLog.w(                        "webview idle destroy failed: " + t.getMessage());
            }
            WebLog.i("webview idle destroyed (5min)");
            webView = null;
        }
        bootstrapReady = false;
        pendingBoot = null;
    }

    // ---- console(主线程回调) ----

    private void appendConsole(ConsoleMessage m) {
        String taskId = activeTaskId;
        if (taskId == null) {
            return;
        }
        TaskEntry e = pending.get(taskId);
        if (e != null) {
            e.appendConsole(formatConsole(m));
        }
    }

    private static String formatConsole(ConsoleMessage m) {
        String level = m.messageLevel() == ConsoleMessage.MessageLevel.ERROR
                ? "[error] " : m.messageLevel() == ConsoleMessage.MessageLevel.WARNING
                ? "[warn] " : "";
        String text = m.message() == null ? "" : m.message();
        if (text.length() > MAX_CONSOLE_LINE_CHARS) {
            text = text.substring(0, MAX_CONSOLE_LINE_CHARS) + "...(截断)";
        }
        return level + text;
    }

    // ---- JS → native 桥(JavaBridge 线程) ----

    private final class NativeBridge {

        @JavascriptInterface
        public void onResult(String taskId, String payload, boolean isError) {
            TaskEntry e = pending.get(taskId);
            if (e == null) {
                WebLog.w(                        "webview onResult for unknown/finished task: " + taskId);
                return;
            }
            try {
                if (isError) {
                    e.error(extractError(payload));
                    return;
                }
                JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                if (o.has("chunked")) {
                    e.chunked = true;
                    e.finishOk(null);
                } else {
                    e.finishOk(payload);
                }
            } catch (Exception ex) {
                e.error("结果解析失败: " + ex.getMessage());
            }
        }

        @JavascriptInterface
        public void onChunk(String taskId, int seq, int total, String b64) {
            TaskEntry e = pending.get(taskId);
            if (e == null) {
                WebLog.w(                        "webview onChunk for unknown/finished task: " + taskId + " seq=" + seq);
                return;
            }
            try {
                e.assembler.feed(seq, total, b64);
            } catch (Exception ex) {
                e.error("分块回传异常: " + ex.getMessage());
            }
        }

        private String extractError(String payload) {
            try {
                JsonObject o = JsonParser.parseString(payload).getAsJsonObject();
                return o.has("result") && !o.get("result").isJsonNull()
                        ? o.get("result").getAsString() : payload;
            } catch (Exception ex) {
                return payload;
            }
        }
    }

    /** 单任务等待槽(latch + 结果/错误/分块重组器 + console 收集)。 */
    private static final class TaskEntry {
        final CountDownLatch latch = new CountDownLatch(1);
        final BridgeCodec.ChunkAssembler assembler = new BridgeCodec.ChunkAssembler();
        private final List<String> console = Collections.synchronizedList(new ArrayList<String>());
        private final AtomicBoolean failed = new AtomicBoolean();
        volatile String failReason;
        volatile String payloadJson;
        volatile boolean chunked;

        boolean failed() {
            return failed.get();
        }

        void finishOk(String json) {
            payloadJson = json;
            latch.countDown();
        }

        void error(String msg) {
            if (failed.compareAndSet(false, true)) {
                failReason = msg;
                latch.countDown();
            }
        }

        void failNative(String msg) {
            error("native: " + msg);
        }

        void appendConsole(String line) {
            if (console.size() < MAX_CONSOLE_LINES) {
                console.add(line);
            }
        }

        List<String> console() {
            synchronized (console) {
                return new ArrayList<>(console);
            }
        }
    }
}
