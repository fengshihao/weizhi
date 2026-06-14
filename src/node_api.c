#include "engine_internal.h"

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct BufferData {
    uint8_t *bytes;
    size_t len;
} BufferData;

typedef struct AsyncJob {
    Engine *engine;
    int64_t request_id;
    WeizhiVfsOp op;
    char *relpath;
    char *relpath2;
    WeizhiBytes in;
} AsyncJob;

static void buffer_finalizer(JSRuntime *rt, JSValue val) {
    Engine *engine = JS_GetRuntimeOpaque(rt);
    BufferData *data;
    if (engine == NULL) {
        return;
    }
    data = JS_GetOpaque(val, engine->buffer_class_id);
    if (data != NULL) {
        free(data->bytes);
        free(data);
    }
}

static JSValue buffer_to_string(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    BufferData *data = JS_GetOpaque(this_val, engine->buffer_class_id);
    const char *enc = "utf8";
    if (data == NULL) {
        return JS_ThrowTypeError(ctx, "不是 Buffer");
    }
    if (argc >= 1 && JS_IsString(argv[0])) {
        enc = JS_ToCString(ctx, argv[0]);
        if (enc == NULL) {
            return JS_EXCEPTION;
        }
    }
    if (strcmp(enc, "utf8") == 0 || strcmp(enc, "utf-8") == 0) {
        JSValue out = JS_NewStringLen(ctx, (const char *)data->bytes, data->len);
        if (argc >= 1 && JS_IsString(argv[0])) {
            JS_FreeCString(ctx, enc);
        }
        return out;
    }
    if (strcmp(enc, "hex") == 0) {
        size_t i;
        char *hex = malloc(data->len * 2 + 1);
        static const char *digits = "0123456789abcdef";
        if (hex == NULL) {
            if (argc >= 1 && JS_IsString(argv[0])) {
                JS_FreeCString(ctx, enc);
            }
            return JS_ThrowOutOfMemory(ctx);
        }
        for (i = 0; i < data->len; i++) {
            hex[i * 2] = digits[data->bytes[i] >> 4];
            hex[i * 2 + 1] = digits[data->bytes[i] & 0xf];
        }
        hex[data->len * 2] = '\0';
        {
            JSValue out = JS_NewString(ctx, hex);
            free(hex);
            if (argc >= 1 && JS_IsString(argv[0])) {
                JS_FreeCString(ctx, enc);
            }
            return out;
        }
    }
    if (argc >= 1 && JS_IsString(argv[0])) {
        JS_FreeCString(ctx, enc);
    }
    return JS_ThrowReferenceError(ctx, "不支持的编码");
}

static JSValue make_buffer(JSContext *ctx, const uint8_t *bytes, size_t len) {
    Engine *engine = weizhi_from_ctx(ctx);
    BufferData *data = calloc(1, sizeof(*data));
    JSValue obj;
    JSValue len_val;
    if (data == NULL) {
        return JS_ThrowOutOfMemory(ctx);
    }
    if (len > 0) {
        data->bytes = malloc(len);
        if (data->bytes == NULL) {
            free(data);
            return JS_ThrowOutOfMemory(ctx);
        }
        memcpy(data->bytes, bytes, len);
        data->len = len;
    }
    obj = JS_NewObjectClass(ctx, engine->buffer_class_id);
    if (JS_IsException(obj)) {
        free(data->bytes);
        free(data);
        return obj;
    }
    JS_SetOpaque(obj, data);
    len_val = JS_NewUint32(ctx, (uint32_t)len);
    JS_DefinePropertyValueStr(ctx, obj, "length", len_val, JS_PROP_C_W_E);
    JS_DefinePropertyValueStr(ctx, obj, "toString", JS_NewCFunction(ctx, buffer_to_string, "toString", 1),
                              JS_PROP_C_W_E);
    return obj;
}

JSValue weizhi_bytes_to_buffer(JSContext *ctx, const unsigned char *bytes, size_t len) {
    return make_buffer(ctx, bytes, len);
}

static int is_buffer(JSContext *ctx, JSValueConst val) {
    Engine *engine = weizhi_from_ctx(ctx);
    return JS_GetOpaque(val, engine->buffer_class_id) != NULL;
}

static JSValue js_buffer_from(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    size_t len = 0;
    const char *text;
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "Buffer.from 需要参数");
    }
    if (JS_IsString(argv[0])) {
        text = JS_ToCStringLen(ctx, &len, argv[0]);
        if (text == NULL) {
            return JS_EXCEPTION;
        }
        {
            JSValue buf = make_buffer(ctx, (const uint8_t *)text, len);
            JS_FreeCString(ctx, text);
            return buf;
        }
    }
    return JS_ThrowTypeError(ctx, "Buffer.from 目前只支持字符串");
}

static JSValue js_buffer_alloc(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int32_t size = 0;
    uint8_t *bytes;
    JSValue buf;
    (void)this_val;
    if (argc < 1 || JS_ToInt32(ctx, &size, argv[0]) || size < 0) {
        return JS_ThrowRangeError(ctx, "Buffer.alloc 大小无效");
    }
    bytes = calloc((size_t)size, 1);
    if (bytes == NULL && size > 0) {
        return JS_ThrowOutOfMemory(ctx);
    }
    buf = make_buffer(ctx, bytes, (size_t)size);
    free(bytes);
    return buf;
}

static JSValue js_buffer_is_buffer(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    return JS_NewBool(ctx, argc >= 1 && is_buffer(ctx, argv[0]));
}

static JSValue make_buffer_module(JSContext *ctx) {
    JSValue mod = JS_NewObject(ctx);
    JSValue buffer = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, buffer, "from", JS_NewCFunction(ctx, js_buffer_from, "from", 1));
    JS_SetPropertyStr(ctx, buffer, "alloc", JS_NewCFunction(ctx, js_buffer_alloc, "alloc", 1));
    JS_SetPropertyStr(ctx, buffer, "isBuffer", JS_NewCFunction(ctx, js_buffer_is_buffer, "isBuffer", 1));
    JS_SetPropertyStr(ctx, mod, "Buffer", buffer);
    JS_SetPropertyStr(ctx, mod, "default", JS_DupValue(ctx, buffer));
    return mod;
}

static JSValue js_path_join(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    char out[PATH_MAX];
    size_t used = 0;
    int i;
    (void)this_val;
    out[0] = '\0';
    for (i = 0; i < argc; i++) {
        const char *part = JS_ToCString(ctx, argv[i]);
        size_t len;
        if (part == NULL) {
            return JS_EXCEPTION;
        }
        len = strlen(part);
        if (len == 0) {
            JS_FreeCString(ctx, part);
            continue;
        }
        if (used > 0 && out[used - 1] != '/') {
            if (used + 1 >= sizeof(out)) {
                JS_FreeCString(ctx, part);
                return JS_ThrowRangeError(ctx, "路径太长");
            }
            out[used++] = '/';
            out[used] = '\0';
        }
        if (used + len >= sizeof(out)) {
            JS_FreeCString(ctx, part);
            return JS_ThrowRangeError(ctx, "路径太长");
        }
        memcpy(out + used, part, len + 1);
        used += len;
        JS_FreeCString(ctx, part);
    }
    return JS_NewString(ctx, out);
}

static JSValue js_path_basename(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *path;
    const char *slash;
    (void)this_val;
    if (argc < 1) {
        return JS_NewString(ctx, "");
    }
    path = JS_ToCString(ctx, argv[0]);
    if (path == NULL) {
        return JS_EXCEPTION;
    }
    slash = strrchr(path, '/');
    {
        JSValue out = JS_NewString(ctx, slash != NULL ? slash + 1 : path);
        JS_FreeCString(ctx, path);
        return out;
    }
}

static JSValue js_path_dirname(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *path;
    char tmp[PATH_MAX];
    char *slash;
    (void)this_val;
    if (argc < 1) {
        return JS_NewString(ctx, ".");
    }
    path = JS_ToCString(ctx, argv[0]);
    if (path == NULL) {
        return JS_EXCEPTION;
    }
    snprintf(tmp, sizeof(tmp), "%s", path);
    JS_FreeCString(ctx, path);
    slash = strrchr(tmp, '/');
    if (slash == NULL) {
        return JS_NewString(ctx, ".");
    }
    if (slash == tmp) {
        return JS_NewString(ctx, "/");
    }
    *slash = '\0';
    return JS_NewString(ctx, tmp);
}

static JSValue js_path_extname(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *path;
    const char *dot;
    const char *slash;
    (void)this_val;
    if (argc < 1) {
        return JS_NewString(ctx, "");
    }
    path = JS_ToCString(ctx, argv[0]);
    if (path == NULL) {
        return JS_EXCEPTION;
    }
    slash = strrchr(path, '/');
    dot = strrchr(path, '.');
    if (dot == NULL || (slash != NULL && dot < slash) || dot == path || (slash && dot == slash + 1)) {
        JS_FreeCString(ctx, path);
        return JS_NewString(ctx, "");
    }
    {
        JSValue out = JS_NewString(ctx, dot);
        JS_FreeCString(ctx, path);
        return out;
    }
}

static JSValue make_path_module(JSContext *ctx) {
    JSValue mod = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, mod, "join", JS_NewCFunction(ctx, js_path_join, "join", 2));
    JS_SetPropertyStr(ctx, mod, "basename", JS_NewCFunction(ctx, js_path_basename, "basename", 1));
    JS_SetPropertyStr(ctx, mod, "dirname", JS_NewCFunction(ctx, js_path_dirname, "dirname", 1));
    JS_SetPropertyStr(ctx, mod, "extname", JS_NewCFunction(ctx, js_path_extname, "extname", 1));
    JS_SetPropertyStr(ctx, mod, "sep", JS_NewString(ctx, "/"));
    JS_SetPropertyStr(ctx, mod, "default", JS_DupValue(ctx, mod));
    return mod;
}

static JSValue js_set_timeout(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    int32_t delay = 0;
    int i;
    (void)this_val;
    if (argc < 1 || !JS_IsFunction(ctx, argv[0])) {
        return JS_ThrowTypeError(ctx, "setTimeout 需要函数");
    }
    if (argc >= 2) {
        JS_ToInt32(ctx, &delay, argv[1]);
        if (delay < 0) {
            delay = 0;
        }
    }
    for (i = 0; i < WEIZHI_MAX_TIMERS; i++) {
        if (!engine->timers[i].active) {
            engine->timers[i].active = 1;
            engine->timers[i].id = engine->next_timer_id++;
            engine->timers[i].when_ms = weizhi_now_ms() + delay;
            engine->timers[i].callback = JS_DupValue(ctx, argv[0]);
            weizhi_wake(engine);
            return JS_NewInt32(ctx, engine->timers[i].id);
        }
    }
    return JS_ThrowRangeError(ctx, "定时器太多");
}

static JSValue js_clear_timeout(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    int32_t id = 0;
    int i;
    (void)this_val;
    if (argc < 1 || JS_ToInt32(ctx, &id, argv[0])) {
        return JS_UNDEFINED;
    }
    for (i = 0; i < WEIZHI_MAX_TIMERS; i++) {
        if (engine->timers[i].active && engine->timers[i].id == id) {
            engine->timers[i].active = 0;
            JS_FreeValue(ctx, engine->timers[i].callback);
            engine->timers[i].callback = JS_UNDEFINED;
            break;
        }
    }
    return JS_UNDEFINED;
}

static int resolve_fs_path(Engine *engine, const char *relpath, char *out, size_t out_len, const char **error) {
    char folder_real[PATH_MAX];
    char joined[PATH_MAX];
    char file_real[PATH_MAX];
    size_t folder_len;
    if (engine->fs_root == NULL) {
        *error = "没有设置工作区";
        return -1;
    }
    if (!weizhi_path_ok(relpath)) {
        *error = "路径不合法";
        return -1;
    }
    if (realpath(engine->fs_root, folder_real) == NULL) {
        *error = "找不到工作区";
        return -1;
    }
    snprintf(joined, sizeof(joined), "%s/%s", folder_real, relpath);
    if (realpath(joined, file_real) == NULL) {
        /* 写新文件时父目录必须存在 */
        char parent[PATH_MAX];
        char *slash;
        snprintf(parent, sizeof(parent), "%s", joined);
        slash = strrchr(parent, '/');
        if (slash == NULL) {
            *error = "路径不合法";
            return -1;
        }
        *slash = '\0';
        if (realpath(parent, file_real) == NULL) {
            *error = "找不到路径";
            return -1;
        }
        folder_len = strlen(folder_real);
        if (strncmp(file_real, folder_real, folder_len) != 0 ||
            (file_real[folder_len] != '/' && file_real[folder_len] != '\0')) {
            *error = "路径越界";
            return -1;
        }
        snprintf(out, out_len, "%s/%s", file_real, slash + 1);
        return 0;
    }
    folder_len = strlen(folder_real);
    if (strncmp(file_real, folder_real, folder_len) != 0 ||
        (file_real[folder_len] != '/' && file_real[folder_len] != '\0')) {
        *error = "路径越界";
        return -1;
    }
    snprintf(out, out_len, "%s", file_real);
    return 0;
}

static int default_vfs_sync(WeizhiVfsOp op, const char *relpath, const char *relpath2, const WeizhiBytes *in,
                            WeizhiBytes *out, char *errbuf, size_t errbuf_len, void *userdata) {
    Engine *engine = userdata;
    char path[PATH_MAX];
    char path2[PATH_MAX];
    const char *error = NULL;
    memset(out, 0, sizeof(*out));
    if (resolve_fs_path(engine, relpath, path, sizeof(path), &error) != 0) {
        snprintf(errbuf, errbuf_len, "%s", error);
        return -1;
    }
    if (op == WEIZHI_VFS_RENAME) {
        if (resolve_fs_path(engine, relpath2, path2, sizeof(path2), &error) != 0) {
            snprintf(errbuf, errbuf_len, "%s", error);
            return -1;
        }
    }
    switch (op) {
    case WEIZHI_VFS_READ: {
        FILE *file = fopen(path, "rb");
        long size;
        if (file == NULL) {
            snprintf(errbuf, errbuf_len, "找不到文件");
            return -1;
        }
        fseek(file, 0, SEEK_END);
        size = ftell(file);
        if (size < 0 || (size_t)size > engine->limits.fs_io_bytes) {
            fclose(file);
            snprintf(errbuf, errbuf_len, "文件太大");
            return -1;
        }
        rewind(file);
        out->data = malloc((size_t)size);
        if ((size > 0 && out->data == NULL) ||
            (size > 0 && fread(out->data, 1, (size_t)size, file) != (size_t)size)) {
            free(out->data);
            out->data = NULL;
            fclose(file);
            snprintf(errbuf, errbuf_len, "读取失败");
            return -1;
        }
        fclose(file);
        out->len = (size_t)size;
        return 0;
    }
    case WEIZHI_VFS_WRITE:
    case WEIZHI_VFS_APPEND: {
        FILE *file;
        if (in == NULL || (in->len > 0 && in->data == NULL)) {
            snprintf(errbuf, errbuf_len, "没有可写数据");
            return -1;
        }
        if (in->len > engine->limits.fs_io_bytes) {
            snprintf(errbuf, errbuf_len, "文件太大");
            return -1;
        }
        file = fopen(path, op == WEIZHI_VFS_APPEND ? "ab" : "wb");
        if (file == NULL || (in->len > 0 && fwrite(in->data, 1, in->len, file) != in->len)) {
            if (file != NULL) {
                fclose(file);
            }
            snprintf(errbuf, errbuf_len, "写入失败");
            return -1;
        }
        fclose(file);
        return 0;
    }
    case WEIZHI_VFS_EXISTS: {
        out->data = malloc(1);
        if (out->data == NULL) {
            snprintf(errbuf, errbuf_len, "内存不足");
            return -1;
        }
        out->data[0] = access(path, F_OK) == 0 ? 1 : 0;
        out->len = 1;
        return 0;
    }
    case WEIZHI_VFS_UNLINK:
    case WEIZHI_VFS_RM:
        if (unlink(path) != 0) {
            snprintf(errbuf, errbuf_len, "删除失败");
            return -1;
        }
        return 0;
    case WEIZHI_VFS_MKDIR:
        if (mkdir(path, 0755) != 0 && errno != EEXIST) {
            snprintf(errbuf, errbuf_len, "创建目录失败");
            return -1;
        }
        return 0;
    case WEIZHI_VFS_RENAME:
        if (rename(path, path2) != 0) {
            snprintf(errbuf, errbuf_len, "重命名失败");
            return -1;
        }
        return 0;
    case WEIZHI_VFS_STAT:
    case WEIZHI_VFS_READDIR:
        snprintf(errbuf, errbuf_len, "不支持的操作");
        return -1;
    default:
        snprintf(errbuf, errbuf_len, "不支持的操作");
        return -1;
    }
}

static void *async_job_main(void *arg) {
    AsyncJob *job = arg;
    WeizhiBytes out;
    char errbuf[128];
    int rc;
    memset(&out, 0, sizeof(out));
    errbuf[0] = '\0';
    if (job->engine->vfs_sync != NULL) {
        rc = job->engine->vfs_sync(job->op, job->relpath, job->relpath2, &job->in, &out, errbuf, sizeof(errbuf),
                                   job->engine->vfs_ud != NULL ? job->engine->vfs_ud : job->engine);
    } else {
        rc = default_vfs_sync(job->op, job->relpath, job->relpath2, &job->in, &out, errbuf, sizeof(errbuf),
                              job->engine);
    }
    weizhi_complete(job->engine, job->request_id, rc == 0, &out, errbuf);
    weizhi_bytes_free(&out);
    free(job->relpath);
    free(job->relpath2);
    free(job->in.data);
    free(job);
    return NULL;
}

static int default_vfs_async(WeizhiEngine *engine, int64_t request_id, WeizhiVfsOp op, const char *relpath,
                             const char *relpath2, const WeizhiBytes *in, void *userdata) {
    AsyncJob *job = calloc(1, sizeof(*job));
    pthread_t thread;
    (void)userdata;
    if (job == NULL) {
        return -1;
    }
    job->engine = engine;
    job->request_id = request_id;
    job->op = op;
    job->relpath = strdup(relpath != NULL ? relpath : "");
    job->relpath2 = relpath2 != NULL ? strdup(relpath2) : NULL;
    if (in != NULL && in->len > 0 && in->data != NULL) {
        job->in.data = malloc(in->len);
        if (job->in.data == NULL) {
            free(job->relpath);
            free(job->relpath2);
            free(job);
            return -1;
        }
        memcpy(job->in.data, in->data, in->len);
        job->in.len = in->len;
    }
    if (pthread_create(&thread, NULL, async_job_main, job) != 0) {
        free(job->relpath);
        free(job->relpath2);
        free(job->in.data);
        free(job);
        return -1;
    }
    pthread_detach(thread);
    return 0;
}

static JSValue buffer_from_bytes(JSContext *ctx, const WeizhiBytes *bytes) {
    if (bytes == NULL || bytes->data == NULL) {
        return make_buffer(ctx, (const uint8_t *)"", 0);
    }
    return make_buffer(ctx, bytes->data, bytes->len);
}

static int bytes_from_js(JSContext *ctx, JSValueConst val, WeizhiBytes *out, size_t limit) {
    Engine *engine = weizhi_from_ctx(ctx);
    BufferData *buf;
    const char *text;
    size_t len = 0;
    memset(out, 0, sizeof(*out));
    buf = JS_GetOpaque(val, engine->buffer_class_id);
    if (buf != NULL) {
        if (buf->len > limit) {
            return -2;
        }
        out->data = malloc(buf->len ? buf->len : 1);
        if (out->data == NULL) {
            return -1;
        }
        memcpy(out->data, buf->bytes, buf->len);
        out->len = buf->len;
        return 0;
    }
    text = JS_ToCStringLen(ctx, &len, val);
    if (text == NULL) {
        return -1;
    }
    if (len > limit) {
        JS_FreeCString(ctx, text);
        return -2;
    }
    out->data = malloc(len ? len : 1);
    if (out->data == NULL) {
        JS_FreeCString(ctx, text);
        return -1;
    }
    memcpy(out->data, text, len);
    out->len = len;
    JS_FreeCString(ctx, text);
    return 0;
}

static JSValue fs_sync_op(JSContext *ctx, WeizhiVfsOp op, JSValueConst path_val, JSValueConst path2_val,
                          JSValueConst data_val, int has_data) {
    Engine *engine = weizhi_from_ctx(ctx);
    const char *relpath;
    const char *relpath2 = NULL;
    WeizhiBytes in;
    WeizhiBytes out;
    char errbuf[128];
    int rc;
    memset(&in, 0, sizeof(in));
    memset(&out, 0, sizeof(out));
    relpath = JS_ToCString(ctx, path_val);
    if (relpath == NULL) {
        return JS_EXCEPTION;
    }
    if (!JS_IsUndefined(path2_val)) {
        relpath2 = JS_ToCString(ctx, path2_val);
        if (relpath2 == NULL) {
            JS_FreeCString(ctx, relpath);
            return JS_EXCEPTION;
        }
    }
    if (has_data) {
        rc = bytes_from_js(ctx, data_val, &in, engine->limits.fs_io_bytes);
        if (rc == -2) {
            JS_FreeCString(ctx, relpath);
            if (relpath2) {
                JS_FreeCString(ctx, relpath2);
            }
            return JS_ThrowRangeError(ctx, "文件太大");
        }
        if (rc != 0) {
            JS_FreeCString(ctx, relpath);
            if (relpath2) {
                JS_FreeCString(ctx, relpath2);
            }
            return JS_EXCEPTION;
        }
    }
    if (engine->vfs_sync != NULL) {
        rc = engine->vfs_sync(op, relpath, relpath2, &in, &out, errbuf, sizeof(errbuf), engine->vfs_ud);
    } else {
        rc = default_vfs_sync(op, relpath, relpath2, &in, &out, errbuf, sizeof(errbuf), engine);
    }
    JS_FreeCString(ctx, relpath);
    if (relpath2) {
        JS_FreeCString(ctx, relpath2);
    }
    weizhi_bytes_free(&in);
    if (rc != 0) {
        weizhi_bytes_free(&out);
        return JS_ThrowReferenceError(ctx, "%s", errbuf);
    }
    if (op == WEIZHI_VFS_READ) {
        JSValue buf = buffer_from_bytes(ctx, &out);
        weizhi_bytes_free(&out);
        return buf;
    }
    if (op == WEIZHI_VFS_EXISTS) {
        int exists = out.len == 1 && out.data != NULL && out.data[0] != 0;
        weizhi_bytes_free(&out);
        return JS_NewBool(ctx, exists);
    }
    weizhi_bytes_free(&out);
    return JS_UNDEFINED;
}

static JSValue js_read_file_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "需要路径");
    }
    return fs_sync_op(ctx, WEIZHI_VFS_READ, argv[0], JS_UNDEFINED, JS_UNDEFINED, 0);
}

static JSValue js_write_file_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 2) {
        return JS_ThrowTypeError(ctx, "需要路径和数据");
    }
    return fs_sync_op(ctx, WEIZHI_VFS_WRITE, argv[0], JS_UNDEFINED, argv[1], 1);
}

static JSValue js_exists_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "需要路径");
    }
    return fs_sync_op(ctx, WEIZHI_VFS_EXISTS, argv[0], JS_UNDEFINED, JS_UNDEFINED, 0);
}

static JSValue js_unlink_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "需要路径");
    }
    return fs_sync_op(ctx, WEIZHI_VFS_UNLINK, argv[0], JS_UNDEFINED, JS_UNDEFINED, 0);
}

static JSValue fs_async_op(JSContext *ctx, WeizhiVfsOp op, JSValueConst path_val, JSValueConst data_val, int has_data) {
    Engine *engine = weizhi_from_ctx(ctx);
    JSValue funcs[2];
    JSValue promise;
    const char *relpath;
    WeizhiBytes in;
    int64_t id;
    int rc;
    memset(&in, 0, sizeof(in));
    promise = JS_NewPromiseCapability(ctx, funcs);
    if (JS_IsException(promise)) {
        return promise;
    }
    relpath = JS_ToCString(ctx, path_val);
    if (relpath == NULL) {
        JS_FreeValue(ctx, funcs[0]);
        JS_FreeValue(ctx, funcs[1]);
        JS_FreeValue(ctx, promise);
        return JS_EXCEPTION;
    }
    if (has_data) {
        rc = bytes_from_js(ctx, data_val, &in, engine->limits.fs_io_bytes);
        if (rc != 0) {
            JS_FreeCString(ctx, relpath);
            JS_FreeValue(ctx, funcs[0]);
            JS_FreeValue(ctx, funcs[1]);
            JS_FreeValue(ctx, promise);
            if (rc == -2) {
                return JS_ThrowRangeError(ctx, "文件太大");
            }
            return JS_EXCEPTION;
        }
    }
    id = weizhi_pending_add(engine, funcs[0], funcs[1]);
    if (id < 0) {
        JS_FreeCString(ctx, relpath);
        weizhi_bytes_free(&in);
        JS_FreeValue(ctx, promise);
        return JS_ThrowRangeError(ctx, "异步请求太多");
    }
    if (engine->vfs_async != NULL) {
        rc = engine->vfs_async(engine, id, op, relpath, NULL, &in, engine->vfs_ud);
    } else {
        rc = default_vfs_async(engine, id, op, relpath, NULL, &in, engine);
    }
    JS_FreeCString(ctx, relpath);
    weizhi_bytes_free(&in);
    if (rc != 0) {
        weizhi_complete(engine, id, 0, NULL, "无法启动异步读写");
    }
    return promise;
}

static JSValue js_promises_read_file(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "需要路径");
    }
    return fs_async_op(ctx, WEIZHI_VFS_READ, argv[0], JS_UNDEFINED, 0);
}

static JSValue js_promises_write_file(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 2) {
        return JS_ThrowTypeError(ctx, "需要路径和数据");
    }
    return fs_async_op(ctx, WEIZHI_VFS_WRITE, argv[0], argv[1], 1);
}

static JSValue make_fs_module(JSContext *ctx) {
    JSValue mod = JS_NewObject(ctx);
    JSValue promises = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, mod, "readFileSync", JS_NewCFunction(ctx, js_read_file_sync, "readFileSync", 1));
    JS_SetPropertyStr(ctx, mod, "writeFileSync", JS_NewCFunction(ctx, js_write_file_sync, "writeFileSync", 2));
    JS_SetPropertyStr(ctx, mod, "existsSync", JS_NewCFunction(ctx, js_exists_sync, "existsSync", 1));
    JS_SetPropertyStr(ctx, mod, "unlinkSync", JS_NewCFunction(ctx, js_unlink_sync, "unlinkSync", 1));
    JS_SetPropertyStr(ctx, promises, "readFile", JS_NewCFunction(ctx, js_promises_read_file, "readFile", 1));
    JS_SetPropertyStr(ctx, promises, "writeFile", JS_NewCFunction(ctx, js_promises_write_file, "writeFile", 2));
    JS_SetPropertyStr(ctx, mod, "promises", promises);
    JS_SetPropertyStr(ctx, mod, "default", JS_DupValue(ctx, mod));
    return mod;
}

static JSValue js_process_cwd(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    return JS_NewString(ctx, engine->fs_root != NULL ? engine->fs_root : ".");
}

static JSValue make_process_object(JSContext *ctx) {
    JSValue mod = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, mod, "cwd", JS_NewCFunction(ctx, js_process_cwd, "cwd", 0));
#if defined(__APPLE__)
    JS_SetPropertyStr(ctx, mod, "platform", JS_NewString(ctx, "darwin"));
#elif defined(__ANDROID__)
    JS_SetPropertyStr(ctx, mod, "platform", JS_NewString(ctx, "android"));
#elif defined(__linux__)
    JS_SetPropertyStr(ctx, mod, "platform", JS_NewString(ctx, "linux"));
#else
    JS_SetPropertyStr(ctx, mod, "platform", JS_NewString(ctx, "unknown"));
#endif
    JS_SetPropertyStr(ctx, mod, "version", JS_NewString(ctx, "v0.1.0-weizhi"));
    JS_SetPropertyStr(ctx, mod, "env", JS_NewObject(ctx));
    JS_SetPropertyStr(ctx, mod, "default", JS_DupValue(ctx, mod));
    return mod;
}

static JSValue js_console_log(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    char *joined = strdup("");
    int i;
    (void)this_val;
    for (i = 0; i < argc; i++) {
        const char *part;
        JSValue str = JS_ToString(ctx, argv[i]);
        size_t old_len;
        size_t part_len;
        char *next;
        if (JS_IsException(str)) {
            free(joined);
            return str;
        }
        part = JS_ToCString(ctx, str);
        JS_FreeValue(ctx, str);
        if (part == NULL) {
            free(joined);
            return JS_EXCEPTION;
        }
        old_len = strlen(joined);
        part_len = strlen(part);
        next = malloc(old_len + part_len + 2);
        if (next == NULL) {
            JS_FreeCString(ctx, part);
            free(joined);
            return JS_ThrowOutOfMemory(ctx);
        }
        snprintf(next, old_len + part_len + 2, "%s%s%s", joined, i == 0 ? "" : " ", part);
        free(joined);
        joined = next;
        JS_FreeCString(ctx, part);
    }
    {
        char *msg_q = weizhi_quote_json(joined);
        char data[1024];
        if (msg_q != NULL) {
            snprintf(data, sizeof(data), "{\"level\":\"log\",\"message\":%s}", msg_q);
            weizhi_emit_log(engine, "console", data);
            free(msg_q);
        }
    }
    free(joined);
    return JS_UNDEFINED;
}

static JSValue js_require(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *id;
    (void)this_val;
    if (argc < 1) {
        return JS_ThrowTypeError(ctx, "require 需要模块名");
    }
    id = JS_ToCString(ctx, argv[0]);
    if (id == NULL) {
        return JS_EXCEPTION;
    }
    if (strcmp(id, "fs") == 0) {
        JS_FreeCString(ctx, id);
        return make_fs_module(ctx);
    }
    if (strcmp(id, "path") == 0) {
        JS_FreeCString(ctx, id);
        return make_path_module(ctx);
    }
    if (strcmp(id, "buffer") == 0) {
        JS_FreeCString(ctx, id);
        return make_buffer_module(ctx);
    }
    if (strcmp(id, "process") == 0) {
        JS_FreeCString(ctx, id);
        return make_process_object(ctx);
    }
    {
        JSValue err = JS_ThrowReferenceError(ctx, "不支持: %s", id);
        JS_FreeCString(ctx, id);
        return err;
    }
}

static int fs_module_init(JSContext *ctx, JSModuleDef *m) {
    JSValue obj = make_fs_module(ctx);
    JS_SetModuleExport(ctx, m, "default", JS_GetPropertyStr(ctx, obj, "default"));
    JS_SetModuleExport(ctx, m, "readFileSync", JS_GetPropertyStr(ctx, obj, "readFileSync"));
    JS_SetModuleExport(ctx, m, "writeFileSync", JS_GetPropertyStr(ctx, obj, "writeFileSync"));
    JS_SetModuleExport(ctx, m, "existsSync", JS_GetPropertyStr(ctx, obj, "existsSync"));
    JS_SetModuleExport(ctx, m, "unlinkSync", JS_GetPropertyStr(ctx, obj, "unlinkSync"));
    JS_SetModuleExport(ctx, m, "promises", JS_GetPropertyStr(ctx, obj, "promises"));
    JS_FreeValue(ctx, obj);
    return 0;
}

static int path_module_init(JSContext *ctx, JSModuleDef *m) {
    JSValue obj = make_path_module(ctx);
    JS_SetModuleExport(ctx, m, "default", JS_DupValue(ctx, obj));
    JS_SetModuleExport(ctx, m, "join", JS_GetPropertyStr(ctx, obj, "join"));
    JS_SetModuleExport(ctx, m, "basename", JS_GetPropertyStr(ctx, obj, "basename"));
    JS_SetModuleExport(ctx, m, "dirname", JS_GetPropertyStr(ctx, obj, "dirname"));
    JS_SetModuleExport(ctx, m, "extname", JS_GetPropertyStr(ctx, obj, "extname"));
    JS_SetModuleExport(ctx, m, "sep", JS_GetPropertyStr(ctx, obj, "sep"));
    JS_FreeValue(ctx, obj);
    return 0;
}

static int buffer_module_init(JSContext *ctx, JSModuleDef *m) {
    JSValue obj = make_buffer_module(ctx);
    JS_SetModuleExport(ctx, m, "default", JS_GetPropertyStr(ctx, obj, "Buffer"));
    JS_SetModuleExport(ctx, m, "Buffer", JS_GetPropertyStr(ctx, obj, "Buffer"));
    JS_FreeValue(ctx, obj);
    return 0;
}

static JSModuleDef *builtin_module_loader(JSContext *ctx, const char *module_name, void *opaque) {
    JSModuleDef *m;
    (void)opaque;
    if (strcmp(module_name, "fs") == 0) {
        m = JS_NewCModule(ctx, module_name, fs_module_init);
        if (m) {
            JS_AddModuleExport(ctx, m, "default");
            JS_AddModuleExport(ctx, m, "readFileSync");
            JS_AddModuleExport(ctx, m, "writeFileSync");
            JS_AddModuleExport(ctx, m, "existsSync");
            JS_AddModuleExport(ctx, m, "unlinkSync");
            JS_AddModuleExport(ctx, m, "promises");
        }
        return m;
    }
    if (strcmp(module_name, "path") == 0) {
        m = JS_NewCModule(ctx, module_name, path_module_init);
        if (m) {
            JS_AddModuleExport(ctx, m, "default");
            JS_AddModuleExport(ctx, m, "join");
            JS_AddModuleExport(ctx, m, "basename");
            JS_AddModuleExport(ctx, m, "dirname");
            JS_AddModuleExport(ctx, m, "extname");
            JS_AddModuleExport(ctx, m, "sep");
        }
        return m;
    }
    if (strcmp(module_name, "buffer") == 0) {
        m = JS_NewCModule(ctx, module_name, buffer_module_init);
        if (m) {
            JS_AddModuleExport(ctx, m, "default");
            JS_AddModuleExport(ctx, m, "Buffer");
        }
        return m;
    }
    JS_ThrowReferenceError(ctx, "不支持: %s", module_name);
    return NULL;
}

int weizhi_install_node_api(Engine *engine) {
    JSClassDef class_def;
    JSValue global;
    JSValue buffer_mod;
    JSValue console;
    JSValue process;
    JS_NewClassID(&engine->buffer_class_id);
    memset(&class_def, 0, sizeof(class_def));
    class_def.class_name = "Buffer";
    class_def.finalizer = buffer_finalizer;
    JS_NewClass(engine->rt, engine->buffer_class_id, &class_def);
    JS_SetRuntimeOpaque(engine->rt, engine);
    JS_SetModuleLoaderFunc(engine->rt, NULL, builtin_module_loader, engine);

    global = JS_GetGlobalObject(engine->ctx);
    JS_SetPropertyStr(engine->ctx, global, "require", JS_NewCFunction(engine->ctx, js_require, "require", 1));
    JS_SetPropertyStr(engine->ctx, global, "setTimeout",
                      JS_NewCFunction(engine->ctx, js_set_timeout, "setTimeout", 2));
    JS_SetPropertyStr(engine->ctx, global, "clearTimeout",
                      JS_NewCFunction(engine->ctx, js_clear_timeout, "clearTimeout", 1));
    buffer_mod = make_buffer_module(engine->ctx);
    JS_SetPropertyStr(engine->ctx, global, "Buffer", JS_GetPropertyStr(engine->ctx, buffer_mod, "Buffer"));
    JS_FreeValue(engine->ctx, buffer_mod);
    JS_SetPropertyStr(engine->ctx, global, "fs", make_fs_module(engine->ctx));
    JS_SetPropertyStr(engine->ctx, global, "path", make_path_module(engine->ctx));
    console = JS_NewObject(engine->ctx);
    JS_SetPropertyStr(engine->ctx, console, "log", JS_NewCFunction(engine->ctx, js_console_log, "log", 1));
    JS_SetPropertyStr(engine->ctx, console, "warn", JS_NewCFunction(engine->ctx, js_console_log, "warn", 1));
    JS_SetPropertyStr(engine->ctx, console, "error", JS_NewCFunction(engine->ctx, js_console_log, "error", 1));
    JS_SetPropertyStr(engine->ctx, global, "console", console);
    process = make_process_object(engine->ctx);
    JS_SetPropertyStr(engine->ctx, global, "process", process);
    JS_SetPropertyStr(engine->ctx, global, "global", JS_DupValue(engine->ctx, global));
    JS_SetPropertyStr(engine->ctx, global, "globalThis", JS_DupValue(engine->ctx, global));
    JS_FreeValue(engine->ctx, global);

    if (engine->vfs_async == NULL) {
        engine->vfs_async = default_vfs_async;
    }
    if (engine->vfs_sync == NULL) {
        engine->vfs_sync = default_vfs_sync;
        engine->vfs_ud = engine;
    }
    return 0;
}
