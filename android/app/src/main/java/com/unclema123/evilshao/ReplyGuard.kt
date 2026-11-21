package com.unclema123.evilshao

/**
 * 复读拦截与截断。
 *
 * 大模型（尤其是温度 0.95 的小模型）很容易连着几轮说同一句话。
 * Persona 里写了「别重复自己说过的句子」，但那只是软约束——模型经常不听。
 * 这里做硬约束：生成完跟最近几句比相似度，太像就重来一次。
 *
 * 相似度用字符二元组 Dice 系数，中文不需要分词：
 *   「笑死我了」vs「笑死我了哈哈哈」→ 高相似
 *   「你猜」vs「谁跟你一样」→ 低相似
 *
 * 参考实现：OpenAI 的 frequency_penalty / presence_penalty 是概率层面的抑制，
 * 但本地模型不一定支持，所以这里在文本层面再兜一道。
 */
object ReplyGuard {

    /** 相似度超过这个值就认为在复读（0~1） */
    const val ECHO_THRESHOLD = 0.62

    /** 去掉标点空白，只留字，避免「笑死我了」和「笑死我了。」被当成两句 */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[\\p{P}\\p{S}\\s]"), "")

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return if (s.isEmpty()) emptySet() else setOf(s)
        val out = HashSet<String>(s.length)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    /** Dice 系数：2|A∩B| / (|A|+|B|)，比 Jaccard 对小句子更宽容 */
    fun similarity(a: String, b: String): Double {
        val x = normalize(a)
        val y = normalize(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val ga = bigrams(x)
        val gb = bigrams(y)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        val inter = ga.count { gb.contains(it) }
        return 2.0 * inter / (ga.size + gb.size)
    }

    /**
     * candidate 是不是在重复 recent 里某一句。
     *
     * 短句（3 字以内）放宽到完全相等才算重复：「嗯」「哦」本来就该重复，
     * 拿二元组去比会误杀。
     */
    fun isEchoOf(candidate: String, recent: List<String>, threshold: Double = ECHO_THRESHOLD): Boolean {
        if (candidate.isBlank()) return false
        val c = normalize(candidate)
        if (c.isEmpty()) return false
        for (r in recent) {
            val n = normalize(r)
            if (n.isEmpty()) continue
            if (c == n) return true
            if (c.length <= 3 || n.length <= 3) continue
            if (similarity(candidate, r) >= threshold) return true
        }
        return false
    }

    /** 把最近说过的几句拼成提示词里的「不要重复」清单 */
    fun bannedBlock(recent: List<String>, n: Int = 6): String {
        val lines = recent
            .filter { it.isNotBlank() }
            .map { it.trim() }
            .distinct()
            .takeLast(n)
        if (lines.isEmpty()) return ""
        return lines.joinToString("、")
    }

    /**
     * 按句末标点截断到 maxLen 以内，尽量不切在半个词上。
     *
     * 原来直接 subSequence(0, maxLen)，会把「济南今天最高气温二十六」
     * 切成「济南今天最高气温二十六度左」这种断头话。
     */
    fun fitToSentence(text: String, maxLen: Int): String {
        val s = text.trim()
        if (s.isEmpty()) return s
        if (s.length <= maxLen) return s

        val head = s.substring(0, maxLen)
        // 从后往前找句末标点，找到就切在那儿（保留标点）
        val enders = charArrayOf('。', '！', '？', '!', '?', '；', ';', '，', ',', '…')
        var cut = -1
        for (i in head.indices.reversed()) {
            if (head[i] in enders) {
                cut = i
                break
            }
        }
        // 句末标点太靠前（前 40% 以内）说明第一句本身就很短，宁可硬切
        if (cut >= maxLen * 4 / 10) return head.substring(0, cut + 1)

        return head
    }
}