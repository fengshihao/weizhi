#ifndef WEIZHI_H
#define WEIZHI_H

#include <stddef.h>
#include <stdint.h>

/* Default engine limits. Tests assert these; change numbers and tests together. */
#define WEIZHI_DEFAULT_JS_HEAP_BYTES (32u * 1024u * 1024u)
#define WEIZHI_DEFAULT_JS_STACK_BYTES (256u * 1024u)
#define WEIZHI_DEFAULT_TIMEOUT_MS 3000
#define WEIZHI_DEFAULT_MAX_HOST_FUNCTIONS 32
/* Max bytes for one fs read OR one fs write payload (not workspace total size). */
#define WEIZHI_DEFAULT_FS_IO_BYTES (32u * 1024u * 1024u)
/* Max in-flight default async VFS jobs (extra work queues; does not fail). */
#define WEIZHI_DEFAULT_MAX_ASYNC_IO 16

/* Host ABI version for native plugins (see docs/HOST_ABI.md). */
#define WEIZHI_HOST_ABI_VERSION 1

typedef struct WeizhiEngine WeizhiEngine;

/* Pass to weizhi_open / Java WeizhiLimits. 0 = use default above. Immutable after open. */
typedef struct WeizhiLimits {
    size_t js_heap_bytes;         /* JS heap (strings/objects). Default 32MB. Over → "memory" */
    size_t js_stack_bytes;        /* JS stack. Default 256KB. Over → "stack" */
    int max_host_functions;       /* addFunction cap. Default 32 */
    size_t fs_io_bytes;           /* Single fs read/write size. Default 32MB. Over → "too large" */
    int max_async_io;             /* In-flight default async I/O workers. Default 16. Excess queues. */
} WeizhiLimits;

typedef struct WeizhiBytes {
    unsigned char *data;
    size_t len;
} WeizhiBytes;

typedef enum WeizhiVfsOp {
    WEIZHI_VFS_READ = 1,
    WEIZHI_VFS_WRITE = 2,
    WEIZHI_VFS_APPEND = 3,
    WEIZHI_VFS_UNLINK = 4,
    WEIZHI_VFS_MKDIR = 5,
    WEIZHI_VFS_READDIR = 6,
    WEIZHI_VFS_STAT = 7,
    WEIZHI_VFS_EXISTS = 8,
    WEIZHI_VFS_RENAME = 9,
    WEIZHI_VFS_RM = 10
} WeizhiVfsOp;

/* Host fn mallocs the return string; the engine frees it. NULL means JSON null. */
typedef char *(*WeizhiHostFn)(const char *args_json, void *userdata);
typedef void (*WeizhiLogFn)(const char *line, void *userdata);

/* Sync VFS: return 0 on success. Host mallocs out->data; engine frees it. errbuf holds an English reason. */
typedef int (*WeizhiVfsSyncFn)(WeizhiVfsOp op, const char *relpath, const char *relpath2,
                              const WeizhiBytes *in, WeizhiBytes *out, char *errbuf, size_t errbuf_len,
                              void *userdata);
/* Async VFS: return 0 if started; later call weizhi_complete from any thread. */
typedef int (*WeizhiVfsAsyncFn)(WeizhiEngine *engine, int64_t request_id, WeizhiVfsOp op,
                               const char *relpath, const char *relpath2, const WeizhiBytes *in,
                               void *userdata);

/* Async HTTP for globalThis.fetch. Return 0 if started; later call weizhi_complete_fetch. */
typedef int (*WeizhiHttpAsyncFn)(WeizhiEngine *engine, int64_t request_id, const char *method,
                                 const char *url, const char *headers_json, const WeizhiBytes *body,
                                 void *userdata);

/* Async ensureNative: host later calls weizhi_complete_native with plugin JSON. */
typedef int (*WeizhiNativeEnsureFn)(WeizhiEngine *engine, int64_t request_id, const char *name,
                                    void *userdata);
/* Sync call into an ensured plugin export. Host mallocs JSON return; engine frees. NULL → JSON null. */
typedef char *(*WeizhiNativeCallFn)(const char *plugin_name, const char *export_name, const char *args_json,
                                    void *userdata);

typedef struct WeizhiResult {
    int ok;
    char *output_text;
    char *error;
    char *error_location;
    int duration_ms;
} WeizhiResult;

WeizhiEngine *weizhi_open(const WeizhiLimits *limits);
/* Returns -1 while a script is running; engine stays usable. 0 on close; pointer is invalid after. */
int weizhi_close(WeizhiEngine *engine);
int weizhi_add_function(WeizhiEngine *engine, const char *name, WeizhiHostFn fn, void *userdata);
/* Folder for loadScript JS libs (leaf filenames only; resolved under this root). */
int weizhi_set_script_folder(WeizhiEngine *engine, const char *folder);
/* Workspace sandbox root. If no custom VFS is set, uses the built-in POSIX impl (thread-pool async). */
int weizhi_set_fs_root(WeizhiEngine *engine, const char *folder);
/* Pass NULL for sync_fn / async_fn to keep that callback; userdata updates with any non-NULL callback. */
void weizhi_set_vfs(WeizhiEngine *engine, WeizhiVfsSyncFn sync_fn, WeizhiVfsAsyncFn async_fn, void *userdata);
/* Install host HTTP. Without this, fetch() fails with an agent-facing unsupported hint. */
void weizhi_set_http(WeizhiEngine *engine, WeizhiHttpAsyncFn async_fn, void *userdata);
/* Install native plugin host. Without this, host.ensureNative fails with unsupported. */
void weizhi_set_native(WeizhiEngine *engine, WeizhiNativeEnsureFn ensure_fn, WeizhiNativeCallFn call_fn,
                       void *userdata);
/* Built-in typed loader (dlopen + IDL manifest). See docs/NATIVE_PLUGIN_IDL.md. */
int weizhi_enable_plugin_loader(WeizhiEngine *engine, const char *plugin_dir);
/* Call image_resize.resize_rgba on an already ensured plugin. Caller frees *out. Returns 0 on success. */
int weizhi_plugin_resize_rgba(WeizhiEngine *engine, const uint8_t *rgba, size_t len, int32_t width, int32_t height,
                              int32_t max_edge, uint8_t **out, size_t *out_len);
void weizhi_complete(WeizhiEngine *engine, int64_t request_id, int ok, const WeizhiBytes *out, const char *error);
/* Complete a fetch() promise. headers_json is a JSON object string (may be "{}"). */
void weizhi_complete_fetch(WeizhiEngine *engine, int64_t request_id, int status, const char *headers_json,
                           const WeizhiBytes *body, const char *error);
/*
 * Complete host.ensureNative. On success plugin_json looks like:
 *   {"name":"echo_math","version":"1.0.0","exports":["add"]}
 * Host owns download/verify/dlopen (or mocks); engine only builds the JS handle.
 */
void weizhi_complete_native(WeizhiEngine *engine, int64_t request_id, int ok, const char *plugin_json,
                            const char *error);
void weizhi_bytes_free(WeizhiBytes *bytes);
void weizhi_set_log(WeizhiEngine *engine, WeizhiLogFn fn, void *userdata);
void weizhi_set_run_id(WeizhiEngine *engine, const char *run_id);
/* timeout_ms 0 uses the default 3000 ms. Negative means no limit. */
WeizhiResult weizhi_run_js(WeizhiEngine *engine, const char *source, int timeout_ms);
void weizhi_result_free(WeizhiResult *result);

#endif
