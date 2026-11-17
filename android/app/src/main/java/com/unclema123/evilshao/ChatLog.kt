package com.unclema123.evilshao

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天记录。按行追加 JSON（jsonl），存在应用私有目录里：
 *
 *   /data/data/com.unclema123.evilshao/files/chatlog.jsonl
 *
 * 每条：{"t":时间戳,"r":"我"/"她","m":"内容"}
 */
class ChatLog(context: Context) {

    class Entry(val time: Long, val role: String, val message: String)

    private val file = File(context.filesDir, "chatlog.jsonl")
    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

    fun append(role: String, message: String) {
        val text = message.replace("\n", " ").trim()
        if (text.isEmpty()) return
        val line = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("r", role)
            .put("m", text)
            .toString()
        try {
            file.appendText(line + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // 磁盘写失败不该让应用崩掉
        }
    }

    fun entries(): List<Entry> {
        if (!file.exists()) return emptyList()
        val out = ArrayList<Entry>()
        file.forEachLine(Charsets.UTF_8) { raw ->
            if (raw.isBlank()) return@forEachLine
            try {
                val o = JSONObject(raw)
                out.add(Entry(o.optLong("t"), o.optString("r"), o.optString("m")))
            } catch (_: Exception) {
            }
        }
        return out
    }

    fun count(): Int = entries().size

    fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    /** 给人看的纯文本，用于弹窗和分享 */
    fun asText(meName: String = "我", herName: String = "小猪"): String {
        val list = entries()
        if (list.isEmpty()) return ""
        val sb = StringBuilder()
        for (e in list) {
            sb.append(fmt.format(Date(e.time)))
            sb.append(' ')
            sb.append(if (e.role == "我") meName else herName)
            sb.append("：")
            sb.append(e.message)
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 按天分组的对话，给弹窗用；同一天只显示一次日期 */
    fun asTextGrouped(meName: String = "我", herName: String = "小猪"): String {
        val list = entries()
        if (list.isEmpty()) return ""
        val dayFmt = SimpleDateFormat("MM-dd", Locale.CHINA)
        val timeFmt = SimpleDateFormat("HH:mm", Locale.CHINA)
        val sb = StringBuilder()
        var lastDay = ""
        for (e in list) {
            val d = dayFmt.format(Date(e.time))
            if (d != lastDay) {
                if (lastDay.isNotEmpty()) sb.append('\n')
                sb.append("── ").append(d).append(" ──\n")
                lastDay = d
            }
            sb.append(timeFmt.format(Date(e.time)))
            sb.append(' ')
            sb.append(if (e.role == "我") meName else herName)
            sb.append("：")
            sb.append(e.message)
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 参与对话的人（去重），便于统计「两个人的对话」 */
    fun participants(): Pair<Int, Int> {
        var mine = 0
        var hers = 0
        for (e in entries()) {
            if (e.role == "我") mine++ else hers++
        }
        return mine to hers
    }

    /** 会话文件路径，方便排查 */
    fun path(): String = file.absolutePath
}