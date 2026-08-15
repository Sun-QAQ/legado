package io.legado.app.ui.book.agent

import com.google.gson.JsonObject

/**
 * Agent 工具执行上下文，由 ViewModel 提供运行时能力和状态
 */
interface AgentToolContext {
    suspend fun searchBooks(key: String): String

    suspend fun createBookSource(url: String): String

    suspend fun readingReport(period: String): String

    fun appendStatus(status: String)

    fun getString(resId: Int, vararg args: Any): String
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
