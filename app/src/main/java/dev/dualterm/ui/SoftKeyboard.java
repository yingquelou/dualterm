package dev.dualterm.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import dev.dualterm.R;
import dev.dualterm.term.TerminalView;

/**
 * 自绘软键盘：字母页 + 符号页，两页顶部共用数字行。
 *
 * 从 MainActivity 拆出（评审：MainActivity 2600 行是最大维护成本）。
 * 它只做「画 + 键语义」，会话/chrome 相关动作通过 Host 回调回主界面。
 */
public final class SoftKeyboard {

    /** 主界面提供的依赖：按键工厂、终端输出、粘贴与状态联动。 */
    public interface Host {
        TerminalView terminal();

        Button makeKeyButton(String label);

        int dp(int value);

        boolean hardKeyboard();

        boolean sessionEnded();

        void paste();

        /** 键盘显隐变化后：主界面刷 chrome/键行布局/状态行/⌨ 键颜色。 */
        void onSoftKbdVisibilityChanged(boolean visible);
    }

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

    private final Host host;

    private ViewGroup kbd;
    private LinearLayout kbdLetters;
    private LinearLayout kbdSymbols;
    /** 两页里所有按键行，横竖屏切换时统一改行高。 */
    private final List<LinearLayout> kbdRows = new ArrayList<>();
    /** SHIFT 键（两页各一个），按一次亮、打完一个字母自动熄灭。 */
    private final List<Button> shiftKeys = new ArrayList<>();
    private boolean kbdVisible;
    private boolean shiftLatch;

    public SoftKeyboard(Host host) {
        this.host = host;
    }

    /** 是否已构建（布局里没有 kbd 容器时为 false）。 */
    public boolean isBuilt() {
        return kbd != null;
    }

    public boolean isVisible() {
        return kbdVisible;
    }

    /**
     * 建自绘键盘：字母页 + 符号页各五行，底下那行是页切换/退格/空格/回车。
     * 上面的 CTRL/ALT/ESC/方向键工具行保持常驻，和键盘连用。
     */
    public void build(ViewGroup container, boolean landscape) {
        if (container == null) {
            return;
        }
        kbd = container;
        kbdLetters = buildPage(KBD_LETTERS);
        kbdSymbols = buildPage(KBD_SYMBOLS);
        kbdSymbols.setVisibility(View.GONE);
        kbd.addView(kbdLetters);
        kbd.addView(kbdSymbols);
        applyMetrics(landscape);
    }

    private LinearLayout buildPage(String[][] rows) {
        LinearLayout page = new LinearLayout(host.terminal().getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (String[] spec : rows) {
            LinearLayout row = new LinearLayout(host.terminal().getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            kbdRows.add(row);
            for (final String label : spec) {
                Button b = host.makeKeyButton(label);
                b.setTextSize(K_SPACE.equals(label) ? 15 : 14);
                float weight = K_SPACE.equals(label) ? 3.0f : 1f;
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, host.dp(34), weight);
                lp.setMargins(host.dp(2), host.dp(2), host.dp(2), 0);
                b.setLayoutParams(lp);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onKey(label);
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
    public void applyMetrics(boolean landscape) {
        if (kbd == null) {
            return;
        }
        int h = host.dp(landscape ? 27 : 34);
        int margin = host.dp(2);
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

    public void toggle() {
        setVisible(!kbdVisible);
    }

    /**
     * 自绘键盘显隐。终端尺寸变化会走 onSizeChanged → computeGrid → TIOCSWINSZ，
     * 所以 vim/htop 会跟着新行数重排，不需要额外通知。
     */
    public void setVisible(boolean show) {
        if (kbd == null || show == kbdVisible) {
            return;
        }
        if (show && (host.hardKeyboard() || host.sessionEnded())) {
            return;
        }
        kbdVisible = show;
        if (show) {
            // 面板不再被键盘顶掉：面板 2 行 + 键盘 4 行 ≈ 6 行，竖屏 67 行、
            // 横屏 18 行都吃得下，而「Ctrl+方向键」这类操作正需要两者同屏。
            // 每次都从字母页、无 SHIFT 开始
            shiftLatch = false;
            switchPage(false);
            applyShiftStyle();
        }
        kbd.setVisibility(show ? View.VISIBLE : View.GONE);
        host.onSoftKbdVisibilityChanged(show);
    }

    private void switchPage(boolean symbols) {
        kbdLetters.setVisibility(symbols ? View.GONE : View.VISIBLE);
        kbdSymbols.setVisibility(symbols ? View.VISIBLE : View.GONE);
    }

    private void applyShiftStyle() {
        for (Button b : shiftKeys) {
            b.setTextColor(shiftLatch ? 0xFF101014 : 0xFFE6E6E6);
            b.setBackgroundResource(shiftLatch ? R.drawable.key_bg_on : R.drawable.key_bg);
        }
    }

    /** 一个按键的语义：修饰键/翻页/编辑键走专用动作，普通字符走 PTY。 */
    private void onKey(String label) {
        if (K_SHIFT.equals(label)) {
            shiftLatch = !shiftLatch;
            applyShiftStyle();
            return;
        }
        if (K_SYM.equals(label)) {
            switchPage(true);
            return;
        }
        if (K_ABC.equals(label)) {
            switchPage(false);
            return;
        }
        TerminalView terminal = host.terminal();
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
            host.paste();
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
}
