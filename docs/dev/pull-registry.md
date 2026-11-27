# docker pull 泛化：Docker Hub 官方镜像子集（设计定稿）

状态：已实现并实机验证（2026-11）。RegistryTest 90 用例 + 其余各套全绿（现共七套）；
实机（官方 Hub 被 DNS 污染的网络）：alpine 换清华源 12s、debian:12 经
dockerproxy.net 约 50s、busybox:1.36/1.37 经 1ms / daocloud（realm token）
45s / 23s 拉完；digest 拒绝、镜像物化容器、rm 守卫、clear 恢复官方均过。
同批新增镜像源（换源）能力，见第八节。2026-11 增补公共第三方 registry
（ghcr.io / quay.io 等）匿名直连，见第九节。

## 一、目标与范围

**做**：

- `docker pull <名[:标签]>` 支持 Docker Hub 上的仓库，第一优先是官方镜像
  （`library/debian`、`ubuntu`、`nginx`……），同时兼容 `owner/repo` 写法
  （token 流程完全相同，增量≈0，镜像质量自负）。
- 匿名拉取，不引入登录/凭据概念。
- 层压缩只收 gzip / 未压缩 tar；落地后沿用现有收尾（DNS、绑定目录、登记）。

**不做（第一期，明确报错而不是静默失败）**：

- digest 引用（`debian@sha256:…`）——报错说明，等有真实诉求再做；
- 私有 registry 与登录鉴权——GHCR / Quay 的公共包匿名可拉，私有包需 PAT，
  第一期不做；带端口的自建 registry 明确报错（端口与 tag 分隔有歧义，
  且本机没有自建场景）；
- 断点续传、schema v1 老镜像；
- **zstd 层**：需要 native 解码器（zstd-jni 数 MB），与「APK ≈ 512KB、
  零第三方依赖」两条硬约束直接冲突。主流官方镜像至今全是 gzip；
  遇到 zstd 层给明确报错，指路 `docker save` 后 `docker load` 导入。

## 二、现有资产复用清单（只列直接可用的）

| 资产 | 用途 |
|---|---|
| `TarGzExtractor.extractLayer` | 层叠加 + whiteout（`.wh.` / `.wh..wh..opq`），等于把 `ImageImporter.importLayered` 的层来源从本地 tar 换成远端 blob |
| `TarGzExtractor.extract` | 未压缩 tar 层（自动识别 gzip） |
| `Containers.finalizeImage` | 落地统一收尾（DNS / 绑定目录 / 登记），assets 解包 / pull / 导入三条路已共用 |
| `Containers.validImageRef` | ref 白名单闸门（ref 会变目录名、也会进 DockerRuntime 的 `su -c` 拼串） |
| `Containers.normalizeImage` / `imageRootfs` / `registerImage` | ref 归一化与登记 |
| `ImageImporter.detectArch` | 落地后 ELF 架构兜底校验（alpine 快路径已在用） |
| `Containers.httpText` / `download` | HTTP 基础设施（需小幅泛化，见下） |

## 三、流程与接口

### 3.1 入口与分发

`Containers.pull(ctx, ref, progress)` 保持唯一入口，内部分发：

```
ref 归一化
├─ alpine 版本标签（3.x / latest / alpine / alpine:3.x）
│    → 现有 dl-cdn 快路径，原样保留（免鉴权、单文件、已在用）
└─ 其它（且不含 @digest）
     → RegistryPuller.pull(ctx, ref, progress)
```

分发逻辑放 `Containers.pull` 内部而不是 `DockerCli` / `MainActivity`：
UI（overlay 进度）和控制台两个入口共用，避免第二次分发漂移。

### 3.2 新类 `dev.dualterm.container.RegistryPuller`

```java
public final class RegistryPuller {
    /** 拉取 ref 指向的镜像，返回登记后的本地 ref（如 debian:12）。 */
    public static String pull(Context ctx, String ref,
                              ContainerRuntime.Progress progress) throws IOException;
}
```

### 3.3 ref 解析（static 纯函数，桌面可测）

| 输入 | repo（发请求用） | tag | 登记的本地 ref |
|---|---|---|---|
| `debian` | `library/debian` | `latest` | `debian:latest` |
| `debian:12` | `library/debian` | `12` | `debian:12` |
| `library/debian:12` | `library/debian` | `12` | `debian:12` |
| `user/repo:tag` | `user/repo` | `tag` | `user/repo:tag` |

- 缺 `library/` 前缀且不含 `/` 时自动补（Hub 对官方镜像的约定）。
- 本地 ref 一律剥掉 `library/` 前缀，与 `normalizeImage` 现状一致。
- 解析结果过 `validImageRef`，非法直接拒绝（防注入闸门前置）。
- 含 `@sha256:` 的输入在解析前就拒绝并给出说明。

### 3.4 token（匿名）

```
GET https://auth.docker.io/token?service=registry.docker.io
    &scope=repository:<repo>:pull
→ {"token":"…"}（JSON，org.json 解析）
```

- 无 Authorization 头，匿名即可拿到；token 随后的 manifest / blob 请求
  以 `Authorization: Bearer <token>` 携带。
- 超时沿用 `httpText` 的 15s / 20s。token 失败统一归入「Docker Hub 不可达」。

### 3.5 manifest 协商

```
GET https://registry-1.docker.io/v2/<repo>/manifests/<tag>
Accept: application/vnd.docker.distribution.manifest.v2+json,
        application/vnd.docker.distribution.manifest.list.v2+json,
        application/vnd.oci.image.manifest.v1+json,
        application/vnd.oci.image.index.v1+json
```

按响应 `Content-Type` 分派：

- **list / index** → 遍历 `manifests[]`，按 §3.6 选平台，取其 `digest`
  再 `GET .../manifests/<digest>` 拿单 manifest；
- **单 manifest** → 直接用。

必须先走 list 再拿单 manifest（两次请求），不能只发一次：绝大多数
官方镜像的 tag 都指向 manifest list，直接要单 manifest 会被 Hub 以
manifest list 应答或 404。

### 3.6 平台选择表

| 设备 ABI | architecture | variant 匹配规则 |
|---|---|---|
| `arm64-v8a` | `arm64` | `v8` / `8` / 缺省 |
| `armeabi-v7a` | `arm` | `v7` / `7` / 缺省 |
| `x86_64` | `amd64` | 任意 variant |

- `os` 必须等于 `linux`。
- 匹配不到：报错并列出该 tag 下全部可用平台（用户能看懂是架构问题，
  而不是对着 404 猜）。

### 3.7 层下载与叠加

- 白名单 `layers[].mediaType`：
  - `application/vnd.docker.image.rootfs.diff.tar.gzip`
  - `application/vnd.oci.image.layer.v1.tar+gzip`
  - `application/vnd.docker.image.rootfs.diff.tar` /
    `application/vnd.oci.image.layer.v1.tar`（未压缩，`extract` 自动识别）
  - `*tar+zstd` → **整体拒绝**（见 §一），报错指路 `docker load` 导入；
  - `*nondistributable*` → 拒绝（法律限制 + 外部 URL 下载不通）。
- blob URL：`https://registry-1.docker.io/v2/<repo>/blobs/<digest>`。
- **边下边验**：流式写 cache 临时文件，同时 `MessageDigest` 算 sha256，
  完成后与 `digest` 比对；不符即删临时 + 报错（防 CDN 串味/截断，
  与 alpine 快路径落地后 ELF 复核同一防御思想）。
- **流水线**：当前层解包时，下一层在后台线程预取（盘上同时最多两个临时层，
  文件名 `pull-layer-*`，pull 开始时顺手清掉上次中断的残留）。
  gcc 这类多分层大镜像，串行「下载→解包→下载」的网络/磁盘空转占近一半墙钟时间；
  流水线把两边都喂满。预取线程不报进度，主线程只报「后台预取 层 i/n …」。
  层类型（mediaType）全部**前置校验**：任何一层不可收就直接拒绝，一个字节都不浪费。
- **失败自愈**：任何异常删掉半个 rootfs 重新抛出；而「进程被杀」留下的
  残留目录由镜像三态判定收口（见 §3.9）。
- 进度：用 `Content-Length` 报「下载 层 i/n（30.2MB）…」，复用现有
  `Progress` 通路（overlay / 控制台 sink）。
- **重定向**：blob 端点会 302/307 到 `production.cloudflare.docker.com`。
  `HttpURLConnection` 跨主机跟随会原样转发请求头（Bearer 泄给 CDN 无实害，
  但不干净）：`setInstanceFollowRedirects(false)`，收到 30x 手动跟随一次，
  新请求不带 token。

### 3.8 落地

`Containers.finalizeImage(ctx, ref, rootfs, progress)`（DNS / 绑定目录 /
登记）→ `ImageImporter.detectArch` 兜底校验（不一致只给 note，不删——
导入路径已是这个语义，保持一致）。

### 3.9 镜像三态（集中判定）

镜像状态只有一种判定入口 `Containers.imageState`，pull / rmi / 导入共用，
不允许各自用目录或注册表单边判断：

| 状态 | 含义 | pull | rmi |
|---|---|---|---|
| `IMG_REGISTERED` | 注册表在册 + 目录有效 | 报「已存在」 | 正常删除 |
| `IMG_ORPHAN` | 注册表没有、目录却有内容（pull 中途进程被杀的残留） | 清残留续拉 | 清残留并成功返回 |
| `IMG_ABSENT` | 都没有 | 正常拉取 | 报「镜像不存在」 |

没有这套收口时，pull 中断的残留会让用户被两头堵死：
pull 说「已存在（先 docker rmi）」，rmi 又说「镜像不存在」。
镜像大小统计（`dirSize`）不跟随符号链接——`File.length()` 会顺着链接
算目标大小，uutils 全家链接都指向同一个 10MB 主二进制，会把 130MB
算成 2.3GB。

## 四、错误分类表（HTTP → 用户可读消息）

| 场景 | 消息要点 |
|---|---|
| token 请求网络失败 | 「Docker Hub 不可达（原因）…」 |
| manifest 404 | 「没有这个镜像/标签：<ref>」（Hub 对私有/不存在仓库回 401/404 掩盖存在性，统一按「没有」处理） |
| blob 429 | 「匿名拉取限速（同 IP 100 次/6h），稍后再试，或用 `docker save` 导入」 |
| zstd 层 | 「该镜像的层用了 zstd 压缩，本端不支持。可在电脑上 `docker save` 后 `docker load -i` 导入」 |
| 平台不匹配 | 列出全部可用平台 |
| digest 校验失败 | 「下载的层数据校验不符，已丢弃（网络异常？）」 |
| ref 非法 | 沿用 `validImageRef` 现有文案 |

所有失败路径都要清理半成品（临时文件 / rootfs 目录），与 `importLayered`
的失败清理同构。

## 五、配套改动

- `DockerCli.pull`：用法提示 `docker pull [alpine:]TAG` → `docker pull 名[:标签]`，
  help 文案补一行「官方源之外的镜像走 Docker Hub（匿名，gzip 层）」。
- `MainActivity.showPullDialog`：标题「拉取镜像」，hint 改
  「镜像名，如 debian:12 / alpine:3.21」，默认值 `debian:12`；
  说明文案按输入分支（alpine → 官方 CDN 约 3MB；其它 → Docker Hub，
  提示几十 MB 起步）。overlay 复用不动。
- `docs/containers.md`、`docs/usage.md`、`docs/dev/roadmap.md` 行为实现后同步。

## 六、测试计划

**桌面可测**（新 `tools/RegistryTest.java`，第 6 套回归）——只测纯函数，
不碰 Context / org.json（桌面 android.jar 是 stub，JSON 留真机）：

- ref 解析全表（§3.3 各行 + 非法字符 + digest 拒绝 + `validImageRef` 闸门）
- 平台选择表（§3.6 各行 + variant 缺省 + 匹配不到）
- mediaType 白名单判定（gzip / 未压缩 / zstd 拒 / nondistributable 拒）
- 错误消息映射函数

因此 JSON 解析、流下载等 I/O 逻辑集中在少数方法里，纯判定逻辑全部
抽成 static 纯函数——这是本次设计对实现的硬要求。

**真机**：

- `debian:12`（manifest list + 多层 + whiteout 全链路）
- `ubuntu:24.04`（arm64 variant 显式标注的样本）
- `alpine:latest`（确认仍走 dl-cdn 快路径，不走 Hub）
- 一个 zstd 层镜像 → 报错文案正确
- 不存在的 tag → 404 文案；断网 → 不可达文案
- `docker images` / `docker run --name t debian:12` → 物化、进入、`apt update` 可用

## 七、体量预估

`RegistryPuller` 约 300–350 行 + `Containers.pull` 分发 ~15 行 + UI 文案
十余行。依赖零新增（HttpsURLConnection / org.json / MessageDigest 全是
Framework），APK 增量≈0，512KB 基线不动。

## 八、镜像源（换源）

**动机**：大陆网络官方 Hub（auth.docker.io / registry-1.docker.io）经常
不可达（DNS 污染），官方直连失败率接近 100%；Alpine 的 dl-cdn 可达但慢。

**配置**：两路各自独立，空值=官方直连。

- 存储：`Containers` prefs 的 `mirror_hub` / `mirror_alpine`，存归一化
  base（https://host[:port]/path，无尾斜杠）；alpine 的值含 `/alpine`
  目录前缀。
- 归一化：`RegistryPuller.normalizeMirror`（纯函数，桌面可测）——裸域名补
  https://，剥尾斜杠与 `/v2` 后缀，空/clear/官方=清空，协议只收 http(s)，
  host 与端口校验，非法抛用户可读 IOException。
- 入口：菜单「容器与镜像 → 镜像源设置…」（两输入框，留空恢复官方）；
  控制台 `docker mirror`（查看）/ `docker mirror hub|alpine <地址|clear>`。

**拉取流程（mirror 版）**：registry 端点 = `<mirror>/v2/…`。

1. 匿名请求 manifest；401 → 解析应答的 `WWW-Authenticate`：
   `Bearer realm="…",service="…"`（纯函数 `realmParam`，桌面可测）。
   realm 就是鉴权端点——镜像站自报自己的（1ms 报
   `docker.1ms.run/openapi/v1/auth/token`，daocloud 报
   `m.daocloud.io/auth/token`），**不依赖 auth.docker.io**；无 realm 才
   退回官方端点。token 字段收 `token` / `access_token` 两版规范。
2. blob 同理：401 时若尚未持有 token，按 realm 补取重试一次。
3. 全匿名站（dockerproxy.net 的 `/v2/` 直接 200）跳过整个 token 环节。

**失败语义**：镜像站不可达报「查询/鉴权服务不可达」带原因；非法源在
保存时就拒绝（设置动作本身报错，不影响既有配置）。

**第一版不做**：多镜像站级联失败转移（用户手动换源即可）、写镜像源的
凭据（镜像站私有鉴权）、镜像源健康探测。

## 九、公共第三方 registry（2026-11 增补）

**动机**：GitHub Packages（ghcr.io）、Quay 等公共镜像没有 Hub 镜像，用户
此前只能 `docker save` 后导入。评估时确认：鉴权（realm/service/scope 来自
401 应答）与 blob 307 跟随两块通用能力项目里已经具备，增量主要是 ref 解析
与端点选择，故直接做。

**ref 规则**（`parseRef`，纯函数）：

- 首段含 `.` / `:` / `localhost` 视为 registry 域名；`docker.io`、
  `index.docker.io`、`registry-1.docker.io` 归一到 Hub（镜像源照常生效）；
- 第三方保留域名进登记名（`ghcr.io/owner/app:tag`），目录名经 `dirName`
  变 `ghcr.io_owner_app-tag`，与 Hub 同名镜像不会混；
- tag 取「最后一个 `/` 之后的冒号」，顺带消掉端口歧义；带端口写法直接
  拒绝（自建场景，指路导入）；
- 第三方必须 `域名/owner/名` 两段；不含 `library/` 前缀补全。

**端点选择**（`registryBase`，纯函数）：Hub → 镜像源或官方；第三方 →
`https://<host>/v2/` 直连。镜像源只代理 Hub，套到 ghcr 上必然 404。

**鉴权**：第三方匿名同样走 401 → `WWW-Authenticate` 的 realm 取 token
（GHCR 为 `https://ghcr.io/token?service=ghcr.io&scope=repository:owner/app:pull`）。
realm 缺失时只有 Hub 退回 auth.docker.io；第三方直接报「未提供匿名鉴权端点」，
不再拿 Hub token 去撞。

**实测（2026-11-27，手机容器网络）**：`GET https://ghcr.io/v2/` → 401；
`home-assistant/home-assistant` 匿名 token → 200；`manifests/latest`（OCI
index Accept）→ 200；层 blob → 307 到 `pkg-containers.githubusercontent.com`
→ 206（Range）。`library/hello-world` 的 token 为 403 是因为 GHCR 上无此包，
不是必须登录。实机直连 ghcr.io 的层下载被限速到 1–5 KB/s（读超时后正确报错、
清理半成品）；把 `ghcr.nju.edu.cn` 当 registry 域名后，`linuxserver/baseimage-alpine:3.21`
（7 层）完整拉取并成功建容器（`/etc/alpine-release` = 3.21.0，`pidof sh` 正常）。

**顺带修复**：拉取暴露了 `TarGzExtractor` 的一个老缺陷——下层把某路径从绝对
符号链接改成实体文件时，`FileOutputStream` 会顺着链接写到宿主只读 `/bin`
（真机报 EROFS）。现在落盘前先删同名链接，`TarTest` 加了对应用例（24→25）。

**边界**：私有包（含 GitHub Packages 默认私有）→ 401 报「拒绝访问」；
zstd 层照旧拒绝；GHCR 大镜像（1–2GB）对手机磁盘和时长不友好，属已知取舍；
busybox 这类硬链接密集镜像解包时会复制成多份（SELinux 禁 `link(2)`），
体积放大是既有行为，另行评估。
