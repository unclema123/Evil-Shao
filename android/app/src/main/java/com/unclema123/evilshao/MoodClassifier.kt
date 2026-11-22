package com.unclema123.evilshao

/**
 * 看她说的话，决定露什么表情。
 *
 * 顺序有讲究：先判断怼人和惊讶，因为「你闭嘴吧」里也可能带个感叹号，
 * 放到后面就会被当成长辈式平静。
 *
 * 从 MainActivity 抽出来，好处是改表情规则不用动 1000 行的 Activity，
 * 也方便单独加语气强度（比如一句话里出现两次「哈哈」更容易笑）。
 */
object MoodClassifier {

    private val ANGRY = listOf(
        "闭嘴", "傻子", "有病", "烦", "起开", "滚", "反弹", "你才", "少来", "无聊", "幼稚", "哼"
    )
    private val SURPRISED = listOf(
        "我的天", "窝趣", "卧槽", "天哪", "真的假的", "不会吧", "什么鬼", "离谱", "我靠"
    )
    private val HAPPY = listOf(
        "哈哈", "嘻嘻", "笑死", "嘿嘿", "好耶", "开心", "好玩", "有意思", "乐死", "朕"
    )
    private val SHY = listOf(
        "人美心善", "我大度", "面子", "谢谢", "夸", "害羞", "哪有", "讨厌"
    )
    private val BORED = listOf(
        "不知道", "随便", "行吧", "算了", "无聊", "没意思", "关我什么事"
    )

    fun of(reply: String): GlbRenderer.Mood {
        val t = reply.trim()
        if (t.isEmpty()) return GlbRenderer.Mood.NEUTRAL

        fun hit(list: List<String>) = list.any { t.contains(it) }

        return when {
            hit(ANGRY) -> GlbRenderer.Mood.ANGRY
            hit(SURPRISED) -> GlbRenderer.Mood.SURPRISED
            hit(HAPPY) -> GlbRenderer.Mood.HAPPY
            hit(SHY) -> GlbRenderer.Mood.SHY
            // 短促的敷衍（嗯、哦、……）才判无聊，长了就不是敷衍
            (hit(BORED) || t in SHORT_APATHY) && t.length <= 6 -> GlbRenderer.Mood.BORED
            else -> GlbRenderer.Mood.NEUTRAL
        }
    }

    /** 单字敷衍也要认出来，否则「哦」会一直是中性脸 */
    private val SHORT_APATHY = setOf("嗯", "哦", "噢", "啊", "……", "行", "算了呗")
}