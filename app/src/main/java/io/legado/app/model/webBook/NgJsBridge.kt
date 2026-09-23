package io.legado.app.model.webBook

import androidx.annotation.Keep
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensions
import io.legado.app.ui.browser.NgSourceBrowserActivity
import io.legado.app.help.http.StrResponse
import io.legado.app.model.analyzeRule.AnalyzeUrl

@Keep
class NgJsBridge(
    private val source: BookSource,
    private val bypassRateLimit: Boolean = false
) : JsExtensions {
    override fun getSource() = source

    override fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) url.firstOrNull().toString() else url.toString()
        val requestSource = if (bypassRateLimit) source.copy(concurrentRate = null) else source
        return kotlin.runCatching {
            AnalyzeUrl(urlStr, source = requestSource).getStrResponse().body
        }.getOrElse { it.stackTraceToString() }
    }

    fun showBrowser(url: String, html: String, javaScript: String, options: String) {
        NgSourceBrowserActivity.open(source, url, html, javaScript, options)
    }
}
