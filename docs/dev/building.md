# 构建、测试与发布

## 环境

JDK 17、Android SDK（compileSdk 34）、NDK 26.1.10909125、CMake 3.22.1。AGP 与应用配置见 `app/build.gradle`。

## 构建

```bash
# 1. 打开工程
#    Android Studio → Open → dualterm/（首次 Sync 会提示安装 NDK 与 CMake，同意即可）

# 2. 准备容器运行时（只影响容器模式；不跑也能编译，本地模式照常可用）
powershell -ExecutionPolicy Bypass -File scripts\fetch-runtime.ps1     # Windows
bash scripts/fetch-runtime.sh                                          # Linux / macOS / Git Bash
#    → proot / loader / 依赖库 落进 app/src/main/jniLibs/<abi>/
#    → rootfs pack 默认不下载、不进包：镜像由应用内 docker pull / 导入 tar 获得
#      需要离线调试包时显式 WITH_ROOTFS=1 再跑脚本，Alpine minirootfs 才会落到
#      app/src/main/assets/runtime/rootfs-<abi>.pack

# 3. 出包（仓库自带 wrapper，Gradle 8.7）
./gradlew assembleDebug
#    产物：app/build/outputs/apk/debug/app-debug.apk
```

运行时脚本默认准备单一 ABI（脚本参数可指定 `arm64-v8a` / `armeabi-v7a` / `x86_64`），发布流水线会依次准备三个。

wrapper 首次运行会从 `services.gradle.org` 下载 Gradle 8.7（约 100MB）；离线环境可自备同版本分发，或直接用本机 Gradle：

```bash
gradle assembleDebug           # 等价，跳过 wrapper 下载
```

> 命令行构建需要 `ANDROID_HOME`（或 `local.properties` 里的 `sdk.dir`）与 `JAVA_HOME` 指向 JDK 17。

## 桌面回归测试

七套测试都不需要设备，用桌面 JVM 直接跑。除 EmuTest 外，其余用 `android.jar` 作编译桩（只为类加载，不调用桩方法）。CI 的发布流水线会在打包前跑全七套。

**终端仿真器**（纯 Java，无依赖）：

```bash
javac -encoding UTF-8 -sourcepath app/src/main/java -d /tmp/etest \
      app/src/main/java/dev/dualterm/term/TerminalEmulator.java tools/EmuTest.java
java -cp /tmp/etest EmuTest      # 全部通过时打印 ALL PASS
```

覆盖 DEC 画线字符集、延迟换行、DECAWM、CJK 占位、DECSCUSR、鼠标序列、焦点上报、备用屏 scrollback 隔离、SO/SI、CPR、resize（顶裁/底补/scrollback 保留/宽字符跨界）、DECALN、`ESC % G` 参数字节吞除、DECSTR、DECOM 原点模式、SGR 冒号真彩、modifyOtherKeys 不当 SGR、制表位（HTS/TBC）、组合字符、DCH 宽字符修复、BCE、选区取文本（软换行拼行 / 填充剥除 / 宽字符收拢 / 全局行号）与长按选词的词边界（空白 / 标点 / 宽字符逐字 / 跨软换行）。修改仿真器后先跑这一套，再上真机看 vim。

**tar/OCI 解包**（`tools/TarTest.java`）：

```bash
javac -encoding UTF-8 -cp $ANDROID_HOME/platforms/android-34/android.jar \
      -sourcepath app/src/main/java -d /tmp/tt tools/TarTest.java
java -cp "/tmp/tt:$ANDROID_HOME/platforms/android-34/android.jar" TarTest
```

覆盖 `..` 穿越拒绝、`.wh.`/opq 白化、pax 与 GNU 长文件名、ustar prefix 拼接、硬链接与绝对符号链接实体化退路、目录与文件互相顶替、gzip 自动识别。

**镜像导入**（`tools/ImportTest.java`，同款跑法）：`detectArch` 的 ELF 架构识别，`pickRef` 的镜像名闸门（恶意 RepoTags 白名单拒绝）。整包 `importArchive` 依赖 Context 与真 `org.json` 实现，仍需真机验证。

**Hub 拉取**（`tools/RegistryTest.java`，同款跑法）：`RegistryPuller` 的纯逻辑部分——`parseRef`（library 前缀补全/剥离、tag 归一化、digest 拒绝、`validImageRef` 闸门）、`platformMatches`（ABI × architecture/variant 匹配表）、`layerKind`/`rejectReason`（gzip 收、zstd / nondistributable 拒）、`normalizeMirror`（换源归一化：补协议/剥尾斜杠与 /v2、非法拒绝）、`realmParam`（WWW-Authenticate 参数摘取，Hub/1ms/daocloud 实样）。JSON 解析与流下载依赖 Context，留真机验证（已过，见 pull-registry.md）。

**注入链**（`tools/SecTest.java`，同款跑法）：`shQuote` 转义还原、`checkSafeName`、恶意 RepoTags 拒绝、`dockerVolumeArg`、`su -c` 拼装层拦截。

**端口声明**（`tools/PortsTest.java`，同款跑法）：`-p` 写法解析（同端口直通、重映射识别、udp / 范围 / IPv6 / 越界拒绝）与提示文案。

**辅助键盘**（`tools/KbdTest.java`，同款跑法）：自定义键步骤序列的纯逻辑——`StepMode` 六种模式（文本 / ^X / ESC / 字节 / 回车 / 延迟）的值校验、规范化、字节变换与自动标签，含「回车模式校验误落文本分支致对话框卡死」的历史 bug 锁定；`KbdLayout.visibleCustomRows` 的行数上限算术（0 / 上限 / 不限 / 边界）。改步骤语义或行数算术必须补用例。

**UI 渲染实测**（`tools/verify_spinner.py`，需真机）：不是桌面回归——靠 adb 逐模式展开下拉、dump 文本、统计截图像素，验证大字体等环境下控件确实可见。改了自绘/系统控件的排版后手动跑一次。

## 发布

`.github/workflows/release.yml` 负责发版。推送 `v*` tag 即出包并创建 Release：

```bash
git tag v3.6.0
git push origin v3.6.0
```

也可在 Actions 页面手动 `Run workflow` 并填入 tag；勾选 `dry_run` 时只构建并上传 artifact，不创建 Release，用于验证构建链。

流水线四件事：

1. 用 `scripts/fetch-runtime.sh` 拉三个 ABI 的 proot 运行时（二进制不入库，见
   `.gitignore`；不拉就会产出「能安装、但容器模式一进就报缺运行时」的包）。
   rootfs 不进包，镜像由用户在应用内 `docker pull` / 导入 tar 获取
2. 跑七套桌面回归：EmuTest / TarTest / ImportTest / RegistryTest / SecTest / PortsTest / KbdTest
3. `assembleRelease`，产物重命名为 `dualterm-<版本>.apk` 并附 `SHA256SUMS`
4. 校验 APK 内三个 ABI 的 `libproot.so` 都在，缺失即失败（rootfs 不进包，不校验）

Release 说明由流水线按 tag 区间生成（`上一个 tag..本次 tag` 的提交列表），不使用 `--generate-notes`：后者在没有上一个 Release 时只输出一行 Full Changelog，首个发布会等于没有变更说明。同一 tag 重跑时会补传产物并刷新说明，不会产生第二个 Release。

**版本号**：tag `v3.6.0` → `versionName 3.6.0`、`versionCode 30600`（major×10000 + minor×100 + patch，单调递增，可覆盖升级）。

**签名**：仓库内的 `keystore/dualterm-test.jks` 是**测试签名**——等同 debug key，口令即 `dualterm`，公开可查，**不构成任何身份保证**。它只在没有配置 Secrets 的构建里兜底（本地随手出包、fork 后跑 CI）；正式发布使用维护者私钥。

正式签名通过四个 Secrets 注入，2026-11-17 起生效：

| Secret | 对应 build.gradle | 内容 |
|---|---|---|
| `KEYSTORE_BASE64` | `DT_KEYSTORE` | 密钥库文件的 base64，**单行无换行**（`base64 -w0 x.jks`） |
| `KEYSTORE_PASSWORD` | `DT_STORE_PASSWORD` | 密钥库口令（storepass） |
| `KEY_ALIAS` | `DT_KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | `DT_KEY_PASSWORD` | 该条目口令（keypass） |

当前发布密钥的公开标识（可安全记录，用于核对「包是不是本工程签的」）：

- 别名 `dualterm`
- 证书 DN `CN=DualTerm, OU=dev, O=DualTerm, L=City, ST=Province, C=CN`
- RSA 4096 / SHA256withRSA；按 `-validity 10950` 生成，有效期到 2056 年前后（以 `keytool -list -v` 的 `Valid from` 为准）
- SHA-256 指纹 `98:FE:8D:25:CB:0F:CE:40:85:9E:0C:32:1A:26:DE:38:A9:29:B4:3C:AC:9C:BE:90:A2:8B:F7:85:71:10:18:AC`

核对任意发布包（不需要私钥）：

```bash
apksigner verify --print-certs dualterm-<版本>.apk | grep "SHA-256"
```

指纹不符即不是本工程正式签名的包。

**私钥保管**：

- 密钥库文件与口令**分开存放**——文件至少两份、异地离线（移动硬盘 / 加密 U 盘），口令进密码管理器；
- 密钥库**不入库**，放仓库目录之外（`.gitignore` 只忽略构建产物，`keystore/*.jks` 并未排除，测试签名是故意入库的）；
- GitHub Secrets **只写不读**，网页与控制台都看不到值，不能充当备份；
- 备份后要验：`keytool -list -keystore <副本> -storepass <口令>` 能看到 `PrivateKeyEntry` 才算有效副本。

**换签名的代价**：同一 `applicationId` 的升级包必须同签名。私钥丢失只能换包名重发，旧版本永远收不到更新；已装的旧签名包也无法覆盖升级（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），需先卸载，容器与设置一并清空。因此不要在同一个版本号上悄悄换签名——应升版本号并在说明里写明（2026-11-17 的 3.6.0 是就地覆盖的例外：当时只有维护者自用）。

本地出签名包走同一条路：`./gradlew assembleRelease`（未设置环境变量时使用测试签名），环境变量 `DT_KEYSTORE` / `DT_STORE_PASSWORD` / `DT_KEY_ALIAS` / `DT_KEY_PASSWORD` 可覆盖。

**升级安装**：覆盖安装要求与已装版本同签名。debug 包与本工程 Release 包签名不同，互装会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，需先卸载（应用内的容器与设置会一并清空）。
