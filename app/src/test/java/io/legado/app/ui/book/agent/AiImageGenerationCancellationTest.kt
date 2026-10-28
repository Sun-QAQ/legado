package io.legado.app.ui.book.agent

import io.legado.app.data.entities.AiImageSource
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AiImageGenerationCancellationTest {

    @Test
    fun `图片接口正文未返回时调用线程仍可运行且取消关闭请求`() {
        assertResponsiveAndCancellable(openAiSource, "/images/generations")
    }

    @Test
    fun `下载图片正文时调用线程仍可运行且取消关闭请求`() {
        assertResponsiveAndCancellable(openAiSource, "/image.png")
    }

    @Test
    fun `ComfyUI提交正文未返回时调用线程仍可运行且取消关闭请求`() {
        assertResponsiveAndCancellable(comfySource, "/prompt")
    }

    @Test
    fun `ComfyUI历史正文未返回时调用线程仍可运行且取消关闭请求`() {
        assertResponsiveAndCancellable(comfySource, "/history/job")
    }

    private fun assertResponsiveAndCancellable(source: AiImageSource, stalledPath: String) = runBlocking {
        val reading = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun canceled(call: Call) {
                    cancelled.set(true)
                    release.countDown()
                }
            })
            .addInterceptor { chain ->
                val request = chain.request()
                val text = when (request.url.encodedPath) {
                    "/images/generations" -> """{"data":[{"url":"http://image.test/image.png"}]}"""
                    "/prompt" -> """{"prompt_id":"job"}"""
                    else -> "image bytes"
                }
                val body = text.toResponseBody("application/json; charset=utf-8".toMediaType())
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(if (request.url.encodedPath == stalledPath) stalledBody(body, reading, release, closed) else body)
                    .build()
            }.build()
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
            val job = launch(caller) {
                AiImageGenerationHelper.generate(source, "苹果", "512x512", client)
            }
            try {
                assertTrue("应开始读取响应正文", reading.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { async(caller) { true }.await() }
                job.cancel()
                withTimeout(2_000) { job.join() }
                assertTrue("收到响应头后取消也应取消底层请求", cancelled.get())
                assertTrue("取消后释放响应正文", closed.await(2, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                job.cancel()
                withTimeout(5_000) { job.join() }
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    private fun stalledBody(
        body: ResponseBody,
        reading: CountDownLatch,
        release: CountDownLatch,
        closed: CountDownLatch
    ): ResponseBody = object : ResponseBody() {
        private val input = object : ForwardingSource(body.source()) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                reading.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "测试未释放正文" }
                return super.read(sink, byteCount)
            }

            override fun close() {
                try {
                    super.close()
                } finally {
                    closed.countDown()
                }
            }
        }.buffer()

        override fun contentType() = body.contentType()
        override fun contentLength() = body.contentLength()
        override fun source(): BufferedSource = input
    }

    private val openAiSource = AiImageSource(baseUrl = "http://image.test", model = "image-model")
    private val comfySource = openAiSource.copy(
        apiType = AiImageSource.TYPE_COMFYUI,
        customBody = """{"6":{"class_type":"CLIPTextEncode","inputs":{"text":"{{prompt}}"}}}"""
    )
}
