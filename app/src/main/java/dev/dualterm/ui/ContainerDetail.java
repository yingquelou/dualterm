package dev.dualterm.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;

import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import dev.dualterm.R;
import dev.dualterm.container.Containers;

/**
 * 容器 / 镜像的「属性页」：列表里只留必要信息，全量属性放这里。
 *
 * <p>这样列表能保持两行、一眼扫完；占用体积、存储路径、创建时间、被哪些
 * 容器引用这些「偶尔要看、但每次列出来就是噪音」的内容，点进来再给。
 * 顺带把「删除镜像前才发现被占用」变成打开属性页就能看见的事实。
 */
public final class ContainerDetail {

    public interface Actions {
        void switchTo(Containers.Item item);

        void stop(Containers.Item item);

        void remove(Containers.Item item);

        void newFrom(Containers.Image image);

        void removeImage(Containers.Image image);
    }

    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);

    /**
     * Containers 里的 createdAt 存的是「秒」（System.currentTimeMillis() / 1000），
     * 直接当毫秒用会显示成 1970 年。
     */
    private static Date time(long epochSeconds) {
        return new Date(epochSeconds * 1000L);
    }

    private final Activity activity;
    private final Context themed;
    private final Dialog dialog;
    private final TextView title;
    private final LinearLayout body;
    private final LinearLayout buttons;

    private ContainerDetail(Activity activity) {
        this.activity = activity;
        this.themed = new ContextThemeWrapper(activity, R.style.Theme_DualTerm_Sheet);

        LinearLayout root = new LinearLayout(themed);
        root.setOrientation(LinearLayout.VERTICAL);

        title = new TextView(themed);
        title.setTextSize(17);
        title.setTextColor(activity.getColor(R.color.text_primary));
        title.setPadding(dp(20), dp(16), dp(20), dp(4));
        root.addView(title);

        body = new LinearLayout(themed);
        body.setOrientation(LinearLayout.VERTICAL);
        // 属性区封顶到半屏多一点，长内容自己滚。
        ScrollView scroll = new ScrollView(themed) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int cap = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.55f);
                super.onMeasure(widthSpec,
                        MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(body);
        root.addView(scroll);

        buttons = new LinearLayout(themed);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(android.view.Gravity.END);
        buttons.setPadding(dp(8), dp(4), dp(8), dp(8));
        root.addView(buttons);

        dialog = BottomPanel.show(activity, root);
    }

    public static void showContainer(Activity activity, Containers.Item item, Executor bg,
                                     Actions actions) {
        ContainerDetail page = new ContainerDetail(activity);
        page.buildContainer(item, bg, actions);
    }

    public static void showImage(Activity activity, Containers.Image image, Actions actions) {
        ContainerDetail page = new ContainerDetail(activity);
        page.buildImage(image, actions);
    }

    private void buildContainer(final Containers.Item item, Executor bg, final Actions actions) {
        final File rootfs = new File(new File(Containers.containersDir(activity), item.name),
                "rootfs");
        title.setText(item.name);
        add("类型", "容器（从镜像物化，自带可写 rootfs）");
        add("状态", item.isRunning() ? "运行中" : "已停止");
        add("来源镜像", item.image);
        add("创建时间", TIME.format(time(item.createdAt)));
        final TextView sizeValue = add("rootfs 占用", "计算中…");
        add("存储路径", rootfs.getAbsolutePath());
        add("注意", "删除容器会连同 rootfs 一起删掉，容器里装的软件与写的文件都不保留；"
                + "镜像和宿主绑定目录不受影响。");

        // 递归数几万个文件，不能在 UI 线程做（与镜像大小缓存同一套理由）。
        bg.execute(() -> {
            final long bytes = Containers.dirSize(rootfs);
            activity.runOnUiThread(() -> sizeValue.setText(Containers.humanSize(bytes)));
        });

        if (item.isRunning()) {
            button("切换", false, v -> {
                dismiss();
                actions.switchTo(item);
            });
            button("停止", false, v -> {
                dismiss();
                actions.stop(item);
            });
        } else {
            button("启动并切换", false, v -> {
                dismiss();
                actions.switchTo(item);
            });
        }
        button("删除", true, v -> {
            dismiss();
            actions.remove(item);
        });
    }

    private void buildImage(final Containers.Image image, final Actions actions) {
        title.setText(image.name);
        add("类型", "镜像（只读模板，容器各自持有一份副本）");

        long size = Containers.cachedImageSize(activity, image.name);
        add("大小", size < 0 ? "未缓存（下次打开镜像列表会补齐）" : Containers.humanSize(size));
        if (image.createdAt > 0) {
            add("导入时间", TIME.format(time(image.createdAt)));
        }

        List<String> users = Containers.containersUsing(activity, image.name);
        add("被容器使用", users.isEmpty()
                ? "没有容器基于它，可以直接删除"
                : users.size() + " 个：" + String.join("、", users));
        add("存储路径", image.rootfs.getParentFile().getAbsolutePath());

        button("基于它新建容器", false, v -> {
            dismiss();
            actions.newFrom(image);
        });
        MaterialButton remove = button("删除镜像", true, v -> {
            dismiss();
            actions.removeImage(image);
        });
        if (!users.isEmpty()) {
            // 被占用时直接禁掉，比让用户点删除、读一遍确认、再吃一个报错友好得多
            remove.setEnabled(false);
            remove.setText("删除镜像（使用中）");
        }
    }

    private void dismiss() {
        if (dialog.isShowing()) {
            dialog.dismiss();
        }
    }

    /** 一行属性：小字标题 + 可选中复制的值。 */
    private TextView add(String label, String value) {
        LinearLayout row = new LinearLayout(themed);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(20), dp(9), dp(20), dp(9));

        TextView key = new TextView(themed);
        key.setText(label);
        key.setTextSize(12);
        key.setTextColor(activity.getColor(R.color.text_dim));
        row.addView(key);

        TextView val = new TextView(themed);
        val.setText(value);
        val.setTextSize(14);
        val.setTextColor(activity.getColor(R.color.text_primary));
        val.setTextIsSelectable(true);
        row.addView(val);

        body.addView(row);
        return val;
    }

    private MaterialButton button(String text, boolean danger, View.OnClickListener click) {
        MaterialButton b = (MaterialButton) android.view.LayoutInflater.from(themed)
                .inflate(R.layout.action_button, buttons, false);
        b.setText(text);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        if (danger) {
            // 危险色只在可用时给；被禁用（镜像还有容器在用）时退回灰，
            // 否则一个红色的「删除镜像（使用中）」看着像能点。
            b.setTextColor(new ColorStateList(
                    new int[][]{{android.R.attr.state_enabled}, {}},
                    new int[]{activity.getColor(R.color.danger_text),
                            activity.getColor(R.color.text_dim)}));
        }
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.setMarginStart(dp(4));
        buttons.addView(b);
        return b;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
