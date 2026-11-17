package dev.dualterm.ui;

/**
 * 自制键盘的布局算术（纯函数，不依赖 Android，桌面回归可测）。
 *
 * 为什么不放在 SoftKeyboard 里：那个类 import 了 R（构建期生成）与一堆
 * View/Context，桌面 javac -sourcepath 编不过；布局算术本身是纯逻辑，
 * 单独一个类既好测也不牵扯资源。
 */
public final class KbdLayout {

    private KbdLayout() {
    }

    /**
     * 可自定义区在行数上限下实际占几行。
     *
     * @param totalRows 键集实际需要的行数
     * @param maxRows   上限；≤0 表示不限（键多则键盘一直变高）
     * @return 可见行数（超出部分由 ScrollView 滚动）
     */
    public static int visibleCustomRows(int totalRows, int maxRows) {
        if (totalRows <= 0) {
            return 0;
        }
        return maxRows <= 0 ? totalRows : Math.min(totalRows, maxRows);
    }
}
