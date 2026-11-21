package com.unclema123.evilshao

import java.util.concurrent.Executors

/**
 * 对话编排：一次提问要经过「记忆 → 联网 → 模型 → 本地兜底」四道关，
 * 这四步原来全塞在 MainActivity 里（还是 1000 行 Activity 的一部分）。
 *
 * 抽出来以后有三点好处：
 *
 * 1. **请求代际（generation）**：用户连发两句时，旧请求的结果会作废。
 *    以前两个 Thread 并发跑，谁先返回谁先说 —— 先问的「济南天气」慢，
 *    后问的「你是谁」快，回复就颠倒了。
 *
 * 2. **单线程串行**：所有请求排一条队列，聊天记录 append 的顺序不会乱。
 *
 * 3. **可测试**：这里不碰任何 View，只通过 [Listener] 回调，
 *    逻辑错了能在 JVM 上直接跑，不用开模拟器。
 */
class ChatBrain(
    private val llm: LlmClient,
    private val web: WebSearch,
    private val knowledge: Knowledge,
    private val quotes: Quotes,
    private val style: StyleProfile,
    private val chatLog: ChatLog,
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

    /** 由外部（点小猪随机说话）写入，让复读检测也知道 */
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
                if (!stale(myGen)) local(myGen, text)
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
        // 没配大模型：纯本地语料，不发请求
        if (!llm.isConfigured()) {
            local(gen, text)
            return
        }

        val styleHint = style.hint()
        val banned = ReplyGuard.bannedBlock(recentList())

        // ① 记忆命中：不直接念旧答案，而是让模型用她的口吻重说一遍。
        //    以前命中就原样返回，问法一变（但 bigram 撞上）就会答非所问。
        val hit = try {
            knowledge.find(text)
        } catch (e: Exception) {
            null
        }
        log("提问：$text 记忆=${hit != null} 联网=${llm.searchEnabled && Query.worthSearching(text)}")
        // 只有「事实类问题」才吃缓存。像「你是谁」这种问她自己/寒暄的话，
        // 命中旧答案反而答得驴唇不对马嘴（曾经答成韩剧），一律走人设。
        if (hit != null && Query.worthSearching(text) && !stale(gen)) {
            val ok = memoryPath(gen, hit.answer)
            if (ok) return
        }

        // ② 联网：搜不到就退回直答，不硬等
        if (llm.searchEnabled && Query.worthSearching(text) && !stale(gen)) {
            status(gen, "……我查一下")
            val ok = searchPath(gen, text, styleHint, banned)
            if (ok) return
            // 被限流/欠费时再走直答只会再失败一次，白等两个读超时
            if (rateLimited()) {
                log("限流，跳过直答直接回本地")
                local(gen, text)
                return
            }
        }

        // ③ 直接问模型
        if (!stale(gen)) {
            val ok = plainPath(gen, text, styleHint, banned)
            if (ok) return
        }

        // ④ 全挂了，回本地，保证有回应
        if (!stale(gen)) local(gen, text)
    }

    /**
     * 用记忆里的答案作答。
     *
     * 这里**不**再跑一趟模型。缓存的意义就是快——实测让模型「用她的口吻重说一遍」
     * 要 3.5 秒，赶上免费接口抽风能拖到读超时 20 秒，用户只会觉得她卡住了。
     * 口吻的修正交给本地 style.adapt（几十微秒，还能离线用）。
     */
    private fun memoryPath(gen: Int, memory: String): Boolean {
        // 不做 style.adapt：它会按「你说话短」把长句砍成一截，
        // 实测把「多云转晴，八到十五度。」砍成了「多云转晴」——事实丢了。
        return accept(gen, memory, Source.MEMORY)
    }

    /** 联网查资料 */
    private fun searchPath(gen: Int, text: String, styleHint: String, banned: String): Boolean {
        val searchWord = Query.withContext(
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
        if (stale(gen)) return true

        log("搜索词=[$searchWord] 命中=${hits.size} 有正文=${hits.count { it.body.isNotEmpty() }} 错误=${web.lastError}")
        val usable = hits.sortedByDescending { it.body.length }
            .filter { it.body.isNotEmpty() || it.snippet.length >= 20 }
        if (usable.isEmpty()) {
            // 没搜到就别装作查过
            status(gen, "……没连上，我先凭印象")
            return false
        }

        val context = try {
            chatLog.lastExchange()
        } catch (e: Exception) {
            ""
        }
        // 免 Key 的公共接口又慢又爱 429：材料少给、超时收紧。
        // 宁可退成「直答一句」，也别让她在那儿转 40 秒。
        val thin = llm.keyless
        val raw = llm.chatStream(
            Persona.researchPrompt(),
            Persona.materialPrompt(text, if (thin) usable.take(2) else usable, context),
            // 查资料时上下文短一点，把预算留给材料
            history = HistoryBudget.build(safeEntries(), budget = 400),
            temperature = 0.25,
            maxTokens = if (thin) 120 else 220,
            readTimeoutMs = if (thin) 10000 else 30000,
            retries = 0,
            onDelta = { partial -> stream(gen, partial) }
        )
        if (raw == null) log("查资料模型失败：${llm.lastError}")
        val clean = ReplyText.sanitize(raw, 60)
        if (clean == null) return false
        if (knowledge.isNonAnswer(clean)) {
            // 「不知道」不记账，也不当最终答案回给她
            return false
        }
        if (!stale(gen)) {
            try {
                knowledge.save(text, clean, usable.map { it.url })
                postToUi { listener.onMemorySaved() }
            } catch (_: Exception) {
            }
        }
        return accept(gen, clean, Source.SEARCH)
    }

    /** 不带资料，纯人设对话 */
    private fun plainPath(gen: Int, text: String, styleHint: String, banned: String): Boolean {
        val sys = Persona.systemPrompt(quotes, styleHint, banned)
        val raw = llm.chatStream(
            sys, text, history(),
            temperature = 0.95,
            maxTokens = 96,
            // 复读抑制：小模型连着说同一句时，这两个参数比提示词管用
            frequencyPenalty = 0.6,
            presencePenalty = 0.3,
            onDelta = { partial -> stream(gen, partial) }
        )
        if (raw == null) log("直答模型失败：${llm.lastError}")
        val clean = ReplyText.sanitize(raw, 40)
        if (clean == null) return false
        if (accept(gen, clean, Source.MODEL)) return true

        // 撞上复读了：再要一次，并且明确点名刚说过哪句
        val retrySys = sys + "\n\n注意：你刚才又想重复「$clean」，这次必须换一句完全不同的话。"
        val second = llm.chat(retrySys, text, history(), temperature = 1.05, maxTokens = 96, retries = 0)
        val clean2 = ReplyText.sanitize(second, 40)
        return clean2 != null && accept(gen, clean2, Source.MODEL)
    }

    /** 本地语料兜底 */
    private fun local(gen: Int, text: String) {
        if (stale(gen)) return
        val raw = try {
            quotes.reply(text)
        } catch (e: Exception) {
            "……"
        }
        accept(gen, style.adapt(raw), Source.LOCAL)
    }

    // ────────────────────────────── 工具 ──────────────────────────────

    private fun history(): List<Pair<String, String>> =
        HistoryBudget.build(safeEntries(), budget = HistoryBudget.DEFAULT_BUDGET)

    private fun safeEntries(): List<ChatLog.Entry> = try {
        chatLog.entries()
    } catch (e: Exception) {
        emptyList()
    }

    private fun recentList(): List<String> = synchronized(recentReplies) { recentReplies.toList() }

    private fun stale(gen: Int) = gen != generation

    /** 复读校验 + 记录 + 回调。返回 false 表示这句被拒了，调用方可以换一条 */
    private fun accept(gen: Int, reply: String, source: Source): Boolean {
        if (stale(gen)) return true
        val text = reply.trim()
        if (text.isEmpty()) return false
        if (ReplyGuard.isEchoOf(text, recentList())) {
            log("复读被拦：$text")
            return false
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

    /** 上次失败是因为限流/额度/鉴权，重试和换路径都没意义 */
    private fun rateLimited(): Boolean {
        val e = llm.lastError ?: return false
        return e.contains("限流") || e.contains("429") || e.contains("402") ||
            e.contains("额度") || e.contains("401") || e.contains("Key 无效")
    }

    /** 统一前缀，方便 adb logcat -s EvilShao 过滤 */
    private fun log(msg: String) {
        android.util.Log.d("EvilShao", msg)
    }

    private fun status(gen: Int, text: String) {
        if (stale(gen)) return
        postToUi { listener.onStatus(text) }
    }
}