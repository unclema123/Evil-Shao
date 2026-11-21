#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一个 GLB 软渲染成 PNG（正交前视图 + 简单光照）。

用途：替换模型以后，不靠手机截图也能确认「形状和颜色没被减面搞坏」——
手机上的截图受动画相位、气泡遮挡、主题配色的干扰，不方便做前后对比。

自己写而不是调外部渲染器，是因为要零依赖跑在没装 trimesh / open3d 的机器上：
只要有 numpy + Pillow 就够，三角形数量和顶点颜色都在内存里直接算。
"""
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, r"E:\EvilShao\tools")
from optimize_pig import read_glb  # noqa: E402


def render(path, out, size=(520, 640), yaw=0.35, pitch=0.18, ss=2):
    pos, col, faces, vnrm = read_glb(path)
    # 颜色统一成 RGB（GLB 里是 VEC4）
    rgb = col[:, :3]
    if rgb.max() <= 1.0:
        rgb = rgb * 255.0

    # 居中 + 缩放到画布
    c = (pos.min(axis=0) + pos.max(axis=0)) / 2
    p = pos - c
    s = 1.9 / max(np.abs(p).max(), 1e-6)

    cy, sy = np.cos(yaw), np.sin(yaw)
    cp, sp = np.cos(pitch), np.sin(pitch)
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rx = np.array([[1, 0, 0], [0, cp, -sp], [0, sp, cp]])
    p = (p @ ry.T) @ rx.T

    W, H = size[0] * ss, size[1] * ss
    screen = np.empty((len(p), 2))
    screen[:, 0] = p[:, 0] * s * W * 0.5 + W / 2
    screen[:, 1] = -p[:, 1] * s * W * 0.5 + H / 2
    depth = p[:, 2].copy()

    img = np.zeros((H, W, 3), dtype=np.float32)
    zbuf = np.full((H, W), -1e9, dtype=np.float32)

    # 面法线（用于简单明暗），顶点色取三角面平均
    tri = p[faces]
    if vnrm is not None:
        # 有顶点法线就按「三个顶点法线取平均」当光照法线，接近真实的光滑着色；
        # 没有就用面法线（原模型就是这种，所以看起来一块一块的）
        n = vnrm[faces[:, 0]] + vnrm[faces[:, 1]] + vnrm[faces[:, 2]]
    else:
        n = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
    n /= np.maximum(np.linalg.norm(n, axis=1, keepdims=True), 1e-12)
    light = np.array([0.3, 0.5, 0.8])
    light /= np.linalg.norm(light)
    lam = 0.45 + 0.55 * np.clip(np.abs(n @ light), 0, 1)
    face_rgb = (rgb[faces[:, 0]] + rgb[faces[:, 1]] + rgb[faces[:, 2]]) / 3.0

    S = screen[faces]
    Z = depth[faces]
    for i in range(len(faces)):
        x0, y0 = S[i, 0]
        x1, y1 = S[i, 1]
        x2, y2 = S[i, 2]
        minx = max(int(np.floor(min(x0, x1, x2))), 0)
        maxx = min(int(np.ceil(max(x0, x1, x2))), W - 1)
        miny = max(int(np.floor(min(y0, y1, y2))), 0)
        maxy = min(int(np.ceil(max(y0, y1, y2))), H - 1)
        if minx > maxx or miny > maxy:
            continue
        xs = np.arange(minx, maxx + 1)
        ys = np.arange(miny, maxy + 1)
        gx, gy = np.meshgrid(xs, ys)
        d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if abs(d) < 1e-9:
            continue
        a = ((y1 - y2) * (gx - x2) + (x2 - x1) * (gy - y2)) / d
        b = ((y2 - y0) * (gx - x2) + (x0 - x2) * (gy - y2)) / d
        cch = 1 - a - b
        inside = (a >= -1e-6) & (b >= -1e-6) & (cch >= -1e-6)
        if not inside.any():
            continue
        zz = a * Z[i, 0] + b * Z[i, 1] + cch * Z[i, 2]
        sub = zbuf[miny:maxy + 1, minx:maxx + 1]
        better = inside & (zz > sub)
        if not better.any():
            continue
        sub[better] = zz[better]
        img[miny:maxy + 1, minx:maxx + 1][better] = face_rgb[i] * lam[i]

    filled = zbuf > -1e8
    img[~filled] = [252, 236, 241]
    out_img = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
    out_img = out_img.resize(size, Image.LANCZOS) if ss > 1 else out_img
    out_img.save(out)
    return img, filled


if __name__ == "__main__":
    a, fa = render(r"C:\Users\Public\evilshao\pig-opt.glb", r"C:\Users\Public\evilshao\render-old.png")
    b, fb = render(r"C:\Users\Public\evilshao\pig-vivid.glb", r"C:\Users\Public\evilshao\render-new.png")
    print("像素:", a.shape, " 覆盖像素 旧", int(fa.sum()), "新", int(fb.sum()))
    iou = (fa & fb).sum() / max(1, (fa | fb).sum())
    print(f"轮廓 IoU: {iou:.4f}")
    both = fa & fb
    diff = np.abs(a[both] - b[both]).mean()
    print(f"共同覆盖区域的平均颜色差: {diff:.1f}/255")
    print("已输出 render-old.png / render-new.png")