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

    static String lineText(TerminalEmulator e, int row) {
        TerminalEmulator.Line line = e.getLine(row);
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

        // 24) 变窄时宽字符正好卡在边界上（曾因拿旧列数做边界而越界崩溃）
        TerminalEmulator w = new TerminalEmulator(20, 4, 100, 0xFFE6E6E6, 0xFF101014);
        feed(w, "012345678中文abc");            // 中 的头在第 9 列
        w.resize(10, 4);
        TerminalEmulator.Line lw2 = w.getLine(0);
        check("shrink width repairs split wide char",
                lw2.text[8] == '8' && lw2.text[9] == ' ',
                "t8=" + lw2.text[8] + " t9=" + lw2.text[9]);

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
