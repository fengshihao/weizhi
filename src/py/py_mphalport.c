/*
 * weizhi replacement for the MicroPython embed port's mphalport.c.
 * All MicroPython output (print, internal prints) funnels through
 * mp_hal_stdout_tx_strn_cooked via MP_PLAT_PRINT_STRN, so rerouting it here
 * captures everything into the weizhi log stream — same place console.log
 * goes in the JS engine.
 */

#include "py/mphal.h"

#include "py_internal.h"

void mp_hal_stdout_tx_strn_cooked(const char *str, size_t len) {
    weizhi_py_emit_stdout(str, len);
}

mp_uint_t mp_hal_stdout_tx_strn(const char *str, size_t len) {
    weizhi_py_emit_stdout(str, len);
    return (mp_uint_t)len;
}
