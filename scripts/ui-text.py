#!/usr/bin/env python3
"""抓真机当前界面的可见文案与几何（Compose / 原生 / WebView 都行）。

本机验证的常规手段：模型看不了截图，但 uiautomator 的无障碍树里有每个节点的
text / content-desc / bounds，足以核对「界面上到底显示了哪国语言」以及
「有没有折行、越界」——多语言适配时尤其管用（英文普遍比中文长）。

用法:
    python scripts/ui-text.py <serial>                  # 列出界面上所有文案
    python scripts/ui-text.py <serial> --grep 设置       # 只列命中的行
    python scripts/ui-text.py <serial> --bounds ID 地址  # 看具体节点的宽高（h 翻倍=折行）
    python scripts/ui-text.py <serial> --overflow 1080  # 找出横向越出屏宽的节点
    python scripts/ui-text.py <serial> --tap Settings   # 按文案/描述点一下（点中了再抓）

依赖: adb（默认找 %LOCALAPPDATA%/Android/Sdk/platform-tools/adb.exe，或用 ADB 环境变量覆盖）
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB") or os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)


def adb(serial, *args, check=False):
    r = subprocess.run(
        [ADB, "-s", serial, *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if check and r.returncode != 0:
        sys.exit(f"adb 失败: {' '.join(args)}\n{r.stderr.strip()}")
    return r.stdout


def dump(serial):
    """偶发 dump 到一半拿到空树/上一帧，重试几次并用独立文件名避免读到旧档"""
    remote = f"/sdcard/.aicast-ui-{os.getpid()}.xml"
    for _ in range(3):
        adb(serial, "shell", "uiautomator", "dump", remote)
        xml = adb(serial, "shell", "cat", remote)
        if "<hierarchy" in xml:
            adb(serial, "shell", "rm", remote)
            return ET.fromstring(xml[xml.index("<hierarchy"):])
    sys.exit(f"{serial}: uiautomator dump 为空（界面未就绪或无障碍被禁？）")


def box(b):
    return [int(v) for v in re.findall(r"-?\d+", b or "[0,0][0,0]")]


def nodes(root):
    for n in root.iter("node"):
        t = (n.get("text") or "").strip()
        d = (n.get("content-desc") or "").strip()
        if t or d:
            yield n, t, d


def main():
    argv = sys.argv[1:]
    if not argv:
        sys.exit(__doc__)
    serial = argv[0]
    rest = argv[1:]

    grep = bounds_for = tap = None
    overflow = None
    if "--grep" in rest:
        grep = rest[rest.index("--grep") + 1]
    if "--bounds" in rest:
        bounds_for = rest[rest.index("--bounds") + 1:]
    if "--tap" in rest:
        tap = rest[rest.index("--tap") + 1]
    if "--overflow" in rest:
        overflow = int(rest[rest.index("--overflow") + 1])

    root = dump(serial)

    if tap:
        for n, t, d in nodes(root):
            if tap in t or tap in d:
                x = box(n.get("bounds"))
                cx, cy = (x[0] + x[2]) // 2, (x[1] + x[3]) // 2
                print(f"  tap {tap!r} @ {cx},{cy}  bounds={n.get('bounds')}")
                adb(serial, "shell", "input", "tap", str(cx), str(cy))
                break
        else:
            sys.exit(f"未找到 {tap!r}")
        return

    if overflow is not None:
        bad = 0
        for n, t, d in nodes(root):
            x = box(n.get("bounds"))
            if x[2] > overflow + 2 or x[0] < -2:
                print(f"  !! 越界 {(t or d)!r} bounds={n.get('bounds')} 屏宽={overflow}")
                bad += 1
        print(f"  越界节点数={bad}")
        return

    if bounds_for:
        want = set(bounds_for)
        for n, t, d in nodes(root):
            if t in want or d in want:
                x = box(n.get("bounds"))
                print(f"  {(t or d)!r:24} w={x[2]-x[0]:4}  h={x[3]-x[1]:4}  bounds={n.get('bounds')}")
        return

    rows = []
    for n, t, d in nodes(root):
        if grep and grep not in t and grep not in d:
            continue
        cls = (n.get("class") or "").rsplit(".", 1)[-1]
        rid = n.get("resource-id") or ""
        rows.append((t, d, cls, rid))

    print(f"-- {serial} 共 {len(rows)} 条文案 --")
    for t, d, cls, rid in rows:
        label = t if t else f"[desc] {d}"
        extra = f"  <{cls}>" if cls else ""
        if rid:
            extra += f" id={rid.rsplit('/', 1)[-1]}"
        print(f"  {label}{extra}")


if __name__ == "__main__":
    main()
