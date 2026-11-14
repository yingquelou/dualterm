package dev.dualterm.cli;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.dualterm.container.ContainerRuntime;
import dev.dualterm.container.Containers;
import dev.dualterm.container.ImageImporter;
import dev.dualterm.container.Volumes;

/**
 * 内置 docker CLI 的一个子集，只做「容器 / 镜像管理」这件事。
 *
 * 唯一入口是 App 内的管理控制台（ConsoleActivity）：命令由 App 自己解析执行，
 * 容器里既没有 docker 脚本也没有任何桩，所以不会跟镜像自带的命令重名、也不会被
 * apk add / 手工改动顶掉。
 *
 * 不支持的东西（明确说清楚，避免误导）：
 *   build / commit / push / 网络管理 / compose —— 这些需要真 dockerd。
 *   这里管的是 PRoot 容器：每个容器是一份独立的 rootfs 副本，镜像只是模板。
 */
public final class DockerCli {

    private DockerCli() {
    }

    /* -------------------- 入口 -------------------- */

    public static String handle(Context ctx, String[] argv) {
        if (argv.length == 0) {
            return help();
        }
        String cmd = argv[0];
        String[] rest = new String[argv.length - 1];
        System.arraycopy(argv, 1, rest, 0, rest.length);

        try {
            switch (cmd) {
                case "version":
                case "--version":
                case "-v":
                    return version();
                case "info":
                    return info(ctx);
                case "images":
                case "image":
                    return images(ctx, rest);
                case "ps":
                    return ps(ctx, rest);
                case "run":
                    return run(ctx, rest);
                case "start":
                    return lifecycle(ctx, "start", rest);
                case "stop":
                    return lifecycle(ctx, "stop", rest);
                case "rm":
                case "remove":
                    return rm(ctx, rest);
                case "rmi":
                    return rmi(ctx, rest);
                case "pull":
                    return pull(ctx, rest);
                case "load":
                    return load(ctx, rest);
                case "import":
                    return importCmd(ctx, rest);
                case "help":
                case "--help":
                    return help();
                default:
                    return "docker: '" + cmd + "' 不是本内置 CLI 支持的命令。\n" + help();
            }
        } catch (Exception e) {
            return "docker: " + e.getMessage() + "\n";
        }
    }

    /* -------------------- 信息类 -------------------- */

    private static String version() {
        return "DualTerm 内置 docker CLI（PRoot 后端子集）\n"
                + " Version:   1.8\n"
                + " Runtime:   proot (fake root, filesystem isolation)\n"
                + " API:       1.41 (compatible subset)\n"
                + " 说明:      这不是真 dockerd，不支持 build/push/compose。\n";
    }

    private static String info(Context ctx) {
        List<Containers.Item> items = Containers.items(ctx);
        int running = Containers.runningItems(ctx).size();
        List<Containers.Image> images = Containers.images(ctx);
        long total = 0;
        for (Containers.Image img : images) {
            total += img.size();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Containers: ").append(items.size()).append('\n');
        sb.append(" Running: ").append(running).append('\n');
        sb.append(" Paused: 0\n");
        sb.append(" Stopped: ").append(items.size() - running).append('\n');
        sb.append("Images: ").append(images.size()).append('\n');
        sb.append("Storage: ").append(Containers.humanSize(total)).append(" (镜像总占用)\n");
        sb.append("Runtime: proot (no root required, filesystem isolation)\n");
        sb.append("Active:  ").append(Containers.activeName(ctx)).append('\n');
        sb.append("Bind:    ").append(Volumes.list(ctx).size()).append(" 个目录\n");
        return sb.toString();
    }

    private static String images(Context ctx, String[] args) {
        List<Containers.Image> list = Containers.images(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append(pad("REPOSITORY", 16)).append(pad("TAG", 10))
                .append(pad("IMAGE ID", 14)).append(pad("SIZE", 10)).append("CREATED\n");
        if (list.isEmpty()) {
            sb.append("（还没有镜像）\n");
            return sb.toString();
        }
        for (Containers.Image img : list) {
            String[] name = splitRef(img.name);
            sb.append(pad(name[0], 16)).append(pad(name[1], 10))
                    .append(pad(idOf(img.name), 14))
                    .append(pad(Containers.humanSize(img.size()), 10))
                    .append(ago(img.createdAt)).append('\n');
        }
        return sb.toString();
    }

    private static String ps(Context ctx, String[] args) {
        boolean all = false;
        for (String a : args) {
            if ("-a".equals(a) || "--all".equals(a)) {
                all = true;
            }
        }
        List<Containers.Item> items = Containers.items(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append(pad("CONTAINER ID", 14)).append(pad("IMAGE", 16))
                .append(pad("NAME", 14)).append(pad("STATUS", 16)).append("CREATED\n");
        boolean any = false;
        for (Containers.Item item : items) {
            boolean running = item.isRunning();
            if (!all && !running) {
                continue;
            }
            any = true;
            sb.append(pad(idOf(item.name), 14))
                    .append(pad(item.image, 16))
                    .append(pad(item.name, 14))
                    .append(pad(running ? "Up (this session)" : "Exited", 16))
                    .append(ago(item.createdAt)).append('\n');
        }
        if (!any) {
            sb.append(all ? "（还没有容器，用 docker run 创建）\n" : "（没有运行中的容器，加 -a 看全部）\n");
        }
        return sb.toString();
    }

    /* -------------------- 生命周期 -------------------- */

    private static String run(Context ctx, String[] args) throws IOException {
        String name = null;
        String image = null;
        List<String> cmd = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--name=")) {
                name = a.substring("--name=".length());
            } else if ("--name".equals(a) && i + 1 < args.length) {
                name = args[++i];
            } else if ("-v".equals(a) || "--volume".equals(a)) {
                if (i + 1 < args.length) {
                    addVolume(ctx, args[++i]);
                }
            } else if (a.startsWith("--volume=")) {
                addVolume(ctx, a.substring("--volume=".length()));
            } else if (a.startsWith("-v") && a.length() > 2) {
                addVolume(ctx, a.substring(2));
            } else if (a.startsWith("-")) {
                // -it / -d / --rm / -e 等：PRoot 后端无对应概念，忽略
            } else if (image == null) {
                image = a;
            } else {
                cmd.add(a);
            }
        }
        if (image == null) {
            return "docker: 用法 docker run [--name NAME] [-v 宿主:容器] IMAGE\n";
        }
        if (name == null || name.isEmpty()) {
            name = "c" + (System.currentTimeMillis() / 1000 % 100000);
        }

        Containers.Item item = Containers.create(ctx, name, image);
        StringBuilder sb = new StringBuilder();
        String target = Containers.findImage(ctx, image) != null
                ? Containers.findImage(ctx, image).name : Containers.normalizeImage(image);
        sb.append(idOf(item.name)).append('\n');
        sb.append("已创建容器 ").append(item.name).append("（镜像 ").append(target).append("）\n");
        if (!cmd.isEmpty()) {
            sb.append("提示: 本后端不执行一次性命令，进入容器后手动运行：")
                    .append(String.join(" ", cmd)).append('\n');
        }
        sb.append("进入容器：容器管理 → 启动并切换（控制台只管增删，不接管终端）\n");
        return sb.toString();
    }

    private static void addVolume(Context ctx, String spec) throws IOException {
        int bar = spec.indexOf(':');
        String host = bar < 0 ? spec : spec.substring(0, bar);
        String guest = bar < 0 ? "" : spec.substring(bar + 1);
        if (!new File(host).isDirectory()) {
            throw new IOException("宿主路径不存在或不是目录：" + host);
        }
        for (String[] v : Volumes.list(ctx)) {
            if (v[0].equals(host) && (v[1] == null ? "" : v[1]).equals(guest)) {
                return;
            }
        }
        Volumes.add(ctx, host, guest);
    }

    /**
     * start / stop 在本后端里没有独立含义：「运行」就是一个该容器的终端会话，
     * 会话只能由 UI 开、由 UI 关。控制台不越界去动终端，否则就得反过来和 UI 同步状态。
     */
    private static String lifecycle(Context ctx, String verb, String[] args) {
        if (args.length == 0) {
            return "docker: 用法 docker " + verb + " NAME\n";
        }
        String name = args[0];
        Containers.Item item = Containers.find(ctx, name);
        if (item == null) {
            return "docker: 没有这个容器：" + name + "\n";
        }
        if ("stop".equals(verb)) {
            return "docker: 容器的启停由终端会话决定，控制台改不了。\n"
                    + "       请在 容器管理 → " + name + " → 停止。\n";
        }
        return name + "\n启动 = 开一个该容器的终端：容器管理 → " + name + " → 启动并切换。\n";
    }

    private static String rm(Context ctx, String[] args) {
        List<String> names = new ArrayList<>();
        for (String a : args) {
            if (!a.startsWith("-")) {
                names.add(a);
            }
        }
        if (names.isEmpty()) {
            return "docker: 用法 docker rm [-f] NAME...\n";
        }
        StringBuilder sb = new StringBuilder();
        for (String name : names) {
            Containers.Item item = Containers.find(ctx, name);
            if (item == null) {
                sb.append("docker: 没有这个容器：").append(name).append('\n');
                continue;
            }
            // 这条命令是从容器里的 shim 发出来的，删掉自己所在的容器等于把脚下的地板拆了：
            // 会话当场 Hangup，连报错都回不去（还会留下半个目录）。直接拒绝。
            if (name.equals(Containers.activeName(ctx))) {
                sb.append("docker: 不能删除当前终端所在的容器 ").append(name)
                        .append("（先在 容器管理 → 启动并切换 到别的容器）\n");
                continue;
            }
            if (item.isRunning()) {
                // 会话在 UI 手里，控制台没法把它停掉，硬删会留下被 proot 占着的目录
                sb.append("docker: 容器 ").append(name)
                        .append(" 正在运行，先在 容器管理 里停止再删除\n");
                continue;
            }
            if (!Containers.remove(ctx, name)) {
                sb.append("docker: 删除容器 ").append(name).append(" 失败（目录被占用？）\n");
                continue;
            }
            sb.append(name).append('\n');
        }
        return sb.toString();
    }

    private static String rmi(Context ctx, String[] args) throws IOException {
        if (args.length == 0) {
            return "docker: 用法 docker rmi IMAGE\n";
        }
        Containers.removeImage(ctx, args[0]);
        return "已删除镜像 " + Containers.normalizeImage(args[0]) + "\n";
    }

    /* -------------------- 导入归档 -------------------- */

    /**
     * docker load -i x.tar：读 docker save 出来的层叠包（也认 containerd/podman 的 OCI 布局）。
     * 镜像名默认取 manifest 里的 RepoTags，可以用第二个参数覆盖。
     */
    private static String load(Context ctx, String[] args) {
        String path = null;
        String refHint = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-i".equals(a) || "--input".equals(a)) {
                if (i + 1 < args.length) {
                    path = args[++i];
                }
            } else if (a.startsWith("--input=")) {
                path = a.substring("--input=".length());
            } else if (!a.startsWith("-")) {
                if (path == null) {
                    path = a;
                } else if (refHint == null) {
                    refHint = a;
                }
            }
        }
        if (path == null) {
            return "docker: 用法 docker load -i 文件.tar[.gz] [镜像名]\n"
                    + "（不支持从 stdin 读：容器里管道输入过不来，请先落到文件）\n";
        }
        return doImport(ctx, path, refHint, true);
    }

    /**
     * docker import x.tar [名:标签]：读 docker export 出来的扁平 rootfs tar。
     * 也顺手兼容 docker save 的包（自动识别，不去严格区分）。
     */
    private static String importCmd(Context ctx, String[] args) {
        String path = null;
        String refHint = null;
        for (String a : args) {
            if (a.startsWith("-")) {
                continue; // -c/-m 之类的元数据选项本后端不落地
            }
            if (path == null) {
                path = a;
            } else if (refHint == null) {
                refHint = a;
            }
        }
        if (path == null) {
            return "docker: 用法 docker import 文件.tar[.gz] [名:标签]\n";
        }
        return doImport(ctx, path, refHint, false);
    }

    private static String doImport(Context ctx, String path, String refHint, boolean loadMode) {
        File file = resolveCliPath(ctx, path);
        if (file == null || !file.isFile()) {
            return "docker: 找不到文件：" + path
                    + "\n（容器里看到的路径要能对应到真实文件：/sdcard/... 需要存储权限，"
                    + "/root/... 是容器自己的 home）\n";
        }
        if (file.length() == 0) {
            return "docker: 文件是空的：" + path + "\n";
        }
        try {
            ImageImporter.Result r = ImageImporter.importArchive(ctx, file, refHint,
                    new ContainerRuntime.Progress() {
                        @Override
                        public void onProgress(String message) {
                            note(message);
                        }
                    });
            StringBuilder sb = new StringBuilder();
            sb.append("格式: ").append(r.format).append('\n');
            if (r.layers > 1) {
                sb.append("层数: ").append(r.layers).append('\n');
            }
            if (r.note != null) {
                sb.append(r.note).append('\n');
            }
            if (loadMode) {
                sb.append("Loaded image: ").append(r.ref).append('\n');
            } else {
                sb.append("sha256:").append(idOf(r.ref)).append('\n');
                sb.append("已导入镜像 ").append(r.ref).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "docker: 导入失败：" + e.getMessage() + "\n";
        }
    }

    /**
     * 把容器里看到的路径翻成 App 进程能打开的路径：
     * 先按字面找（/sdcard 这类宿主绑定），再按容器的 home、rootfs 兜底。
     */
    private static File resolveCliPath(Context ctx, String path) {
        File direct = new File(path);
        if (direct.isFile()) {
            return direct;
        }
        Containers.Item item = Containers.find(ctx, Containers.activeName(ctx));
        if (item == null) {
            return null;
        }
        String p = path.startsWith("/") ? path : "/" + path;
        if (p.equals("/root") || p.startsWith("/root/")) {
            File home = item.home(ctx);
            File f = new File(home, p.length() <= 6 ? "" : p.substring(6));
            if (f.isFile()) {
                return f;
            }
        }
        File inRootfs = new File(item.rootfs(ctx), p.substring(1));
        if (inRootfs.isFile()) {
            return inRootfs;
        }
        return null;
    }

    /* -------------------- 进度回显 -------------------- */

    /** 长任务（导入镜像）的进度回调，控制台把它直接追加到输出里。 */
    public interface ProgressSink {
        void line(String message);
    }

    private static volatile ProgressSink progressSink;

    public static void setProgressSink(ProgressSink sink) {
        progressSink = sink;
    }

    private static void note(String message) {
        ProgressSink sink = progressSink;
        if (sink == null || message == null) {
            return;
        }
        try {
            sink.line(message);
        } catch (Throwable ignored) {
            // 进度丢一两条不影响结果
        }
    }

    private static String pull(Context ctx, String[] args) {
        if (args.length == 0) {
            return "docker: 用法 docker pull [alpine:]TAG\n";
        }
        String ref = args[0];
        // 支持三种写法：pull 3.24 / pull alpine:3.24 / pull alpine（等价 latest）
        String tag;
        if (ref.contains(":")) {
            tag = ref.substring(ref.indexOf(':') + 1);
        } else if (ref.startsWith("alpine")) {
            tag = "latest";
        } else {
            tag = ref;
        }
        if (tag.isEmpty()) {
            tag = "latest";
        }
        try {
            String pulled = Containers.pull(ctx, tag, null);
            return "Pulled " + pulled + "\n";
        } catch (Exception e) {
            return "docker: pull 失败：" + e.getMessage() + "\n";
        }
    }

    private static String help() {
        return "用法: docker COMMAND\n"
                + "\n"
                + "容器\n"
                + "  ps [-a]                  列出容器（-a 含已停止）\n"
                + "  run [--name N] [-v h:g] IMAGE\n"
                + "                           用镜像创建容器并切换过去\n"
                + "  rm NAME                  删除容器（不动镜像）\n"
                + "  start / stop NAME        只给提示：启停在 容器管理 里做\n"
                + "\n"
                + "镜像\n"
                + "  images                   列出镜像\n"
                + "  pull [alpine:]VERSION    从 Alpine 官方源下载镜像，如 pull alpine:3.21\n"
                + "  load -i FILE [名]        导入 docker save 的 tar（含层叠/whiteout）\n"
                + "  import FILE [名:标签]    导入 docker export 的扁平 rootfs tar\n"
                + "  rmi IMAGE                删除镜像（有容器在用时拒绝）\n"
                + "\n"
                + "其他\n"
                + "  info / version / help\n"
                + "\n"
                + "说明: 这是 PRoot 容器，镜像只是模板，容器各自持有一份可写 rootfs；\n"
                + "      这些命令只在 App 的「容器管理控制台」里可用，容器内没有 docker 命令，\n"
                + "      所以不会和镜像自带的同名程序冲突。\n"
                + "      不支持 build / push / compose / 网络管理。\n"
                + "      导入的镜像架构必须与设备一致（如 arm64-v8a）。\n";
    }

    /* -------------------- 小工具 -------------------- */

    private static String[] splitRef(String ref) {
        int i = ref.lastIndexOf(':');
        if (i < 0) {
            return new String[]{ref, "latest"};
        }
        return new String[]{ref.substring(0, i), ref.substring(i + 1)};
    }

    /** 稳定的短 ID：容器名 / 镜像名 hash 前 12 位。 */
    private static String idOf(String name) {
        // 不能用 Long.toHexString 截前 12 位：长度只有 16，取高位会把差异（往往落在低位）
        // 直接裁掉——alpine:3.20 和 alpine:3.24 因此算出同一个 ID。
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(name.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(12);
            for (int i = 0; i < 6; i++) {
                sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(d[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            long h = 1125899906842597L;
            for (int i = 0; i < name.length(); i++) {
                h = 31 * h + name.charAt(i);
            }
            String hex = Long.toHexString(h);
            while (hex.length() < 12) {
                hex = "0" + hex;
            }
            return hex.substring(hex.length() - 12);
        }
    }

    private static String pad(String s, int width) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }

    private static String ago(long epochSeconds) {
        if (epochSeconds <= 0) {
            return "-";
        }
        long diff = System.currentTimeMillis() / 1000 - epochSeconds;
        if (diff < 60) {
            return "just now";
        }
        if (diff < 3600) {
            return (diff / 60) + " minutes ago";
        }
        if (diff < 86400) {
            return (diff / 3600) + " hours ago";
        }
        return String.format(Locale.US, "%d days ago", diff / 86400);
    }

}
