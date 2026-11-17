package dev.dualterm.container;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dev.dualterm.term.LaunchSpec;
import dev.dualterm.util.Fs;

/**
 * PRoot 后端：把 Alpine/Debian 的 minirootfs 当成容器根，proot 负责路径重定向。
 *
 * 隔离边界（说清楚，别夸大）：
 *   ✅ 独立文件系统：/ 就是 rootfs，装 apk/apt 不会污染 Android
 *   ✅ 假 root（-0）：容器内 uid=0，可写 /etc、可装包
 *   ❌ 不是内核级隔离：和 Android 共用同一个内核与 /proc 视图
 *   ❌ 没有独立网络栈、cgroup 限额
 * 需要真 Docker 的场景请看 DockerRuntime。
 */
public final class ProotRuntime extends ContainerRuntime {

    private final Context ctx;

    public ProotRuntime(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public String displayName() {
        return "PRoot（无 root，文件系统隔离）";
    }

    @Override
    public void prepare(Progress progress) throws Exception {
        progress.onProgress("检查镜像与容器…");
        Containers.ensureBootstrap(ctx, progress);
        progress.onProgress("检查 proot 运行时…");
        ContainerBootstrap.ensureProot(ctx);
        Fs.ensureDir(ContainerBootstrap.tmpDir(ctx));
        progress.onProgress("容器已就绪");
    }

    @Override
    public LaunchSpec launchSpec(String containerName) throws Exception {
        Containers.Item item = Containers.find(ctx, containerName);
        if (item == null) {
            throw new IllegalStateException("容器不存在：" + containerName
                    + "\n可用：docker ps -a（或菜单 → 容器管理）");
        }
        File proot = ContainerBootstrap.prootBin(ctx);
        File rootfs = item.rootfs(ctx);
        if (!Containers.existsInRootfs(rootfs, "bin/sh") && !Containers.existsInRootfs(rootfs, "bin/busybox")) {
            throw new IllegalStateException("容器 " + containerName + " 的根文件系统不完整（缺 /bin/sh）"
                    + "\n可删除后重新创建：docker rm " + containerName);
        }
        String archProblem = Containers.archProblem(rootfs);
        if (archProblem != null) {
            // 架构不对时 proot 只会吐一个 SIGBUS，用户完全看不出是镜像拉错了
            throw new IllegalStateException("容器 " + containerName + " 无法启动：" + archProblem
                    + "\n换一个镜像重建：docker rm " + containerName
                    + " && docker run --name " + containerName + " alpine:3.20");
        }
        File home = item.home(ctx);
        File tmp = ContainerBootstrap.tmpDir(ctx);
        Fs.ensureDir(home);
        Fs.ensureDir(new File(rootfs, "var/run"));

        List<String> args = new ArrayList<>();
        args.add(proot.getAbsolutePath());
        args.add("--kill-on-exit");
        args.add("--link2symlink");
        args.add("-0");
        args.add("-r");
        args.add(rootfs.getAbsolutePath());

        bind(args, "/dev");
        bind(args, "/proc");
        bind(args, "/sys");
        bind(args, "/dev/urandom", "/dev/random");
        bind(args, "/proc/self/fd", "/dev/fd");
        bind(args, home.getAbsolutePath(), "/root");
        bind(args, tmp.getAbsolutePath(), "/tmp");
        if (new File("/sdcard").exists()) {
            bind(args, "/sdcard");
        }

        // 用户自定义绑定目录（菜单 → 绑定目录）。宿主路径不存在就跳过，
        // 否则 proot 会以 "can't bind" 直接退出。
        for (String[] vol : Volumes.list(ctx)) {
            String arg = Volumes.prootBindArg(vol);
            if (arg != null) {
                args.add("-b");
                args.add(arg);
            }
        }

        // 登录 shell 由发行版决定（/etc/passwd 对应用户条目的第 7 字段）——
        // Debian 的 root 是 /bin/bash（readline，方向键/HOME/行编辑可用），
        // Alpine 是 /bin/sh（busybox ash 自带行编辑）。passwd 缺失、解析不出
        // 或该 shell 不在 rootfs 里时回退 /bin/sh。
        String loginShell = "/bin/sh";
        File passwd = new File(rootfs, "etc/passwd");
        if (passwd.isFile()) {
            try {
                StringBuilder sb = new StringBuilder();
                java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(
                                new java.io.FileInputStream(passwd),
                                java.nio.charset.StandardCharsets.UTF_8));
                try {
                    String ln;
                    while ((ln = br.readLine()) != null) {
                        sb.append(ln).append('\n');
                    }
                } finally {
                    br.close();
                }
                String shell = Containers.loginShellFromPasswd(sb.toString(), "root");
                if (Containers.existsInRootfs(rootfs,
                        shell.startsWith("/") ? shell.substring(1) : shell)) {
                    loginShell = shell;
                }
            } catch (Exception ignored) {
                // 读不了 passwd 就按 /bin/sh 启动，别让会话起不来
            }
        }

        args.add("-w");
        args.add("/root");
        args.add("/usr/bin/env");
        args.add("-i");
        args.add("HOME=/root");
        args.add("SHELL=" + loginShell);
        args.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        args.add("TERM=xterm-256color");
        args.add("COLORTERM=truecolor");
        args.add("LANG=C.UTF-8");
        args.add("DUALTERM_CONTAINER=" + containerName);
        args.add(loginShell);
        args.add("-l");

        return new LaunchSpec("容器 · " + containerName, args.toArray(new String[0]),
                env(home, tmp), home.getAbsolutePath());
    }

    /** proot 进程自己的环境（不是容器内的环境）。 */
    private String[] env(File home, File tmp) {
        List<String> env = new ArrayList<>();
        env.add("LD_LIBRARY_PATH=" + ContainerBootstrap.libDir(ctx).getAbsolutePath());
        // 关键：rootfs 位于应用私有目录，targetSdk≥29 时该目录 noexec，
        // proot 直接 execve 客人程序会被拒绝。交给 proot 自带的 loader
        // （静态 ELF，放在可执行的 nativeLibraryDir）去 mmap + 跳转。
        env.add("PROOT_LOADER=" + new File(ContainerBootstrap.nativeLibDir(ctx), "libproot_loader.so").getAbsolutePath());
        env.add("PROOT_TMP_DIR=" + tmp.getAbsolutePath());
        env.add("HOME=" + home.getAbsolutePath());
        env.add("TMPDIR=" + tmp.getAbsolutePath());
        env.add("PATH=/sbin:/system/sbin:/system/bin:/system/xbin");
        env.add("TERM=xterm-256color");
        env.add("ANDROID_ROOT=/system");
        env.add("ANDROID_DATA=/data");
        return env.toArray(new String[0]);
    }

    private static void bind(List<String> args, String host) {
        bind(args, host, null);
    }

    private static void bind(List<String> args, String host, String guest) {
        if (!new File(host).exists()) {
            return;
        }
        args.add("-b");
        args.add(guest == null ? host : host + ":" + guest);
    }
}
