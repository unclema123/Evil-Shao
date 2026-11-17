package com.unclema123.pignpc

import javafx.scene.Group
import javafx.scene.paint.Color
import javafx.scene.paint.PhongMaterial
import javafx.scene.shape.Cylinder
import javafx.scene.shape.Sphere
import javafx.scene.transform.Rotate
import kotlin.math.sin

/**
 * 用 JavaFX 3D 的基本体拼出来的拟人化小猪。
 *
 * 坐标约定：+Y 向下，-Z 朝向观察者（摄像机会放在负 Z 上）。
 */
class PigModel {

    val root = Group()

    /** 水平旋转（左右转），由鼠标拖拽驱动 */
    val yaw = Rotate(0.0, Rotate.Y_AXIS)

    /** 俯仰（上下看），限制在 ±20° 以内 */
    val pitch = Rotate(0.0, Rotate.X_AXIS)

    private val skin = PhongMaterial(Color.web("#f9c1d0")).apply {
        specularColor = Color.web("#fff2f6")
        specularPower = 28.0
    }
    private val skinDeep = PhongMaterial(Color.web("#eea3b8")).apply {
        specularPower = 18.0
    }
    private val snoutMat = PhongMaterial(Color.web("#f4a9be")).apply {
        specularColor = Color.web("#ffffff")
        specularPower = 46.0
    }
    private val darkMat = PhongMaterial(Color.web("#a35c72"))
    private val eyeMat = PhongMaterial(Color.web("#3b2f34")).apply {
        specularColor = Color.WHITE
        specularPower = 140.0
    }
    private val whiteMat = PhongMaterial(Color.WHITE)
    private val blushMat = PhongMaterial(Color.web("#f087a3"))

    private val headGroup = Group()
    private val earL = Sphere(26.0, 30)
    private val earR = Sphere(26.0, 30)
    private val eyeL = Sphere(10.5, 26)
    private val eyeR = Sphere(10.5, 26)
    private val hiL = Sphere(3.4, 12)
    private val hiR = Sphere(3.4, 12)
    private val tail = Cylinder(7.0, 24.0, 16)

    private val headBaseY = -30.0
    private val earBase = 26.0

    init {
        root.transforms.addAll(pitch, yaw)
        build()
    }

    private fun build() {
        // 身体
        val body = Sphere(80.0, 48).apply {
            material = skin
            translateY = 72.0
            scaleY = 1.02
            scaleZ = 0.90
        }
        // 肚皮高光
        val belly = Sphere(48.0, 32).apply {
            material = snoutMat
            translateY = 78.0
            translateZ = -28.0
            scaleZ = 0.45
        }
        root.children.addAll(body, belly)

        // 头
        val head = Sphere(70.0, 48).apply { material = skin }
        headGroup.translateY = headBaseY
        headGroup.children.add(head)

        // 耳朵
        earL.apply {
            material = skinDeep
            translateX = -46.0; translateY = -44.0; translateZ = 4.0
            scaleX = 0.90; scaleY = 1.25; scaleZ = 0.50
            rotationAxis = Rotate.Z_AXIS
            rotate = earBase
        }
        earR.apply {
            material = skinDeep
            translateX = 46.0; translateY = -44.0; translateZ = 4.0
            scaleX = 0.90; scaleY = 1.25; scaleZ = 0.50
            rotationAxis = Rotate.Z_AXIS
            rotate = -earBase
        }
        headGroup.children.addAll(earL, earR)

        // 眼睛 + 高光
        eyeL.apply { material = eyeMat; translateX = -30.0; translateY = -12.0; translateZ = -56.0 }
        eyeR.apply { material = eyeMat; translateX = 30.0; translateY = -12.0; translateZ = -56.0 }
        hiL.apply { material = whiteMat; translateX = -32.5; translateY = -15.5; translateZ = -63.5 }
        hiR.apply { material = whiteMat; translateX = 32.5; translateY = -15.5; translateZ = -63.5 }
        headGroup.children.addAll(eyeL, eyeR, hiL, hiR)

        // 猪鼻
        val snout = Cylinder(30.0, 22.0, 48).apply {
            material = snoutMat
            rotationAxis = Rotate.X_AXIS
            rotate = 90.0
            translateY = 14.0
            translateZ = -68.0
        }
        val noseL = Cylinder(5.5, 8.0, 24).apply {
            material = darkMat
            rotationAxis = Rotate.X_AXIS
            rotate = 90.0
            translateX = -11.0; translateY = 14.0; translateZ = -80.0
        }
        val noseR = Cylinder(5.5, 8.0, 24).apply {
            material = darkMat
            rotationAxis = Rotate.X_AXIS
            rotate = 90.0
            translateX = 11.0; translateY = 14.0; translateZ = -80.0
        }
        headGroup.children.addAll(snout, noseL, noseR)

        // 腮红
        val blushL = Sphere(15.0, 24).apply {
            material = blushMat
            opacity = 0.55
            translateX = -50.0; translateY = 14.0; translateZ = -32.0
            scaleY = 0.65; scaleZ = 0.30
        }
        val blushR = Sphere(15.0, 24).apply {
            material = blushMat
            opacity = 0.55
            translateX = 50.0; translateY = 14.0; translateZ = -32.0
            scaleY = 0.65; scaleZ = 0.30
        }
        headGroup.children.addAll(blushL, blushR)
        root.children.add(headGroup)

        // 手脚
        for (side in listOf(-1, 1)) {
            val arm = Cylinder(12.0, 52.0, 26).apply {
                material = skin
                translateX = 72.0 * side
                translateY = 62.0
                rotationAxis = Rotate.Z_AXIS
                rotate = -18.0 * side
            }
            val leg = Cylinder(16.0, 34.0, 26).apply {
                material = skin
                translateX = 36.0 * side
                translateY = 116.0
            }
            root.children.addAll(arm, leg)
        }

        // 尾巴
        tail.apply {
            material = skinDeep
            translateY = 54.0
            translateZ = 66.0
            rotationAxis = Rotate.X_AXIS
            rotate = -55.0
        }
        val curl1 = Sphere(9.0, 20).apply {
            material = skinDeep
            translateY = 46.0; translateZ = 78.0
        }
        val curl2 = Sphere(6.0, 16).apply {
            material = skinDeep
            translateY = 40.0; translateZ = 68.0
        }
        root.children.addAll(tail, curl1, curl2)
    }

    /** 每帧调用，t 单位是秒 */
    fun animate(t: Double) {
        root.translateY = sin(t * 1.7) * 5.0
        headGroup.rotationAxis = Rotate.Y_AXIS
        headGroup.rotate = sin(t * 0.85) * 5.5
        headGroup.translateY = headBaseY + sin(t * 2.2) * 1.8
        earL.rotate = earBase + sin(t * 2.5) * 9.0
        earR.rotate = -earBase - sin(t * 2.5) * 9.0
        tail.rotate = -55.0 + sin(t * 3.1) * 16.0
    }

    /** 眨眼 */
    fun setBlink(closed: Boolean) {
        val k = if (closed) 0.10 else 1.0
        eyeL.scaleY = k
        eyeR.scaleY = k
        hiL.isVisible = !closed
        hiR.isVisible = !closed
    }
}