package com.unclema123.evilshao

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地知识库：把「搜到 + 答过」的内容存下来，下次直接答。
 *
 * 存 jsonl，一行一条：{"t":时间戳,"q":问题,"a":答案,"s":["来源url",...]}
 *
 * 检索用字符二元组重合度（中文没有空格，按 bigram 切最稳），
 * 超过阈值的才算命中，宁可查一次也不乱答。
 */
class Knowledge(context: Context) {

    class Entry(val time: Long, val question: String, val answer: String, val sources: List<String>)

    class Hit(val answer: String, val sources: List<String>, val time: Long, val score: Double)

    private val file = File(context.filesDir, "knowledge.jsonl")
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun save(question: String, answer: String, sources: List<String>) {
        val q = question.trim().replace("\n", " ")
        val a = answer.trim().replace("\n", " ")
        if (q.isEmpty() || a.isEmpty()) return
        try {
            val arr = JSONArray()
            sources.forEach { arr.put(it) }
            val line = JSONObject()
                .put("t", System.currentTimeMillis())
                .put("q", q)
                .put("a", a)
                .put("s", arr)
                .toString()
            file.appendText(line + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    fun all(): List<Entry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Entry>()
        try {
            file.forEachLine(Charsets.UTF_8) { raw ->
                if (raw.isBlank()) return@forEachLine
                try {
                    val o = JSONObject(raw)
                    val srcs = ArrayList<String>()
                    val arr = o.optJSONArray("s")
                    if (arr != null) for (i in 0 until arr.length()) srcs.add(arr.getString(i))
                    out.add(
                        Entry(
                            o.optLong("t"),
                            o.optString("q"),
                            o.optString("a"),
                            srcs
                        )
                    )
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun count(): Int = all().size

    fun recent(n: Int = 3): List<Entry> = all().takeLast(n).reversed()

    /**
     * 找一条已经学过的答案。score 是问题+答案的 bigram 覆盖率，
     * 0.55 以上才认为问的是同一件事。
     */
    fun find(query: String): Hit? {
        val qb = bigrams(normalize(query))
        if (qb.size < 2) return null
        var best: Hit? = null
        for (e in all()) {
            val pool = normalize(e.question + e.answer)
            var matched = 0
            for (g in qb) if (pool.contains(g)) matched++
            val score = matched.toDouble() / qb.size
            if (score >= 0.55 && (best == null || score >= best!!.score)) {
                best = Hit(e.answer, e.sources, e.time, score)
            }
        }
        return best
    }

    fun report(): String {
        val list = all()
        if (list.isEmpty()) return "还没查过东西。"
        val sb = StringBuilder("知识 ${list.size} 条\n")
        list.takeLast(6).reversed().forEach { e ->
            sb.append("  ").append(fmt.format(Date(e.time))).append(' ')
                .append(e.question.take(24)).append(" → ").append(e.answer.take(20)).append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** 只保留字母、数字和汉字，标点空白全去掉 */
    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun bigrams(s: String): Set<String> {
        if (s.length < 2) return emptySet()
        val out = HashSet<String>()
        for (i in 0 until s.length - 1) out.add(s.substring(i, i + 2))
        return out
    }
}