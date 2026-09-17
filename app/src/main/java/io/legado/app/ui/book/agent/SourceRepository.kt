package io.legado.app.ui.book.agent

import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.nio.charset.Charset

data class SourceRepositoryItem(
    val id: Long,
    val name: String,
    val sourceUrl: String,
    val version: String,
    val capabilities: List<String>,
    val author: String,
    val downloads: Int,
    val updatedAt: String
) {
    val jsonUrl: String
        get() = SourceRepository.jsonUrl(id)

    fun toToolResult(): Map<String, Any> = mapOf(
        "id" to id,
        "name" to name,
        "sourceUrl" to sourceUrl,
        "version" to version,
        "capabilities" to capabilities,
        "author" to author,
        "downloads" to downloads,
        "updatedAt" to updatedAt
    )
}

/**
 * 阅读书源仓库搜索。所有请求地址均在本地拼装，避免模型提供任意下载地址。
 */
object SourceRepository {

    private const val BASE_URL = "https://www.yckceo.com"
    private const val SEARCH_PATH = "/yuedu/shuyuan/index.html"
    private const val MAX_RESPONSE_BYTES = 1024 * 1024L
    private const val REQUEST_TIMEOUT_MS = 20_000L

    suspend fun search(rawQuery: String, limit: Int): List<SourceRepositoryItem> {
        val query = normalizeQuery(rawQuery)
        if (query.isBlank()) return emptyList()
        val url = "$BASE_URL$SEARCH_PATH".toHttpUrl().newBuilder()
            .addQueryParameter("keys", query)
            .addQueryParameter("ver", "3")
            .addQueryParameter("order1", "down")
            .addQueryParameter("order2", "1")
            .build()
        val html = withTimeout(REQUEST_TIMEOUT_MS) {
            fetchText(url.toString())
        }
        return parseSearchResults(html, limit.coerceIn(1, 20))
    }

    fun jsonUrl(id: Long): String {
        require(id > 0) { "书源 ID 无效" }
        return "$BASE_URL/yuedu/shuyuan/json/id/$id.json"
    }

    internal fun normalizeQuery(rawQuery: String): String {
        val cleaned = rawQuery.trim()
            .replace(Regex("""\\([./:_-])"""), "$1")
            .trimEnd('/')
        if ('.' in cleaned && cleaned.none(Char::isWhitespace)) {
            val candidate = if (cleaned.startsWith("http://", true) ||
                cleaned.startsWith("https://", true)
            ) {
                cleaned
            } else {
                "https://$cleaned"
            }
            candidate.toHttpUrlOrNull()?.host?.let { return it }
        }
        return cleaned
    }

    internal fun parseSearchResults(html: String, limit: Int): List<SourceRepositoryItem> {
        if (html.isBlank()) return emptyList()
        return Jsoup.parse(html, BASE_URL)
            .select(".ylist")
            .asSequence()
            .mapNotNull(::parseCard)
            .distinctBy { it.id }
            .take(limit.coerceIn(1, 20))
            .toList()
    }

    private fun parseCard(card: Element): SourceRepositoryItem? {
        val id = card.selectFirst("input.class_one")?.attr("value")?.toLongOrNull()
            ?: card.selectFirst("a[href*=/content/id/]")
                ?.attr("href")
                ?.let { CONTENT_ID.find(it)?.groupValues?.getOrNull(1)?.toLongOrNull() }
            ?: return null
        if (id <= 0) return null

        val title = card.selectFirst("h2 > a")?.text()?.trim().orEmpty()
        if (title.isBlank()) return null
        val sourceUrl = URL.find(title)?.value?.trimEnd('，', ',', '。', '.', '；', ';', ')', '）')
            .orEmpty()
        val name = if (sourceUrl.isBlank()) {
            title
        } else {
            title.substringBefore(sourceUrl).trim().trimEnd('：', ':', '-', '—')
                .ifBlank { title }
        }
        val badges = card.select("span").map { it.text().trim() }.filter { it.isNotBlank() }
        val version = badges.firstOrNull { VERSION.matches(it) }.orEmpty()
        val capabilityText = badges.firstOrNull {
            it.length <= 10 && listOf("发", "搜", "图", "声").any(it::contains)
        }.orEmpty()
        val author = badges.firstOrNull { it.startsWith("用户:") || it.startsWith("用户：") }
            ?.substringAfter(':')
            ?.substringAfter('：')
            ?.trim()
            .orEmpty()
        val downloads = badges.firstOrNull { it.startsWith("下载:") || it.startsWith("下载：") }
            ?.let { DOWNLOAD_COUNT.find(it)?.value?.toIntOrNull() }
            ?: 0
        return SourceRepositoryItem(
            id = id,
            name = name,
            sourceUrl = sourceUrl,
            version = version,
            capabilities = capabilityLabels(capabilityText),
            author = author,
            downloads = downloads,
            updatedAt = card.selectFirst("h2 > p.m-right")?.text()?.trim().orEmpty()
        )
    }

    private fun capabilityLabels(text: String): List<String> = buildList {
        if ('发' in text) add("发现")
        if ('搜' in text) add("搜索")
        if ('图' in text) add("图片")
        if ('声' in text) add("有声")
    }

    private suspend fun fetchText(url: String): String {
        return okHttpClient.newCallResponse {
            url(url)
            header("User-Agent", "Legado Android")
            header("Accept", "text/html,application/xhtml+xml")
        }.use { response ->
            if (!response.isSuccessful) {
                error("书源仓库请求失败：HTTP ${response.code}")
            }
            val body = response.body
            val contentLength = body.contentLength()
            if (contentLength > MAX_RESPONSE_BYTES) {
                error("书源仓库响应过大")
            }
            val source = body.source()
            val buffer = Buffer()
            var total = 0L
            while (total <= MAX_RESPONSE_BYTES) {
                val read = source.read(buffer, minOf(8192L, MAX_RESPONSE_BYTES + 1 - total))
                if (read == -1L) break
                total += read
            }
            if (total > MAX_RESPONSE_BYTES) {
                error("书源仓库响应过大")
            }
            val charset: Charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
            buffer.readString(charset)
        }
    }

    private val CONTENT_ID = Regex("/content/id/(\\d+)")
    private val URL = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val VERSION = Regex("\\d+(?:\\.\\w+)?")
    private val DOWNLOAD_COUNT = Regex("\\d+")
}
