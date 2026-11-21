package com.unclema123.evilshao

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import java.util.Locale

class MainActivity : Activity() {

    /** 上次因为「拼音没选字」被拦的时间，2.5 秒内再点一次就放行 */
    private var pinyinWarnAt = 0L

    /**
     * 不用系统输入法。
     *
     * 模拟器接物理键盘时，输入法只留一条候选条浮在屏幕底部，看着就是「字母跑到框外」。
     * 那条东西是输入法画的，应用删不掉，只能整体不用输入法——代价是打不了中文。
     */
    private var noIme = false

    /** 调试脚本塞进来的句子，不受拼音拦截影响 */
    private var debugAsk = false

    private lateinit var gl: GLSurfaceView
    private lateinit var bubble: TextView
    private lateinit var input: EditText
    private lateinit var titleView: TextView
    private lateinit var menuPanel: LinearLayout
    private lateinit var scrim: View
    private lateinit var renderer: GlbRenderer
    private lateinit var quotes: Quotes
    private lateinit var chatLog: ChatLog
    private lateinit var learner: Learner
    private lateinit var dreamer: Dreamer
    private lateinit var llm: LlmClient
    private lateinit var names: Names
    private lateinit var web: WebSearch
    private lateinit var knowledge: Knowledge
    private lateinit var style: StyleProfile

    /** 对话编排（记忆 / 联网 / 模型 / 本地兜底）都在这里，Activity 只管界面 */
    private lateinit var brain: ChatBrain

    private val ui = Handler(Looper.getMainLooper())
    private var sinceTrain = 0
    private var panelText: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        llm = LlmClient(this)
        names = Names(this)
        web = WebSearch()
        knowledge = Knowledge(this)
        style = StyleProfile(this)
        style.load()
        quotes = Quotes(this)
        chatLog = ChatLog(this)
        learner = Learner(this)
        learner.load()
        quotes.learner = learner

        dreamer = Dreamer(this)
        dreamer.load()
        quotes.invented.addAll(dreamer.invented)
        quotes.dreamer = dreamer

        renderer = GlbRenderer(this)

        // 业务逻辑全部委托给 ChatBrain；这里只负责把回调反映到界面上
        brain = ChatBrain(
            llm = llm,
            web = web,
            knowledge = knowledge,
            quotes = quotes,
            style = style,
            chatLog = chatLog,
            listener = object : ChatBrain.Listener {
                override fun onStatus(text: String) = say(text)
                override fun onStream(partial: String) = sayPartial(partial)
                override fun onReply(reply: String, model: Boolean) = afterReply(reply)
                override fun onMemorySaved() {
                    panelText?.text = learnPanelText()
                }
            },
            postToUi = { r -> ui.post(r) }
        )

        gl = findViewById(R.id.gl)
        gl.setEGLContextClientVersion(2)
        // 4x MSAA：边缘锯齿是小猪看着「糙」的主因之一；拿不到多重采样就自动退回普通配置
        gl.setEGLConfigChooser(MsaaChooser())
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        bubble = findViewById(R.id.bubble)
        input = findViewById(R.id.input)
        noIme = getSharedPreferences("input", MODE_PRIVATE).getBoolean("noIme", false)
        titleView = findViewById(R.id.title)
        menuPanel = findViewById(R.id.menuPanel)
        scrim = findViewById(R.id.scrim)
        val menuBtn = findViewById<TextView>(R.id.menu)
        val send = findViewById<Button>(R.id.send)

        titleView.text = getString(R.string.app_name)
        titleView.setOnClickListener { showNameSettings(firstRun = false) }

        gl.setOnTouchListener(TouchHandler())

        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        // 点进来就把软键盘带出来。键盘不弹的时候，有些输入法（雷电自带的拼音）
        // 会在屏幕最底下另开一条合成窗口显示拼音，看着就像字母跑到输入框外面去了。
        // 组词和候选留在键盘里，页面上就不会多那么一条。
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) input.postDelayed({ showKeyboard() }, 120)
        }
        input.setOnClickListener { showKeyboard() }
        input.showSoftInputOnFocus = !noIme
        menuBtn.setOnClickListener { toggleMenu() }
        scrim.setOnClickListener { closeMenu() }
        findViewById<TextView>(R.id.item_close).setOnClickListener { closeMenu() }
        findViewById<TextView>(R.id.item_record).setOnClickListener {
            closeMenu()
            showHistory()
        }
        findViewById<TextView>(R.id.item_learn).setOnClickListener {
            closeMenu()
            showLearner()
        }
        findViewById<TextView>(R.id.item_name).setOnClickListener {
            closeMenu()
            showNameSettings(firstRun = false)
        }
        findViewById<TextView>(R.id.item_ai).setOnClickListener {
            closeMenu()
            showAiSettings()
        }
        findViewById<TextView>(R.id.item_ime).setOnClickListener {
            closeMenu()
            noIme = !noIme
            getSharedPreferences("input", MODE_PRIVATE).edit()
                .putBoolean("noIme", noIme).apply()
            input.showSoftInputOnFocus = !noIme
            if (noIme) {
                input.clearFocus()
                hideKeyboard()
            }
            Toast.makeText(
                this,
                if (noIme) {
                    "已关掉系统输入法：底部那条拼音不会再有，但只能直接打英文/数字"
                } else {
                    "已恢复系统输入法：可以打中文"
                },
                Toast.LENGTH_LONG
            ).show()
        }

        trainAsync(showToast = false)

        // 第一次进来先让他给自己起个名字
        if (!names.configured) {
            ui.postDelayed({ showNameSettings(firstRun = true) }, 900)
        }

        ui.postDelayed({
            say("……你来啦。")
            renderer.wave(2600L)
        }, 500)

        debugMood()
        debugAsk()
    }

    /**
     * 调试图专用：adb 启动时带 --es ask "济南今天天气" 就自动问一句。
     * 中文没法用 adb shell input text 打进去，只能这么测。
     */
    private fun debugAsk() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val q = intent?.getStringExtra("ask")?.trim() ?: return
        if (q.isEmpty()) return
        ui.postDelayed({
            debugAsk = true          // 绕过「拼音没选字」拦截，这是脚本塞进来的完整句子
            input.setText(q)
            submit()
            debugAsk = false
        }, 600)
    }

    /**
     * 调试图专用：adb 启动时带 --es mood happy 就能让她停在某个表情上，
     * 方便截图对比。只在 debuggable 包里生效。
     */
    private fun debugMood() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val want = intent?.getStringExtra("mood")?.lowercase() ?: return
        val m = when (want) {
            "neutral" -> GlbRenderer.Mood.NEUTRAL
            "happy" -> GlbRenderer.Mood.HAPPY
            "angry" -> GlbRenderer.Mood.ANGRY
            "surprised" -> GlbRenderer.Mood.SURPRISED
            "shy" -> GlbRenderer.Mood.SHY
            "bored" -> GlbRenderer.Mood.BORED
            else -> return
        }
        ui.postDelayed({ renderer.show(m, 600_000L) }, 300)
    }

    override fun onResume() {
        super.onResume()
        updateBadges()
    }

    @Deprecated("老版本兼容，用 OnBackPressedDispatcher 更正规")
    override fun onBackPressed() {
        if (menuPanel.visibility == View.VISIBLE) {
            closeMenu()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    private fun submit() {
        val raw = input.text.toString().trim()

        // 两种「拼音还没选成字」的情况：
        //  1) 规矩的输入法会留组词区，span 能查出来
        //  2) 雷电这种输入法在点发送前就把字母直接提交进来了，span 是 -1，
        //     只能看内容：中文输入法 + 纯 a-z 字母串 → 基本就是没选字的拼音
        val composing = !debugAsk && !noIme && BaseInputConnection.getComposingSpanStart(input.text) >= 0
        if (composing ||
            (!debugAsk && !noIme && raw.isNotEmpty() && isChineseIme() && isPlainAscii(raw))
        ) {
            // 第一次拦下来提示，2.5 秒内再点一次就照发——万一是真想发英文单词
            val now = System.currentTimeMillis()
            if (composing || now - pinyinWarnAt > 2500) {
                pinyinWarnAt = now
                Toast.makeText(this, "拼音还没选成字，再点一次就直接发", Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (raw.isEmpty()) return
        input.text.clear()
        chatLog.append("我", raw)
        style.observe(raw)
        style.save()
        say("……")

        // 收键盘的同时要放开焦点。只收键盘不放焦点的话，
        // 气泡一出现、布局一变，输入法会自己弹回来，还会切回默认语言——
        // 就是「回答完自动切换输入法」的来源。
        input.clearFocus()
        ui.postDelayed({ hideKeyboard() }, 80)

        if (llm.isConfigured()) {
            ui.postDelayed({ brain.ask(raw) }, 260)
        } else {
            // 没配大模型时也走编排层，保证「点一下就有回应」的体感不变
            ui.postDelayed({ brain.ask(raw) }, 520)
        }
    }

    /** 纯小写字母（拼音不会带空格和符号；带汉字就一定不是没选字） */
    private fun isPlainAscii(t: String): Boolean =
        t.isNotEmpty() && t.all { it in 'a'..'z' || it == '\'' }

    /** 当前是不是中文拼音类输入法：雷电 / 搜狗 / 百度 / 讯飞 / AOSP 拼音 */
    private fun isChineseIme(): Boolean {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val sub = try {
            imm.currentInputMethodSubtype
        } catch (e: Exception) {
            null
        }
        if (sub != null && sub.locale.contains("zh", ignoreCase = true)) return true
        val id = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (e: Exception) {
            null
        } ?: return false
        val k = id.lowercase(Locale.ROOT)
        return k.contains("pinyin") || k.contains("sogou") || k.contains("baidu.input") ||
            k.contains("iflytek") || k.contains(".zh")
    }


    private fun afterReply(reply: String) {
        chatLog.append("她", reply)
        say(reply)
        val mood = MoodClassifier.of(reply)
        renderer.show(mood)
        if (mood == GlbRenderer.Mood.HAPPY) renderer.wave(2000L)
        updateBadges()
        sinceTrain++
        if (sinceTrain >= 12) {
            sinceTrain = 0
            trainAsync(showToast = false)
        }
    }

    /**
     * 一轮「自学」：重算权重 + 造几条新句子。
     */
    private fun trainAsync(showToast: Boolean) {
        Thread {
            try {
                val entries = chatLog.entries()
                learner.train(entries)
                // 重新统计「你怎么说话」，纠正增量统计的漂移
                style.rebuild(entries.filter { it.role == "我" }.map { it.message })

                val known = HashSet<String>()
                known.addAll(quotes.signatures)
                known.addAll(quotes.fallback)
                quotes.rules.forEach { known.addAll(it.replies) }
                known.addAll(quotes.invented)
                dreamer.grow(known, 2)

                quotes.invented.clear()
                quotes.invented.addAll(dreamer.invented)
            } catch (_: Exception) {
            }
            ui.post {
                updateBadges()
                panelText?.text = learnPanelText()
                if (showToast) {
                    Toast.makeText(
                        this,
                        "学完了：看了 ${style.samples} 句你的话，新句 ${quotes.invented.size} 句",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }.start()
    }

    /** 面板改成纯文字入口，不再显示状态；保留这个函数给各处统一刷新调用 */
    private fun updateBadges() {
    }

    private fun toggleMenu() {
        if (menuPanel.visibility == View.VISIBLE) closeMenu() else openMenu()
    }

    private fun openMenu() {
        updateBadges()
        menuPanel.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        menuPanel.alpha = 0f
        menuPanel.translationY = -12f
        menuPanel.animate().alpha(1f).translationY(0f).setDuration(150).start()
    }

    private fun closeMenu() {
        if (menuPanel.visibility != View.VISIBLE) return
        menuPanel.animate().alpha(0f).translationY(-12f).setDuration(120)
            .withEndAction {
                menuPanel.visibility = View.GONE
                scrim.visibility = View.GONE
            }
            .start()
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

    /**
     * 流式输出用：只改文字，不跑缩放动画。
     * 模型每几十毫秒吐一次增量，每次都触发 190ms 动画会抖成帕金森。
     */
    private fun sayPartial(text: String) {
        if (text.isBlank()) {
            bubble.visibility = View.GONE
            return
        }
        bubble.animate().cancel()
        bubble.alpha = 1f
        bubble.scaleX = 1f
        bubble.scaleY = 1f
        bubble.text = text
        bubble.visibility = View.VISIBLE
    }

    private fun learnPanelText(): String {
        val sb = StringBuilder(learner.report())
        sb.append("\n\n── 你怎么说话（本地学的）──\n")
        sb.append(style.summary())
        sb.append("\n\n── 自己造的新句（按质量分排序）──\n")
        if (quotes.invented.isEmpty()) {
            sb.append("（还没有，点「再造几句」让她想想）")
        } else {
            quotes.invented
                .sortedByDescending { dreamer.qualityOf(it) }
                .take(10)
                .forEach {
                    sb.append(String.format(Locale.CHINA, "  %d分  %s\n", dreamer.qualityOf(it), it))
                }
        }
        sb.append("\n── 大模型 ──\n")
        sb.append(llmStatusText())
        llm.lastError?.let { sb.append("\n最近一次失败：").append(it) }

        sb.append("\n\n── 联网学习 ──\n")
        sb.append(
            when {
                !llm.isConfigured() -> "未生效：联网搜索要靠大模型读搜索结果，先把大模型配好。"
                llm.searchEnabled -> "已开启：不会的先上网查，查到就记住。"
                else -> "已关闭：只用本地语料和大模型本身的知识。"
            }
        )
        sb.append("\n知识库：").append(knowledge.count()).append(" 条")
        val recent = try {
            knowledge.recent(3)
        } catch (e: Exception) {
            emptyList<Knowledge.Entry>()
        }
        for (e in recent) {
            sb.append("\n  ").append(e.question.take(20)).append(" → ").append(e.answer.take(16))
        }
        if (knowledge.count() > 0) sb.append("\n（完整内容看 files/knowledge.jsonl）")

        return sb.toString().trimEnd()
    }

    /** 把「为什么没启用」说清楚，别只给一句未启用 */
    private fun llmStatusText(): String = when {
        llm.isConfigured() && llm.keyless ->
            "已启用（免 Key）：${llm.model}\n公开免费接口，不用注册。忙的时候可能慢几秒或失败。"
        llm.isConfigured() ->
            "已启用：${llm.model}\n断开网络或调用失败时会自动用本地语料。"
        llm.enabled && llm.apiKey.isBlank() && !llm.keyless ->
            "缺 API Key，模型调不动，现在说话用的是本地语料。\n点「接 AI」→「用免 Key 的免费 AI」就能立刻用上。"
        llm.enabled ->
            "配置不完整（接口地址或模型名为空），现在说话用的是本地语料。"
        else ->
            "没勾「启用大模型」，现在说话用的是本地语料。\n点「接 AI」填 Key 并勾上就能接。"
    }

    private fun saveMessage(): String = when {
        llm.isConfigured() && llm.keyless -> "已启用免 Key 免费 AI：${llm.model}"
        llm.isConfigured() -> "已启用大模型：${llm.model}"
        llm.apiKey.isBlank() && !llm.keyless -> "已保存，但没填 Key 也没开免 Key 模式，还是本地语料"
        !llm.enabled -> "已保存，但没勾「启用大模型」"
        else -> "已保存，但接口地址或模型名不完整"
    }

    private fun showHistory() {
        val (mine, hers) = chatLog.participants()
        val total = mine + hers
        val body = chatLog.asTextGrouped(names.me, names.her)
        val head = if (total > 0) "${names.me} $mine 条 · ${names.her} $hers 条\n\n" else ""
        val tv = TextView(this).apply {
            text = if (body.isEmpty()) {
                "还没有任何记录。\n\n去找${names.her}说几句话再来。"
            } else {
                head + body
            }
            textSize = 13f
            setLineSpacing(6f, 1f)
            setPadding(48, 36, 48, 36)
            setTextIsSelectable(true)
        }
        val sv = ScrollView(this).apply { addView(tv) }

        val builder = AlertDialog.Builder(this)
            .setTitle("聊天记录 · $total 条")
            .setView(sv)
            .setNegativeButton("关闭", null)

        if (body.isNotEmpty()) {
            builder.setPositiveButton("分享") { _, _ -> shareLog(body) }
            builder.setNeutralButton("清空") { _, _ -> confirmClear() }
        }
        builder.show()
    }

    /** 设置两个人的名字：点标题进来，或第一次启动时自动弹 */
    private fun showNameSettings(firstRun: Boolean) {
        val pad = (18 * resources.displayMetrics.density).toInt()

        val etMe = EditText(this).apply {
            setText(if (names.configured) names.me else "")
            hint = "你的名字"
            setSingleLine()
        }
        val etHer = EditText(this).apply {
            setText(names.her)
            hint = "她的名字"
            setSingleLine()
        }
        val tip = TextView(this).apply {
            text = if (firstRun) {
                "聊天记录要分清谁在说话。给自己起个名字，以后点左上角标题就能改。"
            } else {
                "只影响聊天记录里显示的称呼，最多 12 个字。"
            }
            textSize = 11f
            setTextColor(0xFF8C8C8C.toInt())
            setPadding(0, 12, 0, 0)
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(0xFF8C6A75.toInt())
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(label("机主名字（你）"))
            addView(etMe)
            addView(label("小猪的名字"))
            addView(etHer)
            addView(tip)
        }

        AlertDialog.Builder(this)
            .setTitle(if (firstRun) "先给自己起个名字" else "设置名字")
            .setView(layout)
            .setPositiveButton("保存") { _, _ ->
                names.save(etMe.text.toString(), etHer.text.toString())
                applyNames()
                Toast.makeText(this, "已保存：${names.me} 和 ${names.her}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(if (firstRun) "以后再说" else "取消", null)
            .show()
    }

    private fun applyNames() {
        // 标题固定用应用名，两个人的称呼只影响聊天记录
        updateBadges()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空记录？")
            .setMessage("所有聊天记录会被删除，无法恢复。\n\n注意：学习结果和造出的新句会保留。")
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
            putExtra(Intent.EXTRA_SUBJECT, "和${names.her}的聊天记录")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(intent, "分享聊天记录"))
    }

    private fun showLearner() {
        val tv = TextView(this).apply {
            text = learnPanelText()
            textSize = 13f
            setLineSpacing(8f, 1f)
            setPadding(48, 36, 48, 24)
            setTextIsSelectable(true)
        }
        panelText = tv
        val sv = ScrollView(this).apply { addView(tv) }

        fun mkButton(label: String): Button = Button(this).apply {
            text = label
            textSize = 12f
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(0xFF8C6A75.toInt())
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(12, 0, 12, 0)
            addView(mkButton("学一次").apply {
                setOnClickListener { trainAsync(showToast = true) }
            })
            addView(mkButton("再造几句").apply {
                setOnClickListener { inventMore() }
            })
            addView(mkButton("重置").apply {
                setOnClickListener { confirmResetLearning() }
            })
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(sv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            ))
            addView(row)
        }

        AlertDialog.Builder(this)
            .setTitle("她学到的东西")
            .setView(root)
            .setNegativeButton("关闭") { _, _ -> panelText = null }
            .show()
    }

    /** 大模型设置：接口地址 / 模型 / Key */
    private fun showAiSettings() {
        val pad = (18 * resources.displayMetrics.density).toInt()

        val cb = CheckBox(this).apply {
            text = "启用大模型"
            isChecked = llm.enabled
        }
        val cbSearch = CheckBox(this).apply {
            text = "联网搜索（不会的先上网查，查到就记住）"
            isChecked = llm.searchEnabled
            textSize = 13f
        }
        val cbKeyless = CheckBox(this).apply {
            text = "免 Key 模式（公开接口，不用填 Key）"
            isChecked = llm.keyless
            textSize = 13f
        }
        val etBase = EditText(this).apply {
            setText(llm.baseUrl)
            hint = "https://open.bigmodel.cn/api/paas/v4"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
        }
        val etModel = EditText(this).apply {
            setText(llm.model)
            hint = "glm-4-flash"
        }
        val etKey = EditText(this).apply {
            setText(llm.apiKey)
            hint = "粘贴 API Key"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val tip = TextView(this).apply {
            text = "想零门槛先用起来：点下面「用免 Key 的免费 AI」，不用注册任何账号。\n" +
                "想更稳定：智谱 open.bigmodel.cn 注册后建 API Key，GLM-4-Flash 免费。\n" +
                "也支持硅基流动、DeepSeek 等 OpenAI 兼容接口。\n" +
                "Key 只存在本机，卸载即清除。"
            textSize = 11f
            setTextColor(0xFF8C8C8C.toInt())
        }
        val result = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF6D4B57.toInt())
        }
        val testBtn = Button(this).apply {
            text = "用免 Key 的免费 AI"
            setOnClickListener {
                etBase.setText(LlmClient.KEYLESS_BASE)
                etModel.setText(LlmClient.KEYLESS_MODEL)
                etKey.setText("")
                cbKeyless.isChecked = true
                cb.isChecked = true
                cbSearch.isChecked = true
                result.text = "已填好免 Key 接口，点「保存」立刻生效"
            }
        }
        val probeBtn = Button(this).apply {
            text = "测试连接"
            setOnClickListener {
                val base = etBase.text.toString().trim()
                val modelName = etModel.text.toString().trim()
                val key = etKey.text.toString().trim()
                val free = cbKeyless.isChecked
                if (key.isBlank() && !free) {
                    result.text = "先填 Key，或勾「免 Key 模式」"
                    return@setOnClickListener
                }
                result.text = "测试中…"
                Thread {
                    val probe = LlmClient(this@MainActivity)
                    probe.enabled = true
                    probe.baseUrl = base
                    probe.model = modelName
                    probe.apiKey = key
                    probe.keyless = free
                    val r = probe.chat("你只回一个词。", "说「好」")
                    ui.post {
                        result.text = if (r != null) "连接成功，模型回了：$r"
                        else "连接失败（检查 Key、模型名或网络）"
                    }
                }.start()
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(cb)
            addView(cbSearch)
            addView(cbKeyless)
            addView(TextView(this@MainActivity).apply {
                text = "接口地址"; textSize = 12f; setTextColor(0xFF8C6A75.toInt())
            })
            addView(etBase)
            addView(TextView(this@MainActivity).apply {
                text = "模型名"; textSize = 12f; setTextColor(0xFF8C6A75.toInt())
            })
            addView(etModel)
            addView(TextView(this@MainActivity).apply {
                text = "API Key"; textSize = 12f; setTextColor(0xFF8C6A75.toInt())
            })
            addView(etKey)
            addView(tip)
            addView(testBtn)
            addView(result)
        }
        val sv = ScrollView(this).apply { addView(layout) }

        AlertDialog.Builder(this)
            .setTitle("接入大模型（可选）")
            .setView(sv)
            .setPositiveButton("保存") { _, _ ->
                llm.baseUrl = etBase.text.toString()
                llm.model = etModel.text.toString()
                llm.apiKey = etKey.text.toString()
                llm.enabled = cb.isChecked
                llm.searchEnabled = cbSearch.isChecked
                llm.keyless = cbKeyless.isChecked
                updateBadges()
                panelText?.text = learnPanelText()
                Toast.makeText(this, saveMessage(), Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun inventMore() {
        Thread {
            var newOnes: List<String> = emptyList()
            try {
                val known = HashSet<String>()
                known.addAll(quotes.signatures)
                known.addAll(quotes.fallback)
                quotes.rules.forEach { known.addAll(it.replies) }
                known.addAll(quotes.invented)
                newOnes = dreamer.grow(known, 3)
                quotes.invented.clear()
                quotes.invented.addAll(dreamer.invented)
            } catch (_: Exception) {
            }
            ui.post {
                updateBadges()
                panelText?.text = learnPanelText()
                if (newOnes.isNotEmpty()) {
                    Toast.makeText(
                        this,
                        "造了 " + newOnes.size + " 句：" +
                            newOnes.joinToString("、") { "${dreamer.qualityOf(it)}分" },
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(this, "这次没造出合格的句子", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun confirmResetLearning() {
        AlertDialog.Builder(this)
            .setTitle("重置学习？")
            .setMessage("学到的权重和造出的新句全部清空，她会回到最初的状态。聊天记录不受影响。")
            .setPositiveButton("重置") { _, _ ->
                learner.reset()
                dreamer.invented.clear()
                dreamer.quality.clear()
                dreamer.save()
                quotes.invented.clear()
                updateBadges()
                panelText?.text = learnPanelText()
                Toast.makeText(this, "已重置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showKeyboard() {
        if (noIme) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        // SHOW_FORCED：模拟器接了物理键盘时，SHOW_IMPLICIT 会被忽略，
        // 输入法只留一条很小的候选条浮在页面最下面。强制拉出完整键盘，
        // 拼音和候选就都在键盘里，不会浮在输入框外面。
        @Suppress("DEPRECATION")
        imm.showSoftInput(input, InputMethodManager.SHOW_FORCED)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
        // 顺带清掉组词状态，免得下次点进来还挂着上次的拼音。
        // currentInputConnection 不是公开 API，直接从文本的 span 上摘。
        try {
            BaseInputConnection.removeComposingSpans(input.text)
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 作废所有在途请求：Activity 都销毁了，回调再打到界面上就是崩
        if (::brain.isInitialized) brain.shutdown()
    }

    /** 拖动旋转、点一下让她说话 */

    /**
     * 优先选带 4x 多重采样的 EGL 配置，选不到就用普通的。
     *
     * 手写的原因：GLSurfaceView 默认配置一定没有 MSAA，Activity 里也没别处能开，
     * 只能自己挑 EGLConfig。边缘锯齿是小猪看着「糙」的主因之一。
     */
    private class MsaaChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(
            egl: javax.microedition.khronos.egl.EGL10,
            display: javax.microedition.khronos.egl.EGLDisplay
        ): javax.microedition.khronos.egl.EGLConfig? {
            val configs = arrayOfNulls<javax.microedition.khronos.egl.EGLConfig>(1)
            val num = IntArray(1)
            fun pick(attribs: IntArray): javax.microedition.khronos.egl.EGLConfig? {
                configs[0] = null
                num[0] = 0
                return if (egl.eglChooseConfig(display, attribs, configs, 1, num) && num[0] > 0) configs[0] else null
            }
            val msaa = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                javax.microedition.khronos.egl.EGL10.EGL_SAMPLE_BUFFERS, 1,
                javax.microedition.khronos.egl.EGL10.EGL_SAMPLES, 4,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
                javax.microedition.khronos.egl.EGL10.EGL_NONE
            )
            pick(msaa)?.let {
                android.util.Log.d("EvilShao", "EGL: 4x MSAA 已启用")
                return it
            }
            val plain = intArrayOf(
                javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, 8,
                javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, 16,
                javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, 4,
                javax.microedition.khronos.egl.EGL10.EGL_NONE
            )
            pick(plain)?.let {
                android.util.Log.d("EvilShao", "EGL: 没拿到 MSAA，用普通配置")
                return it
            }
            return null
        }
    }

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
                        val line = style.adapt(quotes.randomSignature())
                        chatLog.append("她", line)
                        say(line)
                        renderer.show(MoodClassifier.of(line))
                        // 记一笔，免得接下来的模型回复跟她刚说的撞车
                        brain.noteSaid(line)
                        updateBadges()
                    }
                }
            }
            return true
        }
    }
}
