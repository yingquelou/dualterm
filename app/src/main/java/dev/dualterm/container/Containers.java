package dev.dualterm.container;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.dualterm.util.Fs;

/**
 * 多容器 / 多镜像的元数据与文件布局。
 *
 * 目录：
 *   files/container/
 *     images/alpine-3.20/rootfs/     ← 镜像（只读模板，解包自 assets 或 pull 下载）
 *     containers/default/rootfs/     ← 容器（镜像的一份可写副本）
 *     containers/default/home/       ← 挂到容器内 /root
 *
 * 语义对齐 docker：
 *   image   是模板，容器从镜像"物化"出一份可写 rootfs，互不影响；
 *   删镜像不影响已存在的容器（容器自带副本），这点和 docker 一致。
 */
public final class Containers {

    public static final String DEFAULT_IMAGE = "alpine:3.20";
    public static final String DEFAULT_CONTAINER = "default";

    private static final String PREF = "dualterm";
    private static final String KEY_IMAGES = "images";
    private static final String KEY_ITEMS = "containers";
    private static final String KEY_SIZES = "image_sizes";
    private static final String KEY_ACTIVE = "active_container";
    private static final String KEY_MIRROR_HUB = "mirror_hub";       // Docker Hub 镜像源，空=官方
    private static final String KEY_MIRROR_ALPINE = "mirror_alpine"; // Alpine CDN 镜像源，空=dl-cdn

    /** 当前有活会话的容器名（运行态不落盘，进程死了就是没在跑）。 */
    private static final Set<String> RUNNING = Collections.synchronizedSet(new HashSet<String>());

    private Containers() {
    }

    /* -------------------- 数据结构 -------------------- */

    public static final class Image {
        public final String name;   // alpine:3.20
        public final File rootfs;
        public final long createdAt;

        Image(String name, File rootfs, long createdAt) {
            this.name = name;
            this.rootfs = rootfs;
            this.createdAt = createdAt;
        }

        public long size() {
            return dirSize(rootfs);
        }
    }

    public static final class Item {
        public final String name;
        public final String image;
        public final long createdAt;

        Item(String name, String image, long createdAt) {
            this.name = name;
            this.image = image;
            this.createdAt = createdAt;
        }

        public File dir(Context ctx) {
            return new File(containersDir(ctx), name);
        }

        public File rootfs(Context ctx) {
            return new File(dir(ctx), "rootfs");
        }

        public File home(Context ctx) {
            return new File(dir(ctx), "home");
        }

        public boolean isRunning() {
            return RUNNING.contains(name);
        }
    }

    /* -------------------- 路径 -------------------- */

    public static File containerDir(Context ctx) {
        return new File(ctx.getFilesDir(), "container");
    }

    public static File imagesDir(Context ctx) {
        return new File(containerDir(ctx), "images");
    }

    public static File containersDir(Context ctx) {
        return new File(containerDir(ctx), "containers");
    }

    /** 镜像名的目录写法：alpine:3.20 → alpine-3.20 */
    private static String dirName(String ref) {
        return ref.replace(':', '-').replace('/', '_');
    }

    public static File imageRootfs(Context ctx, String ref) {
        return new File(new File(imagesDir(ctx), dirName(ref)), "rootfs");
    }

    /* -------------------- 存储 -------------------- */

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /* -------------------- 镜像源（换源） --------------------
     * 存归一化后的 base（https://host[:port]/path，无尾斜杠）；空串=官方直连。
     * Hub 源拼 /v2/ 端点；Alpine 源是含 /alpine 的完整目录前缀，
     * 例：https://mirrors.tuna.tsinghua.edu.cn/alpine */

    /** Docker Hub 镜像源；未配置返回空串（官方直连）。 */
    public static String hubMirror(Context ctx) {
        String v = prefs(ctx).getString(KEY_MIRROR_HUB, "");
        return v == null ? "" : v;
    }

    /** Alpine 官方 CDN 的镜像源；未配置返回空串（dl-cdn.alpinelinux.org）。 */
    public static String alpineMirror(Context ctx) {
        String v = prefs(ctx).getString(KEY_MIRROR_ALPINE, "");
        return v == null ? "" : v;
    }

    /** 设置 Hub 镜像源；传空/clear 恢复官方。返回归一化后的值。 */
    public static String setHubMirror(Context ctx, String url) throws IOException {
        String v = RegistryPuller.normalizeMirror(url);
        prefs(ctx).edit().putString(KEY_MIRROR_HUB, v).apply();
        return v;
    }

    /** 设置 Alpine 镜像源；传空/clear 恢复官方。返回归一化后的值。 */
    public static String setAlpineMirror(Context ctx, String url) throws IOException {
        String v = RegistryPuller.normalizeMirror(url);
        prefs(ctx).edit().putString(KEY_MIRROR_ALPINE, v).apply();
        return v;
    }

    public static List<Image> images(Context ctx) {
        List<Image> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_IMAGES, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                String[] p = s.split("\\|", -1);
                if (p.length < 3) {
                    continue;
                }
                File rootfs = new File(p[1]);
                if (!isRootfs(rootfs)) {
                    continue; // 目录被删了就把这条当不存在
                }
                out.add(new Image(p[0], rootfs, parseLong(p[2])));
            }
        }
        return out;
    }

    /** 目录里像不像一个根文件系统（bin/usr/etc 任一存在即可，避免把 debian 这类 /bin 是链接的镜像漏掉）。 */
    private static boolean isRootfs(File rootfs) {
        return new File(rootfs, "bin").isDirectory()
                || new File(rootfs, "usr").isDirectory()
                || new File(rootfs, "etc").isDirectory();
    }

    /* -------------------- 镜像大小缓存 -------------------- */

    /**
     * dirSize 要递归数几万个文件，绝不能在 UI 线程调（ANR 风险）。所以在导入/
     * 拉取完成时（本来就在后台线程）算一次缓存起来，UI 只读缓存；缓存缺失
     * 返回 -1，由调用方决定显示「…」还是触发后台刷新。
     */
    public static long cachedImageSize(Context ctx, String ref) {
        String want = normalizeImage(ref);
        String raw = prefs(ctx).getString(KEY_SIZES, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                int bar = line.lastIndexOf('|');
                if (bar > 0 && line.substring(0, bar).equals(want)) {
                    try {
                        return Long.parseLong(line.substring(bar + 1));
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    /** 写一条大小缓存；bytes < 0 表示删除该条（镜像被删时调用）。 */
    public static void cacheImageSize(Context ctx, String ref, long bytes) {
        String want = normalizeImage(ref);
        StringBuilder sb = new StringBuilder();
        String raw = prefs(ctx).getString(KEY_SIZES, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                int bar = line.lastIndexOf('|');
                if (bar <= 0) {
                    continue;
                }
                String name = line.substring(0, bar);
                if (name.equals(want)) {
                    continue; // 覆盖 / 删除
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        if (bytes >= 0) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(want).append('|').append(bytes);
        }
        prefs(ctx).edit().putString(KEY_SIZES, sb.toString()).apply();
    }

    /** 逐个镜像补齐缺失的大小缓存（后台线程调用）。返回是否写入过。 */
    public static boolean refreshImageSizes(Context ctx) {
        boolean changed = false;
        for (Image img : images(ctx)) {
            if (cachedImageSize(ctx, img.name) < 0) {
                cacheImageSize(ctx, img.name, dirSize(img.rootfs));
                changed = true;
            }
        }
        return changed;
    }

    public static Image findImage(Context ctx, String ref) {
        String want = normalizeImage(ref);
        for (Image img : images(ctx)) {
            if (img.name.equals(want)) {
                return img;
            }
        }
        return null;
    }

    /** 补全默认 tag / 常见简写：alpine → alpine:latest；alpine:3.20 → alpine:3.20 */
    public static String normalizeImage(String ref) {
        String s = ref.trim();
        if (!s.contains(":")) {
            s = s + ":latest";
        }
        return s;
    }

    /**
     * 镜像 ref 白名单。ref 会成为镜像目录名（dirName 只替换 : 和 /，其余字符原样
     * 落盘），还会被 Docker 后端拼进 su -c——所以来自外部 tar 的 ref 必须过这里。
     */
    public static boolean validImageRef(String ref) {
        return ref != null && ref.matches("[A-Za-z0-9][A-Za-z0-9_.:/-]{0,127}");
    }

    public static List<Item> items(Context ctx) {
        List<Item> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_ITEMS, "");
        if (raw != null) {
            for (String line : raw.split("\n")) {
                String s = line.trim();
                if (s.isEmpty()) {
                    continue;
                }
                String[] p = s.split("\\|", -1);
                if (p.length < 3) {
                    continue;
                }
                out.add(new Item(p[0], p[1], parseLong(p[2])));
            }
        }
        return out;
    }

    public static Item find(Context ctx, String name) {
        for (Item item : items(ctx)) {
            if (item.name.equals(name)) {
                return item;
            }
        }
        return null;
    }

    private static void writeImages(Context ctx, List<Image> list) {
        StringBuilder sb = new StringBuilder();
        for (Image img : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(img.name).append('|')
                    .append(img.rootfs.getAbsolutePath()).append('|')
                    .append(img.createdAt);
        }
        prefs(ctx).edit().putString(KEY_IMAGES, sb.toString()).apply();
    }

    private static void writeItems(Context ctx, List<Item> list) {
        StringBuilder sb = new StringBuilder();
        for (Item item : list) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(item.name).append('|')
                    .append(item.image).append('|')
                    .append(item.createdAt);
        }
        prefs(ctx).edit().putString(KEY_ITEMS, sb.toString()).apply();
    }

    public static void registerImage(Context ctx, String ref, File rootfs) {
        String name = normalizeImage(ref);
        List<Image> list = images(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.set(i, new Image(name, rootfs, list.get(i).createdAt));
                writeImages(ctx, list);
                cacheImageSize(ctx, name, dirSize(rootfs));
                return;
            }
        }
        list.add(new Image(name, rootfs, System.currentTimeMillis() / 1000));
        writeImages(ctx, list);
        cacheImageSize(ctx, name, dirSize(rootfs));
    }

    /* -------------------- 容器生命周期 -------------------- */

    /** 从镜像物化出一个新容器（复制一份可写 rootfs）。返回容器名。 */
    public static Item create(Context ctx, String name, String imageRef) throws IOException {
        String candidate = sanitize(name);
        Item existing = find(ctx, candidate);
        if (existing != null) {
            throw new IOException("容器名已存在：" + candidate);
        }
        Image img = findImage(ctx, imageRef);
        if (img == null) {
            throw new IOException("镜像不存在：" + imageRef + "（先 docker pull 或重新安装应用）");
        }
        String bad = archProblem(img.rootfs);
        if (bad != null) {
            throw new IOException("镜像 " + img.name + " 不能用：" + bad
                    + "\n换一个镜像：docker rmi " + img.name + " 后用 docker pull 重新拉取");
        }

        File dir = new File(containersDir(ctx), candidate);
        File rootfs = new File(dir, "rootfs");
        if (dir.exists()) {
            deleteRecursive(dir, containersDir(ctx));
        }
        Fs.ensureDir(rootfs);
        copyTree(img.rootfs, rootfs);
        Fs.ensureDir(new File(dir, "home"));

        // 容器自己的 resolv.conf（镜像是只读模板，不能指望它带着 DNS 配置）
        writeText(new File(rootfs, "etc/resolv.conf"), DNS_CONF);
        for (String d : new String[]{"root", "tmp", "dev", "proc", "sys", "var/run"}) {
            Fs.ensureDir(new File(rootfs, d));
        }
        stripLegacyDockerShim(rootfs);

        List<Item> list = items(ctx);
        list.add(new Item(candidate, img.name, System.currentTimeMillis() / 1000));
        writeItems(ctx, list);
        return find(ctx, candidate);
    }

    /** 用原镜像重建容器：删掉可写 rootfs 再物化一份干净的（宿主绑定目录不受影响）。 */
    public static Item recreate(Context ctx, String name) throws IOException {
        Item item = find(ctx, name);
        if (item == null) {
            throw new IOException("容器不存在：" + name);
        }
        String image = item.image;
        deleteRecursive(item.dir(ctx), containersDir(ctx));
        List<Item> list = items(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.remove(i);
                break;
            }
        }
        writeItems(ctx, list);
        return create(ctx, name, image);
    }

    public static boolean remove(Context ctx, String name) {
        Item item = find(ctx, name);
        if (item == null) {
            return false;
        }
        File dir = item.dir(ctx);
        deleteRecursive(dir, containersDir(ctx));
        if (dir.exists()) {
            // 目录还被 proot 占着就删不掉，这时不要把注册表清掉，否则会留下看不见的孤儿目录
            return false;
        }
        List<Item> list = items(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.remove(i);
                break;
            }
        }
        writeItems(ctx, list);
        RUNNING.remove(name);
        // 同名重建不该继承旧容器声明的端口
        Ports.clear(ctx, name);
        if (name.equals(activeName(ctx))) {
            // 删除后的活动容器落位是删除语义的一部分，收口在存储层：
            // 落到剩余第一个，空册落 ""（不自动重建）。菜单与控制台两个入口
            // 不用各写一份善后，也不会漂移。
            String next = list.isEmpty() ? "" : list.get(0).name;
            prefs(ctx).edit().putString(KEY_ACTIVE, next).apply();
        }
        return true;
    }

    /** 默认容器名：c1、c2…跳过已占用的（菜单新建与控制台 run 共用一条规则）。 */
    public static String suggestName(Context ctx) {
        List<Item> list = items(ctx);
        for (int i = 1; ; i++) {
            String candidate = "c" + i;
            boolean taken = false;
            for (Item it : list) {
                if (it.name.equals(candidate)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                return candidate;
            }
        }
    }

    public static void removeImage(Context ctx, String ref) throws IOException {
        String name = normalizeImage(ref);
        for (Item item : items(ctx)) {
            if (item.image.equals(name)) {
                throw new IOException("还有容器（" + item.name + "）基于该镜像，先 docker rm 它们");
            }
        }
        Image img = findImage(ctx, name);
        if (img == null) {
            throw new IOException("镜像不存在：" + name);
        }
        deleteRecursive(img.rootfs.getParentFile(), imagesDir(ctx));
        cacheImageSize(ctx, name, -1); // 大小缓存一并清掉
        List<Image> list = images(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equals(name)) {
                list.remove(i);
                break;
            }
        }
        writeImages(ctx, list);
    }

    public static void setRunning(String name, boolean running) {
        if (running) {
            RUNNING.add(name);
        } else {
            RUNNING.remove(name);
        }
    }

    public static void clearRunning() {
        RUNNING.clear();
    }

    public static List<Item> runningItems(Context ctx) {
        List<Item> out = new ArrayList<>();
        for (Item item : items(ctx)) {
            if (item.isRunning()) {
                out.add(item);
            }
        }
        return out;
    }

    /* -------------------- 活动容器 -------------------- */

    public static String activeName(Context ctx) {
        String name = prefs(ctx).getString(KEY_ACTIVE, DEFAULT_CONTAINER);
        return name == null || name.isEmpty() ? DEFAULT_CONTAINER : name;
    }

    public static void setActive(Context ctx, String name) {
        prefs(ctx).edit().putString(KEY_ACTIVE, name).apply();
    }

    /* -------------------- 首次初始化 -------------------- */

    /**
     * 幂等：迁移旧布局、自愈损坏的容器。**不再负责创建镜像和容器**——
     * APK 不随包内置 rootfs（体积），全新安装从这里出来就是空册，
     * 镜像由用户 pull / 导入 tar 获得，容器再从镜像物化。
     * 老的布局是 container/rootfs（v1.x），直接 rename 成镜像目录，省一次解包。
     */
    public static void ensureBootstrap(Context ctx, ContainerRuntime.Progress progress) throws IOException {
        Fs.ensureDir(imagesDir(ctx));
        Fs.ensureDir(containersDir(ctx));

        if (images(ctx).isEmpty()) {
            // 只做旧版升级迁移；assets 里已经没有 rootfs pack，全新安装保持空册
            File target = imageRootfs(ctx, DEFAULT_IMAGE);
            File old = ContainerBootstrap.rootfsDir(ctx);
            File marker = new File(ContainerBootstrap.containerDir(ctx), ".rootfs-" + ContainerBootstrap.abi() + ".ok");

            if (marker.isFile() && existsInRootfs(old, "bin/sh")
                    && new File(old, "etc/alpine-release").exists()) {
                report(progress, "迁移已有根文件系统…");
                File parent = target.getParentFile();
                Fs.ensureDir(parent);
                if (target.exists()) {
                    deleteRecursive(target, imagesDir(ctx));
                }
                if (!old.renameTo(target)) {
                    copyTree(old, target);
                    deleteRecursive(old, ContainerBootstrap.containerDir(ctx));
                }
                registerImage(ctx, DEFAULT_IMAGE, target);
            }
        }

        // 自愈：早期版本复制 rootfs 时会把绝对符号链接（/bin/sh -> /bin/busybox）当断链丢掉，
        // 结果容器里只剩 busybox 没有 sh，根本起不来。这种 rootfs 本来就没法用，重建即可（home 保留）。
        for (Item item : new ArrayList<>(items(ctx))) {
            File rf = item.rootfs(ctx);
            if (!rf.isDirectory()
                    || (!existsInRootfs(rf, "bin/sh") && !existsInRootfs(rf, "bin/busybox"))) {
                Image img = findImage(ctx, item.image);
                if (img == null) {
                    continue;
                }
                report(progress, "修复容器 " + item.name + " 的根文件系统…");
                try {
                    deleteRecursive(rf, containersDir(ctx));
                    Fs.ensureDir(rf);
                    copyTree(img.rootfs, rf);
                    writeText(new File(rf, "etc/resolv.conf"), DNS_CONF);
                    for (String d : new String[]{"root", "tmp", "dev", "proc", "sys", "var/run"}) {
                        Fs.ensureDir(new File(rf, d));
                    }
                    stripLegacyDockerShim(rf);
                } catch (IOException ignored) {
                    // 修不了就保持原样，启动时会给出明确报错
                }
            }
        }

        // 自愈：v2.2 早期构建里 Item.dir 用错了父目录，把容器物化到了 container/<name>。
        // 规范副本在 containers/<name>，这里的重复内容直接清掉，免得白占一倍空间。
        for (Item item : items(ctx)) {
            File stray = new File(new File(containerDir(ctx), item.name), "rootfs");
            if (stray.isDirectory() && existsInRootfs(item.rootfs(ctx), "bin/sh")) {
                report(progress, "清理重复的容器目录 " + item.name + "…");
                deleteRecursive(stray.getParentFile(), containerDir(ctx));
            }
        }

        // 孤儿目录：注册表里没有、但磁盘上躺着 rootfs 的容器目录。
        // 来自早期版本 rm 半途失败，或创建过程中被系统杀掉——留着只占空间，还查不出来。
        File[] dirs = containersDir(ctx).listFiles();
        if (dirs != null) {
            Set<String> known = new HashSet<>();
            for (Item it : items(ctx)) {
                known.add(it.name);
            }
            for (File d : dirs) {
                if (!d.isDirectory() || known.contains(d.getName())) {
                    continue;
                }
                if (new File(d, "rootfs").isDirectory()) {
                    report(progress, "清理孤儿容器 " + d.getName() + "…");
                    deleteRecursive(d, containersDir(ctx));
                }
            }
        }

        // 空册是合法状态：不自动建默认容器，镜像由用户 pull / 导入后再物化容器。
        // 老版本把 home 放在 container/home，升级用户已有容器，这里兜底搬一次。
        if (!items(ctx).isEmpty()) {
            File oldHome = ContainerBootstrap.homeDir(ctx);
            if (oldHome.isDirectory()) {
                File newHome = find(ctx, activeName(ctx)) != null
                        ? new File(containersDir(ctx), activeName(ctx) + "/home")
                        : null;
                if (newHome != null && newHome.isDirectory()) {
                    File[] children = oldHome.listFiles();
                    if (children != null && children.length > 0 && newHome.listFiles() != null
                            && newHome.listFiles().length == 0) {
                        report(progress, "迁移旧的工作目录…");
                        try {
                            copyTree(oldHome, newHome);
                        } catch (IOException ignored) {
                            // 迁移失败不影响使用
                        }
                    }
                }
            }
        }

        if (items(ctx).isEmpty()) {
            // 没有容器就没有活动容器可言，清掉残留的活动标记
            prefs(ctx).edit().remove(KEY_ACTIVE).apply();
        } else if (find(ctx, activeName(ctx)) == null) {
            setActive(ctx, items(ctx).get(0).name);
        }

        // 清掉早期版本塞进 rootfs 的 docker 转发脚本（管理入口已收到 App 控制台）
        for (Image img : images(ctx)) {
            stripLegacyDockerShim(img.rootfs);
        }
        for (Item item : items(ctx)) {
            stripLegacyDockerShim(item.rootfs(ctx));
        }
    }

    /* -------------------- pull -------------------- */

    private static final Pattern MINIROOTFS =
            Pattern.compile("alpine-minirootfs-(\\d+\\.\\d+\\.\\d+)-([a-z0-9_]+)\\.tar\\.gz");

    /**
     * 镜像拉取唯一入口（UI 拉取对话框与控制台 docker pull 共用）。
     * Alpine 版本标签（3.21 这类）/ latest / 空 走官方 CDN 快路径——免鉴权、
     * 单文件、已在用；其余引用交给 Docker Hub 拉取器（RegistryPuller）。
     * 分发收在这里，两个入口不用各写一份。
     */
    public static String pull(Context ctx, String tag, ContainerRuntime.Progress progress) throws IOException {
        // 容忍 UI/CLI 传进来的各种写法：3.22 / alpine:3.22 / alpine
        String t = tag == null ? "" : tag.trim();
        if (t.startsWith("alpine:")) {
            t = t.substring("alpine:".length());
        } else if ("alpine".equals(t)) {
            t = "latest";
        }
        boolean latest = t.isEmpty() || "latest".equals(t);
        boolean fastPath = latest || t.matches("\\d+\\.\\d+");
        if (!fastPath) {
            return RegistryPuller.pull(ctx, tag.trim(), progress);
        }
        // latest 走官方的 latest-stable 软链，别写死某个版本号，否则越用越旧
        String branch = latest ? "latest-stable" : "v" + t;
        if (!latest && !branch.matches("v\\d+\\.\\d+")) {
            throw new IOException("只支持 Alpine 版本标签，例如 docker pull alpine:3.21");
        }
        String arch;
        String abi = ContainerBootstrap.abi();
        if (ContainerBootstrap.ABI_ARM64.equals(abi)) {
            arch = "aarch64";
        } else if (ContainerBootstrap.ABI_ARM.equals(abi)) {
            arch = "armhf";   // Alpine 的 32 位 arm 目录叫 armhf，不是 armv7
        } else if (ContainerBootstrap.ABI_X86_64.equals(abi)) {
            arch = "x86_64";
        } else {
            arch = "aarch64";
        }

        String cdn = alpineMirror(ctx);
        String base = (cdn.isEmpty() ? "https://dl-cdn.alpinelinux.org/alpine" : cdn)
                + "/" + branch + "/releases/" + arch + "/";
        report(progress, (cdn.isEmpty() ? "查询 " : "经镜像源 " + cdn + " 查询 ")
                + branch + "/" + arch + " 可用版本…");
        String listing;
        try {
            listing = httpText(base);
        } catch (IOException e) {
            throw new IOException("取不到 " + branch + " 的镜像目录（" + e.getMessage() + "）"
                    + "\n版本号是否写错？可用形如 3.20 / 3.24 / latest", e);
        }
        Matcher m = MINIROOTFS.matcher(listing);
        String best = null;
        String bestFile = null;
        while (m.find()) {
            String ver = m.group(1);
            if (best == null || compareVersion(ver, best) > 0) {
                best = ver;
                bestFile = m.group(0);
            }
        }
        if (bestFile == null) {
            throw new IOException("在 " + base + " 找不到 minirootfs（版本号写错了？）");
        }

        String ref = "alpine:" + best.substring(0, best.lastIndexOf('.'));
        File target = imageRootfs(ctx, ref);
        if (new File(target, "bin").isDirectory()) {
            throw new IOException("镜像已存在：" + ref);
        }

        report(progress, "下载 " + bestFile + " …");
        File tmp = new File(ctx.getCacheDir(), bestFile);
        download(base + bestFile, tmp);

        report(progress, "解包镜像…");
        Fs.ensureDir(target);
        try (InputStream in = Files.newInputStream(tmp.toPath())) {
            TarGzExtractor.extract(in, target, null);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();

        // 下载源目录选对了也可能被 CDN/代理串味，落地后按 ELF 头再核一次架构，
        // 免得注册一个跑不起来的镜像（用户只会看到 proot 里 exec 失败）。
        String actual = ImageImporter.detectArch(target);
        String want = "aarch64".equals(arch) ? "aarch64" : ("armhf".equals(arch) ? "arm" : "x86-64");
        if (actual != null && !actual.equals(want)) {
            deleteRecursive(target, imagesDir(ctx));
            throw new IOException("下载到的根文件系统是 " + actual + " 架构，本机为 " + abi
                    + "（期望 " + want + "），已丢弃");
        }

        finalizeImage(ctx, ref, target, progress);
        return ref;
    }

    /**
     * 镜像/容器的 rootfs 架构与本机不符时返回说明，一致或读不出来返回 null。
     * 拉错架构的镜像不会立刻报错，只会在 proot 里 exec 时炸成 SIGBUS，很难看出原因。
     */
    public static String archProblem(File rootfs) {
        String actual = ImageImporter.detectArch(rootfs);
        if (actual == null) {
            return null;
        }
        String device = ContainerBootstrap.abi();
        String want = ContainerBootstrap.ABI_ARM64.equals(device) ? "aarch64"
                : (ContainerBootstrap.ABI_ARM.equals(device) ? "arm" : "x86-64");
        if (actual.equals(want) || ("arm".equals(actual) && "aarch64".equals(want))) {
            return null;
        }
        return "rootfs 是 " + actual + " 架构，本机是 " + device + "（需要 " + want + "）";
    }

    /** 找一个能正常启动的容器（排除 exclude），用于活动容器坏掉时兜底。 */
    public static String firstUsable(Context ctx, String exclude) {
        for (Item item : items(ctx)) {
            if (item.name.equals(exclude)) {
                continue;
            }
            File rf = item.rootfs(ctx);
            if (!existsInRootfs(rf, "bin/sh") && !existsInRootfs(rf, "bin/busybox")) {
                continue;
            }
            if (archProblem(rf) != null) {
                continue;
            }
            return item.name;
        }
        return null;
    }

    /** Android 没有 /etc/resolv.conf，镜像里得自己塞一份。 */
    public static final String DNS_CONF =
            "nameserver 223.5.5.5\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n";

    /**
     * 镜像落地后的统一收尾：DNS、proot 需要存在的绑定目录、docker shim，最后登记到镜像表。
     * assets 解包 / pull / tar 导入三条路径都走这里，避免各自漏步骤。
     */
    public static void finalizeImage(Context ctx, String ref, File rootfs,
                                     ContainerRuntime.Progress progress) {
        report(progress, "写入 DNS 与容器目录…");
        writeText(new File(rootfs, "etc/resolv.conf"), DNS_CONF);
        for (String d : new String[]{"root", "tmp", "dev", "proc", "sys", "var/run"}) {
            Fs.ensureDir(new File(rootfs, d));
        }
        registerImage(ctx, ref, rootfs);
    }

    private static String httpText(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "dualterm");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            // JDK 在 404 时抛的 FileNotFoundException 只有 URL 当消息，看不出是「版本不存在」
            throw new IOException("HTTP " + code + " " + url);
        }
        try (InputStream in = conn.getInputStream()) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 * 1024 * 1024) {
                    break;
                }
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            conn.disconnect();
        }
    }

    private static void download(String url, File dest) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "dualterm");
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new IOException("下载失败 HTTP " + code + "：" + url);
        }
        Fs.ensureDir(dest.getParentFile());
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(dest.toPath())) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static int compareVersion(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? (int) parseLong(x[i]) : 0;
            int yi = i < y.length ? (int) parseLong(y[i]) : 0;
            if (xi != yi) {
                return xi - yi;
            }
        }
        return 0;
    }

    /* -------------------- 旧版 docker 脚本清理 -------------------- */

    /**
     * 早期版本往 rootfs 里塞过 /usr/local/bin/docker（一个转发脚本）。现在管理入口
     * 收到 App 内的控制台，容器里不该再有任何 docker 命令——它既会盖掉镜像自带的
     * 真 docker，也可能被 apk add docker 顶掉，行为和预期完全不可控。
     *
     * 只删「确认是我们写的」那一个（脚本里有 DualTerm 标记），用户自己装的 docker 不动。
     */
    public static void stripLegacyDockerShim(File rootfs) {
        File shim = new File(rootfs, "usr/local/bin/docker");
        if (shim.isFile() && shim.length() <= 4096) {
            try {
                String head = new String(Files.readAllBytes(shim.toPath()), "UTF-8");
                if (head.contains("DualTerm")) {
                    //noinspection ResultOfMethodCallIgnored
                    shim.delete();
                }
            } catch (Exception ignored) {
                // 读不了就当成不是我们的
            }
        }
        // 旧版绑定留下的信箱目录，一起收掉（/var/run 在 Alpine 里是指向 /run 的链接）
        for (String rel : new String[]{"run/dualterm", "var/run/dualterm"}) {
            File stale = new File(rootfs, rel);
            if (!stale.isDirectory()) {
                continue;
            }
            // PRoot 建这个目录时权限是 000，先放开再删
            //noinspection ResultOfMethodCallIgnored
            stale.setReadable(true, true);
            stale.setExecutable(true, true);
            File[] kids = stale.listFiles();
            boolean ours = true;
            if (kids != null) {
                for (File kid : kids) {
                    String n = kid.getName();
                    if (!n.endsWith(".req") && !n.endsWith(".resp")
                            && !n.endsWith(".log") && !n.endsWith(".tmp")) {
                        ours = false;
                        break;
                    }
                }
            }
            if (!ours) {
                continue;
            }
            if (kids != null) {
                for (File kid : kids) {
                    //noinspection ResultOfMethodCallIgnored
                    kid.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            stale.delete();
        }
    }

    /* -------------------- 工具 -------------------- */

    public static void copyTree(File src, File dst) throws IOException {
        copyTree(src, dst, src);
    }

    /**
     * 判断 rootfs 里某个路径「存在」——不能用 File.exists()：
     * Alpine 的 /bin/sh 是指向 /bin/busybox 的绝对链接，嵌在宿主目录里就是断链，
     * exists() 会跟着链接去宿主根目录找，自然返回 false。
     */
    public static boolean existsInRootfs(File rootfs, String relative) {
        File f = new File(rootfs, relative);
        if (f.exists()) {
            return true;
        }
        try {
            return Files.isSymbolicLink(f.toPath());
        } catch (Exception e) {
            return false;
        }
    }

    /** 建不了符号链接时，把目标实体化复制过来（绝对目标按 srcRoot 解析）。 */
    private static void materializeLink(File src, File dst, File srcRoot) {
        try {
            Path target = Files.readSymbolicLink(src.toPath());
            String ts = target.toString();
            File resolved = ts.startsWith("/")
                    ? new File(srcRoot, ts.substring(1))
                    : new File(src.getParentFile(), ts);
            if (resolved.isFile()) {
                Fs.ensureDir(dst.getParentFile());
                Files.copy(resolved.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
                //noinspection ResultOfMethodCallIgnored
                dst.setExecutable(resolved.canExecute(), true);
            } else if (resolved.isDirectory()) {
                Fs.ensureDir(dst);
            }
        } catch (Exception ignored) {
            // 复制不了就留空，启动时会有更明确的报错
        }
    }

    /**
     * 递归复制一棵目录树，符号链接按链接本身复制（不跟随）。
     *
     * 注意：绝对链接（Alpine 里 /bin/sh -> /bin/busybox）在宿主上看是"断链"，
     * File.exists() 会返回 false —— 所以这里绝不能拿 exists() 当入口判断，
     * 否则整棵树的链接都会被静默丢掉，容器里就只剩一个 busybox 而没有 sh。
     */
    private static void copyTree(File src, File dst, File srcRoot) throws IOException {
        Path srcPath = src.toPath();
        boolean link = false;
        try {
            link = Files.isSymbolicLink(srcPath);
        } catch (Exception ignored) {
            // 某些实现不支持，走下面的 readSymbolicLink 兜底判断
        }
        if (!link) {
            try {
                if (!src.exists()) {
                    // 可能是断链（绝对链接）：能读出链接目标就按链接处理
                    Files.readSymbolicLink(srcPath);
                    link = true;
                }
            } catch (Exception e) {
                return; // 真的不存在
            }
        }

        if (link) {
            Fs.ensureDir(dst.getParentFile());
            try {
                Path target = Files.readSymbolicLink(srcPath);
                //noinspection ResultOfMethodCallIgnored
                dst.delete();
                Files.createSymbolicLink(dst.toPath(), target);
                return;
            } catch (Exception e) {
                // 建不了链接就退化成实体拷贝，容器照样能跑（busybox 靠 argv[0] 分派）
                materializeLink(src, dst, srcRoot);
                return;
            }
        }
        if (src.isDirectory()) {
            Fs.ensureDir(dst);
            File[] children = src.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyTree(child, new File(dst, child.getName()), srcRoot);
                }
            }
            return;
        }
        Fs.ensureDir(dst.getParentFile());
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING,
                LinkOption.NOFOLLOW_LINKS);
    }

    public static long dirSize(File dir) {
        if (dir == null || !dir.exists()) {
            return 0;
        }
        if (dir.isFile()) {
            return dir.length();
        }
        long total = 0;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                total += child.isDirectory() && !Files.isSymbolicLink(child.toPath())
                        ? dirSize(child) : child.length();
            }
        }
        return total;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + "B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1fKB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1fMB", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.US, "%.2fGB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 删除 target（必须位于 guardRoot 之下，防止误删）。 */
    public static void deleteRecursive(File target, File guardRoot) {
        // 用绝对路径判断边界，不能用 getCanonicalPath：
        // rootfs 里 bin/sh -> /bin/busybox 这类绝对链接会被解析到 guard 之外，
        // 一旦据此提前返回，链接就留下来，父目录非空也就删不掉（残留目录会一直占空间）。
        String root = guardRoot.getAbsoluteFile().toPath().normalize().toString();
        String path = target.getAbsoluteFile().toPath().normalize().toString();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) {
            return;
        }
        if (Files.isSymbolicLink(target.toPath())) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child, guardRoot);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }

    private static void writeText(File file, String content) {
        try {
            Fs.ensureDir(file.getParentFile());
            Files.write(file.toPath(), content.getBytes("UTF-8"));
        } catch (IOException ignored) {
            // 写不进去时上层会在启动时暴露更具体的错误
        }
    }

    private static void report(ContainerRuntime.Progress progress, String message) {
        if (progress != null) {
            progress.onProgress(message);
        }
    }

    /** 容器名规则：字母/数字开头，只含字母数字._-，最长 32。不合法直接拒绝，不做静默改名。 */
    private static String sanitize(String name) throws IOException {
        String s = name == null ? "" : name.trim();
        if (!s.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,31}")) {
            throw new IOException("容器名不合法：" + s
                    + "（要求：字母或数字开头，只含字母/数字/._-，不超过 32 字符）");
        }
        return s;
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
