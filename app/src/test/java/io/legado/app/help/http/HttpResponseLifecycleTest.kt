package io.legado.app.help.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

class HttpResponseLifecycleTest {
    private class Body : ResponseBody() {
        val closed = AtomicBoolean()
        private val input = object : ForwardingSource(Buffer().writeUtf8("response")) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()
        override fun contentType() = null
        override fun contentLength() = 8L
        override fun source() = input
    }

    private fun response(request: Request, code: Int, body: Body) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body).build()

    private fun assertRetryLifecycle(codes: List<Int>, retry: Int) = runBlocking {
        val bodies = codes.map { Body() }
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val index = calls.getAndIncrement()
            if (index > 0) assertTrue("retry must close previous response", bodies[index - 1].closed.get())
            response(chain.request(), codes[index], bodies[index])
        }.build()
        try {
            val result = client.newCallResponse(retry) { url("http://response.test/") }
            assertEquals(codes.size, calls.get())
            assertEquals(codes.last(), result.code)
            assertFalse("caller owns final response", bodies.last().closed.get())
            result.close()
            assertTrue(bodies.all { it.closed.get() })
        } finally {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun `retry closes failure before success`() = assertRetryLifecycle(listOf(503, 200), 2)
    @Test fun `exhausted retries return final failure to caller`() = assertRetryLifecycle(listOf(503, 502, 500), 2)
    @Test fun `zero retries preserve caller ownership`() = assertRetryLifecycle(listOf(404), 0)

    private class QueueDispatcher : CoroutineDispatcher() {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }

    private class ControlledCall : Call by OkHttpClient().newCall(
        Request.Builder().url("http://response.test/").build()
    ) {
        lateinit var callback: Callback
        var cancelled = false
        override fun request() = Request.Builder().url("http://response.test/").build()
        override fun execute(): Response = error("enqueue only")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = ::callback.isInitialized
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = ControlledCall()
    }

    private fun assertCancellationLifecycle(cancelBeforeResponse: Boolean) = runBlocking {
        val dispatcher = QueueDispatcher()
        val call = ControlledCall()
        val body = Body()
        var delivered = false
        val job = launch(dispatcher) {
            call.await().use { delivered = true }
        }
        dispatcher.drain()
        if (cancelBeforeResponse) job.cancel()
        call.callback.onResponse(call, response(call.request(), 200, body))
        if (!cancelBeforeResponse) job.cancel()
        dispatcher.drain()
        job.join()
        assertTrue(call.cancelled)
        assertTrue(body.closed.get())
        assertFalse(delivered)
    }

    @Test fun `late response is closed after cancellation`() = assertCancellationLifecycle(true)
    @Test fun `response is closed when cancelled before dispatch`() = assertCancellationLifecycle(false)
}
