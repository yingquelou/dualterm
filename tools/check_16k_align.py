#!/usr/bin/env python3
"""校验 APK 内 64 位 .so 的 ELF PT_LOAD 是否按 16KB 对齐。

Android 15+ 存在 16KB 内存页设备：ELF 的 LOAD 段 p_align 小于 0x4000 时，
内核按 16KB 页 mmap 会失败，应用一进容器模式就崩。本工程把 so 解压到
nativeLibraryDir 再从磁盘 exec（useLegacyPackaging true），因此决定因素是
ELF 内的 p_align，而不是 zipalign 的条目偏移对齐——两者都要看，这个脚本查前者。

只需检查 64 位 ABI：16KB 页只存在于 arm64，armeabi-v7a 不受影响（仍会打印）。

用法：
    python3 tools/check_16k_align.py app/build/outputs/apk/release/app-release.apk
退出码 0 = 全部合格；1 = 存在未对齐或无法解析的 so。
"""

import struct
import sys
import zipfile

PAGE_16K = 0x4000
CHECKED_ABIS = ("arm64-v8a", "x86_64")


def load_aligns(blob):
    """返回 ELF 所有 PT_LOAD 段的 p_align；非 ELF 返回 None。"""
    if len(blob) < 0x40 or blob[:4] != b"\x7fELF":
        return None
    is64 = blob[4] == 2
    if is64:
        phoff = struct.unpack_from("<Q", blob, 0x20)[0]
        phentsize = struct.unpack_from("<H", blob, 0x36)[0]
        phnum = struct.unpack_from("<H", blob, 0x38)[0]
        align_off, align_fmt = 0x30, "<Q"
    else:
        phoff = struct.unpack_from("<I", blob, 0x1c)[0]
        phentsize = struct.unpack_from("<H", blob, 0x2a)[0]
        phnum = struct.unpack_from("<H", blob, 0x2c)[0]
        align_off, align_fmt = 0x1c, "<I"
    aligns = []
    for i in range(phnum):
        off = phoff + i * phentsize
        if off + phentsize > len(blob):
            return None
        if struct.unpack_from("<I", blob, off)[0] == 1:  # PT_LOAD
            aligns.append(struct.unpack_from(align_fmt, blob, off + align_off)[0])
    return aligns


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    apk = argv[1]
    bad = []
    with zipfile.ZipFile(apk) as z:
        names = sorted(n for n in z.namelist()
                       if n.startswith("lib/") and n.endswith(".so"))
        if not names:
            print("APK 内没有 lib/<abi>/*.so", file=sys.stderr)
            return 1
        for name in names:
            abi = name.split("/")[1]
            aligns = load_aligns(z.read(name))
            if aligns is None:
                bad.append((name, "非 ELF 或文件头截断"))
                print("BAD ", name, "非 ELF 或文件头截断")
                continue
            ok = bool(aligns) and all(a >= PAGE_16K for a in aligns)
            desc = ",".join(hex(a) for a in aligns)
            if abi not in CHECKED_ABIS:
                # 32 位 ABI 无 16KB 页，仍打印便于人工核对，不参与判定
                print("INFO", name, "p_align=" + desc, "(32 位，免检)")
                continue
            print("OK  " if ok else "BAD ", name, "p_align=" + desc)
            if not ok:
                bad.append((name, desc))
    if bad:
        print("16KB 页对齐不合格：%s" % bad, file=sys.stderr)
        return 1
    print("全部 64 位 so 的 PT_LOAD 均 >= 16KB 对齐")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
