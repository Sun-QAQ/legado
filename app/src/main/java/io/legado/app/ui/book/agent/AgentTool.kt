package io.legado.app.ui.book.agent

import com.google.gson.JsonObject

/**
 * Agent 工具执行上下文，由 ViewModel 提供运行时能力和状态
 */
interface AgentToolContext {
    suspend fun searchBooks(key: String, group: String, limit: Int): String

    suspend fun searchSourceRepository(query: String, limit: Int): String

    suspend fun addBookToShelf(bookUrl: String): String

    suspend fun createBookSource(url: String): String

    suspend fun fetchPage(url: String, method: String?, body: String?): String

    suspend fun updateBookSource(sourceJson: String): String

    suspend fun debugSourceSearch(key: String): String

    suspend fun debugSourceBookInfo(bookUrl: String): String

    suspend fun debugSourceToc(tocUrl: String, bookUrl: String?): String

    suspend fun debugSourceContent(chapterUrl: String, bookUrl: String?, tocUrl: String?): String

    suspend fun saveBookSource(): String

    suspend fun readingReport(period: String): String

    suspend fun getLibraryStats(): String

    suspend fun createAiBook(
        type: String,
        theme: String,
        chapterCount: Int,
        wordsPerChapter: Int
    ): String

    suspend fun readBookContent(
        bookQuery: String,
        startIndex: Int,
        count: Int,
        maxChars: Int
    ): String
}

/**
 * Agent 工具：名称、描述、参数 schema 和执行函数
 */
class AgentTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
    val execute: suspend AgentToolContext.(JsonObject) -> String
) {

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("type", "function")
        add("function", JsonObject().apply {
            addProperty("name", name)
            addProperty("description", description)
            add("parameters", parameters)
        })
    }
}
