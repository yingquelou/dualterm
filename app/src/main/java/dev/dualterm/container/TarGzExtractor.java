package dev.dualterm.container;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 极简 tar 解包器（ustar + pax 扩展头 + GNU 长文件名 + base-256 大文件），
 * 支持 gzip 压缩与未压缩两种 tar，并识别 docker 镜像层的 whiteout 标记。
 *
 * 为什么不用 commons-compress：整个工程刻意保持零第三方依赖，
 * 而 Alpine minirootfs / docker export / docker save 的层都是标准 tar，
 * 手写一份 350 行的解析器足够稳。
 */
public final class TarGzExtractor {

    public interface Progress {
        void onProgress(int entries, String currentPath);
    }

    private TarGzExtractor() {
    }

    /**
     * 解包普通 tar / tar.gz（自动识别 gzip），不做 whiteout 处理。
     * 用于 Alpine minirootfs、docker export 出来的扁平 rootfs。
     */
    public static int extract(InputStream source, File destDir, Progress progress) throws IOException {
        return walk(source, destDir, false, progress);
    }

    /**
     * 解包一个 docker 镜像层：多了一步 whiteout 处理
     * （<code>.wh.文件名</code> 表示删除下层的该文件，<code>.wh..wh..opq</code> 表示清空该目录）。
     * 这样按顺序把 docker save 里的 layer.tar 叠进同一个目录，得到的就是最终 rootfs。
     */
    public static int extractLayer(InputStream source, File destDir, Progress progress) throws IOException {
        return walk(source, destDir, true, progress);
    }

    /**
     * 只读条目名、跳过数据，用来判断归档里到底是什么：
     * manifest.json → docker save；index.json + oci-layout → OCI 布局；否则当扁平 rootfs。
     */
    public static List<String> listNames(InputStream source, int limit) throws IOException {
        List<String> names = new ArrayList<>();
        byte[] header = new byte[512];
        String pendingName = null;
        InputStream in = decompress(source);
        try {
            while (readFully(in, header, 512)) {
                if (isZeroBlock(header)) {
                    break;
                }
                String name = cstr(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int type = header[156] & 0x7F;
                String prefix = cstr(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
                if (type == 'x') {
                    String[] pax = parsePax(new String(readData(in, size), "UTF-8"));
                    if (pax[0] != null) {
                        pendingName = pax[0];
                    }
                    continue;
                }
                if (type == 'L') {
                    pendingName = trimNul(new String(readData(in, size), "UTF-8"));
                    continue;
                }
                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }
                skipEntry(in, size);
                String clean = cleanName(name);
                if (clean != null) {
                    names.add(clean);
                }
                if (names.size() >= limit) {
                    break;
                }
            }
        } finally {
            in.close();
        }
        return names;
    }

    /* -------------------- 主循环 -------------------- */

    private static int walk(InputStream source, File destDir, boolean whiteouts, Progress progress)
            throws IOException {
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("无法创建目录：" + destDir);
        }
        final int bufSize = 64 * 1024;
        byte[] header = new byte[512];
        byte[] copyBuf = new byte[bufSize];

        String destRoot = destDir.getCanonicalPath();
        String pendingName = null;
        String pendingLinkName = null;
        int entries = 0;

        InputStream in = decompress(source);
        try {
            while (readFully(in, header, 512)) {
                if (isZeroBlock(header)) {
                    break;
                }
                String name = cstr(header, 0, 100);
                long size = parseOctal(header, 124, 12);
                int type = header[156] & 0x7F;
                String linkName = cstr(header, 157, 100);
                int mode = (int) parseOctal(header, 100, 8);
                String prefix = cstr(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }

                // pax 扩展头
                if (type == 'x' || type == 'g') {
                    byte[] data = readData(in, size);
                    if (type == 'x') {
                        String[] pax = parsePax(new String(data, "UTF-8"));
                        if (pax[0] != null) {
                            pendingName = pax[0];
                        }
                        if (pax[1] != null) {
                            pendingLinkName = pax[1];
                        }
                    }
                    continue;
                }
                // GNU 长文件名 / 长链接名
                if (type == 'L') {
                    pendingName = trimNul(new String(readData(in, size), "UTF-8"));
                    continue;
                }
                if (type == 'K') {
                    pendingLinkName = trimNul(new String(readData(in, size), "UTF-8"));
                    continue;
                }
                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }
                if (pendingLinkName != null) {
                    linkName = pendingLinkName;
                    pendingLinkName = null;
                }

                String clean = cleanName(name);
                if (clean == null) {
                    skipEntry(in, size);
                    continue;
                }

                if (whiteouts && isWhiteout(clean)) {
                    applyWhiteout(destDir, clean);
                    skipEntry(in, size);
                    continue;
                }

                File out = new File(destDir, clean);
                // 逃逸检查只针对 out 的**父目录**：cleanName 已保证 clean 词法上无 .. /
                // 绝对路径，真正的逃逸只可能来自父目录里指向 rootfs 外的符号链接。
                // 不能查 out 自身——上层镜像常把同一路径留成 guest 绝对路径符号链接
                // （busybox 的 /bin/pidof -> /bin/busybox），getCanonicalPath() 会跟随它
                // 解析到宿主根目录，把合法的「实体文件覆盖旧链接」误判成逃逸而跳过，
                // 结果上层文件内容落不进 rootfs（Linux/Android 可建符号链接时必现）。
                // out 自身要不要覆盖由 writeFile/makeSymlink 各自处理。
                boolean escape;
                try {
                    File outParent = out.getParentFile();
                    escape = !isInside(destRoot,
                            (outParent == null ? destDir : outParent).getCanonicalPath());
                } catch (IOException bad) {
                    escape = true;   // 畸形路径视为逃逸，不让一条恶意条目中断解包
                }
                if (escape) {
                    skipEntry(in, size);
                    continue;
                }

                if (type == '5') {
                    ensureDir(out, destDir);
                    skipEntry(in, size);
                } else if (type == '0' || type == 0 || type == '7') {
                    writeFile(in, out, size, copyBuf, (mode & 0111) != 0, destDir);
                } else if (type == '2') {
                    makeSymlink(destDir, out, linkName);
                    skipEntry(in, size);
                } else if (type == '1') {
                    makeHardLink(destDir, out, linkName);
                    skipEntry(in, size);
                } else {
                    skipEntry(in, size);
                }

                entries++;
                if (progress != null) {
                    progress.onProgress(entries, clean);
                }
            }
        } finally {
            in.close();
        }
        return entries;
    }

    /* -------------------- whiteout -------------------- */

    /** 层里的白化标记：.wh.xxx / .wh..wh..opq */
    private static boolean isWhiteout(String clean) {
        int slash = clean.lastIndexOf('/');
        String base = slash < 0 ? clean : clean.substring(slash + 1);
        return base.startsWith(".wh.");
    }

    private static void applyWhiteout(File destDir, String clean) {
        int slash = clean.lastIndexOf('/');
        String parent = slash < 0 ? "" : clean.substring(0, slash);
        String base = slash < 0 ? clean : clean.substring(slash + 1);
        File dir = parent.isEmpty() ? destDir : new File(destDir, parent);

        if (".wh..wh..opq".equals(base)) {
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteTree(kid, destDir);
                }
            }
            return;
        }
        String target = base.substring(4);
        if (target.isEmpty()) {
            return;
        }
        deleteTree(new File(dir, target), destDir);
    }

    /* -------------------- 条目处理 -------------------- */

    private static void writeFile(InputStream in, File out, long size, byte[] buf,
                                  boolean executable, File destDir) throws IOException {
        File parent = out.getParentFile();
        if (parent != null) {
            ensureDir(parent, destDir);
        }
        if (out.isDirectory()) {
            // 上层是目录、这层是文件：目录让位
            deleteTree(out, destDir);
        }
        // 上层若是符号链接（如 busybox 的 /bin/pidof -> /bin/busybox），直接
        // FileOutputStream 会顺着绝对链接写进宿主 /bin（只读系统分区，EROFS）。
        // 本层实体文件必须落在 rootfs 内，先把旧链接删掉。
        dropSymlink(out);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            long remaining = size;
            while (remaining > 0) {
                int want = (int) Math.min(buf.length, remaining);
                int n = in.read(buf, 0, want);
                if (n < 0) {
                    throw new IOException("tar 数据被截断：" + out);
                }
                fos.write(buf, 0, n);
                remaining -= n;
            }
        }
        out.setReadable(true, true);
        out.setWritable(true, true);
        out.setExecutable(executable, true);
        skip(in, size % 512 == 0 ? 0 : 512 - (size % 512));
    }

    /** mkdirs，但碰到同名文件时先把文件删掉（镜像层里目录替换文件是合法的）。 */
    private static void ensureDir(File dir, File destDir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (dir.exists()) {
            deleteTree(dir, destDir);
        }
        File parent = dir.getParentFile();
        if (parent != null && !parent.exists()) {
            ensureDir(parent, destDir);
        }
        if (!dir.mkdir() && !dir.isDirectory()) {
            throw new IOException("无法创建目录：" + dir);
        }
    }

    private static void makeSymlink(File destDir, File link, String target)
            throws IOException {
        File parent = link.getParentFile();
        if (parent != null) {
            ensureDir(parent, destDir);
        }
        if (link.exists() || Files.isSymbolicLink(link.toPath())) {
            // 可能是上层留下的文件/目录，直接清掉再建
            deleteTree(link, destDir);
        }
        try {
            Path path = link.toPath();
            Files.createSymbolicLink(path, path.getFileSystem().getPath(target));
            return;
        } catch (Exception ignored) {
            // 落空：某些文件系统/权限下建不了链接，走下面的实体化退路
        }
        // 退路：把链接目标实体化复制过来。/bin/sh -> busybox 这类关键链接必须可用，
        // 否则容器里连 shell 都起不来（busybox 靠 argv[0] 分派，复制过去照样能跑）。
        File resolved = resolveLinkTarget(destDir, link, target);
        // 退路是把目标内容复制进 rootfs；目标若经 .. 或符号链接跑出 rootfs，
        // 就成了把宿主文件读进容器（信息泄露）。必须校验解析结果仍在 rootfs 内。
        if (resolved != null) {
            try {
                if (!isInside(destDir.getCanonicalPath(), resolved.getCanonicalPath())) {
                    resolved = null;
                }
            } catch (IOException bad) {
                resolved = null;
            }
        }
        if (resolved != null && resolved.isFile()) {
            copyFile(resolved, link);
            //noinspection ResultOfMethodCallIgnored
            link.setExecutable(resolved.canExecute(), true);
        } else if (resolved != null && resolved.isDirectory()) {
            ensureDir(link, destDir);
        } else {
            deleteTree(link, destDir);
            //noinspection ResultOfMethodCallIgnored
            link.createNewFile();
        }
    }

    /** 解析符号链接目标（支持绝对路径与相对路径，最多跟随 5 跳）。 */
    private static File resolveLinkTarget(File destDir, File link, String target) {
        File current;
        if (target.startsWith("/")) {
            current = new File(destDir, target.substring(1));
        } else {
            File parent = link.getParentFile();
            current = new File(parent == null ? destDir : parent, target);
        }
        for (int hop = 0; hop < 5; hop++) {
            try {
                current = current.getCanonicalFile();
            } catch (IOException ignored) {
                return current;
            }
            if (!current.exists()) {
                return current;
            }
            if (current.isFile() && current.length() > 0) {
                return current;
            }
            return current;
        }
        return current;
    }

    /** 目标位置若是符号链接，先删掉，避免写入顺着链接跑到 rootfs 外。 */
    private static void dropSymlink(File file) throws IOException {
        try {
            if (Files.isSymbolicLink(file.toPath())) {
                Files.delete(file.toPath());
            }
        } catch (IOException e) {
            throw new IOException("无法清理符号链接：" + file, e);
        }
    }

    /** canonical 路径是否落在 root 内（含 root 本身）；带分隔符避免同前缀兄弟目录误判。 */
    private static boolean isInside(String root, String canonical) {
        return canonical.equals(root) || canonical.startsWith(root + File.separator);
    }

    /**
     * 从 start 跟随符号链接（绝对目标按 rootfs 根解释），返回最终实体文件。
     * 只认 rootfs 内的路径，跳数上限 8，防止环路与「宿主绝对链接」逃逸。
     */
    private static File resolveInsideRootfs(File destDir, File start) {
        String root = destDir.getAbsolutePath();
        File cur = start;
        for (int hop = 0; hop < 8; hop++) {
            Path p = cur.toPath();
            if (!Files.isSymbolicLink(p)) {
                return cur.isFile() ? cur : null;
            }
            String target;
            try {
                target = Files.readSymbolicLink(p).toString();
            } catch (IOException e) {
                return null;
            }
            File base = target.startsWith("/")
                    ? destDir
                    : (cur.getParentFile() == null ? destDir : cur.getParentFile());
            cur = new File(base, target.startsWith("/") ? target.substring(1) : target);
            String abs = new File(cur.getAbsolutePath()).toPath().normalize().toString();
            if (!abs.equals(root) && !abs.startsWith(root + File.separator)) {
                return null;   // 指到 rootfs 外，交给拷贝兜底
            }
            cur = new File(abs);
        }
        return null;
    }

    /** rootfs 内的文件转成 guest 绝对路径（/usr/bin/coreutils），与镜像里的写法一致。 */
    private static String guestAbsolutePath(File destDir, File file) {
        String root = destDir.getAbsolutePath();
        String abs = file.getAbsolutePath();
        if (!abs.startsWith(root + File.separator)) {
            return null;
        }
        return "/" + abs.substring(root.length() + 1).replace(File.separatorChar, '/');
    }

    /**
     * uutils 的 applet 替代品：把 applet 名钉死在脚本里，交给多调用二进制分发。
     * 不能靠 $0 的 basename 取名字——proot 执行脚本时传下来的路径不保证是调用路径。
     */
    private static boolean writeCoreutilsShim(File destDir, File link, String guestTarget)
            throws IOException {
        if (!new File(destDir, "bin/sh").exists()) {
            return false;   // 无 shell 的镜像用不了脚本，只能按份拷贝
        }
        String applet = link.getName();
        // applet 名会被写进 shell 脚本的双引号里，只允许安全字符；含 " $ ` \ 换行等
        // 的名字会破坏引号结构甚至注入命令（tar 条目名不可信），退回按份拷贝。
        for (int i = 0; i < applet.length(); i++) {
            char c = applet.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == '+';
            if (!safe) {
                return false;
            }
        }
        String script = "#!/bin/sh\nexec " + guestTarget + " \"" + applet + "\" \"$@\"\n";
        try (FileOutputStream fos = new FileOutputStream(link)) {
            fos.write(script.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
        link.setReadable(true, true);
        link.setWritable(true, true);
        link.setExecutable(true, true);
        return true;
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = Files.newInputStream(src.toPath());
             FileOutputStream out = new FileOutputStream(dest)) {            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /** 测试用：置 true 时跳过 createLink，强制走 Android 上的兜底路径。 */
    static volatile boolean skipHardLink;

    /**
     * tar 硬链接条目（type '1'）。link() 一律被 SELinux 拒绝——**不是**系统调用
     * 缺失（bionic 有 link(2)，Files.createLink 直通它），也**不是**存储权限
     * （app 私有目录不需要任何权限）：AOSP sepolicy 对 untrusted_app/priv_app
     * 域一律 neverallow file_type:file link（Android 6 起如此；注释写明理由：
     * installd 无法保证删净被链接文件、硬链接易被用于安全攻击）。实测同一台
     * 设备上 shell 域（/data/local/tmp）也被拒，符号链接（lnk_file 类、独立
     * 权限线）则可创建。proot 在 app 进程内跑，容器里 link() 同样被拒。
     *
     * 兜底顺序：
     *   1. createLink（最佳：零拷贝、语义一致；root 设备/某些域可过）
     *   2. uutils 多调用二进制（真身叫 coreutils）：写成钉死 applet 名的小脚本。
     *      Ubuntu 25.10 起的 applet 是「cargo/bin/coreutils/<名> 硬链接到
     *      /usr/bin/coreutils」，同名校验打的是 require-utility-to-be-invoked-at-
     *      matching-path 补丁：读 /proc/self/exe 的**文件名**必须等于 argv[0]。
     *      符号链接的最终 dentry 是 coreutils，必然判 Security violation（实测
     *      /usr/bin/env 直接让容器起不来）；拷贝 10MB×上百个 applet 又要 1.2GB。
     *      60 字节脚本 `exec /usr/bin/coreutils 名 "$@"` 保持零拷贝且通过校验，
     *      代价是每次调用多一个 /bin/sh。没有 /bin/sh 的镜像退回拷贝。
     *   3. 指向**最终实体**的符号链接：busybox 这类只看 argv[0] 的多调用二进制
     *      走这里（硬链接密集的 busybox 镜像不再按份拷贝）
     *   4. 实体拷贝并从源恢复可执行位——硬链接条目的 tar 头不带 x 位，丢了它
     *      proot 会报 "'/usr/bin/env' is not executable"。盘满等情况下才走到
     */
    private static void makeHardLink(File destDir, File link, String target) throws IOException {
        String cleanTarget = cleanName(target);
        // cleanName 返回 null 说明目标带 .. / 绝对路径 / 空——绝不能退回原始 target，
        // 否则 new File(destDir, "../../宿主文件") 会把宿主文件链进/拷进 rootfs。
        if (cleanTarget == null) {
            return;
        }
        File src = new File(destDir, cleanTarget);
        // 二次校验：cleanName 已保证目标词法上在 rootfs 内（无 .. / 绝对路径）。
        // 这里只拦「目标路径里夹着指向 rootfs 外的符号链接」这一真逃逸——用父目录的
        // canonical 判断，而不是 src 自身：src 常是 guest 绝对路径符号链接
        // （usr/bin/env -> /usr/bin/coreutils），getCanonicalPath() 跟随它会解析到宿主
        // 根目录，把合法硬链接误判为逃逸并拒绝创建（Linux/Android 可建符号链接时必现，
        // 表现为 applet 脚本/符号链接缺失）。真正的符号链接跟随交给 resolveInsideRootfs
        // 按 rootfs 语义处理。畸形路径 canonical 抛异常（Windows 盘符等）视为逃逸拒绝。
        try {
            String root = destDir.getCanonicalPath();
            File srcParent = src.getParentFile();
            String parentCanonical = (srcParent == null ? destDir : srcParent).getCanonicalPath();
            if (!isInside(root, parentCanonical)) {
                return;
            }
        } catch (IOException bad) {
            return;
        }
        File parent = link.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        dropSymlink(link);
        if (!skipHardLink) {
            try {
                Files.createLink(link.toPath(), src.toPath());
                return;
            } catch (Exception ignored) {
                // 本机不支持硬链接：走下面的兜底
            }
        }
        File real = resolveInsideRootfs(destDir, src);
        String guest = real == null ? null : guestAbsolutePath(destDir, real);
        if (real != null && guest != null) {
            if ("coreutils".equals(real.getName())) {
                if (writeCoreutilsShim(destDir, link, guest)) {
                    return;
                }
            } else {
                try {
                    Files.createSymbolicLink(link.toPath(),
                            link.toPath().getFileSystem().getPath(guest));
                    return;
                } catch (Exception ignored) {
                    // 建不了链接，继续走拷贝
                }
            }
        }
        if (src.isFile()) {
            try {
                copyFile(src, link);
                //noinspection ResultOfMethodCallIgnored
                link.setExecutable(src.canExecute(), true);
                return;
            } catch (Exception ignored) {
                // 盘满等拷贝失败：符号链接聊胜于无
            }
        }
        try {
            Path rel = link.toPath().getParent().relativize(src.toPath());
            //noinspection ResultOfMethodCallIgnored
            link.delete();
            Files.createSymbolicLink(link.toPath(), rel);
        } catch (Exception ignored) {
            // 全部落空：留空，启动时会有更明确的报错
        }
    }

    /* -------------------- 原始读取 -------------------- */

    /** 自动识别 gzip（docker save/export 两种都有），非 gzip 时按裸 tar 读。 */
    private static InputStream decompress(InputStream source) throws IOException {
        BufferedInputStream bin = source instanceof BufferedInputStream
                ? (BufferedInputStream) source
                : new BufferedInputStream(source, 64 * 1024);
        bin.mark(4);
        int b1 = bin.read();
        int b2 = bin.read();
        bin.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            return new GZIPInputStream(bin, 64 * 1024);
        }
        return bin;
    }

    private static boolean readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) {
                return off > 0;
            }
            off += n;
        }
        return true;
    }

    /** 跳过整个条目（数据 + 512 字节对齐填充）。 */
    private static void skipEntry(InputStream in, long size) throws IOException {
        skip(in, size);
        long pad = size % 512 == 0 ? 0 : 512 - (size % 512);
        if (pad > 0) {
            skip(in, pad);
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long n = in.skip(remaining);
            if (n <= 0) {
                if (in.read() < 0) {
                    return;
                }
                n = 1;
            }
            remaining -= n;
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.max(16, size));
        byte[] buf = new byte[8192];
        long remaining = size;
        while (remaining > 0) {
            int want = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, want);
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
            remaining -= n;
        }
        skip(in, size % 512 == 0 ? 0 : 512 - (size % 512));
        return out.toByteArray();
    }

    /**
     * 删除保持在解包根内的路径。委托 Containers.deleteRecursive：
     * 这里原来是 getCanonicalPath 版本，rootfs 里 bin/sh -> /bin/busybox 这类
     * 绝对链接会被解析出根外路径而提前返回，链接删不掉、父目录也删不掉，
     * 白化（whiteout）语义就此失效——收敛到唯一实现。
     *
     * guard 传 destDir 本体而不是 new File(destDir.getCanonicalPath())：
     * Windows 的 TEMP 常是 8.3 短名（YINGQU~1），canonical 展开成长名后
     * 与 File.getAbsoluteFile 产生的短名路径前缀对不上，guard 会误判
     * 「根外」而放弃删除——opq 白化、目录顶替全部静默失效。
     */
    private static void deleteTree(File target, File destDir) {
        Containers.deleteRecursive(target, destDir);
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte b : header) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static String cstr(byte[] buf, int offset, int length) {
        int end = offset;
        while (end < offset + length && buf[end] != 0) {
            end++;
        }
        return new String(buf, offset, end - offset);
    }

    private static long parseOctal(byte[] buf, int offset, int length) {
        // base-256（大文件）标记
        if ((buf[offset] & 0x80) != 0) {
            long value = buf[offset] & 0x7F;
            for (int i = 1; i < length; i++) {
                value = (value << 8) | (buf[offset + i] & 0xFF);
            }
            return value;
        }
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            int c = buf[i];
            if (c == 0 || c == ' ') {
                if (value != 0) {
                    break;
                }
                continue;
            }
            if (c < '0' || c > '7') {
                break;
            }
            value = value * 8 + (c - '0');
        }
        return value;
    }

    private static String cleanName(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("./")) {
            n = n.substring(2);
        }
        while (n.startsWith("/")) {
            n = n.substring(1);
        }
        if (n.isEmpty() || n.equals(".")) {
            return null;
        }
        String[] parts = n.split("/");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                return null;
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /**
     * pax 记录格式是「十进制长度 + 空格 + key=value + 换行」，长度包含自身，
     * 所以不能简单地按 "path=" 前缀找。返回 [path, linkpath]，缺的字段为 null。
     * linkpath 是长链接目标（硬链接/符号链接 ustar 只有 100 字节放不下时用它）。
     */
    private static String[] parsePax(String content) {
        String[] out = new String[2];
        int i = 0;
        while (i < content.length()) {
            int sp = content.indexOf(' ', i);
            if (sp < 0) {
                break;
            }
            int len;
            try {
                len = Integer.parseInt(content.substring(i, sp).trim());
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0) {
                break;
            }
            int end = Math.min(i + len, content.length());
            String record = content.substring(sp + 1, end).replace("\n", "");
            if (record.startsWith("path=")) {
                out[0] = record.substring(5);
            } else if (record.startsWith("linkpath=")) {
                out[1] = record.substring(9);
            }
            i = end;
        }
        return out;
    }

    private static String trimNul(String value) {
        int i = value.indexOf('\0');
        return (i >= 0 ? value.substring(0, i) : value).trim();
    }
}
