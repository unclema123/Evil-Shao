package com.unclema123.evilshao

/**
 * 从这句话和最近的聊天里认两件事：
 *
 *  1. 他是不是在夸**别的**女生 —— 是的话要阴阳回去；
 *  2. 她现在是不是高兴 —— 高兴才允许自称「朕」。
 *
 * 只做字面判断，宁可漏判也不乱判：提示词里还有通用规则兜底，
 * 这里多给一句「他刚夸别人了」，模型就知道这句该酸，而不是顺着夸。
 */
object PersonaSignals {

    /** 指代女性的词。没有这类词时，多半是在夸她自己或别的东西 */
    private val FEMALE = listOf(
        "她", "女生", "女孩", "妹子", "姐姐", "妹妹", "学姐", "学妹",
        "女同事", "女同学", "女朋友", "前女友", "女神", "美女", "小姐姐",
        "闺蜜", "人家"
    )

    /** 夸人的词。只收形容词，避免「她做的美食」这种被误判 */
    private val PRAISE = listOf(
        "好看", "漂亮", "很美", "好美", "真美", "可爱", "温柔", "优秀", "厉害",
        "气质", "身材", "贤惠", "懂事", "聪明", "有趣", "善良", "性感",
        "会打扮", "学霸", "有才", "很好", "挺好", "真棒"
    )

    /** 开心信号：她自己的回复、对方的夸奖、好消息都算 */
    private val HAPPY = listOf(
        "哈哈", "嘿嘿", "嘻嘻", "开心", "好耶", "笑死", "好玩", "有意思",
        "喜欢", "真棒", "厉害", "夸", "赢了", "过了", "考过", "升职", "加薪", "朕"
    )

    /** 这句里出现这些，就别硬塞「朕」，先照顾情绪 */
    private val UNHAPPY = listOf("难过", "生气", "烦", "讨厌", "哭", "累", "吵架", "滚")

    /** 「你真好看」这种是在夸她本人，不算夸别人 */
    private val YOU_PRAISE = Regex(
        "你(今天|现在|真的|真|好|挺|太|最|也|有点|这么|那么)?" +
            "(很|好|挺|真|太|最)?" +
            "(好看|漂亮|美|可爱|温柔|优秀|厉害|聪明|有趣|善良|性感|有气质|身材|懂事)"
    )

    /**
     * 这句是不是在夸别的女生。
     * @return 触发的那一小句（给提示词当证据）；不是就返回 null
     */
    fun praiseOfOtherWoman(text: String): String? {
        for (clause in text.split('。', '！', '？', '，', '；', '~', '…', '\n')) {
            val c = clause.trim()
            if (c.isEmpty()) continue
            if (praisesYou(c)) continue
            if (FEMALE.none { c.contains(it) }) continue
            if (PRAISE.none { c.contains(it) }) continue
            return c
        }
        return null
    }

    /** 「她比你好看」是拿你比，算酸，不算夸你本人 */
    private fun praisesYou(clause: String): Boolean {
        val i = clause.indexOf('你')
        if (i < 0) return false
        // 「比你」是在比较，说明被夸的是别人
        if (i > 0 && clause[i - 1] == '比') return false
        return YOU_PRAISE.containsMatchIn(clause)
    }

    /**
     * 现在心情不错吗：看这句 + 最近几句（她和他的都算）。
     * 被夸、赢了、好消息都算；正在生气就不算。
     */
    fun isHappy(text: String, recent: List<String>): Boolean {
        if (UNHAPPY.any { text.contains(it) }) return false
        // 他刚夸过她（含上一句），这时候心里是美的
        if ((listOf(text) + recent.takeLast(4)).any { YOU_PRAISE.containsMatchIn(it) }) return true
        val window = (recent.takeLast(6) + text).joinToString("；")
        return HAPPY.any { window.contains(it) }
    }
}