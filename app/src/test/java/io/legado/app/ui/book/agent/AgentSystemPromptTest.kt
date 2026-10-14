package io.legado.app.ui.book.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentSystemPromptTest {
    @Test
    fun `预设放在系统提示词最前面并保留人格和工具说明`() {
        assertEquals(
            "请简洁回答\n\n人格提示词\n\n工具说明",
            composeAgentSystemPrompt("人格提示词", "请简洁回答", "工具说明")
        )
    }

    @Test
    fun `未选择预设时保留原系统提示词`() {
        assertEquals("人格提示词工具说明", composeAgentSystemPrompt("人格提示词", "", "工具说明"))
        assertEquals("人格提示词工具说明", composeAgentSystemPrompt("人格提示词", "  \n", "工具说明"))
    }

    @Test
    fun `切换编辑和停用预设不会累积旧提示词`() {
        val original = "人格提示词"
        assertEquals("预设一\n\n人格提示词\n\n工具说明", composeAgentSystemPrompt(original, "预设一", "工具说明"))
        assertEquals("预设二\n\n人格提示词\n\n工具说明", composeAgentSystemPrompt(original, "预设二", "工具说明"))
        assertEquals("修改后的预设二\n\n人格提示词\n\n工具说明", composeAgentSystemPrompt(original, "修改后的预设二", "工具说明"))
        assertEquals("人格提示词工具说明", composeAgentSystemPrompt(original, "", "工具说明"))
    }
}
