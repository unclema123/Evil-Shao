package com.unclema123.evilshao

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.sin

/**
 * 2D 温馨小屋 + 插画女生。
 *
 * 画面不是代码现画的，而是 `art2d/` 里的 SVG 渲染出来的位图：
 * - `scene_bg.jpg`：整间屋子（窗、窗帘、书架、绿植、串灯、地毯）
 * - `girl_*.webp`：同一张人物，七个表情（普通 / 眨眼 / 开心 / 害羞 / 无聊 /
 *   生气 / 惊讶），带透明通道，叠在背景上
 *
 * 这样人物是画师级的矢量插画，同时保留呼吸、眨眼、星光这些动效：
 * 靠切换同一姿势的不同表情图完成，不需要 3D 骨骼。
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
    private var lastBlink = 0L
    private var blinkUntil = 0L

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var bg: Bitmap? = null
    private val sprites = HashMap<String, Bitmap>()
    private var loaded = false

    private val dst = RectF()

    fun showMood(m: Mood, durationMs: Long = 600_000L) {
        mood = m
        moodUntil = SystemClock.uptimeMillis() + durationMs
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
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            bg = context.assets.open("scene_bg.jpg").use { BitmapFactory.decodeStream(it, null, o) }
            for (name in NAMES) {
                context.assets.open("girl_$name.webp").use {
                    BitmapFactory.decodeStream(it, null, o)?.let { b -> sprites[name] = b }
                }
            }
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

        // 手机比 9:16 长（现在大多是 20:9），就按宽度铺满、整体贴底，
        // 顶上多出来的那截用墙面色补上，看不出接缝；反过来（比 9:16 宽）按高度铺。
        val designRatio = DESIGN_H / DESIGN_W
        val tall = h / w >= designRatio
        val scale = if (tall) w / DESIGN_W else h / DESIGN_H
        val dw = DESIGN_W * scale
        val dh = DESIGN_H * scale
        val dx = (w - dw) / 2f
        val dy = if (tall) h - dh else (h - dh) / 2f

        if (tall) {
            // 顶部补墙面色，避免出现黑边
            canvas.drawColor(0xFFFFF6EC.toInt())
        }
        bg?.let {
            dst.set(dx, dy, dx + dw, dy + dh)
            canvas.drawBitmap(it, null, dst, paint)
        }

        // 呼吸：整体上下浮动 0.6% 屏幕高
        val breath = sin(t * 1.5f) * h * 0.0035f
        val hop = if (now < waveUntil) -kotlin.math.abs(sin(t * 6f)) * h * 0.012f else 0f

        val current = if (now < moodUntil) mood else Mood.NEUTRAL
        var name = when (current) {
            Mood.HAPPY -> "happy"
            Mood.SHY -> "shy"
            Mood.BORED -> "bored"
            Mood.ANGRY -> "angry"
            Mood.SURPRISED -> "surprised"
            Mood.NEUTRAL -> "neutral"
        }
        // 只有平静的时候才眨眼，别的表情本身就是「表情」
        if (name == "neutral") {
            if (now - lastBlink > 4200L) {
                lastBlink = now
                blinkUntil = now + 130L
            }
            if (now < blinkUntil) name = "blink"
        }

        sprites[name]?.let { bmp ->
            val gx = dx + GIRL_X * scale
            val gy = dy + GIRL_Y * scale + breath + hop
            dst.set(gx, gy, gx + GIRL_W * scale, gy + GIRL_H * scale)
            canvas.drawBitmap(bmp, null, dst, paint)
        }

        if (now < waveUntil) drawSparkles(canvas, t, dx, dy, scale)
        postInvalidateOnAnimation()
    }

    private fun drawSparkles(c: Canvas, t: Float, dx: Float, dy: Float, scale: Float) {
        paint.color = 0xFFFFE9B0.toInt()
        for (i in 0..4) {
            val a = t * 2.4f + i * 1.2f
            val sx = dx + (GIRL_X + GIRL_W / 2f + sin(a) * 120f) * scale
            val sy = dy + (GIRL_Y + 240f - i * 120f + sin(a * 1.3f) * 40f) * scale
            c.drawCircle(sx, sy, 6f * scale, paint)
        }
    }

    private companion object {
        const val DESIGN_W = 1080f
        const val DESIGN_H = 1920f
        const val GIRL_X = 329f
        const val GIRL_Y = 343f
        const val GIRL_W = 422f
        const val GIRL_H = 1577f
        val NAMES = listOf("neutral", "blink", "happy", "shy", "bored", "angry", "surprised")
    }
}