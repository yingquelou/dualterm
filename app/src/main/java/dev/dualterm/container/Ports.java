package dev.dualterm.container;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 容器对外端口声明。
 *
 * 这个类只解决一个问题：用户敲了 {@code docker run -p ...} 之后，到底怎么才连得上。
 *
 * 关键在于**不做端口映射**。PRoot 后端压根没有网络命名空间隔离，容器里的进程就是本 App
 * 的进程，`listen 0.0.0.0:8080` 直接就是手机上的 8080；Docker 后端在本项目里固定用
 * {@code --net=host}（见 DockerRuntime），同样是宿主端口直通。两边都没有 NAT，
 * 所以 `-p 8080:80` 这种重映射无法实现——与其运行后静默连不上，不如在创建容器时就拒绝。
 *
 * 能做的是「同端口直通」：{@code -p 8080} 或 {@code -p 8080:8080}，容器内监听
 * 0.0.0.0:8080，手机浏览器访问 127.0.0.1:8080 即可。这条声明本身只用于展示与提醒，
 * 不参与启动命令拼装（网络已经是共用的，不需要额外参数）。
 */
public final class Ports {

    private static final String PREF = "dualterm";
    private static final String KEY_PREFIX = "ports.";
    private static final String SEP = "|";

    private Ports() {
    }

    /** 一条端口声明。宿主端口与容器端口分开保留，将来真要加重映射时不用改存储格式。 */
    public static final class Mapping {
        public final int host;
        public final int container;

        Mapping(int host, int container) {
            this.host = host;
            this.container = container;
        }

        /** 同端口 = 本后端唯一支持的形态。 */
        public boolean passthrough() {
            return host == container;
        }

        /** 低于 1024 的端口在容器里绑不上：proot 的 -0 是假 root，实为 app uid。 */
        public boolean privileged() {
            return container < 1024;
        }

        @Override
        public String toString() {
            return host + SEP + container;
        }
    }

    /**
     * 解析 docker 风格的端口写法：{@code 8080}、{@code 8080:8080}、
     * {@code 127.0.0.1:8080:8080}，允许 {@code /tcp} 后缀。
     *
     * <p>协议只收 tcp：udp 在容器里能用，但本项目没有任何 UDP 场景需要提示，
     * 与其让用户以为「声明了就能用」，不如直接拒掉。
     *
     * @throws IllegalArgumentException 写法非法、端口越界或协议不支持
     */
    public static Mapping parse(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            throw new IllegalArgumentException("端口为空");
        }
        String s = spec.trim();

        int slash = s.indexOf('/');
        if (slash >= 0) {
            String proto = s.substring(slash + 1).trim().toLowerCase(Locale.ROOT);
            s = s.substring(0, slash);
            if (!"tcp".equals(proto)) {
                throw new IllegalArgumentException("只支持 tcp 端口（" + spec + "）");
            }
        }
        if (s.indexOf('[') >= 0) {
            throw new IllegalArgumentException("不支持 IPv6 写法（" + spec + "）");
        }
        if (s.indexOf('-') >= 0) {
            throw new IllegalArgumentException("不支持端口范围（" + spec + "）");
        }

        String[] parts = s.split(":", -1);
        if (parts.length > 3) {
            throw new IllegalArgumentException("端口写法看不懂（" + spec + "），用 -p 宿主:容器");
        }
        if (parts.length == 1) {
            int p = port(parts[0], spec);
            return new Mapping(p, p);
        }
        // 三段时第一段是绑定 IP（只接受本地回环语义，值本身不参与判断）
        int host = port(parts[parts.length - 2], spec);
        int container = port(parts[parts.length - 1], spec);
        return new Mapping(host, container);
    }

    private static int port(String raw, String spec) {
        String t = raw.trim();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("端口缺失（" + spec + "）");
        }
        int v;
        try {
            v = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("端口不是数字：" + t);
        }
        if (v < 1 || v > 65535) {
            throw new IllegalArgumentException("端口超出 1-65535：" + v);
        }
        return v;
    }

    /* -------------------- 存储 -------------------- */

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<Mapping> list(Context ctx, String container) {
        List<Mapping> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_PREFIX + container, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String line : raw.split("\n")) {
            String s = line.trim();
            int bar = s.indexOf(SEP);
            if (bar < 0) {
                continue;
            }
            try {
                int host = Integer.parseInt(s.substring(0, bar));
                int inner = Integer.parseInt(s.substring(bar + 1));
                out.add(new Mapping(host, inner));
            } catch (NumberFormatException ignored) {
                // 坏行跳过：这是展示用元数据，不值得因为一行脏数据让容器列表打不开
            }
        }
        return out;
    }

    public static void save(Context ctx, String container, List<Mapping> mappings) {
        StringBuilder sb = new StringBuilder();
        for (Mapping m : mappings) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(m.toString());
        }
        prefs(ctx).edit().putString(KEY_PREFIX + container, sb.toString()).apply();
    }

    /** 容器删除时一并清掉，避免同名重建继承旧端口。 */
    public static void clear(Context ctx, String container) {
        prefs(ctx).edit().remove(KEY_PREFIX + container).apply();
    }

    public static String describe(List<Mapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Mapping m : mappings) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(m.container);
        }
        return sb.toString();
    }

    /** 创建成功后的引导话术：把「怎么连」说到能直接抄的程度。 */
    public static String hint(List<Mapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("已记录端口 ").append(describe(mappings)).append("（tcp）。\n");
        sb.append("在容器里让服务监听 0.0.0.0:") .append(mappings.get(0).container)
                .append("（不要只听 127.0.0.1），然后：\n");
        sb.append("  手机浏览器   http://127.0.0.1:").append(mappings.get(0).container).append('\n');
        sb.append("  同一局域网   http://<手机 Wi-Fi IP>:").append(mappings.get(0).container)
                .append("（路由器开了 AP 隔离就连不通）\n");
        boolean privileged = false;
        for (Mapping m : mappings) {
            privileged |= m.privileged();
        }
        if (privileged) {
            sb.append("提示：低于 1024 的端口需要 root 模式（菜单 → Root Shell），")
                    .append("容器里的假 root 绑不上特权端口。\n");
        }
        return sb.toString();
    }
}
