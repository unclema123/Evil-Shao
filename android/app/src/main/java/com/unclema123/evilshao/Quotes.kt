package com.unclema123.evilshao

import android.content.Context
import kotlin.random.Random

/**
 * 语录与回复引擎。格式和桌面版一致：
 *
 *   [SIG]  口头禅，一行一条
 *   [RULE] 关键词,关键词 => 回复;;回复
 *   [FALL] 兜底
 */
class Quotes(context: Context) {

    class Rule(val keys: List<String>, val replies: List<String>)

    val signatures = ArrayList<String>()
    val rules = ArrayList<Rule>()
    val fallback = ArrayList<String>()

    private val recent = ArrayDeque<String>()

    init {
        context.assets.open("quotes.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            var section = ""
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.uppercase()
                    continue
                }
                when (section) {
                    "[SIG]" -> signatures.add(line)
                    "[FALL]" -> fallback.add(line)
                    "[RULE]" -> {
                        val arrow = line.indexOf("=>")
                        if (arrow <= 0) continue
                        val keys = line.substring(0, arrow).split(',', '，')
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        val replies = line.substring(arrow + 2).split(";;")
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        if (keys.isNotEmpty() && replies.isNotEmpty()) rules.add(Rule(keys, replies))
                    }
                }
            }
        }
        if (fallback.isEmpty()) fallback.add("……")
        if (signatures.isEmpty()) signatures.add("……")
    }

    fun randomSignature(): String = pick(signatures)

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
            roll < 91 -> signatures
            else -> fallback
        }
        return pick(pool)
    }

    private fun pick(pool: List<String>): String {
        if (pool.isEmpty()) return "……"
        if (pool.size == 1) return pool[0]
        repeat(6) {
            val s = pool[Random.nextInt(pool.size)]
            if (!recent.contains(s)) {
                remember(s)
                return s
            }
        }
        val s = pool[Random.nextInt(pool.size)]
        remember(s)
        return s
    }

    private fun remember(s: String) {
        recent.addLast(s)
        while (recent.size > 4) recent.removeFirst()
    }
}