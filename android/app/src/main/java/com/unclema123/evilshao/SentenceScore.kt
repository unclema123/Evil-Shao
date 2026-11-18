package com.unclema123.evilshao

/**
 * 句子质量打分（0~100）。
 *
 * 没法做语义理解，但能把「明显歪掉」和「和已有的太像」的句子压下去：
 *
 *   结构分（60）
 *     - 长度：她说话短，3~10 字最好，超过 16 字开始扣
 *     - 结尾：要落在语气词/标点上（啊呀呢吧了。！？…）
 *     - 逗号：最多一个，多了就散了
 *     - 重复：连续三个相同字、或整句两字循环，直接判死
 *     - 字符：不许出现字母数字
 *   新颖分（40）
 *     - 和已有句子的二元组相似度：几乎一样 → 复读，扣到很低
 *     - 太不像（低于 0.35）→ 可能拼出来是怪话，轻微扣
 *     - 0.35~0.85 之间最舒服
 */
object SentenceScore {

    private val okEnd = "。！？~…吧啊呢呗哈呀了哦咯嘛"

    /** 完整质量分，existing 用来算新颖度 */
    fun quality(s: String, existing: Collection<String>): Int {
        val len = lengthScore(s)
        val end = endScore(s)
        val comma = commaScore(s)
        val rep = repeatScore(s)
        val ascii = asciiScore(s)
        val nov = novelty(s, existing)
        val total = 0.18 * len + 0.20 * end + 0.10 * comma + 0.07 * rep + 0.05 * ascii + 0.40 * nov
        return (total * 100).toInt().coerceIn(0, 100)
    }

    fun lengthScore(s: String): Double = when {
        s.length < 3 -> 0.0
        s.length <= 6 -> 1.0
        s.length <= 10 -> 0.9
        s.length <= 16 -> 0.7
        s.length <= 24 -> 0.4
        else -> 0.0
    }

    fun endScore(s: String): Double {
        if (s.isEmpty()) return 0.0
        val c = s.last()
        return when {
            okEnd.contains(c) -> 1.0
            c == '。' || c == '！' || c == '？' -> 1.0
            c in '，'..'，' -> 0.0
            c == ',' || c == '.' -> 0.0
            else -> 0.4
        }
    }

    fun commaScore(s: String): Double = when (s.count { it == '，' || it == ',' }) {
        0 -> 1.0
        1 -> 0.9
        2 -> 0.4
        else -> 0.1
    }

    fun repeatScore(s: String): Double {
        for (i in 0 until s.length - 2) {
            if (s[i] == s[i + 1] && s[i] == s[i + 2]) return 0.0
        }
        // 两字循环：「六的六的六的」「啊吧啊吧」
        if (s.length >= 6) {
            var period2 = true
            for (i in 2 until s.length) {
                if (s[i] != s[i - 2]) {
                    period2 = false
                    break
                }
            }
            if (period2) return 0.0
        }
        return 1.0
    }

    fun asciiScore(s: String): Double =
        if (s.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) 0.0 else 1.0

    fun novelty(s: String, existing: Collection<String>): Double {
        val mine = bigrams(s)
        if (mine.isEmpty()) return 0.5
        var maxSim = 0.0
        for (e in existing) {
            if (e == s) return 0.0
            val other = bigrams(e)
            if (other.isEmpty()) continue
            val inter = mine.count { it in other }
            val sim = inter.toDouble() / (mine.size + other.size - inter)
            if (sim > maxSim) maxSim = sim
        }
        return when {
            maxSim >= 0.99 -> 0.0
            maxSim >= 0.85 -> 0.15
            maxSim >= 0.60 -> 0.80
            maxSim >= 0.35 -> 1.00
            else -> 0.70
        }
    }

    fun bigrams(s: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    /** 面板/日志用的分解说明 */
    fun explain(s: String, existing: Collection<String>): String =
        "长度${(lengthScore(s) * 100).toInt()} 结尾${(endScore(s) * 100).toInt()} " +
            "逗号${(commaScore(s) * 100).toInt()} 重复${(repeatScore(s) * 100).toInt()} " +
            "新颖${(novelty(s, existing) * 100).toInt()} → ${quality(s, existing)}"
}