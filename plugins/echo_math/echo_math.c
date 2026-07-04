#include "weizhi_echo_math_api.h"

#include <stdlib.h>
#include <string.h>

static WeizhiPluginHost g_host;

WEIZHI_PLUGIN_EXPORT int weizhi_plugin_init(WeizhiPluginHost *host) {
    if (host == NULL) {
        return -1;
    }
    g_host = *host;
    return 0;
}

WEIZHI_PLUGIN_EXPORT int32_t weizhi_echo_math_add(int32_t a, int32_t b) {
    return a + b;
}

WEIZHI_PLUGIN_EXPORT WeizhiBuf weizhi_echo_math_echo_bytes(WeizhiBuf data) {
    WeizhiBuf out;
    memset(&out, 0, sizeof(out));
    if (data.len == 0) {
        return out;
    }
    if (g_host.buf_alloc != NULL) {
        out = g_host.buf_alloc(data.len);
    } else {
        out.data = (uint8_t *)malloc(data.len);
        out.len = data.len;
    }
    if (out.data == NULL) {
        out.len = 0;
        return out;
    }
    if (data.data != NULL) {
        memcpy(out.data, data.data, data.len);
    }
    return out;
}

WEIZHI_PLUGIN_EXPORT int32_t weizhi_echo_math_count_with_cb(int32_t n, uint32_t on_i) {
    int32_t i;
    if (n < 0) {
        n = 0;
    }
    if (n > 64) {
        n = 64;
    }
    for (i = 0; i < n; i++) {
        if (g_host.cb_invoke_i32 != NULL && g_host.engine != NULL) {
            g_host.cb_invoke_i32(g_host.engine, on_i, i);
        }
    }
    return n;
}
