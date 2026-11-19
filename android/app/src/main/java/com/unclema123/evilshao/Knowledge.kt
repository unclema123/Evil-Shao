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
     *
     * 必须带有效期：天气、价格、新闻这类问一次记一辈子的行为，
     * 表现出来就是「搜索很差」——其实它根本没搜，用的是几个月前的旧答案。
     */
    fun find(query: String): Hit? {
        val qb = bigrams(normalize(query))
        if (qb.size < 2) return null
        val ttl = ttlFor(query)
        val now = System.currentTimeMillis()
        var best: Hit? = null
        for (e in all()) {
            if (now - e.time > ttl) continue          // 过期的不算
            if (isNonAnswer(e.answer)) continue       // 「不知道」不算知识
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

    /** 这条问题的答案能信多久 */
    fun ttlFor(query: String): Long =
        if (isTimeSensitive(query)) TTL_FRESH else TTL_STABLE

    /** 问题里有时效性的词，就别拿老答案糊弄 */
    fun isTimeSensitive(query: String): Boolean = TIME_SENSITIVE.containsMatchIn(query)

    /**
     * 这条答案是不是「没答上来」。
     * 存了也没用——下次问还会秒回不知道，看起来就像搜索坏了。
     */
    fun isNonAnswer(answer: String): Boolean {
        val t = answer.replace(Regex("[\\p{P}\\s]"), "")
        if (t.length > 8) return false
        return NON_ANSWER.any { t.contains(it) }
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

    companion object {
        /** 时效性问题的答案只信 1 小时 */
        const val TTL_FRESH = 60L * 60 * 1000

        /** 常识类问题信 7 天 */
        const val TTL_STABLE = 7L * 24 * 60 * 60 * 1000

        private val TIME_SENSITIVE = Regex(
            "今天|今日|现在|目前|最新|最近|刚刚|这周|本周|本月|天气|气温|下雨|降雨|几号|几点|星期几|" +
                "价格|多少钱|股价|汇率|油价|房价|金价|票房|比分|赛程|新闻|发布|停运|限行|放假|假期|" +
                "去世|逝世|当选|比赛结果|开奖|排行|榜|预报"
        )

        /** 判定「没答上来」的词 */
        private val NON_ANSWER = listOf("不知道", "不清楚", "不确定", "没查到", "无法回答", "答不上")
    }
}