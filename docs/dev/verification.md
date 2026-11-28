# 实机验证记录

设备：Redmi Note 8 Pro / Android 11 / arm64-v8a。验证方式为 `--es cmd` 调试入口（见 [internals.md](internals.md#远程调试入口)）。

| 项 | 结果 |
|---|---|
| 本地模式身份 | `uid=10129(u0_a129)`，`uname` 为 Android 内核 4.14.186 aarch64 |
| 本地模式设备信息 | `getprop ro.product.model` → `Redmi Note 8 Pro` |
| 本地模式 `/sdcard` | 可列出（受分区存储限制） |
| 容器发行版 | `NAME="Alpine Linux"`，`/` 为独立 rootfs（`bin etc home lib mnt proc root run sbin …`） |
| 容器身份 | `uid=0(root) gid=0(root)`（PRoot 假 root） |
| 容器架构 | `Linux localhost 4.14.186 … aarch64`（与宿主同内核） |
| 容器包管理 | `apk-tools 2.14.4, compiled for aarch64`；`apk add --no-cache curl` 成功 |
| 容器网络 | `ping 223.5.5.5` 通（27.5 ms）；`nslookup` 走 `/etc/resolv.conf` 正常；HTTPS 请求返回 `200` |
| 容器可写 | `/root/t.txt` 写入并读回正常 |
| 容器服务全系统可达 | 容器内 `nc -l -p 18080`，宿主 `echo ping-from-adb \| nc 127.0.0.1 18080`，数据到达容器终端 |
| 会话保活 | 前台服务 `isForeground=true`，`PARTIAL_WAKE_LOCK 'dualterm:session'` 持有 |
| 会话存活 | 本地 sh 与容器 proot 可同时常驻，切模式复用现场，无残留僵尸进程 |
| Release 包安装 | CI 产物签名校验通过，安装后本地 shell 与容器模式均正常，首次进容器自动解包 Alpine 3.20.3 |
| 主菜单分组 | 横屏顶层 6 组完整显示无裁剪；「屏幕内容」二级展开正常，点「复制当前屏」回调生效（toast 计数正确） |
| 键面板 F7–F12 | 17 键 3 行完整；`cat -v` 下按 F7 回显 `ESC[18~` 的可见部分 `8~`，序列正确 |
| 多标签·会话列表 | 模式 chip 下拉列出全部 tab（`●` 标当前），按创建序排列；「＋ 新建本地 Shell」「＋ 新建容器会话」「容器与镜像」三入口正常 |
| 多标签·并行与切换 | 两个本地 + 一个容器 tab 并存；tab 间切换内容各自保留，切走不杀会话（容器 tab 跨切换存活） |
| 多标签·退出与重启 | `exit` 后 tab 从列表摘除、结束条出现；「重启」只重建该 tab（新 pid），同类其余 tab 不受影响 |
| 多标签·容器停止 | 容器管理「停止」只杀该容器的 tab：tab 摘除、输出保留在屏（code 255）、两个本地 tab 存活 |
| 空册引导 | （代码路径）无容器时进容器模式弹引导：拉取 Alpine / 导入 tar；本地 shell 兜底启动 |
| rootfs 移出 APK | assets 三份 rootfs pack 删除；release APK 10.93MB → 512KB；CI 校验与 fetch 脚本同步（rootfs 仅 WITH_ROOTFS=1 时下载） |
| 系统键盘输入（Android 11） | 搜狗英文逐字进终端，`echo 123456` 正常执行并回显；中文组词在输入法内、选词上屏一次送达；待选区正常收尾 |
| 系统键盘输入（Android 12，Note 9） | 功能无损（每字符恰好送达一次）。曾把「首轮 composing、上屏后转逐字、重开键盘复位」误判为 Android 12 的机型差异——**2026-11-18 更正**：实为搜狗输入法的英文模式配置不同（**字母模式**=逐字上屏 / **单词模式**=组词待选），配置相同行为一致，见 internals.md 输入章 |
| 容器登录 shell | Debian rootfs → `/bin/bash`（方向键 / HOME 行编辑生效）；Alpine → `/bin/sh`；passwd 缺失或 shell 不在 rootfs 时回退 `/bin/sh` |
| 辅助键盘行数上限 | Note 9：上限 2 行时 kbd 高 392px，上滑滚到底「＋」可见；改回 4 行即时恢复 588px；固定区高度不变 |
| 步骤模式下拉渲染 | MIUI 12.5（font_scale 1.17）六种模式文本全部可见且与所选一致（`tools/verify_spinner.py` 逐模式 dump + 截图像素校验） |
| 大字体键区布局 | font_scale 1.4 下键条与辅助键盘无裁剪；竖屏键高 33.8dp / 横屏 26.9dp，全部行在屏内（`dumpsys activity top` 实量） |
| 渲染与解析性能（M2010J19SC / Android 12） | 前台 CPU 160% → 67%，后台 137% → 9%；突发输出 2.1s → 0.8s；janky 帧 11.66% → 0.79% |
| 视口锚点跨重建 | 上滑查看历史后重建 Activity / 切标签，回到原读取位置（绝对行号） |
| 后台输出不推视口 | 停在上滑位置，8 秒后台持续输出，视口漂移 116 行 → 0 |
| 拖动惯性 | 快速上下滑动后自然减速，按当前 scrollback 夹紧不越界；按下即停 |
| 选区端点手柄 | 起点 / 终点水滴手柄可拖拽微调，触点抬高后不遮边界格 |
| 浮动条跟随选区 | 长选区优先贴上方，上方不足转下方；水平居中于选区中点 |
| 接管态默认路径冒烟 | 本地会话 `abc123` 正常落到提示符；选区手柄与浮窗显示正常（默认 `TYPE_NULL`） |
| MainActivity 拆分：键条 → `ui/KeyBar`（2026-11-26，M2010J19SC） | `uiautomator dump` 中 11 个键的文本与 bounds 与拆分前完全一致（`dp` 取整改为截断后对齐）；像素采样 CTRL 由灰 (88,91,98) 变绿 (112,203,125)；⌨ 开/合辅助键盘、长按弹菜单、⛶ 入/退全屏（✕/⛶ 切换、顶栏隐藏/恢复）均正常；七套桌面回归全过 |
| 无障碍虚拟节点（2026-11-26，M2010J19SC） | 35 列 `seq -s. 1 80`：`uiautomator dump` 中 239 字符长行是**一个**节点（bounds 跨 6 行），转横屏后文本不变（跨 2 行）；上滑后首节点 561→483；空白行无节点；`input text` 回显正常。EmuTest 67–69 锁定逻辑行拼接 / 续行标记 / 空白行 |
| 折行恢复：仍折行时按新宽度重分割（2026-11-26，M2010J19SC） | 35 列下 `seq -s. 1 80` 折 6 段（行尾 …12 / .13…）；转横屏 74 列后重分为 4 段（…25 / .26… / …49.5 / 0.51…），01–80 完整、无重无丢；再转回竖屏恢复原分段。EmuTest 54/55 锁定「重分割」与「宽字符不劈开」 |
| 折行恢复：键盘挤压往返幂等（2026-11-26，M2010J19SC） | `seq 1 600` 在 35x19（输入法展开）显示 584–600；收键盘到 35x24 后回填 577–600、提示符贴底，不再出现「提示符停在屏幕中部、下方一片空白」。EmuTest 53 锁定 shrink→grow 屏幕与光标完全复原 |
| 长按选词（2026-11-26，M2010J19SC） | `echo alpha-beta gamma` 后长按 `beta`：松手恰好高亮 beta 四字符并弹出浮动条；词内抖 12px 仍保持整词（词锁）；长按 `/` 只选该标点（标点自成词）；拖出词外按字符扩展。实现为 `TerminalEmulator.wordSelectionAt`（纯 Java，EmuTest 6 例覆盖） |
| 接管态「始终」档（2026-11-26，`hw_ime=1`，输入法窗口隐藏） | 不点屏（`mInputShown=false`）注入 `n i h a o space` → `out#5: 你好`：窗口可见性门槛被绕过，`restartInput` 后仍按档位接管 |
| 接管态「从不」档（2026-11-26，`hw_ime=2`，输入法窗口可见） | 注入 `n i h a o space` → `out#6: n` … `out#11` 逐条直发、无组词；双击 Shift 不生效（不越过用户策略） |
| 视口锚点跨 scrollback 裁剪（2026-11-26） | `seq 1 3000` 上滑 165 行停在 2819–2837；`am send-trim-memory RUNNING_LOW` 裁到 600 行后画面逐行一致；滚到顶胶囊封顶「↓ 600 行」证实裁剪生效 |
| 注音输入（2026-11-26，搜狗注音，大千键位） | 软键盘点按 ㄋㄧˇ 可上屏汉字；接入外接键盘后注入大千序列，输入法弹出 Toast「当前键盘不支持外接键盘输入」（Toast Queue 记录 `pid 4850 / com.sohu.inputmethod.sogou.xiaomi / isSystemToast=false`，应用源码无此文案）并把字母退回直发（`out#: s` / `u` / `3`）。按设计，接管态下物理键盘的键位映射完全由输入法决定，应用只负责不拦截；本例失败点在该输入法的注音方案不支持外接键盘，应用侧无缺陷 |
| 输入方案矩阵（2026-11-26，搜狗小米版，自动档） | 26 键拼音 `zhong` → `中`；五笔 `khk` → `中`；手写 `ab` + 空格 → `阿布`（按拼音候选）；9 键拼音与 26 键一致（`nihao` + 空格 → `你好`；但 9 键候选计算较慢，字母与空格要分两步、间隔 ≥2s，否则空格会把未转换的拼音原样提交）；注音提示「当前键盘不支持外接键盘输入」并退回；笔画输入 `123` 直发、无候选。结论：物理键盘能否用取决于该输入法是否为该方案实现「硬键盘→候选」转换；拼音 9 键与 26 键效果一致，仅 9 键候选计算更慢 |
| 外接键盘接管分支（2026-11-26，uinput 工装，自动档 `hw_ime=0`） | `tools/hardkey.c` 造 `KeyboardType=2 / ALPHAKEY / EXTERNAL / IsExternal:true` 键盘：拼音 `nihao`+空格 → `out#: 你好`（`NO_SUGGESTIONS` 未影响候选选词）；`home / pgup / ins / up / esc` → `^[[H` / `^[[5~` / `^[[2~` / `^[[A` / `^[`；双击 Shift 切英文后字母逐条直发，再双击切回中文恢复组词（输入法须处于拼音布局；9 键与 26 键接管与组词效果一致，9 键候选计算较慢，字母与空格之间要留足间隔）。**注意**：点屏叫出输入法后需等会话重建再注入，紧接着注入的首串字母可能仍落在旧 `TYPE_NULL` 会话上 |
| MainActivity 拆分：容器对话框 → `ui/ContainerDialogs`（2026-11-26，M2010J19SC） | `uiautomator dump` 逐项核对：容器与镜像列表（4 个容器含 `★ ubuntu [运行中]`、7 个镜像带大小）、滚到底后「⇣ 拉取镜像 / ⇣ 导入 tar / ⇄ 镜像源设置」三项齐全；容器动作菜单（切换到此容器 / 停止 / 删除）；绑定目录空册文案 + 添加绑定（默认 `/sdcard/Download → /mnt/download`）；拉取对话框读到当前 Hub 源 `https://docker.m.daocloud.io`。文案与项序与拆分前源码逐字一致；七套桌面回归全过 |
| MainActivity 拆分：菜单面板 → `ui/MenuSheet`（2026-11-26，M2010J19SC） | `⋮` 打开后顶层 7 组完整；进「容器」二级显示 7 行（绿色 `‹ 容器` 返回行 + 容器管理 / 控制台 / 导入镜像 / 重建 / 绑定目录 / 运行时）；返回键第一次回顶层、第二次收起；点「外观 → 字号 +」状态栏由 `96x84 · 5sp` 变 `75x69 · 6sp` 且菜单收起，再点「字号 -」复原（Host 分发与收起时序正确）；七套桌面回归全过 |
| 公共第三方 registry（2026-11-27，M2010J19SC） | `parseRef` 增加域名识别与 `registryBase` 纯函数选择，RegistryTest 73→90 用例全过。实机 `ghcr.io`：`/v2/` 401 → 匿名 token 200 → OCI index 200 → arm64 manifest 200；层下载被直连限速到 1–5 KB/s，读超时后正确报错并清理。改填 `ghcr.nju.edu.cn/linuxserver/baseimage-alpine:3.21`（同为第三方域名路径）完整拉取 7 层成功，`docker run` 建容器后 `cat /etc/alpine-release` = 3.21.0、`pidof sh` 正常。过程中发现并修复 `TarGzExtractor` 缺陷：下层实体文件覆盖上层绝对符号链接时，`FileOutputStream` 顺着链接写进宿主只读 `/bin`（EROFS）；新增 TarTest 用例（24→25） |
| 发布前构建校验（2026-11-27，本地，推送前终检） | `DT_VERSION_NAME=3.10.0 DT_VERSION_CODE=31000 assembleRelease` 成功；`aapt2 dump badging`：`dev.dualterm` versionName `3.10.0` / versionCode `31000`、native-code `arm64-v8a` `armeabi-v7a` `x86_64`；APK 内三 ABI 的 `lib{android-shmem,proot,proot_loader,ptyterm,talloc2}.so` 齐全，体积 563,135 字节；七套桌面回归全过（EmuTest ALL PASS / Sec 42 / Tar 30 / Import 35 / Ports 36 / Registry 90 / Kbd 137）。本地包为测试签名，正式包由 CI 注入 Secrets 私钥 |
| 发布前构建校验（2026-11-26，本地） | `DT_VERSION_NAME=3.10.0 DT_VERSION_CODE=31000 assembleRelease` 成功；`aapt2 dump badging`：`dev.dualterm` versionName `3.10.0` / versionCode `31000`、native-code 三 ABI；APK 内 `lib/{arm64-v8a,armeabi-v7a,x86_64}/libproot.so` 齐全，体积 547KB；七套桌面回归全过（EmuTest ALL PASS / Sec 42 / Tar 24 / Import 35 / Ports 36 / Registry 73 / Kbd 137）。本次本地包为测试签名，正式包由 CI 注入 Secrets 私钥 |
| 选区手柄尺寸可配置（2026-11-26，M2010J19SC） | 「外观 → 选区手柄：杆 9dp / 半径 7dp」打开三输入框（默认 9/7/22，范围校验生效）；杆长改 20 确认后 prefs `handle_stem_dp=20` 落盘，长按选词截图中手柄杆明显加长；改回 9 后 prefs 复原 |
| AltGr 字符修复（2026-11-26，代码级） | `handleKeyCode` 改为「含 Alt 与不含 Alt（保留 Shift）的 KeyCharacterMap 字符不同且可见 → 判为 AltGr，只发字符不加 ESC」。US 布局 Alt+E / Alt+Shift+E 仍走 ESC 前缀（字符相同）；用户复测：法语 AltGr+E→`€` 通过，US 的 Ctrl+Alt+S→`ß` 为 AltGr 语义（与系统文本框一致），非回归 |
| 手动测试结论回收（2026-11-26，用户实机） | E13 TalkBack 按逻辑行朗读通过；H1 端点重锚手感基本没问题；E7 手柄落地前测过、代码复核 shiftSelect 分支未动；F3 `"+yy` 未进剪贴板，判定为 vim 侧能力/配置（命令已写入 manual-test）；G4 AltGr 已修复并复测：法语 `€` 通过，US 的 Ctrl+Alt+S→`ß` 属标准布局语义 |
| F3 Vim 剪贴板根因 + G4 百度输入法（2026-11-27，M2010J19SC / 用户复核） | Ubuntu 22.04 中 Huge Vim 8.2 GTK3 已有 `+clipboard/+xterm_clipboard/+X11`，但 `$DISPLAY` 为空；`"+yy` 后 `+` 寄存器为空，普通 `yy` 能写 `0` 号寄存器。根因是 Vim 的 X11 剪贴板提供方不可用，并非编译缺 `clipboard`，也未向 Dualterm 发送 OSC 52；需另配 OSC 52 插件/提供方，应用侧无缺陷。百度输入法实体键盘由用户确认可用；Gboard 后续已于 2026-11-27 实测通过（见下表「Gboard 实体键盘」条） |
| F3 Neovim OSC 52 方案（2026-11-27，M2010J19SC，ubuntu22） | Jammy 仓库 0.6.1 / stable PPA 0.7.2 过旧；从 `neovim-ppa/unstable` 安装后 `nvim --version` 为 `NVIM v0.13.0-dev`。容器 `~/.config/nvim/init.lua` 配置 `vim.g.clipboard = 'osc52'`（自动探测报 No provider，显式强制后重启生效）；临时缓冲区 `F3_NEOVIM_OK` 经 `"+yy` 出现写入 Toast，退出后 Dualterm `Shift+Insert` 回读同一文本，端到端通过。旧 stable PPA、错误位置的配置副本及临时文件已清理 |
| 拉取镜像非模态（2026-11-26，M2010J19SC） | 拉 alpine:3.21 期间顶栏下方实体显示进度、无全屏遮罩；点 ⋮ 菜单正常弹出、点终端输入法正常弹起；完成后进度条收起且镜像注册（prefs 可见），测试镜像已删除、镜像表复原。tar 导入随后也挪到同一 `dualterm-io` 执行器（启动容器不再被导入阻塞，代码级 + 回归） |
| MainActivity 拆分：会话选择器 → `ui/SessionPicker`（2026-11-26，M2010J19SC） | 点模式 chip 弹出 `● 容器 · ubuntu · pid 29771` + 「＋ 新建本地 Shell / ＋ 新建容器会话（ubuntu）/ 容器与镜像…」；点当前会话项正常收起并保持贴屏；再点「容器与镜像…」打开容器列表（标题与内容完整）；七套桌面回归全过 |
| Gboard 实体键盘（2026-11-27，M2010J19SC，自动档） | uinput 工装 + 容器 `cat`/`chunklog`：`n i h a o` 弹出底部浮动候选条，点首个候选与 `space` 直选均为 `out#: 你好`（不夹空格）；挂拼音 Enter 提交 `nihao` 且无 CR（空闲 Enter 才产生 `\r`）；双击 Shift 切英文后字母逐条直发、再双击切回中文恢复组词。Gboard 在输入法进程内组词、只在上屏时 `commitText`，`imeComposing` 让路分支不触发；候选条在输入法窗口内，软键盘可见时接管成立。测完恢复默认输入法为搜狗 |
| 硬链接兜底优化（2026-11-27，M2010J19SC） | `ubuntu:25.10` 重新 pull：`usr/lib/cargo/bin/coreutils/*` 落成 `#!/bin/sh exec /usr/bin/coreutils "<名>" "$@"` 脚本，镜像 1,333,120KB → 118,984KB（约 1.3GB → 116MB）。新建容器后 `env` / `ls --version`（uutils 0.2.2）/ `cat` / `cp` / `readlink /proc/self/exe`（`/usr/bin/coreutils`）全部正常，登录 bash 正常；30 次 `ls` 脚本 0.59s vs 直调 coreutils 0.48s。`busybox:latest` 走符号链接兜底，落盘 4,221KB、容器内 `ls`/`cat` 正常。TarTest 25→30（新增兜底三分支 + 无 `/bin/sh` 拷贝） |
| 3.10.0 推送前评审修复（2026-11-27，代码级 + 真机抽验） | 复核 ahead 22 提交，修复 8 项：① 退出回调判空（listener 已摘时会话退出不再 NPE 连坐进程）；④ a11y 加可见内容指纹 + 节流补发（进度条 \r 重绘可播报）；⑤ CSI 3 J 改走 trimScrollback 推进 evictedRows（EmuTest 新用例）；⑥ fling 只叠加位移增量不覆盖锚定补偿；② 解包三处防逃逸（硬链接目标 .. / canonical 校验、符号链接退路 rootfs 校验、destRoot 前缀分隔符）；③ pull 加静态锁串行化（对话框/控制台双入口）;⑦ 畸形 digest 报错不再 substring 越界；⑧ pax/GNU 长链接名解析 + shim applet 名白名单。七套回归全绿（Tar 32 / Emu 含 CSI 3 J），Release 构建成功；真机 `clear` 后输出无错位 |
| v3.10.0 CI 失败修复（2026-11-27，Linux 回归） | 首次 Release run `45962646025` 在 Linux 上 TarTest 两例失败，而本地 Windows 七套全绿——Windows 建不了符号链接走「实体化退路」，把真实缺陷掩盖了。根因：逃逸检查用 `out.getCanonicalPath()` / `src.getCanonicalPath()` 跟随**目标自身**的 guest 绝对路径符号链接（`/bin/busybox`、`/usr/bin/coreutils`），canonical 解析到宿主根被误判为逃逸：「实体文件顶替符号链接」的 REGULAR 内容没写进 rootfs，「硬链接兜底」抛 `NoSuchFileException .../t18/usr/bin/ls`。修复 `764d0b5` 改为**只校验父目录 canonical**（真逃逸只可能来自父目录里指向 rootfs 外的链接），目标自身的链接交给 `dropSymlink` / `resolveInsideRootfs` 按 rootfs 语义处理；安全边界保留（`..` 穿越、hardLinkNoEscape 用例仍过）。真 Linux 容器（`eclipse-temurin:17-jre`）复验：修复版 TarTest 32 PASS / 0 FAIL（含此前 Windows 上被 SKIP 的两条符号链接分支），未修复版对照复现同一异常（exit=1），与 CI 日志一致。tag `v3.10.0` 强移到修复提交重推后 run `45965350657` success，产物 `dualterm-3.10.0.apk`（564,011 字节）+ `SHA256SUMS`。**教训：涉及符号链接/权限的代码，Windows 本地全绿不可信，必须真 Linux 容器或 CI 验证** |
| 工具链升级 A+B（2026-11-28，本地构建 + 三真机 API 30/31/36） | **A**：`CMakeLists.txt` 显式加 `-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`，实测 `libptyterm.so` 的 `PT_LOAD p_align` 由 `0x1000` 变为 `0x4000`，APK 内 15 个 so 中全部 64 位（arm64/x86_64）≥16KB，`zipalign -c -P 16` 通过；新增 `tools/check_16k_align.py` 并在 CI 增加同名校验步骤。**B**：AGP 8.2.2→8.13.2、Gradle 8.7→8.14.8、compileSdk/targetSdk 34→36、Build Tools 34.0.0→36.0.0、NDK 26.1→28.2.13676358，JDK 保持 17。targetSdk 36 适配：manifest 显式 `enableOnBackInvokedCallback="true"`，MainActivity 注册 `OnBackInvokedDispatcher` 回调并把返回逻辑抽为 `handleBack()`（API 33+ 走回调、API 26-32 仍走 `onBackPressed`）。真机：**Android 16（PJR110，API 36）** 启动 717ms 无异常，返回键两级正确（第 1 次收软键盘且 Activity 不退、第 2 次退到 launcher 且进程存活；菜单二级→顶层→收起），竖屏与横屏 edge-to-edge 均无状态栏/导航栏遮挡（横屏 103x13、键条完整）；**Android 12（M2010J19SC）/ Android 11（Redmi Note 8 Pro）** 启动无 `NoClassDefFoundError`/`VerifyError`，返回一次即退 launcher 且进程保活。构建链：Gradle 8.14.8 + AGP 8.13.2 组合 `assembleRelease` 成功、`assembleDebug` 成功，`--warning-mode all` 弃用警告清零（`app/build.gradle` 空格赋值改 `=`）。七套回归全绿（Emu ALL PASS / Sec 42 / Tar 32 / Import 35 / Ports 36 / Registry 90 / Kbd 137，均用 android-36 的 jar 编译运行）。未做真 Linux 容器复验：本次未改 `TarGzExtractor`，且 v3.10.0 已在真 Linux 验证同一份解包代码，Windows 本地无符号链接能力只影响那两条 SKIP 用例 |

未经真机验证的部分：Docker 后端（缺少带 namespace 内核的设备）、整包 `ImageImporter.importArchive`（依赖 Context 与真 `org.json`，仅有单元级测试）。
