package dev.dualterm.container;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import dev.dualterm.util.Fs;

/**
 * 容器运行时的「入驻」逻辑：把 proot 和根文件系统从 assets 放到应用私有目录，
 * 首次进入容器模式时解包，之后走快路径。
 *
 * 为什么要拷贝到 filesDir 而不能直接从 assets 跑：
 *   - assets 里的文件不可执行（没有 X 位），必须落到 ext4 私有目录上 chmod。
 *   - /sdcard 是 noexec 的，放那里同样跑不起来。
 */
public final class ContainerBootstrap {

    public static final String ABI_ARM64 = "arm64-v8a";
    public static final String ABI_ARM = "armeabi-v7a";
    public static final String ABI_X86_64 = "x86_64";

    private ContainerBootstrap() {
    }

    /* -------------------- 路径 -------------------- */

    public static File containerDir(Context ctx) {
        return new File(ctx.getFilesDir(), "container");
    }

    public static File rootfsDir(Context ctx) {
        return new File(containerDir(ctx), "rootfs");
    }

    public static File nativeLibDir(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir);
    }

    /**
     * proot 可执行文件。必须放在 nativeLibraryDir：Android 10+ 对 targetSdk≥29 的
     * 应用私有数据目录强制 noexec，exec() 会被 SELinux 拒绝（exit 127）；
     * jniLibs 会随 APK 安装到 native lib 目录，那里允许执行。
     */
    public static File prootBin(Context ctx) {
        return new File(nativeLibDir(ctx), "libproot.so");
    }

    /** proot 依赖的 .so（libtalloc / libandroid-shmem）同样要从 nativeLibraryDir 加载。 */
    public static File libDir(Context ctx) {
        return nativeLibDir(ctx);
    }

    public static File homeDir(Context ctx) {
        return new File(containerDir(ctx), "home");
    }

    public static File tmpDir(Context ctx) {
        return new File(containerDir(ctx), "tmp");
    }

    public static String abi() {
        String[] abis = Build.SUPPORTED_ABIS;
        String first = (abis != null && abis.length > 0) ? abis[0] : ABI_ARM64;
        if (first.startsWith("arm64")) {
            return ABI_ARM64;
        }
        if (first.startsWith("armeabi")) {
            return ABI_ARM;
        }
        if (first.startsWith("x86_64")) {
            return ABI_X86_64;
        }
        return ABI_ARM64;
    }

    /* -------------------- proot -------------------- */

    public static File ensureProot(Context ctx) throws IOException {
        File bin = prootBin(ctx);
        if (bin.isFile() && bin.length() > 0) {
            bin.setExecutable(true, false);
            return bin;
        }
        // nativeLibraryDir 里没有 libproot.so：说明这个 ABI 的运行时没打进 APK
        throw new IOException("缺少本机架构（" + abi() + "）的 proot 运行时"
                + "\n请在工程根目录执行：bash scripts/fetch-runtime.sh " + abi()
                + "（或 powershell -File scripts\\fetch-runtime.ps1 -Abi " + abi() + "）"
                + "\n然后重新构建安装（本地模式不受影响）");
    }

    /* -------------------- 根文件系统 -------------------- */

    /**
     * 把 assets 里的 minirootfs 解包到指定目录（镜像目录）。
     * 只在首次安装 / 拉取镜像时执行，之后走 Containers 的快路径。
     */
    public static void extractAssetsRootfs(Context ctx, File rootfs,
                                           ContainerRuntime.Progress progress) throws IOException {
        report(progress, "首次使用，准备解包容器根文件系统…");
        if (rootfs.exists()) {
            Containers.deleteRecursive(rootfs, ctx.getFilesDir());
        }
        Fs.ensureDir(rootfs);

        String asset = "runtime/rootfs-" + abi() + ".pack";
        if (!assetExists(ctx, asset)) {
            throw new IOException("assets 中缺少 " + asset
                    + "\n请先在工程根目录执行：bash scripts/fetch-runtime.sh " + abi()
                    + "（或 powershell -File scripts\\fetch-runtime.ps1 -Abi " + abi() + "）");
        }

        final ContainerRuntime.Progress sink = progress;
        try (InputStream in = ctx.getAssets().open(asset)) {
            TarGzExtractor.extract(in, rootfs, new TarGzExtractor.Progress() {
                @Override
                public void onProgress(int entries, String currentPath) {
                    if (entries % 80 == 0) {
                        report(sink, "解包根文件系统：已写入 " + entries + " 项");
                    }
                }
            });
        }

        // DNS：Android 没有 /etc/resolv.conf，得自己在 rootfs 里写一份
        writeText(new File(rootfs, "etc/resolv.conf"),
                "nameserver 223.5.5.5\nnameserver 8.8.8.8\nnameserver 1.1.1.1\n");

        // bind mount 目标必须存在
        for (String d : new String[]{"root", "tmp", "dev", "proc", "sys", "var/run"}) {
            Fs.ensureDir(new File(rootfs, d));
        }

        report(progress, "根文件系统就绪");
    }

    /** 兼容旧调用：保证至少有一个镜像 + 一个容器。 */
    public static void ensureRootfs(Context ctx, ContainerRuntime.Progress progress) throws IOException {
        Containers.ensureBootstrap(ctx, progress);
    }

    public static void wipeRootfs(Context ctx) {
        File container = containerDir(ctx);
        Containers.deleteRecursive(rootfsDir(ctx), container);
        File marker = new File(container, ".rootfs-" + abi() + ".ok");
        //noinspection ResultOfMethodCallIgnored
        marker.delete();
    }

    /* -------------------- 资源与文件工具 -------------------- */

    private static void report(ContainerRuntime.Progress progress, String message) {
        if (progress != null) {
            progress.onProgress(message);
        }
    }

    private static boolean assetExists(Context ctx, String assetPath) {
        try (InputStream in = ctx.getAssets().open(assetPath)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    public static void copyAsset(Context ctx, String assetPath, File dest) throws IOException {
        Fs.ensureDir(dest.getParentFile());
        try (InputStream in = ctx.getAssets().open(assetPath);
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void writeText(File file, String content) throws IOException {
        Fs.ensureDir(file.getParentFile());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes("UTF-8"));
        }
    }
}
