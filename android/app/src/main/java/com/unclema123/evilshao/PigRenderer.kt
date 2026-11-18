package com.unclema123.evilshao

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sin

/**
 * 用 OpenGL ES 2.0 画的 3D 小猪。
 *
 * 造型上走了 Q 版路线：头比身子大、眼睛大、鼻子小、四肢短，
 * 脸上有眉毛、腮红和能开合的嘴，所以能做出表情。
 *
 * 表情不是关键帧动画，而是一组「脸部参数」——眼睛开合、眉毛角度、
 * 嘴张开度、嘴角弧度、歪头角度、腮红强度、瞳孔偏移。
 * 每个表情给出这组参数的目标值，渲染时逐帧插值过去，
 * 所以表情变化是连续的，不会突然跳变。
 */
class PigRenderer : GLSurfaceView.Renderer {

    /** 小猪能做的表情 */
    enum class Mood { NEUTRAL, HAPPY, ANGRY, SURPRISED, SHY, BORED }

    /** 拖拽产生的朝向 */
    @Volatile
    var yaw = 0f

    @Volatile
    var pitch = 0f

    @Volatile
    private var mood = Mood.NEUTRAL

    @Volatile
    private var moodUntil = 0L

    @Volatile
    private var waveUntil = 0L

    private var waveAmt = 0f

    /** 抬手挥一挥，ms 毫秒后放下 */
    fun wave(ms: Long = 2400L) {
        waveUntil = System.currentTimeMillis() + ms
    }

    /** 换表情，ms 毫秒后自动回到平常表情 */
    fun show(m: Mood, ms: Long = 2800L) {
        if (m != mood) android.util.Log.d("EvilShao", "表情 → $m")
        mood = m
        moodUntil = System.currentTimeMillis() + ms
    }

    private class Part(
        val mesh: Mesh,
        val r: Float, val g: Float, val b: Float,
        var px: Float, var py: Float, var pz: Float,
        var rx: Float = 0f, var ry: Float = 0f, var rz: Float = 0f,
        var sx: Float = 1f, var sy: Float = 1f, var sz: Float = 1f,
        /** 跟着头一起转（歪头的时候耳朵、五官要一起动） */
        var onHead: Boolean = false,
        var anim: ((Float, Part) -> Unit)? = null
    )

    /**
     * 一组脸的表情参数。带 lerp 是因为要平滑过渡。
     */
    private class Face {
        var eyeOpen = 1f        // 眼睛竖向开合，1 = 正常，0.4 = 眯眼，1.3 = 瞪大
        var browTilt = 0f       // 眉毛内端下压角度，正数 = 生气
        var browLift = 0f       // 眉毛整体上下偏移
        var mouthOpen = 0.12f   // 嘴张开度
        var mouthCurve = 0.18f  // 嘴角弧度，正 = 笑，负 = 撇嘴
        var headTilt = 0f       // 歪头角度
        var blush = 0f          // 腮红强度
        var pupilShift = 0f     // 瞳孔左右偏移

        fun set(
            eye: Float, tilt: Float, lift: Float, mOpen: Float,
            mCurve: Float, hTilt: Float, bl: Float, pup: Float
        ) {
            eyeOpen = eye; browTilt = tilt; browLift = lift
            mouthOpen = mOpen; mouthCurve = mCurve
            headTilt = hTilt; blush = bl; pupilShift = pup
        }

        fun approach(o: Face, k: Float) {
            eyeOpen += (o.eyeOpen - eyeOpen) * k
            browTilt += (o.browTilt - browTilt) * k
            browLift += (o.browLift - browLift) * k
            mouthOpen += (o.mouthOpen - mouthOpen) * k
            mouthCurve += (o.mouthCurve - mouthCurve) * k
            headTilt += (o.headTilt - headTilt) * k
            blush += (o.blush - blush) * k
            pupilShift += (o.pupilShift - pupilShift) * k
        }
    }

    private val face = Face()
    private val aim = Face()

    private var blink = 0f
    private var energy = 1f

    private lateinit var parts: List<Part>

    private var prog = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val root = FloatArray(16)
    private val headM = FloatArray(16)
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
            // 补光：从右前方补一层，免得右半边脸和猪鼻全掉进阴影里
            float fill = max(dot(n, normalize(vec3(0.65, 0.10, 0.75))), 0.0) * 0.26;
            vec3 eye = normalize(vec3(0.0, 0.3, 5.2) - vW);
            vec3 halfV = normalize(lightDir + eye);
            float spec = pow(max(dot(n, halfV), 0.0), 60.0) * 0.22;
            float rim = pow(1.0 - max(dot(n, eye), 0.0), 3.0) * 0.14;
            vec3 col = uColor * (0.50 + 0.58 * diff + fill) + vec3(spec + rim);
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
        Matrix.setLookAtM(view, 0, 0f, 0.20f, 5.35f, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!ready) return
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val t = (System.nanoTime() - startNs) / 1_000_000_000.0
        val tf = t.toFloat()

        // 表情到点自动回平常
        if (mood != Mood.NEUTRAL && System.currentTimeMillis() > moodUntil) mood = Mood.NEUTRAL
        aimFor(mood, aim)
        face.approach(aim, 0.16f)

        // 眨眼：每 4.4 秒眨一次，0.2 秒完成
        val bt = tf % 4.4f
        blink = when {
            bt < 0.10f -> bt / 0.10f
            bt < 0.20f -> (0.20f - bt) / 0.10f
            else -> 0f
        }

        val targetEnergy = when (mood) {
            Mood.HAPPY -> 1.9f
            Mood.SURPRISED -> 1.4f
            Mood.ANGRY -> 0.7f
            Mood.SHY -> 0.45f
            Mood.BORED -> 0.3f
            Mood.NEUTRAL -> 1f
        }
        energy += (targetEnergy - energy) * 0.08f

        // 挥手：抬手和放下都做插值，不会瞬间抬起
        val waveTarget = if (System.currentTimeMillis() < waveUntil) 1f else 0f
        waveAmt += (waveTarget - waveAmt) * 0.12f

        Matrix.setIdentityM(root, 0)
        Matrix.rotateM(root, 0, pitch, 1f, 0f, 0f)
        Matrix.rotateM(root, 0, yaw + sin(tf * 0.6f) * 3.0f, 0f, 1f, 0f)
        Matrix.translateM(root, 0, 0f, sin(tf * 1.7f) * 0.04f * energy, 0f)

        // 头部：以脖子为轴做呼吸起伏 + 歪头
        val bob = sin(tf * 2.2f) * 0.022f * energy
        Matrix.setIdentityM(headM, 0)
        Matrix.translateM(headM, 0, 0f, 0.50f + bob, 0f)
        Matrix.rotateM(headM, 0, face.headTilt, 0f, 0f, 1f)
        Matrix.translateM(headM, 0, 0f, -0.50f, 0f)

        GLES20.glUseProgram(prog)

        for (p in parts) {
            p.anim?.invoke(tf, p)

            Matrix.setIdentityM(local, 0)
            Matrix.translateM(local, 0, p.px, p.py, p.pz)
            if (p.sx != 1f || p.sy != 1f || p.sz != 1f) {
                Matrix.scaleM(local, 0, p.sx, p.sy, p.sz)
            }
            if (p.rz != 0f) Matrix.rotateM(local, 0, p.rz, 0f, 0f, 1f)
            if (p.ry != 0f) Matrix.rotateM(local, 0, p.ry, 0f, 1f, 0f)
            if (p.rx != 0f) Matrix.rotateM(local, 0, p.rx, 1f, 0f, 0f)

            if (p.onHead) {
                Matrix.multiplyMM(vm, 0, headM, 0, local, 0)
                Matrix.multiplyMM(model, 0, root, 0, vm, 0)
            } else {
                Matrix.multiplyMM(model, 0, root, 0, local, 0)
            }
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

    /** 每个表情对应的脸部参数 */
    private fun aimFor(m: Mood, f: Face) {
        when (m) {
            //                 眼开  眉压   眉升   嘴开   嘴角   歪头   腮红   瞳孔
            Mood.NEUTRAL -> f.set(1.00f, 0f, 0.00f, 0.10f, 0.18f, 0f, 0.10f, 0f)
            Mood.HAPPY -> f.set(0.40f, -5f, 0.04f, 0.62f, 0.95f, -4f, 0.70f, 0f)
            Mood.ANGRY -> f.set(0.88f, 25f, -0.05f, 0.26f, -0.80f, 5f, 0.25f, 0f)
            Mood.SURPRISED -> f.set(1.32f, -11f, 0.11f, 0.95f, 0.10f, 0f, 0.35f, -0.02f)
            Mood.SHY -> f.set(0.48f, -7f, 0.02f, 0.18f, 0.70f, 9f, 1.00f, 0.05f)
            Mood.BORED -> f.set(0.46f, 8f, 0.00f, 0.04f, -0.28f, -8f, 0f, -0.035f)
        }
    }

    /** 眼睛竖向的最终缩放：表情开合 × 眨眼 */
    private fun eyeScale(): Float = max(0.06f, face.eyeOpen * (1f - blink * 0.95f))

    private fun buildParts(): List<Part> {
        val skin = Triple(0.984f, 0.784f, 0.835f)
        val skinDeep = Triple(0.941f, 0.667f, 0.741f)
        val snout = Triple(0.965f, 0.698f, 0.769f)
        val dark = Triple(0.671f, 0.396f, 0.478f)
        val eyeC = Triple(0.180f, 0.141f, 0.157f)
        val white = Triple(1f, 1f, 1f)
        val browC = Triple(0.565f, 0.372f, 0.427f)
        val blushC = Triple(0.973f, 0.612f, 0.702f)
        val lipC = Triple(0.451f, 0.263f, 0.318f)
        val cavityC = Triple(0.545f, 0.216f, 0.318f)
        // 恐龙连帽衫的配色
        val hoodGreen = Triple(0.435f, 0.553f, 0.365f)
        val hoodDark = Triple(0.345f, 0.447f, 0.278f)
        val bellyC = Triple(0.898f, 0.871f, 0.596f)
        val toothC = Triple(0.988f, 0.976f, 0.945f)
        val hoofC = Triple(0.400f, 0.290f, 0.235f)

        fun part(mesh: Mesh, c: Triple<Float, Float, Float>, x: Float, y: Float, z: Float,
                 rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
                 sx: Float = 1f, sy: Float = 1f, sz: Float = 1f,
                 onHead: Boolean = false,
                 anim: ((Float, Part) -> Unit)? = null) =
            Part(mesh, c.first, c.second, c.third, x, y, z, rx, ry, rz, sx, sy, sz, onHead, anim)

        val list = ArrayList<Part>()
        val headR = 0.96f
        val headY = 0.50f
        val headZ = 0.06f

        /** 头表面在某一点的 z，五官都按它来贴，改头的半径也不会埋进脸里 */
        fun surfZ(x: Float, y: Float): Float {
            val dx = x
            val dy = y - headY
            val r2 = headR * headR - dx * dx - dy * dy
            return headZ + if (r2 > 0f) kotlin.math.sqrt(r2) else 0f
        }

        // ── 身体 ──
        list += part(Mesh.sphere(0.80f, 24, 30), skin, 0f, -0.78f, 0f)

        // ── 恐龙连帽衫：绿色外壳，底下一个圆口露出粉色下身 ──
        val jacket = part(Mesh.sphereOpen(0.90f, 50f, 22, 28), hoodGreen, 0f, -0.70f, 0f, rx = 90f)
        jacket.anim = { t, p -> p.ry = sin(t * 0.5f) * 2f }
        list += jacket

        // 鹅黄肚皮面板（压在衣服前面）
        list += part(Mesh.sphere(0.45f, 18, 22), bellyC, 0f, -0.72f, 0.58f,
            sx = 0.95f, sy = 1.05f, sz = 0.75f)

        // ── 头（脸盘）──
        list += part(Mesh.sphere(headR, 28, 34), skin, 0f, headY, headZ, onHead = true)

        // ── 兜帽：正面开圆口，脸从口子里露出来 ──
        val hood = part(Mesh.sphereOpen(1.06f, 40f, 26, 34), hoodGreen, 0f, 0.54f, -0.22f,
            onHead = true)
        list += hood

        // 兜帽上沿的牙齿
        for (k in 0 until 6) {
            val deg = 38f + k * 20f
            val rad = deg * Math.PI.toFloat() / 180f
            val rho = 1.06f * sin(40f * Math.PI.toFloat() / 180f) + 0.02f
            val tooth = part(Mesh.cone(0.052f, 0.17f, 12), toothC,
                rho * kotlin.math.cos(rad), 0.54f + rho * sin(rad),
                -0.22f + 1.06f * kotlin.math.cos(40f * Math.PI.toFloat() / 180f) - 0.04f,
                rx = 180f, onHead = true)
            tooth.anim = { _, p -> p.rx = 180f + face.headTilt * 0.4f }
            list += tooth
        }

        // 恐龙背刺（沿头顶往后的三根）
        val spikes = listOf(
            Triple(0f, 1.58f, -0.10f) to Triple(0.135f, 0.30f, -26f),
            Triple(0f, 1.50f, -0.44f) to Triple(0.115f, 0.26f, -38f),
            Triple(0f, 1.33f, -0.72f) to Triple(0.095f, 0.22f, -50f)
        )
        for ((pos, sh) in spikes) {
            list += part(Mesh.cone(sh.first, sh.second, 12), hoodDark,
                pos.first, pos.second, pos.third, rx = sh.third, onHead = true)
        }

        // ── 耳朵（从兜帽两侧穿出来）──
        val earL = part(Mesh.sphere(0.30f, 18, 22), skinDeep, -0.78f, 1.24f, -0.06f,
            rz = -30f, onHead = true)
        earL.anim = { t, p -> p.rz = -30f + sin(t * 2.5f) * 8f }
        val earR = part(Mesh.sphere(0.30f, 18, 22), skinDeep, 0.78f, 1.24f, -0.06f,
            rz = 30f, onHead = true)
        earR.anim = { t, p -> p.rz = 30f - sin(t * 2.5f) * 8f }
        list += earL
        list += earR

        // ── 眼睛：白眼球 + 瞳孔 + 高光 ──
        val eyeY = 0.66f
        val eyeX = 0.31f
        for (side in intArrayOf(-1, 1)) {
            val ex = eyeX * side
            val eyeZ = surfZ(ex, eyeY) - 0.10f

            val sclera = part(Mesh.sphere(0.185f, 18, 22), white, ex, eyeY, eyeZ, onHead = true)
            sclera.anim = { _, p ->
                p.sy = eyeScale()
                p.sx = 1f + (face.eyeOpen - 1f) * 0.08f
            }
            list += sclera

            val pupil = part(Mesh.sphere(0.105f, 16, 20), eyeC, ex, eyeY, eyeZ + 0.10f, onHead = true)
            pupil.anim = { _, p ->
                p.sy = eyeScale()
                p.px = ex + face.pupilShift
                p.py = eyeY
            }
            list += pupil

            val shine = part(Mesh.sphere(0.040f, 10, 12), white,
                ex + 0.045f, eyeY + 0.055f, eyeZ + 0.165f, onHead = true)
            shine.anim = { _, p ->
                p.sy = eyeScale()
                p.px = ex + 0.045f + face.pupilShift
                p.py = eyeY + 0.055f * eyeScale()
            }
            list += shine

            // 眉毛：内端下压 = 生气，上抬 = 惊讶
            val bx = 0.31f * side
            val browZ = surfZ(bx, 0.945f) - 0.01f
            val brow = part(Mesh.box(0.30f, 0.055f, 0.09f), browC, bx, 0.945f, browZ, onHead = true)
            brow.anim = { _, p ->
                p.px = bx
                p.py = 0.945f + face.browLift
                p.rz = -face.browTilt * side
            }
            list += brow

            // 腮红
            val blushZ = surfZ(0.575f * side, 0.26f) - 0.035f
            val blush = part(Mesh.sphere(0.155f, 14, 18), blushC, 0.575f * side, 0.26f, blushZ,
                onHead = true)
            blush.anim = { _, p ->
                p.px = 0.575f * side
                p.sx = 0.95f + face.blush * 0.55f
                p.sy = 0.42f + face.blush * 0.34f
                p.sz = 0.5f
            }
            list += blush
        }

        // ── 猪鼻 ──
        val snoutZ = surfZ(0f, 0.28f) - 0.05f
        list += part(Mesh.cylinder(0.27f, 0.20f, 30), snout, 0f, 0.28f, snoutZ,
            rx = 90f, onHead = true)
        list += part(Mesh.cylinder(0.050f, 0.06f, 14), dark, -0.088f, 0.28f,
            snoutZ + 0.105f, rx = 90f, onHead = true)
        list += part(Mesh.cylinder(0.050f, 0.06f, 14), dark, 0.088f, 0.28f,
            snoutZ + 0.105f, rx = 90f, onHead = true)

        // ── 嘴：张开的腔 + 五颗嘴角点，靠缩放切换 ──
        val mouthZ = surfZ(0f, 0.06f) + 0.012f
        val cavity = part(Mesh.sphere(0.115f, 16, 20), cavityC, 0f, 0.055f,
            mouthZ - 0.06f, onHead = true)
        cavity.anim = { _, p ->
            val o = face.mouthOpen
            p.sx = 1.10f + o * 0.62f
            p.sy = 0.10f + o * 1.45f
            p.sz = 0.55f
            p.py = 0.055f - o * 0.02f
        }
        list += cavity

        for (i in 0 until 5) {
            val fx = (i - 2) / 2f
            val dx = fx * 0.19f
            val dot = part(Mesh.sphere(0.050f, 10, 14), lipC, dx, 0.06f, mouthZ, onHead = true)
            dot.anim = { _, p ->
                val closed = (1f - face.mouthOpen * 1.7f).coerceIn(0f, 1f)
                p.px = dx
                p.py = 0.06f + face.mouthCurve * 0.085f * fx * fx
                p.sy = 0.05f + closed * 0.85f
                p.sx = 0.75f + closed * 0.45f
                p.sz = 0.6f
            }
            list += dot
        }

        // ── 胳膊（绿色袖子）+ 手（粉色，跟着胳膊转）──
        for (side in intArrayOf(-1, 1)) {
            val armX = 0.79f * side
            val armY = -0.52f
            val restRz = 16f * side
            val waveRz = -140f
            val arm = part(Mesh.cylinder(0.145f, 0.60f, 20), hoodGreen, armX, armY, 0.02f, rz = restRz)
            val hand = part(Mesh.sphere(0.145f, 14, 18), skin, armX, armY - 0.30f, 0.03f)
            arm.anim = { t, p ->
                val swing = sin(t * 1.8f) * 4f * energy
                val w = if (side == -1) waveAmt else 0f
                p.px = armX
                p.py = armY + sin(t * 1.8f + 1.2f) * 0.02f * energy
                p.rz = restRz + swing * side + (waveRz - restRz) * w +
                    sin(t * 9f) * 12f * w
            }
            hand.anim = { t, p ->
                val swing = sin(t * 1.8f) * 4f * energy
                val w = if (side == -1) waveAmt else 0f
                val rz = restRz + swing * side + (waveRz - restRz) * w + sin(t * 9f) * 12f * w
                val rad = rz * Math.PI.toFloat() / 180f
                p.px = armX + 0.30f * sin(rad)
                p.py = armY + sin(t * 1.8f + 1.2f) * 0.02f * energy - 0.30f * kotlin.math.cos(rad)
                p.rz = rz
            }
            list += arm
            list += hand
        }

        // ── 腿 + 深色蹄子 ──
        for (side in intArrayOf(-1, 1)) {
            list += part(Mesh.cylinder(0.17f, 0.44f, 20), skin, 0.35f * side, -1.42f, 0f)
            list += part(Mesh.sphere(0.185f, 14, 18), hoofC,
                0.35f * side, -1.62f, 0.06f, sx = 1f, sy = 0.75f, sz = 1.25f)
        }

        // ── 尾巴 ──
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