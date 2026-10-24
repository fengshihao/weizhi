/*
 * weizhi optional Python runtime (experiment; docs/experiment-python.md).
 *
 * Thin sandbox wrapper around MicroPython's embed port. Semantics mirror the
 * JS engine in src/engine.c:
 *   - fixed GC arena -> memory limit, fixed stack budget -> stack limit
 *   - cooperative timeout/cancel via MICROPY_VM_HOOK_LOOP (mpconfigport.h),
 *     raised as KeyboardInterrupt, then mapped to the same English keywords
 *   - console output goes to the log stream as "console" events, and
 *     output_text stays "" (Python exec has no top-level value)
 *
 * MicroPython VM state is process-global (MP_STATE_*), so runs serialize on
 * one process-wide lock; each weizhi_py_run rebuilds the VM (init -> exec ->
 * deinit) so no state leaks between runs.
 */

#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "py/compile.h"
#include "py/gc.h"
#include "py/mphal.h"
#include "py/runtime.h"
#include "py/stackctrl.h"
#include "port/micropython_embed.h"

#include "weizhi_py.h"
#include "py_internal.h"

#define WEIZHI_PY_ST_IDLE 0
#define WEIZHI_PY_ST_RUNNING 1
#define WEIZHI_PY_ST_CLOSED 2

/* Max buffered bytes of one console line before an explicit flush. */
#define WEIZHI_PY_LINE_MAX 8192

struct WeizhiPy {
    WeizhiPyLimits limits;
    WeizhiPyLogFn log_fn;
    void *log_ud;
    void *heap;
    atomic_int state;
    atomic_int cancel_requested;
    int64_t deadline_ms; /* 0 = no wall-clock limit; written by the run thread */
    int log_seq;
};

/* MicroPython VM state is process-global: serialize every run. */
static pthread_mutex_t g_run_mu = PTHREAD_MUTEX_INITIALIZER;
/* Current run's engine, only set/cleared by the running thread (under g_run_mu). */
static WeizhiPy *g_running;
/* Console line buffer, safe under the same serialization. */
static char g_line[WEIZHI_PY_LINE_MAX];
static size_t g_line_len;

static int64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* ---------------------------------------------------------------- stdout */

static void weizhi_py_flush_stdout(void) {
    WeizhiPy *py = g_running;
    if (g_line_len == 0 || py == NULL) {
        g_line_len = 0;
        return;
    }
    g_line[g_line_len] = '\0';
    if (py->log_fn != NULL) {
        /* {"v":1,"seq":N,"event":"console","data":{"level":"log","message":...}} */
        size_t cap = g_line_len * 6 + 128;
        char *line = malloc(cap);
        if (line != NULL) {
            size_t o = (size_t)snprintf(line, cap, "{\"v\":1,\"seq\":%d,\"event\":\"console\","
                                                  "\"data\":{\"level\":\"log\",\"message\":\"",
                                        ++py->log_seq);
            for (size_t i = 0; i < g_line_len && o + 7 < cap; i++) {
                unsigned char c = (unsigned char)g_line[i];
                if (c == '"' || c == '\\') {
                    line[o++] = '\\';
                    line[o++] = (char)c;
                } else if (c < 0x20) {
                    o += (size_t)snprintf(line + o, cap - o, "\\u%04x", c);
                } else {
                    line[o++] = (char)c;
                }
            }
            o += (size_t)snprintf(line + o, cap - o, "\"}}");
            py->log_fn(line, py->log_ud);
            free(line);
        }
    }
    g_line_len = 0;
}

void weizhi_py_emit_stdout(const char *str, size_t len) {
    for (size_t i = 0; i < len; i++) {
        if (g_line_len + 1 >= WEIZHI_PY_LINE_MAX || str[i] == '\n') {
            weizhi_py_flush_stdout();
            if (str[i] == '\n') {
                continue;
            }
        }
        g_line[g_line_len++] = str[i];
    }
}

/* ------------------------------------------------------------- vm hook */

void weizhi_py_vm_hook(void) {
    WeizhiPy *py = g_running;
    if (py == NULL) {
        return;
    }
    if (atomic_load(&py->cancel_requested)) {
        mp_sched_keyboard_interrupt();
        return;
    }
    if (py->deadline_ms > 0 && now_ms() >= py->deadline_ms) {
        mp_sched_keyboard_interrupt();
    }
}

/* --------------------------------------------------------------- errors */

static void vstr_print_strn(void *data, const char *str, size_t len) {
    vstr_add_strn((vstr_t *)data, str, len);
}

/* Split the formatted traceback: error = last non-empty line (usually
 * "ZeroDivisionError: divide by zero"), error_location = last "File ..." line. */
static void take_exception(WeizhiPy *py, mp_obj_t exc, int cancelled, WeizhiPyResult *result) {
    (void)py;
    if (exc == MP_OBJ_FROM_PTR(&MP_STATE_VM(mp_kbd_exception))) {
        result->error = strdup(cancelled ? "script cancelled" : "script exceeded the timeout limit");
        result->error_location = strdup("");
        return;
    }
    if (mp_obj_is_exception_instance(exc) && mp_obj_get_type(exc) == &mp_type_MemoryError) {
        /* Zero-allocation path: formatting a traceback after OOM may re-raise. */
        result->error = strdup("script exceeded the memory limit");
        result->error_location = strdup("");
        return;
    }
    vstr_t vstr;
    vstr_init(&vstr, 1024);
    mp_print_t print = {&vstr, vstr_print_strn};
    mp_obj_print_exception(&print, exc);
    const char *text = vstr_null_terminated_str(&vstr);
    /* Traceback lines that matter are short; longer ones are truncated. */
    char err_line[256] = "";
    char loc_line[256] = "";
    const char *cursor = text;
    while (*cursor != '\0') {
        const char *eol = strchr(cursor, '\n');
        size_t n = eol != NULL ? (size_t)(eol - cursor) : strlen(cursor);
        const char *next = eol != NULL ? eol + 1 : cursor + n;
        while (n > 0 && (cursor[n - 1] == '\r' || cursor[n - 1] == ' ')) {
            n--;
        }
        if (n > 0) {
            snprintf(err_line, sizeof(err_line), "%.*s", (int)(n > 255 ? 255 : n), cursor);
            if (strncmp(cursor, "  File \"", 8) == 0) {
                snprintf(loc_line, sizeof(loc_line), "%.*s", (int)(n > 255 ? 255 : n), cursor);
            }
        }
        cursor = next;
    }
    if (strstr(text, "maximum recursion depth") != NULL) {
        result->error = strdup("script call stack exceeded the limit");
    } else {
        result->error = strdup(err_line[0] != '\0' ? err_line : "script failed");
    }
    result->error_location = strdup(loc_line);
    vstr_clear(&vstr);
}

/* ----------------------------------------------------------------- api */

WeizhiPy *weizhi_py_open(const WeizhiPyLimits *limits) {
    WeizhiPy *py = calloc(1, sizeof(WeizhiPy));
    if (py == NULL) {
        return NULL;
    }
    py->limits.heap_bytes = limits != NULL && limits->heap_bytes > 0 ? limits->heap_bytes
                                                                    : WEIZHI_PY_DEFAULT_HEAP_BYTES;
    py->limits.stack_bytes = limits != NULL && limits->stack_bytes > 0 ? limits->stack_bytes
                                                                      : WEIZHI_PY_DEFAULT_STACK_BYTES;
    py->heap = malloc(py->limits.heap_bytes);
    if (py->heap == NULL) {
        free(py);
        return NULL;
    }
    atomic_store(&py->state, WEIZHI_PY_ST_IDLE);
    return py;
}

int weizhi_py_close(WeizhiPy *py) {
    if (py == NULL) {
        return 0;
    }
    int expected = WEIZHI_PY_ST_IDLE;
    if (!atomic_compare_exchange_strong(&py->state, &expected, WEIZHI_PY_ST_CLOSED)) {
        return -1;
    }
    free(py->heap);
    free(py);
    return 0;
}

void weizhi_py_set_log(WeizhiPy *py, WeizhiPyLogFn fn, void *userdata) {
    if (py != NULL) {
        py->log_fn = fn;
        py->log_ud = userdata;
    }
}

WeizhiPyResult weizhi_py_run(WeizhiPy *py, const char *source, int timeout_ms) {
    WeizhiPyResult result = {0, NULL, NULL, NULL, 0};
    result.output_text = strdup("");
    if (py == NULL || source == NULL) {
        result.error = strdup(py == NULL ? "missing engine" : "missing source");
        return result;
    }
    int expected = WEIZHI_PY_ST_IDLE;
    if (!atomic_compare_exchange_strong(&py->state, &expected, WEIZHI_PY_ST_RUNNING)) {
        result.error = strdup("engine is busy");
        return result;
    }

    int64_t started = now_ms();
    int64_t timeout = timeout_ms == 0 ? WEIZHI_PY_DEFAULT_TIMEOUT_MS : timeout_ms;
    atomic_store(&py->cancel_requested, 0);
    py->deadline_ms = timeout > 0 ? started + timeout : 0;

    int stack_top;
    pthread_mutex_lock(&g_run_mu);
    g_running = py;
    mp_embed_init(py->heap, py->limits.heap_bytes, &stack_top);
    mp_stack_set_limit(py->limits.stack_bytes);

    nlr_buf_t nlr;
    if (nlr_push(&nlr) == 0) {
        /* Same exec path as mp_embed_exec_str, but the exception is captured
         * instead of printed. */
        mp_lexer_t *lex =
            mp_lexer_new_from_str_len(MP_QSTR__lt_stdin_gt_, source, strlen(source), 0);
        qstr source_name = lex->source_name;
        mp_parse_tree_t parse_tree = mp_parse(lex, MP_PARSE_FILE_INPUT);
        mp_obj_t module_fun = mp_compile(&parse_tree, source_name, true);
        mp_call_function_0(module_fun);
        nlr_pop();
        result.ok = 1;
    } else {
        take_exception(py, MP_OBJ_FROM_PTR(nlr.ret_val),
                       atomic_load(&py->cancel_requested) != 0, &result);
    }
    weizhi_py_flush_stdout();
    mp_embed_deinit();
    g_running = NULL;
    pthread_mutex_unlock(&g_run_mu);

    py->deadline_ms = 0;
    atomic_store(&py->state, WEIZHI_PY_ST_IDLE);
    result.duration_ms = (int)(now_ms() - started);
    return result;
}

void weizhi_py_cancel(WeizhiPy *py) {
    if (py != NULL) {
        atomic_store(&py->cancel_requested, 1);
    }
}

void weizhi_py_result_free(WeizhiPyResult *result) {
    if (result != NULL) {
        free(result->output_text);
        free(result->error);
        free(result->error_location);
        result->output_text = NULL;
        result->error = NULL;
        result->error_location = NULL;
    }
}
