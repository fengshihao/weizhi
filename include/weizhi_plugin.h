#ifndef WEIZHI_PLUGIN_H
#define WEIZHI_PLUGIN_H

/*
 * Weizhi native plugin SDK (Host ABI NATIVE, typed / IDL path).
 * Plugins export plain C functions. No JNI_OnLoad. No QuickJS.
 * See docs/NATIVE_PLUGIN_IDL.md.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  define WEIZHI_PLUGIN_EXPORT __declspec(dllexport)
#else
#  define WEIZHI_PLUGIN_EXPORT __attribute__((visibility("default")))
#endif

typedef struct WeizhiBuf {
    uint8_t *data;
    size_t len;
} WeizhiBuf;

/* Host services passed to weizhi_plugin_init (optional but required for callbacks). */
typedef struct WeizhiPluginHost {
    void *engine; /* opaque WeizhiEngine* */
    /* Allocate a buffer the engine will free with free() / weizhi_buf_free. */
    WeizhiBuf (*buf_alloc)(size_t len);
    void (*buf_free)(WeizhiBuf buf);
    /* Invoke a JS callback (id from typed call) with one i32; thread-safe (queues to JS). */
    void (*cb_invoke_i32)(void *engine, uint32_t cb_id, int32_t v0);
} WeizhiPluginHost;

/*
 * Optional. If exported, the loader calls it once after dlopen.
 * Return 0 on success.
 */
typedef int (*WeizhiPluginInitFn)(WeizhiPluginHost *host);

/* Helpers plugins may call when host is non-NULL. */
static inline WeizhiBuf weizhi_buf_from_ptr(uint8_t *data, size_t len) {
    WeizhiBuf b;
    b.data = data;
    b.len = len;
    return b;
}

#ifdef __cplusplus
}
#endif

#endif /* WEIZHI_PLUGIN_H */
