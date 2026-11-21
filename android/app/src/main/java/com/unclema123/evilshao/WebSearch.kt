package com.unclema123.evilshao

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 网页搜索。用 Bing 中国站，不需要注册、不需要 key。
 *
 * 只拿标题和摘要是不够的——Bing 的摘要往往只有七八十个字，还可能是几个月前的缓存，
 * 模型拿这点碎片只能瞎猜。所以这里分两步：
 *   1. search()  拿结果列表（标题 + 摘要 + 链接）
 *   2. enrich()  把前几条的网页正文抓下来（去标签，留前 900 字）
 * 正文抓失败就留空，不影响整体，模型至少还有标题和摘要。
 */
class WebSearch {

    data class Hit(
        val title: String,
        val snippet: String,
        val url: String,
        val body: String = ""
    )

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
                "https://cn.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8") +
                    "&ensearch=0&count=20&mkt=zh-CN"
            )
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 15000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                lastError = "搜索返回 HTTP $code"
                return emptyList()
            }
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            var hits = parse(html, limit)
            // 结构一变就一条都解析不出来，这里兜底：直接扫 h2 > a
            if (hits.isEmpty()) hits = parseLoose(html, limit)
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

    /**
     * 抓前 n 条的正文。逐条串行，每条最多 maxChars 字。
     * 已经抓过的（body 非空）跳过。
     */
    fun enrich(hits: List<Hit>, n: Int = 3, maxChars: Int = 900): List<Hit> {
        if (hits.isEmpty()) return hits
        return hits.mapIndexed { i, h ->
            if (i >= n || h.body.isNotEmpty()) h else h.copy(body = article(h.url, maxChars))
        }
    }

    /**
     * 并行抓前 n 条的正文，带总预算时间。
     *
     * 串行抓 3 篇最坏是 3 × (5s 连接 + 8s 读取) = 39 秒，用户早就等烦了。
     * 这里开 3 条线程同时抓，谁先回来算谁的，超时的直接留空——
     * 少一篇正文比让她「查」一分多钟强。
     *
     * @param budgetMs 整个抓取阶段最多花多少毫秒
     */
    fun enrichParallel(hits: List<Hit>, n: Int = 3, maxChars: Int = 900, budgetMs: Long = 12000): List<Hit> {
        if (hits.isEmpty()) return hits
        val todo = hits.take(n).count { it.body.isEmpty() }
        if (todo == 0) return hits

        val pool = Executors.newFixedThreadPool(minOf(todo, 3)) { r ->
            Thread(r, "web-enrich").apply { isDaemon = true }
        }
        return try {
            val deadline = System.currentTimeMillis() + budgetMs
            val futures = hits.mapIndexed { i, h ->
                if (i >= n || h.body.isNotEmpty()) null
                else pool.submit(Callable { h.copy(body = article(h.url, maxChars)) })
            }
            hits.mapIndexed { i, h ->
                val f = futures[i]
                if (f == null) return@mapIndexed h
                val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(1)
                try {
                    f.get(remain, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    f.cancel(true)
                    h
                }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** 抓单个网页的正文。失败返回空串，不抛异常。 */
    fun article(url: String, maxChars: Int = 900): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
            }
            if (conn.responseCode !in 200..299) return ""
            val type = conn.contentType ?: ""
            if (type.isNotEmpty() && !type.contains("html", true) && !type.contains("text", true)) return ""

            // 只读前 300KB，别为了查一句话把整个视频站拖下来
            val buf = ByteArrayOutputStream()
            conn.inputStream.use { ins ->
                val tmp = ByteArray(8192)
                while (buf.size() < 300_000) {
                    val n = ins.read(tmp)
                    if (n <= 0) break
                    buf.write(tmp, 0, n)
                }
            }
            val bytes = buf.toByteArray()
            extractText(String(bytes, charsetOf(type, bytes)), maxChars)
        } catch (_: Exception) {
            ""
        } finally {
            try {
                conn?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    /** 从 Content-Type 或 <meta charset> 里认编码，国内站不少是 GBK */
    private fun charsetOf(contentType: String, bytes: ByteArray): Charset {
        val fromHeader = Regex("charset=[\"']?([\\w\\-]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val fromMeta = Regex("<meta[^>]+charset=[\"']?([\\w\\-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.groupValues?.get(1)
        val name = (fromHeader ?: fromMeta ?: "utf-8").lowercase()
        return try {
            when (name) {
                "gb2312", "gbk", "gb18030" -> Charset.forName("GBK")
                else -> Charset.forName(name)
            }
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    /** 去脚本、去标签、压空白，留下能读的正文 */
    private fun extractText(html: String, maxChars: Int): String {
        var s = html
        val dot = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        s = s.replace(Regex("<script.*?</script>", dot), " ")
        s = s.replace(Regex("<style.*?</style>", dot), " ")
        s = s.replace(Regex("<noscript.*?</noscript>", dot), " ")
        s = s.replace(Regex("<!--.*?-->", dot), " ")
        // 导航栏、侧边栏、页脚里全是推荐链接，混进正文会带偏模型
        s = s.replace(Regex("<(nav|header|footer|aside)\\b.*?</\\1>", dot), " ")
        // 列表项基本都是「推荐阅读」「相关链接」，整段干掉。
        // 正文一般在 <p> 和表格里，所以只剔 ul/ol 不会伤到内容。
        s = s.replace(Regex("<(ul|ol)\\b.*?</\\1>", dot), " ")
        s = s.replace(
            Regex(
                "<(div|ul|section)[^>]*(id|class)=\"[^\"]*" +
                    "(nav|menu|footer|sidebar|breadcrumb|banner|related|recommend|share|advert|comment)" +
                    "[^\"]*\"[^>]*>.*?</\\1>",
                dot
            ),
            " "
        )
        // 块级标签断行，否则整页会连成一坨
        s = s.replace(
            Regex("</(p|div|section|article|li|h[1-6]|tr|br|table)[^>]*>", RegexOption.IGNORE_CASE),
            "\n"
        )
        s = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = clean(s)

        // 逐行过滤：太短的（导航、按钮、版权）扔掉
        val lines = s.split('\n').map { it.trim() }.filter { it.length >= 12 }
        val sb = StringBuilder()
        for (line in lines) {
            if (sb.length >= maxChars) break
            sb.append(line).append('。')
        }
        var out = sb.toString().replace("。。", "。")
        if (out.length > maxChars) out = out.substring(0, maxChars)
        return out.trim()
    }

    private fun parse(html: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val blockRe = Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL)
        val titleRe = Regex("<h2[^>]*>\\s*<a[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL)
        val urlRe = Regex("href=\"(https?://[^\"]+)\"")
        // 摘要在 p.b_lineclamp*，也有页面用 div.b_caption
        val snippetRe = Regex(
            "<(?:p|div)[^>]*class=\"[^\"]*b_(?:lineclamp|snippet|factrow|caption)[^\"]*\"[^>]*>(.*?)</(?:p|div)>",
            RegexOption.DOT_MATCHES_ALL
        )
        val anyP = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)

        for (b in blockRe.findAll(html)) {
            val block = b.value
            val titleRaw = titleRe.find(block)?.groupValues?.get(1) ?: continue
            val url = urlRe.find(block)?.groupValues?.get(1) ?: continue
            if (url.contains("bing.com") || url.contains("microsoft.com")) continue

            val title = clean(titleRaw)
            if (title.isEmpty()) continue
            val rawSnippet = snippetRe.find(block)?.groupValues?.get(1)
                ?: anyP.find(block)?.groupValues?.get(1) ?: ""
            val snippet = clean(rawSnippet).take(240)
            out.add(Hit(title, snippet, url))
            if (out.size >= limit) break
        }
        return out
    }

    /** b_algo 一个都没匹配上时的兜底：全局扫 h2 > a */
    private fun parseLoose(html: String, limit: Int): List<Hit> {
        val out = ArrayList<Hit>()
        val re = Regex(
            "<h2[^>]*>\\s*<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL
        )
        for (m in re.findAll(html)) {
            val url = m.groupValues[1]
            if (url.contains("bing.com") || url.contains("microsoft.com")) continue
            val title = clean(m.groupValues[2])
            if (title.isEmpty()) continue
            out.add(Hit(title, "", url))
            if (out.size >= limit) break
        }
        return out
    }

    /** 去标签 + 解常见实体 + 压空白 */
    private fun clean(raw: String): String {
        var s = raw.replace(Regex("<[^>]+>"), "")
        s = s.replace("&ensp;", " ").replace("&nbsp;", " ").replace("&emsp;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
            .replace("&hellip;", "…").replace("&mdash;", "—").replace("&ldquo;", "“")
            .replace("&rdquo;", "”").replace("&middot;", "·")
        s = s.replace(Regex("&#(\\d+);")) { m ->
            val code = m.groupValues[1].toIntOrNull() ?: return@replace ""
            if (code in 32..0xFFFF) code.toChar().toString() else ""
        }
        return s.replace(Regex("[\\t\\r\\f\\u000B]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    }
}