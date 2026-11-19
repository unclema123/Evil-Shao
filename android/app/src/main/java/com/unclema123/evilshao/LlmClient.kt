package com.unclema123.evilshao

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 接大模型（OpenAI 兼容的 /chat/completions 接口）。
 *
 * 兼容性：智谱 GLM、硅基流动、DeepSeek、Moonshot、OpenRouter 都是这个格式，
 * 只要改 baseUrl 和 model 就能换供应商。
 *
 * 设计原则：失败一律返回 null，由调用方回退到本地引擎 —— 没网也不能变哑巴。
 */
class LlmClient(context: Context) {

    private val prefs = context.getSharedPreferences("llm", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var baseUrl: String
        get() = prefs.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE
        set(v) = prefs.edit().putString("baseUrl", v.trim()).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()

    /** 免 Key 模式：不发送 Authorization 头，用于 Pollinations 这类公开接口 */
    var keyless: Boolean
        get() = prefs.getBoolean("keyless", false)
        set(v) = prefs.edit().putBoolean("keyless", v).apply()

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    /** 联网搜索开关：查不到就上网找，找到会存进本地知识库 */
    var searchEnabled: Boolean
        get() = prefs.getBoolean("search", true)
        set(v) = prefs.edit().putBoolean("search", v).apply()

    fun isConfigured(): Boolean =
        enabled && baseUrl.isNotBlank() && model.isNotBlank() && (apiKey.isNotBlank() || keyless)

    /** 最近一次失败的原始原因，界面上能看到 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 返回模型回复；任何异常返回 null。
     *
     * 温度和长度要分开传：聊天要 0.95 才有那股跳脱劲儿，
     * 但查资料必须接近 0，不然模型会拿资料"发挥"。
     *
     * history 是之前的对话（role 只能是 user / assistant），按时间正序。
     * 拼在 system 之后、当前这句之前，模型才接得住「那明天呢」这种话。
     */
    fun chat(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        temperature: Double = 0.95,
        maxTokens: Int = 96,
        readTimeoutMs: Int = 20000
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = readTimeoutMs
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer $apiKey")
                }
            }

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
            for ((role, content) in history) {
                if (content.isBlank()) continue
                messages.put(JSONObject().put("role", role).put("content", content))
            }
            messages.put(JSONObject().put("role", "user").put("content", user))

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .put("stream", false)
                .toString()

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: run {
                lastError = "HTTP $code 无响应体"
                return null
            }
            if (code !in 200..299) {
                lastError = explain(code, text)
                return null
            }

            val obj = JSONObject(text)
            val choices = obj.optJSONArray("choices") ?: run {
                lastError = "返回里没有 choices：${text.take(120)}"
                return null
            }
            if (choices.length() == 0) {
                lastError = "choices 为空"
                return null
            }
            val msg = choices.getJSONObject(0).optJSONObject("message") ?: run {
                lastError = "没有 message 字段"
                return null
            }
            val content = msg.optString("content", "")
            lastError = null
            content.trim().ifEmpty {
                lastError = "内容为空"
                null
            }
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            android.util.Log.e("EvilShao", "LLM 调用失败", e)
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val DEFAULT_BASE = "https://open.bigmodel.cn/api/paas/v4"
        const val DEFAULT_MODEL = "glm-4-flash"

        /**
         * 把接口返回的英文错误翻成人话。
         * 免费额度最常见的两种是「45 秒 1 条」和高峰期的 503，
         * 直接甩一坨 JSON 出来没人看得懂，还会被当成「她不搭理我」。
         */
        fun explain(code: Int, body: String): String = when {
            code == 401 -> "Key 无效或过期（HTTP 401）"
            code == 402 && body.contains("FREE_LIMIT") -> "免费额度限流：每 45 秒只能问一次"
            code == 402 -> "需要付费（HTTP 402）"
            code == 429 -> "问得太快，被限流了（HTTP 429）"
            code == 404 -> "接口地址不对，检查 baseUrl（HTTP 404）"
            code == 503 || body.contains("SERVICE_UNAVAILABLE") ->
                "服务端繁忙，过一会儿再问（HTTP 503）"
            else -> "HTTP $code：${body.take(120)}"
        }

        /** 预设供应商，方便切换 */
        val PRESETS = listOf(
            Triple("智谱 GLM-4-Flash（免费）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            Triple("硅基流动 Qwen2.5-7B（免费额度）", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
            Triple("DeepSeek Chat（便宜，非免费）", "https://api.deepseek.com/v1", "deepseek-chat")
        )

        /** 不需要填 Key 的公开接口 */
        const val KEYLESS_BASE = "https://text.pollinations.ai/openai"
        const val KEYLESS_MODEL = "openai"
    }
}