#ifndef WEIZHI_PY_H
#define WEIZHI_PY_H

/*
 * Weizhi optional Python runtime (experiment; see docs/experiment-python.md).
 * Separate SO from the JS engine: no dependency on libweizhi / QuickJS.
 * Semantics mirror include/weizhi.h: limits are immutable after open, errors
 * carry English keywords (cancelled / timeout / memory / stack), console
 * output goes to the log stream as "console" events, never into output_text.
 *
 * MicroPython is a Python 3 subset and its VM state is process-global, so all
 * weizhi_py_run calls (across engines) are serialized with one global lock.
 */

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  define WEIZHIPY_EXPORT __declspec(dllexport)
#else
#  define WEIZHIPY_EXPORT __attribute__((visibility("default")))
#endif

/* Default limits. Tests assert these; change numbers and tests together. */
#define WEIZHI_PY_DEFAULT_HEAP_BYTES (32u * 1024u * 1024u)
#define WEIZHI_PY_DEFAULT_STACK_BYTES (256u * 1024u)
#define WEIZHI_PY_DEFAULT_TIMEOUT_MS (10 * 60 * 1000)

typedef struct WeizhiPy WeizhiPy;

/* Pass to weizhi_py_open. 0 = use defaults above. Immutable after open. */
typedef struct WeizhiPyLimits {
    size_t heap_bytes;   /* MicroPython GC arena. Default 32MB. Over -> "memory" */
    size_t stack_bytes;  /* VM stack budget. Default 256KB. Over -> "stack" */
} WeizhiPyLimits;

typedef struct WeizhiPyResult {
    int ok;
    char *output_text;   /* PoC: always "" (Python exec has no top-level value). */
    char *error;         /* English reason; keyword first when mapped (see above). */
    char *error_location;/* Last traceback "File ..." line, or "" when none. */
    int duration_ms;
} WeizhiPyResult;

/* One JSON log line per event, same shape as the JS engine:
 * {"v":1,"seq":N,"event":"console","data":{"level":"log|error","message":"..."}} */
typedef void (*WeizhiPyLogFn)(const char *line, void *userdata);

WEIZHIPY_EXPORT WeizhiPy *weizhi_py_open(const WeizhiPyLimits *limits);
/* Returns -1 while a script is running; engine stays usable. 0 on close. */
WEIZHIPY_EXPORT int weizhi_py_close(WeizhiPy *py);
WEIZHIPY_EXPORT void weizhi_py_set_log(WeizhiPy *py, WeizhiPyLogFn fn, void *userdata);
/* timeout_ms 0 uses the default (10 minutes). Negative means no wall-clock limit. */
WEIZHIPY_EXPORT WeizhiPyResult weizhi_py_run(WeizhiPy *py, const char *source, int timeout_ms);
/* Abort the in-flight run. Safe from another thread. Idle engines ignore it. */
WEIZHIPY_EXPORT void weizhi_py_cancel(WeizhiPy *py);
WEIZHIPY_EXPORT void weizhi_py_result_free(WeizhiPyResult *result);

#ifdef __cplusplus
}
#endif

#endif
