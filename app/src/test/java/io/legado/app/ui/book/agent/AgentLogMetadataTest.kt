package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentLogMetadataTest {

    @Test
    fun `日志只包含请求元数据而不包含消息正文`() {
        val body = JsonObject().apply {
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "敏感正文和密钥 secret-content")
                })
                add(JsonObject().apply {
                    add("tool_calls", JsonArray().apply {
                        add(JsonObject().apply {
                            add("function", JsonObject().apply {
                                addProperty("name", "read_book_content")
                            })
                        })
                    })
                })
            })
        }

        val log = AgentLogMetadata.format("test-model", body, 123, 200)

        assertEquals(
            "Agent 请求 model=test-model requestChars=${body.toString().length} " +
                "tools=read_book_content errorCode=200 elapsed=123ms",
            log
        )
        assertFalse(log.contains("secret-content"))
    }
}
