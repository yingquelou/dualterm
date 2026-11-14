package dev.dualterm;

import android.content.Context;

/**
 * 容器模式的后端抽象。
 *
 * 两个实现：
 *   ProotRuntime  —— 默认。无 root，用 ptrace 在用户态重定向文件系统，得到一个独立 rootfs。
 *   DockerRuntime —— 实验性。需要设备本身有 root + dockerd，见类内注释。
 *
 * 想在真机上跑「原生 Docker」，瓶颈不在 App 代码，而在内核：
 *   需要 CONFIG_NAMESPACES / CONFIG_CGROUPS / overlayfs，且多数厂商内核根本没开。
 *   所以这里把 PRoot 作为默认隔离方案，Docker 作为可选后端，探测失败会明确报错。
 */
public abstract class ContainerRuntime {

    public interface Progress {
        void onProgress(String message);
    }

    public abstract String displayName();

    /** 幂等：首次调用做重活（解包 / 拷贝二进制 / 探测 root），之后很快返回。 */
    public abstract void prepare(Progress progress) throws Exception;

    /** 生成进入指定容器的启动参数（容器名来自 Containers 注册表）。 */
    public abstract LaunchSpec launchSpec(String containerName) throws Exception;

    public static ContainerRuntime create(Context ctx, String preferred) {
        if ("docker".equals(preferred)) {
            return new DockerRuntime(ctx);
        }
        return new ProotRuntime(ctx);
    }
}
