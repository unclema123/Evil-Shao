package com.unclema123.evilshao

import android.content.Context
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * 学「你怎么说话」，然后让她往你这边靠。
 *
 * 只看用户自己发的消息，提取这几类特征：
 *   - 句子长短（平均字数）
 *   - 标点习惯（爱不爱用句号、感叹号）
 *   - 语气词偏好（啊/呀/呢/吧/哈…）
 *   - 叠字笑（哈哈/哈哈哈）
 *   - 表情标签（[坏笑] 这种）
 *
 * 学到之后做两件事：
 *   1. 本地选句时按你的长度偏好挑，再改写一下语气和标点
 *   2. 走大模型时把你的风格写进提示词，让模型也照着说
 *
 * 数据存在 files/style.json，全程离线。
 */
class StyleProfile(context: Context) {

    private val file = File(context.filesDir, "style.json")

    var samples = 0
        private set
    var totalChars = 0
        private set
    var punctMessages = 0
        private set
    var exclaimMessages = 0
        private set
    var questionMessages = 0
        private set
    var laughMessages = 0
        private set
    var tagMessages = 0
        private set

    private val particles = HashMap<String, Int>()

    private val particleList = listOf(
        "啊", "呀", "呢", "吧", "哈", "嘛", "哦", "咯", "嗯", "唉", "呗", "啦", "诶"
    )
    private val tagRe = Regex("\\[[^\\]]{1,6}\\]")

    /** 平均每条消息多少字；样本不足时返回 0 */
    val avgLen: Int
        get() = if (samples == 0) 0 else totalChars / samples

    /** 一半以上的消息不带标点 → 认为你不爱用标点 */
    val prefersNoPunct: Boolean
        get() = samples >= 4 && punctMessages * 2 < samples

    /** 感叹号用得勤 */
    val lovesExclaim: Boolean
        get() = samples >= 4 && exclaimMessages * 3 > samples

    /** 爱用叠字笑 */
    val usesRepeatedLaugh: Boolean
        get() = laughMessages >= 2

    /** 爱用表情标签 */
    val usesTags: Boolean
        get() = tagMessages >= 2

    fun load() {
        if (!file.exists()) return
        try {
            val o = JSONObject(file.readText(Charsets.UTF_8))
            samples = o.optInt("samples")
            totalChars = o.optInt("totalChars")
            punctMessages = o.optInt("punctMessages")
            exclaimMessages = o.optInt("exclaimMessages")
            questionMessages = o.optInt("questionMessages")
            laughMessages = o.optInt("laughMessages")
            tagMessages = o.optInt("tagMessages")
            val p = o.optJSONObject("particles") ?: JSONObject()
            for (k in p.keys()) particles[k] = p.optInt(k)
        } catch (_: Exception) {
        }
    }

    fun save() {
        try {
            val p = JSONObject()
            for ((k, v) in particles) p.put(k, v)
            file.writeText(
                JSONObject()
                    .put("samples", samples)
                    .put("totalChars", totalChars)
                    .put("punctMessages", punctMessages)
                    .put("exclaimMessages", exclaimMessages)
                    .put("questionMessages", questionMessages)
                    .put("laughMessages", laughMessages)
                    .put("tagMessages", tagMessages)
                    .put("particles", p)
                    .toString(),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
        }
    }

    /** 看一条用户消息 */
    fun observe(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return

        samples++
        totalChars += t.length

        if (t.any { it == '。' || it == '！' || it == '？' || it == '，' || it == '~' || it == '…' }) {
            punctMessages++
        }
        if (t.contains('！') || t.contains('!')) exclaimMessages++
        if (t.contains('？') || t.contains('?')) questionMessages++
        if (t.contains("哈哈") || t.contains("嘿嘿") || t.contains("呵呵")) laughMessages++
        if (tagRe.containsMatchIn(t)) tagMessages++

        for (p in particleList) {
            if (t.contains(p)) particles[p] = (particles[p] ?: 0) + 1
        }
    }

    /** 全量重算，训练时校正增量统计的漂移 */
    fun rebuild(userMessages: List<String>) {
        samples = 0
        totalChars = 0
        punctMessages = 0
        exclaimMessages = 0
        questionMessages = 0
        laughMessages = 0
        tagMessages = 0
        particles.clear()
        for (m in userMessages) observe(m)
        save()
    }

    /** 最常用的语气词，至少出现 2 次才算 */
    fun topParticle(): String? {
        val best = particles.entries
            .filter { it.value >= 2 }
            .maxByOrNull { it.value }
        return best?.key
    }

    fun topParticles(n: Int = 2): List<String> =
        particles.entries.sortedByDescending { it.value }.take(n).map { it.key }

    /**
     * 把她的话改得更像你。
     * 样本太少（<3 句）时原样返回，避免瞎改。
     */
    fun adapt(reply: String, rnd: Random = Random.Default): String {
        if (samples < 3 || reply.isBlank()) return reply
        var s = reply

        // 1. 你爱说短句 → 砍掉后半截，只留第一小节
        if (avgLen in 1..5 && s.length > 10 && rnd.nextFloat() < 0.65f) {
            val cut = s.indexOfAny(charArrayOf('，', '。', '！', '？'))
            if (cut in 2 until s.length) s = s.substring(0, cut)
        }

        // 2. 你不用句号，她也不给
        if (prefersNoPunct && s.endsWith("。")) s = s.dropLast(1)

        // 3. 你爱用感叹号，她也用
        if (lovesExclaim && s.endsWith("。") && rnd.nextFloat() < 0.5f) {
            s = s.dropLast(1) + "！"
        }

        // 4. 你爱打哈哈，她也叠起来
        if (usesRepeatedLaugh && s.contains("哈哈") && !s.contains("哈哈哈") && rnd.nextFloat() < 0.6f) {
            s = s.replace("哈哈", "哈哈哈")
        }

        // 5. 学你的口头语气词（已经带语气词的就别再叠一个）
        val p = topParticle()
        val alreadyHasParticle = particleList.any { s.endsWith(it) }
        if (p != null && !alreadyHasParticle && s.length <= 10 &&
            !s.endsWith("。") && !s.endsWith("！") && !s.endsWith("？") &&
            rnd.nextFloat() < 0.5f
        ) {
            s += p
        }

        return s.ifBlank { reply }
    }

    /** 给大模型用的风格提示 */
    fun hint(): String {
        if (samples < 3) return ""
        val sb = StringBuilder()
        sb.append("对方说话")
        when {
            avgLen <= 5 -> sb.append("很短，多为几个字，")
            avgLen <= 12 -> sb.append("偏短，一二十字以内，")
            else -> sb.append("偏长，常整段说，")
        }
        if (prefersNoPunct) sb.append("基本不用句号，") else if (lovesExclaim) sb.append("爱用感叹号，")
        val ps = topParticles(2)
        if (ps.isNotEmpty()) sb.append("常用语气词「").append(ps.joinToString("、")).append("」，")
        if (usesRepeatedLaugh) sb.append("爱用叠字笑，")
        sb.append("回应时贴近这个节奏。")
        return sb.toString()
    }

    /** 面板上展示的人类可读摘要 */
    fun summary(): String {
        if (samples == 0) return "还没说过话，先聊几句她才能学到。"
        val sb = StringBuilder()
        sb.append("你说了 $samples 句，平均 $avgLen 个字\n")
        val ps = topParticles(3)
        sb.append("常用语气词：").append(if (ps.isEmpty()) "没有明显偏好" else ps.joinToString("、")).append('\n')
        sb.append("标点习惯：").append(
            when {
                prefersNoPunct -> "基本不用标点"
                lovesExclaim -> "爱用感叹号"
                else -> "正常"
            }
        ).append('\n')
        if (usesRepeatedLaugh) sb.append("爱打哈哈（哈哈/哈哈哈）\n")
        if (usesTags) sb.append("爱发表情标签\n")
        sb.append("→ 她在往你的说话节奏上靠")
        return sb.toString()
    }
}