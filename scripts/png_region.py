import sys
sys.path.insert(0, '.')
from importlib.machinery import SourceFileLoader
m = SourceFileLoader('pb', 'scripts/png_bands.py').load_module()
w, h, bpp, px = m.read_png(sys.argv[1])
for spec in sys.argv[2:]:
    x0, y0, x1, y1 = [int(v) for v in spec.split(',')]
    mx = 0; tot = 0; cnt = 0
    for y in range(y0, min(y1, h)):
        base = y * w * bpp
        for x in range(x0, min(x1, w)):
            o = base + x * bpp
            v = (px[o]*299 + px[o+1]*587 + px[o+2]*114)//1000
            tot += v; cnt += 1
            if v > mx: mx = v
    print('  (%4d,%4d)-(%4d,%4d)  mean=%3d max=%3d' % (x0, y0, x1, y1, tot//max(cnt,1), mx))
