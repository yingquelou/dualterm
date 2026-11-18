# 实现说明

## 工程结构

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt
│   └── pty.c                     ← openpty + fork + setsid + TIOCSCTTY + execvp（JNI 符号在 dev.dualterm.term 包下）
├── jniLibs/<abi>/                ← 脚本生成：libproot.so / libproot_loader.so / 依赖库
├── assets/runtime/               ← 脚本生成（仅 WITH_ROOTFS=1 的离线调试）：rootfs-<abi>.pack，默认不进包
├── java/dev/dualterm/
│   ├── MainActivity.java         ← 应用入口：模式 chip、自适应键条、输入设备监听、菜单
│   ├── term/                     ← 终端内核（不依赖容器，可单独复用）
│   │   ├── NativePty.java        ← JNI 声明：forkExec / read / write / setWindowSize / signal
│   │   ├── TermSession.java      ← PTY 会话：读线程 + 1 号线程 waitPid 回收
│   │   ├── TerminalEmulator.java ← VT100/xterm 屏模型（CSI/SGR/滚动区/备用屏/scrollback）
│   │   ├── TerminalView.java     ← 渲染 + 触摸滚动 + 自实现 InputConnection
│   │   ├── LaunchSpec.java       ← 一次会话的启动参数（argv/envp/cwd）
│   │   ├── Shells.java           ← 模式 A：/system/bin/sh（含 su 探测）
│   │   └── SessionService.java   ← 前台服务 + 部分唤醒锁，会话存活期间持有
│   ├── container/                ← 容器与镜像（只依赖 term + util）
│   │   ├── ContainerRuntime.java ← 后端抽象
│   │   ├── ProotRuntime.java     ← 模式 B：proot 参数组装（按容器 rootfs）
│   │   ├── DockerRuntime.java    ← 模式 B 备选：su + dockerd（实验）
│   │   ├── Containers.java       ← 镜像/容器注册表、物化复制、pull
│   │   ├── ContainerBootstrap.java← rootfs/proot 落盘、解包、resolv.conf
│   │   ├── TarGzExtractor.java   ← ustar + pax + GNU 长文件名 + 层叠加
│   │   ├── ImageImporter.java    ← docker save / export / OCI 识别与导入
│   │   ├── Ports.java            ← 端口声明解析与存储
│   │   └── Volumes.java          ← 宿主目录绑定（-b host:guest）
│   ├── cli/
│   │   └── DockerCli.java        ← 控制台背后的命令实现：ps/images/run/rm/rmi/pull…
│   ├── ui/
│   │   ├── ConsoleActivity.java  ← 容器管理控制台界面（docker 语法的子集）
│   │   ├── SoftKeyboard.java     ← 辅助键盘：固定区（编辑键/F 键）+ 可自定义区（步骤序列键）
│   │   ├── StepMode.java         ← 自定义键的单步模式枚举（校验/规范化/字节变换/自动标签集中在此）
│   │   └── KbdLayout.java        ← 键区行数算术（超出上限时自定义区滚动的可见行数）
│   └── util/
│       └── Fs.java               ← ensureDir 之类的小工具
└── res/…
```

依赖方向单向：`ui/cli → container → term → util`，没有反向依赖，也没有跨层回调。根包只保留应用入口 `MainActivity`，因此 `adb am start -n dev.dualterm/.MainActivity` 这个调试入口不会因分包改变。

## PTY

`openpty()` 拿一对 master/slave → `fork` → 子进程 `setsid` 加 `ioctl(TIOCSCTTY)` 抢到控制终端 → `dup2` 到 0/1/2 → `execvp`。父进程只保留 master fd，读线程阻塞在 `read()` 上。

`vi`、`top`、`less`、Tab 补全、`Ctrl-C` 能正常工作，原因就在这里；用 `Runtime.exec` 加管道做不到。

窗口尺寸由 `onSizeChanged` 算出行列后 `ioctl(TIOCSWINSZ)`，否则全屏 TUI 会按 80x24 绘制。

## 终端应答

只画屏不够，`TerminalEmulator` 还要回应三类查询，否则对端会一直等待：

| 查询 | 应答 | 不回应的后果 |
|---|---|---|
| `CSI 6n`（DSR，问光标位置） | `CSI <行>;<列> R` | gh / fzf / survey 卡在第一个提示上 |
| `CSI c`（DA，问设备属性） | `CSI ?62;22c` | 少数程序降级成 7bit 模式或不渲染 |
| `OSC 10/11/12/4;?`（问颜色） | `OSC 11;rgb:…` 等 | 深浅色判断失败，配色错乱并可能等待超时 |

典型症状：`gh auth login` 走到确认提示时按键无反应——程序尚未开始读按键，它在等光标位置报告。部分程序（含 gh）会用 `ESC[999;999f` 加 `CSI 6n` 反测终端尺寸，因此 CPR 必须按当前屏幕行列**钳制**后再回复。

应答不写在解析路径里：`TermSession` 先把应答攒进缓冲区，等 `append()` 退出、离开仿真器锁之后再写回 PTY，否则子进程一时不读就会把读线程锁死。

## 渲染

把同色同属性的连续单元格合成一次 `drawText`，而不是每格一次。宽字符（CJK）占两格，第二格用码点 0 占位。

宽字符必须**单独绘制**：把一个宽字形与它后面的字符塞进同一次 `drawText`，字体会自行撑开两倍宽度，后续字符整体右移一格，中文内容在 vim 里会越往右越歪。

整帧绘制与光标绘制都在 `synchronized (emulator)` 内完成，避免读到被 `append` 改到一半的屏幕状态。

## 输入

`EditorInfo.inputType` 用 `TYPE_NULL`——安卓终端模拟器的标准做法（Termux 同款），
告诉输入法「这里不是普通文本框」，压掉联想与自动纠错。中文输入不受影响：拼音组词
仍在输入法内部，选词上屏走 `commitText`。

`InputConnection` 用 `BaseInputConnection(this, true)`（**fullEditor = true**），
只覆写四处，其余交给基类：

| 方法 | 处理 |
|---|---|
| `commitText` | 先 `super`（把 composing 落成已提交文本），再把内部 `Editable` 的全文发往 PTY 并清空 |
| `finishComposingText` | 先 `super`，IME 强制收尾（切焦点、按实体键）时把残留内容照常提交，再清空 |
| `deleteSurroundingText` | 按 `leftLength` 逐个发 DEL 键事件——三星等键盘开启自动纠错时会一次报多个字符的删除 |
| `sendKeyEvent` | `ACTION_DOWN` 时先走 `handleKeyCode`（方向键/功能键翻译），未认领才交回基类 |

`setComposingText` **不覆写**：composing 文本由基类默认实现落进内部 `Editable` 并维护
composing span，输入法回读状态自洽，待选区才能正常收尾。

这里有一个踩过的坑：早先按「预编辑串不进 PTY」的思路用 `fullEditor=false` 并吞掉
`setComposingText` / `finishComposingText`，结果是输入法的 composing 永远「落不了地」——
表现为键入全程无响应、点选待选区也不消失。终端只需要「最终内容送达 PTY」，中间态由
输入法自己保管即可，不必在 InputConnection 层再拦一道。

**逐字还是 composing，由输入法配置决定，与机型无关**：此前实测曾误判为
Android 11/12 的系统差异（同一版搜狗在两台设备上行为不同）——后经核对，
根因是两台设备的**输入法配置不同**。具体到搜狗：英文输入可在**字母模式**
（逐字符上屏，首键即 `commitText`）与**单词模式**（单词级 composing，
候选区组词、选中后整词送达）之间切换，配置相同则行为完全一致。应用侧
只需保证 composing 与 commitText 两条路都能正确落地，不必关心输入法
处于哪种模式；排查「两台手机输入行为不一致」时先对输入法设置（英文
字母/单词模式），不要先怀疑系统版本。

粘贴时先看程序是否开启括号粘贴（`DECSET 2004`）：开启则用 `ESC[200~ … ESC[201~` 包裹，否则多行内容会被当作连续回车逐条执行（粘贴 token、粘贴多行命令时尤其危险）。未开启括号粘贴且内容含换行时，先弹确认框。

`commitText` 收到的换行统一转成 `\r`（CR）再写入：`\n` 在 vim 中等同 `<C-J>`，与回车语义不同。

### 物理键盘映射

物理键默认先交给输入法，中文输入法会吞掉不认识的组合（如 `Ctrl+C`），因此 `TerminalView.onKeyPreIme` 在 IME 之前截获终端语义键，按 xterm 规则翻译：

| 按键 | 发出 | 备注 |
|---|---|---|
| `Ctrl+A`…`Ctrl+Z` | `0x01`…`0x1A` | `Ctrl+@`/`Ctrl+空格` → `0x00`，`Ctrl+?` → `0x7F` |
| `Ctrl+[ \ ] ^ _` | `0x1B`…`0x1F` | `Ctrl+[` 即 ESC |
| `Alt+<键>` | `ESC` + 键 | Meta 键，emacs/readline 的 `M-x`、vim 的 `Alt+` 映射 |
| `Shift+Tab` | `ESC[Z` | CBT 反向制表 |
| `Ctrl+方向键` | `ESC[1;5A` 等 | 修饰参数 = `1 + shift + 2*alt + 4*ctrl` |
| `Ctrl+Home/End` | `ESC[1;5H` / `ESC[1;5F` | 同上 |
| `F1`–`F4` | `ESC O P/Q/R/S` | 带修饰时 `ESC[1;<m>P` |
| `F5`–`F12` | `ESC[15~` `17~` `18~` `19~` `20~` `21~` `23~` `24~` | |
| `Insert` / `Delete` | `ESC[2~` / `ESC[3~` | |
| `Ctrl+Backspace` | `0x08` | 普通退格是 `0x7F` |

## 全屏程序依赖的能力

vim 一类全屏程序需要下列终端能力，均已实现：

| 能力 | 序列 | 不实现的后果 |
|---|---|---|
| DEC 画线字符集 | `ESC ( 0` / `SO`·`SI` | 分屏边框、`listchars`、`fillchars` 显示成字母 |
| 延迟换行 | 写满末列先不换行 | 每行多滚一行，满屏重绘错位 |
| 自动换行开关 | `DECSET 7` | vim `?7l` 之后仍会多出空行 |
| 备用屏 | `CSI ?1049h/l` | 退出 vim 后主屏内容被冲掉 |
| 备用屏不写 scrollback | — | 翻 vim 时主屏历史被污染 |
| 鼠标上报 | `?1000/?1002/?1003` + `?1006` | `set mouse=a` 时点击、拖选无反应 |
| 焦点上报 | `?1004` | 切回前台 vim 不重绘 |
| 光标形状 | `DECSCUSR` `CSI n q` | 插入/普通模式光标无区别 |
| 括号粘贴 | `?2004` | 多行粘贴被逐条执行 |
| 原点模式 | `DECSET 6`（DECOM） | 使用滚动区的程序光标寻址整体偏移 |
| 软复位 | `CSI ! p`（DECSTR） | 程序崩溃后滚动区/反显/隐藏光标残留 |
| 屏幕对齐测试 | `ESC # 8`（DECALN） | 参数字节漏成屏幕上可见的 `8` |
| 制表位 | `HTS`/`TBC`/`CBT`/`CHT` | `tabs`/`tput tset` 设置的自定义制表位失效 |
| BCE（背景色擦除） | `\e[K` 用当前背景色填充 | 彩色区域里的 `\e[K` 挖出默认底色的洞 |
| 组合字符 | 零宽附加符 | `é`、泰文元音、emoji 肤色修饰各占一格，整行推歪 |
| 宽字符修复 | 覆写/删除/变窄时清孤儿 | 删掉半个汉字后残留残缺字形 |

鼠标对触摸做了转换：单指点击发 `<LeftMouse>`（vim 内定位光标），单指拖动发拖动上报（拖选），**双指上下滑发滚轮**（`<ScrollWheelUp/Down>`，button 64/65）——触摸屏没有滚轮，缺少这一项 vim 无法翻页。

## 与 xterm 对表的取舍

逐项对照 xterm 之后，按「对日常使用的影响」做取舍。

**已对齐**：

- **resize 语义**：变矮时从顶部裁剪（裁掉的行进 scrollback），保证光标留在屏幕上；变高时在底部补空行、内容不跳；scrollback 不再清空（原先转屏或弹键盘就会丢历史）。
- **未知序列不漏字**：`ESC % G`、`ESC SP F`、`ESC N/O` 这类「ESC + 参数字节」的序列会把参数字节吃掉，不再漏成可见字符（`tput init`、`reset`、tmux 都会发送）。
- **SGR 冒号子参数**：`38:2::R:G:B`（真彩）按组解析，不再把色彩空间位当成 R 通道；`4:3`（花式下划线）不再被拆成「下划线 + 斜体」。
- **`CSI > 4 ; 2 m` 是 modifyOtherKeys**，不是 SGR——误当 SGR 会把整屏变成下划线加暗色。
- **xterm 的边界语义**：CUU/CUD 在光标已处于滚动区外时不受区边界限制；DECOM 切换时光标回原点。

**有意不实现**（程序会静默降级，实现收益不抵风险）：

- `IRM`（插入模式）、`LNM`：几乎没有现代程序依赖 terminfo 的 `smir`。
- `modifyOtherKeys`、kitty 键盘协议：开启后需要程序按新格式解码按键；不回话则使用旧格式，无损。
- OSC 52（剪贴板）：等于允许远端进程改写手机剪贴板，多数现代终端也默认禁用。
- OSC 0/2（标题）、OSC 8（超链接）、同步输出 `?2026`：只影响不显示的内容，不影响正确性。
- ZWJ emoji 合成序列按多格绘制；真终端的字形簇合并依赖完整 harfbuzz 管线，不值得引入该复杂度。

## 容器启动

`ProotRuntime` 拼出的命令行：

```
proot --kill-on-exit --link2symlink -0 -r <rootfs> \
      -b /dev -b /proc -b /sys -b /dev/urandom:/dev/random \
      -b /proc/self/fd:/dev/fd -b <files>/container/home:/root -b /sdcard \
      -w /root /usr/bin/env -i HOME=/root SHELL=<登录shell> PATH=… TERM=xterm-256color <登录shell> -l
```

`-0` 是假 root（容器内 `uid=0`）；`--link2symlink` 让 `apk` 的硬链接在 SELinux 下也能通过。

登录 shell 按标准语义取自 rootfs 的 `/etc/passwd`（root 条目第 7 字段）：
Debian 是 `/bin/bash`、Alpine 是 `/bin/sh`——发行版自己决定，app 不选。
passwd 缺失、解析不出或该 shell 不在 rootfs 里时回退 `/bin/sh`。

启动预检（`launchSpec`）会先把「rootfs 不完整 / 架构不符 / `/usr/bin/env`
没有可执行位」翻译成带修复指引的中文报错，而不是放给 proot 吐一句英文
`not executable` / SIGBUS。

### 镜像层解包：硬链接兜底与 uutils

app 进程的 `link()` 一律被 **SELinux** 拒绝——不是系统调用缺失（bionic 有
`link(2)`，`Files.createLink` 直通它），也不是存储权限（app 私有目录本就
不需要任何权限）：AOSP sepolicy 对 `untrusted_app`/`priv_app` 域一律
`neverallow file_type:file link`（Android 6 起如此，注释写明理由：installd
无法保证删净被链接的文件、硬链接易被用于安全攻击）。实测同一台设备上
shell 域（`/data/local/tmp`）也被拒——是域级策略而非目录/文件系统问题；
符号链接属 `lnk_file` 类、走另一条权限线，应用可以创建。proot 在 app
进程内跑，容器里 `link()` 同样被拒。此前文档写「部分设备拒绝」是错的，
实为全平台普遍策略（曾误记为 MIUI 特性）。docker 镜像层里的硬链接条目
（type '1'）按「createLink → 带权限实体拷贝 → 相对符号链接」兜底：

- 拷贝必须从源恢复可执行位——硬链接条目的 tar 头不带 x 位，丢了它
  proot 报 `'/usr/bin/env' is not executable`；
- 符号链接只能作最后手段：ubuntu 25.10 起默认 Rust 版 coreutils（uutils），
  `/usr/bin/env` → `…/coreutils/env` → 硬链接 `/usr/bin/coreutils`，多调用
  分发要求 `argv[0]` 的符号链接**直接目标**就是实际执行的文件
  （对 `/proc/self/exe` 校验），链接链多跳一跳就被它自己的
  `Security violation` 拒掉；硬链接没有这个跳转所以真 docker 没事。
  拷贝兜底的代价是盘空间：uutils 全家 ≈ 120 × 10MB，`ubuntu:latest`
  在 link 不可用的设备上落盘 1.2GB（真 docker 靠硬链接只要 ~130MB）。

### Android 专属前提

以下三条均为实机验证得出的必要条件，任一条不满足都会启动失败：

| 前提 | 不满足时的现象 | 做法 |
|---|---|---|
| proot 必须能被 `exec` | 会话秒退，终端刷出 `无法执行 … Permission denied` 或 `code=127` | 打进 `jniLibs`，重命名为 `libproot.so`，安装后落在 `nativeLibraryDir` |
| `extractNativeLibs` 必须为 true | `nativeLibraryDir` 是空目录（`total 0`），proot 不存在 | `packagingOptions { jniLibs { useLegacyPackaging true } }` |
| rootfs 内的程序需借 proot 的 loader 启动 | proot 已启动但立刻 `status=1` | `PROOT_LOADER=<nativeLibDir>/libproot_loader.so` |

原因是 `targetSdk ≥ 29` 时应用私有数据目录被强制 **noexec**（SELinux `untrusted_app` 对 `app_data_file` 无 execute 权限），`execve` 与 `dlopen` 都会被拒。Termux 可以直接在 `$PREFIX` 里运行，是因为其 `targetSdk` 仍为 28。

此外，AGP 只打包匹配 `lib*.so` 的文件，`libtalloc.so.2` 会被**静默丢弃**（构建成功、运行期缺库）。脚本因此把 proot 的 `DT_NEEDED` 字符串原位改写为 `libtalloc2.so`（更短，NUL 补齐），库文件随之改名。

## 远程调试入口

无需触屏，便于无人值守验证：

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

`ConsoleActivity` 在 release 里 `exported=false`，只能经 `MainActivity` 转发；debug 变体
（`app/src/debug/AndroidManifest.xml`，只有清单没有源码）把它导出，可直接
`am start -n dev.dualterm/.ui.ConsoleActivity --es cmd "ps -a"`，供 adb 自动化驱动。
`--es cmd` 与 `--ei chunklog <n>` 本身在主源集，两种构建都可用。

`--es keytest` 的写法：`ctrl+` / `alt+` / `shift+` 任意前缀组合（可叠加），键名用 `a`–`z`、`0`–`9`、`tab` `enter` `esc` `space` `backspace` `delete` `insert` `up` `down` `left` `right` `home` `end` `pgup` `pgdn` `f1`–`f12`。按键在 5 秒后注入，留出时间让 shell 进入 raw 模式。

## 工程约定

- **改包名等于改 JNI 符号**。`NativePty` 的 native 方法靠 `Java_dev_dualterm_term_NativePty_*` 这类全限定名导出，挪包时必须同步修改 `cpp/pty.c`，否则编译通过、运行期只在 logcat 中出现 `No implementation found for ... NativePty.forkExec`，终端不启动。
- **自定义 View 在布局里写全限定名**（`<dev.dualterm.term.TerminalView>`），挪包同样要改 XML。
- **单元测试跑不了容器**：proot 依赖真机内核特性，验证走「容器内写文件 → 宿主 `run-as` 读」，不要用 logcat 判断输出完整性（高频输出会被 chatty 丢弃）。
- **单线程后台执行器**：所有磁盘与进程操作走 `background`（`dualterm-bg` 单线程），UI 回调经 `postUi` 并检查 `isFinishing`/`isDestroyed`；容器启动带代次号，旧任务的回调直接丢弃。

## 容器内为什么不提供 docker 命令

曾试过在每个容器的 `/usr/local/bin/docker` 放转发脚本，靠绑定目录加文件信箱把
命令转发给 App。能运行，但代价不划算：

- 与镜像自带的真 `docker` 同名冲突，`apk add docker` 会顶掉入口，之后行为不可控；
- 需要维护一条跨 proot 的 IPC（请求/响应文件、轮询、超时）；
- 要支持「只让某一个终端能管理」，还得引入「管理终端」概念并与 UI 同步会话状态。

现行方案：管理动作在 App 进程内直接调用 `Containers` / `DockerCli`，没有 IPC、
没有同名冲突、没有会话状态的双向同步。控制台只做增删查（`run` 只创建容器，
不切换终端）；「启动 / 停止 / 进入容器」属于终端会话，由 UI 负责，数据流保持单向。
用户视角的行为说明见 [../containers.md](../containers.md#容器内没有-docker-命令)。

## Docker 后端抽象

后端抽象 `ContainerRuntime`，两个实现：

1. `ProotRuntime`（默认）：纯用户态，验证过的路线。
2. `DockerRuntime`（实验）：探测 `su` → 探测 `dockerd` → 启动 `dockerd` →
   `docker run -it --net=host`。探测失败会明确报错并提示切回 PRoot，不会假装可用。

固定 host 网络是刻意的选择：手机内核常常没有 iptables nat 表，bridge 模式下
容器能起来但两头都不通；host 模式复用宿主网络栈，端口语义与 PRoot 后端一致，
代价是端口冲突直接 `EADDRINUSE`。
用户视角的说明见 [../containers.md](../containers.md#docker-后端为什么不是默认)。
