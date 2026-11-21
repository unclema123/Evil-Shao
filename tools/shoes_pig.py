#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给小猪穿鞋：把脚切掉，换成两只圆头鞋。

为什么要这么做：脚的形状在低模里最难保 —— 脚趾是几十个小突起，减面必糊，
不减面又占预算。鞋是个大块面，低面数就能很「整」，遮住脚以后整只猪反而干净。

几何用 icosphere（二十面体细分球）+ 压扁 + 抬脚尖，和 GitHub 上
`mikedh/trimesh` 的 `creation.icosphere()` 同一个思路：球面细分保证三角形均匀，
再沿轴缩放成需要的比例。鞋底靠「y 坐标下钳」压平，比布尔切平简单且不会裂。

用法：
    python shoes_pig.py --in pig-detail.glb --out pig-shoe.glb --foot-y 0.03
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from optimize_pig import (  # noqa: E402
    boost_colors, read_glb, transfer_colors, write_glb,
)
from detail_pig import open_edges, weld  # noqa: E402


# ────────────────────────── 基础几何 ──────────────────────────

def icosphere(subdiv=3):
    """二十面体细分球。返回 (顶点 Nx3, 面 Mx3)，面是逆时针朝外。"""
    t = (1.0 + 5.0 ** 0.5) / 2.0
    v = np.array([
        [-1, t, 0], [1, t, 0], [-1, -t, 0], [1, -t, 0],
        [0, -1, t], [0, 1, t], [0, -1, -t], [0, 1, -t],
        [t, 0, -1], [t, 0, 1], [-t, 0, -1], [-t, 0, 1],
    ], dtype=np.float64)
    v /= np.linalg.norm(v, axis=1, keepdims=True)
    f = np.array([
        [0, 11, 5], [0, 5, 1], [0, 1, 7], [0, 7, 10], [0, 10, 11],
        [1, 5, 9], [5, 11, 4], [11, 10, 2], [10, 7, 6], [7, 1, 8],
        [3, 9, 4], [3, 4, 2], [3, 2, 6], [3, 6, 8], [3, 8, 9],
        [4, 9, 5], [2, 4, 11], [6, 2, 10], [8, 6, 7], [9, 8, 1],
    ], dtype=np.int32)

    for _ in range(subdiv):
        verts = list(v)
        cache = {}
        faces = []
        for a, b, c in f:
            mids = []
            for i, j in ((a, b), (b, c), (c, a)):
                key = (min(i, j), max(i, j))
                if key not in cache:
                    p = (v[i] + v[j]) / 2.0
                    p /= np.linalg.norm(p)
                    verts.append(p)
                    cache[key] = len(verts) - 1
                mids.append(cache[key])
            m0, m1, m2 = mids
            faces += [[a, m0, m2], [b, m1, m0], [c, m2, m1], [m0, m1, m2]]
        v = np.array(verts)
        f = np.array(faces, dtype=np.int32)
    return v, f


def build_shoe(center_x, center_z, half_w, half_h, half_l, floor_y, boxy=0.5):
    """
    造一只鞋。返回 (顶点 Nx3, 面 Mx3, 每顶点 RGB)。

    形状要点：
    - 圆头：球压扁，脚尖那半略微收窄
    - 平底：y 坐标下钳，得到一块平鞋底（比切平面简单，不会裂缝）
    - 抬脚尖：前 40% 把底往上抬，走路不绊地的感觉
    """
    v, f = icosphere(subdiv=3)
    v = v.copy()

    # 先把球「推方」成超椭球：|v|^boxy 让截面在接近顶部时仍然很宽，
    # 不然椭圆在 3/4 高度处已经收窄，切脚的那一圈会露在鞋外面。
    # 隐式方程 sum(|p_i / a_i|^(2/boxy)) = 1，验算和这里一致。
    v = np.sign(v) * np.abs(v) ** boxy

    v[:, 0] *= half_w
    v[:, 1] *= half_h
    v[:, 2] *= half_l

    t = (v[:, 2] / half_l + 1.0) / 2.0           # 0=脚跟, 1=脚尖
    v[:, 0] *= 1.0 - 0.18 * np.clip(t - 0.55, 0, 1) / 0.45   # 脚尖收窄
    v[:, 1] += np.clip(t - 0.55, 0, 1) / 0.45 * half_h * 0.55  # 抬脚尖

    # 平底
    v[:, 1] = np.maximum(v[:, 1], -half_h * 0.92)

    # 颜色：鞋底米白、鞋面深棕、脚尖稍亮
    rgb = np.zeros((len(v), 3))
    h = (v[:, 1] + half_h) / (2 * half_h)        # 0=底 1=顶
    sole = h < 0.18
    welt = (h >= 0.18) & (h < 0.26)
    upper = h >= 0.26
    rgb[sole] = [0.90, 0.85, 0.75]
    rgb[welt] = [0.22, 0.12, 0.09]
    base = np.array([0.45, 0.20, 0.14])
    lit = base * (0.85 + 0.35 * t)[:, None]
    rgb[upper] = lit[upper]

    v[:, 0] += center_x
    v[:, 1] += floor_y + half_h * 0.92
    v[:, 2] += center_z
    return v, f, rgb


def boundary_ring(pos, faces):
    """开放边上的顶点（切口那一圈）。"""
    e = np.concatenate([faces[:, [0, 1]], faces[:, [1, 2]], faces[:, [2, 0]]])
    e = np.sort(e, axis=1)
    uq, cnt = np.unique(e, axis=0, return_counts=True)
    return pos[uq[cnt == 1].reshape(-1)]


def hole_cap(ring):
    """给切口补一个扇形盖。看不见（在鞋里），但能保证不会漏出空洞。"""
    if len(ring) == 0:
        return np.zeros((0, 3)), np.zeros((0, 3), dtype=np.int32)
    pts, faces = [], []
    base = 0
    for side in (True, False):
        sel = ring[ring[:, 0] < 0] if side else ring[ring[:, 0] >= 0]
        if len(sel) < 3:
            continue
        c = sel.mean(axis=0)
        order = np.argsort(np.arctan2(sel[:, 2] - c[2], sel[:, 0] - c[0]))
        sel = sel[order]
        pts.append(c[None, :])
        pts.append(sel)
        n = len(sel)
        for i in range(n):
            faces.append([base, base + 1 + i, base + 1 + (i + 1) % n])
        base += n + 1
    return np.concatenate(pts), np.array(faces, dtype=np.int32)


# ────────────────────────── 主流程 ──────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default=r"C:\Users\Public\evilshao\pig-detail.glb")
    ap.add_argument("--out", dest="dst", default=r"C:\Users\Public\evilshao\pig-shoe.glb")
    ap.add_argument("--foot-y", type=float, default=0.03, help="切脚高度（从最低点算，按鞋底脚印量）")
    ap.add_argument("--cut-y", type=float, default=0.12, help="真的切掉的高度")
    ap.add_argument("--height", type=float, default=0.18, help="鞋帮高度")
    ap.add_argument("--boxy", type=float, default=0.5, help="越小越方（1=正球）")
    ap.add_argument("--sat", type=float, default=1.0)
    ap.add_argument("--val", type=float, default=1.0)
    ap.add_argument("--contrast", type=float, default=1.0)
    args = ap.parse_args()

    pos, col, faces, _ = read_glb(args.src)
    y0 = pos[:, 1].min()
    print(f"输入: {len(pos):,} 顶点 / {len(faces):,} 面, 最低点 y={y0:.4f}")

    # 1) 切掉脚：重心在切面以下的面
    cen = pos[faces].mean(axis=1)
    foot = cen[:, 1] < y0 + args.cut_y
    keep = ~foot
    print(f"切掉脚: {foot.sum():,} 面（{foot.sum()/len(faces)*100:.1f}%），剩 {keep.sum():,} 面")

    # 2) 按脚印尺寸造鞋（左右各按自己的脚印，模型本身不对称）
    shoes_v, shoes_f, shoes_c = [], [], []
    specs = []
    offset = 0
    for side, sign in (("左", -1), ("右", 1)):
        # 尺寸按「切面正上方那一圈」量，而不是按脚印：
        # 脚印比脚踝细，按脚印做的鞋会把腿切面露在外面。
        sel = pos[(pos[:, 1] >= y0 + args.cut_y) & (pos[:, 1] < y0 + args.cut_y + 0.05) &
                  ((pos[:, 0] < 0) if sign < 0 else (pos[:, 0] >= 0))]
        if len(sel) < 10:
            print(f"{side}脚: 采样点太少，跳过")
            continue
        cx = sel[:, 0].mean()
        cz = (sel[:, 2].min() + sel[:, 2].max()) / 2
        half_w = (sel[:, 0].max() - sel[:, 0].min()) / 2 * 1.14 + 0.010
        half_l = (sel[:, 2].max() - sel[:, 2].min()) / 2 * 1.12 + 0.012
        half_h = args.height / 2
        specs.append((cx, cz, half_w, half_l, half_h))
        v, f, c = build_shoe(cx, cz, half_w, half_h, half_l, y0, args.boxy)
        print(f"{side}鞋: 中心({cx:+.3f},{cz:+.3f}) 宽{half_w*2:.3f} 长{half_l*2:.3f} 高{args.height:.3f} 面 {len(f)}")
        shoes_v.append(v); shoes_f.append(f + offset + len(pos)); shoes_c.append(c)
        offset += len(v)

    if not shoes_v:
        raise SystemExit("两只鞋都没造出来")

    sv = np.concatenate(shoes_v)
    sf = np.concatenate(shoes_f)
    sc = np.concatenate(shoes_c)

    # 3) 鞋是否真的包住了切口环？用超椭球隐式方程验算，
    #    包不住就按 6% 一档放大重造，最多放大 6 次。
    kept_faces = faces[keep]
    ring = boundary_ring(pos, kept_faces)
    for attempt in range(7):
        ok = True
        for i, (cx, cz, hw, hl, hh) in enumerate(specs):
            c = np.array([cx, y0 + hh * 0.92, cz])
            a = np.array([hw, hh, hl])
            sel = ring[((ring[:, 0] < 0) if c[0] < 0 else (ring[:, 0] >= 0))]
            if len(sel) == 0:
                continue
            q = (np.abs((sel - c) / a) ** (2.0 / args.boxy)).sum(axis=1)
            if q.max() > 1.0:
                ok = False
                break
        if ok:
            print(f"切口环覆盖: 100%（放大第 {attempt} 次后）")
            break
        print(f"切口环没包住（最差 s={q.max():.2f}），鞋放大 6% 重造")
        specs = [(cx, cz, hw * 1.06, hl * 1.06, hh) for (cx, cz, hw, hl, hh) in specs]
        shoes_v2, shoes_f2, shoes_c2 = [], [], []
        off2 = 0
        for cx, cz, hw, hl, hh in specs:
            v, ff, cc = build_shoe(cx, cz, hw, hh, hl, y0, args.boxy)
            shoes_v2.append(v); shoes_f2.append(ff + off2 + len(pos)); shoes_c2.append(cc)
            off2 += len(v)
        sv = np.concatenate(shoes_v2); sf = np.concatenate(shoes_f2); sc = np.concatenate(shoes_c2)
    else:
        print("警告: 放大 6 次仍没完全包住，靠下面的补盖兜底")

    # 4) 切口补盖：就算鞋边缘差一点，也不会露出空洞
    cap_pos, cap_faces = hole_cap(ring)
    cap_start = len(pos) + len(sv)

    # 5) 合到本体上（不焊接：鞋和腿是两块实体，焊了反而会把鞋面拉坏）
    all_pos = np.concatenate([pos, sv, cap_pos])
    all_faces = np.concatenate([kept_faces, sf, cap_faces + cap_start])
    print(f"合并: {len(all_pos):,} 顶点 / {len(all_faces):,} 面（鞋 {len(sf):,} 面）")

    # 4) 本体颜色沿用原来的（从原始模型就近取），鞋用自己造的颜色
    print("颜色转移…")
    body_col = transfer_colors(pos, col, all_pos[:len(pos)])[:, :3]
    body_col = boost_colors(body_col, args.sat, args.val, args.contrast)
    cap_col = np.tile(np.array([[0.82, 0.32, 0.34]]), (len(cap_pos), 1))  # 补盖涂成鞋色/肉色都行，反正看不见
    all_col = np.concatenate([body_col, sc, cap_col])

    idx_ct = write_glb(args.dst, all_pos, all_col, all_faces)
    mb = os.path.getsize(args.dst) / 1048576
    print(f"输出 {args.dst}: {mb:.2f} MB, {len(all_faces):,} 面, 索引 {16 if idx_ct == 5123 else 32} 位")
    print(f"开放边（含鞋体内部分界）: {open_edges(all_faces)}")


if __name__ == "__main__":
    main()