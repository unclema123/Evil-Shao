package com.unclema123.evilshao

/**
 * 按「字符预算」裁上下文，而不是按固定条数。
 *
 * 原来的做法是固定取最近 100 条。问题在于：
 *  - 100 条约 1500 字，glm-4-flash（128k）放得下，但 Qwen2.5-7B（32k）、
 *    Pollinations 这类小接口不一定放得下，超了会被服务端截断或者直接 400；
 *  - 用户偶尔发长段（复制的公众号文章），10 条就爆了；
 *  - 反过来，短对话时又不该塞 100 条让模型分心。
 *
 * 所以改成：从最新往回累加，总字数超预算就停，至少保留最近两轮完整。
 * 思路同 langchain4j 的 TokenWindowChatMemory，只是用字符数近似 token
 * （中文 1 字≈1 token，够用了，且不用引依赖）。
 */
object HistoryBudget {

    /** 默认预算：约 1200 字，留足空间给 system prompt 和回复 */
    const val DEFAULT_BUDGET = 1200

    /** 最多带多少条消息（防止「嗯」「哦」这种超短对话堆太多条） */
    const val MAX_MESSAGES = 40

    /** 无论预算多紧，最近两轮（4 条）必须保留，否则指代接不上 */
    const val MIN_MESSAGES = 4

    /**
     * @param entries 聊天记录，按时间正序
     * @param dropCurrent 是否丢掉最后一条。submit() 是先 append「我」这句再问的，
     *                    当前这句会单独作为 user 传进去，不丢会重复。
     * @return role 只能是 user / assistant
     */
    fun build(
        entries: List<ChatLog.Entry>,
        budget: Int = DEFAULT_BUDGET,
        maxMessages: Int = MAX_MESSAGES,
        dropCurrent: Boolean = true
    ): List<Pair<String, String>> {
        if (entries.isEmpty()) return emptyList()
        val source = if (dropCurrent) entries.dropLast(1) else entries
        if (source.isEmpty()) return emptyList()

        val picked = ArrayList<Pair<String, String>>()
        var used = 0
        for (e in source.asReversed()) {
            val text = e.message.trim()
            if (text.isEmpty()) continue
            val cost = text.length + 8          // 角色名和 JSON 结构的近似开销
            val overBudget = used + cost > budget
            val overCount = picked.size >= maxMessages
            if ((overBudget || overCount) && picked.size >= MIN_MESSAGES) break
            val role = if (e.role == "我") "user" else "assistant"
            // 开头是 assistant 的话，模型容易接着「她」的话往下说，补一句上文更稳
            picked.add(role to text)
            used += cost
        }

        // 倒序取出来的，翻回正序
        picked.reverse()
        // 第一条如果是 assistant，直接去掉，让对话从 user 开始
        while (picked.isNotEmpty() && picked.first().first == "assistant") {
            picked.removeAt(0)
        }
        return picked
    }
}