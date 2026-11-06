package io.legado.app.ui.book.agent

import io.legado.app.data.entities.AiConversation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Captured on the owning thread; mutable collections and tool JSON are detached before encoding. */
internal data class AgentConversationSnapshot private constructor(
    val metadata: AiConversation,
    val messages: List<AgentMessage>,
    val turns: List<AgentChatTurn>
) {
    fun encode() = metadata.copy(
        messagesJson = AgentConversationCodec.encodeMessages(messages),
        turnsJson = AgentConversationCodec.encodeTurns(turns)
    )

    companion object {
        fun capture(metadata: AiConversation, messages: List<AgentMessage>, turns: List<AgentChatTurn>) =
            AgentConversationSnapshot(metadata, messages.map { message ->
                message.copy(
                    books = message.books.map { book -> book.copy().apply {
                        infoHtml = book.infoHtml
                        tocHtml = book.tocHtml
                    } },
                    steps = message.steps.toList(),
                    repositorySources = message.repositorySources.map { it.copy(capabilities = it.capabilities.toList()) },
                    generatedImages = message.generatedImages.toList(),
                    attachments = message.attachments.toList()
                )
            }, turns.map { it.copy(
                toolCalls = it.toolCalls?.deepCopy(),
                attachments = it.attachments?.toList(),
                readingContext = it.readingContext?.let { context -> context.copy(chapters = context.chapters.toList()) }
            ) })
    }
}

internal data class RestoredAgentConversation(
    val metadata: AiConversation,
    val content: AgentConversationEditor.Conversation
)

internal class AgentConversationPersistence(
    private val scope: CoroutineScope,
    private val save: (AiConversation) -> Unit,
    private val delete: (String) -> Unit,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val encode: (AgentConversationSnapshot) -> AiConversation = { it.encode() }
) {
    val mutex = Mutex()
    private val sequence = AtomicLong()
    private val epoch = AtomicLong()
    private val latest = ConcurrentHashMap<String, Long>()
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    fun invalidate(id: String) {
        deleted.add(id)
        latest[id] = sequence.incrementAndGet()
    }
    fun invalidateAll() { deleted.addAll(latest.keys); epoch.incrementAndGet(); latest.clear() }

    private fun ticket(id: String) = Pair(sequence.incrementAndGet().also { latest[id] = it }, epoch.get())
    private suspend fun write(id: String, snapshot: AgentConversationSnapshot?, ticket: Pair<Long, Long>) {
        withContext(dispatcher) {
            mutex.withLock {
                if (latest[id] != ticket.first || epoch.get() != ticket.second || (snapshot != null && id in deleted)) return@withLock
                val encoded = snapshot?.let(encode)
                if (latest[id] != ticket.first || epoch.get() != ticket.second || (snapshot != null && id in deleted)) return@withLock
                if (encoded == null) delete(id) else save(encoded)
            }
        }
    }

    fun schedule(id: String, snapshot: AgentConversationSnapshot?) = ticket(id).let { ticket ->
        scope.launch(dispatcher) { write(id, snapshot, ticket) }
    }

    suspend fun saveNow(snapshot: AgentConversationSnapshot) {
        write(snapshot.metadata.id, snapshot, ticket(snapshot.metadata.id))
    }

    suspend fun load(interrupted: String, read: () -> AiConversation?): RestoredAgentConversation? =
        withContext(dispatcher) { read()?.let { decode(it, interrupted) } }

    fun decode(conversation: AiConversation, interrupted: String): RestoredAgentConversation {
        val messages = AgentConversationCodec.decodeMessages(conversation.messagesJson)
            .filterNot { it.placeholder }.map { message -> message.copy(
                canLoadMore = false, streaming = false,
                steps = message.steps.map { step ->
                    if (step.state == AgentStepState.RUNNING) step.copy(state = AgentStepState.FAILED, summary = interrupted) else step
                }
            ) }
        return RestoredAgentConversation(conversation, AgentConversationEditor.linkLegacy(
            messages, AgentConversationCodec.decodeTurns(conversation.turnsJson)
        ))
    }
}
