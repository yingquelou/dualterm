容器模式的运行时资源放在这个目录，文件名必须严格匹配：

  runtime/
    proot-arm64-v8a          ← PRoot 可执行文件（按 ABI 命名）
    proot-armeabi-v7a
    proot-x86_64
    rootfs-arm64-v8a.tar.gz  ← 容器根文件系统（Alpine / Debian minirootfs 均可）
    rootfs-armeabi-v7a.tar.gz
    rootfs-x86_64.tar.gz
    lib/                     ← 可选：proot 依赖的 .so（如 libtalloc.so）

不想手动准备的话，在工程根目录跑：

  powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1

App 首次进入容器模式时会把这些文件拷到
  /data/data/dev.dualterm/files/container/
然后 chmod +x（assets 里的文件本身没有执行权限，必须落盘）。

没有放这些文件时：
  - 本地 Shell 模式完全正常；
  - 容器模式会弹出明确提示，告诉你缺哪个文件。
