package com.weizhi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weizhi Java wrapper. Async I/O uses {@link ExecutorService} by default, then completes back into the C queue.
 * Agents write JS only; this class is for the app host.
 */
public final class WeizhiEngine implements AutoCloseable {
    /** Matches WEIZHI_DEFAULT_MAX_ASYNC_IO in weizhi.h. */
    private static final int DEFAULT_MAX_ASYNC_IO = 16;

    static {
        System.loadLibrary("weizhijni");
    }

    private long nativeHandle;
    private final ExecutorService executor;
    private final boolean ownsExecutor;
    private String fsRoot;

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
        if (executor != null) {
            this.executor = executor;
            this.ownsExecutor = false;
        } else {
            this.executor = Executors.newFixedThreadPool(asyncIo);
            this.ownsExecutor = true;
        }
        this.nativeHandle = nativeOpen(
                limits == null ? 0 : limits.jsHeapBytes,
                limits == null ? 0 : limits.jsStackBytes,
                limits == null ? 0 : limits.maxPacks,
                limits == null ? 0 : limits.maxHostFunctions,
                limits == null ? 0 : limits.wasmStackBytes,
                limits == null ? 0 : limits.wasmHeapBytes,
                limits == null ? 0 : limits.wasmMaxLinearBytes,
                limits == null ? 0 : limits.fsIoBytes,
                limits == null ? 0 : limits.maxAsyncIo);
        if (this.nativeHandle == 0) {
            if (this.ownsExecutor) {
                this.executor.shutdownNow();
            }
            throw new IllegalStateException("weizhi_open failed");
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

    public void setPackFolder(String folder) {
        if (nativeSetPackFolder(nativeHandle, folder) != 0) {
            throw new IllegalArgumentException("setPackFolder failed");
        }
    }

    /**
     * Run JS. Returns JSON text on success; throws on failure (error text from C).
     */
    public String runJs(String source, int timeoutMs) {
        String out = nativeRunJs(nativeHandle, source, timeoutMs);
        if (out != null && out.startsWith("!")) {
            throw new RuntimeException(out.substring(1));
        }
        return out;
    }

    public String runJs(String source) {
        return runJs(source, 0);
    }

    /** Called from JNI: dispatch real I/O onto the thread pool. */
    @SuppressWarnings("unused")
    void onVfsAsync(long engine, long requestId, int op, String relpath, byte[] data) {
        executor.execute(() -> {
            try {
                Path root = Paths.get(fsRoot == null ? "." : fsRoot).toAbsolutePath().normalize();
                Path target = root.resolve(relpath).normalize();
                if (!target.startsWith(root)) {
                    nativeComplete(engine, requestId, false, null, "path escape");
                    return;
                }
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
                nativeComplete(engine, requestId, false, null, e.getMessage() == null ? "I/O failed" : e.getMessage());
            }
        });
    }

    @Override
    public void close() {
        if (nativeHandle != 0) {
            nativeClose(nativeHandle);
            nativeHandle = 0;
        }
        if (ownsExecutor) {
            executor.shutdownNow();
        }
    }

    private static native long nativeOpen(long jsHeapBytes, long jsStackBytes, int maxPacks, int maxHostFunctions,
                                         long wasmStackBytes, long wasmHeapBytes, long wasmMaxLinearBytes,
                                         long fsIoBytes, int maxAsyncIo);

    private static native void nativeClose(long handle);

    private static native int nativeSetFsRoot(long handle, String folder);

    private static native int nativeSetPackFolder(long handle, String folder);

    private static native String nativeRunJs(long handle, String source, int timeoutMs);

    private native void nativeInstallJavaAsyncVfs(long handle);

    private static native void nativeComplete(long handle, long requestId, boolean ok, byte[] data, String error);
}
