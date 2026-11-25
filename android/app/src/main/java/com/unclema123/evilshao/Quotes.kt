package com.unclema123.evilshao

import kotlin.random.Random

/**
 * 语录与回复引擎。数据来自当前角色的口头禅和规则（见 [Character]）。
 *
 *   signatures  口头禅，没命中关键词时随机说
 *   rules       关键词 => 回复
 *   fallback    兜底
 */
class Quotes {

    class Rule(val keys: List<String>, val replies: List<String>)

    /** 挂上学习器后，回复会按学到的权重来挑 */
    var learner: Learner? = null

    /** 挂上造句器后，自造句按质量分加权 */
    var dreamer: Dreamer? = null

    val signatures = ArrayList<String>()

    /** 她自己造出来的新句子，和原有语料一起参与抽取 */
    val invented = ArrayList<String>()
    val rules = ArrayList<Rule>()
    val fallback = ArrayList<String>()

    private val recent = ArrayDeque<String>()

    /**
     * 换角色：口头禅、规则、兜底全部换成这个人的。
     * 学习权重和自造句不动——那些是「用户教出来的」，换人不该清零。
     */
    fun apply(ch: Character) {
        signatures.clear()
        rules.clear()
        fallback.clear()
        signatures.addAll(ch.lines)
        rules.addAll(ch.rules)
        fallback.addAll(ch.lines.takeLast(4))
        if (fallback.isEmpty()) fallback.add("……")
        if (signatures.isEmpty()) signatures.add("……")
        synchronized(recent) { recent.clear() }
    }

    fun randomSignature(): String = pick(signatures + invented)

    /**
     * 按输入从语录库里检索最贴的几句（给提示词当例句用）。
     * 一句都沾不上就随机取，保证例句池不空。
     */
    fun nearest(input: String, n: Int): List<String> {
        val pool = signatures + invented
        if (pool.isEmpty()) return emptyList()
        val scored = pool.map { it to score(input, it) }.sortedByDescending { it.second }
        val hit = scored.take(n).filter { it.second > 0.0 }
        val out = if (hit.isEmpty()) pool.shuffled().take(minOf(n, pool.size)) else hit.map { it.first }
        return out.distinct()
    }

    /**
     * 离线：从语录库里找一句最贴输入的话。
     * 相似度不够就返回 null，让上层用兜底句——宁可不说话，也别答非所问。
     */
    fun replyFor(input: String, threshold: Double = 0.15): String? {
        val pool = signatures + invented
        var best: String? = null
        var bestScore = 0.0
        for (s in pool) {
            val v = score(input, s)
            if (v > bestScore) {
                bestScore = v
                best = s
            }
        }
        return if (best != null && bestScore >= threshold) best else null
    }

    /** 输入和语录共享多少 2-gram，按语录长度归一化 */
    private fun score(input: String, line: String): Double {
        if (input.isBlank() || line.length < 2) return 0.0
        val a = grams(input)
        val b = grams(line)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val shared = b.count { it in a }
        return shared.toDouble() / b.size
    }

    private fun grams(s: String): Set<String> {
        val out = HashSet<String>()
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    fun reply(input: String): String {
        val text = input.trim()
        if (text.isEmpty()) return randomSignature()

        var best: Rule? = null
        var bestScore = 0
        for (r in rules) {
            var score = 0
            for (k in r.keys) {
                if (text.contains(k, ignoreCase = true)) score += k.length
            }
            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }

        val roll = Random.nextInt(100)
        val pool: List<String> = when {
            best != null && roll < 82 -> best.replies
            roll < 91 -> signatures + invented
            else -> fallback
        }
        return pick(pool, text)
    }

    /**
     * 只回「关键词真的对得上」的规则回复；一句都对不上就返回 null。
     *
     * 给编排层用。随机的签名/兜底句拿来应答用户打的字，
     * 就会变成「你在说啥」→「谁跟你一样」这种没头没脑的对话。
     */
    fun matchedReply(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        var best: Rule? = null
        var bestScore = 0
        for (r in rules) {
            var score = 0
            for (k in r.keys) {
                if (text.contains(k, ignoreCase = true)) score += k.length
            }
            if (score > bestScore) {
                bestScore = score
                best = r
            }
        }
        val rule = best ?: return null
        return pick(rule.replies, text)
    }

    /**
     * 从候选池里按「学到的权重」抽一句。
     *
     * learner 为空时退化成均匀随机；有权重时，被夸过的句子更容易被挑中，
     * 被嫌弃过的会被压低。同时避免最近说过的 4 句。
     */
    private fun pick(pool: List<String>, input: String = ""): String {
        if (pool.isEmpty()) return "……"
        if (pool.size == 1) {
            remember(pool[0])
            return pool[0]
        }

        val fresh = pool.filter { !recent.contains(it) }
        val candidates = if (fresh.isNotEmpty()) fresh else pool

        val learned = learner
        if (learned == null) {
            val s = candidates[Random.nextInt(candidates.size)]
            remember(s)
            return s
        }

        // 12% 概率纯随机：避免低分句永远没机会翻身
        if (Random.nextInt(100) < 12) {
            val s = candidates[Random.nextInt(candidates.size)]
            remember(s)
            return s
        }

        val weights = DoubleArray(candidates.size) { i ->
            var w = learned.bonus(candidates[i], input)
            // 自造句再乘上质量分，防止拼出来的怪句子混进来
            if (invented.contains(candidates[i])) {
                val q = dreamer?.qualityOf(candidates[i]) ?: 60
                w *= 0.70 + 0.60 * (q / 100.0)
            }
            w
        }
        val total = weights.sum()
        if (total <= 0.0) {
            val s = candidates[Random.nextInt(candidates.size)]
            remember(s)
            return s
        }

        var r = Random.nextDouble() * total
        for (i in candidates.indices) {
            r -= weights[i]
            if (r <= 0.0) {
                remember(candidates[i])
                return candidates[i]
            }
        }
        val s = candidates.last()
        remember(s)
        return s
    }

    private fun remember(s: String) {
        recent.addLast(s)
        while (recent.size > 4) recent.removeFirst()
    }
}