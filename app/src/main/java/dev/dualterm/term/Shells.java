package dev.dualterm.term;

import android.content.Context;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import dev.dualterm.util.Fs;

/**
 * 模式 A：直接跑在 Android 底层。
 *
 * 没有 root 时，shell 就是应用沙箱里的 /system/bin/sh（uid=u0_aXXX），
 * 能用的命令是 /system/bin 下真实存在的那些（sh、ls、cat、ps、ping、ip、pm 等）。
 * 有 root 且用户打开开关时，直接 exec su 拿一个 root shell。
 */
public final class Shells {

    private Shells() {
    }

    public static LaunchSpec localSpec(Context ctx, boolean useRoot) {
        File home = new File(ctx.getFilesDir(), "home");
        Fs.ensureDir(home);
        File prefix = new File(ctx.getFilesDir(), "usr");
        Fs.ensureDir(prefix);
        Fs.ensureDir(new File(prefix, "bin"));
        File tmp = new File(ctx.getCacheDir(), "tmp");
        Fs.ensureDir(tmp);

        if (useRoot && isRootAvailable()) {
            String[] env = localEnv(home, tmp, prefix);
            return new LaunchSpec("本地 Shell (root)", new String[]{"su"}, env, home.getAbsolutePath(),
                    true, false);
        }

        String shell = new File("/system/bin/sh").exists() ? "/system/bin/sh" : "sh";
        String[] env = localEnv(home, tmp, prefix);
        return new LaunchSpec("本地 Shell", new String[]{shell, "-i"}, env, home.getAbsolutePath());
    }

    private static String[] localEnv(File home, File tmp, File prefix) {
        List<String> env = new ArrayList<>();
        env.add("HOME=" + home.getAbsolutePath());
        env.add("PREFIX=" + prefix.getAbsolutePath());
        env.add("TMPDIR=" + tmp.getAbsolutePath());
        env.add("PATH=/sbin:/system/sbin:/system/bin:/system/xbin:" + prefix.getAbsolutePath() + "/bin");
        env.add("SHELL=/system/bin/sh");
        env.add("TERM=xterm-256color");
        env.add("COLORTERM=truecolor");
        env.add("LANG=en_US.UTF-8");
        env.add("LC_ALL=en_US.UTF-8");
        env.add("ANDROID_ROOT=/system");
        env.add("ANDROID_DATA=/data");
        env.add("PS1=dualterm:\\w\\$ ");
        return env.toArray(new String[0]);
    }

    /** 简单的 su 存在性探测（不主动触发授权弹窗）。 */
    public static boolean isRootAvailable() {
        String[] candidates = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
                "/data/adb/magisk/magisk", "/data/local/bin/su"
        };
        for (String path : candidates) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }

    /** 执行一条命令并返回 stdout（最多 64KB），失败返回 null。 */
    public static String execCapture(String[] argv) {
        Process process = null;
        try {
            process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, "UTF-8"));
                    if (sb.length() > 65536) {
                        break;
                    }
                }
            }
            process.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
