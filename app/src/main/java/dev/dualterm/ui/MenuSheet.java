package dev.dualterm.ui;

import android.app.Activity;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import dev.dualterm.R;

/**
 * 应用菜单（从 MainActivity 拆出）：自绘的二级分组面板。
 *
 * <p>只负责面板的装配、层级切换（顶层 ↔ 二级）、按锚点上下空间决定弹出方向，
 * 以及返回键「二级 → 顶层 → 收起」。点了哪一项通过 {@link Host} 交回宿主，
 * 具体动作与菜单数据（哪些组、哪些标签）仍由宿主提供。
 *
 * <p>用自绘面板而不是 PopupMenu：PopupMenu 的二级子菜单是级联窗口，顶部那行
 * 绿色组名只是标题摆设——点不动、也没法回到上一层。自绘后层级在同一窗口
 * 内切换：二级首行（绿色组名）点一下回顶层，返回键同理逐层退。
 *
 * <h2>宽度：跟内容，但按屏宽封顶</h2>
 * 面板宽度一直是 WRAP_CONTENT，遇到「容器管理（2 个容器 / 3 个镜像）」这类长标签
 * 在窄屏上会顶到屏边、贴着状态栏看着像被裁过。现在与 {@link SessionPicker}
 * 同一套三段式：自然宽取内容，夹进 [200dp, min(屏宽-24dp, 屏宽×{@value #WIDTH_RATIO})]，
 * 再长的行在行内省略。宽度必须量出来交给面板，否则 ScrollView 会按 UNSPECIFIED
 * 重新把行撑回自然宽，省略就不生效。
 *
 * <h2>无障碍</h2>
 * 行是 TextView，读屏只会念文字、不说「按钮」。{@link A11y#asButton} 把节点
 * 角色改成 Button，返回行另给一条「返回上一级」的朗读文字（可见的 {@code ‹}
 * 符号读出来没有意义）。
 */
public final class MenuSheet {

    /** 一组菜单：组名（二级首行）+ 动作列表（label 与 id 一一对应，id 走 onMenuAction）。 */
    public static final class Group {
        final String title;
        final String[] labels;
        final int[] ids;

        public Group(String title, String[] labels, int[] ids) {
            this.title = title;
            this.labels = labels;
            this.ids = ids;
        }
    }

    public interface Host {
        /** 某个动作项被点击（菜单已收起）。 */
        void onMenuAction(int id);
    }

    private static final int COLOR_TEXT = 0xFFE6E6E6;
    private static final int COLOR_ACCENT = 0xFF7CE38B;

    /** 面板宽度上限占屏宽的比例；比会话下拉（0.68）宽一点，菜单标签本来就更长。 */
    private static final float WIDTH_RATIO = 0.78f;

    /** 面板最小宽度：顶层只有四个组名，太窄不像菜单。 */
    private static final int MIN_WIDTH = 200;

    private final Activity activity;
    private final Host host;

    /** 当前显示的面板（null = 未打开）。非焦点窗口，返回键由宿主接管。 */
    private PopupWindow popup;
    /** 菜单打开期间的层级现场：返回键「二级→顶层→收起」要用。 */
    private View anchor;
    private List<Group> groups;
    private int groupIndex = -1;

    public MenuSheet(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void show(View anchor, List<Group> groups) {
        showPopup(anchor, groups, -1);
    }

    /**
     * 返回键：二级退回顶层，顶层才收起。
     *
     * @return true 表示这次返回被菜单消费
     */
    public boolean handleBack() {
        if (popup == null) {
            return false;
        }
        if (groupIndex >= 0) {
            showPopup(anchor, groups, -1);
        } else {
            dismiss();
        }
        return true;
    }

    public void dismiss() {
        if (popup != null) {
            if (popup.isShowing()) {
                popup.dismiss();
            }
            popup = null;
        }
        anchor = null;
        groups = null;
        groupIndex = -1;
    }

    private void showPopup(final View anchor, final List<Group> groups, final int groupIndex) {
        dismiss();
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundResource(R.drawable.menu_bg);
        panel.setMinimumWidth(dp(MIN_WIDTH));

        // 行先装进 rowsBox 再套 ScrollView：菜单超过锚点下方可用高度时
        // （横屏的容器二级最高）像系统菜单一样可滚，而不是被屏幕裁掉底行。
        LinearLayout rowsBox = new LinearLayout(activity);
        rowsBox.setOrientation(LinearLayout.VERTICAL);

        if (groupIndex < 0) {
            // 顶层：各组组名，点击展开对应二级
            for (int i = 0; i < groups.size(); i++) {
                final int idx = i;
                TextView row = addRow(rowsBox, groups.get(i).title, false);
                A11y.asButton(row);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showPopup(anchor, groups, idx);
                    }
                });
            }
        } else {
            final Group g = groups.get(groupIndex);
            // 二级首行：绿色组名 = 返回按钮（"‹" 提示可点回顶层）
            TextView back = addRow(rowsBox, "‹ " + g.title, true);
            // "‹" 读出来只是个标点，朗读文字换成动作本身，组名保留
            A11y.asButton(back, "返回上一级，" + g.title);
            back.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPopup(anchor, groups, -1);
                }
            });
            for (int i = 0; i < g.ids.length; i++) {
                final int id = g.ids[i];
                TextView row = addRow(rowsBox, g.labels[i], false);
                A11y.asButton(row);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dismiss();
                        host.onMenuAction(id);
                    }
                });
            }
        }

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(rowsBox);
        panel.addView(scroll);
        // 先量锚点上下各有多少可见空间（可见显示框不含导航条和 IME）。
        // 锚点可能在屏幕最底部（键条 ⌨ 长按菜单）：系统键盘未弹出时锚点
        // 下方只剩导航条，向下弹出整窗会落到屏幕外——所以菜单超过下方
        // 空间就向上弹，超长内容压进较大的一侧滚动。
        Rect frame = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(frame);
        int[] loc = new int[2];
        anchor.getLocationOnScreen(loc);
        int spaceBelow = frame.bottom - (loc[1] + anchor.getHeight());
        int spaceAbove = loc[1] - frame.top;
        int avail = Math.max(spaceBelow, spaceAbove) - dp(12);

        // 宽度：自然宽夹进 [200dp, min(屏宽-24dp, 屏宽×比例)]。以 AT_MOST 量行盒，
        // 超宽的行先被压到上限（行内省略），量出来的就是面板宽。
        int screen = activity.getResources().getDisplayMetrics().widthPixels;
        int maxW = Math.min(screen - dp(24), Math.round(screen * WIDTH_RATIO));
        rowsBox.measure(View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.UNSPECIFIED);
        int panelW = Math.min(maxW, Math.max(dp(MIN_WIDTH), rowsBox.getMeasuredWidth()));

        if (rowsBox.getMeasuredHeight() > avail) {
            scroll.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, avail));
        }
        // 必须用 EXACTLY 量面板：ScrollView 会用 UNSPECIFIED 重新量子视图，
        // 那样刚才压下去的宽度会被撑回自然宽，省略形同虚设。
        panel.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.UNSPECIFIED);

        PopupWindow popup = new PopupWindow(panel,
                panelW,
                ViewGroup.LayoutParams.WRAP_CONTENT, false);
        // 背景必须是任意非空 drawable，否则 outsideTouchable 不生效（点外部不收起）
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(8));
        this.popup = popup;
        this.anchor = anchor;
        this.groups = groups;
        this.groupIndex = groupIndex;
        if (panel.getMeasuredHeight() <= spaceBelow) {
            // 下方放得下：常规下拉（顶栏 ⋮ 菜单走这条）
            popup.showAsDropDown(anchor, 0, dp(4), Gravity.END);
        } else {
            // 下方放不下：负偏移向上弹，菜单底边贴在锚点上方。
            // showAsDropDown 的 y 是相对「锚点底边」的偏移，要挪到锚点
            // 顶边之上需要抵掉锚点自身高度再加菜单高度。
            popup.showAsDropDown(anchor, 0,
                    -(anchor.getHeight() + panel.getMeasuredHeight() + dp(4)),
                    Gravity.END);
        }
    }

    private TextView addRow(LinearLayout rowsBox, String text, boolean accent) {
        TextView row = new TextView(activity);
        row.setText(text);
        row.setTextSize(15);
        row.setSingleLine(true);
        // 面板宽度封顶后，超长标签（"容器管理（2 个容器 / 3 个镜像）"）在行内省略，
        // 而不是把面板顶到屏边
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTextColor(accent ? COLOR_ACCENT : COLOR_TEXT);
        row.setBackgroundResource(R.drawable.menu_item_bg);
        row.setPadding(dp(18), 0, dp(18), 0);
        // 整行可点：宽度铺满面板。之前 WRAP_CONTENT 时短行右侧的大片面板空白
        // 是死区——看得见点不着、也不收起菜单，系统 PopupMenu 是整行宽响应。
        // 48dp 是 Material 建议的最小触控高度。
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        row.setLayoutParams(lp);
        rowsBox.addView(row);
        return row;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
