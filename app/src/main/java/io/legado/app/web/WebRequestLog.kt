package io.legado.app.web

import java.net.URLDecoder

internal object WebRequestLog {
    // Older clients may still send their saved access key in the query string.
    fun redactQuery(query: String?) = query?.split('&')?.joinToString("&") { parameter ->
        val name = parameter.substringBefore('=')
        if (runCatching { URLDecoder.decode(name, "UTF-8") }.getOrNull()
                .equals("access_token", ignoreCase = true)) "$name=<redacted>" else parameter
    }
}
