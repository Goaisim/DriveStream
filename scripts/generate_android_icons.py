#!/usr/bin/env python3
"""Generate Android TV launcher icon (512x512) and 16:9 TV banner (320x180)."""

import math
import os
import struct
import zlib


def clamp(v: float, lo: float = 0.0, hi: float = 1.0) -> float:
    return lo if v < lo else (hi if v > hi else v)


def smoothstep(edge0: float, edge1: float, x: float) -> float:
    t = clamp((x - edge0) / (edge1 - edge0))
    return t * t * (3.0 - 2.0 * t)


def sd_rounded_box(px: float, py: float, bx: float, by: float, r: float) -> float:
    qx = abs(px) - bx + r
    qy = abs(py) - by + r
    return math.hypot(max(qx, 0.0), max(qy, 0.0)) + min(max(qx, qy), 0.0) - r


def sd_triangle(px: float, py: float, ax: float, ay: float, bx: float, by: float, cx: float, cy: float) -> float:
    def edge(x0: float, y0: float, x1: float, y1: float) -> tuple[float, float]:
        ex, ey = x1 - x0, y1 - y0
        wx, wy = px - x0, py - y0
        denom = ex * ex + ey * ey
        t = clamp((wx * ex + wy * ey) / denom) if denom > 0 else 0.0
        dx, dy = wx - ex * t, wy - ey * t
        return dx * dx + dy * dy, ex * wy - ey * wx

    d0, c0 = edge(ax, ay, bx, by)
    d1, c1 = edge(bx, by, cx, cy)
    d2, c2 = edge(cx, cy, ax, ay)
    d = math.sqrt(min(d0, d1, d2))
    inside = (c0 >= 0 and c1 >= 0 and c2 >= 0) or (c0 <= 0 and c1 <= 0 and c2 <= 0)
    return -d if inside else d


def sd_cloud(px: float, py: float) -> float:
    d1 = math.hypot(px - 0.0, py + 0.04) - 0.23
    d2 = math.hypot(px + 0.19, py - 0.04) - 0.16
    d3 = math.hypot(px - 0.19, py - 0.04) - 0.17
    d4 = sd_rounded_box(px, py - 0.09, 0.25, 0.11, 0.10)
    return min(d1, d2, d3, d4)


def render_emblem_pixel(nx: float, ny: float, ex: float, ey: float, scale: float, film_holes: bool) -> tuple[int, int, int]:
    diag = clamp((nx + ny + 2.0) * 0.25)
    r = 12.0 + 36.0 * diag
    g = 10.0 + 18.0 * diag
    b = 28.0 + 74.0 * diag

    ex_s = (nx - ex) / scale
    ey_s = (ny - ey) / scale

    radial = math.exp(-(ex_s * ex_s + ey_s * ey_s) * 2.4)
    r += 58.0 * radial
    g += 34.0 * radial
    b += 135.0 * radial

    if film_holes:
        for fx in (-0.64, -0.32, 0.0, 0.32, 0.64):
            for fy in (-0.78, 0.78):
                d_hole = sd_rounded_box(nx - fx, ny - fy, 0.065, 0.042, 0.015)
                alpha_hole = 1.0 - smoothstep(-0.01, 0.01, d_hole)
                if alpha_hole > 0:
                    r = r * (1.0 - 0.35 * alpha_hole) + 120.0 * 0.35 * alpha_hole
                    g = g * (1.0 - 0.35 * alpha_hole) + 115.0 * 0.35 * alpha_hole
                    b = b * (1.0 - 0.35 * alpha_hole) + 210.0 * 0.35 * alpha_hole

    d_cloud = sd_cloud(ex_s, ey_s)
    cloud_glow = math.exp(-max(d_cloud, 0.0) * 9.0) * 0.45
    r += 80.0 * cloud_glow
    g += 65.0 * cloud_glow
    b += 170.0 * cloud_glow

    cloud_fill = 1.0 - smoothstep(-0.012, 0.012, d_cloud)
    if cloud_fill > 0:
        cr = 62.0 + 35.0 * (1.0 - ey_s)
        cg = 52.0 + 35.0 * (1.0 - ey_s)
        cb = 135.0 + 65.0 * (1.0 - ey_s)
        r = r * (1.0 - 0.62 * cloud_fill) + cr * 0.62 * cloud_fill
        g = g * (1.0 - 0.62 * cloud_fill) + cg * 0.62 * cloud_fill
        b = b * (1.0 - 0.62 * cloud_fill) + cb * 0.62 * cloud_fill

    d_tri = sd_triangle(ex_s, ey_s, -0.10, -0.17, -0.10, 0.19, 0.21, 0.01) - 0.02
    tri_glow = math.exp(-max(d_tri, 0.0) * 14.0) * 0.55
    r += 110.0 * tri_glow
    g += 130.0 * tri_glow
    b += 255.0 * tri_glow

    tri_fill = 1.0 - smoothstep(-0.010, 0.010, d_tri)
    if tri_fill > 0:
        r = r * (1.0 - tri_fill) + 245.0 * tri_fill
        g = g * (1.0 - tri_fill) + 247.0 * tri_fill
        b = b * (1.0 - tri_fill) + 255.0 * tri_fill

    return (int(clamp(r, 0, 255)), int(clamp(g, 0, 255)), int(clamp(b, 0, 255)))


def png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    chunk = chunk_type + data
    return struct.pack(">I", len(data)) + chunk + struct.pack(">I", zlib.crc32(chunk) & 0xFFFFFFFF)


def write_png(path: str, width: int, height: int, rows: bytearray) -> None:
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    idat = zlib.compress(bytes(rows), level=9)
    png_data = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr) + png_chunk(b"IDAT", idat) + png_chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png_data)


def render_image(path: str, width: int, height: int, aspect_adjusted: bool) -> None:
    rows = bytearray()
    for y in range(height):
        rows.append(0)
        ny = (y + 0.5) * 2.0 / height - 1.0
        for x in range(width):
            nx = (x + 0.5) * 2.0 / width - 1.0
            if aspect_adjusted:
                ax = nx * (width / height)
                r, g, b = render_emblem_pixel(ax, ny, 0.0, 0.0, 1.05, film_holes=False)
            else:
                r, g, b = render_emblem_pixel(nx, ny, 0.0, 0.0, 1.0, film_holes=True)
            rows.append(r)
            rows.append(g)
            rows.append(b)
    write_png(path, width, height, rows)


def main() -> None:
    out_dir = os.path.join("android", "app", "src", "main", "res", "drawable-xhdpi")
    os.makedirs(out_dir, exist_ok=True)
    render_image(os.path.join(out_dir, "ic_launcher.png"), 512, 512, aspect_adjusted=False)
    render_image(os.path.join(out_dir, "ic_banner.png"), 320, 180, aspect_adjusted=True)
    print("Generated Android TV icons (ic_launcher.png 512x512, ic_banner.png 320x180).")


if __name__ == "__main__":
    main()
