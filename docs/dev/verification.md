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

未经真机验证的部分：Docker 后端（缺少带 namespace 内核的设备）、整包 `ImageImporter.importArchive`（依赖 Context 与真 `org.json`，仅有单元级测试）。
