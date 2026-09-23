package io.legado.app.model.webBook

import androidx.annotation.Keep
import io.legado.app.data.entities.BookSource
import io.legado.app.help.JsExtensions
import io.legado.app.ui.browser.NgSourceBrowserActivity

@Keep
class NgJsBridge(private val source: BookSource) : JsExtensions {
    override fun getSource() = source

    fun showBrowser(url: String, html: String, javaScript: String, options: String) {
        NgSourceBrowserActivity.open(source, url, html, javaScript, options)
    }
}
