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
    fun withContext(q: String, prevUsers: List<String>): String {
        if (q.length >= 6 || prevUsers.isEmpty()) return q
        for (p in prevUsers.asReversed()) {
            val k = toSearchQuery(p)
            if (k.length < 5 || k == q) continue
            return "$q $k"
        }
        return q
    }
}