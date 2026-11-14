package dev.dualterm.term;

import java.util.ArrayList;

/**
 * 一个够用就好的 VT100 / xterm 终端屏幕模型。
 *
 * 支持的序列：CUU/CUD/CUF/CUB/CNL/CPL/CHA/CUP/HVP/VPA、ED/EL/ECH/DCH/ICH、
 * IL/DL/SU/SD、SGR（16 色 / 256 色 / 真彩）、DECSTBM 滚动区、DECSET 1049 备用屏、
 * DECSET 25 光标可见、DECSET 1 应用方向键、OSC（整体忽略）、字符集选择（忽略）。
 *
 * 内部用「行对象数组 + 三套属性数组」存屏幕，滚动出屏的行进 scrollback。
 * 所有对外方法都加了 synchronized：PTY 读线程写、UI 线程读。
 */
public final class TerminalEmulator {

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

    /** 一行屏幕数据。text 存码点，0 表示被宽字符占用的第二格。 */
    public static final class Line {
        public final int[] text;
        public final int[] fg;
        public final int[] bg;
        public final byte[] attr;

        Line(int cols) {
            text = new int[cols];
            fg = new int[cols];
            bg = new int[cols];
            attr = new byte[cols];
        }

        void blank(int cols, int defaultFg, int defaultBg) {
            for (int i = 0; i < cols; i++) {
                text[i] = ' ';
                fg[i] = defaultFg;
                bg[i] = defaultBg;
                attr[i] = 0;
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
    private final int[] params = new int[16];
    private int paramCount;

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
        scrollTop = 0;
        scrollBottom = rows - 1;
        curFg = defaultFg;
        curBg = defaultBg;
        curAttr = 0;
        state = STATE_GROUND;
        csi.setLength(0);
        pendingLen = 0;
    }

    public synchronized void resize(int newCols, int newRows) {
        newCols = Math.max(10, newCols);
        newRows = Math.max(2, newRows);
        if (newCols == cols && newRows == rows) {
            return;
        }
        mainLines = reflow(mainLines, newCols, newRows);
        if (altLines != null) {
            altLines = reflow(altLines, newCols, newRows);
        }
        cols = newCols;
        rows = newRows;
        lines = altActive && altLines != null ? altLines : mainLines;
        scrollback.clear();
        if (cursorRow >= rows) {
            cursorRow = rows - 1;
        }
        if (cursorCol >= cols) {
            cursorCol = cols - 1;
        }
        scrollTop = 0;
        scrollBottom = rows - 1;
    }

    private Line[] reflow(Line[] old, int newCols, int newRows) {
        Line[] out = new Line[newRows];
        for (int r = 0; r < newRows; r++) {
            out[r] = new Line(newCols);
            out[r].blank(newCols, defaultFg, defaultBg);
            if (old != null && r < old.length) {
                int n = Math.min(newCols, old[r].text.length);
                System.arraycopy(old[r].text, 0, out[r].text, 0, n);
                System.arraycopy(old[r].fg, 0, out[r].fg, 0, n);
                System.arraycopy(old[r].bg, 0, out[r].bg, 0, n);
                System.arraycopy(old[r].attr, 0, out[r].attr, 0, n);
            }
        }
        return out;
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

    public synchronized String getText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < scrollback.size(); i++) {
            appendLineText(sb, scrollback.get(i));
        }
        for (int r = 0; r < rows; r++) {
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
                    state = STATE_GROUND;
                } else if (c == 0x1B) {
                    state = STATE_OSC_ESC;
                }
                break;
            case STATE_OSC_ESC:
                state = (c == '\\') ? STATE_GROUND : STATE_OSC;
                break;
            case STATE_CHARSET:
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
                if (cursorCol > 0) {
                    cursorCol--;
                }
                break;
            case 0x09:
                cursorCol = Math.min(cols - 1, (cursorCol / 8 + 1) * 8);
                break;
            case 0x0A:
            case 0x0B:
            case 0x0C:
                lineFeed();
                break;
            case 0x0D:
                cursorCol = 0;
                break;
            case 0x0E:
            case 0x0F:
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
                state = STATE_OSC;
                break;
            case '(':
            case ')':
            case '*':
            case '+':
                state = STATE_CHARSET;
                break;
            case '7':
                saveCursor();
                break;
            case '8':
                restoreCursor();
                break;
            case 'D':
                lineFeed();
                break;
            case 'E':
                cursorCol = 0;
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
            body = body.replace(':', ';');
            String[] parts = body.split(";", -1);
            for (String part : parts) {
                if (paramCount >= params.length) {
                    break;
                }
                params[paramCount++] = parseIntSafe(part);
            }
        }

        switch (finalChar) {
            case 'A':
                cursorRow = clamp(cursorRow - arg(0, 1), scrollTop, scrollBottom);
                break;
            case 'B':
                cursorRow = clamp(cursorRow + arg(0, 1), scrollTop, scrollBottom);
                break;
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
                cursorRow = clamp(arg(0, 1) - 1, 0, rows - 1);
                cursorCol = clamp(arg(1, 1) - 1, 0, cols - 1);
                break;
            case 'd':
                cursorRow = clamp(arg(0, 1) - 1, 0, rows - 1);
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
                applySgr();
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
            default:
                break;
        }
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
            } else if (mode == 25) {
                cursorVisible = enable;
            } else if (mode == 1049 || mode == 1047 || mode == 47) {
                switchAltScreen(enable);
            }
        }
    }

    private void switchAltScreen(boolean enable) {
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
        for (int i = 0; i < paramCount; i++) {
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

    private int extendedColor(int index, boolean foreground) {
        if (index + 1 >= paramCount) {
            return paramCount;
        }
        int mode = params[index + 1];
        if (mode == 5 && index + 2 < paramCount) {
            int color = PALETTE[params[index + 2] & 0xFF];
            if (foreground) {
                curFg = color;
            } else {
                curBg = color;
            }
            return index + 2;
        }
        if (mode == 2 && index + 4 < paramCount) {
            int color = 0xFF000000
                    | ((params[index + 2] & 0xFF) << 16)
                    | ((params[index + 3] & 0xFF) << 8)
                    | (params[index + 4] & 0xFF);
            if (foreground) {
                curFg = color;
            } else {
                curBg = color;
            }
            return index + 4;
        }
        return paramCount;
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
        if (cursorCol >= cols) {
            cursorCol = 0;
            lineFeed();
        }
        int width = charWidth(cp);
        Line line = lines[cursorRow];
        line.text[cursorCol] = cp;
        line.fg[cursorCol] = curFg;
        line.bg[cursorCol] = curBg;
        line.attr[cursorCol] = (byte) curAttr;
        cursorCol++;
        if (width == 2 && cursorCol < cols) {
            line.text[cursorCol] = 0;
            line.fg[cursorCol] = curFg;
            line.bg[cursorCol] = curBg;
            line.attr[cursorCol] = (byte) curAttr;
            cursorCol++;
        }
        if (cursorCol > cols) {
            cursorCol = cols;
        }
    }

    private static int charWidth(int cp) {
        if (cp == 0) {
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
                || (cp >= 0xFFE0 && cp <= 0xFFE6)) {
            return 2;
        }
        return 1;
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
            if (top == 0) {
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

    private void blankLine(int row) {
        lines[row].blank(cols, defaultFg, defaultBg);
    }

    /* -------------------- 擦除与插删 -------------------- */

    private void eraseDisplay(int mode) {
        if (mode == 0) {
            eraseLine(0);
            for (int r = cursorRow + 1; r < rows; r++) {
                blankLine(r);
            }
        } else if (mode == 1) {
            eraseLine(1);
            for (int r = 0; r < cursorRow; r++) {
                blankLine(r);
            }
        } else {
            for (int r = 0; r < rows; r++) {
                blankLine(r);
            }
            if (mode == 3) {
                scrollback.clear();
            }
        }
    }

    private void eraseLine(int mode) {
        Line line = lines[cursorRow];
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
        for (int i = from; i <= to && i < cols; i++) {
            if (i < 0) {
                continue;
            }
            line.text[i] = ' ';
            line.fg[i] = defaultFg;
            line.bg[i] = defaultBg;
            line.attr[i] = 0;
        }
    }

    private void eraseChars(int count) {
        Line line = lines[cursorRow];
        for (int i = 0; i < count && cursorCol + i < cols; i++) {
            line.text[cursorCol + i] = ' ';
            line.fg[cursorCol + i] = defaultFg;
            line.bg[cursorCol + i] = defaultBg;
            line.attr[cursorCol + i] = 0;
        }
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
        }
        for (int i = Math.max(cursorCol, cols - count); i < cols; i++) {
            line.text[i] = ' ';
            line.fg[i] = defaultFg;
            line.bg[i] = defaultBg;
            line.attr[i] = 0;
        }
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
        }
        for (int i = cursorCol; i < Math.min(cols, cursorCol + count); i++) {
            line.text[i] = ' ';
            line.fg[i] = defaultFg;
            line.bg[i] = defaultBg;
            line.attr[i] = 0;
        }
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
