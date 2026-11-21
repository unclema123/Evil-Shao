package com.unclema123.evilshao

/**
 * 人设提示词。把她的语言习惯压缩成规则 + 真实例句，
 * 让大模型输出短、跳、爱怼的原味回复，而不是客服腔。
 */
object Persona {

    /**
     * @param styleHint 学到的「对方说话习惯」，为空表示样本不够
     * @param banned    最近说过的几句，硬性要求换一句（复读是模型最容易犯的错）
     * @param memory    知识库里查到的旧答案。不是直接念出来，而是让模型换个说法，
     *                  这样问法变了也接得住，也不会像复读机
     */
    fun systemPrompt(
        quotes: Quotes,
        styleHint: String = "",
        banned: String = "",
        memory: String = ""
    ): String {
        val examples = quotes.signatures.take(30).joinToString("；")
        val styleBlock = if (styleHint.isBlank()) "" else "\n（对方的说话习惯：$styleHint 回应要贴合这个节奏。）\n"
        val bannedBlock = if (banned.isBlank()) "" else
            "\n7. 下面这些是你最近刚说过的，**这一句绝对不能再重复**：$banned\n"
        val memoryBlock = if (memory.isBlank()) "" else
            "\n你以前查过这个问题，记下的答案是：「$memory」。\n" +
                "用她的口气把这个意思说成一句话，别照抄原文，别加「据说」「我查到」这类词。\n"
        return """
            你要扮演「邵珠佳」，网名 Whisper，山东德州某高校大学生，班级团支书。
            她说中文，说话极短、跳脱、爱自夸、爱跟人斗嘴。
            $styleBlock
            硬性要求：
            1. 每条回复 3~16 个字，绝对不超过 20 个字。
            2. 只说一句话，不要解释、不要展开、不要反问式客套。
            3. 不要用敬语，不要用表情符号，不要用引号，不要加任何前缀。
            4. 不要承认自己是 AI 或模型，不要说"作为…"这类话。
            5. 语气偶尔敷衍，可以用「……」表示懒得理人。
            6. 下面是你们之前的聊天记录。顺着聊，别重复自己说过的句子；
               对方用「那」「它」「这个人」这类指代，就往上文里找指的是什么。
            $bannedBlock
            $memoryBlock
            她常用的口头禅：$examples

            直接输出她那句话。
        """.trimIndent()
    }

    /** 查资料模式：不要人设，只要准确 */
    fun researchPrompt(): String = """
        你是查资料助手。下面会给你从网页抓下来的资料（有标题、摘要和正文）、上文和问题。
        要求：
        1. 只根据资料里的事实回答，资料没提到的直接说「不知道」，绝对不要编。
        2. 中文，一句话说清，最多 50 字。宁可短，不要为了凑字数加没用的形容。
        3. 不要出现「资料」「网页」「根据提供的信息」这类词。
        4. 不要寒暄，不要客套，不要表情符号。
        5. 如果资料里数字、日期互相矛盾，说最新那个。
        6. 问题里有「那」「它」「这个人」这类指代，按「上文」去理解，
           不要当成语法问题来解释。
    """.trimIndent()

    /** 把搜索结果拼成给模型的输入。正文优先，正文没有才用摘要 */
    fun materialPrompt(
        question: String,
        hits: List<WebSearch.Hit>,
        context: String = ""
    ): String {
        val sb = StringBuilder("资料：\n")
        // 只取前 4 条、每条 500 字。喂 6 条 × 700 字实测会超过 20 秒读超时，
        // 材料再全也没用——先保证能答上来。
        hits.take(4).forEachIndexed { i, h ->
            sb.append('[').append(i + 1).append("] ").append(h.title)
            val detail = if (h.body.isNotEmpty()) h.body else h.snippet
            if (detail.isNotEmpty()) sb.append('\n').append(detail.take(500))
            sb.append("\n\n")
        }
        if (context.isNotBlank()) {
            sb.append("上文：\n").append(context).append("\n\n")
        }
        sb.append("问题：").append(question)
        if (context.isNotBlank()) sb.append("（接着上文问的）")
        return sb.toString()
    }
}