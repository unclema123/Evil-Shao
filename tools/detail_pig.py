#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分区减面：手脚保留原精度，身体照减。

之前那次全局 QEM 按「误差/面积」决定塌哪条边，结果耳朵、手、脚这些
面积小但形状关键的地方先被抹平 —— 用户看到的就是「手和脚糊成一坨」。

做法是把网格切成两部分分别处理：

    手（两侧外缘球区）+ 脚（最低一层）  →  一个面都不减，原样保留
    其余（身体、头、衣服）              →  减到指定面数

接缝靠 meshoptimizer 的 preserve_border 保证：身体那部分的切边顶点被锁住不动，
手脚那部分本来就是原始顶点，两边坐标完全一致，合起来按坐标焊接就严丝合缝。

用法：
    python detail_pig.py --in pig-orig.glb --out pig-detail.glb \
           --body-tris 15000 --hand-r 0.13 --foot-y 0.10 --sat 2.0
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from optimize_pig import (  # noqa: E402
    boost_colors, read_glb, sample_surface, nearest_dist,
    transfer_colors, vertex_normals, write_glb,
)


def hand_centers(pos):
    """两侧手的中心：取最外侧 2% 的点估中心，比硬套 |x| 阈值稳。"""
    lx, rx = pos[:, 0].min(), pos[:, 0].max()
    lsel = pos[pos[:, 0] < lx + 0.02]
    rsel = pos[pos[:, 0] > rx - 0.02]
    return np.array([
        [lx, np.median(lsel[:, 1]), np.median(lsel[:, 2])],
        [rx, np.median(rsel[:, 1]), np.median(rsel[:, 2])],
    ])


def in_zones(points, centers, hand_r, foot_y, floor_y):
    """任意一组点是否落在保护区内（手球 ∪ 脚层）。"""
    mask = points[:, 1] < floor_y + foot_y
    for c in centers:
        mask |= np.linalg.norm(points - c, axis=1) < hand_r
    return mask


def zone_mask(pos, faces, hand_r, foot_y):
    """返回每个面是否属于「要保精度」的区域。"""
    cen = pos[faces].mean(axis=1)
    return in_zones(cen, hand_centers(pos), hand_r, foot_y, pos[:, 1].min())


def submesh(pos, faces, mask):
    f = faces[mask]
    used, inv = np.unique(f, return_inverse=True)
    return pos[used], inv.reshape(-1, 3).astype(np.int32)


def weld(pos, faces, eps=6):
    """按坐标合并重合顶点，返回 (pos, faces, 合并掉几个)。"""
    key = np.round(pos, eps)
    uniq, first, inv = np.unique(key, axis=0, return_index=True, return_inverse=True)
    new_pos = pos[first]
    new_faces = inv.reshape(-1)[faces].astype(np.int32)
    # 焊接后可能产生退化面（两个角相同）
    ok = (new_faces[:, 0] != new_faces[:, 1]) & \
         (new_faces[:, 1] != new_faces[:, 2]) & \
         (new_faces[:, 0] != new_faces[:, 2])
    return new_pos, new_faces[ok], len(pos) - len(new_pos)


def open_edges(faces):
    """统计只被一个面用到的边 —— 0 表示封闭（没有裂缝）。"""
    e = np.concatenate([faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]]])
    e = np.sort(e, axis=1)
    uq, cnt = np.unique(e, axis=0, return_counts=True)
    return int((cnt == 1).sum())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default=r"C:\Users\Public\evilshao\pig-orig.glb")
    ap.add_argument("--out", dest="dst", default=r"C:\Users\Public\evilshao\pig-detail.glb")
    ap.add_argument("--body-tris", type=int, default=15000, help="身体减到多少面")
    ap.add_argument("--hand-r", type=float, default=0.13, help="手部保护球半径（模型单位）")
    ap.add_argument("--foot-y", type=float, default=0.10, help="脚部保护高度（从最低点算）")
    ap.add_argument("--agg", type=float, default=6.0)
    ap.add_argument("--sat", type=float, default=2.0)
    ap.add_argument("--val", type=float, default=1.06)
    ap.add_argument("--contrast", type=float, default=1.10)
    args = ap.parse_args()

    pos, col, faces, _ = read_glb(args.src)
    print(f"原始: {len(pos):,} 顶点 / {len(faces):,} 三角形  {os.path.getsize(args.src)/1048576:.2f} MB")

    detail = zone_mask(pos, faces, args.hand_r, args.foot_y)
    print(f"保护区域（手+脚）: {detail.sum():,} 面 ({detail.sum()/len(faces)*100:.1f}%)")

    dpos, dfaces = submesh(pos, faces, detail)
    bpos, bfaces = submesh(pos, faces, ~detail)

    ratio = max(0.0, min(0.99, 1.0 - args.body_tris / len(bfaces)))
    print(f"身体: {len(bfaces):,} 面 → 目标 {args.body_tris:,} 面 (reduction {ratio:.3f})")
    import fast_simplification as fs
    bv, bf = fs.simplify(bpos.astype(np.float32), bfaces.astype(np.int64),
                         target_reduction=ratio, agg=args.agg, preserve_border=True)
    bv = np.asarray(bv, dtype=np.float64)
    bf = np.asarray(bf, dtype=np.int32)
    print(f"身体简化后: {len(bv):,} 顶点 / {len(bf):,} 面")

    all_pos = np.concatenate([dpos, bv])
    all_faces = np.concatenate([dfaces, bf + len(dpos)])
    pos_w, faces_w, merged = weld(all_pos, all_faces)
    print(f"焊接: 合并 {merged:,} 个重合顶点 → {len(pos_w):,} 顶点 / {len(faces_w):,} 面")
    print(f"开放边: {open_edges(faces_w)} 条（0 = 没有裂缝）")

    print("颜色转移…")
    rgb = transfer_colors(pos, col, pos_w)[:, :3]
    rgb = boost_colors(rgb, args.sat, args.val, args.contrast)
    nrm = vertex_normals(pos_w, faces_w)

    idx_ct = write_glb(args.dst, pos_w, rgb, faces_w, nrm)
    mb = os.path.getsize(args.dst) / 1048576
    print(f"输出 {args.dst}: {mb:.2f} MB, {len(faces_w):,} 面, 索引 {16 if idx_ct == 5123 else 32} 位")

    # 分区域看误差：手脚应该近乎 0（原样保留），身体是减面代价
    rng = np.random.default_rng(3)
    diag = float(np.linalg.norm(pos.max(0) - pos.min(0)))
    a = sample_surface(pos, faces, 30000, rng)
    d = nearest_dist(a, pos_w) / diag * 100
    # 采样点用同一套分区规则打标签（直接按位置算，别去搜最近面，太慢）
    labels = in_zones(a, hand_centers(pos), args.hand_r, args.foot_y, pos[:, 1].min())

    print("\n误差（相对包围盒对角线）:")
    print(f"  手脚: 平均 {d[labels].mean():.3f}%  P99 {np.percentile(d[labels], 99):.3f}%")
    print(f"  身体: 平均 {d[~labels].mean():.3f}%  P99 {np.percentile(d[~labels], 99):.3f}%")


if __name__ == "__main__":
    main()