# 容器与镜像

## 运行模型

容器模式使用 PRoot：一个纯用户态工具，通过 `ptrace` 拦截系统调用并重定向路径，让容器进程看到一个独立的 `/`。容器内是 `uid=0`（假 root），可以 `apk add`、写 `/etc`，但**与 Android 共用内核**：没有独立 `/proc`，没有独立网络栈，不是内核级隔离。

Docker 后端作为备选实现存在（菜单 `容器运行时`），需要设备自带 root 与 `dockerd`，多数手机内核不支持，按实验性功能对待，原因见 [Docker 后端为什么不是默认](#docker-后端为什么不是默认)。

## 镜像与容器

镜像只是**模板**；创建容器时从镜像物化一份可写 rootfs，之后两者互不影响，与 docker 的心智模型一致。

```
files/container/
├── images/alpine-3.20/rootfs/     ← 镜像（只读模板）
├── containers/default/rootfs/     ← 容器（镜像的可写副本）
└── containers/default/home/       ← 挂到容器内 /root
```

从 v1.x 升级时，旧的 `container/rootfs` 会被改名成 `images/alpine-3.20/rootfs`（不重新解包），旧 `container/home` 的内容搬进默认容器。

全新安装时**在册容器与镜像都是空的**（rootfs 不随包分发）：第一次切到容器模式会弹引导——拉取 Alpine 镜像、或导入 tar；也可先在本地 shell 里操作。装好镜像后再由镜像创建容器，与 docker 的「先有镜像再有容器」一致。

镜像大小显示的是导入/拉取完成时缓存的值——统计目录大小要递归遍历数万个文件，不能放在 UI 线程。刚导入的立即可见；旧版本遗留的镜像首次打开显示「…」，后台补齐缓存后正常显示。

## 管理入口

两个入口能力完全一致：

- 菜单 → **容器管理**：图形界面，按步骤操作；
- 菜单 → **容器管理控制台**：命令行界面，docker 语法子集。

```
images                     # 列出镜像
pull alpine:3.21           # Alpine 版本：从官方 CDN 拉 minirootfs（约 3MB）
pull debian:12             # 其余：从 Docker Hub 匿名拉取（gzip 层，几十 MB 起）
run --name web alpine:3.21 # 用镜像创建容器（不接管终端）
ps                         # 运行中的容器
ps -a                      # 全部容器
rm web                     # 删除容器（还有活会话时拒绝；活动容器删后自动落到剩余第一个）
rmi alpine:3.21            # 有容器在用时拒绝删除
load -i x.tar              # docker save 的层叠包
import x.tar 名:标签        # docker export 的扁平 rootfs
mirror                     # 查看拉取镜像源（hub / alpine 两路各自独立）
mirror hub docker.1ms.run  # 换 Hub 镜像源；mirror hub clear 恢复官方直连
mirror alpine mirrors.tuna.tsinghua.edu.cn/alpine
                           # 换 Alpine CDN 源；mirror alpine clear 恢复官方
info / version / help
```

`pull` 的分发收在 `Containers.pull` 内部（与菜单「拉镜像」共用同一条路）：
alpine 版本标签 / latest 走 dl-cdn 快路径，其余引用走 Docker Hub 匿名拉取
（Registry HTTP API v2 子集，manifest list 按设备 ABI 选平台，层边下边验
sha256）。zstd 压缩的层不支持（需要 native 解码器），明确报错并指路导入；
自定义 registry（ghcr/quay）、digest 引用、私有仓库第一版不做。

**镜像源（换源）**：Hub 与 Alpine 两路各自可配（菜单「镜像源设置…」或
`docker mirror`）。Hub 镜像站先匿名拉、401 时按应答的 `WWW-Authenticate`
realm 取 token（镜像站自报端点，不依赖 auth.docker.io），全匿名站
（dockerproxy 类）与带 token 站（1ms / daocloud 类）都覆盖。实测
（2026-11，官方直连被 DNS 污染的网络）：debian:12 经 dockerproxy.net
约 50s 拉完，busybox:1.36 经 docker.1ms.run 约 45s 拉完。
设置细节见 [pull-registry.md](pull-registry.md)。

`run -v /sdcard/Download:/mnt/dl ...` 支持绑定宿主目录，写入全局 `Volumes`，与菜单「绑定目录」共用同一份配置。

**不支持**：`build`、`commit`、`push`、`compose`、网络管理、端口重映射——这些必须有真 `dockerd` 或 NAT。

## 容器内没有 docker 命令

第一版在每个容器的 `/usr/local/bin/docker` 放脚本，靠绑定目录加文件信箱把命令转发给 App。能运行，但代价不划算：

- 与镜像自带的真 `docker` 同名冲突，`apk add docker` 会顶掉入口，之后行为不可控；
- 需要维护一条跨 proot 的 IPC（请求/响应文件、轮询、超时）；
- 要支持「只让某一个终端能管理」，还得引入「管理终端」概念并与 UI 同步会话状态。

现在容器内不存在任何 docker 命令。管理动作在 App 进程内直接调用 `Containers` / `DockerCli`，没有 IPC、没有同名冲突、没有会话状态的双向同步。控制台只做增删查（`run` 只创建容器，不切换终端）；「启动 / 停止 / 进入容器」属于终端会话，由 UI 负责，数据流保持单向。

## 端口：同端口直通，不做映射

容器内的服务对整个 Android 系统可见。PRoot 只做 ptrace 路径重定向，容器进程就是本应用的普通进程，与系统共用同一个 network namespace、同一张 `lo`、同一个 `wlan0`——没有 veth、没有 NAT、没有 iptables。容器内 `listen 0.0.0.0:8080` 后，手机浏览器访问 `http://127.0.0.1:8080` 即是该服务；绑 `0.0.0.0` 时同一局域网的设备也能连（路由器开启 AP 隔离则不通）。

`run -p` 只接受**同端口直通**（`8080` 或 `8080:8080`）。声明会保存下来，并在容器创建后输出访问指引；`-p 8080:80` 这类重映射在创建时即拒绝，而不是运行后静默连不上——两条后端都没有 NAT，无法兑现的映射不如提前说明。低于 1024 的端口需要 root 模式：容器内的假 root 实为应用 uid，绑特权端口会 `EACCES`。

端口声明只用于展示，不参与启动参数拼装。

## 会话保活

存在活着的终端会话时，应用会拉起前台服务并持有部分唤醒锁（`SessionService`，`specialUse` 类型）。否则 Android 12+ 的 cached app freezer 会在切后台后把整个进程 `SIGSTOP`：容器里的服务还在，但不再应答。

会话全部结束后服务自动停止。不做常驻守护，不做开机自启。通知权限未授权时服务照常工作，只是通知不可见。

## Docker 后端为什么不是默认

Docker Engine 需要 namespace、cgroup 与 overlayfs，运行 `dockerd` 还需要 root；而多数手机厂商内核没有开启 `CONFIG_NAMESPACES`，`dockerd` 起不来。因此工程做了后端抽象，两个实现：

1. `ProotRuntime`（默认）：纯用户态，验证过的路线。
2. `DockerRuntime`（实验）：探测 `su` → 探测 `dockerd` → 启动 `dockerd` → `docker run -it --net=host`。探测失败会明确报错并提示切回 PRoot，不会假装可用。

固定 host 网络是刻意的选择：手机内核常常没有 iptables nat 表，bridge 模式下容器能起来但两头都不通；host 模式复用宿主网络栈，端口语义与 PRoot 后端一致，代价是端口冲突直接 `EADDRINUSE`。

要在手机上获得真正的 Docker 支持，前提是设备运行带 namespace 的内核，属于 ROM 层面的改动。

## 相关实现细节

proot 启动参数、Android 专属前提（`exec` 权限、`extractNativeLibs`、`PROOT_LOADER`）见 [internals.md](internals.md#容器启动)。
