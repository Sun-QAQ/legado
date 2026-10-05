package io.legado.app.api.controller

import io.legado.app.data.entities.AiSource
import io.legado.app.ui.book.agent.AgentMessage
import io.legado.app.ui.book.agent.AgentStep
import io.legado.app.utils.GSON
import org.junit.Assert.*
import org.junit.Test

class AiChatPayloadTest {
    @Test
    fun supplierDoesNotExposeCredentialsOrPrivateEndpoint() {
        val source = AiSource(id = 42, name = "测试供应商", model = "test-model",
            apiKey = "private-api-key", baseUrl = "https://private-endpoint.invalid",
            headers = "{\"Authorization\":\"private-token\"}")
        val payload = AiChatPayload.supplier(source)
        assertEquals(setOf("id", "name", "model", "models", "modelError"), payload.keys)
        assertEquals("42", payload["id"])
        val json = GSON.toJson(payload)
        assertFalse(json.contains("private-"))
        assertFalse(json.contains("Authorization"))
    }

    @Test
    fun modelOptionsIncludeConfiguredModelAndDeduplicateFetchedModels() {
        val source = AiSource(id = 42, name = "测试供应商", model = "configured")
        val payload = AiChatPayload.supplier(source, listOf("vision", "text", "vision", "", "configured"))
        assertEquals(listOf("configured", "vision", "text"), payload["models"])
        assertNull(payload["modelError"])
        val fallback = AiChatPayload.supplier(source, emptyList(), "获取失败")
        assertEquals(listOf("configured"), fallback["models"])
        assertEquals("获取失败", fallback["modelError"])
    }

    @Test
    fun streamingReplyUsesLatestDeltaWithoutExposingToolDetails() {
        val message = AgentMessage(text = "旧内容", streaming = true,
            steps = listOf(AgentStep("1", "搜索书架", detail = "private-tool-request")))
        val payload = AiChatPayload.message(message, "正在生成的新内容")
        assertEquals("正在生成的新内容", payload["text"])
        assertFalse(GSON.toJson(payload).contains("private-tool-request"))
        assertEquals("旧内容", AiChatPayload.message(message, null)["text"])
    }

    @Test
    fun finalReplyDoesNotReuseAnotherStreamingMessage() {
        val message = AgentMessage(text = "已完成的回复")
        assertEquals("已完成的回复", AiChatPayload.message(message, "后续回复")["text"])
    }
}
