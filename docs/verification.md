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

未经真机验证的部分：Docker 后端（缺少带 namespace 内核的设备）、整包 `ImageImporter.importArchive`（依赖 Context 与真 `org.json`，仅有单元级测试）。
