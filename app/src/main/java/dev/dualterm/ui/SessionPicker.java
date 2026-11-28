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
 * 提示，不至于「只认图标不认动作」。标签怎么打（● 当前、已退出、容器名）
 * 与「切到哪」仍由宿主提供。
 */
public final class SessionPicker {

    public interface Host {
        /** 会话显示标签，顺序即 {@link #onSwitchSession} 的索引。 */
        List<String> sessionLabels();

        /** 当前活动容器名；用于「新建容器会话」的提示文案，无容器时为 null。 */
        String activeContainer();

        void onSwitchSession(int index);

        void onNewLocal();

        void onNewContainer();

        void onContainers();
    }

    private static final int COLOR_TEXT = 0xFFE6E6E6;

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
        panel.setMinimumWidth(dp(230));

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);

        List<String> labels = host.sessionLabels();
        int labelMax = maxLabelWidth();
        for (int i = 0; i < labels.size(); i++) {
            final int index = i;
            TextView row = sessionRow(labels.get(i), labelMax);
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
        actions.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int actionsH = actions.getMeasuredHeight() + Math.max(1, dp(1));
        rows.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int room = Math.max(dp(44), avail - actionsH);
        if (rows.getMeasuredHeight() > room) {
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, room));
        }
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);

        PopupWindow window = new PopupWindow(panel,
                ViewGroup.LayoutParams.WRAP_CONTENT,
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

    /** 会话行：一行一档高，超宽截断（面板宽度上限 = 屏幕宽 - 24dp）。 */
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));
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
        String name = host.activeContainer();
        return name == null || name.isEmpty() ? "新建容器会话" : "新建容器会话（" + name + "）";
    }

    /** 会话行可用宽度：屏幕宽减去面板内边距与左右留白。 */
    private int maxLabelWidth() {
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        return screen - dp(24 + 36);
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
