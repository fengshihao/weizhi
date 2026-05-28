cmake_minimum_required(VERSION 3.16)
project(weizhi C)

add_library(weizhi src/weizhi_stub.c)
target_include_directories(weizhi PUBLIC include)

add_executable(weizhi_tests tests/test_engine.c)
target_link_libraries(weizhi_tests PRIVATE weizhi)
if(NOT APPLE)
    target_link_libraries(weizhi_tests PRIVATE pthread)
endif()
