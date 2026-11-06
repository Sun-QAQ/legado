package io.legado.app.api.controller

import org.junit.Assert.*
import org.junit.Test

class AiChatStateTrackerTest {
    private fun message(id: String, text: String = id) = mapOf<String, Any?>("id" to id, "text" to text)
    private fun state(messages: List<Map<String, Any?>>, conversation: String = "chat", waiting: Boolean = true) =
        mapOf<String, Any?>("conversationId" to conversation, "waiting" to waiting, "messages" to messages)
    @Suppress("UNCHECKED_CAST")
    private fun messages(snapshot: Map<String, Any?>) = snapshot["messages"] as List<Map<String, Any?>>

    @Test fun `legacy requests always receive full messages and unchanged revision is stable`() {
        val tracker = AiChatStateTracker()
        val full = tracker.snapshot(state(listOf(message("one"))))
        val again = tracker.snapshot(state(listOf(message("one"))))
        assertEquals(true, full["reset"])
        assertEquals(full, again)
        val delta = tracker.snapshot(state(listOf(message("one"))), full["revision"] as String)
        assertEquals(false, delta["reset"])
        assertTrue(messages(delta).isEmpty())
    }

    @Test fun `long history sends only changed reply and newly appended messages`() {
        val tracker = AiChatStateTracker()
        val history = (0 until 200).map { message("history-$it", "large history text $it") }
        val full = tracker.snapshot(state(history + message("reply", "first")))
        val changed = tracker.snapshot(state(history + message("reply", "second") + message("new")), full["revision"] as String)
        assertEquals(false, changed["reset"])
        assertEquals(listOf("reply", "new"), messages(changed).map { it["id"] })
        assertEquals(full["revision"], changed["baseRevision"])
    }

    @Test fun `older clients catch up without retaining old full snapshots`() {
        val tracker = AiChatStateTracker()
        val first = tracker.snapshot(state(listOf(message("one"), message("two"))))
        tracker.snapshot(state(listOf(message("one", "edited"), message("two"))))
        val delta = tracker.snapshot(state(listOf(message("one", "edited"), message("two", "final")), waiting = false), first["revision"] as String)
        assertEquals(listOf("edited", "final"), messages(delta).map { it["text"] })
        assertEquals(false, delta["waiting"])
    }

    @Test fun `deletion reordering and conversation switching reset the client`() {
        for (next in listOf(state(listOf(message("two"))), state(listOf(message("two"), message("one"))), state(emptyList(), "new"))) {
            val tracker = AiChatStateTracker()
            val first = tracker.snapshot(state(listOf(message("one"), message("two"))))
            val reset = tracker.snapshot(next, first["revision"] as String)
            assertEquals(true, reset["reset"])
            assertEquals(next["messages"], reset["messages"])
        }
    }

    @Test fun `server restart malformed cursor and future revision return full state`() {
        val state = state(listOf(message("one")))
        val tracker = AiChatStateTracker()
        val first = tracker.snapshot(state)
        for (cursor in listOf("bad", (first["revision"] as String).substringBefore(':') + ":99999", AiChatStateTracker().snapshot(state)["revision"] as String)) {
            assertEquals(true, tracker.snapshot(state, cursor)["reset"])
        }
    }
}
