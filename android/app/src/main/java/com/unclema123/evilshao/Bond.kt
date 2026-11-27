package com.unclema123.evilshao

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 陪伴记录：陪了多少天、好感度多少。
 *
 * 市面上的陪伴类应用基本都有这一层——它不改聊天内容，只是把「你们处了多久」存下来，
 * 用来在关于页和招呼语里体现。数据只在手机里，纯本地计数。
 *
 * 规则：
 * - 每天第一次打开：天数 +1，好感度 +5
 * - 每说一句话：好感度 +2，单日最多 +40，防止刷
 * - 好感度只涨不跌；等级按区间给一个称呼
 */
class Bond(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("companion", Context.MODE_PRIVATE)

    private val today: String
        get() = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date())

    /** 打开应用时调一次：跨天了就记一天 */
    fun onOpen() {
        val t = today
        if (prefs.getString("lastDay", "") == t) return
        val first = prefs.getString("firstDay", "") ?: ""
        val days = prefs.getInt("days", 0)
        val affinity = prefs.getInt("affinity", 0)
        prefs.edit()
            .putString("firstDay", if (first.isEmpty()) t else first)
            .putString("lastDay", t)
            .putInt("days", if (first.isEmpty()) 1 else days + 1)
            .putInt("affinity", affinity + 5)
            .putInt("dayGain", 0)
            .apply()
    }

    /** 她说了一句话 */
    fun onTalk() {
        val gained = prefs.getInt("dayGain", 0)
        if (gained >= 40) return
        prefs.edit()
            .putInt("affinity", prefs.getInt("affinity", 0) + 2)
            .putInt("dayGain", gained + 2)
            .apply()
    }

    val days: Int get() = prefs.getInt("days", 0)
    val affinity: Int get() = prefs.getInt("affinity", 0)

    val level: String
        get() = when {
            affinity >= 600 -> "很亲近"
            affinity >= 300 -> "亲近"
            affinity >= 150 -> "熟悉"
            affinity >= 50 -> "眼熟"
            else -> "刚认识"
        }

    fun summary(): String = "陪伴第 $days 天 · 好感度 $affinity（$level）"
}