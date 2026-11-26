package dev.dualterm.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import dev.dualterm.R;
import dev.dualterm.term.TerminalView;

/**
 * 顶部键条：一屏放得下的常用键（CTRL / ALT / ESC / TAB / ^C / 四方向）+ ⌨ / ⛶。
 *
 * <p>只负责装配与自身状态；终端输入、辅助键盘、系统输入法、全屏与菜单都通过
 * {@link Host} 交回宿主（MainActivity）。键位、样式、尺寸、颜色全部收在本类，
 * 宿主不再持有按键引用。
 *
 * <p>以前「⋯」面板独立展开是两套入口两套状态；编辑键/F 键已并入自制键盘的固定区，
 * 由 ⌨ 统一开合，这一行只留高频键。
 */
public final class KeyBar {

    /** 宿主只提供行为，不提供视图。 */
    public interface Host {
        /** 当前终端视图；会话未建立时为 null（点击不生效）。 */
        TerminalView terminal();

        /** 辅助键盘；构建完成前为 null。 */
        SoftKeyboard softKeyboard();

        /** 系统输入法当前是否可见。 */
        boolean imeVisible();

        /** 打开辅助键盘时顺带叫出系统输入法（组合设计）。 */
        void onKeyboardShown();

        /** 切换全屏；来回都由宿主决定（KeyBar 只负责发指令）。 */
        void onToggleFullscreen();

        void onMenu(View anchor);
    }

    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_ACCENT = 0xFF7CE38B;

    private static final int KEY_MOD_CTRL = 0;
    private static final int KEY_MOD_ALT = 1;
    private static final int KEY_RAW = 2;
    private static final int KEY_CURSOR = 3;

    private static final String K_FULLSCREEN = "\u26f6";     // ⛶
    private static final String K_FULLSCREEN_ON = "\u2715";  // ✕

    private final View container;
    private final LinearLayout row;
    private final Host host;

    private Button ctrlKey;
    private Button altKey;
    private Button btnKeyboard;
    private Button btnFullscreen;

    /**
     * @param container 键条外层（负责内边距；整条隐藏仍由宿主控制）
     * @param row       按键所在的一行
     */
    public KeyBar(View container, LinearLayout row, Host host) {
        this.container = container;
        this.row = row;
        this.host = host;
    }

    public void build() {
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
        };
        // 11sp 文字键与 15sp 图标键混排：按键行要顶边对齐，LinearLayout 默认按
        // 文字基线对齐会把小字键块整体下推约 2dp，底部伸进下一行被盖住。
        row.setBaselineAligned(false);
        for (Object[] spec : primary) {
            Button b = makeKeyButton((String) spec[0]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            lp.setMargins(dp(2), 0, dp(2), 0);
            b.setLayoutParams(lp);
            attachKeyAction(b, (Integer) spec[1], (String) spec[2]);
            row.addView(b);
        }

        // 键盘开关 + 全屏开关：本质上和 CTRL/ALT 一样是「操作终端」，归到这一行，
        // 既省顶栏宽度，又让全屏有可见的退出入口（不再依赖长按终端）。
        btnKeyboard = makeNarrowKey(container.getContext().getString(R.string.keyboard), 15);
        btnKeyboard.setBackgroundResource(R.drawable.key_bg_func);
        btnKeyboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SoftKeyboard kbd = host.softKeyboard();
                if (kbd == null) {
                    return;
                }
                boolean willShow = !kbd.isVisible();
                kbd.toggle();
                // 辅助键盘和系统键盘是一套组合：开辅助键时顺带把系统键盘叫出来，
                // 一步就绪（关辅助键不动系统键盘，IME 有自己的收起手势/返回键）
                if (willShow && !host.imeVisible()) {
                    host.onKeyboardShown();
                }
            }
        });
        // 长按「⌨」= 应用菜单（原「⋯」的职责随面板并入自制键盘移交过来）。
        // 全屏下顶栏收起后 ⋮ 看不见，而键行一直在；单击是高频的键盘开关，
        // 长按给低频的管理动作。
        btnKeyboard.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                host.onMenu(v);
                return true;   // 长按吃掉这次触摸，松开不再触发 click
            }
        });
        row.addView(btnKeyboard);

        btnFullscreen = makeNarrowKey(K_FULLSCREEN, 15);
        btnFullscreen.setContentDescription("全屏");
        btnFullscreen.setBackgroundResource(R.drawable.key_bg_func);
        btnFullscreen.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.onToggleFullscreen();
            }
        });
        row.addView(btnFullscreen);
    }

    /** 修饰键高亮：粘滞锁上时整键反白（触屏没有 LEDs，只能靠颜色）。 */
    public void refreshModifiers(TerminalView terminal) {
        if (terminal == null) {
            return;
        }
        applyModifierLook(ctrlKey, terminal.isCtrlDown());
        applyModifierLook(altKey, terminal.isAltDown());
    }

    /** ⌨ 的强调色：辅助键盘展开时点亮。 */
    public void setKeyboardAccent(boolean active) {
        if (btnKeyboard != null) {
            btnKeyboard.setTextColor(active ? COLOR_ACCENT : COLOR_TEXT);
        }
    }

    /** 外接键盘/会话结束时收起两个图标键（整条键条的显隐仍由宿主控制）。 */
    public void setButtonsVisible(boolean visible) {
        int v = visible ? View.VISIBLE : View.GONE;
        if (btnKeyboard != null) {
            btnKeyboard.setVisibility(v);
        }
        if (btnFullscreen != null) {
            btnFullscreen.setVisibility(v);
        }
    }

    /** 横屏键盘弹出时把键条压矮一档（40dp→28dp），每像素都要还给终端。 */
    public void setCompact(boolean compact) {
        int h = dp(compact ? 28 : 40);
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            ViewGroup.LayoutParams lp = c.getLayoutParams();
            if (lp != null && lp.height != h) {
                lp.height = h;
                c.setLayoutParams(lp);
            }
        }
        container.setPadding(dp(2), dp(compact ? 1 : 3), dp(2), dp(compact ? 1 : 2));
    }

    /** 全屏态下把 ⛶ 换成 ✕ 并点亮，提示这是退出入口。 */
    public void setFullscreenState(boolean on) {
        if (btnFullscreen == null) {
            return;
        }
        btnFullscreen.setText(on ? K_FULLSCREEN_ON : K_FULLSCREEN);
        btnFullscreen.setTextColor(on ? COLOR_ACCENT : COLOR_TEXT);
        btnFullscreen.setContentDescription(on ? "退出全屏" : "全屏");
    }

    private Button makeNarrowKey(String label, int textSize) {
        Button b = makeKeyButton(label);
        b.setTextSize(textSize);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(40));
        lp.setMargins(dp(2), 0, dp(2), 0);   // 与左侧文字键保持同样的键间距
        b.setLayoutParams(lp);
        return b;
    }

    private Button makeKeyButton(String label) {
        Button b = new Button(container.getContext());
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

    private void attachKeyAction(Button b, int kind, final String arg) {
        switch (kind) {
            case KEY_MOD_CTRL:
                ctrlKey = b;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TerminalView t = host.terminal();
                        if (t != null) {
                            t.setCtrlDown(!t.isCtrlDown());
                        }
                    }
                });
                break;
            case KEY_MOD_ALT:
                altKey = b;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TerminalView t = host.terminal();
                        if (t != null) {
                            t.setAltDown(!t.isAltDown());
                        }
                    }
                });
                break;
            case KEY_CURSOR:
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TerminalView t = host.terminal();
                        if (t != null) {
                            t.sendCursor(arg.charAt(0));
                        }
                    }
                });
                break;
            default:
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TerminalView t = host.terminal();
                        if (t != null) {
                            t.sendRaw(arg);
                        }
                    }
                });
                break;
        }
    }

    private void applyModifierLook(Button key, boolean on) {
        if (key == null) {
            return;
        }
        key.setBackgroundResource(on ? R.drawable.key_bg_on : R.drawable.key_bg);
        key.setTextColor(on ? 0xFF101014 : COLOR_TEXT);
    }

    private int dp(int value) {
        // 与 MainActivity.dp 同口径（截断而非四舍五入）：键条是像素级布局，
        // 取整方式变了整行键宽都会跟着漂移
        return (int) (value * container.getResources().getDisplayMetrics().density);
    }
}
