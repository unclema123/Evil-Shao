package com.unclema123.evilshao

import android.content.Context

/**
 * 两个人的称呼。聊天记录里存的是角色代码（"我"/"她"），
 * 显示时才换成名字——这样改名字对历史记录也立刻生效，不用回写文件。
 */
class Names(context: Context) {

    private val prefs = context.getSharedPreferences("names", Context.MODE_PRIVATE)

    val me: String
        get() = prefs.getString("me", "")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_ME

    val her: String
        get() = prefs.getString("her", "")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_HER

    /** 用户是否主动设置过名字 */
    val configured: Boolean
        get() = prefs.getBoolean("configured", false)

    fun save(meName: String, herName: String) {
        val m = clean(meName).ifEmpty { DEFAULT_ME }
        val h = clean(herName).ifEmpty { DEFAULT_HER }
        prefs.edit()
            .putString("me", m)
            .putString("her", h)
            .putBoolean("configured", true)
            .apply()
    }

    private fun clean(raw: String): String =
        raw.replace("\n", " ").trim().take(12)

    companion object {
        const val DEFAULT_ME = "我"
        const val DEFAULT_HER = "小猪"
    }
}