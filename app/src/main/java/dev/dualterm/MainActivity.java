package dev.dualterm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

import dev.dualterm.container.ContainerRuntime;
import dev.dualterm.container.Containers;
import dev.dualterm.container.Volumes;
import dev.dualterm.term.LaunchSpec;
import dev.dualterm.term.SessionService;
import dev.dualterm.term.Shells;
import dev.dualterm.term.TermSession;
import dev.dualterm.term.TerminalView;
import dev.dualterm.ui.ConsoleActivity;
import dev.dualterm.ui.ContainerDialogs;
import dev.dualterm.ui.KeyBar;
import dev.dualterm.ui.MenuSheet;
import dev.dualterm.ui.SessionPicker;
import dev.dualterm.ui.SoftKeyboard;

/**
 * 主界面：两个模式共用一个终端视图。
 *
 * 两种模式各自缓存一个 TermSession，来回切换不丢现场（不重启进程，屏幕内容还在）。
 */
public class MainActivity extends Activity
        implements TermSession.Listener,
        TerminalView.GridListener,
        TerminalView.ModifierWatcher,
        ContainerDialogs.Host {

    private static final String PREF = "dualterm";
    private static final String MODE_LOCAL = "local";
    private static final String MODE_CONTAINER = "container";

    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_ACCENT = 0xFF7CE38B;

    /** 菜单「帮助」指向的在线文档；改文档路径时同步这里。 */
    private static final String HELP_URL =
            "https://github.com/yingquelou/dualterm/blob/master/docs/usage.md";

    private SharedPreferences prefs;

    /**
     * 后台任务的唯一执行器（单线程串行，与 UI 无关的磁盘/进程活都在这）。
     * 以前六处裸 new Thread：切模式/退出后任务照跑、回调时 Activity 可能已销毁。
     * onDestroy 里 shutdownNow 统一收口。
     */
    private final java.util.concurrent.ExecutorService background =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    new java.util.concurrent.ThreadFactory() {
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "dualterm-bg");
                            t.setDaemon(true);
                            return t;
                        }
                    });
    /** 容器启动任务的代次号：每次 startContainer 递增，旧任务的 UI 回调直接丢弃。 */
    private final java.util.concurrent.atomic.AtomicInteger startGen =
            new java.util.concurrent.atomic.AtomicInteger();

    private TerminalView terminal;
    private TextView status;
    private TextView btnMode;
    private Button btnMenu;
    private View keyBar;
    private LinearLayout keyRow;
    private View overlay;
    private TextView overlayText;

    /** 顶部键条（dev.dualterm.ui.KeyBar）：键位与样式已从本类拆出。 */
    private KeyBar keyBarUi;

    /** 自绘软键盘（dev.dualterm.ui.SoftKeyboard）：从本类拆出的构建与键语义。 */
    private SoftKeyboard softKbd;

    /** 容器 / 镜像 / 绑定目录对话框（dev.dualterm.ui.ContainerDialogs）。 */
    private ContainerDialogs containerDialogs;

    /** 应用菜单面板（dev.dualterm.ui.MenuSheet）：层级切换与弹出方向已拆出。 */
    private MenuSheet menuSheet;

    /** 会话结束条：和键条互斥，占同一块屏幕位置。 */
    private View exitBar;
    private TextView exitText;
    private boolean sessionEnded;
    private int lastExitCode;

    /** 软键盘在屏幕上（返回键分层用：先收键盘，再退后台）。 */
    private boolean imeVisible;
    /**
     * 这轮 IME 是终端自己叫出来的（点终端 / ⌨ 键 / 会话启动）。
     * 系统键盘弹起时默认要让自绘键盘让位（对话框的输入框也会弹 IME），
     * 但这个标记为真时保留——「系统键盘 + 辅助键盘」是本应用的组合输入方案。
     */
    private boolean imeFromTerminal;
    /** 有没有物理键盘：决定键条是否出现——键盘上本来就有 ESC/Tab/方向键。 */
    private boolean hardKeyboard;
    private android.hardware.input.InputManager inputManager;
    private android.hardware.input.InputManager.InputDeviceListener inputDeviceListener;
    /** onCreate 走完之前不弹「已连接物理键盘」的提示，避免每次冷启动都刷一条。 */
    private boolean uiReady;

    /**
     * 一个标签页 = 一个终端会话 + 它的身份（模式 / 容器名）。
     * id 用会话根进程的 pid（字符串化）：内核保证存活期内唯一，logcat/adb 调试时
     * id 与 ps 直接对上；进程退出立即从表里摘除，pid 复用也就无从撞上。
     */
    private static final class Tab {
        final TermSession session;
        final String mode;       // MODE_LOCAL / MODE_CONTAINER
        final String container;  // 仅容器模式有意义
        /**
         * 阅读位置（视口顶部对应的全局行号，-1 = 未记录）。
         * 会话是进程级资产，视口不能只活在 View 里：Activity 重建、切 tab 都要回到原段落。
         */
        int viewportAnchor = -1;

        Tab(TermSession session, String mode, String container) {
            this.session = session;
            this.mode = mode;
            this.container = container;
        }
    }

    /**
     * tabId(=根进程 pid) → Tab。LinkedHashMap 保持创建顺序，会话列表按此排序。
     *
     * 进程级（static）：Activity 可能被系统销毁而进程还活着（改系统字体/语言、
     * 开发者选项「不保留活动」、内存回收），会话此时仍在跑。表放在类上，下一个
     * Activity 实例才能把旧 PTY 接管回来，否则它们就成了看不见也停不掉的孤儿。
     * 只在主线程访问。
     */
    private static final java.util.LinkedHashMap<String, Tab> tabs = new java.util.LinkedHashMap<>();
    /** 当前贴在界面上的 tab id；null 表示没有激活的 tab（比如刚退出）。 */
    private static String activeTabId;
    /** 顶栏 chip 的「当前模式」：从激活 tab 派生；无激活 tab 时保持上次值。 */
    private String mode = MODE_LOCAL;
    /** 最近激活 tab 的身份。tab 退出后被「重启」按钮用来重建同类会话——
     *  退出回调会把 tab 从表里摘掉，届时只能靠这两个字段记下它是什么。 */
    private String lastRestartMode = MODE_LOCAL;
    private String lastRestartContainer;

    /** 调试入口：启动后自动敲入的一条命令（正常使用为 null）。 */
    private String pendingCommand;
    /** 本轮启动是否已经做过一次「坏容器 → 换一个」的兜底，防止连环跳。 */
    private boolean fallbackTried;
    /**
     * 界面是否可见（onStart ~ onStop）。后台时关掉重绘、给会话读线程加节流：
     * 持续刷屏的程序在屏幕关着时不该继续吃满 CPU 和电池。
     */
    private boolean activityVisible;

    private View topBar;
    private boolean fullscreen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREF, MODE_PRIVATE);

        terminal = findViewById(R.id.terminal);
        status = findViewById(R.id.status);
        topBar = findViewById(R.id.top_bar);
        btnMode = findViewById(R.id.btn_mode);
        // chip 文本随容器名（以及 root/docker 标记）变长，限宽 + 尾部省略，
        // 别让它把右边的状态行挤没
        btnMode.setSingleLine(true);
        btnMode.setEllipsize(android.text.TextUtils.TruncateAt.END);
        btnMode.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * 0.46f));
        btnMenu = findViewById(R.id.btn_menu);
        keyBar = findViewById(R.id.key_bar);
        keyRow = findViewById(R.id.key_row);
        exitBar = findViewById(R.id.exit_bar);
        exitText = findViewById(R.id.exit_text);
        overlay = findViewById(R.id.overlay);
        overlayText = findViewById(R.id.overlay_text);

        terminal.setGridListener(this);
        terminal.setModifierWatcher(this);
        // 菜单不再挂「长按终端」。长按是 Android 给文本选区的标准手势
        // （TextView 长按 = 选词 + 手柄 +「复制/全选」浮动条），终端要做选区就得
        // 先把它空出来；而且实测它和现有手势打架：vim 鼠标模式下「按住再拖」选段
        // 会被 450ms 的长按抢走（我们还得补发 mouse-up），点终端切键盘时手指
        // 停顿一下也会误弹整个菜单。
        // 现在的入口都是可见控件：顶栏 ⋮（非全屏）/ 长按键行的 ⌨（全屏下也在）。
        // TerminalView 里保留 longPress 钩子，留给将来的选区实现。
        // 会话已退出还继续敲键盘：给一次提示，别让用户对着死 PTY 敲半天
        terminal.setDeadInputListener(new TerminalView.DeadInputListener() {
            @Override
            public void onInputWhileDead() {
                toast("会话已结束 · 点下方「重启」继续");
            }
        });
        // 捏合改字号后落盘，下次进来还是这个大小
        terminal.setFontSizeListener(new TerminalView.FontSizeListener() {
            @Override
            public void onFontSizeChanged(int sp) {
                prefs.edit().putInt("font_size", sp).apply();
                updateStatus(null);
            }
        });
        terminal.setFontRange(fontMinSp(), fontMaxSp());
        terminal.setTextSizeSp(prefs.getInt("font_size", 13));

        buildKeyBar();
        buildSoftKeyboard();
        containerDialogs = new ContainerDialogs(this);
        menuSheet = new MenuSheet(this, new MenuSheet.Host() {
            @Override
            public void onMenuAction(int id) {
                onMenuClick(id);
            }
        });
        installInputDeviceWatcher();
        installImeWatcher();

        installInsetsHandler();
        // 始终 edge-to-edge：adjustResize + 系统栏混着来的时候，窗口高度和
        // 内容高度对不上（实测竖屏窗口 2340、可见区只到 2220），键盘最后一行
        // 正好落进导航条里点不到。统一由 insets handler 给 root 打 padding，
        // 顶部避状态栏/挖孔、底部避导航条/手势条/输入法，行为在横竖屏一致。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            findViewById(R.id.root).requestApplyInsets();
        }
        // 恢复上次的全屏状态（在会话启动前应用，避免先闪一下顶栏）
        applyFullscreen(prefs.getBoolean("fullscreen", false));

        // 模式 chip：点一下选「本地 / 容器 / 容器与镜像」。以前是两个常驻按钮，
        // 占了顶栏一半宽度，而切换模式其实一天也用不了几次——低频操作不该常驻。
        btnMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showModePicker();
            }
        });
        // 会话结束条：进程没了，键条留着也没用，把这块位置换成「重启 / 复制输出」
        findViewById(R.id.btn_restart).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartLastKind();
            }
        });
        findViewById(R.id.btn_copy_out).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });
        // 点终端 = 打字：系统键盘开着就收起，收着就唤起
        terminal.setTapListener(new TerminalView.TapListener() {
            @Override
            public void onTap() {
                // 用 imeVisible（全局布局监听的真源）判断，而不是 IMM.isActive——
                // 后者只表示有输入法连上，键盘收起后仍可能为 true
                if (imeVisible) {
                    terminal.hideKeyboard();
                    return;
                }
                // 这轮 IME 是用户自己要打字的，弹起时别把辅助键盘顺手关掉
                imeFromTerminal = true;
                terminal.showKeyboard();
            }
        });
        btnMenu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });

        String saved = prefs.getString("mode", MODE_LOCAL);        // 调试入口：am start ... --es mode container --es cmd "cat /etc/os-release"
        Intent it = getIntent();
        String forced = it == null ? null : it.getStringExtra("mode");
        if (forced != null) {
            saved = forced;
            pendingCommand = it.getStringExtra("cmd");
            if (pendingCommand != null) {
                TermSession.logChunkLimit = 400;
            }
            if (it.hasExtra("fullscreen")) {
                applyFullscreen(it.getBooleanExtra("fullscreen", false));
            }
        }
        refreshInputMode();
        adoptSessions();
        if (forced != null) {
            switchMode(MODE_CONTAINER.equals(forced) ? MODE_CONTAINER : MODE_LOCAL, false);
        } else if (terminal.getSession() == null) {
            switchMode(MODE_CONTAINER.equals(saved) ? MODE_CONTAINER : MODE_LOCAL, false);
        }
        maybeOpenConsole(it);
        // 诊断用：--ei chunklog N 让会话把前 N 个输出块打进日志（默认 3），
        // 用来抓 vim 滚屏等场景下程序到底发了什么转义序列
        int chunkLog = it.getIntExtra("chunklog", -1);
        if (chunkLog >= 0) {
            dev.dualterm.term.TermSession.logChunkLimit = chunkLog;
        }
        maybeKeyTest(it);
        logWindowFlags();
        // 冷启动窗口不在触摸模式，焦点会落到第一个可聚焦控件（模式 chip）上：
        // 物理键盘按 Enter 就成了「打开模式菜单」。终端才是这台设备的主输入面。
        terminal.post(new Runnable() {
            @Override
            public void run() {
                terminal.requestFocus();
            }
        });
        uiReady = true;
    }

    /** 排查「该应用不允许屏幕截图」：把窗口标志打出来，看是不是我们自己的 FLAG_SECURE。 */
    private void logWindowFlags() {
        int flags = getWindow().getAttributes().flags;
        boolean secure = (flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        android.util.Log.i("DualTerm",
                "window flags=0x" + Integer.toHexString(flags) + " FLAG_SECURE=" + secure);
    }

    /**
     * 调试/自动化：--es keytest "ctrl+c" 往终端注入一次带修饰键的按键事件。
     *
     * 蓝牙/USB 键盘不在手边时，用它回归硬件键映射（Ctrl 组合、Shift+Tab、Alt、方向键、F 键）。
     * 走的是 TerminalView.dispatchKeyEvent → onKeyDown，与真实按键同一条路径。
     */
    private void maybeKeyTest(Intent intent) {
        if (intent == null) {
            return;
        }
        final String spec = intent.getStringExtra("keytest");
        if (spec == null) {
            return;
        }
        terminal.postDelayed(new Runnable() {
            @Override
            public void run() {
                injectKey(spec);
            }
        }, 5000);
    }

    private void injectKey(String spec) {
        int meta = 0;
        String name = spec.trim().toLowerCase(java.util.Locale.US);
        while (true) {
            if (name.startsWith("ctrl+")) {
                meta |= android.view.KeyEvent.META_CTRL_ON | android.view.KeyEvent.META_CTRL_LEFT_ON;
                name = name.substring(5);
            } else if (name.startsWith("alt+")) {
                meta |= android.view.KeyEvent.META_ALT_ON | android.view.KeyEvent.META_ALT_LEFT_ON;
                name = name.substring(4);
            } else if (name.startsWith("shift+")) {
                meta |= android.view.KeyEvent.META_SHIFT_ON | android.view.KeyEvent.META_SHIFT_LEFT_ON;
                name = name.substring(6);
            } else {
                break;
            }
        }
        int code = keyCodeOf(name);
        if (code == 0) {
            android.util.Log.w("DualTerm", "keytest 无法识别: " + spec);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        terminal.requestFocus();
        terminal.dispatchKeyEvent(new android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_DOWN, code, 0, meta));
        terminal.dispatchKeyEvent(new android.view.KeyEvent(
                now, now, android.view.KeyEvent.ACTION_UP, code, 0, meta));
        android.util.Log.i("DualTerm", "keytest 注入 " + spec + " keyCode=" + code + " meta=" + meta);
    }

    private static int keyCodeOf(String name) {
        if (name.length() == 1) {
            char c = name.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return android.view.KeyEvent.KEYCODE_A + (c - 'a');
            }
            if (c >= '0' && c <= '9') {
                return android.view.KeyEvent.KEYCODE_0 + (c - '0');
            }
        }
        switch (name) {
            case "tab": return android.view.KeyEvent.KEYCODE_TAB;
            case "enter": return android.view.KeyEvent.KEYCODE_ENTER;
            case "esc":
            case "escape": return android.view.KeyEvent.KEYCODE_ESCAPE;
            case "space": return android.view.KeyEvent.KEYCODE_SPACE;
            case "backspace": return android.view.KeyEvent.KEYCODE_DEL;
            case "delete": return android.view.KeyEvent.KEYCODE_FORWARD_DEL;
            case "insert": return android.view.KeyEvent.KEYCODE_INSERT;
            case "up": return android.view.KeyEvent.KEYCODE_DPAD_UP;
            case "down": return android.view.KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return android.view.KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return android.view.KeyEvent.KEYCODE_DPAD_RIGHT;
            case "home": return android.view.KeyEvent.KEYCODE_MOVE_HOME;
            case "end": return android.view.KeyEvent.KEYCODE_MOVE_END;
            case "pgup": return android.view.KeyEvent.KEYCODE_PAGE_UP;
            case "pgdn": return android.view.KeyEvent.KEYCODE_PAGE_DOWN;
            case "f1": return android.view.KeyEvent.KEYCODE_F1;
            case "f2": return android.view.KeyEvent.KEYCODE_F2;
            case "f3": return android.view.KeyEvent.KEYCODE_F3;
            case "f4": return android.view.KeyEvent.KEYCODE_F4;
            case "f5": return android.view.KeyEvent.KEYCODE_F5;
            case "f6": return android.view.KeyEvent.KEYCODE_F6;
            case "f7": return android.view.KeyEvent.KEYCODE_F7;
            case "f8": return android.view.KeyEvent.KEYCODE_F8;
            case "f9": return android.view.KeyEvent.KEYCODE_F9;
            case "f10": return android.view.KeyEvent.KEYCODE_F10;
            case "f11": return android.view.KeyEvent.KEYCODE_F11;
            case "f12": return android.view.KeyEvent.KEYCODE_F12;
            default: return 0;
        }
    }

    /** 调试/自动化：--ez console true [--es cmd "ps -a"] 直接打开管理控制台。 */
    private void maybeOpenConsole(android.content.Intent intent) {
        if (intent == null || !intent.getBooleanExtra("console", false)) {
            return;
        }
        android.content.Intent it = new android.content.Intent(this, ConsoleActivity.class);
        String c = intent.getStringExtra("cmd");
        if (c != null) {
            it.putExtra("cmd", c);
        }
        startActivity(it);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String forced = intent == null ? null : intent.getStringExtra("mode");
        if (forced == null) {
            if (intent != null && intent.getBooleanExtra("console", false)) {
                maybeOpenConsole(intent);
            }
            // 调试钩子（keytest）在热交付时也要生效：singleTask 下 am start 落到
            // onNewIntent，不加这行只能冷启动触发
            maybeKeyTest(intent);
            return;
        }
        pendingCommand = intent.getStringExtra("cmd");
        if (pendingCommand != null) {
            TermSession.logChunkLimit = 400;
        }
        switchMode(MODE_CONTAINER.equals(forced) ? MODE_CONTAINER : MODE_LOCAL, true);
        maybeOpenConsole(intent);
    }

    /* -------------------- 模式切换 -------------------- */

    /** 按身份起一个新会话（不动任何现有 tab）。 */
    private void startKind(String sessionMode, String container) {
        if (MODE_LOCAL.equals(sessionMode)) {
            startLocal();
        } else {
            startContainer(container != null ? container : Containers.activeName(this));
        }
    }

    /** 只摘掉并停掉这一个 tab（重启单个会话用，不波及同类 tab）。 */
    private void dropTab(Tab tab) {
        String id = tabIdOf(tab.session);
        tabs.remove(id);
        if (id.equals(activeTabId)) {
            activeTabId = null;
        }
        if (MODE_CONTAINER.equals(tab.mode)) {
            syncContainerRunning(tab.container);
        }
        tab.session.stop();
        updateKeepAlive();
    }

    /**
     * 重启「当前这种」会话。多标签语义下只动一个 tab：
     * 激活 tab 还在 → 只重启它；已被进程退出摘掉 → 按记住的身份另起一个新的，
     * 两种情况都不暗杀其余同类 tab。
     */
    private void restartLastKind() {
        Tab tab = activeTabId != null ? tabs.get(activeTabId) : null;
        if (tab != null) {
            String m = tab.mode;
            String c = tab.container;
            dropTab(tab);
            startKind(m, c);
        } else {
            startKind(lastRestartMode, lastRestartContainer);
        }
    }

    /** 引导创建第一个容器：rootfs 不再随 APK 分发，第一次进容器模式必然走到这里。 */
    private void showNoContainerGuide() {
        new AlertDialog.Builder(this)
                .setTitle("还没有容器")
                .setMessage("安装包不再内置系统镜像。先获取一个镜像，再从镜像创建容器：\n\n"
                        + "· 拉取镜像（联网，Alpine 约 3MB；直连 Hub 不通可在镜像源设置里换源）\n"
                        + "· 导入 docker save / export 出的 tar\n\n"
                        + "创建好之后就能进容器模式了。")
                .setNegativeButton("取消", null)
                .setNeutralButton("导入 tar 镜像", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        containerDialogs.pickArchiveForImport();
                    }
                })
                .setPositiveButton("拉取镜像", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        containerDialogs.showPullDialog();
                    }
                })
                .show();
        // 界面别空着：没有贴着的会话（比如冷启动直奔容器模式）就先起本地 shell
        if (terminal.getSession() == null) {
            mode = MODE_LOCAL;
            prefs.edit().putString("mode", MODE_LOCAL).apply();
            updateModeButtons();
            startLocal();
        }
    }

    private void switchMode(String target, boolean restart) {
        switchMode(target, Containers.activeName(this), restart);
    }

    /**
     * 切到（或新建）一个标签页。
     * 多标签语义：同类 tab 已存活就只切换贴屏，绝不暗杀别的 tab；
     * restart=true 才把同类 tab 全部掐掉重建（「重启当前会话」/ 重建容器）。
     */
    private void switchMode(String target, String containerName, boolean restart) {
        // 容器模式一个容器都没有：引导用户去拉镜像/导入，别把人扔进报错堆栈
        if (MODE_CONTAINER.equals(target) && Containers.items(this).isEmpty()) {
            showNoContainerGuide();
            return;
        }
        if (!restart) {
            Tab existing = findTab(target, containerName);
            if (existing != null) {
                attachTab(existing);
                return;
            }
        } else {
            dropTabs(target, containerName);
        }
        mode = target;
        prefs.edit().putString("mode", target).apply();
        updateModeButtons();
        fallbackTried = false;
        if (MODE_LOCAL.equals(target)) {
            startLocal();
        } else {
            startContainer(containerName);
        }
    }

    private void startLocal() {
        try {
            boolean useRoot = prefs.getBoolean("root", false);
            LaunchSpec spec = Shells.localSpec(getApplicationContext(), useRoot);
            startSession(MODE_LOCAL, null, spec);
        } catch (Throwable t) {
            showError("无法启动本地 Shell", describe(t));
        }
    }

    @Override
    public void startContainer(String containerName) {
        showOverlay("准备容器 " + containerName + " …");
        final String preferred = prefs.getString("runtime", "proot");
        final Context appContext = getApplicationContext();
        // 代次号：连续切换容器时，前一个启动任务的回调作废，避免旧容器
        // 启动完成后把界面拉回已经被放弃的容器
        final int gen = startGen.incrementAndGet();

        background.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ContainerRuntime runtime = ContainerRuntime.create(appContext, preferred);
                    runtime.prepare(new ContainerRuntime.Progress() {
                        @Override
                        public void onProgress(final String message) {
                            postUi(new Runnable() {
                                @Override
                                public void run() {
                                    overlayText.setText(message);
                                }
                            });
                        }
                    });
                    final LaunchSpec spec = runtime.launchSpec(containerName);
                    postUiGen(gen, new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            fallbackTried = false;
                            Containers.setActive(MainActivity.this, containerName);
                            startSession(MODE_CONTAINER, containerName, spec);
                            Containers.setRunning(containerName, true);
                            updateStatus(null);
                        }
                    });
                } catch (final Throwable t) {
                    postUiGen(gen, new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            // 活动容器坏掉（架构不符、rootfs 残缺）时别把人锁死在里面：
                            // 先自动落到另一个能用的容器，全都不行才报错。
                            if (!fallbackTried) {
                                String alt = Containers.firstUsable(MainActivity.this, containerName);
                                if (alt != null) {
                                    fallbackTried = true;
                                    Containers.setActive(MainActivity.this, alt);
                                    toast("容器 " + containerName + " 无法启动，已切到 " + alt);
                                    startContainer(alt);
                                    return;
                                }
                            }
                            showError("容器模式启动失败", describe(t));
                        }
                    });
                }
            }
        });
    }

    /** 容器启动代次守卫的 UI 回调：代次过期或 Activity 已销毁就丢弃。 */
    private void postUiGen(int gen, Runnable r) {
        if (gen != startGen.get()) {
            return;
        }
        postUi(r);
    }

    /** UI 回调统一入口：Activity 已销毁/正在结束时不再回调，防止摸已拆的视图。 */
    private void postUi(Runnable r) {
        if (!isFinishing() && !isDestroyed()) {
            runOnUiThread(r);
        }
    }

    private void startSession(String sessionMode, String container, LaunchSpec spec) {
        int rows = Math.max(3, terminal.getGridRows());
        int cols = Math.max(20, terminal.getGridCols());
        TermSession session = new TermSession(spec, rows, cols, 3000, this);
        // 后台起的会话（通知点回来之前/重建后）一开始就按节流跑
        session.setThrottled(!activityVisible);
        try {
            session.start();
        } catch (Throwable t) {
            showError("启动终端会话失败", describe(t));
            return;
        }
        // id = 根进程 pid：start() 成功后必然 > 0，存活期内内核保证唯一
        Tab tab = new Tab(session, sessionMode, container);
        tabs.put(String.valueOf(session.getPid()), tab);
        attachTab(tab);
        updateKeepAlive();
        // 接了物理键盘（蓝牙/USB）就别强弹软键盘：横屏时软键盘能吃掉大半个屏幕，
        // 终端会被压到只剩两三行，而且软键盘窗口是 secure 的，连截图都会被系统拦掉。
        if (!hasHardKeyboard()) {
            terminal.showKeyboard();
        }
    }

    private boolean hasHardKeyboard() {
        return detectHardKeyboard();
    }

    private static String tabIdOf(TermSession session) {
        return String.valueOf(session.getPid());
    }

    /** 找一个同类且存活的 tab（模式相同；容器模式还要求容器同名）。 */
    private Tab findTab(String sessionMode, String containerName) {
        for (Tab t : tabs.values()) {
            if (!t.mode.equals(sessionMode)) {
                continue;
            }
            if (MODE_CONTAINER.equals(sessionMode) && containerName != null
                    && !containerName.equals(t.container)) {
                continue;
            }
            if (t.session.isRunning()) {
                return t;
            }
        }
        return null;
    }

    /**
     * 掐掉匹配的 tab 并同步摘表（stop() 只发信号，onExit 的摘表是异步的；
     * 先摘再停才能保证保活计数与本方法语义同步）。
     */
    private void dropTabs(String sessionMode, String containerName) {
        java.util.Iterator<Tab> it = tabs.values().iterator();
        java.util.List<String> stoppedContainers = new java.util.ArrayList<>();
        while (it.hasNext()) {
            Tab t = it.next();
            if (!t.mode.equals(sessionMode)) {
                continue;
            }
            if (MODE_CONTAINER.equals(sessionMode) && containerName != null
                    && !containerName.equals(t.container)) {
                continue;
            }
            it.remove();
            if (tabIdOf(t.session).equals(activeTabId)) {
                activeTabId = null;
            }
            if (MODE_CONTAINER.equals(t.mode)) {
                stoppedContainers.add(t.container);
            }
            t.session.stop();
        }
        for (String name : stoppedContainers) {
            syncContainerRunning(name);
        }
        updateKeepAlive();
    }

    /** 同容器还有存活的 tab 就保持 running 标记，全退干净才摘。 */
    private void syncContainerRunning(String containerName) {
        for (Tab t : tabs.values()) {
            if (MODE_CONTAINER.equals(t.mode) && containerName.equals(t.container)
                    && t.session.isRunning()) {
                return;
            }
        }
        Containers.setRunning(containerName, false);
    }

    /**
     * 接管进程里还活着的会话（Activity 被销毁过、进程没死的情形）。
     * 没接管这一步，旧 PTY 会继续跑但界面上再也看不到、也停不掉。
     */
    private void adoptSessions() {
        if (tabs.isEmpty()) {
            return;
        }
        for (Tab t : tabs.values()) {
            t.session.setListener(this);
            t.session.setThrottled(!activityVisible);
        }
        Tab target = activeTabId == null ? null : tabs.get(activeTabId);
        if (target == null) {
            for (Tab t : tabs.values()) {
                if (t.session.isRunning()) {
                    target = t;
                    break;
                }
            }
        }
        if (target != null) {
            attachTab(target);
        } else {
            activeTabId = null;
        }
        updateKeepAlive();
    }

    /** 把当前视口的阅读位置存进所属 tab（切 tab / 进后台 / Activity 销毁前）。 */
    private void saveViewportAnchor() {
        TermSession current = terminal.getSession();
        if (current == null) {
            return;
        }
        Tab tab = tabs.get(tabIdOf(current));
        if (tab != null) {
            tab.viewportAnchor = terminal.getViewportAnchor();
        }
    }

    /** 贴一个 tab 到界面上，并把它记为「当前」。 */
    private void attachTab(Tab tab) {
        activeTabId = tabIdOf(tab.session);
        mode = tab.mode;
        prefs.edit().putString("mode", tab.mode).apply();
        lastRestartMode = tab.mode;
        lastRestartContainer = tab.container;
        attach(tab.session);
        updateModeButtons();
    }

    /**
     * 会话数变了就同步保活状态：还有活着的会话就顶前台服务 + 唤醒锁，一个不剩就收摊。
     * 只在 UI 线程调用（tabs 不是并发容器）。
     */
    private void updateKeepAlive() {
        int alive = 0;
        for (Tab t : tabs.values()) {
            if (t.session != null && t.session.isRunning()) {
                alive++;
            }
        }
        SessionService.sync(getApplicationContext(), alive);
    }

    private void attach(TermSession session) {
        saveViewportAnchor();
        terminal.setSession(session);
        Tab tab = tabs.get(tabIdOf(session));
        if (tab != null) {
            terminal.setViewportAnchor(tab.viewportAnchor);
        }
        terminal.requestFocus();
        session.resize(terminal.getGridRows(), terminal.getGridCols());
        // 切到一个已经退出的会话（比如刚才 crash 掉的那个）也要把结束条显示出来
        if (!session.isRunning()) {
            showExitBar(lastExitCode);
        } else {
            hideExitBar();
        }
        updateStatus(null);
        terminal.postInvalidateOnAnimation();
    }

    /* -------------------- 会话结束反馈 -------------------- */

    /**
     * 进程退出后的界面反馈。取舍：
     * <ul>
     *   <li><b>不弹对话框</b>——退出往往伴随报错，遮住终端等于遮住最需要读的那几行</li>
     *   <li><b>键条原地换成结束条</b>——PTY 已经死了，键条按了也没用；这块位置本来就是拇指区，
     *       换成「重启 / 复制输出」既不增加高度也不改变布局</li>
     *   <li><b>区分退出原因</b>：exit 0 是正常收工；128+n 是被信号杀（Ctrl-C 是 130、
     *       OOM 是 137）；其余是异常返回码。颜色上只对非 0 报警</li>
     *   <li><b>物理键盘下同样显示</b>：这不是键盘替身，而是恢复入口</li>
     * </ul>
     */
    private void showExitBar(int code) {
        sessionEnded = true;
        exitText.setText(describeExit(code) + " · 输出仍在屏上");
        exitText.setTextColor(code == 0 ? 0xFF8A90A0 : 0xFFFF8A65);
        exitBar.setVisibility(View.VISIBLE);
        updateChrome();
    }

    private void hideExitBar() {
        if (sessionEnded) {
            sessionEnded = false;
            exitBar.setVisibility(View.GONE);
            // 重启之后键条要按「有没有物理键盘」重新出现
            refreshInputMode();
        }
    }

    /** 128+n 是 waitpid 的信号编码：130 = SIGINT、137 = SIGKILL、143 = SIGTERM。 */
    private static String describeExit(int code) {
        if (code == 0) {
            return "会话已结束（exit 0）";
        }
        if (code > 128 && code <= 192) {
            int sig = code - 128;
            String name;
            switch (sig) {
                case 1:
                    name = "SIGHUP";
                    break;
                case 2:
                    name = "SIGINT";
                    break;
                case 9:
                    name = "SIGKILL";
                    break;
                case 15:
                    name = "SIGTERM";
                    break;
                default:
                    name = "signal " + sig;
                    break;
            }
            return "被 " + name + " 终止（" + code + "）";
        }
        if (code < 0) {
            return "会话异常结束（" + code + "）";
        }
        return "会话异常退出（code " + code + "）";
    }

    /* -------------------- TermSession.Listener -------------------- */

    @Override
    public void onOutput(TermSession session) {
        // 界面不可见时不必重绘：窗口本来就不合成，调用只会白排一次遍历
        if (activityVisible && terminal.getSession() == session) {
            terminal.postInvalidateOnAnimation();
        }
        String cmd = pendingCommand;
        if (cmd != null && terminal.getSession() == session) {
            pendingCommand = null;
            final TermSession target = session;
            final String text = cmd;
            // 等提示符刷出来再敲命令
            terminal.postDelayed(new Runnable() {
                @Override
                public void run() {
                    target.write(text + "\n");
                }
            }, 600);
        }
    }

    /**
     * OSC 52：程序请求写系统剪贴板（vim `"+y`、tmux copy-mode 等）。
     * 安全边界全在这层（roadmap 定稿）：菜单开关默认关 + 仅应用前台时写入，
     * 焦点判定放 UI 线程，避免从读线程摸窗口状态。
     */
    @Override
    public void onClipboardWrite(final TermSession session, final String text) {
        if (text == null || text.isEmpty() || !prefs.getBoolean("osc52", false)) {
            return;
        }
        postUi(new Runnable() {
            @Override
            public void run() {
                if (isFinishing() || isDestroyed() || !hasWindowFocus()) {
                    return;
                }
                copyToClipboard(text, "程序已写入剪贴板（%d 个字符）");
            }
        });
    }

    @Override
    public void onExit(final TermSession session, final int code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String id = tabIdOf(session);
                Tab tab = tabs.remove(id);
                if (tab != null && MODE_CONTAINER.equals(tab.mode)) {
                    // 用 tab 自带的容器名清 running 标记，避免与 setActive 的竞态误清新容器；
                    // 同容器若还有别的活 tab，标记要留着
                    syncContainerRunning(tab.container);
                }
                if (tab != null && id.equals(activeTabId)) {
                    activeTabId = null;
                    lastRestartMode = tab.mode;
                    lastRestartContainer = tab.container;
                }
                if (terminal.getSession() == session) {
                    lastExitCode = code;
                    showExitBar(code);
                    updateStatus(describeExit(code));
                }
                updateKeepAlive();
            }
        });
    }

    /* -------------------- TerminalView 回调 -------------------- */

    @Override
    public void onGridChanged(int rows, int cols) {
        TermSession session = terminal.getSession();
        if (session != null) {
            session.resize(rows, cols);
        }
        updateStatus(null);
    }

    @Override
    public void onModifiersChanged() {
        updateModifierButtons();
    }

    /* -------------------- 状态与提示 -------------------- */

    /**
     * 模式 chip = 顶栏唯一的「我现在在哪儿」。
     *
     * 除了模式名，还捎上两个只在 chip 上能看见的身份：本地会话是否 su 提权（root）、
     * 容器会话是否走 docker 运行时。信息取自**会话的 LaunchSpec**而不是 prefs——
     * 菜单里切 Root Shell / 运行时是「下次启动生效」，拿配置值会显示成已经切了，
     * 实际还在旧身份里跑；会话没起来时才回退到配置值（这时显示的就是预期值）。
     */
    private void updateModeButtons() {
        Tab tab = activeTabId != null ? tabs.get(activeTabId) : null;
        boolean local = tab != null ? MODE_LOCAL.equals(tab.mode) : MODE_LOCAL.equals(mode);
        TermSession session = terminal.getSession();
        LaunchSpec spec = session == null ? null : session.getSpec();
        boolean root = spec != null ? spec.root : prefs.getBoolean("root", false);
        boolean docker = spec != null
                ? spec.docker
                : "docker".equals(prefs.getString("runtime", "proot"));

        StringBuilder label = new StringBuilder(
                local ? "本地" : "容器 · " + (tab != null ? tab.container
                        : Containers.activeName(this)));
        if (local && root) {
            label.append(" · root");
        }
        if (!local && docker) {
            label.append(" · docker");
        }
        // ▾ 是「可下拉」的提示，本地/容器两边保持一致
        label.append(" ▾");
        // 每帧状态刷新都会走到这里，同文本就不重设，免得白触发一次 layout
        if (!label.toString().contentEquals(btnMode.getText())) {
            btnMode.setText(label);
        }
        btnMode.setTextColor(local ? COLOR_TEXT : COLOR_ACCENT);
    }

    private void updateModifierButtons() {
        if (keyBarUi != null) {
            keyBarUi.refreshModifiers(terminal);
        }
    }

    /**
     * 状态并进顶栏一行（以前是独立的一整行，只为了显示 pid 和行列数）。
     * 全屏时顶栏是收起的，这时额外消息改用 Toast，否则「进程已退出」没人看得见。
     */
    @Override
    public void updateStatus(String extra) {
        TermSession session = terminal.getSession();
        StringBuilder sb = new StringBuilder();
        if (session != null) {
            // 状态行只说终端自己的几何信息：行列数、字号、pid。
            // 模式/容器名不写——左边 chip 本身就是下拉选择器，点开就是完整列表，
            // 顶栏右侧再写一遍纯属重复。早先试过「按 title 去重」，那是自找麻烦：
            // title（"容器 · v24"）和 chip 文本是两套字符串，还得为 Docker 的
            // 「（Docker）」尾巴写规则。不显示就没有重复可去。
            sb.append(terminal.getGridCols()).append('x').append(terminal.getGridRows());
            // 字号跟着行列数一起变，捏合时看这里就知道停在几号（用户反馈：光看字猜不出 sp 值）
            sb.append(" · ").append(terminal.getTextSizeSp()).append("sp");
            if (session.getPid() > 0) {
                sb.append(" · ").append(session.getPid());
            }
            // extra 通常是「进程已退出，code=…」，别再缀一个重复的「已退出」
            if (!session.isRunning() && (extra == null || !extra.contains("退出"))) {
                sb.append(" · 已退出");
            }
        } else {
            sb.append("等待启动");
        }
        if (extra != null && !extra.isEmpty()) {
            sb.append(" · ").append(extra);
            if (fullscreen) {
                toast(extra);
            }
        }
        status.setText(sb.toString());
        // 会话刚起来时 LaunchSpec 才拿到手（root / docker 标记在里面），
        // 所以 chip 跟着状态一起刷：省得另外找"会话启动完成"的回调点
        updateModeButtons();
    }

    private void showOverlay(String message) {
        overlayText.setText(message);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    @Override
    public void showError(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message == null ? "未知错误" : message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        return (msg == null || msg.isEmpty()) ? t.toString() : msg;
    }

    @Override
    public void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /* -------------------- 功能键条 -------------------- */

    /**
     * 键条装配下沉到 ui/KeyBar：键位、样式、图标键行为都在那边；宿主只提供
     * 终端 / 辅助键盘 / IME / 全屏 / 菜单这些行为入口。编辑键与 F 键在自制键盘。
     */
    private void buildKeyBar() {
        keyBarUi = new KeyBar(keyBar, keyRow, new KeyBar.Host() {
            @Override
            public TerminalView terminal() {
                return terminal;
            }

            @Override
            public SoftKeyboard softKeyboard() {
                return softKbd;
            }

            @Override
            public boolean imeVisible() {
                return imeVisible;
            }

            @Override
            public void onKeyboardShown() {
                imeFromTerminal = true;
                terminal.showKeyboard();
            }

            @Override
            public void onToggleFullscreen() {
                applyFullscreen(!fullscreen);
            }

            @Override
            public void onMenu(View anchor) {
                showMenu(anchor);
            }
        });
        keyBarUi.build();
    }

    /* -------------------- 自绘软键盘（构建在 dev.dualterm.ui.SoftKeyboard） -------------------- */

    private void buildSoftKeyboard() {
        softKbd = new SoftKeyboard(new SoftKeyboard.Host() {
            @Override
            public TerminalView terminal() {
                return terminal;
            }

            @Override
            public int dp(int value) {
                return MainActivity.this.dp(value);
            }

            @Override
            public boolean hardKeyboard() {
                return hardKeyboard;
            }

            @Override
            public boolean sessionEnded() {
                return sessionEnded;
            }

            @Override
            public String storedKeysJson() {
                return prefs.getString("soft_kbd_keys", null);
            }

            @Override
            public void storeKeysJson(String json) {
                if (json == null) {
                    prefs.edit().remove("soft_kbd_keys").apply();
                } else {
                    prefs.edit().putString("soft_kbd_keys", json).apply();
                }
            }

            @Override
            public int maxCustomRows() {
                return prefs.getInt("soft_kbd_max_rows", 4);
            }

            @Override
            public void onSoftKbdVisibilityChanged(boolean visible) {
                if (keyBarUi != null) {
                    keyBarUi.setKeyboardAccent(visible);
                }
                updateChrome();
                // kbd 高度变了，终端那一份 weight 必须重算：只让 key_bar 重新测量
                // 会留下「键盘多高、终端还是老高度」的错位（实测最后一行被挤到屏幕外）
                View flow = (View) keyBar.getParent();
                if (flow != null) {
                    flow.requestLayout();
                }
                updateStatus(null);
            }
        });
        softKbd.build((ViewGroup) findViewById(R.id.kbd),
                getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
    }

    /* -------------------- 模式选择 -------------------- */

    private void showModePicker() {
        new SessionPicker(this, btnMode, new SessionPicker.Host() {
            @Override
            public java.util.List<String> sessionLabels() {
                java.util.List<String> labels = new java.util.ArrayList<>();
                // ● = 当前贴屏的，点击直接切换（不杀任何会话）
                for (java.util.Map.Entry<String, Tab> e : tabs.entrySet()) {
                    Tab t = e.getValue();
                    String label = (MODE_LOCAL.equals(t.mode) ? "本地" : "容器 · " + t.container)
                            + " · pid " + t.session.getPid()
                            + (t.session.isRunning() ? "" : "（已退出）");
                    labels.add(e.getKey().equals(activeTabId) ? "● " + label : "   " + label);
                }
                return labels;
            }

            @Override
            public String newContainerLabel() {
                return "＋ 新建容器会话（" + Containers.activeName(MainActivity.this) + "）";
            }

            @Override
            public void onSwitchSession(int index) {
                // 会话列表项：按创建顺序找到对应 tab
                int i = 0;
                for (Tab t : tabs.values()) {
                    if (i++ == index) {
                        attachTab(t);
                        return;
                    }
                }
            }

            @Override
            public void onNewLocal() {
                // 新建 = 直接起会话，已有本地 tab 不会被复用也不会被杀
                startLocal();
            }

            @Override
            public void onNewContainer() {
                if (Containers.items(MainActivity.this).isEmpty()) {
                    showNoContainerGuide();
                    return;
                }
                applyContainerMode();
                refreshModeChip();
                startContainer(Containers.activeName(MainActivity.this));
            }

            @Override
            public void onContainers() {
                containerDialogs.showContainersDialog();
            }
        }).show();
    }

    /* -------------------- 输入设备自适应 -------------------- */

    /**
     * 物理键盘有没有插上，决定「键条要不要存在」「软键盘按钮有没有意义」。
     *
     * 蓝牙键盘热插拔不会改 Configuration（除非系统重启/旋转），所以还得听
     * InputManager 的设备增删事件，否则拔了键盘键条不回来、插上也不消失。
     */
    private void installInputDeviceWatcher() {
        inputManager = (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
        if (inputManager == null) {
            return;
        }
        inputDeviceListener = new android.hardware.input.InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                refreshInputMode();
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                refreshInputMode();
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                refreshInputMode();
            }
        };
        inputManager.registerInputDeviceListener(inputDeviceListener,
                new android.os.Handler(getMainLooper()));
    }

    /**
     * chrome 显隐的唯一入口。全屏、软键盘、物理键盘、会话结束这些状态都会改
     * 顶栏/键条的可见性，原来散在各自的回调里互相覆盖；现在汇到一处统一计算。
     *
     * 关键场景——横屏 + 软键盘：1080px 高的屏键盘吃掉 728px（实测 MIUI），
     * adjustResize 只给窗口留 276px，顶栏 38dp + 键条 46dp ≈ 230px，终端
     * 只剩 1 行还溢出（底部被键盘盖住）。此时收起顶栏、键条压到 28dp，
     * 把三四个终端行还回来；竖屏空间充裕，保持原布局不动。
     */
    private void updateChrome() {
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        boolean tight = (kbdShowing() || imeVisible) && landscape;
        // 自绘键盘没那么高，横屏也留得住顶栏；只有系统 IME（对话框里的输入框）
        // 才会把窗口压到 100dp，那时顶栏让位
        topBar.setVisibility((fullscreen || (imeVisible && landscape))
                ? View.GONE : View.VISIBLE);
        keyBar.setVisibility((hardKeyboard || sessionEnded) ? View.GONE : View.VISIBLE);
        if (keyBarUi != null) {
            keyBarUi.setButtonsVisible(!hardKeyboard);
            keyBarUi.setCompact(tight);
        }
        if (kbdShowing() && (hardKeyboard || sessionEnded)) {
            softKbd.setVisible(false);
        }
        softKbd.applyMetrics(landscape);
    }

    /** 键盘还没构建时（onCreate 前段）updateChrome 也会跑，统一从这里取。 */
    private boolean kbdShowing() {
        return softKbd != null && softKbd.isVisible();
    }

    private void refreshInputMode() {
        boolean hw = detectHardKeyboard();
        if (hw != hardKeyboard) {
            hardKeyboard = hw;
            if (hw && uiReady) {
                toast("已连接物理键盘：屏幕键条已收起，Esc/Tab/方向键直接用键盘");
            }
        }
        if (terminal != null) {
            // 插拔外接键盘会改变「输入法要不要接管硬键盘字母」的判定
            terminal.setImeTakeoverMode(takeoverMode());
            terminal.setExternalKeyboard(hardKeyboard);
        }
        // 会话结束时键条本来就该让位给结束条，键盘插拔也不能把它顶回来
        updateChrome();
        updateStatus(null);
    }

    /** 菜单「外接键盘中文输入」的三态设置，默认自动。 */
    private int takeoverMode() {
        int mode = prefs.getInt("hw_ime", 0);
        return mode == 1 || mode == 2 ? mode : 0;
    }

    private String takeoverLabel() {
        switch (takeoverMode()) {
            case 1:
                return "始终交给输入法";
            case 2:
                return "从不（字母逐键直发）";
            default:
                return terminal != null && terminal.isImeTakeover()
                        ? "自动：已接管" : "自动：未接管";
        }
    }

    /**
     * 外接键盘的中文输入策略。
     *
     * 「自动」跟输入法窗口的可见性走：系统「使用屏幕键盘」关着时候选栏不可见，
     * 组词变成盲打，残留拼音会以错词或原始拼音漏进命令行（实测），所以那种情况下
     * 保持字母直发。「始终」给候选画在自己窗口里的输入法、以及 API 30 以下用；
     * 「从不」保留「硬键盘敲命令 + 软键盘点拼音」的混用通路。
     */
    private void showImeTakeoverDialog() {
        final String[] labels = new String[]{
                "自动（输入法界面可见时接管）",
                "始终交给输入法（盲打风险自担）",
                "从不（硬键盘字母逐键直发）",
        };
        int cur = takeoverMode();
        // 说明文字不放 setMessage：实测（M2010J19SC，MIUI）它与 setSingleChoiceItems
        // 共存时单选列表被压成 0 高（uiautomator 证实 message 与按钮间只剩 ~11px，
        // 缩短 message 也复现），列表整个看不见、无法选档。取舍语义就写在选项文字里；
        // 详细代价与判定条件在 usage.md / limitations.md。
        new AlertDialog.Builder(this)
                .setTitle("外接键盘中文输入")
                .setSingleChoiceItems(labels, cur, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        prefs.edit().putInt("hw_ime", which).apply();
                        terminal.setImeTakeoverMode(which);
                        dialog.dismiss();
                        toast("外接键盘中文输入：" + takeoverLabel());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean detectHardKeyboard() {
        if (inputManager != null) {
            for (int id : inputManager.getInputDeviceIds()) {
                android.view.InputDevice device = inputManager.getInputDevice(id);
                if (device == null || device.isVirtual()) {
                    continue;
                }
                if (device.getKeyboardType()
                        == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC) {
                    return true;
                }
            }
        }
        android.content.res.Configuration c = getResources().getConfiguration();
        return c.keyboard == android.content.res.Configuration.KEYBOARD_QWERTY
                && c.hardKeyboardHidden == android.content.res.Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    /** 软键盘可见性（返回键分层、键盘弹出时收起更多键面板）。 */
    private void installImeWatcher() {
        final View root = findViewById(R.id.root);
        if (root == null) {
            return;
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(
                new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    private final android.graphics.Rect visible = new android.graphics.Rect();

                    @Override
                    public void onGlobalLayout() {
                        root.getWindowVisibleDisplayFrame(visible);
                        int hidden = root.getRootView().getHeight() - visible.bottom
                                - getWindow().getDecorView().getPaddingBottom();
                        boolean shown = hidden > dp(120);
                        if (shown != imeVisible) {
                            imeVisible = shown;
                            // 横屏下键盘吃掉大半屏幕，chrome 显隐和键条高度要跟着变
                            updateChrome();
                            if (shown) {
                                // 终端自己唤起 IME（点终端 / ⌨ / 会话启动）→ 保留辅助键盘，
                                // 两者就是一套组合；对话框输入框把 IME 叫出来 → 辅助键盘让位
                                if (imeFromTerminal) {
                                    imeFromTerminal = false;
                                } else {
                                    softKbd.setVisible(false);
                                }
                            }
                        }
                    }
                });
    }

    /* -------------------- 菜单 -------------------- */

    /**
     * 菜单数据在每次打开时现算：容器数/镜像数、运行时、Root 开关这些标签
     * 都随状态变，缓存反而要到处失效。
     */
    private java.util.List<MenuSheet.Group> buildMenuGroups() {
        java.util.List<MenuSheet.Group> groups = new java.util.ArrayList<>();
        groups.add(new MenuSheet.Group("会话",
                new String[]{"重启当前会话", "退出应用（结束会话）"},
                new int[]{1, 17}));
        groups.add(new MenuSheet.Group("屏幕内容",
                new String[]{"复制全部输出", "复制当前屏", "粘贴", "清屏"},
                new int[]{2, 18, 3, 4}));
        groups.add(new MenuSheet.Group("键盘与输入法",
                new String[]{"切换输入法…", "输入法设置…",
                        "外接键盘中文输入：" + takeoverLabel(),
                        "辅助键盘：恢复默认键集",
                        "辅助键盘行数：" + maxRowsLabel()},
                new int[]{24, 25, 26, 20, 21}));
        groups.add(new MenuSheet.Group("容器",
                new String[]{
                        "容器管理（" + Containers.items(this).size() + " 个容器 / "
                                + Containers.images(this).size() + " 个镜像）",
                        "管理控制台（docker 命令）…",
                        "导入镜像（docker save/export 的 tar）…",
                        "重建当前容器（清空其 rootfs）",
                        "绑定目录（" + Volumes.list(this).size() + "）",
                        "运行时：" + runtimeLabel(),
                },
                new int[]{14, 16, 15, 9, 12, 8}));
        groups.add(new MenuSheet.Group("外观",
                new String[]{fullscreen ? "退出全屏" : "全屏模式", "字号 +", "字号 -",
                        "终端字号范围：" + fontMinSp() + "–" + fontMaxSp() + "sp"},
                new int[]{11, 5, 6, 22}));
        groups.add(new MenuSheet.Group("设置",
                new String[]{
                        prefs.getBoolean("root", false) ? "Root Shell：开" : "Root Shell：关",
                        prefs.getBoolean("osc52", false) ? "程序写剪贴板：开" : "程序写剪贴板：关",
                        "存储权限：" + (hasAllFilesAccess() ? "已授权" : "未授权（点此开启）"),
                },
                new int[]{7, 23, 13}));
        groups.add(new MenuSheet.Group("帮助",
                new String[]{"使用说明…", "关于"},
                new int[]{19, 10}));
        return groups;
    }

    /**
     * 菜单入口。横屏只有约 360dp 高，早年平铺的 19 项会超出屏幕，后半段
     * （存储权限、帮助、关于、退出应用）既看不见也点不到。按职责收成 7 组；
     * 键盘/输入法五项曾分散在「屏幕内容」（切换输入法）与「设置」（外接键盘、
     * 辅助键盘），同类跨两处不好找，收进独立的「键盘与输入法」组；
     * 「终端字号范围」与外观组的字号 ± 同类，挪过去相邻。
     *
     * 面板装配、层级切换与弹出方向在 ui/MenuSheet；本方法只提供菜单数据。
     */
    private void showMenu(View anchor) {
        menuSheet.show(anchor, buildMenuGroups());
    }

    private String runtimeLabel() {
        return "docker".equals(prefs.getString("runtime", "proot")) ? "Docker（需 root）" : "PRoot";
    }

    private boolean onMenuClick(int id) {
        switch (id) {
            case 1:
                restartLastKind();
                return true;
            case 2:
                copyAll();
                return true;
            case 18:
                copyScreen();
                return true;
            case 3:
                paste();
                return true;
            case 4:
                if (terminal.getSession() != null) {
                    terminal.getSession().getEmulator().clearScreen();
                    terminal.invalidate();
                }
                return true;
            case 24:
                // 普通 app 无法静默切换输入法（setInputMethod 等是系统 API，
                // Android 14+ 明确阻断非系统应用）；唯一合法路径 = 系统选择器，
                // 由用户在弹窗里确认。终端场景实用：搜狗 ↔ 无联想输入法来回切。
                ((android.view.inputmethod.InputMethodManager) getSystemService(
                        Context.INPUT_METHOD_SERVICE)).showInputMethodPicker();
                return true;
            case 25:
                // 跳系统「管理输入法」启用页——只有启用了 ≥2 个 IME，
                // 选择器里才有东西可切。
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
                return true;
            case 5:
                changeFontSize(1);
                return true;
            case 6:
                changeFontSize(-1);
                return true;
            case 7:
                toggleRoot();
                return true;
            case 23:
                toggleOsc52();
                return true;
            case 20:
                softKbd.restoreDefaults();
                toast("辅助键盘已恢复默认键集");
                return true;
            case 21:
                showKeyboardRowsDialog();
                return true;
            case 22:
                showFontRangeDialog();
                return true;
            case 26:
                showImeTakeoverDialog();
                return true;
            case 8:
                toggleRuntime();
                return true;
            case 9:
                confirmWipe();
                return true;
            case 10:
                showAbout();
                return true;
            case 11:
                applyFullscreen(!fullscreen);
                if (fullscreen) {
                    toast("已进入全屏：按返回键（或点 ✕）退出");
                }
                return true;
            case 12:
                containerDialogs.showVolumesDialog();
                return true;
            case 13:
                requestStorageAccess();
                return true;
            case 14:
                containerDialogs.showContainersDialog();
                return true;
            case 15:
                containerDialogs.pickArchiveForImport();
                return true;
            case 16:
                startActivity(new android.content.Intent(this, ConsoleActivity.class));
                return true;
            case 17:
                confirmQuit();
                return true;
            case 19:
                openUrl(HELP_URL);
                return true;
            default:
                return false;
        }
    }

    /** 菜单里显示可自定义区的行数上限设置。 */
    private String maxRowsLabel() {
        int n = prefs.getInt("soft_kbd_max_rows", 4);
        return n <= 0 ? "不限（键多则键盘变高）" : "最多 " + n + " 行（超出滚动）";
    }

    /**
     * 可自定义区行数上限：键的数量本身不设上限，超出行数的部分在键盘内部
     * 竖向滚动，固定区（编辑键/F 键）和键条不动，终端不会被顶走。
     * 数字输入框而不是单选列表：留空或 0 = 不限，1–10 = 最多几行。
     */
    private void showKeyboardRowsDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        int cur = prefs.getInt("soft_kbd_max_rows", 4);
        if (cur > 0) {
            input.setText(String.valueOf(cur));
        } else {
            input.setHint("留空 = 不限");
        }
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("辅助键盘最多几行")
                .setMessage("可自定义区的行数上限，超出部分在键盘内竖向滚动。\n留空或 0 表示不限（键多则键盘一直变高）。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int value = parseDialogInt(input.getText().toString(), 0);
                if (value < 0 || value > 10) {
                    toast("行数只能是 0–10（0 或留空 = 不限）");
                    return;
                }
                prefs.edit().putInt("soft_kbd_max_rows", value).apply();
                if (softKbd != null) {
                    softKbd.refresh();
                }
                dialog.dismiss();
                toast(value <= 0
                        ? "键盘行数：不限"
                        : "键盘最多 " + value + " 行，超出可竖向滚动");
            }
        });
    }

    /** 对话框数字输入的统一解析：空白按 fallback，非数字（脏输入）也按 fallback。 */
    private static int parseDialogInt(String text, int fallback) {
        try {
            return text.trim().isEmpty() ? fallback : Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * 终端字号范围：一个对话框两个数字输入框，直接改上下限。
     *
     * 背景：竖屏 80 列（vim 默认限行宽）放不放得下由「屏宽 × 字号」决定，
     * 同为 6sp 最小字号，Note 8 有富余、Note 9 溢出折行——窄屏机需要一个
     * 能再往下的下限，宽屏机则可以把下限调高避免误捏到看不清的字号。
     * 改完立即生效：终端把区间外字号夹回；font_size 落盘值不迁移，下次
     * 读取时由 setTextSizeSp 的钳制兜住。
     */
    private void showFontRangeDialog() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * density);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView minLabel = new TextView(this);
        minLabel.setText("最小字号（sp，2–36）");
        minLabel.setTextSize(12);
        minLabel.setTextColor(0xFF9AA0AE);

        final EditText minInput = new EditText(this);
        minInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        minInput.setSingleLine(true);
        minInput.setText(String.valueOf(fontMinSp()));

        TextView maxLabel = new TextView(this);
        maxLabel.setText("最大字号（sp，最大需大于最小）");
        maxLabel.setTextSize(12);
        maxLabel.setTextColor(0xFF9AA0AE);
        maxLabel.setPadding(0, (int) (10 * density), 0, 0);

        final EditText maxInput = new EditText(this);
        maxInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxInput.setSingleLine(true);
        maxInput.setText(String.valueOf(fontMaxSp()));

        box.addView(minLabel);
        box.addView(minInput);
        box.addView(maxLabel);
        box.addView(maxInput);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("终端字号范围")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", null)
                .show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int min = parseDialogInt(minInput.getText().toString(), -1);
                int max = parseDialogInt(maxInput.getText().toString(), -1);
                if (min < 2 || min > 36) {
                    toast("最小字号只能是 2–36");
                    return;
                }
                if (max < 4 || max > 72) {
                    toast("最大字号只能是 4–72");
                    return;
                }
                if (min >= max) {
                    toast("最大字号（" + max + "）必须大于最小字号（" + min + "）");
                    return;
                }
                prefs.edit().putInt("font_min", min).putInt("font_max", max).apply();
                terminal.setFontRange(min, max);
                updateStatus(null);
                dialog.dismiss();
                toast("字号范围：" + min + "–" + max + "sp");
            }
        });
    }

    /** 真正结束会话（返回键只退后台，避免误触把正在跑的编译/tmux 杀掉）。 */
    private void confirmQuit() {
        new AlertDialog.Builder(this)
                .setTitle("退出应用")
                .setMessage("会结束本地 / 容器里正在跑的进程。\n\n只是想暂时离开的话，按返回键即可，会话会保持。")
                .setNegativeButton("取消", null)
                .setPositiveButton("退出", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        finish();
                    }
                })
                .show();
    }

    private void copyAll() {
        TermSession session = terminal.getSession();
        if (session == null) {
            toast("当前没有会话");
            return;
        }
        copyToClipboard(session.getEmulator().getText(), "已复制 %d 个字符");
    }

    /**
     * 复制当前屏。区别于「复制全部输出」：跑过编译/日志之后全量动辄几万行，
     * 用户多半只需要刚才这一屏（贴到聊天里、贴进 issue）。
     */
    private void copyScreen() {
        TermSession session = terminal.getSession();
        if (session == null) {
            toast("当前没有会话");
            return;
        }
        copyToClipboard(session.getEmulator().getScreenText(), "已复制当前屏 %d 个字符");
    }

    /** 写剪贴板的统一入口。MIUI 上个别 ROM 会对写操作抛安全异常，兜住提示而不是崩。 */
    private void copyToClipboard(String text, String toastTemplate) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        try {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
            toast(String.format(toastTemplate, text.length()));
        } catch (Exception e) {
            toast("复制失败：" + e.getMessage());
        }
    }

    private void paste() {
        // 只调一次 getPrimaryClip 并判空：MIUI「读取剪贴板」弹窗被拒后，
        // hasPrimaryClip 可能仍为 true 而 getPrimaryClip 返回 null，曾因此 NPE 崩溃
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        CharSequence text = null;
        try {
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                ClipData.Item item = clip.getItemAt(0);
                text = item == null ? null : item.coerceToText(this);
            }
        } catch (Exception e) {
            toast("读取剪贴板被拒绝或失败");
            return;
        }
        if (text != null && text.length() > 0) {
            terminal.pasteText(text.toString());
        }
    }

    private void changeFontSize(int delta) {
        int size = terminal.getTextSizeSp() + delta;
        if (size < fontMinSp()) {
            size = fontMinSp();
        }
        if (size > fontMaxSp()) {
            size = fontMaxSp();
        }
        terminal.setTextSizeSp(size);
        prefs.edit().putInt("font_size", size).apply();
        updateStatus(null);
    }

    /** 终端字号区间（菜单「设置 → 终端字号范围」可改，落盘 font_min/font_max）。 */
    private int fontMinSp() {
        return prefs.getInt("font_min", 6);
    }

    private int fontMaxSp() {
        return prefs.getInt("font_max", 28);
    }

    /* -------------------- 全屏 -------------------- */

    /**
     * 全屏：隐藏系统栏（状态栏 + 导航栏）并收起顶栏/状态行，把整块屏幕留给终端。
     * 功能键行保留（至少留一个能敲 Ctrl-C 的入口）。
     *
     * 退出方式：返回键/手势（第一次退全屏、第二次退到后台），或点键行里的 ✕。
     * 之前把长按终端当唯一出口，问题在于「藏起来的操作」——首次进全屏的用户
     * 根本不知道要长按，只能靠 toast 提示记住；而 back 是系统级习惯，无需教学。
     */
    private void applyFullscreen(boolean on) {
        fullscreen = on;
        prefs.edit().putBoolean("fullscreen", on).apply();

        updateChrome();
        if (keyBarUi != null) {
            keyBarUi.setFullscreenState(on);
        }

        Window window = getWindow();
        View decor = window.getDecorView();

        if (on) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowManager.LayoutParams lp = window.getAttributes();
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(lp);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
            }
            // onCreate 阶段窗口还没 attach，此时 hide() 会被系统丢弃，所以延后到布局完成后再隐藏一次
            decor.post(this::hideSystemBars);
            hideSystemBars();
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 保持 edge-to-edge，只把系统栏显示回来；padding 由 insets 回调更新
                WindowInsetsController c = window.getInsetsController();
                if (c != null) {
                    c.show(WindowInsets.Type.systemBars());
                }
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            findViewById(R.id.root).requestApplyInsets();
        }
        updateStatus(null);
        // 全屏下顶栏（含 ⋮）收起，菜单只剩「长按 ⋯」。第一次进全屏提示一次，
        // 之后就别再打扰——全屏的意义就是没有 chrome。
        if (on && !prefs.getBoolean("menu_hint", false)) {
            prefs.edit().putBoolean("menu_hint", true).apply();
            toast("全屏下菜单入口：长按键行的 ⋯");
        }
    }

    /** 隐藏系统栏。窗口未 attach 时调用无效，所以 applyFullscreen / 焦点变化时都会重试。 */
    private void hideSystemBars() {
        if (!fullscreen) {
            return;
        }
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController c = window.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // 从最近任务切回、或用户上滑把系统栏临时叫出来之后，重新进入沉浸式
        if (hasFocus && fullscreen) {
            hideSystemBars();
        }
        // 冷启动/切换回来时如果焦点还没定，把输入焦点还给终端：
        // 不然物理键盘的 Enter 会激活第一个可聚焦控件（模式 chip），而不是发回车
        if (hasFocus && getCurrentFocus() == null) {
            terminal.requestFocus();
        }
    }

    /**
     * 全屏时窗口会延伸到系统栏/挖孔下面，给根布局补上对应的 padding，
     * 否则第一行字符会落在刘海底下、底部功能键会被手势条压住。
     */
    private void applyInsetsPadding(int top, int bottom) {
        View root = findViewById(R.id.root);
        if (root == null) {
            return;
        }
        if (root.getPaddingTop() != top || root.getPaddingBottom() != bottom) {
            root.setPadding(root.getPaddingLeft(), top, root.getPaddingRight(), bottom);
        }
    }

    /** 全屏时的 insets 处理：状态栏/导航栏被隐藏后 systemBars 归零，只剩挖孔还要避让。 */
    private void installInsetsHandler() {
        final View root = findViewById(R.id.root);
        if (root == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            // 顶部：状态栏 / 挖孔（全屏时系统栏归零，只剩挖孔）
            android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            // 底部：导航栏 / 手势条 / 输入法，取最大的那个。decorFits=false 时
            // 窗口不随键盘缩放，只有把 IME 高度算进来，终端底部才不会被盖住
            android.graphics.Insets gest = insets.getInsets(WindowInsets.Type.systemGestures());
            int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            // MIUI 手势导航不报导航条 insets（bars.bottom=0），但手势条实际
            // 压着底部 ~16dp：键盘回车键正好落进去点不到。自己补一块。
            int navPad = isGestureNavigation() ? dp(16) : 0;
            if (fullscreen && bars.top == 0 && bars.bottom == 0) {
                navPad = 0;   // 全屏沉浸：系统栏真隐藏了，空间全给终端
            }
            int bottom = Math.max(Math.max(bars.bottom, gest.bottom),
                    Math.max(ime, navPad));
            applyInsetsPadding(bars.top, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    /* -------------------- 容器对话框宿主能力（ContainerDialogs.Host） -------------------- */

    @Override
    public Context context() {
        return this;
    }

    @Override
    public Context appContext() {
        return getApplicationContext();
    }

    @Override
    public java.util.concurrent.ExecutorService background() {
        return background;
    }

    @Override
    public void runOnUi(Runnable r) {
        runOnUiThread(r);
    }

    @Override
    public void showProgress(String message) {
        showOverlay(message);
    }

    @Override
    public void updateProgress(String message) {
        overlayText.setText(message);
    }

    @Override
    public void hideProgress() {
        hideOverlay();
    }

    @Override
    public boolean allFilesAccess() {
        return hasAllFilesAccess();
    }

    @Override
    public void startForResult(Intent intent, int requestCode) {
        startActivityForResult(intent, requestCode);
    }

    @Override
    public boolean inContainerMode() {
        return MODE_CONTAINER.equals(mode);
    }

    @Override
    public void applyContainerMode() {
        mode = MODE_CONTAINER;
        prefs.edit().putString("mode", MODE_CONTAINER).apply();
    }

    @Override
    public void refreshModeChip() {
        updateModeButtons();
    }

    @Override
    public void resetFallback() {
        fallbackTried = false;
    }

    @Override
    public void stopContainerTabs(String name) {
        dropTabs(MODE_CONTAINER, name);
    }

    @Override
    public void restartContainerMode() {
        switchMode(MODE_CONTAINER, true);
    }

    @Override
    public void openConsole() {
        startActivity(new Intent(this, ConsoleActivity.class));
    }

    /* -------------------- 从 tar 导入镜像 -------------------- */

    /** 文件选择器回来：导入镜像的请求交给 ui/ContainerDialogs 续接。 */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        containerDialogs.handleResult(requestCode, resultCode, data);
    }

    /* -------------------- 存储权限 -------------------- */

    /** Android 11+ 用「所有文件访问」；10 及以下用运行时读写权限。 */
    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (hasAllFilesAccess()) {
            toast("存储权限已授予");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1001);
        }
        toast("授予后容器里的 /sdcard 绑定才能看到真实文件");
    }

    /* -------------------- 全屏结束 -------------------- */

    private void toggleRoot() {
        boolean next = !prefs.getBoolean("root", false);
        prefs.edit().putBoolean("root", next).apply();
        if (next && !Shells.isRootAvailable()) {
            toast("没找到 su，本地模式仍会以普通应用身份运行");
        } else {
            toast(next ? "本地模式将尝试 su 提权（重启会话生效）" : "已关闭 root");
        }
        // 没有活动会话时 chip 回退显示配置值，切换后立刻反映出来
        updateModeButtons();
    }

    /**
     * OSC 52 总开关：程序（vim `"+y`、tmux copy-mode 等）请求写系统剪贴板。
     * 默认关——终端里跑的任何程序都能摸到剪贴板是真实的安全面，
     * 和 Termux 的 allow-clipboard 外部访问同一条界线。
     */
    private void toggleOsc52() {
        boolean next = !prefs.getBoolean("osc52", false);
        prefs.edit().putBoolean("osc52", next).apply();
        toast(next ? "已允许程序写剪贴板（vim 的 \"+y 等生效）"
                : "已禁止程序写剪贴板");
    }

    private void toggleRuntime() {
        boolean docker = "docker".equals(prefs.getString("runtime", "proot"));
        prefs.edit().putString("runtime", docker ? "proot" : "docker").apply();
        toast("容器运行时：" + runtimeLabel() + "（切换后请重启容器会话）");
        updateModeButtons();
    }

    private void confirmWipe() {
        final String name = Containers.activeName(this);
        if (Containers.find(this, name) == null) {
            showNoContainerGuide();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("重建容器 " + name)
                .setMessage("会用镜像重新物化一份干净的 rootfs。容器里装过的软件会全部丢失；"
                        + "镜像和宿主绑定目录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定重建", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        // 只重建这一个容器的 tab；别的容器会话继续跑
                        dropTabs(MODE_CONTAINER, name);
                        showOverlay("重建容器 " + name + " …");
                        background.execute(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Containers.recreate(getApplicationContext(), name);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            hideOverlay();
                                            startContainer(name);
                                        }
                                    });
                                } catch (final Exception e) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            hideOverlay();
                                            showError("重建失败", describe(e));
                                        }
                                    });
                                }
                            }
                        });
                    }
                })
                .show();
    }

    /**
     * 用浏览器打开文档。没有可用浏览器时（精简 ROM、无痕设备）退化为复制链接，
     * 至少让用户能把地址带走，而不是点了一点反应都没有。
     */
    private void openUrl(String url) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("url", url));
            }
            toast("没有可用浏览器，链接已复制：" + url);
        } catch (Exception e) {
            toast("打开失败：" + describe(e));
        }
    }

    private void showAbout() {
        String message = "双态终端 DualTerm " + versionLabel() + "\n\n"
                + "模式 A｜本地：直接 fork 应用沙箱内的 /system/bin/sh，"
                + "可选 su 提权。共享 Android 内核与文件系统。\n\n"
                + "模式 B｜容器：PRoot 用户态重定向，每个容器自带独立 rootfs（Alpine），"
                + "容器内假 root、可 apk add 装包，不污染 Android。\n\n"
                + "容器与镜像：镜像只是模板，容器从镜像物化一份可写 rootfs 后互相独立。"
                + "管理命令只在 App 的「容器管理控制台」里执行（菜单 → 容器管理控制台），"
                + "容器内不放任何脚本，因此不会和镜像自带的 docker 同名冲突，也没法被绕过。\n"
                + "控制台支持：\n"
                + "  docker ps [-a] / images / run / start / stop / rm / rmi / pull\n"
                + "  docker load -i x.tar / import x.tar 名:标签\n"
                + "内置 CLI 是子集，不支持 build / push / compose。\n\n"
                + "镜像导入：认 docker save 的层叠包（manifest.json，含 .wh. 白化与多架构目录）"
                + "和 docker export 的扁平 rootfs，也认 containerd/podman 的 OCI 布局；"
                + "镜像架构必须与设备一致。\n\n"
                + "Docker 后端需设备自带 root 与 dockerd，多数手机内核不支持，仅作实验开关。";
        new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage(message)
                .setNeutralButton("使用说明", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        openUrl(HELP_URL);
                    }
                })
                .setPositiveButton("好的", null)
                .show();
    }

    /** 版本名取自身装包信息，避免关于对话框里的版本号与构建脱节。 */
    private String versionLabel() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /* -------------------- 生命周期 -------------------- */

    @Override
    public void onBackPressed() {
        // 自绘菜单是非焦点窗口，返回键落到这里：二级退回顶层，顶层才收起
        if (menuSheet.handleBack()) {
            return;
        }
        if (overlay.getVisibility() == View.VISIBLE) {
            return;
        }
        if (kbdShowing()) {
            softKbd.setVisible(false);
            return;
        }
        if (imeVisible) {
            terminal.hideKeyboard();
            return;
        }
        // 全屏是「一层」界面状态：返回键先把它退掉，这也是 Android 的标准语义
        // （back 逐层退，最后才离开应用）。会话不受影响。
        if (fullscreen) {
            applyFullscreen(false);
            toast("已退出全屏，会话保持运行");
            return;
        }
        // 返回键在终端里不该等于「杀掉正在跑的进程」：退到后台，回来还在原处。
        // 真正结束会话走菜单里的「退出应用」。
        moveTaskToBack(true);
        toast("已退到后台，会话保持运行（菜单 → 退出应用 可结束）");
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转 / 键盘支架开合都会走这里
        // 菜单锚在 ⋮ 按钮上，转屏后位置会挪，直接收起最干净
        menuSheet.dismiss();
        refreshInputMode();
        updateChrome();
        updateStatus(null);
    }

    @Override
    protected void onStart() {
        super.onStart();
        setSessionsVisible(true);
    }

    @Override
    protected void onStop() {
        saveViewportAnchor();
        setSessionsVisible(false);
        super.onStop();
    }

    /**
     * 可见性变化：所有会话一起切读线程节流。
     * 后台没有绘制来天然限速，不节流的话持续输出会一直吃满一到两个核。
     */
    private void setSessionsVisible(boolean visible) {
        activityVisible = visible;
        for (Tab t : tabs.values()) {
            t.session.setThrottled(!visible);
        }
        if (visible) {
            // 后台期间屏幕模型照常更新，回来补一帧把这段时间的输出画出来
            terminal.postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 后台期间插拔的键盘收不到回调，回到前台补一次
        refreshInputMode();
    }

    /**
     * 内存吃紧时收缩历史。每个会话的 scrollback 是最大的一块 Java 堆占用
     * （3000 行 × 4 个数组 ≈ 2MB/会话），比任何视图都值得先放掉；
     * 逐出计入全局行号，选区坐标不会错位。
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            return;
        }
        int keep = level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND ? 300 : 600;
        for (Tab t : tabs.values()) {
            t.session.getEmulator().trimScrollback(keep);
        }
        if (activityVisible) {
            terminal.postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDestroy() {
        saveViewportAnchor();
        if (inputManager != null && inputDeviceListener != null) {
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
            inputDeviceListener = null;
        }
        if (isFinishing()) {
            background.shutdownNow(); // 排队/在跑的后台任务全部中断
            for (Tab t : tabs.values()) {
                t.session.stop();
            }
            tabs.clear();
            activeTabId = null;
            Containers.clearRunning();
            SessionService.sync(getApplicationContext(), 0);
        } else {
            // 系统销毁 Activity 但进程还在（字体/语言变更、不保留活动、内存回收）：
            // 会话继续跑，静态 tab 表留给下一个实例接管；监听者必须摘掉，
            // 否则已销毁的界面会被会话一直引用着（泄漏整个 View 树）。
            for (Tab t : tabs.values()) {
                t.session.setListener(null);
            }
        }
        super.onDestroy();
    }

    /** 手势导航（navigation_mode=2）。三键导航的系统会正常报导航条 insets。 */
    private boolean isGestureNavigation() {
        try {
            return android.provider.Settings.Secure.getInt(getContentResolver(),
                    "navigation_mode", 0) == 2;
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
