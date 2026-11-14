package dev.dualterm.container;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import dev.dualterm.util.Fs;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 从 tar 归档导入镜像，对齐 docker 的两条路径：
 *
 *   docker import  ← docker export 出来的「扁平 rootfs」tar（也可能被 gzip 过）
 *   docker load    ← docker save 出来的「带 manifest 的层叠包」
 *
 * 两种格式统一走 {@link #importArchive}，靠归档里有没有 manifest.json / index.json 判断：
 *   有 manifest.json            → docker save（旧布局：&lt;layer&gt;/layer.tar + manifest.json）
 *   有 index.json + oci-layout  → OCI 布局（containerd 镜像存储 / podman / skopeo 导出的 docker save）
 *   都没有                      → 当成扁平 rootfs，整包解到镜像目录即可
 *
 * 层叠加时按 manifest 里 Layers 的顺序依次解包，层内的 .wh. 标记会删掉下层的同名条目，
 * 所以最终得到的目录和真 docker 跑出来的 rootfs 一致。
 */
public final class ImageImporter {

    public static final class Result {
        public final String ref;
        public final int layers;
        public final String format;
        public final String note;

        Result(String ref, int layers, String format, String note) {
            this.ref = ref;
            this.layers = layers;
            this.format = format;
            this.note = note;
        }
    }

    private ImageImporter() {
    }

    /* -------------------- 入口 -------------------- */

    public static Result importArchive(Context ctx, File archive, String refHint,
                                       ContainerRuntime.Progress progress) throws IOException {
        if (archive == null || !archive.isFile()) {
            throw new IOException("文件不存在：" + archive);
        }
        if (archive.length() == 0) {
            throw new IOException("文件是空的：" + archive.getName());
        }
        report(progress, "读取归档目录…");
        List<String> names;
        try (InputStream in = new FileInputStream(archive)) {
            names = TarGzExtractor.listNames(in, 8000);
        }
        if (names.isEmpty()) {
            throw new IOException("不是有效的 tar 归档：" + archive.getName());
        }

        boolean dockerSave = names.contains("manifest.json");
        boolean oci = !dockerSave && names.contains("index.json") && names.contains("oci-layout");

        if (dockerSave || oci) {
            return importLayered(ctx, archive, refHint, dockerSave, progress);
        }
        return importFlat(ctx, archive, refHint, progress);
    }

    /* -------------------- 扁平 rootfs（docker export） -------------------- */

    private static Result importFlat(Context ctx, File archive, String refHint,
                                     ContainerRuntime.Progress progress) throws IOException {
        String ref = pickRef(ctx, refHint, null);
        File rootfs = prepareTarget(ctx, ref);

        report(progress, "解包 rootfs（" + Containers.humanSize(archive.length()) + "）…");
        final ContainerRuntime.Progress sink = progress;
        int entries;
        try (InputStream in = new FileInputStream(archive)) {
            entries = TarGzExtractor.extract(in, rootfs, new TarGzExtractor.Progress() {
                @Override
                public void onProgress(int count, String path) {
                    if (count % 200 == 0) {
                        report(sink, "解包 rootfs：" + count + " 项（" + path + "）");
                    }
                }
            });
        } catch (IOException e) {
            Containers.deleteRecursive(rootfs.getParentFile(), Containers.imagesDir(ctx));
            throw e;
        }
        if (entries == 0) {
            Containers.deleteRecursive(rootfs.getParentFile(), Containers.imagesDir(ctx));
            throw new IOException("归档里没有可解出的文件（空的 tar？）");
        }

        String note = finish(ctx, ref, rootfs, progress);
        return new Result(ref, 1, "docker export（扁平 rootfs）", note);
    }

    /* -------------------- 层叠镜像（docker save / OCI） -------------------- */

    private static Result importLayered(Context ctx, File archive, String refHint,
                                        boolean dockerSave, ContainerRuntime.Progress progress)
            throws IOException {
        File temp = new File(ctx.getCacheDir(), "import-" + System.nanoTime());
        Fs.ensureDir(temp);
        try {
            report(progress, "解包镜像归档（" + Containers.humanSize(archive.length()) + "）…");
            try (InputStream in = new FileInputStream(archive)) {
                TarGzExtractor.extract(in, temp, null);
            }

            List<File> layers = new ArrayList<>();
            String ref;
            if (dockerSave) {
                File manifest = new File(temp, "manifest.json");
                JSONObject first = firstManifestObject(manifest);
                JSONArray arr = first.optJSONArray("Layers");
                if (arr == null) {
                    throw new IOException("manifest.json 里没有 Layers 字段，归档可能不完整");
                }
                for (int i = 0; i < arr.length(); i++) {
                    layers.add(resolveInside(temp, arr.optString(i)));
                }
                ref = pickRef(ctx, refHint, repoTags(first));
            } else {
                layers.addAll(ociLayers(temp));
                ref = pickRef(ctx, refHint, ociRefName(temp));
            }
            if (layers.isEmpty()) {
                throw new IOException("归档里没有任何镜像层");
            }

            File rootfs = prepareTarget(ctx, ref);
            try {
                for (int i = 0; i < layers.size(); i++) {
                    File layer = layers.get(i);
                    if (!layer.isFile()) {
                        throw new IOException("缺少层文件：" + layer.getName());
                    }
                    final String label = "层 " + (i + 1) + "/" + layers.size();
                    report(progress, "叠加 " + label + "（" + Containers.humanSize(layer.length()) + "）…");
                    final ContainerRuntime.Progress sink = progress;
                    try (InputStream in = new FileInputStream(layer)) {
                        TarGzExtractor.extractLayer(in, rootfs, new TarGzExtractor.Progress() {
                            @Override
                            public void onProgress(int count, String path) {
                                if (count % 300 == 0) {
                                    report(sink, label + "：" + count + " 项");
                                }
                            }
                        });
                    }
                }
            } catch (IOException e) {
                Containers.deleteRecursive(rootfs.getParentFile(), Containers.imagesDir(ctx));
                throw e;
            }

            String note = finish(ctx, ref, rootfs, progress);
            return new Result(ref, layers.size(),
                    dockerSave ? "docker save（manifest.json 层叠）" : "OCI 布局（containerd/podman）", note);
        } finally {
            Containers.deleteRecursive(temp, ctx.getCacheDir());
        }
    }

    /* -------------------- manifest 解析 -------------------- */

    private static JSONObject firstManifestObject(File manifest) throws IOException {
        if (!manifest.isFile()) {
            throw new IOException("缺少 manifest.json");
        }
        String text = new String(Files.readAllBytes(manifest.toPath()), "UTF-8").trim();
        try {
            if (text.startsWith("[")) {
                JSONArray arr = new JSONArray(text);
                if (arr.length() == 0) {
                    throw new IOException("manifest.json 是空数组");
                }
                return arr.getJSONObject(0);
            }
            return new JSONObject(text);
        } catch (org.json.JSONException e) {
            throw new IOException("manifest.json 解析失败：" + e.getMessage());
        }
    }

    private static String repoTags(JSONObject manifest) {
        JSONArray tags = manifest.optJSONArray("RepoTags");
        if (tags != null) {
            for (int i = 0; i < tags.length(); i++) {
                String t = tags.optString(i, "").trim();
                if (!t.isEmpty() && !"<none>:<none>".equals(t)) {
                    return t;
                }
            }
        }
        return null;
    }

    /** OCI 布局：index.json → image manifest → layers[].digest → blobs/sha256/xxx */
    private static List<File> ociLayers(File temp) throws IOException {
        File index = new File(temp, "index.json");
        if (!index.isFile()) {
            throw new IOException("OCI 布局缺少 index.json");
        }
        List<File> out = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(index.toPath()), "UTF-8"));
            JSONArray manifests = root.optJSONArray("manifests");
            if (manifests == null || manifests.length() == 0) {
                throw new IOException("index.json 里没有 manifests");
            }
            JSONObject desc = manifests.getJSONObject(0);
            File manifestBlob = blobFile(temp, desc.optString("digest"));
            JSONObject manifest = new JSONObject(
                    new String(Files.readAllBytes(manifestBlob.toPath()), "UTF-8"));
            JSONArray layers = manifest.optJSONArray("layers");
            if (layers == null) {
                throw new IOException("OCI manifest 里没有 layers");
            }
            for (int i = 0; i < layers.length(); i++) {
                out.add(blobFile(temp, layers.getJSONObject(i).optString("digest")));
            }
        } catch (org.json.JSONException e) {
            throw new IOException("OCI 布局解析失败：" + e.getMessage());
        }
        return out;
    }

    private static String ociRefName(File temp) {
        try {
            JSONObject root = new JSONObject(
                    new String(Files.readAllBytes(new File(temp, "index.json").toPath()), "UTF-8"));
            JSONArray manifests = root.optJSONArray("manifests");
            if (manifests == null || manifests.length() == 0) {
                return null;
            }
            JSONObject desc = manifests.getJSONObject(0);
            JSONObject ann = desc.optJSONObject("annotations");
            if (ann != null) {
                String name = ann.optString("org.opencontainers.image.ref.name", "");
                if (!name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception ignored) {
            // 没有名字就让用户自己起
        }
        return null;
    }

    private static File blobFile(File temp, String digest) throws IOException {
        if (digest == null || digest.isEmpty()) {
            throw new IOException("manifest 里有条目缺少 digest");
        }
        String hex = digest.startsWith("sha256:") ? digest.substring(7) : digest;
        return resolveInside(temp, "blobs/sha256/" + hex);
    }

    /** 把归档里的相对路径解析成 temp 下的文件，越界直接拒绝。 */
    private static File resolveInside(File temp, String relative) throws IOException {
        File f = new File(temp, relative);
        String root = temp.getCanonicalPath();
        String path = f.getCanonicalPath();
        if (!path.equals(root) && !path.startsWith(root + File.separator)) {
            throw new IOException("归档里有非法路径：" + relative);
        }
        return f;
    }

    /* -------------------- 收尾 -------------------- */

    private static File prepareTarget(Context ctx, String ref) throws IOException {
        File rootfs = Containers.imageRootfs(ctx, ref);
        File dir = rootfs.getParentFile();
        if (dir.exists()) {
            File[] kids = dir.listFiles();
            if (kids != null && kids.length > 0) {
                throw new IOException("镜像已存在：" + ref + "（先 docker rmi " + ref + " 再导入）");
            }
        }
        Fs.ensureDir(rootfs);
        return rootfs;
    }

    /** 补 DNS / 绑定目录 / docker shim，然后登记镜像；返回给用户看的备注（架构提示）。 */
    private static String finish(Context ctx, String ref, File rootfs,
                                 ContainerRuntime.Progress progress) throws IOException {
        if (!new File(rootfs, "bin").isDirectory() && !new File(rootfs, "usr").isDirectory()
                && !new File(rootfs, "etc").isDirectory()) {
            Containers.deleteRecursive(rootfs.getParentFile(), Containers.imagesDir(ctx));
            throw new IOException("归档里没有根文件系统结构（找不到 bin/ usr/ etc/）");
        }

        report(progress, "整理镜像…");
        Containers.finalizeImage(ctx, ref, rootfs, progress);

        String arch = detectArch(rootfs);
        String device = ContainerBootstrap.abi();
        if (arch == null) {
            return "未能识别镜像架构（可能没有 ELF 可执行文件），请确认与设备一致：" + device;
        }
        String want = "arm64-v8a".equals(device) ? "aarch64"
                : ("armeabi-v7a".equals(device) ? "arm" : "x86-64");
        boolean ok = arch.equals(want) || ("aarch64".equals(want) && "arm".equals(arch));
        if (ok) {
            return null;
        }
        return "注意：镜像架构为 " + arch + "，本机为 " + device + "，容器大概率起不来";
    }

    /** 读 /bin/sh 等常见入口的 ELF 头，判断镜像架构；读不出来返回 null。 */
    public static String detectArch(File rootfs) {
        String[] candidates = {"bin/busybox", "bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/env"};
        for (String c : candidates) {
            try {
                File f = resolveInRootfs(rootfs, c);
                if (!f.isFile() || f.length() < 20) {
                    continue;
                }
                byte[] head = new byte[20];
                try (InputStream in = new FileInputStream(f)) {
                    int read = 0;
                    while (read < 20) {
                        int n = in.read(head, read, 20 - read);
                        if (n < 0) {
                            break;
                        }
                        read += n;
                    }
                    if (read < 20) {
                        continue;
                    }
                }
                if ((head[0] & 0xFF) != 0x7F || head[1] != 'E' || head[2] != 'L' || head[3] != 'F') {
                    continue;
                }
                int machine = (head[18] & 0xFF) | ((head[19] & 0xFF) << 8);
                switch (machine) {
                    case 0xB7:
                        return "aarch64";
                    case 0x28:
                        return "arm";
                    case 0x3E:
                        return "x86-64";
                    case 0x03:
                        return "x86";
                    default:
                        return String.format(Locale.US, "elf-machine-0x%x", machine);
                }
            } catch (IOException ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    /** 在 rootfs 内部跟随符号链接（绝对链接按 rootfs 根解析，不能用 getCanonicalFile）。 */
    private static File resolveInRootfs(File rootfs, String relative) {
        File f = new File(rootfs, relative);
        for (int hop = 0; hop < 6; hop++) {
            try {
                if (!Files.isSymbolicLink(f.toPath())) {
                    return f;
                }
                String target = Files.readSymbolicLink(f.toPath()).toString();
                f = target.startsWith("/")
                        ? new File(rootfs, target.substring(1))
                        : new File(f.getParentFile(), target);
            } catch (Exception e) {
                return f;
            }
        }
        return f;
    }

    private static String pickRef(Context ctx, String refHint, String fromArchive) {
        String ref = refHint == null ? "" : refHint.trim();
        if (ref.isEmpty() && fromArchive != null) {
            ref = fromArchive.trim();
        }
        if (ref.isEmpty()) {
            ref = "imported:" + (System.currentTimeMillis() / 1000);
        }
        // 去掉 docker 存档里偶尔带的 docker.io/library/ 前缀，和本地镜像命名保持一致
        if (ref.startsWith("docker.io/library/")) {
            ref = ref.substring("docker.io/library/".length());
        }
        return Containers.normalizeImage(ref);
    }

    private static void report(ContainerRuntime.Progress progress, String message) {
        if (progress != null) {
            progress.onProgress(message);
        }
    }
}
