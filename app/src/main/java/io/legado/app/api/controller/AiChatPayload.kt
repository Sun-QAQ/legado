package io.legado.app.api.controller

import io.legado.app.data.entities.AiSource
import io.legado.app.ui.book.agent.AgentMessage

/** 显式白名单，避免供应商密钥、请求头及工具调试详情出现在 Web 响应中。 */
internal object AiChatPayload {
    fun supplier(source: AiSource, models: List<String> = source.getModelList(), modelError: String? = null): Map<String, Any?> = mapOf(
        "id" to source.id.toString(), "name" to source.name, "model" to source.model,
        "models" to (listOf(source.model) + models).filter { it.isNotBlank() }.distinct(),
        "modelError" to modelError
    )

    fun message(message: AgentMessage, streamingText: String?): Map<String, Any?> = mapOf(
        "isUser" to message.isUser,
        "text" to if (message.streaming) streamingText ?: message.text else message.text,
        "streaming" to message.streaming,
        "canLoadMore" to message.canLoadMore,
        "steps" to message.steps.map { mapOf("id" to it.id, "title" to it.title, "state" to it.state.name, "summary" to it.summary) },
        "books" to message.books.map { mapOf("name" to it.name, "author" to it.author, "originName" to it.originName) },
        "images" to message.generatedImages,
        "attachments" to message.attachments.orEmpty().map {
            mapOf("name" to it.name, "mimeType" to it.mimeType, "size" to it.size,
                "imagePath" to if (it.mimeType.startsWith("image/")) it.path else null)
        }
    )
}
