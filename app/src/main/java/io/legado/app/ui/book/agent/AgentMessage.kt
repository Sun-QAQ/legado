package io.legado.app.ui.book.agent

import io.legado.app.data.entities.SearchBook

/**
 * 对话消息
 */
data class AgentMessage(
    val isUser: Boolean,
    val text: String,
    val books: List<SearchBook> = emptyList()
)
