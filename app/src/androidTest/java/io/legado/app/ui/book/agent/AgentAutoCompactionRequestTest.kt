package io.legado.app.ui.book.agent

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.AiModelThinking
import io.legado.app.data.entities.AiSource
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.putPrefLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** 本机 HTTP 服务模拟超限和 SSE 响应，验证完整请求路径；不调用真实 AI、不保存测试会话。 */
@RunWith(AndroidJUnit4::class)
class AgentAutoCompactionRequestTest {
    @Test
    fun overflowCompactsHistoryAndRetriesWithPersonaAndCurrentToolPairIntact() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previousPersona = context.getPrefLong(PreferKey.aiPersonaId)
        val previousPreset = context.getPrefLong(PreferKey.aiPresetId)
        val agent = AgentViewModel(context.applicationContext as Application, restoreLatestConversation = false)
        @Suppress("UNCHECKED_CAST")
        val history = AgentViewModel::class.java.getDeclaredField("history").apply { isAccessible = true }
            .get(agent) as MutableList<AgentChatTurn>
        fun call(id: String) = AgentChatTurn("assistant", "", toolCalls = JsonParser.parseString(
            """[{"id":"$id","type":"function","function":{"name":"read_book_content","arguments":"{\"query\":\"测试书\"}"}}]"""
        ))
        val oldContent = "前三章的历史剧情和人物关系。".repeat(1800)
        history.addAll(listOf(
            AgentChatTurn("user", "此前的阅读要求"), call("old"), AgentChatTurn("tool", oldContent, "old"),
            AgentChatTurn("assistant", "此前的评价"), AgentChatTurn("user", "继续以小岚的口吻聊聊"),
            call("current"), AgentChatTurn("tool", "本轮新读取的正文", "current")
        ))
        val requests = Collections.synchronizedList(mutableListOf<JsonObject>())
        val serverFailure = AtomicReference<Throwable?>()
        val server = ServerSocket(0)
        val worker = thread(isDaemon = true, name = "agent-context-test") {
            try {
                var completions = 0
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 15000
                        val input = BufferedInputStream(socket.getInputStream())
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val next = input.read()
                            check(next >= 0) { "Unexpected EOF" }
                            header.append(next.toChar())
                        }
                        val length = Regex("(?im)^Content-Length: *([0-9]+)").find(header)!!.groupValues[1].toInt()
                        val bytes = ByteArray(length)
                        var offset = 0
                        while (offset < length) {
                            val count = input.read(bytes, offset, length - offset)
                            check(count > 0) { "Unexpected EOF" }
                            offset += count
                        }
                        val body = JsonParser.parseString(String(bytes, Charsets.UTF_8)).asJsonObject
                        requests.add(body)
                        val isSummary = body.getAsJsonArray("messages")[0].asJsonObject.get("content").asString.startsWith("将历史资料整理")
                        val overflow = !isSummary && completions++ == 0
                        val response = if (overflow) {
                            """{"error":{"code":"context_length_exceeded","message":"maximum context length exceeded; private provider detail"}}"""
                        } else {
                            val content = if (isSummary) "用户在阅读测试书前三章，已获得人物和情节资料，接下来继续评价。" else "旅伴，我们接着聊。"
                            val chunk = JsonObject().apply {
                                add("choices", JsonParser.parseString("""[{"delta":{}}]"""))
                                getAsJsonArray("choices")[0].asJsonObject.getAsJsonObject("delta").addProperty("content", content)
                            }
                            "data: $chunk\n\ndata: [DONE]\n\n"
                        }
                        val payload = response.toByteArray(Charsets.UTF_8)
                        val status = if (overflow) "400 Bad Request" else "200 OK"
                        val type = if (overflow) "application/json" else "text/event-stream"
                        socket.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(payload)
                            flush()
                        }
                    }
                }
            } catch (e: Exception) {
                if (!server.isClosed) serverFailure.set(e)
            }
        }
        try {
            context.putPrefLong(PreferKey.aiPresetId, 0L)
            val persona = "你是冒险家小岚，称用户为旅伴，保持热情的语气。"
            agent.selectPersona(998877L, "合成测试人格", persona)
            val source = AiSource(model = "synthetic-context-test", baseUrl = "http://127.0.0.1:${server.localPort}",
                modelThinking = GSON.toJson(mapOf("synthetic-context-test" to AiModelThinking(contextWindowTokens = 131072))))
            val response = agent.requestChatWithCompaction(source)
            assertEquals("旅伴，我们接着聊。", response.get("content").asString)
            assertNull(serverFailure.get())
            val chats = requests.filterNot {
                it.getAsJsonArray("messages")[0].asJsonObject.get("content").asString.startsWith("将历史资料整理")
            }
            assertEquals(2, chats.size)
            assertTrue(requests.size > chats.size)
            assertTrue(AgentContextBudget.tokens(chats[1]) < AgentContextBudget.tokens(chats[0]))
            val messages = chats[1].getAsJsonArray("messages")
            assertEquals(chats[0].getAsJsonArray("messages")[0], messages[0])
            assertTrue(messages[0].asJsonObject.get("content").asString.contains(persona))
            assertTrue(messages[1].asJsonObject.get("content").asString.contains("历史对话摘要"))
            assertEquals("current", messages[messages.size() - 2].asJsonObject.getAsJsonArray("tool_calls")[0].asJsonObject.get("id").asString)
            assertEquals("current", messages.last().asJsonObject.get("tool_call_id").asString)
            assertEquals("本轮新读取的正文", messages.last().asJsonObject.get("content").asString)
            assertEquals(oldContent, history[2].content)
            assertEquals(4, AgentAutoCompaction.checkpointEnd(history))
        } finally {
            server.close()
            worker.join(2000)
            context.putPrefLong(PreferKey.aiPersonaId, previousPersona)
            context.putPrefLong(PreferKey.aiPresetId, previousPreset)
        }
    }
}
