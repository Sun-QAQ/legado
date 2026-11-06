package io.legado.app.api.controller

import java.util.UUID

/** One current snapshot plus per-message revisions; no copies of the full conversation history. */
internal class AiChatStateTracker {
    private val epoch = UUID.randomUUID().toString()
    private var revision = 0L
    private var resetRevision = 0L
    private var previous: Map<String, Any?>? = null
    private var previousMessages = emptyMap<String, Map<String, Any?>>()
    private var order = emptyList<String>()
    private val changedAt = hashMapOf<String, Long>()

    fun snapshot(state: Map<String, Any?>, cursor: String? = null): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val messages = state["messages"] as List<Map<String, Any?>>
        val ids = messages.map { it["id"] as String }
        require(ids.size == ids.toSet().size) { "Duplicate chat message ID" }
        val metadata = state - "messages"
        val structuralChange = previous != null && (
            previous!!["conversationId"] != metadata["conversationId"] || ids.take(order.size) != order
        )
        val changed = messages.filter { previousMessages[it["id"]] != it }
        if (previous == null || structuralChange || previous != metadata || changed.isNotEmpty()) {
            revision++
            if (structuralChange) {
                resetRevision = revision
                changedAt.clear()
            }
            changed.forEach { changedAt[it["id"] as String] = revision }
        }
        previous = metadata
        previousMessages = messages.associateBy { it["id"] as String }
        order = ids
        val since = cursor?.takeIf { it.startsWith("$epoch:") }?.substringAfterLast(':')?.toLongOrNull()
        val delta = since != null && since in resetRevision..revision
        return metadata + mapOf(
            "revision" to "$epoch:$revision",
            "baseRevision" to if (delta) cursor else null,
            "reset" to !delta,
            "messages" to if (delta) messages.filter { (changedAt[it["id"]] ?: 0L) > since!! } else messages
        )
    }
}
