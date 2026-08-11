#include "engine_internal.h"
#include "weizhi_plugin.h"

#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>
#include <zlib.h>

/* ArrayBuffer free callback for adopted host bytes. */
static void ab_free(JSRuntime *rt, void *opaque, void *ptr) {
    (void)rt;
    (void)opaque;
    free(ptr);
}

JSValue weizhi_throw_unsupported(JSContext *ctx, const char *what) {
    return JS_ThrowReferenceError(ctx, "unsupported: %s", what ? what : "?");
}

JSValue weizhi_throw_bad_arg(JSContext *ctx, const char *api, const char *detail) {
    return JS_ThrowTypeError(ctx, "bad argument: %s: %s", api ? api : "?", detail ? detail : "");
}

/* Throw "unsupported: ns.xxx" on missing members so agents do not only see undefined is not a function. */
JSValue weizhi_guard_module(JSContext *ctx, JSValue obj, const char *ns) {
    JSValue global;
    JSValue ns_val;
    JSValue wrapper;
    JSValue result;
    const char *code =
        "(function(o, ns){\n"
        "  return new Proxy(o, {\n"
        "    get(t, p, r) {\n"
        "      if (typeof p === 'symbol') return Reflect.get(t, p, r);\n"
        "      if (Object.prototype.hasOwnProperty.call(t, p)) {\n"
        "        var v = t[p];\n"
        "        return typeof v === 'function' ? v.bind(t) : v;\n"
        "      }\n"
        "      throw new ReferenceError('unsupported: ' + ns + '.' + String(p));\n"
        "    }\n"
        "  });\n"
        "})(__wz_guard_obj, __wz_guard_ns)";

    global = JS_GetGlobalObject(ctx);
    ns_val = JS_NewString(ctx, ns != NULL ? ns : "?");
    JS_SetPropertyStr(ctx, global, "__wz_guard_obj", obj);
    JS_SetPropertyStr(ctx, global, "__wz_guard_ns", ns_val);
    wrapper = JS_Eval(ctx, code, strlen(code), "<guard>", JS_EVAL_TYPE_GLOBAL);
    {
        JSAtom a1 = JS_NewAtom(ctx, "__wz_guard_obj");
        JSAtom a2 = JS_NewAtom(ctx, "__wz_guard_ns");
        JS_DeleteProperty(ctx, global, a1, 0);
        JS_DeleteProperty(ctx, global, a2, 0);
        JS_FreeAtom(ctx, a1);
        JS_FreeAtom(ctx, a2);
    }
    JS_FreeValue(ctx, global);
    if (JS_IsException(wrapper)) {
        return wrapper;
    }
    result = wrapper;
    return result;
}

static int fill_os_random(uint8_t *buf, size_t len) {
    int fd = open("/dev/urandom", O_RDONLY);
    size_t got = 0;
    if (fd < 0) {
        return -1;
    }
    while (got < len) {
        ssize_t n = read(fd, buf + got, len - got);
        if (n < 0) {
            if (errno == EINTR) {
                continue;
            }
            close(fd);
            return -1;
        }
        if (n == 0) {
            close(fd);
            return -1;
        }
        got += (size_t)n;
    }
    close(fd);
    return 0;
}

static JSValue bytes_to_uint8(JSContext *ctx, const uint8_t *bytes, size_t len);

static JSValue call_buffer_from(JSContext *ctx, JSValueConst arg) {
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue Buffer = JS_GetPropertyStr(ctx, global, "Buffer");
    JSValue from;
    JSValue out;
    JS_FreeValue(ctx, global);
    if (JS_IsException(Buffer)) {
        return Buffer;
    }
    from = JS_GetPropertyStr(ctx, Buffer, "from");
    if (JS_IsException(from)) {
        JS_FreeValue(ctx, Buffer);
        return from;
    }
    out = JS_Call(ctx, from, Buffer, 1, &arg);
    JS_FreeValue(ctx, from);
    JS_FreeValue(ctx, Buffer);
    return out;
}

JSValue weizhi_bytes_to_buffer(JSContext *ctx, const unsigned char *bytes, size_t len) {
    JSValue u8 = bytes_to_uint8(ctx, bytes, len);
    JSValue out;
    if (JS_IsException(u8)) {
        return u8;
    }
    out = call_buffer_from(ctx, u8);
    JS_FreeValue(ctx, u8);
    return out;
}

int weizhi_js_is_buffer(JSContext *ctx, JSValueConst val) {
    JSValue global;
    JSValue Buffer;
    JSValue isBuffer;
    JSValue ret;
    int ok = 0;
    if (!JS_IsObject(val)) {
        return 0;
    }
    global = JS_GetGlobalObject(ctx);
    Buffer = JS_GetPropertyStr(ctx, global, "Buffer");
    JS_FreeValue(ctx, global);
    if (JS_IsException(Buffer) || !JS_IsObject(Buffer)) {
        JS_FreeValue(ctx, Buffer);
        return 0;
    }
    isBuffer = JS_GetPropertyStr(ctx, Buffer, "isBuffer");
    if (JS_IsException(isBuffer) || !JS_IsFunction(ctx, isBuffer)) {
        JS_FreeValue(ctx, isBuffer);
        JS_FreeValue(ctx, Buffer);
        return 0;
    }
    ret = JS_Call(ctx, isBuffer, Buffer, 1, &val);
    JS_FreeValue(ctx, isBuffer);
    JS_FreeValue(ctx, Buffer);
    if (JS_IsException(ret)) {
        JSValue ex = JS_GetException(ctx);
        JS_FreeValue(ctx, ex);
        return 0;
    }
    ok = JS_ToBool(ctx, ret);
    JS_FreeValue(ctx, ret);
    return ok;
}

int weizhi_js_buffer_data(JSContext *ctx, JSValueConst val, uint8_t **data, size_t *len) {
    size_t off = 0;
    size_t blen = 0;
    size_t bpe = 0;
    size_t ab_len = 0;
    uint8_t *p;
    JSValue buf;
    if (!weizhi_js_is_buffer(ctx, val)) {
        return -1;
    }
    buf = JS_GetTypedArrayBuffer(ctx, val, &off, &blen, &bpe);
    if (JS_IsException(buf)) {
        return -1;
    }
    p = JS_GetArrayBuffer(ctx, &ab_len, buf);
    JS_FreeValue(ctx, buf);
    if (p == NULL || off > ab_len || blen > ab_len - off) {
        return -1;
    }
    if (data) {
        *data = p + off;
    }
    if (len) {
        *len = blen;
    }
    return 0;
}

JSValue weizhi_buffer_adopt(JSContext *ctx, uint8_t *bytes, size_t len) {
    JSValue ab;
    JSValue argv[3];
    JSValue u8;
    JSValue out;
    if (bytes == NULL && len > 0) {
        return JS_ThrowOutOfMemory(ctx);
    }
    if (bytes == NULL) {
        bytes = malloc(1);
        if (bytes == NULL) {
            return JS_ThrowOutOfMemory(ctx);
        }
        len = 0;
    }
    ab = JS_NewArrayBuffer(ctx, bytes, len, ab_free, NULL, 0);
    if (JS_IsException(ab)) {
        free(bytes);
        return ab;
    }
    argv[0] = ab;
    argv[1] = JS_NewInt32(ctx, 0);
    argv[2] = JS_NewUint32(ctx, (uint32_t)len);
    u8 = JS_NewTypedArray(ctx, 3, argv, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, argv[2]);
    JS_FreeValue(ctx, argv[1]);
    JS_FreeValue(ctx, ab);
    if (JS_IsException(u8)) {
        return u8;
    }
    out = call_buffer_from(ctx, u8);
    JS_FreeValue(ctx, u8);
    return out;
}

static JSValue make_buffer_module(JSContext *ctx) {
    JSValue mod = JS_NewObject(ctx);
    JSValue global = JS_GetGlobalObject(ctx);
    JSValue buffer = JS_GetPropertyStr(ctx, global, "Buffer");
    JS_FreeValue(ctx, global);
    JS_SetPropertyStr(ctx, mod, "Buffer", JS_DupValue(ctx, buffer));
    JS_SetPropertyStr(ctx, mod, "default", JS_DupValue(ctx, buffer));
    JS_FreeValue(ctx, buffer);
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
                return JS_ThrowRangeError(ctx, "path too long");
            }
            out[used++] = '/';
            out[used] = '\0';
        }
        if (used + len >= sizeof(out)) {
            JS_FreeCString(ctx, part);
            return JS_ThrowRangeError(ctx, "path too long");
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
    JSValue guarded;
    JS_SetPropertyStr(ctx, mod, "join", JS_NewCFunction(ctx, js_path_join, "join", 2));
    JS_SetPropertyStr(ctx, mod, "basename", JS_NewCFunction(ctx, js_path_basename, "basename", 1));
    JS_SetPropertyStr(ctx, mod, "dirname", JS_NewCFunction(ctx, js_path_dirname, "dirname", 1));
    JS_SetPropertyStr(ctx, mod, "extname", JS_NewCFunction(ctx, js_path_extname, "extname", 1));
    JS_SetPropertyStr(ctx, mod, "sep", JS_NewString(ctx, "/"));
    guarded = weizhi_guard_module(ctx, mod, "path");
    if (JS_IsException(guarded)) {
        return guarded;
    }
    JS_SetPropertyStr(ctx, guarded, "default", JS_DupValue(ctx, guarded));
    return guarded;
}

static JSValue js_set_timeout(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    int32_t delay = 0;
    int i;
    (void)this_val;
    if (argc < 1 || !JS_IsFunction(ctx, argv[0])) {
        return weizhi_throw_bad_arg(ctx, "setTimeout", "first argument must be a function");
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
    return JS_ThrowRangeError(ctx, "too many timers");
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
        *error = "workspace not set";
        return -1;
    }
    if (!weizhi_path_ok(relpath)) {
        *error = "invalid path";
        return -1;
    }
    if (realpath(engine->fs_root, folder_real) == NULL) {
        *error = "workspace not found";
        return -1;
    }
    snprintf(joined, sizeof(joined), "%s/%s", folder_real, relpath);
    if (realpath(joined, file_real) == NULL) {
        /* Parent directory must exist when creating a new file */
        char parent[PATH_MAX];
        char *slash;
        snprintf(parent, sizeof(parent), "%s", joined);
        slash = strrchr(parent, '/');
        if (slash == NULL) {
            *error = "invalid path";
            return -1;
        }
        *slash = '\0';
        if (realpath(parent, file_real) == NULL) {
            *error = "path not found";
            return -1;
        }
        folder_len = strlen(folder_real);
        if (strncmp(file_real, folder_real, folder_len) != 0 ||
            (file_real[folder_len] != '/' && file_real[folder_len] != '\0')) {
            *error = "path escape";
            return -1;
        }
        snprintf(out, out_len, "%s/%s", file_real, slash + 1);
        return 0;
    }
    folder_len = strlen(folder_real);
    if (strncmp(file_real, folder_real, folder_len) != 0 ||
        (file_real[folder_len] != '/' && file_real[folder_len] != '\0')) {
        *error = "path escape";
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
            snprintf(errbuf, errbuf_len, "file not found");
            return -1;
        }
        fseek(file, 0, SEEK_END);
        size = ftell(file);
        if (size < 0 || (size_t)size > engine->limits.fs_io_bytes) {
            fclose(file);
            snprintf(errbuf, errbuf_len, "file too large");
            return -1;
        }
        rewind(file);
        out->data = malloc((size_t)size);
        if ((size > 0 && out->data == NULL) ||
            (size > 0 && fread(out->data, 1, (size_t)size, file) != (size_t)size)) {
            free(out->data);
            out->data = NULL;
            fclose(file);
            snprintf(errbuf, errbuf_len, "read failed");
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
            snprintf(errbuf, errbuf_len, "no writable data");
            return -1;
        }
        if (in->len > engine->limits.fs_io_bytes) {
            snprintf(errbuf, errbuf_len, "file too large");
            return -1;
        }
        file = fopen(path, op == WEIZHI_VFS_APPEND ? "ab" : "wb");
        if (file == NULL || (in->len > 0 && fwrite(in->data, 1, in->len, file) != in->len)) {
            if (file != NULL) {
                fclose(file);
            }
            snprintf(errbuf, errbuf_len, "write failed");
            return -1;
        }
        fclose(file);
        return 0;
    }
    case WEIZHI_VFS_EXISTS: {
        out->data = malloc(1);
        if (out->data == NULL) {
            snprintf(errbuf, errbuf_len, "out of memory");
            return -1;
        }
        out->data[0] = access(path, F_OK) == 0 ? 1 : 0;
        out->len = 1;
        return 0;
    }
    case WEIZHI_VFS_UNLINK:
    case WEIZHI_VFS_RM:
        if (unlink(path) != 0) {
            snprintf(errbuf, errbuf_len, "delete failed");
            return -1;
        }
        return 0;
    case WEIZHI_VFS_MKDIR:
        if (mkdir(path, 0755) != 0 && errno != EEXIST) {
            snprintf(errbuf, errbuf_len, "mkdir failed");
            return -1;
        }
        return 0;
    case WEIZHI_VFS_RENAME:
        if (rename(path, path2) != 0) {
            snprintf(errbuf, errbuf_len, "rename failed");
            return -1;
        }
        return 0;
    case WEIZHI_VFS_STAT:
    case WEIZHI_VFS_READDIR:
        snprintf(errbuf, errbuf_len, "unsupported operation");
        return -1;
    default:
        snprintf(errbuf, errbuf_len, "unsupported operation");
        return -1;
    }
}

static void async_job_free(WeizhiAsyncJob *job) {
    if (job == NULL) {
        return;
    }
    free(job->relpath);
    free(job->relpath2);
    free(job->in.data);
    free(job);
}

static void async_job_run(WeizhiAsyncJob *job) {
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
    async_job_free(job);
}

static void *async_worker_main(void *arg) {
    Engine *engine = arg;
    for (;;) {
        WeizhiAsyncJob *job;
        pthread_mutex_lock(&engine->async_mu);
        while (engine->async_queue_head == NULL && !engine->async_stop) {
            pthread_cond_wait(&engine->async_cv, &engine->async_mu);
        }
        if (engine->async_stop && engine->async_queue_head == NULL) {
            pthread_mutex_unlock(&engine->async_mu);
            return NULL;
        }
        job = engine->async_queue_head;
        engine->async_queue_head = job->next;
        if (engine->async_queue_head == NULL) {
            engine->async_queue_tail = NULL;
        }
        job->next = NULL;
        pthread_mutex_unlock(&engine->async_mu);
        async_job_run(job);
    }
}

static int async_pool_ensure_started(Engine *engine) {
    int n;
    int i;
    if (engine->async_pool_started) {
        return 0;
    }
    n = engine->limits.max_async_io;
    if (n < 1) {
        n = WEIZHI_DEFAULT_MAX_ASYNC_IO;
    }
    if (n > WEIZHI_MAX_PENDING) {
        n = WEIZHI_MAX_PENDING;
    }
    engine->async_workers = calloc((size_t)n, sizeof(pthread_t));
    if (engine->async_workers == NULL) {
        return -1;
    }
    engine->async_worker_count = n;
    engine->async_stop = 0;
    for (i = 0; i < n; i++) {
        if (pthread_create(&engine->async_workers[i], NULL, async_worker_main, engine) != 0) {
            int j;
            pthread_mutex_lock(&engine->async_mu);
            engine->async_stop = 1;
            pthread_cond_broadcast(&engine->async_cv);
            pthread_mutex_unlock(&engine->async_mu);
            for (j = 0; j < i; j++) {
                pthread_join(engine->async_workers[j], NULL);
            }
            free(engine->async_workers);
            engine->async_workers = NULL;
            engine->async_worker_count = 0;
            engine->async_stop = 0;
            return -1;
        }
    }
    engine->async_pool_started = 1;
    return 0;
}

void weizhi_async_pool_shutdown(Engine *engine) {
    WeizhiAsyncJob *job;
    int i;
    if (engine == NULL) {
        return;
    }
    pthread_mutex_lock(&engine->async_mu);
    engine->async_stop = 1;
    pthread_cond_broadcast(&engine->async_cv);
    pthread_mutex_unlock(&engine->async_mu);
    if (engine->async_workers != NULL) {
        for (i = 0; i < engine->async_worker_count; i++) {
            pthread_join(engine->async_workers[i], NULL);
        }
        free(engine->async_workers);
        engine->async_workers = NULL;
        engine->async_worker_count = 0;
    }
    pthread_mutex_lock(&engine->async_mu);
    job = engine->async_queue_head;
    engine->async_queue_head = NULL;
    engine->async_queue_tail = NULL;
    pthread_mutex_unlock(&engine->async_mu);
    while (job != NULL) {
        WeizhiAsyncJob *next = job->next;
        weizhi_complete(engine, job->request_id, 0, NULL, "engine closed");
        async_job_free(job);
        job = next;
    }
    engine->async_pool_started = 0;
    engine->async_stop = 0;
}

static int default_vfs_async(WeizhiEngine *engine, int64_t request_id, WeizhiVfsOp op, const char *relpath,
                             const char *relpath2, const WeizhiBytes *in, void *userdata) {
    WeizhiAsyncJob *job = calloc(1, sizeof(*job));
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
            async_job_free(job);
            return -1;
        }
        memcpy(job->in.data, in->data, in->len);
        job->in.len = in->len;
    }
    if (job->relpath == NULL || (relpath2 != NULL && job->relpath2 == NULL)) {
        async_job_free(job);
        return -1;
    }
    if (async_pool_ensure_started(engine) != 0) {
        async_job_free(job);
        return -1;
    }
    pthread_mutex_lock(&engine->async_mu);
    if (engine->async_stop) {
        pthread_mutex_unlock(&engine->async_mu);
        async_job_free(job);
        return -1;
    }
    if (engine->async_queue_tail != NULL) {
        engine->async_queue_tail->next = job;
    } else {
        engine->async_queue_head = job;
    }
    engine->async_queue_tail = job;
    pthread_cond_signal(&engine->async_cv);
    pthread_mutex_unlock(&engine->async_mu);
    return 0;
}

static JSValue buffer_from_bytes(JSContext *ctx, const WeizhiBytes *bytes) {
    if (bytes == NULL || bytes->data == NULL) {
        return weizhi_bytes_to_buffer(ctx, (const unsigned char *)"", 0);
    }
    return weizhi_bytes_to_buffer(ctx, bytes->data, bytes->len);
}

static int bytes_from_js(JSContext *ctx, JSValueConst val, WeizhiBytes *out, size_t limit) {
    const uint8_t *data = NULL;
    size_t len = 0;
    const char *text;
    memset(out, 0, sizeof(*out));
    if (weizhi_js_is_buffer(ctx, val) || JS_IsObject(val)) {
        size_t off = 0;
        size_t blen = 0;
        size_t bpe = 0;
        size_t ab_len = 0;
        uint8_t *p;
        JSValue buf = JS_GetTypedArrayBuffer(ctx, val, &off, &blen, &bpe);
        if (!JS_IsException(buf)) {
            p = JS_GetArrayBuffer(ctx, &ab_len, buf);
            JS_FreeValue(ctx, buf);
            if (p != NULL && off <= ab_len && blen <= ab_len - off) {
                data = p + off;
                len = blen;
                if (len > limit) {
                    return -2;
                }
                out->data = malloc(len ? len : 1);
                if (out->data == NULL) {
                    return -1;
                }
                if (len > 0) {
                    memcpy(out->data, data, len);
                }
                out->len = len;
                return 0;
            }
        } else {
            JSValue ex = JS_GetException(ctx);
            JS_FreeValue(ctx, ex);
        }
        if (weizhi_js_is_buffer(ctx, val)) {
            return -1;
        }
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
            return JS_ThrowRangeError(ctx, "file too large");
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
        rc = engine->vfs_sync(op, relpath, relpath2, &in, &out, errbuf, sizeof(errbuf),
                              engine->vfs_ud != NULL ? engine->vfs_ud : engine);
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
        return weizhi_throw_bad_arg(ctx, "fs.readFileSync", "path required");
    }
    return fs_sync_op(ctx, WEIZHI_VFS_READ, argv[0], JS_UNDEFINED, JS_UNDEFINED, 0);
}

static JSValue js_write_file_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 2) {
        return weizhi_throw_bad_arg(ctx, "fs.writeFileSync", "path and data required");
    }
    return fs_sync_op(ctx, WEIZHI_VFS_WRITE, argv[0], JS_UNDEFINED, argv[1], 1);
}

static JSValue js_exists_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "fs.existsSync", "path required");
    }
    return fs_sync_op(ctx, WEIZHI_VFS_EXISTS, argv[0], JS_UNDEFINED, JS_UNDEFINED, 0);
}

static JSValue js_unlink_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "fs.unlinkSync", "path required");
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
                return JS_ThrowRangeError(ctx, "file too large");
            }
            return JS_EXCEPTION;
        }
    }
    id = weizhi_pending_add(engine, funcs[0], funcs[1]);
    if (id < 0) {
        JS_FreeCString(ctx, relpath);
        weizhi_bytes_free(&in);
        JS_FreeValue(ctx, promise);
        return JS_ThrowRangeError(ctx, "too many async requests");
    }
    if (engine->vfs_async != NULL) {
        void *ud = engine->vfs_async_ud != NULL ? engine->vfs_async_ud : engine->vfs_ud;
        rc = engine->vfs_async(engine, id, op, relpath, NULL, &in, ud);
    } else {
        rc = default_vfs_async(engine, id, op, relpath, NULL, &in, engine);
    }
    JS_FreeCString(ctx, relpath);
    weizhi_bytes_free(&in);
    if (rc != 0) {
        weizhi_complete(engine, id, 0, NULL, "failed to start async I/O");
    }
    return promise;
}

static JSValue js_promises_read_file(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "fs.promises.readFile", "path required");
    }
    return fs_async_op(ctx, WEIZHI_VFS_READ, argv[0], JS_UNDEFINED, 0);
}

static JSValue js_promises_write_file(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 2) {
        return weizhi_throw_bad_arg(ctx, "fs.promises.writeFile", "path and data required");
    }
    return fs_async_op(ctx, WEIZHI_VFS_WRITE, argv[0], argv[1], 1);
}

static JSValue make_fs_module(JSContext *ctx) {
    JSValue mod = JS_NewObject(ctx);
    JSValue promises = JS_NewObject(ctx);
    JSValue guarded_promises;
    JSValue guarded;
    JS_SetPropertyStr(ctx, mod, "readFileSync", JS_NewCFunction(ctx, js_read_file_sync, "readFileSync", 1));
    JS_SetPropertyStr(ctx, mod, "writeFileSync", JS_NewCFunction(ctx, js_write_file_sync, "writeFileSync", 2));
    JS_SetPropertyStr(ctx, mod, "existsSync", JS_NewCFunction(ctx, js_exists_sync, "existsSync", 1));
    JS_SetPropertyStr(ctx, mod, "unlinkSync", JS_NewCFunction(ctx, js_unlink_sync, "unlinkSync", 1));
    JS_SetPropertyStr(ctx, promises, "readFile", JS_NewCFunction(ctx, js_promises_read_file, "readFile", 1));
    JS_SetPropertyStr(ctx, promises, "writeFile", JS_NewCFunction(ctx, js_promises_write_file, "writeFile", 2));
    guarded_promises = weizhi_guard_module(ctx, promises, "fs.promises");
    if (JS_IsException(guarded_promises)) {
        JS_FreeValue(ctx, mod);
        return guarded_promises;
    }
    JS_SetPropertyStr(ctx, mod, "promises", guarded_promises);
    guarded = weizhi_guard_module(ctx, mod, "fs");
    if (JS_IsException(guarded)) {
        return guarded;
    }
    JS_SetPropertyStr(ctx, guarded, "default", JS_DupValue(ctx, guarded));
    return guarded;
}

static JSValue make_caps_object(JSContext *ctx, Engine *engine) {
    JSValue caps = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, caps, "hostAbi", JS_NewInt32(ctx, WEIZHI_HOST_ABI_VERSION));
    JS_SetPropertyStr(ctx, caps, "vfs", JS_TRUE);
    JS_SetPropertyStr(ctx, caps, "http", JS_NewBool(ctx, engine != NULL && engine->http_async != NULL));
    JS_SetPropertyStr(ctx, caps, "native", JS_NewBool(ctx, engine != NULL && engine->native_ensure != NULL));
    JS_SetPropertyStr(ctx, caps, "compress", JS_TRUE);
    JS_SetPropertyStr(ctx, caps, "random", JS_TRUE);
    return caps;
}

void weizhi_refresh_caps(Engine *engine) {
    JSValue global;
    JSValue process;
    if (engine == NULL || engine->ctx == NULL) {
        return;
    }
    global = JS_GetGlobalObject(engine->ctx);
    process = JS_GetPropertyStr(engine->ctx, global, "process");
    if (!JS_IsException(process) && JS_IsObject(process)) {
        JS_SetPropertyStr(engine->ctx, process, "weizhiCaps", make_caps_object(engine->ctx, engine));
    }
    JS_FreeValue(engine->ctx, process);
    JS_FreeValue(engine->ctx, global);
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
    JS_SetPropertyStr(ctx, mod, "weizhiCaps", make_caps_object(ctx, weizhi_from_ctx(ctx)));
    JS_SetPropertyStr(ctx, mod, "default", JS_DupValue(ctx, mod));
    return weizhi_guard_module(ctx, mod, "process");
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

static int zlib_convert(const uint8_t *in, size_t in_len, int window_bits, int decompress, size_t limit,
                         uint8_t **out_bytes, size_t *out_len) {
    z_stream strm;
    uint8_t *buf;
    size_t cap;
    size_t produced = 0;
    int ret;
    if (in_len > limit || in_len > 0xffffffffu) {
        return -2;
    }
    memset(&strm, 0, sizeof(strm));
    if (decompress) {
        if (inflateInit2(&strm, window_bits) != Z_OK) {
            return -1;
        }
    } else if (deflateInit2(&strm, Z_DEFAULT_COMPRESSION, Z_DEFLATED, window_bits, 8, Z_DEFAULT_STRATEGY) != Z_OK) {
        return -1;
    }
    cap = in_len + 64;
    if (cap < 128) {
        cap = 128;
    }
    if (cap > limit) {
        cap = limit;
    }
    buf = malloc(cap ? cap : 1);
    if (buf == NULL) {
        if (decompress) {
            inflateEnd(&strm);
        } else {
            deflateEnd(&strm);
        }
        return -1;
    }
    strm.next_in = (Bytef *)in;
    strm.avail_in = (uInt)in_len;
    for (;;) {
        size_t used;
        if (produced >= cap) {
            size_t next = cap * 2;
            uint8_t *grown;
            if (next > limit) {
                next = limit;
            }
            if (next <= cap) {
                free(buf);
                if (decompress) {
                    inflateEnd(&strm);
                } else {
                    deflateEnd(&strm);
                }
                return -2;
            }
            grown = realloc(buf, next);
            if (grown == NULL) {
                free(buf);
                if (decompress) {
                    inflateEnd(&strm);
                } else {
                    deflateEnd(&strm);
                }
                return -1;
            }
            buf = grown;
            cap = next;
        }
        strm.next_out = buf + produced;
        strm.avail_out = (uInt)(cap - produced);
        ret = decompress ? inflate(&strm, Z_NO_FLUSH) : deflate(&strm, Z_FINISH);
        used = (cap - produced) - strm.avail_out;
        produced += used;
        if (ret == Z_STREAM_END) {
            break;
        }
        if ((ret == Z_OK || ret == Z_BUF_ERROR) && used == 0 && strm.avail_out > 0) {
            free(buf);
            if (decompress) {
                inflateEnd(&strm);
            } else {
                deflateEnd(&strm);
            }
            return -1;
        }
        if (ret == Z_OK || ret == Z_BUF_ERROR) {
            continue;
        }
        free(buf);
        if (decompress) {
            inflateEnd(&strm);
        } else {
            deflateEnd(&strm);
        }
        return -1;
    }
    if (decompress) {
        inflateEnd(&strm);
    } else {
        deflateEnd(&strm);
    }
    *out_bytes = buf;
    *out_len = produced;
    return 0;
}

static JSValue js_zlib_convert(JSContext *ctx, JSValueConst input, int window_bits, int decompress, const char *name) {
    Engine *engine = weizhi_from_ctx(ctx);
    uint8_t *in = NULL;
    size_t in_len = 0;
    uint8_t *out = NULL;
    size_t out_len = 0;
    int rc;
    if (engine == NULL) {
        return JS_ThrowInternalError(ctx, "no engine");
    }
    if (!weizhi_js_is_buffer(ctx, input) || weizhi_js_buffer_data(ctx, input, &in, &in_len) != 0) {
        return weizhi_throw_bad_arg(ctx, name, "expected Buffer");
    }
    rc = zlib_convert(in, in_len, window_bits, decompress, engine->limits.fs_io_bytes, &out, &out_len);
    if (rc == -2) {
        return JS_ThrowRangeError(ctx, "too large: zlib");
    }
    if (rc != 0) {
        return weizhi_throw_bad_arg(ctx, name, "zlib failed");
    }
    return weizhi_buffer_adopt(ctx, out, out_len);
}

static JSValue js_gzip_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "zlib.gzipSync", "expected Buffer");
    }
    return js_zlib_convert(ctx, argv[0], 15 + 16, 0, "zlib.gzipSync");
}

static JSValue js_gunzip_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "zlib.gunzipSync", "expected Buffer");
    }
    return js_zlib_convert(ctx, argv[0], 15 + 16, 1, "zlib.gunzipSync");
}

static JSValue js_deflate_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "zlib.deflateSync", "expected Buffer");
    }
    return js_zlib_convert(ctx, argv[0], 15, 0, "zlib.deflateSync");
}

static JSValue js_inflate_sync(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "zlib.inflateSync", "expected Buffer");
    }
    return js_zlib_convert(ctx, argv[0], 15, 1, "zlib.inflateSync");
}

static JSValue make_zlib_module(JSContext *ctx) {
    JSValue mod = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, mod, "gzipSync", JS_NewCFunction(ctx, js_gzip_sync, "gzipSync", 1));
    JS_SetPropertyStr(ctx, mod, "gunzipSync", JS_NewCFunction(ctx, js_gunzip_sync, "gunzipSync", 1));
    JS_SetPropertyStr(ctx, mod, "deflateSync", JS_NewCFunction(ctx, js_deflate_sync, "deflateSync", 1));
    JS_SetPropertyStr(ctx, mod, "inflateSync", JS_NewCFunction(ctx, js_inflate_sync, "inflateSync", 1));
    return weizhi_guard_module(ctx, mod, "zlib");
}

static JSValue js_require(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const char *id;
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "require", "module name required");
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
    if (strcmp(id, "zlib") == 0) {
        JS_FreeCString(ctx, id);
        return make_zlib_module(ctx);
    }
    {
        char msg[192];
        snprintf(msg, sizeof(msg),
                 "module \"%s\" (available: buffer, fs, path, process, zlib, or ./file.js under script folder)", id);
        JS_FreeCString(ctx, id);
        return weizhi_throw_unsupported(ctx, msg);
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

static JSModuleDef *load_script_js_module(JSContext *ctx, Engine *engine, const char *module_name) {
    uint8_t *bytes = NULL;
    size_t length = 0;
    char errbuf[128];
    JSValue func_val;
    JSModuleDef *m;
    if (weizhi_read_script_leaf(engine, module_name, &bytes, &length, errbuf, sizeof(errbuf)) != 0) {
        JS_ThrowReferenceError(ctx, "could not load module \"%s\": %s", module_name, errbuf);
        return NULL;
    }
    func_val = JS_Eval(ctx, (const char *)bytes, length, module_name,
                       JS_EVAL_TYPE_MODULE | JS_EVAL_FLAG_COMPILE_ONLY);
    free(bytes);
    if (JS_IsException(func_val)) {
        return NULL;
    }
    m = JS_VALUE_GET_PTR(func_val);
    JS_FreeValue(ctx, func_val);
    return m;
}

static int is_script_js_module_name(const char *module_name) {
    size_t i;
    size_t len;
    if (module_name == NULL || module_name[0] == '\0') {
        return 0;
    }
    len = strlen(module_name);
    if (len < 4 || len > 64 || strcmp(module_name + len - 3, ".js") != 0) {
        return 0;
    }
    for (i = 0; i < len; i++) {
        unsigned char c = (unsigned char)module_name[i];
        if (!(isalnum(c) || c == '.' || c == '_' || c == '-')) {
            return 0;
        }
    }
    return 1;
}

static JSModuleDef *builtin_module_loader(JSContext *ctx, const char *module_name, void *opaque) {
    Engine *engine = opaque;
    JSModuleDef *m;
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
    if (is_script_js_module_name(module_name)) {
        return load_script_js_module(ctx, engine, module_name);
    }
    JS_ThrowReferenceError(ctx, "unsupported: module \"%s\" (available: buffer, fs, path, process, zlib, or ./file.js under script folder)",
                           module_name);
    return NULL;
}

/* Response body held on the response object via __body Buffer. */
static JSValue js_response_text(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSValue body_val;
    uint8_t *bytes = NULL;
    size_t len = 0;
    JSValue promise;
    JSValue funcs[2];
    JSValue text;
    (void)argc;
    (void)argv;
    body_val = JS_GetPropertyStr(ctx, this_val, "__body");
    if (JS_IsException(body_val)) {
        return body_val;
    }
    if (weizhi_js_buffer_data(ctx, body_val, &bytes, &len) != 0) {
        JS_FreeValue(ctx, body_val);
        return weizhi_throw_bad_arg(ctx, "Response.text", "missing body");
    }
    text = JS_NewStringLen(ctx, (const char *)(bytes != NULL ? bytes : (uint8_t *)""), len);
    JS_FreeValue(ctx, body_val);
    promise = JS_NewPromiseCapability(ctx, funcs);
    if (JS_IsException(promise)) {
        JS_FreeValue(ctx, text);
        return promise;
    }
    {
        JSValue ret = JS_Call(ctx, funcs[0], JS_UNDEFINED, 1, &text);
        JS_FreeValue(ctx, ret);
    }
    JS_FreeValue(ctx, text);
    JS_FreeValue(ctx, funcs[0]);
    JS_FreeValue(ctx, funcs[1]);
    return promise;
}

static JSValue js_response_array_buffer(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSValue body_val;
    JSValue funcs[2];
    JSValue promise;
    JSValue ret;
    (void)argc;
    (void)argv;
    body_val = JS_GetPropertyStr(ctx, this_val, "__body");
    if (JS_IsException(body_val)) {
        return body_val;
    }
    promise = JS_NewPromiseCapability(ctx, funcs);
    if (JS_IsException(promise)) {
        JS_FreeValue(ctx, body_val);
        return promise;
    }
    ret = JS_Call(ctx, funcs[0], JS_UNDEFINED, 1, &body_val);
    JS_FreeValue(ctx, ret);
    JS_FreeValue(ctx, body_val);
    JS_FreeValue(ctx, funcs[0]);
    JS_FreeValue(ctx, funcs[1]);
    return promise;
}

static JSValue js_response_json(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSValue text_promise;
    JSValue then_fn;
    JSValue parser;
    JSValue result;
    (void)argc;
    (void)argv;
    text_promise = js_response_text(ctx, this_val, 0, NULL);
    if (JS_IsException(text_promise)) {
        return text_promise;
    }
    parser = JS_Eval(ctx,
                     "(t)=>JSON.parse(t)",
                     strlen("(t)=>JSON.parse(t)"),
                     "<fetch-json>",
                     JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(parser)) {
        JS_FreeValue(ctx, text_promise);
        return parser;
    }
    then_fn = JS_GetPropertyStr(ctx, text_promise, "then");
    result = JS_Call(ctx, then_fn, text_promise, 1, &parser);
    JS_FreeValue(ctx, then_fn);
    JS_FreeValue(ctx, parser);
    JS_FreeValue(ctx, text_promise);
    return result;
}

JSValue weizhi_make_fetch_response(JSContext *ctx, int status, const char *headers_json, const WeizhiBytes *body) {
    static const char WRAP[] =
        "(function(h){\n"
        "  var lower = {};\n"
        "  for (var k in h) {\n"
        "    if (Object.prototype.hasOwnProperty.call(h, k))\n"
        "      lower[String(k).toLowerCase()] = h[k];\n"
        "  }\n"
        "  return { get: function(n) {\n"
        "    var v = lower[String(n).toLowerCase()];\n"
        "    return v === undefined ? null : v;\n"
        "  } };\n"
        "})";
    JSValue obj = JS_NewObject(ctx);
    JSValue headers_obj;
    JSValue headers_wrap;
    JSValue wrap_fn;
    JSValue body_buf;
    int ok = status >= 200 && status < 300;
    if (JS_IsException(obj)) {
        return obj;
    }
    JS_SetPropertyStr(ctx, obj, "status", JS_NewInt32(ctx, status));
    JS_SetPropertyStr(ctx, obj, "ok", JS_NewBool(ctx, ok));
    JS_SetPropertyStr(ctx, obj, "statusText", JS_NewString(ctx, ok ? "OK" : "Error"));
    if (headers_json != NULL && headers_json[0] != '\0') {
        headers_obj = JS_ParseJSON(ctx, headers_json, strlen(headers_json), "<fetch-headers>");
        if (JS_IsException(headers_obj)) {
            JS_FreeValue(ctx, headers_obj);
            headers_obj = JS_NewObject(ctx);
        }
    } else {
        headers_obj = JS_NewObject(ctx);
    }
    wrap_fn = JS_Eval(ctx, WRAP, strlen(WRAP), "<fetch-headers-wrap>", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(wrap_fn)) {
        headers_wrap = headers_obj;
    } else {
        headers_wrap = JS_Call(ctx, wrap_fn, JS_UNDEFINED, 1, &headers_obj);
        JS_FreeValue(ctx, wrap_fn);
        JS_FreeValue(ctx, headers_obj);
        if (JS_IsException(headers_wrap)) {
            headers_wrap = JS_NewObject(ctx);
        }
    }
    JS_SetPropertyStr(ctx, obj, "headers", headers_wrap);
    body_buf = weizhi_bytes_to_buffer(ctx, body != NULL ? body->data : NULL, body != NULL ? body->len : 0);
    JS_SetPropertyStr(ctx, obj, "__body", body_buf);
    JS_SetPropertyStr(ctx, obj, "text", JS_NewCFunction(ctx, js_response_text, "text", 0));
    JS_SetPropertyStr(ctx, obj, "json", JS_NewCFunction(ctx, js_response_json, "json", 0));
    JS_SetPropertyStr(ctx, obj, "arrayBuffer", JS_NewCFunction(ctx, js_response_array_buffer, "arrayBuffer", 0));
    return obj;
}

static char *headers_object_to_json(JSContext *ctx, JSValueConst headers_val) {
    JSValue json;
    const char *cstr;
    char *copy;
    if (JS_IsUndefined(headers_val) || JS_IsNull(headers_val)) {
        return strdup("{}");
    }
    if (!JS_IsObject(headers_val)) {
        return NULL;
    }
    json = JS_JSONStringify(ctx, headers_val, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(json)) {
        return NULL;
    }
    cstr = JS_ToCString(ctx, json);
    JS_FreeValue(ctx, json);
    if (cstr == NULL) {
        return NULL;
    }
    copy = strdup(cstr);
    JS_FreeCString(ctx, cstr);
    return copy;
}

static JSValue js_fetch(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    JSValue funcs[2];
    JSValue promise;
    JSValue init = JS_UNDEFINED;
    const char *url = NULL;
    const char *method = "GET";
    char *method_owned = NULL;
    char *headers_json = NULL;
    WeizhiBytes body;
    int64_t id;
    int rc;
    (void)this_val;
    memset(&body, 0, sizeof(body));

    if (engine->http_async == NULL) {
        return weizhi_throw_unsupported(
            ctx,
            "fetch (network is disabled until the host installs HTTP; use local fs, or ask the host "
            "to call weizhi_set_http / enableFetch)");
    }
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "fetch", "url string required (example: await fetch(\"https://...\"))");
    }
    if (JS_IsString(argv[0])) {
        url = JS_ToCString(ctx, argv[0]);
    } else if (JS_IsObject(argv[0])) {
        JSValue href = JS_GetPropertyStr(ctx, argv[0], "href");
        if (JS_IsException(href)) {
            return href;
        }
        if (JS_IsString(href)) {
            url = JS_ToCString(ctx, href);
            JS_FreeValue(ctx, href);
        } else {
            JS_FreeValue(ctx, href);
            url = JS_ToCString(ctx, argv[0]);
        }
    } else {
        return weizhi_throw_bad_arg(ctx, "fetch", "url string required (example: await fetch(\"https://...\"))");
    }
    if (url == NULL) {
        return JS_EXCEPTION;
    }
    if (strncmp(url, "http://", 7) != 0 && strncmp(url, "https://", 8) != 0) {
        char msg[256];
        snprintf(msg, sizeof(msg),
                 "bad argument: fetch: url must start with http:// or https:// (got a non-HTTP scheme; "
                 "for local files use fs.readFileSync instead)");
        JS_FreeCString(ctx, url);
        return JS_ThrowTypeError(ctx, "%s", msg);
    }
    if (argc >= 2 && JS_IsObject(argv[1])) {
        JSValue method_val;
        JSValue headers_val;
        JSValue body_val;
        init = argv[1];
        method_val = JS_GetPropertyStr(ctx, init, "method");
        if (JS_IsString(method_val)) {
            const char *m = JS_ToCString(ctx, method_val);
            if (m != NULL) {
                method_owned = strdup(m);
                JS_FreeCString(ctx, m);
                if (method_owned != NULL) {
                    method = method_owned;
                }
            }
        }
        JS_FreeValue(ctx, method_val);
        headers_val = JS_GetPropertyStr(ctx, init, "headers");
        headers_json = headers_object_to_json(ctx, headers_val);
        JS_FreeValue(ctx, headers_val);
        if (headers_json == NULL) {
            headers_json = strdup("{}");
        }
        body_val = JS_GetPropertyStr(ctx, init, "body");
        if (!JS_IsUndefined(body_val) && !JS_IsNull(body_val)) {
            rc = bytes_from_js(ctx, body_val, &body, engine->limits.fs_io_bytes);
            if (rc == -2) {
                JS_FreeValue(ctx, body_val);
                JS_FreeCString(ctx, url);
                free(method_owned);
                free(headers_json);
                return JS_ThrowRangeError(
                    ctx,
                    "too large: fetch request body exceeds the limit (raise WeizhiLimits.fsIoBytes or send less data)");
            }
            if (rc != 0) {
                JS_FreeValue(ctx, body_val);
                JS_FreeCString(ctx, url);
                free(method_owned);
                free(headers_json);
                return weizhi_throw_bad_arg(ctx, "fetch",
                                            "body must be a string, Buffer, Uint8Array, Blob, FormData, or URLSearchParams");
            }
        }
        JS_FreeValue(ctx, body_val);
    } else {
        headers_json = strdup("{}");
    }

    promise = JS_NewPromiseCapability(ctx, funcs);
    if (JS_IsException(promise)) {
        JS_FreeCString(ctx, url);
        free(method_owned);
        free(headers_json);
        weizhi_bytes_free(&body);
        return promise;
    }
    id = weizhi_pending_add(engine, funcs[0], funcs[1]);
    if (id < 0) {
        JS_FreeValue(ctx, funcs[0]);
        JS_FreeValue(ctx, funcs[1]);
        JS_FreeValue(ctx, promise);
        JS_FreeCString(ctx, url);
        free(method_owned);
        free(headers_json);
        weizhi_bytes_free(&body);
        return JS_ThrowRangeError(ctx, "too many async requests");
    }
    rc = engine->http_async(engine, id, method, url, headers_json, &body, engine->http_ud);
    JS_FreeCString(ctx, url);
    free(method_owned);
    free(headers_json);
    weizhi_bytes_free(&body);
    if (rc != 0) {
        weizhi_complete_fetch(engine, id, 0, NULL, NULL,
                             "fetch failed to start (host HTTP callback returned an error; check network permissions)");
    }
    return promise;
}

static JSValue js_native_export_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                                     int magic, JSValue *func_data) {
    Engine *engine = weizhi_from_ctx(ctx);
    const char *plugin_name;
    const char *export_name;
    JSValue arg;
    JSValue json;
    const char *args_text;
    char *returned;
    JSValue parsed;
    (void)this_val;
    (void)magic;
    if (engine == NULL || engine->native_call == NULL) {
        return weizhi_throw_unsupported(ctx, "native call (host native not installed)");
    }
    plugin_name = JS_ToCString(ctx, func_data[0]);
    export_name = JS_ToCString(ctx, func_data[1]);
    if (plugin_name == NULL || export_name == NULL) {
        if (plugin_name != NULL) {
            JS_FreeCString(ctx, plugin_name);
        }
        if (export_name != NULL) {
            JS_FreeCString(ctx, export_name);
        }
        return JS_EXCEPTION;
    }
    arg = argc > 0 ? argv[0] : JS_UNDEFINED;
    if (JS_IsUndefined(arg) || JS_IsNull(arg)) {
        args_text = "[]";
        json = JS_UNDEFINED;
    } else {
        json = JS_JSONStringify(ctx, arg, JS_UNDEFINED, JS_UNDEFINED);
        if (JS_IsException(json)) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return JS_EXCEPTION;
        }
        args_text = JS_ToCString(ctx, json);
        if (args_text == NULL) {
            JS_FreeValue(ctx, json);
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return JS_EXCEPTION;
        }
    }
    returned = engine->native_call(plugin_name, export_name, args_text, engine->native_ud);
    if (!JS_IsUndefined(json)) {
        JS_FreeCString(ctx, args_text);
        JS_FreeValue(ctx, json);
    }
    JS_FreeCString(ctx, plugin_name);
    JS_FreeCString(ctx, export_name);
    if (returned == NULL) {
        return JS_NULL;
    }
    parsed = JS_ParseJSON(ctx, returned, strlen(returned), "<native>");
    if (JS_IsException(parsed)) {
        JSValue exc = JS_GetException(ctx);
        JS_FreeValue(ctx, exc);
        parsed = JS_NewString(ctx, returned);
    }
    free(returned);
    return parsed;
}

typedef int32_t (*weizhi_fn_ii_i)(int32_t, int32_t);
typedef WeizhiBuf (*weizhi_fn_b_b)(WeizhiBuf);
typedef int32_t (*weizhi_fn_i_cb_i)(int32_t, uint32_t);

static WeizhiPluginExport *find_export(WeizhiLoadedPlugin *plugin, const char *name) {
    int i;
    for (i = 0; i < plugin->nexports; i++) {
        if (strcmp(plugin->exports[i].name, name) == 0) {
            return &plugin->exports[i];
        }
    }
    return NULL;
}

static JSValue js_typed_export_call(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv,
                                    int magic, JSValue *func_data) {
    Engine *engine = weizhi_from_ctx(ctx);
    const char *plugin_name;
    const char *export_name;
    WeizhiLoadedPlugin *plugin;
    WeizhiPluginExport *ex;
    JSValue result = JS_UNDEFINED;
    int cbd;
    (void)this_val;
    (void)magic;
    if (engine == NULL) {
        return JS_ThrowInternalError(ctx, "no engine");
    }
    plugin_name = JS_ToCString(ctx, func_data[0]);
    export_name = JS_ToCString(ctx, func_data[1]);
    if (plugin_name == NULL || export_name == NULL) {
        if (plugin_name) {
            JS_FreeCString(ctx, plugin_name);
        }
        if (export_name) {
            JS_FreeCString(ctx, export_name);
        }
        return JS_EXCEPTION;
    }
    plugin = weizhi_find_plugin(engine, plugin_name);
    if (plugin == NULL) {
        JS_FreeCString(ctx, plugin_name);
        JS_FreeCString(ctx, export_name);
        return weizhi_throw_unsupported(ctx, "native (plugin not loaded)");
    }
    ex = find_export(plugin, export_name);
    if (ex == NULL || ex->fn == NULL) {
        JS_FreeCString(ctx, plugin_name);
        JS_FreeCString(ctx, export_name);
        return weizhi_throw_unsupported(ctx, "native export missing");
    }
    /* Fixed stubs for first-wave signatures (IDL-driven; see NATIVE_PLUGIN_IDL.md). */
    if (ex->nargs == 2 && ex->args[0] == WEIZHI_TY_I32 && ex->args[1] == WEIZHI_TY_I32 &&
        ex->ret == WEIZHI_TY_I32) {
        int32_t a = 0;
        int32_t b = 0;
        int32_t r;
        if (argc < 2) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return weizhi_throw_bad_arg(ctx, export_name, "expected (i32, i32)");
        }
        if (JS_ToInt32(ctx, &a, argv[0]) || JS_ToInt32(ctx, &b, argv[1])) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return JS_EXCEPTION;
        }
        r = ((weizhi_fn_ii_i)ex->fn)(a, b);
        result = JS_NewInt32(ctx, r);
    } else if (ex->nargs == 1 && ex->args[0] == WEIZHI_TY_BYTES && ex->ret == WEIZHI_TY_BYTES) {
        WeizhiBuf in;
        WeizhiBuf out;
        uint8_t *data = NULL;
        size_t len = 0;
        memset(&in, 0, sizeof(in));
        if (argc < 1 || !weizhi_js_is_buffer(ctx, argv[0])) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return weizhi_throw_bad_arg(ctx, export_name, "expected (Buffer)");
        }
        if (weizhi_js_buffer_data(ctx, argv[0], &data, &len) != 0) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return weizhi_throw_bad_arg(ctx, export_name, "invalid Buffer");
        }
        in.data = data;
        in.len = len;
        out = ((weizhi_fn_b_b)ex->fn)(in);
        result = weizhi_buffer_adopt(ctx, out.data, out.len);
    } else if (ex->nargs == 4 && ex->args[0] == WEIZHI_TY_BYTES && ex->args[1] == WEIZHI_TY_I32 &&
               ex->args[2] == WEIZHI_TY_I32 && ex->args[3] == WEIZHI_TY_I32 && ex->ret == WEIZHI_TY_BYTES) {
        WeizhiBuf in;
        WeizhiBuf out;
        uint8_t *data = NULL;
        size_t len = 0;
        int32_t width = 0;
        int32_t height = 0;
        int32_t max_edge = 0;
        typedef WeizhiBuf (*weizhi_fn_biii_b)(WeizhiBuf, int32_t, int32_t, int32_t);
        memset(&in, 0, sizeof(in));
        if (argc < 4 || !weizhi_js_is_buffer(ctx, argv[0])) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return weizhi_throw_bad_arg(ctx, export_name, "expected (Buffer, i32, i32, i32)");
        }
        if (weizhi_js_buffer_data(ctx, argv[0], &data, &len) != 0 || JS_ToInt32(ctx, &width, argv[1]) ||
            JS_ToInt32(ctx, &height, argv[2]) || JS_ToInt32(ctx, &max_edge, argv[3])) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return weizhi_throw_bad_arg(ctx, export_name, "expected (Buffer, i32, i32, i32)");
        }
        in.data = data;
        in.len = len;
        out = ((weizhi_fn_biii_b)ex->fn)(in, width, height, max_edge);
        result = weizhi_buffer_adopt(ctx, out.data, out.len);
    } else if (ex->nargs == 2 && ex->args[0] == WEIZHI_TY_I32 && ex->args[1] == WEIZHI_TY_CB &&
               ex->ret == WEIZHI_TY_I32) {
        int32_t n = 0;
        uint32_t cb_id;
        int32_t r;
        if (argc < 2 || !JS_IsFunction(ctx, argv[1])) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return weizhi_throw_bad_arg(ctx, export_name, "expected (i32, function)");
        }
        if (JS_ToInt32(ctx, &n, argv[0])) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return JS_EXCEPTION;
        }
        cb_id = weizhi_cb_register(engine, argv[1]);
        if (cb_id == 0) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return JS_ThrowRangeError(ctx, "too many native callbacks");
        }
        r = ((weizhi_fn_i_cb_i)ex->fn)(n, cb_id);
        /* Same-turn flush (D3 default). */
        cbd = weizhi_drain_cb_queue(engine);
        if (cbd < 0) {
            JS_FreeCString(ctx, plugin_name);
            JS_FreeCString(ctx, export_name);
            return JS_EXCEPTION;
        }
        result = JS_NewInt32(ctx, r);
    } else {
        JS_FreeCString(ctx, plugin_name);
        JS_FreeCString(ctx, export_name);
        return weizhi_throw_unsupported(ctx, "native signature not implemented in this build");
    }
    JS_FreeCString(ctx, plugin_name);
    JS_FreeCString(ctx, export_name);
    return result;
}

JSValue weizhi_make_native_plugin(JSContext *ctx, const char *plugin_json) {
    JSValue info;
    JSValue name_val;
    JSValue exports_val;
    JSValue abi_val;
    JSValue obj;
    const char *name;
    const char *abi = NULL;
    uint32_t i;
    uint32_t len;
    int typed = 0;

    if (plugin_json == NULL) {
        return JS_ThrowInternalError(ctx, "native ensure failed: empty plugin json");
    }
    info = JS_ParseJSON(ctx, plugin_json, strlen(plugin_json), "<native-plugin>");
    if (JS_IsException(info)) {
        return info;
    }
    name_val = JS_GetPropertyStr(ctx, info, "name");
    exports_val = JS_GetPropertyStr(ctx, info, "exports");
    abi_val = JS_GetPropertyStr(ctx, info, "abi");
    name = JS_ToCString(ctx, name_val);
    if (!JS_IsUndefined(abi_val) && JS_IsString(abi_val)) {
        abi = JS_ToCString(ctx, abi_val);
        if (abi != NULL && strcmp(abi, "weizhi_plugin_v1") == 0) {
            typed = 1;
        }
    }
    if (name == NULL || !JS_IsArray(ctx, exports_val)) {
        if (name != NULL) {
            JS_FreeCString(ctx, name);
        }
        if (abi != NULL) {
            JS_FreeCString(ctx, abi);
        }
        JS_FreeValue(ctx, name_val);
        JS_FreeValue(ctx, exports_val);
        JS_FreeValue(ctx, abi_val);
        JS_FreeValue(ctx, info);
        return JS_ThrowTypeError(ctx, "native plugin json must include name and exports[]");
    }
    obj = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, obj, "name", JS_DupValue(ctx, name_val));
    {
        JSValue ver = JS_GetPropertyStr(ctx, info, "version");
        if (!JS_IsUndefined(ver) && !JS_IsException(ver)) {
            JS_SetPropertyStr(ctx, obj, "version", ver);
        } else {
            JS_FreeValue(ctx, ver);
        }
    }
    {
        JSValue len_val = JS_GetPropertyStr(ctx, exports_val, "length");
        int32_t len32 = 0;
        if (JS_ToInt32(ctx, &len32, len_val) != 0) {
            JS_FreeValue(ctx, len_val);
            JS_FreeCString(ctx, name);
            if (abi) {
                JS_FreeCString(ctx, abi);
            }
            JS_FreeValue(ctx, name_val);
            JS_FreeValue(ctx, exports_val);
            JS_FreeValue(ctx, abi_val);
            JS_FreeValue(ctx, info);
            JS_FreeValue(ctx, obj);
            return JS_EXCEPTION;
        }
        JS_FreeValue(ctx, len_val);
        if (len32 < 0) {
            len32 = 0;
        }
        len = (uint32_t)len32;
    }
    for (i = 0; i < len; i++) {
        JSValue exp = JS_GetPropertyUint32(ctx, exports_val, i);
        const char *exp_name = NULL;
        JSValue data[2];
        JSValue fn;
        JSValue exp_name_val;
        if (JS_IsException(exp)) {
            JS_FreeCString(ctx, name);
            if (abi) {
                JS_FreeCString(ctx, abi);
            }
            JS_FreeValue(ctx, name_val);
            JS_FreeValue(ctx, exports_val);
            JS_FreeValue(ctx, abi_val);
            JS_FreeValue(ctx, info);
            JS_FreeValue(ctx, obj);
            return exp;
        }
        if (typed && JS_IsObject(exp)) {
            exp_name_val = JS_GetPropertyStr(ctx, exp, "name");
            exp_name = JS_ToCString(ctx, exp_name_val);
            JS_FreeValue(ctx, exp_name_val);
        } else {
            exp_name = JS_ToCString(ctx, exp);
        }
        JS_FreeValue(ctx, exp);
        if (exp_name == NULL) {
            JS_FreeCString(ctx, name);
            if (abi) {
                JS_FreeCString(ctx, abi);
            }
            JS_FreeValue(ctx, name_val);
            JS_FreeValue(ctx, exports_val);
            JS_FreeValue(ctx, abi_val);
            JS_FreeValue(ctx, info);
            JS_FreeValue(ctx, obj);
            return JS_EXCEPTION;
        }
        data[0] = JS_NewString(ctx, name);
        data[1] = JS_NewString(ctx, exp_name);
        if (typed) {
            fn = JS_NewCFunctionData(ctx, js_typed_export_call, 8, 0, 2, data);
        } else {
            fn = JS_NewCFunctionData(ctx, js_native_export_call, 1, 0, 2, data);
        }
        JS_FreeValue(ctx, data[0]);
        JS_FreeValue(ctx, data[1]);
        JS_DefinePropertyValueStr(ctx, obj, exp_name, fn, JS_PROP_C_W_E);
        JS_FreeCString(ctx, exp_name);
    }
    JS_FreeCString(ctx, name);
    if (abi) {
        JS_FreeCString(ctx, abi);
    }
    JS_FreeValue(ctx, name_val);
    JS_FreeValue(ctx, exports_val);
    JS_FreeValue(ctx, abi_val);
    JS_FreeValue(ctx, info);
    return obj;
}

static JSValue js_host_ensure_native(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    Engine *engine = weizhi_from_ctx(ctx);
    const char *name = NULL;
    JSValue funcs[2];
    JSValue promise;
    int64_t id;
    int rc;
    (void)this_val;
    if (engine == NULL || engine->native_ensure == NULL) {
        return weizhi_throw_unsupported(
            ctx,
            "native (host.ensureNative needs host NATIVE; ask the host to enableNativeMock / weizhi_set_native)");
    }
    if (argc < 1 || !JS_IsString(argv[0])) {
        return weizhi_throw_bad_arg(ctx, "host.ensureNative", "plugin name string required");
    }
    name = JS_ToCString(ctx, argv[0]);
    if (name == NULL) {
        return JS_EXCEPTION;
    }
    if (name[0] == '\0' || strchr(name, '/') != NULL || strstr(name, "..") != NULL) {
        JS_FreeCString(ctx, name);
        return weizhi_throw_bad_arg(ctx, "host.ensureNative", "invalid plugin name");
    }
    promise = JS_NewPromiseCapability(ctx, funcs);
    if (JS_IsException(promise)) {
        JS_FreeCString(ctx, name);
        return promise;
    }
    id = weizhi_pending_add(engine, funcs[0], funcs[1]);
    if (id < 0) {
        JS_FreeCString(ctx, name);
        JS_FreeValue(ctx, promise);
        return JS_ThrowRangeError(ctx, "too many pending async operations");
    }
    rc = engine->native_ensure(engine, id, name, engine->native_ud);
    JS_FreeCString(ctx, name);
    if (rc != 0) {
        weizhi_complete_native(engine, id, 0, NULL,
                               "native ensure failed to start (host callback returned an error)");
    }
    return promise;
}

static JSValue make_host_object(JSContext *ctx) {
    JSValue host = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, host, "ensureNative",
                      JS_NewCFunction(ctx, js_host_ensure_native, "ensureNative", 1));
    JS_SetPropertyStr(ctx, host, "abiVersion", JS_NewInt32(ctx, WEIZHI_HOST_ABI_VERSION));
    return weizhi_guard_module(ctx, host, "host");
}

static JSValue bytes_to_uint8(JSContext *ctx, const uint8_t *bytes, size_t len) {
    JSValue ab = JS_NewArrayBufferCopy(ctx, bytes, len);
    JSValue argv[3];
    JSValue out;
    if (JS_IsException(ab)) {
        return ab;
    }
    argv[0] = ab;
    argv[1] = JS_NewInt32(ctx, 0);
    argv[2] = JS_NewUint32(ctx, (uint32_t)len);
    out = JS_NewTypedArray(ctx, 3, argv, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, argv[2]);
    JS_FreeValue(ctx, argv[1]);
    JS_FreeValue(ctx, ab);
    return out;
}

static int read_byte_source(JSContext *ctx, JSValueConst val, const uint8_t **data, size_t *len) {
    uint8_t *owned = NULL;
    size_t owned_len = 0;
    if (weizhi_js_is_buffer(ctx, val)) {
        if (weizhi_js_buffer_data(ctx, val, &owned, &owned_len) != 0) {
            return -1;
        }
        *data = owned;
        *len = owned_len;
        return 0;
    }
    {
        size_t off = 0;
        size_t blen = 0;
        size_t bpe = 0;
        JSValue buf = JS_GetTypedArrayBuffer(ctx, val, &off, &blen, &bpe);
        size_t ab_len = 0;
        uint8_t *p;
        if (JS_IsException(buf)) {
            return -1;
        }
        p = JS_GetArrayBuffer(ctx, &ab_len, buf);
        JS_FreeValue(ctx, buf);
        if (p == NULL || off > ab_len || blen > ab_len - off) {
            return -1;
        }
        *data = p + off;
        *len = blen;
        return 0;
    }
}

static JSValue js_text_encode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    size_t len = 0;
    const char *text;
    JSValue out;
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "TextEncoder.encode", "string required");
    }
    text = JS_ToCStringLen(ctx, &len, argv[0]);
    if (text == NULL) {
        return JS_EXCEPTION;
    }
    out = bytes_to_uint8(ctx, (const uint8_t *)text, len);
    JS_FreeCString(ctx, text);
    return out;
}

static JSValue js_text_decode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const uint8_t *data = NULL;
    size_t len = 0;
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "TextDecoder.decode", "Uint8Array or Buffer required");
    }
    if (read_byte_source(ctx, argv[0], &data, &len) != 0) {
        JSValue ex = JS_GetException(ctx);
        JS_FreeValue(ctx, ex);
        return weizhi_throw_bad_arg(ctx, "TextDecoder.decode", "Uint8Array or Buffer required");
    }
    if (data == NULL) {
        data = (const uint8_t *)"";
    }
    return JS_NewStringLen(ctx, (const char *)data, len);
}

static JSValue js_text_encoder_ctor(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSValue obj = JS_NewObject(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (JS_IsException(obj)) {
        return obj;
    }
    JS_SetPropertyStr(ctx, obj, "encoding", JS_NewString(ctx, "utf-8"));
    JS_SetPropertyStr(ctx, obj, "encode", JS_NewCFunction(ctx, js_text_encode, "encode", 1));
    return obj;
}

static JSValue js_text_decoder_ctor(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JSValue obj;
    (void)this_val;
    if (argc >= 1 && !JS_IsUndefined(argv[0]) && !JS_IsNull(argv[0])) {
        const char *cs = JS_ToCString(ctx, argv[0]);
        int ok;
        if (cs == NULL) {
            return JS_EXCEPTION;
        }
        ok = strcmp(cs, "utf-8") == 0 || strcmp(cs, "utf8") == 0 || strcmp(cs, "UTF-8") == 0;
        JS_FreeCString(ctx, cs);
        if (!ok) {
            return weizhi_throw_unsupported(ctx, "TextDecoder charset (utf-8 only)");
        }
    }
    obj = JS_NewObject(ctx);
    if (JS_IsException(obj)) {
        return obj;
    }
    JS_SetPropertyStr(ctx, obj, "encoding", JS_NewString(ctx, "utf-8"));
    JS_SetPropertyStr(ctx, obj, "decode", JS_NewCFunction(ctx, js_text_decode, "decode", 1));
    return obj;
}

static JSValue js_crypto_get_random_values(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    const uint8_t *data = NULL;
    size_t len = 0;
    uint8_t *writable;
    (void)this_val;
    if (argc < 1) {
        return weizhi_throw_bad_arg(ctx, "crypto.getRandomValues", "expected Uint8Array");
    }
    if (read_byte_source(ctx, argv[0], &data, &len) != 0 || !JS_IsObject(argv[0])) {
        JSValue ex = JS_GetException(ctx);
        JS_FreeValue(ctx, ex);
        return weizhi_throw_bad_arg(ctx, "crypto.getRandomValues", "expected Uint8Array");
    }
    if (len > 65536) {
        return JS_ThrowRangeError(ctx, "too large: crypto.getRandomValues");
    }
    writable = (uint8_t *)data;
    if (fill_os_random(writable, len) != 0) {
        return JS_ThrowInternalError(ctx, "crypto.getRandomValues: entropy unavailable");
    }
    return JS_DupValue(ctx, argv[0]);
}

static JSValue js_crypto_random_uuid(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    uint8_t b[16];
    char out[37];
    static const char *hexd = "0123456789abcdef";
    int i;
    int o = 0;
    (void)this_val;
    (void)argc;
    (void)argv;
    if (fill_os_random(b, sizeof(b)) != 0) {
        return JS_ThrowInternalError(ctx, "crypto.randomUUID: entropy unavailable");
    }
    b[6] = (uint8_t)((b[6] & 0x0f) | 0x40);
    b[8] = (uint8_t)((b[8] & 0x3f) | 0x80);
    for (i = 0; i < 16; i++) {
        if (i == 4 || i == 6 || i == 8 || i == 10) {
            out[o++] = '-';
        }
        out[o++] = hexd[b[i] >> 4];
        out[o++] = hexd[b[i] & 0xf];
    }
    out[o] = '\0';
    return JS_NewString(ctx, out);
}

static JSValue make_crypto_object(JSContext *ctx) {
    JSValue crypto = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, crypto, "getRandomValues",
                      JS_NewCFunction(ctx, js_crypto_get_random_values, "getRandomValues", 1));
    JS_SetPropertyStr(ctx, crypto, "randomUUID", JS_NewCFunction(ctx, js_crypto_random_uuid, "randomUUID", 0));
    return crypto;
}

/* URL / URLSearchParams / atob / btoa match the agent prelude so scripts transfer. */
static const char WEIZHI_AGENT_PRELUDE[] =
    "var __B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';\n"
    "globalThis.btoa = function (s) {\n"
    "  s = String(s);\n"
    "  var out = '';\n"
    "  for (var i = 0; i < s.length; i += 3) {\n"
    "    var b1 = s.charCodeAt(i), b2 = s.charCodeAt(i + 1), b3 = s.charCodeAt(i + 2);\n"
    "    if (b1 > 255 || b2 > 255 || b3 > 255) throw new TypeError('bad argument: btoa: latin1 only');\n"
    "    out += __B64.charAt(b1 >> 2);\n"
    "    out += __B64.charAt(((b1 & 3) << 4) | (isNaN(b2) ? 0 : b2 >> 4));\n"
    "    out += isNaN(b2) ? '=' : __B64.charAt(((b2 & 15) << 2) | (isNaN(b3) ? 0 : b3 >> 6));\n"
    "    out += isNaN(b3) ? '=' : __B64.charAt(b3 & 63);\n"
    "  }\n"
    "  return out;\n"
    "};\n"
    "globalThis.atob = function (s) {\n"
    "  s = String(s).replace(/=+$/, '');\n"
    "  var out = '';\n"
    "  for (var i = 0; i < s.length; i += 4) {\n"
    "    var n = (__B64.indexOf(s.charAt(i)) << 18) | (__B64.indexOf(s.charAt(i + 1)) << 12) |\n"
    "      ((s.charAt(i + 2) ? __B64.indexOf(s.charAt(i + 2)) : 0) << 6) |\n"
    "      (s.charAt(i + 3) ? __B64.indexOf(s.charAt(i + 3)) : 0);\n"
    "    out += String.fromCharCode((n >> 16) & 255);\n"
    "    if (s.charAt(i + 2)) out += String.fromCharCode((n >> 8) & 255);\n"
    "    if (s.charAt(i + 3)) out += String.fromCharCode(n & 255);\n"
    "  }\n"
    "  return out;\n"
    "};\n"
    "globalThis.URLSearchParams = function (init) {\n"
    "  this.__p = [];\n"
    "  var self = this;\n"
    "  function dec(s) { return decodeURIComponent(String(s).replace(/\\+/g, ' ')); }\n"
    "  function enc(s) { return encodeURIComponent(String(s)); }\n"
    "  if (typeof init === 'string') {\n"
    "    String(init).replace(/^\\?/, '').split('&').forEach(function (kv) {\n"
    "      if (!kv) return;\n"
    "      var i = kv.indexOf('=');\n"
    "      if (i < 0) self.__p.push([dec(kv), '']);\n"
    "      else self.__p.push([dec(kv.slice(0, i)), dec(kv.slice(i + 1))]);\n"
    "    });\n"
    "  } else if (init && typeof init === 'object') {\n"
    "    Object.keys(init).forEach(function (k) {\n"
    "      var v = init[k];\n"
    "      (Array.isArray(v) ? v : [v]).forEach(function (x) { self.__p.push([k, String(x)]); });\n"
    "    });\n"
    "  }\n"
    "  this.append = function (k, v) { this.__p.push([String(k), String(v)]); };\n"
    "  this.set = function (k, v) { this.delete(k); this.append(k, v); };\n"
    "  this.get = function (k) {\n"
    "    for (var i = 0; i < this.__p.length; i++) if (this.__p[i][0] === String(k)) return this.__p[i][1];\n"
    "    return null;\n"
    "  };\n"
    "  this.getAll = function (k) {\n"
    "    var out = [];\n"
    "    for (var i = 0; i < this.__p.length; i++) if (this.__p[i][0] === String(k)) out.push(this.__p[i][1]);\n"
    "    return out;\n"
    "  };\n"
    "  this.has = function (k) { return this.get(k) !== null; };\n"
    "  this.delete = function (k) { this.__p = this.__p.filter(function (e) { return e[0] !== String(k); }); };\n"
    "  this.forEach = function (cb) { this.__p.forEach(function (e) { cb(e[1], e[0]); }); };\n"
    "  this.toString = function () {\n"
    "    return this.__p.map(function (e) { return enc(e[0]) + '=' + enc(e[1]); }).join('&');\n"
    "  };\n"
    "};\n"
    "globalThis.URL = function (url, base) {\n"
    "  var s = String(url);\n"
    "  if (base !== undefined && s && !/^[a-zA-Z][a-zA-Z0-9+.\\-]*:/.test(s)) {\n"
    "    var b = String(base);\n"
    "    if (b.charAt(b.length - 1) === '/') b = b.slice(0, -1);\n"
    "    s = b + '/' + s.replace(/^\\.\\//, '');\n"
    "  }\n"
    "  var m = /^([a-zA-Z][a-zA-Z0-9+.\\-]*:)?(\\/\\/[^\\/?#]*)?([^?#]*)?(\\?[^#]*)?(#.*)?/.exec(s) || [];\n"
    "  this.protocol = m[1] || '';\n"
    "  var authority = (m[2] || '').replace(/^\\/\\//, '');\n"
    "  this.host = authority.split('@').pop() || '';\n"
    "  var ci = this.host.indexOf(':');\n"
    "  this.hostname = ci >= 0 ? this.host.slice(0, ci) : this.host;\n"
    "  this.port = ci >= 0 ? this.host.slice(ci + 1) : '';\n"
    "  this.pathname = m[3] || (this.host ? '/' : '');\n"
    "  this.search = m[4] || '';\n"
    "  this.hash = m[5] || '';\n"
    "  this.origin = this.protocol + '//' + this.host;\n"
    "  this.href = s;\n"
    "  this.searchParams = new URLSearchParams(this.search.charAt(0) === '?' ? this.search.slice(1) : this.search);\n"
    "  var self = this;\n"
    "  this.toString = function () { return self.href; };\n"
    "};\n"
    "globalThis.__b64ToBytes = function (s) {\n"
    "  var bin = atob(s);\n"
    "  var a = new Uint8Array(bin.length);\n"
    "  for (var i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i);\n"
    "  return a;\n"
    "};\n"
    "globalThis.__bytesToB64 = function (a) {\n"
    "  var s = '';\n"
    "  for (var i = 0; i < a.length; i++) s += String.fromCharCode(a[i]);\n"
    "  return btoa(s);\n"
    "};\n"
    "function __bufferBytes(data, enc) {\n"
    "  if (typeof data === 'string') {\n"
    "    if (enc === 'base64') return __b64ToBytes(data);\n"
    "    if (enc === 'hex') {\n"
    "      if ((data.length % 2) !== 0) throw new TypeError('bad argument: Buffer.from: invalid hex');\n"
    "      var h = new Uint8Array(data.length / 2);\n"
    "      for (var i = 0; i < data.length; i += 2) {\n"
    "        var n = parseInt(data.substr(i, 2), 16);\n"
    "        if (isNaN(n)) throw new TypeError('bad argument: Buffer.from: invalid hex');\n"
    "        h[i / 2] = n;\n"
    "      }\n"
    "      return h;\n"
    "    }\n"
    "    if (enc && enc !== 'utf8' && enc !== 'utf-8')\n"
    "      throw new ReferenceError('unsupported: Buffer encoding (utf8/hex/base64)');\n"
    "    return new TextEncoder().encode(data);\n"
    "  }\n"
    "  if (data instanceof ArrayBuffer) return new Uint8Array(data);\n"
    "  if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);\n"
    "  if (data && typeof data.length === 'number') {\n"
    "    var a = new Uint8Array(data.length);\n"
    "    for (var k = 0; k < data.length; k++) a[k] = data[k] & 255;\n"
    "    return a;\n"
    "  }\n"
    "  throw new TypeError('bad argument: Buffer.from: string, array, or typed array required');\n"
    "}\n"
    "class __BufferClass extends Uint8Array {\n"
    "  constructor(arg, arg2, arg3) {\n"
    "    if (arg instanceof ArrayBuffer) super(arg, arg2 || 0, arg3);\n"
    "    else if (typeof arg === 'number') super(arg);\n"
    "    else super(__bufferBytes(arg, arg2));\n"
    "  }\n"
    "  toString(encoding) {\n"
    "    if (encoding === 'hex') {\n"
    "      var h = '';\n"
    "      for (var i = 0; i < this.length; i++)\n"
    "        h += (this[i] < 16 ? '0' : '') + this[i].toString(16);\n"
    "      return h;\n"
    "    }\n"
    "    if (encoding === 'base64') return __bytesToB64(this);\n"
    "    if (encoding && encoding !== 'utf8' && encoding !== 'utf-8')\n"
    "      throw new ReferenceError('unsupported: Buffer encoding (utf8/hex/base64)');\n"
    "    return new TextDecoder().decode(this);\n"
    "  }\n"
    "}\n"
    "function Buffer(data, encoding) { return new __BufferClass(data, encoding); }\n"
    "Buffer.prototype = __BufferClass.prototype;\n"
    "Buffer.from = function (data, encoding) {\n"
    "  if (arguments.length < 1) throw new TypeError('bad argument: Buffer.from: argument required');\n"
    "  if (typeof data === 'number') throw new TypeError('bad argument: Buffer.from: only strings are supported');\n"
    "  return new __BufferClass(data, encoding);\n"
    "};\n"
    "Buffer.alloc = function (n) {\n"
    "  n = Number(n);\n"
    "  if (!(n >= 0) || n !== (n | 0)) throw new TypeError('bad argument: Buffer.alloc: invalid size');\n"
    "  return new __BufferClass(n);\n"
    "};\n"
    "Buffer.isBuffer = function (x) { return x instanceof __BufferClass; };\n"
    "globalThis.Buffer = Buffer;\n"
    "globalThis.Blob = function (parts, options) {\n"
    "  if (!(this instanceof Blob)) return new Blob(parts, options);\n"
    "  var s = '';\n"
    "  parts = parts || [];\n"
    "  for (var i = 0; i < parts.length; i++) {\n"
    "    var p = parts[i];\n"
    "    if (typeof p === 'string') {\n"
    "      var u = new TextEncoder().encode(p), t = '';\n"
    "      for (var j = 0; j < u.length; j++) t += String.fromCharCode(u[j]);\n"
    "      s += t;\n"
    "    } else if (p && p.__isBlob) {\n"
    "      s += p.__bin;\n"
    "    } else if (p && typeof p.length === 'number') {\n"
    "      var t2 = '';\n"
    "      for (var k = 0; k < p.length; k++) t2 += String.fromCharCode(p[k] & 255);\n"
    "      s += t2;\n"
    "    }\n"
    "  }\n"
    "  this.__bin = s;\n"
    "  this.size = s.length;\n"
    "  this.type = (options && options.type) || '';\n"
    "  this.__isBlob = true;\n"
    "};\n"
    "Blob.prototype.arrayBuffer = function () {\n"
    "  var a = new Uint8Array(this.__bin.length);\n"
    "  for (var i = 0; i < this.__bin.length; i++) a[i] = this.__bin.charCodeAt(i);\n"
    "  return Promise.resolve(a.buffer);\n"
    "};\n"
    "Blob.prototype.text = function () {\n"
    "  var a = new Uint8Array(this.__bin.length);\n"
    "  for (var i = 0; i < this.__bin.length; i++) a[i] = this.__bin.charCodeAt(i);\n"
    "  return Promise.resolve(new TextDecoder().decode(a));\n"
    "};\n"
    "Blob.prototype.slice = function (start, end) {\n"
    "  var b = new Blob([]);\n"
    "  b.__bin = this.__bin.slice(start || 0, end === undefined ? this.size : end);\n"
    "  b.size = b.__bin.length;\n"
    "  b.type = this.type;\n"
    "  return b;\n"
    "};\n"
    "function __utf8bin(s) {\n"
    "  var u = new TextEncoder().encode(String(s)), t = '';\n"
    "  for (var i = 0; i < u.length; i++) t += String.fromCharCode(u[i]);\n"
    "  return t;\n"
    "}\n"
    "function __randomHex(n) {\n"
    "  var a = new Uint8Array(n); crypto.getRandomValues(a);\n"
    "  var h = '';\n"
    "  for (var i = 0; i < a.length; i++) h += (a[i] < 16 ? '0' : '') + a[i].toString(16);\n"
    "  return h;\n"
    "}\n"
    "globalThis.FormData = function () {\n"
    "  if (!(this instanceof FormData)) return new FormData();\n"
    "  this.__p = [];\n"
    "  this.__isFormData = true;\n"
    "};\n"
    "FormData.prototype.append = function (k, v, filename) {\n"
    "  this.__p.push({ name: String(k), value: v, filename: filename });\n"
    "};\n"
    "FormData.prototype.set = function (k, v, filename) {\n"
    "  this.delete(k); this.append(k, v, filename);\n"
    "};\n"
    "FormData.prototype.get = function (k) {\n"
    "  for (var i = 0; i < this.__p.length; i++) if (this.__p[i].name === String(k)) return this.__p[i].value;\n"
    "  return null;\n"
    "};\n"
    "FormData.prototype.getAll = function (k) {\n"
    "  var out = [];\n"
    "  for (var i = 0; i < this.__p.length; i++) if (this.__p[i].name === String(k)) out.push(this.__p[i].value);\n"
    "  return out;\n"
    "};\n"
    "FormData.prototype.has = function (k) { return this.get(k) !== null; };\n"
    "FormData.prototype.delete = function (k) {\n"
    "  this.__p = this.__p.filter(function (e) { return e.name !== String(k); });\n"
    "};\n"
    "FormData.prototype.__serialize = function () {\n"
    "  var boundary = '----WeizhiForm' + __randomHex(12);\n"
    "  var body = '';\n"
    "  for (var i = 0; i < this.__p.length; i++) {\n"
    "    var e = this.__p[i], v = e.value;\n"
    "    var name = __utf8bin(e.name.replace(/[\"\\r\\n]/g, '_'));\n"
    "    body += '--' + boundary + '\\r\\n';\n"
    "    if (v && v.__isBlob) {\n"
    "      var fn = __utf8bin(String(e.filename || 'blob').replace(/[\"\\r\\n]/g, '_'));\n"
    "      body += 'Content-Disposition: form-data; name=\"' + name + '\"; filename=\"' + fn + '\"\\r\\n';\n"
    "      body += 'Content-Type: ' + (v.type || 'application/octet-stream') + '\\r\\n\\r\\n';\n"
    "      body += v.__bin;\n"
    "    } else {\n"
    "      body += 'Content-Disposition: form-data; name=\"' + name + '\"\\r\\n\\r\\n';\n"
    "      body += __utf8bin(v);\n"
    "    }\n"
    "    body += '\\r\\n';\n"
    "  }\n"
    "  body += '--' + boundary + '--\\r\\n';\n"
    "  var a = new Uint8Array(body.length);\n"
    "  for (var j = 0; j < body.length; j++) a[j] = body.charCodeAt(j);\n"
    "  return { body: Buffer.from(a), contentType: 'multipart/form-data; boundary=' + boundary };\n"
    "};\n"
    "(function () {\n"
    "  var nativeFetch = globalThis.fetch;\n"
    "  globalThis.fetch = function (input, init) {\n"
    "    init = init || {};\n"
    "    var url = typeof input === 'string' ? input\n"
    "      : (input && input.href) ? String(input.href)\n"
    "      : String(input);\n"
    "    var hdrs = {};\n"
    "    if (Array.isArray(init.headers)) {\n"
    "      for (var hi = 0; hi < init.headers.length; hi++) hdrs[init.headers[hi][0]] = init.headers[hi][1];\n"
    "    } else if (init.headers && typeof init.headers.forEach === 'function') {\n"
    "      init.headers.forEach(function (v, k) { hdrs[k] = v; });\n"
    "    } else if (init.headers) {\n"
    "      for (var hk in init.headers) hdrs[hk] = init.headers[hk];\n"
    "    }\n"
    "    var hasCt = false;\n"
    "    for (var ck in hdrs) { if (String(ck).toLowerCase() === 'content-type') hasCt = true; }\n"
    "    var body = init.body;\n"
    "    if (body !== undefined && body !== null) {\n"
    "      if (body.__isFormData) {\n"
    "        var ser = body.__serialize();\n"
    "        body = ser.body;\n"
    "        if (!hasCt) hdrs['Content-Type'] = ser.contentType;\n"
    "      } else if (body.__isBlob) {\n"
    "        var ba = new Uint8Array(body.__bin.length);\n"
    "        for (var bi = 0; bi < body.__bin.length; bi++) ba[bi] = body.__bin.charCodeAt(bi);\n"
    "        if (!hasCt && body.type) hdrs['Content-Type'] = body.type;\n"
    "        body = Buffer.from(ba);\n"
    "      } else if (body instanceof URLSearchParams) {\n"
    "        body = body.toString();\n"
    "        if (!hasCt) hdrs['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';\n"
    "      }\n"
    "    }\n"
    "    return nativeFetch(url, { method: init.method, headers: hdrs, body: body });\n"
    "  };\n"
    "})();\n"
    "if (!Promise.withResolvers) {\n"
    "  Promise.withResolvers = function () {\n"
    "    var r = {};\n"
    "    r.promise = new Promise(function (resolve, reject) { r.resolve = resolve; r.reject = reject; });\n"
    "    return r;\n"
    "  };\n"
    "}\n";

static int install_agent_prelude(JSContext *ctx) {
    JSValue ret = JS_Eval(ctx, WEIZHI_AGENT_PRELUDE, strlen(WEIZHI_AGENT_PRELUDE), "<weizhi-prelude>",
                          JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(ret)) {
        return -1;
    }
    JS_FreeValue(ctx, ret);
    return 0;
}

int weizhi_install_node_api(Engine *engine) {
    JSValue global;
    JSValue console;
    JSValue process;
    JS_SetRuntimeOpaque(engine->rt, engine);
    JS_SetModuleLoaderFunc(engine->rt, NULL, builtin_module_loader, engine);

    global = JS_GetGlobalObject(engine->ctx);
    JS_SetPropertyStr(engine->ctx, global, "require", JS_NewCFunction(engine->ctx, js_require, "require", 1));
    JS_SetPropertyStr(engine->ctx, global, "fetch", JS_NewCFunction(engine->ctx, js_fetch, "fetch", 2));
    JS_SetPropertyStr(engine->ctx, global, "host", make_host_object(engine->ctx));
    JS_SetPropertyStr(engine->ctx, global, "setTimeout",
                      JS_NewCFunction(engine->ctx, js_set_timeout, "setTimeout", 2));
    JS_SetPropertyStr(engine->ctx, global, "clearTimeout",
                      JS_NewCFunction(engine->ctx, js_clear_timeout, "clearTimeout", 1));
    JS_SetPropertyStr(engine->ctx, global, "fs", make_fs_module(engine->ctx));
    JS_SetPropertyStr(engine->ctx, global, "path", make_path_module(engine->ctx));
    console = JS_NewObject(engine->ctx);
    JS_SetPropertyStr(engine->ctx, console, "log", JS_NewCFunction(engine->ctx, js_console_log, "log", 1));
    JS_SetPropertyStr(engine->ctx, console, "warn", JS_NewCFunction(engine->ctx, js_console_log, "warn", 1));
    JS_SetPropertyStr(engine->ctx, console, "error", JS_NewCFunction(engine->ctx, js_console_log, "error", 1));
    JS_SetPropertyStr(engine->ctx, global, "console", console);
    process = make_process_object(engine->ctx);
    JS_SetPropertyStr(engine->ctx, global, "process", process);
    JS_SetPropertyStr(engine->ctx, global, "TextEncoder",
                      JS_NewCFunction2(engine->ctx, js_text_encoder_ctor, "TextEncoder", 0, JS_CFUNC_constructor, 0));
    JS_SetPropertyStr(engine->ctx, global, "TextDecoder",
                      JS_NewCFunction2(engine->ctx, js_text_decoder_ctor, "TextDecoder", 1, JS_CFUNC_constructor, 0));
    JS_SetPropertyStr(engine->ctx, global, "crypto", make_crypto_object(engine->ctx));
    JS_SetPropertyStr(engine->ctx, global, "global", JS_DupValue(engine->ctx, global));
    JS_SetPropertyStr(engine->ctx, global, "globalThis", JS_DupValue(engine->ctx, global));
    if (install_agent_prelude(engine->ctx) != 0) {
        JS_FreeValue(engine->ctx, global);
        return -1;
    }
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
