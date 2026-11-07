package io.legado.app.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import splitties.init.appCtx
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class WebAuthenticationFeatureTest {
    private fun request(
        port: Int,
        path: String,
        headers: String = "",
        body: String? = null,
        method: String = if (body == null) "GET" else "POST"
    ): Pair<Int, List<String>> =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val payload = body.orEmpty().toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: localhost:$port\r\n$headers" +
                    if (body != null) "Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n" else "\r\n").toByteArray())
                if (body != null) write(payload)
                flush()
            }
            val reader = socket.getInputStream().bufferedReader()
            val status = reader.readLine().split(' ')[1].toInt()
            val responseHeaders = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                responseHeaders.add(line)
            }
            status to responseHeaders
        }

    @Test fun legacyAuthenticationSettingsDoNotBlockHttpOrSocketAccess() {
        val previousEnabled = appCtx.getPrefBoolean("webAuthEnabled", false)
        val previousKey = appCtx.getPrefString("webAuthKey")
        val http = HttpServer(0) {}
        val socket = WebSocketServer(0) {}
        val key = "feature-test-key"
        val origin = "http://reader.test"
        val browser = "Origin: $origin\r\n"
        val upgrade = browser + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        try {
            appCtx.putPrefBoolean("webAuthEnabled", true)
            appCtx.putPrefString("webAuthKey", key)
            http.start()
            socket.start()
            listOf("/index.html", "/getReadConfig", "/cover", "/image").forEach {
                assertEquals(200, request(http.listeningPort, it).first)
            }
            listOf("/addLocalBook", "/saveBookProgress", "/aiChat").forEach {
                assertEquals(200, request(http.listeningPort, it, browser, body = "{}").first)
            }
            assertEquals(101, request(socket.listeningPort, "/searchBook", upgrade).first)
            val response = request(http.listeningPort, "/getReadConfig", browser)
            assertEquals(200, response.first)
            assertTrue(response.second.any { it.equals("Access-Control-Allow-Origin: $origin", true) })
            val preflight = request(http.listeningPort, "/saveBookProgress", browser, method = "OPTIONS")
            assertEquals(200, preflight.first)
            assertTrue(preflight.second.any { it.equals("Access-Control-Allow-Origin: $origin", true) })
        } finally {
            http.stop()
            http.closeChat()
            socket.stop()
            appCtx.putPrefBoolean("webAuthEnabled", previousEnabled)
            if (previousKey == null) appCtx.removePref("webAuthKey")
            else appCtx.putPrefString("webAuthKey", previousKey)
        }
    }
}
