package com.weizhi;

/**
 * Engine resource limits. Zero means "use C default".
 * See include/weizhi.h and docs/DECISIONS.md for meanings.
 */
public final class WeizhiLimits {
    /** JS heap (strings/objects). Default 32 MiB. */
    public long jsHeapBytes;
    /** JS stack. Default 256 KiB. */
    public long jsStackBytes;
    /** Max host functions. Default 32. */
    public int maxHostFunctions;
    /**
     * Max bytes for a single fs read or write payload (not total disk quota).
     * Default 32 MiB. Over limit → error contains "too large".
     */
    public long fsIoBytes;
    /**
     * Max in-flight async I/O workers for the default Java thread pool.
     * Default 16. Excess work queues; does not fail the script.
     * Ignored if the host passes a custom {@link java.util.concurrent.ExecutorService}.
     */
    public int maxAsyncIo;
}
