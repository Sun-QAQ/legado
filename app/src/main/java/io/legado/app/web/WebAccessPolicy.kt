package io.legado.app.web

import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

internal class WebAccessPolicy {
    private var enabled = false
    private var key = ""
    private val origins = hashSetOf<String>()

    @Synchronized
    fun configure(enabled: Boolean, key: String) {
        if (this.enabled != enabled || this.key != key) origins.clear()
        this.enabled = enabled
        this.key = key
    }

    private fun matches(candidate: String?) = key.isNotEmpty() && candidate != null &&
        MessageDigest.isEqual(key.toByteArray(Charsets.UTF_8), candidate.toByteArray(Charsets.UTF_8))

    private fun origin(value: String?): String? = runCatching {
        val uri = URI(value ?: return null)
        if (uri.scheme !in listOf("http", "https") || uri.host == null || uri.rawUserInfo != null ||
            !uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
        uri.toASCIIString()
    }.getOrNull()

    @Synchronized
    fun pair(candidate: String?, browserOrigin: String?): Boolean {
        if (!enabled || !matches(candidate)) return false
        if (browserOrigin != null) origins.add(origin(browserOrigin) ?: return false)
        return true
    }

    @Synchronized
    fun allowsOrigin(browserOrigin: String?) = browserOrigin != null &&
        (!enabled || origin(browserOrigin)?.let(origins::contains) == true)

    @Synchronized
    fun authorize(header: String?, query: String?, browserOrigin: String?, allowQuery: Boolean): Boolean {
        if (!enabled) return true
        val candidate = if (header?.startsWith("Bearer ", ignoreCase = true) == true) header.substring(7)
            else if (allowQuery) query else null
        return matches(candidate) && (browserOrigin == null || allowsOrigin(browserOrigin))
    }

    companion object {
        fun newKey(): String = ByteArray(32).also(SecureRandom()::nextBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        fun isPublicAsset(path: String) = path.endsWith("/") ||
            path.substringAfterLast('.').lowercase() in setOf(
                "html", "htm", "js", "css", "svg", "png", "jpg", "jpeg", "gif", "webp",
                "ico", "woff", "woff2", "ttf", "otf", "md", "txt", "json", "map"
            )

        fun redactQuery(query: String?) = query?.split('&')?.joinToString("&") { parameter ->
            val name = parameter.substringBefore('=')
            if (runCatching { URLDecoder.decode(name, "UTF-8") }.getOrNull()
                    .equals("access_token", ignoreCase = true)) "$name=<redacted>" else parameter
        }
    }
}
