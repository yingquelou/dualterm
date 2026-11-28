package dev.dualterm.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.dualterm.R;
import dev.dualterm.container.Containers;

/**
 * 容器管理面板：镜像为父、容器为子的单列可展开列表。
 *
 * <p>镜像与容器本来就是目录关系——容器必然由某个镜像物化而来。旧的「容器 / 镜像」
 * 两个平级页签把这个关系抹掉了：切到容器页看不到它属于谁，切到镜像页也看不到
 * 它下面有几个容器。现在一个列表两层级：
 *
 * <ul>
 *   <li>父行（镜像）：{@code ▾ alpine:3.21} + {@code 7.7MB · 2 个容器}，
 *       整行点击展开/折叠它下面的容器；行尾 {@code ⋯} 进镜像属性页
 *       （不给整行双击语义，避免"想看属性结果收起了列表"）；</li>
 *   <li>子行（容器）：缩进 + 左侧竖线，只写 {@code c1} 和运行状态——镜像名
 *       父行已经写了，子行再写一遍是重复。</li>
 * </ul>
 *
 * <p>属性页（{@link ContainerDetail}）保留全量信息：列表只放"扫一眼"要用的两行，
 * 占用、路径、创建时间、被谁引用都点进去看。
 *
 * <p>控件全部是 Framework 的（LinearLayout / ScrollView / Button），没有第三方
 * 依赖：这里的列表最多十几条，RecyclerView 的回收能力用不上；分段页签随父子
 * 结构一起消失了。理由与取舍见 {@code app/build.gradle} 的 dependencies 注释。
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

    /** 「来源镜像已不存在」的容器分组的 key，不会与真实镜像名冲突。 */
    private static final String ORPHANS = "\u0000orphans";

    private final Activity activity;
    private final Actions actions;

    private Dialog dialog;
    private ScrollView scroll;
    private LinearLayout listBox;
    private LinearLayout actionBar;
    private TextView emptyView;

    // 一次读盘的结果：展开/折叠只重建视图，不再回表
    private List<Containers.Item> containers = new ArrayList<>();
    private List<Containers.Image> images = new ArrayList<>();
    private final Set<String> expanded = new HashSet<>();

    public ContainerSheet(Activity activity, Actions actions) {
        this.activity = activity;
        this.actions = actions;
    }

    public void show() {
        Context themed = new ContextThemeWrapper(activity, R.style.Theme_DualTerm_Sheet);
        View content = LayoutInflater.from(themed).inflate(R.layout.sheet_containers, null);

        // 列表区高度固定：展开/折叠时面板不会忽大忽小地跳变，超出就在列表内滚。
        int screenH = activity.getResources().getDisplayMetrics().heightPixels;
        int listHeight = Math.min(dp(420), Math.max(dp(140), screenH - dp(300)));
        content.findViewById(R.id.sheet_list_box).setLayoutParams(
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, listHeight));

        scroll = content.findViewById(R.id.sheet_scroll);
        listBox = content.findViewById(R.id.sheet_list);
        emptyView = content.findViewById(R.id.sheet_empty);
        actionBar = content.findViewById(R.id.sheet_actions);

        content.findViewById(R.id.sheet_done).setOnClickListener(v -> dismiss());
        content.findViewById(R.id.sheet_console).setOnClickListener(v -> {
            dismiss();
            actions.onOpenConsole();
        });

        // 操作栏与页签无关，建一次就够；数据变化只重绘列表。
        // 必须在 BottomPanel.show 之后：action() 取的是列表/操作栏的 Context，
        // 早于窗口创建时 dialog 还是 null（初版就栽在这儿）。
        dialog = BottomPanel.show(activity, content);
        buildActions();
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
        int before = containers.size();
        containers = Containers.items(activity);
        images = Containers.images(activity);

        // 镜像被删掉了，它那一项就不该继续留在展开集合里
        Set<String> alive = new HashSet<>();
        for (Containers.Image image : images) {
            alive.add(image.name);
        }
        expanded.retainAll(alive);

        // 从「一个容器都没有」变成「有了」时自动展开——新建/拉取完就看得见，
        // 之后用户的折叠选择不会再被覆盖。
        if (before == 0 && !containers.isEmpty()) {
            for (Containers.Item item : containers) {
                expanded.add(item.image);
            }
        }
        render();
    }

    private void buildActions() {
        actionBar.removeAllViews();
        actionBar.addView(action("＋ 新建", true, v -> actions.onNewContainer()));
        actionBar.addView(action("⇣ 拉取", false, v -> actions.onPullImage()));
        actionBar.addView(action("⇣ 导入", false, v -> actions.onImportTar()));
        actionBar.addView(action("⇄ 换源", false, v -> actions.onMirrorSettings()));
    }

    private void render() {
        listBox.removeAllViews();

        if (images.isEmpty()) {
            emptyView.setText("还没有镜像。\n\n点下面「⇣ 拉取」下载一个（alpine 走官方 CDN / 镜像源，"
                    + "约 3MB），或用「⇣ 导入」读入 docker save 的 tar。\n\n"
                    + "容器只能由镜像创建，所以这里先有镜像才有容器。");
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        emptyView.setVisibility(View.GONE);

        // 按镜像分组：一次遍历，别为每个镜像再扫一遍容器表
        Map<String, List<Containers.Item>> byImage = new HashMap<>();
        for (Containers.Item item : containers) {
            List<Containers.Item> bucket = byImage.get(item.image);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byImage.put(item.image, bucket);
            }
            bucket.add(item);
        }

        for (Containers.Image image : images) {
            List<Containers.Item> kids = byImage.remove(image.name);
            addImageRow(image, kids == null ? new ArrayList<>() : kids);
        }

        // 兜底：容器的 image 字段指向一个已不存在的镜像（正常流程下 Containers
        // 会拦住删镜像，但数据可能来自旧版本或手工改过的 prefs）。宁可多一组，
        // 也不能让容器在界面里凭空消失。
        List<Containers.Item> orphans = new ArrayList<>();
        for (List<Containers.Item> rest : byImage.values()) {
            orphans.addAll(rest);
        }
        if (!orphans.isEmpty()) {
            addOrphanRow(orphans);
        }
    }

    private void addImageRow(final Containers.Image image, final List<Containers.Item> kids) {
        View row = LayoutInflater.from(listBox.getContext())
                .inflate(R.layout.item_image_row, listBox, false);

        boolean open = !kids.isEmpty() && expanded.contains(image.name);
        ((TextView) row.findViewById(R.id.row_arrow)).setText(open ? "▼" : "▶");
        ((TextView) row.findViewById(R.id.row_title)).setText(image.name);

        long size = Containers.cachedImageSize(activity, image.name);
        String sizeText = size < 0 ? "…" : Containers.humanSize(size);
        ((TextView) row.findViewById(R.id.row_subtitle)).setText(
                sizeText + " · " + (kids.isEmpty() ? "无容器" : kids.size() + " 个容器"));

        // 没有容器时点了也展开不出东西，箭头一律收着，别留下"▾ 打开却是空"的错觉
        row.setOnClickListener(v -> {
            if (!kids.isEmpty()) {
                toggle(image.name);
            }
        });
        row.findViewById(R.id.row_more).setOnClickListener(v -> actions.onImageDetail(image));
        listBox.addView(row);

        if (open) {
            for (Containers.Item item : kids) {
                addContainerRow(item);
            }
        }
    }

    private void addOrphanRow(final List<Containers.Item> orphans) {
        View row = LayoutInflater.from(listBox.getContext())
                .inflate(R.layout.item_image_row, listBox, false);

        boolean open = expanded.contains(ORPHANS);
        ((TextView) row.findViewById(R.id.row_arrow)).setText(open ? "▼" : "▶");
        ((TextView) row.findViewById(R.id.row_title)).setText("其他容器");
        ((TextView) row.findViewById(R.id.row_subtitle)).setText(
                orphans.size() + " 个 · 来源镜像已不存在");
        // 没有对应的镜像实体，属性入口无意义
        row.findViewById(R.id.row_more).setVisibility(View.GONE);

        row.setOnClickListener(v -> toggle(ORPHANS));
        listBox.addView(row);

        if (open) {
            for (Containers.Item item : orphans) {
                addContainerRow(item);
            }
        }
    }

    private void addContainerRow(final Containers.Item item) {
        View row = LayoutInflater.from(listBox.getContext())
                .inflate(R.layout.item_container_row, listBox, false);

        boolean active = item.name.equals(Containers.activeName(activity));
        ((TextView) row.findViewById(R.id.row_title)).setText(
                (active ? "★ " : "") + item.name);

        TextView state = row.findViewById(R.id.row_state);
        state.setText(item.isRunning() ? "运行中" : "已停止");
        state.setTextColor(activity.getColor(
                item.isRunning() ? R.color.accent : R.color.text_dim));

        row.setOnClickListener(v -> actions.onContainerDetail(item));
        listBox.addView(row);
    }

    private void toggle(String key) {
        // 重建子视图会把 ScrollView 的滚动位置带偏，先记下来再放回去
        final int y = scroll.getScrollY();
        if (!expanded.remove(key)) {
            expanded.add(key);
        }
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private Button action(String text, boolean primary, View.OnClickListener click) {
        Button b = (Button) LayoutInflater.from(actionBar.getContext())
                .inflate(R.layout.action_button, actionBar, false);
        b.setText(text);
        b.setTextColor(activity.getColor(primary ? R.color.accent : R.color.text_primary));
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        lp.setMarginStart(dp(2));
        return b;
    }

    private int dp(int value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density);
    }
}
