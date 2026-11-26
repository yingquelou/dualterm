# 后续开发与取舍评估

候选功能的可行性评估与实现边界。硬约束适用于所有条目，先读最后一节。
每项给出「做 / 缓做 / 不做」的结论与触发重评的条件；已完成的项移入文末记录。

## 一、文本选中与复制 —— 已完成（2026-11，实机验证过）

原状：只有「复制全部输出」「复制当前屏」两个整块入口，想复制一个 URL /
token 只能复制整屏再手动删。现由长按拖选覆盖，实测全项通过（adb 注入 +
搜狗剪贴板栏第三方印证，见 manual-test.md E 节）。

**实现要点**：

- `TerminalEmulator.getSelectionText()`（纯 Java，EmuTest 36→46 用例）：
  软换行行拼回一个逻辑行、行尾填充只在逻辑行末行剥除（续行行尾空格是真实内容）、
  宽字符尾格收拢到头格、组合字符随主格保留（不做 NFC 合成）；同节的
  `wordSelectionAt()` 提供长按选词的词边界扫描（空白 / 标点 / 宽字符逐字，
  词可跨软换行），同样纯 Java、可桌面回归；
- 锚点用**全局行号 = 逐出计数（`evictedRows`）+ 绝对行号**，`clearScreen`
  也计入逐出，保证只增不减——新输出挤出 scrollback 时选区不漂移，
  被逐出部分自动裁掉（xterm 语义）；
- `TerminalView`：长按即按词选中（词内抖动保持整词，拖出词外再按字符扩展）
  → 拖动扩展（跨屏幕与 scrollback）→ 拖到上下边缘自动滚动（40ms/行，选区
  端点跟随新进入视口的内容延伸）→ 自绘浮动条（复制 / 全选 / 取消，不用
  ActionMode）。

**边界（对照 xterm Selections / VTE / Windows Terminal / Termux 核实）**：

- Shift 覆盖鼠标上报（xterm「shift override」）：程序开鼠标上报时，硬件
  键盘 Shift+按下/拖动走本地选区；触屏无 Shift，触摸归程序不变。
  **实机待测**（adb 无法模拟带 META_SHIFT 的触摸，manual-test E7）。
- 清除时机：单击清选区（不弹键盘）；任意按键/粘贴弃选区（xterm：key
  press discards selection）；复制后结束选择态（Termux 惯例）。
- 不做：双击选词、三击选行（「点终端=弹键盘」与之冲突）、系统放大镜
  （安卓放大镜绑定原生文本框，终端自绘内容用不上）、键盘 Shift+方向选择。
- 已做（2026-11 发布收尾补齐）：选区两端**自绘手柄**（水滴形，拖拽微调边界；
  触点先抬高 `HANDLE_STEM_DP + HANDLE_RADIUS_DP` 再取格，避免手指遮挡边界格）；
  浮动条**跟随选区**中点，上方空间不足时自动翻到选区下方（原为固定顶部）。
- 长按选词口径（2026-11-26）：空白分隔、标点各成一词（`/data/local/tmp` 长按
  选中 `local` 这样的路径片段）、CJK/emoji 逐字成词；词可跨软换行。词内轻抖
  保持整词（真实手指必然抖动），拖出词外才改按字符扩展。
- 应用菜单不在终端长按上（入口 = 长按键条「⌨」或顶栏「⋮」）；
  鼠标上报模式下终端长按无本地动作（实测确认）。

**遗留**：手感类复核项在 manual-test.md E8（长按时机、滚动速度、
浮动条遮挡观感）。

## 二、OSC 52 写剪贴板 —— 已完成（2026-11，实机验证过）

让 vim 的 `"+y`、tmux 的 copy-mode 写入系统剪贴板。前置（第一节选区）
完成后随即落地，实机 adb 全链路验证（写入 → 粘贴回读一致；关闭后写入被
拦，输入法剪贴板栏第三方位证）。实现细节见 internals.md「OSC 52 写剪贴板」。

**定稿边界逐条落地**：

- `handleOsc` 先拦 `52;` 前缀再走颜色查询分支；OSC 缓冲 64 → 100KB，
  超长序列置 overflow 标志整体丢弃（半截 base64 是垃圾）；
- 解码用 `java.util.Base64.getMimeDecoder()`（minSdk 26，零依赖成立，
  容忍发送方夹换行）；解码后 > 64KB 截断；
- 只支持 `c` 目标；`c2`/`sc` 忽略；`?` 读请求不应答（只写不读）；空数据忽略；
- `TerminalEmulator` 保持纯 Java：出口走 `ClipboardSink` 回调
  （Emulator → TermSession → Activity），策略层在 Activity——
  prefs `osc52` 菜单开关默认关 + 前台判定（UI 线程 `hasWindowFocus`）；
- EmuTest 55 → 66 用例（本节 11 例）：基本写入 / ST 终止 / 目标过滤 / 读请求 /
  空数据 / 坏 base64 / MIME 宽容 / UTF-8 往返 / 64KB 截断 / 超长丢弃 /
  丢弃后恢复 / 其它 OSC 不回归。

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

## 七、MainActivity 拆分 —— 不做一次性全量（触发条件：有 UI 自动化兜底）

**2026-11-26 更新**：第十节的虚拟节点已落地，`uiautomator dump` 可读终端文本，
「有 UI 自动化兜底」的条件已满足；下一步按本节的分区逐块拆、每块一个提交，
不再讨论「是否拆」。

按分区逐块拆，零行为变化、每块一个提交，拆前拆后用 `uiautomator dump` 对比：

- **已完成 键条 → `ui/KeyBar`（2026-11-26）**：键位、样式、⌨/⛶ 行为移出，宿主只提供
  `terminal / softKeyboard / imeVisible / isFullscreen / onMenu` 等行为入口；
  MainActivity 净减约 180 行，键条各键 bounds 与拆分前逐键一致。
- **已完成 容器/镜像/绑定目录对话框 → `ui/ContainerDialogs`（2026-11-26）**：绑定目录、
  容器与镜像列表、容器/镜像动作、拉取/换源、tar 导入（含 SAF 结果与后台任务）整体搬出，
  约 800 行；宿主以 `ContainerDialogs.Host` 提供进度层、提示、模式 chip、会话启停、
  控制台入口等行为，对话框文案与项序逐字保留。MainActivity 3122 → 2534 行。
- **已完成 菜单面板 → `ui/MenuSheet`（2026-11-26）**：自绘二级面板的装配、层级切换、
  按锚点上下空间决定弹出方向、返回键「二级→顶层→收起」整体搬出；宿主只提供
  菜单数据（`MenuSheet.Group` 列表）与动作分发（id → `onMenuClick`）。
  MainActivity 2534 → 2390 行。
- **已完成 会话选择器 → `ui/SessionPicker`（2026-11-26）**：模式 chip 下拉的
  PopupMenu 装配与 id 映射移出；标签（`●` 当前 / 已退出 / 容器名）与切换、新建
  仍由宿主提供。体积持平（MainActivity 2390 → 2389），收益在边界：主类只剩会话语义。
- 至此第七节的分区列表全部落地：键条 / 容器对话框 / 菜单面板 / 会话选择器。
  `onMenuClick` 的动作分发以宿主状态为主，保留在主类，不再单列分区。

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

## 九、外接硬件键盘中文输入 —— 已完成（2026-11-20，实机验证过）

打通「硬键盘 → 输入法 → PTY」：有外接全尺寸键盘且输入法界面可见时，视图改成普通文本
框语义（`TYPE_CLASS_TEXT`），字母交给输入法组词，`commitText` 只把这次提交的内容写进
PTY；输入法收起或用户显式退出接管时回到 `TYPE_NULL`，字母逐键直发。菜单
「键盘与输入法 → 外接键盘中文输入」给自动/始终/从不三档策略，自动档下双击 Shift 在组词与
字母直发之间切换。

评估阶段记下的两处代价，实测后都换了做法：

- 回车不再由应用抢判 composing。搜狗对硬键盘组词全程不调 `setComposingText`，
  应用侧根本看不到 composing 状态，「有组词才让路」这条规则无从生效。改成接管态把
  回车/空格/方向键等终端语义键全数交给输入法，空闲时输入法会用
  `InputConnection.sendKeyEvent` 原样退回来（实测退得干净：`\r`、`\t`、`\e[A`、
  `\e[H`、`\e[5~`、`\e[2~`、`\e[3~`、`\e` 全部照发），挂着拼音时由输入法自己处置
  （回车把拼音原样上屏、不带动命令执行；退格删拼音尾字母；`Esc` 例外——退回来送
  PTY 但组词不取消）。抢在输入法前面发 CR 的旧做法会留下挂在输入法里的拼音，
  下一行凭空冒出词。
- 残留拼音的可见性靠「输入法窗口可见」这个硬门槛兜住：窗口没弹出来就不接管，
  不存在盲挂组词。双击 Shift 切走时窗口收起，组词随会话重建一并清掉。

三条需求各自的落点：①链路 = 接管态判定 + `restartInput` + `commitText` 只送本次提交；
②形态切换 = 外接键盘存在时键条与辅助键盘隐退（既有 `updateChrome` 逻辑，本轮复验
拔插回归）、输入法接管字母，拔掉后辅助键盘与软键盘组词照常；③语言切换 = 双击 Shift
（安卓把 Ctrl+空格/Alt+Shift/Win+Space 收作系统级输入法切换快捷键，应用抢不到也不该抢；
搜狗对硬键盘的 Shift 单击、Ctrl+Shift、Alt+Shift、Shift+空格都不作中英切换，
所以这个开关只能由应用侧做）。

验证通路只认 `tools/hardkey.c`（uinput 真实设备路径）；`input keyevent` 与
`--es keytest` 是虚拟键盘事件，输入法不接管，属假对照。批量动作用
`tools/ime-check.sh`，它会把接管态拉起来再逐条注入。

## 十、可访问性虚拟节点 —— 已完成（2026-11-26，实机验证过）

只读、按**可见逻辑行**暴露节点，服务于读屏与文本级 UI 自动化：`uiautomator dump`
现在能读到终端文本，MainActivity 拆分所需的自动化兜底随之成立。

**实现要点**：

- `TerminalEmulator.getLogicalLineText()` / `isWrappedContinuation()`（纯 Java，
  EmuTest 66→69）：沿 `wrapped` 链合到一行，行尾填充只剥逻辑行末行；空白行返回空串；
- `TerminalView.RowNodeProvider`：虚拟 id = 逻辑行在视口里的起始行，折行续行不重复
  建节点、bounds 覆盖该逻辑行占用的可见行；空白行不建节点；节点文本按逻辑行口径；
- 触摸探索：`dispatchHoverEvent` 聚焦命中行（续行归到逻辑行起点）；滚动 / 新输出 /
  尺寸变化发 `TYPE_WINDOW_CONTENT_CHANGED`（300ms 节流，且在仿真器锁外发送）；
  全部懒生成、不缓存，滚动 / 转屏 / 字号变化后自然按新视口重算；
- 只读：不做节点写操作、不模拟点击、不暴露全量 scrollback。

**实机验收**：35 列 `seq -s. 1 80` 的 239 字符长行在 dump 里是**一个**节点
（bounds 跨 6 行）；转横屏后文本不变（bounds 跨 2 行）；上滑后节点跟随视口
（首节点 561→483）；空白行无节点；`input text` 输入与回显不受影响。
**遗留**：TalkBack 朗读「不把折行读断」需人耳复核（设备已装 TalkBack；见 manual-test E13）。

## 十一、边界评审归档：已判不做（2026-11-26）

本表是「不做」结论的汇总入口，防止旧题重议；有独立章节的条目只在表里留一行。
「重评触发」为空表示保持不做，除非出现新的实测证据。

| 条目 | 判定的理由 | 重评触发 |
|---|---|---|
| 双击选词 / 三击选行 | 单击已被「点终端 = 切键盘」占用，双击与之冲突；长按选词覆盖主要场景（第一节） | 有明确诉求，且能给出与单击不冲突的成对手势 |
| 系统放大镜 | 安卓放大镜绑定原生文本框，终端是自绘内容；自绘放大镜成本高于收益 | 无 |
| 键盘 Shift+方向选择 | 硬件键盘场景已被 Shift+触摸选区和端点手柄覆盖 | 无 |
| 上滑到底继续滑（尾部无实质内容时） | 与 Termux 一致：尾部没有内容就不产生位移；实测下方空白是屏幕尾部，不是内容 | 无 |
| 「复制当前屏」对齐逻辑行口径 | 该入口语义就是「当前屏快照」（按视觉行、逐行剥尾空）；逻辑行复制走选区 | 无 |
| 像素级滚动 / 脏矩形 / bitmap 平移 | 整行步进 + 同色合并已够用；像素级要重写渲染与选区坐标 | 出现可复现掉帧，且测量定位到绘制而非 PTY |
| JNI 零拷贝 PTY 读写 | 现状吞吐满足（`cat` 大文件不卡）；引入 JNI 就放弃「纯 Java 可桌面回归」的硬约束 | 大文件吞吐实测不达标 |
| 每帧 setTextSize（连续缩放） | 字号只在捏合结束时落一次，避免每帧 relayout | 无 |
| 输入法侧自绘复制 / 剪切按钮 | 系统输入法剪贴板栏已覆盖；与选区浮条重复 | 无 |
| 镜像 Editable / 系统 ActionMode 替换浮条 | 自绘选区 + 自绘浮条已满足；镜像 Editable 会与 PTY 状态双写 | 无 |
| scrollback 落盘 | 内存 1000 行够用；落盘带来一致性与隐私成本 | 需要跨进程恢复历史 |
| 多窗口独立视口 | 单窗口多 tab 已覆盖；分屏独立滚动要拆开 View 与 emulator 的 1:1 绑定 | 真正长期分屏，且两个会话都要独立看 |
| 保活（前台服务常驻 / 唤醒锁） | 会话随 Activity 生命周期，切走即停；常驻的电量与合规成本高 | 明确的后台长任务需求 |
| 终端间直达通道 | 系统剪贴板中转够用（第四节） | 多 tab 协作成为高频 |
| Intent 外部调用 | 安全事故面（第五节） | 有明确自动化需求且能限定调用方 |
| MainActivity 一次性全量拆分 | 无 UI 自动化兜底时风险收益比不划算（第七节） | 已满足（第十节已落地）；改为按分区逐块拆，每块一个提交 |
| 本地 shell 包管理器（soar）/ 容器侧内置 soar | 容器内已有 apt/apk；内置包管理器与零依赖、体积冲突 | 无 |
| `docker pull` 的 digest / 第三方 registry / 登录 / 续传、zstd 层 | 第八节已定；导入通路补足 | 主流镜像只发 zstd 且无 gzip 变体 |
| 协议缺口（IRM/LNM、modifyOtherKeys、kitty、OSC 0/2/8、`?2026`、ZWJ、Sixel） | 均静默降级（internals「与 xterm 对表的取舍」） | 程序明确依赖且无降级路径 |

**发布动作**：`3.9.0`（2026-11-22）之后 master 累积了长按选词、折行恢复、无障碍
虚拟节点与 MainActivity 分区拆分，按语义化版本随下一个 `v3.10.0` tag 出，不单独发版；
发版前本地收尾 = release 构建（测试签名）+ 三 ABI `libproot.so` 校验 + 七套回归。

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

- 2026-11-26 MainActivity 拆分第一块：键条抽到 `ui/KeyBar`（Host 只给行为、不给视图）；
  11 个键的 bounds 与拆分前逐键一致，CTRL 高亮 / ⌨ 开合 / 长按菜单 / ⛶ 全屏冒烟通过
- 2026-11-26 MainActivity 拆分第二块：容器/镜像/绑定目录对话框抽到 `ui/ContainerDialogs`
  （约 800 行，Host 只给进度层 / 提示 / 模式 chip / 会话启停 / 控制台入口）；文案与
  项序逐字保留，MainActivity 3122 → 2534 行；实机对话框逐项 dump 与七套回归全过
- 2026-11-26 MainActivity 拆分第三块：菜单面板抽到 `ui/MenuSheet`（分组层级、弹出方向、
  返回键逐层退），宿主只提供菜单数据与 id 分发；MainActivity 2534 → 2390 行；
  实机 7 组/容器二级/返回逐层退/字号动作分发验证通过
- 2026-11-26 MainActivity 拆分第四块：会话选择器抽到 `ui/SessionPicker`（PopupMenu
  装配与 id 映射），标签与切换/新建语义留在宿主；实机会话项 / 新建入口 / 容器管理入口验证通过
- 2026-11-26 无障碍虚拟节点：只读、按可见逻辑行暴露（续行合并 / 空白行跳过 / 触摸探索 /
  内容变化节流通知），`uiautomator dump` 可读终端文本；EmuTest 66→69；
  TalkBack 听感留用户复核
- 2026-11-26 折行恢复收口：宽度双向重排、逻辑行仍超宽时按新列数重新分割、变高从
  scrollback 回填（弹键盘 / 转屏往返幂等）、软换行续行行尾空格不再被剥；EmuTest
  52→55，实机 35↔74 列重分割与 `seq 1 600` 收键盘回填验证
- 2026-11-26 长按选词：长按即按词边界选中（词锁防抖），空白 / 标点 / CJK 逐字，
  词可跨软换行；EmuTest 46→52
- 2026-11 发布收尾：视口锚点与惯性滚动——`viewportAnchor` 存绝对行号，Activity
  重建 / 切标签复原；后台输出不再推走视口（8s 漂移 116 行 → 0）；
  `VelocityTracker` + `Scroller` 惯性（0.25 缩放、上限 8000、下限 180）
- 2026-11 发布收尾：选区端点手柄与浮窗跟随——手指不再遮挡边界，浮动条贴选区显示
- 2026-11 发布收尾：接管态输入法语义收口——`inputType` 补 `NO_SUGGESTIONS`，
  `imeOptions` 固定 `NO_PERSONALIZED_LEARNING`；接管写入收敛到 `applyTakeover` 单入口
- 2026-11-20 外接硬件键盘中文输入评估：`tools/hardkey.c` 建立真实设备按键通路，
  作废「输入法天生不理硬键盘」与「应用侧无解」两条旧结论，改判为第九节（见 internals）
- 2026-11-20 外接硬件键盘中文输入落地：接管态判定 + 三档策略 + 双击 Shift 切换 +
  终端语义键交输入法仲裁，实机跑通「蓝牙键盘打你好中文字」；辅助键盘随外接键盘
  隐退/恢复复验通过（见 usage 物理键盘、internals「外接键盘的输入法接管」、manual-test G3）
- 2026-11 OSC 52 写剪贴板：菜单开关默认关 + 前台写入（见第二节）；
  EmuTest 46→57 用例，实机 adb 全链路验证（含关闭拦截）
- 2026-11 文本选区复制：长按拖选 + 浮动条（见第一节），对照 xterm 核实
  边界；EmuTest 36→46 用例，实机 adb 注入全项验证
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
- 2026-11-26 选区手柄尺寸可配置：杆长 4–40dp（默认 9）/ 圆头半径 3–24dp（默认 7）/
  命中半径 16–48dp（默认 22），菜单「外观 → 选区手柄」即时生效并落盘；解决
  指腹触屏时指甲遮住圆头的手感问题（manual-test E8）
- 2026-11-26 AltGr 字符修复：法语布局 AltGr+E→`€` 此前被当 Meta 发成 `ESC + €`，
  按「带 Alt 与不带 Alt 的 KeyCharacterMap 字符不同即 AltGr」只发字符；US 布局
  Alt+E / Alt+Shift+E 不受影响（manual-test G4）。用户实机复测：法语 `€` 已可用；
  US 的 Ctrl+Alt+S → `ß` 属 AltGr 语义（与系统文本框一致），非回归
