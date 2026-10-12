#!/usr/bin/env python3
"""量化检查 launcher 图标在真机遮罩下的实际渲染（模型看不了图，用像素说话）。

为什么要有这个脚本：
    非自适应图标（在 108 画布里自己画一个圆）遇到圆角方形遮罩时，圆外那圈会
    露出启动器垫的底色（常见是白 / 浅灰）。人眼一眼可见，但模型读不了截图，
    所以把「图标方框四角到底是什么颜色」变成数值：改图标前后各跑一次即可对比。

用法:
    python scripts/icon-probe.py <serial>                  # 桌面找「爱投屏」图标并采样
    python scripts/icon-probe.py <serial> --name 设置        # 指定图标的 content-desc
    python scripts/icon-probe.py <serial> --png shot.png    # 复用已有截图，不再重新截

判读:
    corners = 图标方框四角（内缩 4px）。若它们明显偏亮（亮度 > 200）且和
    wallpaper 基准色不同，就是「圆外露底」——应改成 adaptive icon，让背景层
    铺满 108dp 画布，遮罩裁成任何形状都不会露出垫底色。

依赖: Pillow（装在 ~/.workbuddy/binaries/python/envs/default）
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from PIL import Image

ADB = os.environ.get("ADB") or os.path.join(
    os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe"
)


def adb(serial, *args):
    return subprocess.run(
        [ADB, "-s", serial, *args],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
    ).stdout


def png_bytes(serial):
    raw = subprocess.run(
        [ADB, "-s", serial, "exec-out", "screencap", "-p"],
        capture_output=True, timeout=30,
    ).stdout
    i = raw.find(b"\x89PNG")
    if i < 0:
        sys.exit(f"{serial}: 没找到 PNG 签名（设备可能吐了壳日志），前 80 字节 {raw[:80]!r}")
    return raw[i:]


def icon_bounds(serial, name):
    """桌面图标的 ImageView 节点；拿不到就退回同名任意节点"""
    remote = f"/sdcard/.aicast-icon-{os.getpid()}.xml"
    for _ in range(3):
        adb(serial, "shell", "uiautomator", "dump", remote)
        xml = adb(serial, "shell", "cat", remote)
        if "<hierarchy" not in xml:
            continue
        adb(serial, "shell", "rm", remote)
        root = ET.fromstring(xml[xml.index("<hierarchy"):])
        fallback = None
        for n in root.iter("node"):
            d = (n.get("content-desc") or "").strip()
            if d != name:
                continue
            b = [int(v) for v in re.findall(r"-?\d+", n.get("bounds") or "[0,0][0,0]")]
            if "ImageView" in (n.get("class") or ""):
                return b
            if fallback is None and b[2] > b[0]:
                fallback = b
        if fallback:
            return fallback
    sys.exit(f"{serial}: 桌面上没找到 {name!r}（先回主屏，或确认图标有 content-desc）")


def lum(rgb):
    return round(0.299 * rgb[0] + 0.587 * rgb[1] + 0.114 * rgb[2])


def main():
    argv = sys.argv[1:]
    if not argv:
        sys.exit(__doc__)
    serial = argv[0]
    name = "爱投屏"
    png_path = None
    if "--name" in argv:
        name = argv[argv.index("--name") + 1]
    if "--png" in argv:
        png_path = argv[argv.index("--png") + 1]
    if "--home" in argv:
        adb(serial, "shell", "input", "keyevent", "3")

    box = icon_bounds(serial, name)
    x0, y0, x1, y1 = box
    cx, cy = (x0 + x1) // 2, (y0 + y1) // 2

    tmp = None
    if png_path:
        path = png_path
    else:
        tmp = os.path.join(os.environ.get("TEMP", "/tmp"), f".icon-probe-{os.getpid()}.png")
        open(tmp, "wb").write(png_bytes(serial))
        path = tmp
    im = Image.open(path).convert("RGB")
    if tmp:
        os.unlink(tmp)

    def px(x, y):
        return im.getpixel((max(0, min(x, im.width - 1)), max(0, min(y, im.height - 1))))

    print(f"-- {serial} 图标 {name!r} bounds=[{x0},{y0}][{x1},{y1}] {x1-x0}x{y1-y0} --")
    print(f"  wallpaper  {px(x0 - 24, y0 - 24)}  亮度 {lum(px(x0 - 24, y0 - 24))}   (图标外基准)")
    probes = [
        ("corner 左上", x0 + 4, y0 + 4),
        ("corner 右上", x1 - 5, y0 + 4),
        ("corner 左下", x0 + 4, y1 - 5),
        ("corner 右下", x1 - 5, y1 - 5),
        ("edge   上", cx, y0 + 4),
        ("edge   下", cx, y1 - 5),
        ("edge   左", x0 + 4, cy),
        ("edge   右", x1 - 5, cy),
        ("center   ", cx, cy),
    ]
    for label, x, y in probes:
        c = px(x, y)
        print(f"  {label}  {str(c):18} 亮度 {lum(c):3}")

    corners = [lum(px(x, y)) for _, x, y in probes[:4]]
    wall = lum(px(x0 - 24, y0 - 24))
    bright = [v for v in corners if v > 200]
    verdict = "露底（四角偏亮且与壁纸不同）" if bright and abs(max(corners) - wall) > 40 else "无露底（四角与图标同色系）"
    print(f"  四角亮度 {corners}  壁纸亮度 {wall}  ->  {verdict}")

    if "--scan" in argv:
        for label, pts in (
            ("横扫（左→右）", [(x, cy) for x in range(x0, x1)]),
            ("竖扫（上→下）", [(cx, y) for y in range(y0, y1)]),
        ):
            print(f"  {label}")
            seg_start, seg_color = 0, px(*pts[0])
            for i, (x, y) in enumerate(pts):
                c = px(x, y)
                if max(abs(a - b) for a, b in zip(c, seg_color)) > 24:
                    print(f"    {seg_start:3}-{i-1:3}px  {str(seg_color):18} 亮度 {lum(seg_color):3}")
                    seg_start, seg_color = i, c
            print(f"    {seg_start:3}-{len(pts)-1:3}px  {str(seg_color):18} 亮度 {lum(seg_color):3}")


if __name__ == "__main__":
    main()
