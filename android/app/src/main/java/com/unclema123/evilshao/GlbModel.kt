package com.unclema123.evilshao

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * 一个极简的 GLB（glTF 2.0 二进制）加载器。
 *
 * 只认我们要用的那部分：POSITION / NORMAL / TEXCOORD_0 / 索引 / baseColor 贴图。
 * 骨骼、动画、材质扩展一律跳过 —— Meshy 出的静态模型用不上，
 * 少写一半代码，也少一半出错的机会。
 */
class GlbModel(
    val primitives: List<Primitive>,
    /** 贴图的原始字节（PNG/JPEG），渲染时再解码成 GL 纹理 */
    val images: List<ByteArray>,
    /** 模型包围盒，用来把模型缩放到合适大小、摆正位置 */
    val boundsMin: FloatArray,
    val boundsMax: FloatArray
) {

    class Primitive(
        val positions: FloatArray,
        val normals: FloatArray?,
        val uvs: FloatArray?,
        /** 顶点色（每顶点 3 个分量，0..1）；TripoSR 这类模型没有贴图，靠它上色 */
        val colors: FloatArray?,
        val indices: IntArray?,
        val textureIndex: Int
    ) {
        val vertexCount: Int get() = positions.size / 3
    }

    companion object {

        private const val MAGIC = 0x46546C67
        private const val CHUNK_JSON = 0x4E4F534A
        private const val CHUNK_BIN = 0x004E4942

        /** 解析失败就返回 null，让渲染器回退到自绘的小猪，不至于白屏 */
        fun parse(bytes: ByteArray): GlbModel? {
            return try {
                parseOrThrow(bytes)
            } catch (e: Exception) {
                android.util.Log.w("EvilShao", "GLB 解析失败: ${e.message}")
                null
            }
        }

        private fun parseOrThrow(bytes: ByteArray): GlbModel {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buf.int
            require(magic == MAGIC) { "不是 GLB 文件" }
            buf.int // version
            buf.int // total length

            var json: JSONObject? = null
            var bin: ByteArray? = null

            while (buf.remaining() >= 8) {
                val len = buf.int
                val type = buf.int
                if (len < 0 || len > buf.remaining()) break
                val data = ByteArray(len)
                buf.get(data)
                when (type) {
                    CHUNK_JSON -> json = JSONObject(String(data, StandardCharsets.UTF_8))
                    CHUNK_BIN -> bin = data
                }
            }

            val root = json ?: error("缺少 JSON 块")
            val binData = bin ?: ByteArray(0)

            // ── 贴图 ──
            val images = ArrayList<ByteArray>()
            root.optJSONArray("images")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val img = arr.getJSONObject(i)
                    val viewIdx = img.optInt("bufferView", -1)
                    if (viewIdx >= 0) {
                        val view = root.getJSONArray("bufferViews").getJSONObject(viewIdx)
                        val off = view.optInt("byteOffset", 0)
                        val len = view.getInt("byteLength")
                        if (off >= 0 && len > 0 && off + len <= binData.size) {
                            images.add(binData.copyOfRange(off, off + len))
                        } else {
                            images.add(ByteArray(0))
                        }
                    } else {
                        images.add(ByteArray(0))
                    }
                }
            }

            // ── 网格 ──
            val prims = ArrayList<Primitive>()
            val meshes = root.optJSONArray("meshes")
            if (meshes != null) {
                for (mi in 0 until meshes.length()) {
                    val mesh = meshes.getJSONObject(mi)
                    val parts = mesh.optJSONArray("primitives") ?: continue
                    for (pi in 0 until parts.length()) {
                        val p = parts.getJSONObject(pi)
                        val attrs = p.getJSONObject("attributes")

                        val posIdx = attrs.optInt("POSITION", -1)
                        if (posIdx < 0) continue
                        val positions = readFloats(root, binData, posIdx, 3) ?: continue
                        val normals = attrs.optInt("NORMAL", -1)
                            .takeIf { it >= 0 }?.let { readFloats(root, binData, it, 3) }
                        val uvs = attrs.optInt("TEXCOORD_0", -1)
                            .takeIf { it >= 0 }?.let { readFloats(root, binData, it, 2) }
                        // 顶点色：可能是 VEC3 也可能是 VEC4（带 alpha）
                        var colors = attrs.optInt("COLOR_0", -1)
                            .takeIf { it >= 0 }?.let { ci ->
                                val acc = root.getJSONArray("accessors").getJSONObject(ci)
                                val kind = acc.optString("type", "VEC4")
                                readFloats(root, binData, ci, if (kind == "VEC3") 3 else 4)
                            }
                        val indices = p.optInt("indices", -1)
                            .takeIf { it >= 0 }?.let { readIndices(root, binData, it) }

                        // 材质 → baseColor 贴图
                        var texIndex = -1
                        val matIdx = p.optInt("material", -1)
                        if (matIdx >= 0) {
                            val mat = root.optJSONArray("materials")?.optJSONObject(matIdx)
                            val pbr = mat?.optJSONObject("pbrMetallicRoughness")
                            val bct = pbr?.optJSONObject("baseColorTexture")
                            val texRef = bct?.optInt("index", -1) ?: -1
                            if (texRef >= 0) {
                                val tex = root.optJSONArray("textures")?.optJSONObject(texRef)
                                texIndex = tex?.optInt("source", -1) ?: -1
                            }
                        }

                        prims.add(
                            Primitive(positions, normals, uvs, colors, indices, texIndex)
                        )
                    }
                }
            }
            require(prims.isNotEmpty()) { "模型里没有可用的网格" }

            // ── 包围盒 ──
            val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
            val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
            for (p in prims) {
                var i = 0
                while (i < p.positions.size) {
                    for (k in 0 until 3) {
                        val v = p.positions[i + k]
                        if (v < mn[k]) mn[k] = v
                        if (v > mx[k]) mx[k] = v
                    }
                    i += 3
                }
            }

            return GlbModel(prims, images, mn, mx)
        }

        /**
         * 读一个浮点 accessor，转成扁平数组（每顶点 components 个分量）。
         * 支持 byteStride（交错存放），这是 glTF 允许的排布方式。
         */
        private fun readFloats(
            root: JSONObject, bin: ByteArray, accessorIdx: Int, components: Int
        ): FloatArray? {
            val acc = root.getJSONArray("accessors").getJSONObject(accessorIdx)
            val count = acc.getInt("count")
            val componentType = acc.optInt("componentType", 5126)
            val viewIdx = acc.optInt("bufferView", -1)
            if (viewIdx < 0) return null
            val view = root.getJSONArray("bufferViews").getJSONObject(viewIdx)
            val viewOffset = view.optInt("byteOffset", 0)
            val accOffset = acc.optInt("byteOffset", 0)
            val stride = view.optInt("byteStride", 0)

            val compSize = when (componentType) {
                5126 -> 4   // float
                5123 -> 2   // ushort
                5121 -> 1   // ubyte
                else -> 4
            }
            val elemSize = compSize * components
            val step = if (stride > 0) stride else elemSize

            val out = FloatArray(count * components)
            val base = viewOffset + accOffset
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            // normalized 的整数类型要映射回 0..1，否则颜色会是 0..255
            val normalized = acc.optBoolean("normalized", false)
            val intScale = when {
                !normalized -> 1f
                componentType == 5121 -> 1f / 255f
                componentType == 5123 -> 1f / 65535f
                else -> 1f
            }
            for (i in 0 until count) {
                val p = base + i * step
                if (p < 0 || p + elemSize > bin.size) break
                for (c in 0 until components) {
                    val o = p + c * compSize
                    val v = when (componentType) {
                        5126 -> bb.getFloat(o)
                        5123 -> (bb.getShort(o).toInt() and 0xFFFF).toFloat() * intScale
                        5121 -> (bb.get(o).toInt() and 0xFF).toFloat() * intScale
                        else -> 0f
                    }
                    out[i * components + c] = v
                }
            }
            return out
        }

        private fun readIndices(root: JSONObject, bin: ByteArray, accessorIdx: Int): IntArray? {
            val acc = root.getJSONArray("accessors").getJSONObject(accessorIdx)
            val count = acc.getInt("count")
            val componentType = acc.optInt("componentType", 5123)
            val viewIdx = acc.optInt("bufferView", -1)
            if (viewIdx < 0) return null
            val view = root.getJSONArray("bufferViews").getJSONObject(viewIdx)
            val base = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val bb = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN)
            val out = IntArray(count)
            for (i in 0 until count) {
                out[i] = when (componentType) {
                    5125 -> {
                        val o = base + i * 4
                        if (o + 4 <= bin.size) bb.getInt(o) else 0
                    }
                    5123 -> {
                        val o = base + i * 2
                        if (o + 2 <= bin.size) bb.getShort(o).toInt() and 0xFFFF else 0
                    }
                    5121 -> {
                        val o = base + i
                        if (o + 1 <= bin.size) bb.get(o).toInt() and 0xFF else 0
                    }
                    else -> 0
                }
            }
            return out
        }
    }
}