package dev.dualterm.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import dev.dualterm.R;
import dev.dualterm.term.TerminalView;

/**
 * 快速访问栏：辅助键盘全部收起时唯一留下的那一行键，也是一屏之内一步可达的键。
 *
 * <p>键位、样式、尺寸、颜色全部收在本类；终端输入、辅助键盘、系统输入法、
 * 全屏与菜单都通过 {@link Host} 交回宿主（MainActivity）。
 *
 * <h2>可自定义（2026-11-28 起）</h2>
 * 这一行是「哪些动作要在一屏之内够得着」的选择题，所以做成可配：
 * <ul>
 *   <li><b>只选键，不做键</b>：栏里放什么由键目录（{@link #CATALOG}）决定，
 *       键的行为是固定的；要「造一个键」走辅助键盘的自定义键（步骤序列），
 *       两套机制不重叠，也不互相污染数据；</li>
 *   <li><b>顺序即左右</b>：越靠左越顺手，编辑器里直接上下移动；</li>
 *   <li><b>最多 9 个</b>：再多每个键就窄到写不下 CTRL/TAB 这种四字母；
 *       这一上限是「键宽」的约束，不是功能主张；</li>
 *   <li><b>⌨ 常驻</b>：它不占自选名额，也不能删——没有它就没有辅助键盘入口；</li>
 *   <li><b>⛶ 可关</b>：全屏还能从菜单进、按返回键退，不必占一个位置。</li>
 * </ul>
 * 配置存成 {@code "CTRL,ALT,...|1"}（竖线后是「是否显示全屏键」），一条
 * prefs 字符串；解析不出来的 id 直接丢掉，整条空掉就退回默认——prefs 被
 * 改坏不该让键条消失。
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

        /** 快速访问栏配置；null = 从未自定义，用默认键位。 */
        String storedQuickBar();

        /** 保存配置；传 null 表示清除（恢复默认）。 */
        void storeQuickBar(String value);
    }

    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_ACCENT = 0xFF7CE38B;
    private static final int COLOR_DIM = 0xFF8A90A0;

    private static final int KIND_MOD_CTRL = 0;
    private static final int KIND_MOD_ALT = 1;
    private static final int KIND_RAW = 2;
    private static final int KIND_CURSOR = 3;

    private static final String K_FULLSCREEN = "\u26f6";     // ⛶
    private static final String K_FULLSCREEN_ON = "\u2715";  // ✕

    /** 键目录里的一项：id 落盘、label 上屏、kind+arg 决定点击做什么。 */
    private static final class Spec {
        final String id;
        final String label;
        final int kind;
        final String arg;

        Spec(String id, String label, int kind, String arg) {
            this.id = id;
            this.label = label;
            this.kind = kind;
            this.arg = arg;
        }
    }

    /**
     * 可放进栏里的键（数组顺序 = 编辑器「可添加」的顺序）。
     * 挑的是「系统键盘没有、终端里高频」的那批：控制字符与方向键。
     */
    private static final Spec[] CATALOG = {
            new Spec("CTRL", "CTRL", KIND_MOD_CTRL, null),
            new Spec("ALT", "ALT", KIND_MOD_ALT, null),
            new Spec("ESC", "ESC", KIND_RAW, "\u001b"),
            new Spec("TAB", "TAB", KIND_RAW, "\t"),
            new Spec("^C", "^C", KIND_RAW, "\u0003"),
            new Spec("^D", "^D", KIND_RAW, "\u0004"),
            new Spec("^L", "^L", KIND_RAW, "\u000c"),
            new Spec("^R", "^R", KIND_RAW, "\u0012"),
            new Spec("^U", "^U", KIND_RAW, "\u0015"),
            new Spec("^Z", "^Z", KIND_RAW, "\u001a"),
            new Spec("LEFT", "\u2190", KIND_CURSOR, "D"),
            new Spec("UP", "\u2191", KIND_CURSOR, "A"),
            new Spec("DOWN", "\u2193", KIND_CURSOR, "B"),
            new Spec("RIGHT", "\u2192", KIND_CURSOR, "C"),
    };

    /** 默认键位＝改造之前那一行，原样保留观感。 */
    private static final String DEFAULT_KEYS = "CTRL,ALT,ESC,TAB,^C,LEFT,UP,DOWN,RIGHT";

    /** 自选键上限：再多每格就窄到写不下 4 个字母。 */
    private static final int MAX_KEYS = 9;

    private final View container;
    private final LinearLayout row;
    private final Host host;

    private final List<Spec> quickKeys = new ArrayList<>();
    private boolean showFullscreen = true;
    private boolean compact;
    private boolean buttonsVisible = true;

    private Button ctrlKey;
    private Button altKey;
    private Button btnKeyboard;
    private Button btnFullscreen;

    /**
     * @param container 键条外层（负责内边距；整条隐藏仍由宿主控制）
     * @param row       按键所在的一行（快速访问栏）
     */
    public KeyBar(View container, LinearLayout row, Host host) {
        this.container = container;
        this.row = row;
        this.host = host;
    }

    public void build() {
        load();
        render();
    }

    /* -------------------- 配置读写 -------------------- */

    private void load() {
        quickKeys.clear();
        showFullscreen = true;

        String raw = host.storedQuickBar();
        if (raw == null || raw.trim().isEmpty()) {
            raw = DEFAULT_KEYS + "|1";
        }
        int bar = raw.indexOf('|');
        String ids = bar >= 0 ? raw.substring(0, bar) : raw;
        if (bar >= 0) {
            showFullscreen = !"0".equals(raw.substring(bar + 1).trim());
        }
        for (String id : ids.split(",")) {
            Spec spec = find(id.trim());
            if (spec != null && !quickKeys.contains(spec) && quickKeys.size() < MAX_KEYS) {
                quickKeys.add(spec);
            }
        }
        if (quickKeys.isEmpty()) {
            // prefs 被改坏 / 全部 id 都不认识：退回默认，别让键条空成一条线
            loadDefaults();
        }
    }

    private void loadDefaults() {
        quickKeys.clear();
        for (String id : DEFAULT_KEYS.split(",")) {
            Spec spec = find(id.trim());
            if (spec != null) {
                quickKeys.add(spec);
            }
        }
    }

    private void persist() {
        StringBuilder sb = new StringBuilder();
        for (Spec spec : quickKeys) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(spec.id);
        }
        sb.append('|').append(showFullscreen ? '1' : '0');
        host.storeQuickBar(sb.toString());
    }

    private static Spec find(String id) {
        for (Spec spec : CATALOG) {
            if (spec.id.equals(id)) {
                return spec;
            }
        }
        return null;
    }

    /* -------------------- 装配 -------------------- */

    /**
     * 重建整行。行高要显式给（{@link #applyHeights}），否则 wrap_content 在
     * 三层嵌套里会被测成「差一行」，最后一行整行被裁掉。
     */
    private void render() {
        row.removeAllViews();
        row.setBaselineAligned(false);
        ctrlKey = null;
        altKey = null;
        btnKeyboard = null;
        btnFullscreen = null;

        for (Spec spec : quickKeys) {
            addTextKey(spec);
        }
        addKeyboardKey();
        if (showFullscreen) {
            addFullscreenKey();
        }
        applyHeights();
    }

    private void addTextKey(Spec spec) {
        Button b = makeKeyButton(spec.label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        b.setLayoutParams(lp);
        attachKeyAction(b, spec);
        row.addView(b);
    }

    private void addKeyboardKey() {
        // 键盘开关：本质上和 CTRL/ALT 一样是「操作终端」，归到这一行，
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
        btnKeyboard.setVisibility(buttonsVisible ? View.VISIBLE : View.GONE);
    }

    private void addFullscreenKey() {
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
        btnFullscreen.setVisibility(buttonsVisible ? View.VISIBLE : View.GONE);
    }

    /** 行高随横竖屏/键盘展开调档；改高度不重建视图（重建会丢修饰键的按压态）。 */
    private void applyHeights() {
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

    /* -------------------- 快速访问栏编辑器 -------------------- */

    /**
     * 编辑对话框：上面是已选（可上移/下移/移除），下面是键目录（可添加），
     * 末尾是「全屏键」开关。每次改动即刻生效——键条就在对话框后面，
     * 边配边看比「确定后才变」直观。
     */
    public void showQuickBarEditor() {
        final Context ctx = container.getContext();
        final LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(4));
        fillEditor(box);

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box);

        final AlertDialog dlg = new AlertDialog.Builder(ctx)
                .setTitle("快速访问栏")
                .setView(scroll)
                .setNegativeButton("恢复默认", null)
                .setPositiveButton("完成", null)
                .create();
        dlg.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                // 「恢复默认」默认会关对话框，这里改成原地重置，方便接着挑
                dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(
                        new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                host.storeQuickBar(null);
                                load();
                                render();
                                fillEditor(box);
                            }
                        });
            }
        });
        dlg.show();
    }

    private void fillEditor(final LinearLayout box) {
        box.removeAllViews();
        box.addView(header(box.getContext(),
                "已选 " + quickKeys.size() + "/" + MAX_KEYS + "（顺序＝从左到右）"));

        for (int i = 0; i < quickKeys.size(); i++) {
            final int index = i;
            final LinearLayout line = editorRow(box.getContext());
            line.addView(editorLabel(box.getContext(), quickKeys.get(i).label, COLOR_TEXT));
            line.addView(stepButton(box.getContext(), "\u2191", i > 0,
                    new Runnable() {
                        @Override
                        public void run() {
                            move(index, -1);
                            fillEditor(box);
                        }
                    }));
            line.addView(stepButton(box.getContext(), "\u2193", i < quickKeys.size() - 1,
                    new Runnable() {
                        @Override
                        public void run() {
                            move(index, 1);
                            fillEditor(box);
                        }
                    }));
            line.addView(stepButton(box.getContext(), "\u2715", true,
                    new Runnable() {
                        @Override
                        public void run() {
                            quickKeys.remove(index);
                            persist();
                            render();
                            fillEditor(box);
                        }
                    }));
            box.addView(line);
        }

        box.addView(header(box.getContext(), "可添加"));
        for (final Spec spec : CATALOG) {
            if (quickKeys.contains(spec)) {
                continue;
            }
            final LinearLayout line = editorRow(box.getContext());
            line.addView(editorLabel(box.getContext(), spec.label, COLOR_DIM));
            line.addView(stepButton(box.getContext(), "\uff0b", quickKeys.size() < MAX_KEYS,
                    new Runnable() {
                        @Override
                        public void run() {
                            if (quickKeys.size() >= MAX_KEYS) {
                                return;
                            }
                            quickKeys.add(spec);
                            persist();
                            render();
                            fillEditor(box);
                        }
                    }));
            box.addView(line);
        }

        // 全屏键开关：用整行可点代替 CheckBox（深色主题下系统复选框容易被
        // 主题色染成看不见的深色，一行的文字开关反而更稳）
        LinearLayout fullLine = editorRow(box.getContext());
        final TextView fullLabel = editorLabel(box.getContext(),
                "显示全屏键 " + K_FULLSCREEN + "：" + (showFullscreen ? "开" : "关"), COLOR_TEXT);
        fullLine.addView(fullLabel);
        fullLine.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFullscreen = !showFullscreen;
                persist();
                render();
                fillEditor(box);
            }
        });
        box.addView(fullLine);

        box.addView(header(box.getContext(),
                "⌨ 常驻行尾，不占名额——没有它就没有辅助键盘入口。"));
    }

    private void move(int index, int delta) {
        int to = index + delta;
        if (to < 0 || to >= quickKeys.size()) {
            return;
        }
        Spec spec = quickKeys.remove(index);
        quickKeys.add(to, spec);
        persist();
        render();
    }

    private TextView header(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(COLOR_DIM);
        tv.setPadding(0, dp(10), 0, dp(4));
        return tv;
    }

    private LinearLayout editorRow(Context ctx) {
        LinearLayout line = new LinearLayout(ctx);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
        return line;
    }

    private TextView editorLabel(Context ctx, String text, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTextColor(color);
        tv.setSingleLine(true);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return tv;
    }

    /** 编辑器行尾的小按钮：40dp 触控区，不可用时改灰色且点击无响应。 */
    private Button stepButton(Context ctx, String text, boolean enabled, final Runnable action) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setTextColor(enabled ? COLOR_TEXT : 0xFF4A4F5C);
        b.setBackgroundResource(R.drawable.menu_item_bg);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        b.setEnabled(enabled);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(dp(2), 0, 0, 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return b;
    }

    /* -------------------- 外部状态 -------------------- */

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
        buttonsVisible = visible;
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
        this.compact = compact;
        applyHeights();
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

    /* -------------------- 键的构造与行为 -------------------- */

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

    private void attachKeyAction(Button b, Spec spec) {
        switch (spec.kind) {
            case KIND_MOD_CTRL:
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
            case KIND_MOD_ALT:
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
            case KIND_CURSOR:
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        TerminalView t = host.terminal();
                        if (t != null) {
                            t.sendCursor(spec.arg.charAt(0));
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
                            t.sendRaw(spec.arg);
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
