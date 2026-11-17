package com.unclema123.pignpc

import javafx.animation.AnimationTimer
import javafx.animation.PauseTransition
import javafx.application.Application
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.AmbientLight
import javafx.scene.Group
import javafx.scene.PerspectiveCamera
import javafx.scene.PointLight
import javafx.scene.Scene
import javafx.scene.SceneAntialiasing
import javafx.scene.SubScene
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Dialog
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.paint.CycleMethod
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Stop
import javafx.scene.shape.Rectangle
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Duration
import kotlin.math.abs
import kotlin.math.min

class MainApp : Application() {

    private val store = QuotesStore.load()
    private val dialogue = Dialogue(store)

    private lateinit var pig: PigModel
    private lateinit var bubble: SpeechBubble
    private lateinit var camera: PerspectiveCamera

    override fun start(stage: Stage) {
        val root = StackPane()

        // 背景
        val bg = Rectangle().apply {
            fill = LinearGradient(
                0.0, 0.0, 0.35, 1.0, true, CycleMethod.NO_CYCLE,
                Stop(0.0, Color.web("#fff7fa")),
                Stop(0.55, Color.web("#fdeaf0")),
                Stop(1.0, Color.web("#f5d7e1"))
            )
        }
        bg.widthProperty().bind(root.widthProperty())
        bg.heightProperty().bind(root.heightProperty())

        // 3D 世界
        val world = Group()
        pig = PigModel()
        bubble = SpeechBubble()
        world.children.addAll(pig.root, bubble.root)

        camera = PerspectiveCamera(true).apply {
            fieldOfView = 40.0
            nearClip = 1.0
            farClip = 6000.0
            translateZ = -950.0
            translateY = 44.0
        }

        val ambient = AmbientLight(Color.color(0.66, 0.62, 0.66))
        val keyLight = PointLight(Color.web("#fff3f6")).apply {
            translateX = -430.0
            translateY = -400.0
            translateZ = -760.0
        }
        val rimLight = PointLight(Color.web("#ffd6e4")).apply {
            translateX = 470.0
            translateY = -110.0
            translateZ = 240.0
        }
        world.children.addAll(ambient, keyLight, rimLight)

        val sub = SubScene(world, 1080.0, 760.0, true, SceneAntialiasing.BALANCED).apply {
            fill = null
        }
        sub.camera = camera
        sub.widthProperty().bind(root.widthProperty())
        sub.heightProperty().bind(root.heightProperty())

        // 拖拽旋转 / 滚轮缩放
        var lastX = 0.0
        var lastY = 0.0
        var travelled = 0.0
        sub.setOnMousePressed { e ->
            lastX = e.sceneX; lastY = e.sceneY; travelled = 0.0
        }
        sub.setOnMouseDragged { e ->
            val dx = e.sceneX - lastX
            val dy = e.sceneY - lastY
            lastX = e.sceneX; lastY = e.sceneY
            travelled += abs(dx) + abs(dy)
            pig.yaw.angle += dx * 0.32
            pig.pitch.angle = (pig.pitch.angle + dy * 0.16).coerceIn(-18.0, 18.0)
        }
        sub.setOnMouseReleased {
            if (travelled < 8.0) bubble.say(dialogue.randomSignature())
        }
        sub.setOnScroll { e ->
            val z = camera.translateZ + e.deltaY * 0.9
            camera.translateZ = z.coerceIn(-1500.0, -640.0)
        }

        // 顶部标题
        val title = Label("邵珠佳").apply {
            style = "-fx-font-size: 26px; -fx-font-weight: bold; -fx-text-fill: #6d4b57;"
        }
        val subtitle = Label("一只拟人化的小猪　·　拖动旋转　·　滚轮缩放　·　点它一下").apply {
            style = "-fx-font-size: 12.5px; -fx-text-fill: #a98a94;"
        }
        val topBox = VBox(3.0, title, subtitle).apply {
            alignment = Pos.CENTER
            padding = Insets(24.0, 0.0, 0.0, 0.0)
            isMouseTransparent = true
        }
        StackPane.setAlignment(topBox, Pos.TOP_CENTER)

        // 底部输入
        val input = TextField().apply {
            promptText = "和她说点什么…"
            prefWidth = 470.0
            style = "-fx-background-color: #ffffff; -fx-background-radius: 22px; " +
                "-fx-border-color: #f2c6d4; -fx-border-radius: 22px; -fx-border-width: 1.5px; " +
                "-fx-padding: 12px 18px; -fx-font-size: 14.5px; -fx-text-fill: #4a3f43;"
        }
        val sendBtn = Button("说").apply {
            prefWidth = 76.0
            prefHeight = 44.0
            style = "-fx-background-color: linear-gradient(to bottom, #f8b9ca, #ec93ab); " +
                "-fx-background-radius: 22px; -fx-text-fill: white; -fx-font-size: 15px; " +
                "-fx-font-weight: bold; -fx-cursor: hand;"
        }
        val bottomBar = HBox(12.0, input, sendBtn).apply {
            alignment = Pos.CENTER
            padding = Insets(0.0, 0.0, 36.0, 0.0)
        }
        StackPane.setAlignment(bottomBar, Pos.BOTTOM_CENTER)

        val quotesBtn = Button("语录").apply {
            style = "-fx-background-color: rgba(255,255,255,0.75); -fx-background-radius: 16px; " +
                "-fx-border-color: #f0c3d1; -fx-border-radius: 16px; -fx-border-width: 1px; " +
                "-fx-padding: 7px 16px; -fx-font-size: 12.5px; -fx-text-fill: #8c6a75; -fx-cursor: hand;"
        }
        StackPane.setAlignment(quotesBtn, Pos.TOP_RIGHT)
        StackPane.setMargin(quotesBtn, Insets(24.0, 28.0, 0.0, 0.0))

        val send = {
            val text = input.text.trim()
            if (text.isNotEmpty()) {
                input.clear()
                bubble.say("……")
                val delay = 480.0 + min(900.0, text.length * 26.0)
                val pause = PauseTransition(Duration.millis(delay))
                pause.setOnFinished { bubble.say(dialogue.reply(text)) }
                pause.play()
            }
        }
        sendBtn.setOnAction { send() }
        input.setOnAction { send() }
        quotesBtn.setOnAction { openQuotesEditor(stage) }

        root.children.addAll(bg, sub, topBox, bottomBar, quotesBtn)

        val scene = Scene(root, 1080.0, 760.0)
        stage.scene = scene
        stage.title = "小猪 · 邵珠佳"
        stage.minWidth = 820.0
        stage.minHeight = 600.0
        stage.show()
        input.requestFocus()

        bubble.say("……你来啦。")

        // 待机动画 + 眨眼
        var blinking = false
        var blinkAt = 2.0
        var nextBlink = 2.6 + Math.random() * 2.0

        object : AnimationTimer() {
            override fun handle(now: Long) {
                val t = now / 1_000_000_000.0
                pig.animate(t)
                if (!blinking && t > nextBlink) {
                    blinking = true
                    blinkAt = t
                    pig.setBlink(true)
                } else if (blinking && t - blinkAt > 0.11) {
                    blinking = false
                    pig.setBlink(false)
                    nextBlink = t + 2.4 + Math.random() * 3.2
                }
            }
        }.start()
    }

    private fun openQuotesEditor(owner: Window?) {
        val dialog = Dialog<ButtonType>()
        dialog.initOwner(owner)
        dialog.title = "语录管理"
        dialog.dialogPane.prefWidth = 840.0

        val area = TextArea(store.serialize()).apply {
            prefRowCount = 26
            style = "-fx-font-family: 'Microsoft YaHei', 'Consolas', monospace; -fx-font-size: 13px;"
        }
        val hint = Label("格式：[SIG] 口头禅，一行一条　|　[RULE] 关键词,关键词 => 回复;;回复　|　[FALL] 兜底")
        hint.style = "-fx-font-size: 11.5px; -fx-text-fill: #8a727b;"

        val ok = ButtonType("保存", ButtonBar.ButtonData.OK_DONE)
        dialog.dialogPane.buttonTypes.addAll(ok, ButtonType("取消", ButtonBar.ButtonData.CANCEL_CLOSE))
        dialog.dialogPane.content = VBox(10.0, hint, area).apply { padding = Insets(12.0) }

        val result = dialog.showAndWait()
        if (result.isPresent && result.get().buttonData == ButtonBar.ButtonData.OK_DONE) {
            store.loadFrom(area.text)
            store.save()
            bubble.say("……好了。")
        }
    }
}