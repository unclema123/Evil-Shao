package com.unclema123.evilshao

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个性格，不是一个人：只说「怎么说话」，不说「我是谁」。
 * 用户挑几个性格拼成一个角色，名字只是显示用。
 */
class Trait(
    val id: String,
    val name: String,
    val style: String,
    val lines: List<String>
)

/** 性格库：内置在 assets/traits.json，只读 */
class TraitStore(context: Context) {

    val all: List<Trait> = load(context)

    fun find(id: String): Trait? = all.firstOrNull { it.id == id }

    fun byIds(ids: List<String>): List<Trait> = ids.mapNotNull { find(it) }

    private fun load(context: Context): List<Trait> {
        val out = ArrayList<Trait>()
        try {
            val text = context.assets.open("traits.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONObject(text).optJSONArray("traits") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "").trim()
                if (id.isEmpty()) continue
                val lines = ArrayList<String>()
                o.optJSONArray("lines")?.let { l ->
                    for (j in 0 until l.length()) {
                        l.optString(j).trim().takeIf { it.isNotEmpty() }?.let { lines.add(it) }
                    }
                }
                out.add(
                    Trait(
                        id = id,
                        name = o.optString("name", id),
                        style = o.optString("style", ""),
                        lines = lines
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }
}

/**
 * 一个角色 = 几个性格 + 用户补的语录 + 用户补的说明。
 * 提示词里的「人设」全部来自性格描述，没有预设身份。
 */
class Character(
    val id: String,
    val name: String,
    val traitIds: List<String>,
    val extraLines: List<String>,
    val extraStyle: String,
    val greeting: String,
    val builtin: Boolean,
    val traits: List<Trait>
) {
    /** 用来在菜单里显示：温柔 · 幽默 */
    val tagline: String
        get() = traits.joinToString(" · ") { it.name }.ifBlank { "自定义" }

    /** 语录库：所选性格的语录 + 用户补的，去重保序 */
    val lines: List<String>
        get() = (traits.flatMap { it.lines } + extraLines).distinct()

    /** 提示词里的风格描述：性格说明拼起来，最后附用户补充 */
    val style: String
        get() = buildString {
            append("你的性格是：").append(tagline).append("。\n")
            traits.forEach { append(it.style).append('\n') }
            if (extraStyle.isNotBlank()) append(extraStyle).append('\n')
        }

    /** 性格目前不带关键词规则，离线靠语录检索 */
    val rules: List<Quotes.Rule>
        get() = emptyList()

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("traits", JSONArray(traitIds))
        .put("extraLines", JSONArray(extraLines))
        .put("extraStyle", extraStyle)
        .put("greeting", greeting)

    companion object {
        fun fromJson(o: JSONObject, traits: TraitStore, builtin: Boolean): Character {
            val traitIds = ArrayList<String>()
            o.optJSONArray("traits")?.let { a ->
                for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { traitIds.add(it) }
            }
            val extra = ArrayList<String>()
            o.optJSONArray("extraLines")?.let { a ->
                for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { extra.add(it) }
            }
            var extraStyle = o.optString("extraStyle", "")
            // 兼容 2.0 建的人物：那时候没有性格，只有一整段风格和口头禅
            if (traitIds.isEmpty() && o.has("lines")) {
                o.optJSONArray("lines")?.let { a ->
                    for (i in 0 until a.length()) a.optString(i).trim().takeIf { it.isNotEmpty() }?.let { extra.add(it) }
                }
                if (extraStyle.isBlank()) extraStyle = o.optString("style", "")
            }
            return Character(
                id = o.optString("id", "").ifBlank { "u" + System.currentTimeMillis() },
                name = o.optString("name", "自定义").ifBlank { "自定义" },
                traitIds = traitIds,
                extraLines = extra.distinct(),
                extraStyle = extraStyle,
                greeting = o.optString("greeting", ""),
                builtin = builtin,
                traits = traits.byIds(traitIds)
            )
        }
    }
}

/** 角色仓库：一个默认角色 + 用户自己拼的 */
class CharacterStore(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("input", Context.MODE_PRIVATE)
    private val userFile = File(app.filesDir, "characters.json")
    private val traitStore = TraitStore(app)

    val builtins: List<Character> = listOf(
        compose(
            id = "default",
            name = "小暖",
            traitIds = listOf("wenrou", "youmo"),
            extraLines = emptyList(),
            extraStyle = "",
            greeting = "慢点说，我在听。",
            builtin = true
        )
    )

    val users = ArrayList<Character>()

    var activeId: String = prefs.getString("character", "") ?: ""

    init {
        loadUsers()
        if (find(activeId) == null) activeId = builtins.first().id
    }

    fun traits(): List<Trait> = traitStore.all

    fun compose(
        id: String,
        name: String,
        traitIds: List<String>,
        extraLines: List<String>,
        extraStyle: String,
        greeting: String,
        builtin: Boolean
    ): Character = Character(
        id = id,
        name = name.trim().ifBlank { "自定义" },
        traitIds = traitIds,
        extraLines = extraLines.map { it.trim() }.filter { it.isNotEmpty() },
        extraStyle = extraStyle.trim(),
        greeting = greeting.trim(),
        builtin = builtin,
        traits = traitStore.byIds(traitIds)
    )

    fun all(): List<Character> = builtins + users

    fun find(id: String): Character? = all().firstOrNull { it.id == id }

    fun active(): Character = find(activeId) ?: builtins.first()

    fun select(id: String) {
        if (find(id) == null) return
        activeId = id
        prefs.edit().putString("character", id).apply()
    }

    fun saveUser(ch: Character) {
        users.removeAll { it.id == ch.id }
        users.add(ch)
        persist()
    }

    fun deleteUser(id: String) {
        users.removeAll { it.id == id }
        persist()
        if (activeId == id) select(builtins.first().id)
    }

    private fun loadUsers() {
        if (!userFile.exists()) return
        try {
            val arr = JSONArray(userFile.readText())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { users.add(Character.fromJson(it, traitStore, builtin = false)) }
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