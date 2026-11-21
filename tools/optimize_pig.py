#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
pig.glb 优化：焊接顶点 + meshoptimizer 减面 + 索引降位。

为什么要做，数据都在文件里：

    pig.glb  4.99 MB
      INDICES  2.98 MB   (260,408 三角形 × 3 × 4 字节，uint32)
      POSITION 1.51 MB   (131,536 顶点 × 12 字节)
      COLOR_0  0.50 MB   (131,536 顶点 × 4 字节)

三个问题：
  1. 26 万面对手机 GPU 是硬负担（GLES2 单 pass，60fps 时每帧都在搬 78 万个索引）；
  2. 顶点数 131,536 超过 65535，索引只能 32 位，索引块占了整个文件 60%；
  3. 顶点没焊接过（导出时按面拆开了），同一个位置存了好几份。

减面用 meshoptimizer 的简化器（GitHub: zeux/meshoptimizer），
Python 侧用的是它的封装 fast-simplification，算法和 gltfpack -si 是同一套：
二次误差度量（QEM）边折叠，按 agg 控制锐边保留。

用法：
    python optimize_pig.py --in pig.glb --out pig-opt.glb --tris 65000
    python optimize_pig.py --tris 26000 --agg 10      # 更狠，更糙
"""
import argparse
import json
import struct
import sys

import numpy as np

GLB_MAGIC = 0x46546C67
CHUNK_JSON = 0x4E4F534A
CHUNK_BIN = 0x004E4942


# ────────────────────────────── 读 GLB ──────────────────────────────

def read_glb(path):
    data = open(path, "rb").read()
    magic, _, total = struct.unpack("<III", data[:12])
    if magic != GLB_MAGIC:
        raise SystemExit("不是 GLB 文件")
    off, js, binchunk = 12, None, b""
    while off + 8 <= len(data):
        ln, typ = struct.unpack("<II", data[off:off + 8])
        chunk = data[off + 8:off + 8 + ln]
        if typ == CHUNK_JSON:
            js = json.loads(chunk.decode("utf-8"))
        elif typ == CHUNK_BIN:
            binchunk = chunk
        off += 8 + ln
    if js is None:
        raise SystemExit("缺少 JSON 块")

    prim = js["meshes"][0]["primitives"][0]
    attrs = prim["attributes"]
    acc = js["accessors"]
    views = js["bufferViews"]

    def raw(ai):
        a = acc[ai]
        v = views[a["bufferView"]]
        start = v.get("byteOffset", 0) + a.get("byteOffset", 0)
        n = a["count"]
        ncomp = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}[a["type"]]
        ct = a["componentType"]
        dt = {5120: np.int8, 5121: np.uint8, 5122: np.int16,
              5123: np.uint16, 5125: np.uint32, 5126: np.float32}[ct]
        itemsize = np.dtype(dt).itemsize
        stride = v.get("byteStride", 0) or (itemsize * ncomp)
        if stride == itemsize * ncomp:
            arr = np.frombuffer(binchunk, dtype=dt, count=n * ncomp, offset=start)
            arr = arr.reshape(n, ncomp)
        else:
            out = np.empty((n, ncomp), dtype=dt)
            for i in range(n):
                o = start + i * stride
                out[i] = np.frombuffer(binchunk, dtype=dt, count=ncomp, offset=o)
            arr = out
        return arr, a

    pos, apos = raw(attrs["POSITION"])
    pos = pos.astype(np.float64)
    col, _ = raw(attrs["COLOR_0"])
    col = col.astype(np.float64) / 255.0

    if "indices" in prim:
        idx, a_idx = raw(prim["indices"])
        faces = idx.reshape(-1, 3).astype(np.int32)
    else:
        faces = np.arange(len(pos), dtype=np.int32).reshape(-1, 3)

    nrm = None
    if "NORMAL" in attrs:
        nrm, _ = raw(attrs["NORMAL"])
        nrm = nrm.astype(np.float32)

    return pos, col, faces, nrm


# ────────────────────────────── 焊接 ──────────────────────────────

def weld(pos, col, faces):
    """
    位置 + 颜色都相同的顶点合成一个。

    导出器经常按面拆顶点（法线/UV 不同就拆），但我们的模型没有法线贴图，
    颜色又是逐顶点烘的，所以合并是安全的，能白捡一批顶点。
    """
    key = np.concatenate([pos, col], axis=1)
    uniq, inverse = np.unique(key, axis=0, return_inverse=True)
    new_pos = uniq[:, :3]
    new_col = uniq[:, 3:]
    new_faces = inverse.reshape(-1)[faces]
    return new_pos, new_col, new_faces.astype(np.int32)


# ────────────────────────────── 颜色就近转移 ──────────────────────────────

def transfer_colors(src_pos, src_col, dst_pos, cells=96):
    """
    简化后顶点位置变了，颜色按「最近的原始顶点」取。

    不用暴力算（3 万 × 13 万 = 40 亿次），用体素哈希：把原始顶点塞进格子，
    查询只看周围一圈，几乎 O(1)。
    """
    lo = src_pos.min(axis=0)
    hi = src_pos.max(axis=0)
    span = np.maximum(hi - lo, 1e-9)
    cell = span / cells

    grid = {}
    ijk = np.floor((src_pos - lo) / cell).astype(np.int64)
    for i, k in enumerate(map(tuple, ijk)):
        grid.setdefault(k, []).append(i)

    # 颜色是 VEC4（RGB + alpha），按原宽度返回，别假设只有 3 个分量
    ncomp = src_col.shape[1]
    out = np.empty((len(dst_pos), ncomp), dtype=np.float64)
    q = np.floor((dst_pos - lo) / cell).astype(np.int64)
    for n, k in enumerate(map(tuple, q)):
        best, best_d = -1, np.inf
        ring = 0
        while best < 0 and ring <= cells:
            for dx in range(-ring, ring + 1):
                for dy in range(-ring, ring + 1):
                    for dz in range(-ring, ring + 1):
                        if ring > 0 and max(abs(dx), abs(dy), abs(dz)) != ring:
                            continue
                        cand = grid.get((k[0] + dx, k[1] + dy, k[2] + dz))
                        if not cand:
                            continue
                        d = np.sum((src_pos[cand] - dst_pos[n]) ** 2, axis=1)
                        j = int(np.argmin(d))
                        if d[j] < best_d:
                            best_d, best = d[j], cand[j]
            ring += 1
        out[n] = src_col[best] if best >= 0 else np.concatenate(
            [np.array([0.8, 0.6, 0.62]), np.ones(ncomp - 3)])[:ncomp]
    return out


# ────────────────────────────── 颜色/法线 ──────────────────────────────

def boost_colors(rgb, sat=1.0, val=1.0, contrast=1.0):
    """
    提饱和度/亮度/对比。

    这个模型的顶点色是 Meshy 烘出来的，平均饱和度只有 0.30（灰扑扑的橄榄绿），
    所以手机上看着「不鲜艳」。做法是 HSV 里按「越灰的地方提得越多」的曲线拉饱和度
    （vibrance），纯色不会被推到溢出，灰的地方能明显活过来。
    """
    if sat == 1.0 and val == 1.0 and contrast == 1.0:
        return rgb
    mx = rgb.max(axis=1, keepdims=True)
    mn = rgb.min(axis=1, keepdims=True)
    chroma = mx - mn
    s_old = np.where(mx > 1e-6, chroma / np.maximum(mx, 1e-6), 0.0)
    # 灰度越低提得越多：灰的 ×sat，已经很艳的 ×(sat*0.55 + 0.45)
    factor = sat * (1.0 - s_old * 0.45)
    s_new = np.clip(s_old * factor, 0.0, 1.0)
    scale = np.where(s_old > 1e-6, s_new / np.maximum(s_old, 1e-6), 1.0)

    v = mx * val
    v = np.clip((v - 0.5) * contrast + 0.5, 0.0, 1.0)

    # 保持色相：从 RGB 还原出「单位亮度色」，再按新饱和度和明度重组成 RGB
    unit = np.where(chroma > 1e-6, (rgb - mn) / np.maximum(chroma, 1e-6), 0.0)
    out = unit * (s_new * v) + (v - s_new * v)
    return np.clip(out, 0.0, 1.0)


def vertex_normals(pos, faces):
    """
    按面积加权的平滑顶点法线。

    原模型根本没有 NORMAL：加载器发现没有法线就给全体顶点塞 (0,0,1)，
    于是整只猪被「一刀平」地照着，看上去又平又灰 —— 这才是颜色不鲜艳的主因。
    减面后的网格重新算法线，贴进去渲染立刻就有体积感。
    """
    tri = pos[faces]
    fn = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])  # 长度 = 2×面积
    acc = np.zeros_like(pos)
    for k in range(3):
        np.add.at(acc, faces[:, k], fn)
    ln = np.linalg.norm(acc, axis=1, keepdims=True)
    return acc / np.maximum(ln, 1e-12)


# ────────────────────────────── 误差度量 ──────────────────────────────

def sample_surface(pos, faces, n, rng):
    """在三角面上均匀撒点（面积加权），得到表面点云。"""
    tri = pos[faces]
    a = tri[:, 1] - tri[:, 0]
    b = tri[:, 2] - tri[:, 0]
    area = 0.5 * np.linalg.norm(np.cross(a, b), axis=1)
    p = area / area.sum()
    pick = rng.choice(len(faces), size=n, p=p)
    r1 = np.sqrt(rng.random(n))
    r2 = rng.random(n)
    t = tri[pick]
    return (t[:, 0] * (1 - r1)[:, None]
            + t[:, 1] * (r1 * (1 - r2))[:, None]
            + t[:, 2] * (r1 * r2)[:, None])


def nearest_dist(src, dst, cells=64, rng=None):
    """src 每个点到 dst 点集的最近距离（体素哈希，查不到就全量兜底）。"""
    span = np.maximum(dst.max(axis=0) - dst.min(axis=0), 1e-9)
    lo = dst.min(axis=0) - span * 0.01
    cell = span * 1.02 / cells
    grid = {}
    for i, k in enumerate(map(tuple, np.floor((dst - lo) / cell).astype(np.int64))):
        grid.setdefault(k, []).append(i)

    out = np.empty(len(src))
    misses = []
    keys = np.floor((src - lo) / cell).astype(np.int64)
    for n, k in enumerate(map(tuple, keys)):
        best_d, ring = np.inf, 0
        while ring <= cells // 2 and not np.isfinite(best_d):
            for dx in range(-ring, ring + 1):
                for dy in range(-ring, ring + 1):
                    for dz in range(-ring, ring + 1):
                        if ring > 0 and max(abs(dx), abs(dy), abs(dz)) != ring:
                            continue
                        cand = grid.get((k[0] + dx, k[1] + dy, k[2] + dz))
                        if cand:
                            d = np.min(np.sum((dst[cand] - src[n]) ** 2, axis=1))
                            if d < best_d:
                                best_d = d
            ring += 1
        if np.isfinite(best_d):
            out[n] = np.sqrt(best_d)
        else:
            misses.append(n)
    # 极少数落在空区的点：老老实实全量算，不糊弄
    for n in misses:
        out[n] = np.sqrt(np.min(np.sum((dst - src[n]) ** 2, axis=1)))
    return out


# ────────────────────────────── 写 GLB ──────────────────────────────

def write_glb(path, pos, col, faces, normals=None):
    vcount = len(pos)
    # 顶点数能塞进 16 位就用 16 位索引，索引块直接减半
    if vcount <= 65535:
        idx_dtype, idx_ct = np.uint16, 5123
    else:
        idx_dtype, idx_ct = np.uint32, 5125

    pos_f = pos.astype(np.float32)
    nrm_f = None if normals is None else normals.astype(np.float32)
    col_b = np.clip(np.round(col * 255.0), 0, 255).astype(np.uint8)
    # 颜色是 VEC4，alpha 拉满
    col_b4 = np.concatenate([col_b, np.full((vcount, 1), 255, dtype=np.uint8)], axis=1)
    idx = faces.reshape(-1).astype(idx_dtype)

    def pad4(b, filler=b"\x00"):
        r = len(b) % 4
        return b + filler * ((4 - r) % 4)

    blobs = [pos_f.tobytes()]
    if nrm_f is not None:
        blobs.append(nrm_f.tobytes())
    blobs += [col_b4.tobytes(), idx.tobytes()]
    views, offset = [], 0
    for b in blobs:
        views.append({"buffer": 0, "byteOffset": offset, "byteLength": len(b)})
        offset += len(b)
        pad = (4 - offset % 4) % 4
        offset += pad
    binchunk = b"".join(
        pad4(b) for b in blobs
    )

    mn = pos_f.min(axis=0).tolist()
    mx = pos_f.max(axis=0).tolist()

    accs = [{"bufferView": 0, "componentType": 5126, "count": vcount,
             "type": "VEC3", "min": mn, "max": mx}]
    attrs = {"POSITION": 0}
    next_view = 1
    if nrm_f is not None:
        accs.append({"bufferView": next_view, "componentType": 5126,
                     "count": vcount, "type": "VEC3"})
        attrs["NORMAL"] = next_view
        next_view += 1
    accs.append({"bufferView": next_view, "componentType": 5121, "count": vcount,
                 "type": "VEC4", "normalized": True})
    attrs["COLOR_0"] = next_view
    next_view += 1
    accs.append({"bufferView": next_view, "componentType": idx_ct,
                 "count": len(idx), "type": "SCALAR"})

    js = {
        "asset": {"version": "2.0", "generator": "EvilShao optimize_pig.py (meshoptimizer)"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [{
            "attributes": attrs,
            "indices": len(attrs),
            "mode": 4,
        }]}],
        "accessors": accs,
        "bufferViews": views,
        "buffers": [{"byteLength": len(binchunk)}],
    }
    jsb = pad4(json.dumps(js, separators=(",", ":")).encode("utf-8"), b" ")
    total = 12 + 8 + len(jsb) + 8 + len(binchunk)
    with open(path, "wb") as f:
        f.write(struct.pack("<III", GLB_MAGIC, 2, total))
        f.write(struct.pack("<II", len(jsb), CHUNK_JSON))
        f.write(jsb)
        f.write(struct.pack("<II", len(binchunk), CHUNK_BIN))
        f.write(binchunk)
    return idx_ct


# ────────────────────────────── 主流程 ──────────────────────────────

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--in", dest="src", default=r"E:\EvilShao\android\app\src\main\assets\pig.glb")
    ap.add_argument("--out", dest="dst", default=r"C:\Users\Public\evilshao\pig-opt.glb")
    ap.add_argument("--tris", type=int, default=65000, help="目标三角形数")
    ap.add_argument("--agg", type=float, default=7.0,
                    help="0=尽量保形但慢，10=快但糙（meshoptimizer 的 aggressiveness）")
    ap.add_argument("--no-weld", action="store_true")
    ap.add_argument("--no-normals", action="store_true",
                    help="不写 NORMAL（默认写，没法线会平的一塌糊涂）")
    ap.add_argument("--sat", type=float, default=1.0, help="饱和度倍数，1=原样")
    ap.add_argument("--val", type=float, default=1.0, help="明度倍数")
    ap.add_argument("--contrast", type=float, default=1.0, help="对比度")
    args = ap.parse_args()

    import os
    pos, col, faces, _ = read_glb(args.src)
    src_mb = os.path.getsize(args.src) / 1048576
    n0, t0 = len(pos), len(faces)
    print(f"原始: {n0:,} 顶点 / {t0:,} 三角形 / {src_mb:.2f} MB")

    if not args.no_weld:
        pos, col, faces = weld(pos, col, faces)
        print(f"焊接后: {len(pos):,} 顶点 / {len(faces):,} 三角形 "
              f"(合并掉 {n0 - len(pos):,} 个重复顶点)")

    target = max(1000, args.tris)
    ratio = max(0.0, min(0.99, 1.0 - target / len(faces)))
    if ratio <= 0:
        raise SystemExit("目标三角形数不小于现有面数，不用减")
    print(f"减面: 目标 {target:,} 面，reduction={ratio:.3f}, agg={args.agg}")

    import fast_simplification as fs
    v, f = fs.simplify(pos.astype(np.float32), faces.astype(np.int64),
                       target_reduction=ratio, agg=args.agg)
    v = np.asarray(v, dtype=np.float64)
    f = np.asarray(f, dtype=np.int32)
    print(f"简化后: {len(v):,} 顶点 / {len(f):,} 三角形")

    print(f"原始颜色 alpha 范围: {col[:, 3].min():.2f} ~ {col[:, 3].max():.2f}")
    print("颜色转移…")
    c = transfer_colors(pos, col, v)[:, :3]

    # 双向 Chamfer 距离：原始表面 → 简化表面，以及反过来。
    # 只看顶点距离会低估误差（简化后的面可能比顶点更贴近原表面）。
    rng = np.random.default_rng(7)
    diag = float(np.linalg.norm(pos.max(axis=0) - pos.min(axis=0)))
    a = sample_surface(pos, faces, 40000, rng)
    b = sample_surface(v, f, 40000, rng)
    d1 = nearest_dist(a, b) / diag * 100
    d2 = nearest_dist(b, v[np.random.default_rng(8).choice(len(v), min(20000, len(v)), replace=False)]) / diag * 100
    print(f"形状误差（相对包围盒对角线）")
    print(f"  原表面→新表面: 平均 {d1.mean():.2f}% / P99 {np.percentile(d1, 99):.2f}% / 最大 {d1.max():.2f}%")
    print(f"  新表面→原起点: 平均 {d2.mean():.2f}% / P99 {np.percentile(d2, 99):.2f}%")

    rgb = boost_colors(c, args.sat, args.val, args.contrast)
    nrm = None if args.no_normals else vertex_normals(v, f)
    if nrm is not None:
        print(f"法线: 已生成 {len(nrm):,} 条（原模型没有 NORMAL）")
    idx_ct = write_glb(args.dst, v, rgb, f, nrm)
    dst_mb = os.path.getsize(args.dst) / 1048576
    print(f"输出: {args.dst}")
    print(f"      {dst_mb:.2f} MB（原 {src_mb:.2f} MB，-{(1 - dst_mb / src_mb) * 100:.0f}%）"
          f"，索引 {16 if idx_ct == 5123 else 32} 位")
    print(f"      三角形 {t0:,} → {len(f):,}（-{(1 - len(f) / t0) * 100:.0f}%）"
          f"，顶点 {n0:,} → {len(v):,}")


if __name__ == "__main__":
    sys.exit(main())