# DualTerm · 双态终端

一个 APK，两种终端：

| 模式 | 跑在哪 | 隔离级别 | 需要什么 |
|---|---|---|---|
| **本地** | Android 自己的 shell（`/system/bin/sh`） | 无，就是应用沙箱身份 `u0_aXXX` | 装完即用；可选 `su` 提权 |
| **容器** | 独立 rootfs（Alpine），PRoot 用户态重定向 | 独立文件系统 + 假 root，**不是内核级隔离** | 首次进入自动解包；或跑一次 `scripts/fetch-runtime.ps1` |

两种会话各自常驻，来回切换不丢现场。

---

## 先说实话：Docker 这件事

你原话是"运行在 docker 容器中"。我没把它做成默认，原因不在 App 代码，而在内核：

- Docker Engine 需要 **namespace + cgroup v1/v2 + overlayfs**，跑 `dockerd` 还要 root。
- 绝大多数手机厂商内核 **没开 `CONFIG_NAMESPACES`**，`dockerd` 起不来。
- 所以真机上"能装 Docker"的机器极少（通常是刷了自定义内核的机型，或在 Termux 里带 root 玩）。

因此工程里做了**后端抽象**，两个实现：

1. `ProotRuntime`（默认，已验证路线）：PRoot 是纯用户态方案 —— 通过 `ptrace` 拦截系统调用、重定向路径，给容器一个完全独立的 `/`。能 `apk add` / `apt install`、能写 `/etc`、容器内是 `uid=0`，但**和 Android 共内核**，看不到独立 `/proc`、没有独立网络栈。
2. `DockerRuntime`（实验性开关）：探测 `su` → 探测 `dockerd` → 拉起 `dockerd` 再 `docker run -it`。**探测失败会明确报错并让你切回 PRoot**，不会假装能用。

菜单里 `容器运行时：PRoot / Docker` 就是切这个。要真 Docker 支持，正确的下一步是编译进一个带 namespace 的内核，那是 ROM 层面的事。

---

## 构建

环境：**JDK 17** + Android SDK（compileSdk 34）+ NDK + CMake。

```bash
# 1. 打开工程
#    Android Studio → Open → dualterm/  （首次 Sync 会让你装 NDK + CMake，同意即可）

# 2. 准备容器运行时（只影响容器模式；不跑也能编译，本地模式照样用）
powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1
#    → proot/loader/依赖库 落进 app/src/main/jniLibs/<abi>/
#    → Alpine minirootfs 落进 app/src/main/assets/runtime/rootfs-<abi>.pack

# 3. 出包
gradlew assembleDebug        # 若没有 wrapper，Android Studio 会生成，或 gradle wrapper
#    产物：app/build/outputs/apk/debug/app-debug.apk
```

命令行首次构建没有 wrapper 时：

```bash
gradle wrapper --gradle-version 8.2
./gradlew assembleDebug
```

**零第三方依赖**：没有 AndroidX、没有 Kotlin、没有 commons-compress。只有 Framework API + 一个 200 行的 C 文件 + 手写 tar.gz 解包器。这样做的目的是让构建链尽可能不炸。

---

## 工程结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   └── pty.c                     ← openpty + fork + setsid + TIOCSCTTY + execvp
├── jniLibs/<abi>/                ← 脚本生成：libproot.so / libproot_loader.so / 依赖库
├── assets/runtime/               ← 脚本生成：rootfs-<abi>.pack
├── java/dev/dualterm/
│   ├── NativePty.java            ← JNI 声明：forkExec / read / write / setWindowSize / signal
│   ├── TermSession.java          ← PTY 会话：读线程 + 1 号线程 waitPid 回收
│   ├── TerminalEmulator.java     ← VT100/xterm 屏模型（CSI/SGR/滚动区/备用屏/scrollback）
│   ├── TerminalView.java         ← 渲染 + 触摸滚动 + 自实现 InputConnection
│   ├── MainActivity.java         ← 双模式切换、功能键行、菜单
│   ├── Shells.java               ← 模式 A：/system/bin/sh（含 su 探测）
│   ├── ContainerRuntime.java     ← 容器后端抽象
│   ├── ProotRuntime.java         ← 模式 B：proot 参数组装（按容器 rootfs）
│   ├── DockerRuntime.java        ← 模式 B 备选：su + dockerd（实验）
│   ├── Containers.java           ← 镜像/容器注册表、物化复制、pull
│   ├── ConsoleActivity.java      ← 容器管理控制台（docker 语法的子集，App 内执行）
│   ├── DockerCli.java            ← 控制台背后的命令实现：ps/images/run/rm/rmi/pull…
│   ├── ContainerBootstrap.java   ← rootfs/proot 落盘、解包、resolv.conf
│   └── TarGzExtractor.java       ← ustar + pax + GNU 长文件名
└── res/…
```

### 关键实现点

**PTY**：`openpty()` 拿一对 master/slave → `fork` → 子进程 `setsid` + `ioctl(TIOCSCTTY)` 抢到控制终端 → `dup2` 到 0/1/2 → `execvp`。父进程只留 master fd，读线程阻塞在 `read()` 上。这样 `vi`、`top`、`less`、Tab 补全、Ctrl-C 全都正常——用 `Runtime.exec` + 管道是做不到的。

**窗口尺寸**：`onSizeChanged` → 算出行列 → `ioctl(TIOCSWINSZ)`，不然全屏 TUI 会按 80x24 画歪。

**渲染**：把"同色同属性的连续单元格"合成一次 `drawText`，而不是每格一次。宽字符（CJK）占两格、第二格用码点 0 占位。

**输入**：自己实现 `InputConnection`——中文输入法的预编辑串（`setComposingText`）不进 PTY，只有 `commitText` 才发；退格走 `deleteSurroundingText` → `0x7f`。

**容器启停**（`ProotRuntime`）：

```
proot --kill-on-exit --link2symlink -0 -r <rootfs> \
      -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random \
      -b /proc/self/fd:/dev/fd -b <files>/container/home:/root -b /sdcard \
      -w /root /usr/bin/env -i HOME=/root PATH=… TERM=xterm-256color /bin/sh -l
```

`-0` 是假 root（容器内 uid=0）；`--link2symlink` 让 `apk` 的硬链接在 SELinux 下也能过。

**容器能跑起来的三个 Android 专属前提**（这三条是实机踩出来的，任何一条不满足都会失败）：

| 前提 | 不满足时的现象 | 做法 |
|---|---|---|
| proot 必须能被 `exec` | 会话秒退，终端刷出 `无法执行 … Permission denied` 或直接 `code=127` | 打进 `jniLibs`，重命名为 `libproot.so`，安装后落在 `nativeLibraryDir` |
| `extractNativeLibs` 必须为 true | `nativeLibraryDir` 是空目录（`total 0`），proot 根本不存在 | `packagingOptions { jniLibs { useLegacyPackaging true } }` |
| rootfs 内的程序要借 proot 的 loader 启动 | proot 已启动但立刻 `status=1`，容器内程序起不来 | `PROOT_LOADER=<nativeLibDir>/libproot_loader.so` |

原因是 `targetSdk ≥ 29` 时应用私有数据目录被强制 **noexec**（SELinux `untrusted_app` 对 `app_data_file` 无 execute 权限），`execve` 与 `dlopen` 都会被拒。Termux 可以直接在 `$PREFIX` 里跑，是因为它的 targetSdk 还是 28。

还有一个坑：AGP 只打包匹配 `lib*.so` 的文件，`libtalloc.so.2` 会被**静默丢弃**（构建成功、运行期缺库）。脚本因此把 proot 的 `DT_NEEDED` 字符串原位改写成 `libtalloc2.so`（更短，NUL 补齐），库文件随之改名。

**远程调试入口**（无需触屏，便于无人值守验证）：

```bash
# 启动并自动敲入一条命令，输出同时打到 logcat（tag: DualTerm）
adb shell 'am start -n dev.dualterm/.MainActivity --es mode container --es cmd "cat /etc/os-release; id"'
adb logcat -s DualTerm
```

---

## 用法

- 顶部 `本地` / `容器` 切模式；`⌨` 手动唤键盘；`⋮` 菜单。
- 底部功能键行：ESC TAB CTRL ALT 方向键 HOME END PGUP PGDN `^C ^D ^Z` 以及 `/ - | ~ \`。CTRL/ALT 是**一次性修饰**，作用于下一个按键，亮绿色表示生效中。
- 上下拖动看 scrollback（3000 行）；点一下回到光标并弹键盘。
- 菜单里：重启会话 / 复制全部输出 / 粘贴 / 清屏 / 字号 / Root Shell / 容器运行时 / 容器管理 / 重建当前容器 / 绑定目录 / 存储权限 / 全屏 / 关于。

容器里装东西（需要联网）：

```sh
apk update && apk add python3 git vim
```

---

## 容器与镜像管理（App 内控制台）

镜像只是**模板**，容器从镜像物化出一份可写 rootfs，之后互不影响 —— 和 docker 的心智模型一致。
没有真 dockerd 也照样能管，因为这一层是 App 自己实现的。

入口有两个，能力完全一样：

- 菜单 → **容器管理**：点着做（新建 / 启动并切换 / 停止 / 删除 / 拉镜像 / 导入 tar / 删镜像）
- 菜单 → **容器管理控制台**：习惯命令行的用这个

```sh
images                     # 列出镜像
pull 3.21                  # 从 Alpine 官方源拉一个 minirootfs 当镜像
run --name web alpine:3.21 # 用镜像创建容器（不接管终端，进容器在 UI 里点）
ps                         # 运行中的容器
ps -a                      # 全部容器
rm web                     # 只删容器，镜像还在
rmi alpine:3.21            # 有容器在用时拒绝
load -i x.tar              # docker save 的层叠包
import x.tar 名:标签        # docker export 的扁平 rootfs
info / version / help
```

也支持 `run -v /sdcard/Download:/mnt/dl ...`，绑定会写进全局 `Volumes`（和菜单里的「绑定目录」同一份配置）。

### 为什么容器里没有 docker 命令

第一版是在每个容器的 `/usr/local/bin/docker` 放一个脚本，靠绑定目录 + 文件信箱把命令转发给 App。
能跑，但代价不划算：

- 会和镜像自带的真 `docker` **同名冲突**；`apk add docker` 也会把入口顶掉，之后行为不可控；
- 要维护一条跨 proot 的 IPC（请求/响应文件、轮询、超时）；
- 想"只让某一个终端能管"就得再造一个「管理终端」概念，还得反过来和 UI 同步会话状态。

现在这些全部去掉了：**容器里不存在任何 docker 命令**，管理动作在 App 进程内直接调用
`Containers` / `DockerCli`，没有 IPC、没有会话同步，也没有同名冲突。
控制台只做增删查（`run` 只创建，不切终端）；「启动/停止/进入容器」属于终端会话，由 UI 负责——
这样只有一个方向的数据流，不需要双向同步。

```
files/container/
├── images/alpine-3.20/rootfs/     ← 镜像（只读模板）
├── containers/default/rootfs/     ← 容器（镜像的可写副本）
└── containers/default/home/       ← 挂到容器内 /root
```

> 从 v1.x 升级时，旧的 `container/rootfs` 会被改名成 `images/alpine-3.20/rootfs`（不重新解包），
> 旧 `container/home` 的内容搬进默认容器，不会丢。

**不支持**：`build` / `commit` / `push` / `compose` / 网络管理 / 端口映射 —— 这些必须有真 dockerd。
如果你在已 root 且内核开 namespace 的设备上选了 Docker 后端，那是另一条路径（`DockerRuntime`，实验）。

---

## 实机验证（Redmi Note 8 Pro / Android 11 / arm64-v8a）

用 `--es cmd` 调试入口在真机上跑通的项：

| 项 | 结果 |
|---|---|
| 本地模式身份 | `uid=10129(u0_a129)`，`uname` 为 Android 内核 4.14.186 aarch64 |
| 本地模式设备信息 | `getprop ro.product.model` → `Redmi Note 8 Pro` |
| 本地模式 `/sdcard` | 可列出（受分区存储限制） |
| 容器发行版 | `NAME="Alpine Linux"`，`/` 为独立 rootfs（`bin etc home lib mnt proc root run sbin …`） |
| 容器身份 | `uid=0(root) gid=0(root)`（PRoot 假 root） |
| 容器架构 | `Linux localhost 4.14.186 … aarch64`（与宿主同内核，符合预期） |
| 容器包管理 | `apk-tools 2.14.4, compiled for aarch64`；`apk add --no-cache curl` 成功 |
| 容器网络 | `ping 223.5.5.5` 通（27.5 ms）；`nslookup` 走 `/etc/resolv.conf` 正常；HTTPS 请求返回 `200` |
| 容器可写 | `/root/t.txt` 写入并读回正常 |
| 会话存活 | 本地 sh 与容器 proot 可同时常驻，切模式复用现场，无残留僵尸进程 |

---

## 已知限制

1. **本地模式没 root 时命令很少**：能用的就是 `/system/bin` 里那些（`sh ls cat ps ping ip am pm dumpsys`…）。`ls` 的输出格式也不是 GNU 的。要完整 userland，用容器模式。
2. **容器不是内核隔离**：共享内核和 `/proc`，没有 cgroup 限额，`nsenter` 之类不能用。要真隔离得换内核。
3. **`/sdcard` 绑定默认只读/不可读**：Android 11+ 分区存储，要完整访问得申请 `MANAGE_EXTERNAL_STORAGE`（需上架说明），本工程没默认申请。
4. **proot 依赖 Termux 的 bionic 构建**：脚本从 Termux 官方源取，避免自己交叉编译。若你换源，注意 proot 版本与内核 `ptrace` 策略的兼容性（部分 ROM 需要 `PROOT_NO_SECCOMP=1`）。
5. **Docker 后端未在真机验证**：代码路径完整（探测 → 启 dockerd → `docker run -it`），但没有可测的带 namespace 内核的设备，所以按"实验"对待。
6. **只打了 arm64 的运行时**：`jniLibs/armeabi-v7a`、`x86_64` 需要分别跑一次 `fetch-runtime.ps1 -Abi …`，否则 32 位设备/模拟器上容器模式会提示缺运行时（本地模式不受影响）。
7. **本地模式的 `PS1` 会被 `/system/etc/mkshrc` 覆盖**：所以看到的是 Android 默认提示符（`:当前目录$`），不是工程里设置的那个。纯外观问题。

## 可以接着做的

- 多标签会话（现在是每模式一个）
- 选中/复制（现在只有"复制全部"）
- `Intent` 支持外部调用（`am start -a RUN_COMMAND`）
- rootfs 换成 Debian/Ubuntu 的 minirootfs（改 `fetch-runtime.ps1` 的 URL 即可，解包逻辑通用）
