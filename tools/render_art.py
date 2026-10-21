#!/usr/bin/env python3
"""Draw the plugin icon, the Skin picker thumbnail and the README pictures.

Uses the geometry of src/ca/ponderosa/christmas/Lights.java and Snowman.java,
frozen mid-answer with the mouth part open. Plain Python, no imaging library:
shapes are filled with anti-aliased scanlines and the bulb glow is a blurred
copy of the bulbs.

    python3 tools/render_art.py
"""
import math
import struct
import zlib
from pathlib import Path

ASSETS = Path(__file__).resolve().parents[1] / 'assets'
DOCS = Path(__file__).resolve().parents[1] / 'docs'

# Lights.java
MULTI = [(255, 59, 47), (43, 211, 90), (58, 125, 255), (255, 164, 28)]
WARM = (255, 196, 107)
PUPIL = (255, 241, 208)
RUDOLPH = (255, 42, 26)
TOOTH = (255, 226, 168)
CORD = (36, 69, 43)
SOCKET = (27, 51, 32)
MOUTH_U = [-0.8, -0.6, -0.4, -0.2, 0.0, 0.2, 0.4, 0.6, 0.8]


class Image:
    def __init__(self, w, h, color=(0, 0, 0)):
        self.w, self.h = w, h
        self.px = [list(color) for _ in range(w * h)]

    def fill(self, poly, color, alpha=1.0, ss=4):
        """Paints a closed polygon. color is an (r, g, b) or a function of (x, y)."""
        for y, x, c in coverage(poly, self.w, self.h, ss):
            a = c * alpha
            p = self.px[y * self.w + x]
            col = color(x + 0.5, y + 0.5) if callable(color) else color
            for k in range(3):
                p[k] = p[k] * (1 - a) + col[k] * a

    def add(self, mask, color, strength):
        for i, m in enumerate(mask):
            if m > 0.002:
                p = self.px[i]
                for k in range(3):
                    p[k] += color[k] * m * strength

    def png(self, path):
        raw = bytearray()
        for y in range(self.h):
            raw.append(0)
            for x in range(self.w):
                raw += bytes(max(0, min(255, round(v))) for v in self.px[y * self.w + x])

        def chunk(kind, data):
            return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
        data = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', self.w, self.h, 8, 2, 0, 0, 0))
        data += chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b'')
        path.write_bytes(data)
        print(f'{path.name}: {self.w}x{self.h}, {len(data) // 1024} KB')


def coverage(poly, w, h, ss=4):
    """(y, x, fraction) for each pixel the polygon touches, by even-odd scanlines with ss rows per pixel."""
    ys = [p[1] for p in poly]
    y0, y1 = max(0, int(min(ys))), min(h - 1, int(max(ys)) + 1)
    n = len(poly)
    for y in range(y0, y1 + 1):
        acc = {}
        for k in range(ss):
            sy = y + (k + 0.5) / ss
            xs = []
            for i in range(n):
                xa, ya = poly[i]
                xb, yb = poly[(i + 1) % n]
                if (ya <= sy < yb) or (yb <= sy < ya):
                    xs.append(xa + (sy - ya) * (xb - xa) / (yb - ya))
            xs.sort()
            for a, b in zip(xs[0::2], xs[1::2]):
                a, b = max(0.0, a), min(float(w), b)
                for px in range(int(a), min(w, int(math.ceil(b)))):
                    acc[px] = acc.get(px, 0.0) + (min(b, px + 1) - max(a, px)) / ss
        for x, c in acc.items():
            yield y, x, min(1.0, c)


def mask_of(polys, w, h):
    m = [0.0] * (w * h)
    for poly in polys:
        for y, x, c in coverage(poly, w, h):
            m[y * w + x] = min(1.0, m[y * w + x] + c)
    return m


def blur(src, w, h, radius, passes=3):
    """Three box blurs, close to a gaussian."""
    radius = max(1, int(radius))
    cur = src
    for _ in range(passes):
        tmp = [0.0] * (w * h)
        span = 2 * radius + 1
        for y in range(h):
            row = y * w
            acc = sum(cur[row + min(max(i, 0), w - 1)] for i in range(-radius, radius + 1))
            for x in range(w):
                tmp[row + x] = acc / span
                acc += cur[row + min(x + radius + 1, w - 1)] - cur[row + max(x - radius, 0)]
        out = [0.0] * (w * h)
        for x in range(w):
            acc = sum(tmp[min(max(i, 0), h - 1) * w + x] for i in range(-radius, radius + 1))
            for y in range(h):
                out[y * w + x] = acc / span
                acc += tmp[min(y + radius + 1, h - 1) * w + x] - tmp[max(y - radius, 0) * w + x]
        cur = out
    return cur


def ellipse(cx, cy, rx, ry, rot=0.0, n=40):
    c, s = math.cos(rot), math.sin(rot)
    pts = []
    for i in range(n):
        t = 2 * math.pi * i / n
        x, y = rx * math.cos(t), ry * math.sin(t)
        pts.append((cx + x * c - y * s, cy + x * s + y * c))
    return pts


def stroke(points, width):
    """A thick polyline as one quad per segment, plus round-ish joints."""
    polys = []
    for (xa, ya), (xb, yb) in zip(points, points[1:]):
        dx, dy = xb - xa, yb - ya
        d = math.hypot(dx, dy) or 1
        nx, ny = -dy / d * width / 2, dx / d * width / 2
        polys.append([(xa + nx, ya + ny), (xb + nx, yb + ny), (xb - nx, yb - ny), (xa - nx, ya - ny)])
    for x, y in points[1:-1]:
        polys.append(ellipse(x, y, width / 2, width / 2, n=10))
    return polys


def quad(p, c, q, t):
    a = 1 - t
    return (a * a * p[0] + 2 * a * t * c[0] + t * t * q[0], a * a * p[1] + 2 * a * t * c[1] + t * t * q[1])


def quad_tan(p, c, q, t):
    return (2 * (1 - t) * (c[0] - p[0]) + 2 * t * (q[0] - c[0]), 2 * (1 - t) * (c[1] - p[1]) + 2 * t * (q[1] - c[1]))


def curve(p, c, q, n=24):
    return [quad(p, c, q, i / n) for i in range(n + 1)]


def lights(img, cx, cy0, size, open_=0.45, warm=False):
    """Lights.java at rest mid-answer: no lean, no thinking pose, eyes open."""
    w, h = img.w, img.h
    r = size * 0.46
    cy = cy0 + 0.05 * r
    b = r * 0.05
    bulbs, wires = [], []

    def bulb(x, y, dx, dy):
        d = math.hypot(dx, dy) or 1
        bulbs.append((x, y, dx / d, dy / d))

    ey, er = cy - 0.18 * r, 0.16 * r
    reach = max(2.8 * r, w / 2 + 0.3 * r)
    for side in (-1, 1):
        ex = cx + side * 0.36 * r
        p, q = (ex + side * er, ey), (cx + side * reach, ey - 0.9 * r)
        c = ((p[0] + q[0]) / 2, max(p[1], q[1]) + 0.3 * r)
        wires.append(curve(p, c, q, 40))
        for t in ((0.85, 0.6, 0.35) if side < 0 else (0.35, 0.6, 0.85)):
            x, y = quad(p, c, q, t)
            tx, ty = quad_tan(p, c, q, t)
            bulb(x, y, -ty * side, tx * side)
        a = side * 0.15
        bx0, by0 = ex, ey - er - 0.16 * r
        ca, sa, ln = math.cos(a), math.sin(a), 0.13 * r
        wires.append(curve((bx0 - ca * ln * 1.4, by0 - sa * ln * 1.4), (bx0, by0 + 0.02 * r), (bx0 + ca * ln * 1.4, by0 + sa * ln * 1.4)))
        for s in (-1, -1 / 3, 1 / 3, 1):
            bulb(bx0 + ca * ln * s, by0 + sa * ln * s + 0.01 * r * (1 - s * s), sa, -ca)
        wires.append(ellipse(ex, ey, er, er, n=48) + [(ex + er, ey)])
        for i in range(9):
            th = -math.pi / 2 - side * i * 2 * math.pi / 9
            bulb(ex + er * math.cos(th), ey + er * math.sin(th), math.cos(th), math.sin(th))
    exl, exr = cx - 0.36 * r, cx + 0.36 * r
    p, c, q = (exl + er, ey), (cx, cy + 0.02 * r), (exr - er, ey)
    wires.append(curve(p, c, q))
    top = quad(p, c, q, 0.5)
    nose = (cx, cy + 0.08 * r)
    wires.append([top, (nose[0], nose[1] - 0.07 * r)])

    half, base = 0.5 * r, cy + 0.3 * r
    upper = lambda u: base + 0.1 * r * (1 - u * u)
    lower = lambda u: upper(u) + (0.05 * r + 0.3 * r * open_) * math.sqrt(max(0.0, 1 - u * u))
    wires.append([(cx + i / 20 * half, upper(i / 20)) for i in range(-20, 21)])
    wires.append([(cx + i / 20 * half, lower(i / 20)) for i in range(-20, 21)])
    bulb(cx - half - 0.01 * r, upper(-1), -1, 0)
    for u in MOUTH_U:
        bulb(cx + u * half, upper(u), (upper(u + 0.01) - upper(u - 0.01)) / (0.02 * half), -1)
    bulb(cx + half + 0.01 * r, upper(1), 1, 0)
    for u in reversed(MOUTH_U):
        bulb(cx + u * half, lower(u), -(lower(u + 0.01) - lower(u - 0.01)) / (0.02 * half), 1)

    color = lambda i: WARM if warm else MULTI[i % 4]
    glass = [ellipse(x + dx * 0.95 * b, y + dy * 0.95 * b, 0.66 * b, 0.42 * b, math.atan2(dy, dx), 16) for x, y, dx, dy in bulbs]
    pupils = [(cx + side * 0.36 * r, ey, 0.06 * r) for side in (-1, 1)]
    teeth = [(cx + u * half, upper(u)) for u in (-0.11, 0.11)]

    # The glow: each colour's bulbs blurred twice, wide and tight, and added on.
    groups = {}
    for i, g in enumerate(glass):
        groups.setdefault(color(i), []).append(ellipse(bulbs[i][0] + bulbs[i][2] * 0.95 * b, bulbs[i][1] + bulbs[i][3] * 0.95 * b, 0.8 * b, 0.8 * b, n=16))
    groups.setdefault(PUPIL, []).extend(ellipse(x, y, rr, rr, n=24) for x, y, rr in pupils)
    groups.setdefault(RUDOLPH, []).append(ellipse(nose[0], nose[1], 0.075 * r, 0.075 * r, n=24))
    groups.setdefault(TOOTH, []).extend(ellipse(x, y + b, 0.5 * b, 0.75 * b, n=16) for x, y in teeth)
    for col, polys in groups.items():
        m = mask_of(polys, w, h)
        img.add(blur(m, w, h, b * 1.6), col, 1.6)
        img.add(blur(m, w, h, b * 0.5), col, 0.9)

    for line in wires:
        for poly in stroke(line, max(1.0, r * 0.014)):
            img.fill(poly, CORD)
    for i, (x, y, dx, dy) in enumerate(bulbs):
        col = color(i)
        for poly in stroke([(x, y), (x + dx * 0.4 * b, y + dy * 0.4 * b)], b * 0.45):
            img.fill(poly, SOCKET)
        img.fill(glass[i], [v * 0.95 for v in col])
        hx, hy = x + dx * 0.83 * b, y + dy * 0.83 * b
        img.fill(ellipse(hx, hy, 0.32 * b, 0.18 * b, math.atan2(dy, dx), 12), [c + (255 - c) * 0.7 for c in col], 0.8)
    for x, y in teeth:
        for poly in stroke([(x, y), (x, y + 0.4 * b)], b * 0.5):
            img.fill(poly, SOCKET)
        img.fill(ellipse(x, y + 1.05 * b, 0.5 * b, 0.75 * b, n=16), [c * 0.88 for c in TOOTH])
        img.fill(ellipse(x, y + 0.95 * b, 0.24 * b, 0.4 * b, n=12), (255, 255, 255), 0.75)
    img.fill([(nose[0] - 0.03 * r, nose[1] - 0.105 * r), (nose[0] + 0.03 * r, nose[1] - 0.105 * r),
              (nose[0] + 0.03 * r, nose[1] - 0.065 * r), (nose[0] - 0.03 * r, nose[1] - 0.065 * r)], SOCKET)
    for x, y, rr, col in [(px, py, pr, PUPIL) for px, py, pr in pupils] + [(nose[0], nose[1], 0.075 * r, RUDOLPH)]:
        hot = [c + (255 - c) * 0.55 for c in col]

        def shade(sx, sy, x=x, y=y, rr=rr, col=col, hot=hot):
            t = min(1.0, math.hypot(sx - (x - 0.3 * rr), sy - (y - 0.3 * rr)) / (1.3 * rr))
            return [a + (c - a) * t for a, c in zip(hot, col)]
        img.fill(ellipse(x, y, rr, rr, n=32), shade)


def snowman(img, cx, cy0, size, open_=0.5):
    """Snowman.java at rest mid-answer, on the Snowy night sky, with a fixed scatter of snow."""
    w, h = img.w, img.h
    r = size * 0.325
    cy = cy0 + 0.25 * r
    for y in range(h):
        t = y / (h - 1)
        col = [a + (b - a) * t for a, b in zip((5, 11, 28), (22, 41, 74))]
        for x in range(w):
            img.px[y * w + x] = list(col)
    mx_, my_ = cx, cy - 0.2 * r
    for y in range(h):
        for x in range(w):
            a = max(0.0, 1 - math.hypot(x - mx_, y - my_) / (2.4 * r)) * 0.22
            p = img.px[y * w + x]
            for k, c in enumerate((150, 185, 255)):
                p[k] = p[k] * (1 - a) + c * a

    def seed(i):
        v = math.sin(i * 12.9898 + 4.1) * 43758.5453
        return v - math.floor(v)
    flakes = [(seed(i * 3 + 1) * w, seed(i * 3 + 2) * h, size * (0.004 + 0.008 * seed(i * 3 + 3))) for i in range(70)]
    for i, (x, y, s) in enumerate(flakes):
        if i % 2:
            img.fill(ellipse(x, y, s * 0.8, s * 0.8, n=10), (255, 255, 255), 0.85)

    def head(x, y):
        t = min(1.0, math.hypot(x - (cx - 0.25 * r), y - (cy - 0.3 * r)) / (1.35 * r))
        stops = [(0, (255, 255, 255)), (0.5, (238, 244, 251)), (0.85, (201, 214, 232)), (1, (157, 176, 203))]
        for (p0, c0), (p1, c1) in zip(stops, stops[1:]):
            if t <= p1:
                f = (t - p0) / (p1 - p0)
                return [a + (b - a) * f for a, b in zip(c0, c1)]
        return list(stops[-1][1])
    img.fill(ellipse(cx, cy, r, r, n=96), head)

    knobs = [1, 0.86, 1.05, 0.92, 1.1, 0.95, 0.88, 1.04, 0.97]
    coal = (24, 24, 30)

    def lump(x, y, rad, k):
        img.fill([(x + math.cos(i / 9 * 2 * math.pi + k) * rad * knobs[(i + k * 3) % 9] * 1.08,
                   y + math.sin(i / 9 * 2 * math.pi + k) * rad * knobs[(i + k * 3) % 9] * 1.08) for i in range(9)], coal)
    for side in (-1, 1):
        ex, ey, er = cx + side * 0.3 * r, cy - 0.25 * r, 0.085 * r
        lump(ex, ey, er, 2 if side == 1 else 5)
        img.fill(ellipse(ex - 0.3 * er, ey - 0.35 * er, 0.22 * er, 0.22 * er, n=12), (255, 255, 255), 0.55)

    half, base, depth = 0.4 * r, cy + 0.36 * r, 0.13 * r
    lip = lambda u, low: base + depth * (1 - u * u) + (open_ * 0.17 * r if low else -open_ * 0.04 * r) * math.sqrt(max(0.0, 1 - u * u))
    mouth = [(cx + i / 20 * half, lip(i / 20, False)) for i in range(-20, 21)] + [(cx + i / 20 * half, lip(i / 20, True)) for i in range(20, -21, -1)]
    img.fill(mouth, (14, 14, 19))
    for i in range(7):
        u = -1 + i / 3
        lump(cx + u * half, lip(u, i % 2 == 1), 0.04 * r * (1 - 0.25 * abs(u)), i)

    # The carrot, pointing right and a little down.
    ang, ln, wd = 0.35, 0.45 * r, 0.075 * r
    nx, ny = cx, cy + 0.03 * r
    ca, sa = math.cos(ang), math.sin(ang)
    local = curve((0, -wd), (ln * 0.5, -wd * 0.6), (ln, 0.02 * r), 16)[:-1] + curve((ln, 0.02 * r), (ln * 0.5, wd * 0.75), (0, wd), 16)[:-1] + curve((0, wd), (-wd * 0.5, 0), (0, -wd), 8)[:-1]

    def carrot(x, y):
        ly = -(x - nx) * sa + (y - ny) * ca
        t = max(0.0, min(1.0, (ly + wd) / (2 * wd)))
        return [a + (b - a) * t for a, b in zip((255, 173, 74), (185, 80, 10))] if t > 0.5 else [a + (b - a) * t * 2 for a, b in zip((255, 173, 74), (240, 122, 23))]
    img.fill([(nx + x * ca - y * sa, ny + x * sa + y * ca) for x, y in local], carrot)

    # Scarf.
    red, green = (194, 29, 44), (46, 125, 79)
    band = curve((cx - 0.8 * r, cy + 0.6 * r), (cx, cy + 0.95 * r), (cx + 0.8 * r, cy + 0.6 * r)) + curve((cx + 0.84 * r, cy + 0.86 * r), (cx, cy + 1.22 * r), (cx - 0.84 * r, cy + 0.86 * r))
    tail = [(cx + 0.36 * r, cy + 0.92 * r), (cx + 0.58 * r, cy + 0.88 * r), (cx + 0.66 * r, cy + 1.55 * r), (cx + 0.44 * r, cy + 1.57 * r)]
    img.fill(tail, red)
    for y0 in (1.2, 1.38):
        img.fill([(cx + 0.47 * r, cy + (y0 - 0.02) * r), (cx + 0.62 * r, cy + (y0 - 0.025) * r), (cx + 0.625 * r, cy + (y0 + 0.02) * r), (cx + 0.475 * r, cy + (y0 + 0.025) * r)], green)
    img.fill(band, red)
    stripe = curve((cx - 0.82 * r, cy + 0.73 * r), (cx, cy + 1.1 * r), (cx + 0.82 * r, cy + 0.73 * r))
    for poly in stroke(stripe, 0.045 * r):
        img.fill(poly, green)

    # Top hat, tipped back, with a holly sprig in its band.
    hx, hy, rot = cx + 0.08 * r, cy - 0.8 * r, -0.14
    cr, sr = math.cos(rot), math.sin(rot)
    T = lambda pts: [(hx + x * cr - y * sr, hy + x * sr + y * cr) for x, y in pts]
    crown = [(-0.3 * r, 0), (-0.27 * r, -0.66 * r)] + curve((-0.27 * r, -0.66 * r), (0, -0.7 * r), (0.27 * r, -0.66 * r), 12)[1:] + [(0.3 * r, 0)]

    def felt(x, y):
        lx = (x - hx) * cr + (y - hy) * sr
        t = max(0.0, min(1.0, (lx + 0.3 * r) / (0.6 * r)))
        return [a + (b - a) * min(1.0, t / 0.4) for a, b in zip((58, 58, 70), (23, 23, 29))] if t < 0.4 else [a + (b - a) * (t - 0.4) / 0.6 for a, b in zip((23, 23, 29), (11, 11, 15))]
    img.fill(T(crown), felt)
    img.fill(T([(-0.295 * r, -0.15 * r), (0.295 * r, -0.15 * r), (0.299 * r, -0.03 * r), (-0.299 * r, -0.03 * r)]), (176, 20, 42))
    for a in (-0.6, 0.5):
        lx, ly = 0.14 * r + math.cos(a) * 0.05 * r, -0.1 * r + math.sin(a) * 0.03 * r
        img.fill(T(ellipse(lx, ly, 0.07 * r, 0.028 * r, a, 16)), (47, 125, 58))
    for x, y in ((0.1, -0.09), (0.135, -0.065), (0.15, -0.105)):
        img.fill(T(ellipse(x * r, y * r, 0.022 * r, 0.022 * r, n=12)), (227, 38, 58))
    img.fill(T(ellipse(0, 0, 0.55 * r, 0.085 * r, n=48)), (17, 17, 23))

    for i, (x, y, s) in enumerate(flakes):
        if i % 2 == 0:
            img.fill(ellipse(x, y, s * 1.2, s * 1.2, n=10), (255, 255, 255), 0.85)


def both(w, h, lights_at, snowman_at):
    """Both faces side by side, lights on the left and the snowman on the right, fading into each other in the middle."""
    left, right = Image(w, h), Image(w, h)
    lights(left, *lights_at)
    snowman(right, *snowman_at)
    out = Image(w, h)
    fade = w * 0.05
    for y in range(h):
        for x in range(w):
            t = max(0.0, min(1.0, (x - (w / 2 - fade / 2)) / fade))
            a, b = left.px[y * w + x], right.px[y * w + x]
            out.px[y * w + x] = [p * (1 - t) + q * t for p, q in zip(a, b)]
    return out


if __name__ == '__main__':
    ASSETS.mkdir(exist_ok=True)
    DOCS.mkdir(exist_ok=True)
    # The thumbnail and icon show both faces: the icon is what Community Plugins lists.
    both(640, 400, (165, 190, 420), (482, 178, 340)).png(ASSETS / 'voice-skin.png')
    both(256, 256, (66, 118, 220), (192, 116, 215)).png(ASSETS / 'icon.png')
    # README only, so they stay out of the plugin package.
    single = Image(640, 400)
    lights(single, 320, 175, 370)
    single.png(DOCS / 'lights.png')
    warm = Image(640, 400)
    lights(warm, 320, 175, 370, warm=True)
    warm.png(DOCS / 'warm-white.png')
    snow = Image(640, 400)
    snowman(snow, 320, 165, 330)
    snow.png(DOCS / 'snowman.png')
