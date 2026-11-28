package dev.dualterm.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import dev.dualterm.R;

/**
 * 会话选择器（模式 chip 下拉）：上面全是会话，最底一行是新建/管理动作。
 *
 * <p>以前这里是系统 {@code PopupMenu}：会话行之后还平铺着「＋ 新建本地 Shell」
 * 「＋ 新建容器会话（容器名）」「容器与镜像…」三行。三行固定项和会话抢高度——
 * 会话越多越要先滑过它们。现在三行压成一行图标（空间不足时的退化形式，
 * 图标规范见 {@link Icons} 与 docs/dev/internals.md），省下的两行全给会话。
 *
 * <p>图标只有三个、语义靠形状区分（终端窗口 / 箱体 / 层叠），长按补一条文字
 * 提示，不至于「只认图标不认动作」。箱体那个在册容器多于一个时不再是「进活动
 * 容器」，而是先让宿主弹一层选择（见 {@link Host#containerCount()}），长按提示
 * 也跟着改成「先选容器」——图标不变，但说法不能骗人。标签怎么打（● 当前、
 * 已退出、容器名）与「切到哪」仍由宿主提供。
 *
 * <h2>宽度：跟内容，但按屏幕比例封顶</h2>
 * 早先宽度由 {@code setMinimumWidth(230dp)} 加行内 {@code maxWidth} 决定，
 * 容器会话的标签（{@code 容器 · eclipse-temurin:17-jre · pid 1234}）一长就把
 * 面板顶到近整屏宽——看着像抽屉，不像从 chip 上掉下来的菜单。现在的算法是
 * 三段：自然宽取「最长会话行」与「动作行」的较大值；下限锁在动作行（三个
 * 44dp 图标不许换行）；上限 = 屏宽的 {@link #WIDTH_RATIO} 且至少留两侧 12dp，
 * 再长的标签在行内省略。
 *
 * <h2>高度：行数上限 + 滚动</h2>
 * 会话多寡不该决定窗口多高，所以会话区最多显示 {@link Host#maxVisibleRows()}
 * 行（0 = 不限），超出部分在会话区竖向滚动，动作行始终钉在面板底部。上限与
 * 辅助键盘的行数上限是同一套做法：一条 prefs 整数，菜单里可改；同时仍受锚点
 * 上下可用空间约束，取两者的小值。
 */
public final class SessionPicker {

    public interface Host {
        /** 会话显示标签，顺序即 {@link #onSwitchSession} 的索引。 */
        List<String> sessionLabels();

        /** 当前活动容器名；用于「新建容器会话」的提示文案，无容器时为 null。 */
        String activeContainer();

        /**
         * 在册容器数量：决定「新建容器会话」的长按说明——没有容器要先去建，
         * 一个容器直接进，多个容器先在弹层里选一个。
         */
        int containerCount();

        void onSwitchSession(int index);

        void onNewLocal();

        void onNewContainer();

        void onContainers();

        /**
         * 会话区最多显示几行；0 = 不限（仍受锚点上下可用空间约束）。
         *
         * <p>与辅助键盘的行数上限（{@code soft_kbd_max_rows}）同一口径：
         * 由宿主从 prefs 读，菜单里可改。
         */
        int maxVisibleRows();

        /**
         * 当前活动会话在 {@link #sessionLabels()} 里的下标；-1 = 找不到。
         *
         * <p>只用来把会话区滚到「我在哪一行」——限高之后当前会话很可能
         * 排在第 8 行，一打开却只看见前 3 行。不靠解析标签前缀（●）判断，
         * 那是宿主的显示格式，不该被这边依赖。
         */
        int activeIndex();
    }

    private static final int COLOR_TEXT = 0xFFE6E6E6;

    /** 一行会话的高度；最大行数与可用空间都按它折算，别在两处各写一个数。 */
    private static final int ROW_H = 44;

    /**
     * 面板宽度上限占屏宽的比例：360dp 竖屏 ≈ 245dp。
     *
     * <p>再宽就不像「从 chip 上掉下来的菜单」了；再窄，容器名会被截得只剩
     * 前缀——68% 是这两头之间能同时站住的位置（横屏时屏宽大，上限也跟着放大）。
     */
    private static final float WIDTH_RATIO = 0.68f;

    private final Activity activity;
    private final View anchor;
    private final Host host;

    /** 当前面板（null = 未打开）。 */
    private PopupWindow popup;

    public SessionPicker(Activity activity, View anchor, Host host) {
        this.activity = activity;
        this.anchor = anchor;
        this.host = host;
    }

    public void dismiss() {
        if (popup != null) {
            if (popup.isShowing()) {
                popup.dismiss();
            }
            popup = null;
        }
    }

    public void show() {
        dismiss();

        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.menu_bg);

        // 宽度上限：屏宽的比例与「两侧各留 12dp」取小
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        int maxW = Math.min(screen - dp(24), Math.round(screen * WIDTH_RATIO));

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);

        List<String> labels = host.sessionLabels();
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            TextView row = sessionRow(labels.get(i), maxW);
            A11y.asButton(row);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dismiss();
                    host.onSwitchSession(index);
                }
            });
            rows.addView(row);
        }

        View divider = new View(activity);
        // 与面板描边同色（menu_bg 的 1dp 边框）：动作行和会话列表的分界要看得出来，
        // 但又不能比行本身更抢眼
        divider.setBackgroundColor(0x33FFFFFF);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        dlp.setMargins(dp(14), dp(0), dp(14), dp(0));

        // 三个固定项压成一行图标。居中排布（面板宽度由最长的会话标签撑开，
        // 左对齐会在右侧留出一大片感觉像遗漏的空白）；长按补文字提示。
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_HORIZONTAL);
        actions.setPadding(dp(8), dp(2), dp(8), dp(6));
        actions.addView(iconButton(R.drawable.ic_new_local, "新建本地 Shell",
                new Runnable() {
                    @Override
                    public void run() {
                        host.onNewLocal();
                    }
                }));
        actions.addView(iconButton(R.drawable.ic_new_container, newContainerTip(),
                new Runnable() {
                    @Override
                    public void run() {
                        host.onNewContainer();
                    }
                }));
        actions.addView(iconButton(R.drawable.ic_layers, "容器与镜像",
                new Runnable() {
                    @Override
                    public void run() {
                        host.onContainers();
                    }
                }));

        // 会话多时只滚会话区：动作行钉在面板底部，滚到第 30 个会话也还在。
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rows);
        panel.addView(scroll);
        panel.addView(divider, dlp);
        panel.addView(actions);

        // 弹出方向与菜单面板同一套判断：下方放不下就向上弹（chip 在顶栏，
        // 下方通常够；横屏 + 会话多时才会走到向上这一支）。
        Rect frame = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int spaceBelow = frame.bottom - (loc[1] + anchor.getHeight());
        int spaceAbove = loc[1] - frame.top;
        int avail = Math.max(spaceBelow, spaceAbove) - dp(12);

        // 量三样东西：动作行多高（要钉底）、会话区的自然高与自然宽
        actions.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int actionsH = actions.getMeasuredHeight() + Math.max(1, dp(1));
        rows.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int rowsH = rows.getMeasuredHeight();

        // 宽度：max(最长会话行, 动作行)，再夹进 [动作行宽, 屏宽比例]。
        // 下限用动作行的实测宽（三个图标 + 间距），而不是拍一个 230dp——
        // 图标排不下就会被挤成两行，这才是真正的下限。
        int panelW = Math.max(actions.getMeasuredWidth(),
                Math.min(maxW, rows.getMeasuredWidth()));

        // 高度：min(内容高, 配置行数折算高, 可用空间)，哪一档先到就在哪滚
        int limit = host.maxVisibleRows();
        int limitH = limit > 0 ? limit * dp(ROW_H) : Integer.MAX_VALUE;
        int room = Math.max(dp(ROW_H), avail - actionsH);
        int visible = Math.min(rowsH, Math.min(limitH, room));
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                visible < rowsH ? visible : ViewGroup.LayoutParams.WRAP_CONTENT));

        panel.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);

        // 限高之后当前会话可能落在可视区外：把它滚到中间偏上的位置，
        // 打开的第一眼就能看到「我在哪」，而不是一屏陌生的前几行。
        final int active = host.activeIndex();
        if (visible < rowsH && active >= 0) {
            int target = active * dp(ROW_H) - (visible - dp(ROW_H)) / 2;
            final int maxScroll = rowsH - visible;
            final int to = Math.max(0, Math.min(target, maxScroll));
            if (to > 0) {
                // 布局完成前 scrollTo 会被 clamp 回 0，延到下一帧
                scroll.post(new Runnable() {
                    @Override
                    public void run() {
                        scroll.scrollTo(0, to);
                    }
                });
            }
        }

        PopupWindow window = new PopupWindow(panel, panelW,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        // 背景必须非空，否则 outsideTouchable 不生效（点外部不收起）
        window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        // 面板要接返回键（focusable），但不要因为它拿到焦点又去叫输入法
        window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        window.setElevation(dp(8));
        popup = window;
        if (panel.getMeasuredHeight() <= spaceBelow) {
            window.showAsDropDown(anchor, 0, dp(4), Gravity.START);
        } else {
            window.showAsDropDown(anchor, 0,
                    -(anchor.getHeight() + panel.getMeasuredHeight() + dp(4)), Gravity.START);
        }
    }

    /**
     * 会话行：一行一档高，超宽截断。
     *
     * <p>{@code maxWidth} 是「行能长到多宽」的上限（面板宽上限），不是面板目标宽：
     * 短标签时面板就窄，长标签才长到上限并在行内省略。
     */
    private TextView sessionRow(String text, int maxWidth) {
        TextView row = new TextView(activity);
        row.setText(text);
        row.setTextSize(14);
        row.setTextColor(COLOR_TEXT);
        row.setSingleLine(true);
        row.setEllipsize(TextUtils.TruncateAt.END);
        row.setMaxWidth(maxWidth);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.menu_item_bg);
        row.setPadding(dp(18), 0, dp(18), 0);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ROW_H)));
        return row;
    }

    /**
     * 动作图标按钮：44dp 触控区、20dp 图标。
     *
     * <p>点击先收起面板再执行（与菜单项一致）；长按只弹一条说明，不吃点击——
     * 图标是新的入口形式，留一个「这是什么」的零成本问法。
     */
    private ImageButton iconButton(int iconRes, final String tip, final Runnable action) {
        final ImageButton b = new ImageButton(activity);
        b.setImageDrawable(Icons.get(activity, iconRes, Icons.SIZE_ROW, COLOR_TEXT));
        b.setBackgroundResource(borderless());
        b.setContentDescription(tip);
        b.setPadding(0, 0, 0, 0);
        b.setScaleType(android.widget.ImageView.ScaleType.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
                action.run();
            }
        });
        b.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                Toast.makeText(activity, tip, Toast.LENGTH_SHORT).show();
                return true;
            }
        });
        return b;
    }

    private String newContainerTip() {
        int count = host.containerCount();
        if (count <= 0) {
            return "新建容器会话（还没有容器）";
        }
        String name = host.activeContainer();
        if (count == 1) {
            return name == null || name.isEmpty()
                    ? "新建容器会话" : "新建容器会话（" + name + "）";
        }
        // 多个容器：点了要先选容器，提示别把活动容器写成唯一目标
        return "新建容器会话（先选容器）";
    }

    private int borderless() {
        TypedValue out = new TypedValue();
        if (activity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, out, true)) {
            return out.resourceId;
        }
        return 0;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
