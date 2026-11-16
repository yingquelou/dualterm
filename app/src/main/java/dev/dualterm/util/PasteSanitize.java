package dev.dualterm.util;

/**
 * 粘贴内容净化（xterm 同款安全语义）。放 util 而不是塞进 View：
 * 纯 Java 无 Android 依赖，桌面 JVM 的 EmuTest 可以直接测。
 *
 * <p>三个问题都来自「剪贴板里的东西不是终端友好格式」：
 * <ol>
 *   <li>行分隔不统一：Windows / 网页复制的文本带 \r\n，终端收到后 CR 和 LF
 *       各触发一次回车——每行后面多执行一个空命令。统一归一成 CR（终端的回车）。</li>
 *   <li>夹带控制序列：从终端日志、网页复制的文本可能含 ESC。这些字节会被
 *       终端当作控制序列解释执行，\e[201~ 甚至能提前终止 bracketed paste
 *       边界、让后续内容逃逸出「不会被执行」的承诺。剔除全部控制字符，
 *       只保留 \t（补全/缩进有用）。</li>
 *   <li>误操作式巨量粘贴：整屏复制后忘了清剪贴板，一下粘几 MB 进终端
 *       没有意义还容易卡。设上限，超出截断。</li>
 * </ol>
 */
public final class PasteSanitize {

    /** 粘贴上限（UTF-16 char 数，约等于 512KB ASCII）。 */
    public static final int MAX_LENGTH = 512 * 1024;

    private PasteSanitize() {
    }

    /**
     * 净化粘贴文本。返回的字符串里：
     * 换行只有 \r；除 \t 外没有其它 &lt; 0x20 的控制字符，也没有 0x7f（DEL）；
     * 长度不超过 {@link #MAX_LENGTH}。
     */
    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.length() > MAX_LENGTH) {
            text = text.substring(0, MAX_LENGTH);
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;                    // \r\n 算一个换行
                }
                sb.append('\r');
            } else if (c == '\n') {
                sb.append('\r');            // 孤立的 \n 也是回车（终端语义）
            } else if (c == '\t') {
                sb.append('\t');
            } else if (c >= ' ' && c != 0x7f) {
                sb.append(c);               // 其余控制字符与 DEL 一律丢弃
            }
        }
        return sb.toString();
    }

    /** 净化后的行数（换行只有 \r，直接数即可）。单行文本返回 1。 */
    public static int countLines(String sanitized) {
        int lines = 1;
        for (int i = 0; i < sanitized.length(); i++) {
            if (sanitized.charAt(i) == '\r') {
                lines++;
            }
        }
        return lines;
    }

    /** 是否超出上限（调用方据此提示「已截断」）。 */
    public static boolean wasTruncated(String original) {
        return original != null && original.length() > MAX_LENGTH;
    }
}
