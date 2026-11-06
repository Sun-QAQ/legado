package io.legado.app.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import splitties.init.appCtx
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class WebAuthenticationFeatureTest {
    private fun request(port: Int, path: String, headers: String = "", body: String? = null): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5000
            val payload = body.orEmpty().toByteArray(Charsets.UTF_8)
            val method = if (body == null) "GET" else "POST"
            socket.getOutputStream().apply {
                write(("$method $path HTTP/1.1\r\nHost: localhost:$port\r\n$headers" +
                    if (body != null) "Content-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n" else "\r\n").toByteArray())
                if (body != null) write(payload)
                flush()
            }
            socket.getInputStream().bufferedReader().readLine().split(' ')[1].toInt()
        }

    @Test fun authenticationProtectsHttpResourcesAndSocketHandshake() {
        val previousEnabled = appCtx.getPrefBoolean(PreferKey.webAuthEnabled, false)
        val previousKey = appCtx.getPrefString(PreferKey.webAuthKey)
        val http = HttpServer(0) {}
        val socket = WebSocketServer(0) {}
        val key = "feature-test-key"
        val origin = "http://reader.test"
        val browser = "Origin: $origin\r\n"
        val upgrade = browser + "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
        try {
            appCtx.putPrefBoolean(PreferKey.webAuthEnabled, false)
            http.start()
            socket.start()
            assertEquals(200, request(http.listeningPort, "/getReadConfig"))
            assertEquals(101, request(socket.listeningPort, "/searchBook", upgrade))
            appCtx.putPrefString(PreferKey.webAuthKey, key)
            appCtx.putPrefBoolean(PreferKey.webAuthEnabled, true)
            assertEquals(200, request(http.listeningPort, "/index.html"))
            assertEquals(200, request(http.listeningPort, "/webAuth"))
            listOf("/getReadConfig", "/cover", "/image").forEach {
                assertEquals(401, request(http.listeningPort, it))
            }
            listOf("/addLocalBook", "/saveBookProgress", "/aiChat").forEach {
                assertEquals(401, request(http.listeningPort, it, body = "{}"))
            }
            assertEquals(401, request(socket.listeningPort, "/searchBook", upgrade))
            assertEquals(401, request(http.listeningPort, "/webAuth/pair", browser, "{\"key\":\"wrong\"}"))
            assertEquals(200, request(http.listeningPort, "/webAuth/pair", browser, "{\"key\":\"$key\"}"))
            assertEquals(200, request(http.listeningPort, "/getReadConfig", browser + "Authorization: Bearer $key\r\n"))
            assertEquals(101, request(socket.listeningPort, "/searchBook?access_token=$key", upgrade))
            WebAuth.reset()
            assertEquals(401, request(http.listeningPort, "/getReadConfig", "Authorization: Bearer $key\r\n"))
            assertEquals(401, request(socket.listeningPort, "/searchBook?access_token=$key", upgrade))
        } finally {
            http.stop()
            socket.stop()
            appCtx.putPrefBoolean(PreferKey.webAuthEnabled, previousEnabled)
            if (previousKey == null) appCtx.removePref(PreferKey.webAuthKey)
            else appCtx.putPrefString(PreferKey.webAuthKey, previousKey)
        }
    }
}
