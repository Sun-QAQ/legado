package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingToolCallAccumulatorTest {

    @Test
    fun `合并流式工具调用时保留必需的type并拼接参数`() {
        val accumulator = StreamingToolCallAccumulator()
        accumulator.append(
            JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("index", 0)
                    addProperty("id", "call_source_1")
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", "search_source_repository")
                        addProperty("arguments", "{\"query\":\"")
                    })
                })
            }
        )
        accumulator.append(
            JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("index", 0)
                    add("function", JsonObject().apply {
                        addProperty("arguments", "wenku8.cc\"}")
                    })
                })
            }
        )

        val call = accumulator.toJsonArray().single().asJsonObject

        assertEquals("call_source_1", call.get("id").asString)
        assertEquals("function", call.get("type").asString)
        assertEquals(
            "search_source_repository",
            call.getAsJsonObject("function").get("name").asString
        )
        assertEquals(
            "{\"query\":\"wenku8.cc\"}",
            call.getAsJsonObject("function").get("arguments").asString
        )
    }
}
