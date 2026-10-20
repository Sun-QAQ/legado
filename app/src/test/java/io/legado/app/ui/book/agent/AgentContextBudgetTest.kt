package io.legado.app.ui.book.agent

import com.google.gson.JsonObject
import io.legado.app.data.entities.AiModelThinking
import org.junit.Assert.*
import org.junit.Test

class AgentContextBudgetTest {
    @Test
    fun `全局预算计算工具定义和系统提示词但图片不按Base64文本计算`() {
        val body = JsonObject().apply { addProperty("system", "人格和预设") }
        val before = AgentContextBudget.tokens(body)
        body.addProperty("tools", "工具说明".repeat(100))
        assertTrue(AgentContextBudget.tokens(body) > before)
        val image = JsonObject().apply {
            addProperty("type", "image_url")
            add("image_url", JsonObject().apply { addProperty("url", "data:image/png;base64," + "a".repeat(100000)) })
        }
        assertEquals(8192L, AgentContextBudget.tokens(image))
        assertTrue(AgentContextBudget.inputLimit(32768) < 32768 - 8192)
    }

    @Test
    fun `仅明确超限错误触发恢复且普通HTTP错误不误判`() {
        assertTrue(AgentContextBudget.isContextOverflow(400, """{"error":{"code":"context_length_exceeded","message":"secret"}}"""))
        assertTrue(AgentContextBudget.isContextOverflow(413, "maximum context length is 8192 tokens"))
        assertTrue(AgentContextBudget.isContextOverflow(422, "Input is too long"))
        assertFalse(AgentContextBudget.isContextOverflow(400, "Invalid tool_call_id"))
        assertFalse(AgentContextBudget.isContextOverflow(413, "request entity too large"))
        assertFalse(AgentContextBudget.isContextOverflow(401, "context_length_exceeded"))
        assertFalse(AgentContextBudget.isContextOverflow(500, "maximum context length"))
        assertFalse(AgentContextOverflowException().message!!.contains("secret"))
    }

    @Test
    fun `模型容量默认兼容旧数据且不改变API思考参数`() {
        assertEquals(1048576, AiModelThinking().contextWindow())
        assertEquals(1048576, AiModelThinking(contextWindowTokens = 0).contextWindow())
        assertEquals(32768, AiModelThinking(contextWindowTokens = 32768).contextWindow())
        assertEquals(131072, AiModelThinking(contextWindowTokens = 131072).contextWindow())
        val body = JsonObject()
        AiModelThinking(contextWindowTokens = 131072).applyTo(body, "https://example.com", "model")
        assertEquals(0, body.size())
    }
}
