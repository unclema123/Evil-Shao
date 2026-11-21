#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
雷霆大拖鞋：厚底 + 大红绑带 + 黄闪电。

和上一版运动鞋的区别：
  - 鞋底是厚平台（拖鞋那一大坨），脚整个埋进去，切开的那一圈藏在底里
  - 底比脚大一圈（宽 1.35×、长 1.30×），就是要「大」
  - 上面一条红色宽绑带横跨脚背
  - 外侧贴一枚黄色闪电（薄片几何，不是贴图）

切脚高度 0.06：必须落在厚底的体积里，否则脚踝那圈切口会露在外面。

用法：
    python slipper_pig.py --in pig-detail.glb --out pig-slipper.glb
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from optimize_pig import (  # noqa: E402
    boost_colors, read_glb, transfer_colors, write_glb,
)
from detail_pig import open_edges  # noqa: E402
from shoes_pig import boundary_ring, hole_cap, icosphere  # noqa: E402


# ────────────────────────── 零件 ──────────────────────────

def rounded_slab(hw, hh, hl, boxy=0.35, subdiv=3):
    """方圆厚板：超椭球压扁，当拖鞋大底。"""
    v, f = icosphere(subdiv=subdiv)
    v = np.sign(v) * np.abs(v) ** boxy
    v = v * np.array([hw, hh, hl])
    return v, f


def arch_band(rx, ry, tube, arc=np.pi, segments=26, sides=10):
    """
    横跨脚背的绑带：一段圆环。

    断面必须绕管子的切线转 —— 只把点堆在 XY 平面上会得到一条没厚度的纸带，
    从侧面看就消失了。
    """
    verts, faces = [], []
    for i in range(segments + 1):
        a = -arc / 2 + arc * i / segments
        # 椭圆弧：横向走得远、纵向只抬一点。
        # 用正圆的话半径=半宽，拱顶会顶到肚子上（第一版就是这样）
        px, py = rx * np.sin(a), ry * np.cos(a)
        nx, ny = np.sin(a), np.cos(a)
        for j in range(sides):
            b = 2 * np.pi * j / sides
            verts.append([
                px + tube * np.cos(b) * nx,
                py + tube * np.cos(b) * ny,
                tube * np.sin(b),
            ])
    for i in range(segments):
        for j in range(sides):
            a0 = i * sides + j
            a1 = i * sides + (j + 1) % sides
            b0 = (i + 1) * sides + j
            b1 = (i + 1) * sides + (j + 1) % sides
            faces += [[a0, b0, b1], [a0, b1, a1]]
    return np.array(verts, dtype=np.float64), np.array(faces, dtype=np.int32)


def bolt_decal(x, y0, z0, height, width):
    """
    黄色闪电：一片贴在鞋侧墙上的薄片。

    用「中心线 + 半宽」生成带状网格，比把一个凹多边形三角化稳 ——
    闪电的折角天生是凹的，扇形三角化会自交。
    """
    # 中心线（z 是水平方向，y 是竖直），Z 字形状
    pts = np.array([[0.10, 1.00], [-0.06, 0.56], [0.12, 0.56], [-0.14, 0.02]])
    pts = pts * np.array([width * 4.0, height])
    wid = np.full(len(pts), width)

    d = np.gradient(pts, axis=0)
    d /= np.maximum(np.linalg.norm(d, axis=1, keepdims=True), 1e-9)
    nrm = np.stack([-d[:, 1], d[:, 0]], axis=1)
    left = pts + nrm * wid[:, None] / 2
    right = pts - nrm * wid[:, None] / 2

    verts = []
    for p in np.concatenate([left, right]):
        verts.append([x, y0 + p[1], z0 + p[0]])
    n = len(pts)
    faces = []
    for i in range(n - 1):
        lt, lb = i, i + 1
        rt, rb = n + i, n + i + 1
        faces += [[lt, lb, rb], [lt, rb, rt]]
    return np.array(verts, dtype=np.float64), np.array(faces, dtype=np.int32)


# ────────────────────────── 主流程 ──────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default=r"C:\Users\Public\evilshao\pig-detail.glb")
    ap.add_argument("--out", dest="dst", default=r"C:\Users\Public\evilshao\pig-slipper.glb")
    ap.add_argument("--cut-y", type=float, default=0.20)
    ap.add_argument("--sole-h", type=float, default=0.30, help="鞋底总厚")
    ap.add_argument("--wide", type=float, default=1.35, help="比脚宽多少倍")
    ap.add_argument("--long", type=float, default=1.30, help="比脚长多少倍")
    ap.add_argument("--debug-out", default=r"C:\Users\Public\evilshao\slipper-only.glb",
                    help="只含鞋的调试文件")
    args = ap.parse_args()

    pos, col, faces, _ = read_glb(args.src)
    y0 = pos[:, 1].min()
    cen = pos[faces].mean(axis=1)
    foot = cen[:, 1] < y0 + args.cut_y
    keep = ~foot
    kept_faces = faces[keep]
    print(f"输入 {len(pos):,} 顶点 / {len(faces):,} 面；切脚 {foot.sum():,} 面，剩 {keep.sum():,}")

    ring = boundary_ring(pos, kept_faces)
    print(f"切口环 {len(ring)} 点, y {ring[:,1].min():.3f}~{ring[:,1].max():.3f}")

    parts_v, parts_f, parts_c = [], [], []
    # 鞋的顶点拼在身体后面，索引必须从 len(pos) 起算 ——
    # 从这里开始算 0 的话，所有鞋面会指到身体顶点上，鞋就「消失」了（踩过两次）。
    offset = len(pos)

    def add(v, f, color):
        nonlocal offset
        parts_v.append(v)
        parts_f.append(f + offset)
        parts_c.append(np.broadcast_to(np.asarray(color, float), (len(v), 3)).copy())
        offset += len(v)

    # 切口环是一道「连在一起」的口（脚踝以下两腿并在一起），
    # 所以做一只大拖鞋把两只脚一起装进去 —— 这也正好是「雷霆大拖鞋」。
    soles = []
    # 尺寸按「切面以上那一段身体的包围盒」量，而不是按切口环：
    # 环比脚细，按环做的鞋会让脚从鞋帮外面露出来（上一版就是这样）。
    lowpart = pos[(pos[:, 1] >= y0) & (pos[:, 1] < y0 + args.cut_y + 0.06)]
    cx = float((lowpart[:, 0].min() + lowpart[:, 0].max()) / 2)
    cz = float((lowpart[:, 2].min() + lowpart[:, 2].max()) / 2)
    hw = float((lowpart[:, 0].max() - lowpart[:, 0].min()) / 2 * args.wide + 0.02)
    hl = float((lowpart[:, 2].max() - lowpart[:, 2].min()) / 2 * args.long + 0.03)
    hh = args.sole_h / 2

    # ① 大底
    sv, sf = rounded_slab(hw, hh, hl, boxy=0.35)
    sv = sv + np.array([cx, y0 + hh, cz])
    t = (sv[:, 1] - y0) / (2 * hh)
    c = np.empty((len(sv), 3))
    c[t < 0.18] = [0.13, 0.13, 0.16]                        # 黑色大底
    c[(t >= 0.18) & (t < 0.70)] = [0.97, 0.95, 0.90]        # 米白中底（衬托闪电）
    c[(t >= 0.70) & (t < 0.80)] = [1.00, 0.79, 0.10]        # 一圈黄（雷霆味道）
    c[(t >= 0.80) & (t < 0.87)] = [0.90, 0.17, 0.13]        # 鞋口红边
    c[t >= 0.87] = [0.99, 0.96, 0.92]                       # 米白垫面
    add(sv, sf, c)
    soles.append((cx, cz, hw, hl, hh))

    # ② 鞋口红边：不另外造几何，直接把顶部一圈顶点染色。
    #    之前用半圆拱做绑带，半径等于鞋宽，在 0.26 高度正好绕过肚子 —— 像系了条腰带。

    # ③ 两侧黄闪电，贴在中底上
    for sx in (cx - hw - 0.006, cx + hw + 0.006):
        lv, lf = bolt_decal(sx, y0 + hh * 0.50, cz - hl * 0.12, hh * 0.95, 0.05)
        add(lv, lf, [1.00, 0.88, 0.06])

    print(f"大拖鞋: 中心({cx:+.3f},{cz:+.3f}) 宽{hw*2:.2f} 长{hl*2:.2f} 厚{args.sole_h:.3f} 面 {len(sf)}")

    cap_pos, cap_faces = hole_cap(ring)
    cap_start = offset

    sv_all = np.concatenate(parts_v)
    sf_all = np.concatenate(parts_f)
    sc_all = np.concatenate(parts_c)

    all_pos = np.concatenate([pos, sv_all, cap_pos])
    all_faces = np.concatenate([kept_faces, sf_all, cap_faces + cap_start])
    body_col = boost_colors(transfer_colors(pos, col, all_pos[:len(pos)])[:, :3], 1.9, 1.05, 1.08)
    cap_col = np.tile(np.array([[0.55, 0.18, 0.15]]), (len(cap_pos), 1))
    all_col = np.concatenate([body_col, sc_all, cap_col])

    # 切口环必须埋进大底（底面 y 到 y0+2hh，横向在鞋底范围内）
    # 用超椭球隐式方程逐个验：环上的点是不是都在某只鞋底的体积里
    covered = np.zeros(len(ring), dtype=bool)
    for (cx, cz, hw, hl, hh) in soles:
        q = (np.abs((ring[:, 0] - cx) / hw) ** (2 / 0.35)
             + np.abs((ring[:, 1] - (y0 + hh)) / hh) ** (2 / 0.35)
             + np.abs((ring[:, 2] - cz) / hl) ** (2 / 0.35))
        covered |= q <= 1.0
    print(f"切口环埋进大底: {covered.sum()}/{len(ring)} = {covered.mean()*100:.0f}%")

    # 不变量：鞋的面必须真的引用鞋的顶点
    shoe_faces = all_faces[(all_faces >= len(pos)).all(axis=1)]
    print(f"引用鞋顶点的面: {len(shoe_faces):,}（{len(parts_f)} 组；为 0 就是索引偏移错了）")

    # 单独导一份「只有鞋」的调试文件：我看不到图，只能把鞋单独渲染成字符图核对形状
    if args.debug_out:
        write_glb(args.debug_out, sv_all, sc_all, sf_all)

    idx_ct = write_glb(args.dst, all_pos, all_col, all_faces)
    mb = os.path.getsize(args.dst) / 1048576
    print(f"输出 {args.dst}: {mb:.2f} MB, {len(all_faces):,} 面, 索引 {16 if idx_ct == 5123 else 32} 位")
    print(f"开放边 {open_edges(all_faces)}")


if __name__ == "__main__":
    main()