package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.data.entities.AiSearchSource
import io.legado.app.help.http.addHeaders
import io.legado.app.help.http.newCallStrResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.GSON
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class AiWebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val publishedAt: String? = null,
    val source: String
)

internal data class AiWebSearchResponse(
    val query: String,
    val provider: String,
    val results: List<AiWebSearchResult>
)

internal object AiWebSearchHelper {

    suspend fun search(
        source: AiSearchSource,
        query: String,
        count: Int,
        freshness: String
    ): String {
        val safeQuery = query.trim().take(MAX_QUERY_LENGTH)
        require(safeQuery.isNotBlank()) { "搜索关键词为空" }
        val safeCount = count.coerceIn(1, MAX_RESULTS)
        val client = okHttpClient.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .build()
        val headers = buildHeaders(source)
        val endpoint = endpoint(source)
        val response = client.newCallStrResponse {
            addHeaders(headers)
            if (requestMethod(source) == AiSearchSource.METHOD_GET) {
                url(buildGetUrl(source, endpoint, safeQuery, safeCount, freshness))
            } else {
                url(endpoint)
                post(
                    buildJsonBody(source, safeQuery, safeCount, freshness)
                        .toString()
                        .toRequestBody(JSON_MEDIA_TYPE)
                )
            }
        }
        val body = response.body.orEmpty()
        require(body.length <= MAX_RESPONSE_CHARS) { "搜索响应内容过大" }
        if (!response.isSuccessful()) {
            val message = extractError(body)
            throw IllegalStateException(message ?: "HTTP ${response.code()}: ${response.message()}")
        }
        val results = parseResponse(source, body, safeCount)
        return GSON.toJson(AiWebSearchResponse(safeQuery, source.name, results))
    }

    internal fun buildJsonBody(
        source: AiSearchSource,
        query: String,
        count: Int,
        freshness: String
    ): JsonObject = JsonObject().apply {
        source.customBody?.takeIf { it.isNotBlank() }?.let {
            val custom = JsonParser.parseString(it)
            require(custom.isJsonObject) { "自定义请求体必须是 JSON 对象" }
            custom.asJsonObject.entrySet().forEach { (key, value) -> add(key, value) }
        }
        when (source.type) {
            AiSearchSource.TYPE_TAVILY -> {
                addProperty("query", query)
                addProperty("max_results", count)
                addProperty("search_depth", "basic")
                addProperty("include_answer", false)
                addProperty("include_raw_content", false)
                normalizeFreshness(freshness)?.let { addProperty("time_range", it) }
            }
            else -> {
                addProperty(source.queryParameter.ifBlank { "query" }, query)
                if (!has("count") && !has("max_results")) addProperty("count", count)
                normalizeFreshness(freshness)?.let {
                    if (!has("freshness")) addProperty("freshness", it)
                }
            }
        }
    }

    internal fun buildGetUrl(
        source: AiSearchSource,
        endpoint: String,
        query: String,
        count: Int,
        freshness: String
    ): String {
        val builder = endpoint.toHttpUrl().newBuilder()
        val queryParameter = source.queryParameter.ifBlank { "q" }
        builder.setQueryParameter(queryParameter, query)
        when (source.type) {
            AiSearchSource.TYPE_BRAVE -> {
                builder.setQueryParameter("count", count.toString())
                source.language.takeIf { it.isNotBlank() }?.let {
                    builder.setQueryParameter("search_lang", it)
                }
                source.country.takeIf { it.isNotBlank() }?.let {
                    builder.setQueryParameter("country", it.uppercase())
                }
                builder.setQueryParameter("safesearch", if (source.safeSearch) "strict" else "off")
                braveFreshness(freshness)?.let { builder.setQueryParameter("freshness", it) }
            }
            AiSearchSource.TYPE_SEARXNG -> {
                builder.setQueryParameter("format", "json")
                source.language.takeIf { it.isNotBlank() }?.let {
                    builder.setQueryParameter("language", it)
                }
                builder.setQueryParameter("safesearch", if (source.safeSearch) "2" else "0")
                normalizeFreshness(freshness)?.let { builder.setQueryParameter("time_range", it) }
            }
            else -> {
                builder.setQueryParameter("count", count.toString())
                normalizeFreshness(freshness)?.let {
                    builder.setQueryParameter("freshness", it)
                }
                source.customBody?.takeIf { it.isNotBlank() }?.let { customBody ->
                    val custom = JsonParser.parseString(customBody)
                    require(custom.isJsonObject) { "自定义请求体必须是 JSON 对象" }
                    custom.asJsonObject.entrySet().forEach { (key, value) ->
                        if (value.isJsonPrimitive) builder.setQueryParameter(key, value.asString)
                    }
                }
                builder.setQueryParameter(queryParameter, query)
            }
        }
        return builder.build().toString()
    }

    internal fun parseResponse(
        source: AiSearchSource,
        body: String,
        count: Int
    ): List<AiWebSearchResult> {
        val root = JsonParser.parseString(body)
        val resultPath = when (source.type) {
            AiSearchSource.TYPE_BRAVE -> "web.results"
            else -> source.resultPath.ifBlank { "results" }
        }
        val array = elementAtPath(root, resultPath)?.takeIf { it.isJsonArray }?.asJsonArray
            ?: JsonArray()
        return array.asSequence()
            .mapNotNull { item -> parseItem(source, item) }
            .take(count.coerceIn(1, MAX_RESULTS))
            .toList()
    }

    private fun parseItem(source: AiSearchSource, item: JsonElement): AiWebSearchResult? {
        if (!item.isJsonObject) return null
        val titlePath = source.titlePath.ifBlank { "title" }
        val urlPath = source.urlPath.ifBlank { "url" }
        val snippetPath = when (source.type) {
            AiSearchSource.TYPE_BRAVE -> "description"
            else -> source.snippetPath.ifBlank { "content" }
        }
        val datePath = when (source.type) {
            AiSearchSource.TYPE_BRAVE -> "page_age"
            else -> source.publishedAtPath
        }
        val title = stringAtPath(item, titlePath).take(MAX_TITLE_LENGTH)
        val url = stringAtPath(item, urlPath).trim()
        if (title.isBlank() || !isHttpUrl(url)) return null
        return AiWebSearchResult(
            title = title,
            url = url,
            snippet = stringAtPath(item, snippetPath).take(MAX_SNIPPET_LENGTH),
            publishedAt = datePath.takeIf { it.isNotBlank() }
                ?.let { stringAtPath(item, it).take(MAX_DATE_LENGTH) }
                ?.takeIf { it.isNotBlank() },
            source = runCatching { URI(url).host.orEmpty() }.getOrDefault("")
        )
    }

    private fun buildHeaders(source: AiSearchSource): Map<String, String> =
        HashMap(source.getHeaderMap()).apply {
            if (source.apiKey.isBlank()) return@apply
            when (source.type) {
                AiSearchSource.TYPE_BRAVE -> putIfAbsent("X-Subscription-Token", source.apiKey)
                else -> putIfAbsent(
                    "Authorization",
                    if (source.apiKey.startsWith("Bearer ")) source.apiKey else "Bearer ${source.apiKey}"
                )
            }
        }

    private fun endpoint(source: AiSearchSource): String {
        val baseUrl = source.baseUrl.trim().trimEnd('/')
        require(baseUrl.startsWith("https://") || baseUrl.startsWith("http://")) {
            "搜索接口地址必须使用 HTTP 或 HTTPS"
        }
        return if (source.type == AiSearchSource.TYPE_SEARXNG && !baseUrl.endsWith("/search")) {
            "$baseUrl/search"
        } else {
            baseUrl
        }
    }

    private fun requestMethod(source: AiSearchSource): String = when (source.type) {
        AiSearchSource.TYPE_TAVILY -> AiSearchSource.METHOD_POST
        AiSearchSource.TYPE_BRAVE, AiSearchSource.TYPE_SEARXNG -> AiSearchSource.METHOD_GET
        else -> source.method.uppercase()
    }

    private fun extractError(body: String): String? = runCatching {
        val root = JsonParser.parseString(body).asJsonObject
        val error = root.get("error")
        when {
            error == null || error.isJsonNull -> root.get("message")?.asString
            error.isJsonPrimitive -> error.asString
            else -> error.asJsonObject.get("message")?.asString
                ?: error.asJsonObject.get("detail")?.asString
        }
    }.getOrNull()

    private fun elementAtPath(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return root
        var current = root
        path.split('.').filter { it.isNotBlank() }.forEach { segment ->
            current = when {
                current.isJsonObject -> current.asJsonObject.get(segment) ?: return null
                current.isJsonArray -> {
                    val index = segment.toIntOrNull() ?: return null
                    if (index !in 0 until current.asJsonArray.size()) return null
                    current.asJsonArray[index]
                }
                else -> return null
            }
        }
        return current
    }

    private fun stringAtPath(root: JsonElement, path: String): String =
        elementAtPath(root, path)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val scheme = URI(value).scheme?.lowercase()
        scheme == "http" || scheme == "https"
    }.getOrDefault(false)

    private fun normalizeFreshness(value: String): String? = when (value.trim().lowercase()) {
        "day", "week", "month", "year" -> value.trim().lowercase()
        else -> null
    }

    private fun braveFreshness(value: String): String? = when (normalizeFreshness(value)) {
        "day" -> "pd"
        "week" -> "pw"
        "month" -> "pm"
        "year" -> "py"
        else -> null
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=UTF-8".toMediaType()
    private const val MAX_QUERY_LENGTH = 600
    private const val MAX_RESULTS = 20
    private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
    private const val MAX_TITLE_LENGTH = 300
    private const val MAX_SNIPPET_LENGTH = 2_000
    private const val MAX_DATE_LENGTH = 100
}
