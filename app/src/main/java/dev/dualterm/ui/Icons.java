package dev.dualterm.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

/**
 * 统一图标方案：动作类图标只有这一套，尺寸与颜色都在这里定。
 *
 * <p>为什么需要：屏幕空间不够时（会话列表要把行数让给会话标签、面板操作栏
 * 五个动作挤一行），文字是可以退化成图标的，但退化必须成套——大小、线条粗细、
 * 颜色各不相同的一堆图标，比密集的文字更难认。
 *
 * <p>规范（与 {@code res/drawable/ic_*.xml} 一一对应）：
 * <ul>
 *   <li>画布 24×24，描边 1.8–2，圆头圆角，不填色；</li>
 *   <li>颜色不在 XML 里定，一律运行时 {@code setTint}（深色主题只有两档：
 *       常规 {@code text_primary}、强调 {@code accent}）；</li>
 *   <li>同一语义全应用只用一个图标，语义清单见
 *       {@code docs/dev/internals.md} 的「图标方案」一节；</li>
 *   <li>字形符号（{@code ⋮ ▶ ▼ ★ ← ⌨ ⛶}）继续用字符：它们是排版记号，
 *       跟着字体走，不需要也不应该被矢量化。</li>
 * </ul>
 */
public final class Icons {

    /** 独立图标按钮里的图标（会话列表底部动作行）。 */
    public static final int SIZE_ROW = 20;
    /** 与文字并排的图标（面板操作栏）。 */
    public static final int SIZE_INLINE = 16;

    private Icons() {
    }

    /**
     * 取一个按目标尺寸、目标颜色就绪的图标。
     *
     * <p>返回的 Drawable 已经设好 bounds，直接 {@code ImageView.setImageDrawable}
     * 或 {@code setCompoundDrawables} 都能按这个尺寸画（不依赖 XML 的 intrinsic
     * 24dp，否则在 16dp 的位置会顶出一圈）。
     */
    public static Drawable get(Context ctx, int res, int sizeDp, int color) {
        Drawable d = ctx.getDrawable(res);
        if (d == null) {
            return null;
        }
        d.setTint(color);
        int px = dp(ctx, sizeDp);
        d.setBounds(0, 0, px, px);
        return d;
    }

    /** 把图标放到文字左侧（复合图标），占位宽度按 {@code sizeDp} 而不是 24dp。 */
    public static void compound(TextView view, int res, int sizeDp, int color) {
        Drawable d = get(view.getContext(), res, sizeDp, color);
        if (d == null) {
            return;
        }
        view.setCompoundDrawables(d, null, null, null);
        view.setCompoundDrawablePadding(dp(view.getContext(), 5));
    }

    private static int dp(Context ctx, int value) {
        return (int) (value * ctx.getResources().getDisplayMetrics().density);
    }
}
