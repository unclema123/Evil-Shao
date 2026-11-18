package com.unclema123.evilshao

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/**
 * 画 Meshy 出的 GLB 模型（带贴图）。
 *
 * 模型没有骨骼也没有面部绑定，所以情绪不靠表情，靠**整体动作**：
 * 开心是蹦、生气是压着抖、惊讶是一弹、害羞是歪着缩、无聊是慢慢塌下去。
 * 动作比表情粗糙，但配上她说的话，观感是够的。
 */
class GlbRenderer(private val context: Context) : GLSurfaceView.Renderer {

    enum class Mood { NEUTRAL, HAPPY, ANGRY, SURPRISED, SHY, BORED }

    @Volatile
    var yaw = 0f

    @Volatile
    var pitch = 0f

    @Volatile
    private var mood = Mood.NEUTRAL

    @Volatile
    private var moodUntil = 0L

    /** 换情绪，ms 毫秒后回到平常 */
    fun show(m: Mood, ms: Long = 2800L) {
        if (m != mood) android.util.Log.d("EvilShao", "情绪 → $m")
        mood = m
        moodUntil = System.currentTimeMillis() + ms
    }

    fun wave(ms: Long = 2400L) {
        // 静态模型没有胳膊可抬，用一次蹦代替
        show(Mood.HAPPY, ms)
    }

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aUv = 0
    private var aColor = 0
    private var uMvp = 0
    private var uModel = 0
    private var uTex = 0
    private var uLight = 0
    private var uVertexColor = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val tmp = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)

    private class Drawable(
        val vbo: FloatBuffer,
        val ibo: IntBuffer?,
        val count: Int,
        val texture: Int,
        val hasVertexColor: Boolean
    )

    private val drawables = ArrayList<Drawable>()
    private var textures = IntArray(0)
    private val whiteTex = IntArray(1)
    private var loaded = false
    private var loadFailed = false

    /** 模型归一化：把模型缩放到高度约 3.2，并把中心挪到原点 */
    private var scale = 1f
    private var centerX = 0f
    private var centerY = 0f
    private var centerZ = 0f

    private var startTime = 0L
    private var moodAmt = 0f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.992f, 0.918f, 0.941f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        program = buildProgram(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uVertexColor = GLES20.glGetUniformLocation(program, "uVertexColor")

        // 没贴图的模型要绑一张纯白纹理，否则采样到的是黑
        val white = ByteBuffer.allocateDirect(4).put(
            byteArrayOf(-1, -1, -1, -1)
        ).also { it.position(0) }
        GLES20.glGenTextures(1, whiteTex, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, whiteTex[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, white
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)

        startTime = System.currentTimeMillis()
        loadModel()
    }

    private fun loadModel() {
        if (loaded || loadFailed) return
        val bytes = try {
            context.assets.open("pig.glb").readBytes()
        } catch (e: Exception) {
            android.util.Log.w("EvilShao", "assets/pig.glb 读不到: ${e.message}")
            loadFailed = true
            return
        }
        val glb = GlbModel.parse(bytes)
        if (glb == null) {
            loadFailed = true
            return
        }

        // 贴图
        textures = IntArray(glb.images.size)
        if (glb.images.isNotEmpty()) {
            GLES20.glGenTextures(glb.images.size, textures, 0)
            for (i in glb.images.indices) {
                val bmp = try {
                    BitmapFactory.decodeByteArray(glb.images[i], 0, glb.images[i].size)
                } catch (e: Exception) {
                    null
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[i])
                if (bmp != null) {
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
                    bmp.recycle()
                } else {
                    // 没贴上就填个白，模型至少还能看见轮廓
                    GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 1, 1, 0,
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                        ByteBuffer.allocateDirect(4).put(byteArrayOf(-1, -1, -1, -1))
                    )
                }
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE
                )
                GLES20.glTexParameteri(
                    GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE
                )
            }
        }

        // 归一化到高度 2.85（留出气泡和输入框的空间）
        val height = (glb.boundsMax[1] - glb.boundsMin[1]).coerceAtLeast(0.0001f)
        scale = 2.85f / height
        centerX = (glb.boundsMin[0] + glb.boundsMax[0]) * 0.5f
        centerY = (glb.boundsMin[1] + glb.boundsMax[1]) * 0.5f
        centerZ = (glb.boundsMin[2] + glb.boundsMax[2]) * 0.5f

        for (p in glb.primitives) {
            val n = p.vertexCount
            // 每个顶点：位置 3 + 法线 3 + UV 2 + 顶点色 3 = 11 个 float
            val stride = 11
            val data = ByteBuffer.allocateDirect(n * stride * 4)
                .order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

            val normals = p.normals
            val uvs = p.uvs
            val colors = p.colors
            val hasColor = colors != null
            for (i in 0 until n) {
                data.put(p.positions[i * 3])
                data.put(p.positions[i * 3 + 1])
                data.put(p.positions[i * 3 + 2])
                if (normals != null && normals.size >= (i + 1) * 3) {
                    data.put(normals[i * 3]); data.put(normals[i * 3 + 1]); data.put(normals[i * 3 + 2])
                } else {
                    data.put(0f); data.put(0f); data.put(1f)
                }
                if (uvs != null && uvs.size >= (i + 1) * 2) {
                    data.put(uvs[i * 2]); data.put(1f - uvs[i * 2 + 1])
                } else {
                    data.put(0f); data.put(0f)
                }
                if (colors != null && colors.size >= (i + 1) * 3) {
                    val ci = if (colors.size >= (i + 1) * 4) i * 4 else i * 3
                    data.put(colors[ci]); data.put(colors[ci + 1]); data.put(colors[ci + 2])
                } else {
                    data.put(1f); data.put(1f); data.put(1f)
                }
            }
            data.position(0)

            val ibo = p.indices?.let {
                ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    .asIntBuffer().apply { put(it); position(0) }
            }
            val tex = if (p.textureIndex in textures.indices) textures[p.textureIndex] else whiteTex[0]
            Drawable(
                data, ibo,
                p.indices?.size ?: n,
                tex,
                hasColor
            ).also { drawables.add(it) }
        }

        loaded = true
        android.util.Log.d("EvilShao", "GLB 载入: ${glb.primitives.size} 个网格, ${glb.images.size} 张贴图")
    }

    fun isLoaded(): Boolean = loaded

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 34f, aspect, 0.5f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 情绪到期回落到平常
        val now = System.currentTimeMillis()
        if (now > moodUntil) mood = Mood.NEUTRAL

        val target = mood
        val targetAmt = when (target) {
            Mood.NEUTRAL -> 0f
            else -> 1f
        }
        moodAmt += (targetAmt - moodAmt) * 0.10f

        val t = (now - startTime) / 1000f
        val energy = when (target) {
            Mood.HAPPY -> 1.9f
            Mood.SURPRISED -> 1.5f
            Mood.ANGRY -> 0.9f
            Mood.SHY -> 0.5f
            Mood.BORED -> 0.25f
            Mood.NEUTRAL -> 1f
        }

        // 呼吸 + 情绪动作
        val breathe = sin(t * 1.6f) * 0.012f * energy
        var dy = breathe
        var dz = 0f
        var sx = 1f
        var sy = 1f
        var rz = 0f

        when (target) {
            Mood.HAPPY -> {
                // 蹦：abs(sin) 做成跳
                dy += kotlin.math.abs(sin(t * 5.2f)) * 0.20f * moodAmt
                sy = 1f + sin(t * 5.2f) * 0.045f * moodAmt
                sy = sy - (1f - sy) * 0.0f
                sx = 1f - (sy - 1f) * 0.5f
                rz = sin(t * 5.2f) * 4f * moodAmt
            }
            Mood.SURPRISED -> {
                // 一弹：先缩再弹大
                val k = kotlin.math.exp(-((now - (moodUntil - 2800L)) / 380f).toFloat())
                dy += 0.10f * k * moodAmt
                sy = 1f + 0.10f * k * moodAmt
                sx = 1f - 0.05f * k * moodAmt
            }
            Mood.ANGRY -> {
                rz = sin(t * 22f) * 2.4f * moodAmt
                dy -= 0.04f * moodAmt
                sy = 1f - 0.03f * moodAmt
                sx = 1f + 0.03f * moodAmt
            }
            Mood.SHY -> {
                rz = 7f * moodAmt + sin(t * 1.3f) * 2f * moodAmt
                dy -= 0.03f * moodAmt
                sx = 1f + 0.02f * moodAmt
            }
            Mood.BORED -> {
                rz = -6f * moodAmt
                dy -= 0.06f * moodAmt
                sy = 1f - 0.02f * moodAmt
            }
            Mood.NEUTRAL -> Unit
        }

        // 镜头
        Matrix.setLookAtM(view, 0, 0f, 0.10f, 8.2f, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, 0f, dy, 0f)
        Matrix.rotateM(model, 0, yaw, 0f, 1f, 0f)
        Matrix.rotateM(model, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, rz, 0f, 0f, 1f)
        Matrix.scaleM(model, 0, sx * scale, sy * scale, sx * scale)
        Matrix.translateM(model, 0, -centerX, -centerY, -centerZ)

        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform3f(uLight, -0.35f, 0.72f, 0.60f)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)

        for (d in drawables) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, d.texture)
            GLES20.glUniform1f(uVertexColor, if (d.hasVertexColor) 1f else 0f)

            d.vbo.position(0)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 44, d.vbo)

            GLES20.glEnableVertexAttribArray(aNormal)
            d.vbo.position(3)
            GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 44, d.vbo)

            GLES20.glEnableVertexAttribArray(aUv)
            d.vbo.position(6)
            GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 44, d.vbo)

            GLES20.glEnableVertexAttribArray(aColor)
            d.vbo.position(8)
            GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 44, d.vbo)

            val ibo = d.ibo
            if (ibo != null) {
                ibo.position(0)
                GLES20.glDrawElements(
                    GLES20.GL_TRIANGLES, d.count, GLES20.GL_UNSIGNED_INT, ibo
                )
            } else {
                GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, d.count)
            }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vs)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }

    companion object {
        private const val VS = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec2 aUv;
            attribute vec3 aColor;
            varying vec2 vUv;
            varying vec3 vN;
            varying vec3 vC;
            void main() {
                vUv = aUv;
                vC = aColor;
                vN = normalize((uModel * vec4(aNormal, 0.0)).xyz);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec3 uLight;
            uniform float uVertexColor;
            varying vec2 vUv;
            varying vec3 vN;
            varying vec3 vC;
            void main() {
                vec4 texel = texture2D(uTex, vUv);
                // 有顶点色就用顶点色（TripoSR 这类没贴图的模型），否则用贴图
                vec3 base = mix(texel.rgb, vC * texel.rgb, uVertexColor);
                vec3 n = normalize(vN);
                float diff = max(dot(n, normalize(uLight)), 0.0);
                float fill = max(dot(n, normalize(vec3(0.55, 0.1, 0.8))), 0.0) * 0.25;
                float lum = 0.62 + 0.50 * diff + fill;
                gl_FragColor = vec4(base * lum, 1.0);
            }
        """
    }
}