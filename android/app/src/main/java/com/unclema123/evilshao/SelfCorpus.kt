package com.unclema123.evilshao

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 「本人模式」攒下来的问答对：她本人打的那句话 + 上一句。
 *
 * 和 quotes.txt 的区别是**带上下文**：
 *  - 离线时按相似度找她真说过的那句，而不是按关键词抽一句；
 *  - 在线时挑最像的几句塞进提示词当例子，模型的语气会跟着走。
 *
 * 只存本机（files/self.jsonl），不联网。她打的每句话在本人模式下都会进来，
 * 所以要有长度和去重这两道闸，别把「好的」「哈哈哈」刷满。
 */
class SelfCorpus(context: Context) {

    class Pair(val q: String, val a: String)

    private val file = File(context.filesDir, "self.jsonl")
    private val items = ArrayList<Pair>()
    private val seen = HashSet<String>()

    init {
        load()
    }

    fun size(): Int = synchronized(this) { items.size }

    /** 记一句她本人的话；返回 true 表示真的入库了 */
    fun add(prev: String, said: String): Boolean {
        val a = said.trim()
        val q = prev.trim()
        if (a.length !in 2..40) return false
        if (a in seen) return false
        synchronized(this) {
            if (a in seen) return false
            if (items.size >= MAX) return false
            items.add(Pair(q, a))
            seen.add(a)
            append(q, a)
            return true
        }
    }

    /** 离线：她真在类似情境下说过的话，够像才用 */
    fun best(text: String, threshold: Double = 0.42): String? {
        val snapshot = synchronized(this) { items.toList() }
        if (snapshot.isEmpty()) return null
        var top: Pair? = null
        var bestScore = 0.0
        for (p in snapshot) {
            val s = sim(text, p.q)
            if (s > bestScore) {
                bestScore = s
                top = p
            }
        }
        return if (top != null && bestScore >= threshold) top.a else null
    }

    /** 在线：挑最像的几句当 few-shot；带上一句，模型才知道是「什么话该这么接」 */
    fun examples(text: String, n: Int = 4): List<Pair> {
        val snapshot = synchronized(this) { items.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val scored = snapshot.map { it to sim(text, it.q) }.filter { it.second > 0.12 }
            .sortedByDescending { it.second }
        if (scored.isEmpty()) return snapshot.takeLast(2)
        return scored.take(n).map { it.first }
    }

    fun export(): String = synchronized(this) {
        items.joinToString("\n") { JSONObject().put("q", it.q).put("a", it.a).toString() }
    }

    fun clear() {
        synchronized(this) {
            items.clear()
            seen.clear()
            file.delete()
        }
    }

    // ────────────────────────────── 内部 ──────────────────────────────

    private fun load() {
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val o = JSONObject(line)
                val a = o.optString("a", "").trim()
                val q = o.optString("q", "").trim()
                if (a.length in 2..40 && a !in seen && items.size < MAX) {
                    items.add(Pair(q, a))
                    seen.add(a)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun append(q: String, a: String) {
        try {
            val o = JSONObject().put("q", q).put("a", a).put("t", System.currentTimeMillis())
            file.appendText(o.toString() + "\n")
        } catch (_: Exception) {
        }
    }

    private fun sim(x: String, y: String): Double {
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val a = grams(x)
        val b = grams(y)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { it in b }
        return inter.toDouble() / (a.size + b.size - inter)
    }

    private fun grams(s: String): Set<String> {
        val out = HashSet<String>()
        if (s.length == 1) out.add(s)
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }

    companion object {
        private const val MAX = 3000
    }
}