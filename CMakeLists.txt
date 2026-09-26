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

add_library(weizhi src/engine.c src/node_api.c src/plugin_loader.c src/zip_api.c)
target_include_directories(weizhi PUBLIC include)
target_include_directories(weizhi PRIVATE include src)
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

add_library(image_resize SHARED plugins/image_resize/image_resize.c)
target_include_directories(image_resize PRIVATE include plugins/image_resize/generated)
target_compile_options(image_resize PRIVATE -fvisibility=hidden)
set_target_properties(image_resize PROPERTIES
    OUTPUT_NAME "image_resize"
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/plugins/image_resize"
    RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/plugins/image_resize"
    PREFIX "lib"
)
add_custom_command(TARGET image_resize POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        "${CMAKE_SOURCE_DIR}/plugins/image_resize/generated/manifest.json"
        "$<TARGET_FILE_DIR:image_resize>/manifest.json"
)

# Android cross-build artifacts can be adb-pushed to a device; they cannot run on the host.
add_executable(weizhi_tests tests/test_engine.c)
target_link_libraries(weizhi_tests PRIVATE weizhi)
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
