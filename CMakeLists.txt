cmake_minimum_required(VERSION 3.16)
project(weizhi C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

set(QUICKJS_DIR ${CMAKE_SOURCE_DIR}/third_party/quickjs)
set(WAMR_ROOT_DIR ${CMAKE_SOURCE_DIR}/third_party/wamr)
if(NOT EXISTS ${QUICKJS_DIR}/quickjs.h OR NOT EXISTS ${WAMR_ROOT_DIR}/build-scripts/runtime_lib.cmake)
    message(FATAL_ERROR "缺少 third_party，先运行 scripts/fetch-deps.sh")
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Darwin")
    set(WAMR_BUILD_PLATFORM "darwin")
elseif(CMAKE_SYSTEM_NAME STREQUAL "Linux")
    set(WAMR_BUILD_PLATFORM "linux")
else()
    message(FATAL_ERROR "目前只支持 macOS 与 Linux（含 WSL），当前是 ${CMAKE_SYSTEM_NAME}")
endif()
if(CMAKE_SYSTEM_PROCESSOR MATCHES "^(arm64|aarch64)")
    set(WAMR_BUILD_TARGET "AARCH64")
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|amd64)")
    set(WAMR_BUILD_TARGET "X86_64")
else()
    message(FATAL_ERROR "不支持的 CPU: ${CMAKE_SYSTEM_PROCESSOR}")
endif()
set(WAMR_BUILD_INTERP 1)
set(WAMR_BUILD_FAST_INTERP 1)
set(WAMR_BUILD_AOT 0)
set(WAMR_BUILD_JIT 0)
set(WAMR_BUILD_FAST_JIT 0)
set(WAMR_BUILD_LIBC_WASI 0)
set(WAMR_BUILD_LIBC_BUILTIN 1)
set(WAMR_BUILD_SIMD 0)
set(WAMR_BUILD_REF_TYPES 1)
set(WAMR_BUILD_LIB_PTHREAD 0)
include(${WAMR_ROOT_DIR}/build-scripts/runtime_lib.cmake)

add_library(vmlib ${WAMR_RUNTIME_LIB_SOURCE})
find_package(Threads REQUIRED)
target_link_libraries(vmlib PUBLIC m ${CMAKE_DL_LIBS} Threads::Threads)

add_library(quickjs
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/dtoa.c
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
)
target_include_directories(quickjs PUBLIC ${QUICKJS_DIR})
target_compile_definitions(quickjs PUBLIC CONFIG_VERSION="2026-06-04")

add_library(weizhi src/engine.c)
target_include_directories(weizhi PUBLIC include)
target_include_directories(weizhi PRIVATE ${WAMR_ROOT_DIR}/core/iwasm/include)
target_link_libraries(weizhi PUBLIC quickjs vmlib)
target_compile_options(weizhi PRIVATE -Wall -Wextra)

add_executable(weizhi_tests tests/test_engine.c)
target_link_libraries(weizhi_tests PRIVATE weizhi)
