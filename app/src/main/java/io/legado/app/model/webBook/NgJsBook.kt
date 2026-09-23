package io.legado.app.model.webBook

import com.google.gson.JsonParser
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleData
import kotlin.coroutines.coroutineContext

object NgJsBook {
    suspend fun search(
        source: BookSource, key: String, page: Int,
        filter: ((String, String) -> Boolean)?, shouldBreak: ((Int) -> Boolean)?
    ): ArrayList<SearchBook> {
        val body = NgJsRuntime.call(source, "search", key, page)
        requireList(body, "search", "name", "bookUrl")
        if (JsonParser.parseString(body).asJsonArray.isEmpty) return arrayListOf()
        return BookList.analyzeBookList(
            source, RuleData(), AnalyzeUrl(source.bookSourceUrl, source = source,
                coroutineContext = coroutineContext), source.bookSourceUrl, body,
            filter = filter, shouldBreak = shouldBreak
        )
    }

    suspend fun explore(source: BookSource, url: String, page: Int): ArrayList<SearchBook> {
        val body = NgJsRuntime.call(source, "explore", url, page)
        requireList(body, "explore", "name", "bookUrl")
        if (JsonParser.parseString(body).asJsonArray.isEmpty) return arrayListOf()
        return BookList.analyzeBookList(
            source, RuleData(), AnalyzeUrl(url, source = source,
                coroutineContext = coroutineContext), source.bookSourceUrl, body, isSearch = false
        )
    }

    suspend fun content(source: BookSource, chapter: BookChapter, book: Book, next: String?): String {
        val result = JsonParser.parseString(NgJsRuntime.call(source, "getContent", chapter, book, next))
        require(result.isJsonPrimitive && result.asJsonPrimitive.isString) {
            "NG JS getContent 必须返回正文字符串"
        }
        return result.asString
    }

    fun requireObject(body: String, function: String) {
        require(JsonParser.parseString(body).isJsonObject) { "NG JS $function 必须返回对象" }
    }

    fun requireList(body: String, function: String, vararg fields: String) {
        val value = JsonParser.parseString(body)
        require(value.isJsonArray) { "NG JS $function 必须返回数组" }
        require(value.asJsonArray.all { item ->
            item.isJsonObject && fields.all { field ->
                item.asJsonObject[field]?.let { it.isJsonPrimitive && it.asString.isNotBlank() } == true
            }
        }) { "NG JS $function 返回条目缺少 ${fields.joinToString()}" }
    }
}
