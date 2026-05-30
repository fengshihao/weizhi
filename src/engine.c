#include "weizhi.h"

#include "wasm_export.h"

#include "quickjs.h"

#include <ctype.h>
#include <limits.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define ST_IDLE 0
#define ST_RUNNING 1
#define ST_CLOSED 2
#define MAX_WASM_FILE_BYTES (16u * 1024u * 1024u)
#define MAX_EXPORTS 64
#define MAX_I32_PARAMS 8

typedef struct WeizhiEngine Engine;

typedef struct HostFn {
    char *name;
    WeizhiHostFn fn;
    void *userdata;
} HostFn;

typedef struct PackInst {
    Engine *engine;
    wasm_module_t module;
    wasm_module_inst_t inst;
    wasm_exec_env_t exec;
    uint8_t *bytes;
} PackInst;

struct WeizhiEngine {
    JSRuntime *rt;
    JSContext *ctx;
    WeizhiLimits limits;
    HostFn *hosts;
    int host_count;
    int pack_count;
    char *pack_folder;
    char *run_id;
    WeizhiLogFn log_fn;
    void *log_ud;
    int seq;
    int64_t deadline_ms;
    pthread_t owner;
    atomic_int state;
};

static JSClassID g_pack_class_id;
static pthread_once_t g_class_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_wamr_mu = PTHREAD_MUTEX_INITIALIZER;
static int g_wamr_refs = 0;

static void init_pack_class_id(void) {
    JS_NewClassID(&g_pack_class_id);
}

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

static int wamr_acquire(void) {
    int rc = 0;
    RuntimeInitArgs args;
    pthread_mutex_lock(&g_wamr_mu);
    if (g_wamr_refs == 0) {
        memset(&args, 0, sizeof(args));
        args.mem_alloc_type = Alloc_With_System_Allocator;
        if (!wasm_runtime_full_init(&args)) {
            rc = -1;
        }
    }
    if (rc == 0) {
        g_wamr_refs++;
    }
    pthread_mutex_unlock(&g_wamr_mu);
    return rc;
}

static void wamr_release(void) {
    pthread_mutex_lock(&g_wamr_mu);
    g_wamr_refs--;
    if (g_wamr_refs == 0) {
        wasm_runtime_destroy();
    }
    pthread_mutex_unlock(&g_wamr_mu);
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

static int interrupt_cb(JSRuntime *rt, void *opaque) {
    Engine *engine = opaque;
    (void)rt;
    if (engine->deadline_ms == 0) {
        return 0;
    }
    return now_ms() >= engine->deadline_ms;
}

static int valid_leaf_name(const char *name) {
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
        *error = "找不到能力包";
        return NULL;
    }
    if (fseek(file, 0, SEEK_END) != 0) {
        fclose(file);
        *error = "找不到能力包";
        return NULL;
    }
    size = ftell(file);
    if (size < 0 || (size_t)size > MAX_WASM_FILE_BYTES) {
        fclose(file);
        *error = "能力包文件太大";
        return NULL;
    }
    rewind(file);
    buf = malloc((size_t)size + 1);
    if (buf == NULL || (size > 0 && fread(buf, 1, (size_t)size, file) != (size_t)size)) {
        free(buf);
        fclose(file);
        *error = "找不到能力包";
        return NULL;
    }
    fclose(file);
    buf[size] = '\0';
    *out_len = (size_t)size;
    return buf;
}

static int read_leb(const uint8_t *buf, size_t len, size_t *offset, uint32_t *out) {
    uint32_t result = 0;
    int shift = 0;
    while (*offset < len && shift <= 28) {
        uint8_t byte = buf[(*offset)++];
        result |= (uint32_t)(byte & 0x7f) << shift;
        if ((byte & 0x80) == 0) {
            *out = result;
            return 0;
        }
        shift += 7;
    }
    return -1;
}

static int wasm_linear_bytes(const uint8_t *buf, size_t len, uint64_t *bytes, const char **error) {
    size_t offset = 8;
    *bytes = 0;
    if (len < 8 || memcmp(buf, "\0asm", 4) != 0) {
        *error = "能力包不是合法的 wasm";
        return -1;
    }
    while (offset < len) {
        uint32_t id = 0;
        uint32_t size = 0;
        size_t content;
        if (read_leb(buf, len, &offset, &id) != 0 || read_leb(buf, len, &offset, &size) != 0) {
            *error = "能力包不是合法的 wasm";
            return -1;
        }
        content = offset;
        if ((size_t)size > len - offset) {
            *error = "能力包不是合法的 wasm";
            return -1;
        }
        if (id == 5) {
            uint32_t count = 0;
            uint32_t i;
            if (read_leb(buf, len, &offset, &count) != 0) {
                *error = "能力包不是合法的 wasm";
                return -1;
            }
            for (i = 0; i < count; i++) {
                uint32_t flags = 0;
                uint32_t min_pages = 0;
                if (read_leb(buf, len, &offset, &flags) != 0 ||
                    read_leb(buf, len, &offset, &min_pages) != 0) {
                    *error = "能力包不是合法的 wasm";
                    return -1;
                }
                if ((flags & 1u) != 0) {
                    uint32_t max_pages = 0;
                    if (read_leb(buf, len, &offset, &max_pages) != 0) {
                        *error = "能力包不是合法的 wasm";
                        return -1;
                    }
                }
                *bytes = (uint64_t)min_pages * 65536u;
            }
        }
        offset = content + size;
    }
    return 0;
}

static void pack_finalizer(JSRuntime *rt, JSValue val) {
    PackInst *pack = JS_GetOpaque(val, g_pack_class_id);
    (void)rt;
    if (pack == NULL) {
        return;
    }
    if (pack->exec != NULL) {
        wasm_runtime_destroy_exec_env(pack->exec);
    }
    if (pack->inst != NULL) {
        wasm_runtime_deinstantiate(pack->inst);
    }
    if (pack->module != NULL) {
        wasm_runtime_unload(pack->module);
    }
    free(pack->bytes);
    if (pack->engine != NULL && atomic_load(&pack->engine->state) != ST_CLOSED &&
        pack->engine->pack_count > 0) {
        pack->engine->pack_count--;
    }
    free(pack);
}

static JSValue js_call_export(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                              int magic, JSValue *func_data) {
    PackInst *pack = JS_GetOpaque(func_data[0], g_pack_class_id);
    wasm_export_t exp;
    wasm_function_inst_t func;
    uint32_t param_count;
    uint32_t result_count;
    wasm_valkind_t param_kinds[MAX_I32_PARAMS];
    wasm_valkind_t result_kinds[1];
    uint32_t cells[MAX_I32_PARAMS];
    uint32_t i;
    char error_buf[128];
    (void)this_val;
    if (pack == NULL || pack->inst == NULL) {
        return JS_ThrowInternalError(ctx, "能力包已经释放");
    }
    memset(&exp, 0, sizeof(exp));
    wasm_runtime_get_export_type(pack->module, magic, &exp);
    if (exp.kind != WASM_IMPORT_EXPORT_KIND_FUNC || exp.name == NULL) {
        return JS_ThrowTypeError(ctx, "这个导出不是函数");
    }
    func = wasm_runtime_lookup_function(pack->inst, exp.name);
    if (func == NULL) {
        return JS_ThrowReferenceError(ctx, "找不到导出函数");
    }
    param_count = wasm_func_get_param_count(func, pack->inst);
    result_count = wasm_func_get_result_count(func, pack->inst);
    if (param_count > MAX_I32_PARAMS || result_count > 1) {
        return JS_ThrowTypeError(ctx, "这个函数的参数类型目前只支持少量整数");
    }
    if (param_count > 0) {
        wasm_func_get_param_types(func, pack->inst, param_kinds);
    }
    if (result_count == 1) {
        wasm_func_get_result_types(func, pack->inst, result_kinds);
        if (result_kinds[0] != WASM_I32) {
            return JS_ThrowTypeError(ctx, "这个函数的参数类型目前只支持整数");
        }
    }
    if ((uint32_t)argc < param_count) {
        return JS_ThrowTypeError(ctx, "参数个数不够");
    }
    for (i = 0; i < param_count; i++) {
        int32_t value = 0;
        if (param_kinds[i] != WASM_I32 || JS_ToInt32(ctx, &value, argv[i]) != 0) {
            return JS_ThrowTypeError(ctx, "这个函数的参数类型目前只支持整数");
        }
        cells[i] = (uint32_t)value;
    }
    if (!wasm_runtime_call_wasm(pack->exec, func, param_count, cells)) {
        const char *exception = wasm_runtime_get_exception(pack->inst);
        snprintf(error_buf, sizeof(error_buf), "能力包执行失败%s%s",
                 exception != NULL ? "：" : "", exception != NULL ? exception : "");
        wasm_runtime_clear_exception(pack->inst);
        return JS_ThrowInternalError(ctx, "%s", error_buf);
    }
    if (result_count == 0) {
        return JS_UNDEFINED;
    }
    return JS_NewInt32(ctx, (int32_t)cells[0]);
}

static JSValue js_load_pack(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = JS_GetContextOpaque(ctx);
    const char *name = NULL;
    char wasm_file[80];
    char aot_file[80];
    char path[PATH_MAX];
    const char *error = NULL;
    uint8_t *bytes = NULL;
    size_t length = 0;
    uint64_t linear = 0;
    char wasm_error[128];
    wasm_module_t module = NULL;
    wasm_module_inst_t inst = NULL;
    wasm_exec_env_t exec = NULL;
    PackInst *pack = NULL;
    JSValue obj = JS_EXCEPTION;
    int32_t export_count;
    int32_t index;
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "能力包名字不合法");
    }
    name = JS_ToCString(ctx, argv[0]);
    if (name == NULL) {
        return JS_EXCEPTION;
    }
    if (!valid_leaf_name(name)) {
        JS_FreeCString(ctx, name);
        return JS_ThrowTypeError(ctx, "能力包名字不合法");
    }
    if (engine->pack_folder == NULL) {
        JS_FreeCString(ctx, name);
        return JS_ThrowReferenceError(ctx, "还没有设置能力包文件夹");
    }
    if (engine->pack_count >= engine->limits.max_packs) {
        JS_FreeCString(ctx, name);
        return JS_ThrowRangeError(ctx, "同时装入的能力包太多");
    }
    snprintf(wasm_file, sizeof(wasm_file), "%s.wasm", name);
    snprintf(aot_file, sizeof(aot_file), "%s.aot", name);
    if (resolve_under(engine->pack_folder, wasm_file, path, sizeof(path)) != 0) {
        if (resolve_under(engine->pack_folder, aot_file, path, sizeof(path)) == 0) {
            JS_FreeCString(ctx, name);
            return JS_ThrowTypeError(ctx, "当前构建不能执行 aot 包");
        }
        JS_FreeCString(ctx, name);
        return JS_ThrowReferenceError(ctx, "找不到能力包");
    }
    bytes = read_file(path, &length, &error);
    if (bytes == NULL) {
        JS_FreeCString(ctx, name);
        return JS_ThrowInternalError(ctx, "%s", error);
    }
    if (wasm_linear_bytes(bytes, length, &linear, &error) != 0) {
        free(bytes);
        JS_FreeCString(ctx, name);
        return JS_ThrowTypeError(ctx, "%s", error);
    }
    if (linear > engine->limits.wasm_max_linear_bytes) {
        free(bytes);
        JS_FreeCString(ctx, name);
        return JS_ThrowRangeError(ctx, "能力包需要的内存超过了上限");
    }
    wasm_error[0] = '\0';
    module = wasm_runtime_load(bytes, (uint32_t)length, wasm_error, sizeof(wasm_error));
    if (module == NULL) {
        free(bytes);
        JS_FreeCString(ctx, name);
        return JS_ThrowInternalError(ctx, "能力包无法装载：%s", wasm_error);
    }
    inst = wasm_runtime_instantiate(module, (uint32_t)engine->limits.wasm_stack_bytes,
                                    (uint32_t)engine->limits.wasm_heap_bytes, wasm_error,
                                    sizeof(wasm_error));
    if (inst == NULL) {
        wasm_runtime_unload(module);
        free(bytes);
        JS_FreeCString(ctx, name);
        return JS_ThrowInternalError(ctx, "能力包无法启动：%s", wasm_error);
    }
    exec = wasm_runtime_create_exec_env(inst, (uint32_t)engine->limits.wasm_stack_bytes);
    if (exec == NULL) {
        wasm_runtime_deinstantiate(inst);
        wasm_runtime_unload(module);
        free(bytes);
        JS_FreeCString(ctx, name);
        return JS_ThrowInternalError(ctx, "能力包无法启动");
    }
    pack = calloc(1, sizeof(*pack));
    if (pack == NULL) {
        wasm_runtime_destroy_exec_env(exec);
        wasm_runtime_deinstantiate(inst);
        wasm_runtime_unload(module);
        free(bytes);
        JS_FreeCString(ctx, name);
        return JS_ThrowOutOfMemory(ctx);
    }
    pack->engine = engine;
    pack->module = module;
    pack->inst = inst;
    pack->exec = exec;
    pack->bytes = bytes;
    obj = JS_NewObjectClass(ctx, (int)g_pack_class_id);
    if (JS_IsException(obj)) {
        wasm_runtime_destroy_exec_env(exec);
        wasm_runtime_deinstantiate(inst);
        wasm_runtime_unload(module);
        free(bytes);
        free(pack);
        JS_FreeCString(ctx, name);
        return JS_EXCEPTION;
    }
    JS_SetOpaque(obj, pack);
    engine->pack_count++;
    export_count = wasm_runtime_get_export_count(module);
    for (index = 0; index < export_count && index < MAX_EXPORTS; index++) {
        wasm_export_t exp;
        JSValue data[1];
        JSValue fn;
        memset(&exp, 0, sizeof(exp));
        wasm_runtime_get_export_type(module, index, &exp);
        if (exp.kind != WASM_IMPORT_EXPORT_KIND_FUNC || exp.name == NULL) {
            continue;
        }
        data[0] = obj;
        fn = JS_NewCFunctionData(ctx, js_call_export, 0, index, 1, data);
        JS_DefinePropertyValueStr(ctx, obj, exp.name, fn, JS_PROP_C_W_E);
    }
    {
        char *quoted = quote_json(name);
        char data[160];
        if (quoted != NULL) {
            snprintf(data, sizeof(data), "{\"name\":%s}", quoted);
            emit_log(engine, "load_pack", data);
            free(quoted);
        }
    }
    JS_FreeCString(ctx, name);
    return obj;
}

static JSValue js_load_script(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = JS_GetContextOpaque(ctx);
    const char *name = NULL;
    char path[PATH_MAX];
    const char *error = NULL;
    uint8_t *bytes;
    size_t length = 0;
    JSValue value;
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "脚本名字不合法");
    }
    name = JS_ToCString(ctx, argv[0]);
    if (name == NULL) {
        return JS_EXCEPTION;
    }
    if (!valid_leaf_name(name) || strlen(name) < 4 || strcmp(name + strlen(name) - 3, ".js") != 0) {
        JS_FreeCString(ctx, name);
        return JS_ThrowTypeError(ctx, "脚本名字不合法");
    }
    if (engine->pack_folder == NULL) {
        JS_FreeCString(ctx, name);
        return JS_ThrowReferenceError(ctx, "还没有设置能力包文件夹");
    }
    if (resolve_under(engine->pack_folder, name, path, sizeof(path)) != 0) {
        JS_FreeCString(ctx, name);
        return JS_ThrowReferenceError(ctx, "找不到脚本");
    }
    bytes = read_file(path, &length, &error);
    if (bytes == NULL) {
        JS_FreeCString(ctx, name);
        return JS_ThrowInternalError(ctx, "%s", error != NULL ? error : "找不到脚本");
    }
    value = JS_Eval(ctx, (const char *)bytes, length, name, JS_EVAL_TYPE_GLOBAL);
    free(bytes);
    {
        char *quoted = quote_json(name);
        char data[160];
        if (quoted != NULL) {
            snprintf(data, sizeof(data), "{\"name\":%s}", quoted);
            emit_log(engine, "load_script", data);
            free(quoted);
        }
    }
    JS_FreeCString(ctx, name);
    return value;
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
        return JS_ThrowReferenceError(ctx, "找不到这个功能");
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

static void install_builtin(Engine *engine, const char *name, JSCFunction *fn) {
    JSValue global = JS_GetGlobalObject(engine->ctx);
    JSValue func = JS_NewCFunction(engine->ctx, fn, name, 1);
    JS_SetPropertyStr(engine->ctx, global, name, func);
    JS_FreeValue(engine->ctx, global);
}

static size_t size_or_default(size_t value, size_t fallback) {
    return value == 0 ? fallback : value;
}

static int int_or_default(int value, int fallback) {
    return value > 0 ? value : fallback;
}

WeizhiEngine *weizhi_open(const WeizhiLimits *limits) {
    Engine *engine = calloc(1, sizeof(*engine));
    JSClassDef class_def;
    if (engine == NULL) {
        return NULL;
    }
    pthread_once(&g_class_once, init_pack_class_id);
    if (wamr_acquire() != 0) {
        free(engine);
        return NULL;
    }
    engine->limits.js_heap_bytes = size_or_default(limits ? limits->js_heap_bytes : 0, WEIZHI_DEFAULT_JS_HEAP_BYTES);
    engine->limits.js_stack_bytes = size_or_default(limits ? limits->js_stack_bytes : 0, WEIZHI_DEFAULT_JS_STACK_BYTES);
    engine->limits.max_packs = int_or_default(limits ? limits->max_packs : 0, WEIZHI_DEFAULT_MAX_PACKS);
    engine->limits.max_host_functions =
        int_or_default(limits ? limits->max_host_functions : 0, WEIZHI_DEFAULT_MAX_HOST_FUNCTIONS);
    engine->limits.wasm_stack_bytes =
        size_or_default(limits ? limits->wasm_stack_bytes : 0, WEIZHI_DEFAULT_WASM_STACK_BYTES);
    engine->limits.wasm_heap_bytes =
        size_or_default(limits ? limits->wasm_heap_bytes : 0, WEIZHI_DEFAULT_WASM_HEAP_BYTES);
    engine->limits.wasm_max_linear_bytes =
        size_or_default(limits ? limits->wasm_max_linear_bytes : 0, WEIZHI_DEFAULT_WASM_MAX_LINEAR_BYTES);
    atomic_init(&engine->state, ST_IDLE);
    engine->rt = JS_NewRuntime();
    if (engine->rt == NULL) {
        wamr_release();
        free(engine);
        return NULL;
    }
    JS_SetMemoryLimit(engine->rt, engine->limits.js_heap_bytes);
    JS_SetMaxStackSize(engine->rt, engine->limits.js_stack_bytes);
    JS_SetInterruptHandler(engine->rt, interrupt_cb, engine);
    engine->ctx = JS_NewContext(engine->rt);
    if (engine->ctx == NULL) {
        JS_FreeRuntime(engine->rt);
        wamr_release();
        free(engine);
        return NULL;
    }
    JS_SetContextOpaque(engine->ctx, engine);
    memset(&class_def, 0, sizeof(class_def));
    class_def.class_name = "WeizhiPack";
    class_def.finalizer = pack_finalizer;
    JS_NewClass(engine->rt, g_pack_class_id, &class_def);
    install_builtin(engine, "loadPack", js_load_pack);
    install_builtin(engine, "loadScript", js_load_script);
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
    JS_FreeContext(engine->ctx);
    JS_FreeRuntime(engine->rt);
    wamr_release();
    for (i = 0; i < engine->host_count; i++) {
        free(engine->hosts[i].name);
    }
    free(engine->hosts);
    free(engine->pack_folder);
    free(engine->run_id);
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
    if (strcmp(name, "loadPack") == 0 || strcmp(name, "loadScript") == 0) {
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

int weizhi_set_pack_folder(WeizhiEngine *engine, const char *folder) {
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
    free(engine->pack_folder);
    engine->pack_folder = copy;
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
    if (message != NULL && strstr(message, "interrupted") != NULL) {
        result->error = strdup("脚本运行超过了时间限制");
    } else if (message != NULL && strstr(message, "out of memory") != NULL) {
        result->error = strdup("脚本使用的内存超过了上限");
    } else if (message != NULL && strstr(message, "stack") != NULL) {
        result->error = strdup("脚本调用太深，超过了栈上限");
    } else if (message != NULL) {
        result->error = strdup(message);
    } else {
        result->error = strdup("脚本运行失败");
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
    WeizhiResult result;
    int64_t started;
    int expected = ST_IDLE;
    JSValue value;
    char *source_q;
    char *data;
    memset(&result, 0, sizeof(result));
    if (engine == NULL || source == NULL) {
        return fail_immediately("没有可运行的脚本");
    }
    if (!atomic_compare_exchange_strong(&engine->state, &expected, ST_RUNNING)) {
        if (expected == ST_RUNNING && pthread_equal(engine->owner, pthread_self())) {
            return fail_immediately("不能在脚本里面再次运行脚本");
        }
        return fail_immediately("发动机正忙");
    }
    engine->owner = pthread_self();
    started = now_ms();
    if (timeout_ms == 0) {
        timeout_ms = WEIZHI_DEFAULT_TIMEOUT_MS;
    }
    engine->deadline_ms = timeout_ms > 0 ? started + timeout_ms : 0;
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
    value = JS_Eval(engine->ctx, source, strlen(source), "<eval>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(value)) {
        take_exception(engine, &result);
        result.ok = 0;
    } else {
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
    result.duration_ms = (int)(now_ms() - started);
    emit_log(engine, "run_js_end", result.ok ? "{\"ok\":true}" : "{\"ok\":false}");
    engine->deadline_ms = 0;
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
