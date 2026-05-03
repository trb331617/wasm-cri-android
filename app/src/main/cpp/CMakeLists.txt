cmake_minimum_required(VERSION 3.22.1)
project(uds_helper C)

add_library(uds_helper SHARED uds_helper.c)

# Android NDK already provides libc / log / dl in the sysroot.
target_link_libraries(uds_helper log)
