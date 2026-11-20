#!/usr/bin/env bash
# 实机取证「外接键盘 → 输入法 → PTY」这条链路：拉起接管态、逐条注入硬键盘序列、
# 打印每次注入后 PTY 真正收到的字节。
#
# 用法: bash tools/ime-check.sh '<hardkey stdin 序列>' ['序列2' ...]
#   例: bash tools/ime-check.sh 'n i h a o :800 space' 'shift :100 shift :800 n i h a o'
#
# 前提：
#   1. 只跑 debug 变体（applicationIdSuffix .debug → 包名 dev.dualterm.debug）。
#      起成 dev.dualterm 会命中另一台时间戳更早的 release 包，结论全废。
#   2. 编译并推入工装：见 tools/hardkey.c 头注释（uinput 真实设备路径，
#      input keyevent 与 --es keytest 都是虚拟键盘事件，输入法不接管）。
set -u
export MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'
cd "$(dirname "$0")/.." || exit 1
ADB=.toolchain/sdk/platform-tools/adb.exe
PKG=dev.dualterm.debug
HK="/data/local/tmp/hardkey --stdin --settle 1000 --gap 150"

$ADB shell am force-stop $PKG >/dev/null
sleep 2
$ADB shell am start -n $PKG/dev.dualterm.MainActivity \
    --activity-clear-task -a android.intent.action.VIEW -d 'cat' \
    --ei chunklog 400 >/dev/null
sleep 9

# 点终端区把输入法窗口叫出来（自动档的接管门槛就是它可见），叫不动就再点一次
for _ in 1 2 3; do
    $ADB shell input tap 500 800
    sleep 3
    shown=$($ADB shell "dumpsys input_method | grep -oE 'mInputShown=[a-z]+'" | head -1)
    case "$shown" in *true) break ;; esac
done
printf '输入法窗口: %s · 输入法侧: %s\n' "$shown" \
    "$($ADB shell "dumpsys input_method | grep -oE 'haveComposingNative:[a-z]+|mInputViewStarted=[a-z]+'" | tr '\n' ' ')"

for seq in "$@"; do
    echo "######## $seq"
    $ADB shell logcat -c
    printf '%s\n' "$seq" | $ADB shell "$HK" >/dev/null
    sleep 1
    # out#N 是 PTY 收到的输出块（cat 回显 = 送进 PTY 的字节），逐字节证据只认这一路。
    # 剥前缀不要用固定宽度：logcat 的日期时间列宽随 pid 位数变，写死会一条都匹配不上。
    $ADB shell logcat -d | grep -a "DualTerm" \
        | sed -E 's/^.*DualTerm: //' \
        | grep -E "^out#" | tail -8
done
