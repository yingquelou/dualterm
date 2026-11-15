package dev.dualterm.container;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
                    String p = paxPath(new String(readData(in, size), "UTF-8"));
                    if (p != null) {
                        pendingName = p;
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
                        String p = paxPath(new String(data, "UTF-8"));
                        if (p != null) {
                            pendingName = p;
                        }
                    }
                    continue;
                }
                // GNU 长文件名
                if (type == 'L') {
                    byte[] data = readData(in, size);
                    pendingName = trimNul(new String(data, "UTF-8"));
                    continue;
                }
                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }

                String clean = cleanName(name);
                if (clean == null) {
                    skipEntry(in, size);
                    continue;
                }

                if (whiteouts && isWhiteout(clean)) {
                    applyWhiteout(destDir, destRoot, clean);
                    skipEntry(in, size);
                    continue;
                }

                File out = new File(destDir, clean);
                if (!out.getCanonicalPath().startsWith(destRoot)) {
                    // 防目录穿越
                    skipEntry(in, size);
                    continue;
                }

                if (type == '5') {
                    ensureDir(out, destRoot);
                    skipEntry(in, size);
                } else if (type == '0' || type == 0 || type == '7') {
                    writeFile(in, out, size, copyBuf, (mode & 0111) != 0, destRoot);
                } else if (type == '2') {
                    makeSymlink(destDir, destRoot, out, linkName);
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

    private static void applyWhiteout(File destDir, String destRoot, String clean) {
        int slash = clean.lastIndexOf('/');
        String parent = slash < 0 ? "" : clean.substring(0, slash);
        String base = slash < 0 ? clean : clean.substring(slash + 1);
        File dir = parent.isEmpty() ? destDir : new File(destDir, parent);

        if (".wh..wh..opq".equals(base)) {
            File[] kids = dir.listFiles();
            if (kids != null) {
                for (File kid : kids) {
                    deleteTree(kid, destRoot);
                }
            }
            return;
        }
        String target = base.substring(4);
        if (target.isEmpty()) {
            return;
        }
        deleteTree(new File(dir, target), destRoot);
    }

    /* -------------------- 条目处理 -------------------- */

    private static void writeFile(InputStream in, File out, long size, byte[] buf,
                                  boolean executable, String destRoot) throws IOException {
        File parent = out.getParentFile();
        if (parent != null) {
            ensureDir(parent, destRoot);
        }
        if (out.isDirectory()) {
            // 上层是目录、这层是文件：目录让位
            deleteTree(out, destRoot);
        }
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
    private static void ensureDir(File dir, String destRoot) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (dir.exists()) {
            deleteTree(dir, destRoot);
        }
        File parent = dir.getParentFile();
        if (parent != null && !parent.exists()) {
            ensureDir(parent, destRoot);
        }
        if (!dir.mkdir() && !dir.isDirectory()) {
            throw new IOException("无法创建目录：" + dir);
        }
    }

    private static void makeSymlink(File destDir, String destRoot, File link, String target)
            throws IOException {
        File parent = link.getParentFile();
        if (parent != null) {
            ensureDir(parent, destRoot);
        }
        if (link.exists() || Files.isSymbolicLink(link.toPath())) {
            // 可能是上层留下的文件/目录，直接清掉再建
            deleteTree(link, destRoot);
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
        File resolved = resolveLinkTarget(destDir, destRoot, link, target);
        if (resolved != null && resolved.isFile()) {
            copyFile(resolved, link);
            //noinspection ResultOfMethodCallIgnored
            link.setExecutable(resolved.canExecute(), true);
        } else if (resolved != null && resolved.isDirectory()) {
            ensureDir(link, destRoot);
        } else {
            deleteTree(link, destRoot);
            //noinspection ResultOfMethodCallIgnored
            link.createNewFile();
        }
    }

    /** 解析符号链接目标（支持绝对路径与相对路径，最多跟随 5 跳）。 */
    private static File resolveLinkTarget(File destDir, String destRoot, File link, String target) {
        File base = new File(destRoot);
        File current;
        if (target.startsWith("/")) {
            current = new File(destRoot, target.substring(1));
        } else {
            File parent = link.getParentFile();
            current = new File(parent == null ? base : parent, target);
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

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = Files.newInputStream(src.toPath());
             FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void makeHardLink(File destDir, File link, String target) throws IOException {
        File src = new File(destDir, cleanName(target) == null ? target : cleanName(target));
        File parent = link.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try {
            Files.createLink(link.toPath(), src.toPath());
        } catch (Exception e) {
            if (src.isFile()) {
                try (InputStream in = Files.newInputStream(src.toPath());
                     FileOutputStream out = new FileOutputStream(link)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
            }
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
     */
    private static void deleteTree(File target, String destRoot) {
        Containers.deleteRecursive(target, new File(destRoot));
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
     * 所以不能简单地按 "path=" 前缀找。
     */
    private static String paxPath(String content) {
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
                return record.substring(5);
            }
            if (record.startsWith("linkpath=")) {
                // 目前只需要 path，linkpath 留给以后
            }
            i = end;
        }
        return null;
    }

    private static String trimNul(String value) {
        int i = value.indexOf('\0');
        return (i >= 0 ? value.substring(0, i) : value).trim();
    }
}
