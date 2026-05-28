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

/* 同上，但声明 40 页内存（2.5MB），超过 2MB 上限。内存段必须排在导出段前面。 */
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
    weizhi_set_run_id(engine, "对话1");
    weizhi_set_log(engine, on_log, &buf);
    EXPECT(weizhi_add_function(engine, "echo", echo_host, NULL) == 0);
    result = weizhi_run_js(engine, "echo({\"a\":1})", 1000);
    EXPECT(result.ok == 1);
    EXPECT(buf.data != NULL && strstr(buf.data, "\"run_id\":\"对话1\"") != NULL);
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
    EXPECT(result.error != NULL && strstr(result.error, "内存") != NULL);
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
    EXPECT(result.error != NULL && strstr(result.error, "栈") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
}

static void test_timeout(void) {
    WeizhiEngine *engine = weizhi_open(NULL);
    WeizhiResult result = weizhi_run_js(engine, "while(true){}", 200);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "时间") != NULL);
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
    EXPECT(g_reentry.error != NULL && strstr(g_reentry.error, "再次") != NULL);
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
            sync->second.error = must_dup("宿主函数没有被调用");
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
    EXPECT(sync.second.error != NULL && strstr(sync.second.error, "正忙") != NULL);
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
        /* 默认上限在另一台发动机上单独数，避免和上面的 2 混在一起。 */
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
    EXPECT(result.error != NULL && strstr(result.error, "找不到") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(engine, "loadPack(\"../add\")", 1000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "名字") != NULL);
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
    EXPECT(result.error != NULL && strstr(result.error, "内存") != NULL);
    weizhi_result_free(&result);
    result = weizhi_run_js(
        engine,
        "var a = loadPack(\"add\"); var b = loadPack(\"add\"); var c = loadPack(\"add\"); 1",
        2000);
    EXPECT(result.ok == 0);
    EXPECT(result.error != NULL && strstr(result.error, "太多") != NULL);
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
    EXPECT(result.error != NULL && strstr(result.error, "名字") != NULL);
    weizhi_result_free(&result);
    weizhi_close(engine);
    free(dir);
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
    if (g_failed != 0) {
        fprintf(stderr, "%d 个断言失败\n", g_failed);
        return 1;
    }
    printf("全部通过\n");
    return 0;
}
