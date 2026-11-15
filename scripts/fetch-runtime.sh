#!/usr/bin/env bash
#
#  为容器模式准备运行时资源（fetch-runtime.ps1 的 Linux/macOS 等价版）
#  ------------------------------------------------------------------
#  用法（在工程根目录 dualterm/ 下执行）：
#
#      scripts/fetch-runtime.sh                 # 默认 arm64-v8a
#      scripts/fetch-runtime.sh armeabi-v7a
#      scripts/fetch-runtime.sh x86_64          # 模拟器
#      ALPINE_VERSION=3.20.3 scripts/fetch-runtime.sh
#
#  产物分两处（Android 10+ 的硬限制决定的，与 ps1 版完全一致）：
#
#    app/src/main/jniLibs/<abi>/    libproot.so / libproot_loader.so
#                                   libtalloc2.so / libandroid-shmem.so
#    app/src/main/assets/runtime/   rootfs-<abi>.pack
#
#  三个 Termux 包各出一部分：
#    proot              → bin/proot + libexec/proot/loader
#    libtalloc          → lib/libtalloc.so.2.*（落成 libtalloc2.so，见下方说明）
#    libandroid-shmem   → lib/libandroid-shmem.so
#
#  为什么要有这个脚本：GitHub Actions 跑在 ubuntu 上，ps1 版依赖 Windows 的路径与
#  bsdtar 解 .deb 的能力。这里用 ar + tar + python3 重写，产物可直接互换。
#
#  依赖：curl、ar（binutils）、tar（含 xz）、python3、sha256sum
#
set -euo pipefail

ABI="${1:-arm64-v8a}"
ALPINE_VERSION="${ALPINE_VERSION:-3.20.3}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/runtime"
JNI="$ROOT/app/src/main/jniLibs/$ABI"

case "$ABI" in
    arm64-v8a)   ALPINE_ARCH=aarch64; TERMUX_ARCH=aarch64 ;;
    armeabi-v7a) ALPINE_ARCH=armv7;   TERMUX_ARCH=arm ;;
    x86_64)      ALPINE_ARCH=x86_64;  TERMUX_ARCH=x86_64 ;;
    *) echo "未知 ABI: $ABI（可选 arm64-v8a / armeabi-v7a / x86_64）" >&2; exit 2 ;;
esac

TMP="$(mktemp -d "${TMPDIR:-/tmp}/dualterm-proot-XXXXXX")"
# Git Bash 下 TMPDIR 可能是 Windows 风格（C:\Users\...），带盘符冒号会被 tar 当成
# 「远程主机:路径」而报 "Cannot connect to C:"。cd 进去再 pwd，取回 POSIX 路径。
TMP="$(cd "$TMP" && pwd)"
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$ASSETS" "$JNI"

echo
echo "== DualTerm 运行时准备 =="
echo "ABI        : $ABI"
echo "Alpine 架构: $ALPINE_ARCH"
echo "jniLibs    : $JNI"
echo "assets     : $ASSETS"
echo

# ---------------------------------------------------------------- 1. rootfs
ROOTFS_PACK="$ASSETS/rootfs-$ABI.pack"
ROOTFS_NAME="alpine-minirootfs-$ALPINE_VERSION-$ALPINE_ARCH.tar.gz"
if [ -f "$ROOTFS_PACK" ]; then
    echo "[跳过] 已存在 rootfs-$ABI.pack"
else
    URL="https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$ALPINE_ARCH/$ROOTFS_NAME"
    echo "[1/2] 下载根文件系统 $ROOTFS_NAME ..."
    curl -fsSL --retry 3 --retry-delay 2 --connect-timeout 20 "$URL" -o "$ROOTFS_PACK.part"
    mv "$ROOTFS_PACK.part" "$ROOTFS_PACK"
    echo "      OK  ($(du -h "$ROOTFS_PACK" | cut -f1)) -> assets/runtime/rootfs-$ABI.pack"
fi

# ---------------------------------------------------------------- 2. proot
if [ -f "$JNI/libproot.so" ] && [ -f "$JNI/libtalloc2.so" ] && [ -f "$JNI/libandroid-shmem.so" ]; then
    echo "[跳过] 已存在 jniLibs/$ABI 的 proot 运行时"
    echo
    echo "完成（无需改动）。"
    exit 0
fi

INDEX_URL="https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-$TERMUX_ARCH/Packages"
echo "[2/2] 读取 Termux 软件源索引 ..."
curl -fsSL --retry 3 --connect-timeout 20 "$INDEX_URL" -o "$TMP/Packages"

# 按空行分段，按字段精确匹配 Package 名（避免命中 proot-distro / libtalloc-static）
pkg_block() {
    awk -v want="Package: $1" 'BEGIN{RS="";FS="\n"} { for (i=1;i<=NF;i++) if ($i == want) { print; exit } }' "$TMP/Packages"
}

# 下载 + 校验 + 从 deb 里取出内层 data.tar 的路径（不解包到磁盘：
# libtalloc 的 usr/lib 里全是符号链接，Windows 上创不出来会整包失败）。
fetch_data_tar() {
    local pkg="$1" dir="$TMP/$1" block fn sha url actual data
    block="$(pkg_block "$pkg")"
    if [ -z "$block" ]; then
        echo "源里没有 $pkg 包，检查软件源地址是否变化" >&2
        return 1
    fi
    fn="$(printf '%s\n' "$block" | sed -n 's/^Filename: //p' | head -1)"
    sha="$(printf '%s\n' "$block" | sed -n 's/^SHA256: //p' | head -1)"
    url="https://packages.termux.dev/apt/termux-main/$fn"

    mkdir -p "$dir"
    echo "      下载 $fn ..." >&2
    curl -fsSL --retry 3 --connect-timeout 20 "$url" -o "$dir/pkg.deb"
    if [ -n "$sha" ]; then
        # 从 stdin 读，避免 MSYS/Git Bash 在文件名含特殊字符时给输出加 "\" 前缀
        actual="$(sha256sum < "$dir/pkg.deb" | cut -d' ' -f1)"
        if [ "$actual" != "$sha" ]; then
            echo "SHA256 校验失败：$pkg（期望 $sha，实际 $actual）" >&2
            return 1
        fi
    fi
    ( cd "$dir" && ar x pkg.deb )
    data="$(ls "$dir"/data.tar.* 2>/dev/null | head -1)"
    if [ -z "$data" ]; then
        echo ".deb 里没找到 data.tar.*：$pkg" >&2
        return 1
    fi
    echo "$data"
}

# 在 tar 归档里找第一个普通文件（$1 首字符是 "-"，符号链接是 "l"，正好跳过）
member_of() {
    # 清单先落到文件再筛：`tar -tvf | awk '{print;exit}'` 会让 tar 收到 SIGPIPE，
    # CI 的 GNU tar 上直接 "tar: stdout: write error" 退出码 2，pipefail 一开整步就挂
    tar -tvf "$1" > "$TMP/members.$$.txt"
    awk -v re="$2" '$1 ~ /^-/ && $NF ~ re { print $NF; exit }' "$TMP/members.$$.txt"
}

extract_member() {
    tar -xOf "$1" "$2" > "$3"
}

DATA_PROOT="$(fetch_data_tar proot)"
DATA_TALLOC="$(fetch_data_tar libtalloc)"
DATA_SHMEM="$(fetch_data_tar libandroid-shmem)"

P="$(member_of "$DATA_PROOT" 'usr/bin/proot$')"
[ -n "$P" ] || { echo "deb 里没找到 proot 可执行文件" >&2; exit 1; }
extract_member "$DATA_PROOT" "$P" "$JNI/libproot.so"
chmod 755 "$JNI/libproot.so"

# AGP 只打包匹配 lib*.so 的文件，libtalloc.so.2 会被静默丢弃。
# 所以把 proot 的 DT_NEEDED 字符串原位改写成 libtalloc2.so（更短，NUL 补齐）。
# 两者等长（15 字节），替换后 ELF 布局不变。
python3 - "$JNI/libproot.so" <<'PY'
import sys
path = sys.argv[1]
data = open(path, 'rb').read()
old, new = b"libtalloc.so.2\x00", b"libtalloc2.so\x00\x00"
assert len(old) == len(new), "替换字符串必须等长"
count = data.count(old)
if count != 1:
    sys.exit("期望恰好 1 处 libtalloc.so.2 NEEDED，实际 %d 处" % count)
open(path, 'wb').write(data.replace(old, new))
PY
echo "      proot -> jniLibs/$ABI/libproot.so（NEEDED 已改写为 libtalloc2.so）"

P="$(member_of "$DATA_PROOT" 'usr/libexec/proot/loader$')"
[ -n "$P" ] || { echo "deb 里没找到 proot 的 loader（没有它，noexec 目录里的 rootfs 无法启动）" >&2; exit 1; }
extract_member "$DATA_PROOT" "$P" "$JNI/libproot_loader.so"
chmod 755 "$JNI/libproot_loader.so"
echo "      loader -> jniLibs/$ABI/libproot_loader.so"

# 依赖库取归档里的真文件（libtalloc.so.2 本身是指向 libtalloc.so.2.x 的软链）
P="$(member_of "$DATA_TALLOC" 'usr/lib/libtalloc[.]so[.][0-9]')"
[ -n "$P" ] || { echo "libtalloc 包里没找到 libtalloc.so.*" >&2; exit 1; }
extract_member "$DATA_TALLOC" "$P" "$JNI/libtalloc2.so"
chmod 644 "$JNI/libtalloc2.so"
echo "      $(basename "$P") -> jniLibs/$ABI/libtalloc2.so"

P="$(member_of "$DATA_SHMEM" 'usr/lib/libandroid-shmem[.]so$')"
[ -n "$P" ] || { echo "libandroid-shmem 包里没找到 libandroid-shmem.so" >&2; exit 1; }
extract_member "$DATA_SHMEM" "$P" "$JNI/libandroid-shmem.so"
chmod 644 "$JNI/libandroid-shmem.so"
echo "      libandroid-shmem.so -> jniLibs/$ABI/libandroid-shmem.so"

echo
echo "完成。产物："
ls -l "$JNI" | tail -n +2 | awk '{print "  " $NF, "(" $5 " bytes)"}'
echo "现在可以构建 APK：./gradlew assembleDebug"
