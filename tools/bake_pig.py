"""
把 TripoSR 的模型烘成带贴图的 GLB（三视图上色）。

顶点色的分辨率受顶点密度限制，眼睛、牙齿、拉链这些细节必然糊。
烘到 UV 贴图上，细节由贴图分辨率承载，清晰度高一档，手和脚也跟着清楚。

流程：
1. TripoSR 出网格（mc-resolution 512）
2. xatlas 展开 UV
3. 用 moderngl 把每个纹素对应的 3D 位置「渲」进一张位置图
   （反过来做：知道 UV 纹素在模型上的位置，就能把参考图投上去）
4. 由位置图差分出法线，按法线方位角从正面/侧面/背面三张图取样
5. 导出 GLB（UV + 内嵌 PNG 贴图）

用法：
    python bake_pig.py <front.png> <side.png> <back.png> <out.glb> [--res 2048] [--preview]
"""

import io
import json
import os
import struct
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.environ.get("TRIPOSR_HOME", r"E:\Dev\TripoSR"))

import torch  # noqa: E402
from tsr.system import TSR  # noqa: E402
from tsr.bake_texture import make_atlas, rasterize_position_atlas  # noqa: E402

WEIGHTS = os.environ.get("TRIPOSR_WEIGHTS", r"E:\Dev\triposr-weights")
MC_RES = int(os.environ.get("MC_RES", "384"))


# ---------- 几何 ----------

def rot_x_up_to_y(pos):
    c, s = np.cos(np.pi / 2), np.sin(np.pi / 2)
    rz = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
    return pos @ rz.T


def normals_from_positions(pos_tex, alpha, scale):
    """
    位置图差分出法线。位置图是「纹素 → 模型空间坐标」，
    相邻纹素相减就是切向，叉乘得到法线。
    """
    h, w = pos_tex.shape[:2]
    px = pos_tex[:, :, 0]
    py = pos_tex[:, :, 1]
    pz = pos_tex[:, :, 2]

    def diff(a):
        gx = np.zeros_like(a)
        gy = np.zeros_like(a)
        gx[:, 1:-1] = a[:, 2:] - a[:, :-2]
        gy[1:-1, :] = a[2:, :] - a[:-2, :]
        return gx, gy

    gx = np.stack(diff(px), axis=-1)
    gy = np.stack(diff(py), axis=-1)
    gz = np.stack(diff(pz), axis=-1)
    tx = np.stack([gx[..., 0], gy[..., 0], gz[..., 0]], axis=-1)
    ty = np.stack([gx[..., 1], gy[..., 1], gz[..., 1]], axis=-1)
    n = np.cross(tx, ty)
    ln = np.linalg.norm(n, axis=2)
    ln[ln < 1e-9] = 1
    n /= ln[:, :, None]
    # 方向一致性：法线要和「从中心指向外」同侧
    center = pos_tex[alpha > 0].mean(axis=0)
    outward = ((pos_tex - center) * n).sum(axis=2)
    flip = outward < 0
    n[flip] *= -1
    return n


def sample_view(img, x, y, z, azim_deg, flip, half, side_len, center):
    """正交投影取样，返回颜色和有效掩码。"""
    h, w = img.shape[:2]
    if abs(azim_deg) < 45 or abs(abs(azim_deg) - 180) < 45:
        horiz = x - center[0]
        f = 1.0 if abs(azim_deg) < 45 else -1.0
    else:
        horiz = z - center[2]
        f = flip
    u = f * horiz / side_len * w + w / 2
    v = (-(y - center[1])) / side_len * h + h / 2
    ui = np.clip(u.astype(np.int32), 0, w - 1)
    vi = np.clip(v.astype(np.int32), 0, h - 1)
    return img[vi, ui], vi / h


# ---------- GLB ----------

def write_textured_glb(path, pos, uvs, tri, png_bytes):
    pb = np.ascontiguousarray(pos, dtype=np.float32).tobytes()
    ub = np.ascontiguousarray(uvs, dtype=np.float32).tobytes()
    ib = np.ascontiguousarray(tri, dtype=np.uint32).ravel().tobytes()
    png = png_bytes

    def pad4(b, fill):
        r = len(b) % 4
        return b + bytes([fill]) * (4 - r) if r else b

    pb, ub, ib, png = pad4(pb, 0), pad4(ub, 0), pad4(ib, 0), pad4(png, 0)
    blob = pb + ub + ib + png
    off_p, off_u, off_i, off_t = 0, len(pb), len(pb) + len(ub), len(pb) + len(ub) + len(ib)

    js = {
        "asset": {"version": "2.0", "generator": "EvilShao pig baker"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"mesh": 0}],
        "meshes": [{"primitives": [{
            "attributes": {"POSITION": 0, "TEXCOORD_0": 1},
            "indices": 2, "material": 0, "mode": 4,
        }]}],
        "materials": [{
            "name": "pig",
            "pbrMetallicRoughness": {
                "baseColorTexture": {"index": 0},
                "metallicFactor": 0.0,
                "roughnessFactor": 0.9,
            },
            "doubleSided": True,
        }],
        "textures": [{"sampler": 0, "source": 0}],
        "samplers": [{"magFilter": 9729, "minFilter": 9987, "wrapS": 33071, "wrapT": 33071}],
        "images": [{"bufferView": 3, "mimeType": "image/png"}],
        "buffers": [{"byteLength": len(blob)}],
        "bufferViews": [
            {"buffer": 0, "byteOffset": off_p, "byteLength": len(pb)},
            {"buffer": 0, "byteOffset": off_u, "byteLength": len(ub)},
            {"buffer": 0, "byteOffset": off_i, "byteLength": len(ib)},
            {"buffer": 0, "byteOffset": off_t, "byteLength": len(png)},
        ],
        "accessors": [
            {"bufferView": 0, "componentType": 5126, "count": len(pos), "type": "VEC3",
             "min": [float(v) for v in pos.min(axis=0)],
             "max": [float(v) for v in pos.max(axis=0)]},
            {"bufferView": 1, "componentType": 5126, "count": len(uvs), "type": "VEC2"},
            {"bufferView": 2, "componentType": 5125, "count": int(np.size(tri)), "type": "SCALAR"},
        ],
    }
    jsb = pad4(json.dumps(js, separators=(",", ":")).encode(), 0x20)
    out = struct.pack("<III", 0x46546C67, 2, 12 + 8 + len(jsb) + 8 + len(blob))
    out += struct.pack("<II", len(jsb), 0x4E4F534A) + jsb
    out += struct.pack("<II", len(blob), 0x004E4942) + blob
    open(path, "wb").write(out)
    print(f"导出 {path}  {len(out)/1024/1024:.2f} MB  顶点 {len(pos)}  三角 {len(tri)}")


# ---------- 主流程 ----------

def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    front_p, side_p, back_p, out_glb = args[:4]
    res = 2048
    if "--res" in sys.argv:
        res = int(sys.argv[sys.argv.index("--res") + 1])
    preview = "--preview" in sys.argv

    print("加载模型…")
    model = TSR.from_pretrained(WEIGHTS, config_name="config.yaml", weight_name="model.ckpt")
    model.renderer.set_chunk_size(8192)
    model.to("cuda")

    img = lambda p: np.asarray(Image.open(p).convert("RGB")).astype(np.float32) / 255.0
    front_img = Image.fromarray((img(front_p) * 255).astype(np.uint8))

    with torch.no_grad():
        scene_codes = model([front_img], device="cuda")

    print(f"提取网格（mc-resolution {MC_RES}）…")
    meshes = model.extract_mesh(scene_codes, True, resolution=MC_RES)
    mesh = meshes[0]
    print(f"  {len(mesh.vertices)} 顶点 {len(mesh.faces)} 面")

    # 减面。215k 面塞进 1024 贴图的话每个三角只有 5 个纹素，
    # UV 会碎成上万个小片，等于没有分辨率。减到几万面，每面才有足够纹素。
    target = int(os.environ.get("TARGET_FACES", "45000"))
    if len(mesh.faces) > target:
        import fast_simplification
        import trimesh
        v2, f2 = fast_simplification.simplify(
            np.asarray(mesh.vertices, dtype=np.float32),
            np.asarray(mesh.faces, dtype=np.int32),
            target_count=target,
        )
        new_mesh = trimesh.Trimesh(vertices=v2, faces=f2, process=False)
        print(f"  减面到 {len(new_mesh.vertices)} 顶点 {len(new_mesh.faces)} 面")
        mesh = new_mesh

    padding = 8
    print(f"展开 UV（{res}²）…")
    atlas = make_atlas(mesh, res, padding)
    vmapping = atlas["vmapping"]
    auvs = atlas["uvs"]
    atri = atlas["indices"]

    print("烘位置图…")
    pos_tex = rasterize_position_atlas(mesh, vmapping, atri, auvs, res, padding)
    alpha = pos_tex[:, :, 3]
    print(f"  有效纹素 {int((alpha > 0).sum())} ({float((alpha > 0).mean())*100:.1f}%)")

    # 法线也直接烘进贴图空间。在贴图空间里对位置图做差分是不行的：
    # UV 接缝两侧的纹素属于模型上完全不同的位置，差分会得到乱七八糟的法线，
    # 视角就选错（实测绿帽子上会糊上大片粉色）。
    print("烘法线图…")
    vn = np.asarray(mesh.vertex_normals, dtype=np.float32)
    c, s = np.cos(np.pi / 2), np.sin(np.pi / 2)
    vn = vn @ np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]]).T

    class _Duck:
        def __init__(self, v):
            self.vertices = v

    # 注意：rasterize_position_atlas 内部会自己按 vmapping 索引一次，
    # 所以这里要传「未映射」的逐顶点法线，不能再套一层 [vmapping]
    nrm_tex = rasterize_position_atlas(
        _Duck((vn * 0.5 + 0.5).astype("f4")), vmapping, atri, auvs, res, padding
    )
    nrm = nrm_tex[:, :, :3] * 2.0 - 1.0
    ln = np.linalg.norm(nrm, axis=2)
    ln[ln < 1e-6] = 1.0
    nrm /= ln[:, :, None]

    # 摆正：TripoSR 的模型 X 轴朝上
    verts = rot_x_up_to_y(np.asarray(mesh.vertices, dtype=np.float64)[vmapping])
    flat = pos_tex.reshape(-1, 4)
    flat_pos = rot_x_up_to_y(np.ascontiguousarray(flat[alpha.ravel() > 0, :3]))
    pts = flat_pos

    center = (pts.max(axis=0) + pts.min(axis=0)) / 2
    height = np.ptp(pts[:, 1])
    side_len = max(np.ptp(pts[:, 0]), height) / 0.85

    print("差分法线…")
    pos_rot = np.zeros_like(flat[:, :3])
    pos_rot[alpha.ravel() > 0] = flat_pos
    pos_rot_img = pos_rot.reshape(res, res, 3)
    az = np.degrees(np.arctan2(nrm[:, :, 0], nrm[:, :, 2]))

    views = [
        (0.0, img(front_p), 1.0, 85.0),
        (180.0, img(back_p), 1.0, 85.0),
        (90.0, img(side_p), 1.0, 45.0),
        (-90.0, img(side_p), -1.0, 45.0),
    ]

    print("三视图投影…")
    acc = np.zeros((res, res, 3), dtype=np.float32)
    wsum = np.zeros((res, res), dtype=np.float32)
    x, y, z = pos_rot_img[:, :, 0], pos_rot_img[:, :, 1], pos_rot_img[:, :, 2]
    for azim, image, flip, half in views:
        d = np.abs(((az - azim + 180.0) % 360.0) - 180.0)
        wgt = np.clip(1.0 - d / half, 0.0, 1.0) ** 2
        samp, vpos = sample_view(image, x, y, z, azim, flip, half, side_len, center)
        lum = samp.mean(axis=2)
        bad = (np.abs(samp - 0.5).sum(axis=2) < 0.10) | ((lum < 0.34) & (vpos > 0.86))
        wgt = np.where(bad, 0.0, wgt) * (alpha > 0)
        acc += samp * wgt[:, :, None]
        wsum += wgt

    cover = wsum > 1e-4
    print(f"  覆盖 {float(cover.sum())/max(1,float((alpha>0).sum()))*100:.0f}%")
    tex = np.zeros((res, res, 3), dtype=np.float32)
    tex[cover] = acc[cover] / wsum[cover][:, None]

    # 没覆盖到的纹素：从相邻已覆盖纹素扩散
    for _ in range(24):
        hole = (alpha > 0) & (wsum <= 1e-4)
        if not hole.any():
            break
        acc2 = np.zeros_like(tex)
        cnt = np.zeros((res, res), dtype=np.float32)
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            s = np.roll(np.roll(tex, dy, axis=0), dx, axis=1)
            m = np.roll(np.roll(cover, dy, axis=0), dx, axis=1)
            acc2 += s * m[:, :, None]
            cnt += m
        fill = hole & (cnt > 0)
        if not fill.any():
            break
        tex[fill] = acc2[fill] / cnt[fill][:, None]
        cover |= fill

    rgba = np.zeros((res, res, 4), dtype=np.uint8)
    rgba[:, :, :3] = np.clip(tex, 0, 1) * 255
    rgba[:, :, 3] = (alpha > 0).astype(np.uint8) * 255
    buf = io.BytesIO()
    Image.fromarray(rgba, "RGBA").save(buf, format="PNG")
    png = buf.getvalue()
    print(f"  贴图 PNG {len(png)/1024/1024:.2f} MB")

    # UV 不要翻：位置图就是按 xatlas 这套 UV 烘出来的，
    # PNG 第 0 行正好对应 v=0，采样时 v 直接用即可
    uvs = np.asarray(auvs, dtype=np.float32)
    verts = verts - np.array([0.0, verts[:, 1].min(), 0.0])
    verts[:, 0] -= (verts[:, 0].max() + verts[:, 0].min()) / 2
    verts[:, 2] -= (verts[:, 2].max() + verts[:, 2].min()) / 2

    write_textured_glb(out_glb, verts.astype(np.float32), uvs,
                       np.asarray(atri, dtype=np.uint32), png)

    out_png = out_glb.replace(".glb", "-texture.png")
    Image.fromarray(rgba, "RGBA").save(out_png)
    print(f"贴图另存 {out_png}")


if __name__ == "__main__":
    main()
