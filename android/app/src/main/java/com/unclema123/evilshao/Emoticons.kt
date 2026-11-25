package com.unclema123.evilshao

import kotlin.random.Random

/**
 * 微信/QQ 那种文字表情：直接以 [微笑] [呲牙] 的形式跟句子一起显示，
 * 不用图片资源，字体里有什么就渲染什么。
 *
 * 只在「短句、没有省略号、没有别的地点」的时候挂一个，
 * 不然每句都挂会变成微商客服。
 */
object Emoticons {

    val pool = listOf(
        "[微笑]", "[呲牙]", "[捂脸]", "[旺柴]", "[偷笑]", "[害羞]", "[比心]",
        "[太阳]", "[月亮]", "[咖啡]", "[加油]", "[叹气]", "[皱眉]", "[抱抱]", "[玫瑰]"
    )

    /** 给一句回复加点表情；不适合加的句子原样返回 */
    fun maybe(reply: String): String {
        val text = reply.trim()
        if (text.isEmpty()) return text
        if (text.contains('[') || text.contains("……")) return text
        if (text.length > 18) return text
        if (Random.nextInt(100) >= 24) return text
        return text + pool[Random.nextInt(pool.size)]
    }
}