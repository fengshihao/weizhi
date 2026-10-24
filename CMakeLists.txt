cmake_minimum_required(VERSION 3.16)
project(weizhi C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_C_EXTENSIONS ON)

option(WEIZHI_SANITIZE "Enable ASan+UBSan for memory errors and UB (desktop debug only)" OFF)
if(WEIZHI_SANITIZE)
    if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")
        message(FATAL_ERROR "WEIZHI_SANITIZE is for desktop debug builds only")
    endif()
    # Sanitize only weizhi and tests; QuickJS upstream is too noisy to sanitize.
    set(WEIZHI_SANITIZE_FLAGS -fsanitize=address,undefined -fno-omit-frame-pointer -g -O1)
    message(STATUS "WEIZHI_SANITIZE=ON (weizhi / weizhi_tests / weizhijni only)")
endif()

set(QUICKJS_DIR ${CMAKE_SOURCE_DIR}/third_party/quickjs)
if(NOT EXISTS ${QUICKJS_DIR}/quickjs.h)
    message(FATAL_ERROR "Missing third_party/quickjs; run scripts/fetch-deps.sh first")
endif()

if(NOT (ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android" OR
        CMAKE_SYSTEM_NAME STREQUAL "Darwin" OR CMAKE_SYSTEM_NAME STREQUAL "Linux"))
    message(FATAL_ERROR "Only macOS, Linux (incl. WSL), and Android NDK are supported; got ${CMAKE_SYSTEM_NAME}")
endif()

find_package(Threads REQUIRED)

add_library(quickjs
    ${QUICKJS_DIR}/quickjs.c
    ${QUICKJS_DIR}/dtoa.c
    ${QUICKJS_DIR}/cutils.c
    ${QUICKJS_DIR}/libregexp.c
    ${QUICKJS_DIR}/libunicode.c
)
target_include_directories(quickjs PUBLIC ${QUICKJS_DIR})
target_compile_definitions(quickjs PUBLIC CONFIG_VERSION="2026-06-04")

file(READ "${CMAKE_SOURCE_DIR}/src/mcp_client.js" WEIZHI_MCP_JS_RAW)
string(REPLACE "\\" "\\\\" WEIZHI_MCP_JS "${WEIZHI_MCP_JS_RAW}")
string(REPLACE "\"" "\\\"" WEIZHI_MCP_JS "${WEIZHI_MCP_JS}")
string(REPLACE "\n" "\\n\"\n\"" WEIZHI_MCP_JS "${WEIZHI_MCP_JS}")
set(WEIZHI_MCP_JS "\"${WEIZHI_MCP_JS}\"")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_SOURCE_DIR}/src/mcp_client.js")
configure_file(src/mcp_client_js.h.in "${CMAKE_BINARY_DIR}/mcp_client_js.h" @ONLY)

# Embed the weizhi:video package source for tests (single source of truth:
# shipped inside the :weizhi-video AAR assets so hosts get SO + JS atomically).
file(READ "${CMAKE_SOURCE_DIR}/android/weizhi-video/src/main/assets/weizhi-packages/video@0.4.0/index.js" WEIZHI_VIDEO_PKG_JS_RAW)
string(REPLACE "\\" "\\\\" WEIZHI_VIDEO_PKG_JS "${WEIZHI_VIDEO_PKG_JS_RAW}")
string(REPLACE "\"" "\\\"" WEIZHI_VIDEO_PKG_JS "${WEIZHI_VIDEO_PKG_JS}")
string(REPLACE "\n" "\\n\"\n\"" WEIZHI_VIDEO_PKG_JS "${WEIZHI_VIDEO_PKG_JS}")
set(WEIZHI_VIDEO_PKG_JS "\"${WEIZHI_VIDEO_PKG_JS}\"")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_SOURCE_DIR}/android/weizhi-video/src/main/assets/weizhi-packages/video@0.4.0/index.js")
configure_file(src/weizhi_video_pkg_js.h.in "${CMAKE_BINARY_DIR}/weizhi_video_pkg_js.h" @ONLY)

add_library(weizhi src/engine.c src/node_api.c src/package.c src/plugin_loader.c src/zip_api.c src/sha256.c)
target_include_directories(weizhi PUBLIC include)
target_include_directories(weizhi PRIVATE include src "${CMAKE_BINARY_DIR}")
target_link_libraries(weizhi PUBLIC quickjs Threads::Threads z)
if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")
    target_link_libraries(weizhi PUBLIC log android ${CMAKE_DL_LIBS})
else()
    target_link_libraries(weizhi PUBLIC m ${CMAKE_DL_LIBS})
endif()
target_compile_options(weizhi PRIVATE -Wall -Wextra)
if(WEIZHI_SANITIZE)
    target_compile_options(weizhi PRIVATE ${WEIZHI_SANITIZE_FLAGS})
    target_link_options(weizhi PUBLIC ${WEIZHI_SANITIZE_FLAGS})
endif()

# Sample typed plugin (IDL path).
add_library(echo_math SHARED plugins/echo_math/echo_math.c)
target_include_directories(echo_math PRIVATE include plugins/echo_math/generated)
target_compile_options(echo_math PRIVATE -fvisibility=hidden)
set_target_properties(echo_math PROPERTIES
    OUTPUT_NAME "echo_math"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/plugins/echo_math"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/plugins/echo_math"
    PREFIX "lib"
)
add_custom_command(TARGET echo_math POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${CMAKE_SOURCE_DIR}/plugins/echo_math/generated/manifest.json"
        "$<TARGET_FILE_DIR:echo_math>/manifest.json"
)

# IDL v2 channel PoC for the video pipeline (docs/video-architecture.md P0).
add_library(echo_video SHARED plugins/echo_video/echo_video.c)
target_include_directories(echo_video PRIVATE include plugins/echo_video/generated)
target_compile_options(echo_video PRIVATE -fvisibility=hidden)
set_target_properties(echo_video PROPERTIES
    OUTPUT_NAME "echo_video"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/plugins/echo_video"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/plugins/echo_video"
    PREFIX "lib"
)
add_custom_command(TARGET echo_video POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${CMAKE_SOURCE_DIR}/plugins/echo_video/generated/manifest.json"
        "$<TARGET_FILE_DIR:echo_video>/manifest.json"
)

# Real media plugin: NDK media stack only exists on Android (docs/video-architecture.md §2.1).
# Bundled delivery via the :weizhi-video AAR; desktop builds skip this target entirely.
if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")
    add_library(weizhi_video SHARED
        plugins/weizhi_video/weizhi_video.c
        plugins/weizhi_video/wjson.c
    )
    target_include_directories(weizhi_video PRIVATE include plugins/weizhi_video/generated)
    target_compile_options(weizhi_video PRIVATE -fvisibility=hidden)
    target_link_libraries(weizhi_video mediandk)
    set_target_properties(weizhi_video PROPERTIES
        OUTPUT_NAME "weizhivideo"
        PREFIX "lib"
    )

    # P2: re-encoding export (decoder -> encoder surface tunnel -> muxer).
    add_library(weizhi_transcode SHARED
        plugins/weizhi_transcode/weizhi_transcode.c
        plugins/weizhi_transcode/wtjson.c
    )
    target_include_directories(weizhi_transcode PRIVATE include plugins/weizhi_transcode/generated)
    target_compile_options(weizhi_transcode PRIVATE -fvisibility=hidden)
    target_link_libraries(weizhi_transcode mediandk android log EGL GLESv2)
    set_target_properties(weizhi_transcode PROPERTIES
        OUTPUT_NAME "weizhitranscode"
        PREFIX "lib"
    )
endif()

# Android cross-build artifacts can be adb-pushed to a device; they cannot run on the host.
add_executable(weizhi_tests tests/test_engine.c)
target_link_libraries(weizhi_tests PRIVATE weizhi)
target_include_directories(weizhi_tests PRIVATE "${CMAKE_BINARY_DIR}")
if(WEIZHI_SANITIZE)
    target_compile_options(weizhi_tests PRIVATE ${WEIZHI_SANITIZE_FLAGS})
    target_link_options(weizhi_tests PRIVATE ${WEIZHI_SANITIZE_FLAGS})
endif()

# Android always builds JNI. Desktop builds it when a JDK is available (for host smoke tests).
option(WEIZHI_BUILD_JNI "Build libweizhijni (auto on Android / when JDK found)" ON)
if(ANDROID OR CMAKE_SYSTEM_NAME STREQUAL "Android")
    add_library(weizhijni SHARED jni/weizhi_jni.c)
    target_include_directories(weizhijni PRIVATE include)
    target_link_libraries(weizhijni PRIVATE weizhi log android)
elseif(WEIZHI_BUILD_JNI)
    find_package(JNI)
    if(JNI_FOUND)
        if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
            set_target_properties(quickjs weizhi PROPERTIES POSITION_INDEPENDENT_CODE ON)
        endif()
        add_library(weizhijni SHARED jni/weizhi_jni.c)
        target_include_directories(weizhijni PRIVATE include ${JNI_INCLUDE_DIRS})
        target_link_libraries(weizhijni PRIVATE weizhi)
        if(WEIZHI_SANITIZE)
            target_compile_options(weizhijni PRIVATE ${WEIZHI_SANITIZE_FLAGS})
            target_link_options(weizhijni PRIVATE ${WEIZHI_SANITIZE_FLAGS})
        endif()
        message(STATUS "Building libweizhijni (desktop JNI smoke)")
    else()
        message(STATUS "JDK/JNI not found; skip libweizhijni (set JAVA_HOME to enable)")
    endif()
endif()
