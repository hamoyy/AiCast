#!/usr/bin/env python3
"""电视/手机实拍截图 helper。

为什么不用 `adb exec-out screencap -p > f.png` 直接重定向：
  · 小米电视（MiTV-MFTP0，Android 9）的 adb shell 会在真正输出前吐一行
    外壳日志 "Init wrapper sys mutex successfu..."（实测 45 字节），
    混进 stdout 就让 PNG 头被顶掉，文件看着有 200KB 但打不开。
  · 另外 Git Bash 重定向会把 \n 换成 \r\n，二进制同样会被破坏。

用法: python scripts/cap.py <serial 或 ip:5555> <输出文件.png>
"""
import subprocess
import sys

ADB = r"C:\Users\wapse\AppData\Local\Android\Sdk\platform-tools\adb.exe"


def cap(serial: str, out: str) -> None:
    raw = subprocess.run(
        [ADB, "-s", serial, "exec-out", "screencap", "-p"],
        capture_output=True, timeout=30,
    ).stdout
    i = raw.find(b"\x89PNG")
    if i < 0:
        raise SystemExit(f"没找到 PNG 签名，前 80 字节: {raw[:80]!r}")
    if i:
        print(f"跳过 {i} 字节设备前缀日志")
    open(out, "wb").write(raw[i:])
    print(f"{out} <- {len(raw) - i} 字节")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    cap(sys.argv[1], sys.argv[2])
