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

    fun toSearchQuery(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return raw

        s = PUNCT.replace(s, " ").trim()

        var prev: String
        do {
            prev = s
            s = PREFIX.replace(s, "").trim()
            s = SUFFIX.replace(s, "").trim()
        } while (s != prev && s.isNotEmpty())

        // 去过头了（比如整句都是口水）就退回原话，宁可搜得差也别搜个空串
        if (s.length < 2) return raw.trim()
        return s
    }
}