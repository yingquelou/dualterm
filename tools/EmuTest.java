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
