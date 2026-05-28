#ifndef WEIZHI_H
#define WEIZHI_H

#include <stddef.h>

/* 这些数字是发动机的默认规格。测试按它们验收，改数字必须同时改测试。 */
#define WEIZHI_DEFAULT_JS_HEAP_BYTES (8u * 1024u * 1024u)
#define WEIZHI_DEFAULT_JS_STACK_BYTES (256u * 1024u)
#define WEIZHI_DEFAULT_TIMEOUT_MS 3000
#define WEIZHI_DEFAULT_MAX_PACKS 4
#define WEIZHI_DEFAULT_MAX_HOST_FUNCTIONS 32
#define WEIZHI_DEFAULT_WASM_STACK_BYTES (64u * 1024u)
#define WEIZHI_DEFAULT_WASM_HEAP_BYTES (64u * 1024u)
#define WEIZHI_DEFAULT_WASM_MAX_LINEAR_BYTES (2u * 1024u * 1024u)

typedef struct WeizhiEngine WeizhiEngine;

typedef struct WeizhiLimits {
    size_t js_heap_bytes;       /* 0 表示用默认值 */
    size_t js_stack_bytes;
    int max_packs;
    int max_host_functions;
    size_t wasm_stack_bytes;
    size_t wasm_heap_bytes;
    size_t wasm_max_linear_bytes;
} WeizhiLimits;

/* 宿主函数自己用 malloc 分配返回文字，发动机用 free 释放。返回 NULL 当成 JSON null。 */
typedef char *(*WeizhiHostFn)(const char *args_json, void *userdata);
typedef void (*WeizhiLogFn)(const char *line, void *userdata);

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
void weizhi_set_log(WeizhiEngine *engine, WeizhiLogFn fn, void *userdata);
void weizhi_set_run_id(WeizhiEngine *engine, const char *run_id);
/* timeout_ms 为 0 时用默认 3000 毫秒。负数表示不限时。 */
WeizhiResult weizhi_run_js(WeizhiEngine *engine, const char *source, int timeout_ms);
void weizhi_result_free(WeizhiResult *result);

#endif
