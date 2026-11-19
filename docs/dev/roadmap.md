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

**实现边界**（对照 xterm Selections / VTE / Windows Terminal / Termux 核实）：

做：长按进入选择（锚点落在长按处）→ 拖动扩展 → 跨屏幕与 scrollback →
拖到屏幕上下边缘自动滚动 → 浮动条（复制 / 全选 / 取消）。
取文本新增 `TerminalEmulator.getSelectionText()`，带锁读取，剥尾随填充、
软换行行拼接，复用 reflow 的 `isTrailingPadding` 逻辑。

标准对比后补充（2026-11-19）：

- **Shift 覆盖鼠标上报（xterm「shift override」惯例，VTE 同）**：程序开了
  鼠标上报时，硬件键盘 Shift+按下/拖动走本地选区；触屏没有 Shift，
  长按仍是唯一入口，触摸归程序不变（维持「程序优先」）。
- **锚点稳定性**：选区锚在「全局行号 = 逐出计数 + 绝对行号」上，新输出把
  历史行挤出 scrollback 时选区不漂移，被逐出的部分自动裁掉（xterm 语义）。
- **清除时机**：选中态下单击清除选区（不发键盘）；任意按键/序列输入清除
  选区（xterm：key press discards selection）；复制后结束选择态（Termux 惯例）。
- **复制语义核实**：线性选区复制时软换行行拼回一行（不补 `\n`）、行尾空白
  逐行剥除——与 reflow 的 `isTrailingPadding`/`wrapped` 同源，直接复用。

不做（第一期）：双击选词、三击选行——桌面惯例成立的前提是「单击不承担
弹键盘」，触屏上「点终端=弹键盘」与之直接冲突，长按拖选已覆盖其用途；
将来若提供「点击不弹键盘」配置再评。放大镜与文本手柄、键盘 Shift+方向
选择维持不做。
鼠标上报模式（vim `set mouse=a`）下触摸仍归程序，选区不启用——程序优先
（Shift 直通除外，见上）。

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

## 三、多标签会话 —— 已完成（2026-11）

会话 id 采用**根进程 pid**（用户建议，评估后采纳）：

- pid 由内核保证存活期内唯一，logcat/adb 调试时 id 与 ps 直接对上；
- 代价是「start() 成功前 id 不存在」——会话表改为先 spawn 再入册，
  start 失败则无 tab（本来也没有可展示的会话）；
- pid 会复用，但 tab 退出立即摘表（onExit 同步移除），复用无从撞上。

身份与状态分离：`Tab { session, mode, container }`，`mode` 从全局状态
降级为 tab 属性；「同类 tab 已存活就切换贴屏，绝不暗杀别的 tab」是
切换的唯一语义，restart 只精确作用于目标 tab（重启单个会话不波及同类）。
UI：模式 chip 下拉即会话列表（`●` 标当前）+ 新建本地/容器入口；
`startContainer` 的 drop 已改成显式的用户动作（容器管理里的启动/停止）。

## 四、终端间直达通道 —— 不做（多标签已落地，价值重估后仍缓）

「粘贴到其它会话」有系统剪贴板中转已经够用（实测全通），直达通道的增量
价值主要在多 tab 频繁协作的场景。等真实使用中出现「来回切 tab 粘贴太烦」
再排期。与选区复制（P1）相比优先级低。

## 五、Intent 外部调用（`am start -a RUN_COMMAND`）—— 不做

外部应用可注入命令到终端，是安全事故面；Android 11+ 还要处理包可见性与
调用方白名单。没有明确的自动化诉求之前不引这个口子。
若将来做：仅 local 模式、仅显式声明的调用方、每次弹确认。

## 六、rootfs 换 Debian/Ubuntu —— 已解决前置障碍（2026-11：rootfs 移出 APK）

体积顾虑已消除：APK 不再内置任何 rootfs（原 Alpine pack ×3 ABI ≈ 10MB，
移出后 APK 10.93MB → 512KB）。镜像统一由用户获取：应用内 `docker pull`
（Alpine 官方源，泛化见第八节）或导入 docker save / export 的 tar，容器再从镜像物化；
**在册容器初始为空**，首次进容器模式弹引导。

因此换发行版的代码层障碍本来就为零（`ContainerBootstrap` 与发行版无关），
现在连体积障碍也没了——想用 Debian 就导入 Debian rootfs，不需要改 Java。
`scripts/fetch-runtime.*` 只准备 proot（rootfs pack 仅 `WITH_ROOTFS=1` 的
离线调试场景）。

## 七、MainActivity 拆分 —— 不做独立项（触发条件：有 UI 自动化兜底）

2519 行 / 17 个分区，结构尚清晰。专门的拆分是纯搬移 + 零行为变化，但没有任何
UI 自动化测试兜底，全靠实机手测回归，风险收益比不划算。

**已评估并放弃的方案**：把菜单/键条构建抽成独立类——`onMenuClick` 的百余行
业务逻辑搬不走（深度耦合 `switchMode` / `sessions` / 容器对话框），抽构建部分
只能引入 Host 接口间接层，主文件省不到 200 行，还添一层跳转。不值得。

菜单分 6 组、键条 17 键这两处已经是「大文件里职责最独立」的块，拆分等有
UI 测试后按分区逐块做，每块一个提交。

## 八、docker pull 泛化到 Docker Hub —— 已完成（2026-11，实机验证过）

包体积优化后 `docker pull` 成了镜像唯一的内置获取通路，而它只认 Alpine
官方源——「想用 Debian」的实际答案是去电脑上 docker save 再拷 tar，
这条弯路值得抹平。设计定稿与实现细节见 pull-registry.md：

- 分发收在 `Containers.pull` 内部（UI 与控制台共用）：alpine 版本标签走
  dl-cdn 快路径原样保留，其余引用走 `RegistryPuller`（匿名 token、
  manifest list 按设备 ABI 选平台、blob 边下边验 sha256、串行下载一层叠一层）；
- **只收 gzip / 未压缩 tar 层，拒 zstd**（native 解码器与零依赖、512KB
  两条硬约束冲突），遇 zstd 报错指路导入通路；
- 第一版不做：digest 引用、ghcr/quay 等第三方 registry、登录鉴权、续传；
- 纯判定逻辑（ref 解析 / 平台选择 / mediaType 白名单 / 换源归一化 /
  WWW-Authenticate 解析）抽 static 纯函数，桌面 `RegistryTest`（73 用例）
  回归；JSON 与流下载已实机验证；
- APK 增量≈0（512KB → 530KB，仍远低于旧基线 10.93MB）。

**镜像源（换源）**：官方 Hub 在大陆经常被 DNS 污染（实测 401 前就连接
超时），同批加了换源能力——Hub / Alpine 两路各自可配（菜单「镜像源设置…」
或 `docker mirror`），registry 端点指向镜像站，401 时按应答
`WWW-Authenticate` 的 realm 取 token（镜像站自报端点，**不依赖
auth.docker.io**）。实机：debian:12 经 dockerproxy.net 约 50s、
busybox 经 1ms / daocloud 45s / 23s、alpine 换清华源 12s，全部拉取成功。
详见 pull-registry.md 第八节。

配套统一（见「容器管理统一走 Containers 单一接口」提交）：活动容器落位
下沉 `Containers.remove`，控制台 rm 守卫对齐多 tab（RUNNING 真源），
默认容器名 `suggestName` 两入口共用。

## 硬约束（后续任何条目开工前先对一遍）

1. **零第三方依赖**：`build.gradle` 的 dependencies 块故意留空，整个 App 只用
   Framework API。菜单/对话框/浮条只能用 `PopupMenu` / `AlertDialog` / 自绘。
2. **`TerminalEmulator` 保持纯 Java**：桌面 EmuTest 是唯一的模拟器回归手段，
   引入 Android 类型等于放弃回归。
3. **APK 体积**：rootfs 已移出包（2026-11，APK 约 512KB）；剩余大头是三 ABI
   各一份 proot（jniLibs）。往 assets/nativeLibrary 塞任何东西都先算 ×3。
4. **会话层是多 tab**：会话 id = 根进程 pid，`tabs`（LinkedHashMap）是唯一
   会话表。动 `tabs` / `dropTabs` / `dropTab` / `SessionService` 前先读
   internals.md 的进程清理章节，确认不破坏 HUP → 补杀 → KILL 链；
   「切 tab 不杀会话」是不可回退的语义。

## 已完成记录

- 2026-11 辅助键盘 v2：「⋯」面板并入 ⌨（取消独立展开）；键集对照系统键盘
  重新取舍（粘贴让位系统输入法剪贴板、^C 留在键条不重复、控制符/符号精简为
  默认键集）；固定区（编辑键/F1–F12）不可自定义，可自定义区支持长按删除、
  「＋」添加自定义键（六种步骤模式：文本/控制符/ESC 序列/字节/回车/延迟）、
  恢复默认键集（长按「＋」或菜单），键集落盘 SharedPreferences
- 2026-11 多标签会话：id 用根进程 pid（见第三节）；chip 下拉即会话列表；
  重启/停止精确到单个 tab；七套桌面回归 + 实机全链路验证
- 2026-11 rootfs 移出 APK：在册容器初始为空，首次进容器模式引导拉取/导入；
  APK 10.93MB → 512KB；CI 校验与 fetch 脚本同步收口
- 2026-11 主菜单分 6 组子菜单（横屏 360dp 下平铺 19 项被裁，后半段不可点）
- 2026-11 键面板补 F7–F12（xterm 标准序列 `ESC[18~`–`ESC[24~`，17 键 3 行）
- 2026-11 剪贴板三向评估的 6 项修复：粘贴净化 / 异步分段写 / 复制裁尾空行 /
  权限拒绝 NPE（详见 git log fb75af9）
