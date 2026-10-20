package io.legado.app.ui.book.agent

import java.util.UUID

/** 可见消息用稳定 ID 关联模型轮次；一个 AI 回复可拥有多组工具调用及内部继续提示。 */
internal object AgentConversationEditor {
    data class Conversation(val messages: List<AgentMessage>, val turns: List<AgentChatTurn>)

    /** 旧会话只在恢复时按用户轮次和最终回复补齐关联，不把隐藏的工具回复当作可见消息。 */
    fun linkLegacy(messages: List<AgentMessage>, turns: List<AgentChatTurn>): Conversation {
        val identified = messages.map { if (it.id.isNullOrBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
        val linked = turns.toMutableList()
        var cursor = 0
        identified.forEachIndexed { index, message ->
            if (!message.isUser) return@forEachIndexed
            val start = (cursor until linked.size).firstOrNull {
                val turn = linked[it]
                turn.role == "user" && turn.internalPrompt != true &&
                    (turn.messageId == message.id || (turn.messageId == null && turn.content == message.text))
            } ?: return@forEachIndexed
            linked[start] = linked[start].copy(messageId = message.id)
            val end = (start + 1 until linked.size).firstOrNull {
                linked[it].role == "user" && linked[it].internalPrompt != true
            } ?: linked.size
            var replyStart = start + 1
            for (reply in identified.drop(index + 1).takeWhile { !it.isUser }) {
                val final = (replyStart until end).lastOrNull {
                    val turn = linked[it]
                    turn.role == "assistant" && turn.toolCalls == null && turn.content == reply.text &&
                        (turn.messageId == null || turn.messageId == reply.id)
                } ?: continue
                for (i in replyStart..final) {
                    if (linked[i].messageId == null) linked[i] = linked[i].copy(messageId = reply.id)
                }
                replyStart = final + 1
            }
            cursor = end
        }
        return Conversation(identified, linked)
    }

    fun canBeEmpty(message: AgentMessage): Boolean = message.attachments.isNotEmpty() ||
        message.books.isNotEmpty() || message.generatedImages.isNotEmpty() ||
        message.repositorySources.isNotEmpty() || message.steps.isNotEmpty()

    fun edit(messages: List<AgentMessage>, turns: List<AgentChatTurn>, id: String, text: String): Conversation? {
        val index = messages.indexOfFirst { it.id == id && !it.streaming && !it.placeholder }
        if (index < 0) return null
        val message = messages[index]
        if (text.isBlank() && !canBeEmpty(message)) return null
        val updated = message.copy(text = text)
        val linked = turns.toMutableList()
        val turnIndex = linked.indexOfLast {
            it.messageId == id && it.role == (if (message.isUser) "user" else "assistant") &&
                it.toolCalls == null && it.internalPrompt != true
        }
        if (turnIndex >= 0) {
            linked[turnIndex] = linked[turnIndex].copy(content = text, reasoningContent = null)
        } else {
            // 直接搜索等展示型回复原本没有模型轮次；编辑后按其可见顺序加入上下文。
            val followingIds = messages.drop(index + 1).mapNotNull { it.id }.toSet()
            val insertion = linked.indexOfFirst { it.messageId in followingIds }.takeIf { it >= 0 } ?: linked.size
            linked.add(insertion, AgentChatTurn(
                if (message.isUser) "user" else "assistant", text,
                attachments = message.attachments.takeIf { it.isNotEmpty() }, messageId = id
            ))
        }
        return Conversation(messages.toMutableList().apply { set(index, updated) }, linked)
    }

    fun delete(messages: List<AgentMessage>, turns: List<AgentChatTurn>, id: String): Conversation? {
        if (messages.none { it.id == id && !it.streaming && !it.placeholder }) return null
        return Conversation(messages.filterNot { it.id == id }, turns.filterNot { it.messageId == id })
    }
}
