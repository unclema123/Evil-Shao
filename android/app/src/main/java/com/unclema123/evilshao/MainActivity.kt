package com.unclema123.evilshao

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan

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

    private lateinit var scene: SceneView
    private lateinit var bubble: TextView
    private lateinit var input: EditText
    private lateinit var inputBar: View
    private lateinit var titleView: TextView
    private lateinit var menuPanel: LinearLayout
    private lateinit var scrim: View
    private lateinit var quotes: Quotes
    private lateinit var chars: CharacterStore
    private lateinit var chatLog: ChatLog
    private lateinit var learner: Learner
    private lateinit var dreamer: Dreamer
    private lateinit var llm: LlmClient
    private lateinit var names: Names
    private lateinit var web: WebSearch
    private lateinit var knowledge: Knowledge
    private lateinit var style: StyleProfile
    /** 本人模式：她自己在用，她打的每句话都当语料存下来 */
    private lateinit var selfCorpus: SelfCorpus
    private var selfMode = false

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
        quotes = Quotes()
        chars = CharacterStore(this)
        quotes.apply(chars.active())
        chatLog = ChatLog(this)
        learner = Learner(this)
        learner.load()
        quotes.learner = learner

        dreamer = Dreamer(this)
        dreamer.load()
        quotes.invented.addAll(dreamer.invented)
        quotes.dreamer = dreamer

        selfCorpus = SelfCorpus(this)
        selfMode = getSharedPreferences("input", MODE_PRIVATE).getBoolean("selfMode", false)

        // 业务逻辑全部委托给 ChatBrain；这里只负责把回调反映到界面上
        brain = ChatBrain(
            llm = llm,
            web = web,
            knowledge = knowledge,
            quotes = quotes,
            chars = chars,
            style = style,
            chatLog = chatLog,
            self = selfCorpus,
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

        scene = findViewById(R.id.scene)
        scene.setOnClickListener { poke() }

        bubble = findViewById(R.id.bubble)
        input = findViewById(R.id.input)
        inputBar = findViewById(R.id.inputBar)
        noIme = getSharedPreferences("input", MODE_PRIVATE).getBoolean("noIme", false)
        titleView = findViewById(R.id.title)
        menuPanel = findViewById(R.id.menuPanel)
        scrim = findViewById(R.id.scrim)
        val menuBtn = findViewById<TextView>(R.id.menu)
        val send = findViewById<Button>(R.id.send)

        // 长按「说」= 把剪贴板里的整句中文直接发出去。
        // 模拟器上拼音候选条浮在屏幕底部、跟输入框抢地方，这条路径能绕开它。
        send.setOnLongClickListener {
            pasteAndSend()
            true
        }

        // 软键盘弹出来、收回去、或者窗口因为别的原因变高变矮，都重新算一次输入框位置
        window.decorView.viewTreeObserver.addOnGlobalLayoutListener { syncInputBarLift() }

        titleView.text = getString(R.string.app_name)
        titleView.setOnClickListener { showNameSettings(firstRun = false) }

        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        // 点进来就把软键盘带出来。键盘不弹的时候，有些输入法（雷电自带的拼音）
        // 会在屏幕最底下另开一条合成窗口显示拼音，看着就像字母跑到输入框外面去了。
        // 组词和候选留在键盘里，页面上就不会多那么一条。
        input.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                input.postDelayed({ showKeyboard() }, 120)
                // 等输入法自己决定展不展开，再决定要不要把输入框抬起来
                ui.postDelayed({ syncInputBarLift() }, 320)
            } else {
                ui.postDelayed({ syncInputBarLift() }, 80)
            }
        }
        input.setOnClickListener {
            showKeyboard()
            ui.postDelayed({ syncInputBarLift() }, 320)
        }
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
        findViewById<TextView>(R.id.item_self).setOnClickListener {
            closeMenu()
            toggleSelfMode()
        }
        findViewById<TextView>(R.id.item_character).setOnClickListener {
            closeMenu()
            showCharacters()
        }
        findViewById<TextView>(R.id.item_import).setOnClickListener {
            closeMenu()
            pickQuoteFile()
        }
        updateSelfLabel()
        updateCharacterLabel()
        findViewById<TextView>(R.id.item_name).setOnClickListener {
            closeMenu()
            showNameSettings(firstRun = false)
        }
        findViewById<TextView>(R.id.item_ai).setOnClickListener {
            closeMenu()
            showAiSettings()
        }
        findViewById<TextView>(R.id.item_about).setOnClickListener {
            closeMenu()
            showAbout()
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
            scene.wave(2600L)
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
            "neutral" -> SceneView.Mood.NEUTRAL
            "happy" -> SceneView.Mood.HAPPY
            "angry" -> SceneView.Mood.ANGRY
            "surprised" -> SceneView.Mood.SURPRISED
            "shy" -> SceneView.Mood.SHY
            "bored" -> SceneView.Mood.BORED
            else -> return
        }
        ui.postDelayed({ scene.showMood(m, 600_000L) }, 300)
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
                Toast.makeText(this, "拼音还没选成字：先在候选条里选字；急的话长按「说」粘贴发送", Toast.LENGTH_SHORT).show()
                return
            }
        }

        if (raw.isEmpty()) return
        // 本人模式：她打的这句话就是语料本身，配上一句当上下文。
        // 不弹窗、不让她答题——她正常聊天，学习在后台发生。
        if (selfMode) {
            val prev = try {
                chatLog.recent(1).lastOrNull()?.message ?: ""
            } catch (e: Exception) {
                ""
            }
            if (selfCorpus.add(prev, raw, chars.active().id)) panelText?.text = learnPanelText()
        }
        val keepTyping = input.hasFocus() && !noIme
        input.text.clear()
        chatLog.append("我", raw)
        style.observe(raw)
        style.save()
        say("……")

        // 发完别急着收键盘：收掉的话下一条又得点一下输入框、输入法还要重新绑一次。
        // 键盘会自己弹回来是因为「收键盘又没放开焦点」，现在干脆一直开着。
        if (!keepTyping) {
            input.clearFocus()
            ui.postDelayed({ hideKeyboard() }, 80)
        }

        if (llm.isConfigured()) {
            ui.postDelayed({ brain.ask(raw) }, 260)
        } else {
            // 没配大模型时也走编排层，保证「点一下就有回应」的体感不变
            ui.postDelayed({ brain.ask(raw) }, 520)
        }
    }

    /**
     * 把输入框抬起来一点。
     *
     * 雷电这类「接物理键盘的模拟器」不会把软键盘展开（高度 0），
     * 只在屏幕最底下留一条候选条；那条正好压在输入框和「说」上，
     * 所以只要输入框有焦点、而系统又没报出软键盘高度，就把整条往上挪。
     *
     * 真机上软键盘会展开，窗口本身已经被 resize 顶上去，这里就不动。
     */
    private fun syncInputBarLift() {
        val focused = input.hasFocus()
        val imePx = keyboardInsetPx()
        val shouldLift = focused && imePx < dp(80)
        val target = if (shouldLift) -dp(112).toFloat() else 0f
        if (abs(inputBar.translationY - target) > 1f) {
            inputBar.animate().translationY(target).setDuration(150).start()
        }
    }

    /** 软键盘实际占的高度；API 30 之后直接问 WindowInsets，之前只能靠可见区域推算 */
    private fun keyboardInsetPx(): Int {
        val root = window.decorView
        if (Build.VERSION.SDK_INT >= 30) {
            return root.rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        }
        val r = Rect()
        root.getWindowVisibleDisplayFrame(r)
        return (root.height - r.bottom).coerceAtLeast(0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 长按「说」：把剪贴板里的整句中文直接发出去，省得在模拟器那条候选条上抠字。
     * 电脑上把话打好复制，模拟器和主机剪贴板是通的。
     */
    private fun pasteAndSend() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        } else {
            ""
        }
        if (text.isEmpty()) {
            Toast.makeText(this, "剪贴板是空的：先在别处复制一句中文", Toast.LENGTH_SHORT).show()
            return
        }
        input.setText(text)
        input.setSelection(text.length)
        debugAsk = true          // 这是粘进来的完整句子，别被「拼音没选字」拦
        submit()
        debugAsk = false
        Toast.makeText(this, "已从剪贴板发送", Toast.LENGTH_SHORT).show()
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
        scene.showMood(mood)
        if (mood == SceneView.Mood.HAPPY) scene.wave(2000L)
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
        sb.append("\n\n── 当前角色 ──\n")
        val ch = chars.active()
        sb.append(ch.name).append("｜性格：").append(ch.tagline).append("\n")
        sb.append(ch.style.take(120))
        sb.append("\n语录 ").append(ch.lines.size).append(" 句")
        if (ch.builtin) sb.append("（内置）") else sb.append("（你拼的）")
        sb.append("\n\n── 你怎么说话（本地学的）──\n")
        sb.append(style.summary())
        sb.append("\n\n── 本人模式 ──\n")
        if (selfMode) {
            sb.append("已打开：").append(ch.name).append(" 打的每句话都会存下来（带上一句当上下文），已学 ")
                .append(selfCorpus.size(ch.id)).append(" 句。")
        } else {
            sb.append("未打开：菜单 →「本人模式」，她正常聊天就行，不用答题。")
        }
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
        sb.append("\n接口顺序：").append(llm.planSummary())
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
                "聊天记录要分清谁在说话。给自己起个名字，以后点左上角标题就能改。\n\n" +
                    "她是角色扮演，不是真人；聊天只存本机，菜单「关于」里有说明。"
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

    /**
     * 「关于」：把「她是角色不是真人」「记录只存本机」「素材从哪来」三件事说清楚。
     * 这几句看着像废话，真出事的时候它们就是边界。
     */
    private fun showAbout() {
        val msg = SpannableStringBuilder()
        msg.append("版本 2.4 · 温情陪护 Warm Companion\n\n")
            .append("· 2D 温馨小屋 + 插画女生（七个表情），10 种性格挑几个拼成一个角色。\n")
            .append("· 菜单 →「人物」新建/编辑；「导入语录」选 txt，一行一句并进当前角色。\n")
            .append("· 可以偶尔用 [呲牙] [捂脸] 这种微信/QQ 文字表情；深夜语气会自动放软。\n")
            .append("· 每个人物是角色，不是真人：台词由你选的模型实时生成，偶尔抽风很正常。\n")
            .append("· 聊天记录只存这台手机（files/chatlog.jsonl），「聊天记录」里能随时清空；")
            .append("除了发给你选的大模型接口，不会上传到别处。\n")
            .append("· 免 Key 通道是公开服务，别在里面发密码、身份证这类东西。\n")
            .append("· 素材：人物与场景是本仓库里的 SVG 手绘稿（art2d/）渲染而来，来源与许可见 NOTICE。\n")
        msg.append("· 源码与更新：")
        val linkStart = msg.length
        msg.append("github.com/unclema123/Evil-Shao")
        msg.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, REPO_URL, Toast.LENGTH_LONG).show()
                }
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.isUnderlineText = false
                ds.color = 0xFFB4655A.toInt()
            }
        }, linkStart, msg.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val tv = TextView(this).apply {
            text = msg
            textSize = 13f
            setLineSpacing(8f, 1f)
            setPadding(48, 36, 48, 20)
            movementMethod = LinkMovementMethod.getInstance()
            setLinkTextColor(0xFFB4655A.toInt())
            setTextIsSelectable(false)
        }
        AlertDialog.Builder(this)
            .setTitle("关于")
            .setView(tv)
            .setPositiveButton("知道了", null)
            .show()
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
    private fun updateSelfLabel() {
        findViewById<TextView>(R.id.item_self).text = if (selfMode) "本人模式 · 开" else "本人模式"
    }

    /**
     * 本人模式：她自己在用的时候打开。
     * 不做问卷、不弹评分——她正常聊，她打的每句话连同上一句一起存成语料，
     * 在线时挑最像的几句塞进提示词，离线时直接找她说过的那句。
     */
    private fun updateCharacterLabel() {
        findViewById<TextView>(R.id.item_character).text = "人物 · ${chars.active().name}"
    }

    /** 换角色：语料、提示词、面板立刻跟着换，并让对方先听到这个角色的第一句 */
    private fun applyCharacter(c: Character, announce: Boolean) {
        quotes.apply(c)
        updateCharacterLabel()
        panelText?.text = learnPanelText()
        if (announce && c.greeting.isNotBlank()) {
            bubble.visibility = View.GONE
            ui.postDelayed({ say(c.greeting) }, 220)
        }
    }

    private fun showCharacters() {
        val list = chars.all()
        val names = list.map {
            if (it.builtin) "${it.name} · ${it.tagline}" else "${it.name} · ${it.tagline}（我的）"
        }.toTypedArray()
        val checked = list.indexOfFirst { it.id == chars.active().id }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("人物")
            .setSingleChoiceItems(names, checked) { d, which ->
                d.dismiss()
                val c = list[which]
                if (c.id != chars.active().id) {
                    chars.select(c.id)
                    applyCharacter(c, announce = true)
                }
            }
            .setPositiveButton("新建") { _, _ -> editCharacter(null) }
            .setNeutralButton("编辑/删除") { _, _ -> pickForEditCharacter() }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun pickForEditCharacter() {
        val mine = chars.users
        if (mine.isEmpty()) {
            Toast.makeText(this, "内置人物不能改。点「新建」建一个自己的。", Toast.LENGTH_LONG).show()
            return
        }
        val names = mine.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("我的人物")
            .setItems(names) { _, i ->
                val c = mine[i]
                AlertDialog.Builder(this)
                    .setTitle(c.name)
                    .setItems(arrayOf("编辑", "删除")) { _, k ->
                        if (k == 0) editCharacter(c) else confirmDeleteCharacter(c)
                    }
                    .show()
            }
            .show()
    }

    private fun confirmDeleteCharacter(c: Character) {
        AlertDialog.Builder(this)
            .setTitle("删除「${c.name}」？")
            .setMessage("人设和口头禅会一起删掉；本人模式学到的句子还留着。")
            .setPositiveButton("删除") { _, _ ->
                chars.deleteUser(c.id)
                applyCharacter(chars.active(), announce = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 新建 / 编辑角色：不写身份，只挑性格。
     * 选中的性格说明会拼成提示词，语录库取它们的并集；用户还能补几句自己的。
     */
    private fun editCharacter(ch: Character?) {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        fun addField(hint: String, value: String, lines: Int): EditText {
            val e = EditText(this)
            e.hint = hint
            e.setText(value)
            e.textSize = 14f
            e.setSingleLine(lines == 1)
            if (lines > 1) {
                e.minLines = lines
                e.maxLines = lines + 4
                e.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            box.addView(e)
            return e
        }
        val name = addField("名字（只用来显示，可空）", ch?.name ?: "", 1)

        val title = TextView(this).apply {
            text = "性格（可多选，至少一个）："
            textSize = 13f
            setPadding(0, pad / 2, 0, 4)
        }
        box.addView(title)
        val boxes = ArrayList<Pair<CheckBox, Trait>>()
        val chosen = ch?.traitIds ?: emptyList()
        chars.traits().forEach { t ->
            val cb = CheckBox(this).apply {
                text = "${t.name} — ${t.style}"
                textSize = 13f
                isChecked = chosen.contains(t.id)
            }
            box.addView(cb)
            boxes.add(cb to t)
        }

        val extraLines = addField("额外语录，一行一句（可空；会和性格自带的语录合在一起）", ch?.extraLines?.joinToString("\n") ?: "", 3)
        val extraStyle = addField("补充说明（可空，原样进提示词）", ch?.extraStyle ?: "", 2)
        val greet = addField("见面第一句（可空）", ch?.greeting ?: "", 1)

        AlertDialog.Builder(this)
            .setTitle(if (ch == null) "拼一个角色" else "编辑角色")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("保存") { _, _ ->
                val ids = boxes.filter { it.first.isChecked }.map { it.second.id }
                val extra = extraLines.text.toString().split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                if (ids.isEmpty() && extra.isEmpty()) {
                    Toast.makeText(this, "至少选一个性格，或者自己写几句语录", Toast.LENGTH_LONG).show()
                } else {
                    val c = chars.compose(
                        id = ch?.id ?: ("u" + System.currentTimeMillis()),
                        name = name.text.toString(),
                        traitIds = ids,
                        extraLines = extra,
                        extraStyle = extraStyle.text.toString(),
                        greeting = greet.text.toString(),
                        builtin = false
                    )
                    chars.saveUser(c)
                    chars.select(c.id)
                    applyCharacter(c, announce = true)
                    Toast.makeText(this, "已保存：${c.name}（${c.tagline}）", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_QUOTES || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val raw = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            importLines(raw)
        } catch (e: Exception) {
            Toast.makeText(this, "读不了这个文件：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** 选一个 txt：一行一句，并进当前角色的语录库 */
    private fun pickQuoteFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
        }
        try {
            startActivityForResult(intent, REQ_QUOTES)
        } catch (e: Exception) {
            Toast.makeText(this, "打不开文件选择器：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun importLines(raw: String) {
        val lines = raw.split('\n', '\r')
            .map { it.trim().trimStart('\uFEFF') }
            .filter { it.isNotEmpty() && it.length <= 60 }
            .distinct()
        if (lines.isEmpty()) {
            Toast.makeText(this, "没读到句子（一行一句的 txt 才行）", Toast.LENGTH_LONG).show()
            return
        }
        val ch = chars.active()
        val extra = (ch.extraLines + lines).distinct().take(500)
        // 内置角色不能就地改：导入时自动复制一份成「我的」
        val c = chars.compose(
            id = if (ch.builtin) "u" + System.currentTimeMillis() else ch.id,
            name = ch.name,
            traitIds = ch.traitIds,
            extraLines = extra,
            extraStyle = ch.extraStyle,
            greeting = ch.greeting,
            builtin = false
        )
        chars.saveUser(c)
        chars.select(c.id)
        applyCharacter(c, announce = false)
        Toast.makeText(
            this,
            "导入 ${lines.size} 句，${c.name} 现在共 ${c.lines.size} 句语录",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toggleSelfMode() {
        if (selfMode) {
            selfMode = false
            getSharedPreferences("input", MODE_PRIVATE).edit()
                .putBoolean("selfMode", false).apply()
            updateSelfLabel()
            panelText?.text = learnPanelText()
            Toast.makeText(this, "本人模式：关（已学的 ${selfCorpus.size(chars.active().id)} 句还在）", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("本人模式")
            .setMessage(
                "打开后，她打的每句话都会在这台手机上存成语料（连同上一句当上下文），" +
                    "在线回复时会挑最像的几句作为例子发给接口。\n\n" +
                    "不用答题、不用打分，正常聊就行。\n\n" +
                    "前提：她本人知道这个软件在模仿她，并且同意。"
            )
            .setPositiveButton("打开") { _, _ ->
                selfMode = true
                getSharedPreferences("input", MODE_PRIVATE).edit()
                    .putBoolean("selfMode", true).apply()
                updateSelfLabel()
                panelText?.text = learnPanelText()
                Toast.makeText(this, "本人模式：开。她正常聊就行，不用答题。", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

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
        val cbFallback = CheckBox(this).apply {
            text = "主接口挂了自动换免 Key 接口"
            isChecked = llm.autoFallback
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
                "也支持硅基流动、DeepSeek、百炼（阿里云）等 OpenAI 兼容接口；点「用百炼」会自动填好地址和模型。\n" +
                "主接口挂了会自动换免 Key 接口（内容会发给那个公开服务，介意就关掉上面那项）。\n" +
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
        val baiLianBtn = Button(this).apply {
            text = "用百炼（DeepSeek）"
            setOnClickListener {
                etBase.setText(LlmProviders.ALIYUN_BASE)
                etModel.setText(LlmProviders.ALIYUN_MODEL)
                cbKeyless.isChecked = false
                cb.isChecked = true
                result.text = "已填好百炼的地址和模型，把百炼的 Key 粘到下面，再点「保存」"
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
                    probe.autoFallback = false   // 测试就测你填的这条，别拿备用接口的成绩糊弄你
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
            addView(cbFallback)
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
            addView(baiLianBtn)
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
                llm.autoFallback = cbFallback.isChecked
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

    /** 点一下画面：她说一句自己的话。2D 版不用拖动旋转了，点一下就够 */
    private fun poke() {
        val line = style.adapt(quotes.randomSignature())
        chatLog.append("她", line)
        say(line)
        scene.showMood(MoodClassifier.of(line))
        scene.wave(1200L)
        // 记一笔，免得接下来的模型回复跟她刚说的撞车
        brain.noteSaid(line)
        updateBadges()
    }

    companion object {
        private const val REQ_QUOTES = 1001
        const val REPO_URL = "https://github.com/unclema123/Evil-Shao"
    }
}
