# 后续开发与取舍评估

候选功能的可行性评估与实现边界。硬约束适用于所有条目，先读最后一节。
每项给出「做 / 缓做 / 不做」的结论与触发重评的条件；已完成的项移入文末记录。

## 一、文本选中与复制 —— 做（唯一的 P1 功能缺口）

现状只有「复制全部输出」「复制当前屏」两个整块入口，想复制一个 URL / token
只能复制整屏再手动删。这是手机终端最痛的缺口。

**已具备的基础**（不用新建）：

- `TerminalView.longPressRunnable` 已空出（菜单已移走，注释明说留给选区）
- `scrollOffset` 虚拟滚动 + `getScrollbackLine(index)` 可取任意历史行
- `drawLine` 已按属性分段绘制，选区高亮只需按行叠一层半透明矩形
- `Line`（text/fg/bg/attr/extra）可直接取文本

**实现边界**：

做：长按进入选择（锚点落在长按处）→ 拖动扩展 → 跨屏幕与 scrollback →
拖到屏幕上下边缘自动滚动 → 浮动条（复制 / 全选 / 取消）。
取文本新增 `TerminalEmulator.getSelectionText()`，带锁读取，剥尾随填充、
软换行行拼接，复用 reflow 的 `isTrailingPadding` 逻辑。

不做（第一期）：双击选词、三击选行、放大镜与文本手柄、键盘 Shift+方向选择。
鼠标上报模式（vim `set mouse=a`）下长按仍归程序，选区不启用——程序优先。

**手势插入点**：`onTouchEvent` 已有四条优先级链（回到底部胶囊 → 捏合 →
鼠标上报 → 本地滚动）。选区插在鼠标上报之后、本地滚动之前；
长按已与拖动互斥（`longPressFired` / `dragging`），这条是现成的。

**风险**：拖动期间每帧重绘需节流（选区端点变化才 `invalidate`）；
选中跨 scrollback 时虚拟行号要统一成「绝对行号 = scrollbackCount - scrollOffset + row」。

## 二、OSC 52 —— 缓做（等选区落地后一起做）

让 vim 的 `"+y`、tmux 的 copy-mode 写入系统剪贴板。

**现状**：`handleOsc` 已有完整解析与 reply（OSC 10/11/12/4），加一个分支即可；
`osc` 缓冲上限 64 字符需放宽（OSC 52 的 base64 远超此数，给 100KB 上限防滥用）；
`android.util.Base64` 解码，零依赖可满足。

**安全边界（必须全部满足）**：

- 菜单开关「允许程序写剪贴板」，默认关
- 仅应用在前台时写入（Android 后台本就受限）
- 只支持 `c`（系统剪贴板）目标，`c2` 等其它目标直接忽略
- 解码后 > 64KB 截断

**结构约束**：`TerminalEmulator` 是纯 Java（桌面可跑 EmuTest），不能引入
Context。写入必须经 listener 回调（Emulator → TermSession → Activity），
与现有 `reply` 的出站路径同构。

## 三、多标签会话 —— 不做（触发条件：单容器会话不够用的真实场景）

**为什么不做**：会话 key 是 mode 字符串（`sessions.get("local"/"container")`），
每模式一个会话贯穿 `switchMode` / `startContainer`（内部 `dropSession(MODE_CONTAINER)`
强杀旧会话）/ `SessionService` 计数 / 崩溃恢复 / 误杀防护。改成唯一 id 是会话层
重写，回归面覆盖最近三轮刚修完的进程清理与防误杀。而「每模式一会话」符合
移动端习惯：绝大多数场景就是一个 shell。

**若将来要做**，先做这三件事打底：会话 key 抽象成 id（mode 只是属性之一）、
切换 UI 从两选一改成列表、`startContainer` 的 drop 改成显式用户决定。

## 四、终端间直达通道 —— 不做（多标签的衍生需求）

「粘贴到其它会话」在只有两个会话时价值趋近于零，且系统剪贴板中转已经够用
（实测全通）。与多标签同进退，不单独排期。

## 五、Intent 外部调用（`am start -a RUN_COMMAND`）—— 不做

外部应用可注入命令到终端，是安全事故面；Android 11+ 还要处理包可见性与
调用方白名单。没有明确的自动化诉求之前不引这个口子。
若将来做：仅 local 模式、仅显式声明的调用方、每次弹确认。

## 六、rootfs 换 Debian/Ubuntu —— 不做（已有替代通路）

代码层零障碍：`ContainerBootstrap` 只做解包 + 写 `resolv.conf`，与发行版无关；
`TarGzExtractor` 已支持 symlink。不做的原因是体积——Alpine pack 8.8MB × 3 ABI，
Debian minirootfs 约 30MB，装进 APK 会到 40MB+。

**替代通路已存在**：菜单「导入镜像（docker save/export 的 tar）」可直接导入
Debian/Ubuntu rootfs，按需自取，不占安装包。脚本（`scripts/fetch-runtime.*`）
如需换源，改 URL 与架构映射即可，无需动 Java。

## 七、MainActivity 拆分 —— 不做独立项（触发条件：有 UI 自动化兜底）

2519 行 / 17 个分区，结构尚清晰。专门的拆分是纯搬移 + 零行为变化，但没有任何
UI 自动化测试兜底，全靠实机手测回归，风险收益比不划算。

**已评估并放弃的方案**：把菜单/键条构建抽成独立类——`onMenuClick` 的百余行
业务逻辑搬不走（深度耦合 `switchMode` / `sessions` / 容器对话框），抽构建部分
只能引入 Host 接口间接层，主文件省不到 200 行，还添一层跳转。不值得。

菜单分 6 组、键条 17 键这两处已经是「大文件里职责最独立」的块，拆分等有
UI 测试后按分区逐块做，每块一个提交。

## 硬约束（后续任何条目开工前先对一遍）

1. **零第三方依赖**：`build.gradle` 的 dependencies 块故意留空，整个 App 只用
   Framework API。菜单/对话框/浮条只能用 `PopupMenu` / `AlertDialog` / 自绘。
2. **`TerminalEmulator` 保持纯 Java**：桌面 EmuTest 是唯一的模拟器回归手段，
   引入 Android 类型等于放弃回归。
3. **APK 体积**：三 ABI 各一份 proot + rootfs 已 10.9MB。往 assets/nativeLibrary
   塞任何东西都先算 ×3。
4. **会话层单例是刻意的**：动 `sessions` / `dropSession` / `SessionService` 前
   先读 internals.md 的进程清理章节，确认不破坏 HUP → 补杀 → KILL 链。

## 已完成记录

- 2026-11 主菜单分 6 组子菜单（横屏 360dp 下平铺 19 项被裁，后半段不可点）
- 2026-11 键面板补 F7–F12（xterm 标准序列 `ESC[18~`–`ESC[24~`，17 键 3 行）
- 2026-11 剪贴板三向评估的 6 项修复：粘贴净化 / 异步分段写 / 复制裁尾空行 /
  权限拒绝 NPE（详见 git log fb75af9）
