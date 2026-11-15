import dev.dualterm.container.Containers;
import dev.dualterm.container.ImageImporter;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;

/**
 * ImageImporter 桌面回归（可离线跑的纯逻辑部分）：
 *   1. detectArch —— ELF 头架构识别（导入后给用户的架构提示靠它）
 *   2. pickRef（反射调用，方法体不依赖 Context）—— 镜像 ref 闸门：
 *      白名单拒绝恶意 RepoTags、normalizeImage 补 tag、docker.io 前缀剥离
 *
 * 整包 importArchive 链路依赖 Context + 真 org.json（android.jar 桩在桌面
 * 返回 null），需要上真机或引入 org.json 实现后再测，见 README 已知限制。
 */
public class ImportTest {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        detectArchCases();
        pickRefCases();
        validImageRefCases();

        System.out.println();
        System.out.println("PASS " + pass + "  FAIL " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* -------------------- detectArch -------------------- */

    static void detectArchCases() throws Exception {
        File rootfs = Files.createTempDirectory("impt1").toFile();
        // x86-64：e_machine 小端 0x3E
        check("detectArch: x86-64", "x86-64".equals(
                detectArchWith(rootfs, "bin/busybox", elf(0x3E))));
        check("detectArch: aarch64", "aarch64".equals(
                detectArchWith(rootfs, "bin/busybox", elf(0xB7))));
        check("detectArch: arm", "arm".equals(
                detectArchWith(rootfs, "bin/busybox", elf(0x28))));
        check("detectArch: x86", "x86".equals(
                detectArchWith(rootfs, "bin/busybox", elf(0x03))));
        check("detectArch: 未知机器号给 elf-machine 提示", "elf-machine-0x99".equals(
                detectArchWith(rootfs, "bin/busybox", elf(0x99))));

        // 候选回退：busybox 缺、sh 在
        check("detectArch: busybox 缺时回退 bin/sh", "aarch64".equals(
                detectArchWith(rootfs, "bin/sh", elf(0xB7))));
        // 非 ELF 文本与过短文件
        check("detectArch: 非 ELF 返回 null", null == detectArchWith(rootfs, "bin/busybox",
                "plain text not elf".getBytes("UTF-8")));
        check("detectArch: 过短文件返回 null", null == detectArchWith(rootfs, "bin/busybox",
                new byte[]{0x7F, 'E', 'L', 'F'}));
        // 空目录
        File empty = Files.createTempDirectory("impt2").toFile();
        check("detectArch: 没有 candidates 返回 null", null == ImageImporter.detectArch(empty));
    }

    /** 只放一个候选可执行文件，读回架构。 */
    static String detectArchWith(File rootfs, String path, byte[] content) throws Exception {
        File f = new File(rootfs, path);
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        out.write(content);
        out.close();
        String arch = ImageImporter.detectArch(rootfs);
        f.delete();
        return arch;
    }

    /** 最小 ELF 头：magic + e_machine（偏移 18，小端）。 */
    static byte[] elf(int machine) {
        byte[] h = new byte[20];
        h[0] = 0x7F;
        h[1] = 'E';
        h[2] = 'L';
        h[3] = 'F';
        h[18] = (byte) (machine & 0xFF);
        h[19] = (byte) ((machine >> 8) & 0xFF);
        return h;
    }

    /* -------------------- pickRef（反射，私有但方法体纯逻辑） -------------------- */

    static void pickRefCases() throws Exception {
        Method pick = ImageImporter.class.getDeclaredMethod(
                "pickRef", Class.forName("android.content.Context"), String.class, String.class);
        pick.setAccessible(true);

        check("pickRef: hint 优先于归档 tag", "user:1.0".equals(
                pick.invoke(null, null, "user:1.0", "alpine:3.20")));
        check("pickRef: 无 hint 用归档 RepoTags", "alpine:3.20".equals(
                pick.invoke(null, null, "", "alpine:3.20")));
        check("pickRef: 两者都空时给 imported 时间戳", ((String) pick.invoke(null, null, "", null))
                .startsWith("imported:"));
        check("pickRef: docker.io/library/ 前缀剥离", "alpine:latest".equals(
                pick.invoke(null, null, "", "docker.io/library/alpine")));
        check("pickRef: 无 tag 自动补 :latest", "busybox:latest".equals(
                pick.invoke(null, null, "busybox", null)));

        // P0 闸门：恶意 RepoTags 必须被拒绝而不是静默通过
        String[] evil = {"evil;rm -rf /", "a$(id)", "a`id`", "a b", "a'b\"c", "a|b", "a&b",
                "\u202eexe", "a;b|c", "-oProxyCommand=x"};
        int rejected = 0;
        for (String e : evil) {
            try {
                pick.invoke(null, null, "", e);
            } catch (java.lang.reflect.InvocationTargetException te) {
                if (te.getCause() instanceof java.io.IOException) {
                    rejected++;
                }
            }
        }
        check("pickRef: 恶意归档 tag 全部被拒（10/10）", rejected == evil.length);
    }

    static void validImageRefCases() {
        check("validImageRef: 常规名字通过", Containers.validImageRef("alpine:3.20"));
        check("validImageRef: 带仓库路径通过", Containers.validImageRef("my.reg.io/app/img:1.0"));
        check("validImageRef: 空串拒绝", !Containers.validImageRef(""));
        check("validImageRef: 分号拒绝", !Containers.validImageRef("a;b"));
        check("validImageRef: 命令替换拒绝", !Containers.validImageRef("$(id)"));
        check("validImageRef: 反引号拒绝", !Containers.validImageRef("a`id`"));
        check("validImageRef: 空格拒绝", !Containers.validImageRef("a b"));
        check("validImageRef: 引号拒绝", !Containers.validImageRef("a'b"));
        check("validImageRef: 竖线拒绝", !Containers.validImageRef("a|b"));
        check("validImageRef: 超长拒绝", !Containers.validImageRef(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS  " + name);
        } else {
            fail++;
            System.out.println("FAIL  " + name);
        }
    }
}
