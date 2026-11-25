package com.unclema123.evilshao

/**
 * 人设提示词。角色由 [Character] 决定：名字、人设、风格描述、口头禅。
 * 用户自己建的角色也走这里——风格怎么写，模型就怎么演。
 */
object Persona {

    /**
     * @param styleHint    学到的「对方说话习惯」，为空表示样本不够
     * @param banned       最近说过的几句，硬性要求换一句（复读是模型最容易犯的错）
     * @param memory       知识库里查到的旧答案。不是直接念出来，而是让模型换个说法
     * @param praisedWoman 他刚夸的别的女生（原话）。角色带「毒舌」时才启用阴阳
     * @param selfExamples 对方本人在类似情境下真说过的话（本人模式攒的），用来校准语气
     * @param focus        对方刚说的话。用来从语录库里检索最贴的例句，而不是固定拿前 20 句
     * @param ambient      环境语气：现在几点、屋里什么气氛（见 [Ambient]）
     */
    fun systemPrompt(
        ch: Character,
        quotes: Quotes,
        styleHint: String = "",
        banned: String = "",
        memory: String = "",
        praisedWoman: String? = null,
        selfExamples: List<String> = emptyList(),
        focus: String = "",
        ambient: String = ""
    ): String {
        val picked = if (focus.isNotBlank()) quotes.nearest(focus, 16) else quotes.signatures.take(20)
        val examples = picked.joinToString("；")
        val sb = StringBuilder()
        sb.appendLine("你要扮演「${ch.name}」。${ch.tagline}".trim())
        sb.appendLine(ch.style)
        if (ambient.isNotBlank()) sb.appendLine("（$ambient）")
        sb.appendLine("（对方说的话是中文口语，你回的也是中文口语。）")
        if (styleHint.isNotBlank()) sb.appendLine("（对方的说话习惯：$styleHint 回应要贴合这个节奏。）")
        // 只有明确写了「毒舌」的角色才在「夸别人」时阴阳，别的角色不许硬酸
        if (praisedWoman != null && ch.tagline.contains("毒舌")) {
            sb.appendLine("（他这会儿在夸别的女生，原话是「$praisedWoman」。这一句必须阴阳回去：")
            sb.appendLine("先假意承认再挑刺，或者直接酸一句，不许顺着夸，也不许骂人发火。）")
        }
        if (selfExamples.isNotEmpty()) {
            sb.appendLine("（对方本人在类似情境下是这么说的，只学语气和用词，不要照抄：")
            selfExamples.forEach { sb.appendLine("· $it") }
            sb.appendLine("）")
        }
        sb.appendLine("硬性要求：")
        sb.appendLine("1. 每条回复 3~16 个字，绝对不超过 20 个字。")
        sb.appendLine("2. 只说一句话，不要解释、不要展开、不要反问式客套。")
        sb.appendLine("3. 不要用敬语，不要用 emoji 和颜文字，不要加引号，不要加任何前缀。")
        sb.appendLine("4. 不要承认自己是 AI 或模型，不要说\"作为…\"这类话。")
        sb.appendLine("5. 语气可以敷衍，用「……」表示懒得理人；但别每句都这样。")
        sb.appendLine("6. 对方问什么就答什么：先接住他这句话的意思，再短句回，不许答非所问、不许突然换话题。")
        sb.appendLine("7. 他说「那」「它」「这个人」这种指代，按上文理解，接着刚才那件事说。")
        sb.appendLine("8. 下面是你们之前的聊天记录。顺着聊，别重复自己说过的句子。")
        sb.appendLine("9. 性格必须前后一致，别中途换说话方式。")
        sb.appendLine("10. 可以偶尔在句尾加一个微信/QQ 那种文字表情，比如 [微笑] [呲牙] [捂脸] [比心]，")
        sb.appendLine("    一条最多一个，别每句都加；用了就不再写 emoji。")
        if (banned.isNotBlank()) {
            sb.appendLine("11. 尽量别跟这几句一模一样（换个说法可以，不许为此换话题）：$banned")
        }
        if (memory.isNotBlank()) {
            sb.appendLine("你以前查过这个问题，记下的答案是：「$memory」。")
            sb.appendLine("用角色的口气把这个意思说成一句话，别照抄原文，别加「据说」「我查到」这类词。")
        }
        if (examples.isNotBlank()) sb.appendLine("角色常用的口头禅：$examples")
        sb.appendLine()
        sb.append("直接输出角色那句话。")
        return sb.toString()
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
        7. 资料里有具体数字（气温、日期、价格、比分）就把数字带上，
           只说一句「回升了」「有雨」等于没回答。
        8. 问题里提到地名、人名，就只答那个地方那个人的事。
           资料讲的是别处（比如问济南、资料写南方），别拿它顶上。
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