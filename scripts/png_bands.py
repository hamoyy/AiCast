"""纯 python 读 PNG（screencap 输出 RGBA/8bit 非隔行），按横向条带统计亮度。
用法: png_bands.py <file> [条带数]
"""
import sys, zlib, struct


def read_png(path):
    d = open(path, 'rb').read()
    assert d[:8] == b'\x89PNG\r\n\x1a\n', 'not png (head=%r)' % d[:8]
    pos, idat, w, h, bd, ct = 8, b'', 0, 0, 0, 0
    while pos < len(d):
        ln = struct.unpack('>I', d[pos:pos + 4])[0]
        typ = d[pos + 4:pos + 8]
        body = d[pos + 8:pos + 8 + ln]
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', body[:10])
        elif typ == b'IDAT':
            idat += body
        elif typ == b'IEND':
            break
        pos += 12 + ln
    assert bd == 8 and ct in (2, 6), 'bitdepth=%d colortype=%d' % (bd, ct)
    bpp = 4 if ct == 6 else 3
    raw = zlib.decompress(idat)
    stride = w * bpp
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]; p += 1
        line = bytearray(raw[p:p + stride]); p += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, bpp, bytes(out)


def bands(path, n=32):
    w, h, bpp, px = read_png(path)
    print('%s  %dx%d bpp=%d' % (path, w, h, bpp))
    bh = h // n
    for i in range(n):
        y0, y1 = i * bh, (i + 1) * bh if i < n - 1 else h
        tot = cnt = 0
        for y in range(y0, y1, max(1, bh // 6)):
            base = y * w * bpp
            for x in range(0, w, max(1, w // 24)):
                o = base + x * bpp
                tot += (px[o] * 299 + px[o + 1] * 587 + px[o + 2] * 114) // 1000
                cnt += 1
        v = tot // max(cnt, 1)
        bar = '#' * (v * 40 // 255)
        print('  y%4d-%4d  lum=%3d %s' % (y0, y1, v, bar))


if __name__ == '__main__':
    bands(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 32)
