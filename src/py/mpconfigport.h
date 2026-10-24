#ifndef WEIZHI_PY_MPCONFIGPORT_H
#define WEIZHI_PY_MPCONFIGPORT_H

/*
 * weizhi port config for the MicroPython embed build. Same layering trick as
 * micropython's examples/embedding: include the embed port common config,
 * then pick features on top. CMake puts this directory first on the include
 * path so "mpconfigport.h" resolves here, not to the generated port copy.
 */

#include <port/mpconfigport_common.h>

// Minimal starting configuration; optional features are enabled explicitly.
#define MICROPY_CONFIG_ROM_LEVEL (MICROPY_CONFIG_ROM_LEVEL_MINIMUM)

#define MICROPY_ENABLE_COMPILER (1)
#define MICROPY_ENABLE_GC (1)
#define MICROPY_PY_GC (1)
#define MICROPY_PY_SYS (0)
// Real numbers, like CPython (default MINIMUM builds may drop floats).
#define MICROPY_PY_BUILTINS_FLOAT (1)
#define MICROPY_FLOAT_IMPL (MICROPY_FLOAT_IMPL_DOUBLE)

// weizhi cooperative timeout / cancel: runs on every VM loop branch, mirrors
// the QuickJS interrupt handler in src/engine.c. Raises KeyboardInterrupt.
#define MICROPY_KBD_EXCEPTION (1)
void weizhi_py_vm_hook(void);
/* vm.c emits this bare as a statement; the macro carries its own semicolon. */
#define MICROPY_VM_HOOK_LOOP weizhi_py_vm_hook();

// Enforce the stack budget (default off at MINIMUM level: "not checking means
// segfault on overflow"); weizhi_py_run sets the limit from WeizhiPyLimits.
#define MICROPY_STACK_CHECK (1)

#endif
