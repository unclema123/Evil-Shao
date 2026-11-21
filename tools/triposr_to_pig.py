#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
TripoSR 输出的 obj+贴图 → app 用的 GLB（带贴图、减面）。

为什么不用 TripoSR 自己导出的 GLB：
它出的是 20-40 万面的稠密网格，手机上跑不动；而且我们要的分辨率只要它的
1/4。所以这里读 obj、减面、把 UV 就近转移到新顶点上，再导出带贴图的 GLB。

app 的 GlbModel 只认 POSITION / NORMAL / TEXCOORD_0 / COLOR_0 / 索引 /
内嵌贴图，材质里读 baseColorTexture —— 这些 trimesh 导出的 GLB 都有。
"""
import argparse
import os
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from optimize_pig import transfer_colors, vertex_normals  # noqa: E402


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--obj", required=True, help="TripoSR 输出的 mesh.obj")
    ap.add_argument("--out", required=True, help="输出 GLB 路径")
    ap.add_argument("--tris", type=int, default=40000)
    ap.add_argument("--texture", default=None, help="贴图 PNG（默认从 obj 的 mtl 里找）")
    args = ap.parse_args()

    import trimesh
    mesh = trimesh.load(args.obj, force="mesh", process=False)
    print(f"载入 obj: {len(mesh.vertices):,} 顶点 / {len(mesh.faces):,} 面")

    pos = np.asarray(mesh.vertices, dtype=np.float64)
    faces = np.asarray(mesh.faces, dtype=np.int32)

    uv = None
    visual = getattr(mesh, "visual", None)
    if visual is not None and getattr(visual, "uv", None) is not None:
        uv = np.asarray(visual.uv, dtype=np.float64)
        print(f"UV: {uv.shape}")
    if visual is not None and getattr(visual, "material", None) is not None:
        img = getattr(visual.material, "baseColorTexture", None) or getattr(visual.material, "image", None)
        if img is not None:
            if args.texture is None:
                args.texture = os.path.join(os.path.dirname(args.out), "texture_from_mesh.png")
                try:
                    img.convert("RGB").save(args.texture)
                    print(f"贴图已另存: {args.texture}")
                except Exception as e:
                    print(f"贴图另存失败: {e}")
                    args.texture = None

    # 减面
    if len(faces) > args.tris:
        import fast_simplification as fs
        ratio = 1.0 - args.tris / len(faces)
        v2, f2 = fs.simplify(pos.astype(np.float32), faces.astype(np.int64),
                             target_reduction=ratio, agg=6.0)
        v2 = np.asarray(v2, dtype=np.float64)
        f2 = np.asarray(f2, dtype=np.int32)
        print(f"减面: {len(faces):,} → {len(f2):,} 面")
        if uv is not None and len(uv) == len(pos):
            # UV 和颜色一样是逐顶点属性，就近转移
            uv2 = transfer_colors(pos, uv, v2, cells=128)[:, :2]
            uv2 = np.clip(uv2, 0.0, 1.0)
        else:
            uv2 = None
        pos, faces, uv = v2, f2, uv2
    else:
        print("面数已在目标以下，不减")

    out = trimesh.Trimesh(vertices=pos, faces=faces, process=False)
    if uv is not None:
        from PIL import Image
        tex = Image.open(args.texture).convert("RGB") if args.texture else None
        out.visual = trimesh.visual.TextureVisuals(uv=uv, image=tex)
    out.export(args.out, file_type="glb")

    mb = os.path.getsize(args.out) / 1048576
    print(f"输出 {args.out}: {mb:.2f} MB, {len(faces):,} 面, 贴图 {args.texture}")


if __name__ == "__main__":
    main()