package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** 合并 Chat Completions 流式响应中的工具调用分片。 */
internal class StreamingToolCallAccumulator {

    private val calls = LinkedHashMap<Int, JsonObject>()

    fun append(toolCalls: JsonArray) {
        for (position in 0 until toolCalls.size()) {
            val chunk = toolCalls[position]
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?: continue
            val index = chunk.get("index")
                ?.takeIf { !it.isJsonNull }
                ?.asInt
                ?: position
            val call = calls.getOrPut(index) {
                JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject())
                }
            }
            chunk.get("id")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { call.addProperty("id", it) }
            chunk.get("type")
                ?.takeIf { !it.isJsonNull }
                ?.asString
                ?.takeIf { it.isNotBlank() }
                ?.let { call.addProperty("type", it) }
            chunk.get("function")
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { functionChunk ->
                    val function = call.getAsJsonObject("function")
                    functionChunk.get("name")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.takeIf { it.isNotBlank() }
                        ?.let { function.addProperty("name", it) }
                    functionChunk.get("arguments")
                        ?.takeIf { !it.isJsonNull }
                        ?.asString
                        ?.let { argumentsDelta ->
                            val previous = function.get("arguments")
                                ?.takeIf { !it.isJsonNull }
                                ?.asString
                                .orEmpty()
                            function.addProperty("arguments", previous + argumentsDelta)
                        }
                }
        }
    }

    fun isNotEmpty(): Boolean = calls.isNotEmpty()

    fun toJsonArray(): JsonArray = JsonArray().apply {
        calls.keys.sorted().forEach { add(calls.getValue(it)) }
    }
}
