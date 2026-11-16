package dev.dualterm.term;

import java.util.ArrayList;

/**
 * 一个够用就好的 VT100 / xterm 终端屏幕模型。
 *
 * 支持的序列：CUU/CUD/CUF/CUB/CNL/CPL/CHA/CUP/HVP/VPA、ED/EL/ECH/DCH/ICH、
 * IL/DL/SU/SD、SGR（16 色 / 256 色 / 真彩，含 38:2::R:G:B 冒号写法）、DECSTBM 滚动区 +
 * DECOM 原点模式、DECSET 1049 备用屏、DECSET 25 光标可见、DECSET 1 应用方向键、
 * DECSET 7 自动换行、DECSET 2004 括号粘贴、制表位（HTS/TBC/CBT/CHT）、
 * DEC 字符集（ESC ( 0 画线字符 + SO/SI）、DECALN、DECSTR 软复位、
 * 鼠标上报（1000/1002/1003 + SGR/UTF-8 扩展坐标）、焦点上报、DECSCUSR、
 * 组合字符（零宽挂前格）、CJK/emoji 宽度、BCE（用当前背景色擦除）。
 * OSC 处理 10/11/12/4 颜色查询；其余 OSC（标题/超链接/剪贴板）忽略。
 *
 * 终端**必须回应**两类查询，否则对方会一直等：
 *   - DSR（`CSI 6n`）→ 光标位置报告 CPR；`CSI 5n` → 状态报告。
 *   - DA（`CSI c`）→ 设备属性；`CSI > c` → 次要设备属性。
 *   - OSC 10/11/12/4 + `?` → 前景/背景/光标色/调色板颜色。
 * gh / fzf / survey 这类 TUI 在画提示符前会先问光标位置，收不到回报就卡在第一个提示上。
 *
 * resize 与 xterm 对齐：变矮时从顶部裁（裁掉的行进 scrollback），保证光标不离开屏幕；
 * 变高时在底部补空行，光标行列不变；scrollback 不清空。
 * 变宽/变窄对主屏做双向 reflow：按行的软换行标记拆成逻辑行再按新宽度重新折行，
 * 转屏变窄时右端内容折成续行而不是截断丢失（Line.wrapped）。
 * 备用屏（vim/less 等全屏应用）不 reflow，只截断——应用收到 SIGWINCH 会整屏重画。
 *
 * 内部用「行对象数组 + 三套属性数组」存屏幕，滚动出屏的行进 scrollback。
 * 所有对外方法都加了 synchronized：PTY 读线程写、UI 线程读。
 */
public final class TerminalEmulator {

    /** 终端应答的出口：由 TermSession 接到 PTY 的写端。 */
    public interface ReplySink {
        void onReply(String reply);
    }

    public static final int ATTR_BOLD = 1;
    public static final int ATTR_DIM = 2;
    public static final int ATTR_ITALIC = 4;
    public static final int ATTR_UNDERLINE = 8;
    public static final int ATTR_REVERSE = 16;
    public static final int ATTR_INVISIBLE = 32;
    public static final int ATTR_STRIKE = 64;

    private static final int STATE_GROUND = 0;
    private static final int STATE_ESC = 1;
    private static final int STATE_CSI = 2;
    private static final int STATE_OSC = 3;
    private static final int STATE_OSC_ESC = 4;
    private static final int STATE_CHARSET = 5;
    /** 未实现的「ESC + 一个参数字节」序列：吃掉参数字节，避免它漏成可见字符。 */
    private static final int STATE_ESC_SKIP = 6;
    /** ESC # 8（DECALN 屏幕对齐测试）。 */
    private static final int STATE_ESC_HASH = 7;

    /** 一行屏幕数据。text 存码点，0 表示被宽字符占用的第二格。 */
    public static final class Line {
        public final int[] text;
        public final int[] fg;
        public final int[] bg;
        public final byte[] attr;
        /** 组合字符（零宽）挂在前一个格子上，懒分配：绝大多数行用不到。 */
        private String[] extra;
        /**
         * 软换行标记：true 表示本行内容因 autowrap 被折到下一行继续（不是显式 \n 结束）。
         * resize 重排（reflow）靠它决定相邻两行要不要拆开/拼回，xterm 同款语义。
         */
        boolean wrapped;

        Line(int cols) {
            text = new int[cols];
            fg = new int[cols];
            bg = new int[cols];
            attr = new byte[cols];
        }

        String[] extra() {
            if (extra == null) {
                extra = new String[text.length];
            }
            return extra;
        }

        /** 该格上附加的组合字符（é 的声调、泰文的元音符号…），可能为 null。 */
        public String extraAt(int index) {
            if (extra == null || index < 0 || index >= extra.length) {
                return null;
            }
            return extra[index];
        }

        void clearExtra(int index) {
            if (extra != null && index >= 0 && index < extra.length) {
                extra[index] = null;
            }
        }

        void blank(int cols, int defaultFg, int defaultBg) {
            for (int i = 0; i < cols; i++) {
                text[i] = ' ';
                fg[i] = defaultFg;
                bg[i] = defaultBg;
                attr[i] = 0;
            }
            if (extra != null) {
                java.util.Arrays.fill(extra, null);
            }
        }
    }

    /* -------------------- 颜色表 -------------------- */

    private static final int[] PALETTE = new int[256];

    static {
        int[] base = {
                0x000000, 0xCD3131, 0x0DBC79, 0xE5E510, 0x2472C8, 0xBC3FBC, 0x0FA8CD, 0xE5E5E5,
                0x666666, 0xF14C4C, 0x23D18B, 0xF5F543, 0x3B8EEA, 0xD670D6, 0x29B8DB, 0xFFFFFF
        };
        for (int i = 0; i < 16; i++) {
            PALETTE[i] = 0xFF000000 | base[i];
        }
        for (int i = 0; i < 216; i++) {
            int r = i / 36;
            int g = (i / 6) % 6;
            int b = i % 6;
            PALETTE[16 + i] = 0xFF000000 | (cube(r) << 16) | (cube(g) << 8) | cube(b);
        }
        for (int i = 0; i < 24; i++) {
            int v = 8 + i * 10;
            PALETTE[232 + i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
    }

    private static int cube(int v) {
        return v == 0 ? 0 : 55 + v * 40;
    }

    /* -------------------- 状态 -------------------- */

    private final int defaultFg;
    private final int defaultBg;
    private final int maxScrollback;

    private int cols;
    private int rows;

    private Line[] mainLines;
    private Line[] altLines;
    private Line[] lines;
    private boolean altActive;
    private final ArrayList<Line> scrollback = new ArrayList<>();

    private int cursorRow;
    private int cursorCol;
    private boolean cursorVisible = true;
    private boolean appCursorKeys;

    private int savedRow;
    private int savedCol;
    private int savedFg;
    private int savedBg;
    private int savedAttr;

    private int scrollTop;
    private int scrollBottom;

    private int curFg;
    private int curBg;
    private int curAttr;

    private int state = STATE_GROUND;
    private final StringBuilder csi = new StringBuilder();
    private final StringBuilder osc = new StringBuilder();
    private final int[] params = new int[16];
    private int paramCount;
    /** params[i] 所属的「;」组：组内用「:」分隔的是子参数（SGR 38:2::r:g:b 这种）。 */
    private final int[] groupStart = new int[16];
    private final int[] groupSize = new int[16];

    /** 终端应答出口，null 表示只做屏模型不回应（例如纯回放）。 */
    private volatile ReplySink replySink;

    /**
     * 文字区与单元格的像素尺寸，供 XTWINOPS（CSI 14/16/18 t）应答用。
     * 渲染层在字号/视口变化时推送；0 表示未知（此时对应查询不应答，
     * 程序会走 TIOCGWINSZ 兜底——vim 拿不到这个应答时会多等一个超时）。
     */
    private volatile int textAreaWidthPx;
    private volatile int textAreaHeightPx;
    private volatile int cellWidthPx;
    private volatile int cellHeightPx;

    /** 渲染层同步像素度量（单元格尺寸随字号变化，vim 捏合缩放后依赖它重新对齐几何）。 */
    public void setTextMetrics(int areaW, int areaH, int cellW, int cellH) {
        textAreaWidthPx = areaW;
        textAreaHeightPx = areaH;
        cellWidthPx = cellW;
        cellHeightPx = cellH;
    }

    /** DECSET 2004：括号粘贴。开启后粘贴内容要用 ESC[200~ … ESC[201~ 包起来。 */
    private boolean bracketedPaste;

    /** DECSET 7：自动换行。关掉后写满最后一列就原地覆盖（vim 会用 ?7l 避免多余滚动）。 */
    private boolean autowrap = true;

    /** DECSET 6：原点模式。开启后 CUP/VPA 的行号相对滚动区顶部，而不是屏幕顶部。 */
    private boolean originMode;

    /** 制表位。xterm 默认每 8 列一个，程序可以用 HTS / TBC 改（`tput tsets`）。 */
    private boolean[] tabStops;

    /**
     * 延迟换行。真终端写满最后一列时光标不立刻跳到下一行，而是停在最后一格「待换行」，
     * 下一个字符来了才换。不这么做的话，每行末尾都会多滚一行，vim 满屏重绘时会整体错位。
     */
    private boolean pendingWrap;

    /** G0/G1 指定：'B' = ASCII，'0' = DEC 特殊图形（画线字符，vim 的分屏边框/listchars 用）。 */
    private char charsetG0 = 'B';
    private char charsetG1 = 'B';
    private int charsetTarget;
    /** 当前解释 0x20–0x7E 用哪一组：0 = G0，1 = G1（SO/SI 切换）。 */
    private int glSet;

    /** 鼠标上报：0 关闭，否则是 1000 / 1002 / 1003 三种模式之一。 */
    private int mouseMode;
    /** 1006：SGR 扩展坐标（ESC[<b;x;yM）。 */
    private boolean mouseSgr;
    /** 1005：UTF-8 扩展坐标。 */
    private boolean mouseUtf8;

    /** DECSET 1004：焦点变化上报（ESC[I 进入 / ESC[O 离开）。 */
    private boolean focusReport;

    /** DECSCUSR 光标形状：0/1 方块，2 方块，3 下划线，4 下划线，5 竖线，6 竖线。 */
    private int cursorShape;

    /* UTF-8 增量解码缓冲 */
    private final byte[] pending = new byte[4];
    private int pendingLen;

    public TerminalEmulator(int cols, int rows, int maxScrollback, int defaultFg, int defaultBg) {
        this.defaultFg = defaultFg;
        this.defaultBg = defaultBg;
        this.maxScrollback = Math.max(0, maxScrollback);
        reset(Math.max(4, cols), Math.max(2, rows));
    }

    /* -------------------- 尺寸 -------------------- */

    private void reset(int newCols, int newRows) {
        cols = newCols;
        rows = newRows;
        mainLines = new Line[rows];
        for (int r = 0; r < rows; r++) {
            mainLines[r] = new Line(cols);
            mainLines[r].blank(cols, defaultFg, defaultBg);
        }
        altLines = null;
        altActive = false;
        lines = mainLines;
        scrollback.clear();
        cursorRow = 0;
        cursorCol = 0;
        cursorVisible = true;
        appCursorKeys = false;
        bracketedPaste = false;
        autowrap = true;
        originMode = false;
        pendingWrap = false;
        initTabStops();
        charsetG0 = 'B';
        charsetG1 = 'B';
        charsetTarget = 0;
        glSet = 0;
        mouseMode = 0;
        mouseSgr = false;
        mouseUtf8 = false;
        focusReport = false;
        cursorShape = 0;
        scrollTop = 0;
        scrollBottom = rows - 1;
        curFg = defaultFg;
        curBg = defaultBg;
        curAttr = 0;
        state = STATE_GROUND;
        csi.setLength(0);
        osc.setLength(0);
        pendingLen = 0;
    }

    /**
     * 尺寸变化。两件事必须和真终端一致，否则手机上每次弹键盘都会「丢东西」：
     *
     * <p>1. scrollback 不能清。转屏/弹键盘就抹掉历史，等于把终端最常用的功能废掉。
     * <p>2. 变矮时优先从顶部裁，把裁掉的行推进 scrollback —— 保证光标（也就是提示符）还在屏幕上。
     *    原来是从底部裁，软键盘一弹提示符直接消失。变高时在底部补空行（xterm 也是这么做，
     *    内容不跟着往下跳），光标行列不变。
     */
    public synchronized void resize(int newCols, int newRows) {
        newCols = Math.max(10, newCols);
        newRows = Math.max(2, newRows);
        if (newCols == cols && newRows == rows) {
            return;
        }
        if (newRows != rows) {
            // 先调高度：reflow 要用新的屏幕行数决定窗口，否则转竖屏时会按旧的 23 行算窗口，
            // 多出来的内容被推进 scrollback、屏幕下方留一大片空
            mainLines = resizeHeight(mainLines, newRows, true);
            if (altLines != null) {
                altLines = resizeHeight(altLines, newRows, false);
            }
            rows = newRows;
        }
        if (newCols != cols) {
            // 主屏 + scrollback 做双向 reflow（软换行行拆开/拼回，右端内容不丢）；
            // 备用屏是全屏应用的地盘，应用收到 SIGWINCH 会整屏重画，截断即可。
            reflowMain(newCols);
            if (altLines != null) {
                altLines = resizeWidth(altLines, newCols);
            }
            cols = newCols;
            cursorCol = Math.min(cursorCol, cols - 1);
            pendingWrap = false;
            initTabStops();
        }
        lines = altActive && altLines != null ? altLines : mainLines;
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    /**
     * 主屏宽度重排：把 scrollback + 当前屏视为一条流，按软换行标记拆成逻辑行，
     * 再按新宽度重新折行。变窄时超长行折成续行（xterm 同款），内容不再截断丢失；
     * 变宽时把续行拼回来。重排后光标落在其所在内容行的新位置上。
     */
    private void reflowMain(int newCols) {
        int sbCount = scrollback.size();
        int total = sbCount + rows;
        int cursorInput = sbCount + cursorRow;
        int cursorOutAbs = -1;
        // 屏幕底部的填充空行不是内容：它们只是屏幕比内容高时补的白，
        // 跟着一起重排的话会把窗口整体顶一格（转竖屏后首行被推进 scrollback）。
        // 从末尾往前裁到最后一个有内容的行，但不越过光标（用户可能就停在空行上）。
        int lastIdx = total - 1;
        while (lastIdx > 0 && lastIdx != cursorInput) {
            Line tail = lastIdx < sbCount ? scrollback.get(lastIdx) : mainLines[lastIdx - sbCount];
            if (!isBlankLine(tail)) {
                break;
            }
            lastIdx--;
        }

        ArrayList<Line> out = new ArrayList<>();
        // 逻辑行累积缓冲（四套属性数组同步扩容）
        int cap = Math.max(newCols, 256);
        int[] aText = new int[cap];
        int[] aFg = new int[cap];
        int[] aBg = new int[cap];
        byte[] aAttr = new byte[cap];
        String[] aExtra = new String[cap];
        int accLen = 0;
        boolean runHasCursor = false;
        int cursorCellInRun = 0;

        for (int i = 0; i <= lastIdx; i++) {
            Line li = i < sbCount ? scrollback.get(i) : mainLines[i - sbCount];
            if (accLen == 0) {
                runHasCursor = false;
            }
            if (i == cursorInput && !altActive) {
                runHasCursor = true;
                cursorCellInRun = accLen + cursorCol;
            }
            int len = li.text.length;
            // 逻辑行的最后一块要剥掉行尾填充空格：屏幕行永远是满宽的（139 列的行末尾
            // 可能全是空格），不剥的话窄屏重排会把这些空格折成整行空白的续行
            // （转竖屏后每行之间多出空白行的直接原因）。软换行行不能剥——它本来就填满整行。
            if (!(li.wrapped && i < lastIdx) && li.text.length == cols) {
                int keep = li.text.length;
                while (keep > 0 && isTrailingPadding(li, keep - 1)) {
                    keep--;
                }
                if (i == cursorInput && !altActive) {
                    // 光标停在行尾空白里（echo 尾部空格）时不能把光标位置剥掉
                    keep = Math.max(keep, Math.min(cursorCol + 1, li.text.length));
                }
                len = keep;
            }
            if (accLen + len > cap) {
                while (cap < accLen + len) {
                    cap *= 2;
                }
                aText = java.util.Arrays.copyOf(aText, cap);
                aFg = java.util.Arrays.copyOf(aFg, cap);
                aBg = java.util.Arrays.copyOf(aBg, cap);
                aAttr = java.util.Arrays.copyOf(aAttr, cap);
                aExtra = java.util.Arrays.copyOf(aExtra, cap);
            }
            System.arraycopy(li.text, 0, aText, accLen, len);
            System.arraycopy(li.fg, 0, aFg, accLen, len);
            System.arraycopy(li.bg, 0, aBg, accLen, len);
            System.arraycopy(li.attr, 0, aAttr, accLen, len);
            if (li.extra != null) {
                System.arraycopy(li.extra, 0, aExtra, accLen, len);
            }
            accLen += len;
            if (li.wrapped && i < lastIdx) {
                continue;
            }
            // 逻辑行结束：按 newCols 折行
            int before = out.size();
            if (runHasCursor) {
                cursorOutAbs = before + cursorCellInRun / newCols;
            }
            int s = 0;
            if (accLen == 0) {
                // 纯空行（用户敲空回车）trim 后长度为 0，不能被吃掉
                Line nl = new Line(newCols);
                nl.blank(newCols, defaultFg, defaultBg);
                out.add(nl);
            }
            while (s < accLen) {
                int e = Math.min(s + newCols, accLen);
                // 切点落在宽字符中间时把整个字符挪到下一块
                if (e < accLen && e - 1 > s && aText[e] == 0) {
                    e--;
                }
                Line nl = new Line(newCols);
                int n = e - s;
                System.arraycopy(aText, s, nl.text, 0, n);
                System.arraycopy(aFg, s, nl.fg, 0, n);
                System.arraycopy(aBg, s, nl.bg, 0, n);
                System.arraycopy(aAttr, s, nl.attr, 0, n);
                for (int k = s; k < e; k++) {
                    if (aExtra[k] != null) {
                        nl.extra()[k - s] = aExtra[k];
                    }
                }
                for (int k = n; k < newCols; k++) {
                    nl.text[k] = ' ';
                    nl.fg[k] = defaultFg;
                    nl.bg[k] = defaultBg;
                }
                repairWide(nl, 0, newCols - 1);
                nl.wrapped = e < accLen;
                out.add(nl);
                s = e;
            }
            accLen = 0;
        }

        int n = out.size();
        int maxStart = Math.max(0, n - rows);
        // 窗口底部对齐：终端显示的是「最新输出」，转屏后不该把内容推进 scrollback 再看一片空。
        // 光标行通常在流的末尾附近，天然可见；只有清屏后光标退回顶部这类情形才需要上移窗口兜底。
        int screenStart = maxStart;
        if (cursorOutAbs >= 0 && !altActive && cursorOutAbs < screenStart) {
            screenStart = Math.max(0, cursorOutAbs - rows + 1);
        }
        scrollback.clear();
        if (maxScrollback > 0) {
            for (int i = 0; i < screenStart; i++) {
                scrollback.add(out.get(i));
            }
            while (scrollback.size() > maxScrollback) {
                scrollback.remove(0);
            }
        }
        int screenCount = Math.min(n - screenStart, rows);
        // 内容锚定顶部、空白补底部（xterm 变高同款行为），光标跟着自己的内容行走。
        // 空白行不能共用同一个 Line 对象：原地写入会跨行串味
        mainLines = new Line[rows];
        for (int r = 0; r < rows; r++) {
            if (r < screenCount) {
                mainLines[r] = out.get(screenStart + r);
            } else {
                mainLines[r] = new Line(newCols);
                mainLines[r].blank(newCols, defaultFg, defaultBg);
            }
        }
        if (cursorOutAbs >= 0 && !altActive) {
            cursorRow = clamp(cursorOutAbs - screenStart, 0, rows - 1);
        } else {
            cursorRow = clamp(cursorRow, 0, rows - 1);
        }
    }

    /** 该格是否是「行尾填充空格」——纯空格、无组合字符、背景为默认色、无属性（反显/下划线会让空格可见）。 */
    private boolean isTrailingPadding(Line li, int k) {
        return li.text[k] == ' '
                && (li.extra == null || li.extra[k] == null)
                && li.bg[k] == defaultBg
                && li.attr[k] == 0;
    }

    /** 整行都是不可见的填充空格（屏幕下方的空白行）。 */
    private boolean isBlankLine(Line li) {
        for (int k = 0; k < li.text.length; k++) {
            if (!isTrailingPadding(li, k)) {
                return false;
            }
        }
        return true;
    }

    private Line[] resizeWidth(Line[] old, int newCols) {
        Line[] out = new Line[old.length];
        for (int r = 0; r < old.length; r++) {
            out[r] = new Line(newCols);
            out[r].blank(newCols, defaultFg, defaultBg);
            int n = Math.min(newCols, old[r].text.length);
            System.arraycopy(old[r].text, 0, out[r].text, 0, n);
            System.arraycopy(old[r].fg, 0, out[r].fg, 0, n);
            System.arraycopy(old[r].bg, 0, out[r].bg, 0, n);
            System.arraycopy(old[r].attr, 0, out[r].attr, 0, n);
            if (old[r].extra != null) {
                for (int i = 0; i < n; i++) {
                    if (old[r].extra[i] != null) {
                        out[r].extra()[i] = old[r].extra[i];
                    }
                }
            }
            // 变窄可能把宽字符砍成半个（只剩头没有尾），修掉
            if (newCols < old[r].text.length) {
                repairWide(out[r], 0, newCols - 1);
            }
        }
        return out;
    }

    private Line[] resizeHeight(Line[] src, int newRows, boolean main) {
        Line[] out = new Line[newRows];
        int delta = newRows - rows;
        int dropTop = 0;
        if (delta < 0 && main) {
            // 只有光标会被裁掉时才从顶部删；删掉的行进 scrollback
            dropTop = clamp(cursorRow - (newRows - 1), 0, -delta);
        }
        for (int r = 0; r < newRows; r++) {
            int from = r + dropTop;
            if (from < rows) {
                out[r] = src[from];
            } else {
                out[r] = new Line(cols);
                out[r].blank(cols, defaultFg, defaultBg);
            }
        }
        if (main) {
            for (int r = 0; r < dropTop; r++) {
                pushScrollback(src[r]);
            }
            cursorRow = clamp(cursorRow - dropTop, 0, newRows - 1);
        }
        return out;
    }

    private void initTabStops() {
        tabStops = new boolean[cols];
        for (int i = 8; i < cols; i += 8) {
            tabStops[i] = true;
        }
    }

    /** 下一个制表位。没有更右的制表位时停在最后一列（真终端的行为）。 */
    private int nextTabStop(int from) {
        for (int i = from + 1; i < cols; i++) {
            if (tabStops[i]) {
                return i;
            }
        }
        return cols - 1;
    }

    /** 上一个制表位。 */
    private int prevTabStop(int from) {
        for (int i = Math.min(from, cols) - 1; i > 0; i--) {
            if (tabStops[i]) {
                return i;
            }
        }
        return 0;
    }

    public synchronized int getCols() {
        return cols;
    }

    public synchronized int getRows() {
        return rows;
    }

    /* -------------------- 读接口（给 View 用） -------------------- */

    public synchronized Line getLine(int row) {
        if (row < 0 || row >= rows) {
            return null;
        }
        return lines[row];
    }

    public synchronized int getScrollbackCount() {
        return scrollback.size();
    }

    public synchronized Line getScrollbackLine(int index) {
        if (index < 0 || index >= scrollback.size()) {
            return null;
        }
        return scrollback.get(index);
    }

    public synchronized int getCursorRow() {
        return cursorRow;
    }

    public synchronized int getCursorCol() {
        return cursorCol;
    }

    public synchronized boolean isCursorVisible() {
        return cursorVisible;
    }

    public synchronized boolean isAppCursorKeys() {
        return appCursorKeys;
    }

    public synchronized boolean isAutowrap() {
        return autowrap;
    }

    public synchronized int getCursorShape() {
        return cursorShape;
    }

    /** 鼠标上报是否开启（vim 的 set mouse=a 会开）。开启后触摸应转成鼠标序列而不是本地滚动。 */
    public synchronized boolean isMouseTracking() {
        return mouseMode != 0;
    }

    public synchronized boolean isMouseSgr() {
        return mouseSgr;
    }

    public synchronized boolean isFocusReport() {
        return focusReport;
    }

    /**
     * 生成一条鼠标上报序列。button：0 左 / 1 中 / 2 右，松开请用 {@code release=true}；
     * motion 表示这是拖动中的移动上报（1002/1003 模式下才发）。
     * 坐标从 1 开始（终端语义）。
     */
    public synchronized String mouseReport(int button, int col, int row, boolean release, boolean motion) {
        if (mouseMode == 0) {
            return null;
        }
        if (mouseMode == 9 && (release || motion)) {
            return null;
        }
        if (mouseMode == 1000 && motion) {
            return null;
        }
        int cb = button;
        if (motion) {
            cb += 32;
        }
        if (mouseMode == 1003 && motion) {
            cb |= 32;
        }
        int x = Math.max(1, Math.min(cols, col));
        int y = Math.max(1, Math.min(rows, row));
        if (mouseSgr) {
            return "\u001b[<" + cb + ";" + x + ";" + y + (release ? "m" : "M");
        }
        // 老式 X10/VT200 编码：release 用 button=3
        int b = release ? 3 : cb;
        if (mouseUtf8) {
            // 1005：坐标超出单字节范围时用 UTF-8 编码
            return "\u001b[M" + utf8(32 + b) + utf8(32 + x) + utf8(32 + y);
        }
        return "\u001b[M" + (char) (32 + b) + (char) (32 + x) + (char) (32 + y);
    }

    private static String utf8(int cp) {
        return new String(Character.toChars(cp));
    }

    public synchronized String getText() {
        // 尾部空行只是屏幕比内容高时补的白，复制出去是一串没意义的换行——
        // 裁到最后一个有内容的行为止（用户敲的空回车在中间，不受影响）
        int end = scrollback.size() + rows - 1;
        while (end >= 0) {
            Line li = end < scrollback.size() ? scrollback.get(end) : lines[end - scrollback.size()];
            if (!isBlankLine(li)) {
                break;
            }
            end--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i <= end; i++) {
            appendLineText(sb, i < scrollback.size() ? scrollback.get(i) : lines[i - scrollback.size()]);
        }
        return sb.toString();
    }

    /**
     * 只取当前屏（不含 scrollback）。「复制全部输出」在跑过编译/日志之后
     * 动辄几万行，用户真正想要的多半是「刚才这一屏」，所以单独给一个入口。
     */
    public synchronized String getScreenText() {
        int end = rows - 1;
        while (end >= 0 && isBlankLine(lines[end])) {
            end--;
        }
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r <= end; r++) {
            appendLineText(sb, lines[r]);
        }
        return sb.toString();
    }

    private void appendLineText(StringBuilder sb, Line line) {
        int last = -1;
        for (int i = 0; i < line.text.length; i++) {
            int cp = line.text[i];
            if (cp != ' ' && cp != 0) {
                last = i;
            }
        }
        for (int i = 0; i <= last; i++) {
            int cp = line.text[i];
            if (cp != 0) {
                sb.appendCodePoint(cp);
                String extra = line.extraAt(i);
                if (extra != null) {
                    sb.append(extra);
                }
            }
        }
        sb.append('\n');
    }

    public synchronized void clearScreen() {
        for (int r = 0; r < rows; r++) {
            blankLine(r);
        }
        scrollback.clear();
        cursorRow = 0;
        cursorCol = 0;
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    /* -------------------- 写接口 -------------------- */

    public synchronized void append(byte[] data, int length) {
        if (data == null || length <= 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            feedByte(data[i]);
        }
    }

    private void feedByte(byte b) {
        if (pendingLen == 0 && (b & 0x80) == 0) {
            consume(b & 0x7F);
            return;
        }
        if (pendingLen >= pending.length) {
            pendingLen = 0;
        }
        pending[pendingLen++] = b;
        int need = utf8Length(pending[0]);
        if (need <= 0) {
            pendingLen = 0;
            consume(0xFFFD);
            return;
        }
        if (pendingLen == need) {
            int cp = decodeUtf8(pending, need);
            pendingLen = 0;
            consume(cp < 0 ? 0xFFFD : cp);
        }
    }

    private static int utf8Length(byte first) {
        int b = first & 0xFF;
        if (b < 0x80) {
            return 1;
        }
        if (b >= 0xC2 && b <= 0xDF) {
            return 2;
        }
        if (b >= 0xE0 && b <= 0xEF) {
            return 3;
        }
        if (b >= 0xF0 && b <= 0xF4) {
            return 4;
        }
        return 0;
    }

    private static int decodeUtf8(byte[] buf, int len) {
        int cp;
        switch (len) {
            case 1:
                return buf[0] & 0xFF;
            case 2:
                cp = buf[0] & 0x1F;
                break;
            case 3:
                cp = buf[0] & 0x0F;
                break;
            case 4:
                cp = buf[0] & 0x07;
                break;
            default:
                return -1;
        }
        for (int i = 1; i < len; i++) {
            int b = buf[i] & 0xFF;
            if ((b & 0xC0) != 0x80) {
                return -1;
            }
            cp = (cp << 6) | (b & 0x3F);
        }
        return cp;
    }

    /* -------------------- 解析状态机 -------------------- */

    private void consume(int c) {
        switch (state) {
            case STATE_GROUND:
                ground(c);
                break;
            case STATE_ESC:
                escape(c);
                break;
            case STATE_CSI:
                csiChar(c);
                break;
            case STATE_OSC:
                if (c == 0x07) {
                    handleOsc(osc.toString());
                    osc.setLength(0);
                    state = STATE_GROUND;
                } else if (c == 0x1B) {
                    state = STATE_OSC_ESC;
                } else if (osc.length() < 64) {
                    osc.append((char) c);
                }
                break;
            case STATE_OSC_ESC:
                if (c == '\\') {
                    handleOsc(osc.toString());
                    osc.setLength(0);
                    state = STATE_GROUND;
                } else {
                    state = STATE_OSC;
                }
                break;
            case STATE_CHARSET:
                // ESC ( X / ESC ) X 的 X：'B' = ASCII，'0' = DEC 特殊图形
                if (charsetTarget == 0) {
                    charsetG0 = (char) c;
                } else if (charsetTarget == 1) {
                    charsetG1 = (char) c;
                }
                state = STATE_GROUND;
                break;
            case STATE_ESC_SKIP:
                state = STATE_GROUND;
                break;
            case STATE_ESC_HASH:
                if (c == '8') {
                    alignmentTest();
                }
                state = STATE_GROUND;
                break;
            default:
                state = STATE_GROUND;
                break;
        }
    }

    private void ground(int c) {
        switch (c) {
            case 0x1B:
                state = STATE_ESC;
                break;
            case 0x07:
                break;
            case 0x08:
                pendingWrap = false;
                if (cursorCol > 0) {
                    cursorCol--;
                }
                break;
            case 0x09:
                pendingWrap = false;
                cursorCol = nextTabStop(cursorCol);
                break;
            case 0x0A:
            case 0x0B:
            case 0x0C:
                pendingWrap = false;
                // 显式换行结束一个逻辑行；不 clear 的话 reflow 会把上下两行误拼
                lines[cursorRow].wrapped = false;
                lineFeed();
                break;
            case 0x0D:
                pendingWrap = false;
                cursorCol = 0;
                break;
            case 0x0E:
                glSet = 1;
                break;
            case 0x0F:
                glSet = 0;
                break;
            default:
                if (c >= 0x20 && c != 0x7F) {
                    putChar(c);
                }
                break;
        }
    }

    private void escape(int c) {
        state = STATE_GROUND;
        switch (c) {
            case '[':
                csi.setLength(0);
                state = STATE_CSI;
                break;
            case ']':
                osc.setLength(0);
                state = STATE_OSC;
                break;
            case '(':
                charsetTarget = 0;
                state = STATE_CHARSET;
                break;
            case ')':
                charsetTarget = 1;
                state = STATE_CHARSET;
                break;
            case '*':
            case '+':
                // G2/G3 我们不用，但这两个字符得吃掉，否则会漏成可见字符
                charsetTarget = 2;
                state = STATE_CHARSET;
                break;
            case '#':
                // ESC # 8 = DECALN（把整屏填成 E，用来检查字符网格）
                state = STATE_ESC_HASH;
                break;
            case '%':
            case ' ':
            case '&':
            case 'N':
            case 'O':
                // ESC % G（选 UTF-8）、ESC SP F（7/8 位模式）、ESC N/O（单次移出）都是
                // 「ESC + 一个参数字节」的形式。不把这个参数字节吃掉，它就会变成屏幕上的可见字符
                // —— `tput init`、`reset`、tmux 都会发这些序列。
                state = STATE_ESC_SKIP;
                break;
            case '=':
            case '>':
                // DECKPAM / DECKPNM：应用键盘模式。我们始终按 ANSI 模式发键，忽略即可。
                break;
            case '7':
                saveCursor();
                break;
            case '8':
                restoreCursor();
                break;
            case 'H':
                // HTS：把当前列设成制表位（`tput tset` / tabs 命令用）
                if (cursorCol >= 0 && cursorCol < cols) {
                    tabStops[cursorCol] = true;
                }
                break;
            case 'D':
                lines[cursorRow].wrapped = false;
                lineFeed();
                break;
            case 'E':
                cursorCol = 0;
                lines[cursorRow].wrapped = false;
                lineFeed();
                break;
            case 'M':
                reverseIndex();
                break;
            case 'c':
                reset(cols, rows);
                break;
            default:
                break;
        }
    }

    private void csiChar(int c) {
        if (c >= 0x40 && c <= 0x7E) {
            handleCsi(csi.toString(), (char) c);
            csi.setLength(0);
            state = STATE_GROUND;
        } else if (c == 0x1B) {
            csi.setLength(0);
            state = STATE_ESC;
        } else if (csi.length() < 64) {
            csi.append((char) c);
        }
    }

    private void handleCsi(String raw, char finalChar) {
        char prefix = 0;
        String body = raw;
        if (!body.isEmpty()) {
            char first = body.charAt(0);
            if (first == '?' || first == '!' || first == '>' || first == '<' || first == '=') {
                prefix = first;
                body = body.substring(1);
            }
        }

        paramCount = 0;
        if (!body.isEmpty()) {
            // 先按「;」分组，再按「:」拆子参数。不能一股脑把 ':' 换成 ';'：
            // SGR 的 38:2::R:G:B（含空白色彩空间）换完会多出一个 0，颜色直接错位成别的颜色。
            String[] groups = body.split(";", -1);
            for (String group : groups) {
                int start = paramCount;
                String[] subs = group.split(":", -1);
                for (String sub : subs) {
                    if (paramCount >= params.length) {
                        break;
                    }
                    params[paramCount++] = parseIntSafe(sub);
                }
                for (int k = start; k < paramCount; k++) {
                    groupStart[k] = start;
                    groupSize[k] = paramCount - start;
                }
            }
        }

        // 光标移动类指令会清掉「待换行」状态（SGR 之类不清，否则 vim 每段颜色都会打断换行）
        switch (finalChar) {
            case 'A':
            case 'B':
            case 'C':
            case 'D':
            case 'E':
            case 'F':
            case 'G':
            case '`':
            case 'H':
            case 'f':
            case 'd':
            case 'Z':
                pendingWrap = false;
                break;
            default:
                break;
        }

        switch (finalChar) {
            case 'A': {
                // 和 xterm 一致：光标已经在滚动区外时不受上边界限制，可以一路走到第 0 行
                int min = cursorRow < scrollTop ? 0 : scrollTop;
                cursorRow = Math.max(min, cursorRow - arg(0, 1));
                break;
            }
            case 'B': {
                int max = cursorRow > scrollBottom ? rows - 1 : scrollBottom;
                cursorRow = Math.min(max, cursorRow + arg(0, 1));
                break;
            }
            case 'C':
                cursorCol = clamp(cursorCol + arg(0, 1), 0, cols - 1);
                break;
            case 'D':
                cursorCol = clamp(cursorCol - arg(0, 1), 0, cols - 1);
                break;
            case 'E':
                cursorCol = 0;
                cursorRow = clamp(cursorRow + arg(0, 1), scrollTop, scrollBottom);
                break;
            case 'F':
                cursorCol = 0;
                cursorRow = clamp(cursorRow - arg(0, 1), scrollTop, scrollBottom);
                break;
            case 'G':
            case '`':
                cursorCol = clamp(arg(0, 1) - 1, 0, cols - 1);
                break;
            case 'H':
            case 'f':
                // 原点模式（DECOM）下行号相对滚动区顶部；否则相对屏幕顶部
                cursorRow = originMode
                        ? clamp(scrollTop + arg(0, 1) - 1, scrollTop, scrollBottom)
                        : clamp(arg(0, 1) - 1, 0, rows - 1);
                cursorCol = clamp(arg(1, 1) - 1, 0, cols - 1);
                break;
            case 'd':
                cursorRow = originMode
                        ? clamp(scrollTop + arg(0, 1) - 1, scrollTop, scrollBottom)
                        : clamp(arg(0, 1) - 1, 0, rows - 1);
                break;
            case 'Z': {
                // CBT：向左跳 n 个制表位（Shift+Tab 用它，vim 里反向跳缩进）
                int n = Math.max(1, arg(0, 1));
                for (int i = 0; i < n; i++) {
                    cursorCol = prevTabStop(cursorCol);
                }
                break;
            }
            case 'I': {
                // CHT：向右跳 n 个制表位
                int n = Math.max(1, arg(0, 1));
                for (int i = 0; i < n; i++) {
                    cursorCol = nextTabStop(cursorCol);
                }
                break;
            }
            case 'g':
                if (prefix == 0) {
                    // TBC：0 = 清当前列的制表位，3 = 清全部
                    int mode = arg(0, 0);
                    if (mode == 0) {
                        tabStops[cursorCol] = false;
                    } else if (mode == 3) {
                        java.util.Arrays.fill(tabStops, false);
                    }
                }
                break;
            case 'p':
                // DECSTR（CSI ! p）软复位：`reset` / `tput init` / 程序异常退出后用它把状态拉回来
                if (prefix == '!') {
                    softReset();
                }
                break;
            case 'q':
                // DECSCUSR：2 方块 / 4 下划线 / 6 竖线
                if (prefix == 0) {
                    cursorShape = arg(0, 0);
                }
                break;
            case 'J':
                eraseDisplay(arg(0, 0));
                break;
            case 'K':
                eraseLine(arg(0, 0));
                break;
            case 'L':
                insertLines(arg(0, 1));
                break;
            case 'M':
                deleteLines(arg(0, 1));
                break;
            case 'P':
                deleteChars(arg(0, 1));
                break;
            case '@':
                insertChars(arg(0, 1));
                break;
            case 'X':
                eraseChars(arg(0, 1));
                break;
            case 'S':
                scrollUpLines(arg(0, 1));
                break;
            case 'T':
                scrollDownLines(arg(0, 1));
                break;
            case 'm':
                // 只认 SGR（无前缀）。`CSI > 4 ; 2 m` 是 xterm 的 modifyOtherKeys 开关，
                // 当成 SGR 会莫名其妙把整屏变成下划线+暗色
                if (prefix == 0) {
                    applySgr();
                }
                break;
            case 'r': {
                int top = clamp(arg(0, 1) - 1, 0, rows - 1);
                int bottom = clamp(arg(1, rows) - 1, 0, rows - 1);
                if (bottom > top) {
                    scrollTop = top;
                    scrollBottom = bottom;
                    cursorRow = top;
                    cursorCol = 0;
                }
                break;
            }
            case 's':
                saveCursor();
                break;
            case 'u':
                restoreCursor();
                break;
            case 'h':
                if (prefix == '?') {
                    setPrivateMode(true);
                }
                break;
            case 'l':
                if (prefix == '?') {
                    setPrivateMode(false);
                }
                break;
            case 'n': {
                // DSR：6 = 光标位置报告（CPR），5 = 运行状态。回不上来对方就会一直等。
                int kind = arg(0, 0);
                if (prefix == '?') {
                    if (kind == 6) {
                        // DECXCPR，多一个页号
                        reply("\u001b[?" + (cursorRow + 1) + ";" + (cursorCol + 1) + ";1R");
                    }
                } else if (prefix == 0) {
                    if (kind == 6) {
                        reply("\u001b[" + (cursorRow + 1) + ";" + (cursorCol + 1) + "R");
                    } else if (kind == 5) {
                        reply("\u001b[0n");
                    }
                }
                break;
            }
            case 'c':
                // DA：主设备属性回 VT220，次要设备属性回「VT220 + 标准颜色」。
                if (prefix == '>') {
                    reply("\u001b[>0;10;1c");
                } else if (prefix == 0) {
                    reply("\u001b[?62;22c");
                }
                break;
            case 't':
                // XTWINOPS 窗口操作。查询类要应答（vim 收到 SIGWINCH 后靠它
                // 重新对齐几何；实测不应答会把「几何不一致」拖长到秒级，
                // 期间程序往屏幕外画，内容落错位置）。设置类（22/23 推拉
                // 标题栈）静默忽略。
                if (prefix == 0) {
                    int op = arg(0, 0);
                    switch (op) {
                        case 14:
                            // 文字区像素尺寸
                            if (textAreaWidthPx > 0 && textAreaHeightPx > 0) {
                                reply("\u001b[4;" + textAreaHeightPx + ";" + textAreaWidthPx + "t");
                            }
                            break;
                        case 16:
                            // 单元格像素尺寸
                            if (cellWidthPx > 0 && cellHeightPx > 0) {
                                reply("\u001b[6;" + cellHeightPx + ";" + cellWidthPx + "t");
                            }
                            break;
                        case 18:
                            // 字符行列数
                            reply("\u001b[8;" + rows + ";" + cols + "t");
                            break;
                        case 19:
                            // 屏幕字符尺寸（我们没有分离的"屏幕/文字区"，同 18）
                            reply("\u001b[9;" + rows + ";" + cols + "t");
                            break;
                        default:
                            break;
                    }
                }
                break;
            default:
                break;
        }
    }

    /**
     * OSC 查询应答。只处理带 `?` 的颜色查询（10 前景 / 11 背景 / 12 光标 / 4;N 调色板），
     * 其它 OSC（改标题、超链接、剪贴板）一律忽略。
     */
    private void handleOsc(String payload) {
        if (payload.isEmpty() || !payload.endsWith("?")) {
            return;
        }
        int semi = payload.indexOf(';');
        int kind = parseIntSafe(semi < 0 ? payload.substring(0, payload.length() - 1)
                : payload.substring(0, semi));
        String body = payload.substring(0, payload.length() - 1);
        switch (kind) {
            case 10:
                reply("\u001b]10;rgb:" + rgbOf(defaultFg) + "\u0007");
                break;
            case 11:
                reply("\u001b]11;rgb:" + rgbOf(defaultBg) + "\u0007");
                break;
            case 12:
                reply("\u001b]12;rgb:" + rgbOf(defaultFg) + "\u0007");
                break;
            case 4: {
                // 形如 4;N;?：N 是调色板索引
                String[] parts = body.split(";", -1);
                int index = parts.length >= 2 ? parseIntSafe(parts[1]) : 0;
                if (index >= 0 && index < PALETTE.length) {
                    reply("\u001b]4;" + index + ";rgb:" + rgbOf(PALETTE[index]) + "\u0007");
                }
                break;
            }
            default:
                break;
        }
    }

    /** 0xFFRRGGBB → xterm 风格 rrrr/gggg/bbbb（每通道两位十六进制重复一次）。 */
    private static String rgbOf(int color) {
        String hex = String.format("%06x", color & 0xFFFFFF);
        String r = hex.substring(0, 2);
        String g = hex.substring(2, 4);
        String b = hex.substring(4, 6);
        return r + r + "/" + g + g + "/" + b + b;
    }

    private void reply(String text) {
        ReplySink sink = replySink;
        if (sink != null) {
            sink.onReply(text);
        }
    }

    public void setReplySink(ReplySink sink) {
        this.replySink = sink;
    }

    /** 括号粘贴是否开启（DECSET 2004）。开启时粘贴内容必须用 ESC[200~/ESC[201~ 包起来。 */
    public synchronized boolean isBracketedPaste() {
        return bracketedPaste;
    }

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int value = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return value;
            }
            value = value * 10 + (c - '0');
            if (value > 99999) {
                return 99999;
            }
        }
        return value;
    }

    private int arg(int index, int defaultValue) {
        if (index >= paramCount) {
            return defaultValue;
        }
        int v = params[index];
        return v == 0 ? defaultValue : v;
    }

    private static int clamp(int v, int min, int max) {
        if (v < min) {
            return min;
        }
        if (v > max) {
            return max;
        }
        return v;
    }

    /* -------------------- 模式与 SGR -------------------- */

    private void setPrivateMode(boolean enable) {
        for (int i = 0; i < paramCount; i++) {
            int mode = params[i];
            if (mode == 1) {
                appCursorKeys = enable;
            } else if (mode == 6) {
                // DECOM：光标寻址相对滚动区。切换时真终端会把光标送回原点
                originMode = enable;
                cursorRow = enable ? scrollTop : 0;
                cursorCol = 0;
                pendingWrap = false;
            } else if (mode == 7) {
                autowrap = enable;
                if (!enable) {
                    pendingWrap = false;
                }
            } else if (mode == 25) {
                cursorVisible = enable;
            } else if (mode == 1049 || mode == 1047 || mode == 47) {
                switchAltScreen(enable);
            } else if (mode == 2004) {
                bracketedPaste = enable;
            } else if (mode == 1000 || mode == 1002 || mode == 1003) {
                mouseMode = enable ? mode : 0;
            } else if (mode == 1005) {
                mouseUtf8 = enable;
            } else if (mode == 1006) {
                mouseSgr = enable;
            } else if (mode == 1004) {
                focusReport = enable;
            } else if (mode == 9) {
                // X10：只报按下
                mouseMode = enable ? 9 : 0;
            }
        }
    }

    /**
     * DECSTR 软复位（CSI ! p）。程序被 kill 或崩了以后经常留下半截状态——滚动区还在、
     * 光标被藏了、反显没关。`reset` / `tput init` 就是发这条序列把终端拉回默认。
     * 按规范不清屏、不动光标位置，只复位模式与 SGR。
     */
    private void softReset() {
        autowrap = true;
        originMode = false;
        cursorVisible = true;
        appCursorKeys = false;
        pendingWrap = false;
        glSet = 0;
        charsetG0 = 'B';
        charsetG1 = 'B';
        scrollTop = 0;
        scrollBottom = rows - 1;
        curFg = defaultFg;
        curBg = defaultBg;
        curAttr = 0;
        initTabStops();
    }

    /** DECALN（ESC # 8）：整屏填 E，光标回左上。用来检查字符网格是否等宽。 */
    private void alignmentTest() {
        for (int r = 0; r < rows; r++) {
            Line line = lines[r];
            for (int c = 0; c < cols; c++) {
                line.text[c] = 'E';
                line.fg[c] = defaultFg;
                line.bg[c] = defaultBg;
                line.attr[c] = 0;
            }
        }
        cursorRow = 0;
        cursorCol = 0;
        pendingWrap = false;
    }

    private void switchAltScreen(boolean enable) {
        pendingWrap = false;
        if (enable) {
            if (!altActive) {
                savedRow = cursorRow;
                savedCol = cursorCol;
                savedFg = curFg;
                savedBg = curBg;
                savedAttr = curAttr;
                altLines = new Line[rows];
                for (int r = 0; r < rows; r++) {
                    altLines[r] = new Line(cols);
                    altLines[r].blank(cols, defaultFg, defaultBg);
                }
                altActive = true;
                lines = altLines;
                scrollTop = 0;
                scrollBottom = rows - 1;
            }
        } else if (altActive) {
            altActive = false;
            lines = mainLines;
            cursorRow = clamp(savedRow, 0, rows - 1);
            cursorCol = clamp(savedCol, 0, cols - 1);
            curFg = savedFg;
            curBg = savedBg;
            curAttr = savedAttr;
            scrollTop = 0;
            scrollBottom = rows - 1;
        }
    }

    private void applySgr() {
        if (paramCount == 0) {
            // `CSI m` 等价于 `CSI 0 m`（真终端就是这么处理的）
            curFg = defaultFg;
            curBg = defaultBg;
            curAttr = 0;
            return;
        }
        for (int i = 0; i < paramCount; i++) {
            // 组内的子参数由主参数消费（4:3 是「花式下划线」，不是「下划线 + 斜体」）
            if (groupStart[i] != i) {
                continue;
            }
            int v = params[i];
            switch (v) {
                case 0:
                    curFg = defaultFg;
                    curBg = defaultBg;
                    curAttr = 0;
                    break;
                case 1:
                    curAttr |= ATTR_BOLD;
                    break;
                case 2:
                    curAttr |= ATTR_DIM;
                    break;
                case 3:
                    curAttr |= ATTR_ITALIC;
                    break;
                case 4:
                    curAttr |= ATTR_UNDERLINE;
                    break;
                case 7:
                    curAttr |= ATTR_REVERSE;
                    break;
                case 8:
                    curAttr |= ATTR_INVISIBLE;
                    break;
                case 9:
                    curAttr |= ATTR_STRIKE;
                    break;
                case 21:
                case 22:
                    curAttr &= ~(ATTR_BOLD | ATTR_DIM);
                    break;
                case 23:
                    curAttr &= ~ATTR_ITALIC;
                    break;
                case 24:
                    curAttr &= ~ATTR_UNDERLINE;
                    break;
                case 27:
                    curAttr &= ~ATTR_REVERSE;
                    break;
                case 28:
                    curAttr &= ~ATTR_INVISIBLE;
                    break;
                case 29:
                    curAttr &= ~ATTR_STRIKE;
                    break;
                case 38:
                    i = extendedColor(i, true);
                    break;
                case 48:
                    i = extendedColor(i, false);
                    break;
                case 39:
                    curFg = defaultFg;
                    break;
                case 49:
                    curBg = defaultBg;
                    break;
                default:
                    if (v >= 30 && v <= 37) {
                        curFg = PALETTE[v - 30];
                    } else if (v >= 40 && v <= 47) {
                        curBg = PALETTE[v - 40];
                    } else if (v >= 90 && v <= 97) {
                        curFg = PALETTE[v - 90 + 8];
                    } else if (v >= 100 && v <= 107) {
                        curBg = PALETTE[v - 100 + 8];
                    }
                    break;
            }
        }
    }

    /**
     * 38 / 48 扩展色。两种写法都得认：
     * 分号式 `38;2;R;G;B`（5 个子参数），冒号式 `38:2::R:G:B`（6 个，第二位是色彩空间，通常留空）。
     * 原来把 ':' 一律换成 ';'，冒号式会多出一个 0，R 被当成色彩空间、G 被当成 R，颜色整体错位。
     */
    private int extendedColor(int index, boolean foreground) {
        int size = Math.max(1, groupSize[index]);
        int mode = index + 1 < paramCount ? params[index + 1] : 0;
        if (mode == 5 && index + 2 < paramCount) {
            int color = PALETTE[params[index + 2] & 0xFF];
            if (foreground) {
                curFg = color;
            } else {
                curBg = color;
            }
            return index + 2;
        }
        if (mode == 2) {
            int base = size >= 6 ? index + 3 : index + 2;
            if (base + 2 < paramCount) {
                int color = 0xFF000000
                        | ((params[base] & 0xFF) << 16)
                        | ((params[base + 1] & 0xFF) << 8)
                        | (params[base + 2] & 0xFF);
                if (foreground) {
                    curFg = color;
                } else {
                    curBg = color;
                }
                return base + 2;
            }
        }
        // 认不出的形式：整组吃掉，别把子参数当成独立的 SGR
        return index + size - 1;
    }

    /* -------------------- 光标与写字符 -------------------- */

    private void saveCursor() {
        savedRow = cursorRow;
        savedCol = cursorCol;
        savedFg = curFg;
        savedBg = curBg;
        savedAttr = curAttr;
    }

    private void restoreCursor() {
        cursorRow = clamp(savedRow, 0, rows - 1);
        cursorCol = clamp(savedCol, 0, cols - 1);
        curFg = savedFg;
        curBg = savedBg;
        curAttr = savedAttr;
    }

    private void putChar(int cp) {
        cp = mapCharset(cp);
        int width = charWidth(cp);
        if (width == 0) {
            // 组合字符（é 的声调、泰文元音、emoji 的 ZWJ/肤色修饰）不占格，挂到前一个格子上
            combineInto(cp);
            return;
        }

        if (pendingWrap) {
            pendingWrap = false;
            if (autowrap) {
                lines[cursorRow].wrapped = true;
                cursorCol = 0;
                lineFeed();
            }
        }
        if (cursorCol >= cols) {
            if (autowrap) {
                lines[cursorRow].wrapped = true;
                cursorCol = 0;
                lineFeed();
            } else {
                cursorCol = cols - 1;
            }
        }
        // 宽字符卡在最后一列（只剩一格）时，真终端会先换行再画
        if (width == 2 && cursorCol == cols - 1 && autowrap) {
            lines[cursorRow].wrapped = true;
            cursorCol = 0;
            lineFeed();
        }

        Line line = lines[cursorRow];
        // 覆写宽字符的任意一半时把另一半也清掉，否则屏幕上会留半个字形
        for (int k = 0; k < width; k++) {
            clearCell(line, cursorCol + k);
        }
        line.text[cursorCol] = cp;
        line.fg[cursorCol] = curFg;
        line.bg[cursorCol] = curBg;
        line.attr[cursorCol] = (byte) curAttr;
        cursorCol++;
        if (width == 2) {
            if (cursorCol < cols) {
                line.text[cursorCol] = 0;
                line.fg[cursorCol] = curFg;
                line.bg[cursorCol] = curBg;
                line.attr[cursorCol] = (byte) curAttr;
                cursorCol++;
            }
        }
        if (cursorCol >= cols) {
            // 停在最后一格并标记待换行；下一个字符到来时才真正换行
            cursorCol = cols - 1;
            pendingWrap = autowrap;
        }
    }

    /** 把零宽字符挂到光标前一个格子上（光标刚写在 pendingWrap 时就是当前格）。 */
    private void combineInto(int cp) {
        Line line = lines[cursorRow];
        int target = pendingWrap ? cursorCol : cursorCol - 1;
        if (target < 0 || target >= cols) {
            return;
        }
        if (line.text[target] == 0 && target > 0) {
            target--;
        }
        String[] extra = line.extra();
        String prev = extra[target];
        extra[target] = (prev == null ? "" : prev) + new String(Character.toChars(cp));
    }

    /** 清掉一格（含它的组合字符），不动配对角。 */
    private void blankCell(Line line, int col) {
        if (col < 0 || col >= line.text.length) {
            return;
        }
        line.text[col] = ' ';
        line.fg[col] = defaultFg;
        line.bg[col] = defaultBg;
        line.attr[col] = 0;
        line.clearExtra(col);
    }

    /** 清掉一格，同时清掉宽字符的另一半。 */
    private void clearCell(Line line, int col) {
        if (col < 0 || col >= line.text.length) {
            return;
        }
        if (line.text[col] == 0 && col > 0) {
            blankCell(line, col - 1);
        } else if (charWidth(line.text[col]) == 2 && col + 1 < line.text.length
                && line.text[col + 1] == 0) {
            blankCell(line, col + 1);
        }
        blankCell(line, col);
    }

    /**
     * 修复被编辑操作拆散的宽字符（删除/擦除只碰到头或只碰到尾）。
     * 不修的话，屏幕上会留下半个汉字，或者一个占位格被当成空格。
     * 边界按 line 自身的长度钳制：resize 时行的宽度可能和 cols 字段不一致。
     */
    private void repairWide(Line line, int from, int to) {
        int len = line.text.length;
        int lo = Math.max(0, from - 1);
        int hi = Math.min(len - 1, to + 1);
        for (int i = lo; i <= hi; i++) {
            if (line.text[i] == 0) {
                boolean orphan = i == 0 || line.text[i - 1] == 0
                        || charWidth(line.text[i - 1]) != 2;
                if (orphan) {
                    blankCell(line, i);
                }
            } else if (charWidth(line.text[i]) == 2) {
                boolean orphan = i + 1 >= len || line.text[i + 1] != 0;
                if (orphan) {
                    blankCell(line, i);
                }
            }
        }
    }

    /**
     * DEC 特殊图形字符集（ESC ( 0）。vim 的垂直分屏线、listchars、fillchars 都靠它，
     * 不映射的话 `\x1b(0x\x1b(B` 会显示成一个字面的字母 x。
     */
    private int mapCharset(int cp) {
        if (cp < 0x20 || cp > 0x7E) {
            return cp;
        }
        char set = glSet == 1 ? charsetG1 : charsetG0;
        if (set != '0') {
            return cp;
        }
        switch (cp) {
            case '`': return 0x25C6; // ◆
            case 'a': return 0x2592; // ▒
            case 'b': return 0x2409; // ␉
            case 'c': return 0x240C; // ␌
            case 'd': return 0x240D; // ␍
            case 'e': return 0x240A; // ␊
            case 'f': return 0x00B0; // °
            case 'g': return 0x00B1; // ±
            case 'h': return 0x2424; // ␤
            case 'i': return 0x240B; // ␋
            case 'j': return 0x2518; // ┘
            case 'k': return 0x2510; // ┐
            case 'l': return 0x250C; // ┌
            case 'm': return 0x2514; // └
            case 'n': return 0x253C; // ┼
            case 'o': return 0x23BA; // ⎺
            case 'p': return 0x23BB; // ⎻
            case 'q': return 0x2500; // ─
            case 'r': return 0x23BC; // ⎼
            case 's': return 0x23BD; // ⎽
            case 't': return 0x251C; // ├
            case 'u': return 0x2524; // ┤
            case 'v': return 0x2534; // ┴
            case 'w': return 0x252C; // ┬
            case 'x': return 0x2502; // │
            case 'y': return 0x2264; // ≤
            case 'z': return 0x2265; // ≥
            case '{': return 0x03C0; // π
            case '|': return 0x2260; // ≠
            case '}': return 0x00A3; // £
            case '~': return 0x00B7; // ·
            case '_': return 0x0020; // 不换行空格（显示成空格）
            default: return cp;
        }
    }

    /** 码点占几列：CJK/全角/emoji 占 2 列，组合字符占 0 列，其余 1 列（0 也表示宽字符的占位尾格）。 */
    public static int charWidth(int cp) {
        if (cp == 0) {
            return 0;
        }
        if (isZeroWidth(cp)) {
            return 0;
        }
        if (cp < 0x1100) {
            return 1;
        }
        if ((cp >= 0x1100 && cp <= 0x115F)
                || (cp >= 0x2E80 && cp <= 0x303E)
                || (cp >= 0x3041 && cp <= 0x33FF)
                || (cp >= 0x3400 && cp <= 0x4DBF)
                || (cp >= 0x4E00 && cp <= 0x9FFF)
                || (cp >= 0xA000 && cp <= 0xA4CF)
                || (cp >= 0xAC00 && cp <= 0xD7A3)
                || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFE30 && cp <= 0xFE6F)
                || (cp >= 0xFF00 && cp <= 0xFF60)
                || (cp >= 0xFFE0 && cp <= 0xFFE6)
                || (cp >= 0x1F300 && cp <= 0x1F5FF)
                || (cp >= 0x1F600 && cp <= 0x1F64F)
                || (cp >= 0x1F680 && cp <= 0x1F6FF)
                || (cp >= 0x1F900 && cp <= 0x1F9FF)
                || (cp >= 0x1FA70 && cp <= 0x1FAFF)
                || cp == 0x1F004 || cp == 0x1F0CF || cp == 0x1F18E
                || (cp >= 0x1F191 && cp <= 0x1F19A)) {
            return 2;
        }
        return 1;
    }

    /**
     * 零宽字符：组合用附加符号（声调、泰文/天城文元音）、变体选择符、ZWJ/ZWNJ、
     * emoji 的肤色修饰。它们要挂在前一个格子上，单独占格会把整行文字推歪。
     */
    private static boolean isZeroWidth(int cp) {
        return (cp >= 0x0300 && cp <= 0x036F)
                || (cp >= 0x0483 && cp <= 0x0489)
                || (cp >= 0x0591 && cp <= 0x05C7)
                || (cp >= 0x0610 && cp <= 0x061A)
                || (cp >= 0x064B && cp <= 0x065F)
                || cp == 0x0670
                || (cp >= 0x06D6 && cp <= 0x06ED)
                || cp == 0x0711
                || (cp >= 0x0730 && cp <= 0x074A)
                || (cp >= 0x07A6 && cp <= 0x07B0)
                || (cp >= 0x0900 && cp <= 0x0902)
                || (cp >= 0x093A && cp <= 0x093C)
                || (cp >= 0x0941 && cp <= 0x0948)
                || cp == 0x094D
                || (cp >= 0x0951 && cp <= 0x0957)
                || cp == 0x0E31
                || (cp >= 0x0E34 && cp <= 0x0E3A)
                || (cp >= 0x0E47 && cp <= 0x0E4E)
                || cp == 0x0EB1
                || (cp >= 0x0EB4 && cp <= 0x0EBC)
                || (cp >= 0x0EC8 && cp <= 0x0ECD)
                || (cp >= 0x1AB0 && cp <= 0x1AFF)
                || (cp >= 0x1DC0 && cp <= 0x1DFF)
                || (cp >= 0x200B && cp <= 0x200F)
                || (cp >= 0x20D0 && cp <= 0x20F0)
                || (cp >= 0xFE00 && cp <= 0xFE0F)
                || (cp >= 0xFE20 && cp <= 0xFE2F)
                || (cp >= 0x1F3FB && cp <= 0x1F3FF)
                || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    /* -------------------- 滚动 -------------------- */

    private void lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(scrollTop, scrollBottom, 1);
        } else if (cursorRow < rows - 1) {
            cursorRow++;
        }
    }

    private void reverseIndex() {
        if (cursorRow == scrollTop) {
            scrollDown(scrollTop, scrollBottom, 1);
        } else if (cursorRow > 0) {
            cursorRow--;
        }
    }

    private void scrollUp(int top, int bottom, int count) {
        if (count < 1) {
            count = 1;
        }
        if (count > bottom - top + 1) {
            count = bottom - top + 1;
        }
        for (int i = 0; i < count; i++) {
            // 备用屏（vim/less 的全屏界面）滚动不该污染主屏的 scrollback
            if (top == 0 && !altActive) {
                pushScrollback(lines[top]);
            }
            System.arraycopy(lines, top + 1, lines, top, bottom - top);
            lines[bottom] = newLine();
        }
    }

    private void scrollDown(int top, int bottom, int count) {
        if (count < 1) {
            count = 1;
        }
        if (count > bottom - top + 1) {
            count = bottom - top + 1;
        }
        for (int i = 0; i < count; i++) {
            System.arraycopy(lines, top, lines, top + 1, bottom - top);
            lines[top] = newLine();
        }
    }

    private void scrollUpLines(int count) {
        scrollUp(scrollTop, scrollBottom, count);
    }

    private void scrollDownLines(int count) {
        scrollDown(scrollTop, scrollBottom, count);
    }

    private Line newLine() {
        Line line = new Line(cols);
        line.blank(cols, defaultFg, defaultBg);
        return line;
    }

    private void pushScrollback(Line line) {
        if (maxScrollback <= 0) {
            return;
        }
        if (line.text.length != cols) {
            return;
        }
        scrollback.add(line);
        while (scrollback.size() > maxScrollback) {
            scrollback.remove(0);
        }
    }

    /**
     * 清空一行——必须换成新的 Line 对象，不能原地 blank()。
     *
     * IL/DL/滚屏都用 arraycopy 搬「引用」，被搬走的行和新清空的行会指向
     * 同一个 Line 实例；原地清空会把搬上去的内容一起擦掉，之后写入又会
     * 同时显示在多行（实测：vim 滚轮 tick = DL 3 行，屏幕上内容以 3 行为
     * 周期重复平铺）。换新对象让行内容真正「移动」。
     */
    private void blankLine(int row) {
        lines[row] = newLine();
    }

    /* -------------------- 擦除与插删 -------------------- */

    /**
     * 擦除时填什么背景色。xterm 的 BCE（background color erase）就是用「当前背景色」，
     * xterm-256color 的 terminfo 里带着 bce 标记，程序（vim、less、mc）会依赖它画色块。
     * 原来一律填默认背景，彩色区域里的 \e[K 会挖出一块黑底。
     */
    private int eraseBg() {
        return (curAttr & ATTR_REVERSE) != 0 ? curFg : curBg;
    }

    private void eraseRange(Line line, int from, int to, int bg) {
        if (line == null || from > to) {
            return;
        }
        int start = Math.max(0, from);
        int end = Math.min(cols - 1, to);
        for (int i = start; i <= end; i++) {
            line.text[i] = ' ';
            line.fg[i] = defaultFg;
            line.bg[i] = bg;
            line.attr[i] = 0;
            line.clearExtra(i);
        }
        repairWide(line, start, end);
    }

    private void eraseDisplay(int mode) {
        int bg = eraseBg();
        if (mode == 0) {
            eraseLine(0);
            for (int r = cursorRow + 1; r < rows; r++) {
                eraseRange(lines[r], 0, cols - 1, bg);
                lines[r].wrapped = false;
            }
        } else if (mode == 1) {
            eraseLine(1);
            for (int r = 0; r < cursorRow; r++) {
                eraseRange(lines[r], 0, cols - 1, bg);
                lines[r].wrapped = false;
            }
        } else {
            for (int r = 0; r < rows; r++) {
                eraseRange(lines[r], 0, cols - 1, bg);
                lines[r].wrapped = false;
            }
            if (mode == 3) {
                scrollback.clear();
            }
        }
    }

    private void eraseLine(int mode) {
        int from;
        int to;
        if (mode == 0) {
            from = cursorCol;
            to = cols - 1;
        } else if (mode == 1) {
            from = 0;
            to = cursorCol;
        } else {
            from = 0;
            to = cols - 1;
        }
        eraseRange(lines[cursorRow], from, to, eraseBg());
        if (from == 0) {
            lines[cursorRow].wrapped = false;
        }
    }

    private void eraseChars(int count) {
        eraseRange(lines[cursorRow], cursorCol, cursorCol + Math.max(1, count) - 1, eraseBg());
    }

    private void deleteChars(int count) {
        if (cursorCol >= cols || count <= 0) {
            return;
        }
        Line line = lines[cursorRow];
        int move = cols - cursorCol - count;
        if (move > 0) {
            System.arraycopy(line.text, cursorCol + count, line.text, cursorCol, move);
            System.arraycopy(line.fg, cursorCol + count, line.fg, cursorCol, move);
            System.arraycopy(line.bg, cursorCol + count, line.bg, cursorCol, move);
            System.arraycopy(line.attr, cursorCol + count, line.attr, cursorCol, move);
            if (line.extra != null) {
                System.arraycopy(line.extra, cursorCol + count, line.extra, cursorCol, move);
            }
        }
        int bg = eraseBg();
        for (int i = Math.max(cursorCol, cols - count); i < cols; i++) {
            line.text[i] = ' ';
            line.fg[i] = defaultFg;
            line.bg[i] = bg;
            line.attr[i] = 0;
            line.clearExtra(i);
        }
        repairWide(line, cursorCol, cols - 1);
    }

    private void insertChars(int count) {
        if (cursorCol >= cols || count <= 0) {
            return;
        }
        Line line = lines[cursorRow];
        int move = cols - cursorCol - count;
        if (move > 0) {
            System.arraycopy(line.text, cursorCol, line.text, cursorCol + count, move);
            System.arraycopy(line.fg, cursorCol, line.fg, cursorCol + count, move);
            System.arraycopy(line.bg, cursorCol, line.bg, cursorCol + count, move);
            System.arraycopy(line.attr, cursorCol, line.attr, cursorCol + count, move);
            if (line.extra != null) {
                System.arraycopy(line.extra, cursorCol, line.extra, cursorCol + count, move);
            }
        }
        int bg = eraseBg();
        for (int i = cursorCol; i < Math.min(cols, cursorCol + count); i++) {
            line.text[i] = ' ';
            line.fg[i] = defaultFg;
            line.bg[i] = bg;
            line.attr[i] = 0;
            line.clearExtra(i);
        }
        repairWide(line, cursorCol, cols - 1);
    }

    private void insertLines(int count) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) {
            return;
        }
        if (count < 1) {
            count = 1;
        }
        int available = scrollBottom - cursorRow + 1;
        if (count > available) {
            count = available;
        }
        System.arraycopy(lines, cursorRow, lines, cursorRow + count, available - count);
        for (int r = cursorRow; r < cursorRow + count; r++) {
            blankLine(r);
        }
    }

    private void deleteLines(int count) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) {
            return;
        }
        if (count < 1) {
            count = 1;
        }
        int available = scrollBottom - cursorRow + 1;
        if (count > available) {
            count = available;
        }
        System.arraycopy(lines, cursorRow + count, lines, cursorRow, available - count);
        for (int r = scrollBottom - count + 1; r <= scrollBottom; r++) {
            blankLine(r);
        }
    }
}
