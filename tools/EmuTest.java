import dev.dualterm.term.TerminalEmulator;

import java.nio.charset.StandardCharsets;

/** 桌面 JVM 上的仿真器回归：DEC 字符集 / 延迟换行 / DECAWM / CJK 占格 / 鼠标与光标形状。 */
public class EmuTest {

    static int fails = 0;

    static void check(String name, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "   -> " + detail));
        if (!ok) {
            fails++;
        }
    }

    static TerminalEmulator mk() {
        return new TerminalEmulator(10, 4, 100, 0xFFE6E6E6, 0xFF101014);
    }

    static void feed(TerminalEmulator e, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        e.append(b, b.length);
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    static String lineText(TerminalEmulator e, int row) {
        return textOf(e.getLine(row));
    }

    static String textOf(TerminalEmulator.Line line) {
        StringBuilder sb = new StringBuilder();
        for (int cp : line.text) {
            sb.appendCodePoint(cp == 0 ? ' ' : cp);
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        // 1) DEC 特殊图形字符集（vim 分屏边框 / listchars）
        TerminalEmulator e = mk();
        feed(e, "\u001b(0lqqqk\u001b(B");
        String line = lineText(e, 0);
        check("DEC charset maps line drawing",
                line.startsWith("┌───┐"),
                "got: " + line);

        // 2) 延迟换行：写满最后一列不立刻换行，下一个字符才换
        e = mk();
        feed(e, "0123456789");
        check("pending wrap: cursor stays in last col",
                e.getCursorRow() == 0 && e.getCursorCol() == 9,
                "row=" + e.getCursorRow() + " col=" + e.getCursorCol());
        feed(e, "A");
        check("pending wrap: next char wraps",
                e.getCursorRow() == 1 && lineText(e, 0).startsWith("0123456789")
                        && lineText(e, 1).startsWith("A"),
                "row0=" + lineText(e, 0) + " row1=" + lineText(e, 1));

        // 3) DECAWM off：写满后原地覆盖
        e = mk();
        feed(e, "\u001b[?7l0123456789AB");
        check("autowrap off overwrites last col",
                lineText(e, 0).startsWith("012345678B") && lineText(e, 1).charAt(0) == ' ',
                "row0=" + lineText(e, 0));
        feed(e, "\u001b[?7hXY");
        // 光标停在最后一格时先覆盖写入，再进入 pending wrap；下一个字符才换行
        check("autowrap back on",
                lineText(e, 0).startsWith("012345678X") && e.getCursorRow() == 1
                        && lineText(e, 1).startsWith("Y"),
                "row0=" + lineText(e, 0) + " row1=" + lineText(e, 1) + " row=" + e.getCursorRow());

        // 4) CJK 占两格，占位格为 0
        e = mk();
        feed(e, "中文a");
        TerminalEmulator.Line l = e.getLine(0);
        check("CJK width",
                l.text[0] == '中' && l.text[1] == 0 && l.text[2] == '文'
                        && l.text[3] == 0 && l.text[4] == 'a' && e.getCursorCol() == 5,
                "col=" + e.getCursorCol());

        // 5) CJK + 行尾：宽字符只剩一格时先换行再画
        e = mk();
        feed(e, "\u001b[1;10H中");
        check("wide char at last col wraps first",
                e.getCursorRow() == 1 && lineText(e, 1).startsWith("中") && e.getCursorCol() == 2,
                "row=" + e.getCursorRow() + " row1=" + lineText(e, 1) + " col=" + e.getCursorCol());

        // 6) DECSCUSR
        e = mk();
        feed(e, "\u001b[6 q");
        check("DECSCUSR", e.getCursorShape() == 6, "shape=" + e.getCursorShape());

        // 7) 鼠标模式与 SGR 上报
        e = mk();
        feed(e, "\u001b[?1002h\u001b[?1006h");
        check("mouse tracking on", e.isMouseTracking() && e.isMouseSgr(), "");
        check("mouse press", "\u001b[<0;5;3M".equals(e.mouseReport(0, 5, 3, false, false)),
                "got: " + e.mouseReport(0, 5, 3, false, false));
        check("mouse motion", "\u001b[<32;5;3M".equals(e.mouseReport(0, 5, 3, false, true)),
                "got: " + e.mouseReport(0, 5, 3, false, true));
        check("mouse release", "\u001b[<0;5;3m".equals(e.mouseReport(0, 5, 3, true, false)),
                "got: " + e.mouseReport(0, 5, 3, true, false));
        feed(e, "\u001b[?1002l\u001b[?1006l");
        check("mouse off -> null", e.mouseReport(0, 5, 3, false, false) == null, "");

        // 8) 焦点上报开关
        e = mk();
        feed(e, "\u001b[?1004h");
        check("focus report on", e.isFocusReport(), "");
        feed(e, "\u001b[?1004l");
        check("focus report off", !e.isFocusReport(), "");

        // 9) 备用屏滚动不进 scrollback
        e = mk();
        for (int i = 0; i < 20; i++) {
            feed(e, "line" + i + "\r\n");
        }
        int before = e.getScrollbackCount();
        check("scrollback grows on main screen", before > 0, "count=" + before);
        feed(e, "\u001b[?1049h");
        for (int i = 0; i < 10; i++) {
            feed(e, "alt" + i + "\r\n");
        }
        check("alt screen keeps scrollback clean",
                e.getScrollbackCount() == before,
                "count=" + e.getScrollbackCount() + " before=" + before);
        feed(e, "\u001b[?1049l");
        check("main screen restored",
                lineText(e, 0).startsWith("line"),
                "row0=" + lineText(e, 0));

        // 10) SO/SI 切换 G1
        e = mk();
        feed(e, "\u001b)0\u000el\u000f");
        check("SO/SI selects G1 charset",
                lineText(e, 0).startsWith("┌"),
                "row0=" + lineText(e, 0));

        // 11) CPR 依旧正确（含 pendingWrap 状态下）
        e = mk();
        feed(e, "\u001b[3;5H\u001b[6n");
        check("CPR after cursor move", cprOf(e).equals("3;5"), "reply=" + cprOf(e));

        // 12) 变矮：光标（提示符）必须留在屏幕上，被裁掉的行进 scrollback
        e = mk();
        for (int i = 0; i < 8; i++) {
            feed(e, "L" + i + "\r\n");
        }
        int sbBefore = e.getScrollbackCount();
        e.resize(10, 2);
        check("shrink keeps cursor visible",
                e.getCursorRow() < 2 && e.getCursorRow() >= 0,
                "row=" + e.getCursorRow());
        check("shrink preserves scrollback",
                e.getScrollbackCount() >= sbBefore,
                "count=" + e.getScrollbackCount() + " before=" + sbBefore);

        // 13) 变高：内容不跟着跳，空白补在下面（xterm 行为）
        e = mk();
        feed(e, "top\r\n");
        e.resize(10, 6);
        check("grow keeps content anchored at top",
                lineText(e, 0).startsWith("top") && lineText(e, 5).trim().isEmpty(),
                "row0=" + lineText(e, 0));

        // 14) ESC # 8（DECALN）整屏填 E，且参数字节不能漏成可见字符
        e = mk();
        feed(e, "\u001b#8");
        check("DECALN fills screen with E",
                lineText(e, 0).equals("EEEEEEEEEE") && lineText(e, 3).equals("EEEEEEEEEE"),
                "row0=" + lineText(e, 0));

        // 15) ESC % G（选 UTF-8）不能把 G 打到屏幕上
        e = mk();
        feed(e, "\u001b%Gab");
        check("ESC % G consumes its argument",
                lineText(e, 0).startsWith("ab"),
                "row0=" + lineText(e, 0));

        // 16) DECSTR 软复位：SGR、滚动区、自动换行都回到默认
        e = mk();
        feed(e, "\u001b[41;1mX");                 // X 带红底 + 粗体
        feed(e, "\u001b[2;3r\u001b[?7l");          // 设滚动区、关自动换行
        feed(e, "\u001b[1;1H\u001b[!pY");          // 软复位后写 Y
        TerminalEmulator.Line l0 = e.getLine(0);
        check("DECSTR resets SGR", l0.bg[1] == 0xFF101014 && l0.attr[1] == 0,
                "bg=" + Integer.toHexString(l0.bg[1]) + " attr=" + l0.attr[1]);
        check("DECSTR re-enables autowrap", e.isAutowrap(), "");

        e = mk();
        feed(e, "A\r\nB\r\nC\r\nD");
        feed(e, "\u001b[2;3r");
        feed(e, "\u001b[!p");
        feed(e, "\u001b[4;1H\n");                   // 在最后一行换行：滚动区若已复位，整屏滚
        check("DECSTR resets scroll region",
                lineText(e, 0).startsWith("B"),
                "row0=" + lineText(e, 0));

        // 17) 原点模式：CUP 的行号相对滚动区顶部
        e = mk();
        feed(e, "\u001b[2;4r\u001b[?6h\u001b[1;1HX");
        check("origin mode: CUP row relative to margin",
                lineText(e, 1).startsWith("X"),
                "row1=" + lineText(e, 1) + " row0=" + lineText(e, 0));
        feed(e, "\u001b[?6l\u001b[1;1HY");
        check("origin mode off: CUP back to absolute",
                lineText(e, 0).startsWith("Y"),
                "row0=" + lineText(e, 0));

        // 18) SGR 冒号式真彩：38:2::R:G:B（第二个子参数是色彩空间，常为空）
        e = mk();
        feed(e, "\u001b[38:2::255:0:0mX");
        check("SGR colon truecolor",
                e.getLine(0).fg[0] == 0xFFFF0000,
                "fg=" + Integer.toHexString(e.getLine(0).fg[0]));

        // 19) CSI > 4 ; 2 m 是 modifyOtherKeys，不能当成 SGR
        e = mk();
        feed(e, "\u001b[>4;2mX");
        check("modifyOtherKeys is not SGR",
                e.getLine(0).attr[0] == 0,
                "attr=" + e.getLine(0).attr[0]);

        // 20) 制表位：HTS 设点、TBC 清全部、CHT 右跳
        e = mk();
        feed(e, "\u001b[4G\u001bH\u001b[1;1H\tX");
        check("HTS sets a custom tab stop",
                lineText(e, 0).charAt(3) == 'X',
                "row0=[" + lineText(e, 0) + "]");
        e = mk();
        feed(e, "\u001b[3g\tX");
        check("TBC 3 clears all tab stops",
                lineText(e, 0).charAt(9) == 'X',
                "row0=[" + lineText(e, 0) + "]");

        // 21) 组合字符：声调不占格，挂在前一格
        e = mk();
        feed(e, "e\u0301x");
        check("combining mark merges into previous cell",
                e.getCursorCol() == 2
                        && "\u0301".equals(e.getLine(0).extraAt(0))
                        && lineText(e, 0).startsWith("ex"),
                "col=" + e.getCursorCol() + " extra=" + e.getLine(0).extraAt(0));

        // 22) 删掉宽字符的一半时，另一半也不能留在屏幕上
        e = mk();
        feed(e, "\u4e2d\u6587\u001b[1;4H\u001b[1P");
        TerminalEmulator.Line lw = e.getLine(0);
        check("DCH does not leave half a wide char",
                lw.text[2] == ' ' && lw.text[3] == ' ',
                "text2=" + lw.text[2] + " text3=" + lw.text[3]);

        // 23) BCE：\e[K 用当前背景色擦（xterm-256color 的 bce）
        e = mk();
        feed(e, "\u001b[44m\u001b[K");
        check("BCE erases with current background",
                e.getLine(0).bg[0] == 0xFF2472C8,
                "bg=" + Integer.toHexString(e.getLine(0).bg[0]));

        // 24) 变窄时宽字符正好卡在边界上：整个字符挪到续行，不留半个字形
        TerminalEmulator w = new TerminalEmulator(20, 4, 100, 0xFFE6E6E6, 0xFF101014);
        feed(w, "012345678中文abc");            // 中 的头在第 9 列
        w.resize(10, 4);
        TerminalEmulator.Line lw0 = w.getLine(0);
        TerminalEmulator.Line lw1 = w.getLine(1);
        check("shrink width repairs split wide char",
                lw0.text[8] == '8'
                        && lw1.text[0] == '中' && lw1.text[1] == 0
                        && lw1.text[2] == '文' && lw1.text[3] == 0,
                "r0_8=" + lw0.text[8] + " r1=" + lw1.text[0] + "," + lw1.text[1]
                        + "," + lw1.text[2] + "," + lw1.text[3]);

        // 25) DL（删除行）在子滚动区里必须真正"上移+底部补空"，
        //     且不能因为 arraycopy 搬引用 + 原地 blank 让行对象别名共享
        //     （曾实测：vim 滚轮每个 tick 发 DL 3，屏幕内容以 3 行为周期重复平铺）
        TerminalEmulator d = new TerminalEmulator(8, 10, 100, 0xFFE6E6E6, 0xFF101014);
        for (int r = 1; r <= 10; r++) {
            feed(d, "\u001b[" + r + ";1HR" + r);
        }
        feed(d, "\u001b[1;9r\u001b[1;1H\u001b[3M\u001b[1;10r");
        String[] afterDl = new String[10];
        for (int r = 0; r < 10; r++) {
            afterDl[r] = lineText(d, r).trim();
        }
        check("DL3 in region 1..9 shifts tail up, keeps row 10",
                afterDl[0].equals("R4") && afterDl[3].equals("R7")
                        && afterDl[6].isEmpty() && afterDl[9].equals("R10"),
                java.util.Arrays.toString(afterDl));
        feed(d, "\u001b[7;1HNEW7\u001b[8;1HNEW8\u001b[9;1HNEW9");
        check("DL vacated rows do not alias shifted rows",
                lineText(d, 3).trim().equals("R7")
                        && lineText(d, 6).trim().equals("NEW7")
                        && !lineText(d, 3).contains("NEW"),
                "r4=" + lineText(d, 3).trim() + " r7=" + lineText(d, 6).trim());

        // 26) IL（插入行）同样：下方内容下移、末尾行被挤掉，不能出现别名
        TerminalEmulator il = new TerminalEmulator(8, 10, 100, 0xFFE6E6E6, 0xFF101014);
        for (int r = 1; r <= 10; r++) {
            feed(il, "\u001b[" + r + ";1HR" + r);
        }
        feed(il, "\u001b[1;9r\u001b[1;1H\u001b[2L\u001b[1;10r");
        // 区域 1..9 里插 2 行：头两行空，R1..R7 下移，R8/R9 被挤出区域，row10 不动
        check("IL2 in region 1..9 shifts down, drops region bottom",
                lineText(il, 0).trim().isEmpty()
                        && lineText(il, 1).trim().isEmpty()
                        && lineText(il, 2).trim().equals("R1")
                        && lineText(il, 8).trim().equals("R7")
                        && lineText(il, 9).trim().equals("R10"),
                "r1=" + lineText(il, 0).trim() + " r3=" + lineText(il, 2).trim()
                        + " r9=" + lineText(il, 8).trim());
        feed(il, "\u001b[1;1HT0P");
        check("IL vacated rows do not alias shifted rows",
                lineText(il, 2).trim().equals("R1") && lineText(il, 0).trim().equals("T0P"),
                "r1=" + lineText(il, 0).trim() + " r3=" + lineText(il, 2).trim());

        // 27) reflow：变窄时右端内容折成续行（不截断），变宽时拼回
        TerminalEmulator rf = new TerminalEmulator(20, 6, 100, 0xFFE6E6E6, 0xFF101014);
        feed(rf, "0123456789ABCDEFGH1234567890abcd");          // 32 列 → 折成 20+12
        check("27a wrap at 20 cols",
                lineText(rf, 0).startsWith("0123456789ABCDEFGH12")
                        && lineText(rf, 1).startsWith("34567890abcd"),
                "r0=" + lineText(rf, 0) + " r1=" + lineText(rf, 1));
        rf.resize(10, 6);
        // 内容不足一屏：底部对齐推出顶部对齐，32 字符折成 10+10+10+2 四块
        String r0 = lineText(rf, 0).trim();
        String r1 = lineText(rf, 1).trim();
        String r2 = lineText(rf, 2).trim();
        String r3 = lineText(rf, 3).trim();
        check("27b narrow folds into wrapped rows",
                r0.equals("0123456789") && r1.equals("ABCDEFGH12")
                        && r2.equals("34567890ab") && r3.equals("cd"),
                "r0=" + r0 + " r1=" + r1 + " r2=" + r2 + " r3=" + r3);
        rf.resize(20, 6);
        check("27c widen rejoins wrapped lines",
                lineText(rf, 0).startsWith("0123456789ABCDEFGH12")
                        && lineText(rf, 1).startsWith("34567890abcd"),
                "r0=" + lineText(rf, 0) + " r1=" + lineText(rf, 1));

        // 28) 显式 \r\n 结束的行不能被 reflow 误拼
        TerminalEmulator ex = new TerminalEmulator(20, 6, 100, 0xFFE6E6E6, 0xFF101014);
        feed(ex, "ab\r\ncd");
        ex.resize(10, 6);
        ex.resize(20, 6);
        check("explicit newline rows stay separate",
                lineText(ex, 0).startsWith("ab") && lineText(ex, 1).startsWith("cd")
                        && lineText(ex, 1).charAt(2) == ' ',
                "r0=" + lineText(ex, 0) + " r1=" + lineText(ex, 1));

        // 29) 转屏往返（宽→窄→宽）内容零丢失：长行 + 显式换行混合
        TerminalEmulator rot = new TerminalEmulator(40, 10, 100, 0xFFE6E6E6, 0xFF101014);
        StringBuilder longLine = new StringBuilder();
        for (int i = 0; i < 90; i++) {
            longLine.append((char) ('A' + i % 26));
        }
        feed(rot, longLine + "\r\nprompt$ ");
        String tail = longLine.substring(78);                 // 前 78 列进 scrollback（10 行屏）
        rot.resize(20, 10);
        rot.resize(40, 10);
        // 全量文本：屏幕 + scrollback 拼起来必须包含整条长行
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < rot.getScrollbackCount(); i++) {
            all.append(textOf(rot.getScrollbackLine(i)));
        }
        for (int r = 0; r < 10; r++) {
            all.append(textOf(rot.getLine(r)));
        }
        check("rotation roundtrip loses nothing",
                all.toString().contains(longLine.toString())
                        && all.toString().contains("prompt$ "),
                "sb=" + rot.getScrollbackCount() + " all=" + all);
        check("rotation roundtrip tail intact",
                all.toString().contains(tail + "prompt$ ")
                        || all.toString().replace(" ", "").contains((tail + "prompt$ ").replace(" ", "")),
                "tail=" + tail);

        // 30) 转屏往返不能在行之间插入空白行：屏幕行是满宽的（尾部大量填充空格），
        //     reflow 若把填充空格当内容，窄屏下每个短行后面都会折出整行空白的续行
        TerminalEmulator gap = new TerminalEmulator(131, 23, 200, 0xFFE6E6E6, 0xFF101014);
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            body.append("help line ").append(i).append("\r\n");
        }
        body.append("$ ");
        feed(gap, body.toString());
        gap.resize(62, 60);
        StringBuilder portrait = new StringBuilder();
        int nonEmpty = 0;
        for (int r = 0; r < gap.getRows(); r++) {
            String s = lineText(gap, r).trim();
            if (!s.isEmpty()) {
                portrait.append(s).append('|');
                nonEmpty++;
            }
        }
        check("portrait reflow inserts no blank rows",
                portrait.toString().equals("help line 0|help line 1|help line 2|help line 3|"
                        + "help line 4|help line 5|$|") && nonEmpty == 7,
                "rows=" + nonEmpty + " [" + portrait + "]");
        gap.resize(131, 23);
        StringBuilder back = new StringBuilder();
        for (int r = 0; r < 7; r++) {
            back.append(lineText(gap, r).trim()).append('|');
        }
        check("landscape reflow keeps rows adjacent",
                back.toString().equals("help line 0|help line 1|help line 2|help line 3|"
                        + "help line 4|help line 5|$|"),
                "[" + back + "]");

        // 31) 用户敲的空回车（真正的空行）在重排后必须还在
        TerminalEmulator eb = new TerminalEmulator(40, 8, 100, 0xFFE6E6E6, 0xFF101014);
        feed(eb, "a\r\n\r\nb");
        eb.resize(10, 8);
        eb.resize(40, 8);
        check("empty line survives reflow",
                lineText(eb, 0).trim().equals("a")
                        && lineText(eb, 1).trim().isEmpty()
                        && lineText(eb, 2).trim().equals("b"),
                "r0=[" + lineText(eb, 0).trim() + "] r1=[" + lineText(eb, 1).trim()
                        + "] r2=[" + lineText(eb, 2).trim() + "]");

        // 32) 光标停在行尾空格里时，那段空格不能被当成填充剥掉（否则光标位置会错）
        TerminalEmulator ec = new TerminalEmulator(20, 4, 100, 0xFFE6E6E6, 0xFF101014);
        feed(ec, "abc   ");
        ec.resize(20, 4);                                   // 同宽不同高，走 resizeHeight
        ec.resize(40, 4);                                   // 变宽：abc + 3 空格保留，光标在第 6 列
        check("cursor in trailing spaces keeps position",
                lineText(ec, 0).trim().equals("abc") && ec.getCursorCol() == 6,
                "r0=[" + lineText(ec, 0).trim() + "] col=" + ec.getCursorCol());

        // 33) 粘贴净化：CRLF 归一、控制字符剔除、TAB 保留、上限截断
        check("sanitize normalizes CRLF to CR",
                dev.dualterm.util.PasteSanitize.sanitize("a\r\nb").equals("a\rb")
                        && dev.dualterm.util.PasteSanitize.sanitize("a\nb\nc").equals("a\rb\rc"),
                "[" + dev.dualterm.util.PasteSanitize.sanitize("a\r\nb") + "]");
        // ESC 剔除后剩下的可见字符保留（xterm paste-controls=off 同款）
        check("sanitize strips escape sequences",
                dev.dualterm.util.PasteSanitize.sanitize("\u001b[201~abc\u001b]0;x").equals("[201~abc]0;x"),
                "[" + dev.dualterm.util.PasteSanitize.sanitize("\u001b[201~abc\u001b]0;x") + "]");
        check("sanitize keeps tab drops DEL",
                dev.dualterm.util.PasteSanitize.sanitize("a\tb\u007fc").equals("a\tbc"),
                "[" + dev.dualterm.util.PasteSanitize.sanitize("a\tb\u007fc") + "]");
        check("sanitize counts CRLF lines once",
                dev.dualterm.util.PasteSanitize.countLines(dev.dualterm.util.PasteSanitize.sanitize("a\r\nb\r\nc")) == 3,
                "lines=" + dev.dualterm.util.PasteSanitize.countLines(dev.dualterm.util.PasteSanitize.sanitize("a\r\nb\r\nc")));
        check("sanitize truncates oversize paste",
                dev.dualterm.util.PasteSanitize.sanitize(repeat('a', 600 * 1024)).length()
                        == dev.dualterm.util.PasteSanitize.MAX_LENGTH,
                "len=" + dev.dualterm.util.PasteSanitize.sanitize(repeat('a', 600 * 1024)).length());

        // 34) 复制裁尾部空行：整屏只用前几行时，后面是屏幕填充不是内容
        TerminalEmulator ct = new TerminalEmulator(20, 10, 100, 0xFFE6E6E6, 0xFF101014);
        feed(ct, "line1\r\nline2");
        check("copy screen trims trailing blank rows",
                ct.getScreenText().equals("line1\nline2\n"),
                "[" + ct.getScreenText() + "]");

        // 35) 中间空行（用户敲的空回车）必须保留
        feed(ct, "\r\n\r\nafter-blank");
        check("copy keeps interior blank lines",
                ct.getScreenText().equals("line1\nline2\n\nafter-blank\n"),
                "[" + ct.getScreenText() + "]");

        // 36) getText 同样裁尾部空行、保留中间空行
        TerminalEmulator cg = new TerminalEmulator(20, 4, 100, 0xFFE6E6E6, 0xFF101014);
        feed(cg, "top\r\n\r\nbottom");
        check("copy all keeps interior blanks trims tail",
                cg.getText().equals("top\n\nbottom\n"),
                "[" + cg.getText() + "]");

        System.out.println(fails == 0 ? "ALL PASS" : fails + " FAILURES");
        System.exit(fails == 0 ? 0 : 1);
    }

    static String cprOf(TerminalEmulator e) {
        final String[] out = {""};
        e.setReplySink(new TerminalEmulator.ReplySink() {
            @Override
            public void onReply(String reply) {
                out[0] = reply.replace("\u001b[", "").replace("R", "");
            }
        });
        feed(e, "\u001b[6n");
        return out[0];
    }
}
