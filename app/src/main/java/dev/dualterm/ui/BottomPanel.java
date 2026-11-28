package dev.dualterm.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import dev.dualterm.R;

/**
 * 底部面板容器：全屏透明窗口 + 贴底内容。
 *
 * <p>没有用 Material 的 BottomSheetDialog，是因为它在 Android 11 上不可靠：
 * 外层的 CoordinatorLayout 会被按 AT_MOST 量成「内容高度」，BottomSheetBehavior
 * 算 {@code childTop = parentHeight - childHeight} 时得到 0，面板直接贴到屏幕顶部
 * （Android 16 上正常，所以一直没暴露）。给它换 MATCH_PARENT、换绝对高度都只能
 * 治一半，还会把内容顶出屏幕。
 *
 * <p>这里自己拿窗口几何：全屏窗口 + 内容 gravity=BOTTOM，位置就是确定的；
 * 点面板外空白处或按返回键收起。控件仍是 Material 的（MaterialButton /
 * RecyclerView），颜色来自 {@code Theme.DualTerm.Sheet}。
 */
final class BottomPanel {

    private BottomPanel() {
    }

    static Dialog show(Activity activity, View content) {
        Context themed = new ContextThemeWrapper(activity, R.style.Theme_DualTerm_Sheet);
        Dialog dialog = new Dialog(themed, R.style.Theme_DualTerm_Sheet);

        FrameLayout root = new FrameLayout(themed);
        root.setOnClickListener(v -> dialog.dismiss());

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM;
        content.setLayoutParams(lp);
        content.setBackgroundResource(R.drawable.panel_bg);
        root.addView(content);

        // 全屏窗口会压到手势导航条上：把导航条高度补成底部内边距。
        // 有的 ROM 窗口本身已避让（insets 为 0），所以再兜一个 12dp 下限，
        // 免得操作按钮贴着屏幕底边。
        final int minPad = (int) (12 * themed.getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getPaddingRight(), Math.max(bars.bottom, minPad));
            return insets;
        });

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.5f);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
        return dialog;
    }
}
