package com.unclema123.evilshao

import android.app.Activity
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var gl: GLSurfaceView
    private lateinit var bubble: TextView
    private lateinit var input: EditText
    private lateinit var renderer: PigRenderer
    private lateinit var quotes: Quotes

    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        quotes = Quotes(this)
        renderer = PigRenderer()

        gl = findViewById(R.id.gl)
        gl.setEGLContextClientVersion(2)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        bubble = findViewById(R.id.bubble)
        input = findViewById(R.id.input)
        val send = findViewById<Button>(R.id.send)

        gl.setOnTouchListener(TouchHandler())

        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }

        ui.postDelayed({ say("……你来啦。") }, 500)
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        say("……")
        ui.postDelayed({ say(quotes.reply(text)) }, 520)
        hideKeyboard()
    }

    private fun say(text: String) {
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.animate().cancel()
        bubble.alpha = 0f
        bubble.scaleX = 0.7f
        bubble.scaleY = 0.7f
        bubble.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(190)
            .start()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    /** 拖动旋转、点一下让她说话 */
    private inner class TouchHandler : View.OnTouchListener {
        private var lastX = 0f
        private var lastY = 0f
        private var travelled = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    travelled = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    travelled += abs(dx) + abs(dy)
                    renderer.yaw += dx * 0.38f
                    renderer.pitch = (renderer.pitch + dy * 0.20f).coerceIn(-18f, 18f)
                }
                MotionEvent.ACTION_UP -> {
                    if (travelled < 12f) say(quotes.randomSignature())
                }
            }
            return true
        }
    }
}