package com.unclema123.evilshao

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本地自学习：从聊天记录里推断「哪句话效果好」，给回复打权重。
 *
 * 判断依据（隐式反馈，不需要用户点赞）：
 *   +1  用户下一条说了「哈哈」「对」「厉害」这类正面词
 *   -1  用户下一条说了「无语」「不对」「什么鬼」，或者把同一句话又问了一遍
 *    0  没有明确信号
 *
 * 权重用拉普拉斯平滑：w = (赞+2) / (赞+踩+4)，范围 0~1，0.5 表示中性。
 * 数据存在 files/learn.json，训练完全离线。
 */
class Learner(context: Context) {

    class Stats(var pos: Int = 0, var neg: Int = 0, var used: Int = 0) {
        /** 0~1 的得分，0.5 为中性 */
        fun weight(): Double = (pos + 2.0) / (pos + neg + 4.0)
    }

    private val file = File(context.filesDir, "learn.json")
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    val replyStats = HashMap<String, Stats>()

    var analyzed = 0
    var feedbacks = 0
    var lastTrainAt = 0L

    private val positive = listOf(
        "哈哈", "嘿", "笑", "对", "好", "嗯", "厉害", "牛", "可以", "有意思",
        "太", "6", "是的", "确实", "真的", "不错", "行"
    )
    private val negative = listOf(
        "无语", "什么鬼", "你说什么", "不对", "不是", "没意思", "重复",
        "无聊", "听不懂", "??", "？？", "别", "又来了"
    )

    fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            analyzed = o.optInt("analyzed")
            feedbacks = o.optInt("feedbacks")
            lastTrainAt = o.optLong("lastTrainAt")
            val arr = o.optJSONObject("replies") ?: JSONObject()
            for (key in arr.keys()) {
                val s = arr.getJSONObject(key)
                replyStats[key] = Stats(s.optInt("pos"), s.optInt("neg"), s.optInt("used"))
            }
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            val arr = JSONObject()
            for ((k, v) in replyStats) {
                arr.put(k, JSONObject().put("pos", v.pos).put("neg", v.neg).put("used", v.used))
            }
            val o = JSONObject()
                .put("analyzed", analyzed)
                .put("feedbacks", feedbacks)
                .put("lastTrainAt", lastTrainAt)
                .put("replies", arr)
            file.writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    /** 跑一次训练：重扫全部聊天记录，重算权重 */
    fun train(entries: List<ChatLog.Entry>) {
        replyStats.clear()
        var a = 0
        var f = 0
        for (i in 0 until entries.size - 1) {
            val me = entries[i]
            val her = entries[i + 1]
            if (me.role != "我" || her.role != "她") continue
            a++
            // 联网查来的资料型长回答不算「她的话」，不该混进风格权重
            if (her.message.length > 30) continue

            val s = replyStats.getOrPut(her.message) { Stats() }
            s.used++

            val next = entries.getOrNull(i + 2)
            if (next != null && next.role == "我") {
                when (judge(next.message, me.message)) {
                    1 -> { s.pos++; f++ }
                    -1 -> { s.neg++; f++ }
                }
            }
        }
        analyzed = a
        feedbacks = f
        lastTrainAt = System.currentTimeMillis()
        save()
    }

    private fun judge(next: String, prev: String): Int {
        val n = next.trim()
        if (n.isEmpty()) return 0
        // 同一句话又问一遍，说明上一次没答到点上
        if (n == prev.trim()) return -1
        if (negative.any { n.contains(it) }) return -1
        if (positive.any { n.contains(it) }) return 1
        return 0
    }

    /**
     * 给某个候选回复的权重。1.0 为基准，越高越容易被选中。
     * 没见过的句子给一点点额外机会（探索），避免一开始就锁死。
     */
    fun bonus(reply: String, input: String): Double {
        val s = replyStats[reply] ?: return 1.35
        var w = s.weight() * 1.8
        if (s.used < 3) w += 0.35
        if (input.length > 18 && reply.length > 12) w += 0.20
        if (input.length <= 5 && reply.length <= 8) w += 0.20
        return w.coerceAtLeast(0.05)
    }

    fun top(n: Int, best: Boolean): List<Pair<String, Stats>> {
        return replyStats.entries
            .filter { it.value.pos + it.value.neg >= 1 }
            .sortedByDescending { if (best) it.value.weight() else -it.value.weight() }
            .take(n)
            .map { it.key to it.value }
    }

    fun reset() {
        replyStats.clear()
        analyzed = 0
        feedbacks = 0
        lastTrainAt = 0L
        save()
    }

    fun report(): String {
        val sb = StringBuilder()
        if (analyzed == 0 && replyStats.isEmpty()) {
            return "她还没学到东西。\n\n多和她聊几句（最好带上「哈哈」「不对」这种反馈），她就能开始琢磨了。"
        }
        sb.append("分析过 $analyzed 条对话，其中 $feedbacks 条有明确反馈。\n")
        if (lastTrainAt > 0) {
            sb.append("上次学习：").append(fmt.format(Date(lastTrainAt))).append("\n")
        }
        sb.append('\n')

        val good = top(3, true)
        if (good.isNotEmpty()) {
            sb.append("说得好的：\n")
            for ((k, v) in good) {
                sb.append(String.format(Locale.CHINA, "  %.0f分  %s\n", v.weight() * 100, k))
            }
            sb.append('\n')
        }
        val bad = top(3, false)
        if (bad.isNotEmpty() && bad.first().second.weight() < 0.5) {
            sb.append("被嫌弃的：\n")
            for ((k, v) in bad) {
                if (v.weight() >= 0.5) continue
                sb.append(String.format(Locale.CHINA, "  %.0f分  %s\n", v.weight() * 100, k))
            }
        }
        return sb.toString().trimEnd()
    }
}