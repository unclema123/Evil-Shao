# tools

生成模型用的脚本。这些脚本依赖本机的 Python 环境，**不参与 App 构建**。

## build_pig.py —— 三视图上色（现在线上用的就是它）

把 TripoSR 出的网格摆正、按法线方位角从正面/侧面/背面三张参考图投影上色、清掉地面，再导出 GLB。

```powershell
E:\Dev\triposr-venv\Scripts\python.exe tools\build_pig.py `
  E:\EvilShao\gen2\0\mesh.glb `
  E:\EvilShao\android\app\src\main\assets\pig.glb `
  E:\EvilShao\ref-front-512.png `
  E:\EvilShao\ref-side-512.png `
  E:\EvilShao\ref-back-512.png `
  --views
```

- `--views` 会在输出旁边再渲染 正面 / 45° / 侧面 / 背面 四张预览图，改参数时不用开模拟器
- 侧视窗宽度可以用环境变量调：`$env:SIDE_DEG = "45"`，默认 38。
  **别调太大**：放到 55° 时侧脸的颜色会渗到腮帮子上

## bake_pig.py —— 烘 UV 贴图（未走通，仅存档）

思路是让贴图分辨率承载细节，替代顶点色。流程：TripoSR 出网格 → 减面 → xatlas 展开 → moderngl 把每个纹素的 3D 位置渲进位置图 → 三视图投影 → 导出带贴图的 GLB。

**当前状态：颜色错位，未接入 App。** 已修的问题和没定位的根因记在 `android/README.md` 的「试过但没走通的路」。

## mock_llm.py

本地假 LLM，用来在没有 key 的情况下测对话链路。

```powershell
python tools\mock_llm.py 8000
# 然后在 App 里把 baseUrl 设成 http://10.0.2.2:8000
```

## 环境

```
E:\Dev\triposr-venv         Python 虚拟环境（torch 2.4.1+cu121 / trimesh / scikit-image / fast-simplification）
E:\Dev\TripoSR              TripoSR 源码（已打两个补丁，见 android/README.md）
E:\Dev\triposr-weights      模型权重（model.ckpt）
```