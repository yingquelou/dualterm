package dev.dualterm.ui;

import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 无障碍补丁：把自绘面板里「可点的 TextView」在语义上标成按钮。
 *
 * <p>自绘行没有系统控件的语义，TalkBack 会把一行菜单读成一段普通文本——
 * 只念文字、不说「按钮」，也不给「双击即可激活」的提示（点击本身是有效的，
 * 缺的只是角色）。Framework 的 {@link View.AccessibilityDelegate} 就能补：
 * 把节点 className 换成 {@code Button}，朗读即变成「<文字>，按钮」。
 *
 * <p>不用 {@code androidx.core} 的 ViewCompat / AccessibilityDelegateCompat：
 * 这里要的只是一个 className，为它引一整条依赖不划算——准入规则见
 * {@code app/build.gradle} 的 dependencies 注释。
 */
final class A11y {

    private A11y() {
    }

    /** 标成按钮，朗读文字沿用控件自身的 text。 */
    static void asButton(View view) {
        asButton(view, null);
    }

    /**
     * 标成按钮，并可覆盖朗读文字。
     *
     * @param label 覆盖朗读文字；null = 用控件自身的 text。可见文字与朗读不一致时
     *              才传（例如行首带 {@code ‹} 返回符号的行）。
     */
    static void asButton(View view, final String label) {
        // 可点已经有 clickable=true（setOnClickListener 会置），这里补焦点与角色，
        // 让读屏按「控件」而不是「静态文本」来处理
        view.setFocusable(true);
        if (label != null) {
            view.setContentDescription(label);
        }
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
            }
        });
    }
}
