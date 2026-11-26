# DualTerm · 双态终端

Android 终端模拟器。一个 APK 提供两种会话模式：

| 模式 | 运行载体 | 隔离级别 | 前置条件 |
|---|---|---|---|
| **本地** | Android 自带 `/system/bin/sh` | 无额外隔离，身份为应用沙箱 `u0_aXXX` | 安装即用；可选 `su` 提权 |
| **容器** | 独立 rootfs（镜像由应用内拉取/导入），PRoot 用户态路径重定向 | 独立文件系统 + 假 root，**非内核级隔离** | 先获取镜像：应用内拉取 Alpine（联网，约 3MB）或导入 docker save / export 的 tar；proot 运行时已随包内置 |

多标签会话互不干扰、来回切换不丢现场；会话 id 即终端根进程 pid，`ps` 可对照。界面随输入设备自适应：纯触屏时输入由系统输入法（主键盘）与辅助键盘（固定区：编辑键/F1–F12；可自定义区：控制字符/shell 符号）组合承担，接入物理键盘后软键盘自动收起。

## 能力

- **终端仿真**：VT100/xterm 子集，`vim`、`htop`、`tmux`、`less` 可正常使用（DEC 画线字符集、备用屏、鼠标上报、括号粘贴、BCE、组合字符等）。
- **输入**：外接键盘全键映射（`Ctrl`/`Alt` 组合、`F1`–`F12`、`Ctrl+方向键`）；系统输入法 + 辅助键盘（编辑键/F 键固定区、控制字符/shell 符号可自定义区）组合；一次性修饰键；多行粘贴确认。
- **文本选区**：长按即按词选中（空白/标点边界，中文逐字；词内抖动不收缩），拖动扩展（跨屏幕与 3000 行 scrollback，边缘自动滚动），两端手柄可拖拽微调；浮动条跟随选区而非固定在顶部；软换行拼行、行尾空白剥除，与桌面终端复制语义一致。
- **滚动与视口**：拖动带惯性；视口按绝对行号记录，Activity 重建与切换标签后回到原位置，后台输出不会把视口推走。
- **无障碍**：终端文本按可见逻辑行暴露为只读节点（续行自动拼接、空白行跳过、内容变化节流通知），TalkBack 与 `uiautomator dump` 可直接读取；滚出视口的历史不在节点内。
- **程序写剪贴板（OSC 52）**：vim `"+y`、tmux copy-mode 直写系统剪贴板；菜单开关默认关、仅前台写入、只写不读、单次 64KB 上限。
- **容器**：PRoot 独立 rootfs（镜像不随包分发，体积优先），可在应用内拉取镜像、导入 `docker save` / `export` / OCI 归档，创建、启动、停止、删除容器与镜像；附带 docker 语法子集的控制台。
- **多标签会话**：任意多个本地 / 容器会话并行，模式 chip 下拉即会话列表；重启与停止精确到单个标签页。
- **服务可见性**：容器进程与系统共用网络栈，容器内监听的服务对整个系统可见；`-p` 声明同端口直通并给出访问指引。
- **会话保活**：存在活跃会话时以前台服务加部分唤醒锁维持进程，切后台不被冻结。
- **零第三方依赖**：仅 Framework API、一个约 200 行的 C 文件（PTY）与手写 tar.gz 解包器。

## 安装

从 [Releases](https://github.com/yingquelou/dualterm/releases) 下载 `dualterm-<版本>.apk`。

发布包的签名方式见 [docs/dev/building.md](docs/dev/building.md#发布)。覆盖安装要求与已装版本同签名，debug 包与本工程 Release 包互装需先卸载。

## 从源码构建

```bash
# 容器运行时（可选，只影响容器模式）
bash scripts/fetch-runtime.sh                      # Linux / macOS / Git Bash
powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1   # Windows

./gradlew assembleDebug
```

环境要求、桌面回归测试与发布流程见 [docs/dev/building.md](docs/dev/building.md)。

## 文档

文档分两类：**用户文档**（docs/ 根目录，讲怎么用）与**开发者文档**（docs/dev/，讲怎么实现/构建/测试）。两者可互相引用，但内容不混排。

**用户文档：**

| 文档 | 内容 |
|---|---|
| [docs/usage.md](docs/usage.md) | 界面构成、键条与自定义键、手势、菜单、会话结束与返回键行为（应用内「帮助」指向此篇） |
| [docs/containers.md](docs/containers.md) | 容器与镜像模型、管理命令、端口直通、会话保活、Docker 后端取舍 |
| [docs/limitations.md](docs/limitations.md) | 已知限制 |

**开发者文档（docs/dev/）：**

| 文档 | 内容 |
|---|---|
| [docs/dev/internals.md](docs/dev/internals.md) | 工程结构、PTY 与仿真器实现、xterm 对齐记录、容器启动前提、远程调试入口 |
| [docs/dev/building.md](docs/dev/building.md) | 构建环境、七套桌面回归、发布流水线与签名 |
| [docs/dev/roadmap.md](docs/dev/roadmap.md) | 后续开发的取舍评估：候选功能的边界、成本与不做/缓做的理由 |
| [docs/dev/verification.md](docs/dev/verification.md) | 实机验证记录 |
| [docs/dev/pull-registry.md](docs/dev/pull-registry.md) | docker pull 泛化与镜像源设计定稿 |
| [docs/dev/manual-test.md](docs/dev/manual-test.md) | 手动测试清单（桌面回归之外的真机交互项） |

## 设计取向

- **不做内核级隔离**：PRoot 的作用是给出独立文件系统与假 root，代价是与宿主共内核、共享 `/proc` 与网络栈。真正的隔离需要设备运行带 namespace 的内核。
- **不做端口映射**：两条后端都没有 NAT，`-p` 只接受同端口直通，重映射在创建时即拒绝，避免运行后静默连不上。
- **容器内没有 docker 命令**：管理动作在应用进程内直接调用，去掉跨 proot 的 IPC 与同名冲突。
- **Docker 后端为实验性**：多数手机内核未开启 namespace，`dockerd` 无法启动；探测失败会明确报错并提示切回 PRoot。

## 许可

MIT，见 [LICENSE](LICENSE)。
