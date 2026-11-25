package com.unclema123.evilshao

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * 2D 温馨小屋 + 平面插画风格的女生。
 *
 * 上一版是 OpenGL 的 3D 小猪，换成 2D 之后所有东西都用 Canvas 画：
 * 背景（窗、窗帘、书架、绿植、地毯、串灯）和人物（头发、脸、表情、毛衣）。
 * 表情由 [Mood] 控制，眨眼、呼吸、串灯呼吸都是同一条时间轴算出来的。
 */
class SceneView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mood { NEUTRAL, HAPPY, ANGRY, SURPRISED, SHY, BORED }

    private val started = SystemClock.uptimeMillis()

    private var mood: Mood = Mood.NEUTRAL
    private var moodUntil = 0L
    private var waveUntil = 0L

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private val rect = RectF()

    private var w = 0f
    private var h = 0f

    /** 头的半径，其他部位都按它缩放 */
    private var r = 0f
    private var cx = 0f
    private var cy = 0f

    private val wall = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sky = Paint(Paint.ANTI_ALIAS_FLAG)

    fun showMood(m: Mood, durationMs: Long = 600_000L) {
        mood = m
        moodUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
    }

    /** 被逗笑 / 被夸时来一下：轻微弹跳 + 几点星光 */
    fun wave(durationMs: Long = 2000L) {
        waveUntil = SystemClock.uptimeMillis() + durationMs
        invalidate()
    }

    override fun onSizeChanged(nw: Int, nh: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(nw, nh, oldw, oldh)
        w = nw.toFloat()
        h = nh.toFloat()
        r = w * 0.165f
        cx = w * 0.5f
        cy = h * 0.40f
        wall.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0xFFFFF5EA.toInt(), 0xFFFFE3CE.toInt()),
            null, Shader.TileMode.CLAMP
        )
        sky.shader = LinearGradient(
            0f, cy - 4 * r, 0f, cy + 2 * r,
            intArrayOf(0xFFCFEBFF.toInt(), 0xFFEFFAFF.toInt()),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val t = (SystemClock.uptimeMillis() - started) / 1000f
        if (w <= 0f) return
        val current = if (SystemClock.uptimeMillis() < moodUntil) mood else Mood.NEUTRAL
        val waving = SystemClock.uptimeMillis() < waveUntil

        drawRoom(canvas, t)
        canvas.save()
        val breath = sin(t * 1.7f) * r * 0.018f
        val hop = if (waving) -kotlin.math.abs(sin(t * 6f)) * r * 0.10f else 0f
        canvas.translate(0f, breath + hop)
        drawGirl(canvas, t, current)
        canvas.restore()
        if (waving) drawSparkles(canvas, t)
        postInvalidateOnAnimation()
    }

    // ───────────────────────────── 房间 ─────────────────────────────

    private fun drawRoom(c: Canvas, t: Float) {
        c.drawRect(0f, 0f, w, h, wall)
        // 地板
        fill.color = 0xFFE9C6A5.toInt()
        c.drawRect(0f, h * 0.70f, w, h, fill)
        fill.color = 0x33FFFFFF
        c.drawRect(0f, h * 0.70f, w, h * 0.705f, fill)
        // 地毯
        fill.color = 0xFFF6D5CE.toInt()
        rect.set(w * 0.10f, h * 0.86f, w * 0.90f, h * 0.94f)
        c.drawOval(rect, fill)

        drawWindow(c)
        drawShelf(c)
        drawPlant(c)
        drawLights(c, t)
    }

    private fun drawWindow(c: Canvas) {
        val left = w * 0.06f
        val top = h * 0.10f
        val right = w * 0.46f
        val bottom = h * 0.33f
        // 外框
        fill.color = 0xFFF0CFB2.toInt()
        rect.set(left - r * 0.10f, top - r * 0.10f, right + r * 0.10f, bottom + r * 0.10f)
        c.drawRoundRect(rect, r * 0.18f, r * 0.18f, fill)
        // 天空
        c.drawRect(left, top, right, bottom, sky)
        // 太阳
        fill.color = 0xFFFFE3A0.toInt()
        c.drawCircle(right - r * 0.55f, top + r * 0.42f, r * 0.30f, fill)
        // 云
        fill.color = 0xCCFFFFFF.toInt()
        c.drawCircle(left + r * 0.60f, top + r * 0.55f, r * 0.20f, fill)
        c.drawCircle(left + r * 0.85f, top + r * 0.50f, r * 0.26f, fill)
        c.drawCircle(left + r * 1.10f, top + r * 0.58f, r * 0.18f, fill)
        // 窗棂
        stroke.color = 0xFFF7E2CD.toInt()
        stroke.strokeWidth = r * 0.05f
        val midX = (left + right) / 2f
        c.drawLine(midX, top, midX, bottom, stroke)
        c.drawLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f, stroke)
        // 窗帘
        fill.color = 0xFFF8C4C4.toInt()
        path.reset()
        path.moveTo(left - r * 0.55f, top - r * 0.15f)
        path.lineTo(left + r * 0.10f, top - r * 0.15f)
        path.quadTo(left - r * 0.10f, (top + bottom) / 2f, left + r * 0.05f, bottom + r * 0.15f)
        path.lineTo(left - r * 0.45f, bottom + r * 0.15f)
        path.close()
        c.drawPath(path, fill)
        path.reset()
        path.moveTo(right - r * 0.10f, top - r * 0.15f)
        path.lineTo(right + r * 0.55f, top - r * 0.15f)
        path.lineTo(right + r * 0.45f, bottom + r * 0.15f)
        path.lineTo(right - r * 0.05f, bottom + r * 0.15f)
        path.quadTo(right + r * 0.10f, (top + bottom) / 2f, right - r * 0.10f, top - r * 0.15f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawShelf(c: Canvas) {
        val left = w * 0.66f
        val top = h * 0.13f
        val right = w * 0.95f
        fill.color = 0xFFEFCFB0.toInt()
        rect.set(left, top, right, top + r * 0.10f)
        c.drawRoundRect(rect, r * 0.04f, r * 0.04f, fill)
        rect.set(left, top + r * 0.95f, right, top + r * 1.05f)
        c.drawRoundRect(rect, r * 0.04f, r * 0.04f, fill)
        // 书
        val colors = intArrayOf(0xFFE89AA6.toInt(), 0xFF9CC7B6.toInt(), 0xFFF0C987.toInt(), 0xFF9FB4D9.toInt())
        var x = left + r * 0.10f
        var i = 0
        while (x < right - r * 0.20f && i < 9) {
            val bw = r * (0.14f + 0.03f * (i % 3))
            val bh = r * (0.55f + 0.10f * (i % 2))
            fill.color = colors[i % colors.size]
            c.drawRoundRect(RectF(x, top + r * 0.95f - bh, x + bw, top + r * 0.95f), r * 0.02f, r * 0.02f, fill)
            x += bw + r * 0.05f
            i++
        }
        // 小盆栽
        fill.color = 0xFFB7D9B0.toInt()
        c.drawCircle(right - r * 0.35f, top + r * 0.62f, r * 0.22f, fill)
        fill.color = 0xFFE0A98C.toInt()
        rect.set(right - r * 0.50f, top + r * 0.70f, right - r * 0.20f, top + r * 1.00f)
        c.drawRoundRect(rect, r * 0.04f, r * 0.04f, fill)
    }

    private fun drawPlant(c: Canvas) {
        val px = w * 0.88f
        val py = h * 0.70f
        fill.color = 0xFF9CC7A4.toInt()
        c.drawOval(RectF(px - r * 0.45f, py - r * 0.95f, px - r * 0.05f, py - r * 0.35f), fill)
        c.drawOval(RectF(px + r * 0.02f, py - r * 1.05f, px + r * 0.42f, py - r * 0.45f), fill)
        c.drawOval(RectF(px - r * 0.25f, py - r * 1.25f, px + r * 0.15f, py - r * 0.60f), fill)
        fill.color = 0xFFE0A98C.toInt()
        rect.set(px - r * 0.38f, py - r * 0.40f, px + r * 0.38f, py + r * 0.25f)
        c.drawRoundRect(rect, r * 0.10f, r * 0.10f, fill)
    }

    private fun drawLights(c: Canvas, t: Float) {
        val y = h * 0.06f
        stroke.color = 0x66B98A6E
        stroke.strokeWidth = r * 0.02f
        path.reset()
        path.moveTo(0f, y)
        path.cubicTo(w * 0.25f, y + r * 0.35f, w * 0.65f, y - r * 0.05f, w, y + r * 0.25f)
        c.drawPath(path, stroke)
        for (i in 0..7) {
            val p = i / 7f
            val lx = w * p
            val ly = y + sin(p * 3.14f) * r * 0.20f + r * 0.10f
            val glow = (0.55f + 0.45f * sin(t * 2f + i)).coerceIn(0.2f, 1f)
            fill.color = Color.argb((200 * glow).toInt(), 255, 226, 168)
            c.drawCircle(lx, ly, r * 0.10f, fill)
            fill.color = Color.argb((60 * glow).toInt(), 255, 226, 168)
            c.drawCircle(lx, ly, r * 0.20f, fill)
        }
    }

    // ───────────────────────────── 人物 ─────────────────────────────

    private fun drawGirl(c: Canvas, t: Float, mood: Mood) {
        val blink = (t % 4.4f) < 0.11f

        // 后面的长发
        fill.color = 0xFF6B4A39.toInt()
        rect.set(cx - r * 1.38f, cy - r * 1.50f, cx + r * 1.38f, cy + r * 2.30f)
        c.drawRoundRect(rect, r * 1.10f, r * 1.10f, fill)

        // 脖子
        fill.color = 0xFFFFDCCB.toInt()
        rect.set(cx - r * 0.30f, cy + r * 0.85f, cx + r * 0.30f, cy + r * 1.55f)
        c.drawRoundRect(rect, r * 0.12f, r * 0.12f, fill)

        // 毛衣
        fill.color = 0xFFF7EBDE.toInt()
        path.reset()
        path.moveTo(cx - r * 2.05f, h + r * 0.6f)
        path.lineTo(cx - r * 1.55f, cy + r * 1.85f)
        path.quadTo(cx - r * 0.95f, cy + r * 1.32f, cx, cy + r * 1.30f)
        path.quadTo(cx + r * 0.95f, cy + r * 1.32f, cx + r * 1.55f, cy + r * 1.85f)
        path.lineTo(cx + r * 2.05f, h + r * 0.6f)
        path.close()
        c.drawPath(path, fill)
        // 毛衣领口
        stroke.color = 0xFFE8D3BE.toInt()
        stroke.strokeWidth = r * 0.055f
        path.reset()
        path.moveTo(cx - r * 0.62f, cy + r * 1.42f)
        path.quadTo(cx, cy + r * 1.72f, cx + r * 0.62f, cy + r * 1.42f)
        c.drawPath(path, stroke)
        // 细肩线，显瘦
        stroke.color = 0x33B08A74
        stroke.strokeWidth = r * 0.03f
        c.drawLine(cx - r * 1.45f, cy + r * 1.95f, cx - r * 1.00f, cy + r * 2.55f, stroke)
        c.drawLine(cx + r * 1.45f, cy + r * 1.95f, cx + r * 1.00f, cy + r * 2.55f, stroke)

        // 脸
        fill.color = 0xFFFFE2D2.toInt()
        rect.set(cx - r * 1.00f, cy - r * 1.14f, cx + r * 1.00f, cy + r * 1.16f)
        c.drawOval(rect, fill)
        // 下巴阴影
        fill.color = 0x14C98F7C
        rect.set(cx - r * 0.62f, cy + r * 0.55f, cx + r * 0.62f, cy + r * 1.10f)
        c.drawOval(rect, fill)

        drawBangs(c)
        drawEyes(c, blink, mood)
        drawBrows(c, mood)
        drawMouth(c, mood)

        // 腮红
        val blush = if (mood == Mood.SHY) 0x55FF98A6 else 0x2EFF98A6
        fill.color = blush
        c.drawOval(RectF(cx - r * 0.92f, cy + r * 0.30f, cx - r * 0.48f, cy + r * 0.56f), fill)
        c.drawOval(RectF(cx + r * 0.48f, cy + r * 0.30f, cx + r * 0.92f, cy + r * 0.56f), fill)

        // 鼻子
        stroke.color = 0x66C98F7C
        stroke.strokeWidth = r * 0.028f
        c.drawArc(RectF(cx - r * 0.10f, cy + r * 0.30f, cx + r * 0.08f, cy + r * 0.44f), 20f, 110f, false, stroke)

        // 头发高光
        stroke.color = 0x55FFE9D2
        stroke.strokeWidth = r * 0.10f
        c.drawArc(RectF(cx - r * 0.95f, cy - r * 1.25f, cx + r * 0.35f, cy - r * 0.35f), 195f, 60f, false, stroke)

        // 两侧发丝
        fill.color = 0xFF6B4A39.toInt()
        path.reset()
        path.moveTo(cx - r * 1.16f, cy - r * 0.85f)
        path.quadTo(cx - r * 1.42f, cy + r * 0.60f, cx - r * 1.10f, cy + r * 1.90f)
        path.quadTo(cx - r * 0.92f, cy + r * 0.55f, cx - r * 0.98f, cy - r * 0.80f)
        path.close()
        c.drawPath(path, fill)
        path.reset()
        path.moveTo(cx + r * 1.16f, cy - r * 0.85f)
        path.quadTo(cx + r * 1.42f, cy + r * 0.60f, cx + r * 1.10f, cy + r * 1.90f)
        path.quadTo(cx + r * 0.92f, cy + r * 0.55f, cx + r * 0.98f, cy - r * 0.80f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawBangs(c: Canvas) {
        fill.color = 0xFF6B4A39.toInt()
        path.reset()
        path.moveTo(cx - r * 1.03f, cy - r * 0.05f)
        path.cubicTo(cx - r * 1.12f, cy - r * 1.35f, cx + r * 1.12f, cy - r * 1.35f, cx + r * 1.03f, cy - r * 0.05f)
        path.quadTo(cx + r * 0.72f, cy - r * 0.42f, cx + r * 0.40f, cy - r * 0.16f)
        path.quadTo(cx + r * 0.02f, cy - r * 0.58f, cx - r * 0.30f, cy - r * 0.10f)
        path.quadTo(cx - r * 0.66f, cy - r * 0.46f, cx - r * 1.03f, cy - r * 0.05f)
        path.close()
        c.drawPath(path, fill)
    }

    private fun drawEyes(c: Canvas, blink: Boolean, mood: Mood) {
        val eyeY = cy + r * 0.02f
        val dx = r * 0.44f
        val big = if (mood == Mood.SURPRISED) 1.18f else 1f
        val half = mood == Mood.BORED
        val closedHappy = mood == Mood.SHY

        listOf(cx - dx, cx + dx).forEach { ex ->
            if (blink || half || closedHappy) {
                stroke.color = 0xFF4A3228.toInt()
                stroke.strokeWidth = r * 0.055f
                path.reset()
                if (closedHappy) {
                    path.moveTo(ex - r * 0.20f, eyeY + r * 0.03f)
                    path.quadTo(ex, eyeY - r * 0.10f, ex + r * 0.20f, eyeY + r * 0.03f)
                } else {
                    path.moveTo(ex - r * 0.22f, eyeY)
                    path.lineTo(ex + r * 0.22f, eyeY)
                }
                c.drawPath(path, stroke)
                return@forEach
            }
            // 眼白
            fill.color = 0xFFFFFFFF.toInt()
            c.drawOval(RectF(ex - r * 0.25f * big, eyeY - r * 0.30f * big, ex + r * 0.25f * big, eyeY + r * 0.30f * big), fill)
            // 虹膜
            fill.color = 0xFF6B4432.toInt()
            c.drawOval(RectF(ex - r * 0.17f * big, eyeY - r * 0.24f * big, ex + r * 0.17f * big, eyeY + r * 0.24f * big), fill)
            // 瞳孔
            fill.color = 0xFF3A2318.toInt()
            c.drawOval(RectF(ex - r * 0.10f * big, eyeY - r * 0.15f * big, ex + r * 0.10f * big, eyeY + r * 0.15f * big), fill)
            // 高光
            fill.color = 0xFFFFFFFF.toInt()
            c.drawCircle(ex - r * 0.06f * big, eyeY - r * 0.09f * big, r * 0.06f * big, fill)
            c.drawCircle(ex + r * 0.07f * big, eyeY + r * 0.08f * big, r * 0.028f * big, fill)
        }
    }

    private fun drawBrows(c: Canvas, mood: Mood) {
        stroke.color = 0xFF5A3B2C.toInt()
        stroke.strokeWidth = r * 0.045f
        val y = cy - r * 0.38f
        val dx = r * 0.44f
        path.reset()
        when (mood) {
            Mood.ANGRY -> {
                path.moveTo(cx - dx - r * 0.18f, y)
                path.lineTo(cx - dx + r * 0.16f, y + r * 0.10f)
            }
            Mood.SURPRISED -> {
                path.addArc(RectF(cx - dx - r * 0.20f, y - r * 0.22f, cx - dx + r * 0.20f, y + r * 0.10f), 200f, 140f)
            }
            else -> {
                path.moveTo(cx - dx - r * 0.18f, y + r * 0.04f)
                path.quadTo(cx - dx, y - r * 0.08f, cx - dx + r * 0.18f, y + r * 0.02f)
            }
        }
        c.drawPath(path, stroke)
        path.reset()
        when (mood) {
            Mood.ANGRY -> {
                path.moveTo(cx + dx + r * 0.18f, y)
                path.lineTo(cx + dx - r * 0.16f, y + r * 0.10f)
            }
            Mood.SURPRISED -> {
                path.addArc(RectF(cx + dx - r * 0.20f, y - r * 0.22f, cx + dx + r * 0.20f, y + r * 0.10f), 200f, 140f)
            }
            else -> {
                path.moveTo(cx + dx + r * 0.18f, y + r * 0.04f)
                path.quadTo(cx + dx, y - r * 0.08f, cx + dx - r * 0.18f, y + r * 0.02f)
            }
        }
        c.drawPath(path, stroke)
    }

    private fun drawMouth(c: Canvas, mood: Mood) {
        val my = cy + r * 0.58f
        stroke.color = 0xFFC2544D.toInt()
        stroke.strokeWidth = r * 0.05f
        fill.color = 0xFFC2544D.toInt()
        path.reset()
        when (mood) {
            Mood.HAPPY -> {
                path.moveTo(cx - r * 0.30f, my)
                path.quadTo(cx, my + r * 0.46f, cx + r * 0.30f, my)
                path.quadTo(cx, my + r * 0.10f, cx - r * 0.30f, my)
                path.close()
                c.drawPath(path, fill)
            }
            Mood.ANGRY -> {
                path.moveTo(cx - r * 0.22f, my + r * 0.10f)
                path.lineTo(cx + r * 0.22f, my + r * 0.10f)
                c.drawPath(path, stroke)
            }
            Mood.SURPRISED -> {
                c.drawOval(RectF(cx - r * 0.10f, my, cx + r * 0.10f, my + r * 0.22f), stroke)
            }
            Mood.BORED -> {
                path.moveTo(cx - r * 0.20f, my + r * 0.06f)
                path.quadTo(cx, my + r * 0.14f, cx + r * 0.20f, my + r * 0.06f)
                c.drawPath(path, stroke)
            }
            else -> {
                path.moveTo(cx - r * 0.20f, my)
                path.quadTo(cx, my + r * 0.30f, cx + r * 0.20f, my)
                c.drawPath(path, stroke)
            }
        }
    }

    private fun drawSparkles(c: Canvas, t: Float) {
        fill.color = 0xFFFFE9B0.toInt()
        for (i in 0..4) {
            val a = t * 2.2f + i * 1.1f
            val sx = cx + sin(a) * r * (1.4f + 0.2f * i)
            val sy = cy - r * (0.9f + 0.35f * i) + sin(a * 1.4f) * r * 0.15f
            val size = r * (0.05f + 0.03f * ((i % 3)))
            c.drawCircle(sx, sy, size, fill)
        }
    }
}