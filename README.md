# DualTerm · 双态终端

Android 终端模拟器。一个 APK 提供两种会话模式：

| 模式 | 运行载体 | 隔离级别 | 前置条件 |
|---|---|---|---|
| **本地** | Android 自带 `/system/bin/sh` | 无额外隔离，身份为应用沙箱 `u0_aXXX` | 安装即用；可选 `su` 提权 |
| **容器** | 独立 Alpine rootfs，PRoot 用户态路径重定向 | 独立文件系统 + 假 root，**非内核级隔离** | 首次进入自动解包；或预先运行运行时脚本 |

两种会话各自常驻，来回切换不丢现场。界面随输入设备自适应：纯触屏时提供键条与自绘键盘，接入物理键盘后自动收起。

## 能力

- **终端仿真**：VT100/xterm 子集，`vim`、`htop`、`tmux`、`less` 可正常使用（DEC 画线字符集、备用屏、鼠标上报、括号粘贴、BCE、组合字符等）。
- **输入**：外接键盘全键映射（`Ctrl`/`Alt` 组合、`F1`–`F12`、`Ctrl+方向键`）；自绘软键盘与更多键面板；一次性修饰键；多行粘贴确认。
- **容器**：PRoot 独立 rootfs（Alpine），可在应用内创建、启动、停止、删除容器与镜像；支持 `docker save` / `export` / OCI 归档导入；附带 docker 语法子集的控制台。
- **服务可见性**：容器进程与系统共用网络栈，容器内监听的服务对整个系统可见；`-p` 声明同端口直通并给出访问指引。
- **会话保活**：存在活跃会话时以前台服务加部分唤醒锁维持进程，切后台不被冻结。
- **零第三方依赖**：仅 Framework API、一个约 200 行的 C 文件（PTY）与手写 tar.gz 解包器。

## 安装

从 [Releases](https://github.com/yingquelou/dualterm/releases) 下载 `dualterm-<版本>.apk`。

发布包的签名方式见 [docs/building.md](docs/building.md#发布)。覆盖安装要求与已装版本同签名，debug 包与本工程 Release 包互装需先卸载。

## 从源码构建

```bash
# 容器运行时（可选，只影响容器模式）
bash scripts/fetch-runtime.sh                      # Linux / macOS / Git Bash
powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1   # Windows

./gradlew assembleDebug
```

环境要求、桌面回归测试与发布流程见 [docs/building.md](docs/building.md)。

## 文档

| 文档 | 内容 |
|---|---|
| [docs/usage.md](docs/usage.md) | 界面构成、键条、手势、菜单、会话结束与返回键行为（应用内「帮助」指向此篇） |
| [docs/containers.md](docs/containers.md) | 容器与镜像模型、管理命令、端口直通、会话保活、Docker 后端取舍 |
| [docs/internals.md](docs/internals.md) | 工程结构、PTY 与仿真器实现、xterm 对齐记录、容器启动前提、远程调试入口 |
| [docs/building.md](docs/building.md) | 构建环境、五套桌面回归、发布流水线与签名 |
| [docs/limitations.md](docs/limitations.md) | 已知限制与后续计划 |
| [docs/roadmap.md](docs/roadmap.md) | 后续开发的取舍评估：候选功能的边界、成本与不做/缓做的理由 |
| [docs/verification.md](docs/verification.md) | 实机验证记录 |

## 设计取向

- **不做内核级隔离**：PRoot 的作用是给出独立文件系统与假 root，代价是与宿主共内核、共享 `/proc` 与网络栈。真正的隔离需要设备运行带 namespace 的内核。
- **不做端口映射**：两条后端都没有 NAT，`-p` 只接受同端口直通，重映射在创建时即拒绝，避免运行后静默连不上。
- **容器内没有 docker 命令**：管理动作在应用进程内直接调用，去掉跨 proot 的 IPC 与同名冲突。
- **Docker 后端为实验性**：多数手机内核未开启 namespace，`dockerd` 无法启动；探测失败会明确报错并提示切回 PRoot。

## 许可

MIT，见 [LICENSE](LICENSE)。
