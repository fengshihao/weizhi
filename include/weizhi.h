#ifndef WEIZHI_H
#define WEIZHI_H

#include <stddef.h>
#include <stdint.h>

/* Default engine limits. Tests assert these; change numbers and tests together. */
#define WEIZHI_DEFAULT_JS_HEAP_BYTES (8u * 1024u * 1024u)
#define WEIZHI_DEFAULT_JS_STACK_BYTES (256u * 1024u)
#define WEIZHI_DEFAULT_TIMEOUT_MS 3000
#define WEIZHI_DEFAULT_MAX_PACKS 4
#define WEIZHI_DEFAULT_MAX_HOST_FUNCTIONS 32
#define WEIZHI_DEFAULT_WASM_STACK_BYTES (64u * 1024u)
#define WEIZHI_DEFAULT_WASM_HEAP_BYTES (64u * 1024u)
#define WEIZHI_DEFAULT_WASM_MAX_LINEAR_BYTES (2u * 1024u * 1024u)
#define WEIZHI_DEFAULT_FS_IO_BYTES (1u * 1024u * 1024u)

typedef struct WeizhiEngine WeizhiEngine;

typedef struct WeizhiLimits {
    size_t js_heap_bytes;       /* 0 means use the default */
    size_t js_stack_bytes;
    int max_packs;
    int max_host_functions;
    size_t wasm_stack_bytes;
    size_t wasm_heap_bytes;
    size_t wasm_max_linear_bytes;
    size_t fs_io_bytes;
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
int weizhi_set_pack_folder(WeizhiEngine *engine, const char *folder);
/* Workspace sandbox root. If no custom VFS is set, uses the built-in POSIX impl (thread-pool async). */
int weizhi_set_fs_root(WeizhiEngine *engine, const char *folder);
/* Pass NULL for sync_fn / async_fn to keep that callback; userdata updates with any non-NULL callback. */
void weizhi_set_vfs(WeizhiEngine *engine, WeizhiVfsSyncFn sync_fn, WeizhiVfsAsyncFn async_fn, void *userdata);
void weizhi_complete(WeizhiEngine *engine, int64_t request_id, int ok, const WeizhiBytes *out, const char *error);
void weizhi_bytes_free(WeizhiBytes *bytes);
void weizhi_set_log(WeizhiEngine *engine, WeizhiLogFn fn, void *userdata);
void weizhi_set_run_id(WeizhiEngine *engine, const char *run_id);
/* timeout_ms 0 uses the default 3000 ms. Negative means no limit. */
WeizhiResult weizhi_run_js(WeizhiEngine *engine, const char *source, int timeout_ms);
void weizhi_result_free(WeizhiResult *result);

#endif
