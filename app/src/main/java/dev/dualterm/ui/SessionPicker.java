package dev.dualterm.ui;

import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

import java.util.List;

/**
 * 会话选择器（模式 chip 下拉）：顶部列出全部 tab，下面三个新建/管理入口。
 *
 * <p>只负责弹出系统 PopupMenu 与 id 到意图的映射；标签怎么打（● 当前、已退出、
 * 容器名）与「切到哪」属于会话语义，仍由宿主提供。
 */
public final class SessionPicker {

    public interface Host {
        /** 会话显示标签，顺序即 {@link #onSwitchSession} 的索引。 */
        List<String> sessionLabels();

        /** 「新建容器会话」那一行的完整文案（含当前容器名）。 */
        String newContainerLabel();

        void onSwitchSession(int index);

        void onNewLocal();

        void onNewContainer();

        void onContainers();
    }

    private final Activity activity;
    private final View anchor;
    private final Host host;

    public SessionPicker(Activity activity, View anchor, Host host) {
        this.activity = activity;
        this.anchor = anchor;
        this.host = host;
    }

    public void show() {
        PopupMenu menu = new PopupMenu(activity, anchor);
        Menu root = menu.getMenu();
        int order = 0;
        List<String> labels = host.sessionLabels();
        for (int i = 0; i < labels.size(); i++) {
            root.add(0, 100 + i, order++, labels.get(i));
        }
        root.add(0, 1, order++, "＋ 新建本地 Shell");
        root.add(0, 2, order++, host.newContainerLabel());
        root.add(0, 3, order++, "容器与镜像…");
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id >= 100) {
                    host.onSwitchSession(id - 100);
                    return true;
                }
                switch (id) {
                    case 1:
                        host.onNewLocal();
                        return true;
                    case 2:
                        host.onNewContainer();
                        return true;
                    case 3:
                        host.onContainers();
                        return true;
                    default:
                        return false;
                }
            }
        });
        menu.show();
    }
}
