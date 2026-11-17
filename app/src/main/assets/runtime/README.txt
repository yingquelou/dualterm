容器模式的运行时资源目录。rootfs pack 不再进包（体积优化，2026-11）：
镜像由应用内 docker pull（Alpine 官方源）或导入 docker save / export
的 tar 获得。只有显式 WITH_ROOTFS=1 跑 fetch 脚本做离线调试时，这里才
会有 rootfs-<abi>.pack（同样不进 git，见 .gitignore）。

proot 本体和依赖不在 assets，而是作为 jniLibs 打进 APK 的 lib/<abi>/：
  libproot.so          proot 可执行文件（装到 nativeLibraryDir，那里允许 exec）
  libproot_loader.so   静态 ELF 加载器，proot 靠它 mmap + 跳转
  libtalloc2.so        proot 依赖
  libandroid-shmem.so  proot 依赖
之所以放 jniLibs：Android 10+ 对 targetSdk≥29 的应用私有数据目录强制
noexec，exec() 会被 SELinux 拒绝；native lib 目录是唯一允许执行的地方。

生成脚本（工程根目录，构建前跑一次，只产出 proot 部分）：
  bash scripts/fetch-runtime.sh <abi>                      ← Linux / macOS / Git Bash
  powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1 [-Abi <abi>]
  WITH_ROOTFS=1 时额外下载 rootfs pack（仅供离线调试，正常发布不需要）

缺 proot 时的表现：
  - 本地 Shell 模式完全正常；
  - 容器模式会弹出明确提示，说明缺哪个文件、如何生成。
