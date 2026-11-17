package com.unclema123.evilshao

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 网页搜索。用 Bing 中国站，不需要注册、不需要 key。
 *
 * 只取标题和摘要（不下载网页正文），够喂给模型判断事实了，
 * 流量小、速度快，也不会把整站抓下来。
 */
class WebSearch {

    class Hit(val title: String, val snippet: String, val url: String)

    /** 最近一次失败原因，界面上能看到 */
    @Volatile
    var lastError: String? = null
        private set

    fun search(query: String, limit: Int = 5): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(
                "https://cn.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8") + "&ensearch=0"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
                )
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                lastError = "搜索返回 HTTP $code"
                return emptyList()
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val hits = parse(html, limit)
            if (hits.isEmpty()) lastError = "没有解析到结果（页面结构可能变了）" else lastError = null
            hits
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            emptyList()
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun parse(html: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val blockRe = Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
        val titleRe = Regex("<h2[^>]*>\\s*<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val urlRe = Regex("href=\"(https?://[^\"]+)\"")
        val snippetRe = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

        for (b in blockRe.findAll(html)) {
            val block = b.value
            val titleRaw = titleRe.find(block)?.groupValues?.get(1) ?: continue
            val url = urlRe.find(block)?.groupValues?.get(1) ?: continue
            if (url.contains("bing.com") || url.contains("microsoft.com")) continue

            val title = clean(titleRaw)
            if (title.isEmpty()) continue
            val snippet = clean(snippetRe.find(block)?.groupValues?.get(1) ?: "")
            out.add(Hit(title, snippet, url))
            if (out.size >= limit) break
        }
        return out
    }

    /** 去标签 + 解常见实体 + 压空白 */
    private fun clean(raw: String): String {
        var s = raw.replace(Regex("<[^>]+>"), "")
        s = s.replace("&ensp;", " ").replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        s = s.replace(Regex("&#(\\d+);")) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace ""
            if (code in 32..0xFFFF) code.toChar().toString() else ""
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }
}