#!/usr/bin/env python3
"""
生成 API 26 以下的启动器图标位图（mipmap-<dpi>/ic_launcher.png）。

为什么需要这个脚本：
  自适应图标（mipmap-anydpi-v26/ic_launcher.xml）是 API 26（Android 8.0）才引入的。
  minSdk 是 24，所以 Android 7.x 必须有一份普通位图兜底，否则 @mipmap/ic_launcher
  在 API 24/25 上解析不到任何资源，系统会退回默认的安卓机器人图标。

  几何与 drawable/ic_launcher.xml（TV banner）保持同源：108x108 viewBox。
  改设计时两边一起改，然后重跑本脚本。

用法： python3 scripts/gen-launcher-icons.py
"""
from PIL import Image, ImageDraw
import pathlib

BG = (16, 20, 24, 255)      # #101418
ACC = (76, 194, 165, 255)   # #4CC2A5
SS = 4                      # 超采样倍数（PIL 没有抗锯齿，靠放大绘制再缩小）

# Pillow >= 9.1 用 Image.Resampling.LANCZOS，旧版只有 Image.LANCZOS
RESAMPLE = getattr(getattr(Image, "Resampling", Image), "LANCZOS")

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def render(px: int) -> Image.Image:
    w = px * SS
    k = w / 108.0
    img = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # 1) 深色圆 r=52 @ (54,54)
    d.ellipse([(54 - 52) * k, (54 - 52) * k, (54 + 52) * k, (54 + 52) * k], fill=BG)
    # 2) 青绿屏幕：圆角矩形 (26,42)-(82,74)，圆角 4
    d.rounded_rectangle([26 * k, 42 * k, 82 * k, 74 * k], radius=4 * k, fill=ACC)
    # 3) 两条内容线
    d.rectangle([40 * k, 52 * k, 68 * k - 1, 56 * k - 1], fill=BG)
    d.rectangle([40 * k, 60 * k, 58 * k - 1, 64 * k - 1], fill=BG)
    return img.resize((px, px), RESAMPLE)


def main() -> None:
    base = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/res"
    for dens, px in DENSITIES.items():
        out = base / f"mipmap-{dens}"
        out.mkdir(parents=True, exist_ok=True)
        p = out / "ic_launcher.png"
        render(px).save(p, "PNG", optimize=True)
        print(f"{p.relative_to(base.parent.parent.parent)}  {px}x{px}  {p.stat().st_size}B")


if __name__ == "__main__":
    main()
