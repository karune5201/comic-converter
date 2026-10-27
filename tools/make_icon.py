#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「漫画转换器」的启动图标。

没有任何图形库可用（这台机器没装 PIL），所以自己光栅化 + 自己写 PNG。
画面元素：靛蓝渐变底 + 白色漫画页 + 两格画格 + 一个琥珀色对话气泡。

先渲染一张 1536×1536 的主图，再用面积平均降采样到各个密度档，
降采样本身就是抗锯齿，所以小尺寸下边缘也干净。
"""
import os, struct, zlib, math

MASTER = 1536

# 配色（跟 App 的 MD3 色板同一支靛蓝）
BG_A = (0x1E, 0x28, 0x6E)
BG_B = (0x4B, 0x5C, 0xC4)
PAGE = (0xFF, 0xFF, 0xFF)
PANEL = (0x3C, 0x4C, 0xAC)
BUBBLE = (0xFF, 0xC4, 0x3D)


class Img:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.buf = bytearray(w * h * 4)   # RGBA

    def row_bounds(self, y, x0, y0, x1, y1, r):
        """圆角矩形在第 y 行的左右边界"""
        if y < y0 or y > y1:
            return None
        dy = min(y - y0, y1 - y)
        if dy < r:
            dx = r - math.sqrt(max(0.0, r * r - (r - dy) ** 2))
        else:
            dx = 0.0
        a, b = x0 + dx, x1 - dx
        if b <= a:
            return None
        return a, b

    def fill_rrect(self, x0, y0, x1, y1, r, color, alpha=255):
        buf, w = self.buf, self.w
        cr, cg, cb = color
        yy0, yy1 = max(0, int(y0)), min(self.h - 1, int(math.ceil(y1)))
        xx0, xx1 = max(0, int(x0)), min(w - 1, int(math.ceil(x1)))
        for y in range(yy0, yy1 + 1):
            b = self.row_bounds(y + 0.5, x0, y0, x1, y1, r)
            if not b:
                continue
            a, c = max(xx0, int(b[0])), min(xx1, int(b[1]))
            if c < a:
                continue
            base = (y * w + a) * 4
            px = bytes((cr, cg, cb, alpha))
            buf[base:base + (c - a + 1) * 4] = px * (c - a + 1)

    def fill_tri(self, pts, color):
        """填充三角形（用于气泡尾巴），按行扫描"""
        buf, w = self.buf, self.w
        cr, cg, cb = color
        ys = [p[1] for p in pts]
        y0, y1 = max(0, int(min(ys))), min(self.h - 1, int(max(ys)))
        for y in range(y0, y1 + 1):
            xs = []
            yc = y + 0.5
            for i in range(3):
                ax, ay = pts[i]
                bx, by = pts[(i + 1) % 3]
                if (ay <= yc < by) or (by <= yc < ay):
                    t = (yc - ay) / (by - ay)
                    xs.append(ax + t * (bx - ax))
            if len(xs) < 2:
                continue
            a, c = int(min(xs)), int(max(xs))
            a, c = max(0, a), min(w - 1, c)
            if c < a:
                continue
            base = (y * w + a) * 4
            buf[base:base + (c - a + 1) * 4] = bytes((cr, cg, cb, 255)) * (c - a + 1)

    def grad_rrect(self, x0, y0, x1, y1, r, ca, cb):
        buf, w = self.buf, self.w
        span = (x1 - x0) + (y1 - y0)
        for y in range(max(0, int(y0)), min(self.h - 1, int(math.ceil(y1))) + 1):
            b = self.row_bounds(y + 0.5, x0, y0, x1, y1, r)
            if not b:
                continue
            a, c = max(0, int(b[0])), min(w - 1, int(b[1]))
            if c < a:
                continue
            for x in range(a, c + 1):
                t = ((x + 0.5 - x0) + (y + 0.5 - y0)) / span
                t = 0.0 if t < 0 else (1.0 if t > 1 else t)
                o = (y * w + x) * 4
                buf[o] = int(ca[0] + (cb[0] - ca[0]) * t)
                buf[o + 1] = int(ca[1] + (cb[1] - ca[1]) * t)
                buf[o + 2] = int(ca[2] + (cb[2] - ca[2]) * t)
                buf[o + 3] = 255


def render(rounded_bg, motif_scale):
    """渲染一张主图。rounded_bg=False 时背景透明（用于自适应图标的前景层）"""
    S = MASTER
    im = Img(S, S)

    if rounded_bg:
        im.grad_rrect(0, 0, S - 1, S - 1, S * 0.225, BG_A, BG_B)

    # ---- 版面坐标（相对画布 0~1），整体围绕中心缩放 ----
    def sc(v):
        return (v - 0.5) * motif_scale + 0.5

    px0, px1 = sc(0.190), sc(0.810)
    py0, py1 = sc(0.155), sc(0.845)
    pad = 0.078 * (px1 - px0) / 0.62      # 页内边距，随缩放一起变
    gap = 0.050 * (px1 - px0) / 0.62

    ix0, ix1 = px0 + pad, px1 - pad
    iy0, iy1 = py0 + pad, py1 - pad

    # 白色漫画页
    im.fill_rrect(px0 * S, py0 * S, px1 * S, py1 * S, S * 0.038, PAGE)

    # 上面一格：通栏
    inner_h = iy1 - iy0
    top_h = (inner_h - gap) * 0.575
    im.fill_rrect(ix0 * S, iy0 * S, ix1 * S, (iy0 + top_h) * S, S * 0.012, PANEL)

    # 下面两格
    by0 = iy0 + top_h + gap
    half = (ix1 - ix0 - gap) / 2
    im.fill_rrect(ix0 * S, by0 * S, (ix0 + half) * S, iy1 * S, S * 0.012, PANEL)

    # 右下角放对话气泡（代替第四格）
    bx0 = ix0 + half + gap
    bx1 = ix1
    im.fill_rrect(bx0 * S, by0 * S, bx1 * S, iy1 * S, S * 0.030, BUBBLE)
    # 气泡尾巴：指向左下
    # 尾巴尖不能超出页面底边，否则会糊到背景上
    cell_h = iy1 - by0
    tail_h = min(cell_h * 0.34, py1 - iy1 - 0.008)
    tx_a = bx0 + (bx1 - bx0) * 0.30
    tx_b = bx0 + (bx1 - bx0) * 0.60
    im.fill_tri([(tx_a * S, (iy1 - tail_h * 0.20) * S),
                 (tx_b * S, (iy1 - tail_h * 0.20) * S),
                 ((tx_a - (tx_b - tx_a) * 0.55) * S, (iy1 + tail_h) * S)], BUBBLE)
    return im


def downsample(im, size):
    """面积平均降采样（预乘 alpha，避免透明边缘发黑）"""
    src, sw, sh = im.buf, im.w, im.h
    out = bytearray(size * size * 4)
    fx, fy = sw / size, sh / size
    for ty in range(size):
        sy0, sy1 = int(ty * fy), max(int(ty * fy) + 1, int((ty + 1) * fy))
        for tx in range(size):
            sx0, sx1 = int(tx * fx), max(int(tx * fx) + 1, int((tx + 1) * fx))
            r = g = b = a = 0
            n = 0
            for yy in range(sy0, sy1):
                base = yy * sw
                for xx in range(sx0, sx1):
                    o = (base + xx) * 4
                    al = src[o + 3]
                    r += src[o] * al
                    g += src[o + 1] * al
                    b += src[o + 2] * al
                    a += al
                    n += 1
            o = (ty * size + tx) * 4
            if a > 0:
                out[o] = r // a
                out[o + 1] = g // a
                out[o + 2] = b // a
                out[o + 3] = a // n
            else:
                out[o + 3] = 0
    return bytes(out), size, size


def write_png(path, raw, w, h):
    def chunk(tag, data):
        c = tag + data
        return (struct.pack('>I', len(data)) + c
                + struct.pack('>I', zlib.crc32(c) & 0xffffffff))
    rows = b''.join(b'\x00' + raw[y * w * 4:(y + 1) * w * 4] for y in range(h))
    png = (b'\x89PNG\r\n\x1a\n'
           + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
           + chunk(b'IDAT', zlib.compress(rows, 9))
           + chunk(b'IEND', b''))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, 'wb').write(png)
    return len(png)


if __name__ == '__main__':
    base = '/root/comicconv/res'
    print('渲染主图…')
    legacy = render(rounded_bg=True, motif_scale=1.0)
    fg = render(rounded_bg=False, motif_scale=0.90)

    # 传统图标：48 / 72 / 96 / 144 / 192
    for dpi, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96),
                      ('xxhdpi', 144), ('xxxhdpi', 192)]:
        raw, w, h = downsample(legacy, size)
        n = write_png(f'{base}/mipmap-{dpi}/ic_launcher.png', raw, w, h)
        print(f'  mipmap-{dpi}/ic_launcher.png  {size}x{size}  {n} 字节')

    # 自适应图标前景层：108dp
    for dpi, size in [('mdpi', 108), ('hdpi', 162), ('xhdpi', 216),
                      ('xxhdpi', 324), ('xxxhdpi', 432)]:
        raw, w, h = downsample(fg, size)
        n = write_png(f'{base}/mipmap-{dpi}/ic_launcher_foreground.png', raw, w, h)
        print(f'  mipmap-{dpi}/ic_launcher_foreground.png  {size}x{size}  {n} 字节')
    print('完成')
