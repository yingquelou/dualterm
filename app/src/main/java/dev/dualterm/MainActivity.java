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
    private Button btnMenu;
    private View keyBar;
    private LinearLayout keyRow;
    private LinearLayout keyPanel;
    private View overlay;
    private TextView overlayText;

    private Button ctrlKey;
    private Button altKey;
    private boolean keyPanelOpen;

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
        btnKeyboard = findViewById(R.id.btn_keyboard);
        btnMenu = findViewById(R.id.btn_menu);
        keyBar = findViewById(R.id.key_bar);
        keyRow = findViewById(R.id.key_row);
        keyPanel = findViewById(R.id.key_panel);
        overlay = findViewById(R.id.overlay);
        overlayText = findViewById(R.id.overlay_text);

        terminal.setGridListener(this);
        terminal.setModifierWatcher(this);
        // 全屏时顶栏被收起，长按终端打开菜单（也是唯一的退出入口）
        terminal.setLongPressListener(new TerminalView.LongPressListener() {
            @Override
            public void onLongPress() {
                showMenu(terminal);
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
        installInputDeviceWatcher();
        installImeWatcher();

        installInsetsHandler();
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
        btnKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (imeVisible) {
                    terminal.hideKeyboard();
                } else {
                    terminal.showKeyboard();
                }
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
        maybeKeyTest(it);
        logWindowFlags();
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
        session.resize(terminal.getGridRows(), terminal.getGridCols());
        updateStatus(null);
        terminal.postInvalidateOnAnimation();
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
                    updateStatus("进程已退出，code=" + code + "（菜单可重启）");
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
            sb.append(session.getSpec().title);
            sb.append(" · ").append(terminal.getGridCols()).append('x').append(terminal.getGridRows());
            if (session.getPid() > 0) {
                sb.append(" · ").append(session.getPid());
            }
            if (!session.isRunning()) {
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
        buildKeyPanel();
    }

    /** 更多键：HOME/END/PGUP/PGDN/DEL/INS、F1-F6，以及软键盘上最难打的那些符号。 */
    private static final String[][] PANEL_KEYS = {
            {"HOME", "\u001b[H"}, {"END", "\u001b[F"}, {"PGUP", "\u001b[5~"},
            {"PGDN", "\u001b[6~"}, {"DEL", "\u001b[3~"}, {"INS", "\u001b[2~"},
            {"F1", "\u001bOP"}, {"F2", "\u001bOQ"}, {"F3", "\u001bOR"},
            {"F4", "\u001bOS"}, {"F5", "\u001b[15~"}, {"F6", "\u001b[17~"},
            {"/", "/"}, {"-", "-"}, {"|", "|"}, {"~", "~"}, {"\\", "\\"}, {"*", "*"},
            {"&", "&"}, {"$", "$"}, {">", ">"}, {"<", "<"}, {"\"", "\""}, {"'", "'"},
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
                    terminal.sendRaw(sequence);
                    closeKeyPanel();
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
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        toggleKeyPanel();
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

    private void toggleKeyPanel() {
        if (keyPanelOpen) {
            closeKeyPanel();
        } else {
            keyPanelOpen = true;
            keyPanel.setVisibility(View.VISIBLE);
        }
    }

    private void closeKeyPanel() {
        if (!keyPanelOpen) {
            return;
        }
        keyPanelOpen = false;
        keyPanel.setVisibility(View.GONE);
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

    private void refreshInputMode() {
        boolean hw = detectHardKeyboard();
        if (hw != hardKeyboard) {
            hardKeyboard = hw;
            if (hw && uiReady) {
                closeKeyPanel();
                toast("已连接物理键盘：隐藏屏幕键条，Esc/Tab/方向键直接用键盘");
            }
        }
        keyBar.setVisibility(hardKeyboard ? View.GONE : View.VISIBLE);
        btnKeyboard.setVisibility(hardKeyboard ? View.GONE : View.VISIBLE);
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
                            if (shown) {
                                // 键盘和面板同时开着，终端只剩两行
                                closeKeyPanel();
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
        menu.getMenu().add(0, 3, 2, "粘贴");
        menu.getMenu().add(0, 4, 3, "清屏");
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
                    toast("已进入全屏：长按终端可打开菜单");
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
     * 功能键行保留（至少留一个能敲 Ctrl-C 的入口）；退出靠长按终端 → 菜单。
     */
    private void applyFullscreen(boolean on) {
        fullscreen = on;
        prefs.edit().putBoolean("fullscreen", on).apply();

        if (topBar != null) {
            topBar.setVisibility(on ? View.GONE : View.VISIBLE);
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
                window.setDecorFitsSystemWindows(true);
                WindowInsetsController c = window.getInsetsController();
                if (c != null) {
                    c.show(WindowInsets.Type.systemBars());
                }
            } else {
                decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
            applyInsetsPadding(0, 0);
        }
        updateStatus(null);
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
            if (fullscreen) {
                android.graphics.Insets cut = insets.getInsets(WindowInsets.Type.displayCutout());
                android.graphics.Insets gest = insets.getInsets(WindowInsets.Type.systemGestures());
                applyInsetsPadding(cut.top, Math.max(cut.bottom, gest.bottom));
            }
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
        if (imeVisible) {
            terminal.hideKeyboard();
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
