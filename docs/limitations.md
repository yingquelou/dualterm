# 已知限制与后续计划

## 已知限制

1. **本地模式在无 root 时可用命令很少**：只有 `/system/bin` 里那些（`sh ls cat ps ping ip am pm dumpsys`…），`ls` 的输出格式也不是 GNU 的。需要完整 userland 请用容器模式。
2. **容器不是内核隔离**：共享内核与 `/proc`，没有 cgroup 限额，`nsenter` 之类不可用。真隔离需要更换内核。
3. **`/sdcard` 绑定默认不可完整访问**：Android 11+ 分区存储，完整访问需 `MANAGE_EXTERNAL_STORAGE`，应用内通过菜单「存储权限」申请。
4. **proot 依赖 Termux 的 bionic 构建**：脚本从 Termux 官方源获取，避免自行交叉编译。更换源时需注意 proot 版本与内核 `ptrace` 策略的兼容性（部分 ROM 需要 `PROOT_NO_SECCOMP=1`）。
5. **Docker 后端未经真机验证**：代码路径完整（探测 → 启 dockerd → `docker run -it --net=host`），但没有带 namespace 内核的设备可测，按实验性功能对待。固定 host 网络的取舍见 [containers.md](containers.md#docker-后端为什么不是默认)。
6. **运行时按 ABI 单独准备**：`scripts/fetch-runtime.*` 默认只处理一个 ABI。未准备的 ABI 上容器模式会提示缺运行时；本地模式不受影响。发布流水线会准备三个 ABI。
7. **本地模式的 `PS1` 会被 `/system/etc/mkshrc` 覆盖**：因此提示符是 Android 默认的 `:当前目录$`，而非工程设置的值。纯外观问题。
8. **软键盘弹出时终端会被压扁**：横屏全键盘可占 600dp 以上，`adjustResize` 之后终端只剩两三行（辅助键盘已收敛为固定区 + 可自定义区共 5 行，比旧「键盘 + 面板」两套浮层更矮）。接入物理键盘时不触发。
9. **部分终端协议有意不实现**：`IRM` / `LNM`、`modifyOtherKeys` 与 kitty 键盘协议、OSC 0/2 标题、OSC 8 超链接、Sixel 图片、同步输出 `?2026`、ZWJ emoji 合成序列。取舍理由见 [internals.md](internals.md#与-xterm-对表的取舍)，这些程序都会静默降级。OSC 52 不在此列，已在计划中（见 [roadmap.md](roadmap.md)）。
10. **软键盘与截图**：终端 inputType 曾误用 `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`，中文输入法因此切安全键盘（`FLAG_SECURE`，截图被系统拒绝、键位残缺）。已改回标准文本输入（不联想 + 多行），普通输入法窗口可正常截图；若截图仍被拒，先确认用的是标准键盘而非密码形态。

## 后续计划

逐项的可行性评估、实现边界与不做/缓做的理由统一收在 [roadmap.md](roadmap.md)。
速览：

- **做**：文本选中与复制（长按锚点 + 拖动 + 跨 scrollback，P1 缺口）
- **缓做**：OSC 52（默认关，等选区落地后一起做）
- **不做**（触发条件后重评）：终端间直达、Intent 外部调用、rootfs 换 Debian（用「导入镜像」通路替代）、MainActivity 全量拆分
