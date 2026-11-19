"""
把 TripoSR 出的 GLB 整理成 App 能用的模型。

做四件事：
1. 摆正（TripoSR 的模型是 X 轴朝上，转成 Y 轴朝上）
2. 三视图投影上色——正面/侧面/背面各一张参考图，按顶点法线的朝向
   分配给对应视角取样，这样背面不再是灰的
3. 清理（只留最大连通块、砍掉地面投影、砍掉脚下的阴影）
4. 导出 app/src/main/assets/pig.glb

用法：
    python build_pig.py <in.glb> <out.glb> <front.png> <side.png> <back.png> [--views]
"""

import json
import os
import struct
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy.sparse import coo_matrix
from scipy.sparse.csgraph import connected_components
from scipy.spatial import cKDTree


# ---------- GLB 读写 ----------

def read_glb(path):
    raw = open(path, "rb").read()
    _, _ver, total = struct.unpack_from("<III", raw, 0)
    off, js, bin_ = 12, None, None
    while off < total:
        clen, ctype = struct.unpack_from("<II", raw, off)
        off += 8
        data = raw[off:off + clen]
        off += clen
        if ctype == 0x4E4F534A:
            js = json.loads(data.decode("utf-8"))
        elif ctype == 0x004E4942:
            bin_ = data
    return js, bin_


def read_accessor(js, bin_, idx, comps):
    a = js["accessors"][idx]
    bv = js["bufferViews"][a["bufferView"]]
    start = bv.get("byteOffset", 0) + a.get("byteOffset", 0)
    ct, n = a["componentType"], a["count"]
    size = {5126: 4, 5123: 2, 5125: 4, 5121: 1}[ct]
    dt = {5126: np.float32, 5123: np.uint16, 5125: np.uint32, 5121: np.uint8}[ct]
    stride = bv.get("byteStride") or size * comps
    out = np.zeros((n, comps), dtype=np.float64)
    for i in range(n):
        base = start + i * stride
        for c in range(comps):
            out[i, c] = np.frombuffer(bin_, dtype=dt, count=1, offset=base + c * size)[0]
    if ct == 5121 and a.get("normalized"):
        out /= 255.0
    return out


def write_glb(path, pos, col, tri):
    cb = np.hstack([
        (np.clip(col, 0, 1) * 255).astype(np.uint8),
        np.full((len(col), 1), 255, np.uint8),
    ])
    pb = pos.astype(np.float32).tobytes()
    cbb = cb.tobytes()
    ib = tri.astype(np.uint32).ravel().tobytes()

    def pad4(b, fill):
        r = len(b) % 4
        return b + bytes([fill]) * (4 - r) if r else b

    pb, cbb, ib = pad4(pb, 0), pad4(cbb, 0), pad4(ib, 0)
    blob = pb + cbb + ib
    js = {
        "asset": {"version": "2.0", "generator": "EvilShao pig builder"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [{
            "attributes": {"POSITION": 0, "COLOR_0": 1}, "indices": 2, "mode": 4,
        }]}],
        "buffers": [{"byteLength": len(blob)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": 0, "byteLength": len(pb)},
            {"buffer": 0, "byteOffset": len(pb), "byteLength": len(cbb)},
            {"buffer": 0, "byteOffset": len(pb) + len(cbb), "byteLength": len(ib)},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(pos), "type": "VEC3",
             "min": [float(x) for x in pos.min(axis=0)],
             "max": [float(x) for x in pos.max(axis=0)]},
            {"bufferView": 1, "componentType": 5121, "normalized": True,
             "count": len(col), "type": "VEC4"},
            {"bufferView": 2, "componentType": 5125, "count": int(tri.size), "type": "SCALAR"},
        ],
    }
    jsb = pad4(json.dumps(js, separators=(",", ":")).encode(), 0x20)
    out = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(jsb) + 8 + len(blob))
    out += struct.pack("<II", len(jsb), 0x4E4F534A) + jsb
    out += struct.pack("<II", len(blob), 0x004E4942) + blob
    open(path, "wb").write(out)
    print(f"导出 {path}  {len(out)/1024/1024:.2f} MB  顶点 {len(pos)}  三角 {len(tri)}")


# ---------- 几何工具 ----------

def keep_largest_component(pos, col, tri):
    n = len(pos)
    e = np.vstack([tri[:, [0, 1]], tri[:, [1, 2]], tri[:, [2, 0]]])
    _nc, lab = connected_components(
        coo_matrix((np.ones(len(e)), (e[:, 0], e[:, 1])), shape=(n, n)), directed=False
    )
    keep = (lab[tri] == np.argmax(np.bincount(lab))).all(axis=1)
    tri = tri[keep]
    used = np.unique(tri)
    remap = -np.ones(n, dtype=np.int64)
    remap[used] = np.arange(len(used))
    return pos[used], col[used], remap[tri]


def vertex_normals(pos, tri):
    fn = np.cross(pos[tri[:, 1]] - pos[tri[:, 0]], pos[tri[:, 2]] - pos[tri[:, 0]])
    vn = np.zeros_like(pos)
    for k in range(3):
        np.add.at(vn, tri[:, k], fn)
    ln = np.linalg.norm(vn, axis=1)
    ln[ln == 0] = 1
    return vn / ln[:, None]


def rot_x_up_to_y(pos):
    """TripoSR 出来的模型 X 轴朝上，绕 Z 转 90° 让它站起来。"""
    c, s = np.cos(np.pi / 2), np.sin(np.pi / 2)
    rz = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
    p = pos @ rz.T
    return p - (p.max(axis=0) + p.min(axis=0)) / 2


# ---------- 三视图投影上色 ----------

def sample_view(img, pos, azim_deg, side):
    """
    把一个视角的参考图投到顶点上。

    pos 已经摆正居中；图片是「物体占 85%、512 见方、灰底」的构图，
    所以正交投影的映射是固定的。
    """
    h, w = img.shape[:2]
    side_len = max(np.ptp(pos[:, 0]), np.ptp(pos[:, 1])) / 0.85
    cx = (pos[:, 0].max() + pos[:, 0].min()) / 2
    cy = (pos[:, 1].max() + pos[:, 1].min()) / 2
    cz = (pos[:, 2].max() + pos[:, 2].min()) / 2

    if abs(azim_deg) < 45:                 # 正面：横轴是 x
        horiz = pos[:, 0] - cx
        flip = 1.0
    elif abs(abs(azim_deg) - 180) < 45:    # 背面：横轴是 x，但左右反
        horiz = pos[:, 0] - cx
        flip = -1.0
    else:                                  # 侧面：横轴是 z
        horiz = pos[:, 2] - cz
        flip = side                 # ±1，取决于是左视图还是右视图

    u = flip * horiz / side_len * w + w / 2
    v = (-(pos[:, 1] - cy)) / side_len * h + h / 2
    ui = np.clip(u.astype(int), 0, w - 1)
    vi = np.clip(v.astype(int), 0, h - 1)
    return img[vi, ui], vi / h


def project_multiview(pos, tri, views, sharp=2.0):
    """
    views: [(azim_deg, image, flip, half_deg)]

    每个视角只在 ±half 度的方位角窗口内说话，窗口外权重为 0。
    这样侧视图不会渗到脸上，正面图也不会跑到后脑勺。
    """
    vn = vertex_normals(pos, tri)
    az = np.degrees(np.arctan2(vn[:, 0], vn[:, 2]))   # 0 = 朝 +Z

    acc = np.zeros((len(pos), 3))
    wsum = np.zeros(len(pos))
    for azim, img, flip, half in views:
        d = np.abs(((az - azim + 180.0) % 360.0) - 180.0)
        wgt = np.clip(1.0 - d / half, 0.0, 1.0) ** sharp
        samp, vpos = sample_view(img, pos, azim, flip)
        lum = samp.mean(axis=1)
        # 灰底（没采到）不要；画面最底部的暗色是原图的地面阴影，也不要。
        # 注意别把所有暗色都拒掉——眼睛瞳孔本来就是暗的。
        bad = (np.abs(samp - 0.5).sum(axis=1) < 0.10) | ((lum < 0.34) & (vpos > 0.86))
        wgt = np.where(bad, 0.0, wgt)
        acc += samp * wgt[:, None]
        wsum += wgt

    covered = wsum > 0.02
    col = np.zeros_like(acc)
    col[covered] = acc[covered] / wsum[covered, None]
    print(f"  投影覆盖 {covered.sum()}/{len(pos)} ({covered.sum()/len(pos)*100:.0f}%)")

    if (~covered).any() and covered.any():
        tree = cKDTree(pos[covered])
        d, nn = tree.query(pos[~covered], k=10)
        wgt = 1.0 / (d + 1e-3)
        wgt /= wgt.sum(axis=1, keepdims=True)
        col[~covered] = (col[covered][nn] * wgt[:, :, None]).sum(axis=1)
    return np.clip(col, 0, 1)


# ---------- 清理 ----------

def clean(pos, col, tri):
    height = np.ptp(pos[:, 1])

    # 地面：底部一层几乎水平的三角
    fn = np.cross(pos[tri[:, 1]] - pos[tri[:, 0]], pos[tri[:, 2]] - pos[tri[:, 0]])
    ln = np.linalg.norm(fn, axis=1)
    ln[ln == 0] = 1
    fn /= ln[:, None]
    tri_y = pos[tri][:, :, 1].mean(axis=1)
    flat = (np.abs(fn[:, 1]) > 0.55) & (tri_y < pos[:, 1].min() + 0.09 * height)
    print(f"  砍掉水平地面 {int(flat.sum())} 三角")

    tri_col = col[tri].mean(axis=1)
    sat = tri_col.max(axis=1) - tri_col.min(axis=1)
    floor = (sat < 0.08) & (tri_col.max(axis=1) > 0.35) & (tri_y < pos[:, 1].min() + 0.10 * height)
    print(f"  砍掉灰色地面 {int(floor.sum())} 三角")

    # 脚下阴影：底部、不是粉色皮肤的
    pink = (tri_col[:, 0] > 0.60) & (tri_col[:, 0] > tri_col[:, 2] + 0.05) & (tri_col[:, 1] > 0.40)
    shadow = (~pink) & (tri_y < pos[:, 1].min() + 0.05 * height)
    print(f"  砍掉脚下阴影 {int(shadow.sum())} 三角")

    drop = floor | shadow | flat
    tri = tri[~drop]
    used = np.unique(tri)
    remap = -np.ones(len(pos), dtype=np.int64)
    remap[used] = np.arange(len(used))
    pos, col = pos[used], col[used]
    tri = remap[tri]
    pos[:, 1] -= pos[:, 1].min()
    return pos, col, tri


# ---------- 渲染预览 ----------

def render(pos, col, tri, path, yaw=0.0, size=700):
    p = pos - pos.mean(axis=0)
    c, s = np.cos(yaw), np.sin(yaw)
    p = p @ np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]]).T
    sc = size * 0.86 / max(np.ptp(p[:, 0]), np.ptp(p[:, 1]))
    x = p[:, 0] * sc + size / 2
    y = -p[:, 1] * sc + size / 2
    p0, p1, p2 = p[tri[:, 0]], p[tri[:, 1]], p[tri[:, 2]]
    nm = np.cross(p1 - p0, p2 - p0)
    ln = np.linalg.norm(nm, axis=1)
    ln[ln == 0] = 1
    nm /= ln[:, None]
    light = np.array([-0.35, 0.62, 0.70])
    light /= np.linalg.norm(light)
    shade = 0.58 + 0.42 * np.clip(nm @ light, 0, 1)
    fc = np.clip(col[tri].mean(axis=1) * shade[:, None], 0, 1)
    depth = p[tri][:, :, 2].mean(axis=1)
    img = Image.new("RGB", (size, size), (255, 255, 255))
    d = ImageDraw.Draw(img)
    X, Y = x[tri], y[tri]
    for t in np.argsort(depth):
        d.polygon([(X[t, 0], Y[t, 0]), (X[t, 1], Y[t, 1]), (X[t, 2], Y[t, 2])],
                  fill=tuple((fc[t] * 255).astype(int)))
    img.save(path)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    src, dst, front_p, side_p, back_p = args[:5]
    preview = "--views" in sys.argv

    js, bin_ = read_glb(src)
    prim = js["meshes"][0]["primitives"][0]
    pos = read_accessor(js, bin_, prim["attributes"]["POSITION"], 3)
    idx = read_accessor(js, bin_, prim["indices"], 1).astype(np.int64).ravel().reshape(-1, 3)
    print(f"读入 {len(pos)} 顶点 {len(idx)} 三角")

    dummy = np.zeros_like(pos)
    pos, _, idx = keep_largest_component(pos, dummy, idx)
    pos = rot_x_up_to_y(pos)
    print(f"摆正后尺寸 {np.ptp(pos, axis=0).round(3)}")

    img = lambda p: np.asarray(Image.open(p).convert("RGB")).astype(np.float32) / 255.0
    views = [
        (0.0, img(front_p), 1.0, 85.0),
        (180.0, img(back_p), 1.0, 85.0),
        (90.0, img(side_p), 1.0, float(os.environ.get("SIDE_DEG", "38"))),
        (-90.0, img(side_p), -1.0, float(os.environ.get("SIDE_DEG", "38"))),
    ]
    col = project_multiview(pos, idx, views)

    pos, col, idx = clean(pos, col, idx)
    write_glb(dst, pos, col, idx)

    if preview:
        for name, yaw in (("front", 0.0), ("q45", np.pi * 0.25),
                          ("side", np.pi * 0.5), ("back", np.pi)):
            render(pos, col, idx, dst.replace(".glb", f"-{name}.png"), yaw)
        print("预览渲染完成")


if __name__ == "__main__":
    main()
