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

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    fun isConfigured(): Boolean =
        enabled && apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank()

    /** 最近一次失败的原始原因，界面上能看到 */
    @Volatile
    var lastError: String? = null
        private set

    /** 返回模型回复；任何异常返回 null */
    fun chat(system: String, user: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(baseUrl.trimEnd('/') + "/chat/completions")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 20000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }

            val messages = JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user))

            val body = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.95)
                .put("max_tokens", 96)
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
                lastError = "HTTP $code：${text.take(120)}"
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

        /** 预设供应商，方便切换 */
        val PRESETS = listOf(
            Triple("智谱 GLM-4-Flash（免费）", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            Triple("硅基流动 Qwen2.5-7B（免费额度）", "https://api.siliconflow.cn/v1", "Qwen/Qwen2.5-7B-Instruct"),
            Triple("DeepSeek Chat（便宜，非免费）", "https://api.deepseek.com/v1", "deepseek-chat")
        )
    }
}