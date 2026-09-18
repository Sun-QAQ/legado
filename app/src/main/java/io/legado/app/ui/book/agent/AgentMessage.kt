package io.legado.app.ui.book.agent

import io.legado.app.data.entities.SearchBook

/**
 * 对话消息
 */
data class AgentMessage(
    val isUser: Boolean = false,
    val text: String = "",
    val books: List<SearchBook> = emptyList(),
    val steps: List<AgentStep> = emptyList(),
    val placeholder: Boolean = false,
    val canLoadMore: Boolean = false,
    val streaming: Boolean = false,
    val repositorySources: List<SourceRepositoryItem> = emptyList(),
    val generatedImages: List<AgentGeneratedImage> = emptyList()
)
