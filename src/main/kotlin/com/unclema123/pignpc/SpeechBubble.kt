package com.unclema123.pignpc

import javafx.animation.Interpolator
import javafx.animation.KeyFrame
import javafx.animation.KeyValue
import javafx.animation.Timeline
import javafx.geometry.VPos
import javafx.scene.Group
import javafx.scene.SnapshotParameters
import javafx.scene.canvas.Canvas
import javafx.scene.image.ImageView
import javafx.scene.paint.Color
import javafx.scene.text.Font
import javafx.scene.text.Text
import javafx.util.Duration
import kotlin.math.max

/**
 * 浮在小猪头顶的对话气泡。
 *
 * 用 Canvas 画好文字再快照成图片，放进 3D 场景里当「广告牌」，
 * 这样不用引入任何字体排版依赖，也能保证中文正常显示。
 */
class SpeechBubble {

    val root = Group()

    private val font = Font.font("Microsoft YaHei", 17.0)
    private val view = ImageView()
    private val measureNode = Text().apply { font = this@SpeechBubble.font }

    private val maxWidth = 300.0
    private val lineHeight = 26.0
    private val padX = 22.0
    private val padY = 15.0

    init {
        root.children.add(view)
        root.isVisible = false
        root.translateZ = -30.0
    }

    fun say(text: String) {
        val img = render(text)
        view.image = img
        root.isVisible = true

        root.scaleX = 0.55
        root.scaleY = 0.55
        root.opacity = 0.0

        val tl = Timeline(
            KeyFrame(Duration.ZERO, KeyValue(root.scaleXProperty(), 0.55), KeyValue(root.scaleYProperty(), 0.55), KeyValue(root.opacityProperty(), 0.0)),
            KeyFrame(Duration.millis(220.0),
                KeyValue(root.scaleXProperty(), 1.0, Interpolator.EASE_OUT),
                KeyValue(root.scaleYProperty(), 1.0, Interpolator.EASE_OUT),
                KeyValue(root.opacityProperty(), 1.0, Interpolator.EASE_OUT))
        )
        tl.play()
    }

    private fun measure(s: String): Double {
        measureNode.text = s
        return measureNode.layoutBounds.width
    }

    private fun wrap(text: String, limit: Double): List<String> {
        val lines = mutableListOf<String>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isEmpty()) {
                lines.add("")
                continue
            }
            val cur = StringBuilder()
            for (ch in paragraph) {
                cur.append(ch)
                if (measure(cur.toString()) > limit) {
                    cur.deleteCharAt(cur.length - 1)
                    lines.add(cur.toString())
                    cur.setLength(0)
                    cur.append(ch)
                }
            }
            if (cur.isNotEmpty()) lines.add(cur.toString())
        }
        return if (lines.isEmpty()) listOf(text) else lines
    }

    private fun render(text: String): javafx.scene.image.WritableImage {
        val lines = wrap(text, maxWidth)
        val widest = lines.maxOf { measure(it) }
        val w = max(widest + padX * 2, 92.0)
        val h = lines.size * lineHeight + padY * 2

        val canvas = Canvas(w, h)
        val g = canvas.graphicsContext2D

        g.fill = Color.web("#fffdfd")
        g.fillRoundRect(0.0, 0.0, w, h, 26.0, 26.0)
        g.stroke = Color.web("#f0b6c7")
        g.lineWidth = 3.0
        g.strokeRoundRect(1.5, 1.5, w - 3.0, h - 3.0, 26.0, 26.0)

        g.font = font
        g.fill = Color.web("#463a3e")
        g.textBaseline = VPos.TOP
        lines.forEachIndexed { i, line ->
            g.fillText(line, padX, padY + i * lineHeight + 1.0)
        }

        view.fitWidth = w
        view.isPreserveRatio = true

        // 气泡底边留出 18 的悬空间隙
        root.translateY = -118.0 - h / 2.0

        return canvas.snapshot(SnapshotParameters().apply { fill = Color.TRANSPARENT }, null)
    }
}