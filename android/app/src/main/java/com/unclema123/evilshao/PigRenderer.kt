package com.unclema123.evilshao

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.sin

/**
 * 用 OpenGL ES 2.0 画的 3D 小猪。球体和圆柱体是真几何体，
 * 所以拖动能真的看到侧面、背面。
 */
class PigRenderer : GLSurfaceView.Renderer {

    /** 拖拽产生的朝向 */
    @Volatile
    var yaw = 0f

    @Volatile
    var pitch = 0f

    private class Part(
        val mesh: Mesh,
        val r: Float, val g: Float, val b: Float,
        var px: Float, var py: Float, var pz: Float,
        var rx: Float = 0f, var ry: Float = 0f, var rz: Float = 0f,
        var anim: ((Float, Part) -> Unit)? = null
    )

    private lateinit var parts: List<Part>

    private var prog = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val root = FloatArray(16)
    private val local = FloatArray(16)
    private val model = FloatArray(16)
    private val vm = FloatArray(16)
    private val mvp = FloatArray(16)

    private var startNs = 0L
    private var ready = false

    private val vs = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec3 aNrm;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            vN = mat3(uModel) * aNrm;
            vec4 w = uModel * vec4(aPos, 1.0);
            vW = w.xyz;
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """.trimIndent()

    private val fs = """
        precision mediump float;
        uniform vec3 uColor;
        varying vec3 vN;
        varying vec3 vW;
        void main() {
            vec3 n = normalize(vN);
            vec3 lightDir = normalize(vec3(-0.45, 0.75, 0.85));
            float diff = max(dot(n, lightDir), 0.0);
            vec3 eye = normalize(vec3(0.0, 0.3, 5.2) - vW);
            vec3 halfV = normalize(lightDir + eye);
            float spec = pow(max(dot(n, halfV), 0.0), 42.0) * 0.45;
            float rim = pow(1.0 - max(dot(n, eye), 0.0), 3.0) * 0.12;
            vec3 col = uColor * (0.42 + 0.70 * diff) + vec3(spec + rim);
            gl_FragColor = vec4(col, 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.992f, 0.917f, 0.941f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)

        prog = buildProgram(vs, fs)
        uMvp = GLES20.glGetUniformLocation(prog, "uMvp")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uColor = GLES20.glGetUniformLocation(prog, "uColor")

        parts = buildParts()
        startNs = System.nanoTime()
        ready = true
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
        Matrix.perspectiveM(proj, 0, 42f, aspect, 1f, 60f)
        Matrix.setLookAtM(view, 0, 0f, 0.16f, 5.4f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val t = (System.nanoTime() - startNs) / 1_000_000_000.0
        val tf = t.toFloat()

        Matrix.setIdentityM(root, 0)
        Matrix.rotateM(root, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(root, 0, yaw + sin(tf * 0.6f) * 3.0f, 0f, 1f, 0f)
        Matrix.translateM(root, 0, 0f, sin(tf * 1.7f) * 0.05f, 0f)

        GLES20.glUseProgram(prog)

        for (p in parts) {
            p.anim?.invoke(tf, p)

            Matrix.setIdentityM(local, 0)
            Matrix.translateM(local, 0, p.px, p.py, p.pz)
            if (p.rz != 0f) Matrix.rotateM(local, 0, p.rz, 0f, 0f, 1f)
            if (p.ry != 0f) Matrix.rotateM(local, 0, p.ry, 0f, 1f, 0f)
            if (p.rx != 0f) Matrix.rotateM(local, 0, p.rx, 1f, 0f, 0f)

            Matrix.multiplyMM(model, 0, root, 0, local, 0)
            Matrix.multiplyMM(vm, 0, view, 0, model, 0)
            Matrix.multiplyMM(mvp, 0, proj, 0, vm, 0)

            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES20.glUniform3f(uColor, p.r, p.g, p.b)

            val buf = p.mesh.data
            buf.position(0)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 24, buf)
            buf.position(3)
            GLES20.glEnableVertexAttribArray(1)
            GLES20.glVertexAttribPointer(1, 3, GLES20.GL_FLOAT, false, 24, buf)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, p.mesh.vertexCount)
            GLES20.glDisableVertexAttribArray(0)
            GLES20.glDisableVertexAttribArray(1)
        }
    }

    private fun buildParts(): List<Part> {
        val skin = Triple(0.976f, 0.757f, 0.816f)
        val skinDeep = Triple(0.933f, 0.639f, 0.722f)
        val snout = Triple(0.957f, 0.663f, 0.745f)
        val dark = Triple(0.639f, 0.361f, 0.447f)
        val eyeC = Triple(0.231f, 0.184f, 0.204f)
        val white = Triple(1f, 1f, 1f)

        fun part(mesh: Mesh, c: Triple<Float, Float, Float>, x: Float, y: Float, z: Float,
                 rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
                 anim: ((Float, Part) -> Unit)? = null) =
            Part(mesh, c.first, c.second, c.third, x, y, z, rx, ry, rz, anim)

        val list = ArrayList<Part>()

        // 身体
        list += part(Mesh.sphere(0.95f, 26, 32), skin, 0f, -0.62f, 0f)
        // 头
        val head = part(Mesh.sphere(0.88f, 26, 32), skin, 0f, 0.52f, -0.02f)
        head.anim = { t, p -> p.py = 0.52f + sin(t * 2.2f) * 0.022f }
        list += head

        // 耳朵（左右对称，往返摆动）
        val earL = part(Mesh.sphere(0.26f, 18, 22), skinDeep, -0.56f, 1.14f, 0.02f, rz = -24f)
        earL.anim = { t, p -> p.rz = -24f + sin(t * 2.5f) * 9f }
        val earR = part(Mesh.sphere(0.26f, 18, 22), skinDeep, 0.56f, 1.14f, 0.02f, rz = 24f)
        earR.anim = { t, p -> p.rz = 24f - sin(t * 2.5f) * 9f }
        list += earL
        list += earR

        // 眼睛
        list += part(Mesh.sphere(0.135f, 16, 20), eyeC, -0.31f, 0.66f, 0.70f)
        list += part(Mesh.sphere(0.135f, 16, 20), eyeC, 0.31f, 0.66f, 0.70f)
        // 高光
        list += part(Mesh.sphere(0.045f, 10, 12), white, -0.335f, 0.63f, 0.80f)
        list += part(Mesh.sphere(0.045f, 10, 12), white, 0.335f, 0.63f, 0.80f)

        // 猪鼻（圆柱体，绕 X 转 90° 让轴朝向观察者）
        list += part(Mesh.cylinder(0.30f, 0.24f, 30), snout, 0f, 0.34f, 0.82f, rx = 90f)
        // 鼻孔
        list += part(Mesh.cylinder(0.055f, 0.07f, 14), dark, -0.10f, 0.34f, 0.95f, rx = 90f)
        list += part(Mesh.cylinder(0.055f, 0.07f, 14), dark, 0.10f, 0.34f, 0.95f, rx = 90f)

        // 胳膊
        list += part(Mesh.cylinder(0.13f, 0.62f, 20), skin, -0.93f, -0.40f, 0f, rz = -15f)
        list += part(Mesh.cylinder(0.13f, 0.62f, 20), skin, 0.93f, -0.40f, 0f, rz = 15f)
        // 腿
        list += part(Mesh.cylinder(0.17f, 0.46f, 20), skin, -0.40f, -1.50f, 0f)
        list += part(Mesh.cylinder(0.17f, 0.46f, 20), skin, 0.40f, -1.50f, 0f)

        // 尾巴
        val tail = part(Mesh.cylinder(0.055f, 0.52f, 16), skinDeep, 0f, -0.88f, -0.72f, rx = -70f)
        tail.anim = { t, p -> p.rx = -70f + sin(t * 3.0f) * 14f }
        list += tail
        list += part(Mesh.sphere(0.10f, 14, 18), skinDeep, 0f, -0.74f, -1.00f)
        list += part(Mesh.sphere(0.075f, 12, 16), skinDeep, 0f, -0.62f, -0.90f)

        return list
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val v = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val f = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glBindAttribLocation(p, 0, "aPos")
        GLES20.glBindAttribLocation(p, 1, "aNrm")
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            throw RuntimeException("链接着色器失败: " + GLES20.glGetProgramInfoLog(p))
        }
        GLES20.glDeleteShader(v)
        GLES20.glDeleteShader(f)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            throw RuntimeException("编译着色器失败: " + GLES20.glGetShaderInfoLog(s))
        }
        return s
    }
}