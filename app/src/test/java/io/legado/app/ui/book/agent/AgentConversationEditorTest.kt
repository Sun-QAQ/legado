package io.legado.app.ui.book.agent

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class AgentConversationEditorTest {
    private fun user(id: String, text: String = "相同问题") = AgentMessage(true, text, id = id)
    private fun reply(id: String, text: String = "回复") = AgentMessage(text = text, id = id)
    private fun call(owner: String?, id: String) = AgentChatTurn("assistant", "准备读取", toolCalls = JsonParser.parseString(
        """[{"id":"$id","type":"function","function":{"name":"read_book_content","arguments":"{}"}}]"""
    ), messageId = owner)

    @Test
    fun `重复内容通过ID修改指定消息且保留附件及后续对话`() {
        val attachment = AgentAttachment("资料.txt", "text/plain", "/file", 10)
        val messages = listOf(user("u1"), reply("a1"), user("u2").copy(attachments = listOf(attachment)), reply("a2"))
        val turns = messages.map { AgentChatTurn(if (it.isUser) "user" else "assistant", it.text, attachments = it.attachments, messageId = it.id) }
        val result = AgentConversationEditor.edit(messages, turns, "u2", "修改后的问题\n第二行")!!
        assertEquals("相同问题", result.messages[0].text)
        assertEquals("修改后的问题\n第二行", result.messages[2].text)
        assertEquals(listOf(attachment), result.messages[2].attachments)
        assertEquals(listOf(attachment), result.turns[2].attachments)
        assertEquals(result.messages[2].text, result.turns[2].content)
        assertEquals(messages[3], result.messages[3])
    }

    @Test
    fun `修改AI最终正文保留工具配对并清除旧的最终推理`() {
        val messages = listOf(user("u"), reply("a", "旧结论"))
        val turns = listOf(AgentChatTurn("user", "问题", messageId = "u"), call("a", "read"),
            AgentChatTurn("tool", "正文", "read", messageId = "a"),
            AgentChatTurn("assistant", "旧结论", reasoningContent = "旧推理", messageId = "a"))
        val result = AgentConversationEditor.edit(messages, turns, "a", "**新结论**")!!
        assertEquals("**新结论**", result.messages.last().text)
        assertEquals("**新结论**", result.turns.last().content)
        assertNull(result.turns.last().reasoningContent)
        assertEquals(turns.take(3), result.turns.take(3))
    }

    @Test
    fun `删除AI回复会删除其多组工具结果和内部提示但保留其他消息`() {
        val messages = listOf(user("u"), reply("a"), user("next"), reply("next-a"))
        val turns = listOf(AgentChatTurn("user", "问题", messageId = "u"), call("a", "read1"),
            AgentChatTurn("tool", "正文1", "read1", messageId = "a"),
            AgentChatTurn("user", "继续", internalPrompt = true, messageId = "a"), call("a", "read2"),
            AgentChatTurn("tool", "正文2", "read2", messageId = "a"), AgentChatTurn("assistant", "回复", messageId = "a"),
            AgentChatTurn("user", "后续问题", messageId = "next"), AgentChatTurn("assistant", "后续回复", messageId = "next-a"))
        val result = AgentConversationEditor.delete(messages, turns, "a")!!
        assertEquals(listOf("u", "next", "next-a"), result.messages.map { it.id })
        assertEquals(listOf("u", "next", "next-a"), result.turns.map { it.messageId })
    }

    @Test
    fun `仅删除用户消息不连带删除其他回复`() {
        val messages = listOf(user("u"), reply("a"))
        val turns = listOf(AgentChatTurn("user", "问题", messageId = "u"), call("a", "read"),
            AgentChatTurn("tool", "正文", "read", messageId = "a"), AgentChatTurn("assistant", "回复", messageId = "a"))
        val result = AgentConversationEditor.delete(messages, turns, "u")!!
        assertEquals(listOf(messages.last()), result.messages)
        assertEquals(turns.drop(1), result.turns)
    }

    @Test
    fun `旧会话按重复用户轮次补齐稳定ID并将隐藏步骤关联到最终回复`() {
        val messages = listOf(AgentMessage(true, "问题"), AgentMessage(text = "结论"), AgentMessage(true, "问题"), AgentMessage(text = "结论"))
        val turns = (1..2).flatMap { listOf(AgentChatTurn("user", "问题"), call(null, "read$it"),
            AgentChatTurn("tool", "正文$it", "read$it"), AgentChatTurn("user", "继续", internalPrompt = true),
            AgentChatTurn("assistant", "结论")) }
        val result = AgentConversationEditor.linkLegacy(messages, turns)
        assertEquals(4, result.messages.map { it.id }.toSet().size)
        assertEquals(result.messages[0].id, result.turns[0].messageId)
        assertTrue(result.turns.subList(1, 5).all { it.messageId == result.messages[1].id })
        assertEquals(result.messages[2].id, result.turns[5].messageId)
        assertTrue(result.turns.subList(6, 10).all { it.messageId == result.messages[3].id })
        assertEquals(result, AgentConversationEditor.linkLegacy(result.messages, result.turns))
        val deleted = AgentConversationEditor.delete(result.messages, result.turns, result.messages[1].id!!)!!
        assertEquals(6, deleted.turns.size)
        assertEquals(result.messages, AgentConversationCodec.decodeMessages(AgentConversationCodec.encodeMessages(result.messages)))
        assertEquals(result.turns, AgentConversationCodec.decodeTurns(AgentConversationCodec.encodeTurns(result.turns)))
    }

    @Test
    fun `展示型回复编辑后插入正确位置且删除时不会删到其他轮次`() {
        val messages = listOf(user("u"), reply("card", ""), user("next"))
        val turns = listOf(AgentChatTurn("user", "相同问题", messageId = "u"), AgentChatTurn("user", "相同问题", messageId = "next"))
        val result = AgentConversationEditor.edit(messages, turns, "card", "手动补充说明")!!
        assertEquals(listOf("u", "card", "next"), result.turns.map { it.messageId })
        assertEquals("手动补充说明", result.turns[1].content)
        assertEquals(turns, AgentConversationEditor.delete(result.messages, result.turns, "card")!!.turns)
    }

    @Test
    fun `空文本仅允许保留有附件或卡片的消息且生成中不能修改删除`() {
        val attachment = AgentAttachment("资料", "text/plain", "/file", 1)
        val plain = user("plain")
        assertNull(AgentConversationEditor.edit(listOf(plain), emptyList(), "plain", " \n"))
        val attached = plain.copy(attachments = listOf(attachment))
        assertEquals("", AgentConversationEditor.edit(listOf(attached), emptyList(), "plain", "")!!.messages.single().text)
        val streaming = reply("stream").copy(streaming = true)
        assertNull(AgentConversationEditor.edit(listOf(streaming), emptyList(), "stream", "新内容"))
        assertNull(AgentConversationEditor.delete(listOf(streaming), emptyList(), "stream"))
        assertNull(AgentConversationEditor.delete(listOf(plain), emptyList(), "stale-id"))
        val final = AgentConversationEditor.delete(listOf(plain), listOf(AgentChatTurn("user", plain.text, messageId = "plain")), "plain")!!
        assertTrue(final.messages.isEmpty())
        assertTrue(final.turns.isEmpty())
    }
}
