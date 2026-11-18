package com.unclema123.evilshao

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一个极简的三角形网格：交错存放 position(3) + normal(3)。
 *
 * 半径和高度在构建时就烘进顶点数据里，所以运行时只做旋转和平移、
 * 不做非等比缩放 —— 法线矩阵就等于旋转矩阵，省掉求逆转置。
 */
class Mesh private constructor(val data: FloatBuffer, val vertexCount: Int) {

    companion object {

        private fun build(verts: MutableList<Float>): Mesh {
            val arr = FloatArray(verts.size)
            for (i in verts.indices) arr[i] = verts[i]
            val buf = ByteBuffer.allocateDirect(arr.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            buf.put(arr)
            buf.position(0)
            return Mesh(buf, arr.size / 6)
        }

        private fun putVertex(v: MutableList<Float>, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) {
            v.add(x); v.add(y); v.add(z)
            v.add(nx); v.add(ny); v.add(nz)
        }

        private fun putTri(v: MutableList<Float>, a: FloatArray, b: FloatArray, c: FloatArray) {
            putVertex(v, a[0], a[1], a[2], a[3], a[4], a[5])
            putVertex(v, b[0], b[1], b[2], b[3], b[4], b[5])
            putVertex(v, c[0], c[1], c[2], c[3], c[4], c[5])
        }

        /** 球面点：x,y,z + 法线（球心在原点，所以法线就是归一化坐标） */
        private fun spherePoint(r: Float, phi: Float, theta: Float): FloatArray {
            val x = r * sin(phi) * sin(theta)
            val y = r * cos(phi)
            val z = r * sin(phi) * cos(theta)
            fun n(v: Float) = v / r
            return floatArrayOf(x, y, z, n(x), n(y), n(z))
        }

        fun sphere(radius: Float, rings: Int = 22, sectors: Int = 28): Mesh {
            val v = ArrayList<Float>(rings * sectors * 36)
            for (i in 0 until rings) {
                val phi0 = i.toFloat() / rings * Math.PI.toFloat()
                val phi1 = (i + 1).toFloat() / rings * Math.PI.toFloat()
                for (j in 0 until sectors) {
                    val th0 = j.toFloat() / sectors * 2f * Math.PI.toFloat()
                    val th1 = (j + 1).toFloat() / sectors * 2f * Math.PI.toFloat()
                    val a = spherePoint(radius, phi0, th0)
                    val b = spherePoint(radius, phi0, th1)
                    val c = spherePoint(radius, phi1, th1)
                    val d = spherePoint(radius, phi1, th0)
                    putTri(v, a, b, c)
                    putTri(v, a, c, d)
                }
            }
            return build(v)
        }

        /**
         * 长方体，中心在原点。用来做眉毛这种硬边小零件。
         */
        fun box(w: Float, h: Float, d: Float): Mesh {
            val v = ArrayList<Float>(72)
            val x = w / 2f
            val y = h / 2f
            val z = d / 2f

            fun quad(a: FloatArray, b: FloatArray, c: FloatArray, dd: FloatArray) {
                putTri(v, a, b, c)
                putTri(v, a, c, dd)
            }

            // +Z
            quad(floatArrayOf(-x, -y, z, 0f, 0f, 1f), floatArrayOf(x, -y, z, 0f, 0f, 1f),
                 floatArrayOf(x, y, z, 0f, 0f, 1f), floatArrayOf(-x, y, z, 0f, 0f, 1f))
            // -Z
            quad(floatArrayOf(x, -y, -z, 0f, 0f, -1f), floatArrayOf(-x, -y, -z, 0f, 0f, -1f),
                 floatArrayOf(-x, y, -z, 0f, 0f, -1f), floatArrayOf(x, y, -z, 0f, 0f, -1f))
            // +X
            quad(floatArrayOf(x, -y, z, 1f, 0f, 0f), floatArrayOf(x, -y, -z, 1f, 0f, 0f),
                 floatArrayOf(x, y, -z, 1f, 0f, 0f), floatArrayOf(x, y, z, 1f, 0f, 0f))
            // -X
            quad(floatArrayOf(-x, -y, -z, -1f, 0f, 0f), floatArrayOf(-x, -y, z, -1f, 0f, 0f),
                 floatArrayOf(-x, y, z, -1f, 0f, 0f), floatArrayOf(-x, y, -z, -1f, 0f, 0f))
            // +Y
            quad(floatArrayOf(-x, y, z, 0f, 1f, 0f), floatArrayOf(x, y, z, 0f, 1f, 0f),
                 floatArrayOf(x, y, -z, 0f, 1f, 0f), floatArrayOf(-x, y, -z, 0f, 1f, 0f))
            // -Y
            quad(floatArrayOf(-x, -y, -z, 0f, -1f, 0f), floatArrayOf(x, -y, -z, 0f, -1f, 0f),
                 floatArrayOf(x, -y, z, 0f, -1f, 0f), floatArrayOf(-x, -y, z, 0f, -1f, 0f))
            return build(v)
        }

        /**
         * 圆柱，轴沿 Y，中心在原点。用极小的半径就能当细杆（胳膊腿尾巴）。
         */
        fun cylinder(radius: Float, height: Float, segments: Int = 26): Mesh {
            val v = ArrayList<Float>(segments * 36)
            val hy = height / 2f
            for (j in 0 until segments) {
                val a0 = j.toFloat() / segments * 2f * Math.PI.toFloat()
                val a1 = (j + 1).toFloat() / segments * 2f * Math.PI.toFloat()
                val x0 = radius * sin(a0); val z0 = radius * cos(a0)
                val x1 = radius * sin(a1); val z1 = radius * cos(a1)
                val n0 = floatArrayOf(sin(a0), 0f, cos(a0), sin(a0), 0f, cos(a0))
                val n1 = floatArrayOf(sin(a1), 0f, cos(a1), sin(a1), 0f, cos(a1))

                val p00 = floatArrayOf(x0, -hy, z0, n0[0], 0f, n0[2])
                val p10 = floatArrayOf(x0, hy, z0, n0[0], 0f, n0[2])
                val p11 = floatArrayOf(x1, hy, z1, n1[0], 0f, n1[2])
                val p01 = floatArrayOf(x1, -hy, z1, n1[0], 0f, n1[2])
                putTri(v, p00, p10, p11)
                putTri(v, p00, p11, p01)

                // 上下两个盖
                val cTop = floatArrayOf(0f, hy, 0f, 0f, 1f, 0f)
                val cBot = floatArrayOf(0f, -hy, 0f, 0f, -1f, 0f)
                val t0 = floatArrayOf(x0, hy, z0, 0f, 1f, 0f)
                val t1 = floatArrayOf(x1, hy, z1, 0f, 1f, 0f)
                putTri(v, cTop, t0, t1)

                val b0 = floatArrayOf(x0, -hy, z0, 0f, -1f, 0f)
                val b1 = floatArrayOf(x1, -hy, z1, 0f, -1f, 0f)
                putTri(v, cBot, b1, b0)
            }
            return build(v)
        }
    }
}