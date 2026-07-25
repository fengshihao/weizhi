#include "engine_internal.h"
#include "weizhi_plugin.h"

#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if defined(__APPLE__)
#define WEIZHI_SO_EXT ".dylib"
#elif defined(_WIN32)
#define WEIZHI_SO_EXT ".dll"
#else
#define WEIZHI_SO_EXT ".so"
#endif

static WeizhiBuf host_buf_alloc(size_t len) {
    WeizhiBuf b;
    b.data = len ? (uint8_t *)malloc(len) : NULL;
    b.len = (b.data != NULL || len == 0) ? len : 0;
    if (len > 0 && b.data == NULL) {
        b.len = 0;
    }
    return b;
}

static void host_buf_free(WeizhiBuf buf) {
    free(buf.data);
}

static int ty_from_str(const char *s) {
    if (s == NULL) {
        return 0;
    }
    if (strcmp(s, "i32") == 0) {
        return WEIZHI_TY_I32;
    }
    if (strcmp(s, "i64") == 0) {
        return WEIZHI_TY_I64;
    }
    if (strcmp(s, "f64") == 0) {
        return WEIZHI_TY_F64;
    }
    if (strcmp(s, "bytes") == 0) {
        return WEIZHI_TY_BYTES;
    }
    if (strcmp(s, "void") == 0) {
        return WEIZHI_TY_VOID;
    }
    if (strncmp(s, "cb(", 3) == 0) {
        return WEIZHI_TY_CB;
    }
    return 0;
}

/* Extremely small JSON helpers for our manifest shape (no full parser). */
static const char *skip_ws(const char *p) {
    while (*p == ' ' || *p == '\n' || *p == '\r' || *p == '\t') {
        p++;
    }
    return p;
}

static int json_find_string(const char *json, const char *key, char *out, size_t out_len) {
    char pat[96];
    const char *p;
    size_t i = 0;
    snprintf(pat, sizeof(pat), "\"%s\"", key);
    p = strstr(json, pat);
    if (p == NULL) {
        return -1;
    }
    p += strlen(pat);
    p = skip_ws(p);
    if (*p != ':') {
        return -1;
    }
    p = skip_ws(p + 1);
    if (*p != '"') {
        return -1;
    }
    p++;
    while (*p && *p != '"' && i + 1 < out_len) {
        if (*p == '\\' && p[1]) {
            p++;
        }
        out[i++] = *p++;
    }
    out[i] = '\0';
    return 0;
}

static char *read_file_all(const char *path, size_t *out_len) {
    FILE *f = fopen(path, "rb");
    long sz;
    char *buf;
    if (f == NULL) {
        return NULL;
    }
    if (fseek(f, 0, SEEK_END) != 0) {
        fclose(f);
        return NULL;
    }
    sz = ftell(f);
    if (sz < 0 || sz > 1 * 1024 * 1024) {
        fclose(f);
        return NULL;
    }
    rewind(f);
    buf = malloc((size_t)sz + 1);
    if (buf == NULL) {
        fclose(f);
        return NULL;
    }
    if (sz > 0 && fread(buf, 1, (size_t)sz, f) != (size_t)sz) {
        free(buf);
        fclose(f);
        return NULL;
    }
    fclose(f);
    buf[sz] = '\0';
    if (out_len) {
        *out_len = (size_t)sz;
    }
    return buf;
}

static void free_loaded(WeizhiLoadedPlugin *p) {
    int i;
    if (p == NULL) {
        return;
    }
    for (i = 0; i < p->nexports; i++) {
        free(p->exports[i].name);
        free(p->exports[i].symbol);
    }
    if (p->handle != NULL) {
        dlclose(p->handle);
    }
    free(p->name);
    free(p->version);
    free(p);
}

void weizhi_plugins_clear(Engine *engine) {
    WeizhiLoadedPlugin *p;
    int i;
    if (engine == NULL) {
        return;
    }
    p = engine->plugins;
    engine->plugins = NULL;
    while (p != NULL) {
        WeizhiLoadedPlugin *n = p->next;
        free_loaded(p);
        p = n;
    }
    for (i = 0; i < WEIZHI_MAX_CBS; i++) {
        if (engine->cbs[i].in_use) {
            JS_FreeValue(engine->ctx, engine->cbs[i].fn);
            engine->cbs[i].fn = JS_UNDEFINED;
            engine->cbs[i].in_use = 0;
        }
    }
    engine->cb_queue_len = 0;
    free(engine->plugin_dir);
    engine->plugin_dir = NULL;
}

WeizhiLoadedPlugin *weizhi_find_plugin(Engine *engine, const char *name) {
    WeizhiLoadedPlugin *p;
    if (engine == NULL || name == NULL) {
        return NULL;
    }
    for (p = engine->plugins; p != NULL; p = p->next) {
        if (strcmp(p->name, name) == 0) {
            return p;
        }
    }
    return NULL;
}

uint32_t weizhi_cb_register(Engine *engine, JSValue fn) {
    uint32_t i;
    for (i = 1; i < WEIZHI_MAX_CBS; i++) {
        if (!engine->cbs[i].in_use) {
            engine->cbs[i].in_use = 1;
            engine->cbs[i].fn = JS_DupValue(engine->ctx, fn);
            return i;
        }
    }
    return 0;
}

void weizhi_cb_invoke_i32(void *engine_ptr, uint32_t cb_id, int32_t v0) {
    Engine *engine = (Engine *)engine_ptr;
    if (engine == NULL || cb_id == 0 || cb_id >= WEIZHI_MAX_CBS) {
        return;
    }
    pthread_mutex_lock(&engine->wake_mu);
    if (engine->cb_queue_len < WEIZHI_MAX_PENDING) {
        WeizhiCbEvent *ev = &engine->cb_queue[engine->cb_queue_len++];
        ev->cb_id = cb_id;
        ev->v0 = v0;
        ev->ready = 1;
    }
    pthread_cond_signal(&engine->wake_cv);
    pthread_mutex_unlock(&engine->wake_mu);
}

int weizhi_drain_cb_queue(Engine *engine) {
    WeizhiCbEvent local[WEIZHI_MAX_PENDING];
    int n = 0;
    int i;
    int applied = 0;
    if (engine == NULL) {
        return 0;
    }
    pthread_mutex_lock(&engine->wake_mu);
    n = engine->cb_queue_len;
    if (n > 0) {
        memcpy(local, engine->cb_queue, (size_t)n * sizeof(local[0]));
        engine->cb_queue_len = 0;
    }
    pthread_mutex_unlock(&engine->wake_mu);
    for (i = 0; i < n; i++) {
        uint32_t id = local[i].cb_id;
        JSValue arg;
        JSValue ret;
        if (id == 0 || id >= WEIZHI_MAX_CBS || !engine->cbs[id].in_use) {
            continue;
        }
        arg = JS_NewInt32(engine->ctx, local[i].v0);
        ret = JS_Call(engine->ctx, engine->cbs[id].fn, JS_UNDEFINED, 1, &arg);
        JS_FreeValue(engine->ctx, arg);
        applied++;
        if (JS_IsException(ret)) {
            return -1;
        }
        JS_FreeValue(engine->ctx, ret);
    }
    return applied;
}

static int parse_exports(WeizhiLoadedPlugin *plugin, const char *json, void *handle, char *err, size_t errlen) {
    const char *p = strstr(json, "\"exports\"");
    if (p == NULL) {
        snprintf(err, errlen, "native verify failed: manifest missing exports");
        return -1;
    }
    p = strchr(p, '[');
    if (p == NULL) {
        snprintf(err, errlen, "native verify failed: bad exports");
        return -1;
    }
    p++;
    while (*p && plugin->nexports < WEIZHI_MAX_PLUGIN_EXPORTS) {
        const char *obj;
        char name[64];
        char symbol[128];
        char args_region[256];
        const char *a;
        const char *end_obj;
        WeizhiPluginExport *ex;
        p = skip_ws(p);
        if (*p == ']') {
            break;
        }
        if (*p == ',') {
            p++;
            continue;
        }
        if (*p != '{') {
            break;
        }
        obj = p;
        end_obj = strchr(obj, '}');
        if (end_obj == NULL) {
            break;
        }
        {
            size_t n = (size_t)(end_obj - obj + 1);
            char *slice = malloc(n + 1);
            if (slice == NULL) {
                snprintf(err, errlen, "out of memory");
                return -1;
            }
            memcpy(slice, obj, n);
            slice[n] = '\0';
            name[0] = '\0';
            symbol[0] = '\0';
            json_find_string(slice, "name", name, sizeof(name));
            json_find_string(slice, "symbol", symbol, sizeof(symbol));
            args_region[0] = '\0';
            a = strstr(slice, "\"args\"");
            if (a != NULL) {
                const char *br = strchr(a, '[');
                const char *br2 = br ? strchr(br, ']') : NULL;
                if (br && br2 && (size_t)(br2 - br) < sizeof(args_region)) {
                    memcpy(args_region, br, (size_t)(br2 - br + 1));
                    args_region[br2 - br + 1] = '\0';
                }
            }
            {
                char retbuf[64];
                retbuf[0] = '\0';
                json_find_string(slice, "ret", retbuf, sizeof(retbuf));
                ex = &plugin->exports[plugin->nexports];
                memset(ex, 0, sizeof(*ex));
                ex->name = strdup(name);
                ex->symbol = strdup(symbol[0] ? symbol : name);
                ex->ret = ty_from_str(retbuf[0] ? retbuf : "void");
                ex->nargs = 0;
                if (args_region[0] == '[') {
                    char *tok = args_region + 1;
                    while (*tok && *tok != ']' && ex->nargs < WEIZHI_MAX_PLUGIN_ARGS) {
                        char *q1 = strchr(tok, '"');
                        char *q2;
                        char tbuf[64];
                        size_t tl;
                        if (q1 == NULL) {
                            break;
                        }
                        q2 = strchr(q1 + 1, '"');
                        if (q2 == NULL) {
                            break;
                        }
                        tl = (size_t)(q2 - q1 - 1);
                        if (tl >= sizeof(tbuf)) {
                            tl = sizeof(tbuf) - 1;
                        }
                        memcpy(tbuf, q1 + 1, tl);
                        tbuf[tl] = '\0';
                        ex->args[ex->nargs++] = ty_from_str(tbuf);
                        tok = q2 + 1;
                    }
                }
                ex->fn = dlsym(handle, ex->symbol);
                if (ex->fn == NULL) {
                    snprintf(err, errlen, "native load failed: missing symbol %s", ex->symbol);
                    free(slice);
                    free(ex->name);
                    free(ex->symbol);
                    return -1;
                }
                plugin->nexports++;
            }
            free(slice);
        }
        p = end_obj + 1;
    }
    if (plugin->nexports == 0) {
        snprintf(err, errlen, "native verify failed: no exports");
        return -1;
    }
    return 0;
}

static int load_one(Engine *engine, const char *name, char *err, size_t errlen) {
    char manifest_path[PATH_MAX];
    char so_path[PATH_MAX];
    char *json;
    WeizhiLoadedPlugin *plugin;
    void *handle;
    WeizhiPluginInitFn init_fn;
    WeizhiPluginHost host;
    char version[64];
    int abi = 0;
    char abi_s[32];

    if (engine->plugin_dir == NULL) {
        snprintf(err, errlen, "unsupported: native (plugin dir not set)");
        return -1;
    }
    if (weizhi_find_plugin(engine, name) != NULL) {
        return 0;
    }
    snprintf(manifest_path, sizeof(manifest_path), "%s/%s/manifest.json", engine->plugin_dir, name);
    snprintf(so_path, sizeof(so_path), "%s/%s/lib%s%s", engine->plugin_dir, name, name, WEIZHI_SO_EXT);
    json = read_file_all(manifest_path, NULL);
    if (json == NULL) {
        snprintf(err, errlen, "unsupported: native \"%s\" (not in catalog)", name);
        return -1;
    }
    version[0] = '\0';
    json_find_string(json, "version", version, sizeof(version));
    abi_s[0] = '\0';
    if (json_find_string(json, "min_host_abi", abi_s, sizeof(abi_s)) == 0) {
        abi = atoi(abi_s);
    } else {
        /* numeric without quotes */
        const char *k = strstr(json, "\"min_host_abi\"");
        if (k != NULL) {
            k = strchr(k, ':');
            if (k) {
                abi = atoi(k + 1);
            }
        }
    }
    if (abi > WEIZHI_HOST_ABI_VERSION) {
        snprintf(err, errlen, "native incompatible: min_host_abi %d > host %d", abi, WEIZHI_HOST_ABI_VERSION);
        free(json);
        return -1;
    }
    handle = dlopen(so_path, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        snprintf(err, errlen, "native download failed: dlopen %s (%s)", so_path, dlerror());
        free(json);
        return -1;
    }
    plugin = calloc(1, sizeof(*plugin));
    if (plugin == NULL) {
        dlclose(handle);
        free(json);
        snprintf(err, errlen, "out of memory");
        return -1;
    }
    plugin->name = strdup(name);
    plugin->version = strdup(version[0] ? version : "0");
    plugin->handle = handle;
    if (parse_exports(plugin, json, handle, err, errlen) != 0) {
        free_loaded(plugin);
        free(json);
        return -1;
    }
    free(json);
    memset(&host, 0, sizeof(host));
    host.engine = engine;
    host.buf_alloc = host_buf_alloc;
    host.buf_free = host_buf_free;
    host.cb_invoke_i32 = weizhi_cb_invoke_i32;
    init_fn = (WeizhiPluginInitFn)dlsym(handle, "weizhi_plugin_init");
    if (init_fn != NULL && init_fn(&host) != 0) {
        snprintf(err, errlen, "native load failed: weizhi_plugin_init");
        free_loaded(plugin);
        return -1;
    }
    plugin->next = engine->plugins;
    engine->plugins = plugin;
    return 0;
}

static char *build_typed_json(WeizhiLoadedPlugin *plugin) {
    size_t cap = 512;
    char *buf = malloc(cap);
    size_t len = 0;
    int i;
    int j;
    if (buf == NULL) {
        return NULL;
    }
    len += (size_t)snprintf(buf + len, cap - len,
                            "{\"name\":\"%s\",\"version\":\"%s\",\"abi\":\"weizhi_plugin_v1\",\"exports\":[",
                            plugin->name, plugin->version);
    for (i = 0; i < plugin->nexports; i++) {
        WeizhiPluginExport *ex = &plugin->exports[i];
        char piece[256];
        size_t need;
        int n = snprintf(piece, sizeof(piece), "%s{\"name\":\"%s\",\"symbol\":\"%s\",\"args\":[",
                         i ? "," : "", ex->name, ex->symbol);
        for (j = 0; j < ex->nargs; j++) {
            const char *ts = "i32";
            if (ex->args[j] == WEIZHI_TY_BYTES) {
                ts = "bytes";
            } else if (ex->args[j] == WEIZHI_TY_CB) {
                ts = "cb(i: i32)";
            } else if (ex->args[j] == WEIZHI_TY_F64) {
                ts = "f64";
            } else if (ex->args[j] == WEIZHI_TY_I64) {
                ts = "i64";
            }
            n += snprintf(piece + n, sizeof(piece) - (size_t)n, "%s\"%s\"", j ? "," : "", ts);
        }
        {
            const char *rts = "void";
            if (ex->ret == WEIZHI_TY_I32) {
                rts = "i32";
            } else if (ex->ret == WEIZHI_TY_BYTES) {
                rts = "bytes";
            } else if (ex->ret == WEIZHI_TY_F64) {
                rts = "f64";
            } else if (ex->ret == WEIZHI_TY_I64) {
                rts = "i64";
            }
            n += snprintf(piece + n, sizeof(piece) - (size_t)n, "],\"ret\":\"%s\"}", rts);
        }
        need = len + (size_t)n + 4;
        if (need > cap) {
            char *grown = realloc(buf, need + 256);
            if (grown == NULL) {
                free(buf);
                return NULL;
            }
            buf = grown;
            cap = need + 256;
        }
        memcpy(buf + len, piece, (size_t)n);
        len += (size_t)n;
        buf[len] = '\0';
    }
    if (len + 3 >= cap) {
        char *grown = realloc(buf, len + 8);
        if (grown == NULL) {
            free(buf);
            return NULL;
        }
        buf = grown;
    }
    memcpy(buf + len, "]}", 3);
    return buf;
}

static int loader_ensure(WeizhiEngine *engine, int64_t request_id, const char *name, void *userdata) {
    Engine *eng = (Engine *)engine;
    char err[256];
    char *json;
    (void)userdata;
    err[0] = '\0';
    if (load_one(eng, name, err, sizeof(err)) != 0) {
        weizhi_complete_native(engine, request_id, 0, NULL, err);
        return 0;
    }
    json = build_typed_json(weizhi_find_plugin(eng, name));
    if (json == NULL) {
        weizhi_complete_native(engine, request_id, 0, NULL, "out of memory");
        return 0;
    }
    weizhi_complete_native(engine, request_id, 1, json, NULL);
    free(json);
    return 0;
}

int weizhi_enable_plugin_loader(WeizhiEngine *engine, const char *plugin_dir) {
    char *copy;
    if (engine == NULL || plugin_dir == NULL || plugin_dir[0] == '\0') {
        return -1;
    }
    copy = strdup(plugin_dir);
    if (copy == NULL) {
        return -1;
    }
    free(((Engine *)engine)->plugin_dir);
    ((Engine *)engine)->plugin_dir = copy;
    weizhi_set_native(engine, loader_ensure, NULL, engine);
    return 0;
}

int weizhi_plugin_resize_rgba(WeizhiEngine *engine, const uint8_t *rgba, size_t len, int32_t width, int32_t height,
                              int32_t max_edge, uint8_t **out, size_t *out_len) {
    Engine *eng = (Engine *)engine;
    WeizhiLoadedPlugin *plugin;
    WeizhiPluginExport *ex;
    WeizhiBuf in;
    WeizhiBuf produced;
    typedef WeizhiBuf (*resize_fn)(WeizhiBuf, int32_t, int32_t, int32_t);
    int i;
    if (eng == NULL || out == NULL || out_len == NULL) {
        return -1;
    }
    plugin = weizhi_find_plugin(eng, "image_resize");
    if (plugin == NULL) {
        return -1;
    }
    ex = NULL;
    for (i = 0; i < plugin->nexports; i++) {
        if (strcmp(plugin->exports[i].name, "resize_rgba") == 0) {
            ex = &plugin->exports[i];
            break;
        }
    }
    if (ex == NULL || ex->fn == NULL) {
        return -1;
    }
    memset(&in, 0, sizeof(in));
    in.data = (uint8_t *)rgba;
    in.len = len;
    produced = ((resize_fn)ex->fn)(in, width, height, max_edge);
    if (produced.data == NULL || produced.len < 8) {
        free(produced.data);
        return -1;
    }
    *out = produced.data;
    *out_len = produced.len;
    return 0;
}
