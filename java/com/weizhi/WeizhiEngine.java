package com.weizhi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.weizhi.platform.MiniJson;
import java.lang.ref.Cleaner;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Weizhi Java wrapper. Async I/O uses {@link ExecutorService} by default, then completes back into the C queue.
 * Agents write JS only; this class is for the app host.
 */
public final class WeizhiEngine implements AutoCloseable {
    /** Matches WEIZHI_DEFAULT_MAX_ASYNC_IO in weizhi.h. */
    private static final int DEFAULT_MAX_ASYNC_IO = 16;
    /** Matches WEIZHI_DEFAULT_FS_IO_BYTES in weizhi.h. */
    private static final long DEFAULT_FS_IO = 32L * 1024L * 1024L;

    static {
        System.loadLibrary("weizhijni");
    }

    /** 兜底回收 native 句柄：宿主忘记 {@link #close()} 时避免泄漏；守护线程不影响 JVM 退出。 */
    private static final Cleaner NATIVE_CLEANER = Cleaner.create(task -> {
        Thread t = new Thread(task, "weizhi-native-cleaner");
        t.setDaemon(true);
        return t;
    });

    private long nativeHandle;
    private final AtomicBoolean nativeReleased = new AtomicBoolean(false);
    private final Cleaner.Cleanable nativeGuard;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private final long maxIoBytes;
    private String fsRoot;
    private String[] fetchHostAllowlist;
    private boolean fetchEnabled;
    private boolean hostCallInstalled;
    private HostCall hostCall;
    /** Java 侧镜像的取消标记：cancel() 后排队中的异步任务直接拒绝，避免取消后仍执行全量 I/O。 */
    private volatile boolean cancelRequested;

    /** Sync host bridge used by platform objects (`android` / `mac` / `linux`). Args and return are JSON. */
    public interface HostCall {
        String call(String argsJson);
    }

    public WeizhiEngine() {
        this(null, null);
    }

    public WeizhiEngine(WeizhiLimits limits) {
        this(limits, null);
    }

    public WeizhiEngine(ExecutorService executor) {
        this(null, executor);
    }

    public WeizhiEngine(WeizhiLimits limits, ExecutorService executor) {
        int asyncIo = resolveAsyncIo(limits);
        this.maxIoBytes = limits != null && limits.fsIoBytes > 0 ? limits.fsIoBytes : DEFAULT_FS_IO;
        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            // 有界队列：脚本可以疯狂排队 async I/O，不能让队列无界增长吃内存；
            // 拒绝时由 onVfsAsync/onFetchAsync 以错误完成请求。
            int queueCapacity = Math.max(asyncIo * 4, 64);
            ThreadPoolExecutor pool = new ThreadPoolExecutor(
                    asyncIo, asyncIo, 60L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(queueCapacity),
                    task -> {
                        Thread t = new Thread(task, "weizhi-async-io");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
            this.executor = pool;
            this.ownsExecutor = true;
        }
        this.nativeHandle = nativeOpen(
                limits == null ? 0 : limits.jsHeapBytes,
                limits == null ? 0 : limits.jsStackBytes,
                limits == null ? 0 : limits.maxHostFunctions,
                limits == null ? 0 : limits.fsIoBytes,
                limits == null ? 0 : limits.maxAsyncIo);
        if (this.nativeHandle == 0) {
            if (this.ownsExecutor) {
                this.executor.shutdownNow();
            }
            throw new IllegalStateException("weizhi_open failed");
        }
        this.nativeGuard = NATIVE_CLEANER.register(this, new NativeHandleGuard(
                this.nativeHandle, this.nativeReleased, this.ownsExecutor ? this.executor : null));
    }

    /** 只允许 close() 与 GC Cleaner 其中一方释放一次 native 句柄。 */
    private static final class NativeHandleGuard implements Runnable {
        private final long handle;
        private final AtomicBoolean released;
        private final ExecutorService ownedExecutor;

        NativeHandleGuard(long handle, AtomicBoolean released, ExecutorService ownedExecutor) {
            this.handle = handle;
            this.released = released;
            this.ownedExecutor = ownedExecutor;
        }

        @Override
        public void run() {
            if (handle != 0 && released.compareAndSet(false, true)) {
                nativeClose(handle);
            }
            if (ownedExecutor != null) {
                ownedExecutor.shutdownNow();
            }
        }
    }

    private static int resolveAsyncIo(WeizhiLimits limits) {
        if (limits != null && limits.maxAsyncIo > 0) {
            return limits.maxAsyncIo;
        }
        return DEFAULT_MAX_ASYNC_IO;
    }

    public void setFsRoot(String folder) {
        if (nativeSetFsRoot(nativeHandle, folder) != 0) {
            throw new IllegalArgumentException("setFsRoot failed");
        }
        this.fsRoot = folder;
        nativeInstallJavaAsyncVfs(nativeHandle);
    }

    /** Folder for {@code import './file.js'} libraries (leaf names only). */
    public void setScriptFolder(String folder) {
        if (nativeSetScriptFolder(nativeHandle, folder) != 0) {
            throw new IllegalArgumentException("setScriptFolder failed");
        }
    }

    /**
     * Enable {@code globalThis.fetch}. Optional host suffix allowlist (e.g. {@code "example.com"});
     * {@code null} allows any http(s) host. Agents get clear errors when blocked.
     */
    public void enableFetch(String[] hostSuffixAllowlist) {
        this.fetchHostAllowlist = hostSuffixAllowlist;
        this.fetchEnabled = true;
        nativeInstallJavaHttp(nativeHandle);
    }

    public void enableFetch() {
        enableFetch(null);
    }

    /**
     * Install a <b>mock</b> native-plugin host (catalog / download / signature / "dlopen" are simulated).
     * Agents call {@code await host.ensureNative("echo_math")} then {@code plugin.add([1,2])}.
     * Built-in mock catalog: {@code echo_math}, {@code bad_sig}, {@code too_new}.
     */
    public void enableNativeMock() {
        nativeInstallJavaNative(nativeHandle);
    }

    /**
     * Enable typed plugin loader ({@code dir/&lt;name&gt;/manifest.json} + {@code lib&lt;name&gt;.so}).
     * See docs/NATIVE_PLUGIN_IDL.md.
     */
    public void enableNativePlugins(String pluginDir) {
        if (nativeEnablePluginLoader(nativeHandle, pluginDir) != 0) {
            throw new IllegalArgumentException("enableNativePlugins failed");
        }
    }

    /**
     * Install the single sync host entry {@code __caps(args)} used by a platform object.
     * Call while idle (before or between {@code runJs}). The Java callback may be replaced later;
     * the native binding is installed once per engine.
     */
    public void setHostCall(HostCall call) {
        this.hostCall = call;
        if (!hostCallInstalled) {
            if (nativeInstallHostCall(nativeHandle) != 0) {
                throw new IllegalStateException("setHostCall failed");
            }
            hostCallInstalled = true;
        }
    }

    /** Current {@code __caps} handler; null if not installed. Used to chain agent tool bridge. */
    public HostCall getHostCall() {
        return hostCall;
    }

    /** Called from JNI on the JS thread. */
    @SuppressWarnings("unused")
    String onHostCall(String argsJson) {
        HostCall call = this.hostCall;
        if (call == null) {
            return "{\"error\":\"unsupported: host call\"}";
        }
        try {
            String out = call.call(argsJson);
            return out == null ? "null" : out;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return "{\"error\":" + quoteJson(msg) + "}";
        }
    }

    static String quoteJson(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                case '\\':
                    sb.append('\\').append(c);
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** Called from JNI: mock catalog → verify → complete_native on the pool thread. */
    @SuppressWarnings("unused")
    void onNativeEnsure(long engine, long requestId, String name) {
        executor.execute(() -> {
            try {
                Thread.sleep(15); // pretend download
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (name == null || name.isEmpty()) {
                nativeCompleteNative(engine, requestId, false, null, "bad argument: host.ensureNative: empty name");
                return;
            }
            if ("bad_sig".equals(name)) {
                nativeCompleteNative(engine, requestId, false, null, "native verify failed: signature");
                return;
            }
            if ("too_new".equals(name)) {
                nativeCompleteNative(engine, requestId, false, null,
                        "native incompatible: min_host_abi 99 > host " + 1);
                return;
            }
            if ("echo_math".equals(name)) {
                // Mock: verified + "loaded" — exports implemented in onNativeCall (no real .so).
                nativeCompleteNative(engine, requestId, true,
                        "{\"name\":\"echo_math\",\"version\":\"1.0.0-mock\",\"exports\":[\"add\",\"mul\"]}", null);
                return;
            }
            nativeCompleteNative(engine, requestId, false, null,
                    "unsupported: native \"" + name + "\" (not in catalog)");
        });
    }

    /** Sync plugin export call from the JS thread (mock SO body). */
    @SuppressWarnings("unused")
    String onNativeCall(String plugin, String exportName, String argsJson) {
        if (!"echo_math".equals(plugin)) {
            return null;
        }
        double[] nums = parseNumberArray(argsJson);
        if ("add".equals(exportName)) {
            double a = nums.length > 0 ? nums[0] : 0;
            double b = nums.length > 1 ? nums[1] : 0;
            return Double.toString(a + b);
        }
        if ("mul".equals(exportName)) {
            double a = nums.length > 0 ? nums[0] : 0;
            double b = nums.length > 1 ? nums[1] : 0;
            return Double.toString(a * b);
        }
        return null;
    }

    private static double[] parseNumberArray(String argsJson) {
        if (argsJson == null) {
            return new double[0];
        }
        String s = argsJson.trim();
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1).trim();
            if (s.isEmpty()) {
                return new double[0];
            }
            String[] parts = s.split(",");
            double[] out = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try {
                    out[i] = Double.parseDouble(parts[i].trim());
                } catch (NumberFormatException e) {
                    out[i] = 0;
                }
            }
            return out;
        }
        return new double[0];
    }

    /**
     * Run JS. Returns JSON text on success; throws on failure (error text from C).
     */
    public String runJs(String source, int timeoutMs) {
        return runJs(source, timeoutMs, null);
    }

    /**
     * @param filename QuickJS eval filename for stack traces (workspace-relative path); null uses {@code <eval>}.
     */
    public String runJs(String source, int timeoutMs, String filename) {
        cancelRequested = false;
        String out = nativeRunJs(nativeHandle, source, timeoutMs, filename);
        if (out != null && out.startsWith("!")) {
            throw new RuntimeException(out.substring(1));
        }
        return out;
    }

    public String runJs(String source) {
        return runJs(source, 0);
    }

    /**
     * Stop the script currently inside {@link #runJs}. Safe to call from another thread.
     * The running call throws with {@code cancelled} in the message. Network waits end here
     * rather than by the wall-clock limit.
     */
    public void cancel() {
        cancelRequested = true;
        if (nativeHandle != 0) {
            nativeCancel(nativeHandle);
        }
    }

    /** Called from JNI: dispatch real I/O onto the thread pool. */
    @SuppressWarnings("unused")
    void onVfsAsync(long engine, long requestId, int op, String relpath, byte[] data) {
        if (cancelRequested) {
            nativeComplete(engine, requestId, false, null, "cancelled");
            return;
        }
        try {
            executor.execute(() -> {
                if (cancelRequested) {
                    nativeComplete(engine, requestId, false, null, "cancelled");
                    return;
                }
                try {
                    Path target = resolveFsTarget(relpath);
                    switch (op) {
                        case 1: { // READ
                            byte[] bytes = Files.readAllBytes(target);
                            nativeComplete(engine, requestId, true, bytes, null);
                            break;
                        }
                        case 2: { // WRITE
                            Files.write(target, data == null ? new byte[0] : data);
                            nativeComplete(engine, requestId, true, null, null);
                            break;
                        }
                        default:
                            nativeComplete(engine, requestId, false, null, "unsupported operation");
                    }
                } catch (IOException e) {
                    nativeComplete(engine, requestId, false, null,
                            e.getMessage() == null ? "I/O failed" : e.getMessage());
                }
            });
        } catch (RejectedExecutionException e) {
            nativeComplete(engine, requestId, false, null,
                    "queue full: too many pending async I/O requests (script is flooding async operations)");
        }
    }

    /** 相对或绝对路径：normalize 后必须在 fsRoot 下（与 C 层 weizhi_resolve_workspace_path 对齐）。 */
    private Path resolveFsTarget(String path) throws IOException {
        if (path == null || path.isEmpty() || path.indexOf('\\') >= 0) {
            throw new IOException("invalid path");
        }
        Path root = Paths.get(fsRoot == null ? "." : fsRoot).toAbsolutePath().normalize();
        Path input = Paths.get(path);
        Path target = input.isAbsolute() ? input.normalize() : root.resolve(input).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("path escape");
        }
        if (containsSymlinkUnder(root, target)) {
            throw new IOException("path escape: symlink inside workspace is not allowed");
        }
        return target;
    }

    /** workspace 内任何一段为符号链接都拒绝：normalize+startsWith 挡不住 symlink 逃逸。 */
    private static boolean containsSymlinkUnder(Path root, Path target) {
        Path cur = root;
        for (int i = root.getNameCount(); i < target.getNameCount(); i++) {
            cur = cur.resolve(target.getName(i));
            if (Files.isSymbolicLink(cur)) {
                return true;
            }
        }
        return false;
    }

    /** Called from JNI: run HTTP on the thread pool, then complete_fetch. */
    @SuppressWarnings("unused")
    void onFetchAsync(long engine, long requestId, String method, String url, String headersJson, byte[] body) {
        if (cancelRequested) {
            nativeCompleteFetch(engine, requestId, 0, null, null, "cancelled");
            return;
        }
        try {
            executor.execute(() -> {
                if (!fetchEnabled) {
                    nativeCompleteFetch(engine, requestId, 0, null, null,
                            "unsupported: fetch (call WeizhiEngine.enableFetch() on the host first)");
                    return;
                }
                if (cancelRequested) {
                    nativeCompleteFetch(engine, requestId, 0, null, null, "cancelled");
                    return;
                }
                try {
                    doFetch(engine, requestId, method, url, headersJson, body);
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "fetch network error" : e.getMessage();
                    String hint = msg.toLowerCase(Locale.US).contains("permission")
                            ? " (add android.permission.INTERNET to the app manifest)"
                            : " (check URL, connectivity, host allowlist, and INTERNET permission)";
                    nativeCompleteFetch(engine, requestId, 0, null, null, "fetch failed: " + msg + hint);
                }
            });
        } catch (RejectedExecutionException e) {
            nativeCompleteFetch(engine, requestId, 0, null, null,
                    "queue full: too many pending fetch requests (script is flooding async operations)");
        }
    }

    private static final int MAX_FETCH_REDIRECTS = 8;

    private void doFetch(long engine, long requestId, String method, String url, String headersJson, byte[] body)
            throws IOException {
        String currentUrl = url;
        String currentMethod = method == null || method.isEmpty() ? "GET" : method.toUpperCase(Locale.US);
        byte[] currentBody = body;
        int redirects = 0;

        while (true) {
            URL parsed = new URL(currentUrl);
            String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.US);
            if (!isFetchHostAllowed(host)) {
                nativeCompleteFetch(engine, requestId, 0, null, null,
                        "fetch blocked: host \"" + host + "\" is not allowlisted "
                                + "(ask the host to widen enableFetch allowlist, or use an approved URL)");
                return;
            }

            HttpURLConnection conn = (HttpURLConnection) parsed.openConnection();
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setRequestMethod(currentMethod);
            conn.setInstanceFollowRedirects(false);
            if (headersJson != null && !headersJson.isEmpty() && !headersJson.equals("{}")) {
                try {
                    applyHeaders(conn, headersJson);
                } catch (Exception e) {
                    conn.disconnect();
                    nativeCompleteFetch(engine, requestId, 0, null, null,
                            "bad argument: fetch headers must be a flat JSON object of string values");
                    return;
                }
            }
            if (currentBody != null && currentBody.length > 0) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(currentBody);
                }
            }

            int status = conn.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = conn.getHeaderField("Location");
                drainAndDisconnect(conn);
                if (location == null || location.isEmpty()) {
                    nativeCompleteFetch(engine, requestId, 0, null, null,
                            "fetch failed: redirect response missing Location header");
                    return;
                }
                if (redirects >= MAX_FETCH_REDIRECTS) {
                    nativeCompleteFetch(engine, requestId, 0, null, null,
                            "fetch failed: too many redirects");
                    return;
                }
                redirects++;
                try {
                    currentUrl = new URL(parsed, location).toString();
                } catch (MalformedURLException e) {
                    nativeCompleteFetch(engine, requestId, 0, null, null,
                            "fetch failed: invalid redirect Location");
                    return;
                }
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER) {
                    if (!"GET".equals(currentMethod) && !"HEAD".equals(currentMethod)) {
                        currentMethod = "GET";
                        currentBody = null;
                    }
                }
                continue;
            }

            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null) {
                stream = conn.getInputStream();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            if (stream != null) {
                byte[] buf = new byte[8192];
                int n;
                long total = 0;
                while ((n = stream.read(buf)) >= 0) {
                    total += n;
                    if (total > maxIoBytes) {
                        conn.disconnect();
                        nativeCompleteFetch(engine, requestId, 0, null, null,
                                "too large: fetch response exceeds " + maxIoBytes
                                        + " bytes (raise WeizhiLimits.fsIoBytes or request a smaller payload)");
                        return;
                    }
                    bos.write(buf, 0, n);
                }
            }
            String headersOut = headersToJson(conn.getHeaderFields());
            conn.disconnect();
            nativeCompleteFetch(engine, requestId, status, headersOut, bos.toByteArray(), null);
            return;
        }
    }

    private boolean isFetchHostAllowed(String host) {
        if (fetchHostAllowlist == null || fetchHostAllowlist.length == 0) {
            return true;
        }
        String h = host == null ? "" : host.toLowerCase(Locale.US);
        for (String suffix : fetchHostAllowlist) {
            if (suffix == null || suffix.isEmpty()) {
                continue;
            }
            String s = suffix.toLowerCase(Locale.US);
            if (h.equals(s) || h.endsWith("." + s)) {
                return true;
            }
        }
        return false;
    }

    private static void drainAndDisconnect(HttpURLConnection conn) {
        try {
            InputStream stream = conn.getErrorStream();
            if (stream == null) {
                stream = conn.getInputStream();
            }
            if (stream != null) {
                byte[] buf = new byte[4096];
                while (stream.read(buf) >= 0) {
                    // discard redirect body
                }
            }
        } catch (IOException ignored) {
            // best effort
        } finally {
            conn.disconnect();
        }
    }

    private static void applyHeaders(HttpURLConnection conn, String headersJson) {
        Map<String, Object> headers = MiniJson.object(headersJson);
        for (Map.Entry<String, Object> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty() || !(entry.getValue() instanceof String)) {
                continue;
            }
            conn.setRequestProperty(entry.getKey(), (String) entry.getValue());
        }
    }

    private static String headersToJson(Map<String, java.util.List<String>> fields) {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        boolean first = true;
        if (fields != null) {
            for (Map.Entry<String, java.util.List<String>> e : fields.entrySet()) {
                if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) {
                    continue;
                }
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(escape(e.getKey().toLowerCase(Locale.US))).append('"').append(':');
                sb.append('"').append(escape(e.getValue().get(0))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        long handle = nativeHandle;
        nativeHandle = 0;
        if (handle != 0 && nativeReleased.compareAndSet(false, true)) {
            nativeClose(handle);
        }
        if (ownsExecutor) {
            executor.shutdownNow();
        }
        if (nativeGuard != null) {
            nativeGuard.clean();
        }
    }

    private static native long nativeOpen(long jsHeapBytes, long jsStackBytes, int maxHostFunctions,
                                         long fsIoBytes, int maxAsyncIo);

    private static native void nativeClose(long handle);

    private static native int nativeSetFsRoot(long handle, String folder);

    private static native int nativeSetScriptFolder(long handle, String folder);

    private static native String nativeRunJs(long handle, String source, int timeoutMs, String filename);

    private static native void nativeCancel(long handle);

    private native void nativeInstallJavaAsyncVfs(long handle);

    private native void nativeInstallJavaHttp(long handle);

    private native void nativeInstallJavaNative(long handle);

    private static native int nativeEnablePluginLoader(long handle, String pluginDir);

    private native int nativeInstallHostCall(long handle);

    private static native byte[] nativeResizeRgba(long handle, byte[] rgba, int width, int height, int maxEdge);

    /** Scale RGBA through the loaded {@code image_resize} plugin. Null when that plugin is not ensured. */
    public byte[] resizeRgba(byte[] rgba, int width, int height, int maxEdge) {
        return nativeResizeRgba(nativeHandle, rgba, width, height, maxEdge);
    }

    private static native void nativeComplete(long handle, long requestId, boolean ok, byte[] data, String error);

    private static native void nativeCompleteFetch(long handle, long requestId, int status, String headersJson,
                                                   byte[] body, String error);

    private static native void nativeCompleteNative(long handle, long requestId, boolean ok, String pluginJson,
                                                    String error);
}
