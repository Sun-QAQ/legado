package io.legado.app.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * AI 对话快照。消息和模型上下文分别序列化，便于完整恢复会话。
 */
@Entity(tableName = "aiConversations")
data class AiConversation(
    @PrimaryKey
    val id: String,
    val title: String,
    val messagesJson: String,
    val turnsJson: String,
    val supplierId: Long,
    val personaId: Long,
    val personaName: String,
    val personaPrompt: String,
    val createdAt: Long,
    val updatedAt: Long
)
