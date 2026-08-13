package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.R
import io.legado.app.utils.GSON

/**
 * Agent 工具注册表：所有可调用工具的声明、schema 和执行入口
 */
object AgentTools {

    private val searchBooksTool = AgentTool(
        name = "search_books",
        description = "根据书名搜索书籍，返回搜索结果列表",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "query",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "书名关键字")
                    }
                )
            },
            required = arrayOf("query")
        ),
        execute = { arguments ->
            val query = arguments.getStringValue("query")
            if (query.isBlank()) {
                GSON.toJson(emptyList<Any>())
            } else {
                appendStatus(getString(R.string.agent_status_searching, query))
                searchBooks(query)
            }
        }
    )

    private val createBookSourceTool = AgentTool(
        name = "create_book_source",
        description = "根据网站地址编写Legado书源，并自动调试保存",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "url",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "网站首页地址，例如 https://www.example.com")
                    }
                )
            },
            required = arrayOf("url")
        ),
        execute = { arguments ->
            val url = arguments.getStringValue("url")
            if (url.isBlank()) {
                "书源网址为空"
            } else {
                appendStatus(getString(R.string.agent_status_creating_source))
                createBookSource(url)
            }
        }
    )

    private val allTools = listOf(searchBooksTool, createBookSourceTool)
    private val toolMap = allTools.associateBy { it.name }

    fun find(name: String): AgentTool? = toolMap[name]

    fun toJsonArray(): JsonArray = JsonArray().apply {
        allTools.forEach { add(it.toJson()) }
    }

    private fun objectParameters(
        properties: JsonObject,
        required: Array<String>
    ): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        add("properties", properties)
        add("required", JsonArray().apply { required.forEach { add(it) } })
    }

    private fun JsonObject.getStringValue(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
}
