package dev.dualterm.container;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import dev.dualterm.util.Fs;

/**
 * 容器运行时的「入驻」逻辑：proot 从 jniLibs 取（打包在 APK 里），
 * rootfs 不随包分发（体积），镜像由用户 pull / 导入 tar 落到应用私有目录。
 *
 * 为什么要落到应用目录而不能直接跑：
 *   - /sdcard 是 noexec 的，放那里跑不起来。
 *   - proot 可执行必须放 nativeLibraryDir：Android 10+ 对应用私有数据目录
 *     强制 noexec，exec() 会被 SELinux 拒绝（exit 127）。
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

    /*
     * rootfs 不再随 APK 分发（体积优化，2026-11）：assets 里没有 rootfs pack，
     * 镜像由用户 docker pull（Containers.pull）或导入 tar 获得。
     * 旧版升级用户的数据迁移见 Containers.ensureBootstrap。
     */

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

    /** 保留给未来可能的小体积 assets（如离线脚本）；当前没有调用方。 */
    @SuppressWarnings("unused")
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
}
