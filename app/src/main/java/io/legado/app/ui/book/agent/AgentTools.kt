package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.utils.GSON

/**
 * Agent 工具注册表：所有可调用工具的声明、schema 和执行入口
 */
object AgentTools {

    private val searchBooksTool = AgentTool(
        name = "search_books",
        description = "根据书名搜索书籍，可通过 group 指定书源分组或书源名称，返回搜索结果列表",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "query",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "书名关键字")
                    }
                )
                add(
                    "group",
                    JsonObject().apply {
                        addProperty("type", "string")
                        addProperty("description", "可选，书源分组名称或具体书源名称，省略时搜索全部已启用书源")
                    }
                )
            },
            required = arrayOf("query")
        ),
        execute = { arguments ->
            val query = arguments.getStringValue("query")
            val group = arguments.getStringValue("group")
            if (query.isBlank()) {
                GSON.toJson(emptyList<Any>())
            } else {
                searchBooks(query, group)
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
                createBookSource(url)
            }
        }
    )

    private val readingReportTool = AgentTool(
        name = "reading_report",
        description = "生成阅读周报或月报，返回统计周期内的阅读时长和书籍列表",
        parameters = objectParameters(
            properties = JsonObject().apply {
                add(
                    "period",
                    JsonObject().apply {
                        addProperty("type", "string")
                        add(
                            "enum",
                            JsonArray().apply {
                                add("week")
                                add("month")
                            }
                        )
                        addProperty("description", "统计周期：week 表示本周，month 表示本月")
                    }
                )
            },
            required = arrayOf("period")
        ),
        execute = { arguments ->
            val period = arguments.getStringValue("period")
            if (period != "week" && period != "month") {
                "统计周期无效，请使用 week 或 month"
            } else {
                readingReport(period)
            }
        }
    )

    private val libraryStatsTool = AgentTool(
        name = "library_stats",
        description = "查询当前书源数量、订阅源数量、书源分组及各组数量、书籍总数、书架分组及各组书籍数量",
        parameters = objectParameters(
            properties = JsonObject(),
            required = arrayOf()
        ),
        execute = { getLibraryStats() }
    )

    private val allTools = listOf(
        searchBooksTool,
        createBookSourceTool,
        readingReportTool,
        libraryStatsTool
    )
    private val toolMap = allTools.associateBy { it.name }

    fun find(name: String): AgentTool? = toolMap[name]

    /**
     * 生成工具清单摘要，用于注入系统提示词，与工具注册表保持单一事实来源
     */
    fun overview(): String =
        allTools.joinToString("；") { "${it.name}：${it.description}" }

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
