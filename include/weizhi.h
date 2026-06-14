#ifndef WEIZHI_H
#define WEIZHI_H

#include <stddef.h>
#include <stdint.h>

/* 这些数字是发动机的默认规格。测试按它们验收，改数字必须同时改测试。 */
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
    size_t js_heap_bytes;       /* 0 表示用默认值 */
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

/* 宿主函数自己用 malloc 分配返回文字，发动机用 free 释放。返回 NULL 当成 JSON null。 */
typedef char *(*WeizhiHostFn)(const char *args_json, void *userdata);
typedef void (*WeizhiLogFn)(const char *line, void *userdata);

/* 同步 VFS：成功返回 0。out->data 由宿主 malloc，发动机负责 free。errbuf 写中文原因。 */
typedef int (*WeizhiVfsSyncFn)(WeizhiVfsOp op, const char *relpath, const char *relpath2,
                              const WeizhiBytes *in, WeizhiBytes *out, char *errbuf, size_t errbuf_len,
                              void *userdata);
/* 异步 VFS：成功启动返回 0，稍后在任意线程调 weizhi_complete。 */
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
/* 正在跑脚本时返回 -1，发动机保持可用。成功关闭返回 0，之后指针不能再使用。 */
int weizhi_close(WeizhiEngine *engine);
int weizhi_add_function(WeizhiEngine *engine, const char *name, WeizhiHostFn fn, void *userdata);
int weizhi_set_pack_folder(WeizhiEngine *engine, const char *folder);
/* 工作区沙箱根。设置后若未自定义 VFS，使用内置 POSIX 实现（含线程池异步）。 */
int weizhi_set_fs_root(WeizhiEngine *engine, const char *folder);
void weizhi_set_vfs(WeizhiEngine *engine, WeizhiVfsSyncFn sync_fn, WeizhiVfsAsyncFn async_fn, void *userdata);
void weizhi_complete(WeizhiEngine *engine, int64_t request_id, int ok, const WeizhiBytes *out, const char *error);
void weizhi_bytes_free(WeizhiBytes *bytes);
void weizhi_set_log(WeizhiEngine *engine, WeizhiLogFn fn, void *userdata);
void weizhi_set_run_id(WeizhiEngine *engine, const char *run_id);
/* timeout_ms 为 0 时用默认 3000 毫秒。负数表示不限时。 */
WeizhiResult weizhi_run_js(WeizhiEngine *engine, const char *source, int timeout_ms);
void weizhi_result_free(WeizhiResult *result);

#endif
