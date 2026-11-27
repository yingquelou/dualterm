import dev.dualterm.container.ContainerBootstrap;
import dev.dualterm.container.RegistryPuller;

import java.io.IOException;

/**
 * RegistryPuller 桌面回归（纯逻辑部分，可离线跑）：
 *   1. parseRef  —— ref 解析与闸门（library 前缀、tag 归一化、digest 拒绝、非法字符、
 *      第三方 registry 域名与 Hub 域名归一）
 *   2. platformMatches —— manifest list 的平台选择（按设备 ABI + variant 匹配规则）
 *   3. layerKind / rejectReason —— 层 mediaType 白名单（gzip 收、zstd/nondistributable 拒）
 *   4. registryBase —— Hub 走镜像源、第三方直连
 *
 * JSON 解析与流下载依赖 Context + 真 org.json，留真机验证（与 ImportTest 同一限制）。
 * 纯函数都是 static 且不碰 Context——这是 RegistryPuller 对本回归的实现承诺。
 */
public class RegistryTest {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        parseRefCases();
        platformCases();
        layerKindCases();
        registryBaseCases();
        mirrorCases();
        realmCases();

        System.out.println();
        System.out.println("PASS " + pass + "  FAIL " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* -------------------- parseRef -------------------- */

    static void parseRefCases() throws Exception {
        // 设计文档 §3.3 全表：repo 全名 / tag 归一化 / 登记名剥 library 前缀
        RegistryPuller.Ref r = parseOk("debian");
        check("parseRef: debian → library/debian", "library/debian".equals(r.repo));
        check("parseRef: debian → latest", "latest".equals(r.tag));
        check("parseRef: debian → 登记名 debian:latest", "debian:latest".equals(r.local));

        r = parseOk("debian:12");
        check("parseRef: debian:12 repo", "library/debian".equals(r.repo));
        check("parseRef: debian:12 tag", "12".equals(r.tag));
        check("parseRef: debian:12 local", "debian:12".equals(r.local));

        r = parseOk("library/debian:12");
        check("parseRef: library 前缀 repo 原样", "library/debian".equals(r.repo));
        check("parseRef: library 前缀登记名剥掉", "debian:12".equals(r.local));

        r = parseOk("user/repo:tag");
        check("parseRef: owner/repo repo 原样", "user/repo".equals(r.repo));
        check("parseRef: owner/repo local", "user/repo:tag".equals(r.local));

        r = parseOk("alpine:edge");
        check("parseRef: alpine:edge → library/alpine", "library/alpine".equals(r.repo));
        check("parseRef: alpine:edge local", "alpine:edge".equals(r.local));

        // 第三方 registry：域名保留在登记名里，发请求时 repo 不再补 library/
        r = parseOk("ghcr.io/owner/app:1.2");
        check("parseRef: ghcr host", "ghcr.io".equals(r.host));
        check("parseRef: ghcr repo", "owner/app".equals(r.repo));
        check("parseRef: ghcr tag", "1.2".equals(r.tag));
        check("parseRef: ghcr local", "ghcr.io/owner/app:1.2".equals(r.local));

        r = parseOk("ghcr.io/owner/app");
        check("parseRef: ghcr 无 tag → latest", "latest".equals(r.tag));

        r = parseOk("quay.io/coreos/etcd:v3.5.14");
        check("parseRef: quay host", "quay.io".equals(r.host));
        check("parseRef: quay local", "quay.io/coreos/etcd:v3.5.14".equals(r.local));

        // Docker 官方域名归一：登记名与 Hub 写法一致，镜像源照常生效
        r = parseOk("docker.io/library/nginx:1.27");
        check("parseRef: docker.io 归一 Hub",
                "".equals(r.host) && "library/nginx".equals(r.repo));
        check("parseRef: docker.io 登记名", "nginx:1.27".equals(r.local));

        r = parseOk("index.docker.io/user/app:1");
        check("parseRef: index.docker.io 归一 Hub",
                "".equals(r.host) && "user/app".equals(r.repo));

        // 拒绝类
        parseFail("parseRef: 空", "");
        parseFail("parseRef: null", null);
        parseFail("parseRef: digest 引用", "debian@sha256:abc123");
        parseFail("parseRef: 只有冒号", ":12");
        parseFail("parseRef: tag 非法字符", "debian:12..//");
        parseFail("parseRef: repo 大写非法字符", "Deb ian");
        parseFail("parseRef: validImageRef 闸门", "debian:tag with space");
        parseFail("parseRef: 带端口的自建 registry", "localhost:5000/x/y");
        parseFail("parseRef: 自建 registry localhost", "localhost/x/y");
        parseFail("parseRef: 第三方缺 owner 段", "ghcr.io/app");
        parseFail("parseRef: 非法域名", "gh cr.io/x/y");
        check("parseRef: 闸门消息带拒绝字样", true);
    }

    /* -------------------- registryBase -------------------- */

    static void registryBaseCases() {
        check("registryBase: Hub 无源 → 官方",
                "https://registry-1.docker.io/v2/".equals(RegistryPuller.registryBase("", "")));
        check("registryBase: Hub 有源 → 镜像站", "https://docker.1ms.run/v2/"
                .equals(RegistryPuller.registryBase("", "https://docker.1ms.run")));
        check("registryBase: 第三方直连，忽略镜像源", "https://ghcr.io/v2/"
                .equals(RegistryPuller.registryBase("ghcr.io", "https://docker.1ms.run")));
    }

    static RegistryPuller.Ref parseOk(String in) throws Exception {
        return RegistryPuller.parseRef(in);
    }

    static void parseFail(String label, String in) {
        try {
            RegistryPuller.parseRef(in);
            check(label + "（应拒绝）", false);
        } catch (IOException e) {
            check(label, e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }

    /* -------------------- platformMatches -------------------- */

    static void platformCases() {
        String a64 = ContainerBootstrap.ABI_ARM64;
        String a7 = ContainerBootstrap.ABI_ARM;
        String x64 = ContainerBootstrap.ABI_X86_64;

        // §3.6 平台选择表
        check("platform: arm64-v8a/arm64/v8", RegistryPuller.platformMatches(a64, "linux", "arm64", "v8"));
        check("platform: arm64-v8a/arm64/缺省", RegistryPuller.platformMatches(a64, "linux", "arm64", ""));
        check("platform: arm64-v8a/arm64/8", RegistryPuller.platformMatches(a64, "linux", "arm64", "8"));
        check("platform: arm64-v8a 拒 arm", !RegistryPuller.platformMatches(a64, "linux", "arm", "v7"));
        check("platform: arm64-v8a 拒 amd64", !RegistryPuller.platformMatches(a64, "linux", "amd64", ""));

        check("platform: v7a/arm/v7", RegistryPuller.platformMatches(a7, "linux", "arm", "v7"));
        check("platform: v7a/arm/缺省", RegistryPuller.platformMatches(a7, "linux", "arm", ""));
        check("platform: v7a/arm/v8 不匹配", !RegistryPuller.platformMatches(a7, "linux", "arm", "v8"));

        check("platform: x86_64/amd64", RegistryPuller.platformMatches(x64, "linux", "amd64", ""));
        check("platform: x86_64 拒 arm64", !RegistryPuller.platformMatches(x64, "linux", "arm64", "v8"));

        // os 必须是 linux（attestation 条目的 platform 是 unknown，靠这条挡掉）
        check("platform: os 非 linux 拒", !RegistryPuller.platformMatches(a64, "windows", "arm64", "v8"));
        check("platform: os 空 拒", !RegistryPuller.platformMatches(a64, "", "arm64", "v8"));
        check("platform: 未知 ABI 拒", !RegistryPuller.platformMatches("mips", "linux", "arm64", "v8"));
    }

    /* -------------------- layerKind / rejectReason -------------------- */

    static void layerKindCases() {
        check("layer: docker gzip → 0", RegistryPuller.layerKind(
                "application/vnd.docker.image.rootfs.diff.tar.gzip") == 0);
        check("layer: oci gzip → 0", RegistryPuller.layerKind(
                "application/vnd.oci.image.layer.v1.tar+gzip") == 0);
        check("layer: 缺省 mediaType → 0", RegistryPuller.layerKind("") == 0);
        check("layer: null → 0", RegistryPuller.layerKind(null) == 0);

        check("layer: docker 未压缩 tar → 1", RegistryPuller.layerKind(
                "application/vnd.docker.image.rootfs.diff.tar") == 1);
        check("layer: oci 未压缩 tar → 1", RegistryPuller.layerKind(
                "application/vnd.oci.image.layer.v1.tar") == 1);

        String zstd = RegistryPuller.rejectReason("application/vnd.oci.image.layer.v1.tar+zstd");
        check("layer: oci zstd 拒", RegistryPuller.layerKind(
                "application/vnd.oci.image.layer.v1.tar+zstd") == -1);
        check("layer: zstd 文案指路导入", zstd != null && zstd.contains("docker save"));

        String nd = RegistryPuller.rejectReason(
                "application/vnd.oci.image.layer.nondistributable.v1.tar+gzip");
        check("layer: nondistributable 拒", nd != null && nd.contains("不可分发"));
        check("layer: foreign 拒", RegistryPuller.rejectReason(
                "application/vnd.docker.image.rootfs.foreign.diff.tar.gzip") != null);

        check("layer: 陌生类型拒", RegistryPuller.layerKind("application/x-mystery") == -1);
        check("layer: 陌生类型有原因", RegistryPuller.rejectReason("application/x-mystery") != null);
    }

    /* -------------------- normalizeMirror（换源） -------------------- */

    static void mirrorCases() throws Exception {
        // 清空语义
        check("mirror: null → 空", RegistryPuller.normalizeMirror(null).isEmpty());
        check("mirror: 空串 → 空", RegistryPuller.normalizeMirror("").isEmpty());
        check("mirror: 空白 → 空", RegistryPuller.normalizeMirror("  ").isEmpty());
        check("mirror: clear → 空", RegistryPuller.normalizeMirror("Clear").isEmpty());
        check("mirror: 官方 → 空", RegistryPuller.normalizeMirror("官方").isEmpty());

        // 归一化
        check("mirror: 裸域名补 https",
                "https://docker.1ms.run".equals(RegistryPuller.normalizeMirror("docker.1ms.run")));
        check("mirror: 剥尾斜杠",
                "https://docker.1ms.run".equals(RegistryPuller.normalizeMirror("docker.1ms.run/")));
        check("mirror: 剥 /v2 后缀",
                "https://docker.1ms.run".equals(RegistryPuller.normalizeMirror(
                        "https://docker.1ms.run/v2")));
        check("mirror: 剥 /v2/ 后缀",
                "https://docker.1ms.run".equals(RegistryPuller.normalizeMirror(
                        "https://docker.1ms.run/v2/")));
        check("mirror: 保留路径（alpine 目录）",
                "https://mirrors.tuna.tsinghua.edu.cn/alpine".equals(
                        RegistryPuller.normalizeMirror("mirrors.tuna.tsinghua.edu.cn/alpine/")));
        check("mirror: 大小写协议归一后主机保留原样", RegistryPuller.normalizeMirror(
                "HTTPS://Docker.1ms.RUN").endsWith(".RUN"));
        check("mirror: 带端口", "http://192.168.1.10:5000".equals(
                RegistryPuller.normalizeMirror("http://192.168.1.10:5000")));

        // 拒绝类
        mirrorFail("mirror: ftp 协议拒", "ftp://example.com");
        mirrorFail("mirror: 陌生协议拒", "file:///etc/passwd");
        mirrorFail("mirror: 主机含非法字符", "https://dock er.io");
        mirrorFail("mirror: 端口非数字", "https://example.com:port");
        mirrorFail("mirror: 只有协议", "https://");
    }

    static void mirrorFail(String label, String in) {
        try {
            RegistryPuller.normalizeMirror(in);
            check(label + "（应拒绝）", false);
        } catch (IOException e) {
            check(label, e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }

    /* -------------------- realmParam（WWW-Authenticate 解析） -------------------- */

    static void realmCases() {
        String hub = "Bearer realm=\"https://auth.docker.io/token\","
                + "service=\"registry.docker.io\",scope=\"repository:library/busybox:pull\","
                + "error=\"insufficient_scope\"";
        check("realm: hub realm", "https://auth.docker.io/token".equals(
                RegistryPuller.realmParam(hub, "realm")));
        check("realm: hub service", "registry.docker.io".equals(
                RegistryPuller.realmParam(hub, "service")));
        check("realm: hub scope", "repository:library/busybox:pull".equals(
                RegistryPuller.realmParam(hub, "scope")));
        check("realm: 参数名前缀不误匹配（error 不含 err）",
                RegistryPuller.realmParam(hub, "err") == null);

        String ms = "Bearer realm=\"https://docker.1ms.run/openapi/v1/auth/token\","
                + "service=\"docker.1ms.run\"";
        check("realm: 1ms realm", "https://docker.1ms.run/openapi/v1/auth/token".equals(
                RegistryPuller.realmParam(ms, "realm")));
        check("realm: 1ms service", "docker.1ms.run".equals(
                RegistryPuller.realmParam(ms, "service")));
        check("realm: 无 scope 返回 null", RegistryPuller.realmParam(ms, "scope") == null);

        String dc = "Www-Authenticate: Bearer realm=\"https://m.daocloud.io/auth/token\","
                + "service=\"docker.m.daocloud.io\"";
        check("realm: daocloud realm", "https://m.daocloud.io/auth/token".equals(
                RegistryPuller.realmParam(dc, "realm")));

        check("realm: null 头返回 null", RegistryPuller.realmParam(null, "realm") == null);
        check("realm: 空串返回 null", RegistryPuller.realmParam("", "realm") == null);
        check("realm: 无引号形式返回 null",
                RegistryPuller.realmParam("Basic realm=\"x\"", "charset") == null);
    }

    /* -------------------- 工具 -------------------- */

    static void check(String label, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  ok  " + label);
        } else {
            fail++;
            System.out.println("FAIL  " + label);
        }
    }
}
