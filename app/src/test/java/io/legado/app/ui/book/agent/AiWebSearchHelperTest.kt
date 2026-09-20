package io.legado.app.ui.book.agent

import io.legado.app.data.entities.AiSearchSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiWebSearchHelperTest {

    @Test
    fun `Tavily 请求固定使用当前查询与数量`() {
        val source = AiSearchSource(
            type = AiSearchSource.TYPE_TAVILY,
            customBody = """{"query":"old","max_results":99,"topic":"news"}"""
        )

        val body = AiWebSearchHelper.buildJsonBody(source, "Android 16", 5, "week")

        assertEquals("Android 16", body.get("query").asString)
        assertEquals(5, body.get("max_results").asInt)
        assertEquals("week", body.get("time_range").asString)
        assertEquals("news", body.get("topic").asString)
    }

    @Test
    fun `Brave GET 请求包含过滤参数`() {
        val source = AiSearchSource(
            type = AiSearchSource.TYPE_BRAVE,
            baseUrl = AiSearchSource.defaultBaseUrl(AiSearchSource.TYPE_BRAVE),
            queryParameter = "q",
            language = "zh",
            country = "CN",
            safeSearch = true
        )

        val url = AiWebSearchHelper.buildGetUrl(source, source.baseUrl, "最新消息", 8, "day")

        assertTrue(url.contains("q=%E6%9C%80%E6%96%B0%E6%B6%88%E6%81%AF"))
        assertTrue(url.contains("count=8"))
        assertTrue(url.contains("freshness=pd"))
        assertTrue(url.contains("safesearch=strict"))
    }

    @Test
    fun `解析 SearXNG 搜索结果并过滤非网页链接`() {
        val source = AiSearchSource(type = AiSearchSource.TYPE_SEARXNG)
        val body = """
            {"results":[
              {"title":"结果一","url":"https://example.com/a","content":"摘要","publishedDate":"2026-09-18"},
              {"title":"本地文件","url":"file:///tmp/a","content":"忽略"}
            ]}
        """.trimIndent()

        val results = AiWebSearchHelper.parseResponse(source, body, 5)

        assertEquals(1, results.size)
        assertEquals("结果一", results.first().title)
        assertEquals("example.com", results.first().source)
        assertEquals("2026-09-18", results.first().publishedAt)
    }

    @Test
    fun `自定义响应支持点路径映射`() {
        val source = AiSearchSource(
            type = AiSearchSource.TYPE_CUSTOM,
            resultPath = "data.items",
            titlePath = "metadata.name",
            urlPath = "link",
            snippetPath = "summary",
            publishedAtPath = ""
        )
        val body = """
            {"data":{"items":[{
              "metadata":{"name":"嵌套标题"},
              "link":"https://example.org/result",
              "summary":"嵌套摘要"
            }]}}
        """.trimIndent()

        val results = AiWebSearchHelper.parseResponse(source, body, 5)

        assertEquals(1, results.size)
        assertEquals("嵌套标题", results.first().title)
        assertFalse(results.first().snippet.isBlank())
    }

    @Test
    fun `配置多个 API Key 时按顺序轮询且兼容多种分隔符`() {
        val source = AiSearchSource(
            id = 202609201L,
            type = AiSearchSource.TYPE_CUSTOM,
            apiKey = "key-a\nkey-b, key-c;key-d"
        )

        assertEquals(listOf("key-a", "key-b", "key-c", "key-d"), source.getApiKeyList())
        assertEquals("key-a", AiWebSearchHelper.nextApiKey(source))
        assertEquals("key-b", AiWebSearchHelper.nextApiKey(source))
        assertEquals("key-c", AiWebSearchHelper.nextApiKey(source))
        assertEquals("key-d", AiWebSearchHelper.nextApiKey(source))
        assertEquals("key-a", AiWebSearchHelper.nextApiKey(source))
    }

    @Test
    fun `只配置一个 API Key 时始终返回同一个`() {
        val source = AiSearchSource(id = 202609202L, apiKey = " only-key ")

        assertEquals(listOf("only-key"), source.getApiKeyList())
        assertEquals("only-key", AiWebSearchHelper.nextApiKey(source))
        assertEquals("only-key", AiWebSearchHelper.nextApiKey(source))
    }

    @Test
    fun `未配置 API Key 时返回 null`() {
        val source = AiSearchSource(id = 202609203L, apiKey = "  \n , ; ")

        assertTrue(source.getApiKeyList().isEmpty())
        assertNull(AiWebSearchHelper.nextApiKey(source))
    }
}
