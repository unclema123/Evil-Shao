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
import android.text.InputType
import android.view.MotionEvent
import android.view.View
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

        gl = findViewById(R.id.gl)
        gl.setEGLContextClientVersion(2)
        gl.setRenderer(renderer)
        gl.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        bubble = findViewById(R.id.bubble)
        input = findViewById(R.id.input)
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
            input.setText(q)
            submit()
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
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        chatLog.append("我", text)
        style.observe(text)
        style.save()
        say("……")
        hideKeyboard()

        if (llm.isConfigured()) {
            ui.postDelayed({ askLlm(text) }, 260)
        } else {
            ui.postDelayed({ afterReply(style.adapt(quotes.reply(text))) }, 520)
        }
    }

    /** 走大模型；查得到就用知识库，查不到就上网搜，搜到记住；全失败回退本地 */
    private fun askLlm(userText: String) {
        // 1. 先看自己学过的
        val known = try {
            knowledge.find(userText)
        } catch (e: Exception) {
            null
        }
        if (known != null) {
            afterReply(known.answer)
            return
        }

        // 2. 没学过，且没开联网 → 直接问模型
        if (!llm.searchEnabled) {
            askLlmPlain(userText)
            return
        }

        // 3. 联网搜一轮，抓正文喂给模型
        say("……我查一下")
        Thread {
            val hits = try {
                // 先压搜索词，再拿 6 条，然后把前 3 条的正文抓下来。
                // 只有标题和七八十字摘要的话，模型问啥都答不上来。
                val raw = web.search(Query.toSearchQuery(userText), 6)
                if (raw.isEmpty()) raw else web.enrich(raw, 3, 900)
            } catch (e: Exception) {
                emptyList<WebSearch.Hit>()
            }

            val usable = hits.sortedByDescending { it.body.length }
                .filter { it.body.isNotEmpty() || it.snippet.length >= 20 }
            android.util.Log.d(
                "EvilShao",
                "搜索词=[${Query.toSearchQuery(userText)}] 命中=${hits.size} " +
                    "有正文=${hits.count { it.body.isNotEmpty() }} 可用=${usable.size} " +
                    "错误=${web.lastError}"
            )
            if (usable.isNotEmpty()) {
                android.util.Log.d(
                    "EvilShao",
                    "材料=" + Persona.materialPrompt(userText, usable).take(300)
                )
            }
            val reply = if (usable.isEmpty()) {
                // 没搜到就别装作查过，先告诉用户，再凭记忆答
                ui.post { say("……没连上，我先凭印象") }
                llm.chat(Persona.systemPrompt(quotes, style.hint()), userText)
            } else {
                // 查资料要确定性，温度和长度都跟聊天分开
                llm.chat(
                    Persona.researchPrompt(),
                    Persona.materialPrompt(userText, usable),
                    temperature = 0.25,
                    maxTokens = 300,
                    readTimeoutMs = 45000
                )
            }

            ui.post {
                android.util.Log.d("EvilShao", "模型返回=[${reply}] 错误=[${llm.lastError}]")
                val clean = sanitize(reply, if (usable.isEmpty()) 40 else 70)
                if (clean != null) {
                    // 「不知道」也存进知识库的话，以后每次问都秒回不知道，
                    // 看起来就像搜索坏了。没查到的答案不记账。
                    if (usable.isNotEmpty() && !knowledge.isNonAnswer(clean)) {
                        knowledge.save(userText, clean, usable.map { it.url })
                    }
                    afterReply(style.adapt(clean))
                    panelText?.text = learnPanelText()
                } else {
                    afterReply(style.adapt(quotes.reply(userText)))
                }
            }
        }.start()
    }

    private fun askLlmPlain(userText: String) {
        Thread {
            val reply = try {
                llm.chat(Persona.systemPrompt(quotes, style.hint()), userText)
            } catch (e: Exception) {
                null
            }
            ui.post {
                val clean = sanitize(reply, 40)
                if (clean != null) afterReply(style.adapt(clean))
                else afterReply(style.adapt(quotes.reply(userText)))
            }
        }.start()
    }

    /** 模型偶尔会带引号或换行，清理一下 */
    private fun sanitize(raw: String?, maxLen: Int = 40): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim().replace("\n", " ")
        s = s.trim('"', '「', '」', '“', '”', '\'')
        if (s.contains("作为") && s.contains("AI")) return null
        if (s.length > maxLen) s = s.take(maxLen)
        return s.ifBlank { null }
    }

    private fun afterReply(reply: String) {
        chatLog.append("她", reply)
        say(reply)
        val mood = moodFor(reply)
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
     * 看她说了什么，决定露什么表情。
     *
     * 顺序有讲究：先判断怼人和惊讶，因为「你闭嘴吧」里也可能带个感叹号，
     * 放到后面就会被当成长辈式平静。
     */
    private fun moodFor(reply: String): GlbRenderer.Mood {
        val t = reply.trim()
        if (t.isEmpty()) return GlbRenderer.Mood.NEUTRAL

        val angry = listOf("闭嘴", "傻子", "有病", "烦", "起开", "滚", "反弹", "你才", "少来", "无聊", "幼稚")
        val surprised = listOf("我的天", "窝趣", "卧槽", "天哪", "真的假的", "不会吧", "什么鬼", "离谱")
        val happy = listOf("哈哈", "嘻嘻", "笑死", "嘿嘿", "好耶", "开心", "好玩", "有意思")
        val shy = listOf("人美心善", "我大度", "面子", "谢谢", "夸", "害羞", "哪有", "讨厌")
        val bored = listOf("不知道", "随便", "行吧", "算了", "无聊", "嗯", "哦", "没意思")

        fun hit(list: List<String>) = list.any { t.contains(it) }

        return when {
            hit(angry) -> GlbRenderer.Mood.ANGRY
            hit(surprised) -> GlbRenderer.Mood.SURPRISED
            hit(happy) -> GlbRenderer.Mood.HAPPY
            hit(shy) -> GlbRenderer.Mood.SHY
            // 短促的敷衍（嗯、哦、……）才判无聊，长了就不是敷衍
            hit(bored) && t.length <= 6 -> GlbRenderer.Mood.BORED
            else -> GlbRenderer.Mood.NEUTRAL
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
                        val line = style.adapt(quotes.randomSignature())
                        chatLog.append("她", line)
                        say(line)
                        renderer.show(moodFor(line))
                        updateBadges()
                    }
                }
            }
            return true
        }
    }
}