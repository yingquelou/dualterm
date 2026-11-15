容器模式的运行时资源。这些文件由构建脚本生成，不进 git（见 .gitignore）。

assets/runtime/
  rootfs-arm64-v8a.pack       ← Alpine minirootfs（tar.gz 流，按 ABI 命名）
  rootfs-armeabi-v7a.pack
  rootfs-x86_64.pack

proot 本体和依赖不在 assets，而是作为 jniLibs 打进 APK 的 lib/<abi>/：
  libproot.so          proot 可执行文件（装到 nativeLibraryDir，那里允许 exec）
  libproot_loader.so   静态 ELF 加载器，proot 靠它 mmap + 跳转
  libtalloc2.so        proot 依赖
  libandroid-shmem.so  proot 依赖
之所以放 jniLibs：Android 10+ 对 targetSdk≥29 的应用私有数据目录强制
noexec，exec() 会被 SELinux 拒绝；native lib 目录是唯一允许执行的地方。

生成脚本（工程根目录，构建前跑一次）：
  bash scripts/fetch-runtime.sh <abi>                      ← Linux / macOS / Git Bash
  powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1 [-Abi <abi>]

App 首次进入容器模式时把对应 .pack 解包成镜像 rootfs，写好 DNS 与
绑定目录，之后由 marker 文件（.rootfs-<abi>.ok）走快路径。

缺文件时的表现：
  - 本地 Shell 模式完全正常；
  - 容器模式会弹出明确提示，说明缺哪个文件、如何生成。
