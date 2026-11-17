package dev.dualterm.container;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;

import dev.dualterm.util.Fs;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Docker Hub 匿名拉取（Registry HTTP API v2 子集），设计见 docs/pull-registry.md。
 *
 * 范围（缺一明确报错而不是静默失败）：
 *   - repo 支持 library/官方镜像 与 owner/repo，tag 形式；digest 引用不支持
 *   - 默认匿名直连 Docker Hub；配置了镜像源（换源）则 registry 端点指向镜像站，
 *     先匿名拉、401 再走 auth token 兜底——全匿名站（dockerproxy 类）与
 *     复用 Hub token 的站（daocloud / 1ms 类）两种形态都覆盖
 *   - manifest list / OCI index 按设备 ABI 选平台（linux/arm64-v8·arm-v7·amd64）
 *   - 层压缩只收 gzip / 未压缩 tar；zstd 需要 native 解码器，与「零第三方依赖、
 *     APK ≈ 512KB」两条硬约束冲突，整体拒绝并指路 docker save → load 导入
 *
 * 分层约定（对桌面回归 RegistryTest 的承诺）：parseRef / platformMatches /
 * layerKind / rejectReason 是 static 纯函数，不碰 Context 与 org.json；
 * JSON 解析与流下载集中在下面的私有方法里，留真机验证。
 */
public final class RegistryPuller {

    private RegistryPuller() {
    }

    /* -------------------- 常量 -------------------- */

    private static final String AUTH_URL =
            "https://auth.docker.io/token?service=registry.docker.io&scope=repository:";
    private static final String REGISTRY = "https://registry-1.docker.io/v2/";

    /** 带状态码的 HTTP 失败：换源流程据此判断「要不要补 token 再试一次」。 */
    static final class StatusException extends IOException {
        final int code;
        final String wwwAuth;   // 401 应答的 WWW-Authenticate 头，供 realm 解析；可空

        StatusException(int code, String message) {
            this(code, message, null);
        }

        StatusException(int code, String message, String wwwAuth) {
            super(message);
            this.code = code;
            this.wwwAuth = wwwAuth;
        }
    }

    private static final String MT_MANIFEST =
            "application/vnd.docker.distribution.manifest.v2+json";
    private static final String MT_LIST =
            "application/vnd.docker.distribution.manifest.list.v2+json";
    private static final String MT_OCI_MANIFEST =
            "application/vnd.oci.image.manifest.v1+json";
    private static final String MT_OCI_INDEX =
            "application/vnd.oci.image.index.v1+json";

    /** tag 请求：单 manifest 与列表都收（tag 大多指向 manifest list）。 */
    private static final String ACCEPT_ALL =
            MT_MANIFEST + ", " + MT_LIST + ", " + MT_OCI_MANIFEST + ", " + MT_OCI_INDEX;
    /** 按 digest 取单 manifest：不能再收列表类型，否则 registry 可能又回一个 list。 */
    private static final String ACCEPT_SINGLE = MT_MANIFEST + ", " + MT_OCI_MANIFEST;

    /* -------------------- ref 解析（纯函数） -------------------- */

    /** 解析结果：repo 是发请求用的全名（library/debian 或 owner/name）。 */
    public static final class Ref {
        public final String repo;
        public final String tag;
        public final String local;   // 登记用：剥掉 library/ 前缀，如 debian:12

        Ref(String repo, String tag, String local) {
            this.repo = repo;
            this.tag = tag;
            this.local = local;
        }
    }

    /**
     * ref 解析。输入即用户在控制台 / 拉取对话框里敲的字符串。
     * 非法输入抛 IOException，消息直接给用户看。
     */
    public static Ref parseRef(String input) throws IOException {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty()) {
            throw new IOException("用法：docker pull 名[:标签]，例如 docker pull debian:12");
        }
        if (s.contains("@")) {
            throw new IOException("暂不支持 digest 引用（名@sha256:…），请用标签形式");
        }
        // repo 段不含冒号（不支持自建 registry 的 域名:端口 写法），最后一个冒号后是 tag
        String repo;
        String tag;
        int i = s.lastIndexOf(':');
        if (i < 0) {
            repo = s;
            tag = "latest";
        } else {
            repo = s.substring(0, i);
            tag = s.substring(i + 1);
        }
        if (repo.isEmpty()) {
            throw new IOException("镜像名不完整：" + input);
        }
        if (tag.isEmpty()) {
            tag = "latest";
        }
        repo = repo.toLowerCase(Locale.US);
        if (!repo.matches("[a-z0-9][a-z0-9._-]*(/[a-z0-9][a-z0-9._-]*)*")) {
            throw new IOException("镜像名含非法字符：" + repo
                    + "\n（只支持 Docker Hub 的 名 或 owner/名 写法，全小写）");
        }
        // 官方镜像的约定：不带 / 的 repo 发请求时补 library/ 前缀
        if (!repo.contains("/")) {
            repo = "library/" + repo;
        }
        if (!tag.matches("[A-Za-z0-9_][A-Za-z0-9._-]{0,127}")) {
            throw new IOException("标签含非法字符：" + tag);
        }
        // 登记名剥掉官方镜像的 library/ 前缀，与本地镜像命名（normalizeImage）一致
        String local = (repo.startsWith("library/")
                ? repo.substring("library/".length()) : repo) + ":" + tag;
        // local 会变成镜像目录名，也会被 Docker 后端拼进 su -c——闸门前置
        if (!Containers.validImageRef(local)) {
            throw new IOException("镜像名含非法字符，拒绝拉取：" + local);
        }
        return new Ref(repo, tag, local);
    }

    /* -------------------- 镜像源归一化（纯函数） -------------------- */

    /**
     * 换源设置的归一化。空 / null / "clear" / "官方" → ""（官方直连）；
     * 裸域名自动补 https://（docker.1ms.run → https://docker.1ms.run）；
     * 剥掉尾部斜杠与 /v2 后缀（用户常从文档里整段复制）；协议只收 http(s)，
     * http 放行是给局域网自建源留门。非法输入抛 IOException，消息直接给用户。
     */
    public static String normalizeMirror(String input) throws IOException {
        String s = input == null ? "" : input.trim();
        if (s.isEmpty() || "clear".equalsIgnoreCase(s) || "官方".equals(s)) {
            return "";
        }
        String lower = s.toLowerCase(Locale.US);
        boolean hasScheme;
        if (lower.startsWith("https://") || lower.startsWith("http://")) {
            hasScheme = true;
            s = s.substring(lower.indexOf("://") + 3);
        } else if (lower.contains("://")) {
            throw new IOException("镜像源协议只支持 http(s)：" + input);
        } else {
            hasScheme = false;
        }
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.toLowerCase(Locale.US).endsWith("/v2")) {
            s = s.substring(0, s.length() - 3);
        }
        if (s.isEmpty()) {
            throw new IOException("镜像源地址不合法：" + input
                    + "\n（示例：docker.1ms.run 或 https://docker.m.daocloud.io）");
        }
        int slash = s.indexOf('/');
        String host = slash < 0 ? s : s.substring(0, slash);
        int colon = host.lastIndexOf(':');
        String port = colon < 0 ? "" : host.substring(colon + 1);
        if (colon >= 0) {
            host = host.substring(0, colon);
        }
        boolean ok = !host.isEmpty() && host.matches("[A-Za-z0-9._-]+")
                && (port.isEmpty() || port.matches("\\d{1,5}"));
        if (!ok) {
            throw new IOException("镜像源地址不合法：" + input
                    + "\n（示例：docker.1ms.run 或 https://docker.m.daocloud.io）");
        }
        return (hasScheme ? lower.substring(0, lower.indexOf("://") + 3) : "https://") + s;
    }

    /* -------------------- 平台选择（纯函数） -------------------- */

    /**
     * manifest list 的 platform 是否匹配本设备。
     * abi 显式传参（不调 ContainerBootstrap.abi()），桌面可测。
     */
    public static boolean platformMatches(String abi, String os, String arch, String variant) {
        if (os == null || !"linux".equals(os)) {
            return false;
        }
        if (ContainerBootstrap.ABI_ARM64.equals(abi)) {
            return "arm64".equals(arch)
                    && (isEmpty(variant) || "v8".equals(variant) || "8".equals(variant));
        }
        if (ContainerBootstrap.ABI_ARM.equals(abi)) {
            return "arm".equals(arch)
                    && (isEmpty(variant) || "v7".equals(variant) || "7".equals(variant));
        }
        if (ContainerBootstrap.ABI_X86_64.equals(abi)) {
            return "amd64".equals(arch);   // amd64 不看 variant
        }
        return false;
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isEmpty();
    }

    /* -------------------- 层类型判定（纯函数） -------------------- */

    /** 层 mediaType：0=gzip、1=未压缩 tar、-1=拒绝（reason 说明原因）。 */
    public static int layerKind(String mediaType) {
        if (mediaType == null || mediaType.isEmpty()) {
            return 0; // 老 manifest 不带字段时按 gzip 对待（Hub 实际全是 gzip）
        }
        return rejectReason(mediaType) == null ? (isGzip(mediaType) ? 0 : 1) : -1;
    }

    /** 白名单之外的层给出拒绝原因；可收的层返回 null。mediaType 缺省按 gzip 对待。 */
    public static String rejectReason(String mediaType) {
        if (mediaType == null || mediaType.isEmpty()) {
            return null; // 老 manifest 不带字段；Hub 实际全是 gzip
        }
        if (mediaType.contains("nondistributable") || mediaType.contains("foreign")) {
            return "该镜像包含不可分发层（" + mediaType + "），本端不支持";
        }
        if (mediaType.contains("zstd")) {
            return "该镜像的层用了 zstd 压缩，本端不支持（需要 native 解码器）。"
                    + "可在电脑上 docker save 后用本应用「导入镜像」（docker load -i）";
        }
        if (isGzip(mediaType)) {
            return null;
        }
        if (mediaType.endsWith(".tar")) {
            return null;
        }
        return "不支持的层类型：" + mediaType;
    }

    private static boolean isGzip(String mediaType) {
        return mediaType.endsWith("tar.gzip") || mediaType.endsWith("tar+gzip");
    }

    /* -------------------- 主流程 -------------------- */

    public static String pull(Context ctx, String input, ContainerRuntime.Progress progress)
            throws IOException {
        Ref ref = parseRef(input);
        String hub = Containers.hubMirror(ctx);
        String registryBase = hub.isEmpty() ? REGISTRY : hub + "/v2/";
        report(progress, (hub.isEmpty() ? "查询 Docker Hub：" : "经镜像源 " + hub + " 查询 ")
                + ref.repo + ":" + ref.tag + " …");

        File rootfs = Containers.imageRootfs(ctx, ref.local);
        File dir = rootfs.getParentFile();
        if (dir.exists()) {
            File[] kids = dir.listFiles();
            if (kids != null && kids.length > 0) {
                throw new IOException("镜像已存在：" + ref.local + "（先 docker rmi " + ref.local + "）");
            }
        }

        // 匿名先试：官方 registry 恒 401，会落到 token 兜底；全匿名镜像站免 token；
        // 需要复用 Hub token 的镜像站也在同一兜底里覆盖
        String token = null;
        JSONObject manifest;
        try {
            manifest = fetchManifest(registryBase, ref, null, progress);
        } catch (StatusException e) {
            if (e.code != 401) {
                throw e;
            }
            token = resolveToken(e.wwwAuth, ref.repo);
            manifest = fetchManifest(registryBase, ref, token, progress);
        }
        JSONArray layers = manifest.optJSONArray("layers");
        if (layers == null || layers.length() == 0) {
            throw new IOException("manifest 里没有任何镜像层");
        }

        Fs.ensureDir(rootfs);
        try {
            File cache = ctx.getCacheDir();
            for (int i = 0; i < layers.length(); i++) {
                JSONObject layer;
                try {
                    layer = layers.getJSONObject(i);
                } catch (org.json.JSONException e) {
                    throw new IOException("manifest 层条目解析失败：" + e.getMessage());
                }
                String digest = layer.optString("digest", "");
                String reason = rejectReason(layer.optString("mediaType", ""));
                if (digest == null || digest.isEmpty()) {
                    throw new IOException("第 " + (i + 1) + " 层缺少 digest");
                }
                if (reason != null) {
                    throw new IOException(reason);
                }
                String label = "层 " + (i + 1) + "/" + layers.length();
                // 串行「下载一层、叠一层、删临时」：debian 解包后 80MB+，
                // 不把整镜像和解包副本同时留在盘上
                report(progress, "下载 " + label + " …");
                File tmp = new File(cache, "pull-" + System.nanoTime());
                downloadBlob(registryBase, ref.repo, token, digest, tmp, progress, label);
                report(progress, "解包 " + label + " …");
                try (InputStream in = new FileInputStream(tmp)) {
                    TarGzExtractor.extractLayer(in, rootfs, null);
                } finally {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                }
            }
        } catch (IOException e) {
            Containers.deleteRecursive(dir, Containers.imagesDir(ctx));
            throw e;
        }

        // manifest 选对了平台也可能被中间层串味，落地后按 ELF 头再核一次
        // （与 alpine 快路径同一防御思想；导入路径的语义是提示不删，这里刚下载
        // 完还没有任何用户数据，直接丢弃更干净）
        String problem = Containers.archProblem(rootfs);
        if (problem != null) {
            Containers.deleteRecursive(dir, Containers.imagesDir(ctx));
            throw new IOException("拉取到的镜像与本机架构不符（" + problem + "），已丢弃");
        }

        Containers.finalizeImage(ctx, ref.local, rootfs, progress);
        report(progress, "完成：" + ref.local);
        return ref.local;
    }

    /* -------------------- token / manifest -------------------- */

    /**
     * 401 后取 token。规范流程：WWW-Authenticate 的 realm 就是鉴权端点
     * （镜像站会报自己的，如 docker.1ms.run/openapi/v1/auth/token——这正好
     * 绕开大陆网络经常不可达的 auth.docker.io）；没有 realm 或解析不出才退回
     * Hub 官方端点。
     */
    private static String resolveToken(String wwwAuth, String repo) throws IOException {
        String realm = realmParam(wwwAuth, "realm");
        if (realm != null && !realm.isEmpty()) {
            String service = realmParam(wwwAuth, "service");
            StringBuilder url = new StringBuilder(realm);
            url.append(realm.indexOf('?') >= 0 ? '&' : '?');
            if (service != null && !service.isEmpty()) {
                url.append("service=").append(service).append('&');
            }
            url.append("scope=repository:").append(repo).append(":pull");
            String body;
            try {
                body = getForText(url.toString(), null, null)[0];
            } catch (IOException e) {
                throw new IOException("鉴权服务不可达（" + e.getMessage() + "）", e);
            }
            return tokenField(body, "鉴权端点 " + realm);
        }
        return token(repo);
    }

    /** Hub 官方 token 端点（realm 缺失时的兜底）。 */
    private static String token(String repo) throws IOException {
        String body;
        try {
            body = getForText(AUTH_URL + repo + ":pull", null, null)[0];
        } catch (IOException e) {
            throw new IOException("Docker Hub 不可达（" + e.getMessage() + "）", e);
        }
        return tokenField(body, "Docker Hub 鉴权应答");
    }

    /** token 应答里取 access_token / token 字段（两版规范字段名不同）。 */
    private static String tokenField(String body, String what) throws IOException {
        try {
            JSONObject o = new JSONObject(body);
            String t = o.optString("token", "");
            if (t.isEmpty()) {
                t = o.optString("access_token", "");
            }
            if (t.isEmpty()) {
                throw new org.json.JSONException("缺少 token/access_token 字段");
            }
            return t;
        } catch (org.json.JSONException e) {
            throw new IOException(what + "异常（" + e.getMessage() + "）");
        }
    }

    /** 从 WWW-Authenticate 头里摘参数：Bearer realm="…",service="…"。纯函数，桌面可测。 */
    public static String realmParam(String wwwAuth, String name) {
        if (wwwAuth == null || name == null) {
            return null;
        }
        int i = wwwAuth.indexOf(name + "=\"");
        if (i < 0) {
            return null;
        }
        int start = i + name.length() + 2;
        int end = wwwAuth.indexOf('"', start);
        return end < 0 ? null : wwwAuth.substring(start, end);
    }

    private static JSONObject fetchManifest(String registryBase, Ref ref, String token,
                                            ContainerRuntime.Progress progress) throws IOException {
        String url = registryBase + ref.repo + "/manifests/" + ref.tag;
        String[] r = getForText(url, token, ACCEPT_ALL);
        String body = r[0];
        String type = stripParams(r[1]);

        if (MT_LIST.equals(type) || MT_OCI_INDEX.equals(type)) {
            // tag 指向 manifest list：按设备 ABI 选平台，再按 digest 取单 manifest
            JSONObject index = parseJson(body, "manifest 列表");
            String digest = pickPlatform(index, ref);
            report(progress, "选定平台，取 manifest（" + shortDigest(digest) + "）…");
            String[] m = getForText(registryBase + ref.repo + "/manifests/" + digest,
                    token, ACCEPT_SINGLE);
            body = m[0];
            type = stripParams(m[1]);
        }
        if (!MT_MANIFEST.equals(type) && !MT_OCI_MANIFEST.equals(type)) {
            if (type.contains("v1+")) {
                throw new IOException("镜像太老（schema v1 manifest），本端不支持");
            }
            throw new IOException("registry 返回了不认识的 manifest 类型：" + type);
        }
        return parseJson(body, "manifest");
    }

    /** 从 manifest list 里挑匹配本设备的条目，返回其 digest；挑不出就列出全部可用平台。 */
    private static String pickPlatform(JSONObject index, Ref ref) throws IOException {
        JSONArray manifests = index.optJSONArray("manifests");
        if (manifests == null || manifests.length() == 0) {
            throw new IOException("manifest 列表为空");
        }
        String abi = ContainerBootstrap.abi();
        StringBuilder avail = new StringBuilder();
        for (int i = 0; i < manifests.length(); i++) {
            JSONObject m;
            try {
                m = manifests.getJSONObject(i);
            } catch (org.json.JSONException e) {
                throw new IOException("manifest 列表条目解析失败：" + e.getMessage());
            }
            JSONObject p = m.optJSONObject("platform");
            if (p == null) {
                continue;
            }
            String os = p.optString("os", "");
            String arch = p.optString("architecture", "");
            String variant = p.isNull("variant") ? "" : p.optString("variant", "");
            if (avail.length() > 0) {
                avail.append("、");
            }
            avail.append(os).append('/').append(arch)
                    .append(variant.isEmpty() ? "" : "/" + variant);
            if (platformMatches(abi, os, arch, variant)) {
                String digest = m.optString("digest", "");
                if (!digest.isEmpty()) {
                    return digest;
                }
            }
        }
        throw new IOException("标签 " + ref.repo + ":" + ref.tag
                + " 下没有匹配本设备（" + abi + "）的镜像\n可用平台：" + avail);
    }

    private static JSONObject parseJson(String body, String what) throws IOException {
        try {
            return new JSONObject(body);
        } catch (org.json.JSONException e) {
            throw new IOException(what + "解析失败：" + e.getMessage());
        }
    }

    /* -------------------- 层下载（边下边验 sha256） -------------------- */

    private static void downloadBlob(String registryBase, String repo, String token,
                                     String digest, File dest,
                                     ContainerRuntime.Progress progress, String label)
            throws IOException {
        try {
            downloadBlobOnce(registryBase, repo, token, digest, dest, progress, label);
        } catch (StatusException e) {
            // manifest 匿名成功但 blob 要鉴权的镜像站（少见）：补 token 重试一次
            if (e.code != 401 || token != null) {
                throw e;
            }
            downloadBlobOnce(registryBase, repo, resolveToken(e.wwwAuth, repo),
                    digest, dest, progress, label);
        }
    }

    private static void downloadBlobOnce(String registryBase, String repo, String token,
                                         String digest, File dest,
                                         ContainerRuntime.Progress progress, String label)
            throws IOException {
        String url = registryBase + repo + "/blobs/" + digest;
        String want = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        for (int hop = 0; hop < 3; hop++) {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            // blob 端点会 302/307 到 CDN；跨主机跟随会原样转发请求头，
            // 手动跟一次，新请求不带 token
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "dualterm");
            if (token != null && url.startsWith(registryBase)) {
                conn.setRequestProperty("Authorization", "Bearer " + token);
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new IOException("层下载重定向缺少 Location");
                }
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code != 200) {
                String wwwAuth = conn.getHeaderField("WWW-Authenticate");
                conn.disconnect();
                throw httpError(code, "层 blob 下载", wwwAuth);
            }
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                long total = conn.getContentLength();
                Fs.ensureDir(dest.getParentFile());
                long done = 0;
                long lastReport = 0;
                try (InputStream in = conn.getInputStream();
                     OutputStream out = Files.newOutputStream(dest.toPath())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        md.update(buf, 0, n);
                        done += n;
                        if (total > 0 && done - lastReport >= 1024 * 1024) {
                            lastReport = done;
                            report(progress, label + "：" + Containers.humanSize(done)
                                    + " / " + Containers.humanSize(total));
                        }
                    }
                }
                String hex = hex(md.digest());
                if (!hex.equals(want)) {
                    //noinspection ResultOfMethodCallIgnored
                    dest.delete();
                    throw new IOException(label + " 数据校验不符（期望 " + want.substring(0, 12)
                            + "…，实得 " + hex.substring(0, 12) + "…），网络异常？");
                }
                return;
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IOException("本机缺少 SHA-256 实现", e);
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("层下载重定向次数过多");
    }

    /* -------------------- HTTP 与小工具 -------------------- */

    /** GET 文本应答，返回 {body, Content-Type}。 */
    private static String[] getForText(String url, String token, String accept) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "dualterm");
        if (token != null) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (accept != null) {
            conn.setRequestProperty("Accept", accept);
        }
        int code = conn.getResponseCode();
        if (code != 200) {
            String wwwAuth = conn.getHeaderField("WWW-Authenticate");
            conn.disconnect();
            throw httpError(code, "查询 " + url, wwwAuth);
        }
        String type = conn.getContentType();
        StringBuilder out = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.append(new String(buf, 0, n, "UTF-8"));
                if (out.length() > 4 * 1024 * 1024) {
                    throw new IOException("manifest 大得反常（超过 4MB），已中止");
                }
            }
        } finally {
            conn.disconnect();
        }
        return new String[]{out.toString(), type == null ? "" : type};
    }

    /** HTTP 状态码 → 用户可读的错误（Hub 对私有/不存在仓库回 401/404 掩盖存在性）。 */
    private static StatusException httpError(int code, String what, String wwwAuth) {
        if (code == 404) {
            return new StatusException(code, what + "：没有这个镜像/标签（404）");
        }
        if (code == 401) {
            return new StatusException(code, what + "：拒绝访问（401）"
                    + "——仓库可能不存在，或是不支持匿名拉取的私有仓库", wwwAuth);
        }
        if (code == 429) {
            return new StatusException(code, what + "：匿名拉取限速（同 IP 100 次/6 小时），"
                    + "稍后再试，或在电脑上 docker save 后导入");
        }
        return new StatusException(code, what + "失败：HTTP " + code);
    }

    private static String stripParams(String contentType) {
        if (contentType == null) {
            return "";
        }
        int i = contentType.indexOf(';');
        return i < 0 ? contentType.trim() : contentType.substring(0, i).trim();
    }

    private static String shortDigest(String digest) {
        return digest.startsWith("sha256:") ? digest.substring(7, 19) + "…" : digest;
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void report(ContainerRuntime.Progress progress, String message) {
        if (progress != null) {
            progress.onProgress(message);
        }
    }
}
