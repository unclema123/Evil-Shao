package com.unclema123.evilshao

/**
 * 把用户的一句话压成搜索词。
 *
 * 实测过：直接拿原话去搜，「济南今天天气怎么样」搜回来的是百度百科「济南市」、
 * 济南市政府网、知乎——一条天气都没有，模型只能答「不知道」。
 * 去掉「怎么样」这类口水词之后，同样的 Bing 就能搜到天气站。
 *
 * 刻意不调模型：这一层要快，几十毫秒的事没必要多等一次网络往返。
 */
object Query {

    /** 开头客套话，去掉不影响语义 */
    private val PREFIX = Regex(
        "^(请问一下|请问|我想问一下|我想问|我想知道|帮我查一下|帮我查|帮我看看|帮我|麻烦|能不能|可以|你知道|你说)"
    )

    /** 结尾的口水，去掉之后剩下的才是关键词 */
    private val SUFFIX = Regex(
        "(是什么|咋样|怎么样|怎么办|如何|是多少|多少|什么样|行不行|好不好|对不对|有没有|是不是|" +
            "吗|呢|啊|吧|呀|啦|哦|嘛|了|的|呗)+$"
    )

    /** 标点统一去掉，中文问句里标点不传递信息 */
    private val PUNCT = Regex("[\\p{P}\\p{S}\\s]+")

    /**
     * 开头的指代、连词、语气词。
     *
     * 带着「那」去搜，Bing 会当成查这个字——实测「那空气质量 济南今天天气」
     * 头条结果是汉典的「那 nà、nǎ、nèi、nā」词条。
     */
    private val LEAD = Regex(
        "^(那么|那|这个|那个|这些|那些|这|它|他|她|还|再|又|然后|所以|但是|不过|而且|另外|顺便|就是|对啊|嗯)+"
    )

    /**
     * 这句到底该不该上网搜。
     *
     * 实测教训：问「你是谁」，Bing 返回「韩国 tvN 2013 年播出的迷你剧《你是谁》」，
     * 然后这条还被存进知识库——以后每次问都会答韩剧。问她本人的话、寒暄、
     * 骂人、撒娇都不是事实问题，去搜只会搜出噪音，直接走人设更对。
     */
    fun worthSearching(text: String): Boolean {
        val t = text.trim()
        if (t.length < 2) return false
        if (SMALL_TALK_FULL.matches(t)) return false
        // 「你是谁」「你叫什么」「你多大」「你喜欢什么」——问的是她自己
        if (t.length <= 8 && SELF_QUESTION.containsMatchIn(t)) return false
        return true
    }

    /** 整句就是寒暄/语气词 */
    private val SMALL_TALK_FULL = Regex(
        "^(你好|您好|hi|hello|在吗|在不在|早|早上好|中午好|晚安|拜拜|再见|谢谢|多谢|辛苦了|" +
            "哈哈+|嘿嘿+|嗯+|哦+|噢+|啊+|好+|行|可以|没事|随便聊聊|聊聊天)[。！？~…\\s]*$",
        RegexOption.IGNORE_CASE
    )

    /** 问她自己的句式 */
    private val SELF_QUESTION = Regex(
        "^(你|您)(是|叫|姓|多大|几岁|喜欢|爱|会|在|住|吃|睡|忙|干|做|觉得|认为|有|的|好|长|想|最近|今天|现在|平时)"
    )

    fun toSearchQuery(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return raw

        s = PUNCT.replace(s, " ").trim()

        var prev: String
        do {
            prev = s
            s = PREFIX.replace(s, "").trim()
            s = SUFFIX.replace(s, "").trim()
            s = LEAD.replace(s, "").trim()
        } while (s != prev && s.isNotEmpty())

        // 去过头了（比如整句都是口水）就退回原话，宁可搜得差也别搜个空串
        if (s.length < 2) return raw.trim()
        return s
    }

    /**
     * 短问题补上下文。
     *
     * 「那明天呢」单独拿去搜，结果是一堆无关内容——它根本没有主语。
     * 这种就往前翻，找到**第一个本身就有实词**的问句，把关键词拼上：
     *
     *   那后天呢  →  那后天 济南今天天气      ✅
     *   那后天呢  →  那后天 那明天 那今天      ❌ 全是代词，越拼越乱
     *
     * 所以短的、全是代词的都跳过，只借有内容的那个。
     */
    /**
     * 只有「真的缺主语」才补上文。
     *
     * 踩过的坑：问过一句 hello，紧接着问「你是谁」，搜索词变成
     * 「你是谁 hello」——上一句的寒暄被当成了话题，搜出来的东西全不相关。
     * 现在的规则是：句子里得真有指代/连接词（那、它、这个、还、然后…）才借上文，
     * 而且上一句得有汉字、不是寒暄。
     */
    private val REFERENTIAL = Regex(
        "(那|它|这个|那个|这些|那些|他|她|他们|她们|还|再|然后|所以|上面|刚才|前面|之前说的)"
    )

    /** 寒暄、测试句不能当话题上下文 */
    private val SMALL_TALK = Regex(
        "^(你好|您好|hi|hello|在吗|在不在|测试|test|嗯+|哦+|哈哈+|在)$",
        RegexOption.IGNORE_CASE
    )

    /** 纯英文/数字的上一句多半是寒暄或命令，不是话题 */
    private val CJK = Regex("[\\u4e00-\\u9fa5]")

    fun withContext(q: String, prevUsers: List<String>): String {
        // 句子够长说明主语自己带着，别画蛇添足
        if (q.length >= 8 || prevUsers.isEmpty()) return q
        // 没有指代就说明这句是独立的（「你是谁」不该接上一句的茬）
        if (!REFERENTIAL.containsMatchIn(q)) return q
        for (p in prevUsers.asReversed()) {
            val t = p.trim()
            if (t.isEmpty() || SMALL_TALK.matches(t)) continue
            if (!CJK.containsMatchIn(t)) continue
            val k = toSearchQuery(t)
            if (k.length < 5 || k == q) continue
            return "$q $k"
        }
        return q
    }
}