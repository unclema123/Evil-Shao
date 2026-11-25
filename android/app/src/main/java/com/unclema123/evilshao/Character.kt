package com.unclema123.evilshao

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个角色：名字、一句话人设、风格描述、口头禅、离线规则。
 *
 * 内置角色写在 assets/characters.json，只读；用户自己建的存在
 * files/characters.json，可以改、可以删。风格描述会原样进提示词，
 * 所以「人物风格由用户决定」在这里是真的由用户决定。
 */
class Character(
    val id: String,
    val name: String,
    val tagline: String,
    val style: String,
    val greeting: String,
    val lines: List<String>,
    val rules: List<Quotes.Rule>,
    val builtin: Boolean
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        rules.forEach { r ->
            arr.put(
                JSONObject()
                    .put("keys", JSONArray(r.keys))
                    .put("replies", JSONArray(r.replies))
            )
        }
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("tagline", tagline)
            .put("style", style)
            .put("greeting", greeting)
            .put("lines", JSONArray(lines))
            .put("rules", arr)
    }

    companion object {
        fun fromJson(o: JSONObject, builtin: Boolean): Character {
            val keysLine = o.optString("id", "").ifBlank { "u" + System.currentTimeMillis() }
            val rules = ArrayList<Quotes.Rule>()
            val arr = o.optJSONArray("rules")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONObject(i) ?: continue
                    val keys = ArrayList<String>()
                    r.optJSONArray("keys")?.let { k ->
                        for (j in 0 until k.length()) k.optString(j).trim().takeIf { it.isNotEmpty() }?.let { keys.add(it) }
                    }
                    val replies = ArrayList<String>()
                    r.optJSONArray("replies")?.let { k ->
                        for (j in 0 until k.length()) k.optString(j).trim().takeIf { it.isNotEmpty() }?.let { replies.add(it) }
                    }
                    if (keys.isNotEmpty() && replies.isNotEmpty()) rules.add(Quotes.Rule(keys, replies))
                }
            }
            val lines = ArrayList<String>()
            o.optJSONArray("lines")?.let { l ->
                for (i in 0 until l.length()) l.optString(i).trim().takeIf { it.isNotEmpty() }?.let { lines.add(it) }
            }
            return Character(
                id = keysLine,
                name = o.optString("name", "新角色").ifBlank { "新角色" },
                tagline = o.optString("tagline", ""),
                style = o.optString("style", ""),
                greeting = o.optString("greeting", ""),
                lines = lines,
                rules = rules,
                builtin = builtin
            )
        }
    }
}

/** 角色仓库：内置的 + 用户建的，记住当前选的是谁 */
class CharacterStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("input", Context.MODE_PRIVATE)
    private val userFile = File(app.filesDir, "characters.json")

    val builtins: List<Character> = loadBuiltins()
    val users = ArrayList<Character>()

    /** 当前角色的 id；读不到就落到第一个内置角色 */
    var activeId: String = prefs.getString("character", "") ?: ""

    init {
        loadUsers()
        if (find(activeId) == null) {
            activeId = builtins.firstOrNull()?.id ?: "linwan"
        }
    }

    fun all(): List<Character> = builtins + users

    fun find(id: String): Character? = all().firstOrNull { it.id == id }

    fun active(): Character = find(activeId) ?: builtins.first()

    fun select(id: String) {
        if (find(id) == null) return
        activeId = id
        prefs.edit().putString("character", id).apply()
    }

    /** 新建或覆盖一个用户角色 */
    fun saveUser(ch: Character) {
        users.removeAll { it.id == ch.id }
        users.add(ch)
        persist()
    }

    fun deleteUser(id: String) {
        users.removeAll { it.id == id }
        persist()
        if (activeId == id) select(builtins.firstOrNull()?.id ?: "linwan")
    }

    private fun loadBuiltins(): List<Character> {
        val out = ArrayList<Character>()
        try {
            val text = app.assets.open("characters.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(text).optJSONArray("characters") ?: JSONArray()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(Character.fromJson(it, builtin = true)) }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) {
            out.add(
                Character(
                    id = "linwan", name = "林晚", tagline = "温柔学姐",
                    style = "说话温和，先接住对方的情绪。",
                    greeting = "来了呀，坐。",
                    lines = listOf("慢点说，我在听。", "今天也辛苦啦。", "我在呢。"),
                    rules = emptyList(), builtin = true
                )
            )
        }
        return out
    }

    private fun loadUsers() {
        if (!userFile.exists()) return
        try {
            val text = userFile.readText()
            val arr = JSONArray(text)
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { users.add(Character.fromJson(it, builtin = false)) }
            }
        } catch (_: Exception) {
        }
    }

    private fun persist() {
        try {
            val arr = JSONArray()
            users.forEach { arr.put(it.toJson()) }
            userFile.writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}