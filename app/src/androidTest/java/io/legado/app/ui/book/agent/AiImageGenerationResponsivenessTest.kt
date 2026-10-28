package io.legado.app.ui.book.agent

import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.data.entities.AiImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 本地服务器发送少量正文后暂停，复现生成图片时等待正文的现场，不调用真实供应商。 */
@RunWith(AndroidJUnit4::class)
class AiImageGenerationResponsivenessTest {

    @Test
    fun slowImageResponseKeepsMainThreadResponsiveAndCancellationClosesSocket() = runBlocking {
        val reading = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val mainResponded = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5_000
            val connectionClosed = executor.submit<Int> {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
                    val headers = StringBuilder()
                    while (!headers.endsWith("\r\n\r\n")) {
                        val byte = input.read()
                        check(byte != -1)
                        headers.append(byte.toChar())
                    }
                    val length = headers.lineSequence()
                        .first { it.startsWith("Content-Length:", true) }
                        .substringAfter(':').trim().toInt()
                    repeat(length) { check(input.read() != -1) }
                    socket.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: 1000\r\nConnection: close\r\n\r\n{").toByteArray())
                        flush()
                    }
                    // 客户端取消应主动断开，服务端无需等待 180 秒超时。
                    try {
                        input.read()
                    } catch (_: SocketException) {
                        -1
                    }
                }
            }
            val client = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    reading.countDown()
                }

                override fun canceled(call: Call) {
                    cancelled.countDown()
                }
            }).build()
            val job = launch(Dispatchers.Main) {
                AiImageGenerationHelper.generate(
                    AiImageSource(baseUrl = "http://127.0.0.1:${server.localPort}", model = "test-image"),
                    "苹果", "512x512", client
                )
            }
            try {
                assertTrue("开始读取慢响应正文", reading.await(5, TimeUnit.SECONDS))
                Handler(Looper.getMainLooper()).post { mainResponded.countDown() }
                assertTrue("等待图片时主线程仍能处理事件", mainResponded.await(2, TimeUnit.SECONDS))
                job.cancel()
                withTimeout(2_000) { job.join() }
                assertTrue("取消生成时取消底层连接", cancelled.await(2, TimeUnit.SECONDS))
                assertEquals(-1, connectionClosed.get(3, TimeUnit.SECONDS))
            } finally {
                job.cancel()
                withTimeout(5_000) { job.join() }
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
                executor.shutdownNow()
            }
        }
    }
}
