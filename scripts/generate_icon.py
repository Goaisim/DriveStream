#!/usr/bin/env python3
"""Generate opaque (RGB, no alpha) dark-indigo DriveStream app icons."""

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
    def edge(x0, y0, x1, y1):
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
    # Subtle stylized cloud silhouette centered slightly below middle
    d1 = math.hypot(px - 0.0, py + 0.04) - 0.23
    d2 = math.hypot(px + 0.19, py - 0.04) - 0.16
    d3 = math.hypot(px - 0.19, py - 0.04) - 0.17
    d4 = sd_rounded_box(px, py - 0.09, 0.25, 0.11, 0.10)
    return min(d1, d2, d3, d4)


def render_pixel(nx: float, ny: float) -> tuple[int, int, int]:
    # Normalized coords in [-1, 1]
    # Rich dark-purple/indigo diagonal background gradient
    diag = clamp((nx + ny + 2.0) * 0.25)
    r = 12.0 + 36.0 * diag
    g = 10.0 + 18.0 * diag
    b = 28.0 + 74.0 * diag

    # Radial indigo glow behind emblem
    radial = math.exp(-(nx * nx + ny * ny) * 2.4)
    r += 58.0 * radial
    g += 34.0 * radial
    b += 135.0 * radial

    # Outer filmstrip perforation dots along top & bottom edges
    for fx in (-0.64, -0.32, 0.0, 0.32, 0.64):
        for fy in (-0.78, 0.78):
            d_hole = sd_rounded_box(nx - fx, ny - fy, 0.065, 0.042, 0.015)
            alpha_hole = 1.0 - smoothstep(-0.01, 0.01, d_hole)
            if alpha_hole > 0:
                r = r * (1.0 - 0.35 * alpha_hole) + 120.0 * 0.35 * alpha_hole
                g = g * (1.0 - 0.35 * alpha_hole) + 115.0 * 0.35 * alpha_hole
                b = b * (1.0 - 0.35 * alpha_hole) + 210.0 * 0.35 * alpha_hole

    # Translucent cloud/drive motif
    d_cloud = sd_cloud(nx, ny)
    cloud_glow = math.exp(-max(d_cloud, 0.0) * 9.0) * 0.45
    r += 80.0 * cloud_glow
    g += 65.0 * cloud_glow
    b += 170.0 * cloud_glow

    cloud_fill = 1.0 - smoothstep(-0.012, 0.012, d_cloud)
    if cloud_fill > 0:
        cr = 62.0 + 35.0 * (1.0 - ny)
        cg = 52.0 + 35.0 * (1.0 - ny)
        cb = 135.0 + 65.0 * (1.0 - ny)
        r = r * (1.0 - 0.62 * cloud_fill) + cr * 0.62 * cloud_fill
        g = g * (1.0 - 0.62 * cloud_fill) + cg * 0.62 * cloud_fill
        b = b * (1.0 - 0.62 * cloud_fill) + cb * 0.62 * cloud_fill

    # Glowing play triangle (slightly right-shifted for optical balance)
    d_tri = sd_triangle(nx, ny, -0.10, -0.17, -0.10, 0.19, 0.21, 0.01) - 0.02
    tri_glow = math.exp(-max(d_tri, 0.0) * 14.0) * 0.55
    r += 110.0 * tri_glow
    g += 130.0 * tri_glow
    b += 255.0 * tri_glow

    tri_fill = 1.0 - smoothstep(-0.010, 0.010, d_tri)
    if tri_fill > 0:
        tr, tg, tb = 245.0, 247.0, 255.0
        r = r * (1.0 - tri_fill) + tr * tri_fill
        g = g * (1.0 - tri_fill) + tg * tri_fill
        b = b * (1.0 - tri_fill) + tb * tri_fill

    return (int(clamp(r, 0, 255)), int(clamp(g, 0, 255)), int(clamp(b, 0, 255)))


def png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    chunk = chunk_type + data
    return struct.pack(">I", len(data)) + chunk + struct.pack(">I", zlib.crc32(chunk) & 0xFFFFFFFF)


def write_rgb_png(path: str, size: int, base_pixels: list[bytes]) -> None:
    # Downsample from 1024x1024 (or render directly at size) for crisp anti-aliasing
    base_size = len(base_pixels)
    rows = bytearray()
    if size == base_size:
        for row in base_pixels:
            rows.append(0)
            rows.extend(row)
    else:
        scale = base_size / size
        for y in range(size):
            rows.append(0)
            y0 = int(y * scale)
            y1 = max(y0 + 1, int((y + 1) * scale))
            for x in range(size):
                x0 = int(x * scale)
                x1 = max(x0 + 1, int((x + 1) * scale))
                sr = sg = sb = count = 0
                for sy in range(y0, min(y1, base_size)):
                    row = base_pixels[sy]
                    for sx in range(x0, min(x1, base_size)):
                        idx = sx * 3
                        sr += row[idx]
                        sg += row[idx + 1]
                        sb += row[idx + 2]
                        count += 1
                rows.append(sr // count)
                rows.append(sg // count)
                rows.append(sb // count)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)  # Color type 2 = RGB (opaque, no alpha)
    idat = zlib.compress(bytes(rows), level=9)
    png_data = b"\x89PNG\r\n\x1a\n" + png_chunk(b"IHDR", ihdr) + png_chunk(b"IDAT", idat) + png_chunk(b"IEND", b"")
    with open(path, "wb") as f:
        f.write(png_data)


def main() -> None:
    out_dir = os.path.join("Sources", "Assets.xcassets", "AppIcon.appiconset")
    os.makedirs(out_dir, exist_ok=True)

    master_size = 1024
    inv = 2.0 / master_size
    base_pixels: list[bytes] = []
    for y in range(master_size):
        ny = (y + 0.5) * inv - 1.0
        row = bytearray(master_size * 3)
        for x in range(master_size):
            nx = (x + 0.5) * inv - 1.0
            r, g, b = render_pixel(nx, ny)
            idx = x * 3
            row[idx] = r
            row[idx + 1] = g
            row[idx + 2] = b
        base_pixels.append(bytes(row))

    write_rgb_png(os.path.join(out_dir, "AppIcon.png"), 1024, base_pixels)
    write_rgb_png(os.path.join(out_dir, "AppIcon-180.png"), 180, base_pixels)
    write_rgb_png(os.path.join(out_dir, "AppIcon-120.png"), 120, base_pixels)
    print("Generated opaque RGB app icons (1024, 180, 120).")


if __name__ == "__main__":
    main()
