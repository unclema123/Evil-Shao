package com.unclema123.evilshao

import java.util.Calendar

/**
 * 环境语气：把「现在几点、屋里什么气氛」写进提示词。
 *
 * 同一句话，凌晨和下午回起来不该一个语气。这里不带定位、不联网，
 * 只用手机本地时间 + 固定的场景描述，所以不会泄漏任何东西。
 */
object Ambient {

    /** 给提示词用的一句话环境描述 */
    fun prompt(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val period = when (hour) {
            in 5..8 -> "清晨"
            in 9..11 -> "上午"
            in 12..13 -> "中午"
            in 14..17 -> "下午"
            in 18..21 -> "晚上"
            else -> "深夜"
        }
        val scene = when (hour) {
            in 0..5 -> "屋里只开着一盏小灯，窗外没声音"
            in 6..8 -> "窗帘刚拉开，光很软"
            in 12..13 -> "窗边亮堂堂的，桌上可能还放着饭"
            in 18..21 -> "屋里是暖黄的灯，窗外开始暗下来"
            in 22..23 -> "灯调暗了，屋里很安静"
            else -> "暖光落在窗台和书架上"
        }
        val tone = when (hour) {
            in 0..4 -> "已经很晚了，说话轻一点、短一点，别把对方越聊越精神。"
            in 5..8 -> "刚起床的时间，语气轻快一点。"
            in 12..13 -> "中午，可以顺口问一句吃饭没。"
            in 22..23 -> "快到睡觉的点了，别主动起新话题，语气放软。"
            else -> ""
        }
        return "现在是${period}（${hour}点），${scene}。$tone"
    }
}