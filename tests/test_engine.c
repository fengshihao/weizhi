#include "weizhi.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

static int g_failed = 0;

#define EXPECT(cond)                                                                 \
    do {                                                                             \
        if (!(cond)) {                                                               \
            fprintf(stderr, "FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);         \
            g_failed++;                                                              \
        }                                                                            \
    } while (0)

static char *must_dup(const char *text) {
    char *copy = strdup(text);
    if (copy == NULL) {
        abort();
    }
    return copy;
}

static char *make_temp_dir(void) {
    char pattern[] = "/tmp/weizhiXXXXXX";
    char *dir = mkdtemp(pattern);
    if (dir == NULL) {
        perror("mkdtemp");
        abort();
    }
    return must_dup(dir);
}

static void write_file(const char *dir, const char *name, const void *bytes, size_t len) {
    char path[512];
    FILE *file;
    snprintf(path, sizeof(path), "%s/%s", dir, name);
    file = fopen(path, "wb");
    if (file == NULL) {
        perror(path);
        abort();
    }
    if (fwrite(bytes, 1, len, file) != len) {
        abort();
    }
    fclose(file);
}

/* (func (export "add") (param i32 i32) (result i32) (i32.add)) */
static const unsigned char ADD_WASM[] = {
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01,
    0x7f, 0x03, 0x02, 0x01, 0x00, 0x07, 0x07, 0x01, 0x03, 0x61, 0x64, 0x64, 0x00, 0x00, 0x0a, 0x09,
    0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a, 0x0b,
};

/* Same as above, but declares 40 pages (2.5MB), over the 2MB limit. Memory section must precede exports. */
static const unsigned char BIG_MEM_WASM[] = {
    0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, 0x01, 0x07, 0x01, 0x60, 0x02, 0x7f, 0x7f, 0x01,
    0x7f, 0x03, 0x02, 0x01, 0x00, 0x05, 0x03, 0x01, 0x00, 0x28, 0x07, 0x07, 0x01, 0x03, 0x61, 0x64,
    0x64, 0x00, 0x00, 0x0a, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6a, 0x0b,
};

static void test_arithmetic(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    EXPECT(engine != NULL);
    result = weizhi_run_js(engine, "1 + 2", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "3") == 0);
    EXPECT(result.duration_ms >= 0);
    weizhi_result_free(&result);
    EXPECT(weizhi_close(engine) == 0);
}

static void test_engine_survives_syntax_error(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult bad = weizhi_run_js(engine, "function(", 1000);
    WeizhiResult good;
    EXPECT(bad.ok == 0);
    EXPECT(bad.error != NULL && bad.error[0] != '\0');
    EXPECT(bad.error_location != NULL && strstr(bad.error_location, "1") != NULL);
    weizhi_result_free(&bad);
    good = weizhi_run_js(engine, "4 + 5", 1000);
    EXPECT(good.ok == 1);
    EXPECT(good.output_text != NULL && strcmp(good.output_text, "9") == 0);
    weizhi_result_free(&good);
    weizhi_close(engine);
}

static char *echo_host(const char *args_json, void *userdata) {
    (void)userdata;
    return must_dup(args_json);
}

static void test_host_function_receives_json(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    EXPECT(weizhi_add_function(engine, "echo", echo_host, NULL) == 0);
    result = weizhi_run_js(engine, "echo({\"a\":1})", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "{\"a\":1}") == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

typedef struct LogBuf {
    char *data;
    size_t len;
    size_t cap;
} LogBuf;

static void on_log(const char *line, void *userdata) {
    LogBuf *buf = userdata;
    size_t line_len = strlen(line);
    if (buf->len + line_len + 2 > buf->cap) {
        buf->cap = (buf->len + line_len + 2) * 2 + 64;
        buf->data = realloc(buf->data, buf->cap);
        if (buf->data == NULL) {
            abort();
        }
    }
    memcpy(buf->data + buf->len, line, line_len);
    buf->len += line_len;
    buf->data[buf->len++] = '\n';
    buf->data[buf->len] = '\0';
}

static void test_log_contains_host_call(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    LogBuf buf;
    WeizhiResult result;
    memset(&buf, 0, sizeof(buf));
    weizhi_set_run_id(engine, "dialog1");
    weizhi_set_log(engine, on_log, &buf);
    EXPECT(weizhi_add_function(engine, "echo", echo_host, NULL) == 0);
    result = weizhi_run_js(engine, "echo({\"a\":1})", 1000);
    EXPECT(result.ok == 1);
    EXPECT(buf.data != NULL && strstr(buf.data, "\"run_id\":\"dialog1\"") != NULL);
    EXPECT(buf.data != NULL && strstr(buf.data, "host_call") != NULL);
    EXPECT(buf.data != NULL && strstr(buf.data, "\"a\":1") != NULL);
    EXPECT(buf.data != NULL && strstr(buf.data, "run_js_start") != NULL);
    weizhi_result_free(&result);
    free(buf.data);
    weizhi_close(engine);
}

static void test_memory_limit(void) {
    WeizhiLimits limits;
    WeizhiEngine *engine;
    WeizhiResult result;
    memset(&limits, 0, sizeof(limits));
    limits.js_heap_bytes = 512 * 1024;
    engine = weizhi_open(&limits);
    result = weizhi_run_js(engine, "\"x\".repeat(2 * 1024 * 1024)", 3000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "memory") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "6", 1000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_stack_limit(void) {
    WeizhiLimits limits;
    WeizhiEngine *engine;
    WeizhiResult result;
    const char *source =
        "function f(n){ if(n===0) return 1; return f(n-1)+1; } f(100000)";
    memset(&limits, 0, sizeof(limits));
    limits.js_stack_bytes = 32 * 1024;
    engine = weizhi_open(&limits);
    result = weizhi_run_js(engine, source, 3000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "stack") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_timeout(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result = weizhi_run_js(engine, "while(true){}", 200);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "timeout") != NULL);
    EXPECT(result.duration_ms < 2000);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "8", 1000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static pthread_t g_main_thread;
static int g_same_thread = 0;

static char *thread_host(const char *args_json, void *userdata) {
    (void)args_json;
    (void)userdata;
    g_same_thread = pthread_equal(pthread_self(), g_main_thread);
    return must_dup("1");
}

static void test_host_runs_on_caller_thread(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    g_main_thread = pthread_self();
    EXPECT(weizhi_add_function(engine, "ping", thread_host, NULL) == 0);
    result = weizhi_run_js(engine, "ping({})", 1000);
    EXPECT(result.ok == 1);
    EXPECT(g_same_thread == 1);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static WeizhiResult g_reentry;
static int g_reentry_checked = 0;

static char *reentry_host(const char *args_json, void *userdata) {
    (void)args_json;
    g_reentry = weizhi_run_js(userdata, "1+1", 1000);
    g_reentry_checked = 1;
    return must_dup("7");
}

static void test_reentry_rejected(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    memset(&g_reentry, 0, sizeof(g_reentry));
    EXPECT(weizhi_add_function(engine, "again", reentry_host, engine) == 0);
    result = weizhi_run_js(engine, "again({})", 1000);
    EXPECT(g_reentry_checked == 1);
    EXPECT(g_reentry.ok == 0);
    EXPECT(g_reentry.error != NULL && strstr(g_reentry.error, "again") != NULL);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "7") == 0);
    weizhi_result_free(&g_reentry);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

typedef struct BusySync {
    pthread_mutex_t mu;
    pthread_cond_t cv;
    WeizhiEngine *engine;
    int host_entered;
    int second_done;
    WeizhiResult second;
} BusySync;

static char *busy_host(const char *args_json, void *userdata) {
    BusySync *sync = userdata;
    (void)args_json;
    pthread_mutex_lock(&sync->mu);
    sync->host_entered = 1;
    pthread_cond_signal(&sync->cv);
    while (!sync->second_done) {
        pthread_cond_wait(&sync->cv, &sync->mu);
    }
    pthread_mutex_unlock(&sync->mu);
    return must_dup("1");
}

static void *busy_thread(void *userdata) {
    BusySync *sync = userdata;
    struct timespec deadline;
    clock_gettime(CLOCK_REALTIME, &deadline);
    deadline.tv_sec += 2;
    pthread_mutex_lock(&sync->mu);
    while (!sync->host_entered) {
        if (pthread_cond_timedwait(&sync->cv, &sync->mu, &deadline) == ETIMEDOUT) {
            sync->second.ok = 0;
            sync->second.error = must_dup("host function was not called");
            sync->second_done = 1;
            pthread_mutex_unlock(&sync->mu);
            return NULL;
        }
    }
    pthread_mutex_unlock(&sync->mu);
    sync->second = weizhi_run_js(sync->engine, "1", 1000);
    pthread_mutex_lock(&sync->mu);
    sync->second_done = 1;
    pthread_cond_signal(&sync->cv);
    pthread_mutex_unlock(&sync->mu);
    return NULL;
}

static void test_second_thread_rejected(void) {
    BusySync sync;
    pthread_t thread;
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    memset(&sync, 0, sizeof(sync));
    pthread_mutex_init(&sync.mu, NULL);
    pthread_cond_init(&sync.cv, NULL);
    sync.engine = engine;
    EXPECT(weizhi_add_function(engine, "hold", busy_host, &sync) == 0);
    EXPECT(pthread_create(&thread, NULL, busy_thread, &sync) == 0);
    result = weizhi_run_js(engine, "hold({})", 3000);
    pthread_join(thread, NULL);
    EXPECT(sync.second.ok == 0);
    EXPECT(sync.second.error != NULL && strstr(sync.second.error, "busy") != NULL);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    weizhi_result_free(&sync.second);
    weizhi_close(engine);
    pthread_mutex_destroy(&sync.mu);
    pthread_cond_destroy(&sync.cv);
}

static int g_close_rc = 99;

static char *close_host(const char *args_json, void *userdata) {
    (void)args_json;
    g_close_rc = weizhi_close(userdata);
    return must_dup("1");
}

static void test_close_while_running_fails(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    g_close_rc = 99;
    EXPECT(weizhi_add_function(engine, "shutdown", close_host, engine) == 0);
    result = weizhi_run_js(engine, "shutdown({})", 1000);
    EXPECT(g_close_rc == -1);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "9", 1000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    EXPECT(weizhi_close(engine) == 0);
}

static void test_host_function_limit(void) {
    WeizhiLimits limits;
    WeizhiEngine *engine;
    int i;
    memset(&limits, 0, sizeof(limits));
    limits.max_host_functions = 2;
    engine = weizhi_open(&limits);
    EXPECT(weizhi_add_function(engine, "a", echo_host, NULL) == 0);
    EXPECT(weizhi_add_function(engine, "b", echo_host, NULL) == 0);
    EXPECT(weizhi_add_function(engine, "c", echo_host, NULL) == -1);
    for (i = 0; i < WEIZHI_DEFAULT_MAX_HOST_FUNCTIONS; i++) {
        /* Count the default limit on a separate engine so it is not mixed with the limit of 2 above. */
    }
    weizhi_close(engine);
    engine = weizhi_open(NULL);
    for (i = 0; i < WEIZHI_DEFAULT_MAX_HOST_FUNCTIONS; i++) {
        char name[16];
        snprintf(name, sizeof(name), "f%d", i);
        EXPECT(weizhi_add_function(engine, name, echo_host, NULL) == 0);
    }
    EXPECT(weizhi_add_function(engine, "overflow", echo_host, NULL) == -1);
    weizhi_close(engine);
}

static void test_load_pack_add(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    write_file(dir, "add.wasm", ADD_WASM, sizeof(ADD_WASM));
    EXPECT(weizhi_set_pack_folder(engine, dir) == 0);
    result = weizhi_run_js(engine, "const p = loadPack(\"add\"); p.add(20, 22)", 2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "42") == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_missing_and_illegal_pack(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    const unsigned char aot_mark[] = {'a', 'o', 't'};
    write_file(dir, "add.wasm", ADD_WASM, sizeof(ADD_WASM));
    write_file(dir, "old.aot", aot_mark, sizeof(aot_mark));
    EXPECT(weizhi_set_pack_folder(engine, dir) == 0);
    result = weizhi_run_js(engine, "loadPack(\"missing\")", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "not found") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "loadPack(\"../add\")", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "name") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "loadPack(\"old\")", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "aot") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_pack_memory_and_count(void) {
    char *dir = make_temp_dir();
    WeizhiLimits limits;
    WeizhiEngine *engine;
    WeizhiResult result;
    write_file(dir, "add.wasm", ADD_WASM, sizeof(ADD_WASM));
    write_file(dir, "big.wasm", BIG_MEM_WASM, sizeof(BIG_MEM_WASM));
    memset(&limits, 0, sizeof(limits));
    limits.max_packs = 2;
    engine = weizhi_open(&limits);
    EXPECT(weizhi_set_pack_folder(engine, dir) == 0);
    result = weizhi_run_js(engine, "loadPack(\"big\")", 2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "memory") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(
        engine,
        "var a = loadPack(\"add\"); var b = loadPack(\"add\"); var c = loadPack(\"add\"); 1",
        2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "too many") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_load_script(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    const char *lib = "globalThis.inc = function(x){ return x + 1; }; 0";
    write_file(dir, "util.js", lib, strlen(lib));
    EXPECT(weizhi_set_pack_folder(engine, dir) == 0);
    result = weizhi_run_js(engine, "loadScript(\"util.js\"); inc(41)", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "42") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "loadScript(\"../util.js\")", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "name") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_promise_await(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result = weizhi_run_js(engine, "await Promise.resolve(42)", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "42") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "await (async () => 1 + 2)()", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "3") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "await new Promise(r => setTimeout(() => r(7), 20))", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "7") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "await Promise.reject(new Error('boom'))", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "boom") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                           "await new Promise((resolve, reject) => {"
                           "  const id = setTimeout(() => reject(new Error('should-not')), 30);"
                           "  clearTimeout(id);"
                           "  setTimeout(() => resolve(9), 10);"
                           "})",
                           1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "9") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "await new Promise(() => {})", 100);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "timeout") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_buffer_path_require(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result = weizhi_run_js(engine, "Buffer.from('hi').toString()", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"hi\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "Buffer.from('A').toString('hex')", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"41\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "Buffer.alloc(3).length", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "3") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "Buffer.isBuffer(Buffer.from('x')) && !Buffer.isBuffer({})", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "true") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "path.join('a','b')", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"a/b\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                           "path.basename('/a/b.txt') + '|' + path.dirname('/a/b.txt') + '|' + path.extname('b.txt')",
                           1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"b.txt|/a|.txt\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "require('fs').existsSync", 1000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "require('child_process')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_fs_sync_and_promises(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    EXPECT(weizhi_set_fs_root(engine, dir) == 0);
    result = weizhi_run_js(engine, "fs.writeFileSync('a.txt','hello'); fs.readFileSync('a.txt').toString()", 2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"hello\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.writeFileSync('../x.txt','no')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && (strstr(result.error, "path") != NULL || strstr(result.error, "escape") != NULL));
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                           "await fs.promises.writeFile('b.txt','world'); "
                           "(await fs.promises.readFile('b.txt')).toString()",
                           3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"world\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "await fs.promises.readFile('missing.txt')", 2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "not found") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                           "fs.writeFileSync('c.txt','bye'); fs.unlinkSync('c.txt'); fs.existsSync('c.txt')",
                           2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "false") == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_fs_size_limit(void) {
    char *dir = make_temp_dir();
    WeizhiLimits limits;
    WeizhiEngine *engine;
    WeizhiResult result;
    char big[64];
    memset(&limits, 0, sizeof(limits));
    limits.fs_io_bytes = 16;
    engine = weizhi_open(&limits);
    EXPECT(weizhi_set_fs_root(engine, dir) == 0);
    result = weizhi_run_js(engine, "fs.writeFileSync('big.txt', 'abcdefghijklmnopqrstuvwxyz')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "too large") != NULL);
    weizhi_result_free(&result);
    memset(big, 'Z', sizeof(big));
    write_file(dir, "on_disk.bin", big, sizeof(big));
    result = weizhi_run_js(engine, "fs.readFileSync('on_disk.bin')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "too large") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_pack_fs_roots_isolated(void) {
    char *pack_dir = make_temp_dir();
    char *fs_dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    write_file(pack_dir, "add.wasm", ADD_WASM, sizeof(ADD_WASM));
    write_file(pack_dir, "secret.txt", "from-pack", 9);
    EXPECT(weizhi_set_pack_folder(engine, pack_dir) == 0);
    EXPECT(weizhi_set_fs_root(engine, fs_dir) == 0);
    result = weizhi_run_js(engine, "loadPack('add').add(2,3)", 2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "5") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.readFileSync('secret.txt')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "not found") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.writeFileSync('add.wasm','corrupt'); loadPack('add').add(1,1)", 2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "2") == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(pack_dir);
    free(fs_dir);
}

static void test_console_logs(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    LogBuf buf;
    WeizhiResult result;
    memset(&buf, 0, sizeof(buf));
    weizhi_set_log(engine, on_log, &buf);
    result = weizhi_run_js(engine, "console.log('hello', 1); 0", 1000);
    EXPECT(result.ok == 1);
    EXPECT(buf.data != NULL && strstr(buf.data, "\"event\":\"console\"") != NULL);
    EXPECT(buf.data != NULL && strstr(buf.data, "hello") != NULL);
    weizhi_result_free(&result);
    free(buf.data);
    weizhi_close(engine);
}

static void test_import_fs(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    EXPECT(weizhi_set_fs_root(engine, dir) == 0);
    result = weizhi_run_js(engine,
                           "import fs from 'fs';\n"
                           "fs.writeFileSync('i.txt','ok');\n",
                           2000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.readFileSync('i.txt').toString()", 1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"ok\"") == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

typedef struct SlowFs {
    char *root;
    int delay_ms;
} SlowFs;

static int slow_join(const char *root, const char *rel, char *out, size_t out_len) {
    if (rel == NULL || strstr(rel, "..") != NULL || rel[0] == '/') {
        return -1;
    }
    snprintf(out, out_len, "%s/%s", root, rel);
    return 0;
}

static int slow_vfs_sync(WeizhiVfsOp op, const char *relpath, const char *relpath2, const WeizhiBytes *in,
                         WeizhiBytes *out, char *errbuf, size_t errbuf_len, void *userdata) {
    SlowFs *fs = userdata;
    char path[512];
    (void)relpath2;
    memset(out, 0, sizeof(*out));
    if (slow_join(fs->root, relpath, path, sizeof(path)) != 0) {
        snprintf(errbuf, errbuf_len, "invalid path");
        return -1;
    }
    if (op == WEIZHI_VFS_WRITE) {
        FILE *file = fopen(path, "wb");
        if (file == NULL || (in && in->len > 0 && fwrite(in->data, 1, in->len, file) != in->len)) {
            if (file) {
                fclose(file);
            }
            snprintf(errbuf, errbuf_len, "write failed");
            return -1;
        }
        fclose(file);
        return 0;
    }
    if (op == WEIZHI_VFS_READ) {
        FILE *file = fopen(path, "rb");
        long size;
        if (file == NULL) {
            snprintf(errbuf, errbuf_len, "file not found");
            return -1;
        }
        fseek(file, 0, SEEK_END);
        size = ftell(file);
        rewind(file);
        out->data = malloc((size_t)size);
        if (size > 0 && (out->data == NULL || fread(out->data, 1, (size_t)size, file) != (size_t)size)) {
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
    snprintf(errbuf, errbuf_len, "unsupported operation");
    return -1;
}

typedef struct SlowJob {
    WeizhiEngine *engine;
    int64_t request_id;
    WeizhiVfsOp op;
    char *relpath;
    WeizhiBytes in;
    SlowFs *fs;
} SlowJob;

static void *slow_job_main(void *arg) {
    SlowJob *job = arg;
    WeizhiBytes out;
    char errbuf[64];
    int rc;
    usleep((useconds_t)job->fs->delay_ms * 1000);
    memset(&out, 0, sizeof(out));
    rc = slow_vfs_sync(job->op, job->relpath, NULL, &job->in, &out, errbuf, sizeof(errbuf), job->fs);
    weizhi_complete(job->engine, job->request_id, rc == 0, &out, errbuf);
    weizhi_bytes_free(&out);
    free(job->relpath);
    free(job->in.data);
    free(job);
    return NULL;
}

static int slow_vfs_async(WeizhiEngine *engine, int64_t request_id, WeizhiVfsOp op, const char *relpath,
                          const char *relpath2, const WeizhiBytes *in, void *userdata) {
    SlowJob *job = calloc(1, sizeof(*job));
    pthread_t thread;
    (void)relpath2;
    if (job == NULL) {
        return -1;
    }
    job->engine = engine;
    job->request_id = request_id;
    job->op = op;
    job->fs = userdata;
    job->relpath = strdup(relpath != NULL ? relpath : "");
    if (in != NULL && in->len > 0 && in->data != NULL) {
        job->in.data = malloc(in->len);
        if (job->in.data == NULL) {
            free(job->relpath);
            free(job);
            return -1;
        }
        memcpy(job->in.data, in->data, in->len);
        job->in.len = in->len;
    }
    if (pthread_create(&thread, NULL, slow_job_main, job) != 0) {
        free(job->relpath);
        free(job->in.data);
        free(job);
        return -1;
    }
    pthread_detach(thread);
    return 0;
}

static void test_promise_all_parallel(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    SlowFs fs;
    WeizhiResult result;
    struct timespec t0, t1;
    long elapsed_ms;
    fs.root = dir;
    fs.delay_ms = 80;
    EXPECT(weizhi_set_fs_root(engine, dir) == 0);
    weizhi_set_vfs(engine, slow_vfs_sync, slow_vfs_async, &fs);
    result = weizhi_run_js(engine, "fs.writeFileSync('x.txt','x'); fs.writeFileSync('y.txt','y'); 1", 2000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    clock_gettime(CLOCK_MONOTONIC, &t0);
    result = weizhi_run_js(engine,
                           "const a = fs.promises.readFile('x.txt');"
                           "const b = fs.promises.readFile('y.txt');"
                           "await Promise.all([a,b]); 1",
                           3000);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    elapsed_ms = (long)((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000);
    EXPECT(result.ok == 1);
    /* Each path sleeps 80ms; parallel should be clearly under serial 160ms */
    EXPECT(elapsed_ms < 150);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_agent_precise_errors(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    result = weizhi_run_js(engine, "require('http')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported") != NULL);
    EXPECT(result.error != NULL && strstr(result.error, "http") != NULL);
    EXPECT(result.error != NULL && strstr(result.error, "available") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.watch", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported: fs.watch") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.promises.createReadStream", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported: fs.promises.createReadStream") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "Buffer.from(1)", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "bad argument: Buffer.from") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "process.exit", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported: process.exit") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

int main(void) {
    test_arithmetic();
    test_engine_survives_syntax_error();
    test_host_function_receives_json();
    test_log_contains_host_call();
    test_memory_limit();
    test_stack_limit();
    test_timeout();
    test_host_runs_on_caller_thread();
    test_reentry_rejected();
    test_second_thread_rejected();
    test_close_while_running_fails();
    test_host_function_limit();
    test_load_pack_add();
    test_missing_and_illegal_pack();
    test_pack_memory_and_count();
    test_load_script();
    test_promise_await();
    test_buffer_path_require();
    test_fs_sync_and_promises();
    test_fs_size_limit();
    test_pack_fs_roots_isolated();
    test_console_logs();
    test_import_fs();
    test_promise_all_parallel();
    test_agent_precise_errors();
    if (g_failed != 0) {
        fprintf(stderr, "%d assertion(s) failed\n", g_failed);
        return 1;
    }
    printf("all passed\n");
    return 0;
}
