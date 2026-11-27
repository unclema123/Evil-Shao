package com.unclema123.evilshao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 人物画面。
 *
 * 2.6 起人物直接用用户给的那张参考图：`scene_bg.jpg` 是把原图铺到 1080×1920、
 * 底部渐隐到奶油色、再补一层柔光斑合成的整屏图（原图在上半屏，下半屏留给气泡）。
 *
 * 因为只有一张静态图，情绪不靠换脸，而靠光影与微动：
 * - 呼吸：整体极缓地放大缩小（以底部为锚点）
 * - 眨眼：按眼睛坐标盖两层眼睑（只有平静时）
 * - 害羞：两颊加粉色腮红
 * - 生气：四周压一层红
 * - 无聊：降饱和 + 压暗
 * - 惊讶：轻轻一弹 + 白光
 * - 开心：暖光亮一下 + 星光
 */
class SceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mood { NEUTRAL, HAPPY, ANGRY, SURPRISED, SHY, BORED }

    private val started = SystemClock.uptimeMillis()
    private var mood: Mood = Mood.NEUTRAL
    private var moodAt = 0L
    private var moodUntil = 0L
    private var waveUntil = 0L
    private var lastBlink = 0L
    private var blinkUntil = 0L

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    private var bg: Bitmap? = null
    private var loaded = false
    private var desat: ColorMatrixColorFilter? = null

    private val dst = RectF()
    private val lidPath = Path()

    fun showMood(m: Mood, durationMs: Long = 600_000L) {
        mood = m
        moodAt = SystemClock.uptimeMillis()
        moodUntil = moodAt + durationMs
        invalidate()
    }

    /** 被逗笑 / 被夸时来一下：轻轻弹一下 + 几点星光 */
    fun wave(durationMs: Long = 2000L) {
        waveUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
    }

    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 1 }
            bg = context.assets.open("scene_bg.jpg").use { BitmapFactory.decodeStream(it, null, o) }
            desat = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0.55f) })
        } catch (e: Exception) {
            android.util.Log.w("EvilShao", "场景图加载失败：${e.message}")
        }
    }

    override fun onDraw(canvas: Canvas) {
        load()
        val now = SystemClock.uptimeMillis()
        val t = (now - started) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val designRatio = DESIGN_H / DESIGN_W
        val tall = h / w >= designRatio
        val scale = if (tall) w / DESIGN_W else h / DESIGN_H
        val dw = DESIGN_W * scale
        val dh = DESIGN_H * scale
        val dx = (w - dw) / 2f
        val dy = if (tall) h - dh else (h - dh) / 2f

        if (tall) canvas.drawColor(SCENE_WALL)

        val current = if (now < moodUntil) mood else Mood.NEUTRAL
        val since = if (now < moodUntil) (now - moodAt) / 1000f else 99f
        val bitmap = bg
        if (bitmap == null) {
            postInvalidateOnAnimation()
            return
        }

        // 呼吸：1.0 ~ 1.012，锚在底部
        val breath = 1f + sin(t * 0.9f) * 0.006f
        // 惊讶：先弹一下再回落
        val pop = if (current == Mood.SURPRISED && since < 0.45f)
            1.03f - 0.03f * (since / 0.45f) else 1f
        // 开心：轻微上浮
        val hop = if (now < waveUntil) -kotlin.math.abs(sin(t * 6f)) * h * 0.010f else 0f
        // 生气：极轻微左右抖
        val shake = if (current == Mood.ANGRY)
            sin(t * 26f) * 2.2f * scale else 0f

        val s = breath * pop
        canvas.save()
        canvas.translate(shake, hop)
        canvas.scale(s, s, w / 2f, h)

        paint.colorFilter = if (current == Mood.BORED) desat else null
        dst.set(dx, dy, dx + dw, dy + dh)
        canvas.drawBitmap(bitmap, null, dst, paint)
        paint.colorFilter = null

        // 眼睛与腮红都按贴图坐标换算到屏幕
        fun sx(px: Float) = dx + px * scale
        fun sy(py: Float) = dy + py * scale

        when (current) {
            Mood.SHY -> drawBlush(canvas, sx(EYE_LX - 20f), sy(EYE_LY + 150f),
                sx(EYE_RX + 5f), sy(EYE_RY + 145f), 120f * scale)
            Mood.ANGRY -> drawVignette(canvas, w, h, Color.argb(60, 190, 40, 40))
            Mood.BORED -> {
                plain.color = Color.argb(28, 20, 16, 12)
                canvas.drawRect(0f, 0f, w, h, plain)
            }
            Mood.HAPPY -> drawGlow(canvas, w, h)
            Mood.SURPRISED -> {
                val a = (1f - since / 0.5f).coerceIn(0f, 1f)
                if (a > 0f) {
                    plain.color = Color.argb((90 * a).toInt(), 255, 250, 240)
                    canvas.drawRect(0f, 0f, w, h, plain)
                }
            }
            Mood.NEUTRAL -> Unit
        }

        // 只有平静的时候眨眼
        if (current == Mood.NEUTRAL) {
            if (now - lastBlink > 4200L) {
                lastBlink = now
                blinkUntil = now + 140L
            }
            if (now < blinkUntil) drawLids(canvas, scale, dx, dy)
        }

        if (now < waveUntil) drawSparkles(canvas, t, dx, dy, scale)
        canvas.restore()
        postInvalidateOnAnimation()
    }

    /** 两层眼睑：肤色盖住眼睛，再压一道睫毛弧线 */
    private fun drawLids(c: Canvas, scale: Float, dx: Float, dy: Float) {
        drawLid(c, dx + EYE_LX * scale, dy + EYE_LY * scale, scale)
        drawLid(c, dx + EYE_RX * scale, dy + EYE_RY * scale, scale)
    }

    private fun drawLid(c: Canvas, cx: Float, cy: Float, scale: Float) {
        val rx = 52f * scale
        val ry = 38f * scale
        plain.color = LID_SKIN
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, plain)

        lidPath.reset()
        lidPath.moveTo(cx - rx * 0.94f, cy - ry * 0.10f)
        lidPath.quadTo(cx, cy + ry * 0.85f, cx + rx * 0.94f, cy - ry * 0.10f)
        plain.style = Paint.Style.STROKE
        plain.strokeWidth = 6f * scale
        plain.strokeCap = Paint.Cap.ROUND
        plain.color = LASH
        c.drawPath(lidPath, plain)
        plain.style = Paint.Style.FILL
    }

    private fun drawBlush(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, r: Float) {
        for ((x, y) in listOf(x1 to y1, x2 to y2)) {
            val g = RadialGradient(
                x, y, r,
                intArrayOf(Color.argb(105, 255, 130, 140), Color.argb(0, 255, 130, 140)),
                floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
            )
            plain.shader = g
            c.drawCircle(x, y, r, plain)
            plain.shader = null
        }
    }

    private fun drawVignette(c: Canvas, w: Float, h: Float, color: Int) {
        val g = RadialGradient(
            w / 2f, h * 0.42f, w * 0.95f,
            intArrayOf(Color.argb(0, 190, 40, 40), color),
            floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP
        )
        plain.shader = g
        c.drawRect(0f, 0f, w, h, plain)
        plain.shader = null
    }

    private fun drawGlow(c: Canvas, w: Float, h: Float) {
        val g = RadialGradient(
            w * 0.5f, h * 0.18f, w * 0.8f,
            intArrayOf(Color.argb(60, 255, 232, 160), Color.argb(0, 255, 232, 160)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        plain.shader = g
        c.drawRect(0f, 0f, w, h, plain)
        plain.shader = null
    }

    private fun drawSparkles(c: Canvas, t: Float, dx: Float, dy: Float, scale: Float) {
        plain.color = 0xFFFFE9B0.toInt()
        for (i in 0..4) {
            val a = t * 2.4f + i * 1.2f
            val x = dx + (540f + sin(a) * 260f) * scale
            val y = dy + (420f - i * 70f + sin(a * 1.3f) * 40f) * scale
            c.drawCircle(x, y, 6f * scale, plain)
        }
    }

    private companion object {
        const val DESIGN_W = 1080f
        const val DESIGN_H = 1920f

        // 眼睛中心（贴图坐标，来自参考图瞳孔位置，左右各一）
        const val EYE_LX = 470f
        const val EYE_LY = 446f
        const val EYE_RX = 642f
        const val EYE_RY = 428f

        const val LID_SKIN = 0xFFF6D9C7.toInt()
        const val LASH = 0xFF43291C.toInt()
        const val SCENE_WALL = 0xFFFBF0C8.toInt()
    }
}