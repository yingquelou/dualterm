# -*- coding: utf-8 -*-
"""Spinner 修复验证：dump 驱动逐模式选择，dump 取文本 + 截图取像素。"""
import re, subprocess, time, sys
from PIL import Image
from collections import Counter

SERIAL = 'vkpv5dmzwk7hoffe'
ADB = ['E:/repo/java/demo/.toolchain/sdk/platform-tools/adb.exe', '-s', SERIAL]
LABELS = ['文本', '^X', 'ESC', '字节', '回车', '延迟']

def sh(*args):
    return subprocess.run(ADB + list(args), capture_output=True, text=True,
                          timeout=60).stdout

def dump():
    sh('shell', 'uiautomator', 'dump', '/sdcard/ui.xml')
    return sh('shell', 'cat', '/sdcard/ui.xml')

def dump_node(pattern):
    xml = dump()
    m = re.search(pattern, xml)
    if not m:
        return None
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    return t.group(1), tuple(int(b.group(i)) for i in range(1, 5))

def tap(x, y, wait=1.0):
    sh('shell', 'input', 'tap', str(x), str(y))
    time.sleep(wait)

def center(b):
    return (b[0]+b[2])//2, (b[1]+b[3])//2

sh('shell', 'am', 'force-stop', 'dev.dualterm')
time.sleep(0.5)
sh('shell', 'am', 'start', '-n', 'dev.dualterm/.MainActivity')
time.sleep(6)

# 展开辅助键盘
node = dump_node(r'<node[^>]*text="⌨"[^>]*class="android.widget.Button"[^>]*>')
if not node:
    print('FAIL: 找不到 ⌨ 键'); sys.exit(1)
tap(*center(node[1]), wait=1.2)

# 点 ＋
node = dump_node(r'<node[^>]*text="＋"[^>]*class="android.widget.Button"[^>]*>')
if not node:
    print('FAIL: 找不到 ＋ 键'); sys.exit(1)
tap(*center(node[1]), wait=1.5)

results = []
for label in LABELS:
    # 打开下拉
    node = dump_node(r'<node[^>]*class="android.widget.Spinner"[^>]*>')
    if not node:
        print('FAIL: 找不到 Spinner'); sys.exit(1)
    tap(*center(node[1]), wait=1.2)
    # 下拉项：ListView 里文本为 LABELS 的 TextView
    xml = dump()
    item = None
    for m in re.finditer(r'<node[^>]*class="android.widget.TextView"[^>]*>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n)
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if t and b and t.group(1) == label:
            item = tuple(int(b.group(i)) for i in range(1, 5))
            break
    if item is None:
        print('FAIL: 下拉里找不到 %s' % label); sys.exit(1)
    tap((item[0]+item[2])//2, (item[1]+item[3])//2, wait=2.0)
    # 验证：Spinner 边界内的 TextView 文本 + 截图像素
    spin = dump_node(r'<node[^>]*class="android.widget.Spinner"[^>]*>')
    sb = spin[1]
    txt, b = None, None
    xml = dump()
    for m in re.finditer(r'<node[^>]*class="android.widget.TextView"[^>]*>', xml):
        n = m.group(0)
        t = re.search(r' text="([^"]*)"', n)
        b2 = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if not (t and b2):
            continue
        bb = tuple(int(b2.group(i)) for i in range(1, 5))
        if bb[0] >= sb[0] and bb[2] <= sb[2] and bb[1] >= sb[1] and bb[3] <= sb[3]:
            txt, b = t.group(1), bb
            break
    if b is None:
        print('FAIL: Spinner 内没有 TextView'); sys.exit(1)
    png = subprocess.run(ADB + ['exec-out', 'screencap', '-p'],
                         capture_output=True, timeout=60).stdout
    open('.tmp_v.png', 'wb').write(png)
    im = Image.open('.tmp_v.png').convert('RGB')
    w, h = im.size
    c = Counter()
    for yy in range(b[1], b[3]):
        for xx in range(b[0], min(b[2], w)):
            c[im.getpixel((xx, yy))] += 1
    nonbg = sum(c.values()) - c.most_common(1)[0][1]
    vis = 'VISIBLE' if nonbg > 300 else 'BLANK'
    print('%-4s dump_text=%r render=%s (nonbg=%d)' % (label, txt, vis, nonbg))
    results.append((label, txt, vis))

print('---')
allok = all(v == 'VISIBLE' and t == l for (l, t, v) in results)
print('ALL VISIBLE & TEXT MATCH' if allok else 'SOME FAILED')
