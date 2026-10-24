#ifndef WEIZHI_PY_INTERNAL_H
#define WEIZHI_PY_INTERNAL_H

/* Shared between the weizhi MicroPython port shim and py_engine.c. */

#include <stddef.h>

/* Called from the running script's thread only. Line-buffers console output
 * and emits one "console" log event per line (never into output_text). */
void weizhi_py_emit_stdout(const char *str, size_t len);

#endif
