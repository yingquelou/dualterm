package dev.dualterm.container;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 容器绑定目录（volume）配置。
 *
 * 存成一行一条："宿主路径|容器内路径"，容器内路径为空表示按原路径挂载。
 * 只做数据与校验，真正拼 proot -b / docker -v 参数在各自的 Runtime 里。
 *
 * 注意：绑定的是宿主真实目录，容器内对该目录的写入会直接落到宿主的文件上，
 * 这是"共享目录"而不是"复制一份"——所以别把 /data 之类的系统目录绑进去。
 */
public final class Volumes {

    private static final String PREF = "dualterm";
    private static final String KEY = "volumes";
    private static final String SEP = "|";

    private Volumes() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 返回 [宿主路径, 容器内路径]（容器内路径可能为空字符串）。 */
    public static List<String[]> list(Context ctx) {
        List<String[]> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            int bar = s.indexOf(SEP);
            if (bar < 0) {
                out.add(new String[]{s, ""});
            } else {
                out.add(new String[]{s.substring(0, bar), s.substring(bar + 1)});
            }
        }
        return out;
    }

    public static void save(Context ctx, List<String[]> items) {
        StringBuilder sb = new StringBuilder();
        for (String[] item : items) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(item[0]).append(SEP).append(item[1] == null ? "" : item[1]);
        }
        prefs(ctx).edit().putString(KEY, sb.toString()).apply();
    }

    public static void add(Context ctx, String host, String guest) {
        List<String[]> items = list(ctx);
        items.add(new String[]{host, guest == null ? "" : guest});
        save(ctx, items);
    }

    public static void removeAt(Context ctx, int index) {
        List<String[]> items = list(ctx);
        if (index >= 0 && index < items.size()) {
            items.remove(index);
            save(ctx, items);
        }
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }

    /** 展示用：/sdcard → /mnt/sdcard */
    public static String label(String[] item) {
        String guest = (item[1] == null || item[1].isEmpty()) ? item[0] : item[1];
        return item[0] + "  →  " + guest;
    }

    /**
     * 这条绑定在宿主侧是否可用；可用返回 null，否则返回原因。
     *
     * proot 只做路径翻译，真正读文件用的是本应用的 uid/权限，
     * 所以「能绑定」不等于「能读取」——把原因显式标出来，免得容器里报
     * Permission denied 时让人以为是 App 的问题。
     */
    public static String state(String[] item) {
        File f = new File(item[0]);
        if (!f.exists()) {
            return "宿主路径不存在";
        }
        if (!f.isDirectory()) {
            return "不是目录";
        }
        if (!f.canRead()) {
            return "应用无权读取";
        }
        return null;
    }

    /** 生成 proot 的 -b 参数值：宿主[:容器内]。宿主机上不存在就返回 null。 */
    public static String prootBindArg(String[] item) {
        if (!new File(item[0]).exists()) {
            return null;
        }
        if (item[1] == null || item[1].isEmpty()) {
            return item[0];
        }
        return item[0] + ":" + item[1];
    }

    /** 生成 docker 的 -v 参数值：宿主:容器内。 */
    public static String dockerVolumeArg(String[] item) {
        String guest = (item[1] == null || item[1].isEmpty()) ? item[0] : item[1];
        return item[0] + ":" + guest;
    }

    /** 常见候选路径，给添加对话框做提示。 */
    public static String[] suggestions() {
        return new String[]{
                "/sdcard",
                "/sdcard/Download",
                "/storage/emulated/0/Documents",
                "/data/local/tmp"
        };
    }
}
