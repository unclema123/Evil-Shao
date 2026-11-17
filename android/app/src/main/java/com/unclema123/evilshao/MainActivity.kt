package com.unclema123.evilshao

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var gl: GLSurfaceView
    private lateinit var bubble: TextView
    private lateinit var input: EditText
    private lateinit var recordView: TextView
    private lateinit var learnView: TextView
    private lateinit var renderer: PigRenderer
    private lateinit var quotes: Quotes
    private lateinit var chatLog: ChatLog
    private lateinit var learner: Learner

    private val ui = Handler(Looper.getMainLooper())
    private var sinceTrain = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        quotes = Quotes(this)
        chatLog = ChatLog(this)
        learner = Learner(this)
        learner.load()
        quotes.learner = learner

        renderer = PigRenderer()

        gl = findViewById(R.id.gl)
        gl.setEGLContextClientVersion(2)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        bubble = findViewById(R.id.bubble)
        input = findViewById(R.id.input)
        recordView = findViewById(R.id.record)
        learnView = findViewById(R.id.learn)
        val send = findViewById<Button>(R.id.send)

        gl.setOnTouchListener(TouchHandler())

        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        recordView.setOnClickListener { showHistory() }
        learnView.setOnClickListener { showLearner() }

        // 启动时先按历史记录学一遍
        trainAsync(showToast = false)

        ui.postDelayed({ say("……你来啦。") }, 500)
    }

    override fun onResume() {
        super.onResume()
        updateBadges()
    }

    private fun submit() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        chatLog.append("我", text)
        say("……")
        ui.postDelayed({
            val reply = quotes.reply(text)
            chatLog.append("她", reply)
            say(reply)
            updateBadges()
            sinceTrain++
            if (sinceTrain >= 12) {
                sinceTrain = 0
                trainAsync(showToast = false)
            }
        }, 520)
        hideKeyboard()
    }

    /** 后台线程重扫聊天记录、重算权重 */
    private fun trainAsync(showToast: Boolean) {
        Thread {
            try {
                learner.train(chatLog.entries())
                ui.post {
                    updateBadges()
                    if (showToast) {
                        Toast.makeText(this, "学完了：分析了 ${learner.analyzed} 条对话", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun updateBadges() {
        val n = chatLog.count()
        recordView.text = if (n > 0) "记录 $n" else "记录"
        learnView.text = if (learner.analyzed > 0) "学习 ${learner.feedbacks}" else "学习"
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

    /** 聊天记录：翻看 / 分享 / 清空 */
    private fun showHistory() {
        val body = chatLog.asText()
        val tv = TextView(this).apply {
            text = if (body.isEmpty()) "还没有任何记录。\n\n去和小猪说几句话再来。" else body
            textSize = 13f
            setLineSpacing(6f, 1f)
            setPadding(48, 36, 48, 36)
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }

        val builder = AlertDialog.Builder(this)
            .setTitle("聊天记录 · ${chatLog.count()} 条")
            .setView(sv)
            .setNegativeButton("关闭", null)

        if (body.isNotEmpty()) {
            builder.setPositiveButton("分享") { _, _ -> shareLog(body) }
            builder.setNeutralButton("清空") { _, _ -> confirmClear() }
        }
        builder.show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空记录？")
            .setMessage("所有聊天记录会被删除，无法恢复。\n\n注意：学习结果会保留，想一起清掉请用「学习 → 重置」。")
            .setPositiveButton("清空") { _, _ ->
                chatLog.clear()
                updateBadges()
                Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareLog(body: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "小猪聊天记录")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, "分享聊天记录"))
    }

    /** 学习面板：看她学到了什么 */
    private fun showLearner() {
        val tv = TextView(this).apply {
            text = learner.report()
            textSize = 13f
            setLineSpacing(8f, 1f)
            setPadding(48, 36, 48, 36)
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }

        AlertDialog.Builder(this)
            .setTitle("她学到的东西")
            .setView(sv)
            .setPositiveButton("学一次") { _, _ -> trainAsync(showToast = true) }
            .setNeutralButton("重置") { _, _ -> confirmResetLearning() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun confirmResetLearning() {
        AlertDialog.Builder(this)
            .setTitle("重置学习？")
            .setMessage("学到的东西全部清空，她会回到最初的状态。聊天记录不受影响。")
            .setPositiveButton("重置") { _, _ ->
                learner.reset()
                updateBadges()
                Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
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
                    if (travelled < 12f) {
                        val line = quotes.randomSignature()
                        chatLog.append("她", line)
                        say(line)
                        updateBadges()
                    }
                }
            }
            return true
        }
    }
}