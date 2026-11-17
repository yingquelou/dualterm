package dev.dualterm.ui;

import java.nio.charset.StandardCharsets;

/**
 * 自定义键步骤的模式：一个步骤 = 模式 + 原始值字符串，模式决定值怎么校验、
 * 怎么变成字节、怎么显示。
 *
 * 六个分派点（校验/规范化/字节变换/输入框提示/自动标签/是否有内容可填）
 * 全部收敛在枚举常量上——新增模式只改这里，编译器穷尽检查，不会再现
 * 「回车步骤落进文本校验报不可见的错」那类分派遗漏。
 *
 * 所有方法都是纯函数（不碰 Context/View/Handler）：桌面回归（tools/KbdTest）
 * 直接逐模式验证；发送时的延迟调度在 SoftKeyboard.runSteps，不属于本类。
 */
public enum StepMode {

    /** 文本：UTF-8 原样直发，任意 Unicode、可整段（命令片段）。 */
    TEXT("文本") {
        @Override
        public byte[] bytesOf(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String validate(String value) {
            return value.trim().isEmpty() ? "需要发送内容" : null;
        }

        @Override
        public String normalize(String value) {
            return value;
        }

        @Override
        public String hint() {
            return "原样发送，可整段/任意 Unicode（如 ls -la）";
        }

        @Override
        public String autoLabel(String value) {
            return value;
        }
    },

    /** ^X：控制字符，填单个字母或 \（如 A 发 ^A、\ 发 ^\）。 */
    CTRL("^X") {
        @Override
        public byte[] bytesOf(String value) {
            char c = value.toUpperCase().charAt(0);
            return new byte[]{(byte) (c & 0x1f)};
        }

        @Override
        public String validate(String value) {
            String v = stripCaret(value);
            if (v.length() != 1
                    || !(Character.isLetter(v.charAt(0)) || v.charAt(0) == '\\')) {
                return "填单个字母或 \\";
            }
            return null;
        }

        @Override
        public String normalize(String value) {
            return stripCaret(value).toUpperCase();
        }

        @Override
        public String hint() {
            return "单个字母或 \\（如 A 发 ^A）";
        }

        @Override
        public String autoLabel(String value) {
            return "^" + value.toUpperCase();
        }

        /** 接受 "^a" 这类带前缀的写法，剥掉前缀留字母。 */
        private static String stripCaret(String value) {
            String v = value.trim();
            return v.length() == 2 && v.charAt(0) == '^' ? v.substring(1) : v;
        }
    },

    /** ESC 序列：发 ESC + 值；值可留空 = 只发一个裸 ESC。 */
    ESC("ESC") {
        @Override
        public byte[] bytesOf(String value) {
            byte[] tail = value.getBytes(StandardCharsets.UTF_8);
            byte[] out = new byte[1 + tail.length];
            out[0] = 0x1b;
            System.arraycopy(tail, 0, out, 1, tail.length);
            return out;
        }

        @Override
        public String validate(String value) {
            return null;   // 允许留空 = 只发一个 ESC
        }

        @Override
        public String normalize(String value) {
            return value;
        }

        @Override
        public String hint() {
            return "ESC 后面的部分（如 [H），可留空只发 ESC";
        }

        @Override
        public String autoLabel(String value) {
            return value.isEmpty() ? "ESC" : value;
        }
    },

    /** 字节：1–2 位十六进制发单个原始字节（00=NUL、7f=DEL、1b=裸 ESC）。 */
    HEX("字节") {
        @Override
        public byte[] bytesOf(String value) {
            return new byte[]{(byte) Integer.parseInt(value, 16)};
        }

        @Override
        public String validate(String value) {
            return value.trim().matches("[0-9a-fA-F]{1,2}")
                    ? null : "填 1–2 位十六进制（00–ff）";
        }

        @Override
        public String normalize(String value) {
            String v = value.trim().toLowerCase();
            return v.length() == 1 ? "0" + v : v;
        }

        @Override
        public String hint() {
            return "两位十六进制（如 7f=DEL、1b=ESC、00=NUL）";
        }

        @Override
        public String autoLabel(String value) {
            return "0x" + normalize(value);
        }
    },

    /** 回车：CR(0x0d)，与物理键盘 Enter 同字节；没有内容可填。 */
    RET("回车") {
        @Override
        public byte[] bytesOf(String value) {
            return new byte[]{0x0d};
        }

        @Override
        public String validate(String value) {
            return null;   // 没有内容可填，永远合法
        }

        @Override
        public String normalize(String value) {
            return "";
        }

        @Override
        public boolean hasInput() {
            return false;
        }

        @Override
        public String hint() {
            return "";
        }

        @Override
        public String autoLabel(String value) {
            return "↵";
        }
    },

    /** 延迟：毫秒（1–10000），冲刷已攒字节、等 N 毫秒后继续——宏节拍。 */
    DELAY("延迟") {
        @Override
        public byte[] bytesOf(String value) {
            return new byte[0];   // 不产出字节；runSteps 在此之前走延迟分支
        }

        @Override
        public String validate(String value) {
            String v = value.trim();
            if (!v.matches("[0-9]+")) {
                return "填毫秒数（1–10000）";
            }
            long ms = Long.parseLong(v);
            if (ms < DELAY_MIN_MS || ms > DELAY_MAX_MS) {
                return "毫秒数应在 1–10000 之间";
            }
            return null;
        }

        @Override
        public String normalize(String value) {
            return value.trim();
        }

        @Override
        public String hint() {
            return "毫秒数（1–10000）";
        }

        @Override
        public String autoLabel(String value) {
            return "⏱" + value;
        }
    };

    public static final long DELAY_MIN_MS = 1;
    public static final long DELAY_MAX_MS = 10000;

    /** 对话框 Spinner 上的显示名。 */
    public final String label;

    StepMode(String label) {
        this.label = label;
    }

    /** 值 → 原始字节（校验过的合法值；延迟步骤不产出字节）。 */
    public abstract byte[] bytesOf(String value);

    /** 值校验：非法返回错误文案（标在输入框上），合法返回 null。 */
    public abstract String validate(String value);

    /** 合法值的规范化（落盘前）：^a→A、十六进制补零小写等。 */
    public abstract String normalize(String value);

    /** 输入框提示文案；返回空串表示该模式隐藏输入框。 */
    public abstract String hint();

    /** 标签留空时的自动标签（单步键）。 */
    public abstract String autoLabel(String value);

    /** 该模式有没有内容可填（回车没有）。 */
    public boolean hasInput() {
        return true;
    }

    /** 落盘的 mode 字符串 → 枚举；未知（prefs 被改坏）返回 null，解析方跳过该步。 */
    public static StepMode from(String mode) {
        for (StepMode m : values()) {
            if (m.name().equalsIgnoreCase(mode)) {
                return m;
            }
        }
        return null;
    }
}
