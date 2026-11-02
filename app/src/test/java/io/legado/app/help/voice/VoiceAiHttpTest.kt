package io.legado.app.help.voice

import com.google.gson.JsonObject
import io.legado.app.data.entities.AiSource
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class VoiceAiHttpTest {
    private class Server(val status: Int = 200, val response: String, val stallBody: Boolean = false) : Closeable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private var socket: Socket? = null
        val headers = CompletableDeferred<String>()
        val requestBody = CompletableDeferred<String>()
        val bodyStarted = CompletableDeferred<Unit>()
        val url = "http://127.0.0.1:${server.localPort}/v1"
        init {
            Thread({
                try {
                    val client = server.accept(); socket = client
                    client.use {
                        val input = it.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) header.append(input.read().toChar())
                        headers.complete(header.toString())
                        val size = Regex("(?i)content-length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        requestBody.complete(String(input.readNBytes(size), Charsets.UTF_8))
                        val bytes = response.toByteArray(Charsets.UTF_8)
                        val out = it.getOutputStream()
                        out.write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush(); bodyStarted.complete(Unit)
                        if (stallBody) { while (input.read() >= 0) { } } else { out.write(bytes); out.flush() }
                    }
                } catch (_: Exception) { }
            }, "voice-ai-http-test").apply { isDaemon = true; start() }
        }
        override fun close() { socket?.close(); server.close() }
    }

    private fun envelope(content: String): String = JsonObject().apply {
        add("error", com.google.gson.JsonNull.INSTANCE)
        add("choices", com.google.gson.JsonArray().apply { add(JsonObject().apply {
            add("message", JsonObject().apply { addProperty("content", content) })
        }) })
    }.toString()

    @Test fun sendsSelectedModelAndAuthorizationWithoutLeakingContextUrls() = runBlocking {
        Server(response = envelope("""{"actions":[{"action":"bookshelf"}]}""")).use { server ->
            val source = AiSource(baseUrl = server.url, apiKey = "test-secret", model = "chosen-model")
            val context = VoiceAiContext(listOf(VoiceAiBook("b0", "示例", "作者", 1, "private://not-sent")), emptyList(), "bookshelf")
            val result = VoiceAiInterpreter.request(source, VoiceAiInterpreter.requestBody(source, "回到书架", context), OkHttpClient())
            assertTrue(result.contains("bookshelf"))
            assertTrue(server.headers.await().contains("Authorization: Bearer test-secret"))
            val body = server.requestBody.await()
            assertTrue(body.contains("chosen-model")); assertFalse(body.contains("private://"))
            assertFalse(body.contains("test-secret"))
        }
    }

    @Test fun authenticationErrorsDoNotExposeRemoteErrorBody() = runBlocking {
        Server(401, "secret-from-provider").use { server ->
            val error = runCatching { VoiceAiInterpreter.request(AiSource(baseUrl = server.url), JsonObject(), OkHttpClient()) }.exceptionOrNull()
            assertTrue(error is VoiceAiException)
            assertTrue(error!!.message!!.contains("认证失败"))
            assertFalse(error.message!!.contains("secret-from-provider"))
        }
    }

    @Test fun rejectsMalformedAndOversizedResponses() = runBlocking {
        for (body in listOf("not json", "x".repeat(65_537), """{"choices":[]}""")) {
            Server(response = body).use { server ->
                assertTrue(runCatching { VoiceAiInterpreter.request(AiSource(baseUrl = server.url), JsonObject(), OkHttpClient()) }
                    .exceptionOrNull() is VoiceAiException)
            }
        }
    }

    @Test fun cancellationAfterHeadersCancelsSocketWhileBodyIsPending() = runBlocking {
        Server(response = envelope("result"), stallBody = true).use { server ->
            val cancelled = AtomicBoolean()
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun canceled(call: Call) { cancelled.set(true) }
            }).build()
            val job = launch { VoiceAiInterpreter.request(AiSource(baseUrl = server.url), JsonObject(), client) }
            withTimeout(3000) { server.bodyStarted.await() }
            withTimeout(1500) { job.cancelAndJoin() }
            assertTrue(cancelled.get())
        }
    }
}
