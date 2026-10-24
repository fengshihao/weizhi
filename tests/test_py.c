/* Desktop tests for the optional Python runtime (experiment). Same style as
 * test_engine.c: hand-rolled EXPECT + a failure counter, never aborts.
 * Run: ./build/weizhi_py_tests (built by CMake when micropython embed sources
 * are present; see scripts/build-python-embed.sh). */

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "weizhi_py.h"

static int g_failed = 0;
#define EXPECT(cond)                                                                  \
    do {                                                                              \
        if (!(cond)) {                                                                \
            fprintf(stderr, "FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond);           \
            g_failed++;                                                               \
        }                                                                             \
    } while (0)

typedef struct {
    char buf[16384];
    size_t len;
} LogBuf;

static void on_log(const char *line, void *ud) {
    LogBuf *lb = ud;
    if (lb->len + strlen(line) + 1 < sizeof(lb->buf)) {
        memcpy(lb->buf + lb->len, line, strlen(line));
        lb->len += strlen(line);
        lb->buf[lb->len] = '\0';
    }
}

typedef struct {
    WeizhiPy *py;
    const char *src;
    int timeout_ms;
    WeizhiPyResult r;
} RunJob;

static void *run_thread(void *p) {
    RunJob *j = p;
    j->r = weizhi_py_run(j->py, j->src, j->timeout_ms);
    return NULL;
}

static void test_basic_print(void) {
    LogBuf lb = {0};
    WeizhiPy *py = weizhi_py_open(NULL);
    weizhi_py_set_log(py, on_log, &lb);
    WeizhiPyResult r = weizhi_py_run(py, "print('hello', 1)\nprint('world')", 10000);
    EXPECT(r.ok == 1);
    EXPECT(r.output_text != NULL && strcmp(r.output_text, "") == 0);
    EXPECT(r.error == NULL);
    EXPECT(strstr(lb.buf, "\"event\":\"console\"") != NULL);
    EXPECT(strstr(lb.buf, "\"message\":\"hello 1\"") != NULL);
    EXPECT(strstr(lb.buf, "\"message\":\"world\"") != NULL);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_no_log_fn_drops_output(void) {
    /* Without a log callback console output is dropped, not crashed on. */
    WeizhiPy *py = weizhi_py_open(NULL);
    WeizhiPyResult r = weizhi_py_run(py, "print('nobody listens')", 10000);
    EXPECT(r.ok == 1);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_print_end_flush(void) {
    LogBuf lb = {0};
    WeizhiPy *py = weizhi_py_open(NULL);
    weizhi_py_set_log(py, on_log, &lb);
    WeizhiPyResult r = weizhi_py_run(py, "print('tail', end='')", 10000);
    EXPECT(r.ok == 1);
    EXPECT(strstr(lb.buf, "tail") != NULL); /* partial line flushed at run end */
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_arithmetic(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    WeizhiPyResult r = weizhi_py_run(
        py,
        "def add(a, b):\n"
        "    return a + b\n"
        "x = add(19, 23)\n"
        "try:\n"
        "    1 // 0\n"
        "except ZeroDivisionError:\n"
        "    x = x * 2\n",
        10000);
    EXPECT(r.ok == 1);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_no_state_between_runs(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    WeizhiPyResult r = weizhi_py_run(py, "leak = 41", 10000);
    EXPECT(r.ok == 1);
    weizhi_py_result_free(&r);
    /* Every run rebuilds the VM; globals must not leak across runs. */
    r = weizhi_py_run(py, "print(leak)", 10000);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL && strstr(r.error, "NameError") != NULL);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_uncaught_exception(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    WeizhiPyResult r = weizhi_py_run(py, "x = 1\ny = x // 0\n", 10000);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL && strstr(r.error, "ZeroDivisionError") != NULL);
    EXPECT(r.error_location != NULL && strstr(r.error_location, "File") != NULL);
    EXPECT(r.duration_ms >= 0);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_syntax_error(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    WeizhiPyResult r = weizhi_py_run(py, "def f(:\n    pass\n", 10000);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL && strstr(r.error, "SyntaxError") != NULL);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_engine_reusable_after_error(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    WeizhiPyResult r = weizhi_py_run(py, "1 // 0", 10000);
    EXPECT(r.ok == 0);
    weizhi_py_result_free(&r);
    r = weizhi_py_run(py, "6 * 7", 10000);
    EXPECT(r.ok == 1);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_timeout(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    WeizhiPyResult r = weizhi_py_run(py, "while True:\n    pass\n", 200);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL && strstr(r.error, "timeout") != NULL);
    EXPECT(r.duration_ms < 2000);
    /* Engine stays usable after a timeout. */
    weizhi_py_result_free(&r);
    r = weizhi_py_run(py, "42", 10000);
    EXPECT(r.ok == 1);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_cancel(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    RunJob job = {py, "while True:\n    pass\n", -1, {0}};
    pthread_t th;
    pthread_create(&th, NULL, run_thread, &job);
    usleep(200 * 1000);
    weizhi_py_cancel(py);
    pthread_join(th, NULL);
    EXPECT(job.r.ok == 0);
    EXPECT(job.r.error != NULL && strstr(job.r.error, "cancelled") != NULL);
    weizhi_py_result_free(&job.r);
    weizhi_py_close(py);
}

static void test_busy(void) {
    WeizhiPy *py = weizhi_py_open(NULL);
    RunJob job = {py, "while True:\n    pass\n", -1, {0}};
    pthread_t th;
    pthread_create(&th, NULL, run_thread, &job);
    usleep(200 * 1000);
    WeizhiPyResult r = weizhi_py_run(py, "1", 1000); /* second run on same engine */
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL && strstr(r.error, "busy") != NULL);
    weizhi_py_result_free(&r);
    EXPECT(weizhi_py_close(py) == -1); /* close while running is refused */
    weizhi_py_cancel(py);
    pthread_join(th, NULL);
    weizhi_py_result_free(&job.r);
    EXPECT(weizhi_py_close(py) == 0);
}

static void test_memory_limit(void) {
    WeizhiPyLimits limits = {512u * 1024u, 0};
    WeizhiPy *py = weizhi_py_open(&limits);
    WeizhiPyResult r = weizhi_py_run(py, "big = list(range(200000))\n", 30000);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL && strstr(r.error, "memory") != NULL);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_stack_limit(void) {
    WeizhiPyLimits limits = {0, 128u * 1024u};
    WeizhiPy *py = weizhi_py_open(&limits);
    WeizhiPyResult r = weizhi_py_run(
        py, "def f():\n    return f()\nf()\n", 30000);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL && strstr(r.error, "stack") != NULL);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

static void test_missing_inputs(void) {
    WeizhiPyResult r = weizhi_py_run(NULL, "1", 1000);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL);
    weizhi_py_result_free(&r);
    WeizhiPy *py = weizhi_py_open(NULL);
    r = weizhi_py_run(py, NULL, 1000);
    EXPECT(r.ok == 0);
    EXPECT(r.error != NULL);
    weizhi_py_result_free(&r);
    weizhi_py_close(py);
}

int main(void) {
    test_basic_print();
    test_no_log_fn_drops_output();
    test_print_end_flush();
    test_arithmetic();
    test_no_state_between_runs();
    test_uncaught_exception();
    test_syntax_error();
    test_engine_reusable_after_error();
    test_timeout();
    test_cancel();
    test_busy();
    test_memory_limit();
    test_stack_limit();
    test_missing_inputs();
    if (g_failed != 0) {
        fprintf(stderr, "%d py test(s) failed\n", g_failed);
        return 1;
    }
    printf("all py tests passed\n");
    return 0;
}
