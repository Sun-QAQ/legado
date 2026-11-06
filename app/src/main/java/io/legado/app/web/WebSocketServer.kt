package io.legado.app.web

import fi.iki.elonen.NanoWSD
import io.legado.app.service.WebService
import io.legado.app.web.socket.*

class WebSocketServer(port: Int, private val onRequest: () -> Unit = { WebService.serve() }) : NanoWSD("::", port) {

    override fun serve(session: IHTTPSession): Response {
        if (!WebAuth.authorize(session, socket = true)) return WebAuth.unauthorized()
        return super.serve(session)
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket? {
        onRequest()
        return when (handshake.uri) {
            "/bookSourceDebug" -> {
                BookSourceDebugWebSocket(handshake)
            }
            "/rssSourceDebug" -> {
                RssSourceDebugWebSocket(handshake)
            }
            "/searchBook" -> {
                BookSearchWebSocket(handshake)
            }
            else -> null
        }
    }
}
