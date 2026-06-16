package com.weizhi;

/**
 * Engine resource limits. Zero means "use C default".
 * See include/weizhi.h and docs/DECISIONS.md for meanings.
 */
public final class WeizhiLimits {
    /** JS heap (strings/objects). Default 8 MiB. */
    public long jsHeapBytes;
    /** JS stack. Default 256 KiB. */
    public long jsStackBytes;
    /** Max loaded Wasm packs. Default 4. */
    public int maxPacks;
    /** Max host functions. Default 32. */
    public int maxHostFunctions;
    /** Per-pack Wasm call stack. Default 64 KiB. */
    public long wasmStackBytes;
    /** Per-pack Wasm internal heap. Default 64 KiB. */
    public long wasmHeapBytes;
    /** Max Wasm linear memory a pack may request. Default 2 MiB. */
    public long wasmMaxLinearBytes;
    /**
     * Max bytes for a single fs read or write payload (not total disk quota).
     * Default 1 MiB. Over limit → error contains "too large".
     */
    public long fsIoBytes;
    /**
     * Max in-flight async I/O workers for the default Java thread pool.
     * Default 16. Excess work queues; does not fail the script.
     * Ignored if the host passes a custom {@link java.util.concurrent.ExecutorService}.
     */
    public int maxAsyncIo;
}
