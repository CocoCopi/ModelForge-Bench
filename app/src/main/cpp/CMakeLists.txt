cmake_minimum_required(VERSION 3.22.1)
project(modelforgebench CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(BENCH_CORE_SOURCES core/gpt.cpp)

if(ANDROID)
    add_library(modelforgebench SHARED
        bench_jni.cpp
        ${BENCH_CORE_SOURCES}
    )
    target_include_directories(modelforgebench PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
    target_compile_options(modelforgebench PRIVATE -O3 -ffast-math -fno-finite-math-only -pthread)
    target_link_libraries(modelforgebench PRIVATE android log)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        target_compile_definitions(modelforgebench PRIVATE MF_ARM64=1)
        set_target_properties(modelforgebench PROPERTIES COMPILE_FLAGS "-march=armv8-a")
    endif()
else()
    enable_testing()
    find_package(Threads REQUIRED)

    function(bench_add_host_test name source)
        add_executable(${name} ${source} ${BENCH_CORE_SOURCES})
        target_include_directories(${name} PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
        target_compile_options(${name} PRIVATE -O2 -ffast-math -fno-finite-math-only -pthread)
        target_link_libraries(${name} PRIVATE Threads::Threads)
        add_test(NAME ${name} COMMAND ${name})
        set_tests_properties(${name} PROPERTIES TIMEOUT 300)
    endfunction()

    bench_add_host_test(test_eval tests/test_eval.cpp)
endif()
