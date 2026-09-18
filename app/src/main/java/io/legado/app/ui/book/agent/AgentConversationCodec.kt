package io.legado.app.ui.book.agent

import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import io.legado.app.utils.GSON

data class AgentChatTurn(
    val role: String,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: JsonElement? = null
)

internal object AgentConversationCodec {

    private val messageListType = object : TypeToken<List<AgentMessage>>() {}.type
    private val turnListType = object : TypeToken<List<AgentChatTurn>>() {}.type

    fun encodeMessages(messages: List<AgentMessage>): String = GSON.toJson(messages)

    fun decodeMessages(json: String): List<AgentMessage> = runCatching {
        GSON.fromJson<List<AgentMessage>>(json, messageListType).orEmpty()
    }.getOrDefault(emptyList())

    fun encodeTurns(turns: List<AgentChatTurn>): String = GSON.toJson(turns)

    fun decodeTurns(json: String): List<AgentChatTurn> = runCatching {
        GSON.fromJson<List<AgentChatTurn>>(json, turnListType).orEmpty()
    }.getOrDefault(emptyList())

    fun titleFrom(text: String, fallback: String): String {
        val normalized = text.trim().replace(Regex("\\s+"), " ")
        return normalized.ifBlank { fallback }.take(MAX_TITLE_LENGTH)
    }

    private const val MAX_TITLE_LENGTH = 40
}
