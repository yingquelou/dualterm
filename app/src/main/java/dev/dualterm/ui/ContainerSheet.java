package dev.dualterm.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.dualterm.R;
import dev.dualterm.container.Containers;

/**
 * 容器管理面板：一个弹层、两个页签（容器 / 镜像）、操作栏随页签切换。
 *
 * <p>换掉的是原来那个「一个 AlertDialog 平铺 N 个容器 + M 个镜像」的列表：
 * 两类对象混在一个列表里，只能靠「镜像」两字前缀分辨；新建在顶、拉取导入在底，
 * 列表一长就要来回滑。现在把两类彻底分开，动作贴着自己那一页放。
 *
 * <p>列表只放必要信息（容器：镜像 + 状态；镜像：大小 + 被几个容器占用），
 * 占用、路径、创建时间这些全量属性进 {@link ContainerDetail} 属性页。
 *
 * <p>控件用 Material 的标准件（MaterialButtonToggleGroup / RecyclerView），
 * 弹层本身由 {@link BottomPanel} 提供；主题走 {@code Theme.DualTerm.Sheet}，
 * 不改动 App 主体的 Framework 主题，既有对话框观感不受影响。
 */
public final class ContainerSheet {

    /** 界面只负责「点了什么」，具体动作仍在 ContainerDialogs 里。 */
    public interface Actions {
        void onNewContainer();

        void onOpenConsole();

        void onPullImage();

        void onImportTar();

        void onMirrorSettings();

        void onContainerDetail(Containers.Item item);

        void onImageDetail(Containers.Image image);
    }

    private static final int PAGE_CONTAINERS = 0;
    private static final int PAGE_IMAGES = 1;

    private final Activity activity;
    private final Actions actions;
    private final RowAdapter adapter = new RowAdapter();

    private Dialog dialog;
    private MaterialButtonToggleGroup tabs;
    private LinearLayout actionBar;
    private TextView emptyView;
    private int page = PAGE_CONTAINERS;
    // 一次读盘的结果：重绘（切页签）时直接用，不再回表
    private List<Containers.Item> containers = new ArrayList<>();
    private List<Containers.Image> images = new ArrayList<>();

    public ContainerSheet(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
    }

    public void show() {
        Context themed = new ContextThemeWrapper(activity, R.style.Theme_DualTerm_Sheet);
        View content = LayoutInflater.from(themed).inflate(R.layout.sheet_containers, null);

        // 列表区高度固定：容器 2 行 / 镜像 6 行切页签时面板不会忽大忽小地跳变。
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.min(dp(420), Math.max(dp(140), screenH - dp(300)));
        content.findViewById(R.id.sheet_list_box).setLayoutParams(
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        RecyclerView list = content.findViewById(R.id.sheet_list);
        list.setLayoutManager(new LinearLayoutManager(themed));
        list.setAdapter(adapter);

        emptyView = content.findViewById(R.id.sheet_empty);
        actionBar = content.findViewById(R.id.sheet_actions);
        tabs = content.findViewById(R.id.sheet_tabs);
        tabs.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId,
                                        boolean isChecked) {
                if (!isChecked) {
                    return;
                }
                int next = checkedId == R.id.tab_images ? PAGE_IMAGES : PAGE_CONTAINERS;
                if (next == page) {
                    return;
                }
                page = next;
                render();
            }
        });

        dialog = BottomPanel.show(activity, content);
        reload();
    }

    /** 外部改动了容器 / 镜像（删除、新建、拉取完成）后重绘；已收起则忽略。 */
    public void reloadIfShowing() {
        if (dialog != null && dialog.isShowing()) {
            reload();
        }
    }

    public void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
            dialog = null;
        }
    }

    private void reload() {
        containers = Containers.items(activity);
        images = Containers.images(activity);

        // 页签上的数字就是规模，省得再写一行「共 n 个」。
        button(R.id.tab_containers).setText(activity.getString(
                R.string.sheet_tab_containers, containers.size()));
        button(R.id.tab_images).setText(activity.getString(
                R.string.sheet_tab_images, images.size()));
        render();
    }

    private MaterialButton button(int id) {
        return (MaterialButton) tabs.findViewById(id);
    }

    private void render() {
        if (page == PAGE_CONTAINERS) {
            renderContainers();
        } else {
            renderImages();
        }
    }

    private void renderContainers() {
        List<Containers.Item> items = containers;
        String active = Containers.activeName(activity);
        List<Row> rows = new ArrayList<>();
        for (Containers.Item item : items) {
            final Containers.Item it = item;
            rows.add(new Row(
                    (item.name.equals(active) ? "★ " : "") + item.name,
                    item.image + " · " + (item.isRunning() ? "运行中" : "已停止"),
                    () -> actions.onContainerDetail(it)));
        }
        adapter.submit(rows);
        showEmpty(items.isEmpty(),
                "还没有容器。\n\n点下面「＋ 新建」，从镜像物化一份可写 rootfs；"
                        + "容器之间的改动互不影响。");

        actionBar.removeAllViews();
        actionBar.addView(action("＋ 新建", v -> actions.onNewContainer()));
        actionBar.addView(action("控制台", v -> actions.onOpenConsole()));
    }

    private void renderImages() {
        List<Containers.Image> images = this.images;
        // 一张表算完引用数：别为每个镜像再读一遍容器表。
        Map<String, Integer> used = new HashMap<>();
        for (Containers.Item item : containers) {
            used.merge(item.image, 1, Integer::sum);
        }
        List<Row> rows = new ArrayList<>();
        for (Containers.Image image : images) {
            final Containers.Image img = image;
            long size = Containers.cachedImageSize(activity, image.name);
            int users = used.getOrDefault(image.name, 0);
            StringBuilder sub = new StringBuilder(size < 0 ? "…" : Containers.humanSize(size));
            if (users > 0) {
                sub.append(" · ").append(users).append(" 个容器在用");
            }
            rows.add(new Row(image.name, sub.toString(), () -> actions.onImageDetail(img)));
        }
        adapter.submit(rows);
        showEmpty(images.isEmpty(),
                "还没有镜像。\n\n点下面「⇣ 拉取」下载一个（alpine 走官方 CDN / 镜像源，约 3MB），"
                        + "或用「⇣ 导入」读入 docker save 的 tar。");

        actionBar.removeAllViews();
        actionBar.addView(action("⇣ 拉取", v -> actions.onPullImage()));
        actionBar.addView(action("⇣ 导入", v -> actions.onImportTar()));
        actionBar.addView(action("⇄ 换源", v -> actions.onMirrorSettings()));
    }

    private void showEmpty(boolean isEmpty, String text) {
        emptyView.setText(text);
        emptyView.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
    }

    private MaterialButton action(String text, View.OnClickListener click) {
        MaterialButton b = (MaterialButton) LayoutInflater.from(dialog.getContext())
                .inflate(R.layout.action_button, actionBar, false);
        b.setText(text);
        b.setInsetTop(0);
        b.setInsetBottom(0);
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.setMarginStart(dp(4));
        return b;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }

    /* -------------------- 列表 -------------------- */

    private static final class Row {
        final String title;
        final String subtitle;
        final Runnable action;

        Row(String title, String subtitle, Runnable action) {
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private static final class RowAdapter extends RecyclerView.Adapter<RowAdapter.VH> {

        private final List<Row> rows = new ArrayList<>();

        void submit(List<Row> next) {
            rows.clear();
            rows.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_row, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Row row = rows.get(position);
            holder.title.setText(row.title);
            holder.subtitle.setText(row.subtitle);
            holder.itemView.setOnClickListener(v -> row.action.run());
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        static final class VH extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView subtitle;

            VH(View v) {
                super(v);
                title = v.findViewById(R.id.row_title);
                subtitle = v.findViewById(R.id.row_subtitle);
            }
        }
    }
}
