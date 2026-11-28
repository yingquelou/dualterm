cmake_minimum_required(VERSION 3.22.1)
project(ptyterm C)

add_library(ptyterm SHARED pty.c)

target_compile_options(ptyterm PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)

# Android 15+ 有 16KB 内存页设备：ELF 的 PT_LOAD 必须按 16KB 对齐，否则
# 加载 libptyterm.so 会直接失败。NDK r28 默认已按 16KB 链接，这里再显式钉死，
# 保证换回旧 NDK / 换工具链时也不退化（外部 proot 运行时已是 16KB 对齐）。
target_link_options(ptyterm PRIVATE
        -Wl,-z,max-page-size=16384
        -Wl,-z,common-page-size=16384)

find_library(log-lib log)
target_link_libraries(ptyterm PRIVATE ${log-lib})
