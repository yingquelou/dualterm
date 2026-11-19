# 已知限制与后续计划

## 已知限制

1. **本地模式在无 root 时可用命令很少**：只有 `/system/bin` 里那些（`sh ls cat ps ping ip am pm dumpsys`…），`ls` 的输出格式也不是 GNU 的。需要完整 userland 请用容器模式。
2. **容器不是内核隔离**：共享内核与 `/proc`，没有 cgroup 限额，`nsenter` 之类不可用。真隔离需要更换内核。
3. **`/sdcard` 绑定默认不可完整访问**：Android 11+ 分区存储，完整访问需 `MANAGE_EXTERNAL_STORAGE`，应用内通过菜单「存储权限」申请。
4. **proot 依赖 Termux 的 bionic 构建**：脚本从 Termux 官方源获取，避免自行交叉编译。更换源时需注意 proot 版本与内核 `ptrace` 策略的兼容性（部分 ROM 需要 `PROOT_NO_SECCOMP=1`）。
5. **Docker 后端未经真机验证**：代码路径完整（探测 → 启 dockerd → `docker run -it --net=host`），但没有带 namespace 内核的设备可测，按实验性功能对待。固定 host 网络的取舍见 [containers.md](containers.md#docker-后端为什么不是默认)。6. **运行时按 ABI 单独准备**：`scripts/fetch-runtime.*` 默认只处理一个 ABI。未准备的 ABI 上容器模式会提示缺运行时；本地模式不受影响。发布流水线会准备三个 ABI。
7. **本地模式的 `PS1` 会被 `/system/etc/mkshrc` 覆盖**：因此提示符是 Android 默认的 `:当前目录$`，而非工程设置的值。纯外观问题。
8. **软键盘弹出时终端会被压扁**：横屏下输入法本体可占 600dp 以上，`adjustResize` 之后终端只剩两三行；辅助键盘默认再叠 2 行固定区 + 4 行自定义区（菜单「辅助键盘行数」可降到 2 行）。接入物理键盘时不触发。
9. **部分终端协议有意不实现**：`IRM` / `LNM`、`modifyOtherKeys` 与 kitty 键盘协议、OSC 0/2 标题、OSC 8 超链接、Sixel 图片、同步输出 `?2026`、ZWJ emoji 合成序列。取舍理由见 [dev/internals.md](dev/internals.md#与-xterm-对表的取舍)，这些程序都会静默降级。OSC 52 不在此列，已在计划中（见 [dev/roadmap.md](dev/roadmap.md)）。
10. **软键盘与截图**：终端 `inputType` 曾误用 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`，中文输入法因此切安全键盘（`FLAG_SECURE`，截图被拒、键位残缺）。现已改用终端模拟器的标准做法 `TYPE_NULL`（Termux 同款），输入法不会进入密码形态，截图正常。若截图仍被拒，先确认输入法没有切到安全/密码键盘。
11. **容器内 `groups` / `id` 报 `cannot find name for group ID 3003` 之类**：proot 只用 `-0` 伪造 uid/gid（假 root），**不伪造补充组列表**——进程真实身份仍是安卓应用的 `u0_aXXX`，宿主补充组 GID 原样继承。实测（M2010J19SC，`/proc/<pid>/status` 的 `Groups` 与容器内 `id` 输出逐一对上）：`3003`(inet)、`9997`(everybody)、`20277`/`50277`（Android 按 appId 生成的 per-app cache/shared 组 = 20000+277 / 50000+277）。Android 自身不维护 `/etc/group`（宿主上是 0 字节空文件，组名编译在 bionic 里），容器发行版的 `/etc/group` 自然也没有这些 GID，glibc 查不到名字就逐条打印。**纯显示层报错，不影响权限判定与文件访问**，可忽略；想让输出干净就在容器 `/etc/group` 补对应 GID 条目（具体数字随设备/appId 变化）。

## 后续计划

逐项的可行性评估、实现边界与不做/缓做的理由统一收在 [dev/roadmap.md](dev/roadmap.md)。
速览：

- **已完成**：文本选中与复制（长按拖选 + 浮动条，实机验证过）
- **缓做**：OSC 52（默认关；选区已落地，随时可开工）
- **不做**（触发条件后重评）：终端间直达、Intent 外部调用、rootfs 换 Debian（用「导入镜像」通路替代）、MainActivity 全量拆分
