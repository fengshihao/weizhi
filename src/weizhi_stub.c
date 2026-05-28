#include "weizhi.h"

#include <stdlib.h>
#include <string.h>

struct WeizhiEngine {
    int unused;
};

WeizhiEngine *weizhi_open(const WeizhiLimits *limits) {
    (void)limits;
    return calloc(1, sizeof(WeizhiEngine));
}

int weizhi_close(WeizhiEngine *engine) {
    free(engine);
    return 0;
}

int weizhi_add_function(WeizhiEngine *engine, const char *name, WeizhiHostFn fn, void *userdata) {
    (void)engine;
    (void)name;
    (void)fn;
    (void)userdata;
    return -1;
}

int weizhi_set_pack_folder(WeizhiEngine *engine, const char *folder) {
    (void)engine;
    (void)folder;
    return -1;
}

void weizhi_set_log(WeizhiEngine *engine, WeizhiLogFn fn, void *userdata) {
    (void)engine;
    (void)fn;
    (void)userdata;
}

void weizhi_set_run_id(WeizhiEngine *engine, const char *run_id) {
    (void)engine;
    (void)run_id;
}

WeizhiResult weizhi_run_js(WeizhiEngine *engine, const char *source, int timeout_ms) {
    WeizhiResult result;
    (void)engine;
    (void)source;
    (void)timeout_ms;
    memset(&result, 0, sizeof(result));
    result.ok = 0;
    result.error = strdup("未实现");
    return result;
}

void weizhi_result_free(WeizhiResult *result) {
    if (result == NULL) {
        return;
    }
    free(result->output_text);
    free(result->error);
    free(result->error_location);
    memset(result, 0, sizeof(*result));
}
