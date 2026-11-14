cmake_minimum_required(VERSION 3.22.1)
project(ptyterm C)

add_library(ptyterm SHARED pty.c)

target_compile_options(ptyterm PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)

find_library(log-lib log)
target_link_libraries(ptyterm PRIVATE ${log-lib})
