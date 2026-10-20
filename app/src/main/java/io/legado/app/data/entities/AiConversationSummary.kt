package io.legado.app.data.entities

/** 不含消息、模型上下文和人格提示词的轻量会话信息。 */
data class AiConversationSummary(
    val id: String,
    val title: String,
    val supplierId: Long,
    val personaId: Long,
    val personaName: String,
    val createdAt: Long,
    val updatedAt: Long
)
