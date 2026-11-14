package dev.dualterm.container;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import dev.dualterm.term.LaunchSpec;
import dev.dualterm.term.Shells;

/**
 * 实验性 Docker 后端。
 *
 * 前提（缺一不可，探测失败会直接报错而不是假装能用）：
 *   1. 设备已 root，且 su 已授权本应用
 *   2. 设备上装了 dockerd + docker CLI（例如在 Termux 里 root 安装，或厂商 ROM 自带）
 *   3. 内核开了 namespace / cgroup；overlayfs 或 vfs 存储驱动可用
 *
 * 很多手机内核没有 CONFIG_NAMESPACES，dockerd 起不来 —— 这就是为什么默认走 PRoot。
 */
public final class DockerRuntime extends ContainerRuntime {

    private final Context ctx;

    public DockerRuntime(Context ctx) {
        this.ctx = ctx;
    }

    @Override
    public String displayName() {
        return "Docker（需 root + dockerd）";
    }

    @Override
    public void prepare(Progress progress) throws Exception {
        progress.onProgress("检查 root 权限…");
        if (!Shells.isRootAvailable()) {
            throw new IllegalStateException(
                    "未检测到 su，无法使用 Docker 后端。\n请在菜单中选择「容器运行时 → PRoot」继续使用容器模式。");
        }
        String id = Shells.execCapture(new String[]{"su", "-c", "id"});
        if (id == null || !id.contains("uid=0")) {
            throw new IllegalStateException("su 未授予 root 权限（返回：" + id + "）");
        }

        progress.onProgress("检查 dockerd / docker CLI…");
        String probe = Shells.execCapture(new String[]{"su", "-c", "command -v dockerd; command -v docker"});
        if (probe == null || probe.trim().isEmpty()) {
            throw new IllegalStateException(
                    "设备上没有 dockerd / docker CLI。\n"
                            + "Docker 需要内核支持 namespace 与 cgroup，多数手机内核不支持；\n"
                            + "请在菜单中切回 PRoot 后端。");
        }

        progress.onProgress("启动 dockerd…");
        Shells.execCapture(new String[]{"su", "-c", bootScript() + "; docker info | head -n 5"});
        progress.onProgress("Docker 就绪");
    }

    @Override
    public LaunchSpec launchSpec(String containerName) {
        String name = (containerName == null || containerName.isEmpty())
                ? Containers.DEFAULT_CONTAINER : containerName;
        Containers.Item item = Containers.find(ctx, name);
        String image = item == null ? "alpine:latest" : item.image;

        StringBuilder script = new StringBuilder();
        script.append(bootScript())
                .append("; exec docker run -it --name ").append(name)
                .append(" -v /sdcard:/mnt/sdcard");
        // 用户自定义绑定目录，与 PRoot 后端共用同一份配置
        for (String[] vol : Volumes.list(ctx)) {
            if (!new java.io.File(vol[0]).exists()) {
                continue;
            }
            script.append(" -v ").append(Volumes.dockerVolumeArg(vol));
        }
        script.append(" ").append(image).append(" /bin/sh");
        return new LaunchSpec("容器 · " + name + "（Docker）", new String[]{"su", "-c", script.toString()}, env(), "/");
    }

    /** 幂等启动 dockerd，并把 DOCKER_HOST 指到 TCP/Unix socket。 */
    private String bootScript() {
        String sock = "unix:///data/local/tmp/docker.sock";
        return "export DOCKER_HOST=" + sock + "; "
                + "if ! docker info >/dev/null 2>&1; then "
                + "mkdir -p /data/local/tmp/docker; "
                + "(dockerd --data-root /data/local/tmp/docker --host " + sock
                + " >/data/local/tmp/dockerd.log 2>&1 &); "
                + "i=0; while [ $i -lt 15 ]; do docker info >/dev/null 2>&1 && break; sleep 1; i=$((i+1)); done; "
                + "fi";
    }

    private String[] env() {
        List<String> env = new ArrayList<>();
        env.add("HOME=/data/local/tmp");
        env.add("TMPDIR=/data/local/tmp");
        env.add("PATH=/sbin:/system/sbin:/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin");
        env.add("TERM=xterm-256color");
        env.add("DOCKER_HOST=unix:///data/local/tmp/docker.sock");
        return env.toArray(new String[0]);
    }
}
