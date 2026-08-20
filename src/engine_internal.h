#ifndef WEIZHI_INTERNAL_H
#define WEIZHI_INTERNAL_H

#include "weizhi.h"

#include "quickjs.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>

#define ST_IDLE 0
#define ST_RUNNING 1
#define ST_CLOSED 2
#define WEIZHI_MAX_TIMERS 64
#define WEIZHI_MAX_PENDING 64
#define WEIZHI_PENDING_BUFFER 0
#define WEIZHI_PENDING_FETCH 1
#define WEIZHI_PENDING_NATIVE 2
#define WEIZHI_MAX_PLUGIN_ARGS 8
#define WEIZHI_MAX_PLUGIN_EXPORTS 32
#define WEIZHI_MAX_CBS 64
#define WEIZHI_TY_I32 1
#define WEIZHI_TY_I64 2
#define WEIZHI_TY_F64 3
#define WEIZHI_TY_BYTES 4
#define WEIZHI_TY_CB 5
#define WEIZHI_TY_VOID 6

typedef struct WeizhiPluginExport {
    char *name;
    char *symbol;
    int args[WEIZHI_MAX_PLUGIN_ARGS];
    int nargs;
    int ret;
    void *fn;
} WeizhiPluginExport;

typedef struct WeizhiLoadedPlugin {
    char *name;
    char *version;
    void *handle;
    WeizhiPluginExport exports[WEIZHI_MAX_PLUGIN_EXPORTS];
    int nexports;
    struct WeizhiLoadedPlugin *next;
} WeizhiLoadedPlugin;

typedef struct WeizhiCbSlot {
    int in_use;
    JSValue fn;
} WeizhiCbSlot;

typedef struct WeizhiCbEvent {
    uint32_t cb_id;
    int32_t v0;
    int ready;
} WeizhiCbEvent;

typedef struct HostFn {
    char *name;
    WeizhiHostFn fn;
    void *userdata;
} HostFn;

typedef struct WeizhiTimer {
    int id;
    int active;
    int64_t when_ms;
    JSValue callback;
} WeizhiTimer;

typedef struct WeizhiPending {
    int64_t id;
    int in_use;
    int completed;
    int ok;
    int kind;
    int http_status;
    char *headers_json;
    JSValue resolve;
    JSValue reject;
    WeizhiBytes out;
    char *error;
} WeizhiPending;

/* Built-in async VFS queue node (node_api.c). */
typedef struct WeizhiAsyncJob {
    struct WeizhiEngine *engine;
    int64_t request_id;
    WeizhiVfsOp op;
    char *relpath;
    char *relpath2;
    WeizhiBytes in;
    struct WeizhiAsyncJob *next;
} WeizhiAsyncJob;

struct WeizhiEngine {
    JSRuntime *rt;
    JSContext *ctx;
    WeizhiLimits limits;
    HostFn *hosts;
    int host_count;
    char *script_folder;
    char *fs_root;
    char *run_id;
    WeizhiLogFn log_fn;
    void *log_ud;
    WeizhiVfsSyncFn vfs_sync;
    WeizhiVfsAsyncFn vfs_async;
    void *vfs_ud;
    void *vfs_async_ud;
    WeizhiHttpAsyncFn http_async;
    void *http_ud;
    WeizhiNativeEnsureFn native_ensure;
    WeizhiNativeCallFn native_call;
    void *native_ud;
    char *plugin_dir;
    WeizhiLoadedPlugin *plugins;
    WeizhiCbSlot cbs[WEIZHI_MAX_CBS];
    WeizhiCbEvent cb_queue[WEIZHI_MAX_PENDING];
    int cb_queue_len;
    int seq;
    int64_t deadline_ms;
    atomic_int cancel_requested;
    pthread_t owner;
    atomic_int state;
    WeizhiTimer timers[WEIZHI_MAX_TIMERS];
    int next_timer_id;
    WeizhiPending pending[WEIZHI_MAX_PENDING];
    int64_t next_request_id;
    pthread_mutex_t wake_mu;
    pthread_cond_t wake_cv;
    /* Default async VFS worker pool (lazy-start; unused when host sets custom vfs_async). */
    pthread_t *async_workers;
    int async_worker_count;
    WeizhiAsyncJob *async_queue_head;
    WeizhiAsyncJob *async_queue_tail;
    pthread_mutex_t async_mu;
    pthread_cond_t async_cv;
    int async_stop;
    int async_pool_started;
};

typedef struct WeizhiEngine Engine;

int64_t weizhi_now_ms(void);
void weizhi_emit_log(Engine *engine, const char *event, const char *data_json);
char *weizhi_quote_json(const char *text);
Engine *weizhi_from_ctx(JSContext *ctx);
int weizhi_install_node_api(Engine *engine);
void weizhi_timers_clear(Engine *engine);
void weizhi_pending_clear(Engine *engine);
void weizhi_async_pool_shutdown(Engine *engine);
int weizhi_fire_due_timers(Engine *engine);
int weizhi_apply_completions(Engine *engine);
void weizhi_wake(Engine *engine);
JSValue weizhi_await_value(Engine *engine, JSValue value);
int64_t weizhi_pending_add(Engine *engine, JSValue resolve, JSValue reject);
int weizhi_path_ok(const char *relpath);
JSValue weizhi_bytes_to_buffer(JSContext *ctx, const unsigned char *bytes, size_t len);
JSValue weizhi_throw_unsupported(JSContext *ctx, const char *what);
JSValue weizhi_throw_bad_arg(JSContext *ctx, const char *api, const char *detail);
JSValue weizhi_guard_module(JSContext *ctx, JSValue obj, const char *ns);
JSValue weizhi_make_fetch_response(JSContext *ctx, int status, const char *headers_json,
                                   const WeizhiBytes *body);
/* Build plugin handle from ensureNative JSON; exports call native_call. */
JSValue weizhi_make_native_plugin(JSContext *ctx, const char *plugin_json);
/* Refresh process.weizhiCaps after host installs HTTP/NATIVE. */
void weizhi_refresh_caps(Engine *engine);
/* Buffer helpers for typed plugins (Buffer is a Uint8Array subclass). */
int weizhi_js_is_buffer(JSContext *ctx, JSValueConst val);
int weizhi_js_buffer_data(JSContext *ctx, JSValueConst val, uint8_t **data, size_t *len);
JSValue weizhi_buffer_adopt(JSContext *ctx, uint8_t *bytes, size_t len);
/* Read a leaf .js from script_folder into *out (caller frees). Returns 0 on success. */
int weizhi_read_script_leaf(Engine *engine, const char *leaf, uint8_t **out, size_t *out_len,
                            char *errbuf, size_t errbuf_len);
/* Zip pack/unpack under fs root (zip-slip safe). Returns 0 on success. */
int weizhi_zip_extract(Engine *engine, const char *zip_rel, const char *dest_rel, int *out_entries,
                       int *out_skipped, char *errbuf, size_t errbuf_len);
int weizhi_zip_create(Engine *engine, const char *src_rel, const char *zip_rel, int *out_files,
                      char *errbuf, size_t errbuf_len);
WeizhiLoadedPlugin *weizhi_find_plugin(Engine *engine, const char *name);
uint32_t weizhi_cb_register(Engine *engine, JSValue fn);
void weizhi_cb_invoke_i32(void *engine_ptr, uint32_t cb_id, int32_t v0);
int weizhi_drain_cb_queue(Engine *engine);
void weizhi_plugins_clear(Engine *engine);

#endif
