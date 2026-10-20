package io.legado.app.ui.book.agent

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentConversationCodecTest {
    @Test
    fun `自定义名称清理空白并拒绝空名称和超长名称`() {
        assertEquals("共读 三体", AgentConversationCodec.customTitle("  共读\n 三体  "))
        assertNull(AgentConversationCodec.customTitle(" \n\t "))
        assertEquals("名".repeat(80), AgentConversationCodec.customTitle("名".repeat(80)))
        assertNull(AgentConversationCodec.customTitle("名".repeat(81)))
    }

    @Test
    fun `会话标题压缩空白并限制长度`() {
        val title = AgentConversationCodec.titleFrom(
            "  请帮我  总结一下\n这本书里非常非常长的一段问题，它不应该完整显示在历史列表标题中  ",
            "新对话"
        )

        assertEquals("请帮我 总结一下 这本书里非常非常长的一段问题，它不应该完整显示在历史列表标题中".take(40), title)
    }

    @Test
    fun `消息和工具上下文可完整序列化恢复`() {
        val messages = listOf(
            AgentMessage(isUser = true, text = "查找三体"),
            AgentMessage(
                text = "找到了",
                steps = listOf(AgentStep("1", "搜索")),
                generatedImages = listOf(AgentGeneratedImage("/images/result.img", "星空"))
            )
        )
        val turns = listOf(
            AgentChatTurn("user", "查找三体"),
            AgentChatTurn(
                role = "assistant",
                content = "",
                toolCalls = JsonParser.parseString("[{\"id\":\"call-1\"}]"),
                reasoningContent = "需要先调用搜索工具"
            )
        )

        assertEquals(messages, AgentConversationCodec.decodeMessages(AgentConversationCodec.encodeMessages(messages)))
        assertEquals(turns, AgentConversationCodec.decodeTurns(AgentConversationCodec.encodeTurns(turns)))
    }

    @Test
    fun `损坏的历史数据恢复为空列表`() {
        assertEquals(emptyList<AgentMessage>(), AgentConversationCodec.decodeMessages("not-json"))
        assertEquals(emptyList<AgentChatTurn>(), AgentConversationCodec.decodeTurns("not-json"))
    }
}
