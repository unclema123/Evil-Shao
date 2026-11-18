package com.unclema123.evilshao

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 实用分：从聊天记录里推断「这句话说得怎么样」，不需要用户点赞。
 *
 * 七个信号源（每个都不完美，合起来就稳了）：
 *
 *   夸奖    下一条含「哈哈/厉害/牛/不错」              +1.0
 *   嫌弃    下一条含「无语/什么鬼/不对」                -1.2
 *   复读    把同一个问题又原样问了一遍                  -1.0
 *   改问    下一条和原问题有 2 个以上共同二元组（换着法再问） -0.6
 *   追问    下一条又是个问句                            -0.3
 *   打哈哈  下一条有「哈哈/笑死/6/牛」                  +0.8
 *   继续聊  下一条明显比平时长（说明有兴趣）            +0.5
 *   冷场    下一条只有 1~2 个字（嗯/哦）                -0.4
 *   接话    下一条用了她刚才说过的词                    +0.4
 *   时效    回复得很快 +0.3；十几分钟才接 -0.3
 *
 * 打分方式：
 *   - 每条回复按时间衰减，越近的一次影响越大（每次 ×0.92）
 *   - 分出 0~1，0.5 为中性；样本少时往 0.5 拉（置信度 = 次数/(次数+3)）
 *   - 同一个句子还会按「语境」单独记分（问句/夸奖/指令/闲聊），
 *     问她问题时合适的句子，未必适合跟她斗嘴
 *   - 数据存 files/learn.json，全程离线
 */
class Learner(context: Context) {

    class Stats(var pos: Double = 0.0, var neg: Double = 0.0, var used: Int = 0) {

        /** 0~1，0.5 中性；样本少时自动往 0.5 收缩 */
        fun perf(): Double {
            val raw = 0.5 + 0.5 * ((pos - neg) / (pos + neg + 1.5))
            val conf = used / (used + 3.0)
            return 0.5 + (raw - 0.5) * conf
        }

        fun score(): Int = (perf() * 100).roundToInt()
    }

    /** 每次训练的反馈统计，用于面板展示 */
    class Tally {
        var praise = 0
        var complain = 0
        var repeat = 0
        var echo = 0
        var followUp = 0
        var laugh = 0
        var engaged = 0
        var cold = 0
        var total = 0
    }

    private val file = File(context.filesDir, "learn.json")
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    val replyStats = HashMap<String, Stats>()
    val ctxStats = HashMap<String, Stats>()
    var tally = Tally()

    var analyzed = 0
    var feedbacks = 0
    var lastTrainAt = 0L

    private val positive = listOf(
        "哈哈", "厉害", "牛", "不错", "有意思", "确实", "笑死", "好家伙",
        "666", "绝了", "可以啊", "真的吗"
    )
    private val negative = listOf(
        "无语", "什么鬼", "不对", "没意思", "重复", "无聊", "听不懂",
        "别说了", "又来了", "？？", "??"
    )
    private val laughs = listOf("哈哈", "嘿嘿", "呵呵", "笑死")

    fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            analyzed = o.optInt("analyzed")
            feedbacks = o.optInt("feedbacks")
            lastTrainAt = o.optLong("lastTrainAt")
            readMap(o.optJSONObject("replies"), replyStats)
            readMap(o.optJSONObject("ctx"), ctxStats)
        } catch (_: Exception) {
        }
    }

    private fun readMap(src: JSONObject?, dst: HashMap<String, Stats>) {
        if (src == null) return
        for (k in src.keys()) {
            val s = src.getJSONObject(k)
            dst[k] = Stats(s.optDouble("pos", 0.0), s.optDouble("neg", 0.0), s.optInt("used"))
        }
    }

    fun save() {
        try {
            val o = JSONObject()
                .put("analyzed", analyzed)
                .put("feedbacks", feedbacks)
                .put("lastTrainAt", lastTrainAt)
                .put("replies", writeMap(replyStats))
                .put("ctx", writeMap(ctxStats))
            file.writeText(o.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
        }
    }

    private fun writeMap(src: HashMap<String, Stats>): JSONObject {
        val arr = JSONObject()
        for ((k, v) in src) {
            arr.put(k, JSONObject().put("pos", v.pos).put("neg", v.neg).put("used", v.used))
        }
        return arr
    }

    /** 话题/语境分类，用于给同一句话在不同场合分别打分 */
    fun contextOf(text: String): String = when {
        text.contains('？') || text.contains('?') || text.contains("吗") ||
            text.contains("什么") || text.contains("怎么") || text.contains("为什么") ||
            text.contains("哪") || text.contains("几") -> "question"
        text.contains("哈哈") || text.contains("厉害") || text.contains("牛") ||
            text.contains("好看") || text.contains("喜欢") -> "praise"
        text.contains("帮我") || text.contains("给我") || text.contains("快去") ||
            text.contains("快点") -> "command"
        text.length <= 4 -> "short"
        else -> "statement"
    }

    /** 跑一次训练：重扫全部聊天记录，重算所有分 */
    fun train(entries: List<ChatLog.Entry>) {
        replyStats.clear()
        ctxStats.clear()
        tally = Tally()

        val mine = entries.filter { it.role == "我" }
        val avgLen = if (mine.isEmpty()) 8 else (mine.sumOf { it.message.length } / mine.size)

        var a = 0
        var f = 0
        for (i in 0 until entries.size - 1) {
            val me = entries[i]
            val her = entries[i + 1]
            if (me.role != "我" || her.role != "她") continue
            a++
            // 联网资料型长回答不算她的话，不进风格权重
            if (her.message.length > 30) continue

            val next = entries.getOrNull(i + 2)?.takeIf { it.role == "我" }
            val gap = if (next != null) next.time - her.time else 0L
            val signal = evaluate(
                next = next?.message ?: "",
                reply = her.message,
                question = me.message,
                gapMs = gap,
                userAvg = avgLen
            )

            val s = replyStats.getOrPut(her.message) { Stats() }
            apply(s, signal)

            val ctx = contextOf(me.message)
            val cs = ctxStats.getOrPut("$ctx\u0001${her.message}") { Stats() }
            apply(cs, signal)

            tally.total++
            if (kotlin.math.abs(signal) >= 0.5) f++
        }
        analyzed = a
        feedbacks = f
        lastTrainAt = System.currentTimeMillis()
        save()
    }

    /** 时间衰减后累加：越近的一次影响越大 */
    private fun apply(s: Stats, signal: Double) {
        s.pos *= 0.92
        s.neg *= 0.92
        if (signal >= 0) s.pos += signal else s.neg += -signal
        s.used++
    }

    private fun evaluate(
        next: String,
        reply: String,
        question: String,
        gapMs: Long,
        userAvg: Int
    ): Double {
        val n = next.trim()
        if (n.isEmpty()) {
            tally.cold++
            return -0.2
        }
        var s = 0.0

        if (n == question.trim()) {
            s -= 1.0
            tally.repeat++
        } else if (negative.any { n.contains(it) }) {
            s -= 1.2
            tally.complain++
        } else if (positive.any { n.contains(it) }) {
            s += 1.0
            tally.praise++
        }

        // 换着法再问一遍：和原问题共享 2 个以上二元组
        val qb = SentenceScore.bigrams(question)
        val nb = SentenceScore.bigrams(n)
        val shared = nb.count { it in qb }
        if (n != question.trim() && shared >= 2) {
            s -= 0.6
            tally.echo++
        }

        if (laughs.any { n.contains(it) } || n.contains('6')) {
            s += 0.8
            tally.laugh++
        }

        if (n.contains('？') || n.contains('?') || n.contains("吗") ||
            n.contains("为什么") || n.contains("啥")
        ) {
            s -= 0.3
            tally.followUp++
        }

        when {
            n.length >= maxOf(6, (userAvg * 1.5).toInt()) -> {
                s += 0.5
                tally.engaged++
            }
            n.length <= 2 -> {
                s -= 0.4
                tally.cold++
            }
        }

        // 她说的词被你接过去用了，说明聊到一块了
        val rb = SentenceScore.bigrams(reply)
        if (nb.any { it in rb }) s += 0.4

        when {
            gapMs in 1..60_000 -> s += 0.3
            gapMs > 900_000 -> s -= 0.3
        }

        return s.coerceIn(-2.0, 2.0)
    }

    /**
     * 采样权重：越高越容易被选中。
     * 结合整体表现和当前语境的表现；没见过的句子给中性机会。
     */
    fun bonus(reply: String, input: String): Double {
        val overall = replyStats[reply] ?: return 1.0
        val ctx = ctxStats["${contextOf(input)}\u0001$reply"]
        var perf = overall.perf()
        if (ctx != null && ctx.used >= 2) perf = 0.6 * perf + 0.4 * ctx.perf()

        var w = 0.15 + 2.30 * perf
        if (input.length > 18 && reply.length > 12) w += 0.15
        if (input.length <= 5 && reply.length <= 8) w += 0.15
        return w.coerceAtLeast(0.08)
    }

    /** 某句话在当前语境下的分数，0~100 */
    fun scoreOf(reply: String, input: String): Int {
        val overall = replyStats[reply] ?: return 50
        val ctx = ctxStats["${contextOf(input)}\u0001$reply"]
        val perf = if (ctx != null && ctx.used >= 2) 0.6 * overall.perf() + 0.4 * ctx.perf()
        else overall.perf()
        return (perf * 100).roundToInt()
    }

    fun top(n: Int, best: Boolean): List<Pair<String, Stats>> {
        return replyStats.entries
            .filter { it.value.used >= 1 && it.value.pos + it.value.neg > 0.3 }
            .sortedByDescending { if (best) it.value.perf() else -it.value.perf() }
            .take(n)
            .map { it.key to it.value }
    }

    fun reset() {
        replyStats.clear()
        ctxStats.clear()
        tally = Tally()
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
        sb.append("分析过 $analyzed 条对话，$feedbacks 条收到明确反馈。\n")
        if (lastTrainAt > 0) {
            sb.append("上次学习：").append(fmt.format(Date(lastTrainAt))).append("\n")
        }

        val t = tally
        sb.append("\n反馈来源：夸奖 ").append(t.praise)
            .append(" · 嫌弃 ").append(t.complain)
            .append(" · 复读 ").append(t.repeat)
            .append(" · 改问 ").append(t.echo)
            .append(" · 追问 ").append(t.followUp)
            .append(" · 冷场 ").append(t.cold)
            .append(" · 打哈哈 ").append(t.laugh)
            .append(" · 继续聊 ").append(t.engaged)
            .append('\n')

        val good = top(3, true).filter { it.second.perf() > 0.53 }
        val goodKeys = good.map { it.first }.toHashSet()
        if (good.isNotEmpty()) {
            sb.append("\n说得好的：\n")
            for ((k, v) in good) {
                sb.append(String.format(Locale.CHINA, "  %d分 (%d次)  %s\n", v.score(), v.used, k))
            }
        }
        val bad = top(3, false).filter { it.second.perf() < 0.47 && it.first !in goodKeys }
        if (bad.isNotEmpty()) {
            sb.append("\n被嫌弃的：\n")
            for ((k, v) in bad) {
                sb.append(String.format(Locale.CHINA, "  %d分 (%d次)  %s\n", v.score(), v.used, k))
            }
        }
        sb.append("\n计分：夸奖+1.0 嫌弃-1.2 复读-1.0 改问-0.6 追问-0.3")
        sb.append("\n      冷场-0.4 打哈哈+0.8 继续聊+0.5 接话+0.4 回得快+0.3")
        sb.append("\n      越近的越算数；次数少时分数会往 50 靠")
        return sb.toString().trimEnd()
    }
}