import dev.dualterm.container.TarGzExtractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * TarGzExtractor 桌面回归：手写 tar 构建器喂归档，验证
 * 穿越/白化/长名/硬链接/符号链接/目录与文件互换/压缩识别。
 * 零依赖 main + 断言，与 EmuTest 同款跑法。
 */
public class TarTest {

    static int pass = 0;
    static int fail = 0;

    public static void main(String[] args) throws Exception {
        File base = Files.createTempDirectory("dartest").toFile();

        basicExtract(new File(base, "t1"));
        gzipDetect(new File(base, "t2"));
        traversalRejected(new File(base, "t3"));
        dotSlashAndAbsolute(new File(base, "t4"));
        whiteoutFile(new File(base, "t5"));
        whiteoutOpaqueDir(new File(base, "t6"));
        whiteoutNotAppliedInPlainExtract(new File(base, "t7"));
        paxLongName(new File(base, "t8"));
        gnuLongName(new File(base, "t9"));
        ustarPrefixName(new File(base, "t10"));
        hardLink(new File(base, "t11"));
        symlinkMaterialize(new File(base, "t12"));
        dirReplacedByFile(new File(base, "t13"));
        fileReplacedByDir(new File(base, "t14"));
        listNamesCleaned(new File(base, "t15"));
        fileReplacesSymlink(new File(base, "t17"));
        hardLinkFallback(new File(base, "t18"));
        hardLinkNoEscape(new File(base, "t19"));

        File guard = new File(base, "t16");
        guard.mkdirs();
        File inside = new File(guard, "kid/link");
        inside.getParentFile().mkdirs();
        new FileOutputStream(inside).write(1);
        File outside = new File(base, "victim.txt");
        new FileOutputStream(outside).write(1);
        boolean linked;
        try {
            Files.createSymbolicLink(inside.toPath(), outside.toPath());
            linked = true;
        } catch (Exception e) {
            linked = false; // Windows 无符号链接权限时跳过该用例
        }
        if (linked) {
            dev.dualterm.container.Containers.deleteRecursive(guard, base);
            check("绝对符号链接不阻断删除（删除收敛的核心场景）", !guard.exists() || list(guard).isEmpty());
        } else {
            pass++;
            System.out.println("SKIP  绝对符号链接用例（本机不支持符号链接）");
        }

        System.out.println();
        System.out.println("PASS " + pass + "  FAIL " + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }

    /* -------------------- 用例 -------------------- */

    static void basicExtract(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.dir("etc");
        b.file("etc/hostname", "myhost".getBytes("UTF-8"), 0644);
        b.file("run.sh", "#!/bin/sh\necho hi".getBytes("UTF-8"), 0755);
        extract(b, dest, false);

        check("普通条目：文件落位", readFile(dest, "etc/hostname").equals("myhost"));
        check("普通条目：脚本落位", readFile(dest, "run.sh").startsWith("#!/bin/sh"));
        check("普通条目：目录建出", new File(dest, "etc").isDirectory());
    }

    static void gzipDetect(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.file("a.txt", "gz-content".getBytes("UTF-8"), 0644);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        GZIPOutputStream g = new GZIPOutputStream(gz);
        g.write(b.build());
        g.close();
        int n = TarGzExtractor.extract(new ByteArrayInputStream(gz.toByteArray()), dest, null);
        check("gzip 自动识别并解包", n == 1 && readFile(dest, "a.txt").equals("gz-content"));
    }

    static void traversalRejected(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.file("../../etc/evil", "pwned".getBytes("UTF-8"), 0644);
        b.file("ok.txt", "fine".getBytes("UTF-8"), 0644);
        extract(b, dest, false);

        check("「..」穿越条目被拒", !new File(dest, "etc/evil").exists()
                && !new File(dest.getParentFile(), "etc").exists());
        check("穿越条目不影响后续正常条目", readFile(dest, "ok.txt").equals("fine"));
    }

    static void dotSlashAndAbsolute(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.file("./dotted.txt", "d1".getBytes("UTF-8"), 0644);
        b.file("/abs/path.txt", "d2".getBytes("UTF-8"), 0644);
        extract(b, dest, false);

        check("./ 前缀归一", readFile(dest, "dotted.txt").equals("d1"));
        check("绝对路径收进解包根", readFile(dest, "abs/path.txt").equals("d2"));
    }

    static void whiteoutFile(File dest) throws Exception {
        // 下层：etc/a.conf, etc/b.conf
        File etc = new File(dest, "etc");
        etc.mkdirs();
        writeRaw(new File(etc, "a.conf"), "old-a");
        writeRaw(new File(etc, "b.conf"), "old-b");

        TarBuilder b = new TarBuilder();
        b.file("etc/.wh.a.conf", new byte[0], 0644); // 白化 a.conf
        b.file("etc/c.conf", "new-c".getBytes("UTF-8"), 0644);
        extract(b, dest, true);

        check("白化：.wh.x 删除下层文件", !new File(etc, "a.conf").exists());
        check("白化：同目录其他文件保留", readFile(dest, "etc/b.conf").equals("old-b"));
        check("白化：新文件照常写入", readFile(dest, "etc/c.conf").equals("new-c"));
    }

    static void whiteoutOpaqueDir(File dest) throws Exception {
        writeRaw(new File(new File(dest, "opt/lower"), "x.txt"), "x");
        writeRaw(new File(new File(dest, "opt/lower"), "y.txt"), "y");
        writeRaw(new File(new File(dest, "opt/keep"), "k.txt"), "k");

        TarBuilder b = new TarBuilder();
        b.file("opt/lower/.wh..wh..opq", new byte[0], 0644);
        b.file("opt/lower/new.txt", "n".getBytes("UTF-8"), 0644);
        extract(b, dest, true);

        check("opq 白化：目录下层内容清空", !new File(dest, "opt/lower/x.txt").exists()
                && !new File(dest, "opt/lower/y.txt").exists());
        check("opq 白化：本层新文件保留", readFile(dest, "opt/lower/new.txt").equals("n"));
        check("opq 白化：其他目录不受影响", readFile(dest, "opt/keep/k.txt").equals("k"));
    }

    static void whiteoutNotAppliedInPlainExtract(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.file(".wh.sensitive", new byte[0], 0644);
        extract(b, dest, false);
        check("普通解包不处理白化标记", new File(dest, ".wh.sensitive").exists());
    }

    static void paxLongName(File dest) throws Exception {
        String deep = "p/";
        for (int i = 0; i < 12; i++) {
            deep += "segment" + i + "/";
        }
        deep += "leaf-with-very-long-name-final.txt";
        // name 字段塞不下的长名走 pax 扩展头
        TarBuilder b = new TarBuilder();
        b.paxFile(deep, "pax".getBytes("UTF-8"), 0644);
        extract(b, dest, false);
        check("pax 长文件名（>" + 100 + " 字节）正确落位", readFile(dest, deep).equals("pax"));
    }

    static void gnuLongName(File dest) throws Exception {
        String deep = "g/";
        for (int i = 0; i < 10; i++) {
            deep += "gnu-seg" + i + "/";
        }
        deep += "final.bin";
        TarBuilder b = new TarBuilder();
        b.gnuFile(deep, "gnu".getBytes("UTF-8"), 0644);
        extract(b, dest, false);
        check("GNU L 长文件名正确落位", readFile(dest, deep).equals("gnu"));
    }

    static void ustarPrefixName(File dest) throws Exception {
        // name=leaf.txt，prefix=a/b/c —— 两个 100 字节字段拼出全名
        TarBuilder b = new TarBuilder();
        b.prefixedFile("a/b/c", "leaf.txt", "pre".getBytes("UTF-8"), 0644);
        extract(b, dest, false);
        check("ustar prefix+name 拼接", readFile(dest, "a/b/c/leaf.txt").equals("pre"));
    }

    static void hardLink(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.file("bin/busybox", "BUSYBOX".getBytes("UTF-8"), 0755);
        b.link("bin/sh", "bin/busybox", '1');
        extract(b, dest, false);
        File sh = new File(dest, "bin/sh");
        check("硬链接可用（createLink 或复制退路）", sh.exists()
                && Arrays.equals(readBytes(dest, "bin/sh"), "BUSYBOX".getBytes("UTF-8")));
    }

    static void symlinkMaterialize(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.file("bin/busybox", "BUSYBOX2".getBytes("UTF-8"), 0755);
        b.link("bin/sh", "/bin/busybox", '2');
        extract(b, dest, false);
        File sh = new File(dest, "bin/sh");
        // 建链成功时落的是「悬空绝对链接」——/bin/busybox 在解包宿主上不存在，
        // File.exists() 跟链返回 false，这是预期形态（真机上 proot 在 rootfs 内解析）。
        // 判定用 NOFOLLOW：要么是链接本身，要么是实体化退路（内容正确）。
        boolean ok = Files.isSymbolicLink(sh.toPath())
                || Arrays.equals(readBytes(dest, "bin/sh"), "BUSYBOX2".getBytes("UTF-8"));
        check("绝对符号链接：建链或实体化退路", ok);
    }

    static void dirReplacedByFile(File dest) throws Exception {
        File d = new File(dest, "d");
        d.mkdirs();
        writeRaw(new File(d, "child.txt"), "stale");
        TarBuilder b = new TarBuilder();
        b.file("d", "now-a-file".getBytes("UTF-8"), 0644);
        extract(b, dest, false);
        check("上层目录被本层文件顶替", new File(dest, "d").isFile()
                && readFile(dest, "d").equals("now-a-file"));
    }

    static void fileReplacedByDir(File dest) throws Exception {
        writeRaw(new File(dest, "d"), "stale-file");
        TarBuilder b = new TarBuilder();
        b.dir("d");
        b.file("d/inner.txt", "inner".getBytes("UTF-8"), 0644);
        extract(b, dest, false);
        check("上层文件被本层目录顶替", new File(dest, "d").isDirectory()
                && readFile(dest, "d/inner.txt").equals("inner"));
    }

    /** Android 上硬链接一律被 SELinux 拒，桌面能建链接，用反射把生产代码切到兜底路径。
     *  uutils 的 applet 必须变成钉死名字的脚本：符号链接会让 /proc/self/exe 报
     *  coreutils，触发 Security violation（ubuntu:25.10 实测容器起不来）。 */
    static void hardLinkFallback(File dest) throws Exception {
        boolean links = symlinkSupported(dest.getParentFile());
        TarBuilder b = new TarBuilder();
        b.file("bin/sh", "SH".getBytes("UTF-8"), 0755);
        b.file("usr/bin/coreutils", "COREUTILS".getBytes("UTF-8"), 0755);
        b.link("usr/bin/env", "/usr/bin/coreutils", '2');
        b.link("usr/bin/ls", "usr/bin/env", '1');
        b.link("usr/bin/cat", "usr/bin/coreutils", '1');
        b.file("bin/busybox", "BUSYBOX".getBytes("UTF-8"), 0755);
        b.link("bin/pidof", "bin/busybox", '1');
        forceFallback(true);
        try {
            extract(b, dest, false);
        } finally {
            forceFallback(false);
        }
        check("兜底：uutils applet 写成钉死名字的脚本",
                readFile(dest, "usr/bin/cat").equals(
                        "#!/bin/sh\nexec /usr/bin/coreutils \"cat\" \"$@\"\n"));
        if (links) {
            check("兜底：经符号链接的硬链接钉死自己的名字",
                    readFile(dest, "usr/bin/ls").contains("\"ls\""));
            check("兜底：符号链接条目保持链接",
                    Files.isSymbolicLink(new File(dest, "usr/bin/env").toPath()));
            check("兜底：非 uutils 的硬链接走符号链接",
                    Files.isSymbolicLink(new File(dest, "bin/pidof").toPath())
                            && Files.readSymbolicLink(new File(dest, "bin/pidof").toPath())
                            .toString().equals("/bin/busybox"));
        } else {
            pass += 3;
            System.out.println("SKIP  兜底链接形态（本机不支持符号链接）");
        }

        File noSh = new File(dest.getParentFile(), dest.getName() + "-nosh");
        TarBuilder c = new TarBuilder();
        c.file("usr/bin/coreutils", "COREUTILS".getBytes("UTF-8"), 0755);
        c.link("usr/bin/cat", "usr/bin/coreutils", '1');
        forceFallback(true);
        try {
            extract(c, noSh, false);
        } finally {
            forceFallback(false);
        }
        check("兜底：没有 /bin/sh 时按份拷贝",
                Arrays.equals(readBytes(noSh, "usr/bin/cat"), "COREUTILS".getBytes("UTF-8")));
    }

    /** 硬链接目标穿越：.. / 绝对路径 / 同前缀兄弟目录都不能把宿主文件读进 rootfs。 */
    static void hardLinkNoEscape(File dest) throws Exception {
        // rootfs 外的宿主文件——任何条目都不该把它读进来
        File victim = new File(dest.getParentFile(), "escape-victim.txt");
        try (FileOutputStream f = new FileOutputStream(victim)) {
            f.write("SECRET".getBytes("UTF-8"));
        }
        String vabs = victim.getAbsolutePath();
        String dots = "../" + victim.getName();

        TarBuilder b = new TarBuilder();
        b.file("bin/sh", "SH".getBytes("UTF-8"), 0755);
        b.link("usr/bin/a-dotdot", dots, '1');       // .. 目标：cleanName 返回 null
        b.link("usr/bin/b-absolute", vabs, '1');     // 绝对目标：cleanName 去前导 / 后仍含 .. 语义
        forceFallback(true);
        try {
            extract(b, dest, false);
        } finally {
            forceFallback(false);
        }
        // 目标条目要么不存在，要么绝不是宿主文件内容
        for (String n : new String[]{"usr/bin/a-dotdot", "usr/bin/b-absolute"}) {
            File made = new File(dest, n);
            check("硬链接拒绝穿越目标：" + n,
                    !made.exists() || !readFile(dest, n).equals("SECRET"));
        }
        victim.delete();
    }

    /** 探测本机是否能建符号链接（Windows 未开开发者模式时为否）。 */
    static boolean symlinkSupported(File dir) {
        File a = new File(dir, "symlink-probe-a");
        File b = new File(dir, "symlink-probe-b");
        try {
            a.createNewFile();
            Files.createSymbolicLink(b.toPath(), a.toPath());
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            b.delete();
            a.delete();
        }
    }

    /** 桌面上 createLink 可用；把生产代码的开关拨到「跳过链接」，回到 Android 的路径。 */
    static void forceFallback(boolean value) throws Exception {
        java.lang.reflect.Field f = TarGzExtractor.class.getDeclaredField("skipHardLink");
        f.setAccessible(true);
        f.setBoolean(null, value);
    }

    static void listNamesCleaned(File dest) throws Exception {
        TarBuilder b = new TarBuilder();
        b.file("ok.txt", "x".getBytes("UTF-8"), 0644);
        b.file("../escape", "x".getBytes("UTF-8"), 0644);
        List<String> names = TarGzExtractor.listNames(new ByteArrayInputStream(b.build()), 100);
        check("listNames 清洗并滤穿越", names.contains("ok.txt") && !names.contains("escape"));
    }

    /** 层 1 把路径建成绝对符号链接，层 2 又在同一路径落实体文件（busybox 镜像的常见形态）。
     *  直接开流写会顺着链接跑到 rootfs 外（真机表现为宿主 /bin 只读，EROFS）。 */
    static void fileReplacesSymlink(File dest) throws Exception {
        TarBuilder lower = new TarBuilder();
        lower.file("bin/busybox", "BUSYBOX4".getBytes("UTF-8"), 0755);
        lower.link("bin/pidof", "/bin/busybox", '2');
        extract(lower, dest, false);
        TarBuilder upper = new TarBuilder();
        upper.file("bin/pidof", "REGULAR".getBytes("UTF-8"), 0755);
        extract(upper, dest, false);
        check("实体文件顶替符号链接：内容落在 rootfs 内",
                Arrays.equals(readBytes(dest, "bin/pidof"), "REGULAR".getBytes("UTF-8")));
    }

    /* -------------------- 工具 -------------------- */

    static void extract(TarBuilder b, File dest, boolean layer) throws Exception {
        InputStream in = new ByteArrayInputStream(b.build());
        if (layer) {
            TarGzExtractor.extractLayer(in, dest, null);
        } else {
            TarGzExtractor.extract(in, dest, null);
        }
    }

    static String readFile(File dest, String rel) throws IOException {
        return new String(readBytes(dest, rel), "UTF-8");
    }

    static byte[] readBytes(File dest, String rel) throws IOException {
        return Files.readAllBytes(new File(dest, rel).toPath());
    }

    static void writeRaw(File f, String content) throws IOException {
        f.getParentFile().mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        out.write(content.getBytes("UTF-8"));
        out.close();
    }

    static List<String> list(File dir) {
        String[] kids = dir.list();
        return kids == null ? java.util.Collections.<String>emptyList() : Arrays.asList(kids);
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

    /** 手写 ustar 头的极简归档构建器。 */
    static class TarBuilder {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        void dir(String name) throws IOException {
            entry(name, 0, '5', 0755, "", new byte[0]);
        }

        void file(String name, byte[] data, int mode) throws IOException {
            entry(name, 0, '0', mode, "", data);
        }

        void paxFile(String name, byte[] data, int mode) throws IOException {
            // pax 记录：长度含自身。"NN path=...\n"
            String record = " path=" + name + "\n";
            String header = (record.length() + String.valueOf(record.length()).length()) + record;
            entry("", 0, 'x', 0644, "", header.getBytes("UTF-8"));
            entry(name.length() >= 100 ? name.substring(0, 99) : name, 0, '0', mode, "", data);
        }

        void gnuFile(String name, byte[] data, int mode) throws IOException {
            entry("", 0, 'L', 0644, "", (name + "\0").getBytes("UTF-8"));
            entry(name.length() >= 100 ? name.substring(0, 99) : name, 0, '0', mode, "", data);
        }

        void prefixedFile(String prefix, String name, byte[] data, int mode) throws IOException {
            prefixHeader(prefix, name, data, mode);
        }

        void link(String name, String target, char type) throws IOException {
            entry(name, 0, type, 0777, target, new byte[0]);
        }

        void entry(String name, long mtime, char type, int mode, String linkName, byte[] data)
                throws IOException {
            byte[] h = new byte[512];
            byte[] nb = name.getBytes("UTF-8");
            if (nb.length > 100) {
                throw new IllegalArgumentException("name 字段超长，请走 paxFile/gnuFile：" + name);
            }
            System.arraycopy(nb, 0, h, 0, nb.length);
            oct(h, 100, 8, mode);          // mode
            oct(h, 108, 8, 0);             // uid
            oct(h, 116, 8, 0);             // gid
            oct(h, 124, 12, data.length);  // size
            oct(h, 136, 12, mtime);        // mtime
            h[156] = (byte) type;
            byte[] lb = linkName.getBytes("UTF-8");
            System.arraycopy(lb, 0, h, 157, Math.min(lb.length, 100));
            System.arraycopy("ustar\0".getBytes("UTF-8"), 0, h, 257, 6);
            System.arraycopy("00".getBytes("UTF-8"), 0, h, 263, 2);
            // checksum：chksum 字段先按空格算
            Arrays.fill(h, 148, 156, (byte) ' ');
            long sum = 0;
            for (byte x : h) {
                sum += x & 0xFF;
            }
            oct6(h, 148, sum);

            out.write(h);
            out.write(data);
            long pad = data.length % 512 == 0 ? 0 : 512 - (data.length % 512);
            for (long i = 0; i < pad; i++) {
                out.write(0);
            }
        }

        /** prefix + name 两段式（ustar 拆分长名）。 */
        void prefixHeader(String prefix, String name, byte[] data, int mode) throws IOException {
            byte[] h = new byte[512];
            byte[] nb = name.getBytes("UTF-8");
            System.arraycopy(nb, 0, h, 0, nb.length);
            oct(h, 100, 8, mode);
            oct(h, 124, 12, data.length);
            oct(h, 136, 12, 0);
            h[156] = '0';
            byte[] pb = prefix.getBytes("UTF-8");
            System.arraycopy(pb, 0, h, 345, pb.length);
            System.arraycopy("ustar\0".getBytes("UTF-8"), 0, h, 257, 6);
            System.arraycopy("00".getBytes("UTF-8"), 0, h, 263, 2);
            Arrays.fill(h, 148, 156, (byte) ' ');
            long sum = 0;
            for (byte x : h) {
                sum += x & 0xFF;
            }
            oct6(h, 148, sum);
            out.write(h);
            out.write(data);
            long pad = data.length % 512 == 0 ? 0 : 512 - (data.length % 512);
            for (long i = 0; i < pad; i++) {
                out.write(0);
            }
        }

        void oct(byte[] h, int off, int len, long v) {
            String s = Long.toOctalString(v);
            String padded = "000000000000".substring(0, len - 1 - s.length()) + s;
            byte[] sb = padded.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(sb, 0, h, off, sb.length);
            h[off + len - 1] = 0;
        }

        void oct6(byte[] h, int off, long v) {
            String s = Long.toOctalString(v);
            String padded = "000000".substring(0, 6 - s.length()) + s;
            byte[] sb = padded.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(sb, 0, h, off, sb.length);
            h[off + 6] = 0;
            h[off + 7] = ' ';
        }

        byte[] build() throws IOException {
            out.write(new byte[1024]); // 结束零块 ×2
            return out.toByteArray();
        }
    }
}
