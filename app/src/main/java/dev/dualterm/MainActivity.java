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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import dev.dualterm.container.ContainerBootstrap;
import dev.dualterm.container.ContainerRuntime;
import dev.dualterm.container.Containers;
import dev.dualterm.container.ImageImporter;
import dev.dualterm.container.Volumes;
import dev.dualterm.term.LaunchSpec;
import dev.dualterm.term.Shells;
import dev.dualterm.term.TermSession;
import dev.dualterm.term.TerminalView;
import dev.dualterm.ui.ConsoleActivity;

/**
 * 主界面：两个模式共用一个终端视图。
 *
 * 两种模式各自缓存一个 TermSession，来回切换不丢现场（不重启进程，屏幕内容还在）。
 */
public class MainActivity extends Activity
        implements TermSession.Listener,
        TerminalView.GridListener,
        TerminalView.ModifierWatcher {

    private static final String PREF = "dualterm";
    private static final String MODE_LOCAL = "local";
    private static final String MODE_CONTAINER = "container";

    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_ACCENT = 0xFF7CE38B;

    private static final int REQ_IMPORT_TAR = 3301;

    private SharedPreferences prefs;

    private TerminalView terminal;
    private TextView status;
    private TextView btnMode;
    private Button btnKeyboard;
    private Button btnFullscreen;
    private Button btnMenu;
    private View keyBar;
    private LinearLayout keyRow;
    private LinearLayout keyPanel;
    private View overlay;
    private TextView overlayText;

    private Button ctrlKey;
    private Button altKey;
    private boolean keyPanelOpen;
    /** 键行里的「⋯」按钮：面板驻留后要靠它高亮显示开关状态。 */
    private Button keyPanelKey;

    /* -------------------- 自绘软键盘 -------------------- */
    /** 字母页 / 符号页容器（同一个 kbd 下互斥显示）。 */
    private LinearLayout kbd;
    private LinearLayout kbdLetters;
    private LinearLayout kbdSymbols;
    /** 两页里所有按键行，横竖屏切换时统一改行高。 */
    private final java.util.List<LinearLayout> kbdRows = new java.util.ArrayList<>();
    /** SHIFT 键（两页各一个），按一次亮、打完一个字母自动熄灭。 */
    private final java.util.List<Button> shiftKeys = new java.util.ArrayList<>();
    private boolean kbdVisible;
    private boolean shiftLatch;
    private boolean symbolPage;

    /** 会话结束条：和键条互斥，占同一块屏幕位置。 */
    private View exitBar;
    private TextView exitText;
    private boolean sessionEnded;
    private int lastExitCode;

    /** 软键盘在屏幕上（返回键分层用：先收键盘，再退后台）。 */
    private boolean imeVisible;
    /** 有没有物理键盘：决定键条是否出现——键盘上本来就有 ESC/Tab/方向键。 */
    private boolean hardKeyboard;
    private android.hardware.input.InputManager inputManager;
    private android.hardware.input.InputManager.InputDeviceListener inputDeviceListener;
    /** onCreate 走完之前不弹「已连接物理键盘」的提示，避免每次冷启动都刷一条。 */
    private boolean uiReady;

    private final Map<String, TermSession> sessions = new HashMap<>();
    private String mode = MODE_LOCAL;

    /** 调试入口：启动后自动敲入的一条命令（正常使用为 null）。 */
    private String pendingCommand;
    /** 本轮启动是否已经做过一次「坏容器 → 换一个」的兜底，防止连环跳。 */
    private boolean fallbackTried;

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
        btnMenu = findViewById(R.id.btn_menu);
        keyBar = findViewById(R.id.key_bar);
        keyRow = findViewById(R.id.key_row);
        kbd = findViewById(R.id.kbd);
        keyPanel = findViewById(R.id.key_panel);
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
        // 现在的入口都是可见控件：顶栏 ⋮（非全屏）/ 长按键行的 ⋯（全屏下也在）。
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
        terminal.setTextSizeSp(prefs.getInt("font_size", 13));

        buildKeyBar();
        buildSoftKeyboard();
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
                switchMode(mode, true);
            }
        });
        findViewById(R.id.btn_copy_out).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAll();
            }
        });
        // 点终端 = 切换自绘键盘（和键行里的 ⌨ 同一个动作）
        terminal.setKeypadListener(new TerminalView.KeypadListener() {
            @Override
            public void onToggleKeypad() {
                // 面板是浮层：点终端正文 = 不用它了，先关面板（这是「点空白关闭」
                // 的一致语义），免得点一下终端又弹键盘、面板还赖着
                if (keyPanelOpen) {
                    closeKeyPanel();
                    return;
                }
                toggleSoftKbd();
            }

            @Override
            public void onShowKeypad() {
                setSoftKbd(true);
            }

            @Override
            public void onHideKeypad() {
                setSoftKbd(false);
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
        switchMode(MODE_CONTAINER.equals(saved) ? MODE_CONTAINER : MODE_LOCAL, false);
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
    private void switchMode(String target, boolean restart) {
        TermSession cached = sessions.get(target);
        if (!restart && cached != null && cached.isRunning()) {
            mode = target;
            prefs.edit().putString("mode", target).apply();
            updateModeButtons();
            attach(cached);
            return;
        }
        mode = target;
        prefs.edit().putString("mode", target).apply();
        updateModeButtons();
        fallbackTried = false;
        if (MODE_LOCAL.equals(target)) {
            startLocal(restart);
        } else {
            startContainer(restart);
        }
    }

    private void startLocal(boolean restart) {
        TermSession existing = sessions.remove(MODE_LOCAL);
        if (existing != null) {
            existing.stop();
        }
        try {
            boolean useRoot = prefs.getBoolean("root", false);
            LaunchSpec spec = Shells.localSpec(getApplicationContext(), useRoot);
            startSession(MODE_LOCAL, spec);
        } catch (Throwable t) {
            showError("无法启动本地 Shell", describe(t));
        }
    }

    private void startContainer(boolean restart) {
        startContainer(Containers.activeName(getApplicationContext()), restart);
    }

    private void startContainer(final String containerName, boolean restart) {
        TermSession existing = sessions.remove(MODE_CONTAINER);
        if (existing != null) {
            existing.stop();
        }
        Containers.setRunning(containerName, false);

        showOverlay("准备容器 " + containerName + " …");
        final String preferred = prefs.getString("runtime", "proot");
        final Context appContext = getApplicationContext();

        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    ContainerRuntime runtime = ContainerRuntime.create(appContext, preferred);
                    runtime.prepare(new ContainerRuntime.Progress() {
                        @Override
                        public void onProgress(final String message) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    overlayText.setText(message);
                                }
                            });
                        }
                    });
                    final LaunchSpec spec = runtime.launchSpec(containerName);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            fallbackTried = false;
                            Containers.setActive(MainActivity.this, containerName);
                            startSession(MODE_CONTAINER, spec);
                            Containers.setRunning(containerName, true);
                            updateStatus(null);
                        }
                    });
                } catch (final Throwable t) {
                    runOnUiThread(new Runnable() {
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
                                    startContainer(alt, true);
                                    return;
                                }
                            }
                            showError("容器模式启动失败", describe(t));
                        }
                    });
                }
            }
        }, "container-bootstrap");
        worker.setDaemon(true);
        worker.start();
    }

    private void startSession(String key, LaunchSpec spec) {
        int rows = Math.max(3, terminal.getGridRows());
        int cols = Math.max(20, terminal.getGridCols());
        TermSession session = new TermSession(spec, rows, cols, 3000, this);
        try {
            session.start();
        } catch (Throwable t) {
            showError("启动终端会话失败", describe(t));
            return;
        }
        sessions.put(key, session);
        attach(session);
        // 接了物理键盘（蓝牙/USB）就别强弹软键盘：横屏时软键盘能吃掉大半个屏幕，
        // 终端会被压到只剩两三行，而且软键盘窗口是 secure 的，连截图都会被系统拦掉。
        if (!hasHardKeyboard()) {
            terminal.showKeyboard();
        }
    }

    private boolean hasHardKeyboard() {
        return detectHardKeyboard();
    }

    private void attach(TermSession session) {
        terminal.setSession(session);
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
        closeKeyPanel();
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
        if (terminal.getSession() == session) {
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

    @Override
    public void onExit(final TermSession session, final int code) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // 会话真的退出了，容器就该从 running 里摘掉
                if (session == sessions.get(MODE_CONTAINER)
                        && !session.isRunning()) {
                    Containers.setRunning(Containers.activeName(MainActivity.this), false);
                }
                if (terminal.getSession() == session) {
                    lastExitCode = code;
                    showExitBar(code);
                    updateStatus(describeExit(code));
                }
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

    private void updateModeButtons() {
        boolean local = MODE_LOCAL.equals(mode);
        btnMode.setText(local ? "本地" : "容器 · " + Containers.activeName(this) + " ▾");
        btnMode.setTextColor(local ? COLOR_TEXT : COLOR_ACCENT);
    }

    private void updateModifierButtons() {
        applyModifierLook(ctrlKey, terminal.isCtrlDown());
        applyModifierLook(altKey, terminal.isAltDown());
    }

    /** 粘滞修饰键锁上时整键反白：触屏上没有 LEDs，只能靠颜色告诉用户「Ctrl 还按着」。 */
    private void applyModifierLook(Button key, boolean on) {
        if (key == null) {
            return;
        }
        key.setBackgroundResource(on ? R.drawable.key_bg_on : R.drawable.key_bg);
        key.setTextColor(on ? 0xFF101014 : COLOR_TEXT);
    }

    /**
     * 状态并进顶栏一行（以前是独立的一整行，只为了显示 pid 和行列数）。
     * 全屏时顶栏是收起的，这时额外消息改用 Toast，否则「进程已退出」没人看得见。
     */
    private void updateStatus(String extra) {
        TermSession session = terminal.getSession();
        StringBuilder sb = new StringBuilder();
        if (session != null) {
            // 容器模式下 chip 已经写了容器名，状态行不重复它
            String title = session.getSpec().title;
            boolean titleOnChip = MODE_CONTAINER.equals(mode)
                    && title.equals(Containers.activeName(this));
            if (!titleOnChip) {
                sb.append(title).append(" · ");
            }
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
            sb.append(MODE_LOCAL.equals(mode) ? "本地 Shell：等待启动" : "容器：等待启动");
        }
        if (extra != null && !extra.isEmpty()) {
            sb.append(" · ").append(extra);
            if (fullscreen) {
                toast(extra);
            }
        }
        status.setText(sb.toString());
    }

    private void showOverlay(String message) {
        overlayText.setText(message);
        overlay.setVisibility(View.VISIBLE);
    }

    private void hideOverlay() {
        overlay.setVisibility(View.GONE);
    }

    private void showError(String title, String message) {
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

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    /* -------------------- 功能键条 -------------------- */

    private static final int KEY_MOD_CTRL = 0;
    private static final int KEY_MOD_ALT = 1;
    private static final int KEY_RAW = 2;
    private static final int KEY_CURSOR = 3;
    private static final int KEY_PANEL = 4;

    /**
     * 键条 = 一屏放得下的常用键 + 可展开的更多键面板。
     *
     * 以前是 20 个键塞在横向 ScrollView 里：^C 排在第 13 位，得先横滑才找得到，
     * 而横向滚动条被隐藏后根本没人知道右边还有键。现在常用键等宽铺满一行（不用滑），
     * 剩下的进面板，按一次就收起——面板只在「临时要个 F 键」时占用高度。
     */
    private void buildKeyBar() {
        Object[][] primary = {
                {"CTRL", KEY_MOD_CTRL, null},
                {"ALT", KEY_MOD_ALT, null},
                {"ESC", KEY_RAW, "\u001b"},
                {"TAB", KEY_RAW, "\t"},
                {"^C", KEY_RAW, "\u0003"},
                {"←", KEY_CURSOR, "D"},
                {"↑", KEY_CURSOR, "A"},
                {"↓", KEY_CURSOR, "B"},
                {"→", KEY_CURSOR, "C"},
                {"⋯", KEY_PANEL, null},
        };
        for (Object[] spec : primary) {
            Button b = makeKeyButton((String) spec[0]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            b.setLayoutParams(lp);
            attachKeyAction(b, (String) spec[0], (Integer) spec[1], (String) spec[2]);
            keyRow.addView(b);
        }
        // 键盘开关 + 全屏开关。以前在顶栏各占 40dp 常驻（状态行被挤掉十几列），
        // 而它们本质上和 CTRL/ALT 一样是「操作终端」，归到这一行：既省顶栏宽度，
        // 又让全屏有可见的退出入口（不再依赖长按终端）。
        btnKeyboard = makeNarrowKey(getString(R.string.keyboard), 15);
        btnKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleSoftKbd();
            }
        });
        keyRow.addView(btnKeyboard);

        btnFullscreen = makeNarrowKey(K_FULLSCREEN, 15);
        btnFullscreen.setContentDescription("全屏");
        btnFullscreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyFullscreen(!fullscreen);
            }
        });
        keyRow.addView(btnFullscreen);

        buildKeyPanel();
    }

    private static final String K_FULLSCREEN = "\u26f6";   // ⛶
    private static final String K_FULLSCREEN_ON = "\u2715";  // ✕ 全屏时的退出提示

    /**
     * 功能键行右侧的窄键（固定宽度，不参与 weight 等分）。
     * 键盘/全屏这类图标键只需要能点到，不该把 CTRL/ALT 这些文字键挤窄。
     */
    private Button makeNarrowKey(String label, int textSize) {
        Button b = makeKeyButton(label);
        b.setTextSize(textSize);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(40));
        lp.setMargins(dp(2), 0, dp(2), 0);   // 与左侧文字键保持同样的键间距
        b.setLayoutParams(lp);
        return b;
    }

    /**
     * 更多键：HOME/END/PGUP/PGDN/DEL/INS + F1-F6。
     * 最初这里还有 12 个符号（/ - | ~ \ * & $ > < " '），和符号页完全重复
     * （面板展开时 4 行 + 键行 + 键盘占掉半屏）。符号统一收敛到键盘符号页，
     * 面板只留键盘上没有的编辑键/功能键。
     */
    private static final String[][] PANEL_KEYS = {
            {"HOME", "\u001b[H"}, {"END", "\u001b[F"}, {"PGUP", "\u001b[5~"},
            {"PGDN", "\u001b[6~"}, {"DEL", "\u001b[3~"}, {"INS", "\u001b[2~"},
            {"F1", "\u001bOP"}, {"F2", "\u001bOQ"}, {"F3", "\u001bOR"},
            {"F4", "\u001bOS"}, {"F5", "\u001b[15~"}, {"F6", "\u001b[17~"},
    };

    private void buildKeyPanel() {
        final int perRow = 6;
        LinearLayout row = null;
        for (int i = 0; i < PANEL_KEYS.length; i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                keyPanel.addView(row);
            }
            Button b = makeKeyButton(PANEL_KEYS[i][0]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            lp.setMargins(dp(2), dp(2), dp(2), 0);
            b.setLayoutParams(lp);
            final String sequence = PANEL_KEYS[i][1];
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 不收起：面板里全是「发送即生效」的编辑键/F 键，连按 PGUP、
                    // HOME 之后接 END 是常见操作。收起一次就会重排一次终端
                    // （TIOCSWINSZ → SIGWINCH），vim/htop 跟着整屏重绘，画面抖动
                    // 比多点一次难受得多。收起交给「⋯」再点一次 / 返回键 / 点终端。
                    terminal.sendRaw(sequence);
                }
            });
            row.addView(b);
        }
    }

    private Button makeKeyButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(11);
        b.setAllCaps(false);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(COLOR_TEXT);
        b.setBackgroundResource(R.drawable.key_bg);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setSingleLine(true);
        return b;
    }

    private void attachKeyAction(Button b, String label, int kind, final String arg) {
        switch (kind) {
            case KEY_MOD_CTRL:
                ctrlKey = b;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        terminal.setCtrlDown(!terminal.isCtrlDown());
                    }
                });
                break;
            case KEY_MOD_ALT:
                altKey = b;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        terminal.setAltDown(!terminal.isAltDown());
                    }
                });
                break;
            case KEY_CURSOR:
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        terminal.sendCursor(arg.charAt(0));
                    }
                });
                break;
            case KEY_PANEL:
                keyPanelKey = b;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleKeyPanel();
                    }
                });
                // 长按「⋯」= 应用菜单。这是全屏下唯一常驻的菜单入口：
                // 顶栏收起后 ⋮ 看不见，而键行一直在（触屏才有键行，菜单也只在
                // 触屏上才需要）。单击仍是高频的键面板，长按给低频的管理动作，
                // 两档手势都落在同一个可见控件上，不用教也不占宽度。
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        if (keyPanelOpen) {
                            closeKeyPanel();
                        }
                        showMenu(v);
                        return true;   // 长按吃掉这次触摸，松开不再触发 click
                    }
                });
                break;
            default:
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        terminal.sendRaw(arg);
                    }
                });
                break;
        }
    }

    /**
     * 「更多键」面板是**驻留**浮层，不是一次性弹出框：点开之后连按多少下都留着，
     * 直到再点一次「⋯」、按返回键，或在终端上点一下。键条上的「⋯」同步高亮，
     * 免得面板开着却看不出是谁开着的。
     */
    private void toggleKeyPanel() {
        if (keyPanelOpen) {
            closeKeyPanel();
        } else {
            keyPanelOpen = true;
            keyPanel.setVisibility(View.VISIBLE);
            applyKeyPanelStyle();
        }
    }

    private void closeKeyPanel() {
        if (!keyPanelOpen) {
            return;
        }
        keyPanelOpen = false;
        keyPanel.setVisibility(View.GONE);
        applyKeyPanelStyle();
    }

    private void applyKeyPanelStyle() {
        if (keyPanelKey == null) {
            return;
        }
        keyPanelKey.setTextColor(keyPanelOpen ? 0xFF101014 : COLOR_TEXT);
        keyPanelKey.setBackgroundResource(keyPanelOpen ? R.drawable.key_bg_on : R.drawable.key_bg);
    }

    /* -------------------- 自绘软键盘 -------------------- */

    private static final String K_SHIFT = "\u21e7";   // ⇧
    private static final String K_SYM = "?123";
    private static final String K_ABC = "ABC";
    private static final String K_SPACE = "\u2423";   // ␣
    private static final String K_BACK = "\u232b";    // ⌫
    private static final String K_ENTER = "\u23ce";   // ⏎
    /** 粘贴键：终端里粘贴是高频动作（贴命令、token、diff），藏在 ⋮ 菜单里要两步。 */
    private static final String K_PASTE = "粘贴";

    private static final String[] KBD_DIGITS = {
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "0",
    };

    private static final String[][] KBD_LETTERS = {
            // 顶部数字行：真键盘有，终端里也用得多（端口、chmod 755、kill -9、
            // 行号）。两页都放，切页不影响打数字
            KBD_DIGITS,
            {"q", "w", "e", "r", "t", "y", "u", "i", "o", "p"},
            {"a", "s", "d", "f", "g", "h", "j", "k", "l"},
            {K_SHIFT, "z", "x", "c", "v", "b", "n", "m", ",", "."},
            {K_SYM, K_PASTE, K_BACK, K_SPACE, K_ENTER},
    };

    /**
     * 符号页只放 shell 里真会用到的那批：管道/重定向/括号/引号/通配符。
     * 这些符号在系统软键盘上要翻两三页才找得到，这里一屏全在。
     */
    private static final String[][] KBD_SYMBOLS = {
            // 数字行两页一致；第一行补上 }（之前 { 有、} 全键盘找不到，
            // EOF 重定向写不了）；第三行删掉 , . （字母页已有），换上 ; 和 %
            KBD_DIGITS,
            {"|", "&", "<", ">", "(", ")", "[", "]", "{", "}"},
            {"$", "`", "\\", "\"", "'", "*", "?", "!", "~", "^"},
            {"=", "+", "-", "_", ":", ";", "/", "@", "#", "%"},
            {K_ABC, K_PASTE, K_BACK, K_SPACE, K_ENTER},
    };

    /**
     * 建自绘键盘：字母页 + 符号页各四行，底下那行是页切换/退格/空格/回车。
     * 上面的 CTRL/ALT/ESC/方向键工具行保持常驻，和键盘连用。
     */
    private void buildSoftKeyboard() {
        if (kbd == null) {
            return;
        }
        kbdLetters = buildKbdPage(KBD_LETTERS);
        kbdSymbols = buildKbdPage(KBD_SYMBOLS);
        kbdSymbols.setVisibility(View.GONE);
        kbd.addView(kbdLetters);
        kbd.addView(kbdSymbols);
        applyKbdMetrics(getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE);
    }

    private LinearLayout buildKbdPage(String[][] rows) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (String[] spec : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            kbdRows.add(row);
            for (final String label : spec) {
                Button b = makeKeyButton(label);
                b.setTextSize(K_SPACE.equals(label) ? 15 : 14);
                float weight = K_SPACE.equals(label) ? 3.0f : 1f;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(34), weight);
                lp.setMargins(dp(2), dp(2), dp(2), 0);
                b.setLayoutParams(lp);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onKbdKey(label);
                    }
                });
                if (K_SHIFT.equals(label)) {
                    shiftKeys.add(b);
                }
                row.addView(b);
            }
            page.addView(row);
        }
        return page;
    }

    /**
     * 横竖屏、键盘弹收时统一调行高：横屏一行 27dp 已经够点，省下的全给终端。
     *
     * 键盘自身高度写死（行数 × 行高），不用 wrap_content：三层嵌套的
     * wrap_content 在首次布局时会被测成「三行半」，最后一行整行被裁掉
     * （实测 ⏎ 键边界是 [0,0][0,0]，点了没反应）。写死之后 key_bar 的
     * wrap_content 也变成确定值，weight 分配才不会错位。
     */
    private void applyKbdMetrics(boolean landscape) {
        int h = dp(landscape ? 27 : 34);
        int margin = dp(2);
        for (LinearLayout row : kbdRows) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View c = row.getChildAt(i);
                ViewGroup.LayoutParams lp = c.getLayoutParams();
                if (lp.height != h) {
                    lp.height = h;
                    c.setLayoutParams(lp);
                }
            }
        }
        ViewGroup.LayoutParams klp = kbd.getLayoutParams();
        if (klp != null) {
            // 按实际行数算，不写死：键盘页从 4 行加到 5 行时写死会把底行裁掉
            int pageRows = kbdLetters == null ? 5 : kbdLetters.getChildCount();
            int kbdH = pageRows * (h + margin);
            if (klp.height != kbdH) {
                klp.height = kbdH;
                kbd.setLayoutParams(klp);
            }
        }
    }

    private void toggleSoftKbd() {
        setSoftKbd(!kbdVisible);
    }

    /**
     * 自绘键盘显隐。终端尺寸变化会走 onSizeChanged → computeGrid → TIOCSWINSZ，
     * 所以 vim/htop 会跟着新行数重排，不需要额外通知。
     */
    private void setSoftKbd(boolean show) {
        if (kbd == null || show == kbdVisible) {
            return;
        }
        if (show && (hardKeyboard || sessionEnded)) {
            return;
        }
        kbdVisible = show;
        if (show) {
            // 面板不再被键盘顶掉：面板 2 行 + 键盘 4 行 ≈ 6 行，竖屏 67 行、
            // 横屏 18 行都吃得下，而「Ctrl+方向键」这类操作正需要两者同屏。
            // 每次都从字母页、无 SHIFT 开始
            shiftLatch = false;
            switchKbdPage(false);
            applyShiftStyle();
        }
        kbd.setVisibility(show ? View.VISIBLE : View.GONE);
        btnKeyboard.setTextColor(show ? COLOR_ACCENT : COLOR_TEXT);
        updateChrome();
        // kbd 高度变了，终端那一份 weight 必须重算：只让 key_bar 重新测量
        // 会留下「键盘多高、终端还是老高度」的错位（实测最后一行被挤到屏幕外）
        View flow = (View) keyBar.getParent();
        if (flow != null) {
            flow.requestLayout();
        }
        updateStatus(null);
    }

    private void switchKbdPage(boolean symbols) {
        symbolPage = symbols;
        kbdLetters.setVisibility(symbols ? View.GONE : View.VISIBLE);
        kbdSymbols.setVisibility(symbols ? View.VISIBLE : View.GONE);
    }

    private void applyShiftStyle() {
        for (Button b : shiftKeys) {
            b.setTextColor(shiftLatch ? 0xFF101014 : COLOR_TEXT);
            b.setBackgroundResource(shiftLatch ? R.drawable.key_bg_on : R.drawable.key_bg);
        }
    }

    /** 一个按键的语义：修饰键/翻页/编辑键走专用动作，普通字符走 PTY。 */
    private void onKbdKey(String label) {
        if (K_SHIFT.equals(label)) {
            shiftLatch = !shiftLatch;
            applyShiftStyle();
            return;
        }
        if (K_SYM.equals(label)) {
            switchKbdPage(true);
            return;
        }
        if (K_ABC.equals(label)) {
            switchKbdPage(false);
            return;
        }
        if (K_SPACE.equals(label)) {
            terminal.sendRaw(" ");
            return;
        }
        if (K_BACK.equals(label)) {
            terminal.sendRaw("\u007f");
            return;
        }
        if (K_ENTER.equals(label)) {
            terminal.sendRaw("\r");
            return;
        }
        if (K_PASTE.equals(label)) {
            paste();
            return;
        }
        if (label.length() != 1) {
            return;
        }
        char c = label.charAt(0);
        if (shiftLatch && Character.isLetter(c)) {
            c = Character.toUpperCase(c);
        }
        String out;
        if (terminal.isCtrlDown()) {
            // Ctrl+a..z → 0x01..0x1a；Ctrl+[ / \ / ] 也吃同一个公式（0x1b/0x1c/0x1d）
            out = String.valueOf((char) (Character.toLowerCase(c) & 0x1f));
        } else if (terminal.isAltDown()) {
            out = "\u001b" + c;
        } else {
            out = String.valueOf(c);
        }
        // sendRaw 内部会清掉 CTRL/ALT 锁定：修饰键是一次性的，不会粘住
        terminal.sendRaw(out);
        if (shiftLatch) {
            shiftLatch = false;
            applyShiftStyle();
        }
    }

    /* -------------------- 模式选择 -------------------- */

    private void showModePicker() {
        PopupMenu menu = new PopupMenu(this, btnMode);
        menu.getMenu().add(0, 1, 0, "本地 Shell");
        menu.getMenu().add(0, 2, 1, "容器：" + Containers.activeName(this));
        menu.getMenu().add(0, 3, 2, "容器与镜像…");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case 1:
                        switchMode(MODE_LOCAL, false);
                        return true;
                    case 2:
                        switchMode(MODE_CONTAINER, false);
                        return true;
                    case 3:
                        showContainersDialog();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
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
        boolean tight = (kbdVisible || imeVisible) && landscape;
        // 自绘键盘没那么高，横屏也留得住顶栏；只有系统 IME（对话框里的输入框）
        // 才会把窗口压到 100dp，那时顶栏让位
        topBar.setVisibility((fullscreen || (imeVisible && landscape))
                ? View.GONE : View.VISIBLE);
        keyBar.setVisibility((hardKeyboard || sessionEnded) ? View.GONE : View.VISIBLE);
        int auxVis = hardKeyboard ? View.GONE : View.VISIBLE;
        if (btnKeyboard != null) {
            btnKeyboard.setVisibility(auxVis);
        }
        if (btnFullscreen != null) {
            btnFullscreen.setVisibility(auxVis);
        }
        setKeyRowCompact(tight);
        if (kbdVisible && (hardKeyboard || sessionEnded)) {
            setSoftKbd(false);
        }
        applyKbdMetrics(landscape);
    }

    /** 横屏键盘弹出时把键条压矮一档（40dp→28dp），每像素都要还给终端。 */
    private void setKeyRowCompact(boolean compact) {
        int h = dp(compact ? 28 : 40);
        for (int i = 0; i < keyRow.getChildCount(); i++) {
            View c = keyRow.getChildAt(i);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                c.setLayoutParams(lp);
            }
        }
        keyBar.setPadding(dp(2), dp(compact ? 1 : 3), dp(2), dp(compact ? 1 : 2));
    }

    private void refreshInputMode() {
        boolean hw = detectHardKeyboard();
        if (hw != hardKeyboard) {
            hardKeyboard = hw;
            if (hw && uiReady) {
                closeKeyPanel();
                toast("已连接物理键盘：隐藏屏幕键条，Esc/Tab/方向键直接用键盘");
            }
        }
        // 会话结束时键条本来就该让位给结束条，键盘插拔也不能把它顶回来
        updateChrome();
        updateStatus(null);
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
                                // 键盘和面板同时开着，终端只剩两行
                                closeKeyPanel();
                                // 对话框里的输入框把系统键盘叫出来了，自绘键盘让位
                                setSoftKbd(false);
                            }
                        }
                    }
                });
    }

    /* -------------------- 菜单 -------------------- */

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "重启当前会话");
        menu.getMenu().add(0, 2, 1, "复制全部输出");
        menu.getMenu().add(0, 18, 2, "复制当前屏");
        menu.getMenu().add(0, 3, 3, "粘贴");
        menu.getMenu().add(0, 4, 4, "清屏");
        menu.getMenu().add(0, 5, 4, "字号 +");
        menu.getMenu().add(0, 6, 5, "字号 -");
        menu.getMenu().add(0, 7, 6, prefs.getBoolean("root", false) ? "Root Shell：开" : "Root Shell：关");
        menu.getMenu().add(0, 8, 7, "容器运行时：" + runtimeLabel());
        menu.getMenu().add(0, 16, 8, "容器管理控制台（docker 命令）…");
        menu.getMenu().add(0, 14, 8, "容器管理（"
                + Containers.items(this).size() + " 个容器 / "
                + Containers.images(this).size() + " 个镜像）");
        menu.getMenu().add(0, 15, 9, "导入镜像（docker save/export 的 tar）…");
        menu.getMenu().add(0, 9, 9, "重建当前容器（清空其 rootfs）");
        menu.getMenu().add(0, 12, 10, "绑定目录（" + Volumes.list(this).size() + "）");
        menu.getMenu().add(0, 13, 11, "存储权限：" + (hasAllFilesAccess() ? "已授权" : "未授权（点此开启）"));
        menu.getMenu().add(0, 11, 12, fullscreen ? "退出全屏" : "全屏模式");
        menu.getMenu().add(0, 10, 13, "关于");
        // 返回键只退到后台（会话不丢），真正结束会话放在这里
        menu.getMenu().add(0, 17, 14, "退出应用（结束会话）");

        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                return onMenuClick(item.getItemId());
            }
        });
        menu.show();
    }

    private String runtimeLabel() {
        return "docker".equals(prefs.getString("runtime", "proot")) ? "Docker（需 root）" : "PRoot";
    }

    private boolean onMenuClick(int id) {
        switch (id) {
            case 1:
                switchMode(mode, true);
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
            case 5:
                changeFontSize(1);
                return true;
            case 6:
                changeFontSize(-1);
                return true;
            case 7:
                toggleRoot();
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
                showVolumesDialog();
                return true;
            case 13:
                requestStorageAccess();
                return true;
            case 14:
                showContainersDialog();
                return true;
            case 15:
                pickArchiveForImport();
                return true;
            case 16:
                startActivity(new android.content.Intent(this, ConsoleActivity.class));
                return true;
            case 17:
                confirmQuit();
                return true;
            default:
                return false;
        }
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
        String text = session.getEmulator().getText();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
            toast("已复制 " + text.length() + " 个字符");
        }
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
        String text = session.getEmulator().getScreenText();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("terminal", text));
            toast("已复制当前屏 " + text.length() + " 个字符");
        }
    }

    private void paste() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip() == null) {
            return;
        }
        ClipData.Item item = cm.getPrimaryClip().getItemAt(0);
        CharSequence text = item == null ? null : item.coerceToText(this);
        if (text != null) {
            terminal.pasteText(text.toString());
        }
    }

    private void changeFontSize(int delta) {
        int size = terminal.getTextSizeSp() + delta;
        if (size < 6) {
            size = 6;
        }
        if (size > 28) {
            size = 28;
        }
        terminal.setTextSizeSp(size);
        prefs.edit().putInt("font_size", size).apply();
        updateStatus(null);
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
        if (btnFullscreen != null) {
            btnFullscreen.setText(on ? K_FULLSCREEN_ON : K_FULLSCREEN);
            btnFullscreen.setTextColor(on ? COLOR_ACCENT : COLOR_TEXT);
            btnFullscreen.setContentDescription(on ? "退出全屏" : "全屏");
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

    /* -------------------- 绑定目录（volumes） -------------------- */

    private void showVolumesDialog() {
        final java.util.List<String[]> items = Volumes.list(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("容器绑定目录");

        if (items.isEmpty()) {
            builder.setMessage("还没有绑定。\n\n"
                    + "绑定后，容器内的路径直接指向宿主真实目录——写进容器的文件就在手机上，"
                    + "不是复制一份。\n\n"
                    + "常用：宿主 /sdcard/Download → 容器 /mnt/download");
        } else {
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                String st = Volumes.state(items.get(i));
                labels[i] = (st == null ? "✓ " : "⚠ ")
                        + Volumes.label(items.get(i))
                        + (st == null ? "" : "  · " + st);
            }
            builder.setItems(labels, new android.content.DialogInterface.OnClickListener() {
                @Override
                public void onClick(android.content.DialogInterface dialog, int which) {
                    confirmRemoveVolume(items, which);
                }
            });
        }

        builder.setPositiveButton("添加绑定", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                showAddVolumeDialog();
            }
        });
        builder.setNeutralButton("全部清除", new android.content.DialogInterface.OnClickListener() {
            @Override
            public void onClick(android.content.DialogInterface dialog, int which) {
                Volumes.clear(MainActivity.this);
                toast("已清除全部绑定");
            }
        });
        builder.setNegativeButton("关闭", null);
        builder.show();
    }

    private void confirmRemoveVolume(final java.util.List<String[]> items, final int index) {
        new AlertDialog.Builder(this)
                .setTitle("删除绑定")
                .setMessage(Volumes.label(items.get(index)) + "\n\n只删除配置，不会动宿主目录里的文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        Volumes.removeAt(MainActivity.this, index);
                        toast("已删除（重启容器会话后生效）");
                    }
                })
                .show();
    }

    private void showAddVolumeDialog() {
        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView hostLabel = new TextView(this);
        hostLabel.setText("宿主路径（手机上的真实目录）");
        hostLabel.setTextSize(12);
        hostLabel.setTextColor(0xFF9AA0AE);

        final EditText host = new EditText(this);
        host.setHint("/sdcard/Download");
        host.setText("/sdcard/Download");
        host.setSingleLine(true);

        TextView guestLabel = new TextView(this);
        guestLabel.setText("容器内路径（留空表示同名挂载）");
        guestLabel.setTextSize(12);
        guestLabel.setTextColor(0xFF9AA0AE);
        guestLabel.setPadding(0, (int) (10 * density), 0, 0);

        final EditText guest = new EditText(this);
        guest.setHint("/mnt/download");
        guest.setText("/mnt/download");
        guest.setSingleLine(true);

        box.addView(hostLabel);
        box.addView(host);
        box.addView(guestLabel);
        box.addView(guest);

        new AlertDialog.Builder(this)
                .setTitle("添加绑定目录")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String hostPath = host.getText().toString().trim();
                        String guestPath = guest.getText().toString().trim();
                        if (hostPath.isEmpty()) {
                            toast("宿主路径不能为空");
                            return;
                        }
                        File hostFile = new File(hostPath);
                        if (!hostFile.exists()) {
                            toast("宿主路径不存在：" + hostPath);
                            return;
                        }
                        if (!hostFile.isDirectory()) {
                            toast("宿主路径不是目录：" + hostPath);
                            return;
                        }
                        if (!guestPath.isEmpty() && !guestPath.startsWith("/")) {
                            toast("容器内路径要以 / 开头");
                            return;
                        }
                        if ((hostPath.startsWith("/sdcard") || hostPath.startsWith("/storage"))
                                && !hasAllFilesAccess()) {
                            toast("提示：未授予「所有文件访问」，容器里可能看到空目录（菜单 → 存储权限）");
                        } else if (!hostFile.canRead()) {
                            toast("提示：应用无权读取该目录，容器里会报 Permission denied");
                        }
                        Volumes.add(MainActivity.this, hostPath, guestPath);
                        if (MODE_CONTAINER.equals(mode)) {
                            toast("已添加，正在重启容器会话");
                            switchMode(MODE_CONTAINER, true);
                        } else {
                            toast("已添加，切到容器模式后生效");
                        }
                    }
                })
                .show();
    }

    /* -------------------- 容器管理 -------------------- */

    private void showContainersDialog() {
        final java.util.List<Containers.Item> containers = Containers.items(this);
        final java.util.List<Containers.Image> images = Containers.images(this);
        final String active = Containers.activeName(this);

        final java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<int[]> meta = new java.util.ArrayList<>();

        labels.add("⌨ 管理控制台（docker 命令）");
        meta.add(new int[]{5, -1});
        labels.add("＋ 新建容器（从镜像）");
        meta.add(new int[]{2, -1});
        for (int i = 0; i < containers.size(); i++) {
            Containers.Item item = containers.get(i);
            labels.add((item.name.equals(active) ? "★ " : "   ")
                    + item.name + "   " + item.image
                    + "   [" + (item.isRunning() ? "运行中" : "已停止") + "]");
            meta.add(new int[]{0, i});
        }
        for (int i = 0; i < images.size(); i++) {
            Containers.Image img = images.get(i);
            labels.add("镜像   " + img.name + "   " + Containers.humanSize(img.size()));
            meta.add(new int[]{1, i});
        }
        labels.add("⇣ 拉取 Alpine 镜像…");
        meta.add(new int[]{3, -1});
        labels.add("⇣ 导入 tar 镜像（docker save / export）…");
        meta.add(new int[]{4, -1});

        new AlertDialog.Builder(this)
                .setTitle("容器与镜像")
                .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        int[] m = meta.get(which);
                        switch (m[0]) {
                            case 0:
                                showContainerActions(containers.get(m[1]));
                                break;
                            case 1:
                                showImageActions(images.get(m[1]));
                                break;
                            case 2:
                                chooseImageForNewContainer();
                                break;
                            case 3:
                                showPullDialog();
                                break;
                            case 4:
                                pickArchiveForImport();
                                break;
                            case 5:
                                startActivity(new android.content.Intent(MainActivity.this,
                                        ConsoleActivity.class));
                                break;
                            default:
                                break;
                        }
                    }
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showContainerActions(final Containers.Item item) {
        final java.util.List<String> list = new java.util.ArrayList<>();
        list.add(item.isRunning() ? "切换到此容器" : "启动并切换");
        if (item.isRunning()) {
            list.add("停止");
        }
        list.add("删除");
        final String[] actions = list.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        String action = actions[which];
                        if ("删除".equals(action)) {
                            confirmRemoveContainer(item);
                        } else if ("停止".equals(action)) {
                            Containers.Item it = Containers.find(MainActivity.this, item.name);
                            if (it != null && it.isRunning()) {
                                TermSession existing = sessions.remove(MODE_CONTAINER);
                                if (existing != null) {
                                    existing.stop();
                                }
                                Containers.setRunning(item.name, false);
                                updateStatus("已停止 " + item.name);
                            }
                        } else {
                            mode = MODE_CONTAINER;
                            prefs.edit().putString("mode", MODE_CONTAINER).apply();
                            fallbackTried = false;
                            Containers.setActive(MainActivity.this, item.name);
                            updateModeButtons();
                            startContainer(item.name, true);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveContainer(final Containers.Item item) {
        new AlertDialog.Builder(this)
                .setTitle("删除容器 " + item.name)
                .setMessage("会删除这个容器的 rootfs（容器里装的软件、写的文件全部消失）。"
                        + "\n\n镜像和宿主绑定目录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        TermSession existing = sessions.remove(MODE_CONTAINER);
                        if (existing != null) {
                            existing.stop();
                        }
                        boolean wasActive = item.name.equals(Containers.activeName(MainActivity.this));
                        if (!Containers.remove(MainActivity.this, item.name)) {
                            toast("删除失败：" + item.name + " 的目录还在被占用");
                            return;
                        }
                        toast("已删除 " + item.name);
                        if (wasActive) {
                            // 旧的活动容器没了，落到剩下的第一个（全删完则重建默认容器）
                            java.util.List<Containers.Item> rest = Containers.items(MainActivity.this);
                            String next = rest.isEmpty() ? Containers.DEFAULT_CONTAINER : rest.get(0).name;
                            Containers.setActive(MainActivity.this, next);
                            startContainer(next, true);
                        } else {
                            updateStatus(null);
                        }
                    }
                })
                .show();
    }

    private void chooseImageForNewContainer() {
        final java.util.List<Containers.Image> images = Containers.images(this);
        if (images.isEmpty()) {
            toast("还没有镜像，先拉取一个");
            return;
        }
        if (images.size() == 1) {
            askContainerName(images.get(0));
            return;
        }
        String[] names = new String[images.size()];
        for (int i = 0; i < images.size(); i++) {
            names[i] = images.get(i).name + "   " + Containers.humanSize(images.get(i).size());
        }
        new AlertDialog.Builder(this)
                .setTitle("选择镜像")
                .setItems(names, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        askContainerName(images.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askContainerName(final Containers.Image image) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("容器名，如 web");
        input.setText("c" + (Containers.items(this).size() + 1));
        new AlertDialog.Builder(this)
                .setTitle("从 " + image.name + " 新建容器")
                .setMessage("会复制一份可写 rootfs（" + Containers.humanSize(image.size()) + "），与镜像互不影响。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        createContainerAsync(input.getText().toString().trim(), image.name);
                    }
                })
                .show();
    }

    private void createContainerAsync(final String name, final String image) {
        showOverlay("创建容器 " + name + " …");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Containers.Item created = Containers.create(getApplicationContext(), name, image);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            Containers.setActive(MainActivity.this, created.name);
                            mode = MODE_CONTAINER;
                            prefs.edit().putString("mode", MODE_CONTAINER).apply();
                            updateModeButtons();
                            startContainer(created.name, true);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            showError("创建容器失败", describe(e));
                        }
                    });
                }
            }
        }, "create-container").start();
    }

    private void showImageActions(final Containers.Image image) {
        String[] actions = {"基于它新建容器", "删除镜像"};
        new AlertDialog.Builder(this)
                .setTitle(image.name)
                .setItems(actions, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        if (which == 0) {
                            askContainerName(image);
                        } else {
                            confirmRemoveImage(image);
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmRemoveImage(final Containers.Image image) {
        new AlertDialog.Builder(this)
                .setTitle("删除镜像 " + image.name)
                .setMessage("只有当没有容器基于它时才能删除。已存在的容器自带 rootfs 副本，不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        try {
                            Containers.removeImage(MainActivity.this, image.name);
                            toast("已删除镜像 " + image.name);
                        } catch (Exception e) {
                            toast(describe(e));
                        }
                    }
                })
                .show();
    }

    private void showPullDialog() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Alpine 版本，如 3.20");
        input.setText("3.21");
        new AlertDialog.Builder(this)
                .setTitle("拉取 Alpine 镜像")
                .setMessage("从 dl-cdn.alpinelinux.org 下载 minirootfs（约 3MB），作为新镜像。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("拉取", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        pullImageAsync(input.getText().toString().trim());
                    }
                })
                .show();
    }

    private void pullImageAsync(final String tag) {
        showOverlay("拉取 alpine:" + tag + " …");
        final Context appContext = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String ref = Containers.pull(appContext, tag, new ContainerRuntime.Progress() {
                        @Override
                        public void onProgress(final String message) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    overlayText.setText(message);
                                }
                            });
                        }
                    });
                    final String pulled = ref;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            toast("已拉取 " + pulled + "（菜单 → 容器管理可基于它新建容器）");
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            showError("拉取失败", describe(e));
                        }
                    });
                }
            }
        }, "pull-image").start();
    }

    /* -------------------- 从 tar 导入镜像 -------------------- */

    /**
     * 选一个 tar 让系统文件选择器打开（SAF，不需要存储权限）。
     * 支持 docker export 的扁平 rootfs，也支持 docker save 的层叠包（含 OCI 布局）。
     */
    private void pickArchiveForImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            startActivityForResult(intent, REQ_IMPORT_TAR);
        } catch (Exception e) {
            toast("没有可用的文件选择器：" + describe(e));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT_TAR || resultCode != RESULT_OK || data == null) {
            return;
        }
        final android.net.Uri uri = data.getData();
        if (uri == null) {
            return;
        }
        showOverlay("读取归档…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                File copied = null;
                try {
                    copied = copyUriToCache(uri);
                    final File ready = copied;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            askImportName(ready);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            showError("读取文件失败", describe(e));
                        }
                    });
                }
            }
        }, "copy-archive").start();
    }

    private File copyUriToCache(android.net.Uri uri) throws Exception {
        String name = "import-source.tar";
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String display = c.getString(idx);
                    if (display != null && !display.isEmpty()) {
                        name = display.replaceAll("[^A-Za-z0-9._-]", "_");
                    }
                }
            }
        } catch (Exception ignored) {
            // 拿不到名字就用默认的
        }
        File dest = new File(getCacheDir(), name);
        try (java.io.InputStream in = getContentResolver().openInputStream(uri);
             java.io.OutputStream out = new java.io.FileOutputStream(dest)) {
            if (in == null) {
                throw new java.io.IOException("无法打开该文件");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return dest;
    }

    /** 镜像名留空时用归档自带的标签（docker save 的 RepoTags / OCI 的 ref.name）。 */
    private void askImportName(final File archive) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("留空则用归档里的名字，如 alpine:3.20");
        new AlertDialog.Builder(this)
                .setTitle("导入镜像")
                .setMessage(archive.getName() + "（" + Containers.humanSize(archive.length()) + "）\n\n"
                        + "支持 docker export 的扁平 rootfs tar，也支持 docker save 的层叠 tar（含 .wh. 白化）。\n"
                        + "导入的镜像架构需与设备一致：" + ContainerBootstrap.abi())
                .setView(input)
                .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        //noinspection ResultOfMethodCallIgnored
                        archive.delete();
                    }
                })
                .setPositiveButton("导入", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        importArchiveAsync(archive, input.getText().toString().trim());
                    }
                })
                .show();
    }

    private void importArchiveAsync(final File archive, final String refHint) {
        showOverlay("导入镜像…");
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final ImageImporter.Result result = ImageImporter.importArchive(app, archive, refHint,
                            new ContainerRuntime.Progress() {
                                @Override
                                public void onProgress(final String message) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            overlayText.setText(message);
                                        }
                                    });
                                }
                            });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            //noinspection ResultOfMethodCallIgnored
                            archive.delete();
                            String message = "已导入 " + result.ref + "\n"
                                    + "格式：" + result.format + "\n"
                                    + "层数：" + result.layers + "\n\n"
                                    + "下一步：菜单 → 容器管理 → 新建容器；"
                                    + "或在容器终端里 docker run --name c1 " + result.ref;
                            if (result.note != null) {
                                message = result.note + "\n\n" + message;
                            }
                            new AlertDialog.Builder(MainActivity.this)
                                    .setTitle("导入完成")
                                    .setMessage(message)
                                    .setPositiveButton("好的", null)
                                    .show();
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hideOverlay();
                            //noinspection ResultOfMethodCallIgnored
                            archive.delete();
                            showError("导入失败", describe(e));
                        }
                    });
                }
            }
        }, "import-image").start();
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
    }

    private void toggleRuntime() {
        boolean docker = "docker".equals(prefs.getString("runtime", "proot"));
        prefs.edit().putString("runtime", docker ? "proot" : "docker").apply();
        toast("容器运行时：" + runtimeLabel() + "（切换后请重启容器会话）");
    }

    private void confirmWipe() {
        final String name = Containers.activeName(this);
        new AlertDialog.Builder(this)
                .setTitle("重建容器 " + name)
                .setMessage("会用镜像重新物化一份干净的 rootfs。容器里装过的软件会全部丢失；"
                        + "镜像和宿主绑定目录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确定重建", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        TermSession existing = sessions.remove(MODE_CONTAINER);
                        if (existing != null) {
                            existing.stop();
                        }
                        Containers.setRunning(name, false);
                        showOverlay("重建容器 " + name + " …");
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Containers.recreate(getApplicationContext(), name);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            hideOverlay();
                                            startContainer(name, true);
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
                        }, "recreate-container").start();
                    }
                })
                .show();
    }

    private void showAbout() {
        String message = "双态终端 DualTerm 3.5\n\n"
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
                .setPositiveButton("好的", null)
                .show();
    }

    /* -------------------- 生命周期 -------------------- */

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) {
            return;
        }
        if (keyPanelOpen) {
            closeKeyPanel();
            return;
        }
        if (kbdVisible) {
            setSoftKbd(false);
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
        refreshInputMode();
        updateChrome();
        updateStatus(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 后台期间插拔的键盘收不到回调，回到前台补一次
        refreshInputMode();
    }

    @Override
    protected void onDestroy() {
        if (inputManager != null && inputDeviceListener != null) {
            inputManager.unregisterInputDeviceListener(inputDeviceListener);
            inputDeviceListener = null;
        }
        if (isFinishing()) {
            for (TermSession session : sessions.values()) {
                session.stop();
            }
            sessions.clear();
            Containers.clearRunning();
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
