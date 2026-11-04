cmake_minimum_required(VERSION 3.20.0)

# A scratch ztest app that makes the MDF reader's fixtures. It links rt_core from the checkout
# read-only, exactly as rt_core's own tests/unit/domain suite does (same prj.conf, Kconfig and
# board files, copied beside this file): nothing is written outside this folder and its build
# directory.
set(RT_CORE_TESTS_DIR "/home/ubuntu/zephyr/workspace/project/modules/eerie_leap_rt_core/tests")
set(RT_CORE_TEST_SIM_FLASH ON)
include(${RT_CORE_TESTS_DIR}/rt_core_test.cmake)

find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(mdf_fixtures)

target_sources(app PRIVATE src/main.cpp)

# memory_stream_buf.h (a copy of rt_core's tests/common/memory_stream_buf.h)
target_include_directories(app PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
