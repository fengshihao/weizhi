#include "weizhi.h"

#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
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

static void test_run_js_ex_filename_in_stack(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    const char *source = "const a = 1;\n"
                         "const b = 2;\n"
                         "const c = 3;\n"
                         "const d = 4;\n"
                         "}\n";
    WeizhiResult bad = weizhi_run_js_ex(engine, source, 1000, "scripts/foo.js");
    EXPECT(bad.ok == 0);
    EXPECT(bad.error != NULL && bad.error[0] != '\0');
    EXPECT(bad.error_location != NULL);
    EXPECT(strstr(bad.error_location, "scripts/foo.js") != NULL);
    EXPECT(strstr(bad.error_location, ":5") != NULL);
    weizhi_result_free(&bad);
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

typedef struct CancelArg {
    WeizhiEngine *engine;
    const char *source;
    WeizhiResult result;
} CancelArg;

static void *run_until_cancelled(void *arg) {
    CancelArg *job = arg;
    job->result = weizhi_run_js(job->engine, job->source, -1);
    return NULL;
}

static void expect_cancelled(const char *source) {
    WeizhiEngine *engine = weizhi_open(NULL);
    CancelArg job;
    pthread_t thread;
    WeizhiResult again;
    job.engine = engine;
    job.source = source;
    memset(&job.result, 0, sizeof(job.result));
    EXPECT(pthread_create(&thread, NULL, run_until_cancelled, &job) == 0);
    {
        int i;
        for (i = 0; i < 40; i++) {
            usleep(10 * 1000);
            weizhi_cancel(engine);
        }
    }
    EXPECT(pthread_join(thread, NULL) == 0);
    EXPECT(job.result.ok == 0);
    EXPECT(job.result.error != NULL && strstr(job.result.error, "cancelled") != NULL);
    weizhi_result_free(&job.result);
    again = weizhi_run_js(engine, "3", 1000);
    EXPECT(again.ok == 1);
    weizhi_result_free(&again);
    weizhi_close(engine);
}

static void test_cancel(void) {
    expect_cancelled("while (true) {}");
    expect_cancelled("await new Promise(() => {})");
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

static void test_load_script_removed(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result = weizhi_run_js(engine, "loadScript(\"util.js\")", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "loadScript") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_relative_import(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    const char *lib = "export function inc(x){ return x + 1; }\n";
    write_file(dir, "util.js", lib, strlen(lib));
    EXPECT(weizhi_set_script_folder(engine, dir) == 0);
    result = weizhi_run_js(engine,
                           "import { inc } from './util.js';\n"
                           "export default inc(41);\n",
                           2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "42") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                           "const m = await import('./util.js');\n"
                           "m.inc(9);\n",
                           2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "10") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "import './../util.js';\n", 1000);
    EXPECT(result.ok == 0);
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
    result = weizhi_run_js(engine,
                           "const b = Buffer.from('AB');"
                           "({u8: b instanceof Uint8Array, i0: b[0], i1: b[1], len: b.length})",
                           1000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"u8\":true") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"i0\":65") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"i1\":66") != NULL);
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
    {
        char folder_real[512];
        char js[640];
        EXPECT(realpath(dir, folder_real) != NULL);
        result = weizhi_run_js(engine, "fs.writeFileSync('abs.txt','abs');", 2000);
        EXPECT(result.ok == 1);
        weizhi_result_free(&result);
        snprintf(js, sizeof(js), "fs.readFileSync('%s/abs.txt').toString()", folder_real);
        result = weizhi_run_js(engine, js, 2000);
        EXPECT(result.ok == 1);
        EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"abs\"") == 0);
        weizhi_result_free(&result);
        result = weizhi_run_js(engine, "fs.readFileSync('./abs.txt').toString()", 2000);
        EXPECT(result.ok == 1);
        EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"abs\"") == 0);
        weizhi_result_free(&result);
        result = weizhi_run_js(engine, "fs.readFileSync('/etc/passwd').toString()", 1000);
        EXPECT(result.ok == 0);
        EXPECT(result.error != NULL && strstr(result.error, "escape") != NULL);
        weizhi_result_free(&result);
    }
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

static void test_fs_mkdir_and_nested_write(void) {
    char *dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    char folder_real[512];
    char js[640];
    EXPECT(weizhi_set_fs_root(engine, dir) == 0);
    EXPECT(realpath(dir, folder_real) != NULL);
    result = weizhi_run_js(engine,
                           "fs.mkdirSync('out', { recursive: true });"
                           "fs.writeFileSync('out/icon.png','png');"
                           "fs.readFileSync('out/icon.png').toString()",
                           3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"png\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.writeFileSync('out2/nested/a.bin','bin'); fs.readFileSync('out2/nested/a.bin')",
                           3000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    snprintf(js, sizeof(js), "fs.readFileSync('%s/abs.txt').toString()", folder_real);
    result = weizhi_run_js(engine, "fs.writeFileSync('abs.txt','abs')", 2000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, js, 3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"abs\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                           "await fs.promises.mkdir('async-out', { recursive: true });"
                           "await fs.promises.writeFile('async-out/b.txt','async');"
                           "(await fs.promises.readFile('async-out/b.txt')).toString()",
                           5000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"async\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.mkdirSync('out', { recursive: true })", 2000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.mkdirSync('out')", 2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "directory exists") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.mkdirSync('../escape')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && (strstr(result.error, "path") != NULL || strstr(result.error, "escape") != NULL));
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

static void test_workspace_catalog_module_fallback(void) {
    char *script_dir = make_temp_dir();
    char *fs_dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    const char *docx = "export const fromCatalog = true;\n";
    const char *helper = "export const fromWorkspace = true;\n";
    write_file(script_dir, "docx.js", docx, strlen(docx));
    {
        char jobs[512];
        snprintf(jobs, sizeof(jobs), "%s/jobs", fs_dir);
        EXPECT(mkdir(jobs, 0755) == 0);
    }
    write_file(fs_dir, "jobs/helper.js", helper, strlen(helper));
    EXPECT(weizhi_set_script_folder(engine, script_dir) == 0);
    EXPECT(weizhi_set_fs_root(engine, fs_dir) == 0);
    result = weizhi_run_js_ex(engine,
                              "import { fromCatalog } from './docx.js';\n"
                              "export default fromCatalog;\n",
                              2000,
                              "jobs/run.js");
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "true") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js_ex(engine,
                              "import { fromWorkspace } from './helper.js';\n"
                              "export default fromWorkspace;\n",
                              2000,
                              "jobs/run.js");
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "true") == 0);
    weizhi_result_free(&result);
    {
        char abs_helper[512];
        char js[640];
        EXPECT(realpath(fs_dir, abs_helper) != NULL);
        snprintf(js, sizeof(js),
                 "import { fromWorkspace } from '%s/jobs/helper.js';\n"
                 "export default fromWorkspace;\n",
                 abs_helper);
        result = weizhi_run_js_ex(engine, js, 2000, "jobs/run.js");
        EXPECT(result.ok == 1);
        EXPECT(result.output_text != NULL && strcmp(result.output_text, "true") == 0);
        weizhi_result_free(&result);
    }
    result = weizhi_run_js_ex(engine,
                              "import { fromCatalog } from 'docx';\n"
                              "export default fromCatalog;\n",
                              2000,
                              "jobs/run.js");
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "true") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js_ex(engine, "import '../../../etc/passwd';\n", 1000, "jobs/run.js");
    EXPECT(result.ok == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(script_dir);
    free(fs_dir);
}

static void test_script_fs_roots_isolated(void) {
    char *script_dir = make_temp_dir();
    char *fs_dir = make_temp_dir();
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    const char *lib = "export const secretFromScript = 'from-script';\n";
    write_file(script_dir, "util.js", lib, strlen(lib));
    write_file(script_dir, "secret.txt", "from-script-dir", 15);
    EXPECT(weizhi_set_script_folder(engine, script_dir) == 0);
    EXPECT(weizhi_set_fs_root(engine, fs_dir) == 0);
    result = weizhi_run_js(engine,
                           "import { secretFromScript } from './util.js';\n"
                           "export default secretFromScript;\n",
                           2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"from-script\"") == 0);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "fs.readFileSync('secret.txt')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "not found") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(script_dir);
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

/* Sync VFS with artificial delay so default async pool concurrency is observable. */
static int slow_vfs_sync(WeizhiVfsOp op, const char *relpath, const char *relpath2, const WeizhiBytes *in,
                         WeizhiBytes *out, char *errbuf, size_t errbuf_len, void *userdata) {
    SlowFs *fs = userdata;
    char path[512];
    (void)relpath2;
    memset(out, 0, sizeof(*out));
    if (fs->delay_ms > 0) {
        usleep((useconds_t)fs->delay_ms * 1000);
    }
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
    /* Keep default async pool; only replace sync so workers hit the delay. */
    weizhi_set_vfs(engine, slow_vfs_sync, NULL, &fs);
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
    /* Each path sleeps 80ms; default pool (16) should run them in parallel. */
    EXPECT(elapsed_ms < 150);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_async_io_serializes(void) {
    char *dir = make_temp_dir();
    WeizhiLimits limits;
    WeizhiEngine *engine;
    SlowFs fs;
    WeizhiResult result;
    struct timespec t0, t1;
    long elapsed_ms;
    memset(&limits, 0, sizeof(limits));
    limits.max_async_io = 1;
    engine = weizhi_open(&limits);
    fs.root = dir;
    fs.delay_ms = 80;
    EXPECT(weizhi_set_fs_root(engine, dir) == 0);
    weizhi_set_vfs(engine, slow_vfs_sync, NULL, &fs);
    result = weizhi_run_js(engine, "fs.writeFileSync('x.txt','x'); fs.writeFileSync('y.txt','y'); 1", 2000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    clock_gettime(CLOCK_MONOTONIC, &t0);
    result = weizhi_run_js(engine,
                           "const a = fs.promises.readFile('x.txt');"
                           "const b = fs.promises.readFile('y.txt');"
                           "await Promise.all([a,b]); 1",
                           5000);
    clock_gettime(CLOCK_MONOTONIC, &t1);
    elapsed_ms = (long)((t1.tv_sec - t0.tv_sec) * 1000 + (t1.tv_nsec - t0.tv_nsec) / 1000000);
    EXPECT(result.ok == 1);
    /* One worker: two 80ms jobs should take about 160ms (queue, no error). */
    EXPECT(elapsed_ms >= 150);
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
    result = weizhi_run_js(engine, "fetch('https://example.com')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported: fetch") != NULL);
    EXPECT(result.error != NULL && strstr(result.error, "enableFetch") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static int mock_http_async(WeizhiEngine *engine, int64_t request_id, const char *method, const char *url,
                           const char *headers_json, const WeizhiBytes *body, void *userdata) {
    WeizhiBytes out;
    const char *payload = "{\"hello\":\"world\"}";
    (void)method;
    (void)headers_json;
    (void)body;
    (void)userdata;
    if (url != NULL && strstr(url, "blocked.example") != NULL) {
        weizhi_complete_fetch(engine, request_id, 0, NULL, NULL,
                              "fetch blocked: host \"blocked.example\" is not allowlisted");
        return 0;
    }
    memset(&out, 0, sizeof(out));
    out.len = strlen(payload);
    out.data = (unsigned char *)malloc(out.len);
    if (out.data == NULL) {
        weizhi_complete_fetch(engine, request_id, 0, NULL, NULL, "out of memory");
        return 0;
    }
    memcpy(out.data, payload, out.len);
    weizhi_complete_fetch(engine, request_id, 200, "{\"content-type\":\"application/json\"}", &out, NULL);
    weizhi_bytes_free(&out);
    return 0;
}

static char *mcp_body_cstr(const WeizhiBytes *body) {
    char *s;
    if (body == NULL || body->data == NULL || body->len == 0) {
        return strdup("");
    }
    s = (char *)malloc(body->len + 1);
    if (s == NULL) {
        return NULL;
    }
    memcpy(s, body->data, body->len);
    s[body->len] = '\0';
    return s;
}

static const char *mcp_method_of(const char *body) {
    const char *p;
    const char *end;
    static char method[64];
    size_t n;
    p = strstr(body, "\"method\":\"");
    if (p == NULL) {
        return "";
    }
    p += strlen("\"method\":\"");
    end = strchr(p, '"');
    if (end == NULL) {
        return "";
    }
    n = (size_t)(end - p);
    if (n >= sizeof(method)) {
        n = sizeof(method) - 1;
    }
    memcpy(method, p, n);
    method[n] = '\0';
    return method;
}

static void mcp_complete(WeizhiEngine *engine, int64_t request_id, int status, const char *ctype,
                         const char *json) {
    WeizhiBytes out;
    char headers[160];
    memset(&out, 0, sizeof(out));
    out.data = (unsigned char *)json;
    out.len = strlen(json);
    snprintf(headers, sizeof(headers), "{\"content-type\":\"%s\"}", ctype);
    weizhi_complete_fetch(engine, request_id, status, headers, &out, NULL);
}

typedef struct McpMock {
    int initialized;
} McpMock;

static int mock_mcp_http(WeizhiEngine *engine, int64_t request_id, const char *method, const char *url,
                         const char *headers_json, const WeizhiBytes *body, void *userdata) {
    McpMock *st = userdata;
    char *text;
    const char *rpc;
    const char *ctype = "application/json";
    (void)method;
    text = mcp_body_cstr(body);
    if (text == NULL) {
        weizhi_complete_fetch(engine, request_id, 0, NULL, NULL, "out of memory");
        return 0;
    }
    rpc = mcp_method_of(text);
    if (url != NULL && strstr(url, "/sse") != NULL) {
        ctype = "text/event-stream";
        mcp_complete(engine, request_id, 200, ctype,
                     "event: message\n"
                     "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"s\",\"description\":\"\","
                     "\"inputSchema\":{}}]}}\n\n");
    } else if (url != NULL && strstr(url, "/version") != NULL
               && headers_json != NULL && strstr(headers_json, "MCP-Protocol-Version") != NULL) {
        mcp_complete(engine, request_id, 400, ctype,
                     "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32000,"
                     "\"message\":\"Unsupported protocol version\"}}");
    } else if (strcmp(rpc, "initialize") == 0) {
        st->initialized = 1;
        mcp_complete(engine, request_id, 200, ctype,
                     "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2026-07-28\"}}");
    } else if (url != NULL && strstr(url, "/init") != NULL && !st->initialized) {
        mcp_complete(engine, request_id, 200, ctype,
                     "{\"jsonrpc\":\"2.0\",\"id\":1,\"error\":{\"code\":-32001,\"message\":\"Session not found\"}}");
    } else if (strcmp(rpc, "tools/call") == 0) {
        mcp_complete(engine, request_id, 200, ctype,
                     "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}]}}");
    } else {
        mcp_complete(engine, request_id, 200, ctype,
                     "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[{\"name\":\"echo\",\"description\":\"say\","
                     "\"inputSchema\":{\"type\":\"object\"}}]}}");
    }
    free(text);
    return 0;
}

static void test_mcp_client(void) {
    WeizhiEngine *engine;
    WeizhiResult result;
    McpMock st;
    memset(&st, 0, sizeof(st));

    engine = weizhi_open(NULL);
    result = weizhi_run_js(engine,
                           "const c = await mcp.connect({url:'https://mcp.example/rpc'});"
                           "await c.listTools()",
                           3000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported: fetch") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);

    engine = weizhi_open(NULL);
    result = weizhi_run_js(engine, "await mcp.connect({url:'/local'})", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "bad argument: mcp.connect") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);

    engine = weizhi_open(NULL);
    result = weizhi_run_js(engine,
                           "const c = await mcp.connect({url:'https://mcp.example/rpc'});"
                           "await c.close(); await c.listTools()",
                           1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "mcp client is closed") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);

    engine = weizhi_open(NULL);
    weizhi_set_http(engine, mock_mcp_http, &st);
    result = weizhi_run_js(engine,
                           "const c = await mcp.connect({url:'https://mcp.example/rpc', headers:{Authorization:'Bearer t'}});"
                           "const tools = await c.listTools();"
                           "const r = await c.callTool('echo', {msg:'hi'});"
                           "({name:tools[0].name, schema:tools[0].inputSchema.type, text:r.text, err:r.isError})",
                           3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"name\":\"echo\"") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"schema\":\"object\"") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"text\":\"pong\"") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"err\":false") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);

    engine = weizhi_open(NULL);
    memset(&st, 0, sizeof(st));
    weizhi_set_http(engine, mock_mcp_http, &st);
    result = weizhi_run_js(engine,
                           "const c = await mcp.connect({url:'https://mcp.example/version'});"
                           "const tools = await c.listTools();"
                           "tools[0].name",
                           3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "echo") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);

    engine = weizhi_open(NULL);
    memset(&st, 0, sizeof(st));
    weizhi_set_http(engine, mock_mcp_http, &st);
    result = weizhi_run_js(engine,
                           "const c = await mcp.connect({url:'https://mcp.example/init'});"
                           "const tools = await c.listTools();"
                           "tools[0].name",
                           3000);
    EXPECT(result.ok == 1);
    EXPECT(st.initialized == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "echo") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);

    engine = weizhi_open(NULL);
    memset(&st, 0, sizeof(st));
    weizhi_set_http(engine, mock_mcp_http, &st);
    result = weizhi_run_js(engine,
                           "const c = await mcp.connect({url:'https://mcp.example/sse'});"
                           "const tools = await c.listTools();"
                           "tools[0].name",
                           3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "\"s\"") == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_fetch_with_host(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    weizhi_set_http(engine, mock_http_async, NULL);
    result = weizhi_run_js(engine,
                          "const r = await fetch('https://ok.example/api');"
                          "const t = await r.text();"
                          "({ok:r.ok,status:r.status,body:t})",
                          3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"ok\":true") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"status\":200") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "hello") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                          "const resp = await fetch('https://ok.example/api');"
                          "({ct: resp.headers.get('Content-Type'), miss: resp.headers.get('nope')})",
                          3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "application/json") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"miss\":null") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine,
                          "const q = new URLSearchParams({a:'1'});"
                          "const resp2 = await fetch('https://ok.example/api', {method:'POST', body:q});"
                          "resp2.ok",
                          3000);
    EXPECT(result.ok == 1);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "await fetch('https://blocked.example/')", 3000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "fetch blocked") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "await fetch('/local/path')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "http://") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

typedef struct NativeMock {
    int ensure_calls;
    int call_calls;
} NativeMock;

static int mock_native_ensure(WeizhiEngine *engine, int64_t request_id, const char *name, void *userdata) {
    NativeMock *m = userdata;
    m->ensure_calls++;
    if (strcmp(name, "bad_sig") == 0) {
        weizhi_complete_native(engine, request_id, 0, NULL, "native verify failed: signature");
        return 0;
    }
    if (strcmp(name, "echo_math") == 0) {
        weizhi_complete_native(engine, request_id, 1,
                               "{\"name\":\"echo_math\",\"version\":\"1.0.0\",\"exports\":[\"add\"]}", NULL);
        return 0;
    }
    {
        char err[160];
        snprintf(err, sizeof(err), "unsupported: native \"%s\" (not in catalog)", name != NULL ? name : "?");
        weizhi_complete_native(engine, request_id, 0, NULL, err);
    }
    return 0;
}

static char *mock_native_call(const char *plugin_name, const char *export_name, const char *args_json,
                              void *userdata) {
    NativeMock *m = userdata;
    int a = 0;
    int b = 0;
    char *out;
    m->call_calls++;
    (void)plugin_name;
    if (strcmp(export_name, "add") != 0) {
        return NULL;
    }
    if (args_json != NULL && sscanf(args_json, "[%d,%d]", &a, &b) >= 1) {
        /* ok */
    }
    out = malloc(32);
    if (out == NULL) {
        return NULL;
    }
    snprintf(out, 32, "%d", a + b);
    return out;
}

static void test_native_ensure_mock(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    NativeMock mock;
    WeizhiResult result;
    memset(&mock, 0, sizeof(mock));
    result = weizhi_run_js(engine, "await host.ensureNative('echo_math')", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "unsupported: native") != NULL);
    weizhi_result_free(&result);

    weizhi_set_native(engine, mock_native_ensure, mock_native_call, &mock);
    result = weizhi_run_js(engine,
                           "const p = await host.ensureNative('echo_math');"
                           "({name:p.name,version:p.version,sum:p.add([20,22]),caps:process.weizhiCaps.native})",
                           2000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"sum\":42") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"caps\":true") != NULL);
    weizhi_result_free(&result);
    EXPECT(mock.ensure_calls >= 1);
    EXPECT(mock.call_calls >= 1);

    result = weizhi_run_js(engine, "await host.ensureNative('bad_sig')", 2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "signature") != NULL);
    weizhi_result_free(&result);

    result = weizhi_run_js(engine, "await host.ensureNative('nope')", 2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "not in catalog") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_typed_plugin_loader(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    char plugin_dir[512];
    const char *root = getenv("WEIZHI_BUILD_DIR");
    if (root == NULL) {
        root = "build";
    }
    snprintf(plugin_dir, sizeof(plugin_dir), "%s/plugins", root);
    EXPECT(weizhi_enable_plugin_loader(engine, plugin_dir) == 0);
    result = weizhi_run_js(engine,
                           "const p = await host.ensureNative('echo_math');"
                           "const sum = p.add(20, 22);"
                           "const b = Buffer.from('hi');"
                           "const e = p.echo_bytes(b);"
                           "let seen = 0;"
                           "const n = p.count_with_cb(3, (i) => { seen += i; });"
                           "({sum, elen:e.length, n, seen, caps:process.weizhiCaps.native})",
                           5000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"sum\":42") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"elen\":2") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"n\":3") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"seen\":3") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"caps\":true") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "const q = await host.ensureNative('echo_math'); q.add(1)", 2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "bad argument") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_zlib_roundtrip(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result = weizhi_run_js(engine,
                                         "const z = require('zlib');"
                                         "const src = Buffer.from('hello zlib');"
                                         "const gzip = z.gunzipSync(z.gzipSync(src)).toString();"
                                         "const raw = z.inflateSync(z.deflateSync(src)).toString();"
                                         "({gzip, raw, compress: process.weizhiCaps.compress})",
                                         3000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"gzip\":\"hello zlib\"") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"raw\":\"hello zlib\"") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"compress\":true") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_zip_roundtrip(void) {
    char *dir = make_temp_dir();
    char pack[512];
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    /* Minimal stored zip with a zip-slip entry (skipped) and a safe entry. */
    static const unsigned char slip_zip[] = {
        0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x83, 0x16, 0xdc, 0x8c, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x00, 0x00,
        0x2e, 0x2e, 0x2f, 0x65, 0x76, 0x69, 0x6c, 0x2e, 0x74, 0x78, 0x74, 0x78,
        0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xac, 0x2a, 0x93, 0xd8, 0x02, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
        0x6f, 0x6b, 0x2e, 0x74, 0x78, 0x74, 0x68, 0x69,
        0x50, 0x4b, 0x01, 0x02, 0x14, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x83, 0x16, 0xdc, 0x8c, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0b, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x2e, 0x2e, 0x2f, 0x65, 0x76, 0x69, 0x6c, 0x2e, 0x74, 0x78, 0x74,
        0x50, 0x4b, 0x01, 0x02, 0x14, 0x00, 0x14, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xac, 0x2a, 0x93, 0xd8, 0x02, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x06, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x2a, 0x00, 0x00, 0x00,
        0x6f, 0x6b, 0x2e, 0x74, 0x78, 0x74,
        0x50, 0x4b, 0x05, 0x06, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0x02, 0x00, 0x6d, 0x00, 0x00, 0x00,
        0x50, 0x00, 0x00, 0x00, 0x00, 0x00
    };
    snprintf(pack, sizeof(pack), "%s/pack", dir);
    EXPECT(mkdir(pack, 0755) == 0);
    write_file(dir, "pack/a.txt", "hello zip", 9);
    write_file(dir, "slip.zip", slip_zip, sizeof(slip_zip));
    EXPECT(weizhi_set_fs_root(engine, dir) == 0);
    result = weizhi_run_js(engine,
                           "const zip = require('zip');"
                           "const c = zip.createSync('pack', 'out.zip');"
                           "const e = zip.extractSync('out.zip', 'unpacked');"
                           "const text = fs.readFileSync('unpacked/a.txt').toString();"
                           "const slip = zip.extractSync('slip.zip', 'safe');"
                           "const evil = fs.existsSync('evil.txt');"
                           "const ok = fs.readFileSync('safe/ok.txt').toString();"
                           "({files:c.files, entries:e.entries, text, slipEntries:slip.entries,"
                           " slipSkipped:slip.skipped, evil, ok, zipCap: process.weizhiCaps.zip})",
                           5000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"files\":1") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"text\":\"hello zip\"") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"ok\":\"hi\"") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"evil\":false") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"slipSkipped\":1") != NULL);
    EXPECT(result.output_text != NULL && strstr(result.output_text, "\"zipCap\":true") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
}

static void test_image_resize_plugin(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result;
    char plugin_dir[512];
    const char *root = getenv("WEIZHI_BUILD_DIR");
    if (root == NULL) {
        root = "build";
    }
    snprintf(plugin_dir, sizeof(plugin_dir), "%s/plugins", root);
    EXPECT(weizhi_enable_plugin_loader(engine, plugin_dir) == 0);
    result = weizhi_run_js(engine,
                           "const p = await host.ensureNative('image_resize');"
                           "const src = Buffer.from(String.fromCharCode(1,2,3,4,5,6,7,8));"
                           "const out = p.resize_rgba(src, 2, 1, 1);"
                           "out.length",
                           5000);
    EXPECT(result.ok == 1);
    EXPECT(result.output_text != NULL && strcmp(result.output_text, "12") == 0);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

int main(void) {
    test_arithmetic();
    test_engine_survives_syntax_error();
    test_run_js_ex_filename_in_stack();
    test_host_function_receives_json();
    test_log_contains_host_call();
    test_memory_limit();
    test_stack_limit();
    test_timeout();
    test_cancel();
    test_host_runs_on_caller_thread();
    test_reentry_rejected();
    test_second_thread_rejected();
    test_close_while_running_fails();
    test_host_function_limit();
    test_load_script_removed();
    test_relative_import();
    test_promise_await();
    test_buffer_path_require();
    test_fs_sync_and_promises();
    test_fs_mkdir_and_nested_write();
    test_fs_size_limit();
    test_workspace_catalog_module_fallback();
    test_script_fs_roots_isolated();
    test_console_logs();
    test_import_fs();
    test_promise_all_parallel();
    test_async_io_serializes();
    test_agent_precise_errors();
    test_fetch_with_host();
    test_mcp_client();
    test_native_ensure_mock();
    test_typed_plugin_loader();
    test_zlib_roundtrip();
    test_zip_roundtrip();
    test_image_resize_plugin();
    if (g_failed != 0) {
        fprintf(stderr, "%d assertion(s) failed\n", g_failed);
        return 1;
    }
    printf("all passed\n");
    return 0;
}
