package com.unclema123.evilshao

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * 造句器：从她自己的语言碎片里重组出新句子。
 *
 * 碎片全部取自真实聊天记录（开场词、自夸、怼人、感叹、语气尾巴），
 * 按几种固定句式拼装，所以造出来的句子风格不会跑偏。
 *
 * 造出来的句子会进入候选池，跟原有语料一样接受学习打分：
 * 被夸的留下并且更容易再被说，被嫌弃的权重自然掉下去。
 */
class Dreamer(context: Context) {

    private val file = File(context.filesDir, "invent.json")
    private val rnd = Random(System.nanoTime())

    val invented = ArrayList<String>()

    /** 每句自己造的话的质量分（0~100），造句时打分排序用 */
    val quality = HashMap<String, Int>()

    private val openers = listOf(
        "我的天", "哎呀，我的妈呀", "我去", "窝趣", "呵呵", "噢", "嗯", "……"
    )
    private val praises = listOf(
        "谁让我这么聪明", "我这么人美心善", "我家没我得散", "我大度",
        "谢谢谢谢", "我就知道", "这我拿手", "我也是要面子的人"
    )
    private val jabs = listOf(
        "你猜我是傻子吗？", "反弹。", "你可以闭嘴了", "我允许你再猜一次",
        "起开", "瞎说", "谁跟你一样", "你！！！"
    )
    private val exclaims = listOf(
        "笑死我了", "我真是服了", "窝趣", "对对对", "哈哈哈", "真的是"
    )
    private val tails = listOf("吧", "啊", "呢", "呗", "哈", "呀")
    private val titles = listOf("德州市长", "班长", "团支书", "学管会主席")

    fun load() {
        invented.clear()
        quality.clear()
        if (!file.exists()) return
        try {
            val text = file.readText(Charsets.UTF_8).trim()
            if (text.startsWith("[")) {
                // 老格式：纯数组，没有分数
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) invented.add(arr.getString(i))
            } else {
                val o = JSONObject(text)
                val arr = o.optJSONArray("sentences") ?: JSONArray()
                for (i in 0 until arr.length()) invented.add(arr.getString(i))
                val q = o.optJSONObject("q") ?: JSONObject()
                for (k in q.keys()) quality[k] = q.optInt(k)
            }
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            val arr = JSONArray()
            for (s in invented) arr.put(s)
            val q = JSONObject()
            for ((k, v) in quality) q.put(k, v)
            file.writeText(
                JSONObject().put("sentences", arr).put("q", q).toString(),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
        }
    }

    /**
     * 造 n 条新句子。existing 里是已经存在的所有句子（语料 + 已造 + 说过的），
     * 保证不会重复造已经有的。
     */
    fun grow(existing: Set<String>, n: Int = 2): List<String> {
        // 多造一批，打分后只留最好的几条
        val scored = ArrayList<Pair<String, Int>>()
        val seen = HashSet<String>()
        var tries = 0
        while (tries < ATTEMPTS && scored.size < ATTEMPTS) {
            tries++
            val c = candidate()
            if (!valid(c, existing + scored.map { it.first } + seen)) continue
            val q = SentenceScore.quality(c, existing)
            if (q < MIN_QUALITY) continue
            seen.add(c)
            scored.add(c to q)
        }
        val out = scored.sortedByDescending { it.second }.take(n).map { it.first }

        invented.addAll(out)
        for ((s, q) in scored) if (s in out) quality[s] = q
        // 控制总量，太多就成了复读机；只保留最近造的
        while (invented.size > MAX_KEEP) {
            val dropped = invented.removeAt(0)
            quality.remove(dropped)
        }
        if (out.isNotEmpty()) save()
        return out
    }

    /** 某句自造句的质量分，没有记录就现算 */
    fun qualityOf(s: String): Int = quality[s] ?: SentenceScore.quality(s, invented)

    private fun pick(list: List<String>) = list[rnd.nextInt(list.size)]

    private fun candidate(): String {
        return when (rnd.nextInt(8)) {
            0 -> "${pick(openers)}，${pick(exclaims)}"
            1 -> "${pick(praises)}${pick(tails)}"
            2 -> pick(jabs)
            3 -> "${pick(openers)}，${pick(jabs)}"
            4 -> "${pick(exclaims)}。"
            5 -> "建议推举我当${pick(titles)}"
            6 -> "${pick(openers)}，${pick(praises)}"
            else -> {
                val a = pick(titles)
                var b = pick(titles)
                var guard = 0
                while (b == a && guard++ < 8) b = pick(titles)
                "比起$a，我更想当$b"
            }
        }
    }

    /** 去掉标点后比较，避免「笑死我了」和「笑死我了。」被当成两句 */
    private fun bare(s: String): String =
        s.replace("，", "").replace("。", "")
            .replace("！", "").replace("？", "")
            .replace("~", "").replace("…", "")

    /** 风格与质量校验：不合格的直接丢弃重造 */
    private fun valid(s: String, existing: Set<String>): Boolean {
        if (s.length < 3 || s.length > 24) return false

        // 和已有句子本质重复（只差标点）就不要
        val mine = bare(s)
        if (existing.any { bare(it) == mine }) return false

        // 不能有英文字母、数字
        if (s.any { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' }) return false
        // 连续三个相同字符说明拼歪了
        for (i in 0 until s.length - 2) {
            if (s[i] == s[i + 1] && s[i] == s[i + 2]) return false
        }
        // 逗号两侧不能是同一段词
        val parts = s.split('，')
        if (parts.size == 2 && parts[0].isNotEmpty() && parts[0] == parts[1]) return false
        // 她说话短促，一句里最多一个逗号
        if (parts.size > 2) return false
        // 结尾要有语气，符合她短促的口语习惯
        val okEnd = "。！？~…吧啊呢呗哈呀了"
        if (!okEnd.contains(s.last())) return false
        return true
    }

    companion object {
        /** 最多留这么多句自己造的，超了就丢最早的 */
        const val MAX_KEEP = 45

        /** 每轮尝试造多少条候选，按分数取前几名 */
        const val ATTEMPTS = 60

        /** 质量分低于这个的直接不要 */
        const val MIN_QUALITY = 55
    }
}