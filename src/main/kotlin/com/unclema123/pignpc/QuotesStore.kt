package com.unclema123.pignpc

import java.io.File

/**
 * 一条关键词规则：命中任意一个关键词，就从 replies 里随机回一条。
 */
data class Rule(val keys: List<String>, val replies: List<String>)

/**
 * 语录仓库。
 *
 * 文本格式（人可读、可手改）：
 *
 * ```
 * [SIG]
 * 谁让我这么聪明
 * 笑死我了
 *
 * [RULE]
 * 吃,饭,饿 => 刚才去拿了快递，然后买了饭;;因为下午的时候不饿[坏笑]
 *
 * [FALL]
 * ……
 * ```
 *
 * 规则行里 `;;` 分隔多条回复；`#` 开头是注释。
 */
class QuotesStore {

    val signatures = mutableListOf<String>()
    val rules = mutableListOf<Rule>()
    val fallback = mutableListOf<String>()

    fun serialize(): String {
        val sb = StringBuilder()
        sb.appendLine("# 小猪邵珠佳的语录 —— 改了保存，重启程序生效")
        sb.appendLine("# [SIG] 口头禅（没命中关键词时随机说）")
        sb.appendLine("# [RULE] 关键词,关键词 => 回复;;回复")
        sb.appendLine("# [FALL] 兜底回复（完全没头绪时）")
        sb.appendLine()
        sb.appendLine("[SIG]")
        signatures.forEach { sb.appendLine(it) }
        sb.appendLine()
        sb.appendLine("[RULE]")
        rules.forEach { r -> sb.appendLine(r.keys.joinToString(",") + " => " + r.replies.joinToString(";;")) }
        sb.appendLine()
        sb.appendLine("[FALL]")
        fallback.forEach { sb.appendLine(it) }
        return sb.toString()
    }

    fun loadFrom(text: String) {
        signatures.clear()
        rules.clear()
        fallback.clear()
        var section = "SIG"
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            when {
                line.equals("[SIG]", true) -> section = "SIG"
                line.equals("[RULE]", true) -> section = "RULE"
                line.equals("[FALL]", true) -> section = "FALL"
                section == "SIG" -> signatures.add(line)
                section == "FALL" -> fallback.add(line)
                else -> {
                    val idx = line.indexOf("=>")
                    if (idx <= 0) continue
                    val keys = line.substring(0, idx).split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                    val replies = line.substring(idx + 2).split(";;").map { it.trim() }.filter { it.isNotEmpty() }
                    if (keys.isNotEmpty() && replies.isNotEmpty()) rules.add(Rule(keys, replies))
                }
            }
        }
        if (signatures.isEmpty()) signatures.add("……")
        if (fallback.isEmpty()) fallback.add("……")
    }

    fun save() {
        runCatching {
            val dir = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PigNpc")
            dir.mkdirs()
            File(dir, "quotes.txt").writeText(serialize(), Charsets.UTF_8)
        }
    }

    companion object {
        fun load(): QuotesStore {
            val store = QuotesStore()
            val userFile = File(File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "PigNpc"), "quotes.txt")
            val text = when {
                userFile.isFile -> userFile.readText(Charsets.UTF_8)
                else -> QuotesStore::class.java.getResourceAsStream("/quotes.txt")
                    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            }
            store.loadFrom(text)
            return store
        }
    }
}

/**
 * 回复引擎：关键词打分 → 特色口头禅 / 兜底，并避开最近用过的 4 条。
 */
class Dialogue(private val store: QuotesStore) {

    private val recent = ArrayDeque<String>(8)

    fun reply(input: String): String {
        val text = input.lowercase()
        val scored = store.rules.map { it to keyScore(it, text) }.filter { it.second > 0 }
        if (scored.isNotEmpty()) {
            val best = scored.maxOf { it.second }
            val top = scored.filter { it.second == best }.map { it.first }
            return pick(top.random().replies)
        }
        val pool = if (Math.random() < 0.7) store.signatures else store.fallback
        return pick(pool)
    }

    fun randomSignature(): String = pick(store.signatures)

    private fun keyScore(rule: Rule, text: String): Int =
        rule.keys.filter { it.isNotBlank() && text.contains(it.lowercase()) }.sumOf { it.length }

    private fun pick(pool: List<String>): String {
        if (pool.isEmpty()) return "……"
        val fresh = pool.filter { it !in recent }
        val src = if (fresh.isEmpty()) pool else fresh
        val picked = src.random()
        recent.addLast(picked)
        while (recent.size > 4) recent.removeFirst()
        return picked
    }
}