package dev.dualterm.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;

import dev.dualterm.term.TerminalView;

/**
 * 自绘辅助键盘：只放系统输入法不提供、或要翻好几页才找得到的键。
 *
 * 输入方案是「系统输入法（主键盘）+ 自绘键盘（特殊键）」：
 * 字母/数字/常用标点在系统键盘上打更顺手（还有中文、剪贴板、语音），
 * 这里只补终端才需要的键——
 * <ul>
 *   <li>控制字符 ^C ^D ^Z ^\ ^A ^E ^U ^K ^W ^L / ^R ^O ^S ^V ^Y ^F ^B ^P ^N ^T：
 *       系统键盘没有；键条上虽有 CTRL 锁定键，但「锁一下再打字母」两步，
 *       常用控制键直发更省事</li>
 *   <li>shell 符号 | &amp; &lt; &gt; ( ) [ ] { } $ ` " ' * ? ! ~ =：
 *       输入法符号页里翻找成本高，终端里又是高频</li>
 *   <li>粘贴：系统输入法的粘贴在终端里不可靠（走的是编辑框的剪贴板协议）</li>
 * </ul>
 *
 * 不在这里重复的：ESC / TAB / ^C / 方向键在常驻键条（一步可达）；
 * F1–F12 / HOME / END / PGUP / PGDN / DEL / INS 在「⋯」面板；
 * 空格 / 退格 / 回车 / 数字 / 字母 / 常用标点交给系统键盘。
 *
 * 行高与主界面同档（横 27dp / 竖 34dp），随 updateChrome / 转屏刷新。
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

    /** 粘贴键：终端里粘贴是高频动作（贴命令、token、diff），藏在 ⋮ 菜单里要两步。 */
    private static final String K_PASTE = "粘贴";

    /**
     * 四行：控制字符两行、shell 符号两行（最后一个位置放粘贴）。
     * 键数按 10 个一排，与系统键盘的节奏一致，手指不用重新建立肌肉记忆。
     */
    private static final String[][] ROWS = {
            {"^C", "^D", "^Z", "^\\", "^A", "^E", "^U", "^K", "^W", "^L"},
            {"^R", "^O", "^S", "^V", "^Y", "^F", "^B", "^P", "^N", "^T"},
            {"|", "&", "<", ">", "(", ")", "[", "]", "{", "}"},
            {"$", "`", "\"", "'", "*", "?", "!", "~", "=", K_PASTE},
    };

    private final Host host;

    private ViewGroup kbd;
    /** 所有按键行，横竖屏切换时统一改行高。 */
    private final List<LinearLayout> kbdRows = new ArrayList<>();
    private boolean kbdVisible;

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

    /** 建自绘键盘：控制键两行 + 符号两行。 */
    public void build(ViewGroup container, boolean landscape) {
        if (container == null) {
            return;
        }
        kbd = container;
        LinearLayout page = new LinearLayout(host.terminal().getContext());
        page.setOrientation(LinearLayout.VERTICAL);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (String[] spec : ROWS) {
            LinearLayout row = new LinearLayout(host.terminal().getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            // 同行禁止基线对齐：混排 11sp/14sp 时默认对齐会把小字键块下推，
            // 底部被下一行盖住（键行圆角残缺的根因）
            row.setBaselineAligned(false);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            kbdRows.add(row);
            for (final String label : spec) {
                Button b = host.makeKeyButton(label);
                b.setTextSize(K_PASTE.equals(label) ? 12 : 14);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, host.dp(34), 1f);
                lp.setMargins(host.dp(2), host.dp(2), host.dp(2), 0);
                b.setLayoutParams(lp);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        onKey(label);
                    }
                });
                row.addView(b);
            }
            page.addView(row);
        }
        kbd.addView(page);
        applyMetrics(landscape);
    }

    /**
     * 横竖屏、键盘弹收时统一调行高：横屏一行 27dp 已经够点，省下的全给终端。
     *
     * 键盘自身高度写死（行数 × 行高），不用 wrap_content：三层嵌套的
     * wrap_content 在首次布局时会被测成「差一行」，最后一行整行被裁掉
     * （实测 ⏎ 键边界是 [0,0][0,0]，点了没反应）。
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
            int kbdH = ROWS.length * (h + margin);
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
        kbd.setVisibility(show ? View.VISIBLE : View.GONE);
        host.onSoftKbdVisibilityChanged(show);
    }

    /** 一个按键的语义：^X 直发控制字符，其余按字符直发（不叠 CTRL/ALT 锁）。 */
    private void onKey(String label) {
        TerminalView terminal = host.terminal();
        if (K_PASTE.equals(label)) {
            host.paste();
            return;
        }
        if (label.length() == 2 && label.charAt(0) == '^') {
            // ^A..^Z → 0x01..0x1a；^\ 也走同一公式（0x1c）
            char c = label.charAt(1);
            if (c != '\\' && !Character.isLetter(c)) {
                return;
            }
            terminal.sendRaw(String.valueOf((char) (Character.toUpperCase(c) & 0x1f)));
            return;
        }
        if (label.length() == 1) {
            terminal.sendRaw(label);
        }
    }
}
