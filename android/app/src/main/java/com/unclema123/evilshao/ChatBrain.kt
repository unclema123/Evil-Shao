package com.unclema123.evilshao

import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * 对话编排：一次提问只走一条路，最多打**一次**模型请求。
 *
 * 上一版为了「答得更全」串了四条路（记忆 → 查资料 → 直答 → 复读重试），
 * 结果是：免费额度 45 秒 1 条，一个问题打 4 次请求必然 429，
 * 于是绝大多数消息都掉进「随机语录」兜底——看起来就是答非所问、没有条理。
 *
 * 这一版的原则：
 *  1. **一次请求**：查资料成功就结束；失败且不是限流，才允许用一次直答补救。
 *  2. **宁可少说也不乱说**：失败不再甩随机语录，而是给一句「网不行，等会儿」；
 *     随机语录只在用户自己没配大模型（纯离线玩具模式）时用。
 *  3. **复读不再重打一次**：提示词里点出最近 3 句就够，撞车也照发——
 *     重复一句切题的，好过换一句不着边际的。
 */
class ChatBrain(
    private val llm: LlmClient,
    private val web: WebSearch,
    private val knowledge: Knowledge,
    private val quotes: Quotes,
    /** 当前角色和角色仓库：换人之后下一句立刻用新角色 */
    private val chars: CharacterStore,
    private val style: StyleProfile,
    private val chatLog: ChatLog,
    /** 本人模式攒的问答对；没开就是空壳 */
    private val self: SelfCorpus? = null,
    private val listener: Listener,
    /** 把回调切到主线程，一般是 Handler(Looper.getMainLooper())::post */
    private val postToUi: (Runnable) -> Unit
) {

    interface Listener {
        /** 临时气泡，比如「……我查一下」 */
        fun onStatus(text: String)

        /** 流式增量：partial 是累积到目前的完整文本 */
        fun onStream(partial: String)

        /** 最终一句。model=true 表示来自大模型，false 表示本地语料 */
        fun onReply(reply: String, model: Boolean)

        /** 知识库写入了新条目，面板可以刷新 */
        fun onMemorySaved()
    }

    enum class Source { LOCAL, MEMORY, SEARCH, MODEL }

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-brain").apply { isDaemon = true }
    }

    /** 每次提问自增；回调时对不上就说明用户已经问了新的，旧结果直接丢 */
    @Volatile
    private var generation = 0

    /** 最近说过的几句（进程内），用于复读检测 */
    private val recentReplies = ArrayDeque<String>()

    /** 本轮请求开始时间，只用来打日志（单线程队列，不会被并发覆盖） */
    private var startedAt = 0L

    init {
        // 进程重启后 recentReplies 是空的，会把她上次刚说过的话再说一遍。
        // 用聊天记录预热，复读检测才跨进程有效。
        try {
            chatLog.recent(16)
                .filter { it.role != "我" }
                .forEach { noteSaid(it.message) }
        } catch (_: Exception) {
        }
    }

    fun noteSaid(text: String) {
        synchronized(recentReplies) {
            recentReplies.addLast(text)
            while (recentReplies.size > 8) recentReplies.removeFirst()
        }
    }

    fun ask(userText: String) {
        val text = userText.trim()
        if (text.isEmpty()) return
        val myGen = ++generation
        worker.execute {
            if (stale(myGen)) return@execute
            startedAt = System.currentTimeMillis()
            try {
                run(myGen, text)
            } catch (e: Exception) {
                android.util.Log.e("EvilShao", "对话流程异常", e)
                if (!stale(myGen)) honest(myGen, text)
            }
        }
    }

    /** Activity 退出时调用：作废所有在途请求 */
    fun shutdown() {
        generation++
        worker.shutdownNow()
    }

    // ────────────────────────────── 主流程 ──────────────────────────────

    private fun run(gen: Int, text: String) {
        // 用户根本没配大模型：这就是个纯语录玩具，保持原来的玩法
        if (!llm.isConfigured()) {
            quip(gen, text)
            return
        }

        val fact = Query.worthSearching(text)      // 事实类问题：该查、该吃缓存
        val question = Query.looksLikeQuestion(text)

        // ① 知识库：只有事实类问题才吃缓存，避免拿旧答案糊弄「你是谁」这类话
        if (fact) {
            val hit = try {
                knowledge.find(text)
            } catch (e: Exception) {
                null
            }
            if (hit != null) {
                log("记忆命中 score=${String.format("%.2f", hit.score)} 答案=${hit.answer}")
                if (deliver(gen, hit.answer, Source.MEMORY)) return
                log("这条刚说过，改走模型")
            }
        }

        val styleHint = style.hint()

        // ② 事实类且开了联网：查资料（一次模型请求）
        if (fact && llm.searchEnabled && !stale(gen)) {
            status(gen, "……我查一下")
            val usable = fetchFor(gen, text)
            if (usable.isNotEmpty() && !stale(gen)) {
                if (research(gen, text, usable)) return
                // 限流/欠费时再打一次必然还失败，直接说人话
                if (rateLimited()) {
                    log("限流，不再纠正答")
                    honest(gen, text)
                    return
                }
            } else if (!stale(gen)) {
                status(gen, "……没搜到，我凭印象说")
            }
        }

        // ③ 人设直答（最后一次模型请求）
        if (!stale(gen) && persona(gen, text, styleHint, question)) return

        // ④ 兜底：给一句有关系的话，而不是随机语录
        honest(gen, text)
    }

    /** 查资料作答 */
    private fun research(gen: Int, text: String, usable: List<WebSearch.Hit>): Boolean {
        val thin = llm.keyless
        val context = try {
            chatLog.lastExchange()
        } catch (e: Exception) {
            ""
        }
        val raw = llm.chatStream(
            Persona.researchPrompt(),
            Persona.materialPrompt(text, if (thin) usable.take(2) else usable, context),
            history = HistoryBudget.build(safeEntries(), budget = 900),
            temperature = 0.25,
            maxTokens = if (thin) 120 else 220,
            readTimeoutMs = if (thin) 10000 else 30000,
            retries = 0,
            onDelta = { partial -> stream(gen, partial) }
        )
        if (raw == null) {
            log("查资料失败：${llm.lastError}")
            return false
        }
        val clean = ReplyText.sanitize(raw, 60) ?: run {
            log("查资料输出清洗后为空：$raw")
            return false
        }
        if (knowledge.isNonAnswer(clean)) {
            log("资料没答上来：$clean")
            return false
        }
        try {
            knowledge.save(text, clean, usable.map { it.url })
            postToUi { listener.onMemorySaved() }
        } catch (_: Exception) {
        }
        return deliver(gen, clean, Source.SEARCH, ignoreEcho = true)
    }

    /** 人设直答 */
    private fun persona(gen: Int, text: String, styleHint: String, question: Boolean): Boolean {
        val thin = llm.keyless
        // 上下文信号：只有「毒舌」角色才在夸别人时阴阳，别的角色提示词里不启用
        val praised = PersonaSignals.praiseOfOtherWoman(text)
        if (praised != null) log("情境：夸别人=[$praised]")
        val ch = chars.active()
        val selfExamples = try {
            self?.examples(text, 4, ch.id)?.map { p ->
                // 带上下文的例子才有用：只说「她会这么说」，模型不知道在接什么话
                if (p.q.isBlank()) "对方说过「${p.a}」" else "对方说「${p.q}」，角色回「${p.a}」"
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        if (selfExamples.isNotEmpty()) log("本人例子 ${selfExamples.size} 句：${selfExamples.first()}")
        val sys = Persona.systemPrompt(
            ch,
            quotes,
            styleHint,
            // 只点最近 3 句。列 6 句以上，模型为了「不重复」会开始胡说八道
            ReplyGuard.bannedBlock(recentList(), 3),
            praisedWoman = praised,
            // 本人模式：对方真在类似情境下说过的话，比任何规则都准
            selfExamples = selfExamples
        )
        val raw = llm.chatStream(
            sys, text, history(),
            // 问事实时压低温度要准确，斗嘴时才放开
            temperature = if (question) 0.65 else 0.95,
            maxTokens = 96,
            readTimeoutMs = if (thin) 12000 else 20000,
            frequencyPenalty = 0.6,
            presencePenalty = 0.3,
            retries = if (thin) 0 else 1,
            onDelta = { partial -> stream(gen, partial) }
        )
        if (raw == null) {
            log("直答失败：${llm.lastError}")
            return false
        }
        val clean = ReplyText.sanitize(raw, 40) ?: run {
            log("直答输出清洗后为空：$raw")
            return false
        }
        return deliver(gen, clean, Source.MODEL, ignoreEcho = true)
    }

    /**
     * 纯离线模式（用户关掉了大模型）。
     *
     * 原来的玩法是「随机抽一句语录」，问题在于语录跟用户说的话毫无关系：
     * 「你好」→「瞎说」、「你在说啥」→「谁跟你一样」，连说三句就是精神分裂。
     * 现在改成：关键词规则真的对得上就用规则回复，否则给一句**接得住这句话**的短应答。
     * 想听她随口吐槽，点她本人就行（那条路仍然是随机签名）。
     */
    private fun quip(gen: Int, text: String) {
        if (stale(gen)) return
        val ch = chars.active()
        // 纯离线也要认得「夸别的女生」：但只有毒舌角色才阴阳
        val praised = PersonaSignals.praiseOfOtherWoman(text)
        if (praised != null && ch.tagline.contains("毒舌")) {
            log("离线阴阳：$praised")
            deliver(gen, style.adapt(pickSarcastic()), Source.LOCAL, ignoreEcho = true)
            return
        }
        val mine = try {
            self?.best(text, ch.id)
        } catch (e: Exception) {
            null
        }
        if (mine != null) {
            log("本人语料命中：$mine")
            deliver(gen, style.adapt(mine), Source.LOCAL, ignoreEcho = true)
            return
        }
        val matched = try {
            quotes.matchedReply(text)
        } catch (e: Exception) {
            null
        }
        if (matched != null) {
            deliver(gen, style.adapt(matched), Source.LOCAL, ignoreEcho = true)
            return
        }
        val pool = if (Query.looksLikeQuestion(text)) OFFLINE_QUESTION else OFFLINE_CHAT
        deliver(gen, pool[Random.nextInt(pool.size)], Source.LOCAL, ignoreEcho = true)
    }

    /** 离线阴阳话：短、酸，但还接得住「他在夸别人」这件事 */
    private fun pickSarcastic(): String = SARCASTIC[Random.nextInt(SARCASTIC.size)]

    /**
     * 模型不可用时的兜底。
     *
     * 关键：**不能说随机语录**。语录跟用户这句话没关系，
     * 连说三句就是「答非所问」，比沉默更让人烦躁。
     * 这里给一句跟「没连上」有关的话，至少逻辑自洽。
     */
    private fun honest(gen: Int, text: String) {
        if (stale(gen)) return
        // 语录里如果有能对上的关键词规则，就用它（那是有关系的）
        val matched = try {
            quotes.matchedReply(text)
        } catch (e: Exception) {
            null
        }
        val line = matched?.let { style.adapt(it) }
            ?: OFFLINE_LINES[Random.nextInt(OFFLINE_LINES.size)]
        deliver(gen, line, Source.LOCAL, ignoreEcho = true)
    }

    // ────────────────────────────── 工具 ──────────────────────────────

    private fun fetchFor(gen: Int, text: String): List<WebSearch.Hit> {
        val searchWord = Query.withContext(
            text,
            Query.toSearchQuery(text),
            try {
                chatLog.recentMine(4)
            } catch (e: Exception) {
                emptyList<String>()
            }
        )
        val hits = try {
            val raw = web.search(searchWord, 6)
            // 并行抓正文：串行抓 3 篇最坏 3×13 秒，等不起
            if (raw.isEmpty()) raw else web.enrichParallel(raw, 3, 900, 12000)
        } catch (e: Exception) {
            emptyList<WebSearch.Hit>()
        }
        // 用问题里的实词筛一遍：问济南天气，就别把「南方阴雨」的稿子喂给模型
        val anchors = Query.anchors(searchWord)
        val scored = hits.map { h ->
            val hay = h.title + " " + h.snippet + " " + h.body
            h to anchors.count { hay.contains(it) }
        }
        val onTopic = if (anchors.isEmpty()) scored else scored.filter { it.second > 0 }
        log(
            "搜索词=[$searchWord] 实词=$anchors 命中=${hits.size} " +
                "切题=${onTopic.size} 有正文=${hits.count { it.body.isNotEmpty() }} 错误=${web.lastError}"
        )
        // 全是别处的事（问济南、资料写南方）：宁可不用，也别拿它冒充答案。
        // 直接返回空，让上层退成「凭印象直答」，比喂错材料又快又不丢人。
        if (anchors.isNotEmpty() && onTopic.isEmpty()) {
            log("资料都不切题，跳过查资料")
            return emptyList()
        }
        val pool = if (onTopic.isEmpty()) scored else onTopic
        return pool
            .sortedWith(
                compareByDescending<Pair<WebSearch.Hit, Int>> { it.second }
                    .thenByDescending { it.first.body.length }
            )
            .map { it.first }
            .filter { it.body.isNotEmpty() || it.snippet.length >= 20 }
    }

    private fun history(): List<Pair<String, String>> =
        HistoryBudget.build(safeEntries(), budget = HistoryBudget.DEFAULT_BUDGET)

    private fun safeEntries(): List<ChatLog.Entry> = try {
        chatLog.entries()
    } catch (e: Exception) {
        emptyList()
    }

    private fun recentList(): List<String> = synchronized(recentReplies) { recentReplies.toList() }

    private fun stale(gen: Int) = gen != generation

    /** 上次失败是因为限流/额度/鉴权，重试和换路径都没意义 */
    private fun rateLimited(): Boolean {
        val e = llm.lastError ?: return false
        return e.contains("限流") || e.contains("429") || e.contains("402") ||
            e.contains("额度") || e.contains("401") || e.contains("Key 无效")
    }

    /**
     * 交付一句给界面。
     *
     * @param ignoreEcho 模型说的话跟最近某句很像时是否照发。
     *        模型这条一律 true：重复一句切题的，好过换一句不着边际的；
     *        知识库那条 false：缓存答句一模一样时换条路，否则每次问同一句都是复读。
     */
    private fun deliver(gen: Int, reply: String, source: Source, ignoreEcho: Boolean = false): Boolean {
        if (stale(gen)) return true
        val text = reply.trim()
        if (text.isEmpty()) return false
        if (ReplyGuard.isEchoOf(text, recentList())) {
            if (!ignoreEcho) {
                log("复读被拦：$text")
                return false
            }
            log("与最近某句相似，仍照发：$text")
        }
        noteSaid(text)
        log("回复[$source] ${System.currentTimeMillis() - startedAt}ms 尝试=${llm.lastAttempts} 文本=$text")
        postToUi { listener.onReply(text, source != Source.LOCAL) }
        return true
    }

    private fun stream(gen: Int, partial: String) {
        if (stale(gen)) return
        postToUi { listener.onStream(partial) }
    }

    private fun status(gen: Int, text: String) {
        if (stale(gen)) return
        postToUi { listener.onStatus(text) }
    }

    /** 统一前缀，方便 adb logcat -s EvilShao 过滤 */
    private fun log(msg: String) {
        android.util.Log.d("EvilShao", msg)
    }

    companion object {
        /**
         * 连不上模型时的回答。
         *
         * 挑的都是「接得住对方那句」的话——至少不会出现
         * 「你在说啥」→「谁跟你一样」这种驴唇不对马嘴的对话。
         */
        private val OFFLINE_LINES = listOf(
            "……网不行，等会儿再说",
            "……先等等，我这边没连上",
            "……等会儿，卡住了",
            "……我缓缓，网太烂"
        )

        /** 离线时被问到问题：承认答不了，但接住这句话 */
        private val OFFLINE_QUESTION = listOf(
            "……我这会儿查不了",
            "……等我连上网再说",
            "……你问这么难，我哪知道",
            "……先别问，没联网"
        )

        /** 离线时的闲聊应答：短，但至少是在跟你说话 */
        private val OFFLINE_CHAT = listOf(
            "嗯", "……哦", "行吧", "你说啥"
        )

        /**
         * 离线时听说他在夸别的女生。
         * 现成的阴阳话，比随机语录切题；走大模型时按提示词自由发挥。
         */
        private val SARCASTIC = listOf(
            "是是是，她最好了",
            "好看，滤镜开的吧",
            "那你找她聊啊",
            "呵呵，真会夸",
            "她那么好，你去找她"
        )
    }
}