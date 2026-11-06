package io.legado.app.web

import fi.iki.elonen.NanoHTTPD
import io.legado.app.api.ReturnData
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

internal object WebAuth {
    private val policy = WebAccessPolicy()
    val enabled get() = appCtx.getPrefBoolean(PreferKey.webAuthEnabled, false)

    @Synchronized
    fun accessKey(): String = appCtx.getPrefString(PreferKey.webAuthKey)?.takeIf { it.isNotBlank() }
        ?: WebAccessPolicy.newKey().also { appCtx.putPrefString(PreferKey.webAuthKey, it) }

    @Synchronized
    fun reset() {
        appCtx.putPrefString(PreferKey.webAuthKey, WebAccessPolicy.newKey())
        currentPolicy()
    }

    @Synchronized
    private fun currentPolicy(): WebAccessPolicy = policy.also {
        it.configure(enabled, if (enabled) accessKey() else "")
    }

    @Synchronized
    fun pair(key: String?, origin: String?) = currentPolicy().pair(key, origin)
    @Synchronized
    fun allowsOrigin(origin: String?) = currentPolicy().allowsOrigin(origin)
    @Synchronized
    fun authorize(session: NanoHTTPD.IHTTPSession, socket: Boolean = false): Boolean = currentPolicy().authorize(
        session.headers["authorization"], session.parameters["access_token"]?.firstOrNull(),
        session.headers["origin"], socket || session.uri in setOf("/cover", "/image", "/saveBookProgress")
    )

    // A generic authentication error is public so cross-origin clients can show pairing again.
    fun unauthorized(origin: String? = null): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(
        NanoHTTPD.Response.Status.UNAUTHORIZED, "application/json; charset=UTF-8",
        GSON.toJson(ReturnData().setErrorMsg("网页服务需要配对，请输入 App 中的访问密钥"))
    ).apply {
        addHeader("Cache-Control", "no-store")
        origin?.let { addHeader("Access-Control-Allow-Origin", it) }
        addHeader("Vary", "Origin")
    }
}
