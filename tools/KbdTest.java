import dev.dualterm.ui.KbdLayout;
import dev.dualterm.ui.StepMode;

/**
 * StepMode 桌面回归（纯逻辑，可离线跑）：自定义键步骤的六个模式，
 * 每个模式验五件套——validate / normalize / bytesOf / autoLabel / hasInput。
 *
 * 这是分派收敛后的防回归网：历史 bug「回车步骤落进文本校验、错误标在
 * 隐藏输入框上导致对话框卡死」就在 validate 分派上，此处逐模式锁定。
 *
 * SoftKeyboard 本体依赖 Context/View/Handler，留真机验证（与 RegistryTest
 * 同一限制）；本类只测枚举常量上的纯函数。
 */
public class KbdTest {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) {
        enumShape();
        textCases();
        ctrlCases();
        escCases();
        hexCases();
        retCases();
        delayCases();
        rowsCases();

        System.out.println();
        System.out.println("PASS " + pass + "  FAIL " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    // ---- 断言工具 ----

    static void eq(Object actual, Object expected, String what) {
        if (!actual.equals(expected)) {
            fail++;
            System.out.println("FAIL " + what + ": expected=" + expected
                    + " actual=" + actual);
        } else {
            pass++;
        }
    }

    static void ok(boolean cond, String what) {
        if (!cond) {
            fail++;
            System.out.println("FAIL " + what);
        } else {
            pass++;
        }
    }

    static void err(String validateResult, String expectedFragment, String what) {
        ok(validateResult != null && validateResult.contains(expectedFragment),
                what + "（应报错含「" + expectedFragment + "」，实际="
                + validateResult + "）");
    }

    static void bytes(byte[] actual, int[] expected, String what) {
        if (actual.length != expected.length) {
            fail++;
            System.out.println("FAIL " + what + ": length " + actual.length
                    + " != " + expected.length);
            return;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((actual[i] & 0xff) != expected[i]) {
                fail++;
                System.out.println("FAIL " + what + ": byte[" + i + "]="
                        + (actual[i] & 0xff) + " expected " + expected[i]);
                return;
            }
        }
        pass++;
    }

    // ---- 枚举形状 ----

    static void enumShape() {
        eq(StepMode.values().length, 6, "模式数量");
        // Spinner 下标 = ordinal，label 不得为空（否则下拉显示空行）
        for (StepMode m : StepMode.values()) {
            ok(!m.label.isEmpty(), m.name() + ".label 非空");
        }
        eq(StepMode.from("text"), StepMode.TEXT, "from 小写兼容旧格式");
        eq(StepMode.from("HEX"), StepMode.HEX, "from 忽略大小写");
        ok(StepMode.from("bogus") == null, "未知模式返回 null");
    }

    // ---- TEXT ----

    static void textCases() {
        StepMode m = StepMode.TEXT;
        ok(m.validate("ls -la") == null, "text 非空合法");
        err(m.validate("  "), "需要发送内容", "text 空白");
        err(m.validate(""), "需要发送内容", "text 空");
        eq(m.normalize("ls -la"), "ls -la", "text normalize 原样");
        bytes(m.bytesOf("ok"), new int[]{'o', 'k'}, "text ascii");
        bytes(m.bytesOf("€"), new int[]{0xe2, 0x82, 0xac}, "text UTF-8 多字节");
        bytes(m.bytesOf("你"), new int[]{0xe4, 0xbd, 0xa0}, "text 中文");
        eq(m.autoLabel("ls -la"), "ls -la", "text autoLabel");
        ok(m.hasInput(), "text 有输入框");
    }

    // ---- CTRL ----

    static void ctrlCases() {
        StepMode m = StepMode.CTRL;
        ok(m.validate("A") == null, "ctrl 字母合法");
        ok(m.validate("\\") == null, "ctrl 反斜杠合法");
        ok(m.validate("^a") == null, "ctrl 带前缀合法");
        err(m.validate("AB"), "单个字母", "ctrl 两字符");
        err(m.validate("1"), "单个字母", "ctrl 数字");
        err(m.validate(""), "单个字母", "ctrl 空");
        eq(m.normalize("^a"), "A", "ctrl 剥前缀+大写");
        eq(m.normalize("z"), "Z", "ctrl 大写");
        eq(m.normalize("\\"), "\\", "ctrl 反斜杠原样");
        bytes(m.bytesOf("A"), new int[]{0x01}, "ctrl A=^A");
        bytes(m.bytesOf("Z"), new int[]{0x1a}, "ctrl Z=^Z");
        bytes(m.bytesOf("\\"), new int[]{0x1c}, "ctrl \\=^\\(FS)");
        eq(m.autoLabel("u"), "^U", "ctrl autoLabel");
        ok(m.hasInput(), "ctrl 有输入框");
    }

    // ---- ESC ----

    static void escCases() {
        StepMode m = StepMode.ESC;
        ok(m.validate("") == null, "esc 留空合法（只发 ESC）");
        ok(m.validate("[H") == null, "esc 序列合法");
        eq(m.normalize("[15~"), "[15~", "esc normalize 原样");
        bytes(m.bytesOf(""), new int[]{0x1b}, "esc 留空=裸 ESC");
        bytes(m.bytesOf("[H"), new int[]{0x1b, '[', 'H'}, "esc [H");
        eq(m.autoLabel(""), "ESC", "esc 留空标签");
        eq(m.autoLabel("[H"), "[H", "esc 序列标签");
        ok(m.hasInput(), "esc 有输入框");
    }

    // ---- HEX ----

    static void hexCases() {
        StepMode m = StepMode.HEX;
        ok(m.validate("00") == null, "hex 00 合法");
        ok(m.validate("7f") == null, "hex 7f 合法");
        ok(m.validate("F") == null, "hex 单位合法");
        err(m.validate("1g"), "十六进制", "hex 非法字符");
        err(m.validate("123"), "十六进制", "hex 三位");
        err(m.validate(""), "十六进制", "hex 空");
        eq(m.normalize("F"), "0f", "hex 单位补零");
        eq(m.normalize("FF"), "ff", "hex 大写转小写");
        bytes(m.bytesOf("00"), new int[]{0x00}, "hex 00=NUL");
        bytes(m.bytesOf("7f"), new int[]{0x7f}, "hex 7f=DEL");
        bytes(m.bytesOf("1b"), new int[]{0x1b}, "hex 1b=ESC");
        bytes(m.bytesOf("ff"), new int[]{0xff}, "hex ff 边界");
        eq(m.autoLabel("f"), "0x0f", "hex autoLabel 用规范化值");
        ok(m.hasInput(), "hex 有输入框");
    }

    // ---- RET（历史 bug 锁定：校验不得落进 text 分支） ----

    static void retCases() {
        StepMode m = StepMode.RET;
        ok(m.validate("") == null, "ret 空值合法（输入框隐藏，值为空串）");
        eq(m.normalize(""), "", "ret normalize 空串");
        bytes(m.bytesOf(""), new int[]{0x0d}, "ret=CR");
        eq(m.autoLabel(""), "↵", "ret autoLabel");
        ok(!m.hasInput(), "ret 无输入框（修复对话框卡死的关键）");
    }

    // ---- DELAY ----

    static void delayCases() {
        StepMode m = StepMode.DELAY;
        ok(m.validate("300") == null, "delay 300 合法");
        ok(m.validate(" 1 ") == null, "delay 带空白合法");
        ok(m.validate("10000") == null, "delay 上界合法");
        ok(m.validate("1") == null, "delay 下界合法");
        err(m.validate("0"), "1–10000", "delay 低于下界");
        err(m.validate("10001"), "1–10000", "delay 超上界");
        err(m.validate("abc"), "毫秒数", "delay 非数字");
        err(m.validate(""), "毫秒数", "delay 空");
        eq(m.normalize(" 300 "), "300", "delay normalize 去空白");
        eq(m.autoLabel("300"), "⏱300", "delay autoLabel");
        bytes(m.bytesOf("300"), new int[]{}, "delay 不产出字节");
        ok(m.hasInput(), "delay 有输入框");
    }

    // ---- 可自定义区行数上限（C13：键不限量，超过上限竖向滚动） ----

    static void rowsCases() {
        eq(KbdLayout.visibleCustomRows(3, 0), 3, "上限不限：全展开");
        eq(KbdLayout.visibleCustomRows(3, -1), 3, "负上限按不限处理");
        eq(KbdLayout.visibleCustomRows(6, 4), 4, "6 行超上限 4 → 取 4");
        eq(KbdLayout.visibleCustomRows(4, 4), 4, "恰好等于上限：不滚");
        eq(KbdLayout.visibleCustomRows(2, 4), 2, "未到上限：取实际行数");
        eq(KbdLayout.visibleCustomRows(0, 4), 0, "无键：0 行");
    }
}
