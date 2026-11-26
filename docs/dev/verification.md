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
| 发布前构建校验（2026-11-26，本地） | `DT_VERSION_NAME=3.10.0 DT_VERSION_CODE=31000 assembleRelease` 成功；`aapt2 dump badging`：`dev.dualterm` versionName `3.10.0` / versionCode `31000`、native-code 三 ABI；APK 内 `lib/{arm64-v8a,armeabi-v7a,x86_64}/libproot.so` 齐全，体积 547KB；七套桌面回归全过（EmuTest ALL PASS / Sec 42 / Tar 24 / Import 35 / Ports 36 / Registry 73 / Kbd 137）。本次本地包为测试签名，正式包由 CI 注入 Secrets 私钥 |
| 选区手柄尺寸可配置（2026-11-26，M2010J19SC） | 「外观 → 选区手柄：杆 9dp / 半径 7dp」打开三输入框（默认 9/7/22，范围校验生效）；杆长改 20 确认后 prefs `handle_stem_dp=20` 落盘，长按选词截图中手柄杆明显加长；改回 9 后 prefs 复原 |
| AltGr 字符修复（2026-11-26，代码级） | `handleKeyCode` 改为「含 Alt 与不含 Alt（保留 Shift）的 KeyCharacterMap 字符不同且可见 → 判为 AltGr，只发字符不加 ESC」。US 布局 Alt+E / Alt+Shift+E 仍走 ESC 前缀（字符相同）；用户复测：法语 AltGr+E→`€` 通过，US 的 Ctrl+Alt+S→`ß` 为 AltGr 语义（与系统文本框一致），非回归 |
| 手动测试结论回收（2026-11-26，用户实机） | E13 TalkBack 按逻辑行朗读通过；H1 端点重锚手感基本没问题；E7 手柄落地前测过、代码复核 shiftSelect 分支未动；F3 `"+yy` 未进剪贴板，判定为 vim 侧能力/配置（命令已写入 manual-test）；G4 AltGr 已修复并复测：法语 `€` 通过，US 的 Ctrl+Alt+S→`ß` 属标准布局语义 |
| 拉取镜像非模态（2026-11-26，M2010J19SC） | 拉 alpine:3.21 期间顶栏下方实体显示进度、无全屏遮罩；点 ⋮ 菜单正常弹出、点终端输入法正常弹起；完成后进度条收起且镜像注册（prefs 可见），测试镜像已删除、镜像表复原 |
| MainActivity 拆分：会话选择器 → `ui/SessionPicker`（2026-11-26，M2010J19SC） | 点模式 chip 弹出 `● 容器 · ubuntu · pid 29771` + 「＋ 新建本地 Shell / ＋ 新建容器会话（ubuntu）/ 容器与镜像…」；点当前会话项正常收起并保持贴屏；再点「容器与镜像…」打开容器列表（标题与内容完整）；七套桌面回归全过 |

未经真机验证的部分：Docker 后端（缺少带 namespace 内核的设备）、整包 `ImageImporter.importArchive`（依赖 Context 与真 `org.json`，仅有单元级测试）。
