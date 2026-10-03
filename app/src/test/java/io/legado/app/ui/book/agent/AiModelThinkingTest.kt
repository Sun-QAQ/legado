package io.legado.app.ui.book.agent

import com.google.gson.JsonObject
import io.legado.app.data.entities.AiModelThinking
import org.junit.Assert.*
import org.junit.Test

class AiModelThinkingTest {
    @Test fun defaultKeepsExistingRequest() {
        val body = JsonObject().apply { addProperty("model", "gpt-5") }
        AiModelThinking().applyTo(body, "https://api.openai.com/v1", "gpt-5")
        assertEquals(1, body.size())
    }

    @Test fun nativeDisabledOmitsEffort() {
        val body = JsonObject()
        AiModelThinking("disabled", "high").applyTo(body, "https://api.deepseek.com/v1", "deepseek-chat")
        assertEquals("disabled", body.getAsJsonObject("thinking").get("type").asString)
        assertFalse(body.has("reasoning_effort"))
    }

    @Test fun openAiUsesSelectedEffortAndSupportsDisable() {
        val body = JsonObject()
        AiModelThinking("enabled", "high").applyTo(body, "https://opencode.ai/zen/go/v1", "glm-5.2")
        assertEquals("high", body.get("reasoning_effort").asString)
        assertFalse(body.has("thinking"))
        AiModelThinking("disabled").applyTo(body, "https://api.openai.com/v1", "gpt-5")
        assertEquals("none", body.get("reasoning_effort").asString)
    }

    @Test fun qwenMapsEffortToBudget() {
        val body = JsonObject()
        AiModelThinking("enabled", "high").applyTo(body, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen3")
        assertTrue(body.get("enable_thinking").asBoolean)
        assertEquals(8192, body.get("thinking_budget").asInt)
        assertFalse(body.has("reasoning_effort"))
    }

    @Test fun explicitFormatOverridesAutoDetection() {
        val body = JsonObject()
        AiModelThinking("enabled", "low", "thinking").applyTo(body, "https://gateway.example/v1", "custom")
        assertEquals("enabled", body.getAsJsonObject("thinking").get("type").asString)
        assertEquals("low", body.get("reasoning_effort").asString)
    }
}
