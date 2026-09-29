#include "engine_internal.h"

#include <ctype.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define MAX_SCRIPT_FILE_BYTES (16u * 1024u * 1024u)

int64_t weizhi_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

Engine *weizhi_from_ctx(JSContext *ctx) {
    return JS_GetContextOpaque(ctx);
}

static int64_t now_ms(void) {
    return weizhi_now_ms();
}

static char *quote_json(const char *text) {
    const unsigned char *src = (const unsigned char *)(text ? text : "");
    size_t cap = strlen((const char *)src) * 6 + 3;
    char *out = malloc(cap);
    char *dst;
    if (out == NULL) {
        return NULL;
    }
    dst = out;
    *dst++ = '"';
    for (; *src != '\0'; src++) {
        unsigned char c = *src;
        if (c == '"' || c == '\\') {
            *dst++ = '\\';
            *dst++ = (char)c;
        } else if (c == '\n') {
            *dst++ = '\\';
            *dst++ = 'n';
        } else if (c == '\r') {
            *dst++ = '\\';
            *dst++ = 'r';
        } else if (c < 0x20) {
            sprintf(dst, "\\u%04x", c);
            dst += 6;
        } else {
            *dst++ = (char)c;
        }
    }
    *dst++ = '"';
    *dst = '\0';
    return out;
}

static void emit_log(Engine *engine, const char *event, const char *data_json) {
    char *run_id = quote_json(engine->run_id != NULL ? engine->run_id : "run");
    char *event_q = quote_json(event);
    size_t cap;
    char *line;
    if (run_id == NULL || event_q == NULL) {
        free(run_id);
        free(event_q);
        return;
    }
    cap = strlen(run_id) + strlen(event_q) + strlen(data_json) + 64;
    line = malloc(cap);
    if (line != NULL) {
        engine->seq++;
        snprintf(line, cap,
                 "{\"v\":1,\"seq\":%d,\"run_id\":%s,\"event\":%s,\"data\":%s}",
                 engine->seq, run_id, event_q, data_json);
        if (engine->log_fn != NULL) {
            engine->log_fn(line, engine->log_ud);
        }
        free(line);
    }
    free(run_id);
    free(event_q);
}

char *weizhi_quote_json(const char *text) {
    return quote_json(text);
}

void weizhi_emit_log(Engine *engine, const char *event, const char *data_json) {
    emit_log(engine, event, data_json);
}

static int interrupt_cb(JSRuntime *rt, void *opaque) {
    Engine *engine = opaque;
    (void)rt;
    if (atomic_load(&engine->cancel_requested)) {
        return 1;
    }
    if (engine->deadline_ms == 0) {
        return 0;
    }
    return now_ms() >= engine->deadline_ms;
}

int weizhi_valid_script_leaf(const char *name) {
    size_t i;
    size_t len;
    if (name == NULL || name[0] == '\0') {
        return 0;
    }
    len = strlen(name);
    if (len > 64 || strcmp(name, ".") == 0 || strcmp(name, "..") == 0) {
        return 0;
    }
    for (i = 0; i < len; i++) {
        unsigned char c = (unsigned char)name[i];
        if (!(isalnum(c) || c == '.' || c == '_' || c == '-')) {
            return 0;
        }
    }
    return 1;
}

static int resolve_under(const char *folder, const char *filename, char *out, size_t out_len) {
    char folder_real[PATH_MAX];
    char joined[PATH_MAX];
    char file_real[PATH_MAX];
    size_t folder_len;
    if (realpath(folder, folder_real) == NULL) {
        return -1;
    }
    snprintf(joined, sizeof(joined), "%s/%s", folder_real, filename);
    if (realpath(joined, file_real) == NULL) {
        return -1;
    }
    folder_len = strlen(folder_real);
    if (strncmp(file_real, folder_real, folder_len) != 0 ||
        (file_real[folder_len] != '/' && file_real[folder_len] != '\0')) {
        return -2;
    }
    snprintf(out, out_len, "%s", file_real);
    return 0;
}

static uint8_t *read_file(const char *path, size_t *out_len, const char **error) {
    FILE *file = fopen(path, "rb");
    long size;
    uint8_t *buf;
    if (file == NULL) {
        *error = "script not found";
        return NULL;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        *error = "script not found";
        return NULL;
    }
    size = ftell(file);
    if (size < 0 || (size_t)size > MAX_SCRIPT_FILE_BYTES) {
        fclose(file);
        *error = "script file too large";
        return NULL;
    }
    rewind(file);
    buf = malloc((size_t)size + 1);
    if (buf == NULL || (size > 0 && fread(buf, 1, (size_t)size, file) != (size_t)size)) {
        free(buf);
        fclose(file);
        *error = "script not found";
        return NULL;
    }
    fclose(file);
    buf[size] = '\0';
    *out_len = (size_t)size;
    return buf;
}

int weizhi_read_script_leaf(Engine *engine, const char *leaf, uint8_t **out, size_t *out_len,
                            char *errbuf, size_t errbuf_len) {
    char path[PATH_MAX];
    const char *error = NULL;
    uint8_t *bytes;
    size_t length = 0;
    if (out == NULL || out_len == NULL) {
        return -1;
    }
    *out = NULL;
    *out_len = 0;
    if (engine == NULL || leaf == NULL ||
        !weizhi_valid_script_leaf(leaf) || strlen(leaf) < 4 || strcmp(leaf + strlen(leaf) - 3, ".js") != 0) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "invalid script name");
        }
        return -1;
    }
    if (engine->script_folder == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "script folder not set");
        }
        return -1;
    }
    if (resolve_under(engine->script_folder, leaf, path, sizeof(path)) != 0) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "script not found");
        }
        return -1;
    }
    bytes = read_file(path, &length, &error);
    if (bytes == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "%s", error != NULL ? error : "script not found");
        }
        return -1;
    }
    *out = bytes;
    *out_len = length;
    return 0;
}

int weizhi_read_workspace_script(Engine *engine, const char *relpath, uint8_t **out, size_t *out_len,
                                 char *errbuf, size_t errbuf_len) {
    char folder_real[PATH_MAX];
    char joined[PATH_MAX];
    char file_real[PATH_MAX];
    size_t folder_len;
    const char *error = NULL;
    uint8_t *bytes;
    size_t length = 0;
    if (out == NULL || out_len == NULL) {
        return -1;
    }
    *out = NULL;
    *out_len = 0;
    if (engine == NULL || relpath == NULL || relpath[0] == '\0') {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "invalid path");
        }
        return -1;
    }
    if (engine->fs_root == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "workspace not set");
        }
        return -1;
    }
    if (!weizhi_path_ok(relpath)) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "invalid path");
        }
        return -1;
    }
    if (strlen(relpath) < 4 || strcmp(relpath + strlen(relpath) - 3, ".js") != 0) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "not a script path");
        }
        return -1;
    }
    if (realpath(engine->fs_root, folder_real) == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "workspace not found");
        }
        return -1;
    }
    snprintf(joined, sizeof(joined), "%s/%s", folder_real, relpath);
    if (realpath(joined, file_real) == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "script not found");
        }
        return -1;
    }
    folder_len = strlen(folder_real);
    if (strncmp(file_real, folder_real, folder_len) != 0 ||
        (file_real[folder_len] != '/' && file_real[folder_len] != '\0')) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "path escape");
        }
        return -2;
    }
    bytes = read_file(file_real, &length, &error);
    if (bytes == NULL) {
        if (errbuf != NULL && errbuf_len > 0) {
            snprintf(errbuf, errbuf_len, "%s", error != NULL ? error : "script not found");
        }
        return -1;
    }
    *out = bytes;
    *out_len = length;
    return 0;
}

static JSValue js_host_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv, int magic) {
    Engine *engine = JS_GetContextOpaque(ctx);
    HostFn *host;
    JSValue arg;
    JSValue json;
    const char *args_text;
    char *returned;
    JSValue parsed;
    (void)this_val;
    if (magic < 0 || magic >= engine->host_count) {
        return JS_ThrowReferenceError(ctx, "function not found");
    }
    host = &engine->hosts[magic];
    arg = argc > 0 ? argv[0] : JS_NULL;
    json = JS_JSONStringify(ctx, arg, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json)) {
        return JS_EXCEPTION;
    }
    args_text = JS_ToCString(ctx, json);
    JS_FreeValue(ctx, json);
    if (args_text == NULL) {
        return JS_EXCEPTION;
    }
    returned = host->fn(args_text, host->userdata);
    {
        char *name_q = quote_json(host->name);
        const char *args_json = args_text != NULL ? args_text : "null";
        const char *result_json = returned != NULL ? returned : "null";
        if (name_q != NULL) {
            size_t cap = strlen(name_q) + strlen(args_json) + strlen(result_json) + 48;
            char *data = malloc(cap);
            if (data != NULL) {
                snprintf(data, cap, "{\"name\":%s,\"args\":%s,\"result\":%s}", name_q, args_json, result_json);
                emit_log(engine, "host_call", data);
                free(data);
            }
        }
        free(name_q);
    }
    JS_FreeCString(ctx, args_text);
    if (returned == NULL) {
        return JS_NULL;
    }
    parsed = JS_ParseJSON(ctx, returned, strlen(returned), "<host>");
    if (JS_IsException(parsed)) {
        JSValue exc = JS_GetException(ctx);
        JS_FreeValue(ctx, exc);
        parsed = JS_NewString(ctx, returned);
    }
    free(returned);
    return parsed;
}

static size_t size_or_default(size_t value, size_t fallback) {
    return value == 0 ? fallback : value;
}

static int int_or_default(int value, int fallback) {
    return value > 0 ? value : fallback;
}

WeizhiEngine *weizhi_open(const WeizhiLimits *limits) {
    Engine *engine = calloc(1, sizeof(*engine));
    if (engine == NULL) {
        return NULL;
    }
    engine->limits.js_heap_bytes = size_or_default(limits ? limits->js_heap_bytes : 0, WEIZHI_DEFAULT_JS_HEAP_BYTES);
    engine->limits.js_stack_bytes = size_or_default(limits ? limits->js_stack_bytes : 0, WEIZHI_DEFAULT_JS_STACK_BYTES);
    engine->limits.max_host_functions =
        int_or_default(limits ? limits->max_host_functions : 0, WEIZHI_DEFAULT_MAX_HOST_FUNCTIONS);
    engine->limits.fs_io_bytes = size_or_default(limits ? limits->fs_io_bytes : 0, WEIZHI_DEFAULT_FS_IO_BYTES);
    engine->limits.max_async_io =
        int_or_default(limits ? limits->max_async_io : 0, WEIZHI_DEFAULT_MAX_ASYNC_IO);
    atomic_init(&engine->state, ST_IDLE);
    atomic_init(&engine->cancel_requested, 0);
    engine->next_timer_id = 1;
    engine->next_request_id = 1;
    pthread_mutex_init(&engine->wake_mu, NULL);
    pthread_cond_init(&engine->wake_cv, NULL);
    pthread_mutex_init(&engine->async_mu, NULL);
    pthread_cond_init(&engine->async_cv, NULL);
    engine->rt = JS_NewRuntime();
    if (engine->rt == NULL) {
        pthread_mutex_destroy(&engine->wake_mu);
        pthread_cond_destroy(&engine->wake_cv);
        pthread_mutex_destroy(&engine->async_mu);
        pthread_cond_destroy(&engine->async_cv);
        free(engine);
        return NULL;
    }
    JS_SetMemoryLimit(engine->rt, engine->limits.js_heap_bytes);
    JS_SetMaxStackSize(engine->rt, engine->limits.js_stack_bytes);
    JS_SetInterruptHandler(engine->rt, interrupt_cb, engine);
    engine->ctx = JS_NewContext(engine->rt);
    if (engine->ctx == NULL) {
        JS_FreeRuntime(engine->rt);
        pthread_mutex_destroy(&engine->wake_mu);
        pthread_cond_destroy(&engine->wake_cv);
        pthread_mutex_destroy(&engine->async_mu);
        pthread_cond_destroy(&engine->async_cv);
        free(engine);
        return NULL;
    }
    JS_SetContextOpaque(engine->ctx, engine);
    if (weizhi_install_node_api(engine) != 0) {
        weizhi_close(engine);
        return NULL;
    }
    return engine;
}

int weizhi_close(WeizhiEngine *engine) {
    int expected = ST_IDLE;
    int i;
    if (engine == NULL) {
        return 0;
    }
    if (!atomic_compare_exchange_strong(&engine->state, &expected, ST_CLOSED)) {
        return -1;
    }
    weizhi_async_pool_shutdown(engine);
    weizhi_timers_clear(engine);
    weizhi_pending_clear(engine);
    weizhi_plugins_clear(engine);
    JS_FreeContext(engine->ctx);
    JS_FreeRuntime(engine->rt);
    for (i = 0; i < engine->host_count; i++) {
        free(engine->hosts[i].name);
    }
    free(engine->hosts);
    free(engine->script_folder);
    free(engine->fs_root);
    free(engine->run_id);
    pthread_mutex_destroy(&engine->wake_mu);
    pthread_cond_destroy(&engine->wake_cv);
    pthread_mutex_destroy(&engine->async_mu);
    pthread_cond_destroy(&engine->async_cv);
    free(engine);
    return 0;
}

int weizhi_add_function(WeizhiEngine *engine, const char *name, WeizhiHostFn fn, void *userdata) {
    HostFn *grown;
    JSValue global;
    JSValue func;
    int index;
    if (engine == NULL || name == NULL || name[0] == '\0' || fn == NULL) {
        return -1;
    }
    if (atomic_load(&engine->state) != ST_IDLE) {
        return -1;
    }
    if (strcmp(name, "require") == 0 ||
        strcmp(name, "Buffer") == 0 || strcmp(name, "setTimeout") == 0 || strcmp(name, "clearTimeout") == 0) {
        return -1;
    }
    if (engine->host_count >= engine->limits.max_host_functions) {
        return -1;
    }
    grown = realloc(engine->hosts, (size_t)(engine->host_count + 1) * sizeof(HostFn));
    if (grown == NULL) {
        return -1;
    }
    engine->hosts = grown;
    index = engine->host_count;
    engine->hosts[index].name = strdup(name);
    engine->hosts[index].fn = fn;
    engine->hosts[index].userdata = userdata;
    if (engine->hosts[index].name == NULL) {
        return -1;
    }
    global = JS_GetGlobalObject(engine->ctx);
    func = JS_NewCFunctionMagic(engine->ctx, js_host_call, name, 1, JS_CFUNC_generic_magic, index);
    JS_SetPropertyStr(engine->ctx, global, name, func);
    JS_FreeValue(engine->ctx, global);
    engine->host_count++;
    return 0;
}

int weizhi_set_script_folder(WeizhiEngine *engine, const char *folder) {
    char *copy;
    if (engine == NULL || folder == NULL || folder[0] == '\0') {
        return -1;
    }
    if (atomic_load(&engine->state) != ST_IDLE) {
        return -1;
    }
    copy = strdup(folder);
    if (copy == NULL) {
        return -1;
    }
    free(engine->script_folder);
    engine->script_folder = copy;
    return 0;
}

void weizhi_set_log(WeizhiEngine *engine, WeizhiLogFn fn, void *userdata) {
    if (engine == NULL) {
        return;
    }
    engine->log_fn = fn;
    engine->log_ud = userdata;
}

void weizhi_set_run_id(WeizhiEngine *engine, const char *run_id) {
    char *copy;
    if (engine == NULL) {
        return;
    }
    copy = run_id != NULL ? strdup(run_id) : NULL;
    free(engine->run_id);
    engine->run_id = copy;
}

static void take_exception(Engine *engine, WeizhiResult *result) {
    JSValue exc = JS_GetException(engine->ctx);
    JSValue stack = JS_GetPropertyStr(engine->ctx, exc, "stack");
    const char *message = JS_ToCString(engine->ctx, exc);
    const char *stack_text = NULL;
    if (!JS_IsUndefined(stack) && !JS_IsException(stack)) {
        stack_text = JS_ToCString(engine->ctx, stack);
    }
    if (atomic_load(&engine->cancel_requested)) {
        result->error = strdup("script cancelled");
    } else if (message != NULL && strstr(message, "interrupted") != NULL) {
        result->error = strdup("script exceeded the timeout limit");
    } else if (message != NULL && strstr(message, "out of memory") != NULL) {
        result->error = strdup("script exceeded the memory limit");
    } else if (message != NULL && strstr(message, "stack") != NULL) {
        result->error = strdup("script call stack exceeded the limit");
    } else if (message != NULL) {
        result->error = strdup(message);
    } else {
        result->error = strdup("script failed");
    }
    if (stack_text != NULL) {
        result->error_location = strdup(stack_text);
    } else if (message != NULL) {
        result->error_location = strdup(message);
    } else {
        result->error_location = strdup("");
    }
    if (message != NULL) {
        JS_FreeCString(engine->ctx, message);
    }
    if (stack_text != NULL) {
        JS_FreeCString(engine->ctx, stack_text);
    }
    JS_FreeValue(engine->ctx, stack);
    JS_FreeValue(engine->ctx, exc);
}

static WeizhiResult fail_immediately(const char *error) {
    WeizhiResult result;
    memset(&result, 0, sizeof(result));
    result.ok = 0;
    result.error = strdup(error);
    result.error_location = strdup("");
    return result;
}

WeizhiResult weizhi_run_js(WeizhiEngine *engine, const char *source, int timeout_ms) {
    return weizhi_run_js_ex(engine, source, timeout_ms, NULL);
}

WeizhiResult weizhi_run_js_ex(WeizhiEngine *engine, const char *source, int timeout_ms,
                              const char *filename) {
    WeizhiResult result;
    int64_t started;
    int expected = ST_IDLE;
    JSValue value;
    char *source_q;
    char *data;
    int eval_flags;
    const char *eval_name;
    memset(&result, 0, sizeof(result));
    if (engine == NULL || source == NULL) {
        return fail_immediately("no script to run");
    }
    eval_name = (filename != NULL && filename[0] != '\0') ? filename : "<eval>";
    if (!atomic_compare_exchange_strong(&engine->state, &expected, ST_RUNNING)) {
        if (expected == ST_RUNNING && pthread_equal(engine->owner, pthread_self())) {
            return fail_immediately("cannot run a script again from inside a script");
        }
        return fail_immediately("engine is busy");
    }
    engine->owner = pthread_self();
    atomic_store(&engine->cancel_requested, 0);
    /* QuickJS stack limits are relative to the thread that last updated the stack top. */
    JS_UpdateStackTop(engine->rt);
    started = now_ms();
    if (timeout_ms == 0) {
        timeout_ms = WEIZHI_DEFAULT_TIMEOUT_MS;
    }
    engine->deadline_ms = timeout_ms > 0 ? started + timeout_ms : 0;
    weizhi_timers_clear(engine);
    source_q = quote_json(source);
    if (source_q != NULL) {
        data = malloc(strlen(source_q) + 16);
        if (data != NULL) {
            snprintf(data, strlen(source_q) + 16, "{\"source\":%s}", source_q);
            emit_log(engine, "run_js_start", data);
            free(data);
        }
        free(source_q);
    }
    if (JS_DetectModule(source, strlen(source))) {
        eval_flags = JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY;
    } else {
        eval_flags = JS_EVAL_TYPE_GLOBAL | JS_EVAL_FLAG_ASYNC;
    }
    value = JS_Eval(engine->ctx, source, strlen(source), eval_name, eval_flags);
    if (JS_IsException(value)) {
        take_exception(engine, &result);
        result.ok = 0;
    } else {
        JSModuleDef *module_def = NULL;
        if ((eval_flags & JS_EVAL_TYPE_MODULE) != 0) {
            module_def = JS_VALUE_GET_PTR(value);
            value = JS_EvalFunction(engine->ctx, value);
            if (JS_IsException(value)) {
                take_exception(engine, &result);
                result.ok = 0;
                goto done;
            }
        }
        value = weizhi_await_value(engine, value);
        if (JS_IsException(value)) {
            take_exception(engine, &result);
            result.ok = 0;
        } else {
            /* ASYNC scripts wrap the settled value in { value } to avoid confusing it with the Promise itself. */
            if ((eval_flags & JS_EVAL_FLAG_ASYNC) != 0 && JS_IsObject(value)) {
                JSValue inner = JS_GetPropertyStr(engine->ctx, value, "value");
                if (!JS_IsException(inner)) {
                    JS_FreeValue(engine->ctx, value);
                    value = inner;
                }
            }
            /* Module scripts: prefer export default as the runJs result. */
            if (module_def != NULL && (JS_IsUndefined(value) || JS_IsNull(value))) {
                JSValue ns = JS_GetModuleNamespace(engine->ctx, module_def);
                if (!JS_IsException(ns)) {
                    JSValue def = JS_GetPropertyStr(engine->ctx, ns, "default");
                    JS_FreeValue(engine->ctx, ns);
                    if (!JS_IsException(def) && !JS_IsUndefined(def)) {
                        JS_FreeValue(engine->ctx, value);
                        value = def;
                    } else {
                        JS_FreeValue(engine->ctx, def);
                    }
                }
            }
            {
            JSValue json = JS_JSONStringify(engine->ctx, value, JS_UNDEFINED, JS_UNDEFINED);
            JS_FreeValue(engine->ctx, value);
            if (JS_IsException(json) || JS_IsUndefined(json)) {
                if (JS_IsException(json)) {
                    take_exception(engine, &result);
                } else {
                    result.output_text = strdup("");
                    result.ok = 1;
                }
            } else {
                const char *text = JS_ToCString(engine->ctx, json);
                result.output_text = strdup(text != NULL ? text : "");
                result.ok = 1;
                if (text != NULL) {
                    JS_FreeCString(engine->ctx, text);
                }
            }
            if (!JS_IsUndefined(json)) {
                JS_FreeValue(engine->ctx, json);
            }
            }
        }
    }
done:
    result.duration_ms = (int)(now_ms() - started);
    emit_log(engine, "run_js_end", result.ok ? "{\"ok\":true}" : "{\"ok\":false}");
    engine->deadline_ms = 0;
    weizhi_timers_clear(engine);
    atomic_store(&engine->state, ST_IDLE);
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

void weizhi_bytes_free(WeizhiBytes *bytes) {
    if (bytes == NULL) {
        return;
    }
    free(bytes->data);
    bytes->data = NULL;
    bytes->len = 0;
}

void weizhi_wake(Engine *engine) {
    if (engine == NULL) {
        return;
    }
    pthread_mutex_lock(&engine->wake_mu);
    pthread_cond_signal(&engine->wake_cv);
    pthread_mutex_unlock(&engine->wake_mu);
}

void weizhi_timers_clear(Engine *engine) {
    int i;
    for (i = 0; i < WEIZHI_MAX_TIMERS; i++) {
        if (engine->timers[i].active) {
            JS_FreeValue(engine->ctx, engine->timers[i].callback);
            engine->timers[i].active = 0;
            engine->timers[i].callback = JS_UNDEFINED;
        }
    }
}

void weizhi_pending_clear(Engine *engine) {
    int i;
    for (i = 0; i < WEIZHI_MAX_PENDING; i++) {
        if (engine->pending[i].in_use) {
            JS_FreeValue(engine->ctx, engine->pending[i].resolve);
            JS_FreeValue(engine->ctx, engine->pending[i].reject);
            weizhi_bytes_free(&engine->pending[i].out);
            free(engine->pending[i].error);
            free(engine->pending[i].headers_json);
            memset(&engine->pending[i], 0, sizeof(engine->pending[i]));
        }
    }
}

int weizhi_fire_due_timers(Engine *engine) {
    int i;
    int fired = 0;
    int64_t now = now_ms();
    for (i = 0; i < WEIZHI_MAX_TIMERS; i++) {
        WeizhiTimer *timer = &engine->timers[i];
        JSValue ret;
        if (!timer->active || timer->when_ms > now) {
            continue;
        }
        timer->active = 0;
        ret = JS_Call(engine->ctx, timer->callback, JS_UNDEFINED, 0, NULL);
        JS_FreeValue(engine->ctx, timer->callback);
        timer->callback = JS_UNDEFINED;
        fired++;
        if (JS_IsException(ret)) {
            return -1;
        }
        JS_FreeValue(engine->ctx, ret);
    }
    return fired;
}

int weizhi_apply_completions(Engine *engine) {
    int i;
    int applied = 0;
    pthread_mutex_lock(&engine->wake_mu);
    for (i = 0; i < WEIZHI_MAX_PENDING; i++) {
        WeizhiPending *p = &engine->pending[i];
        JSValue arg;
        JSValue ret;
        if (!p->in_use || !p->completed) {
            continue;
        }
        pthread_mutex_unlock(&engine->wake_mu);
        if (p->ok) {
            if (p->kind == WEIZHI_PENDING_FETCH) {
                arg = weizhi_make_fetch_response(engine->ctx, p->http_status, p->headers_json, &p->out);
            } else if (p->kind == WEIZHI_PENDING_NATIVE) {
                arg = weizhi_make_native_plugin(engine->ctx, p->headers_json);
            } else if (p->out.data != NULL) {
                arg = weizhi_bytes_to_buffer(engine->ctx, p->out.data, p->out.len);
            } else {
                arg = JS_UNDEFINED;
            }
            ret = JS_Call(engine->ctx, p->resolve, JS_UNDEFINED, 1, &arg);
            JS_FreeValue(engine->ctx, arg);
        } else {
            arg = JS_NewString(engine->ctx, p->error != NULL ? p->error : "async operation failed");
            ret = JS_Call(engine->ctx, p->reject, JS_UNDEFINED, 1, &arg);
            JS_FreeValue(engine->ctx, arg);
        }
        JS_FreeValue(engine->ctx, p->resolve);
        JS_FreeValue(engine->ctx, p->reject);
        weizhi_bytes_free(&p->out);
        free(p->error);
        free(p->headers_json);
        memset(p, 0, sizeof(*p));
        applied++;
        if (JS_IsException(ret)) {
            return -1;
        }
        JS_FreeValue(engine->ctx, ret);
        pthread_mutex_lock(&engine->wake_mu);
    }
    pthread_mutex_unlock(&engine->wake_mu);
    return applied;
}

int64_t weizhi_pending_add(Engine *engine, JSValue resolve, JSValue reject) {
    int i;
    for (i = 0; i < WEIZHI_MAX_PENDING; i++) {
        if (!engine->pending[i].in_use) {
            engine->pending[i].in_use = 1;
            engine->pending[i].completed = 0;
            engine->pending[i].id = engine->next_request_id++;
            engine->pending[i].resolve = resolve;
            engine->pending[i].reject = reject;
            return engine->pending[i].id;
        }
    }
    JS_FreeValue(engine->ctx, resolve);
    JS_FreeValue(engine->ctx, reject);
    return -1;
}

JSValue weizhi_await_value(Engine *engine, JSValue value) {
    for (;;) {
        JSPromiseStateEnum state = JS_PromiseState(engine->ctx, value);
        int err;
        int fired;
        int applied;
        struct timespec ts;
        int64_t wait_ms;
        if ((int)state < 0) {
            return value;
        }
        if (state == JS_PROMISE_FULFILLED) {
            JSValue result = JS_PromiseResult(engine->ctx, value);
            JS_FreeValue(engine->ctx, value);
            return result;
        }
        if (state == JS_PROMISE_REJECTED) {
            JSValue result = JS_PromiseResult(engine->ctx, value);
            JS_FreeValue(engine->ctx, value);
            return JS_Throw(engine->ctx, result);
        }
        if (atomic_load(&engine->cancel_requested)) {
            JS_FreeValue(engine->ctx, value);
            return JS_ThrowInternalError(engine->ctx, "cancelled");
        }
        if (engine->deadline_ms != 0 && now_ms() >= engine->deadline_ms) {
            JS_FreeValue(engine->ctx, value);
            return JS_ThrowInternalError(engine->ctx, "interrupted");
        }
        for (;;) {
            err = JS_ExecutePendingJob(engine->rt, NULL);
            if (err < 0) {
                JS_FreeValue(engine->ctx, value);
                return JS_EXCEPTION;
            }
            if (err == 0) {
                break;
            }
        }
        fired = weizhi_fire_due_timers(engine);
        if (fired < 0) {
            JS_FreeValue(engine->ctx, value);
            return JS_EXCEPTION;
        }
        applied = weizhi_apply_completions(engine);
        if (applied < 0) {
            JS_FreeValue(engine->ctx, value);
            return JS_EXCEPTION;
        }
        {
            int cbd = weizhi_drain_cb_queue(engine);
            if (cbd < 0) {
                JS_FreeValue(engine->ctx, value);
                return JS_EXCEPTION;
            }
            if (cbd > 0) {
                continue;
            }
        }
        if (fired > 0 || applied > 0 || JS_IsJobPending(engine->rt)) {
            continue;
        }
        wait_ms = 5;
        if (engine->deadline_ms != 0) {
            int64_t left = engine->deadline_ms - now_ms();
            if (left <= 0) {
                JS_FreeValue(engine->ctx, value);
                return JS_ThrowInternalError(engine->ctx, "interrupted");
            }
            if (left < wait_ms) {
                wait_ms = left;
            }
        }
        {
            int i;
            int64_t now = now_ms();
            for (i = 0; i < WEIZHI_MAX_TIMERS; i++) {
                if (engine->timers[i].active) {
                    int64_t left = engine->timers[i].when_ms - now;
                    if (left < wait_ms) {
                        wait_ms = left < 0 ? 0 : left;
                    }
                }
            }
        }
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += (long)(wait_ms % 1000) * 1000000L;
        ts.tv_sec += (time_t)(wait_ms / 1000);
        if (ts.tv_nsec >= 1000000000L) {
            ts.tv_sec += 1;
            ts.tv_nsec -= 1000000000L;
        }
        pthread_mutex_lock(&engine->wake_mu);
        pthread_cond_timedwait(&engine->wake_cv, &engine->wake_mu, &ts);
        pthread_mutex_unlock(&engine->wake_mu);
    }
}

void weizhi_cancel(WeizhiEngine *engine) {
    if (engine == NULL) {
        return;
    }
    atomic_store(&engine->cancel_requested, 1);
    pthread_mutex_lock(&engine->wake_mu);
    pthread_cond_broadcast(&engine->wake_cv);
    pthread_mutex_unlock(&engine->wake_mu);
}

void weizhi_complete(WeizhiEngine *engine, int64_t request_id, int ok, const WeizhiBytes *out, const char *error) {
    int i;
    if (engine == NULL) {
        return;
    }
    pthread_mutex_lock(&engine->wake_mu);
    for (i = 0; i < WEIZHI_MAX_PENDING; i++) {
        WeizhiPending *p = &engine->pending[i];
        if (!p->in_use || p->id != request_id || p->completed) {
            continue;
        }
        p->completed = 1;
        p->ok = ok;
        p->kind = WEIZHI_PENDING_BUFFER;
        if (ok && out != NULL && out->data != NULL && out->len > 0) {
            p->out.data = malloc(out->len);
            if (p->out.data != NULL) {
                memcpy(p->out.data, out->data, out->len);
                p->out.len = out->len;
            } else {
                p->ok = 0;
                p->error = strdup("out of memory");
            }
        } else if (!ok) {
            p->error = strdup(error != NULL ? error : "async operation failed");
        }
        break;
    }
    pthread_cond_signal(&engine->wake_cv);
    pthread_mutex_unlock(&engine->wake_mu);
}

void weizhi_complete_fetch(WeizhiEngine *engine, int64_t request_id, int status, const char *headers_json,
                           const WeizhiBytes *body, const char *error) {
    int i;
    if (engine == NULL) {
        return;
    }
    pthread_mutex_lock(&engine->wake_mu);
    for (i = 0; i < WEIZHI_MAX_PENDING; i++) {
        WeizhiPending *p = &engine->pending[i];
        if (!p->in_use || p->id != request_id || p->completed) {
            continue;
        }
        p->completed = 1;
        p->kind = WEIZHI_PENDING_FETCH;
        if (error != NULL && error[0] != '\0') {
            p->ok = 0;
            p->error = strdup(error);
            break;
        }
        p->ok = 1;
        p->http_status = status;
        p->headers_json = strdup(headers_json != NULL ? headers_json : "{}");
        if (p->headers_json == NULL) {
            p->ok = 0;
            p->error = strdup("out of memory");
            break;
        }
        if (body != NULL && body->data != NULL && body->len > 0) {
            p->out.data = malloc(body->len);
            if (p->out.data == NULL) {
                p->ok = 0;
                free(p->headers_json);
                p->headers_json = NULL;
                p->error = strdup("out of memory");
                break;
            }
            memcpy(p->out.data, body->data, body->len);
            p->out.len = body->len;
        }
        break;
    }
    pthread_cond_signal(&engine->wake_cv);
    pthread_mutex_unlock(&engine->wake_mu);
}

void weizhi_set_http(WeizhiEngine *engine, WeizhiHttpAsyncFn async_fn, void *userdata) {
    if (engine == NULL) {
        return;
    }
    engine->http_async = async_fn;
    engine->http_ud = userdata;
    weizhi_refresh_caps(engine);
}

void weizhi_set_native(WeizhiEngine *engine, WeizhiNativeEnsureFn ensure_fn, WeizhiNativeCallFn call_fn,
                       void *userdata) {
    if (engine == NULL) {
        return;
    }
    engine->native_ensure = ensure_fn;
    engine->native_call = call_fn;
    engine->native_ud = userdata;
    weizhi_refresh_caps(engine);
}

void weizhi_complete_native(WeizhiEngine *engine, int64_t request_id, int ok, const char *plugin_json,
                            const char *error) {
    int i;
    if (engine == NULL) {
        return;
    }
    pthread_mutex_lock(&engine->wake_mu);
    for (i = 0; i < WEIZHI_MAX_PENDING; i++) {
        WeizhiPending *p = &engine->pending[i];
        if (!p->in_use || p->id != request_id || p->completed) {
            continue;
        }
        p->completed = 1;
        p->kind = WEIZHI_PENDING_NATIVE;
        if (!ok || error != NULL) {
            p->ok = 0;
            p->error = strdup(error != NULL ? error : "native ensure failed");
            break;
        }
        if (plugin_json == NULL || plugin_json[0] == '\0') {
            p->ok = 0;
            p->error = strdup("native ensure failed: empty plugin json");
            break;
        }
        p->ok = 1;
        p->headers_json = strdup(plugin_json);
        if (p->headers_json == NULL) {
            p->ok = 0;
            p->error = strdup("out of memory");
        }
        break;
    }
    pthread_cond_signal(&engine->wake_cv);
    pthread_mutex_unlock(&engine->wake_mu);
}

int weizhi_set_fs_root(WeizhiEngine *engine, const char *folder) {
    char *copy;
    if (engine == NULL || folder == NULL || folder[0] == '\0') {
        return -1;
    }
    if (atomic_load(&engine->state) != ST_IDLE) {
        return -1;
    }
    copy = strdup(folder);
    if (copy == NULL) {
        return -1;
    }
    free(engine->fs_root);
    engine->fs_root = copy;
    return 0;
}

void weizhi_set_vfs(WeizhiEngine *engine, WeizhiVfsSyncFn sync_fn, WeizhiVfsAsyncFn async_fn, void *userdata) {
    if (engine == NULL) {
        return;
    }
    if (sync_fn != NULL) {
        engine->vfs_sync = sync_fn;
        if (userdata != NULL) {
            engine->vfs_ud = userdata;
        }
    }
    if (async_fn != NULL) {
        engine->vfs_async = async_fn;
        if (userdata != NULL) {
            engine->vfs_async_ud = userdata;
        }
    }
}

int weizhi_path_ok(const char *relpath) {
    size_t i;
    if (relpath == NULL || relpath[0] == '\0') {
        return 0;
    }
    if (relpath[0] == '/' || relpath[0] == '\\') {
        return 0;
    }
    if (strstr(relpath, "..") != NULL) {
        return 0;
    }
    for (i = 0; relpath[i] != '\0'; i++) {
        if (relpath[i] == '\\') {
            return 0;
        }
    }
    return 1;
}
