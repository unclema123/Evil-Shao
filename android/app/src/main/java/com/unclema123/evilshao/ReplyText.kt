package com.unclema123.evilshao

/**
 * 模型输出的清洗。从 MainActivity 里抽出来的，逻辑基本没变，
 * 只改了两处：
 *  1. 超长时按句末标点截断（[ReplyGuard.fitToSentence]），不再切在半个词上；
 *  2. 把「作为助手」「我是AI」这类崩人设的句子挡掉的范围放宽了一点。
 */
object ReplyText {

    private val AI_TELLS = listOf(
        "作为AI", "作为 AI", "作为人工智能", "我是一个AI", "我是 AI",
        "语言模型", "AI助手", "AI 助手", "无法回答", "我不能回答"
    )

    /**
     * @param maxLen 最多保留多少字
     * @return 清洗后的句子；空串或崩人设时返回 null
     */
    fun sanitize(raw: String?, maxLen: Int = 40): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        // 有的模型爱用 markdown 代码块包一层
        s = s.removePrefix("```").removeSuffix("```").trim()
        s = s.replace("\r", " ").replace("\n", " ")
        s = s.replace(Regex("\\s{2,}"), " ")
        // 去掉整体包裹的引号/书名号
        s = s.trim('"', '\'', '「', '」', '“', '”', '『', '』')
        if (s.isBlank()) return null

        for (tell in AI_TELLS) {
            if (s.contains(tell, ignoreCase = true)) return null
        }
        // 冒号开头的「她：……」是模型在自报角色，去掉前缀
        s = s.replace(Regex("^(她|小猪|Whisper)\\s*[:：]\\s*"), "")

        if (s.length > maxLen) s = ReplyGuard.fitToSentence(s, maxLen)
        return s.trim().ifBlank { null }
    }
}