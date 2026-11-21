#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
给小猪的脚「上色」成一双小靴子 —— 不动几何，只改颜色。

为什么这么做：前面几版都在切脚、造鞋，代价是切面、补洞、索引偏移一堆坑，
而且鞋一大就丑。脚本来就在模型里，形状没毛病，把它染成靴子最省事也最不容易出错：
没有新几何、没有洞、没有接缝，渲染结果只取决于颜色。

配色（按高度分层，都在脚的范围内）：
    最底一层   米白鞋底
    往上一点   深色鞋底线
    鞋面       深枣红皮
    鞋口       一圈亮金边
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from optimize_pig import boost_colors, read_glb, transfer_colors, vertex_normals, write_glb  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default=r"E:\EvilShao\android\app\src\main\assets\pig.glb")
    ap.add_argument("--out", dest="dst", default=r"C:\Users\Public\evilshao\pig-boots.glb")
    ap.add_argument("--boot-top", type=float, default=0.105, help="靴口高度（从最低点算）")
    ap.add_argument("--sat", type=float, default=1.45, help="身体颜色饱和度")
    args = ap.parse_args()

    pos, col, faces, _ = read_glb(args.src)
    y0 = pos[:, 1].min()
    rgb = boost_colors(col[:, :3], args.sat, 1.04, 1.06)

    h = pos[:, 1] - y0
    top = args.boot_top

    sole = h < top * 0.13
    welt = (h >= top * 0.13) & (h < top * 0.24)
    leather = (h >= top * 0.24) & (h < top * 0.90)
    rim = (h >= top * 0.90) & (h < top)

    rgb[sole] = [0.96, 0.94, 0.88]        # 米白鞋底
    rgb[welt] = [0.24, 0.14, 0.12]        # 鞋底线
    rgb[leather] = [0.62, 0.24, 0.20]     # 枣红皮
    rgb[rim] = [0.99, 0.83, 0.45]         # 金边

    # 皮面加点上下渐变，别是一块死色
    if leather.sum():
        t = (h[leather] - top * 0.24) / (top * 0.66)
        rgb[leather] *= (0.88 + 0.22 * t)[:, None]

    n_boots = int((h < top).sum())
    print(f"上色: 靴口 {top:.3f}，涉及 {n_boots:,} 个顶点 "
          f"(鞋底 {int(sole.sum())} / 线 {int(welt.sum())} / 皮 {int(leather.sum())} / 边 {int(rim.sum())})")

    nrm = vertex_normals(pos, faces)
    idx_ct = write_glb(args.dst, pos, rgb, faces, nrm)
    mb = os.path.getsize(args.dst) / 1048576
    print(f"输出 {args.dst}: {mb:.2f} MB, {len(faces):,} 面, 索引 {16 if idx_ct == 5123 else 32} 位")


if __name__ == "__main__":
    main()