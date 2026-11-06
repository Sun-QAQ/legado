package io.legado.app.ui.book.agent

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.legado.app.data.entities.AiConversation
import io.legado.app.data.entities.SearchBook
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

class AgentConversationPersistenceTest {
    private fun metadata(id: String = "chat", title: String = "title") =
        AiConversation(id, title, "", "", 1, 0, "default", "prompt", 1, 2)

    private fun snapshot(id: String = "chat", text: String = "text") = AgentConversationSnapshot.capture(
        metadata(id), listOf(AgentMessage(text = text, id = "reply")), listOf(AgentChatTurn("assistant", text))
    )

    @Test fun `snapshot detaches mutable message collections books and tool JSON`() {
        val book = SearchBook(name = "original")
        val books = arrayListOf(book)
        val steps = arrayListOf(AgentStep("step", "original"))
        val call = JsonObject().apply { addProperty("name", "original") }
        val calls = JsonArray().apply { add(call) }
        val turns = arrayListOf(AgentChatTurn("assistant", "tool", toolCalls = calls))
        val messages = arrayListOf(AgentMessage(text = "message", books = books, steps = steps))
        val frozen = AgentConversationSnapshot.capture(metadata(), messages, turns)
        book.name = "changed"
        books.clear()
        steps.clear()
        call.addProperty("name", "changed")
        turns.clear()
        messages.clear()
        val encoded = frozen.encode()
        val restored = AgentConversationCodec.decodeMessages(encoded.messagesJson).single()
        assertEquals("original", restored.books.single().name)
        assertEquals("original", restored.steps.single().title)
        assertEquals("original", AgentConversationCodec.decodeTurns(encoded.turnsJson).single().toolCalls!!.asJsonArray[0].asJsonObject["name"].asString)
    }

    private class Queue : CoroutineDispatcher() {
        private val pending = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.add(block) }
        fun drain() { while (true) (pending.poll() ?: return).run() }
    }

    @Test fun `newer snapshot supersedes queued encoding without dropping other conversations`() {
        val queue = Queue()
        val saved = arrayListOf<AiConversation>()
        var encoded = 0
        val persistence = AgentConversationPersistence(CoroutineScope(SupervisorJob() + queue), saved::add, {}, queue) {
            encoded++
            it.encode()
        }
        persistence.schedule("chat", snapshot(text = "old"))
        persistence.schedule("other", snapshot("other"))
        persistence.schedule("chat", snapshot(text = "latest"))
        queue.drain()
        assertEquals(2, encoded)
        assertEquals(listOf("other", "chat"), saved.map { it.id })
        assertEquals("latest", AgentConversationCodec.decodeMessages(saved.last().messagesJson).single().text)
    }

    @Test fun `deletion and invalidation prevent queued saves from recreating conversations`() {
        val queue = Queue()
        val saved = arrayListOf<AiConversation>()
        val deleted = arrayListOf<String>()
        val persistence = AgentConversationPersistence(CoroutineScope(SupervisorJob() + queue), saved::add, deleted::add, queue)
        persistence.schedule("chat", snapshot())
        persistence.schedule("chat", null)
        queue.drain()
        assertTrue(saved.isEmpty())
        assertEquals(listOf("chat"), deleted)
        persistence.schedule("chat", snapshot())
        persistence.invalidate("chat")
        persistence.schedule("chat", snapshot())
        queue.drain()
        assertTrue(saved.isEmpty())
        persistence.schedule("chat", snapshot())
        persistence.schedule("other", snapshot("other"))
        persistence.invalidateAll()
        queue.drain()
        assertTrue(saved.isEmpty())
    }

    @Test fun `large snapshot encoding writing and decoding run on worker`() = runBlocking {
        val owner = Thread.currentThread()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { worker ->
            var saved: AiConversation? = null
            val persistence = AgentConversationPersistence(this, {
                assertNotSame(owner, Thread.currentThread())
                saved = it
            }, {}, worker) {
                assertNotSame(owner, Thread.currentThread())
                it.encode()
            }
            val content = "章节😀".repeat(100000)
            persistence.saveNow(snapshot(text = content))
            val loaded = persistence.load("interrupted") {
                assertNotSame(owner, Thread.currentThread())
                saved
            }!!
            assertSame(owner, Thread.currentThread())
            assertEquals(content, loaded.content.messages.single().text)
            assertEquals(content, loaded.content.turns.single().content)
            assertEquals("title", loaded.metadata.title)
        }
    }

    @Test fun `restore repairs interrupted steps and links legacy messages`() = runBlocking {
        val persistence = AgentConversationPersistence(this, {}, {})
        val original = metadata().copy(
            messagesJson = AgentConversationCodec.encodeMessages(listOf(
                AgentMessage(true, "question"),
                AgentMessage(text = "answer", streaming = true, canLoadMore = true, steps = listOf(AgentStep("step", "tool")))
            )),
            turnsJson = AgentConversationCodec.encodeTurns(listOf(AgentChatTurn("user", "question"), AgentChatTurn("assistant", "answer")))
        )
        val restored = persistence.load("interrupted") { original }!!.content
        assertTrue(restored.messages.all { !it.id.isNullOrBlank() && !it.streaming && !it.canLoadMore })
        assertEquals(AgentStepState.FAILED, restored.messages.last().steps.single().state)
        assertEquals("interrupted", restored.messages.last().steps.single().summary)
        assertEquals(restored.messages.map { it.id }, restored.turns.map { it.messageId })
    }
}
