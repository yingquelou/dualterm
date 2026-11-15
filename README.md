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

# 3. 出包（仓库自带 wrapper，Gradle 8.7）
./gradlew assembleDebug
#    产物：app/build/outputs/apk/debug/app-debug.apk
```

wrapper 首次运行会从 `services.gradle.org` 下载 Gradle 8.7（约 100MB）；离线环境请自备同版本分发，
或用本机已装的 Gradle 直接构建：

```bash
gradle assembleDebug           # 等价，跳过 wrapper 下载
```

> 命令行构建需要 `ANDROID_HOME`（或 `local.properties` 里的 `sdk.dir`）与 `JAVA_HOME` 指向 JDK 17。

**终端仿真器回归**（不需要设备，`TerminalEmulator` 是纯 Java）：

```bash
javac -encoding UTF-8 -d /tmp/etest \
      app/src/main/java/dev/dualterm/term/TerminalEmulator.java tools/EmuTest.java
java -cp /tmp/etest EmuTest      # 全部 PASS 打印 "ALL PASS"
```

覆盖 DEC 画线字符集、延迟换行、DECAWM、CJK 占位、DECSCUSR、鼠标序列、焦点上报、
备用屏 scrollback 隔离、SO/SI、CPR、resize（顶裁/底补/scrollback 保留/宽字符跨界）、
DECALN、`ESC % G` 参数字节吞除、DECSTR、DECOM 原点模式、SGR 冒号真彩、
modifyOtherKeys 不当 SGR、制表位（HTS/TBC）、组合字符、DCH 宽字符修复、BCE。
改仿真器后先跑这个，再去真机看 vim。

**零第三方依赖**：没有 AndroidX、没有 Kotlin、没有 commons-compress。只有 Framework API + 一个 200 行的 C 文件 + 手写 tar.gz 解包器。这样做的目的是让构建链尽可能不炸。

---

## 工程结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   └── pty.c                     ← openpty + fork + setsid + TIOCSCTTY + execvp（JNI 符号在 dev.dualterm.term 包下）
├── jniLibs/<abi>/                ← 脚本生成：libproot.so / libproot_loader.so / 依赖库
├── assets/runtime/               ← 脚本生成：rootfs-<abi>.pack
├── java/dev/dualterm/
│   ├── MainActivity.java         ← 应用入口：模式 chip、自适应键条、输入设备监听、菜单
│   ├── term/                     ← 终端内核（不依赖容器，可单独复用）
│   │   ├── NativePty.java        ← JNI 声明：forkExec / read / write / setWindowSize / signal
│   │   ├── TermSession.java      ← PTY 会话：读线程 + 1 号线程 waitPid 回收
│   │   ├── TerminalEmulator.java ← VT100/xterm 屏模型（CSI/SGR/滚动区/备用屏/scrollback）
│   │   ├── TerminalView.java     ← 渲染 + 触摸滚动 + 自实现 InputConnection
│   │   ├── LaunchSpec.java       ← 一次会话的启动参数（argv/envp/cwd）
│   │   └── Shells.java           ← 模式 A：/system/bin/sh（含 su 探测）
│   ├── container/                ← 容器与镜像（只依赖 term + util）
│   │   ├── ContainerRuntime.java ← 后端抽象
│   │   ├── ProotRuntime.java     ← 模式 B：proot 参数组装（按容器 rootfs）
│   │   ├── DockerRuntime.java    ← 模式 B 备选：su + dockerd（实验）
│   │   ├── Containers.java       ← 镜像/容器注册表、物化复制、pull
│   │   ├── ContainerBootstrap.java← rootfs/proot 落盘、解包、resolv.conf
│   │   ├── TarGzExtractor.java   ← ustar + pax + GNU 长文件名 + 层叠加
│   │   ├── ImageImporter.java    ← docker save / export / OCI 识别与导入
│   │   └── Volumes.java          ← 宿主目录绑定（-b host:guest）
│   ├── cli/
│   │   └── DockerCli.java        ← 控制台背后的命令实现：ps/images/run/rm/rmi/pull…
│   ├── ui/
│   │   └── ConsoleActivity.java  ← 容器管理控制台界面（docker 语法的子集）
│   └── util/
│       └── Fs.java               ← ensureDir 之类的小工具
└── res/…
```

依赖方向是单向的：`ui/cli → container → term → util`，没有反向依赖，也没有跨层回调。
根包只留应用入口 `MainActivity`（这样 `adb am start -n dev.dualterm/.MainActivity` 这个调试入口不会因分包而变）。

### 关键实现点

**PTY**：`openpty()` 拿一对 master/slave → `fork` → 子进程 `setsid` + `ioctl(TIOCSCTTY)` 抢到控制终端 → `dup2` 到 0/1/2 → `execvp`。父进程只留 master fd，读线程阻塞在 `read()` 上。这样 `vi`、`top`、`less`、Tab 补全、Ctrl-C 全都正常——用 `Runtime.exec` + 管道是做不到的。

**窗口尺寸**：`onSizeChanged` → 算出行列 → `ioctl(TIOCSWINSZ)`，不然全屏 TUI 会按 80x24 画歪。

**终端要回话**：只画屏不够。`TerminalEmulator` 还得回应三类查询，否则对面会一直等：

| 查询 | 回什么 | 不回会怎样 |
|---|---|---|
| `CSI 6n`（DSR，问光标位置） | `CSI <行>;<列> R` | gh / fzf / survey 卡在第一个提示上不动 |
| `CSI c`（DA，问设备属性） | `CSI ?62;22c` | 少数程序降级成 7bit 模式或直接不渲染 |
| `OSC 10/11/12/4;?`（问颜色） | `OSC 11;rgb:…` 之类 | 深/浅色判断失败，配色错乱（还可能白等超时） |

典型症状就是 `gh auth login` 走到 `? Authenticate Git with your GitHub credentials? (Y/n)` 时**按什么都没反应**——
其实它还没开始读按键，它在等光标位置报告。有些程序（含 gh）还会用 `ESC[999;999f` + `CSI 6n` 反测终端尺寸，
所以 CPR 必须按当前屏幕行列**钳制**后再回，否则它会以为终端只有几行。

回包不要写在解析路径里：`TermSession` 先把应答攒进缓冲区，等 `append()` 退出、离开仿真器的锁之后再写回 PTY，
不然子进程一时不读就会把读线程锁死在自己身上。

**渲染**：把"同色同属性的连续单元格"合成一次 `drawText`，而不是每格一次。宽字符（CJK）占两格、第二格用码点 0 占位。
注意宽字符要**单独画在自己那一格**上：把一个宽字形和它后面的字符塞进同一个 `drawText`，字体会自己撑开两倍宽，
后面所有字符就整体右移一格——中文文件在 vim 里会越往右越歪。

**输入**：自己实现 `InputConnection`——中文输入法的预编辑串（`setComposingText`）不进 PTY，只有 `commitText` 才发；退格走 `deleteSurroundingText` → `0x7f`。粘贴则看程序有没有开括号粘贴（`DECSET 2004`）：开了就用 `ESC[200~ … ESC[201~` 包起来，否则多行内容会被当成连续回车逐条执行（粘贴 token、粘贴多行命令时特别危险）。
软键盘的回车送的是 `\n`，进 PTY 前要换成 `\r`（CR）：`\n` 在 vim 里是 `<NL>`，等于 `<C-J>`，和回车的语义不一样。

**外接键盘（蓝牙/USB）**：物理键默认先交给输入法，中文输入法会把不认识的组合（`Ctrl+C`）吞掉，
所以 `TerminalView.onKeyPreIme` 在 IME 之前把终端语义键抢下来，再按 xterm 的规则翻译：

| 按键 | 发出 | 备注 |
|---|---|---|
| `Ctrl+A`…`Ctrl+Z` | `0x01`…`0x1A` | `Ctrl+@`/`Ctrl+空格` → `0x00`，`Ctrl+?` → `0x7F` |
| `Ctrl+[ \ ] ^ _` | `0x1B`…`0x1F` | `Ctrl+[` 就是 ESC |
| `Alt+<键>` | `ESC` + 键 | 即 Meta 键，emacs/readline 的 `M-x`、vim 的 `Alt+` 映射 |
| `Shift+Tab` | `ESC[Z` | CBT 反向制表 |
| `Ctrl+方向键` | `ESC[1;5A` 等 | 修饰参数 = `1 + shift + 2*alt + 4*ctrl` |
| `Ctrl+Home/End` | `ESC[1;5H` / `ESC[1;5F` | 同上 |
| `F1`–`F4` | `ESC O P/Q/R/S` | 带修饰时 `ESC[1;<m>P` |
| `F5`–`F12` | `ESC[15~` `17~` `18~` `19~` `20~` `21~` `23~` `24~` | |
| `Insert` / `Delete` | `ESC[2~` / `ESC[3~` | |
| `Ctrl+Backspace` | `0x08` | 普通退格是 `0x7F` |

**vim 这类全屏程序还依赖这些终端能力**（都已实现）：

| 能力 | 序列 | 不实现会怎样 |
|---|---|---|
| DEC 画线字符集 | `ESC ( 0` / `SO`·`SI` | 分屏边框、`listchars`、`fillchars` 显示成 `l q q k x` 一堆字母 |
| 延迟换行 | 写满末列先不换行 | 每行多滚一行，满屏重绘整体错位 |
| 自动换行开关 | `DECSET 7` | vim `?7l` 之后仍会多出空行 |
| 备用屏 | `CSI ?1049h/l` | 退出 vim 后主屏内容被冲掉 |
| 备用屏不写 scrollback | — | 翻 vim 时主屏历史被污染 |
| 鼠标上报 | `?1000/?1002/?1003` + `?1006` | `set mouse=a` 时点击、拖选全无反应 |
| 焦点上报 | `?1004` | 切回前台 vim 不重绘 |
| 光标形状 | `DECSCUSR` `CSI n q` | 插入/普通模式光标无区别 |
| 括号粘贴 | `?2004` | 多行粘贴被逐条执行 |
| 原点模式 | `DECSET 6`（DECOM） | 用了滚动区的程序光标寻址整体偏移，画面错位 |
| 软复位 | `CSI ! p`（DECSTR） | 程序崩掉后滚动区/反显/隐藏光标一直残留，`reset` 救不回来 |
| 屏幕对齐测试 | `ESC # 8`（DECALN） | 参数字节漏成屏幕上一个可见的 `8` |
| 制表位 | `HTS`/`TBC`/`CBT`/`CHT` | `tabs`/`tput tset` 设的自定义制表位失效 |
| BCE（背景色擦除） | `\e[K` 用当前背景色填 | 彩色区域里的 `\e[K` 挖出默认底色的洞 |
| 组合字符 | 零宽附加符 | `é`、泰文元音、emoji 肤色修饰各占一格，整行推歪 |
| 宽字符修复 | 覆写/删除/变窄时清孤儿 | 删掉半个汉字后屏幕上留下残缺字形 |

鼠标这块对触摸做了转换：单指点击发 `<LeftMouse>`（vim 里定位光标），单指拖动发拖动上报（拖选），
**双指上下滑发滚轮**（`<ScrollWheelUp/Down>`，button 64/65）——触摸屏没有滚轮，不给滚轮 vim 就没法翻页。

### 与真终端对表的取舍（审计结论）

逐项对照 xterm 后，按「对日常使用的影响」做了取舍：

**已对齐**：
- **resize 语义**：变矮时从顶部裁（裁掉的行进 scrollback），保证光标（提示符）留在屏幕上；
  变高时在底部补空行、内容不跟着跳；scrollback 不再清空——原来转个屏/弹个键盘历史就没了。
- **未知序列不漏字**：`ESC % G`、`ESC SP F`、`ESC N/O` 这类「ESC + 参数字节」的序列会把参数字节吃掉，
  不再漏成屏幕上的可见字符（`tput init` / `reset` / tmux 都会发）。
- **SGR 冒号子参数**：`38:2::R:G:B`（真彩）按组解析，不再把色彩空间位当成 R 通道把颜色错位；
  `4:3`（花式下划线）不再被拆成「下划线 + 斜体」。
- **`CSI > 4 ; 2 m` 是 modifyOtherKeys**，不是 SGR——误当 SGR 会把整屏变成下划线加暗色。
- **xterm 的边界语义**：CUU/CUD 在光标已处于滚动区外时不受区边界限制；DECOM 切换时光标回原点。

**有意不实现**（程序会静默降级，实现它们的风险/收益不划算）：
- `IRM`（插入模式）/`LNM`：几乎没有现代程序依赖 terminfo 的 `smir`。
- `modifyOtherKeys` / kitty 键盘协议：开启后需要程序按新格式解码按键；不回话它就用旧格式，无损。
- OSC 52（剪贴板）：等于允许远端进程改写手机剪贴板，很多现代终端也默认禁用。
- OSC 0/2（标题）、OSC 8（超链接）、同步输出 `?2026`：只影响「不显示的东西」，不影响正确性。
- ZWJ emoji 合成序列（家庭 emoji 这类多码点图形）按多格画；真终端做字形簇合并也是靠整个
  harfbuzz 管线，这里不值得背这个复杂度。

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

# 直接打开容器管理控制台并执行一条命令（ConsoleActivity 不 exported，由 MainActivity 转发）
adb shell 'am start -n dev.dualterm/.MainActivity --ez console true --es cmd "ps -a"'

# 注入一次带修饰键的按键（手边没有蓝牙键盘时回归键映射）
# 走的是 TerminalView.dispatchKeyEvent → onKeyDown，与真实按键同一条路径
adb shell 'am start -n dev.dualterm/.MainActivity --es mode container --es keytest "ctrl+c"'
```

`--es keytest` 的写法：`ctrl+` / `alt+` / `shift+` 任意前缀组合（可叠加），键名用 `a`–`z`、`0`–`9`、
`tab` `enter` `esc` `space` `backspace` `delete` `insert` `up` `down` `left` `right` `home` `end` `pgup` `pgdn` `f1`–`f12`。
按键在 5 秒后注入（留时间让 shell 进 raw 模式再抓字节）。

---

## 用法

- 顶栏三个元素：模式 chip（点一下选「本地 / 容器 / 容器与镜像」，当前模式只在这里显示）、一行状态（行列数 · 字号 · pid，只说终端几何，不重复模式名）、`⋮` 菜单。
- 底部键条（**只在触屏时存在**）：`CTRL ALT ESC TAB ^C ← ↑ ↓ → ⋯` 等宽铺满一行，末尾是两个图标键 `⌨`（自绘键盘）和 `⛶`（全屏）。不用横向滚动去找；`⋯` **单击**展开更多键面板（HOME/END/PGUP/PGDN/DEL/INS、F1–F6），**长按**打开应用菜单。CTRL/ALT 是一次性修饰，作用于下一个按键，锁上时整键反白（绿色）。
- 菜单入口只有可见控件：顶栏 `⋮`（非全屏）、长按键行的 `⋯`（全屏下顶栏收起，靠它）。终端长按不再弹菜单。
- 接了物理键盘：键条和 `⌨` 按钮自动消失（键盘上本来就有这些键），热插拔实时生效，终端自动多出一行高度。
- 上下拖动看 scrollback（3000 行）；上滚时右下角浮出绿色胶囊「↓ N 行 · 回到底部」，点它一键回游标——不会停在历史里找不到光标。
- 双指捏合改字号（持久化）；蓝牙鼠标/触控板：滚轮滚屏，程序开了鼠标上报就转成滚轮序列（vim/htop 里可用），悬停移动也会上报。
- **进程退出（exit / Ctrl-D / 崩溃）**：底部键条原地换成会话结束条——「会话已结束 (exit 0) · 输出仍在屏上」+「复制输出」「重启」；正常退出是灰色，被信号杀（128+n）或异常码是橙色。终端最后几行输出原样保留不遮挡；对死掉的 PTY 敲键盘会提示「点下方重启」。
- 返回键分层：先收键盘 → 再收更多键面板 → 最后**退到后台**（会话保持，回来还在原处）。真正结束会话走菜单「退出应用」。
- 物理键盘冷启动：输入焦点自动落在终端上，Enter 直接进 PTY 而不是激活顶栏按钮。
- 菜单里：重启会话 / 复制全部输出 / 粘贴 / 清屏 / 字号 / Root Shell / 容器运行时 / 容器管理 / 重建当前容器 / 绑定目录 / 存储权限 / 全屏 / 退出应用 / 关于。

---

## UI 与交互：跟着输入设备变，而不是固定三层

终端 UI 的核心矛盾：**同一个 App，两种输入姿态**。接蓝牙键盘时，屏幕上任何 chrome 都是浪费（ESC/Tab/方向键/F 键键盘上都有）；纯触屏时，这些键又是刚需，chrome 从「导航」变成「缺失键盘的延伸」。所以 chrome 必须自适应：

| | 纯触屏 | 物理键盘 |
|---|---|---|
| 底部键条 | 显示（常用键一行 + 可收起面板） | 整条隐藏，省一行终端高度 |
| `⌨` 软键盘按钮 | 显示 | 隐藏（没意义还占位） |
| 终端高度 | 少一行 | 多一行 |

设计上的几个取舍：

- **状态行并进顶栏**。原来「顶栏 + 状态行 + 键条」三层常驻，横屏能吃掉 100dp ≈ 四五行终端。行列数/pid 是低频信息，不配占一整行。
- **模式切换收进 chip**。「本地/容器」一天切换不了几次，两个常驻按钮占掉顶栏一半宽度；点 chip 弹三选一（本地 / 容器 / 容器与镜像），低频操作不该常驻。
- **键条不横向滚动**。原来 20 个键塞在横向 ScrollView 里，`^C` 排第 13 位，得先横滑才找得到，而滚动条又是隐藏的——没人知道右边还有键。现在常用 10 键等宽铺满，扩展键进面板、按一次就收（面板只在「临时要个 F 键」时花高度）。
- **粘滞修饰键整键反白**。触屏上没有键盘 LED，Ctrl 锁上之后如果只有文字变色，用户很容易忘了自己按过 Ctrl，然后输入的每个字母都变成控制字符。
- **面板键条发送的序列不叠加粘滞修饰**（`sendRaw`）：这些序列本身就是终端语义，Ctrl 锁着时连 `^C` 都会被 ESC 前缀搅坏。发完顺手解锁。
- **返回键 ≠ 杀会话**。终端里 Back 退出等于 `kill` 正在跑的编译/tmux——改成「收键盘 → 收面板 → 退后台」，会话原样保留；真正退出在菜单里，且有确认。
- **菜单不挂长按终端**。长按是 Android 给文本控件的标准手势（选词 + 手柄 +「复制/全选」浮动条），终端要做选区就得先把它空出来；而且它和现有手势打架：vim 鼠标模式下「按住再拖」选段会被 450ms 的长按抢走（还得补发 mouse-up 收尾），点终端切键盘时手指停顿一下也会误弹菜单。改成两档都落在可见控件上：顶栏 `⋮` / 长按 `⋯`。
- **`^C` 单独留一个键，不做成「CTRL 再点 c」**。功能上确实重复，但中断是终端里最高频的救命动作，CTRL 还是一次性锁存（点完键行会反白，忘了解锁后续输入全变控制字符）：一击直达比两击加一次状态确认便宜得多。真嫌挤该动的是别处——4 个方向键 + 2 个 38dp 图标键占掉近半行宽，横屏宽度富余时把面板里的编辑键提上来更划算。
- **滚动要能「回来」**。上滚看历史时光标不画、新输出看不见，很容易以为程序卡死；胶囊提示偏移量并给一键回底。

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
8. **软键盘弹出时终端会被压扁**：横屏全键盘能吃掉 600dp 以上，`adjustResize` 之后终端只剩两三行（此时更多键面板会自动收起）。接了物理键盘时不会触发（代码里有判断，有硬键盘就不强弹软键盘）。
9. **`TerminalEmulator` 有意不实现的协议**：`IRM`/`LNM`（插入/换行模式）、`modifyOtherKeys` 与 kitty 键盘协议、OSC 52（程序直接写系统剪贴板，vim 的 `"+y`）、OSC 0/2 标题、OSC 8 超链接、Sixel 图片、同步输出（`?2026`）、ZWJ emoji 合成序列。取舍理由见上文「与真终端对表的取舍」；这些程序都会静默降级，不影响基本使用。
10. **这个 App 自己没禁止截图**，但**开着软键盘时截不了图**：输入法窗口带 `FLAG_SECURE`，系统会拒绝整屏截图并提示「该应用不允许屏幕截图」。要截图先收起键盘。可用 `adb logcat -s DualTerm | grep "window flags"` 确认 App 自己的 `FLAG_SECURE=false`。

## 可以接着做的

- 多标签会话（现在是每模式一个）
- 选中/复制（现在只有"复制全部"）
- 键条面板第二页（F7–F12 目前只有物理键盘能给）
- 触屏文本选择：长按手势已经空出来了（菜单挪到 `⋯` 长按），理想是先选词再出「复制」浮动条
- `Intent` 支持外部调用（`am start -a RUN_COMMAND`）
- OSC 52：让 vim 的 `"+y` 能写进系统剪贴板
- rootfs 换成 Debian/Ubuntu 的 minirootfs（改 `fetch-runtime.ps1` 的 URL 即可，解包逻辑通用）

---

## 工程约定

- **改包名 = 改 JNI 符号**。`NativePty` 的 native 方法靠 `Java_dev_dualterm_term_NativePty_*` 这种全限定名导出，
  挪包时必须同步改 `cpp/pty.c`，否则编译照过、运行期只在 logcat 里冒
  `No implementation found for ... NativePty.forkExec`，终端直接不启动。
- **自定义 View 在布局里写的是全限定名**（`<dev.dualterm.term.TerminalView>`），挪包同样要改 XML。
- **单元测试跑不了容器**：proot 依赖真机内核特性，验证一律走「容器内写文件 → 宿主 `run-as` 读」，
  不要用 logcat 判断输出完整性（高频输出会被 chatty 丢弃）。

## 许可

MIT，见 [LICENSE](LICENSE)。
