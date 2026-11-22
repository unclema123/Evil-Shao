package com.unclema123.evilshao

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 接大模型（OpenAI 兼容的 /chat/completions 接口）。
 *
 * 兼容性：智谱 GLM、硅基流动、DeepSeek、Moonshot、OpenRouter 都是这个格式，
 * 只要改 baseUrl 和 model 就能换供应商。
 *
 * 设计原则：失败一律返回 null，由调用方回退到本地引擎 —— 没网也不能变哑巴。
 *
 * v1.1 起加了四件事（都是对着开源实现抄的作业）：
 *  1. **流式输出** [chatStream]：SSE 逐块回调，气泡能一个字一个字往外蹦。
 *     参考 openai-kotlin（aallam）的 SSE 解析：只认 `data:` 行，`[DONE]` 结束。
 *  2. **自动重试**：429/5xx/超时重试一次。免费额度「45 秒 1 条」是常态，
 *     以前一次失败就掉回本地语录，看起来像她突然不理人。
 *  3. **复读抑制**：frequency_penalty / presence_penalty，对应 OpenAI 文档里的
 *     频率惩罚和存在惩罚，小模型连说三遍同一句话时很有效。
 *  4. **流式失败自动回退**：有些免费接口不支持 stream，返回的是普通 JSON，
 *     这里识别出来直接当非流式用，不会白等。
 */
class LlmClient(context: Context) {

    private val prefs = context.getSharedPreferences("llm", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    var baseUrl: String
        get() = prefs.getString("baseUrl", KEYLESS_BASE) ?: KEYLESS_BASE
        set(v) = prefs.edit().putString("baseUrl", v.trim()).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()

    /** 免 Key 模式：不发送 Authorization 头，用于 Pollinations 这类公开接口 */
    var keyless: Boolean
        get() = prefs.getBoolean("keyless", true)
        set(v) = prefs.edit().putBoolean("keyless", v).apply()

    var model: String
        get() = prefs.getString("model", KEYLESS_MODEL) ?: KEYLESS_MODEL
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    /** 联网搜索开关：查不到就上网找，找到会存进本地知识库 */
    var searchEnabled: Boolean
        get() = prefs.getBoolean("search", true)
        set(v) = prefs.edit().putBoolean("search", v).apply()

    /**
     * 主接口挂了自动换免 Key 公开接口。
     *
     * 换来的是「她不会突然哑掉」，代价是这句话会发给另一个公开服务，
     * 所以做成开关：默认开，介意的话可以关掉。
     */
    var autoFallback: Boolean
        get() = prefs.getBoolean("autoFallback", true)
        set(v) = prefs.edit().putBoolean("autoFallback", v).apply()

    /** 刚失败的接口，冷却期内排到最后，别每次都从坏的那个开始撞 */
    private val cooldownUntil = HashMap<String, Long>()

    /** 上一次成功的接口 id，下一轮先试它 */
    @Volatile
    private var lastGoodId: String? = null

    /** 用户当前填的那条（没填全就返回 null） */
    private fun userProvider(): LlmProviders.P? {
        val base = baseUrl.trim()
        val m = model.trim()
        if (base.isEmpty() || m.isEmpty()) return null
        if (!keyless && apiKey.isBlank()) return null
        return LlmProviders.P("当前设置", base, m, apiKey, keyless)
    }

    /** 这一轮要依次试的接口；冷却中的排最后，但全冷却时仍然硬试，不能整机哑掉 */
    private fun providers(): List<LlmProviders.P> {
        val user = userProvider()
        val free = if (autoFallback) LlmProviders.FREE else emptyList()
        if (user == null && free.isEmpty()) return emptyList()
        val plan = LlmProviders.plan(user, lastGoodId, free)
        val now = System.currentTimeMillis()
        return plan.sortedBy { if ((cooldownUntil[it.id] ?: 0L) > now) 1 else 0 }
    }

    /** 给界面上看的：这一轮大概会走哪几条 */
    fun planSummary(): String {
        val list = providers()
        return if (list.isEmpty()) "没有可用接口" else list.joinToString(" → ") { it.label }
    }

    fun isConfigured(): Boolean = enabled && providers().isNotEmpty()

    /** 最近一次失败的原始原因，界面上能看到 */
    @Volatile
    var lastError: String? = null
        private set

    /** 上一次调用实际用了几次尝试（1 = 一次就成） */
    @Volatile
    var lastAttempts: Int = 0
        private set

    // ────────────────────────────── 非流式 ──────────────────────────────

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
        readTimeoutMs: Int = 20000,
        frequencyPenalty: Double = 0.0,
        presencePenalty: Double = 0.0,
        retries: Int = 1
    ): String? {
        val list = providers()
        if (list.isEmpty()) {
            lastError = "没有可用的接口"
            return null
        }
        lastAttempts = 0
        var firstError: String? = null
        for (p in list) {
            var attempt = 0
            while (true) {
                attempt++
                lastAttempts++
                val r = once(p, system, user, history, temperature, maxTokens, readTimeoutMs,
                    frequencyPenalty, presencePenalty)
                if (r != null) {
                    lastGoodId = p.id
                    cooldownUntil.remove(p.id)
                    if (p.id != list.first().id) {
                        android.util.Log.d("EvilShao", "已切到备用接口：${p.label}")
                    }
                    return r
                }
                if (firstError == null) firstError = lastError
                if (attempt > retries || !retryable(lastError)) break
                sleepBeforeRetry(attempt)
            }
            cooldownUntil[p.id] = System.currentTimeMillis() + COOLDOWN_MS
            android.util.Log.d("EvilShao", "接口不可用，换下一个：${p.label}（$lastError）")
        }
        // 全挂了：报第一个的真实原因，别让最后一条把它盖成别的
        lastError = firstError ?: lastError
        return null
    }

    private fun once(
        p: LlmProviders.P,
        system: String,
        user: String,
        history: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        readTimeoutMs: Int,
        frequencyPenalty: Double,
        presencePenalty: Double
    ): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(p, "/chat/completions", readTimeoutMs)
            val body = JSONObject()
                .put("model", p.model)
                .put("messages", buildMessages(system, user, history))
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .put("stream", false)
                .also { if (frequencyPenalty > 0) it.put("frequency_penalty", frequencyPenalty) }
                .also { if (presencePenalty > 0) it.put("presence_penalty", presencePenalty) }
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
            lastError = null
            extractContent(text)
        } catch (e: Exception) {
            lastError = describeException(e)
            null
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    // ────────────────────────────── 流式 ──────────────────────────────

    /**
     * 流式对话：每收到一段就回调一次完整文本（不是增量，调用方直接拿来显示）。
     *
     * @param onDelta 每段回调，**在主线程还是工作线程不确定**，由调用方自己切
     * @return 完整回复；失败且一个字都没收到时返回 null
     */
    fun chatStream(
        system: String,
        user: String,
        history: List<Pair<String, String>> = emptyList(),
        temperature: Double = 0.95,
        maxTokens: Int = 96,
        readTimeoutMs: Int = 20000,
        frequencyPenalty: Double = 0.0,
        presencePenalty: Double = 0.0,
        retries: Int = 1,
        onDelta: (String) -> Unit
    ): String? {
        val list = providers()
        if (list.isEmpty()) {
            lastError = "没有可用的接口"
            return null
        }
        lastAttempts = 0
        var firstError: String? = null
        for (p in list) {
            var attempt = 0
            while (true) {
                attempt++
                lastAttempts++
                val sb = StringBuilder()
                val ok = streamOnce(p, system, user, history, temperature, maxTokens,
                    readTimeoutMs, frequencyPenalty, presencePenalty) { piece ->
                    sb.append(piece)
                    onDelta(sb.toString())
                }
                if (ok && sb.isNotEmpty()) {
                    lastGoodId = p.id
                    cooldownUntil.remove(p.id)
                    if (p.id != list.first().id) {
                        android.util.Log.d("EvilShao", "已切到备用接口：${p.label}")
                    }
                    return sb.toString()
                }

                // 已经收到一半了就别换接口，换了她会从头再说一遍
                if (sb.isNotEmpty()) return sb.toString()
                if (firstError == null) firstError = lastError
                if (attempt > retries || !retryable(lastError)) break
                sleepBeforeRetry(attempt)
            }
            cooldownUntil[p.id] = System.currentTimeMillis() + COOLDOWN_MS
            android.util.Log.d("EvilShao", "接口不可用，换下一个：${p.label}（$lastError）")
        }
        lastError = firstError ?: lastError
        return null
    }

    /**
     * @return true 表示走完了流（可能有内容也可能没有）；false 表示连接/HTTP 层失败
     */
    private fun streamOnce(
        p: LlmProviders.P,
        system: String,
        user: String,
        history: List<Pair<String, String>>,
        temperature: Double,
        maxTokens: Int,
        readTimeoutMs: Int,
        frequencyPenalty: Double,
        presencePenalty: Double,
        onDelta: (String) -> Unit
    ): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(p, "/chat/completions", readTimeoutMs)
            val body = JSONObject()
                .put("model", p.model)
                .put("messages", buildMessages(system, user, history))
                .put("temperature", temperature)
                .put("max_tokens", maxTokens)
                .put("stream", true)
                .also { if (frequencyPenalty > 0) it.put("frequency_penalty", frequencyPenalty) }
                .also { if (presencePenalty > 0) it.put("presence_penalty", presencePenalty) }
                .toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                lastError = explain(code, err)
                return false
            }

            var sawSse = false
            var events = 0
            var rawJson = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    if (!line.startsWith("data:")) {
                        // 有些不支持 stream 的接口会直接吐一整段 JSON
                        if (!sawSse && line.trimStart().startsWith("{")) {
                            rawJson.append(line).append('\n')
                        }
                        continue
                    }
                    sawSse = true
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    val piece = parseDelta(payload) ?: continue
                    if (piece.isNotEmpty()) {
                        events++
                        onDelta(piece)
                    }
                }
            }

            if (!sawSse && rawJson.isNotEmpty()) {
                // 整个响应就是一段普通 JSON，按非流式处理
                val content = extractContent(rawJson.toString())
                if (content != null) {
                    onDelta(content)
                    lastError = null
                    return true
                }
                return false
            }
            lastError = null
            android.util.Log.d("EvilShao", "SSE 结束：增量块=$events 走了流=$sawSse")
            true
        } catch (e: SocketTimeoutException) {
            lastError = "读取超时（${readTimeoutMs / 1000} 秒）"
            false
        } catch (e: Exception) {
            lastError = describeException(e)
            false
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 从一条 SSE 的 data 负载里取 delta.content */
    private fun parseDelta(payload: String): String? {
        return try {
            val obj = JSONObject(payload)
            val choices = obj.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val first = choices.getJSONObject(0)
            val delta = first.optJSONObject("delta")
            if (delta != null) {
                val c = delta.optString("content", "")
                if (c.isNotEmpty()) return c
            }
            // 少数接口把内容放在 message 里
            first.optJSONObject("message")?.optString("content", "")?.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    // ────────────────────────────── 公共零件 ──────────────────────────────

    private fun open(p: LlmProviders.P, path: String, readTimeoutMs: Int): HttpURLConnection {
        val url = URL(p.base.trimEnd('/') + path)
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            this.readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream, application/json")
            // 关掉连接池复用带来的 keep-alive 半死连接问题：免费接口经常直接断开
            setRequestProperty("Connection", "close")
            // 只有用户自己的接口才带 Key；免 Key 的公开接口带了反而会被拒
            if (!p.keyless && p.key.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer ${p.key}")
            }
        }
    }

    private fun buildMessages(
        system: String,
        user: String,
        history: List<Pair<String, String>>
    ): JSONArray {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
        for ((role, content) in history) {
            if (content.isBlank()) continue
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        messages.put(JSONObject().put("role", "user").put("content", user))
        return messages
    }

    /** 从非流式响应里取 choices[0].message.content */
    private fun extractContent(text: String): String? {
        return try {
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
            val content = msg.optString("content", "").trim()
            if (content.isEmpty()) {
                lastError = "内容为空"
                null
            } else {
                content
            }
        } catch (e: Exception) {
            lastError = "解析失败：${e.javaClass.simpleName}"
            null
        }
    }

    /** 这类错误值得再试一次；401/402/404 试多少次都一样 */
    private fun retryable(err: String?): Boolean {
        val e = err ?: return true
        return e.contains("429") || e.contains("500") || e.contains("502") ||
            e.contains("503") || e.contains("504") || e.contains("超时") ||
            e.contains("限流") || e.contains("繁忙") ||
            e.contains("SocketTimeout") || e.contains("ConnectException") ||
            e.contains("UnknownHost") || e.contains("SocketException")
    }

    private fun sleepBeforeRetry(attempt: Int) {
        try {
            // 600ms、1200ms… 不去吓唬服务端，但也要给限流一点恢复时间
            Thread.sleep(600L * attempt)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun describeException(e: Exception): String = when (e) {
        is SocketTimeoutException -> "读取超时"
        is IOException -> "${e.javaClass.simpleName}: ${e.message}"
        else -> "${e.javaClass.simpleName}: ${e.message}"
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

        /** 不需要填 Key 的公开接口（真正的列表在 LlmProviders.FREE 里） */
        const val KEYLESS_BASE = LlmProviders.KEYLESS_BASE
        const val KEYLESS_MODEL = LlmProviders.KEYLESS_MODEL

        /** 一个接口刚失败，多久之内不再优先试它（1 分钟） */
        private const val COOLDOWN_MS = 60_000L
    }
}