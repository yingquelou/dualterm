package dev.dualterm.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

import dev.dualterm.container.ContainerBootstrap;
import dev.dualterm.container.ContainerRuntime;
import dev.dualterm.container.Containers;
import dev.dualterm.container.ImageImporter;
import dev.dualterm.container.Volumes;

/**
 * 容器 / 镜像 / 绑定目录的对话框集合（从 MainActivity 拆出）。
 *
 * <p>这里只做「装配 + 后台任务编排」，数据读写仍在 {@link Containers} /
 * {@link Volumes} / {@link ImageImporter}；与主界面有关的部分（模式 chip、
 * 会话启停、覆盖层、提示、控制台入口）通过 {@link Host} 交回宿主。
 *
 * <p>拆分目的：这些对话框合计约 700 行，只依赖容器数据层与少量宿主能力，
 * 与 tab / 会话状态零耦合，是 MainActivity 里最容易整块搬走的部分。
 */
public final class ContainerDialogs {

    /** 从 tar 导入镜像时向系统文件选择器发起的请求码。 */
    public static final int REQ_IMPORT_TAR = 3301;

    /** 宿主只提供行为，不提供视图。 */
    public interface Host {
        /** 对话框用的 Context（必须是 Activity，AlertDialog 要用它的主题）。 */
        Context context();

        /** 后台任务用的全局 Context。 */
        Context appContext();

        /** 单线程后台执行器（与宿主共用，退出时统一收口）。 */
        ExecutorService background();

        /** 长 IO 专用执行器（拉取 / 导入共用一条）：不堵启动、创建等短任务。 */
        ExecutorService ioLong();

        /** 回到 UI 线程（等价于 Activity.runOnUiThread）。 */
        void runOnUi(Runnable r);

        /** 非模态进度条：显示 / 更新文案 / 隐藏（不遮屏，菜单与会话照常可用）。 */
        void showProgress(String message);

        void updateProgress(String message);

        void hideProgress();

        void toast(String message);

        void showError(String title, String message);

        boolean allFilesAccess();

        void startForResult(Intent intent, int requestCode);

        boolean inContainerMode();

        /** 把「当前模式」记成容器（内存 + 落盘），不动会话。 */
        void applyContainerMode();

        /** 刷新顶栏模式 chip。 */
        void refreshModeChip();

        /** 允许下一轮容器启动再做一次「坏容器 → 换一个」的兜底。 */
        void resetFallback();

        void startContainer(String name);

        /** 掐掉某个容器的全部 tab（停止 / 删除 / 重建容器前）。 */
        void stopContainerTabs(String name);

        void updateStatus(String extra);

        /** 以重启语义切到容器模式（绑定目录变更后重建会话）。 */
        void restartContainerMode();

        void openConsole();
    }

    private final Host host;

    /** 正在拉取的 tag（主线程访问）：进度条不遮屏后，防同一镜像被重复点两次。 */
    private final java.util.Set<String> pulling = new java.util.HashSet<>();

    /** 当前打开的容器管理面板：删除 / 新建 / 拉取完成后要就地重绘，已收起则忽略。 */
    private ContainerSheet sheet;

    public ContainerDialogs(Host host) {
        this.host = host;
    }

    /* -------------------- 绑定目录（volumes） -------------------- */

    public void showVolumesDialog() {
        final Context ctx = host.context();
        final List<String[]> items = Volumes.list(ctx);
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle("容器绑定目录");

        if (items.isEmpty()) {
            builder.setMessage("还没有绑定。\n\n"
                    + "绑定后，容器内的路径直接指向宿主真实目录——写进容器的文件就在手机上，"
                    + "不是复制一份。\n\n"
                    + "常用：宿主 /sdcard/Download → 容器 /mnt/download");
        } else {
            String[] labels = new String[items.size()];
            for (int i = 0; i < items.size(); i++) {
                String st = Volumes.state(items.get(i));
                labels[i] = (st == null ? "✓ " : "⚠ ")
                        + Volumes.label(items.get(i))
                        + (st == null ? "" : "  · " + st);
            }
            builder.setItems(labels, new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    confirmRemoveVolume(items, which);
                }
            });
        }

        builder.setPositiveButton("添加绑定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showAddVolumeDialog();
            }
        });
        builder.setNeutralButton("全部清除", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                Volumes.clear(host.context());
                host.toast("已清除全部绑定");
            }
        });
        builder.setNegativeButton("关闭", null);
        builder.show();
    }

    private void confirmRemoveVolume(final List<String[]> items, final int index) {
        new AlertDialog.Builder(host.context())
                .setTitle("删除绑定")
                .setMessage(Volumes.label(items.get(index)) + "\n\n只删除配置，不会动宿主目录里的文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Volumes.removeAt(host.context(), index);
                        host.toast("已删除（重启容器会话后生效）");
                    }
                })
                .show();
    }

    private void showAddVolumeDialog() {
        final Context ctx = host.context();
        float density = ctx.getResources().getDisplayMetrics().density;
        int pad = (int) (18 * density);

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView hostLabel = new TextView(ctx);
        hostLabel.setText("宿主路径（手机上的真实目录）");
        hostLabel.setTextSize(12);
        hostLabel.setTextColor(0xFF9AA0AE);

        final EditText hostInput = new EditText(ctx);
        hostInput.setHint("/sdcard/Download");
        hostInput.setText("/sdcard/Download");
        hostInput.setSingleLine(true);

        TextView guestLabel = new TextView(ctx);
        guestLabel.setText("容器内路径（留空表示同名挂载）");
        guestLabel.setTextSize(12);
        guestLabel.setTextColor(0xFF9AA0AE);
        guestLabel.setPadding(0, (int) (10 * density), 0, 0);

        final EditText guest = new EditText(ctx);
        guest.setHint("/mnt/download");
        guest.setText("/mnt/download");
        guest.setSingleLine(true);

        box.addView(hostLabel);
        box.addView(hostInput);
        box.addView(guestLabel);
        box.addView(guest);

        new AlertDialog.Builder(ctx)
                .setTitle("添加绑定目录")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String hostPath = hostInput.getText().toString().trim();
                        String guestPath = guest.getText().toString().trim();
                        if (hostPath.isEmpty()) {
                            host.toast("宿主路径不能为空");
                            return;
                        }
                        File hostFile = new File(hostPath);
                        if (!hostFile.exists()) {
                            host.toast("宿主路径不存在：" + hostPath);
                            return;
                        }
                        if (!hostFile.isDirectory()) {
                            host.toast("宿主路径不是目录：" + hostPath);
                            return;
                        }
                        if (!guestPath.isEmpty() && !guestPath.startsWith("/")) {
                            host.toast("容器内路径要以 / 开头");
                            return;
                        }
                        if ((hostPath.startsWith("/sdcard") || hostPath.startsWith("/storage"))
                                && !host.allFilesAccess()) {
                            host.toast("提示：未授予「所有文件访问」，容器里可能看到空目录（菜单 → 存储权限）");
                        } else if (!hostFile.canRead()) {
                            host.toast("提示：应用无权读取该目录，容器里会报 Permission denied");
                        }
                        Volumes.add(host.context(), hostPath, guestPath);
                        if (host.inContainerMode()) {
                            host.toast("已添加，正在重启容器会话");
                            host.restartContainerMode();
                        } else {
                            host.toast("已添加，切到容器模式后生效");
                        }
                    }
                })
                .show();
    }

    /* -------------------- 容器管理 -------------------- */

    /** 后台补齐镜像大小缓存（对话框只读缓存，绝不在线程里递归数几万个文件）。 */
    private void refreshImageSizesAsync() {
        final Context app = host.appContext();
        host.background().execute(new Runnable() {
            @Override
            public void run() {
                boolean changed = false;
                try {
                    changed = Containers.refreshImageSizes(app);
                } catch (Throwable ignored) {
                    // 缓存补齐失败不影响列表打开
                }
                if (changed) {
                    // 大小是后台补的：补齐后重绘一次，别让镜像页一直停在「…」
                    final boolean filled = changed;
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            if (filled && sheet != null) {
                                sheet.reloadIfShowing();
                            }
                        }
                    });
                }
            }
        });
    }

    public void showContainersDialog() {
        refreshImageSizesAsync();
        final Activity activity = (Activity) host.context();
        sheet = new ContainerSheet(activity, new ContainerSheet.Actions() {
            @Override
            public void onNewContainer() {
                chooseImageForNewContainer();
            }

            @Override
            public void onOpenConsole() {
                host.openConsole();
            }

            @Override
            public void onPullImage() {
                showPullDialog();
            }

            @Override
            public void onImportTar() {
                pickArchiveForImport();
            }

            @Override
            public void onMirrorSettings() {
                showMirrorDialog();
            }

            @Override
            public void onContainerDetail(Containers.Item item) {
                showContainerDetail(item);
            }

            @Override
            public void onImageDetail(Containers.Image image) {
                showImageDetail(image);
            }
        });
        sheet.show();
    }

    private void showContainerDetail(final Containers.Item item) {
        ContainerDetail.showContainer((Activity) host.context(), item, host.background(),
                new ContainerDetail.Actions() {
                    @Override
                    public void switchTo(Containers.Item it) {
                        host.applyContainerMode();
                        host.resetFallback();
                        Containers.setActive(host.context(), it.name);
                        host.refreshModeChip();
                        host.startContainer(it.name);
                    }

                    @Override
                    public void stop(Containers.Item it) {
                        Containers.Item current = Containers.find(host.context(), it.name);
                        if (current != null && current.isRunning()) {
                            // 只掐这个容器的 tab，别的容器会话不受影响
                            host.stopContainerTabs(it.name);
                            host.updateStatus("已停止 " + it.name);
                        }
                        sheet.reloadIfShowing();
                    }

                    @Override
                    public void remove(Containers.Item it) {
                        confirmRemoveContainer(it);
                    }

                    @Override
                    public void newFrom(Containers.Image image) {
                        askContainerName(image);
                    }

                    @Override
                    public void removeImage(Containers.Image image) {
                        confirmRemoveImage(image);
                    }
                });
    }

    private void showImageDetail(final Containers.Image image) {
        ContainerDetail.showImage((Activity) host.context(), image, new ContainerDetail.Actions() {
            @Override
            public void switchTo(Containers.Item item) {
            }

            @Override
            public void stop(Containers.Item item) {
            }

            @Override
            public void remove(Containers.Item item) {
            }

            @Override
            public void newFrom(Containers.Image img) {
                askContainerName(img);
            }

            @Override
            public void removeImage(Containers.Image img) {
                confirmRemoveImage(img);
            }
        });
    }

    private void confirmRemoveContainer(final Containers.Item item) {
        new AlertDialog.Builder(host.context())
                .setTitle("删除容器 " + item.name)
                .setMessage("会删除这个容器的 rootfs（容器里装的软件、写的文件全部消失）。"
                        + "\n\n镜像和宿主绑定目录不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 不管是不是活动容器都先停它的 tab，否则 rootfs 目录被 proot 占着删不掉
                        host.stopContainerTabs(item.name);
                        if (!Containers.remove(host.context(), item.name)) {
                            host.toast("删除失败：" + item.name + " 的目录还在被占用");
                            return;
                        }
                        host.toast("已删除 " + item.name);
                        // 活动容器落位（落到剩余第一个 / 空册）由 Containers.remove 统一处理
                        host.updateStatus(Containers.items(host.context()).isEmpty()
                                ? "已删除最后一个容器" : null);
                        if (sheet != null) {
                            sheet.reloadIfShowing();
                        }
                    }
                })
                .show();
    }

    private void chooseImageForNewContainer() {
        refreshImageSizesAsync();
        final Context ctx = host.context();
        final List<Containers.Image> images = Containers.images(ctx);
        if (images.isEmpty()) {
            host.toast("还没有镜像，先拉取一个");
            return;
        }
        if (images.size() == 1) {
            askContainerName(images.get(0));
            return;
        }
        String[] names = new String[images.size()];
        for (int i = 0; i < images.size(); i++) {
            long sz = Containers.cachedImageSize(ctx, images.get(i).name);
            names[i] = images.get(i).name + "   " + (sz < 0 ? "…" : Containers.humanSize(sz));
        }
        new AlertDialog.Builder(ctx)
                .setTitle("选择镜像")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        askContainerName(images.get(which));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void askContainerName(final Containers.Image image) {
        final Context ctx = host.context();
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint("容器名，如 web");
        input.setText(Containers.suggestName(ctx));
        long sz = Containers.cachedImageSize(ctx, image.name);
        String message = sz < 0
                ? "会复制一份可写 rootfs，与镜像互不影响。"
                : "会复制一份可写 rootfs（" + Containers.humanSize(sz) + "），与镜像互不影响。";
        new AlertDialog.Builder(ctx)
                .setTitle("从 " + image.name + " 新建容器")
                .setMessage(message)
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        createContainerAsync(input.getText().toString().trim(), image.name);
                    }
                })
                .show();
    }

    private void createContainerAsync(final String name, final String image) {
        host.showProgress("创建容器 " + name + " …");
        final Context app = host.appContext();
        host.background().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final Containers.Item created = Containers.create(app, name, image);
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            host.hideProgress();
                            Containers.setActive(host.context(), created.name);
                            host.applyContainerMode();
                            host.refreshModeChip();
                            host.startContainer(created.name);
                            if (sheet != null) {
                                sheet.reloadIfShowing();
                            }
                        }
                    });
                } catch (final Exception e) {
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            host.hideProgress();
                            host.showError("创建容器失败", describe(e));
                        }
                    });
                }
            }
        });
    }

    private void confirmRemoveImage(final Containers.Image image) {
        new AlertDialog.Builder(host.context())
                .setTitle("删除镜像 " + image.name)
                .setMessage("已存在的容器自带 rootfs 副本，不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            Containers.removeImage(host.context(), image.name);
                            host.toast("已删除镜像 " + image.name);
                        } catch (Exception e) {
                            host.toast(describe(e));
                        }
                        if (sheet != null) {
                            sheet.reloadIfShowing();
                        }
                    }
                })
                .show();
    }

    public void showPullDialog() {
        final Context ctx = host.context();
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint("镜像名，如 debian:12 / alpine:3.21 / ghcr.io/owner/app:tag");
        input.setText("alpine:3.21");
        String hub = Containers.hubMirror(ctx);
        new AlertDialog.Builder(ctx)
                .setTitle("拉取镜像")
                .setMessage("alpine 版本：从官方 CDN 或镜像源下载 minirootfs（约 3MB）。\n"
                        + "其余：匿名拉取（几十 MB 起，层压缩仅支持 gzip）。\n"
                        + "ghcr.io / quay.io 等公共 registry 直连，不走 Hub 镜像源；"
                        + "私有仓库与自建（带端口）registry 不支持。\n"
                        + "当前 Hub 源：" + (hub.isEmpty() ? "官方直连" : hub)
                        + "（可在「镜像源设置」里换源）")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("拉取", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pullImageAsync(input.getText().toString().trim());
                    }
                })
                .show();
    }

    /** 换源设置：Hub 与 Alpine 两个源各一个输入框，留空=恢复官方直连。 */
    private void showMirrorDialog() {
        final Context ctx = host.context();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);

        TextView l1 = new TextView(ctx);
        l1.setText("Docker Hub 镜像源（留空=官方直连）\n示例：docker.1ms.run");
        final EditText hub = new EditText(ctx);
        hub.setSingleLine(true);
        hub.setHint("https://docker.m.daocloud.io");
        hub.setText(Containers.hubMirror(ctx));
        TextView l2 = new TextView(ctx);
        l2.setText("Alpine CDN 镜像源（留空=官方 dl-cdn）\n示例：mirrors.tuna.tsinghua.edu.cn/alpine");
        final EditText alpine = new EditText(ctx);
        alpine.setSingleLine(true);
        alpine.setHint("https://mirrors.tuna.tsinghua.edu.cn/alpine");
        alpine.setText(Containers.alpineMirror(ctx));

        box.addView(l1);
        box.addView(hub);
        box.addView(l2);
        box.addView(alpine);

        new AlertDialog.Builder(ctx)
                .setTitle("镜像源设置")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        try {
                            String h = Containers.setHubMirror(host.context(),
                                    hub.getText().toString().trim());
                            String a = Containers.setAlpineMirror(host.context(),
                                    alpine.getText().toString().trim());
                            host.toast("镜像源已保存：Hub " + (h.isEmpty() ? "官方直连" : h)
                                    + " / Alpine " + (a.isEmpty() ? "官方直连" : a));
                        } catch (Exception e) {
                            host.toast("保存失败：" + e.getMessage());
                        }
                    }
                })
                .show();
    }

    private void pullImageAsync(final String tag) {
        if (!pulling.add(tag)) {
            host.toast("正在拉取 " + tag + " …");
            return;
        }
        host.showProgress("拉取 " + tag + " …");
        final Context appContext = host.appContext();
        host.ioLong().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String ref = Containers.pull(appContext, tag, new ContainerRuntime.Progress() {
                        @Override
                        public void onProgress(final String message) {
                            host.runOnUi(new Runnable() {
                                @Override
                                public void run() {
                                    host.updateProgress(message);
                                }
                            });
                        }
                    });
                    final String pulled = ref;
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            pulling.remove(tag);
                            host.hideProgress();
                            host.toast("已拉取 " + pulled + "（点下面的镜像查看属性，或基于它新建容器）");
                            // 面板正开着时要立刻多出一个镜像，否则页面停在「0 个」直到重开
                            if (sheet != null) {
                                sheet.reloadIfShowing();
                            }
                            refreshImageSizesAsync();
                        }
                    });
                } catch (final Exception e) {
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            pulling.remove(tag);
                            host.hideProgress();
                            host.showError("拉取失败", describe(e));
                        }
                    });
                }
            }
        });
    }

    /* -------------------- 从 tar 导入镜像 -------------------- */

    /**
     * 选一个 tar 让系统文件选择器打开（SAF，不需要存储权限）。
     * 支持 docker export 的扁平 rootfs，也支持 docker save 的层叠包（含 OCI 布局）。
     */
    public void pickArchiveForImport() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            host.startForResult(intent, REQ_IMPORT_TAR);
        } catch (Exception e) {
            host.toast("没有可用的文件选择器：" + describe(e));
        }
    }

    /**
     * 文件选择器回来的结果。不是导入请求或用户取消就返回 false，由宿主继续处理。
     */
    public boolean handleResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_IMPORT_TAR || resultCode != android.app.Activity.RESULT_OK
                || data == null) {
            return false;
        }
        final Uri uri = data.getData();
        if (uri == null) {
            return true;
        }
        host.showProgress("读取归档…");
        host.ioLong().execute(new Runnable() {
            @Override
            public void run() {
                File copied = null;
                try {
                    copied = copyUriToCache(uri);
                    final File ready = copied;
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            host.hideProgress();
                            askImportName(ready);
                        }
                    });
                } catch (final Exception e) {
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            host.hideProgress();
                            host.showError("读取文件失败", describe(e));
                        }
                    });
                }
            }
        });
        return true;
    }

    private File copyUriToCache(Uri uri) throws Exception {
        String name = "import-source.tar";
        try (android.database.Cursor c =
                     host.context().getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String display = c.getString(idx);
                    if (display != null && !display.isEmpty()) {
                        name = display.replaceAll("[^A-Za-z0-9._-]", "_");
                    }
                }
            }
        } catch (Exception ignored) {
            // 拿不到名字就用默认的
        }
        File dest = new File(host.context().getCacheDir(), name);
        try (InputStream in = host.context().getContentResolver().openInputStream(uri);
             OutputStream out = new java.io.FileOutputStream(dest)) {
            if (in == null) {
                throw new IOException("无法打开该文件");
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        return dest;
    }

    /** 镜像名留空时用归档自带的标签（docker save 的 RepoTags / OCI 的 ref.name）。 */
    private void askImportName(final File archive) {
        final Context ctx = host.context();
        final EditText input = new EditText(ctx);
        input.setSingleLine(true);
        input.setHint("留空则用归档里的名字，如 alpine:3.20");
        new AlertDialog.Builder(ctx)
                .setTitle("导入镜像")
                .setMessage(archive.getName() + "（" + Containers.humanSize(archive.length()) + "）\n\n"
                        + "支持 docker export 的扁平 rootfs tar，也支持 docker save 的层叠 tar（含 .wh. 白化）。\n"
                        + "导入的镜像架构需与设备一致：" + ContainerBootstrap.abi())
                .setView(input)
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        //noinspection ResultOfMethodCallIgnored
                        archive.delete();
                    }
                })
                .setPositiveButton("导入", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        importArchiveAsync(archive, input.getText().toString().trim());
                    }
                })
                .show();
    }

    private void importArchiveAsync(final File archive, final String refHint) {
        host.showProgress("导入镜像…");
        final Context app = host.appContext();
        host.ioLong().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final ImageImporter.Result result = ImageImporter.importArchive(app, archive, refHint,
                            new ContainerRuntime.Progress() {
                                @Override
                                public void onProgress(final String message) {
                                    host.runOnUi(new Runnable() {
                                        @Override
                                        public void run() {
                                            host.updateProgress(message);
                                        }
                                    });
                                }
                            });
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            host.hideProgress();
                            //noinspection ResultOfMethodCallIgnored
                            archive.delete();
                            String message = "已导入 " + result.ref + "\n"
                                    + "格式：" + result.format + "\n"
                                    + "层数：" + result.layers + "\n\n"
                                    + "下一步：镜像页里点它 → 「基于它新建容器」；"
                                    + "或控制台里 docker run --name c1 " + result.ref;
                            if (result.note != null) {
                                message = result.note + "\n\n" + message;
                            }
                            if (sheet != null) {
                                sheet.reloadIfShowing();
                            }
                            refreshImageSizesAsync();
                            new AlertDialog.Builder(host.context())
                                    .setTitle("导入完成")
                                    .setMessage(message)
                                    .setPositiveButton("好的", null)
                                    .show();
                        }
                    });
                } catch (final Exception e) {
                    host.runOnUi(new Runnable() {
                        @Override
                        public void run() {
                            host.hideProgress();
                            //noinspection ResultOfMethodCallIgnored
                            archive.delete();
                            host.showError("导入失败", describe(e));
                        }
                    });
                }
            }
        });
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        return (msg == null || msg.isEmpty()) ? t.toString() : msg;
    }
}
